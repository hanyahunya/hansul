package com.hansul.app.data.local

import com.hansul.app.ai.LocalAgentContext
import com.hansul.app.ai.LocalDecisionSource
import com.hansul.app.ai.LocalGemmaAgent
import com.hansul.app.ai.LocalModelManager
import com.hansul.app.data.CookingSessionRepository
import com.hansul.app.model.Addressing
import com.hansul.app.model.CookingEvent
import com.hansul.app.model.CookingNavigation
import com.hansul.app.model.CookingPhotoCue
import com.hansul.app.model.CookingProgress
import com.hansul.app.model.CookingSession
import com.hansul.app.model.CookingTimerCue
import com.hansul.app.model.CookingTimerDeadline
import com.hansul.app.model.CookingTurn
import com.hansul.app.model.Recipe
import com.hansul.app.model.StepProgressStatus
import com.hansul.app.model.TimerAcknowledgement
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OfflineCookingSessionRepository internal constructor(
    private val store: LocalCookingStore,
    private val decisions: LocalDecisionSource,
) : CookingSessionRepository {
    constructor(store: LocalCookingStore, models: LocalModelManager) : this(store, LocalGemmaAgent(models))
    private val mutex = Mutex()

    override suspend fun start(recipeId: String, targetServings: Int): CookingSession = mutex.withLock {
        require(targetServings in 1..99)
        val recipe = recipe(recipeId)
        store.latestSession(recipeId)?.takeIf { previous ->
            previous.targetServings == targetServings && recipe.steps.any { it.number !in previous.session.state.completedSourceStepNumbers }
        }?.let { return@withLock updateElapsedTimers(it).session }
        val session = CookingSession(
            id = "local-session-${UUID.randomUUID()}", timerCues = LocalTimerExtractor.extract(recipe),
            timerDeadlines = emptyList(), state = CookingProgress(focusedSourceStepNumber = recipe.steps.first().number),
        )
        store.saveSession(LocalSessionSnapshot(recipeId, targetServings, session))
        session
    }

    override suspend fun getSession(sessionId: String): CookingSession = mutex.withLock {
        updateElapsedTimers(snapshot(sessionId)).session
    }

    override suspend fun navigate(sessionId: String, clientNavigationId: String, sourceStepNumber: Int): CookingNavigation = mutex.withLock {
        val snapshot = snapshot(sessionId)
        val key = "navigate:$clientNavigationId"
        val payload = sourceStepNumber.toString()
        snapshot.navigationCommands[key]?.let {
            require(snapshot.commandPayloads[key] == payload) { "같은 명령 ID로 다른 단계를 지정할 수 없어요." }
            return@withLock it
        }
        requireStep(snapshot, sourceStepNumber)
        val state = snapshot.session.state.copy(
            version = snapshot.session.state.version + 1, focusedSourceStepNumber = sourceStepNumber,
        )
        val response = CookingNavigation(sessionId, sourceStepNumber, state)
        store.saveSession(snapshot.copy(
            session = snapshot.session.copy(state = state),
            navigationCommands = snapshot.navigationCommands + (key to response),
            commandPayloads = snapshot.commandPayloads + (key to payload),
        ))
        response
    }

    override suspend fun updateStepProgress(sessionId: String, clientCommandId: String, sourceStepNumber: Int,
        status: StepProgressStatus): CookingNavigation = mutex.withLock {
        val snapshot = snapshot(sessionId)
        val key = "progress:$clientCommandId"
        val payload = "$sourceStepNumber:${status.name}"
        snapshot.navigationCommands[key]?.let {
            require(snapshot.commandPayloads[key] == payload) { "같은 명령 ID로 다른 완료 상태를 지정할 수 없어요." }
            return@withLock it
        }
        requireStep(snapshot, sourceStepNumber)
        val previous = snapshot.session.state
        val state = previous.copy(
            version = previous.version + 1,
            completedSourceStepNumbers = (previous.completedSourceStepNumbers - sourceStepNumber) +
                if (status == StepProgressStatus.COMPLETED) setOf(sourceStepNumber) else emptySet(),
            skippedSourceStepNumbers = (previous.skippedSourceStepNumbers - sourceStepNumber) +
                if (status == StepProgressStatus.SKIPPED) setOf(sourceStepNumber) else emptySet(),
        )
        val response = CookingNavigation(sessionId, sourceStepNumber, state)
        store.saveSession(snapshot.copy(
            session = snapshot.session.copy(state = state),
            navigationCommands = snapshot.navigationCommands + (key to response),
            commandPayloads = snapshot.commandPayloads + (key to payload),
        ))
        response
    }

    override suspend fun submitTurn(sessionId: String, clientTurnId: String, transcript: String): CookingTurn {
        val context = mutex.withLock {
            val snapshot = updateElapsedTimers(snapshot(sessionId))
            snapshot.turns[clientTurnId]?.let {
                require(snapshot.turnInputs[clientTurnId] == transcript) { "같은 대화 ID로 다른 내용을 보낼 수 없어요." }
                return it
            }
            require(transcript.isNotBlank() && transcript.length <= 3000)
            val recipe = recipe(snapshot.recipeId)
            LocalAgentContext(recipe, snapshot.targetServings, snapshot.session,
                snapshot.offeredTimerCueIds, transcript, snapshot.turns.values.toList().takeLast(3).map { it.replyText },
                snapshot.turnInputs.values.toList().takeLast(5))
        }
        // Native generation must never block manual navigation/timers under the repository lock.
        val generated = com.hansul.app.ai.LocalDecisionValidator.validate(decisions.decide(context), context)
        return mutex.withLock {
        val snapshot = updateElapsedTimers(snapshot(sessionId))
        snapshot.turns[clientTurnId]?.let {
            require(snapshot.turnInputs[clientTurnId] == transcript) { "같은 대화 ID로 다른 내용을 보낼 수 없어요." }
            return@withLock it
        }
        val recipe = recipe(snapshot.recipeId)
        val proposal = com.hansul.app.ai.LocalDecisionValidator.rebase(generated,
            context.session.state.version, snapshot.session.state.version)
        val direct = proposal.addressing == Addressing.DIRECT
        val previous = snapshot.session.state
        val state = if (!direct) previous else previous.copy(
            version = previous.version + 1,
            focusedSourceStepNumber = proposal.viewedSourceStepNumber ?: previous.focusedSourceStepNumber,
            completedSourceStepNumbers = previous.completedSourceStepNumbers + proposal.completedSteps.map { it.sourceStepNumber },
            skippedSourceStepNumbers = previous.skippedSourceStepNumbers - proposal.completedSteps.map { it.sourceStepNumber }.toSet(),
            ingredientAdjustments = previous.ingredientAdjustments + proposal.ingredientAdjustments.filter { it !in previous.ingredientAdjustments },
        )
        val alreadyStarted = snapshot.session.timerDeadlines.map { it.timerCueId }.toSet()
        val newlyAccepted = proposal.timerAcceptIds.toSet() - snapshot.requestedTimerCueIds - alreadyStarted
        val newOffers = proposal.timerOfferIds.toSet() - snapshot.requestedTimerCueIds - alreadyStarted - newlyAccepted
        val events = newOffers.map { CookingEvent("TIMER_OFFERED", it) } +
            newlyAccepted.map { CookingEvent("TIMER_START_REQUESTED", it) }
        val response = CookingTurn(
            sessionId, clientTurnId, proposal.addressing, proposal.replyText,
            proposal.photoSourceSteps.distinct().mapNotNull { number ->
                recipe.steps.firstOrNull { it.number == number }?.imageUrl?.let { CookingPhotoCue(number, it) }
            }, events, state,
        )
        store.saveSession(snapshot.copy(
            session = snapshot.session.copy(state = state, pendingTimerCueIds = snapshot.requestedTimerCueIds + newlyAccepted),
            offeredTimerCueIds = (snapshot.offeredTimerCueIds + newOffers) - newlyAccepted - alreadyStarted,
            requestedTimerCueIds = snapshot.requestedTimerCueIds + newlyAccepted,
            turns = snapshot.turns + (clientTurnId to response),
            turnInputs = snapshot.turnInputs + (clientTurnId to transcript),
        ))
        response
        }
    }

    override suspend fun acknowledgeTimerStart(sessionId: String, clientAckId: String, timerCueId: String): TimerAcknowledgement = mutex.withLock {
        val snapshot = snapshot(sessionId)
        snapshot.acknowledgements[clientAckId]?.let {
            require(it.timerDeadline?.timerCueId == timerCueId) { "같은 예약 ID로 다른 타이머를 확인할 수 없어요." }
            return@withLock it
        }
        val cue = requireNotNull(snapshot.session.timerCues[timerCueId]) { "원문에 없는 타이머는 시작할 수 없어요." }
        require(cue.durationSeconds > 0)
        require(timerCueId in snapshot.requestedTimerCueIds) { "시작 요청을 확인한 타이머만 예약할 수 있어요." }
        require(snapshot.session.timerDeadlines.none { it.timerCueId == timerCueId }) { "이미 예약한 타이머예요." }
        val now = Instant.now()
        val deadline = CookingTimerDeadline(timerCueId, now.toString(), now.plusSeconds(cue.durationSeconds).toString(), null)
        val response = TimerAcknowledgement(sessionId, clientAckId, deadline)
        store.saveSession(snapshot.copy(
            session = snapshot.session.copy(
                timerDeadlines = snapshot.session.timerDeadlines.filterNot { it.timerCueId == timerCueId } + deadline,
                pendingTimerCueIds = snapshot.requestedTimerCueIds - timerCueId),
            requestedTimerCueIds = snapshot.requestedTimerCueIds - timerCueId,
            offeredTimerCueIds = snapshot.offeredTimerCueIds - timerCueId,
            acknowledgements = snapshot.acknowledgements + (clientAckId to response),
        ))
        response
    }

    override suspend fun requestTimerStart(sessionId: String, clientCommandId: String, timerCueId: String): CookingProgress = mutex.withLock {
        val snapshot = updateElapsedTimers(snapshot(sessionId))
        val cue = requireNotNull(snapshot.session.timerCues[timerCueId]) { "원문에 없는 타이머는 시작할 수 없어요." }
        val key = "timer:$clientCommandId"
        snapshot.navigationCommands[key]?.let {
            require(snapshot.commandPayloads[key] == timerCueId) { "같은 명령 ID로 다른 타이머를 시작할 수 없어요." }
            return@withLock it.state
        }
        require(snapshot.session.timerDeadlines.none { it.timerCueId == timerCueId } && timerCueId !in snapshot.requestedTimerCueIds) {
            "이미 시작을 요청하거나 예약한 타이머예요."
        }
        val pending = snapshot.requestedTimerCueIds + timerCueId
        val state = snapshot.session.state.copy(version = snapshot.session.state.version + 1)
        store.saveSession(snapshot.copy(
            session = snapshot.session.copy(state = state, pendingTimerCueIds = pending), requestedTimerCueIds = pending,
            offeredTimerCueIds = snapshot.offeredTimerCueIds - timerCueId,
            navigationCommands = snapshot.navigationCommands + (key to CookingNavigation(sessionId, cue.sourceStepNumber, state)),
            commandPayloads = snapshot.commandPayloads + (key to timerCueId),
        ))
        state
    }

    private suspend fun recipe(id: String): Recipe = store.withCachedImages(
        requireNotNull(store.findRecipe(id)) { "기기에 저장한 레시피를 찾지 못했어요." }
    )

    private suspend fun snapshot(id: String): LocalSessionSnapshot =
        requireNotNull(store.findSession(id)) { "기기에 저장한 조리 기록을 찾지 못했어요." }

    private suspend fun requireStep(snapshot: LocalSessionSnapshot, number: Int) {
        require(recipe(snapshot.recipeId).steps.any { it.number == number }) { "원문에 없는 단계를 선택할 수 없어요." }
    }

    private suspend fun updateElapsedTimers(snapshot: LocalSessionSnapshot): LocalSessionSnapshot {
        val now = Instant.now()
        val deadlines = snapshot.session.timerDeadlines.map { deadline ->
            if (deadline.elapsedAt == null && !Instant.parse(deadline.endsAt).isAfter(now)) deadline.copy(elapsedAt = now.toString()) else deadline
        }
        if (deadlines == snapshot.session.timerDeadlines) return snapshot
        return snapshot.copy(session = snapshot.session.copy(timerDeadlines = deadlines)).also { store.saveSession(it) }
    }
}

internal object LocalTimerExtractor {
    private val duration = Regex("(?<![0-9./])(약\\s*)?([0-9]{1,4}(?:\\.[0-9]+)?|\\.[0-9]+)(?:\\s*(?:~|～|∼|-|–|—|부터|에서)\\s*([0-9]{1,4}(?:\\.[0-9]+)?))?\\s*(시간|분|초)(?:\\s*(정도|쯤|내외|가량))?")
    private val totalDuration = Regex("전체|예상|조리\\s*시간|총\\s*[0-9]")
    private val uncertainPrefix = Regex("(?:최소|최대|[-–—])(?:\\s*약)?\\s*$|[0-9]+(?:\\.[0-9]+)?\\s*(?:시간|분|초)?\\s*(?:~|～|∼|–|—|-|에서|부터)\\s*$")
    private val uncertainSuffix = Regex("^\\s*(?:이상|이하|최대|최소|부터|까지|마다|또는|씩|에서|반|의|~|～|∼|–|—|-)")

    fun extract(recipe: Recipe): Map<String, CookingTimerCue> = buildMap {
        recipe.steps.forEach { step ->
            if (totalDuration.containsMatchIn(step.text)) return@forEach
            val matches = duration.findAll(step.text).toList()
            var index = 0
            while (index < matches.size) {
                val match = matches[index]
                // A range is not one proven duration. Do not silently choose one endpoint.
                if (match.groupValues[3].isNotBlank()) { index++; continue }
                val next = matches.getOrNull(index + 1)
                val gap = next?.let { step.text.substring(match.range.last + 1, it.range.first).trim() }
                if (gap in setOf("~", "～", "∼", "-", "–", "—", "부터", "에서")) { index += 2; continue }
                var seconds = seconds(match) ?: 0L
                var approximate = match.groupValues[1].isNotBlank() || match.groupValues[5].isNotBlank()
                var last = match
                var end = index
                while (end + 1 < matches.size) {
                    val following = matches[end + 1]
                    if (following.groupValues[3].isNotBlank() ||
                        step.text.substring(last.range.last + 1, following.range.first).isNotBlank() ||
                        unitSeconds(following) >= unitSeconds(last)) break
                    seconds += seconds(following) ?: 0L
                    approximate = approximate || following.groupValues[1].isNotBlank() || following.groupValues[5].isNotBlank()
                    last = following
                    end++
                }
                val id = "local-timer-${step.number}-$index"
                val prefix = step.text.substring(0, match.range.first)
                val suffix = step.text.substring(last.range.last + 1)
                if (seconds in 1..604800 && !uncertainPrefix.containsMatchIn(prefix) && !uncertainSuffix.containsMatchIn(suffix)) {
                    put(id, CookingTimerCue(id, seconds, step.number, approximate))
                }
                index = end + 1
            }
        }
    }

    private fun unitSeconds(match: MatchResult) = when (match.groupValues[4]) { "시간" -> 3600L; "분" -> 60L; else -> 1L }
    private fun seconds(match: MatchResult): Long? = runCatching {
        match.groupValues[2].toBigDecimal().multiply(unitSeconds(match).toBigDecimal()).longValueExact()
    }.getOrNull()
}

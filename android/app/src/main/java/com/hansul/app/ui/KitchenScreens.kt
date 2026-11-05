package com.hansul.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.hansul.app.ai.LocalAiStatus
import com.hansul.app.ai.LocalModelManager
import com.hansul.app.data.ExecutionMode
import com.hansul.app.model.*
import com.hansul.app.voice.VoiceCapturePhase
import com.hansul.app.voice.VoiceCaptureState
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

@Composable
internal fun KitchenLogo(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = "한술" }) {
        val stroke = size.width * .065f
        drawArc(color, 0f, 180f, false, Offset(size.width * .08f, size.height * .38f),
            Size(size.width * .76f, size.height * .48f), style = Stroke(stroke))
        drawLine(color, Offset(size.width * .07f, size.height * .62f), Offset(size.width * .85f, size.height * .62f), stroke)
        drawLine(color, Offset(size.width * .64f, size.height * .47f), Offset(size.width * .88f, size.height * .12f), stroke)
        drawLine(color, Offset(size.width * .30f, size.height * .40f), Offset(size.width * .34f, size.height * .17f), stroke)
        drawLine(color, Offset(size.width * .44f, size.height * .40f), Offset(size.width * .48f, size.height * .10f), stroke)
    }
}

@Composable
internal fun KitchenWelcome(state: RecipeImportUiState, onImport: (String) -> Unit,
    onSignIn: () -> Unit, onLocal: () -> Unit, onPickVideo: () -> Unit = {}) {
    var url by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 30.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            KitchenLogo(Modifier.size(56.dp))
            Text("오늘은 어떤 요리를\n할까요?", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 30.sp, lineHeight = 40.sp), fontWeight = FontWeight.Bold)
            Text("좋아하는 레시피를 가져오세요.\n손이 바쁠 때는 한술에게 물어보세요.",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    KitchenGlyphIcon(KitchenGlyph.LINK, tint = MaterialTheme.colorScheme.primary)
                    Text("레시피 링크", style = MaterialTheme.typography.titleMedium)
                }
                OutlinedTextField(url, { url = it }, placeholder = { Text("만개의레시피·YouTube 링크를 붙여넣으세요") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3, shape = RoundedCornerShape(14.dp))
                Button(onClick = { onImport(url) }, enabled = url.isNotBlank(), shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("가져오기", style = MaterialTheme.typography.titleMedium) }
            }
        }
        OutlinedButton(onClick = onPickVideo, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("휴대폰 영상으로 가져오기", style = MaterialTheme.typography.titleMedium) }
        Text("레시피 사이트·YouTube·갤러리에서 공유 → 한술을 선택해도 돼요.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (state.executionMode == ExecutionMode.CLOUD && !state.isAuthenticated) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (state.pendingUrl != null) "공유한 레시피를 준비했어요" else "클라우드로 함께 요리하려면 로그인해 주세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onSignIn, enabled = !state.isAuthBusy, modifier = Modifier.heightIn(min = 48.dp)) { Text("로그인") }
                    TextButton(onClick = onLocal, modifier = Modifier.heightIn(min = 48.dp)) { Text("이 기기에서 시작") }
                }
                state.authErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        } else {
            Text(if (state.executionMode == ExecutionMode.LOCAL) "기기 내 모드 · AI 준비는 설정에서 확인해 주세요." else "사진과 설명은 가져온 레시피를 기준으로 안내해요.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** [progress] (0..1) draws a bar instead of the spinner; [onCancel] adds a way out of a long video import. */
@Composable
internal fun KitchenLoading(title: String, detail: String? = null, progress: Float? = null, onCancel: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        KitchenLogo(Modifier.size(70.dp))
        Spacer(Modifier.height(26.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        detail?.let { Spacer(Modifier.height(10.dp)); Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(28.dp))
        if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(.7f).height(6.dp).clip(CircleShape),
            trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
        else CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        onCancel?.let { Spacer(Modifier.height(20.dp)); TextButton(onClick = it, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } }
    }
}

@Composable
internal fun KitchenError(message: String, onRetry: (() -> Unit)?, onClear: () -> Unit, onSignIn: (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text("잠깐, 확인이 필요해요", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(24.dp))
        onSignIn?.let { Button(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("로그인") } }
        // Without a pending URL (a rejected share) there is nothing to fetch again.
        onRetry?.let {
            if (onSignIn == null) Button(onClick = it, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("다시 가져오기") }
            else OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 54.dp)) { Text("다시 가져오기") }
        }
        TextButton(onClick = onClear) { Text("다른 링크로 시작하기") }
    }
}

/** Local mode keeps recipes on the device, but a new video can only be analysed by the server's AI. */
@Composable
internal fun KitchenVideoLogin(state: RecipeImportUiState, onSignIn: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        KitchenLogo(Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("로그인이 필요해요", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text("영상 레시피는 한술 서버의 AI가 영상을 보고 정리해요. 로그인하면 바로 분석을 시작할게요.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSignIn, enabled = !state.isAuthBusy, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("로그인", style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") }
        state.authErrorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
    }
}

/** Asked once, before the first phone video goes to the server: who receives it and what happens to it. */
@Composable
internal fun KitchenVideoConsent(onAccept: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        KitchenLogo(Modifier.size(56.dp))
        Spacer(Modifier.height(20.dp))
        Text("영상을 AI 분석에 보낼까요?", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(VIDEO_UPLOAD_NOTICE, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAccept, shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text("동의하고 분석하기", style = MaterialTheme.typography.titleMedium) }
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") }
    }
}

@Composable
internal fun KitchenRecipeScreen(state: RecipeImportUiState, onDecrease: () -> Unit,
    onIncrease: () -> Unit, onStart: () -> Unit,
    onAsk: (String) -> Unit = {}) {
    val recipe = state.recipe ?: return
    var selectedTab by rememberSaveable(recipe.id) { mutableIntStateOf(0) }
    var question by remember { mutableStateOf(false) }
    var videoRequest by remember(recipe.id) { mutableStateOf<VideoPlayRequest?>(null) }
    val previewList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val video = recipe.video
    val fromVideo = recipe.origin == RecipeOrigin.VIDEO_AI
    // The panel sits in the first item, so a time chip scrolls back up to it before it plays.
    fun playAt(seconds: Int) {
        val source = video ?: return
        if (source.playsInApp()) {
            videoRequest = videoRequest.next(seconds)
            scope.launch { previewList.animateScrollToItem(0) }
        } else source.externalUrl(seconds)?.let { openExternally(context, it) }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = previewList, contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item {
                // An analysed video leads with the video itself; its thumbnail becomes the poster.
                if (fromVideo && video != null) RecipeVideoPanel(video, recipe.imageUrl, videoRequest, ::playAt, { videoRequest = null })
                else recipe.imageUrl?.let { SourcePhoto(it, "만개의레시피 · 원문 사진", 250, edgeToEdge = true) }
                Column(Modifier.padding(horizontal = 24.dp).padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(recipe.title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("${recipe.sourceLabel()} · ${recipe.steps.size}단계 · ${if (fromVideo) "영상 기준" else "원문"} ${recipe.sourceServingLabel()}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (fromVideo) NoticeCard("AI가 영상의 말과 화면 글자를 보고 정리한 레시피예요. 분량과 시간은 영상에서 꼭 확인해 주세요.")
                    if (!fromVideo && video != null) {
                        Text("원문 영상", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        RecipeVideoPanel(video, null, videoRequest, ::playAt, { videoRequest = null },
                            Modifier.clip(RoundedCornerShape(16.dp)))
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("만들 분량", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDecrease, enabled = state.targetServings > 1 && state.cookingSession == null && !state.isStartingCooking,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "인분 줄이기" }) { Text("−", fontSize = 24.sp) }
                            Text("${state.targetServings}인분", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 8.dp))
                            IconButton(onClick = onIncrease, enabled = state.targetServings < 99 && state.cookingSession == null && !state.isStartingCooking,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "인분 늘리기" }) { Text("+", fontSize = 24.sp) }
                        }
                    }
                }
            }
            if (state.assistantReply != null || state.isTurnPending) item(key = "recipe-answer") {
                Box(Modifier.padding(horizontal = 24.dp)) { AnswerCard(state.assistantReply, state.lastTranscript, state.isTurnPending) }
            }
            items(state.voicePhotos, key = { "reference-${it.sourceStepNumber}-${it.imageUrl}" }) { photo ->
                Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourcePhoto(photo.imageUrl, "질문 참고 · ${recipe.stepPhotoLabel(photo.sourceStepNumber)}", 220, noun = recipe.photoNoun())
                    Text("참고 사진이에요. 조리 순서는 바뀌지 않아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.voiceErrorMessage?.let { item { Box(Modifier.padding(horizontal = 24.dp)) { HintCard("답변을 가져오지 못했어요", it, true) } } }
            item {
                TabRow(selectedTabIndex = selectedTab, containerColor = Color.Transparent, contentColor = MaterialTheme.colorScheme.primary) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("재료", style = MaterialTheme.typography.titleMedium) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("조리 순서", style = MaterialTheme.typography.titleMedium) })
                }
            }
            if (selectedTab == 0) {
                if (recipe.ingredients.isEmpty()) item { Text("원문에 재료 목록이 없어요.", Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(recipe.ingredients) { index, ingredient ->
                    val scaled = state.scaledIngredients?.ingredients?.getOrNull(index)?.takeIf { it.name == ingredient.name }
                    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
                            Text(ingredient.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text(if (scaled == null) ingredient.quantityText else scaled.scaledQuantityText.ifBlank { scaled.originalQuantityText },
                                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (scaled != null && scaled.status !in setOf("SOURCE", "UNCHANGED")) {
                            Text("${if (scaled.status == "COOKING_REVIEW_REQUIRED") "조리 조건 확인 필요" else "비례 참고값"} · 원문 ${scaled.originalQuantityText}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider(Modifier.padding(top = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    }
                }
                if (state.isScalingIngredients) item {
                    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth()); Text("분량 참고값을 준비해요", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.scaledIngredients != null) item {
                    Text("양은 계산 참고값이에요. 물·양념·불·시간은 조리 상황에 따라 달라질 수 있어요.",
                        Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(recipe.steps, key = { it.number }) { step ->
                    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${step.number}단계", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            val start = step.videoStartSeconds
                            if (start != null && video?.opensAtTime() == true) AssistChip(onClick = { playAt(start) },
                                label = { Text("▶ ${videoTime(start)}") },
                                modifier = Modifier.semantics { contentDescription = "${videoTime(start)}부터 영상 보기" })
                        }
                        step.imageUrl?.let { SourcePhoto(it, recipe.stepPhotoLabel(step.number), 190, noun = recipe.photoNoun()) }
                        Text(step.text, style = MaterialTheme.typography.bodyLarge)
                        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                    }
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onStart, enabled = !state.isStartingCooking && recipe.steps.isNotEmpty(), shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp)) {
                    if (state.isStartingCooking) CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else {
                        Text(if (state.cookingSession != null) "이어서 요리하기" else "요리 시작", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(12.dp)); KitchenGlyphIcon(KitchenGlyph.FORWARD, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                IconButton(onClick = { question = true }, enabled = !state.isTurnPending && !state.isStartingCooking,
                    modifier = Modifier.size(56.dp).semantics { contentDescription = "한술에게 자유롭게 질문하기" }) {
                    KitchenGlyphIcon(KitchenGlyph.CHAT, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
    if (question) QuestionDialog("", state.isTurnPending || state.isStartingCooking,
        onDismiss = { question = false }, onSend = { onAsk(it); question = false; scope.launch { previewList.animateScrollToItem(1) } })
}

@Composable
internal fun KitchenCookingScreen(state: RecipeImportUiState, voice: VoiceCaptureState,
    onStep: (Int) -> Unit, onProgress: (StepProgressStatus) -> Unit, onAsk: (String) -> Unit,
    onEnableVoice: () -> Unit, onDisableVoice: () -> Unit, onResumeListening: () -> Unit,
    onRetry: () -> Unit, onPermission: () -> Unit, onRetryTimer: (String) -> Unit,
    onDismissError: () -> Unit, onStartSourceTimer: (String) -> Unit,
    onReturnToRecipe: () -> Unit) {
    val recipe = state.recipe ?: return
    val step = recipe.steps.getOrNull(state.currentStepIndex) ?: return
    var expandSourceText by rememberSaveable(step.number) { mutableStateOf(false) }
    var sourceTextOverflows by remember(step.number) { mutableStateOf(false) }
    // Keyed by step, so moving to another step stops the segment instead of playing two at once.
    var segmentRequest by remember(step.number) { mutableStateOf<VideoPlayRequest?>(null) }
    LaunchedEffect(state.isVoiceEnabled) { segmentRequest = segmentRequest.withVoice(state.isVoiceEnabled) }
    val context = LocalContext.current
    val video = recipe.video
    val segmentStart = step.videoStartSeconds?.takeIf { video?.opensAtTime() == true }
    var showAllSteps by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf<String?>(null) }
    var replyRequestedAtStep by remember { mutableStateOf<Int?>(null) }
    val scrollState = rememberLazyListState()
    LaunchedEffect(step.number) { scrollState.scrollToItem(0) }
    val sourceTimers = state.cookingSession?.timerCues?.values.orEmpty().filter { it.sourceStepNumber == step.number }
    LaunchedEffect(state.isTurnPending) { if (state.isTurnPending) replyRequestedAtStep = step.number }
    LaunchedEffect(state.isTurnPending, state.assistantReply, state.voicePhotos) {
        if (!state.isTurnPending && state.assistantReply != null && replyRequestedAtStep == step.number &&
            !scrollState.isScrollInProgress && scrollState.firstVisibleItemIndex <= 2) {
            val answerIndex = 3 + (if (state.timerDeadlines.isNotEmpty()) 1 else 0) +
                (if (sourceTimers.isNotEmpty()) 1 else 0) + (if (state.pendingDeviceTimers.isNotEmpty()) 1 else 0)
            scrollState.animateScrollToItem(answerIndex)
        }
    }
    val isComplete = step.number in state.progress.completedSourceStepNumbers
    val isSkipped = step.number in state.progress.skippedSourceStepNumbers
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), state = scrollState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text(recipe.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${state.currentStepIndex + 1} / ${recipe.steps.size} 단계", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { showAllSteps = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                        KitchenGlyphIcon(KitchenGlyph.LIST, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("전체 순서")
                    }
                }
                LinearProgressIndicator(progress = { (state.currentStepIndex + 1).toFloat() / recipe.steps.size },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(CircleShape)
                        .semantics { contentDescription = "보고 있는 조리 단계 위치. 완료 여부와는 별개입니다." },
                    trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
            }
            item(key = "current-${step.number}") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val request = segmentRequest
                    // The playing segment takes the place of its scene photo.
                    if (request != null && video != null) RecipeVideoPanel(video, null, request, { segmentRequest = segmentRequest.next(it) },
                        { segmentRequest = null }, Modifier.clip(RoundedCornerShape(20.dp)))
                    else if (step.imageUrl != null) SourcePhoto(step.imageUrl, "${recipe.stepPhotoLabel(step.number)} · 눌러 크게 보기", 245,
                        noun = recipe.photoNoun())
                    else if (segmentStart == null) Text("이 단계에는 ${recipe.photoNoun()}이 없어요.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Text(step.text, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 22.sp, lineHeight = 32.sp),
                        maxLines = if (expandSourceText) Int.MAX_VALUE else 5,
                        onTextLayout = { if (!expandSourceText) sourceTextOverflows = it.hasVisualOverflow },
                        overflow = TextOverflow.Ellipsis)
                    if (sourceTextOverflows || expandSourceText) TextButton(onClick = { expandSourceText = !expandSourceText }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (expandSourceText) "짧게 보기" else "원문 설명 더보기")
                    }
                    if (segmentStart != null && video != null) OutlinedButton(onClick = {
                        // The video's own speech must not be heard as the cook talking to Hansul.
                        if (state.isVoiceEnabled) onDisableVoice()
                        if (video.playsInApp()) segmentRequest = segmentRequest.next(segmentStart)
                        else video.externalUrl(segmentStart)?.let { openExternally(context, it) }
                    }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("영상에서 보기 ${videoRange(segmentStart, step.videoEndSeconds)}")
                    }
                }
            }
            item {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isComplete, onClick = { onProgress(if (isComplete) StepProgressStatus.PENDING else StepProgressStatus.COMPLETED) },
                        label = { Text(if (isComplete) "완료 · 되돌리기" else "완료 표시") })
                    FilterChip(selected = isSkipped, onClick = { onProgress(if (isSkipped) StepProgressStatus.PENDING else StepProgressStatus.SKIPPED) },
                        label = { Text(if (isSkipped) "건너뜀 · 되돌리기" else "건너뛰기") })
                }
                if (state.isStepSyncing) Text("보고 있는 단계를 맞추고 있어요…", style = MaterialTheme.typography.bodySmall)
                state.stepSyncError?.let { HintCard("화면은 이동했어요", "$it 다음 질문 전에 위치를 다시 맞춥니다.", true) }
            }
            if (state.timerDeadlines.isNotEmpty()) item { ActiveTimers(state) }
            if (sourceTimers.isNotEmpty()) item {
                sourceTimers.forEach { cue ->
                    TextButton(onClick = { onStartSourceTimer(cue.id) },
                        enabled = state.timerDeadlines.none { it.timerCueId == cue.id } && state.pendingDeviceTimers.none { it.cueId == cue.id },
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("${if (cue.approximate) "약 " else ""}${cue.durationSeconds / 60}분 ${if (cue.durationSeconds % 60 != 0L) "${cue.durationSeconds % 60}초 " else ""}타이머 시작")
                    }
                }
            }
            if (state.pendingDeviceTimers.isNotEmpty()) item {
                HintCard("타이머 설정 확인", "알림과 알람 권한을 허용하면 앱을 보지 않아도 알려드려요.")
                TextButton(onClick = onPermission) { Text("권한 확인") }
                state.pendingDeviceTimers.filter { it.stage == TimerScheduleStage.ACK_FAILED }.forEach {
                    TextButton(onClick = { onRetryTimer(it.cueId) }) { Text("타이머 상태 다시 저장") }
                }
            }
            if (state.isTurnPending || state.assistantReply != null) item {
                AnswerCard(state.assistantReply, state.lastTranscript, state.isTurnPending)
            }
            items(state.voicePhotos.filterNot { it.sourceStepNumber == step.number && it.imageUrl == step.imageUrl }) { photo ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("질문 참고 · ${recipe.stepPhotoLabel(photo.sourceStepNumber)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    SourcePhoto(photo.imageUrl, "답변 참고 · ${recipe.stepPhotoLabel(photo.sourceStepNumber)}", 230, noun = recipe.photoNoun())
                    Text("참고 사진을 봐도 현재 조리 위치는 바뀌지 않아요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.progress.ingredientAdjustments.isNotEmpty()) item {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("우리 재료 메모", style = MaterialTheme.typography.titleMedium)
                        state.progress.ingredientAdjustments.takeLast(8).forEach {
                            Text(ingredientMemo(it), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            state.voiceErrorMessage?.let { error -> item {
                HintCard("잠깐 확인해 주세요", error, true)
                Row { if (state.failedVoiceTurn != null) TextButton(onClick = onRetry) { Text("답변 다시 요청") }
                    TextButton(onClick = onDismissError) { Text("닫기") } }
            } }
        }
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { onStep(state.currentStepIndex - 1) }, enabled = state.currentStepIndex > 0,
                        modifier = Modifier.size(52.dp).semantics { contentDescription = "이전 조리 단계" }) { KitchenGlyphIcon(KitchenGlyph.BACK) }
                    val lastStep = state.currentStepIndex == recipe.steps.lastIndex
                    Button(onClick = {
                        if (!lastStep) onStep(state.currentStepIndex + 1)
                        else if (isComplete) onReturnToRecipe()
                        else onProgress(StepProgressStatus.COMPLETED)
                    },
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(when { !lastStep -> "다음 단계"; isComplete -> "레시피로 돌아가기"; else -> "이 단계 완료" },
                            style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(12.dp))
                        KitchenGlyphIcon(if (lastStep && !isComplete) KitchenGlyph.CHECK else KitchenGlyph.FORWARD,
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { question = "" }, enabled = !state.isTurnPending,
                            modifier = Modifier.weight(1f).heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                KitchenGlyphIcon(KitchenGlyph.CHAT, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (state.isTurnPending) "답변을 준비하고 있어요" else "자유롭게 물어보세요", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        IconButton(onClick = if (state.isVoiceEnabled) onDisableVoice else onEnableVoice,
                            modifier = Modifier.size(48.dp).clip(CircleShape)
                                .background(if (state.isVoiceEnabled) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .semantics {
                                    contentDescription = if (state.isVoiceEnabled) "음성 듣기 끄기" else "음성으로 질문하기"
                                    stateDescription = if (state.isVoiceEnabled) "음성 켜짐" else "음성 꺼짐"
                                }) {
                            KitchenGlyphIcon(KitchenGlyph.MIC, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${if (state.executionMode == ExecutionMode.LOCAL) "기기 내" else "클라우드"} · ${voiceLabel(state, voice)}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.isVoiceEnabled && voice.phase == VoiceCapturePhase.ERROR)
                        TextButton(onClick = onResumeListening, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("다시 듣기") }
                }
            }
        }
    }
    question?.let { QuestionDialog(it, state.isTurnPending, { question = null }) { text -> onAsk(text); question = null } }
    if (showAllSteps) StepsDialog(recipe, state.currentStepIndex, state.progress,
        onDismiss = { showAllSteps = false }, onBrowse = onStep, onSelect = { onStep(it); showAllSteps = false })
}

@Composable
private fun ActiveTimers(state: RecipeImportUiState) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(1000) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.timerDeadlines.filter { it.elapsedAt == null }.forEach { timer ->
            val remaining = runCatching { Instant.parse(timer.endsAt).epochSecond - now.epochSecond }.getOrDefault(0).coerceAtLeast(0)
            val cue = state.cookingSession?.timerCues?.get(timer.timerCueId)
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text(if (remaining == 0L) "시간이 됐어요" else "${cue?.sourceStepNumber?.let { "${it}단계 · " }.orEmpty()}타이머",
                        style = MaterialTheme.typography.labelLarge)
                        Text(if (cue?.approximate == true) "원문 기준 약 ${cue.durationSeconds / 60}분" else "기기에 알림 예약됨", style = MaterialTheme.typography.bodySmall) }
                    Text("%02d:%02d".format(remaining / 60, remaining % 60), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
    }
}

@Composable
private fun AnswerCard(answer: String?, transcript: String?, pending: Boolean) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitchenLogo(Modifier.size(24.dp))
                Text("한술의 안내", style = MaterialTheme.typography.titleMedium)
                if (pending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            transcript?.let { Text("“$it”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(if (pending) "조리 상황을 확인하고 있어요…" else answer.orEmpty(), style = MaterialTheme.typography.bodyLarge)
            if (!pending) Text("AI 답변은 원문과 함께 확인해 주세요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HintCard(title: String, text: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun StatusDot(color: Color = MaterialTheme.colorScheme.primary) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}

@Composable
private fun NoticeCard(text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/** Steps of an analysed video show scenes captured from it, never source-page photos. */
private fun Recipe.photoNoun() = if (origin == RecipeOrigin.VIDEO_AI) "영상 장면" else "원문 사진"

private fun Recipe.stepPhotoLabel(number: Int) =
    if (origin == RecipeOrigin.VIDEO_AI) "영상 장면 · ${number}단계" else "원문 ${number}단계"

@Composable
private fun SourcePhoto(url: String, label: String, height: Int, edgeToEdge: Boolean = false, noun: String = "원문 사진") {
    var expanded by remember(url) { mutableStateOf(false) }
    var failed by remember(url) { mutableStateOf(false) }
    var loading by remember(url) { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().height(if (failed) 88.dp else height.dp)
            .clip(RoundedCornerShape(if (edgeToEdge) 0.dp else 20.dp)).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            if (!failed) AsyncImage(model = url, contentDescription = label, contentScale = if (edgeToEdge) ContentScale.Crop else ContentScale.Fit,
                onSuccess = { loading = false }, onError = { loading = false; failed = true },
                modifier = Modifier.fillMaxSize().clickable { expanded = true })
            if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            if (failed) Text("${noun}을 불러오지 못했어요.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        Text(label, modifier = Modifier.padding(horizontal = if (edgeToEdge) 24.dp else 0.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (expanded) Dialog(onDismissRequest = { expanded = false }) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                AsyncImage(model = url, contentDescription = label, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 540.dp))
                TextButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            }
        }
    }
}

@Composable
private fun QuestionDialog(initial: String, pending: Boolean, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("한술에게 물어보기") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("지금 궁금한 것을 편하게 알려주세요.", style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(text, { text = it.take(3000) }, modifier = Modifier.fillMaxWidth(), minLines = 3,
                label = { Text("질문 입력") },
                placeholder = { Text("예: 재료를 이미 넣었는데 괜찮을까?") }, shape = RoundedCornerShape(16.dp))
        } },
        confirmButton = { Button(onClick = { onSend(text) }, enabled = text.isNotBlank() && !pending) { Text("물어보기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } })
}

@Composable
private fun StepsDialog(recipe: Recipe, currentIndex: Int, progress: CookingProgress,
    onDismiss: () -> Unit, onBrowse: (Int) -> Unit, onSelect: (Int) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(20.dp)) {
                Text("전체 조리 순서", style = MaterialTheme.typography.titleLarge)
                Text("스크롤한 단계가 대화에 반영돼요. 완료 표시는 별도예요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                val list = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex)
                val browse by rememberUpdatedState(onBrowse)
                LaunchedEffect(list) {
                    snapshotFlow { list.firstVisibleItemIndex }.distinctUntilChanged().drop(1).collect { browse(it) }
                }
                LazyColumn(Modifier.weight(1f, fill = false), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(recipe.steps) { index, step ->
                        Surface(onClick = { onSelect(index) }, shape = RoundedCornerShape(16.dp),
                            color = if (index == currentIndex) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val badge = when (step.number) { in progress.completedSourceStepNumbers -> " · 완료"; in progress.skippedSourceStepNumbers -> " · 건너뜀"; else -> "" }
                                Text("${index + 1}단계$badge", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                Text(step.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KitchenSettingsSheet(state: RecipeImportUiState, manager: LocalModelManager?, onDismiss: () -> Unit,
    onMode: (ExecutionMode) -> Unit, onImportModel: () -> Unit, onDownloadModel: () -> Unit,
    onRemoveModel: () -> Unit, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    val local = manager?.state?.collectAsStateWithLifecycle()?.value
    var confirmDownload by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("나에게 맞는 한술", style = MaterialTheme.typography.headlineSmall)
            Text("AI 처리 위치", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(state.executionMode == ExecutionMode.LOCAL, { onMode(ExecutionMode.LOCAL) },
                    label = { Text("이 기기에서") }, enabled = !state.isCooking && !state.isTurnPending)
                FilterChip(state.executionMode == ExecutionMode.CLOUD, { onMode(ExecutionMode.CLOUD) },
                    label = { Text("클라우드") }, enabled = !state.isCooking && !state.isTurnPending)
            }
            Text("기기 모드는 계정·한술 서버 없이 이용합니다. 처음 모델을 받을 때와 새 레시피를 읽을 때는 인터넷이 필요해요.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("Gemma 4 E2B", style = MaterialTheme.typography.titleLarge)
            Text(local?.message ?: "기기 내 AI를 준비해 주세요.", style = MaterialTheme.typography.bodyMedium)
            local?.backend?.let { Text("실행 장치 · $it", style = MaterialTheme.typography.bodySmall) }
            local?.progress?.let { progress ->
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            }
            val busy = local?.status in setOf(LocalAiStatus.DOWNLOADING, LocalAiStatus.IMPORTING, LocalAiStatus.LOADING, LocalAiStatus.GENERATING)
            if (manager != null) {
                Button(onClick = { confirmDownload = true }, enabled = !busy && !state.isTurnPending, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("공식 모델 다운로드 · 약 2.6GB") }
                OutlinedButton(onClick = onImportModel, enabled = !busy && !state.isTurnPending, modifier = Modifier.fillMaxWidth()) { Text(".litertlm 모델 파일 가져오기") }
                if (local?.modelName != null) TextButton(onClick = { confirmRemove = true }, enabled = !busy) { Text("기기 내 모델 지우기") }
            }
            Text("기기 내 AI는 실험 기능이에요. 대체재·인분 답변이 부정확할 수 있으니 원문과 함께 확인해 주세요. 한국어 오프라인 음성 인식·TTS는 기기 지원에 따라 달라요. 모델 없이도 화면과 터치로 조리할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            Text("계정", style = MaterialTheme.typography.titleMedium)
            if (state.isAuthenticated) TextButton(onClick = onSignOut, enabled = !state.isCooking) { Text("로그아웃") }
            else TextButton(onClick = onSignIn) { Text("클라우드용 로그인") }
            Text("안전하게 요리하기", style = MaterialTheme.typography.titleMedium)
            Text("AI의 조언은 원문 설명과 구분해 확인하세요. 알레르기·보관·덜 익은 식재료 등 안전 판단은 확답하지 않습니다. 음성은 앱이 화면에 열려 있을 때만 듣습니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (confirmDownload) AlertDialog(onDismissRequest = { confirmDownload = false }, title = { Text("모델을 다운로드할까요?") },
        text = { Text("약 2.6GB를 이 기기에 저장합니다. Wi-Fi와 충분한 여유 공간을 권장해요. 다운로드가 끝나면 모델 파일을 검증합니다.") },
        confirmButton = { Button(onClick = { confirmDownload = false; onDownloadModel() }) { Text("다운로드") } },
        dismissButton = { TextButton(onClick = { confirmDownload = false }) { Text("취소") } })
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false }, title = { Text("모델 파일을 지울까요?") },
        text = { Text("저장한 레시피는 남습니다. 로컬 AI를 다시 쓰려면 모델을 다시 받아야 해요.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; onRemoveModel() }) { Text("지우기") } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("취소") } })
}

private fun voiceLabel(state: RecipeImportUiState, voice: VoiceCaptureState): String = when {
    state.isTurnPending -> "답변 준비 중"
    !state.isVoiceEnabled -> "말로 요리하기"
    voice.phase == VoiceCapturePhase.LISTENING -> "듣고 있어요 · 눌러 끄기"
    voice.phase == VoiceCapturePhase.SPEAKING -> "답변을 읽고 있어요"
    voice.phase == VoiceCapturePhase.ERROR -> "음성 확인 필요"
    voice.phase == VoiceCapturePhase.PAUSED_BACKGROUND -> "음성 일시정지"
    else -> "음성 켜짐 · 눌러 끄기"
}

private fun ingredientMemo(item: IngredientAdjustment): String = when (item.kind) {
    "MISSING" -> "${item.ingredientName} · 없음"
    "AVAILABLE" -> "${item.ingredientName} · 준비됨"
    "ADDED" -> "${item.ingredientName} ${item.quantityText} · 추가"
    "REPLACED" -> "${item.ingredientName} → ${item.replacementName} ${item.quantityText}"
    else -> "${item.ingredientName} ${item.quantityText}"
}

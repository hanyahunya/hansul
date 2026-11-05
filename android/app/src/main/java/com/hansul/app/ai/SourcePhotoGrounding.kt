package com.hansul.app.ai

import com.hansul.app.model.RecipeStep

/** Generic source-text retrieval. Never invents a URL, recipe-specific rule, or ingredient synonym list. */
internal object SourcePhotoGrounding {
    fun select(decision: LocalCookingDecision, context: LocalAgentContext): List<Int> {
        val candidates = context.recipe.steps.filter { !it.imageUrl.isNullOrBlank() }
        val asksForVisualGuidance = Regex("사진|이미지|모양|크기|어떻게|방법|\\bhow\\b|\\bphoto\\b|\\bimage\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(context.transcript)
        if (candidates.isEmpty() || !asksForVisualGuidance && decision.photoSourceSteps.isEmpty() && decision.photoSourceEvidence.isEmpty()) return emptyList()
        val answerParts = decision.replyText.split(Regex("[.!?。\\n]+"))
            .filter { it.isNotBlank() }.ifEmpty { listOf(decision.replyText) }
        fun affinity(step: RecipeStep): Double = answerParts.maxOfOrNull { similarity(step.text, it) } ?: 0.0
        // A copied source evidence phrase can repair a wrong numeric ID only when its source is unique.
        val evidenceMatches = decision.photoSourceEvidence.mapNotNull { evidence ->
            evidence.trim().takeIf { it.length >= 8 }?.let { phrase ->
                candidates.filter { phrase in it.text }.singleOrNull()?.takeIf {
                    affinity(it) >= 0.24 || similarity(it.text, context.transcript) >= 0.24
                }
            }
        }.distinctBy { it.number }
        if (evidenceMatches.isNotEmpty()) return evidenceMatches.map { it.number }

        // The assistant often quotes a source instruction accurately but mixes its ID with current focus.
        // Correct only a strong, uniquely best textual match; ambiguous images are omitted, not guessed.
        val ranked = candidates.map { it to affinity(it) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return emptyList()
        if (best.second >= 0.42 && best.second - (ranked.getOrNull(1)?.second ?: 0.0) >= 0.12) return listOf(best.first.number)
        return decision.photoSourceSteps.distinct().mapNotNull { number ->
            candidates.firstOrNull { it.number == number && affinity(it) >= 0.28 }?.number
        }
    }

    private fun similarity(first: String, second: String): Double {
        fun grams(value: String): Set<String> {
            val normalized = value.lowercase()
                // Korean case particles change in faithful paraphrases (noun+를 -> noun+는).
                // Strip them at word boundaries, not by recipe/ingredient-name exceptions.
                .replace(Regex("(^|\\s)([가-힣]{2,}?)(?:은|는|이|가|을|를|와|과|도|만)(?=\\s|[.,!?])"), "$1$2")
                .replace(Regex("해주세요|하세요|주세요|합니다|습니다|입니다"), "")
                .filter { it.isLetterOrDigit() }
            return normalized.windowed(3).toSet()
        }
        val left = grams(first)
        val right = grams(second)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return 2.0 * left.intersect(right).size / (left.size + right.size)
    }
}

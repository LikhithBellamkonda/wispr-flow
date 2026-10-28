package com.flowwrite.ai.personalization

import com.flowwrite.ai.database.FeedbackExampleEntity
import com.flowwrite.ai.database.FlowWriteDatabase
import com.flowwrite.ai.database.WritingPreferenceEntity
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.min

/**
 * Section 5: Text Correction & Rewriting Modes
 * Default is CLEAN mode.
 */
enum class RefinementMode {
    RAW,
    CLEAN,
    POLISH,
    CASUAL,
    CUSTOM
}

enum class FeedbackActionType {
    ACCEPTED,
    REJECTED,
    EDITED,
    REVERTED
}

class PersonalizationRepository(
    private val db: FlowWriteDatabase
) {
    fun observePreferences(): Flow<List<WritingPreferenceEntity>> =
        db.preferenceDao().observeAll()

    fun observeFeedback(): Flow<List<FeedbackExampleEntity>> =
        db.feedbackDao().observeAll()

    suspend fun getTopActivePreferences(maxCount: Int = 5): List<WritingPreferenceEntity> =
        db.preferenceDao().getActiveConditioningPreferences(
            minConfidence = AdaptiveLearningEngine.PROMOTION_CONFIDENCE_THRESHOLD,
            limit = maxCount
        )

    suspend fun getRelevantApprovedExamples(
        currentTranscript: String,
        mode: RefinementMode,
        maxCount: Int = 2
    ): List<FeedbackExampleEntity> {
        val pool = db.feedbackDao().getCandidateExamplesForMode(mode = mode.name, candidatePoolSize = 35)
        return AdaptiveLearningEngine.selectMostRelevantExamples(
            currentTranscript = currentTranscript,
            candidates = pool,
            maxExamples = maxCount
        )
    }

    suspend fun addExplicitPreference(preferenceText: String, contextCategory: String): Long {
        val trimmed = preferenceText.trim()
        require(trimmed.isNotEmpty()) { "Preference text cannot be blank." }
        val key = "manual_${trimmed.lowercase().hashCode()}"
        return db.preferenceDao().upsert(
            WritingPreferenceEntity(
                patternKey = key,
                preferenceText = trimmed,
                contextCategory = contextCategory.ifBlank { "MANUAL" },
                sourceType = "MANUAL",
                occurrenceCount = 1,
                confidenceScore = 1.0f,
                enabled = true
            )
        )
    }

    suspend fun setPreferenceEnabled(id: Long, enabled: Boolean) {
        db.preferenceDao().setEnabled(id, enabled)
    }

    suspend fun deletePreference(id: Long) {
        db.preferenceDao().deleteById(id)
    }

    suspend fun recordFeedbackAndLearn(
        originalTranscript: String,
        generatedOutput: String,
        approvedCorrection: String?,
        feedbackType: FeedbackActionType,
        mode: RefinementMode,
        learningEnabled: Boolean
    ): List<WritingPreferenceEntity> {
        if (!learningEnabled) return emptyList()

        val promotedOrUpdated = mutableListOf<WritingPreferenceEntity>()

        val detectedPatterns = if (feedbackType == FeedbackActionType.EDITED &&
            !approvedCorrection.isNullOrBlank() &&
            approvedCorrection.trim() != generatedOutput.trim()
        ) {
            AdaptiveLearningEngine.extractPatternsFromCorrection(
                originalTranscript = originalTranscript,
                generatedOutput = generatedOutput,
                correctedText = approvedCorrection
            )
        } else {
            emptyList()
        }

        val signalWeight = when (feedbackType) {
            FeedbackActionType.EDITED -> 1.0f
            FeedbackActionType.ACCEPTED -> 0.6f
            FeedbackActionType.REJECTED, FeedbackActionType.REVERTED -> -0.25f
        }

        val tagsCsv = detectedPatterns.joinToString(",") { it.patternKey }

        db.feedbackDao().insert(
            FeedbackExampleEntity(
                originalTranscript = originalTranscript.trim(),
                generatedOutput = generatedOutput.trim(),
                approvedCorrection = if (signalWeight > 0f) approvedCorrection?.trim() else null,
                feedbackType = feedbackType.name,
                writingMode = mode.name,
                signalWeight = signalWeight,
                extractedTags = tagsCsv
            )
        )

        if (feedbackType == FeedbackActionType.EDITED && detectedPatterns.isNotEmpty()) {
            val now = System.currentTimeMillis()
            for (pattern in detectedPatterns) {
                val existing = db.preferenceDao().findByPatternKey(pattern.patternKey)
                val updatedEntity = if (existing == null) {
                    WritingPreferenceEntity(
                        patternKey = pattern.patternKey,
                        preferenceText = pattern.preferenceText,
                        contextCategory = pattern.category,
                        sourceType = "AUTO_LEARNED",
                        occurrenceCount = 1,
                        confidenceScore = AdaptiveLearningEngine.INITIAL_CANDIDATE_CONFIDENCE,
                        enabled = true,
                        createdAt = now,
                        updatedAt = now
                    )
                } else {
                    val newCount = existing.occurrenceCount + 1
                    val newConfidence = min(
                        1.0f,
                        existing.confidenceScore + AdaptiveLearningEngine.REPEATED_PATTERN_BOOST
                    )
                    existing.copy(
                        occurrenceCount = newCount,
                        confidenceScore = newConfidence,
                        updatedAt = now
                    )
                }
                db.preferenceDao().upsert(updatedEntity)
                promotedOrUpdated.add(updatedEntity)
            }
        } else if (feedbackType == FeedbackActionType.REJECTED || feedbackType == FeedbackActionType.REVERTED) {
            val allPrefs = db.preferenceDao().getAllPreferences()
            for (pref in allPrefs) {
                if (pref.sourceType == "AUTO_LEARNED" && pref.occurrenceCount <= 1) {
                    val decayed = max(0.10f, pref.confidenceScore - 0.10f)
                    db.preferenceDao().updateConfidence(pref.id, decayed)
                }
            }
        }

        return promotedOrUpdated
    }

    suspend fun deleteFeedbackById(id: Long) {
        db.feedbackDao().deleteById(id)
    }

    suspend fun resetAllLearnedData() {
        db.preferenceDao().deleteAll()
        db.feedbackDao().deleteAll()
    }

    suspend fun clearAllPersonalizationData() {
        db.preferenceDao().deleteAll()
        db.feedbackDao().deleteAll()
        db.dictionaryDao().deleteAll()
    }
}

object PromptBuilder {

    /**
     * Dynamically constructs a Qwen2.5-0.5B-Instruct ChatML prompt supporting:
     * - 5 Rewriting Modes: RAW, CLEAN (default), POLISH, CASUAL, CUSTOM
     * - 4 Language Styles: AUTO_CODE_MIXED, ENGLISH, HINGLISH, KANGLISH
     * - Strict Latin/Roman script preservation for Hinglish and Kanglish without translating into English
     *   or converting to Devanagari/Kannada script.
     */
    fun buildChatMlPrompt(
        preprocessedTranscript: String,
        mode: RefinementMode,
        languageStyle: LanguageStyle = LanguageStyle.AUTO_CODE_MIXED,
        customInstruction: String = "",
        activePreferences: List<WritingPreferenceEntity> = emptyList(),
        fewShotExamples: List<FeedbackExampleEntity> = emptyList(),
        personalizationEnabled: Boolean = true
    ): String {
        if (mode == RefinementMode.RAW) {
            return preprocessedTranscript.trim()
        }

        val effectiveStyle = if (languageStyle == LanguageStyle.AUTO_CODE_MIXED) {
            AdaptiveLearningEngine.detectDominantLanguageStyle(preprocessedTranscript)
        } else {
            languageStyle
        }

        val codeMixRule = when (effectiveStyle) {
            LanguageStyle.HINGLISH -> """
                Language Style: HINGLISH (Hindi-English code-mixed speech in Latin/Roman alphabet).
                - Keep all Hindi words in English/Latin letters (e.g., "Kal college mein meeting hai, toh mujhe time pe aana.").
                - NEVER convert Hinglish into Devanagari script and NEVER translate Hindi words into English.
            """.trimIndent()

            LanguageStyle.KANGLISH -> """
                Language Style: KANGLISH (Kannada-English code-mixed speech in Latin/Roman alphabet).
                - Keep all Kannada words in English/Latin letters (e.g., "Naale college ge bega barbeku.").
                - NEVER convert Kanglish into Kannada script and NEVER translate Kannada words into English.
            """.trimIndent()

            LanguageStyle.ENGLISH, LanguageStyle.AUTO_CODE_MIXED -> """
                Language Style: Preserve any code-mixed Hindi (Hinglish) or Kannada (Kanglish) words in Latin/English script without translating them into English or Indic scripts.
            """.trimIndent()
        }

        val modeInstruction = when (mode) {
            RefinementMode.CLEAN -> """
                You are a voice keyboard text cleaner operating in CLEAN mode.
                1. Correct speech recognition errors, spelling, capitalization, and punctuation while strictly preserving the speaker's original words and language mixture.
                2. Remove spoken filler noises (um, uh, er) and accidental immediate word repetitions.
                3. Do not rewrite sentence structure or force formal English grammar onto code-mixed speech.
                4. Return ONLY the final cleaned text without explanations, Markdown, or quotation marks.
            """.trimIndent()

            RefinementMode.POLISH -> """
                You are a voice keyboard text refiner operating in POLISH mode.
                1. Improve clarity and sentence flow and resolve mid-sentence spoken self-corrections while preserving exact meaning and language style.
                2. Preserve all names, places, technical terms, numbers, and Hinglish/Kanglish code-mixed words in Latin script.
                3. Return ONLY the final polished text without explanations, Markdown, or quotation marks.
            """.trimIndent()

            RefinementMode.CASUAL -> """
                You are a voice keyboard refiner operating in CASUAL mode.
                1. Keep the wording natural, warm, and conversational for chat apps like WhatsApp and Instagram.
                2. Preserve regional expressions, contractions, and informal Hinglish/Kanglish phrasing in Latin script.
                3. Fix only obvious recognition errors and punctuation. Return ONLY the final text.
            """.trimIndent()

            RefinementMode.CUSTOM -> {
                val userPrompt = customInstruction.trim().ifEmpty {
                    "Clean up punctuation and preserve the original meaning and language mixture."
                }
                """
                You are a voice keyboard text rewriter operating in CUSTOM mode.
                User Instruction: $userPrompt
                Preserve factual details and return ONLY the final rewritten text without commentary or quotes.
                """.trimIndent()
            }

            RefinementMode.RAW -> return preprocessedTranscript.trim()
        }

        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(modeInstruction).append("\n\n")
        sb.append(codeMixRule).append("\n")

        if (personalizationEnabled && activePreferences.isNotEmpty()) {
            sb.append("\nLearned User Style & Transliteration Preferences:\n")
            for (pref in activePreferences.take(5)) {
                sb.append("- [").append(pref.contextCategory).append("] ")
                    .append(pref.preferenceText.trim())
                    .append("\n")
            }
        }
        sb.append("<|im_end|>\n")

        if (personalizationEnabled && fewShotExamples.isNotEmpty()) {
            for (example in fewShotExamples.take(2)) {
                val approved = example.approvedCorrection?.trim()
                if (!approved.isNullOrEmpty()) {
                    sb.append("<|im_start|>user\n")
                    sb.append(example.originalTranscript.trim()).append("<|im_end|>\n")
                    sb.append("<|im_start|>assistant\n")
                    sb.append(approved).append("<|im_end|>\n")
                }
            }
        }

        sb.append("<|im_start|>user\n")
        sb.append(preprocessedTranscript.trim()).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")

        return sb.toString()
    }
}

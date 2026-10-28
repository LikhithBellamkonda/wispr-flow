package com.flowwrite.ai.validation

import com.flowwrite.ai.personalization.AdaptiveLearningEngine
import com.flowwrite.ai.personalization.LanguageStyle

/**
 * OutputValidator:
 * Validates local Qwen2.5 output for empty responses, conversational prefixes,
 * unwanted Indic script conversion (Devanagari/Kannada script when Latin Hinglish/Kanglish is required),
 * degenerate repetition loops, and length anomalies.
 */
object OutputValidator {

    sealed class ValidationResult {
        data class Valid(val cleanedText: String) : ValidationResult()
        data class InvalidFallback(val fallbackTranscript: String, val reason: String) : ValidationResult()
    }

    private val BANNED_CHAT_PREFIXES = listOf(
        "sure, here",
        "here is the",
        "here's the",
        "cleaned text:",
        "polished text:",
        "rewritten text:",
        "casual text:",
        "output:",
        "transcript:"
    )

    fun validate(
        rawModelOutput: String?,
        originalTranscript: String,
        languageStyle: LanguageStyle = LanguageStyle.AUTO_CODE_MIXED
    ): ValidationResult {
        val fallback = originalTranscript.trim()
        if (rawModelOutput.isNullOrBlank()) {
            return ValidationResult.InvalidFallback(fallback, "Model returned an empty response.")
        }

        var candidate = rawModelOutput
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .trim()

        if (candidate.length >= 2 &&
            ((candidate.startsWith("\"") && candidate.endsWith("\"")) ||
             (candidate.startsWith("'") && candidate.endsWith("'"))) &&
            !fallback.startsWith("\"")
        ) {
            candidate = candidate.substring(1, candidate.length - 1).trim()
        }

        val lower = candidate.lowercase()
        for (prefix in BANNED_CHAT_PREFIXES) {
            if (lower.startsWith(prefix)) {
                val colonIndex = candidate.indexOf(':')
                if (colonIndex in 0..45 && colonIndex + 1 < candidate.length) {
                    candidate = candidate.substring(colonIndex + 1).trim()
                }
            }
        }

        if (candidate.isEmpty()) {
            return ValidationResult.InvalidFallback(fallback, "Output was empty after stripping formatting markers.")
        }

        // Section 2 Guard: Do not convert Hinglish into Devanagari or Kanglish into Kannada script
        // if the input transcript was in Latin script.
        if (!AdaptiveLearningEngine.containsIndicNativeScript(fallback) &&
            AdaptiveLearningEngine.containsIndicNativeScript(candidate)
        ) {
            return ValidationResult.InvalidFallback(
                fallback,
                "Model converted Romanized Hinglish/Kanglish into Indic script; preserved Latin transcript."
            )
        }

        val maxAllowedChars = (fallback.length * 2.5).toInt() + 48
        if (candidate.length > maxAllowedChars) {
            return ValidationResult.InvalidFallback(
                fallback,
                "Output length (${candidate.length}) exceeded conservative expansion bound ($maxAllowedChars)."
            )
        }

        if (hasDegenerateRepetitionLoop(candidate)) {
            return ValidationResult.InvalidFallback(
                fallback,
                "Detected degenerate repetition loop in model output."
            )
        }

        return ValidationResult.Valid(candidate)
    }

    internal fun hasDegenerateRepetitionLoop(text: String): Boolean {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 9) return false

        for (i in 0..(words.size - 9)) {
            val w1 = words[i].lowercase()
            val w2 = words[i + 1].lowercase()
            val w3 = words[i + 2].lowercase()

            if (w1 == words[i + 3].lowercase() &&
                w2 == words[i + 4].lowercase() &&
                w3 == words[i + 5].lowercase() &&
                w1 == words[i + 6].lowercase() &&
                w2 == words[i + 7].lowercase() &&
                w3 == words[i + 8].lowercase()
            ) {
                return true
            }
        }
        return false
    }
}

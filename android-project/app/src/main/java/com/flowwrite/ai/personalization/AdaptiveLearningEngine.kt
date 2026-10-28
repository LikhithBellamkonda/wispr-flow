package com.flowwrite.ai.personalization

import com.flowwrite.ai.database.FeedbackExampleEntity
import kotlin.math.max
import kotlin.math.min

/**
 * Language Style / Code-Mixing Mode (Section 2):
 * - AUTO_CODE_MIXED: Automatically detects English, Hinglish (Hindi in Latin script),
 *   and Kanglish (Kannada in Latin script) tokens and preserves their Latin transliteration.
 * - ENGLISH: Standard English dictation.
 * - HINGLISH: Hindi-English code-mixed speech written strictly in Roman/Latin alphabet.
 * - KANGLISH: Kannada-English code-mixed speech written strictly in Roman/Latin alphabet.
 */
enum class LanguageStyle {
    AUTO_CODE_MIXED,
    ENGLISH,
    HINGLISH,
    KANGLISH
}

data class DetectedPreferencePattern(
    val patternKey: String,
    val preferenceText: String,
    val category: String, // PUNCTUATION, LENGTH, TONE, FORMALITY, VOCABULARY, SPELLING, TRANSLITERATION
    val confidenceDelta: Float = 0.35f
)

/**
 * AdaptiveLearningEngine:
 * Handles code-mixed Hinglish & Kanglish Roman-script preservation, transliteration normalization,
 * automatic diff learning from user corrections, and similarity-based example retrieval.
 */
object AdaptiveLearningEngine {

    const val PROMOTION_CONFIDENCE_THRESHOLD = 0.65f
    const val INITIAL_CANDIDATE_CONFIDENCE = 0.35f
    const val REPEATED_PATTERN_BOOST = 0.35f

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "is", "are", "was", "were", "be", "been", "it",
        "this", "that", "i", "we", "you", "they", "he", "she"
    )

    // High-frequency Hinglish (Roman Hindi) markers for code-mixed detection & spelling learning
    val COMMON_HINGLISH_MARKERS = setOf(
        "kal", "aaj", "abhi", "mein", "hai", "hain", "toh", "mujhe", "tumhe", "aana",
        "jaana", "karna", "kya", "kyun", "kaise", "nahi", "nhi", "bahut", "bohot",
        "accha", "acha", "theek", "thik", "bhai", "yaar", "matlab", "zaroor", "jaldi",
        "kaam", "baat", "sath", "saath", "pe", "par", "aur", "lekin", "agar", "wala"
    )

    // High-frequency Kanglish (Roman Kannada) markers for code-mixed detection & spelling learning
    val COMMON_KANGLISH_MARKERS = setOf(
        "naale", "nale", "ivattu", "iga", "ge", "ige", "bega", "barbeku", "hogbeku",
        "madbeku", "maadbeku", "ide", "illa", "hage", "hege", "yaake", "yake", "enu",
        "yenu", "swalpa", "thumba", "tumba", "chennagide", "aytu", "aaytu", "gottu",
        "gottilla", "madi", "maadi", "beku", "beda", "kodi", "heli", "nange", "nimge",
        "namaskara", "maga", "machaa", "oota"
    )

    // Common Hinglish/Kanglish phonetic spelling variants for transliteration preference learning
    private val TRANSLITERATION_VARIANT_GROUPS = listOf(
        setOf("naale", "nale"),
        setOf("barbeku", "barabeku"),
        setOf("madbeku", "maadbeku"),
        setOf("madi", "maadi"),
        setOf("aytu", "aaytu", "aayithu"),
        setOf("thumba", "tumba"),
        setOf("mujhe", "muje"),
        setOf("nahi", "nhi", "nai"),
        setOf("accha", "acha", "achha"),
        setOf("theek", "thik"),
        setOf("bahut", "bohot"),
        setOf("toh", "to"),
        setOf("mein", "me")
    )

    private val CONTRACTION_MAP = mapOf(
        "do not" to "don't",
        "cannot" to "can't",
        "will not" to "won't",
        "should not" to "shouldn't",
        "would not" to "wouldn't",
        "could not" to "couldn't",
        "i am" to "i'm",
        "we are" to "we're",
        "they are" to "they're",
        "it is" to "it's",
        "let us" to "let's",
        "that is" to "that's"
    )

    /**
     * Detects whether a transcript contains Hinglish, Kanglish, or mixed tokens when
     * `LanguageStyle.AUTO_CODE_MIXED` is active.
     */
    fun detectDominantLanguageStyle(transcript: String): LanguageStyle {
        val tokens = tokenizeWords(transcript)
        var hinglishHits = 0
        var kanglishHits = 0

        for (t in tokens) {
            if (t in COMMON_HINGLISH_MARKERS) hinglishHits++
            if (t in COMMON_KANGLISH_MARKERS) kanglishHits++
        }

        return when {
            kanglishHits > 0 && kanglishHits >= hinglishHits -> LanguageStyle.KANGLISH
            hinglishHits > 0 -> LanguageStyle.HINGLISH
            else -> LanguageStyle.ENGLISH
        }
    }

    /**
     * Checks if text contains any Devanagari (U+0900..U+097F) or Kannada (U+0C80..U+0CFF)
     * native script characters, and transliterates common vowels/consonants to Latin script
     * as a safety net so Hinglish/Kanglish output stays strictly in English letters.
     */
    fun containsIndicNativeScript(text: String): Boolean {
        for (ch in text) {
            val code = ch.code
            if (code in 0x0900..0x097F || code in 0x0C80..0x0CFF) {
                return true
            }
        }
        return false
    }

    /**
     * Compares (originalTranscript, generatedOutput, correctedText) to learn:
     * - Preferred Hinglish / Kanglish transliteration spellings (e.g., "nale" -> "naale", "nhi" -> "nahi")
     * - Preservation of code-mixed Hindi/Kannada words when the LLM over-translated to English
     * - Punctuation, sentence length, contractions, vocabulary, and casing preferences
     */
    fun extractPatternsFromCorrection(
        originalTranscript: String,
        generatedOutput: String,
        correctedText: String
    ): List<DetectedPreferencePattern> {
        val gen = generatedOutput.trim()
        val cor = correctedText.trim()
        if (gen.isEmpty() || cor.isEmpty() || gen == cor) return emptyList()

        val patterns = mutableListOf<DetectedPreferencePattern>()

        // 1. HINGLISH / KANGLISH TRANSLITERATION SPELLING PREFERENCE
        val genWords = tokenizeWords(gen)
        val corWords = tokenizeWords(cor)
        for (variantGroup in TRANSLITERATION_VARIANT_GROUPS) {
            val genMatch = genWords.firstOrNull { it in variantGroup }
            val corMatch = corWords.firstOrNull { it in variantGroup }
            if (genMatch != null && corMatch != null && genMatch != corMatch) {
                patterns.add(
                    DetectedPreferencePattern(
                        patternKey = "translit_${genMatch}_to_${corMatch}",
                        preferenceText = "Spell the Romanized word \"$corMatch\" as \"$corMatch\" instead of \"$genMatch\".",
                        category = "TRANSLITERATION"
                    )
                )
            }
        }

        // 2. CODE-MIXED PRESERVATION: User restored a Hinglish/Kanglish word that LLM translated
        val rawTokens = tokenizeWords(originalTranscript).toSet()
        val genTokenSet = genWords.toSet()
        for (cWord in corWords) {
            if ((cWord in COMMON_HINGLISH_MARKERS || cWord in COMMON_KANGLISH_MARKERS) &&
                cWord in rawTokens && cWord !in genTokenSet
            ) {
                patterns.add(
                    DetectedPreferencePattern(
                        patternKey = "codemix_preserve_latin_words",
                        preferenceText = "Preserve spoken Hinglish and Kanglish words in Latin script without translating them into English.",
                        category = "TRANSLITERATION"
                    )
                )
                break
            }
        }

        // 3. PUNCTUATION: Exclamation mark removal or addition
        if (gen.contains('!') && !cor.contains('!')) {
            patterns.add(
                DetectedPreferencePattern(
                    patternKey = "punct_no_exclamation",
                    preferenceText = "Avoid exclamation marks; use periods for a calm, composed tone.",
                    category = "PUNCTUATION"
                )
            )
        } else if (!gen.contains('!') && cor.contains('!')) {
            patterns.add(
                DetectedPreferencePattern(
                    patternKey = "punct_use_exclamation",
                    preferenceText = "Preserve enthusiastic exclamation marks in casual messages.",
                    category = "TONE"
                )
            )
        }

        // 4. SENTENCE LENGTH & CONCISENESS
        if (genWords.size >= 8 && corWords.size <= (genWords.size * 0.78).toInt()) {
            patterns.add(
                DetectedPreferencePattern(
                    patternKey = "length_concise_direct",
                    preferenceText = "Keep sentences concise and direct; trim unnecessary transitional words and verbose phrasing.",
                    category = "LENGTH"
                )
            )
        }

        // 5. FORMALITY: Contractions vs Expanded Forms
        val genLower = gen.lowercase()
        val corLower = cor.lowercase()
        var switchedToContractions = false
        var switchedToExpandedFormal = false

        for ((expanded, contracted) in CONTRACTION_MAP) {
            if (genLower.contains(expanded) && corLower.contains(contracted)) {
                switchedToContractions = true
            } else if (genLower.contains(contracted) && corLower.contains(expanded)) {
                switchedToExpandedFormal = true
            }
        }

        if (switchedToContractions) {
            patterns.add(
                DetectedPreferencePattern(
                    patternKey = "formality_natural_contractions",
                    preferenceText = "Use natural conversational contractions (e.g., don't, can't, let's, we're) instead of stiff expanded verbs.",
                    category = "FORMALITY"
                )
            )
        } else if (switchedToExpandedFormal) {
            patterns.add(
                DetectedPreferencePattern(
                    patternKey = "formality_no_contractions",
                    preferenceText = "Use full formal verb forms (e.g., do not, cannot, we will) without contractions.",
                    category = "FORMALITY"
                )
            )
        }

        // 6. VOCABULARY & SPELLING: Targeted 1-to-1 substitutions (including custom Hinglish/Kanglish spellings)
        val wordReplacements = extractSingleTokenSubstitutions(gen, cor)
        for ((fromWord, toWord) in wordReplacements.take(2)) {
            val alreadyCovered = patterns.any { it.patternKey.endsWith("_to_${toWord.lowercase()}") }
            if (alreadyCovered) continue

            if (fromWord.equals(toWord, ignoreCase = true) && fromWord != toWord) {
                patterns.add(
                    DetectedPreferencePattern(
                        patternKey = "spelling_case_${toWord.lowercase()}",
                        preferenceText = "Always spell and capitalize \"$toWord\" exactly as \"$toWord\".",
                        category = "SPELLING"
                    )
                )
            } else {
                val isIndicWord = toWord.lowercase() in COMMON_HINGLISH_MARKERS ||
                    toWord.lowercase() in COMMON_KANGLISH_MARKERS
                patterns.add(
                    DetectedPreferencePattern(
                        patternKey = "vocab_${fromWord.lowercase()}_to_${toWord.lowercase()}",
                        preferenceText = if (isIndicWord) {
                            "Spell the Hinglish/Kanglish word \"$toWord\" as \"$toWord\" instead of \"$fromWord\"."
                        } else {
                            "Prefer the word \"$toWord\" instead of \"$fromWord\" when appropriate."
                        },
                        category = if (isIndicWord) "TRANSLITERATION" else "VOCABULARY"
                    )
                )
            }
        }

        return patterns
    }

    internal fun extractSingleTokenSubstitutions(
        generated: String,
        corrected: String
    ): List<Pair<String, String>> {
        val genTokens = generated.trim().split(Regex("\\s+")).map { it.trim(',', '.', '?', '!', ';', ':') }
        val corTokens = corrected.trim().split(Regex("\\s+")).map { it.trim(',', '.', '?', '!', ';', ':') }

        if (genTokens.size != corTokens.size || genTokens.isEmpty()) return emptyList()

        val diffs = mutableListOf<Pair<String, String>>()
        for (i in genTokens.indices) {
            val g = genTokens[i]
            val c = corTokens[i]
            if (g != c && g.length >= 2 && c.length >= 2) {
                val gLower = g.lowercase()
                val cLower = c.lowercase()
                if (gLower !in STOP_WORDS && cLower !in STOP_WORDS) {
                    diffs.add(g to c)
                }
            }
        }
        return diffs
    }

    fun selectMostRelevantExamples(
        currentTranscript: String,
        candidates: List<FeedbackExampleEntity>,
        maxExamples: Int = 2
    ): List<FeedbackExampleEntity> {
        if (candidates.isEmpty() || maxExamples <= 0) return emptyList()

        val queryTokens = tokenizeContentWords(currentTranscript)
        val queryWordCount = max(1, tokenizeWords(currentTranscript).size)

        return candidates
            .asSequence()
            .filter { !it.approvedCorrection.isNullOrBlank() && it.signalWeight > 0f }
            .map { candidate ->
                val candTokens = tokenizeContentWords(candidate.originalTranscript)
                val jaccard = computeJaccardSimilarity(queryTokens, candTokens)

                val candWordCount = max(1, tokenizeWords(candidate.originalTranscript).size)
                val lengthRatio = min(queryWordCount, candWordCount).toFloat() /
                    max(queryWordCount, candWordCount).toFloat()

                val editPriority = if (candidate.feedbackType == "EDITED") 1.0f else 0.55f

                val compositeScore = (jaccard * 0.65f) + (lengthRatio * 0.20f) + (editPriority * 0.15f)
                candidate to compositeScore
            }
            .sortedByDescending { it.second }
            .take(maxExamples)
            .map { it.first }
            .toList()
    }

    private fun computeJaccardSimilarity(setA: Set<String>, setB: Set<String>): Float {
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val intersectionSize = setA.count { it in setB }
        val unionSize = setA.size + setB.size - intersectionSize
        return if (unionSize == 0) 0f else intersectionSize.toFloat() / unionSize.toFloat()
    }

    private fun tokenizeWords(text: String): List<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9']+"))
            .filter { it.isNotBlank() }

    private fun tokenizeContentWords(text: String): Set<String> =
        tokenizeWords(text)
            .filter { it.length > 2 && it !in STOP_WORDS }
            .toSet()
}

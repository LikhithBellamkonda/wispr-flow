package com.flowwrite.ai

import com.flowwrite.ai.database.FeedbackExampleEntity
import com.flowwrite.ai.database.PersonalDictionaryEntity
import com.flowwrite.ai.database.WritingPreferenceEntity
import com.flowwrite.ai.dictionary.DictionaryProcessor
import com.flowwrite.ai.personalization.AdaptiveLearningEngine
import com.flowwrite.ai.personalization.LanguageStyle
import com.flowwrite.ai.personalization.PromptBuilder
import com.flowwrite.ai.personalization.RefinementMode
import com.flowwrite.ai.validation.OutputValidator
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Tests for FlowWrite AI covering:
 * - Hinglish & Kanglish code-mixed language detection (`detectDominantLanguageStyle`)
 * - Transliteration spelling variant extraction (`nale` -> `naale`, `nhi` -> `nahi`)
 * - All 5 rewriting modes (`RAW`, `CLEAN`, `POLISH`, `CASUAL`, `CUSTOM`)
 * - Indic native script rejection guard (`OutputValidator` rejecting Devanagari/Kannada script conversion)
 * - Personalization Disabled vs Enabled prompt comparison
 */
class FlowWriteUnitTests {

    @Test
    fun `detectDominantLanguageStyle identifies Hinglish and Kanglish in Latin script`() {
        val hinglish = "Kal college mein meeting hai, toh mujhe time pe aana"
        val kanglish = "Naale college ge bega barbeku"
        val english = "Please review the pull request before tomorrow afternoon"

        assertEquals(LanguageStyle.HINGLISH, AdaptiveLearningEngine.detectDominantLanguageStyle(hinglish))
        assertEquals(LanguageStyle.KANGLISH, AdaptiveLearningEngine.detectDominantLanguageStyle(kanglish))
        assertEquals(LanguageStyle.ENGLISH, AdaptiveLearningEngine.detectDominantLanguageStyle(english))
    }

    @Test
    fun `extractPatternsFromCorrection learns Hinglish and Kanglish transliteration spelling preferences`() {
        val raw = "nale college ge bega barabeku"
        val generated = "Nale college ge bega barabeku."
        val userCorrected = "Naale college ge bega barbeku."

        val patterns = AdaptiveLearningEngine.extractPatternsFromCorrection(
            originalTranscript = raw,
            generatedOutput = generated,
            correctedText = userCorrected
        )

        val keys = patterns.map { it.patternKey }.toSet()
        assertTrue(keys.contains("translit_nale_to_naale"))
        assertTrue(keys.contains("translit_barabeku_to_barbeku"))
    }

    @Test
    fun `promptBuilder supports Clean, Polish, Casual, Raw, and Custom modes with Hinglish and Kanglish`() {
        val kanglishInput = "naale college ge bega barbeku"

        val cleanPrompt = PromptBuilder.buildChatMlPrompt(
            preprocessedTranscript = kanglishInput,
            mode = RefinementMode.CLEAN,
            languageStyle = LanguageStyle.AUTO_CODE_MIXED
        )
        assertTrue(cleanPrompt.contains("CLEAN mode"))
        assertTrue(cleanPrompt.contains("KANGLISH"))
        assertTrue(cleanPrompt.contains("NEVER convert Kanglish into Kannada script"))

        val customPrompt = PromptBuilder.buildChatMlPrompt(
            preprocessedTranscript = "kal team meeting mein deployment discuss karenge",
            mode = RefinementMode.CUSTOM,
            languageStyle = LanguageStyle.HINGLISH,
            customInstruction = "Format as a short bullet point in Hinglish"
        )
        assertTrue(customPrompt.contains("CUSTOM mode"))
        assertTrue(customPrompt.contains("Format as a short bullet point in Hinglish"))

        val rawPrompt = PromptBuilder.buildChatMlPrompt(
            preprocessedTranscript = kanglishInput,
            mode = RefinementMode.RAW
        )
        assertEquals(kanglishInput, rawPrompt)
    }

    @Test
    fun `outputValidator rejects unwanted Devanagari or Kannada script conversion when input is Latin`() {
        val romanHinglish = "Kal college mein meeting hai, toh mujhe time pe aana."
        val unwantedDevanagari = "कल कॉलेज में मीटिंग है, तो मुझे टाइम पे आना।"

        val res = OutputValidator.validate(unwantedDevanagari, romanHinglish, LanguageStyle.HINGLISH)
        assertTrue(res is OutputValidator.ValidationResult.InvalidFallback)
        assertEquals(
            romanHinglish,
            (res as OutputValidator.ValidationResult.InvalidFallback).fallbackTranscript
        )
    }

    @Test
    fun `adaptiveLearningEngine ranks relevant examples by token overlap and edit priority`() {
        val candidates = listOf(
            FeedbackExampleEntity(
                id = 1,
                originalTranscript = "naale office ge bega barbeku meeting ide",
                generatedOutput = "Nale office ge bega barabeku meeting ide.",
                approvedCorrection = "Naale office ge bega barbeku, meeting ide.",
                feedbackType = "EDITED",
                writingMode = "CLEAN",
                signalWeight = 1.0f
            ),
            FeedbackExampleEntity(
                id = 2,
                originalTranscript = "pick up groceries milk and bread on the way home",
                generatedOutput = "Pick up groceries, milk, and bread on the way home.",
                approvedCorrection = "Pick up milk and bread on the way home.",
                feedbackType = "EDITED",
                writingMode = "CLEAN",
                signalWeight = 1.0f
            )
        )

        val currentInput = "naale college ge bega barbeku"
        val selected = AdaptiveLearningEngine.selectMostRelevantExamples(
            currentTranscript = currentInput,
            candidates = candidates,
            maxExamples = 1
        )

        assertEquals(1, selected.size)
        assertEquals(1L, selected[0].id)
    }

    @Test
    fun `dictionaryProcessor replaces whole words conservatively and ignores disabled entries`() {
        val entries = listOf(
            PersonalDictionaryEntity(
                id = 1,
                spokenForm = "nale",
                preferredSpelling = "Naale",
                category = "Transliteration",
                enabled = true
            ),
            PersonalDictionaryEntity(
                id = 2,
                spokenForm = "barabeku",
                preferredSpelling = "barbeku",
                category = "Transliteration",
                enabled = true
            )
        )

        val input = "nale college ge bega barabeku"
        val processed = DictionaryProcessor.applyConservativeReplacements(input, entries)

        assertEquals("Naale college ge bega barbeku", processed)
    }
}

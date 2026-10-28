package com.flowwrite.ai.dictionary

import com.flowwrite.ai.database.PersonalDictionaryDao
import com.flowwrite.ai.database.PersonalDictionaryEntity
import kotlinx.coroutines.flow.Flow

/**
 * Section 7: Personal Dictionary Engine & Repository.
 * Applies user-configured mappings for personal names, acronyms, organization names,
 * and technical terms conservatively using whole-word boundary matching.
 */
class DictionaryProcessor(
    private val dictionaryDao: PersonalDictionaryDao? = null
) {

    fun observeAllEntries(): Flow<List<PersonalDictionaryEntity>>? =
        dictionaryDao?.observeAll()

    suspend fun getEnabledEntries(): List<PersonalDictionaryEntity> =
        dictionaryDao?.getEnabledEntries() ?: emptyList()

    suspend fun saveEntry(
        id: Long = 0,
        spokenForm: String,
        preferredSpelling: String,
        category: String,
        enabled: Boolean = true
    ) {
        val cleanedSpoken = spokenForm.trim()
        val cleanedPreferred = preferredSpelling.trim()
        if (cleanedSpoken.isEmpty() || cleanedPreferred.isEmpty()) return

        dictionaryDao?.upsert(
            PersonalDictionaryEntity(
                id = id,
                spokenForm = cleanedSpoken,
                preferredSpelling = cleanedPreferred,
                category = category,
                enabled = enabled
            )
        )
    }

    suspend fun setEntryEnabled(id: Long, enabled: Boolean) {
        dictionaryDao?.setEnabled(id, enabled)
    }

    suspend fun deleteEntry(id: Long) {
        dictionaryDao?.deleteById(id)
    }

    companion object {
        /**
         * Conservatively replaces spoken forms with preferred spellings only when surrounded
         * by word boundaries, ordered longest-first to avoid partial phrase collisions.
         */
        fun applyConservativeReplacements(
            rawTranscript: String,
            entries: List<PersonalDictionaryEntity>
        ): String {
            if (rawTranscript.isBlank() || entries.isEmpty()) return rawTranscript

            val activeSorted = entries
                .filter { it.enabled && it.spokenForm.isNotBlank() }
                .sortedByDescending { it.spokenForm.length }

            var output = rawTranscript
            for (entry in activeSorted) {
                val pattern = "\\b${Regex.escape(entry.spokenForm.trim())}\\b"
                val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                output = regex.replace(output) {
                    MatcherReplacement.escapeReplacement(entry.preferredSpelling)
                }
            }
            return output
        }
    }

    private object MatcherReplacement {
        fun escapeReplacement(literal: String): String = literal
    }
}

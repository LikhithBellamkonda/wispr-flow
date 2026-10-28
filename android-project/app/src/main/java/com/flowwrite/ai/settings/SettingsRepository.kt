package com.flowwrite.ai.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.flowwrite.ai.personalization.LanguageStyle
import com.flowwrite.ai.personalization.RefinementMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "flowwrite_settings")

/**
 * FlowWriteAppSettings:
 * Defaults to `RefinementMode.CLEAN` and `LanguageStyle.AUTO_CODE_MIXED` per Section 2 & Section 5.
 * Includes `autoInsertAfterProcessing` toggle per Section 1.
 */
data class FlowWriteAppSettings(
    val selectedMode: RefinementMode = RefinementMode.CLEAN,
    val languageStyle: LanguageStyle = LanguageStyle.AUTO_CODE_MIXED,
    val customRewriteInstruction: String = "",
    val autoInsertAfterProcessing: Boolean = true,
    val importedModelPath: String? = null,
    val importedModelSizeBytes: Long = 0L,
    val contextWindowTokens: Int = 1024,
    val temperature: Float = 0.2f,
    val cpuThreads: Int = 4,
    val inferenceTimeoutMs: Long = 3000L,
    val personalizationEnabled: Boolean = true,
    val automaticLearningEnabled: Boolean = true,
    val preferStrictOfflineStt: Boolean = true
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val SELECTED_MODE = stringPreferencesKey("selected_mode")
        val LANGUAGE_STYLE = stringPreferencesKey("language_style")
        val CUSTOM_REWRITE_INSTRUCTION = stringPreferencesKey("custom_rewrite_instruction")
        val AUTO_INSERT_AFTER_PROCESSING = booleanPreferencesKey("auto_insert_after_processing")
        val IMPORTED_MODEL_PATH = stringPreferencesKey("imported_model_path")
        val IMPORTED_MODEL_SIZE = longPreferencesKey("imported_model_size_bytes")
        val CONTEXT_WINDOW = intPreferencesKey("context_window_tokens")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val CPU_THREADS = intPreferencesKey("cpu_threads")
        val INFERENCE_TIMEOUT_MS = longPreferencesKey("inference_timeout_ms")
        val PERSONALIZATION_ENABLED = booleanPreferencesKey("personalization_enabled")
        val AUTOMATIC_LEARNING_ENABLED = booleanPreferencesKey("automatic_learning_enabled")
        val PREFER_STRICT_OFFLINE_STT = booleanPreferencesKey("prefer_strict_offline_stt")
    }

    val settingsFlow: Flow<FlowWriteAppSettings> = context.dataStore.data.map { prefs ->
        val modeStr = prefs[Keys.SELECTED_MODE] ?: RefinementMode.CLEAN.name
        val mode = runCatching { RefinementMode.valueOf(modeStr) }.getOrDefault(RefinementMode.CLEAN)

        val styleStr = prefs[Keys.LANGUAGE_STYLE] ?: LanguageStyle.AUTO_CODE_MIXED.name
        val style = runCatching { LanguageStyle.valueOf(styleStr) }.getOrDefault(LanguageStyle.AUTO_CODE_MIXED)

        FlowWriteAppSettings(
            selectedMode = mode,
            languageStyle = style,
            customRewriteInstruction = prefs[Keys.CUSTOM_REWRITE_INSTRUCTION] ?: "",
            autoInsertAfterProcessing = prefs[Keys.AUTO_INSERT_AFTER_PROCESSING] ?: true,
            importedModelPath = prefs[Keys.IMPORTED_MODEL_PATH],
            importedModelSizeBytes = prefs[Keys.IMPORTED_MODEL_SIZE] ?: 0L,
            contextWindowTokens = prefs[Keys.CONTEXT_WINDOW] ?: 1024,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.2f,
            cpuThreads = prefs[Keys.CPU_THREADS] ?: 4,
            inferenceTimeoutMs = prefs[Keys.INFERENCE_TIMEOUT_MS] ?: 3000L,
            personalizationEnabled = prefs[Keys.PERSONALIZATION_ENABLED] ?: true,
            automaticLearningEnabled = prefs[Keys.AUTOMATIC_LEARNING_ENABLED] ?: true,
            preferStrictOfflineStt = prefs[Keys.PREFER_STRICT_OFFLINE_STT] ?: true
        )
    }

    suspend fun updateSelectedMode(mode: RefinementMode) {
        context.dataStore.edit { it[Keys.SELECTED_MODE] = mode.name }
    }

    suspend fun updateLanguageStyle(style: LanguageStyle) {
        context.dataStore.edit { it[Keys.LANGUAGE_STYLE] = style.name }
    }

    suspend fun updateCustomRewriteInstruction(instruction: String) {
        context.dataStore.edit { it[Keys.CUSTOM_REWRITE_INSTRUCTION] = instruction }
    }

    suspend fun updateAutoInsertAfterProcessing(autoInsert: Boolean) {
        context.dataStore.edit { it[Keys.AUTO_INSERT_AFTER_PROCESSING] = autoInsert }
    }

    suspend fun updateImportedModel(path: String?, sizeBytes: Long) {
        context.dataStore.edit {
            if (path == null) {
                it.remove(Keys.IMPORTED_MODEL_PATH)
                it[Keys.IMPORTED_MODEL_SIZE] = 0L
            } else {
                it[Keys.IMPORTED_MODEL_PATH] = path
                it[Keys.IMPORTED_MODEL_SIZE] = sizeBytes
            }
        }
    }

    suspend fun updatePerformanceConfig(
        contextWindowTokens: Int,
        temperature: Float,
        cpuThreads: Int,
        timeoutMs: Long
    ) {
        context.dataStore.edit {
            it[Keys.CONTEXT_WINDOW] = contextWindowTokens.coerceIn(512, 4096)
            it[Keys.TEMPERATURE] = temperature.coerceIn(0.0f, 1.0f)
            it[Keys.CPU_THREADS] = cpuThreads.coerceIn(1, 8)
            it[Keys.INFERENCE_TIMEOUT_MS] = timeoutMs.coerceIn(1000L, 15000L)
        }
    }

    suspend fun updatePersonalizationEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.PERSONALIZATION_ENABLED] = enabled }
    }

    suspend fun updateAutomaticLearningEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.AUTOMATIC_LEARNING_ENABLED] = enabled }
    }
}

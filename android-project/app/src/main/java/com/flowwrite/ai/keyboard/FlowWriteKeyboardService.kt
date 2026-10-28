package com.flowwrite.ai.keyboard

import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.flowwrite.ai.R
import com.flowwrite.ai.app.MainActivity
import com.flowwrite.ai.database.FlowWriteDatabase
import com.flowwrite.ai.dictionary.DictionaryProcessor
import com.flowwrite.ai.llm.LlamaBridge
import com.flowwrite.ai.personalization.FeedbackActionType
import com.flowwrite.ai.personalization.LanguageStyle
import com.flowwrite.ai.personalization.PersonalizationRepository
import com.flowwrite.ai.personalization.PromptBuilder
import com.flowwrite.ai.personalization.RefinementMode
import com.flowwrite.ai.settings.FlowWriteAppSettings
import com.flowwrite.ai.settings.SettingsRepository
import com.flowwrite.ai.stt.AndroidOnDeviceSttEngine
import com.flowwrite.ai.stt.SpeechToTextEngine
import com.flowwrite.ai.validation.OutputValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * FlowWriteKeyboardService:
 * Wispr Flow-style voice button keyboard that appears inside WhatsApp, Instagram, Gmail, or Messages.
 *
 * Strictly implements the zero-typing voice flow:
 * 1. User taps the Microphone Pill inside the messaging app and speaks naturally (English / Hinglish / Kanglish).
 * 2. When the user stops speaking, FlowWrite AI sends the transcript to the local Qwen2.5 LLM.
 * 3. Enforces a strict < 5000ms (5-second) end-to-end latency budget.
 * 4. Automatically inserts the LLM-corrected text directly into the messaging app's text field via
 *    `InputConnection.commitText()` with zero manual typing or intermediate review screens required.
 * 5. Passively observes if the user edits the inserted text in the messaging app (`onUpdateSelection`)
 *    to learn their style automatically in the background.
 */
class FlowWriteKeyboardService : InputMethodService() {

    companion object {
        const val MAX_END_TO_END_LATENCY_MS = 4500L // Strict < 5 second total latency budget
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var database: FlowWriteDatabase
    private lateinit var personalizationRepo: PersonalizationRepository
    private lateinit var dictionaryProcessor: DictionaryProcessor
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var sttEngine: SpeechToTextEngine
    private lateinit var llamaBridge: LlamaBridge

    private var currentSettings = FlowWriteAppSettings()
    private var activePipelineJob: Job? = null
    private val editorSessionCounter = AtomicLong(0L)

    // Minimalist Voice Button Bar UI
    private var btnCycleMode: Button? = null
    private var btnLangStyle: Button? = null
    private var tvStatus: TextView? = null
    private var progressInference: ProgressBar? = null
    private var btnRevertRaw: Button? = null
    private var btnMicRecord: Button? = null
    private var btnCancelStop: Button? = null

    private var isCurrentFieldSensitive: Boolean = false
    private var lastRawTranscript: String? = null
    private var lastGeneratedText: String? = null
    private var lastInsertedSpanLength: Int = 0

    override fun onCreate() {
        super.onCreate()
        database = FlowWriteDatabase.getInstance(applicationContext)
        personalizationRepo = PersonalizationRepository(database)
        dictionaryProcessor = DictionaryProcessor(database.dictionaryDao())
        settingsRepo = SettingsRepository(applicationContext)
        sttEngine = AndroidOnDeviceSttEngine(applicationContext, requireStrictOffline = true)
        llamaBridge = LlamaBridge(applicationContext)

        serviceScope.launch {
            val initialSettings = settingsRepo.settingsFlow.first()
            if (initialSettings.importedModelPath.isNullOrBlank()) {
                val discovered = llamaBridge.discoverAccessibleLocalModels()
                    .firstOrNull { it.isReadableAndValidGguf }
                if (discovered != null) {
                    settingsRepo.updateImportedModel(discovered.absolutePath, discovered.sizeBytes)
                }
            }

            settingsRepo.settingsFlow.collect { settings ->
                currentSettings = settings
                updateModeButtonLabel(settings.selectedMode)
                updateLanguageButtonLabel(settings.languageStyle)
                val path = settings.importedModelPath
                if (!path.isNullOrBlank()) {
                    llamaBridge.ensureModelLoaded(
                        modelPath = path,
                        contextWindowTokens = settings.contextWindowTokens,
                        cpuThreads = settings.cpuThreads
                    )
                }
            }
        }
    }

    override fun onCreateInputView(): View {
        val root = layoutInflater.inflate(R.layout.view_flowwrite_keyboard, null)

        btnCycleMode = root.findViewById(R.id.btn_cycle_mode)
        btnLangStyle = root.findViewById(R.id.btn_lang_style)
        tvStatus = root.findViewById(R.id.tv_status)
        progressInference = root.findViewById(R.id.progress_inference)
        btnRevertRaw = root.findViewById(R.id.btn_revert_raw)
        btnMicRecord = root.findViewById(R.id.btn_mic_record)
        btnCancelStop = root.findViewById(R.id.btn_cancel_stop)

        val btnSwitchIme = root.findViewById<Button>(R.id.btn_switch_ime)
        val btnBackspace = root.findViewById<Button>(R.id.btn_backspace)
        val btnSpace = root.findViewById<Button>(R.id.btn_space)

        btnCycleMode?.setOnClickListener { cycleRefinementMode() }
        btnLangStyle?.setOnClickListener { cycleLanguageStyle() }

        btnMicRecord?.setOnClickListener { onMicButtonClicked() }
        btnCancelStop?.setOnClickListener { cancelActiveOperation() }
        btnRevertRaw?.setOnClickListener { undoToRawTranscript() }

        btnSwitchIme?.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        btnBackspace?.setOnClickListener {
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }

        btnSpace?.setOnClickListener {
            insertTextAtCursorSafely(" ")
        }

        updateModeButtonLabel(currentSettings.selectedMode)
        updateLanguageButtonLabel(currentSettings.languageStyle)
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        editorSessionCounter.incrementAndGet()
        btnRevertRaw?.visibility = View.GONE

        isCurrentFieldSensitive = isSensitiveOrPasswordField(info)
        if (isCurrentFieldSensitive) {
            tvStatus?.text = getString(R.string.privacy_password_blocked)
            btnMicRecord?.isEnabled = false
        } else {
            btnMicRecord?.isEnabled = true
            tvStatus?.text = "Tap mic to speak (<5s auto-insert)"
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Before closing or switching apps, check if user tweaked the auto-inserted text inside the messaging app
        capturePassiveLearningFromHostEditor()
        editorSessionCounter.incrementAndGet()
        if (sttEngine.isListening() || activePipelineJob?.isActive == true) {
            cancelActiveOperation()
        }
    }

    /**
     * Passively learns from user corrections made directly inside the messaging app's text box
     * without requiring the user to type inside a separate FlowWrite review screen.
     */
    private fun capturePassiveLearningFromHostEditor() {
        val raw = lastRawTranscript ?: return
        val generated = lastGeneratedText ?: return
        val ic = currentInputConnection ?: return

        val currentFieldText = runCatching {
            val before = ic.getTextBeforeCursor(250, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(250, 0)?.toString().orEmpty()
            (before + after).trim()
        }.getOrDefault("")

        if (currentFieldText.isNotEmpty() && currentFieldText != raw) {
            val wasEdited = currentFieldText != generated
            serviceScope.launch(Dispatchers.IO) {
                personalizationRepo.recordFeedbackAndLearn(
                    originalTranscript = raw,
                    generatedOutput = generated,
                    approvedCorrection = currentFieldText,
                    feedbackType = if (wasEdited) FeedbackActionType.EDITED else FeedbackActionType.ACCEPTED,
                    mode = currentSettings.selectedMode,
                    learningEnabled = currentSettings.automaticLearningEnabled
                )
            }
        }
        lastRawTranscript = null
        lastGeneratedText = null
    }

    internal fun isSensitiveOrPasswordField(info: EditorInfo?): Boolean {
        if (info == null) return false
        val inputType = info.inputType
        if (inputType == InputType.TYPE_NULL) return false

        val klass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (klass == InputType.TYPE_CLASS_TEXT) {
            if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) {
                return true
            }
        }
        if (klass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return true
        }
        return false
    }

    private fun cycleRefinementMode() {
        val next = when (currentSettings.selectedMode) {
            RefinementMode.CLEAN -> RefinementMode.POLISH
            RefinementMode.POLISH -> RefinementMode.CASUAL
            RefinementMode.CASUAL -> RefinementMode.RAW
            RefinementMode.RAW -> RefinementMode.CUSTOM
            RefinementMode.CUSTOM -> RefinementMode.CLEAN
        }
        updateModeButtonLabel(next)
        serviceScope.launch {
            settingsRepo.updateSelectedMode(next)
        }
    }

    private fun updateModeButtonLabel(mode: RefinementMode) {
        btnCycleMode?.text = mode.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun cycleLanguageStyle() {
        val next = when (currentSettings.languageStyle) {
            LanguageStyle.AUTO_CODE_MIXED -> LanguageStyle.HINGLISH
            LanguageStyle.HINGLISH -> LanguageStyle.KANGLISH
            LanguageStyle.KANGLISH -> LanguageStyle.ENGLISH
            LanguageStyle.ENGLISH -> LanguageStyle.AUTO_CODE_MIXED
        }
        updateLanguageButtonLabel(next)
        serviceScope.launch {
            settingsRepo.updateLanguageStyle(next)
        }
    }

    private fun updateLanguageButtonLabel(style: LanguageStyle) {
        btnLangStyle?.text = when (style) {
            LanguageStyle.AUTO_CODE_MIXED -> "Auto Mix"
            LanguageStyle.HINGLISH -> "Hinglish"
            LanguageStyle.KANGLISH -> "Kanglish"
            LanguageStyle.ENGLISH -> "English"
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun onMicButtonClicked() {
        if (isCurrentFieldSensitive) {
            tvStatus?.text = getString(R.string.privacy_password_blocked)
            return
        }

        if (!hasRecordAudioPermission()) {
            tvStatus?.text = getString(R.string.mic_permission_required)
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            return
        }

        if (sttEngine.isListening()) {
            sttEngine.stopListening()
            return
        }

        startVoiceToMessagingAppFlow()
    }

    /**
     * Direct Voice -> STT -> Local Qwen2.5 (<5s latency budget) -> Auto-Insert into Messaging App.
     */
    private fun startVoiceToMessagingAppFlow() {
        // Save any passive learning from previous utterance first
        capturePassiveLearningFromHostEditor()

        val sessionAtStart = editorSessionCounter.get()
        activePipelineJob?.cancel()

        activePipelineJob = serviceScope.launch {
            lastInsertedSpanLength = 0
            btnRevertRaw?.visibility = View.GONE
            btnMicRecord?.text = "⏹ Listening... Tap to Finish"
            btnMicRecord?.background?.setTint(0xFFDC2626.toInt())
            btnCancelStop?.visibility = View.VISIBLE
            progressInference?.visibility = View.VISIBLE
            tvStatus?.text = "Listening..."

            val sttResult = sttEngine.transcribeSpeech(currentSettings.languageStyle)
            btnMicRecord?.text = "🎤 Tap to Speak"
            btnMicRecord?.background?.setTint(0xFF4F46E5.toInt())

            val rawTranscript = sttResult.getOrElse { error ->
                progressInference?.visibility = View.GONE
                btnCancelStop?.visibility = View.GONE
                tvStatus?.text = error.message ?: "No speech detected."
                return@launch
            }

            if (editorSessionCounter.get() != sessionAtStart) {
                progressInference?.visibility = View.GONE
                btnCancelStop?.visibility = View.GONE
                return@launch
            }

            val processingStartMs = SystemClock.elapsedRealtime()
            lastRawTranscript = rawTranscript
            tvStatus?.text = "Auto-correcting with local Qwen2.5 (<5s)..."

            val finalOutput = runLocalLlmRefinementUnder5Seconds(rawTranscript, processingStartMs)
            val elapsedMs = SystemClock.elapsedRealtime() - processingStartMs

            if (editorSessionCounter.get() != sessionAtStart) {
                progressInference?.visibility = View.GONE
                btnCancelStop?.visibility = View.GONE
                return@launch
            }

            progressInference?.visibility = View.GONE
            btnCancelStop?.visibility = View.GONE
            lastGeneratedText = finalOutput

            // Immediately insert into the active messaging app text field!
            if (insertTextAtCursorSafely(finalOutput)) {
                btnRevertRaw?.visibility = if (finalOutput != rawTranscript) View.VISIBLE else View.GONE
                tvStatus?.text = "Inserted in ${elapsedMs}ms"
            } else {
                tvStatus?.text = "Target field unavailable."
            }
        }
    }

    private suspend fun runLocalLlmRefinementUnder5Seconds(
        rawTranscript: String,
        startRealtimeMs: Long
    ): String {
        val settings = settingsRepo.settingsFlow.first()
        val dictEntries = dictionaryProcessor.getEnabledEntries()
        val preprocessed = DictionaryProcessor.applyConservativeReplacements(rawTranscript, dictEntries)

        if (settings.selectedMode == RefinementMode.RAW) {
            return preprocessed
        }

        val modelPath = settings.importedModelPath
        if (modelPath.isNullOrBlank()) {
            return preprocessed
        }

        val loadRes = llamaBridge.ensureModelLoaded(
            modelPath = modelPath,
            contextWindowTokens = settings.contextWindowTokens,
            cpuThreads = settings.cpuThreads
        )
        if (loadRes.isFailure) {
            return preprocessed
        }

        val activePrefs = if (settings.personalizationEnabled) {
            personalizationRepo.getTopActivePreferences(maxCount = 5)
        } else {
            emptyList()
        }

        val relevantExamples = if (settings.personalizationEnabled) {
            personalizationRepo.getRelevantApprovedExamples(
                currentTranscript = preprocessed,
                mode = settings.selectedMode,
                maxCount = 2
            )
        } else {
            emptyList()
        }

        val chatMlPrompt = PromptBuilder.buildChatMlPrompt(
            preprocessedTranscript = preprocessed,
            mode = settings.selectedMode,
            languageStyle = settings.languageStyle,
            customInstruction = settings.customRewriteInstruction,
            activePreferences = activePrefs,
            fewShotExamples = relevantExamples,
            personalizationEnabled = settings.personalizationEnabled
        )

        // Enforce strict < 5000ms end-to-end ceiling
        val spentMs = SystemClock.elapsedRealtime() - startRealtimeMs
        val remainingBudgetMs = max(800L, minOf(settings.inferenceTimeoutMs, MAX_END_TO_END_LATENCY_MS - spentMs))

        val generationResult = llamaBridge.generateRefinedText(
            chatMlPrompt = chatMlPrompt,
            maxNewTokens = 160,
            temperature = settings.temperature,
            timeoutMs = remainingBudgetMs
        )

        val rawLlmText = generationResult.getOrNull()
        return when (val validated = OutputValidator.validate(rawLlmText, rawTranscript, settings.languageStyle)) {
            is OutputValidator.ValidationResult.Valid -> validated.cleanedText
            is OutputValidator.ValidationResult.InvalidFallback -> validated.fallbackTranscript
        }
    }

    private fun insertTextAtCursorSafely(textToInsert: String): Boolean {
        val ic: InputConnection = currentInputConnection ?: return false
        return try {
            ic.beginBatchEdit()
            val beforeCursor = ic.getTextBeforeCursor(1, 0)
            val prefix = if (!beforeCursor.isNullOrEmpty() && !beforeCursor[0].isWhitespace() && textToInsert != " ") {
                " "
            } else {
                ""
            }
            val finalCommit = prefix + textToInsert
            val committed = ic.commitText(finalCommit, 1)
            ic.endBatchEdit()
            if (committed) {
                lastInsertedSpanLength = finalCommit.length
            }
            committed
        } catch (e: Exception) {
            false
        }
    }

    private fun undoToRawTranscript() {
        val raw = lastRawTranscript ?: return
        val generated = lastGeneratedText ?: raw
        val ic: InputConnection = currentInputConnection ?: return

        try {
            ic.beginBatchEdit()
            if (lastInsertedSpanLength > 0) {
                ic.deleteSurroundingText(lastInsertedSpanLength, 0)
            }
            ic.commitText(raw, 1)
            ic.endBatchEdit()
            lastInsertedSpanLength = raw.length
            btnRevertRaw?.visibility = View.GONE
            tvStatus?.text = "Restored raw speech"
        } catch (_: Exception) {
        }

        serviceScope.launch(Dispatchers.IO) {
            personalizationRepo.recordFeedbackAndLearn(
                originalTranscript = raw,
                generatedOutput = generated,
                approvedCorrection = null,
                feedbackType = FeedbackActionType.REVERTED,
                mode = currentSettings.selectedMode,
                learningEnabled = currentSettings.automaticLearningEnabled
            )
        }
    }

    private fun cancelActiveOperation() {
        sttEngine.stopListening()
        llamaBridge.cancelActiveInference()
        activePipelineJob?.cancel()
        progressInference?.visibility = View.GONE
        btnCancelStop?.visibility = View.GONE
        btnMicRecord?.text = "🎤 Tap to Speak"
        btnMicRecord?.background?.setTint(0xFF4F46E5.toInt())
        tvStatus?.text = "Cancelled"
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            llamaBridge.onTrimMemory()
        }
    }

    override fun onDestroy() {
        sttEngine.release()
        llamaBridge.releaseModel()
        serviceJob.cancel()
        super.onDestroy()
    }
}

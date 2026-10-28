package com.flowwrite.ai.llm

import android.content.Context
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

sealed class ModelLoadStatus {
    object Unloaded : ModelLoadStatus()
    data class Loading(val fileName: String) : ModelLoadStatus()
    data class Ready(
        val filePath: String,
        val fileSizeBytes: Long,
        val contextSize: Int,
        val threads: Int
    ) : ModelLoadStatus()
    data class Error(val message: String) : ModelLoadStatus()
}

data class DiscoveredLocalModel(
    val absolutePath: String,
    val fileName: String,
    val sizeBytes: Long,
    val sourceDescription: String,
    val isReadableAndValidGguf: Boolean
)

/**
 * Section 4: Local Model Discovery & llama.cpp JNI Bridge.
 * Inspects accessible app-private and shared directories for existing Qwen2-family GGUF models
 * so the user does not have to download a second copy unnecessarily, while respecting Android
 * app sandboxing and storage permissions.
 */
class LlamaBridge(private val context: Context) {

    companion object {
        const val RECOMMENDED_MODEL_FILENAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"

        private var nativeLibLoaded = false

        init {
            try {
                System.loadLibrary("flowwrite_jni")
                nativeLibLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                nativeLibLoaded = false
            }
        }
    }

    private val loadMutex = Mutex()

    @Volatile
    var status: ModelLoadStatus = ModelLoadStatus.Unloaded
        private set

    private var loadedModelPath: String? = null
    private var loadedCtxSize: Int = 0
    private var loadedThreads: Int = 0

    private external fun nativeIsLlamaLinked(): Boolean
    private external fun nativeGetLastError(): String
    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Boolean
    private external fun nativeGenerate(prompt: String, maxNewTokens: Int, temperature: Float): String
    private external fun nativeCancelInference()
    private external fun nativeClearKvCache()
    private external fun nativeReleaseModel()

    fun isNativeLibraryAvailable(): Boolean =
        nativeLibLoaded && runCatching { nativeIsLlamaLinked() }.getOrDefault(false)

    /**
     * Section 4: Inspects accessible device storage locations for an existing Qwen2-family `.gguf` file
     * before asking the user to import or copy one:
     * 1. App-private internal storage: `context.filesDir/models`
     * 2. App-specific external storage: `context.getExternalFilesDir(null)`
     * 3. Public Download / Documents directories if readable under current Android permissions
     *
     * Note on Android Sandboxing: Files stored inside *another* app's private `/data/data/<other.pkg>/`
     * directory cannot be accessed directly due to Linux UID isolation; users can select models from
     * shared storage once via the system file picker (SAF).
     */
    suspend fun discoverAccessibleLocalModels(): List<DiscoveredLocalModel> = withContext(Dispatchers.IO) {
        val candidateDirs = listOfNotNull(
            File(context.filesDir, "models") to "App-Private Internal Storage",
            context.filesDir to "App-Private Root",
            context.getExternalFilesDir(null) to "App External Storage",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) to "Shared Downloads",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS) to "Shared Documents"
        )

        val discovered = mutableListOf<DiscoveredLocalModel>()
        val seenPaths = mutableSetOf<String>()

        for ((dir, label) in candidateDirs) {
            if (!dir.exists() || !dir.canRead()) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (file.isFile && file.name.endsWith(".gguf", ignoreCase = true)) {
                    val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
                    if (seenPaths.add(canonical)) {
                        val valid = hasValidGgufHeader(file)
                        discovered.add(
                            DiscoveredLocalModel(
                                absolutePath = file.absolutePath,
                                fileName = file.name,
                                sizeBytes = file.length(),
                                sourceDescription = label,
                                isReadableAndValidGguf = valid
                            )
                        )
                    }
                }
            }
        }

        // Prioritize Qwen2.5 / Qwen2 models first
        discovered.sortedByDescending {
            it.isReadableAndValidGguf && it.fileName.contains("qwen", ignoreCase = true)
        }
    }

    suspend fun importGgufModelFromUri(sourceUri: Uri, targetFileName: String = RECOMMENDED_MODEL_FILENAME): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
                val destFile = File(modelsDir, targetFileName)

                context.contentResolver.openInputStream(sourceUri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, bufferSize = 64 * 1024)
                    }
                } ?: throw IllegalStateException("Unable to open selected GGUF file stream.")

                if (!hasValidGgufHeader(destFile)) {
                    destFile.delete()
                    throw IllegalArgumentException("Selected file does not have a valid 'GGUF' binary header.")
                }

                destFile
            }
        }

    fun hasValidGgufHeader(file: File): Boolean {
        if (!file.exists() || !file.isFile || file.canRead().not() || file.length() < 64L) return false
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                if (fis.read(header) != 4) return false
                header[0] == 'G'.code.toByte() &&
                    header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun ensureModelLoaded(
        modelPath: String,
        contextWindowTokens: Int = 1024,
        cpuThreads: Int = 4
    ): Result<ModelLoadStatus.Ready> = withContext(Dispatchers.IO) {
        loadMutex.withLock {
            val current = status
            if (current is ModelLoadStatus.Ready &&
                loadedModelPath == modelPath &&
                loadedCtxSize == contextWindowTokens &&
                loadedThreads == cpuThreads
            ) {
                return@withContext Result.success(current)
            }

            val file = File(modelPath)
            if (!hasValidGgufHeader(file)) {
                val err = ModelLoadStatus.Error("Model file missing or invalid GGUF header: $modelPath")
                status = err
                return@withContext Result.failure(IllegalArgumentException(err.message))
            }

            if (!nativeLibLoaded) {
                val err = ModelLoadStatus.Error("Native library libflowwrite_jni.so is not loaded.")
                status = err
                return@withContext Result.failure(IllegalStateException(err.message))
            }

            status = ModelLoadStatus.Loading(file.name)
            val ok = nativeLoadModel(modelPath, contextWindowTokens, cpuThreads)
            if (!ok) {
                val nativeErr = nativeGetLastError().ifBlank { "Failed to initialize llama.cpp model." }
                val err = ModelLoadStatus.Error(nativeErr)
                status = err
                return@withContext Result.failure(IllegalStateException(nativeErr))
            }

            loadedModelPath = modelPath
            loadedCtxSize = contextWindowTokens
            loadedThreads = cpuThreads

            val ready = ModelLoadStatus.Ready(
                filePath = modelPath,
                fileSizeBytes = file.length(),
                contextSize = contextWindowTokens,
                threads = cpuThreads
            )
            status = ready
            Result.success(ready)
        }
    }

    suspend fun generateRefinedText(
        chatMlPrompt: String,
        maxNewTokens: Int = 256,
        temperature: Float = 0.2f,
        timeoutMs: Long = 3000L
    ): Result<String> = withContext(Dispatchers.IO) {
        if (status !is ModelLoadStatus.Ready) {
            return@withContext Result.failure(IllegalStateException("Local GGUF model is not loaded."))
        }

        val output = withTimeoutOrNull(timeoutMs) {
            nativeGenerate(chatMlPrompt, maxNewTokens, temperature)
        }

        if (output == null) {
            nativeCancelInference()
            return@withContext Result.failure(
                IllegalStateException("Local LLM inference timed out after ${timeoutMs}ms.")
            )
        }

        if (output.isBlank()) {
            val err = nativeGetLastError().ifBlank { "Model returned empty output." }
            return@withContext Result.failure(IllegalStateException(err))
        }

        Result.success(output)
    }

    fun cancelActiveInference() {
        if (nativeLibLoaded) {
            runCatching { nativeCancelInference() }
        }
    }

    fun onTrimMemory() {
        if (nativeLibLoaded) {
            runCatching { nativeClearKvCache() }
        }
    }

    fun releaseModel() {
        if (nativeLibLoaded) {
            runCatching { nativeReleaseModel() }
        }
        loadedModelPath = null
        status = ModelLoadStatus.Unloaded
    }
}

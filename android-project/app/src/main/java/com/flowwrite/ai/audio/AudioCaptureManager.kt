package com.flowwrite.ai.audio

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Section 2 & Section 10: Audio capture layer using Android's AudioRecord API.
 * Captures 16 kHz mono 16-bit PCM audio frames for local offline STT engines
 * (such as Sherpa-ONNX or Whisper.cpp) after verifying RECORD_AUDIO permission.
 */
class AudioCaptureManager(private val context: Context) {

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private val isRecordingAtomic = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null

    fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isRecording(): Boolean = isRecordingAtomic.get()

    @SuppressLint("MissingPermission")
    suspend fun recordPcmAudioUntilStopped(
        onAmplitudeChanged: ((Float) -> Unit)? = null
    ): Result<ShortArray> = withContext(Dispatchers.IO) {
        if (!hasMicrophonePermission()) {
            return@withContext Result.failure(
                SecurityException("RECORD_AUDIO permission has not been granted by the user.")
            )
        }

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_ENCODING
        )
        if (minBufferSize <= 0) {
            return@withContext Result.failure(
                IllegalStateException("Hardware audio recorder unavailable for 16kHz PCM mono.")
            )
        }

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            CHANNEL_CONFIG,
            AUDIO_ENCODING,
            minBufferSize * 2
        )

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withContext Result.failure(
                IllegalStateException("AudioRecord failed to initialize.")
            )
        }

        audioRecord = record
        val accumulatedSamples = ArrayList<Short>(SAMPLE_RATE_HZ * 10)
        val readBuffer = ShortArray(1024)

        try {
            record.startRecording()
            isRecordingAtomic.set(true)

            while (isActive && isRecordingAtomic.get()) {
                val readCount = record.read(readBuffer, 0, readBuffer.size)
                if (readCount > 0) {
                    var sumSq = 0.0
                    for (i in 0 until readCount) {
                        val s = readBuffer[i]
                        accumulatedSamples.add(s)
                        sumSq += (s * s).toDouble()
                    }
                    val rms = kotlin.math.sqrt(sumSq / readCount).toFloat() / Short.MAX_VALUE
                    onAmplitudeChanged?.invoke(rms)
                }
            }
            Result.success(accumulatedSamples.toShortArray())
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            isRecordingAtomic.set(false)
            runCatching {
                record.stop()
                record.release()
            }
            audioRecord = null
        }
    }

    fun stopRecording() {
        isRecordingAtomic.set(false)
    }
}

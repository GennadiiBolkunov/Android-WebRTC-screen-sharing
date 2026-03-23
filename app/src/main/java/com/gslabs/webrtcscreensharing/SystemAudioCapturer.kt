package com.gslabs.webrtcscreensharing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "SystemAudioCapturer"

/** Частота дискретизации: 48 кГц — стандарт для Opus/WebRTC */
const val AUDIO_SAMPLE_RATE = 48000

/** Моно для уменьшения трафика через DataChannel */
const val AUDIO_CHANNELS = 1
const val AUDIO_CHANNEL_MASK_IN = AudioFormat.CHANNEL_IN_MONO

/** 16-bit PCM */
const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT

/** Размер одного фрейма в семплах (20 мс @ 48kHz) — совпадает с Opus frame */
const val FRAME_SIZE_SAMPLES = 960 // 48000 * 0.020

/** Размер одного фрейма в байтах (моно, 16-bit) */
const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 * AUDIO_CHANNELS

/**
 * Callback для получения аудио-фреймов.
 * Вызывается из рабочего потока, НЕ из main thread.
 *
 * @param data      — Opus-пакет (если encodeOpus=true) или PCM S16LE (если false)
 * @param sizeBytes — фактическое количество байт
 */
typealias AudioFrameCallback = (data: ByteArray, sizeBytes: Int) -> Unit

@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCapturer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val enableMic: Boolean = false,
    private val encodeOpus: Boolean = true,
    private val onFrame: AudioFrameCallback,
    private val onError: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)

    private var systemAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private var opusEncoder: OpusEncoder? = null
    private var captureThread: Thread? = null

    fun start(): Boolean {
        if (running.getAndSet(true)) {
            onError("Захват уже запущен")
            return false
        }

        // --- Opus кодирование ---
        if (encodeOpus) {
            val encoder = OpusEncoder(AUDIO_SAMPLE_RATE, AUDIO_CHANNELS)
            if (encoder.start()) {
                opusEncoder = encoder
                Log.i(TAG, "Opus encoding enabled (${OpusEncoder.OPUS_BITRATE_BPS / 1000} kbps)")
            } else {
                Log.w(TAG, "Opus encoder unavailable, falling back to raw PCM")
            }
        }

        // --- Системное аудио через AudioPlaybackCapture ---
        val systemRecord = try {
            createSystemAudioRecord()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось создать AudioRecord для системного аудио", e)
            onError("AudioPlaybackCapture недоступен: ${e.message}")
            opusEncoder?.stop(); opusEncoder = null
            running.set(false)
            return false
        }

        if (systemRecord.state != AudioRecord.STATE_INITIALIZED) {
            onError("AudioRecord для системного аудио не инициализирован")
            systemRecord.release()
            opusEncoder?.stop(); opusEncoder = null
            running.set(false)
            return false
        }
        systemAudioRecord = systemRecord

        // --- Микрофон (опционально) ---
        var micRecord: AudioRecord? = null
        if (enableMic) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
            ) {
                micRecord = try {
                    createMicAudioRecord()
                } catch (e: Exception) {
                    Log.w(TAG, "Микрофон недоступен", e)
                    onError("Микрофон недоступен: ${e.message}")
                    null
                }
                if (micRecord != null && micRecord.state != AudioRecord.STATE_INITIALIZED) {
                    micRecord.release(); micRecord = null
                }
            }
        }
        micAudioRecord = micRecord

        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            captureLoop(systemRecord, micRecord)
        }, "SystemAudioCaptureThread").also { it.start() }

        Log.i(TAG, "Захват запущен (system=true, mic=${micRecord != null}, opus=${opusEncoder != null})")
        return true
    }

    /** true если Opus-кодирование активно (определяется после start()) */
    val isOpusEncoding: Boolean get() = opusEncoder != null

    fun stop() {
        if (!running.getAndSet(false)) return
        captureThread?.interrupt()
        captureThread?.join(2000)
        captureThread = null

        systemAudioRecord?.let { safeRelease(it, "system") }
        micAudioRecord?.let { safeRelease(it, "mic") }
        systemAudioRecord = null
        micAudioRecord = null

        opusEncoder?.stop()
        opusEncoder = null
        Log.i(TAG, "Захват остановлен")
    }

    // ─────────────────────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createSystemAudioRecord(): AudioRecord {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AUDIO_ENCODING)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AUDIO_CHANNEL_MASK_IN)
            .build()

        val minBufSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_MASK_IN, AUDIO_ENCODING)
        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(maxOf(minBufSize, FRAME_SIZE_BYTES * 4))
            .build()
    }

    @Suppress("MissingPermission")
    private fun createMicAudioRecord(): AudioRecord {
        val minBufSize = AudioRecord.getMinBufferSize(AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_MASK_IN, AUDIO_ENCODING)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_MASK_IN, AUDIO_ENCODING,
            maxOf(minBufSize, FRAME_SIZE_BYTES * 4)
        )
    }

    private fun captureLoop(systemRec: AudioRecord, micRec: AudioRecord?) {
        val systemBuf = ByteArray(FRAME_SIZE_BYTES)
        val micBuf = if (micRec != null) ByteArray(FRAME_SIZE_BYTES) else null
        val mixBuf = if (micRec != null) ByteArray(FRAME_SIZE_BYTES) else null
        val encoder = opusEncoder

        try {
            systemRec.startRecording()
            micRec?.startRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска AudioRecord", e)
            onError("Ошибка запуска записи: ${e.message}")
            return
        }

        while (running.get() && !Thread.currentThread().isInterrupted) {
            val systemRead = systemRec.read(systemBuf, 0, FRAME_SIZE_BYTES)
            if (systemRead < 0) {
                Log.e(TAG, "Ошибка чтения системного аудио: $systemRead")
                onError("Ошибка чтения системного аудио: code=$systemRead")
                break
            }
            if (systemRead == 0) continue

            val pcmData: ByteArray
            val pcmSize: Int

            if (micRec != null && micBuf != null && mixBuf != null) {
                val micRead = micRec.read(micBuf, 0, FRAME_SIZE_BYTES)
                if (micRead > 0) {
                    val s = minOf(systemRead, micRead)
                    mixPcm16(systemBuf, micBuf, mixBuf, s)
                    pcmData = mixBuf; pcmSize = s
                } else {
                    pcmData = systemBuf; pcmSize = systemRead
                }
            } else {
                pcmData = systemBuf; pcmSize = systemRead
            }

            if (encoder != null) {
                encoder.encode(pcmData, pcmSize) { opusPacket, opusSize ->
                    onFrame(opusPacket, opusSize)
                }
            } else {
                onFrame(pcmData, pcmSize)
            }
        }

        safeStop(systemRec, "system")
        if (micRec != null) safeStop(micRec, "mic")
    }

    private fun mixPcm16(a: ByteArray, b: ByteArray, out: ByteArray, sizeBytes: Int) {
        val bbA = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        val bbB = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val bbOut = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val samples = sizeBytes / 2
        for (i in 0 until samples) {
            val sA = bbA.getShort(i * 2).toInt()
            val sB = bbB.getShort(i * 2).toInt()
            bbOut.putShort(i * 2, (sA + sB).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
        }
    }

    private fun safeStop(record: AudioRecord, label: String) {
        try { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
        catch (e: Exception) { Log.w(TAG, "Ошибка остановки AudioRecord ($label)", e) }
    }

    private fun safeRelease(record: AudioRecord, label: String) {
        try { safeStop(record, label); record.release() }
        catch (e: Exception) { Log.w(TAG, "Ошибка освобождения AudioRecord ($label)", e) }
    }
}
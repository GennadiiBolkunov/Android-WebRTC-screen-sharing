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

/**
 * Захват системного аудио через AudioPlaybackCapture API (Android 10+).
 *
 * АРХИТЕКТУРА АУДИО В WebRTC
 * ==========================
 * Стандартный WebRTC Android SDK (google-webrtc AAR) НЕ поддерживает инъекцию
 * произвольных PCM-данных в аудио-трек. JavaAudioDeviceModule жёстко привязан
 * к AudioRecord с микрофона.
 *
 * Для прототипа: системное аудио захватывается этим классом и передаётся
 * на STB через WebRTC DataChannel (бинарные PCM-фреймы).
 * STB декодирует и воспроизводит их параллельно с WebRTC аудио-треком (микрофон).
 *
 * Для продакшена: необходимо собрать libwebrtc из исходников с кастомным
 * AudioDeviceModule, который будет микшировать системное аудио и микрофон
 * в единый RTP-поток.
 *
 * ОГРАНИЧЕНИЯ AudioPlaybackCapture (Android 10+):
 * - Приложения могут запретить захват через setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)
 * - Захватывается только аудио с USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN
 * - Требуется активная MediaProjection с foreground service
 */

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
 * Callback для получения PCM-фреймов.
 * Вызывается из рабочего потока, НЕ из main thread.
 *
 * @param pcmData   — 16-bit PCM little-endian, [AUDIO_CHANNELS] каналов, [AUDIO_SAMPLE_RATE] Hz
 * @param sizeBytes — фактическое количество байт в массиве
 */
typealias AudioFrameCallback = (pcmData: ByteArray, sizeBytes: Int) -> Unit

@RequiresApi(Build.VERSION_CODES.Q)
class SystemAudioCapturer(
    private val context: Context,
    private val mediaProjection: MediaProjection,
    private val enableMic: Boolean = false,
    private val onFrame: AudioFrameCallback,
    private val onError: (String) -> Unit = {}
) {
    private val running = AtomicBoolean(false)

    private var systemAudioRecord: AudioRecord? = null
    private var micAudioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    /**
     * Запускает захват. Возвращает true, если захват успешно стартовал.
     */
    fun start(): Boolean {
        if (running.getAndSet(true)) {
            onError("Захват уже запущен")
            return false
        }

        // --- Системное аудио через AudioPlaybackCapture ---
        val systemRecord = try {
            createSystemAudioRecord()
        } catch (e: Exception) {
            Log.e(TAG, "Не удалось создать AudioRecord для системного аудио", e)
            onError("AudioPlaybackCapture недоступен: ${e.message}")
            running.set(false)
            return false
        }

        if (systemRecord.state != AudioRecord.STATE_INITIALIZED) {
            onError("AudioRecord для системного аудио не инициализирован (state=${systemRecord.state})")
            systemRecord.release()
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
                } catch (e: SecurityException) {
                    Log.w(TAG, "Нет разрешения на микрофон", e)
                    onError("Микрофон недоступен: нет разрешения RECORD_AUDIO")
                    null
                } catch (e: Exception) {
                    Log.w(TAG, "Не удалось создать AudioRecord для микрофона", e)
                    onError("Микрофон недоступен: ${e.message}")
                    null
                }

                if (micRecord != null && micRecord.state != AudioRecord.STATE_INITIALIZED) {
                    onError("AudioRecord для микрофона не инициализирован")
                    micRecord.release()
                    micRecord = null
                }
            } else {
                onError("Микрофон пропущен: нет разрешения RECORD_AUDIO")
            }
        }
        micAudioRecord = micRecord

        // --- Запуск потока захвата ---
        captureThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            captureLoop(systemRecord, micRecord)
        }, "SystemAudioCaptureThread").also { it.start() }

        Log.i(TAG, "Захват запущен (system=true, mic=${micRecord != null})")
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return

        captureThread?.interrupt()
        captureThread?.join(2000)
        captureThread = null

        systemAudioRecord?.let { safeRelease(it, "system") }
        micAudioRecord?.let { safeRelease(it, "mic") }
        systemAudioRecord = null
        micAudioRecord = null

        Log.i(TAG, "Захват остановлен")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Внутренняя реализация
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

        val minBufSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_MASK_IN, AUDIO_ENCODING
        )
        // Буфер ≥ 4 фрейма для устойчивости
        val bufferSize = maxOf(minBufSize, FRAME_SIZE_BYTES * 4)

        return AudioRecord.Builder()
            .setAudioPlaybackCaptureConfig(captureConfig)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    @Suppress("MissingPermission") // разрешение проверяется выше
    private fun createMicAudioRecord(): AudioRecord {
        val minBufSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_MASK_IN, AUDIO_ENCODING
        )
        val bufferSize = maxOf(minBufSize, FRAME_SIZE_BYTES * 4)

        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AUDIO_SAMPLE_RATE,
            AUDIO_CHANNEL_MASK_IN,
            AUDIO_ENCODING,
            bufferSize
        )
    }

    /**
     * Основной цикл: читаем системное аудио, опционально микшируем с микрофоном,
     * отправляем PCM-фрейм в callback.
     */
    private fun captureLoop(systemRec: AudioRecord, micRec: AudioRecord?) {
        val systemBuf = ByteArray(FRAME_SIZE_BYTES)
        val micBuf = if (micRec != null) ByteArray(FRAME_SIZE_BYTES) else null
        val mixBuf = if (micRec != null) ByteArray(FRAME_SIZE_BYTES) else null

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

            if (micRec != null && micBuf != null && mixBuf != null) {
                val micRead = micRec.read(micBuf, 0, FRAME_SIZE_BYTES)
                if (micRead > 0) {
                    mixPcm16(systemBuf, micBuf, mixBuf, minOf(systemRead, micRead))
                    onFrame(mixBuf, minOf(systemRead, micRead))
                } else {
                    // Микрофон не вернул данные — отправляем только системное аудио
                    onFrame(systemBuf, systemRead)
                }
            } else {
                onFrame(systemBuf, systemRead)
            }
        }

        safeStop(systemRec, "system")
        if (micRec != null) safeStop(micRec, "mic")
    }

    /**
     * Микширование двух 16-bit PCM буферов с клиппингом.
     * Результат записывается в [out].
     */
    private fun mixPcm16(a: ByteArray, b: ByteArray, out: ByteArray, sizeBytes: Int) {
        val bbA = ByteBuffer.wrap(a).order(ByteOrder.LITTLE_ENDIAN)
        val bbB = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        val bbOut = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)

        val samples = sizeBytes / 2
        for (i in 0 until samples) {
            val sA = bbA.getShort(i * 2).toInt()
            val sB = bbB.getShort(i * 2).toInt()
            // Суммируем и клиппируем до Short.MIN_VALUE..Short.MAX_VALUE
            val mixed = (sA + sB).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            bbOut.putShort(i * 2, mixed.toShort())
        }
    }

    private fun safeStop(record: AudioRecord, label: String) {
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка остановки AudioRecord ($label)", e)
        }
    }

    private fun safeRelease(record: AudioRecord, label: String) {
        try {
            safeStop(record, label)
            record.release()
        } catch (e: Exception) {
            Log.w(TAG, "Ошибка освобождения AudioRecord ($label)", e)
        }
    }
}
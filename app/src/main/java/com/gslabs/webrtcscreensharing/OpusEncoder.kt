package com.gslabs.webrtcscreensharing

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

/**
 * Кодирует 16-bit PCM в Opus через Android MediaCodec.
 *
 * Использует синхронный режим MediaCodec для минимальной задержки
 * и простоты интеграции с потоком захвата аудио.
 *
 * На выходе — сырые Opus-пакеты (без контейнера OGG/WebM),
 * совместимые с opus_decode() на STB.
 *
 * Доступен на Android 10+ (API 29) как software кодек c2.android.opus.encoder.
 */
class OpusEncoder(
    private val sampleRate: Int = AUDIO_SAMPLE_RATE,
    private val channels: Int = AUDIO_CHANNELS,
    private val bitrate: Int = OPUS_BITRATE_BPS
) {
    companion object {
        private const val TAG = "OpusEncoder"
        private const val MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        const val OPUS_BITRATE_BPS = 64_000

        // MediaCodec timeout для dequeueBuffer (мкс).
        // 10мс — достаточно для синхронного режима в реальном времени.
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private val bufferInfo = MediaCodec.BufferInfo()

    /**
     * Инициализирует кодек. Вызывать перед encode().
     * @return true если кодек создан и запущен
     */
    fun start(): Boolean {
        try {
            val format = MediaFormat.createAudioFormat(MIME, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                // Opus frame = 20ms
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_SIZE_BYTES)
            }

            codec = MediaCodec.createEncoderByType(MIME).also { mc ->
                mc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                mc.start()
            }

            Log.i(TAG, "Opus encoder started: ${sampleRate}Hz ${channels}ch ${bitrate}bps")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create Opus encoder", e)
            codec = null
            return false
        }
    }

    /**
     * Кодирует один PCM-фрейм (20мс) в Opus.
     *
     * @param pcmData  — 16-bit PCM LE, [channels] каналов, [sampleRate] Hz
     * @param size     — размер данных в байтах
     * @param callback — вызывается с каждым готовым Opus-пакетом (может быть 0 или 1 раз за вызов)
     */
    fun encode(pcmData: ByteArray, size: Int, callback: (opusPacket: ByteArray, packetSize: Int) -> Unit) {
        val mc = codec ?: return

        // --- Подать PCM на вход ---
        val inIdx = mc.dequeueInputBuffer(TIMEOUT_US)
        if (inIdx >= 0) {
            val inBuf = mc.getInputBuffer(inIdx) ?: return
            inBuf.clear()
            val toCopy = minOf(size, inBuf.remaining())
            inBuf.put(pcmData, 0, toCopy)
            mc.queueInputBuffer(inIdx, 0, toCopy, 0, 0)
        }

        // --- Забрать Opus с выхода ---
        drainOutput(mc, callback)
    }

    /**
     * Вычитывает все готовые Opus-пакеты из выхода кодека.
     */
    private fun drainOutput(mc: MediaCodec, callback: (ByteArray, Int) -> Unit) {
        while (true) {
            val outIdx = mc.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            when {
                outIdx >= 0 -> {
                    if (bufferInfo.size > 0) {
                        val outBuf = mc.getOutputBuffer(outIdx) ?: break
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)

                        val opusPacket = ByteArray(bufferInfo.size)
                        outBuf.get(opusPacket)
                        callback(opusPacket, opusPacket.size)
                    }
                    mc.releaseOutputBuffer(outIdx, false)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "Output format changed: ${mc.outputFormat}")
                }
                else -> break // INFO_TRY_AGAIN_LATER
            }
        }
    }

    /**
     * Останавливает и освобождает кодек.
     */
    fun stop() {
        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping encoder", e)
        }
        try {
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing encoder", e)
        }
        codec = null
        Log.i(TAG, "Opus encoder stopped")
    }
}
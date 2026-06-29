package com.sherpacaption.app.capture

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.SystemClock
import android.util.Log
import com.sherpacaption.app.audio.AudioCaptureConfig
import com.sherpacaption.app.audio.AudioLevelMeter
import com.sherpacaption.app.audio.AudioLevelStats
import com.sherpacaption.app.audio.SilenceGate
import com.sherpacaption.app.audio.SilenceTransition
import com.sherpacaption.app.util.LogTags
import java.util.concurrent.atomic.AtomicBoolean

class PlaybackAudioCapture(
    private val mediaProjection: MediaProjection,
    private val listener: Listener
) {
    private val isRunning = AtomicBoolean(false)
    private val silenceGate = SilenceGate()
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    fun start() {
        if (!isRunning.compareAndSet(false, true)) {
            Log.i(LogTags.SHERPA_CAPTION, "PlaybackAudioCapture is already running")
            return
        }

        val preparedCapture = runCatching { createAudioRecord() }
            .onFailure { error ->
                isRunning.set(false)
                val message = "AudioRecord initialization failed: ${error.message}"
                Log.e(LogTags.SHERPA_CAPTION, message, error)
                listener.onError(message)
            }
            .getOrNull() ?: return

        audioRecord = preparedCapture.audioRecord
        silenceGate.reset()
        captureThread = Thread(
            { readAudioLoop(preparedCapture) },
            "PlaybackAudioCapture"
        ).also { it.start() }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        runCatching { audioRecord?.stop() }
            .onFailure { Log.w(LogTags.SHERPA_CAPTION, "AudioRecord stop failed", it) }
        captureThread?.join(STOP_JOIN_TIMEOUT_MS)
        captureThread = null
        audioRecord?.release()
        audioRecord = null
        Log.i(LogTags.SHERPA_CAPTION, "PlaybackAudioCapture stopped")
    }

    private fun readAudioLoop(preparedCapture: PreparedCapture) {
        val record = preparedCapture.audioRecord
        val config = preparedCapture.config
        val buffer = ShortArray(config.bufferSizeBytes / BYTES_PER_SHORT)
        var lastUpdateTime = 0L

        try {
            record.startRecording()
            listener.onAudioCaptureStarted()
            Log.i(
                LogTags.SHERPA_CAPTION,
                "AudioRecord started: rate=${config.sampleRate}, " +
                    "channels=${config.channelLabel}, buffer=${config.bufferSizeBytes}"
            )

            while (isRunning.get()) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) {
                    val message = "read error: $read"
                    Log.w(LogTags.SHERPA_CAPTION, message)
                    listener.onReadError(message)
                    SystemClock.sleep(config.updateIntervalMs)
                    continue
                }

                val now = SystemClock.elapsedRealtime()
                val stats = AudioLevelMeter.calculate(buffer, read, config)
                val silenceResult = silenceGate.evaluate(stats, now)

                when (silenceResult.transition) {
                    SilenceTransition.SILENCE_DETECTED -> {
                        Log.i(LogTags.SHERPA_CAPTION, "Silence detected")
                        listener.onSilenceDetected()
                    }
                    SilenceTransition.SPEECH_RESUMED -> {
                        Log.i(LogTags.SHERPA_CAPTION, "Speech resumed")
                        listener.onSpeechResumed()
                    }
                    SilenceTransition.NONE -> Unit
                }

                if (!listener.isPcmInputFlowing()) {
                    Log.d(
                        LogTags.SHERPA_CAPTION,
                        "PCM direct feed continues while inputState=PAUSED read=${stats.read} " +
                            "avg=${stats.averageAbsolute} max=${stats.maxAbsolute}"
                    )
                }
                listener.onPcmAudio(buffer, read, config)

                if (now - lastUpdateTime >= config.updateIntervalMs) {
                    listener.onAudioLevel(stats)
                    lastUpdateTime = now
                }
            }
        } catch (error: Throwable) {
            val message =
                "Audio capture thread failed: ${error.message ?: error.javaClass.simpleName}"
            Log.e(LogTags.SHERPA_CAPTION, message, error)
            runCatching { listener.onError(message) }
                .onFailure {
                    Log.e(LogTags.SHERPA_CAPTION, "Audio error callback failed", it)
                }
        } finally {
            isRunning.set(false)
            runCatching { listener.onAudioCaptureStopped() }
                .onFailure {
                    Log.e(LogTags.SHERPA_CAPTION, "Audio stopped callback failed", it)
                }
            runCatching { record.stop() }
            record.release()
            if (audioRecord === record) {
                audioRecord = null
            }
        }
    }

    private fun createAudioRecord(): PreparedCapture {
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val sampleRates = listOf(48_000, 44_100, 16_000)
        val channelMasks = listOf(AudioFormat.CHANNEL_IN_STEREO, AudioFormat.CHANNEL_IN_MONO)
        val failures = mutableListOf<String>()

        for (sampleRate in sampleRates) {
            for (channelMask in channelMasks) {
                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate,
                    channelMask,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBufferSize <= 0) {
                    failures += "rate=$sampleRate channel=$channelMask minBuffer=$minBufferSize"
                    continue
                }

                val bufferSize = minBufferSize * BUFFER_SIZE_MULTIPLIER
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .build()

                val record = runCatching {
                    AudioRecord.Builder()
                        .setAudioPlaybackCaptureConfig(playbackConfig)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                }.onFailure {
                    failures += "rate=$sampleRate channel=$channelMask error=${it.message}"
                }.getOrNull() ?: continue

                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    return PreparedCapture(
                        audioRecord = record,
                        config = AudioCaptureConfig(
                            sampleRate = sampleRate,
                            channelMask = channelMask,
                            bufferSizeBytes = bufferSize
                        )
                    )
                }

                failures += "rate=$sampleRate channel=$channelMask state=${record.state}"
                record.release()
            }
        }

        error("No supported AudioRecord config. ${failures.joinToString("; ")}")
    }

    interface Listener {
        fun onPcmAudio(samples: ShortArray, sampleCount: Int, config: AudioCaptureConfig)
        fun onAudioLevel(stats: AudioLevelStats)
        fun onAudioCaptureStarted()
        fun onAudioCaptureStopped()
        fun onSilenceDetected()
        fun onSpeechResumed()
        fun isPcmInputFlowing(): Boolean
        fun onReadError(message: String)
        fun onError(message: String)
    }

    private data class PreparedCapture(
        val audioRecord: AudioRecord,
        val config: AudioCaptureConfig
    )

    companion object {
        private const val BYTES_PER_SHORT = 2
        private const val BUFFER_SIZE_MULTIPLIER = 4
        private const val STOP_JOIN_TIMEOUT_MS = 1_000L
    }
}

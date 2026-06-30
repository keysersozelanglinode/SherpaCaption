package com.sherpacaption.app.asr

import android.content.Context
import android.util.Log
import com.sherpacaption.app.audio.AudioCaptureConfig
import com.sherpacaption.app.sherpa.AsrInputPreparer
import com.sherpacaption.app.util.LogTags

class AsrManager(
    context: Context,
    private val listener: Listener
) : ZipformerBackend.Listener {
    private val appContext = context.applicationContext
    private var backend: AsrBackend? = null

    fun start() {
        if (backend != null) {
            return
        }

        backend = createBackend().also { selectedBackend ->
            Log.i(LogTags.SHERPA_CAPTION, "ASR_BACKEND=${AsrBackendConfig.ASR_BACKEND}")
            selectedBackend.start()
        }
    }

    fun acceptPcm16(
        samples: ShortArray,
        sampleCount: Int,
        config: AudioCaptureConfig
    ) {
        val preparedInput = runCatching {
            AsrInputPreparer.prepare(samples, sampleCount, config)
        }.onFailure { error ->
            listener.onError("ASR input failed: ${error.description()}")
        }.getOrNull() ?: return

        if (preparedInput.samples.isEmpty()) {
            return
        }

        backend?.acceptWaveform(preparedInput.samples)
    }

    fun markInputIdle() {
        (backend as? ZipformerBackend)?.markInputIdle()
    }

    fun markInputResumed() {
        (backend as? ZipformerBackend)?.markInputResumed()
    }

    fun release() {
        backend?.release()
        backend = null
    }

    override fun onResult(text: String, frames: Long) {
        listener.onResult(text, frames)
    }

    override fun onError(message: String) {
        listener.onError(message)
    }

    private fun createBackend(): AsrBackend {
        return when (AsrBackendConfig.ASR_BACKEND) {
            AsrBackendConfig.BACKEND_PARAKEET -> ParakeetBackend()
            AsrBackendConfig.BACKEND_ZIPFORMER -> ZipformerBackend(appContext, this)
            else -> ZipformerBackend(appContext, this)
        }
    }

    private fun Throwable.description(): String {
        return message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
    }

    interface Listener {
        fun onResult(text: String, frames: Long)
        fun onError(message: String)
    }
}

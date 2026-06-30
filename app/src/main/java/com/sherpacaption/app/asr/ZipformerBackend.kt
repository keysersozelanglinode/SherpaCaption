package com.sherpacaption.app.asr

import android.content.Context
import com.sherpacaption.app.sherpa.SherpaRecognizer
import java.util.concurrent.atomic.AtomicReference

class ZipformerBackend(
    private val context: Context,
    private val listener: Listener
) : AsrBackend, SherpaRecognizer.Listener {
    private val latestResult = AtomicReference("")
    private var recognizer: SherpaRecognizer? = null

    override fun start() {
        if (recognizer != null) {
            return
        }
        recognizer = SherpaRecognizer(
            context = context,
            listener = this
        )
    }

    override fun acceptWaveform(samples: FloatArray) {
        recognizer?.acceptWaveform(samples)
    }

    override fun getResult(): String = latestResult.get()

    override fun reset() {
        latestResult.set("")
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
        latestResult.set("")
    }

    fun markInputIdle() {
        recognizer?.markInputIdle()
    }

    fun markInputResumed() {
        recognizer?.markInputResumed()
    }

    override fun onResult(text: String, frames: Long) {
        latestResult.set(text)
        listener.onResult(text, frames)
    }

    override fun onError(message: String) {
        listener.onError(message)
    }

    interface Listener {
        fun onResult(text: String, frames: Long)
        fun onError(message: String)
    }
}

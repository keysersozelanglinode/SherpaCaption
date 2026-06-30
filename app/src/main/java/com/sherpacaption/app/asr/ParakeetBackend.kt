package com.sherpacaption.app.asr

import android.util.Log
import com.sherpacaption.app.util.LogTags

class ParakeetBackend : AsrBackend {
    private var isRunning = false

    override fun start() {
        isRunning = true
        Log.i(LogTags.SHERPA_CAPTION, "ParakeetBackend mock started")
    }

    override fun acceptWaveform(samples: FloatArray) = Unit

    override fun getResult(): String = ""

    override fun reset() {
        Log.i(LogTags.SHERPA_CAPTION, "ParakeetBackend mock reset")
    }

    override fun release() {
        if (!isRunning) {
            return
        }
        isRunning = false
        Log.i(LogTags.SHERPA_CAPTION, "ParakeetBackend mock released")
    }
}

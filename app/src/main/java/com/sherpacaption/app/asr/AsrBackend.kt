package com.sherpacaption.app.asr

interface AsrBackend {
    fun start()

    fun acceptWaveform(samples: FloatArray)

    fun getResult(): String

    fun reset()

    fun release()
}

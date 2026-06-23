package com.sherpacaption.app.audio

import android.media.AudioFormat

data class AudioCaptureConfig(
    val sampleRate: Int,
    val channelMask: Int,
    val bufferSizeBytes: Int,
    val updateIntervalMs: Long = 200L
) {
    val channelLabel: String
        get() = when (channelMask) {
            AudioFormat.CHANNEL_IN_STEREO -> "stereo"
            AudioFormat.CHANNEL_IN_MONO -> "mono"
            else -> "unknown"
        }
}

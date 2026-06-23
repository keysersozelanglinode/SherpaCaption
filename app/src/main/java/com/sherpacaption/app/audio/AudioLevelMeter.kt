package com.sherpacaption.app.audio

import kotlin.math.abs

object AudioLevelMeter {
    fun calculate(
        samples: ShortArray,
        sampleCount: Int,
        config: AudioCaptureConfig
    ): AudioLevelStats {
        if (sampleCount <= 0) {
            return AudioLevelStats(
                read = sampleCount,
                averageAbsolute = 0,
                maxAbsolute = 0,
                sampleRate = config.sampleRate,
                channelLabel = config.channelLabel
            )
        }

        var total = 0L
        var max = 0

        for (index in 0 until sampleCount) {
            val absolute = abs(samples[index].toInt())
            total += absolute
            if (absolute > max) {
                max = absolute
            }
        }

        return AudioLevelStats(
            read = sampleCount,
            averageAbsolute = (total / sampleCount).toInt(),
            maxAbsolute = max,
            sampleRate = config.sampleRate,
            channelLabel = config.channelLabel
        )
    }
}

data class AudioLevelStats(
    val read: Int,
    val averageAbsolute: Int,
    val maxAbsolute: Int,
    val sampleRate: Int,
    val channelLabel: String
) {
    fun toDebugText(): String {
        return "read=$read avg=$averageAbsolute max=$maxAbsolute " +
            "rate=$sampleRate channels=$channelLabel"
    }
}

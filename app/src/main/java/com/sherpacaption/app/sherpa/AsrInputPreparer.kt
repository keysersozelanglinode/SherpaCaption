package com.sherpacaption.app.sherpa

import com.sherpacaption.app.audio.AudioCaptureConfig
import kotlin.math.floor

object AsrInputPreparer {
    private const val TargetSampleRate = 16_000
    private const val ShortPcmMax = 32768f

    fun prepare(
        samples: ShortArray,
        sampleCount: Int,
        config: AudioCaptureConfig
    ): PreparedAsrInput {
        if (sampleCount <= 0) {
            return PreparedAsrInput(floatArrayOf(), TargetSampleRate)
        }

        val mono = toMono(samples, sampleCount, config.channelCount)
        val resampled = if (config.sampleRate == TargetSampleRate) {
            mono
        } else {
            resampleLinear(
                input = mono,
                sourceSampleRate = config.sampleRate,
                targetSampleRate = TargetSampleRate
            )
        }

        return PreparedAsrInput(resampled, TargetSampleRate)
    }

    private fun toMono(
        samples: ShortArray,
        sampleCount: Int,
        channelCount: Int
    ): FloatArray {
        if (channelCount <= 1) {
            return FloatArray(sampleCount) { index ->
                samples[index].toFloat() / ShortPcmMax
            }
        }

        val frameCount = sampleCount / channelCount
        return FloatArray(frameCount) { frameIndex ->
            var sum = 0f
            val sampleOffset = frameIndex * channelCount
            for (channelIndex in 0 until channelCount) {
                sum += samples[sampleOffset + channelIndex].toFloat() / ShortPcmMax
            }
            sum / channelCount
        }
    }

    private fun resampleLinear(
        input: FloatArray,
        sourceSampleRate: Int,
        targetSampleRate: Int
    ): FloatArray {
        if (input.isEmpty() || sourceSampleRate <= 0) {
            return floatArrayOf()
        }

        val outputSize = (input.size.toLong() * targetSampleRate / sourceSampleRate)
            .coerceAtLeast(1)
            .toInt()
        val ratio = sourceSampleRate.toDouble() / targetSampleRate

        return FloatArray(outputSize) { outputIndex ->
            val sourcePosition = outputIndex * ratio
            val lowerIndex = floor(sourcePosition).toInt().coerceIn(0, input.lastIndex)
            val upperIndex = (lowerIndex + 1).coerceAtMost(input.lastIndex)
            val fraction = (sourcePosition - lowerIndex).toFloat()
            input[lowerIndex] * (1f - fraction) + input[upperIndex] * fraction
        }
    }
}

data class PreparedAsrInput(
    val samples: FloatArray,
    val sampleRate: Int
)

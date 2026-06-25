package com.sherpacaption.app.audio

class SilenceGate(
    private val lowAverageThreshold: Int = 30,
    private val lowMaxThreshold: Int = 300,
    private val silenceDelayMs: Long = 500L
) {
    private var lowLevelStartedAt = 0L
    private var silent = false

    fun evaluate(
        stats: AudioLevelStats,
        nowMs: Long
    ): SilenceGateResult {
        val absoluteSilence =
            stats.averageAbsolute == 0 || stats.maxAbsolute == 0
        val lowLevel =
            stats.averageAbsolute < lowAverageThreshold &&
                stats.maxAbsolute < lowMaxThreshold

        if (!lowLevel) {
            lowLevelStartedAt = 0L
            val speechResumed = silent
            silent = false
            return SilenceGateResult(
                shouldFeed = true,
                transition = if (speechResumed) {
                    SilenceTransition.SPEECH_RESUMED
                } else {
                    SilenceTransition.NONE
                }
            )
        }

        if (lowLevelStartedAt == 0L) {
            lowLevelStartedAt = nowMs
        }
        val silenceDetected =
            !silent && nowMs - lowLevelStartedAt >= silenceDelayMs
        if (silenceDetected) {
            silent = true
        }

        return SilenceGateResult(
            shouldFeed = !absoluteSilence && !silent,
            transition = if (silenceDetected) {
                SilenceTransition.SILENCE_DETECTED
            } else {
                SilenceTransition.NONE
            }
        )
    }

    fun reset() {
        lowLevelStartedAt = 0L
        silent = false
    }
}

data class SilenceGateResult(
    val shouldFeed: Boolean,
    val transition: SilenceTransition
)

enum class SilenceTransition {
    NONE,
    SILENCE_DETECTED,
    SPEECH_RESUMED
}

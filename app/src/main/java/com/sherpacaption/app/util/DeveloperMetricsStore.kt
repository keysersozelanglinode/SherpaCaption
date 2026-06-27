package com.sherpacaption.app.util

import android.os.Debug
import java.util.concurrent.atomic.AtomicReference

object DeveloperMetricsStore {
    private val state = AtomicReference(DeveloperMetrics())

    fun update(transform: (DeveloperMetrics) -> DeveloperMetrics) {
        while (true) {
            val current = state.get()
            if (state.compareAndSet(current, transform(current))) {
                return
            }
        }
    }

    fun snapshot(): DeveloperMetrics {
        val current = state.get()
        val runtime = Runtime.getRuntime()
        val memoryInfo = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return current.copy(
            appMemoryMb = memoryInfo.totalPss.kilobytesToMegabytes(),
            javaHeapUsedMb = (runtime.totalMemory() - runtime.freeMemory()).bytesToMegabytes(),
            nativeHeapUsedMb = Debug.getNativeHeapAllocatedSize().bytesToMegabytes()
        )
    }

    private fun Int.kilobytesToMegabytes(): Double = this / 1024.0

    private fun Long.bytesToMegabytes(): Double = this / (1024.0 * 1024.0)
}

data class DeveloperMetrics(
    val serviceRunning: Boolean = false,
    val audioCaptureRunning: Boolean = false,
    val asrState: AsrRuntimeState = AsrRuntimeState.STOPPED,
    val silent: Boolean = true,
    val appMemoryMb: Double = 0.0,
    val javaHeapUsedMb: Double = 0.0,
    val nativeHeapUsedMb: Double = 0.0,
    val asrFrames: Long = 0L,
    val pcmAverage: Int = 0,
    val pcmMax: Int = 0,
    val lastRecognitionTextTimeMs: Long = 0L,
    val overlayHidden: Boolean = true,
    val recognitionMode: String = "unknown",
    val hotwordsEnabled: Boolean = false
)

enum class AsrRuntimeState {
    STOPPED,
    INITIALIZING,
    RUNNING,
    ERROR
}

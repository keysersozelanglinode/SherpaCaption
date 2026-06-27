package com.sherpacaption.app.util

import android.util.Log

object ASRStateController {
    private val lock = Any()
    private var state = ASRSystemState()

    fun snapshot(): ASRSystemState = synchronized(lock) { state }

    fun reset() {
        update("reset") { ASRSystemState() }
    }

    fun startServiceInitializing() {
        update("service start initializing") {
            ASRSystemState(
                serviceRunning = true,
                audioCaptureRunning = false,
                asrState = AsrRuntimeState.INITIALIZING,
                inputState = PcmInputState.FLOWING,
                overlayHidden = true,
                recognitionMode = "initializing"
            )
        }
    }

    fun stopService(reason: String) {
        update("service stop reason=$reason") {
            it.copy(
                serviceRunning = false,
                audioCaptureRunning = false,
                asrState = AsrRuntimeState.STOPPED,
                inputState = PcmInputState.PAUSED,
                overlayHidden = true
            )
        }
    }

    fun setServiceRunning(running: Boolean) {
        update("service=$running") {
            it.copy(
                serviceRunning = running,
                inputState = if (running) PcmInputState.FLOWING else PcmInputState.PAUSED
            )
        }
    }

    fun setAudioCaptureRunning(running: Boolean) {
        update("audio=$running") { it.copy(audioCaptureRunning = running) }
    }

    fun setAsrState(asrState: AsrRuntimeState) {
        update("asr=$asrState") { it.copy(asrState = asrState) }
    }

    fun setInputState(inputState: PcmInputState, reason: String) {
        update("input=$inputState reason=$reason") { it.copy(inputState = inputState) }
    }

    fun setFrames(frames: Long) {
        update("frames=$frames") { it.copy(asrFrames = frames) }
    }

    fun setPcmLevel(average: Int, max: Int) {
        update("pcm avg=$average max=$max") {
            it.copy(pcmAverage = average, pcmMax = max)
        }
    }

    fun markRecognitionTextUpdated(timestampMs: Long) {
        update("recognitionText=$timestampMs") {
            it.copy(lastRecognitionTextTimeMs = timestampMs)
        }
    }

    fun setOverlayHidden(hidden: Boolean) {
        update("overlayHidden=$hidden") { it.copy(overlayHidden = hidden) }
    }

    fun setRecognitionConfig(
        recognitionMode: String,
        hotwordsEnabled: Boolean
    ) {
        update("recognitionConfig mode=$recognitionMode hotwords=$hotwordsEnabled") {
            it.copy(
                recognitionMode = recognitionMode,
                hotwordsEnabled = hotwordsEnabled
            )
        }
    }

    private fun update(
        reason: String,
        transform: (ASRSystemState) -> ASRSystemState
    ): ASRSystemState = synchronized(lock) {
        Log.d(LogTags.SHERPA_CAPTION, "STATE_UPDATE_BEGIN $reason")
        val next = transform(state)
        state = next
        syncDeveloperMetrics(next)
        Log.d(LogTags.SHERPA_CAPTION, "STATE_UPDATE_COMMIT $reason state=$next")
        logConsistency(next)
        next
    }

    private fun syncDeveloperMetrics(state: ASRSystemState) {
        DeveloperMetricsStore.update {
            it.copy(
                serviceRunning = state.serviceRunning,
                audioCaptureRunning = state.audioCaptureRunning,
                asrState = state.asrState,
                silent = state.inputState == PcmInputState.PAUSED,
                asrFrames = state.asrFrames,
                pcmAverage = state.pcmAverage,
                pcmMax = state.pcmMax,
                lastRecognitionTextTimeMs = state.lastRecognitionTextTimeMs,
                overlayHidden = state.overlayHidden,
                recognitionMode = state.recognitionMode,
                hotwordsEnabled = state.hotwordsEnabled
            )
        }
    }

    private fun logConsistency(state: ASRSystemState) {
        val inconsistent = (!state.serviceRunning &&
            (state.audioCaptureRunning || state.asrState != AsrRuntimeState.STOPPED)) ||
            (state.inputState == PcmInputState.FLOWING && !state.serviceRunning)

        if (inconsistent) {
            Log.w(LogTags.SHERPA_CAPTION, "STATE_DESYNC_DETECTED state=$state")
        } else {
            Log.d(LogTags.SHERPA_CAPTION, "STATE_CONSISTENCY_OK state=$state")
        }
    }
}

data class ASRSystemState(
    val serviceRunning: Boolean = false,
    val audioCaptureRunning: Boolean = false,
    val asrState: AsrRuntimeState = AsrRuntimeState.STOPPED,
    val inputState: PcmInputState = PcmInputState.PAUSED,
    val asrFrames: Long = 0L,
    val pcmAverage: Int = 0,
    val pcmMax: Int = 0,
    val lastRecognitionTextTimeMs: Long = 0L,
    val overlayHidden: Boolean = true,
    val recognitionMode: String = "unknown",
    val hotwordsEnabled: Boolean = false
)

enum class PcmInputState {
    FLOWING,
    PAUSED
}

package com.sherpacaption.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sherpacaption.app.audio.AudioCaptureConfig
import com.sherpacaption.app.audio.AudioLevelStats
import com.sherpacaption.app.capture.PlaybackAudioCapture
import com.sherpacaption.app.overlay.FloatingCaptionWindow
import com.sherpacaption.app.sherpa.SherpaRecognitionStatus
import com.sherpacaption.app.sherpa.SherpaRecognizer
import com.sherpacaption.app.sherpa.SherpaRecognizerState
import com.sherpacaption.app.subtitle.SubtitleFormatter
import com.sherpacaption.app.util.ASRStateController
import com.sherpacaption.app.util.AsrRuntimeState
import com.sherpacaption.app.util.DeveloperMetricsStore
import com.sherpacaption.app.util.LogTags
import com.sherpacaption.app.util.PcmInputState
import java.util.concurrent.atomic.AtomicBoolean

class CaptionCaptureService : Service(), PlaybackAudioCapture.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val firstPcmLogged = AtomicBoolean(false)
    private var floatingCaptionWindow: FloatingCaptionWindow? = null
    private var mediaProjection: MediaProjection? = null
    private var playbackAudioCapture: PlaybackAudioCapture? = null
    private var sherpaRecognizer: SherpaRecognizer? = null
    private var lastOverlayUpdateTime = 0L
    private var serviceStartedTime = 0L
    private var lastSubtitleUpdateTime = 0L
    private var lastValidSpeechTime = 0L
    @Volatile
    private var acceptedFrames = 0L
    @Volatile
    private var latestAudioLevel: AudioLevelStats? = null
    @Volatile
    private var latestPartialText = ""
    @Volatile
    private var latestRawSubtitle = ""
    @Volatile
    private var latestAsrError: String? = null
    @Volatile
    private var asrState = SherpaRecognizerState.INITIALIZING
    private var serviceStopReason = "not stopped"
    @Volatile
    private var hasReceivedPcm = false
    @Volatile
    private var isServiceActive = false
    private var lastPerformanceLogTime = 0L
    private var lastWatchdogCheckTime = 0L
    private var lastWatchdogFrames = 0L
    private var lastPcmFeedTime = 0L
    private var lastPipelineFrames = 0L
    private var lastUiReplaceTime = 0L

    private val overlayHeartbeat = object : Runnable {
        override fun run() {
            if (!isServiceActive) {
                return
            }
            bindOverlayToState()
            updateDebugOverlayIfNeeded()
            checkOverlayTimeout()
            updateDeveloperMetrics()
            runInputWatchdog()
            logPerformanceIfDue()
            mainHandler.postDelayed(this, OVERLAY_HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                Log.i(LogTags.SHERPA_CAPTION, "Service start")
                Log.i(LogTags.SHERPA_CAPTION, "DEBUG_OVERLAY=$DEBUG_OVERLAY_ENABLED")
                Log.i(
                    LogTags.SHERPA_CAPTION,
                    "REALTIME_RENDER_INTERVAL=$REALTIME_RENDER_INTERVAL_MS"
                )
                isServiceActive = true
                serviceStopReason = "running"
                resetRuntimeStatus()
                startCaptureForeground()
                showFloatingCaptionWindow()
                startOverlayHeartbeat()
                startInternalAudioCapture(intent)
                Log.i(
                    LogTags.SHERPA_CAPTION,
                    "CaptionCaptureService started with MediaProjection permission"
                )
                START_STICKY
            }

            ACTION_STOP -> {
                stopCaptionService("user requested stop")
                START_NOT_STICKY
            }

            else -> {
                Log.i(LogTags.SHERPA_CAPTION, "CaptionCaptureService received unknown action")
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        isServiceActive = false
        ASRStateController.stopService("service destroy")
        mainHandler.removeCallbacks(overlayHeartbeat)
        Log.i(LogTags.SHERPA_CAPTION, "Service stop reason: $serviceStopReason")
        releaseCaptureResources()
        releaseSherpaRecognizer()
        releaseFloatingCaptionWindow()
        Log.i(LogTags.SHERPA_CAPTION, "CaptionCaptureService destroyed")
        super.onDestroy()
    }

    override fun onPcmAudio(
        samples: ShortArray,
        sampleCount: Int,
        config: AudioCaptureConfig
    ) {
        try {
            if (!isPcmInputFlowing()) {
                Log.d(LogTags.SHERPA_CAPTION, "PCM blocked by inputState=PAUSED")
                return
            }
            if (sampleCount > 0) {
                hasReceivedPcm = true
                lastPcmFeedTime = SystemClock.elapsedRealtime()
                if (firstPcmLogged.compareAndSet(false, true)) {
                    Log.i(
                        LogTags.SHERPA_CAPTION,
                        "First PCM received: samples=$sampleCount, " +
                            "rate=${config.sampleRate}, channels=${config.channelLabel}"
                    )
                }
            }
            sherpaRecognizer?.acceptPcm16(samples, sampleCount, config)
        } catch (error: Throwable) {
            val message =
                "PCM delivery to ASR failed: ${error.message ?: error.javaClass.simpleName}"
            latestAsrError = message
            Log.e(LogTags.SHERPA_CAPTION, message, error)
            showPersistentError(message)
        }
    }

    private fun onRecognitionStatus(status: SherpaRecognitionStatus) {
        asrState = status.state
        acceptedFrames = status.acceptedFrames
        ASRStateController.setAsrState(
            when (status.state) {
                SherpaRecognizerState.INITIALIZING -> AsrRuntimeState.INITIALIZING
                SherpaRecognizerState.RUNNING -> AsrRuntimeState.RUNNING
                SherpaRecognizerState.ERROR -> AsrRuntimeState.ERROR
            }
        )
        ASRStateController.setFrames(status.acceptedFrames)
        status.noticeMessage?.let(::showTransientNotice)
        status.errorMessage?.let {
            showPersistentError(it)
            return
        }
        if (!isPcmInputFlowing()) {
            return
        }

        if (status.acceptedFrames == lastPipelineFrames) {
            Log.d(
                LogTags.SHERPA_CAPTION,
                "EVENT_FRAME_FROZEN frames=${status.acceptedFrames}"
            )
            return
        }
        lastPipelineFrames = status.acceptedFrames

        val rawSubtitle = status.partialText.trim()
        if (rawSubtitle.isNotEmpty()) {
            latestRawSubtitle = rawSubtitle
            renderRealtimeSubtitle(rawSubtitle)
        } else {
            Log.d(LogTags.SHERPA_CAPTION, "EVENT_PIPELINE_NOOP")
        }

        when (status.state) {
            SherpaRecognizerState.INITIALIZING ->
                Log.i(LogTags.SHERPA_CAPTION, "ASR init status received")
            SherpaRecognizerState.RUNNING ->
                Log.d(LogTags.SHERPA_CAPTION, "ASR running frames=${status.acceptedFrames}")
            SherpaRecognizerState.ERROR ->
                Log.e(LogTags.SHERPA_CAPTION, status.errorMessage.orEmpty())
        }
    }

    override fun onAudioLevel(stats: AudioLevelStats) {
        latestAudioLevel = stats
        ASRStateController.setPcmLevel(stats.averageAbsolute, stats.maxAbsolute)
        Log.d(LogTags.SHERPA_CAPTION, stats.toDebugText())
        if (stats.averageAbsolute >= SPEECH_AVERAGE_THRESHOLD ||
            stats.maxAbsolute >= SPEECH_MAX_THRESHOLD
        ) {
            lastValidSpeechTime = SystemClock.elapsedRealtime()
        }
        if (latestAsrError == null &&
            latestPartialText.isEmpty() &&
            shouldShowDebugOverlay()
        ) {
            showDiagnosticStatus(buildDebugOverlayText(stats))
        }
    }

    override fun onSilenceDetected() {
        if (!isPcmInputFlowing()) {
            return
        }
        ASRStateController.setInputState(PcmInputState.PAUSED, "silence detected")
        sherpaRecognizer?.clearPendingInput("silence")
        latestRawSubtitle = ""
        latestPartialText = ""
        forceHideOverlay("input paused")
        Log.i(LogTags.SHERPA_CAPTION, "Silence detected")
        Log.i(LogTags.SHERPA_CAPTION, "DEBUG_OVERLAY inputState=PAUSED hidden=true")
        Log.i(LogTags.SHERPA_CAPTION, "ASR_INPUT_FLOW_ONLY silence=true")
    }

    override fun onSpeechResumed() {
        ASRStateController.setInputState(PcmInputState.FLOWING, "speech resumed")
        lastValidSpeechTime = SystemClock.elapsedRealtime()
        Log.i(LogTags.SHERPA_CAPTION, "Speech resumed")
        Log.i(LogTags.SHERPA_CAPTION, "DEBUG_OVERLAY inputState=FLOWING")
        Log.i(LogTags.SHERPA_CAPTION, "ASR_INPUT_FLOW_ONLY silence=false")
    }

    override fun onAudioCaptureStarted() {
        ASRStateController.setAudioCaptureRunning(true)
    }

    override fun onAudioCaptureStopped() {
        ASRStateController.setAudioCaptureRunning(false)
        forceHideOverlay("audio stopped")
    }

    override fun isPcmInputFlowing(): Boolean {
        return ASRStateController.snapshot().inputState == PcmInputState.FLOWING
    }

    override fun onReadError(message: String) {
        Log.w(LogTags.SHERPA_CAPTION, message)
        showPersistentError(message)
    }

    override fun onError(message: String) {
        Log.e(LogTags.SHERPA_CAPTION, message)
        showPersistentError(message)
    }

    private fun startCaptureForeground() {
        createNotificationChannel()
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startInternalAudioCapture(intent: Intent) {
        if (playbackAudioCapture != null) {
            Log.i(LogTags.SHERPA_CAPTION, "Internal audio capture is already active")
            return
        }

        val resultCode = intent.getIntExtra(
            EXTRA_MEDIA_PROJECTION_RESULT_CODE,
            MEDIA_PROJECTION_RESULT_CODE_MISSING
        )
        val projectionData = intent.getMediaProjectionDataExtra()

        if (resultCode == MEDIA_PROJECTION_RESULT_CODE_MISSING || projectionData == null) {
            showPersistentError("Missing MediaProjection permission data")
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching {
            projectionManager.getMediaProjection(resultCode, projectionData)
        }.onFailure { error ->
            showPersistentError(
                "Failed to create MediaProjection: " +
                    (error.message ?: error.javaClass.simpleName)
            )
        }.getOrNull() ?: return

        mediaProjection = projection
        Log.i(LogTags.SHERPA_CAPTION, "MediaProjection created")

        runCatching {
            SherpaRecognizer(
                context = this,
                listener = ::onRecognitionStatus
            )
        }.onSuccess {
            sherpaRecognizer = it
        }.onFailure { error ->
            showPersistentError(
                "ASR init failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }

        playbackAudioCapture = PlaybackAudioCapture(projection, this).also {
            runCatching { it.start() }
                .onFailure { error ->
                    showPersistentError(
                        "AudioRecord start failed: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                }
        }
    }

    private fun stopCaptionService(reason: String) {
        serviceStopReason = reason
        isServiceActive = false
        mainHandler.removeCallbacks(overlayHeartbeat)
        Log.i(LogTags.SHERPA_CAPTION, "Service stop reason: $reason")
        ASRStateController.stopService(reason)
        releaseCaptureResources()
        releaseSherpaRecognizer()
        releaseFloatingCaptionWindow()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun releaseCaptureResources() {
        playbackAudioCapture?.stop()
        playbackAudioCapture = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun releaseSherpaRecognizer() {
        sherpaRecognizer?.release()
        sherpaRecognizer = null
    }

    private fun startOverlayHeartbeat() {
        mainHandler.removeCallbacks(overlayHeartbeat)
        mainHandler.post(overlayHeartbeat)
    }

    private fun renderRealtimeSubtitle(rawText: String) {
        val now = SystemClock.elapsedRealtime()
        if (!isRenderAllowed()) {
            Log.d(LogTags.SHERPA_CAPTION, "REALTIME_RENDER_BLOCKED_STATE")
            return
        }

        val formattedText = SubtitleFormatter.format(rawText)
        if (formattedText.isBlank()) {
            Log.d(LogTags.SHERPA_CAPTION, "REALTIME_RENDER_SKIPPED_EMPTY")
            return
        }

        if (formattedText == latestPartialText) {
            Log.d(LogTags.SHERPA_CAPTION, "REALTIME_RENDER_SKIPPED_DUPLICATE")
            return
        }

        if (now - lastUiReplaceTime < REALTIME_RENDER_INTERVAL_MS) {
            Log.d(LogTags.SHERPA_CAPTION, "EVENT_UI_DEBOUNCED")
            return
        }

        val window = floatingCaptionWindow ?: return
        if (floatingCaptionWindow?.isVisible != true) {
            window.show(formattedText)
        }
        window.replaceSubtitleLines(listOf(formattedText))
        window.show()
        latestPartialText = formattedText
        lastSubtitleUpdateTime = now
        lastOverlayUpdateTime = now
        lastUiReplaceTime = now
        ASRStateController.markRecognitionTextUpdated(System.currentTimeMillis())
        ASRStateController.setOverlayHidden(false)
        Log.d(LogTags.SHERPA_CAPTION, "REALTIME_RENDER_UPDATE")
    }

    private fun showDiagnosticStatus(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOverlayUpdateTime < ASR_STATUS_UPDATE_INTERVAL_MS) {
            return
        }

        floatingCaptionWindow?.apply {
            updateText(SubtitleFormatter.format(message))
            show()
        }
        lastOverlayUpdateTime = now
    }

    private fun updateDebugOverlayIfNeeded() {
        if (!DEBUG_OVERLAY_ENABLED ||
            latestAsrError != null ||
            latestPartialText.isNotBlank() ||
            !shouldShowDebugOverlay()
        ) {
            return
        }

        showDiagnosticStatus(buildDebugOverlayText(latestAudioLevel))
    }

    private fun buildDebugOverlayText(stats: AudioLevelStats?): String {
        val avg = stats?.averageAbsolute ?: 0
        val max = stats?.maxAbsolute ?: 0
        return if (hasReceivedPcm || acceptedFrames > 0L) {
            "ASR running frames=$acceptedFrames avg=$avg max=$max"
        } else {
            "waiting PCM / ASR running frames=$acceptedFrames avg=$avg max=$max"
        }
    }

    private fun checkOverlayTimeout() {
        if (latestAsrError != null) {
            return
        }
        if (DEBUG_OVERLAY_ENABLED && latestPartialText.isBlank() && shouldShowDebugOverlay()) {
            return
        }
        if (!isRenderAllowed()) {
            forceHideOverlay("state binding")
            return
        }
        if (latestPartialText.isNotBlank()) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val referenceTime = lastValidSpeechTime.takeIf { it > 0L } ?: serviceStartedTime
        if (now - referenceTime >= SUBTITLE_HIDE_TIMEOUT_MS &&
            floatingCaptionWindow?.isVisible == true
        ) {
            floatingCaptionWindow?.hide()
            ASRStateController.setOverlayHidden(true)
            Log.d(LogTags.SHERPA_CAPTION, "Overlay hidden after subtitle timeout")
        }
    }

    private fun showPersistentError(message: String) {
        latestAsrError = message
        Log.e(LogTags.SHERPA_CAPTION, message)
        floatingCaptionWindow?.apply {
            updateText(SubtitleFormatter.format(message))
            show()
        }
        lastOverlayUpdateTime = SystemClock.elapsedRealtime()
        ASRStateController.setAsrState(AsrRuntimeState.ERROR)
    }

    private fun showTransientNotice(message: String) {
        Log.w(LogTags.SHERPA_CAPTION, message)
        floatingCaptionWindow?.apply {
            updateText(SubtitleFormatter.format(message))
            show()
        }
        lastOverlayUpdateTime = SystemClock.elapsedRealtime()
    }

    private fun resetRuntimeStatus() {
        firstPcmLogged.set(false)
        hasReceivedPcm = false
        acceptedFrames = 0L
        latestAudioLevel = null
        latestPartialText = ""
        latestRawSubtitle = ""
        latestAsrError = null
        asrState = SherpaRecognizerState.INITIALIZING
        lastOverlayUpdateTime = 0L
        serviceStartedTime = SystemClock.elapsedRealtime()
        lastSubtitleUpdateTime = 0L
        lastValidSpeechTime = serviceStartedTime
        lastPerformanceLogTime = 0L
        lastWatchdogCheckTime = 0L
        lastWatchdogFrames = 0L
        lastPcmFeedTime = 0L
        lastPipelineFrames = 0L
        lastUiReplaceTime = 0L
        ASRStateController.startServiceInitializing()
    }

    private fun updateDeveloperMetrics() {
        ASRStateController.setFrames(acceptedFrames)
        ASRStateController.setOverlayHidden(floatingCaptionWindow?.isVisible != true)
    }

    private fun bindOverlayToState() {
        if (mustHideOverlayForState() && floatingCaptionWindow?.isVisible == true) {
            Log.w(LogTags.SHERPA_CAPTION, "STATE_BINDING_MISMATCH")
            forceHideOverlay("state binding")
        }
    }

    private fun isRenderAllowed(): Boolean {
        val state = ASRStateController.snapshot()
        return state.asrState == AsrRuntimeState.RUNNING &&
            state.inputState == PcmInputState.FLOWING &&
            state.audioCaptureRunning
    }

    private fun shouldShowDebugOverlay(): Boolean {
        if (!DEBUG_OVERLAY_ENABLED) {
            return false
        }

        val state = ASRStateController.snapshot()
        return state.serviceRunning && state.inputState == PcmInputState.FLOWING
    }

    private fun mustHideOverlayForState(): Boolean {
        val state = ASRStateController.snapshot()
        return state.inputState == PcmInputState.PAUSED ||
            (!state.audioCaptureRunning && hasReceivedPcm)
    }

    private fun forceHideOverlay(reason: String) {
        floatingCaptionWindow?.hide()
        ASRStateController.setOverlayHidden(true)
        Log.i(LogTags.SHERPA_CAPTION, "OVERLAY_FORCE_HIDDEN reason=$reason")
    }

    private fun runInputWatchdog() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastWatchdogCheckTime < WATCHDOG_INTERVAL_MS) {
            return
        }
        lastWatchdogCheckTime = now

        val state = ASRStateController.snapshot()
        val level = latestAudioLevel
        val speechLikely = level != null &&
            (level.averageAbsolute >= SPEECH_AVERAGE_THRESHOLD ||
                level.maxAbsolute >= SPEECH_MAX_THRESHOLD)
        val framesChanged = acceptedFrames != lastWatchdogFrames
        lastWatchdogFrames = acceptedFrames

        val desynced = state.serviceRunning != isServiceActive ||
            state.audioCaptureRunning != (playbackAudioCapture != null) ||
            state.asrFrames != acceptedFrames ||
            (state.asrState == AsrRuntimeState.ERROR) != (latestAsrError != null)

        if (desynced) {
            Log.w(LogTags.SHERPA_CAPTION, "STATE_DESYNC_DETECTED state=$state")
            Log.w(
                LogTags.SHERPA_CAPTION,
                "STATE_INCONSISTENT state=$state service=$isServiceActive " +
                    "audio=${playbackAudioCapture != null} frames=$acceptedFrames " +
                    "error=${latestAsrError != null}"
            )
            return
        }

        if (state.inputState == PcmInputState.FLOWING &&
            speechLikely &&
            !framesChanged &&
            now - lastPcmFeedTime >= WATCHDOG_STALE_INPUT_MS
        ) {
            Log.w(
                LogTags.SHERPA_CAPTION,
                "STATE_DESYNC_DETECTED stale pcm feed state=$state"
            )
            Log.w(
                LogTags.SHERPA_CAPTION,
                "STATE_INCONSISTENT stale pcm feed avg=${level.averageAbsolute} " +
                    "max=${level.maxAbsolute} frames=$acceptedFrames"
            )
            return
        }

        Log.d(
            LogTags.SHERPA_CAPTION,
            "STATE_CHECK_OK input=${state.inputState} speech=$speechLikely " +
                "frames=$acceptedFrames changed=$framesChanged"
        )
    }

    private fun logPerformanceIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPerformanceLogTime < PERFORMANCE_LOG_INTERVAL_MS) {
            return
        }

        val metrics = DeveloperMetricsStore.snapshot()
        Log.i(
            LogTags.SHERPA_CAPTION,
            "Performance: memory=${metrics.appMemoryMb.toInt()}MB " +
                "native=${metrics.nativeHeapUsedMb.toInt()}MB " +
                "frames=${metrics.asrFrames} silence=${metrics.silent}"
        )
        lastPerformanceLogTime = now
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Sherpa Caption",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Sherpa Caption realtime subtitle service"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sherpa Caption")
            .setContentText("Internal audio capture and offline ASR are running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showFloatingCaptionWindow() {
        val window = floatingCaptionWindow ?: FloatingCaptionWindow(this).also {
            floatingCaptionWindow = it
        }
        if (DEBUG_OVERLAY_ENABLED) {
            window.show(SubtitleFormatter.format(WAITING_PCM_TEXT))
        }
    }

    private fun releaseFloatingCaptionWindow() {
        floatingCaptionWindow?.release()
        floatingCaptionWindow = null
    }

    @Suppress("DEPRECATION")
    private fun Intent.getMediaProjectionDataExtra(): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA)
        }
    }

    companion object {
        private const val ACTION_START = "com.sherpacaption.app.service.action.START"
        private const val ACTION_STOP = "com.sherpacaption.app.service.action.STOP"
        private const val EXTRA_MEDIA_PROJECTION_RESULT_CODE =
            "com.sherpacaption.app.extra.MEDIA_PROJECTION_RESULT_CODE"
        private const val EXTRA_MEDIA_PROJECTION_DATA =
            "com.sherpacaption.app.extra.MEDIA_PROJECTION_DATA"
        private const val NOTIFICATION_CHANNEL_ID = "caption_capture"
        private const val NOTIFICATION_ID = 1001
        private const val WAITING_PCM_TEXT = "waiting PCM, service alive"
        private const val MEDIA_PROJECTION_RESULT_CODE_MISSING = Int.MIN_VALUE
        private const val ASR_STATUS_UPDATE_INTERVAL_MS = 350L
        private const val OVERLAY_HEARTBEAT_INTERVAL_MS = 350L
        private const val SUBTITLE_HIDE_TIMEOUT_MS = 2_500L
        private const val SPEECH_AVERAGE_THRESHOLD = 30
        private const val SPEECH_MAX_THRESHOLD = 300
        private const val PERFORMANCE_LOG_INTERVAL_MS = 30_000L
        private const val WATCHDOG_INTERVAL_MS = 1_500L
        private const val WATCHDOG_STALE_INPUT_MS = 2_000L
        private const val REALTIME_RENDER_INTERVAL_MS = 150L
        private const val DEBUG_OVERLAY_ENABLED = true

        fun createStartIntent(
            context: Context,
            resultCode: Int,
            data: Intent?
        ): Intent {
            return Intent(context, CaptionCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MEDIA_PROJECTION_RESULT_CODE, resultCode)
                putExtra(EXTRA_MEDIA_PROJECTION_DATA, data)
            }
        }

        fun createStopIntent(context: Context): Intent {
            return Intent(context, CaptionCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

}

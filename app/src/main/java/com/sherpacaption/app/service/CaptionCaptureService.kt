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
import com.sherpacaption.app.subtitle.PunctuationRestorer
import com.sherpacaption.app.subtitle.SubtitleFormatter
import com.sherpacaption.app.util.LogTags
import com.sherpacaption.app.util.AsrRuntimeState
import com.sherpacaption.app.util.DeveloperMetricsStore
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

class CaptionCaptureService : Service(), PlaybackAudioCapture.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val firstPcmLogged = AtomicBoolean(false)
    private val punctuationRestorer = PunctuationRestorer()
    private var floatingCaptionWindow: FloatingCaptionWindow? = null
    private var mediaProjection: MediaProjection? = null
    private var playbackAudioCapture: PlaybackAudioCapture? = null
    private var sherpaRecognizer: SherpaRecognizer? = null
    private var lastOverlayUpdateTime = 0L
    private var serviceStartedTime = 0L
    private var lastSubtitleUpdateTime = 0L
    private var lastValidSpeechTime = 0L
    private val pendingSubtitleLock = Any()
    private val pendingSubtitleUpdates = ArrayDeque<PendingSubtitleUpdate>()
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
    @Volatile
    private var isSilent = false
    private var lastPerformanceLogTime = 0L

    private val overlayHeartbeat = object : Runnable {
        override fun run() {
            if (!isServiceActive) {
                return
            }
            flushPendingSubtitles()
            checkOverlayTimeout()
            updateDeveloperMetrics()
            logPerformanceIfDue()
            mainHandler.postDelayed(this, OVERLAY_HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                Log.i(LogTags.SHERPA_CAPTION, "Service start")
                isServiceActive = true
                DeveloperMetricsStore.update {
                    it.copy(
                        serviceRunning = true,
                        asrState = AsrRuntimeState.INITIALIZING
                    )
                }
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
        DeveloperMetricsStore.update {
            it.copy(
                serviceRunning = false,
                audioCaptureRunning = false,
                asrState = AsrRuntimeState.STOPPED,
                overlayHidden = true
            )
        }
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
            if (sampleCount > 0) {
                hasReceivedPcm = true
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
        DeveloperMetricsStore.update {
            it.copy(
                asrState = when (status.state) {
                    SherpaRecognizerState.INITIALIZING -> AsrRuntimeState.INITIALIZING
                    SherpaRecognizerState.RUNNING ->
                        if (isSilent) AsrRuntimeState.PAUSED else AsrRuntimeState.RUNNING
                    SherpaRecognizerState.ERROR -> AsrRuntimeState.ERROR
                },
                asrFrames = status.acceptedFrames
            )
        }
        status.noticeMessage?.let(::showTransientNotice)
        status.errorMessage?.let {
            showPersistentError(it)
            return
        }
        if (isSilent) {
            return
        }

        val rawSubtitle = status.partialText.trim()
        if (rawSubtitle.isNotEmpty() &&
            (rawSubtitle != latestRawSubtitle || status.isFinal)
        ) {
            latestRawSubtitle = rawSubtitle
            latestPartialText = punctuationRestorer.restore(
                text = SubtitleFormatter.format(rawSubtitle),
                isFinal = status.isFinal
            )
            lastSubtitleUpdateTime = SystemClock.elapsedRealtime()
            DeveloperMetricsStore.update {
                it.copy(lastRecognitionTextTimeMs = System.currentTimeMillis())
            }
            enqueueSubtitleUpdate(
                text = latestPartialText,
                isFinal = status.isFinal
            )
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
        DeveloperMetricsStore.update {
            it.copy(
                pcmAverage = stats.averageAbsolute,
                pcmMax = stats.maxAbsolute
            )
        }
        Log.d(LogTags.SHERPA_CAPTION, stats.toDebugText())
        if (stats.averageAbsolute >= SPEECH_AVERAGE_THRESHOLD ||
            stats.maxAbsolute >= SPEECH_MAX_THRESHOLD
        ) {
            lastValidSpeechTime = SystemClock.elapsedRealtime()
        }
        if (latestAsrError == null &&
            latestPartialText.isEmpty() &&
            !isSilent &&
            floatingCaptionWindow?.isVisible == true
        ) {
            showDiagnosticStatus(
                "ASR running frames=$acceptedFrames " +
                    "avg=${stats.averageAbsolute} max=${stats.maxAbsolute}"
            )
        }
    }

    override fun onSilenceDetected() {
        isSilent = true
        sherpaRecognizer?.pauseInput()
        latestPartialText = ""
        latestRawSubtitle = ""
        synchronized(pendingSubtitleLock) {
            pendingSubtitleUpdates.clear()
        }
        Log.i(LogTags.SHERPA_CAPTION, "Silence detected")
        DeveloperMetricsStore.update {
            it.copy(silent = true, asrState = AsrRuntimeState.PAUSED)
        }
    }

    override fun onSpeechResumed() {
        isSilent = false
        lastValidSpeechTime = SystemClock.elapsedRealtime()
        sherpaRecognizer?.resumeInput()
        Log.i(LogTags.SHERPA_CAPTION, "Speech resumed")
        DeveloperMetricsStore.update {
            it.copy(silent = false, asrState = AsrRuntimeState.RUNNING)
        }
    }

    override fun onAudioCaptureStarted() {
        DeveloperMetricsStore.update { it.copy(audioCaptureRunning = true) }
    }

    override fun onAudioCaptureStopped() {
        DeveloperMetricsStore.update { it.copy(audioCaptureRunning = false) }
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
        DeveloperMetricsStore.update {
            it.copy(
                serviceRunning = false,
                audioCaptureRunning = false,
                asrState = AsrRuntimeState.STOPPED
            )
        }
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

    private fun enqueueSubtitleUpdate(text: String, isFinal: Boolean) {
        if (text.isBlank()) {
            return
        }

        synchronized(pendingSubtitleLock) {
            if (!isFinal && pendingSubtitleUpdates.lastOrNull()?.isFinal == false) {
                pendingSubtitleUpdates.removeLast()
            }
            pendingSubtitleUpdates.addLast(
                PendingSubtitleUpdate(
                    text = text,
                    isFinal = isFinal
                )
            )
        }
    }

    private fun flushPendingSubtitles() {
        if (isSilent) {
            synchronized(pendingSubtitleLock) {
                pendingSubtitleUpdates.clear()
            }
            return
        }

        val updates = synchronized(pendingSubtitleLock) {
            buildList {
                while (pendingSubtitleUpdates.isNotEmpty()) {
                    add(pendingSubtitleUpdates.removeFirst())
                }
            }
        }
        if (updates.isEmpty()) {
            return
        }

        val window = floatingCaptionWindow ?: return
        updates.forEach { update ->
            window.updateSubtitle(update.text, update.isFinal)
        }
        window.show()
        lastOverlayUpdateTime = SystemClock.elapsedRealtime()
    }

    private fun showDiagnosticStatus(message: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastOverlayUpdateTime < ASR_STATUS_UPDATE_INTERVAL_MS) {
            return
        }

        floatingCaptionWindow?.updateText(SubtitleFormatter.format(message))
        lastOverlayUpdateTime = now
    }

    private fun checkOverlayTimeout() {
        if (latestAsrError != null) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        val referenceTime = lastValidSpeechTime.takeIf { it > 0L } ?: serviceStartedTime
        if (now - referenceTime >= SUBTITLE_HIDE_TIMEOUT_MS &&
            floatingCaptionWindow?.isVisible == true
        ) {
            floatingCaptionWindow?.hide()
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
        DeveloperMetricsStore.update { it.copy(asrState = AsrRuntimeState.ERROR) }
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
        isSilent = false
        lastPerformanceLogTime = 0L
        DeveloperMetricsStore.update {
            it.copy(
                serviceRunning = true,
                audioCaptureRunning = false,
                asrState = AsrRuntimeState.INITIALIZING,
                silent = false,
                asrFrames = 0L,
                pcmAverage = 0,
                pcmMax = 0,
                lastRecognitionTextTimeMs = 0L,
                recognitionMode = "initializing",
                hotwordsEnabled = false
            )
        }
        synchronized(pendingSubtitleLock) {
            pendingSubtitleUpdates.clear()
        }
    }

    private fun updateDeveloperMetrics() {
        DeveloperMetricsStore.update {
            it.copy(
                serviceRunning = isServiceActive,
                asrFrames = acceptedFrames,
                pcmAverage = latestAudioLevel?.averageAbsolute ?: 0,
                pcmMax = latestAudioLevel?.maxAbsolute ?: 0,
                silent = isSilent,
                overlayHidden = floatingCaptionWindow?.isVisible != true
            )
        }
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
        window.show(SubtitleFormatter.format(WAITING_PCM_TEXT))
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

    private data class PendingSubtitleUpdate(
        val text: String,
        val isFinal: Boolean
    )
}

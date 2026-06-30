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
import com.sherpacaption.app.audio.AudioLevelMeter
import com.sherpacaption.app.audio.AudioLevelStats
import com.sherpacaption.app.asr.AsrManager
import com.sherpacaption.app.capture.PlaybackAudioCapture
import com.sherpacaption.app.overlay.FloatingCaptionWindow
import com.sherpacaption.app.subtitle.StaticLatestLinesRenderer
import com.sherpacaption.app.util.LogTags
import com.sherpacaption.app.util.PerformanceMetrics
import java.util.concurrent.atomic.AtomicBoolean

class CaptionCaptureService : Service(), PlaybackAudioCapture.Listener, AsrManager.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val firstPcmLogged = AtomicBoolean(false)
    private var floatingCaptionWindow: FloatingCaptionWindow? = null
    private var mediaProjection: MediaProjection? = null
    private var playbackAudioCapture: PlaybackAudioCapture? = null
    private var asrManager: AsrManager? = null
    private val staticLatestLinesRenderer = StaticLatestLinesRenderer()
    private val pcmInputFlowing = AtomicBoolean(true)
    private var lastDisplayedText = ""
    private var serviceStopReason = "not stopped"

    private val performanceLogger = object : Runnable {
        override fun run() {
            PerformanceMetrics.logPerfIfDue()
            mainHandler.postDelayed(this, PERFORMANCE_LOG_CHECK_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                Log.i(LogTags.SHERPA_CAPTION, "Service start")
                serviceStopReason = "running"
                pcmInputFlowing.set(true)
                PerformanceMetrics.reset()
                startCaptureForeground()
                showFloatingCaptionWindow()
                mainHandler.post(performanceLogger)
                startInternalAudioCapture(intent)
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
        mainHandler.removeCallbacks(performanceLogger)
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
        if (sampleCount <= 0) {
            return
        }
        if (!pcmInputFlowing.get()) {
            LogTags.d { "PCM dropped before ASR because input is silent" }
            return
        }

        val level = AudioLevelMeter.calculate(samples, sampleCount, config)
        PerformanceMetrics.markPcmReceived(sampleCount, level.averageAbsolute, level.maxAbsolute)
        if (firstPcmLogged.compareAndSet(false, true)) {
            LogTags.iDiagnostic {
                "PCM_RECEIVED samples=$sampleCount rate=${config.sampleRate} " +
                    "channels=${config.channelLabel}"
            }
        } else {
            LogTags.d { "PCM_RECEIVED samples=$sampleCount" }
        }
        asrManager?.acceptPcm16(samples, sampleCount, config)
    }

    override fun onResult(text: String, frames: Long) {
        val resultCreatedAtMs = SystemClock.elapsedRealtime()
        mainHandler.post {
            val rawText = text.trim()
            if (rawText.isBlank()) {
                return@post
            }
            if (rawText == lastDisplayedText) {
                LogTags.d { "STATIC_RENDER_SKIP_SAME_TEXT" }
                return@post
            }
            lastDisplayedText = rawText
            floatingCaptionWindow?.renderStaticLatestLines(rawText, staticLatestLinesRenderer)
            floatingCaptionWindow?.show()
            PerformanceMetrics.markUiUpdate(resultCreatedAtMs)
            LogTags.d { "UI_UPDATE text=$rawText" }
        }
    }

    override fun onError(message: String) {
        Log.e(LogTags.SHERPA_CAPTION, message)
        mainHandler.post {
            floatingCaptionWindow?.updateText(message)
            floatingCaptionWindow?.show()
        }
    }

    override fun onAudioLevel(stats: AudioLevelStats) = Unit

    override fun onAudioCaptureStarted() {
        Log.i(LogTags.SHERPA_CAPTION, "AudioCapture running")
    }

    override fun onAudioCaptureStopped() {
        Log.i(LogTags.SHERPA_CAPTION, "AudioCapture stopped")
    }

    override fun onSilenceDetected() {
        pcmInputFlowing.set(false)
        PerformanceMetrics.markSpeechIdle()
        asrManager?.markInputIdle()
        mainHandler.post {
            lastDisplayedText = ""
            floatingCaptionWindow?.hide()
        }
    }

    override fun onSpeechResumed() {
        pcmInputFlowing.set(true)
        PerformanceMetrics.markSpeechResumed()
        asrManager?.markInputResumed()
    }

    override fun isPcmInputFlowing(): Boolean = pcmInputFlowing.get()

    override fun onReadError(message: String) {
        Log.w(LogTags.SHERPA_CAPTION, message)
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
            onError("Missing MediaProjection permission data")
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching {
            projectionManager.getMediaProjection(resultCode, projectionData)
        }.onFailure { error ->
            onError("Failed to create MediaProjection: ${error.message ?: error.javaClass.simpleName}")
        }.getOrNull() ?: return

        mediaProjection = projection
        Log.i(LogTags.SHERPA_CAPTION, "MediaProjection created")

        asrManager = AsrManager(
            context = this,
            listener = this
        ).also { it.start() }

        playbackAudioCapture = PlaybackAudioCapture(projection, this).also {
            runCatching { it.start() }
                .onFailure { error ->
                    onError("AudioRecord start failed: ${error.message ?: error.javaClass.simpleName}")
                }
        }
    }

    private fun stopCaptionService(reason: String) {
        serviceStopReason = reason
        mainHandler.removeCallbacks(performanceLogger)
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
        asrManager?.release()
        asrManager = null
    }

    private fun showFloatingCaptionWindow() {
        val window = floatingCaptionWindow ?: FloatingCaptionWindow(this).also {
            floatingCaptionWindow = it
        }
        window.show("waiting PCM")
    }

    private fun releaseFloatingCaptionWindow() {
        floatingCaptionWindow?.release()
        floatingCaptionWindow = null
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
        private const val MEDIA_PROJECTION_RESULT_CODE_MISSING = Int.MIN_VALUE
        private const val PERFORMANCE_LOG_CHECK_INTERVAL_MS = 1_000L

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

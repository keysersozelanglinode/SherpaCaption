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
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.sherpacaption.app.audio.AudioLevelStats
import com.sherpacaption.app.capture.PlaybackAudioCapture
import com.sherpacaption.app.overlay.FloatingCaptionWindow
import com.sherpacaption.app.util.LogTags

class CaptionCaptureService : Service(), PlaybackAudioCapture.Listener {
    private var floatingCaptionWindow: FloatingCaptionWindow? = null
    private var mediaProjection: MediaProjection? = null
    private var playbackAudioCapture: PlaybackAudioCapture? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return when (intent?.action) {
            ACTION_START -> {
                startCaptureForeground()
                showFloatingCaptionWindow()
                startInternalAudioCapture(intent)
                Log.i(
                    LogTags.SHERPA_CAPTION,
                    "CaptionCaptureService started with MediaProjection permission"
                )
                START_STICKY
            }

            ACTION_STOP -> {
                Log.i(LogTags.SHERPA_CAPTION, "CaptionCaptureService stop requested")
                stopCaptionService()
                START_NOT_STICKY
            }

            else -> {
                Log.i(LogTags.SHERPA_CAPTION, "CaptionCaptureService received unknown action")
                START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        releaseCaptureResources()
        releaseFloatingCaptionWindow()
        Log.i(LogTags.SHERPA_CAPTION, "CaptionCaptureService destroyed")
        super.onDestroy()
    }

    override fun onAudioLevel(stats: AudioLevelStats) {
        val debugText = stats.toDebugText()
        floatingCaptionWindow?.updateText(debugText)
        Log.d(LogTags.SHERPA_CAPTION, debugText)
    }

    override fun onReadError(message: String) {
        floatingCaptionWindow?.updateText(message)
        Log.w(LogTags.SHERPA_CAPTION, message)
    }

    override fun onError(message: String) {
        floatingCaptionWindow?.updateText(message)
        Log.e(LogTags.SHERPA_CAPTION, message)
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
            val message = "Missing MediaProjection permission data"
            Log.e(LogTags.SHERPA_CAPTION, message)
            floatingCaptionWindow?.updateText(message)
            return
        }

        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching {
            projectionManager.getMediaProjection(resultCode, projectionData)
        }.onFailure {
            val message = "Failed to create MediaProjection: ${it.message}"
            Log.e(LogTags.SHERPA_CAPTION, message, it)
            floatingCaptionWindow?.updateText(message)
        }.getOrNull() ?: return

        mediaProjection = projection
        playbackAudioCapture = PlaybackAudioCapture(projection, this).also {
            it.start()
        }
    }

    private fun stopCaptionService() {
        releaseCaptureResources()
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
            .setContentText("Internal audio capture debug is running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showFloatingCaptionWindow() {
        val window = floatingCaptionWindow ?: FloatingCaptionWindow(this).also {
            floatingCaptionWindow = it
        }
        window.show(INITIAL_CAPTION_TEXT)
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
        private const val INITIAL_CAPTION_TEXT = "Waiting for internal audio..."
        private const val MEDIA_PROJECTION_RESULT_CODE_MISSING = Int.MIN_VALUE

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

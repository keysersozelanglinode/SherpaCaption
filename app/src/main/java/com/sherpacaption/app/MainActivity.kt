package com.sherpacaption.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.sherpacaption.app.permission.NotificationPermissionState
import com.sherpacaption.app.permission.PermissionStatusProvider
import com.sherpacaption.app.service.CaptionCaptureService
import com.sherpacaption.app.service.CaptionServiceState
import com.sherpacaption.app.ui.SherpaCaptionApp
import com.sherpacaption.app.util.LogTags
import com.sherpacaption.app.util.DeveloperMetrics
import com.sherpacaption.app.util.DeveloperMetricsStore
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            var hasOverlayPermission by remember {
                mutableStateOf(PermissionStatusProvider.hasOverlayPermission(this))
            }
            var notificationPermissionState by remember {
                mutableStateOf(PermissionStatusProvider.notificationPermissionState(this))
            }
            var serviceState by remember {
                mutableStateOf(CaptionServiceState.NotStarted)
            }
            var developerMetrics by remember {
                mutableStateOf(DeveloperMetricsStore.snapshot())
            }

            LaunchedEffect(Unit) {
                while (true) {
                    developerMetrics = DeveloperMetricsStore.snapshot()
                    delay(DEVELOPER_REFRESH_INTERVAL_MS)
                }
            }

            fun refreshPermissionState() {
                hasOverlayPermission = PermissionStatusProvider.hasOverlayPermission(this)
                notificationPermissionState =
                    PermissionStatusProvider.notificationPermissionState(this)
            }

            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) {
                notificationPermissionState =
                    PermissionStatusProvider.notificationPermissionState(this)
                Log.i(LogTags.SHERPA_CAPTION, "Notification permission result: $it")
            }

            val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                Log.i(LogTags.SHERPA_CAPTION, "Record audio permission result: $isGranted")
            }

            val mediaProjectionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    val startIntent = CaptionCaptureService.createStartIntent(
                        context = this,
                        resultCode = result.resultCode,
                        data = result.data
                    )
                    ContextCompat.startForegroundService(this, startIntent)
                    serviceState = CaptionServiceState.Running
                } else {
                    Log.i(LogTags.SHERPA_CAPTION, "MediaProjection permission denied")
                }
            }

            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                refreshPermissionState()
            }

            SherpaCaptionApp(
                hasOverlayPermission = hasOverlayPermission,
                notificationPermissionState = notificationPermissionState,
                serviceState = serviceState,
                developerMetrics = developerMetrics,
                onRequestOverlayPermission = {
                    Log.i(LogTags.SHERPA_CAPTION, "Request overlay permission clicked")
                    if (PermissionStatusProvider.hasOverlayPermission(this)) {
                        hasOverlayPermission = true
                    } else {
                        startActivity(createOverlayPermissionIntent())
                    }
                },
                onStartCaptioning = {
                    Log.i(LogTags.SHERPA_CAPTION, "Start realtime caption clicked")
                    refreshPermissionState()

                    when {
                        !hasOverlayPermission -> startActivity(createOverlayPermissionIntent())
                        notificationPermissionState == NotificationPermissionState.NotGranted ->
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED ->
                            recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        else -> {
                            val mediaProjectionManager = getSystemService(
                                MediaProjectionManager::class.java
                            )
                            mediaProjectionLauncher.launch(
                                mediaProjectionManager.createScreenCaptureIntent()
                            )
                        }
                    }
                },
                onStopCaptioning = {
                    Log.i(LogTags.SHERPA_CAPTION, "Stop clicked")
                    startService(CaptionCaptureService.createStopIntent(this))
                    serviceState = CaptionServiceState.Stopped
                }
            )
        }
    }

    private fun createOverlayPermissionIntent(): Intent {
        val packageUri = Uri.parse("package:$packageName")
        return Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri)
    }

    companion object {
        private const val DEVELOPER_REFRESH_INTERVAL_MS = 1_000L
    }
}

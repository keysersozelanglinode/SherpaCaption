package com.sherpacaption.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sherpacaption.app.permission.NotificationPermissionState
import com.sherpacaption.app.service.CaptionServiceState
import com.sherpacaption.app.ui.theme.SherpaCaptionTheme
import com.sherpacaption.app.util.AsrRuntimeState
import com.sherpacaption.app.util.DeveloperMetrics
import java.util.Locale
import kotlin.math.max

@Composable
fun SherpaCaptionApp(
    hasOverlayPermission: Boolean,
    notificationPermissionState: NotificationPermissionState,
    serviceState: CaptionServiceState,
    developerMetrics: DeveloperMetrics,
    onRequestOverlayPermission: () -> Unit,
    onStartCaptioning: () -> Unit,
    onStopCaptioning: () -> Unit
) {
    SherpaCaptionTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold { innerPadding ->
                MainScreen(
                    hasOverlayPermission = hasOverlayPermission,
                    notificationPermissionState = notificationPermissionState,
                    serviceState = serviceState,
                    developerMetrics = developerMetrics,
                    onRequestOverlayPermission = onRequestOverlayPermission,
                    onStartCaptioning = onStartCaptioning,
                    onStopCaptioning = onStopCaptioning,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
private fun MainScreen(
    hasOverlayPermission: Boolean,
    notificationPermissionState: NotificationPermissionState,
    serviceState: CaptionServiceState,
    developerMetrics: DeveloperMetrics,
    onRequestOverlayPermission: () -> Unit,
    onStartCaptioning: () -> Unit,
    onStopCaptioning: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Sherpa Caption",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(24.dp))

        StatusText("悬浮窗权限", if (hasOverlayPermission) "已授权" else "未授权")
        StatusText("通知权限", notificationPermissionState.toDisplayText())
        StatusText("服务状态", serviceState.toDisplayText())

        Spacer(modifier = Modifier.height(20.dp))

        ActionButton("授权悬浮窗", onRequestOverlayPermission)
        Spacer(modifier = Modifier.height(12.dp))
        ActionButton("开始实时字幕", onStartCaptioning)
        Spacer(modifier = Modifier.height(12.dp))
        ActionButton("停止", onStopCaptioning)

        Spacer(modifier = Modifier.height(28.dp))
        DeveloperInfo(developerMetrics)
    }
}

@Composable
private fun DeveloperInfo(metrics: DeveloperMetrics) {
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "开发者信息",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(8.dp))

    MetricRow("Service", runningText(metrics.serviceRunning))
    MetricRow("AudioCapture", runningText(metrics.audioCaptureRunning))
    MetricRow("ASR", metrics.asrState.toDisplayText())
    MetricRow("Audio state", if (metrics.silent) "Silence" else "Speech")
    MetricRow("App memory", formatMb(metrics.appMemoryMb))
    MetricRow("Java heap", formatMb(metrics.javaHeapUsedMb))
    MetricRow("Native heap", formatMb(metrics.nativeHeapUsedMb))
    MetricRow("ASR frames", metrics.asrFrames.toString())
    MetricRow("PCM avg / max", "${metrics.pcmAverage} / ${metrics.pcmMax}")
    MetricRow(
        "Last recognition",
        lastRecognitionText(metrics.lastRecognitionTextTimeMs)
    )
    MetricRow("Subtitle", if (metrics.overlayHidden) "Hidden" else "Visible")
    MetricRow("Recognition mode", metrics.recognitionMode)
    MetricRow("Hotwords", if (metrics.hotwordsEnabled) "Enabled" else "Disabled")
}

@Composable
private fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatusText(label: String, value: String) {
    Text(
        text = "$label：$value",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun ActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = text)
    }
}

private fun runningText(running: Boolean): String =
    if (running) "Running" else "Stopped"

private fun formatMb(value: Double): String =
    String.format(Locale.US, "%.1f MB", value)

private fun lastRecognitionText(timestampMs: Long): String {
    if (timestampMs <= 0L) {
        return "Never"
    }
    val seconds = max(0L, (System.currentTimeMillis() - timestampMs) / 1_000L)
    return if (seconds < 2L) "Just now" else "${seconds}s ago"
}

private fun AsrRuntimeState.toDisplayText(): String = when (this) {
    AsrRuntimeState.STOPPED -> "Stopped"
    AsrRuntimeState.INITIALIZING -> "Initializing"
    AsrRuntimeState.RUNNING -> "Running"
    AsrRuntimeState.ERROR -> "Error"
}

private fun NotificationPermissionState.toDisplayText(): String = when (this) {
    NotificationPermissionState.Granted -> "已授权"
    NotificationPermissionState.NotGranted -> "未授权"
    NotificationPermissionState.NotRequired -> "不需要"
}

private fun CaptionServiceState.toDisplayText(): String = when (this) {
    CaptionServiceState.NotStarted -> "未启动"
    CaptionServiceState.Running -> "运行中"
    CaptionServiceState.Stopped -> "已停止"
}

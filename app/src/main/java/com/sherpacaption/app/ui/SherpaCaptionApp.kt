package com.sherpacaption.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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

@Composable
fun SherpaCaptionApp(
    hasOverlayPermission: Boolean,
    notificationPermissionState: NotificationPermissionState,
    serviceState: CaptionServiceState,
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
    onRequestOverlayPermission: () -> Unit,
    onStartCaptioning: () -> Unit,
    onStopCaptioning: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Sherpa Caption",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(28.dp))

        PermissionStatusText(
            label = "悬浮窗权限",
            value = if (hasOverlayPermission) "已授权" else "未授权"
        )
        PermissionStatusText(
            label = "通知权限",
            value = notificationPermissionState.toDisplayText()
        )
        PermissionStatusText(
            label = "服务状态",
            value = serviceState.toDisplayText()
        )

        Spacer(modifier = Modifier.height(28.dp))

        ActionButton(
            text = "授权悬浮窗",
            onClick = onRequestOverlayPermission
        )

        Spacer(modifier = Modifier.height(12.dp))

        ActionButton(
            text = "开始实时字幕",
            onClick = onStartCaptioning
        )

        Spacer(modifier = Modifier.height(12.dp))

        ActionButton(
            text = "停止",
            onClick = onStopCaptioning
        )
    }
}

@Composable
private fun PermissionStatusText(
    label: String,
    value: String
) {
    Text(
        text = "$label：$value",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text = text)
    }
}

private fun NotificationPermissionState.toDisplayText(): String {
    return when (this) {
        NotificationPermissionState.Granted -> "已授权"
        NotificationPermissionState.NotGranted -> "未授权"
        NotificationPermissionState.NotRequired -> "不需要"
    }
}

private fun CaptionServiceState.toDisplayText(): String {
    return when (this) {
        CaptionServiceState.NotStarted -> "未启动"
        CaptionServiceState.Running -> "运行中"
        CaptionServiceState.Stopped -> "已停止"
    }
}

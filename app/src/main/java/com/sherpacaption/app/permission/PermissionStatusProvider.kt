package com.sherpacaption.app.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object PermissionStatusProvider {
    fun hasOverlayPermission(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun notificationPermissionState(context: Context): NotificationPermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationPermissionState.NotRequired
        }

        val isGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        return if (isGranted) {
            NotificationPermissionState.Granted
        } else {
            NotificationPermissionState.NotGranted
        }
    }
}

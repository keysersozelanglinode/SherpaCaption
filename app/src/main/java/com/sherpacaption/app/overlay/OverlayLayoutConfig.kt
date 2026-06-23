package com.sherpacaption.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import kotlin.math.roundToInt

data class OverlayLayoutConfig(
    val widthRatio: Float = 0.9f,
    val bottomMarginDp: Int = 96,
    val horizontalPaddingDp: Int = 20,
    val verticalPaddingDp: Int = 12,
    val cornerRadiusDp: Int = 16,
    val textSizeSp: Float = 22f
) {
    fun createLayoutParams(context: Context): WindowManager.LayoutParams {
        val displayMetrics = context.resources.displayMetrics
        val overlayWidth = (displayMetrics.widthPixels * widthRatio).roundToInt()

        return WindowManager.LayoutParams(
            overlayWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = bottomMarginDp.dpToPx(context)
        }
    }

    companion object {
        fun windowType(): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
        }
    }
}

internal fun Int.dpToPx(context: Context): Int {
    return (this * context.resources.displayMetrics.density).roundToInt()
}

package com.sherpacaption.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import com.sherpacaption.app.subtitle.SubtitleDisplayMode
import kotlin.math.roundToInt

data class OverlayLayoutConfig(
    val displayMode: SubtitleDisplayMode = SubtitleDisplayMode.LEARNING,
    val widthRatio: Float = displayMode.widthRatio,
    val bottomMarginDp: Int = 96,
    val horizontalPaddingDp: Int = displayMode.horizontalPaddingDp,
    val verticalPaddingDp: Int = displayMode.verticalPaddingDp,
    val cornerRadiusDp: Int = 6,
    val textSizeSp: Float = displayMode.textSizeSp,
    val maxLines: Int = displayMode.maxLines
) {
    fun createLayoutParams(context: Context): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            overlayWidth(context),
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

    fun overlayWidth(context: Context): Int {
        return (currentScreenWidth(context) * widthRatio).roundToInt()
    }

    fun textContentWidth(context: Context): Int {
        return (
            overlayWidth(context) -
                horizontalPaddingDp.dpToPx(context) * 2
            ).coerceAtLeast(1)
    }

    companion object {
        private fun currentScreenWidth(context: Context): Int {
            val windowManager =
                context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowManager.currentWindowMetrics.bounds.width()
            } else {
                @Suppress("DEPRECATION")
                val metrics = DisplayMetrics().also {
                    windowManager.defaultDisplay.getRealMetrics(it)
                }
                metrics.widthPixels
            }
        }

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

package com.sherpacaption.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.sherpacaption.app.util.LogTags

class FloatingCaptionWindow(
    context: Context,
    private val config: OverlayLayoutConfig = OverlayLayoutConfig()
) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var captionView: TextView? = null

    fun show(initialText: String) {
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(LogTags.App, "Cannot show floating caption window: overlay permission missing")
            return
        }

        if (captionView != null) {
            updateText(initialText)
            return
        }

        val view = createCaptionView(initialText)
        val layoutParams = config.createLayoutParams(appContext)

        runCatching {
            windowManager.addView(view, layoutParams)
            captionView = view
            Log.i(LogTags.App, "Floating caption window shown")
        }.onFailure {
            Log.e(LogTags.App, "Failed to show floating caption window", it)
        }
    }

    fun updateText(text: String) {
        captionView?.post {
            captionView?.text = text
        }
    }

    fun release() {
        val view = captionView ?: return
        runCatching {
            windowManager.removeView(view)
            Log.i(LogTags.App, "Floating caption window removed")
        }.onFailure {
            Log.e(LogTags.App, "Failed to remove floating caption window", it)
        }
        captionView = null
    }

    private fun createCaptionView(text: String): TextView {
        return TextView(appContext).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = config.textSizeSp
            gravity = Gravity.CENTER
            includeFontPadding = true
            setPadding(
                config.horizontalPaddingDp.dpToPx(appContext),
                config.verticalPaddingDp.dpToPx(appContext),
                config.horizontalPaddingDp.dpToPx(appContext),
                config.verticalPaddingDp.dpToPx(appContext)
            )
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = config.cornerRadiusDp.dpToPx(appContext).toFloat()
                setColor(Color.argb(190, 0, 0, 0))
            }
        }
    }
}

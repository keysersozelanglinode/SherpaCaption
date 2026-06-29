package com.sherpacaption.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.sherpacaption.app.subtitle.LearningSubtitleLayout
import com.sherpacaption.app.subtitle.StaticLatestLinesRenderer
import com.sherpacaption.app.subtitle.SubtitleDisplayMode
import com.sherpacaption.app.util.LogTags
import com.sherpacaption.app.util.PerformanceMetrics

class FloatingCaptionWindow(
    context: Context,
    private val config: OverlayLayoutConfig = OverlayLayoutConfig()
) {
    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val learningLayout = LearningSubtitleLayout(config.maxLines)

    private var rootView: LinearLayout? = null
    private var captionViews: List<TextView> = emptyList()
    private var windowLayoutParams: WindowManager.LayoutParams? = null
    private var lastContentWidth = 0

    fun show(initialText: String) {
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(LogTags.App, "Cannot show floating caption window: overlay permission missing")
            return
        }

        if (rootView != null) {
            updateText(initialText)
            show()
            return
        }

        val (container, textViews) = createCaptionLayout()
        val layoutParams = config.createLayoutParams(appContext)

        runCatching {
            windowManager.addView(container, layoutParams)
            rootView = container
            captionViews = textViews
            windowLayoutParams = layoutParams
            updateText(initialText)
            isVisible = true
            Log.i(
                LogTags.App,
                "Floating caption window shown: width=${layoutParams.width}"
            )
        }.onFailure {
            Log.e(LogTags.App, "Failed to show floating caption window", it)
        }
    }

    fun show() {
        rootView?.post {
            refreshWindowWidth()
            rootView?.visibility = View.VISIBLE
            isVisible = true
        }
    }

    fun hide() {
        rootView?.post {
            rootView?.visibility = View.INVISIBLE
            isVisible = false
        }
    }

    fun updateText(text: String) {
        rootView?.post {
            refreshWindowWidth()
            renderLines(listOf(text))
        }
    }

    fun updateSubtitle(text: String, isFinal: Boolean) {
        rootView?.post {
            refreshWindowWidth()
            val referenceView = captionViews.firstOrNull() ?: return@post
            val contentWidth = config.textContentWidth(appContext)
            if (lastContentWidth != 0 && lastContentWidth != contentWidth) {
                learningLayout.reset()
            }
            lastContentWidth = contentWidth

            val lines = when (config.displayMode) {
                SubtitleDisplayMode.LEARNING ->
                    learningLayout.update(text, isFinal, referenceView.paint, contentWidth)
                else -> listOf(text)
            }
            renderLines(lines)
        }
    }

    fun replaceSubtitleLines(lines: List<String>) {
        rootView?.post {
            refreshWindowWidth()
            learningLayout.reset()
            renderLines(lines.takeLast(config.maxLines))
        }
    }

    fun updateRenderedLines(
        lines: List<String>,
        changedLines: Set<Int>
    ) {
        rootView?.post {
            refreshWindowWidth()
            renderIncrementalLines(lines, changedLines)
        }
    }

    fun renderStaticLatestLines(
        text: String,
        renderer: StaticLatestLinesRenderer
    ) {
        rootView?.post {
            val renderStartNs = SystemClock.elapsedRealtimeNanos()
            refreshWindowWidth()
            val referenceView = captionViews.firstOrNull() ?: return@post
            val staticLayoutStartNs = SystemClock.elapsedRealtimeNanos()
            val lines = renderer.render(
                text = text,
                paint = referenceView.paint,
                widthPx = config.textContentWidth(appContext)
            )
            PerformanceMetrics.markStaticLayout(
                SystemClock.elapsedRealtimeNanos() - staticLayoutStartNs
            )
            renderLines(lines)
            PerformanceMetrics.markUiRender(SystemClock.elapsedRealtimeNanos() - renderStartNs)
        }
    }

    fun resetFrozenSubtitle() {
        renderLines(emptyList())
    }

    fun release() {
        val view = rootView ?: return
        runCatching {
            windowManager.removeView(view)
            Log.i(LogTags.App, "Floating caption window removed")
        }.onFailure {
            Log.e(LogTags.App, "Failed to remove floating caption window", it)
        }
        rootView = null
        captionViews = emptyList()
        windowLayoutParams = null
        lastContentWidth = 0
        learningLayout.reset()
        isVisible = false
    }

    private fun createCaptionLayout(): Pair<LinearLayout, List<TextView>> {
        val container = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
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

        val textViews = List(config.maxLines) {
            createLineView().also(container::addView)
        }
        return container to textViews
    }

    private fun createLineView(): TextView {
        return TextView(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            textSize = config.textSizeSp
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            includeFontPadding = true
            maxLines = 1
            ellipsize = null
            setHorizontallyScrolling(false)
            setLineSpacing(0f, LINE_SPACING_MULTIPLIER)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            }
            visibility = View.GONE
        }
    }

    private fun renderLines(lines: List<String>) {
        val visibleLines = lines.filter(String::isNotBlank).takeLast(config.maxLines)
        captionViews.forEachIndexed { index, view ->
            val line = visibleLines.getOrNull(index)
            val setTextStartNs = SystemClock.elapsedRealtimeNanos()
            view.text = line.orEmpty()
            view.visibility = if (line == null) View.GONE else View.VISIBLE
            PerformanceMetrics.markSetText(SystemClock.elapsedRealtimeNanos() - setTextStartNs)
        }
    }

    private fun renderIncrementalLines(
        lines: List<String>,
        changedLines: Set<Int>
    ) {
        val visibleLines = lines.filter(String::isNotBlank).takeLast(config.maxLines)
        captionViews.forEachIndexed { index, view ->
            val lineNumber = index + 1
            val line = visibleLines.getOrNull(index).orEmpty()
            val shouldBeVisible = line.isNotBlank()
            val targetVisibility = if (shouldBeVisible) View.VISIBLE else View.GONE
            if (!changedLines.contains(lineNumber) &&
                view.text.toString() == line &&
                view.visibility == targetVisibility
            ) {
                Log.d(LogTags.SHERPA_CAPTION, "LAYOUT_SKIP_SAME_TEXT")
                return@forEachIndexed
            }

            if (view.text.toString() != line) {
                view.text = line
                Log.d(LogTags.SHERPA_CAPTION, "UI_LINE_UPDATE line=$lineNumber")
            } else {
                Log.d(LogTags.SHERPA_CAPTION, "LAYOUT_SKIP_SAME_TEXT")
            }

            if (view.visibility != targetVisibility) {
                view.visibility = targetVisibility
                Log.d(LogTags.SHERPA_CAPTION, "UI_LINE_UPDATE line=$lineNumber")
            }
        }
    }

    private fun wrapDisplayLines(lines: List<String>): List<String> {
        val paint = captionViews.firstOrNull()?.paint ?: return lines
        val contentWidth = config.textContentWidth(appContext)
        if (contentWidth <= 0) {
            return lines
        }

        return lines.flatMap { line ->
            wrapWords(line, paint, contentWidth)
        }.takeLast(config.maxLines)
    }

    private fun wrapWords(
        text: String,
        paint: android.text.TextPaint,
        contentWidth: Int
    ): List<String> {
        val words = text.split(Regex("""\s+""")).filter(String::isNotBlank)
        if (words.isEmpty()) {
            return emptyList()
        }

        val lines = mutableListOf<String>()
        val currentWords = mutableListOf<String>()
        words.forEach { word ->
            val candidate = (currentWords + word).joinToString(" ")
            if (currentWords.isNotEmpty() && paint.measureText(candidate) > contentWidth) {
                lines += currentWords.joinToString(" ")
                currentWords.clear()
            }
            currentWords += word
        }
        if (currentWords.isNotEmpty()) {
            lines += currentWords.joinToString(" ")
        }
        return lines
    }

    private fun refreshWindowWidth() {
        val view = rootView ?: return
        val params = windowLayoutParams ?: return
        val targetWidth = config.overlayWidth(appContext)
        if (params.width == targetWidth) {
            return
        }

        params.width = targetWidth
        runCatching {
            windowManager.updateViewLayout(view, params)
            Log.i(LogTags.App, "Floating caption width updated: width=$targetWidth")
        }.onFailure {
            Log.e(LogTags.App, "Failed to update floating caption width", it)
        }
    }

    @Volatile
    var isVisible: Boolean = false
        private set

    companion object {
        private const val LINE_SPACING_MULTIPLIER = 1.15f
    }
}

package com.sherpacaption.app.subtitle

import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.sherpacaption.app.util.LogTags

class StaticLatestLinesRenderer(
    private val maxLines: Int = DEFAULT_MAX_LINES
) {
    fun render(
        text: String,
        paint: TextPaint,
        widthPx: Int
    ): List<String> {
        val cleanText = text.cleanForStaticRender()
        if (cleanText.isBlank() || widthPx <= 0) {
            return emptyList()
        }

        Log.d(LogTags.SHERPA_CAPTION, "STATIC_RENDER_WIDTH width=$widthPx")
        val layout = StaticLayout.Builder
            .obtain(cleanText, 0, cleanText.length, paint, widthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

        val firstVisibleLine = (layout.lineCount - maxLines).coerceAtLeast(0)
        val lines = (firstVisibleLine until layout.lineCount)
            .map { line ->
                cleanText
                    .substring(layout.getLineStart(line), layout.getLineEnd(line))
                    .trim()
            }
            .filter(String::isNotBlank)

        Log.d(
            LogTags.SHERPA_CAPTION,
            "STATIC_RENDER_LINES line1=${lines.getOrNull(0).orEmpty()} " +
                "line2=${lines.getOrNull(1).orEmpty()} " +
                "line3=${lines.getOrNull(2).orEmpty()}"
        )
        return lines
    }

    private fun String.cleanForStaticRender(): String {
        val words = trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .toMutableList()
        if (words.lastOrNull()?.length == 1) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString(" ")
    }

    private companion object {
        private const val DEFAULT_MAX_LINES = 3
    }
}

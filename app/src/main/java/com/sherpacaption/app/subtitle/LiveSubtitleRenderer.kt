package com.sherpacaption.app.subtitle

import android.text.TextPaint
import android.util.Log
import com.sherpacaption.app.util.LogTags

class LiveSubtitleRenderer(
    private val liveWindowWords: Int = LIVE_WINDOW_WORDS,
    private val maxLines: Int = 3
) {
    private val stableWords = mutableListOf<String>()
    private var liveWords: List<String> = emptyList()
    private var lastLines: List<String> = emptyList()

    fun update(
        rawText: String,
        paint: TextPaint,
        maxLineWidthPx: Int
    ): RenderedSubtitle {
        val rawWords = rawText.toWords().dropTrailingIncompleteToken()
        if (rawWords.isEmpty()) {
            return RenderedSubtitle(lastLines, emptySet())
        }

        val newLiveWords = rawWords.takeLast(liveWindowWords)
        Log.d(LogTags.SHERPA_CAPTION, "LIVE_WINDOW_WORDS=$liveWindowWords")
        Log.d(LogTags.SHERPA_CAPTION, "COMMIT_GUARD_WORDS=$COMMIT_GUARD_WORDS")
        val appendWords = wordsLeavingLiveWindow(rawWords)
        if (appendWords.isNotEmpty()) {
            stableWords += appendWords
            Log.d(
                LogTags.SHERPA_CAPTION,
                "STABLE_REGION_APPEND words=${appendWords.joinToString(" ")}"
            )
        }

        if (newLiveWords != liveWords) {
            liveWords = newLiveWords
            Log.d(LogTags.SHERPA_CAPTION, "LIVE_REGION_UPDATE")
        }

        val displayWords = displayWordsWithBoundaryDedup()
        val lines = wrapLines(displayWords, paint, maxLineWidthPx)
        val changedLines = changedLines(lastLines, lines)
        if (changedLines.any { it <= 2 }) {
            Log.d(LogTags.SHERPA_CAPTION, "LINE_SHIFT")
        }
        if (changedLines.contains(3)) {
            Log.d(LogTags.SHERPA_CAPTION, "LINE3_REFRESH")
        }
        lastLines = lines
        logLines(lines)
        return RenderedSubtitle(lines, changedLines)
    }

    fun reset() {
        stableWords.clear()
        liveWords = emptyList()
        lastLines = emptyList()
    }

    private fun wordsLeavingLiveWindow(rawWords: List<String>): List<String> {
        val protectedTailSize = liveWindowWords + COMMIT_GUARD_WORDS
        val stableCandidate = rawWords.dropLast(protectedTailSize.coerceAtMost(rawWords.size))
        if (stableCandidate.size <= stableWords.size) {
            return emptyList()
        }

        val nextWords = stableCandidate.drop(stableWords.size)
        val appendWords = mutableListOf<String>()
        nextWords.forEach { word ->
            if (word.length < MIN_STABLE_TOKEN_LENGTH) {
                Log.d(LogTags.SHERPA_CAPTION, "STABLE_COMMIT_BLOCKED_GUARD word=$word")
                return appendWords
            }
            if (isPrefixOfLiveWord(word)) {
                Log.d(LogTags.SHERPA_CAPTION, "STABLE_COMMIT_BLOCKED_PREFIX word=$word")
                return appendWords
            }
            appendWords += word
        }
        return appendWords
    }

    private fun isPrefixOfLiveWord(word: String): Boolean {
        return liveWords.any { liveWord ->
            liveWord.length > word.length && liveWord.startsWith(word, ignoreCase = true)
        }
    }

    private fun displayWordsWithBoundaryDedup(): List<String> {
        val stableDisplayWords = stableWords.toMutableList()
        val liveDisplayWords = liveWords.toMutableList()
        while (stableDisplayWords.isNotEmpty() && liveDisplayWords.isNotEmpty()) {
            val stableWord = stableDisplayWords.last()
            val liveWord = liveDisplayWords.first()
            when {
                stableWord.equals(liveWord, ignoreCase = true) -> {
                    liveDisplayWords.removeAt(0)
                    Log.d(
                        LogTags.SHERPA_CAPTION,
                        "DISPLAY_BOUNDARY_DEDUP old=$stableWord new=$liveWord"
                    )
                }

                liveWord.length > stableWord.length &&
                    liveWord.startsWith(stableWord, ignoreCase = true) -> {
                    stableDisplayWords.removeAt(stableDisplayWords.lastIndex)
                    Log.d(
                        LogTags.SHERPA_CAPTION,
                        "DISPLAY_BOUNDARY_DEDUP old=$stableWord new=$liveWord"
                    )
                }

                stableWord.length > liveWord.length &&
                    stableWord.startsWith(liveWord, ignoreCase = true) -> {
                    liveDisplayWords.removeAt(0)
                    Log.d(
                        LogTags.SHERPA_CAPTION,
                        "DISPLAY_BOUNDARY_DEDUP old=$liveWord new=$stableWord"
                    )
                }

                else -> return stableDisplayWords + liveDisplayWords
            }
        }
        return stableDisplayWords + liveDisplayWords
    }

    private fun wrapLines(
        words: List<String>,
        paint: TextPaint,
        maxLineWidthPx: Int
    ): List<String> {
        if (maxLineWidthPx <= 0) {
            return listOf(words.joinToString(" ")).filter(String::isNotBlank)
        }

        val lines = mutableListOf<String>()
        val currentWords = mutableListOf<String>()
        words.forEach { word ->
            val candidate = (currentWords + word).joinToString(" ")
            if (currentWords.isNotEmpty() && paint.measureText(candidate) > maxLineWidthPx) {
                lines += currentWords.joinToString(" ")
                currentWords.clear()
            }
            currentWords += word
        }

        if (currentWords.isNotEmpty()) {
            lines += currentWords.joinToString(" ")
        }

        return lines.takeLast(maxLines)
    }

    private fun changedLines(
        oldLines: List<String>,
        newLines: List<String>
    ): Set<Int> {
        val changed = mutableSetOf<Int>()
        for (lineNumber in 1..maxLines) {
            if (oldLines.getOrNull(lineNumber - 1) != newLines.getOrNull(lineNumber - 1)) {
                changed += lineNumber
            }
        }
        return changed
    }

    private fun logLines(lines: List<String>) {
        Log.d(
            LogTags.SHERPA_CAPTION,
            "DISPLAY_LINES line1=${lines.getOrNull(0).orEmpty()} " +
                "line2=${lines.getOrNull(1).orEmpty()} " +
                "line3=${lines.getOrNull(2).orEmpty()}"
        )
    }

    private fun String.toWords(): List<String> {
        return trim()
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
    }

    private fun List<String>.dropTrailingIncompleteToken(): List<String> {
        if (lastOrNull()?.length == 1) {
            return dropLast(1)
        }
        return this
    }

    private companion object {
        private const val LIVE_WINDOW_WORDS = 8
        private const val COMMIT_GUARD_WORDS = 2
        private const val MIN_STABLE_TOKEN_LENGTH = 3
    }
}

data class RenderedSubtitle(
    val lines: List<String>,
    val changedLines: Set<Int>
)

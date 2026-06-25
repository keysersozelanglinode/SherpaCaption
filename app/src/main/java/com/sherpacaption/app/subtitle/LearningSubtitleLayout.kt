package com.sherpacaption.app.subtitle

import android.text.TextPaint
import java.util.ArrayDeque

class LearningSubtitleLayout(
    private val maxLines: Int = 3
) {
    private val stableLines = ArrayDeque<String>()
    private var frozenWordCount = 0
    private var activePartial = ""

    fun update(
        text: String,
        isFinal: Boolean,
        paint: TextPaint,
        contentWidth: Int
    ): List<String> {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return currentLines()
        }

        if (activePartial.isEmpty()) {
            frozenWordCount = 0
        }
        activePartial = normalized

        val words = normalized.split(WORD_SEPARATOR).filter(String::isNotBlank)
        var remainingWords = words.drop(frozenWordCount.coerceAtMost(words.size))
        var wrappedLines = wrapWords(remainingWords, paint, contentWidth)

        while (wrappedLines.size > 1) {
            val overflowWords = remainingWords.drop(wrappedLines.first().wordCount)
            if (!isFinal &&
                overflowWords.size == 1 &&
                !isLikelyCompleteWord(overflowWords.first())
            ) {
                break
            }
            val lineToFreeze = wrappedLines.first()
            appendStableLine(lineToFreeze.text)
            frozenWordCount += lineToFreeze.wordCount
            remainingWords = words.drop(frozenWordCount.coerceAtMost(words.size))
            wrappedLines = wrapWords(remainingWords, paint, contentWidth)
        }

        val liveLine = wrappedLines.firstOrNull()?.text.orEmpty()
        if (isFinal) {
            if (liveLine.isNotEmpty()) {
                appendStableLine(liveLine)
            }
            activePartial = ""
            frozenWordCount = 0
            return currentLines()
        }

        return buildList {
            addAll(stableLines)
            if (liveLine.isNotEmpty()) {
                add(liveLine)
            }
        }.takeLast(maxLines)
    }

    fun reset() {
        stableLines.clear()
        frozenWordCount = 0
        activePartial = ""
    }

    private fun appendStableLine(line: String) {
        if (line.isBlank()) {
            return
        }
        stableLines.addLast(line)
        while (stableLines.size > maxLines - 1) {
            stableLines.removeFirst()
        }
    }

    private fun currentLines(): List<String> {
        return stableLines.toList().takeLast(maxLines)
    }

    private fun wrapWords(
        words: List<String>,
        paint: TextPaint,
        contentWidth: Int
    ): List<MeasuredLine> {
        if (words.isEmpty()) {
            return emptyList()
        }

        val lines = mutableListOf<MeasuredLine>()
        val currentWords = mutableListOf<String>()

        words.forEach { word ->
            val candidate = (currentWords + word).joinToString(" ")
            if (currentWords.isNotEmpty() && paint.measureText(candidate) > contentWidth) {
                lines += MeasuredLine(
                    text = currentWords.joinToString(" "),
                    wordCount = currentWords.size
                )
                currentWords.clear()
            }
            currentWords += word
        }

        if (currentWords.isNotEmpty()) {
            lines += MeasuredLine(
                text = currentWords.joinToString(" "),
                wordCount = currentWords.size
            )
        }
        return lines
    }

    private fun isLikelyCompleteWord(word: String): Boolean {
        val letters = word.filter(Char::isLetter)
        return letters.length >= MIN_COMPLETE_WORD_LENGTH ||
            letters.uppercase() in SHORT_COMPLETE_WORDS ||
            word.lastOrNull() in TERMINAL_PUNCTUATION
    }

    private data class MeasuredLine(
        val text: String,
        val wordCount: Int
    )

    companion object {
        private const val MIN_COMPLETE_WORD_LENGTH = 3
        private val WORD_SEPARATOR = Regex("""\s+""")
        private val SHORT_COMPLETE_WORDS = setOf("A", "I", "AI", "UK")
        private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', ':', ';')
    }
}

package com.sherpacaption.app.subtitle

import java.util.Locale

class PunctuationRestorer {
    fun restore(text: String, isFinal: Boolean): String {
        val normalized = normalizePunctuation(text)
        if (normalized.isEmpty()) {
            return ""
        }

        val readableText = restoreProtectedTerms(
            capitalizePersonalPronoun(
                insertIntraSentenceCommas(
                    capitalizeFirstLetter(normalized)
                )
            )
        )
        return if (isFinal && readableText.last() !in TERMINAL_PUNCTUATION) {
            "$readableText."
        } else {
            readableText
        }
    }

    private fun normalizePunctuation(text: String): String {
        val builder = StringBuilder()
        var previousWasSpace = false
        var previousWasPunctuation: Char? = null

        text.trim().forEach { char ->
            when {
                char.isWhitespace() -> {
                    if (builder.isNotEmpty()) {
                        previousWasSpace = true
                    }
                }
                char in ALL_PUNCTUATION -> {
                    while (builder.lastOrNull() == ' ') {
                        builder.deleteCharAt(builder.lastIndex)
                    }
                    if (!isRepeatedPunctuation(previousWasPunctuation, char)) {
                        builder.append(char)
                    }
                    previousWasSpace = true
                    previousWasPunctuation = char
                }
                else -> {
                    if (previousWasSpace && builder.isNotEmpty()) {
                        builder.append(' ')
                    }
                    builder.append(char)
                    previousWasSpace = false
                    previousWasPunctuation = null
                }
            }
        }

        return builder.toString().trim()
    }

    private fun capitalizeFirstLetter(text: String): String {
        val firstLetterIndex = text.indexOfFirst(Char::isLetter)
        if (firstLetterIndex < 0 || text[firstLetterIndex].isUpperCase()) {
            return text
        }

        return text.replaceRange(
            firstLetterIndex,
            firstLetterIndex + 1,
            text[firstLetterIndex].titlecase(Locale.US)
        )
    }

    private fun capitalizePersonalPronoun(text: String): String {
        return text.split(WORD_SEPARATOR)
            .joinToString(" ") { word ->
                when (word.lowercase(Locale.US)) {
                    "i" -> "I"
                    "i'm" -> "I'm"
                    "i've" -> "I've"
                    "i'd" -> "I'd"
                    "i'll" -> "I'll"
                    else -> word
                }
            }
    }

    private fun restoreProtectedTerms(text: String): String {
        return text.split(WORD_SEPARATOR)
            .joinToString(" ") { word ->
                val suffix = word.takeLastWhile { it in ALL_PUNCTUATION }
                val core = word.dropLast(suffix.length)
                val restored = when (core.lowercase(Locale.US)) {
                    "congress" -> "Congress"
                    "openai" -> "OpenAI"
                    "chatgpt" -> "ChatGPT"
                    "microsoft" -> "Microsoft"
                    "google" -> "Google"
                    "youtube" -> "YouTube"
                    else -> core
                }
                restored + suffix
            }
            .replacePhrase("wall street", "Wall Street")
            .replacePhrase("united states", "United States")
            .replacePhrase("white house", "White House")
    }

    private fun String.replacePhrase(source: String, replacement: String): String {
        val words = split(WORD_SEPARATOR)
        if (words.isEmpty()) {
            return this
        }

        val rebuilt = mutableListOf<String>()
        var index = 0
        val sourceWords = source.split(' ')
        while (index < words.size) {
            val candidate = words
                .drop(index)
                .take(sourceWords.size)
                .joinToString(" ") { it.cleanToken().lowercase(Locale.US) }
            if (candidate == source) {
                val suffix = words[index + sourceWords.lastIndex]
                    .takeLastWhile { it in ALL_PUNCTUATION }
                rebuilt += replacement + suffix
                index += sourceWords.size
            } else {
                rebuilt += words[index]
                index += 1
            }
        }
        return rebuilt.joinToString(" ")
    }

    private fun insertIntraSentenceCommas(text: String): String {
        val words = text.split(WORD_SEPARATOR).filter(String::isNotBlank)
        if (words.size <= COMMA_WORD_THRESHOLD) {
            return text
        }

        val result = ArrayList<String>(words.size)
        var insertedCommaCount = 0
        words.forEachIndexed { index, word ->
            val normalized = word.cleanToken().lowercase(Locale.US)
            val shouldInsertComma = shouldInsertCommaBefore(
                normalizedToken = normalized,
                wordIndex = index,
                wordCount = words.size,
                insertedCommaCount = insertedCommaCount,
                previousWord = words.getOrNull(index - 1),
                nextWord = words.getOrNull(index + 1)
            )

            if (shouldInsertComma && result.isNotEmpty()) {
                result[result.lastIndex] = result.last().withTrailingComma()
                insertedCommaCount += 1
            }
            result += word
        }

        return result.joinToString(" ")
    }

    private fun shouldInsertCommaBefore(
        normalizedToken: String,
        wordIndex: Int,
        wordCount: Int,
        insertedCommaCount: Int,
        previousWord: String?,
        nextWord: String?
    ): Boolean {
        if (wordIndex == 0 || insertedCommaCount >= MAX_INSERTED_COMMAS) {
            return false
        }
        if (previousWord?.endsWithTerminalOrComma() == true) {
            return false
        }
        if (isProtectedPhraseBoundary(previousWord, normalizedToken, nextWord)) {
            return false
        }

        return when (normalizedToken) {
            "that's" ->
                wordIndex >= MIN_THATS_INDEX
            "but", "because", "so", "however" ->
                wordIndex >= MIN_CONNECTOR_INDEX
            "and" ->
                wordCount >= LONG_SENTENCE_WORD_THRESHOLD &&
                    wordIndex >= MIN_AND_INDEX &&
                    wordIndex <= wordCount - MIN_WORDS_AFTER_AND
            else -> false
        }
    }

    private fun isProtectedPhraseBoundary(
        previousWord: String?,
        normalizedToken: String,
        nextWord: String?
    ): Boolean {
        val previous = previousWord?.cleanToken()?.lowercase(Locale.US).orEmpty()
        val next = nextWord?.cleanToken()?.lowercase(Locale.US).orEmpty()
        return (previous == "wall" && normalizedToken == "street") ||
            (normalizedToken == "private" && next == "equity") ||
            (previous == "private" && normalizedToken == "equity") ||
            (normalizedToken == "united" && next == "states") ||
            (previous == "united" && normalizedToken == "states") ||
            normalizedToken == "openai"
    }

    private fun String.cleanToken(): String {
        return trim { it in ALL_PUNCTUATION }
    }

    private fun String.endsWithTerminalOrComma(): Boolean {
        return lastOrNull() in TERMINAL_PUNCTUATION || lastOrNull() == ','
    }

    private fun String.withTrailingComma(): String {
        val trimmed = trimEnd()
        val last = trimmed.lastOrNull()
        return when {
            last == ',' -> trimmed
            last in TERMINAL_PUNCTUATION -> trimmed
            else -> "$trimmed,"
        }
    }

    private fun isRepeatedPunctuation(previous: Char?, current: Char): Boolean {
        return previous == current && current in DEDUPED_PUNCTUATION
    }

    companion object {
        private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', ':', ';')
        private val ALL_PUNCTUATION = TERMINAL_PUNCTUATION + setOf(',',)
        private val DEDUPED_PUNCTUATION = TERMINAL_PUNCTUATION + setOf(',')
        private val WORD_SEPARATOR = Regex("""\s+""")
        private const val COMMA_WORD_THRESHOLD = 8
        private const val LONG_SENTENCE_WORD_THRESHOLD = 12
        private const val MIN_THATS_INDEX = 2
        private const val MIN_CONNECTOR_INDEX = 3
        private const val MIN_AND_INDEX = 5
        private const val MIN_WORDS_AFTER_AND = 3
        private const val MAX_INSERTED_COMMAS = 2
    }
}

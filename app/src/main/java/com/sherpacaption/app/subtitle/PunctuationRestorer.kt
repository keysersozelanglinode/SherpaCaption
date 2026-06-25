package com.sherpacaption.app.subtitle

import java.util.Locale

class PunctuationRestorer {
    fun restore(text: String, isFinal: Boolean): String {
        val normalized = text.trim()
        if (normalized.isEmpty()) {
            return ""
        }

        val sentenceCase = capitalizeFirstLetter(normalized)
        return if (isFinal && sentenceCase.last() !in TERMINAL_PUNCTUATION) {
            "$sentenceCase."
        } else {
            sentenceCase
        }
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

    companion object {
        private val TERMINAL_PUNCTUATION = setOf('.', '!', '?', ':', ';')
    }
}

package com.sherpacaption.app.subtitle

import java.util.Locale

object SubtitleFormatter {
    private val preservedTerms = linkedMapOf(
        "usa" to "USA",
        "uk" to "UK",
        "ai" to "AI",
        "cpu" to "CPU",
        "gpu" to "GPU",
        "usb" to "USB",
        "hdmi" to "HDMI",
        "wi-fi" to "Wi-Fi",
        "wifi" to "Wi-Fi",
        "openai" to "OpenAI",
        "chatgpt" to "ChatGPT"
    )

    fun format(text: String): String {
        val normalized = text
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.isEmpty()) {
            return ""
        }

        val readable = if (isAllUppercaseEnglish(normalized)) {
            normalizeUppercaseEnglish(normalized)
        } else {
            restorePreservedTerms(normalized)
        }

        return readable
    }

    private fun isAllUppercaseEnglish(text: String): Boolean {
        val letters = text.filter(Char::isLetter)
        return letters.isNotEmpty() &&
            letters.any { it in 'A'..'Z' } &&
            letters.none { it in 'a'..'z' }
    }

    private fun normalizeUppercaseEnglish(text: String): String {
        val lowercase = text.lowercase(Locale.US)
        val firstLetterIndex = lowercase.indexOfFirst(Char::isLetter)
        val sentenceCase = if (firstLetterIndex >= 0) {
            lowercase.replaceRange(
                firstLetterIndex,
                firstLetterIndex + 1,
                lowercase[firstLetterIndex].titlecase(Locale.US)
            )
        } else {
            lowercase
        }
        return restorePreservedTerms(sentenceCase)
    }

    private fun restorePreservedTerms(text: String): String {
        var result = text
        preservedTerms.forEach { (source, replacement) ->
            result = result.replace(
                Regex("""(?i)(?<![A-Za-z0-9])${Regex.escape(source)}(?![A-Za-z0-9])"""),
                replacement
            )
        }
        return result
    }

}

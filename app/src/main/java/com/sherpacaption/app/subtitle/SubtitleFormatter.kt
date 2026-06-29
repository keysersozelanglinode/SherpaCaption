package com.sherpacaption.app.subtitle

object SubtitleFormatter {
    fun format(text: String): String {
        return text
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

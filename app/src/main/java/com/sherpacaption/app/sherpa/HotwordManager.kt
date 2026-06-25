package com.sherpacaption.app.sherpa

import android.util.Log
import com.sherpacaption.app.util.LogTags
import java.io.File
import java.util.Locale

class HotwordManager(
    private val outputDirectory: File
) {
    fun prepare(
        bpeModelFile: File?,
        bpeVocabFile: File?,
        customHotwords: List<String> = emptyList()
    ): HotwordPreparation {
        if (bpeModelFile?.isFile != true) {
            return HotwordPreparation.Disabled("missing bpe.model")
        }
        if (bpeVocabFile?.isFile != true) {
            return HotwordPreparation.Disabled("missing bpe.vocab")
        }
        if (!isValidBpeVocab(bpeVocabFile)) {
            return HotwordPreparation.Disabled("invalid bpe.vocab")
        }
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            return HotwordPreparation.Disabled(
                "cannot create ${outputDirectory.absolutePath}"
            )
        }

        val hotwords = (DEFAULT_HOTWORDS + customHotwords)
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy { it.lowercase(Locale.US) }
        if (hotwords.isEmpty()) {
            return HotwordPreparation.Disabled("hotword list is empty")
        }

        return runCatching {
            val file = File(outputDirectory, HOTWORDS_FILE_NAME)
            file.writeText(hotwords.joinToString(separator = "\n", postfix = "\n"))
            Log.i(
                LogTags.SHERPA_CAPTION,
                "Hotwords prepared: count=${hotwords.size}, path=${file.absolutePath}"
            )
            HotwordPreparation.Enabled(
                hotwordsFile = file,
                bpeModelFile = bpeModelFile,
                bpeVocabFile = bpeVocabFile
            )
        }.getOrElse { error ->
            HotwordPreparation.Disabled(
                "cannot write hotwords file: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private fun isValidBpeVocab(file: File): Boolean {
        var validLines = 0
        file.useLines { lines ->
            lines.forEach { line ->
                val columns = line.trim().split(WHITESPACE)
                if (columns.size < 2 || columns.last().toFloatOrNull() == null) {
                    return false
                }
                validLines += 1
            }
        }
        return validLines > 0
    }

    sealed interface HotwordPreparation {
        data class Enabled(
            val hotwordsFile: File,
            val bpeModelFile: File,
            val bpeVocabFile: File
        ) : HotwordPreparation

        data class Disabled(
            val reason: String
        ) : HotwordPreparation
    }

    companion object {
        val DEFAULT_HOTWORDS = listOf(
            "Wall Street",
            "private equity",
            "renters",
            "Federal Reserve",
            "Congress",
            "White House",
            "Donald Trump",
            "housing market",
            "home buyers",
            "mortgage rates",
            "inflation",
            "interest rates",
            "OpenAI",
            "Microsoft",
            "Google",
            "YouTube"
        )

        private const val HOTWORDS_FILE_NAME = "news-hotwords.txt"
        private val WHITESPACE = Regex("""\s+""")
    }
}

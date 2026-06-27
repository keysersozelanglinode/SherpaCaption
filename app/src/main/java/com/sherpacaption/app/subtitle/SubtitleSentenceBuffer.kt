package com.sherpacaption.app.subtitle

import android.os.SystemClock
import android.util.Log
import com.sherpacaption.app.util.LogTags
import java.util.Locale

class SubtitleSentenceBuffer(
    private val config: SubtitleSentenceBufferConfig = SubtitleSentenceBufferConfig()
) {
    private var activeChunk = ""
    private var lastCommittedRaw = ""
    private var openedAtMs = 0L
    private var lastUpdateAtMs = 0L
    private var lastCommitTimestampMs = 0L
    private var lifecycleState = SentenceLifecycleState.FLUSHED
    private var speechMode = SpeechRhythmMode.CONTINUOUS

    fun accept(text: String, isFinal: Boolean): SubtitleSentenceResult {
        val normalized = normalize(text)
        if (normalized.isEmpty()) {
            Log.d(LogTags.SHERPA_CAPTION, "EVENT_COMMIT_SKIPPED_EMPTY")
            return SubtitleSentenceResult.None
        }

        val now = SystemClock.elapsedRealtime()
        val tokenGapMs = if (lastUpdateAtMs == 0L) 0L else now - lastUpdateAtMs
        lastUpdateAtMs = now

        val incomingChunk = removeCommittedPrefix(normalized)
        if (incomingChunk.isEmpty() || incomingChunk == activeChunk) {
            return SubtitleSentenceResult.None
        }

        if (lifecycleState == SentenceLifecycleState.FLUSHED || activeChunk.isEmpty()) {
            openedAtMs = now
            lifecycleState = SentenceLifecycleState.OPEN
        }
        activeChunk = incomingChunk

        val analysis = analyze(activeChunk, now, tokenGapMs, isFinal)
        speechMode = analysis.speechMode
        val splitIndex = chooseSplitIndex(analysis)
        return when {
            splitIndex != null -> commitPrefix(analysis, splitIndex)
            isFinal && shouldCommitFinal(analysis) -> commitAll(analysis, SentenceFlushReason.FINAL_RESULT)
            shouldHardCommit(analysis) -> commitAtReadabilityPoint(analysis)
            else -> {
                lifecycleState = if (analysis.hasCandidateBoundary) {
                    SentenceLifecycleState.STABLE
                } else {
                    SentenceLifecycleState.OPEN
                }
                SubtitleSentenceResult.None
            }
        }
    }

    fun finalizeForSilence(): SubtitleSentenceResult {
        if (activeChunk.isBlank()) {
            return SubtitleSentenceResult.None
        }

        val analysis = analyze(
            text = activeChunk,
            nowMs = SystemClock.elapsedRealtime(),
            tokenGapMs = config.strongTokenGapMs,
            isFinal = false
        )
        val splitIndex = chooseSplitIndex(analysis)
        return when {
            splitIndex != null -> commitPrefix(analysis, splitIndex)
            analysis.wordCount >= config.minSilenceCommitWords ->
                commitAll(analysis, SentenceFlushReason.STRONG_RHYTHM_BOUNDARY)
            else -> SubtitleSentenceResult.None
        }
    }

    fun reset() {
        activeChunk = ""
        lastCommittedRaw = ""
        openedAtMs = 0L
        lastUpdateAtMs = 0L
        lastCommitTimestampMs = 0L
        lifecycleState = SentenceLifecycleState.FLUSHED
        speechMode = SpeechRhythmMode.CONTINUOUS
    }

    private fun chooseSplitIndex(analysis: SentenceAnalysis): Int? {
        val candidate = analysis.candidateSplitIndex ?: return null
        if (analysis.wordCount < config.minMicroSplitWords) {
            return null
        }

        val rhythmAllowsSplit =
            analysis.rhythmBoundary == RhythmBoundary.STRONG_BOUNDARY ||
                analysis.rhythmBoundary == RhythmBoundary.SOFT_BOUNDARY ||
                analysis.hasReportingVerbBoundary ||
                analysis.visualOverflow ||
                analysis.durationMs >= analysis.softDurationMs

        if (!rhythmAllowsSplit) {
            return null
        }
        if (analysis.continuityScore > config.continuityHoldThreshold &&
            !analysis.visualOverflow &&
            analysis.rhythmBoundary != RhythmBoundary.STRONG_BOUNDARY
        ) {
            return null
        }
        return candidate
    }

    private fun shouldCommitFinal(analysis: SentenceAnalysis): Boolean {
        return analysis.wordCount >= config.minFinalWords ||
            analysis.durationMs >= config.minFinalDurationMs ||
            analysis.hasTerminalPunctuation
    }

    private fun shouldHardCommit(analysis: SentenceAnalysis): Boolean {
        return analysis.wordCount >= analysis.wordLimit ||
            analysis.durationMs >= analysis.durationLimitMs ||
            (analysis.durationMs >= config.maxOpenDurationMs &&
                analysis.continuityScore < config.downgradeContinuityThreshold)
    }

    private fun commitAtReadabilityPoint(analysis: SentenceAnalysis): SubtitleSentenceResult {
        val words = analysis.words
        val splitIndex = analysis.candidateSplitIndex
            ?: readabilitySplitIndex(words)
            ?: words.lastIndex
        return commitPrefix(analysis, splitIndex)
    }

    private fun commitPrefix(
        analysis: SentenceAnalysis,
        splitIndex: Int
    ): SubtitleSentenceResult {
        val words = analysis.words
        val safeIndex = splitIndex.coerceIn(config.minCommitWords - 1, words.lastIndex)
        val committed = words.take(safeIndex + 1).joinToString(" ")
        val remaining = words.drop(safeIndex + 1).joinToString(" ")
        return commit(
            committedText = committed,
            remainingText = remaining,
            analysis = analysis,
            reason = when (analysis.rhythmBoundary) {
                RhythmBoundary.STRONG_BOUNDARY -> SentenceFlushReason.STRONG_RHYTHM_BOUNDARY
                RhythmBoundary.SOFT_BOUNDARY -> SentenceFlushReason.SOFT_RHYTHM_BOUNDARY
                RhythmBoundary.NONE -> SentenceFlushReason.SEMANTIC_BOUNDARY
            }
        )
    }

    private fun commitAll(
        analysis: SentenceAnalysis,
        reason: SentenceFlushReason
    ): SubtitleSentenceResult {
        return commit(
            committedText = activeChunk,
            remainingText = "",
            analysis = analysis,
            reason = reason
        )
    }

    private fun commit(
        committedText: String,
        remainingText: String,
        analysis: SentenceAnalysis,
        reason: SentenceFlushReason
    ): SubtitleSentenceResult {
        val committed = normalize(committedText)
        if (committed.isEmpty()) {
            Log.d(LogTags.SHERPA_CAPTION, "EVENT_COMMIT_SKIPPED_EMPTY")
            activeChunk = normalize(remainingText)
            return SubtitleSentenceResult.None
        }

        val now = SystemClock.elapsedRealtime()
        if (committed == lastCommittedRaw && now == lastCommitTimestampMs) {
            Log.d(LogTags.SHERPA_CAPTION, "EVENT_COMMIT_SKIPPED_DUPLICATE")
            activeChunk = normalize(remainingText)
            return SubtitleSentenceResult.None
        }
        if (committed == lastCommittedRaw) {
            Log.d(LogTags.SHERPA_CAPTION, "EVENT_COMMIT_SKIPPED_DUPLICATE")
            activeChunk = normalize(remainingText)
            return SubtitleSentenceResult.None
        }

        lastCommittedRaw = committed
        lastCommitTimestampMs = now
        activeChunk = normalize(remainingText)
        lifecycleState = if (activeChunk.isEmpty()) {
            SentenceLifecycleState.FLUSHED
        } else {
            SentenceLifecycleState.OPEN
        }
        openedAtMs = if (activeChunk.isEmpty()) 0L else SystemClock.elapsedRealtime()

        return SubtitleSentenceResult.FinalSentence(
            text = committed,
            lifecycleState = lifecycleState,
            reason = reason,
            wordCount = committed.split(WORD_SEPARATOR).count(String::isNotBlank),
            durationMs = analysis.durationMs,
            rhythmBoundary = analysis.rhythmBoundary,
            speechMode = analysis.speechMode
        )
    }

    private fun analyze(
        text: String,
        nowMs: Long,
        tokenGapMs: Long,
        isFinal: Boolean
    ): SentenceAnalysis {
        val words = text.split(WORD_SEPARATOR).filter(String::isNotBlank)
        val normalizedWords = words.map { it.cleanToken().lowercase(Locale.US) }
        val durationMs = nowMs - openedAtMs
        val rhythmBoundary = rhythmBoundary(tokenGapMs)
        val mode = speechMode(words = normalizedWords, durationMs = durationMs, tokenGapMs = tokenGapMs)
        val newsMode = mode == SpeechRhythmMode.NEWS
        val fastMode = mode == SpeechRhythmMode.FAST_SPEECH
        val continuityScore = continuityScore(normalizedWords, text)

        return SentenceAnalysis(
            words = words,
            wordCount = words.size,
            durationMs = durationMs,
            rhythmBoundary = rhythmBoundary,
            speechMode = mode,
            candidateSplitIndex = candidateSplitIndex(normalizedWords),
            hasCandidateBoundary = hasCandidateBoundary(normalizedWords),
            hasReportingVerbBoundary = hasReportingVerbBoundary(normalizedWords),
            visualOverflow = visualOverflow(words.size),
            continuityScore = continuityScore,
            hasTerminalPunctuation = isFinal || text.lastOrNull() in TERMINAL_PUNCTUATION,
            wordLimit = when {
                newsMode -> config.newsWordLimit
                fastMode -> config.fastSpeechWordLimit
                else -> config.defaultWordLimit
            },
            durationLimitMs = when {
                newsMode -> config.newsDurationLimitMs
                fastMode -> config.fastSpeechDurationLimitMs
                else -> config.defaultDurationLimitMs
            },
            softDurationMs = when {
                newsMode -> config.newsSoftDurationMs
                fastMode -> config.fastSpeechSoftDurationMs
                else -> config.defaultSoftDurationMs
            }
        )
    }

    private fun rhythmBoundary(tokenGapMs: Long): RhythmBoundary {
        return when {
            tokenGapMs > config.strongTokenGapMs -> RhythmBoundary.STRONG_BOUNDARY
            tokenGapMs > config.softTokenGapMs -> RhythmBoundary.SOFT_BOUNDARY
            else -> RhythmBoundary.NONE
        }
    }

    private fun speechMode(
        words: List<String>,
        durationMs: Long,
        tokenGapMs: Long
    ): SpeechRhythmMode {
        val newsTerms = words.count { it in NEWS_TERMS }
        return when {
            words.size >= config.newsWordThreshold &&
                (durationMs >= config.newsDurationMs || newsTerms >= config.minNewsTerms) ->
                SpeechRhythmMode.NEWS
            words.size >= config.fastSpeechWordThreshold &&
                durationMs <= config.fastSpeechDurationMs &&
                tokenGapMs <= config.softTokenGapMs ->
                SpeechRhythmMode.FAST_SPEECH
            tokenGapMs > config.softTokenGapMs ->
                SpeechRhythmMode.BREATH_PAUSE
            else -> SpeechRhythmMode.CONTINUOUS
        }
    }

    private fun candidateSplitIndex(words: List<String>): Int? {
        val andTransition = words.withIndex().firstOrNull { (index, word) ->
            word == "and" &&
                index >= config.minAndBoundaryIndex &&
                index <= words.lastIndex - config.minWordsAfterBoundary
        }?.index
        if (andTransition != null) {
            return andTransition - 1
        }

        val reportingIndex = words.withIndex().firstOrNull { (index, word) ->
            word in REPORTING_VERBS &&
                index >= config.minWordsBeforeBoundary &&
                index <= words.lastIndex - config.minWordsAfterBoundary
        }?.index
        if (reportingIndex != null) {
            return reportingIndex
        }

        val connectorIndex = words.withIndex().firstOrNull { (index, word) ->
            word in SENTENCE_SPLIT_CONNECTORS &&
                index >= config.minSentenceSplitBoundary &&
                index <= words.lastIndex - config.minWordsAfterBoundary
        }?.index
        return connectorIndex?.minus(1)
    }

    private fun hasCandidateBoundary(words: List<String>): Boolean {
        return candidateSplitIndex(words) != null
    }

    private fun hasReportingVerbBoundary(words: List<String>): Boolean {
        return words.any { it in REPORTING_VERBS }
    }

    private fun visualOverflow(wordCount: Int): Boolean {
        return wordCount >= config.visualSoftWordLimit
    }

    private fun readabilitySplitIndex(words: List<String>): Int? {
        if (words.size < config.visualSoftWordLimit) {
            return null
        }
        val preferred = words.size / 2
        return (preferred downTo config.minCommitWords - 1).firstOrNull { index ->
            words.getOrNull(index + 1)?.cleanToken()?.lowercase(Locale.US) !in PROTECTED_SPLIT_STARTS
        }
    }

    private fun continuityScore(words: List<String>, text: String): Double {
        var score = 0.0
        if (words.any { it in SEMANTIC_CONNECTORS || it == "and" }) {
            score += 0.3
        }
        if (text.lastOrNull() !in TERMINAL_PUNCTUATION) {
            score += 0.3
        }
        if (containsProperNounChain(words)) {
            score += 0.2
        }
        if (words.any { it.any(Char::isDigit) || it in TIME_WORDS }) {
            score += 0.2
        }
        return score.coerceAtMost(1.0)
    }

    private fun containsProperNounChain(words: List<String>): Boolean {
        return words.windowed(2).any {
            it == listOf("wall", "street") ||
                it == listOf("private", "equity") ||
                it == listOf("united", "states") ||
                it == listOf("white", "house")
        } || words.any { it == "openai" }
    }

    private fun removeCommittedPrefix(text: String): String {
        if (lastCommittedRaw.isEmpty()) {
            return text
        }
        return if (text.startsWith(lastCommittedRaw, ignoreCase = true)) {
            text.drop(lastCommittedRaw.length).trim()
        } else {
            text
        }
    }

    private fun normalize(text: String): String {
        return text.replace(WORD_SEPARATOR, " ").trim()
    }

    private fun String.cleanToken(): String {
        return trim { it in TRIMMED_PUNCTUATION }
    }

    companion object {
        private val WORD_SEPARATOR = Regex("""\s+""")
        private val TERMINAL_PUNCTUATION = setOf('.', '!', '?')
        private val TRIMMED_PUNCTUATION = TERMINAL_PUNCTUATION + setOf(',', ':', ';')
        private val SEMANTIC_CONNECTORS = setOf(
            "but",
            "however",
            "because",
            "so",
            "although",
            "that",
            "which",
            "who"
        )
        private val SENTENCE_SPLIT_CONNECTORS = setOf("however", "so", "although")
        private val REPORTING_VERBS = setOf("said", "told", "reported")
        private val PROTECTED_SPLIT_STARTS = setOf("street", "equity", "states", "house")
        private val NEWS_TERMS = setOf(
            "government",
            "congress",
            "president",
            "market",
            "federal",
            "reserve",
            "inflation",
            "rates",
            "housing",
            "negotiations",
            "decision"
        )
        private val TIME_WORDS = setOf(
            "today",
            "tomorrow",
            "yesterday",
            "monday",
            "tuesday",
            "wednesday",
            "thursday",
            "friday",
            "saturday",
            "sunday",
            "january",
            "february",
            "march",
            "april",
            "may",
            "june",
            "july",
            "august",
            "september",
            "october",
            "november",
            "december"
        )
    }
}

data class SubtitleSentenceBufferConfig(
    val softTokenGapMs: Long = 450L,
    val strongTokenGapMs: Long = 900L,
    val defaultWordLimit: Int = 20,
    val newsWordLimit: Int = 26,
    val fastSpeechWordLimit: Int = 18,
    val defaultDurationLimitMs: Long = 5_500L,
    val newsDurationLimitMs: Long = 7_000L,
    val fastSpeechDurationLimitMs: Long = 4_500L,
    val defaultSoftDurationMs: Long = 2_800L,
    val newsSoftDurationMs: Long = 4_800L,
    val fastSpeechSoftDurationMs: Long = 2_000L,
    val maxOpenDurationMs: Long = 9_000L,
    val minFinalWords: Int = 5,
    val minFinalDurationMs: Long = 900L,
    val minMicroSplitWords: Int = 10,
    val minCommitWords: Int = 6,
    val minSilenceCommitWords: Int = 5,
    val minWordsBeforeBoundary: Int = 4,
    val minWordsAfterBoundary: Int = 3,
    val minSentenceSplitBoundary: Int = 8,
    val minAndBoundaryIndex: Int = 10,
    val visualSoftWordLimit: Int = 15,
    val continuityHoldThreshold: Double = 0.72,
    val downgradeContinuityThreshold: Double = 0.5,
    val newsWordThreshold: Int = 13,
    val newsDurationMs: Long = 2_200L,
    val minNewsTerms: Int = 1,
    val fastSpeechWordThreshold: Int = 12,
    val fastSpeechDurationMs: Long = 2_400L
)

enum class SentenceLifecycleState {
    OPEN,
    STABLE,
    FLUSHED
}

enum class SentenceFlushReason {
    FINAL_RESULT,
    HARD_LIMIT,
    SEMANTIC_BOUNDARY,
    SOFT_RHYTHM_BOUNDARY,
    STRONG_RHYTHM_BOUNDARY
}

enum class RhythmBoundary {
    NONE,
    SOFT_BOUNDARY,
    STRONG_BOUNDARY
}

enum class SpeechRhythmMode {
    CONTINUOUS,
    BREATH_PAUSE,
    NEWS,
    FAST_SPEECH
}

private data class SentenceAnalysis(
    val words: List<String>,
    val wordCount: Int,
    val durationMs: Long,
    val rhythmBoundary: RhythmBoundary,
    val speechMode: SpeechRhythmMode,
    val candidateSplitIndex: Int?,
    val hasCandidateBoundary: Boolean,
    val hasReportingVerbBoundary: Boolean,
    val visualOverflow: Boolean,
    val continuityScore: Double,
    val hasTerminalPunctuation: Boolean,
    val wordLimit: Int,
    val durationLimitMs: Long,
    val softDurationMs: Long
)

sealed interface SubtitleSentenceResult {
    data object None : SubtitleSentenceResult
    data class FinalSentence(
        val text: String,
        val lifecycleState: SentenceLifecycleState,
        val reason: SentenceFlushReason,
        val wordCount: Int,
        val durationMs: Long,
        val rhythmBoundary: RhythmBoundary,
        val speechMode: SpeechRhythmMode
    ) : SubtitleSentenceResult
}

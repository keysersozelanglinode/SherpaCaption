package com.sherpacaption.app.util

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object PerformanceMetrics {
    private const val LOG_INTERVAL_MS = 10_000L
    private const val NANOS_PER_MS = 1_000_000L

    private val firstTokenLogged = AtomicBoolean(false)
    private val firstPcmReceivedAt = AtomicLong(0L)
    private val firstAcceptWaveformAt = AtomicLong(0L)
    private val firstDecodeAt = AtomicLong(0L)
    private val firstNonEmptyResultAt = AtomicLong(0L)
    private val firstUiUpdateAt = AtomicLong(0L)
    private val firstReadyDecodeAt = AtomicLong(0L)

    private val pcmCount = AtomicLong(0L)
    private val zeroPcmCount = AtomicLong(0L)
    private val silentPcmCount = AtomicLong(0L)
    private val pcmIntervalTotalMs = AtomicLong(0L)
    private val pcmIntervalMaxMs = AtomicLong(0L)
    private val pcmAvgLevelTotal = AtomicLong(0L)
    private val pcmMaxLevelTotal = AtomicLong(0L)
    private val lastPcmAt = AtomicLong(0L)

    private val asrResultCount = AtomicLong(0L)
    private val asrResultIntervalTotalMs = AtomicLong(0L)
    private val lastAsrResultAt = AtomicLong(0L)

    private val uiUpdateCount = AtomicLong(0L)
    private val resultToUiTotalMs = AtomicLong(0L)
    private val resultToUiMaxMs = AtomicLong(0L)

    private val acceptStats = DurationStats()
    private val decodeStats = DurationStats()
    private val decodeQueueStats = DurationStats()
    private val getResultStats = DurationStats()
    private val resultAvailabilityStats = DurationStats()
    private val decodeGatingStats = DurationStats()
    private val staticLayoutStats = DurationStats()
    private val setTextStats = DurationStats()
    private val uiRenderStats = DurationStats()

    @Volatile
    private var lastPerfLogAt = 0L

    fun reset() {
        firstTokenLogged.set(false)
        firstPcmReceivedAt.set(0L)
        firstAcceptWaveformAt.set(0L)
        firstDecodeAt.set(0L)
        firstNonEmptyResultAt.set(0L)
        firstUiUpdateAt.set(0L)
        firstReadyDecodeAt.set(0L)
        pcmCount.set(0L)
        zeroPcmCount.set(0L)
        silentPcmCount.set(0L)
        pcmIntervalTotalMs.set(0L)
        pcmIntervalMaxMs.set(0L)
        pcmAvgLevelTotal.set(0L)
        pcmMaxLevelTotal.set(0L)
        lastPcmAt.set(0L)
        asrResultCount.set(0L)
        asrResultIntervalTotalMs.set(0L)
        lastAsrResultAt.set(0L)
        uiUpdateCount.set(0L)
        resultToUiTotalMs.set(0L)
        resultToUiMaxMs.set(0L)
        acceptStats.reset()
        decodeStats.reset()
        decodeQueueStats.reset()
        getResultStats.reset()
        resultAvailabilityStats.reset()
        decodeGatingStats.reset()
        staticLayoutStats.reset()
        setTextStats.reset()
        uiRenderStats.reset()
        lastPerfLogAt = SystemClock.elapsedRealtime()
    }

    fun markPcmReceived(sampleCount: Int, avgLevel: Int, maxLevel: Int) {
        val now = SystemClock.elapsedRealtime()
        firstPcmReceivedAt.compareAndSet(0L, now)
        val previous = lastPcmAt.getAndSet(now)
        if (previous > 0L) {
            val interval = now - previous
            pcmIntervalTotalMs.addAndGet(interval)
            pcmIntervalMaxMs.updateMax(interval)
        }
        pcmCount.incrementAndGet()
        if (sampleCount == 0 || maxLevel == 0) {
            zeroPcmCount.incrementAndGet()
        }
        if (avgLevel < 30 && maxLevel < 300) {
            silentPcmCount.incrementAndGet()
        }
        pcmAvgLevelTotal.addAndGet(avgLevel.toLong())
        pcmMaxLevelTotal.addAndGet(maxLevel.toLong())
    }

    fun markAcceptWaveform(durationNs: Long) {
        firstAcceptWaveformAt.compareAndSet(0L, SystemClock.elapsedRealtime())
        acceptStats.add(durationNs)
    }

    fun markDecode(durationNs: Long) {
        val now = SystemClock.elapsedRealtime()
        firstDecodeAt.compareAndSet(0L, now)
        firstReadyDecodeAt.compareAndSet(0L, now)
        decodeStats.add(durationNs)
    }

    fun markDecodeQueue(durationNs: Long) {
        decodeQueueStats.add(durationNs)
    }

    fun markGetResult(durationNs: Long, text: String) {
        getResultStats.add(durationNs)
        if (text.isBlank()) {
            return
        }

        val now = SystemClock.elapsedRealtime()
        firstNonEmptyResultAt.compareAndSet(0L, now)
        val previous = lastAsrResultAt.getAndSet(now)
        if (previous > 0L) {
            asrResultIntervalTotalMs.addAndGet(now - previous)
        }
        asrResultCount.incrementAndGet()
    }

    fun markResultAvailability(durationNs: Long) {
        resultAvailabilityStats.add(durationNs)
    }

    fun markDecodeGatingDelay(durationNs: Long) {
        decodeGatingStats.add(durationNs)
    }

    fun markUiUpdate(resultCreatedAtMs: Long) {
        val now = SystemClock.elapsedRealtime()
        firstUiUpdateAt.compareAndSet(0L, now)
        uiUpdateCount.incrementAndGet()
        if (resultCreatedAtMs > 0L) {
            val duration = now - resultCreatedAtMs
            resultToUiTotalMs.addAndGet(duration)
            resultToUiMaxMs.updateMax(duration)
        }
        logFirstTokenLatencyIfReady()
    }

    fun markStaticLayout(durationNs: Long) {
        staticLayoutStats.add(durationNs)
    }

    fun markSetText(durationNs: Long) {
        setTextStats.add(durationNs)
    }

    fun markUiRender(durationNs: Long) {
        uiRenderStats.add(durationNs)
    }

    fun logPerfIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPerfLogAt < LOG_INTERVAL_MS) {
            return
        }
        lastPerfLogAt = now

        val runtime = Runtime.getRuntime()
        val javaUsedMb = (runtime.totalMemory() - runtime.freeMemory()).toMb()
        val nativeMb = Debug.getNativeHeapAllocatedSize().toMb()
        val totalMb = javaUsedMb + nativeMb
        val pcmTotal = pcmCount.get()
        val asrTotal = asrResultCount.get()
        val uiTotal = uiUpdateCount.get()
        val threadCount = Thread.activeCount()

        Log.i(
            LogTags.SHERPA_CAPTION,
            "PERF memoryJavaMb=$javaUsedMb memoryNativeMb=$nativeMb " +
                "memoryTotalMb=$totalMb threadCount=$threadCount " +
                "availableProcessors=${runtime.availableProcessors()} " +
                "pcmPipeline=direct pcmCount=$pcmTotal " +
                "avgPcmIntervalMs=${average(pcmIntervalTotalMs.get(), (pcmTotal - 1).coerceAtLeast(0L))} " +
                "maxPcmIntervalMs=${pcmIntervalMaxMs.get()} " +
                "avgPcmAvgLevel=${average(pcmAvgLevelTotal.get(), pcmTotal)} " +
                "avgPcmMaxLevel=${average(pcmMaxLevelTotal.get(), pcmTotal)} " +
                "zeroPcmCount=${zeroPcmCount.get()} silentPcmCount=${silentPcmCount.get()} " +
                "asrResultCount=$asrTotal uiUpdateCount=$uiTotal " +
                "firstTokenLatencyMs=${firstTokenLatencyMs()} " +
                "avgAcceptMs=${acceptStats.averageMs()} maxAcceptMs=${acceptStats.maxMs()} " +
                "avgDecodeQueueMs=${decodeQueueStats.averageMs()} maxDecodeQueueMs=${decodeQueueStats.maxMs()} " +
                "avgDecodeMs=${decodeStats.averageMs()} maxDecodeMs=${decodeStats.maxMs()} " +
                "avgGetResultMs=${getResultStats.averageMs()} maxGetResultMs=${getResultStats.maxMs()} " +
                "avgResultAvailabilityMs=${resultAvailabilityStats.averageMs()} " +
                "maxResultAvailabilityMs=${resultAvailabilityStats.maxMs()} " +
                "avgDecodeGatingMs=${decodeGatingStats.averageMs()} " +
                "maxDecodeGatingMs=${decodeGatingStats.maxMs()} " +
                "encoderWarmupMs=${encoderWarmupMs()} " +
                "firstHypothesisMs=${firstHypothesisMs()} " +
                "avgAsrResultIntervalMs=${average(asrResultIntervalTotalMs.get(), (asrTotal - 1).coerceAtLeast(0L))} " +
                "avgResultToUiMs=${average(resultToUiTotalMs.get(), uiTotal)} " +
                "maxResultToUiMs=${resultToUiMaxMs.get()} " +
                "avgUiRenderMs=${uiRenderStats.averageMs()} maxUiRenderMs=${uiRenderStats.maxMs()} " +
                "avgStaticLayoutMs=${staticLayoutStats.averageMs()} maxStaticLayoutMs=${staticLayoutStats.maxMs()} " +
                "avgSetTextMs=${setTextStats.averageMs()} maxSetTextMs=${setTextStats.maxMs()} " +
                "cpuHint=unavailable"
        )
    }

    private fun logFirstTokenLatencyIfReady() {
        if (firstTokenLogged.get()) {
            return
        }
        val pcmAt = firstPcmReceivedAt.get()
        val acceptAt = firstAcceptWaveformAt.get()
        val decodeAt = firstDecodeAt.get()
        val resultAt = firstNonEmptyResultAt.get()
        val uiAt = firstUiUpdateAt.get()
        if (pcmAt == 0L || acceptAt == 0L || decodeAt == 0L || resultAt == 0L || uiAt == 0L) {
            return
        }
        if (!firstTokenLogged.compareAndSet(false, true)) {
            return
        }
        Log.i(
            LogTags.SHERPA_CAPTION,
            "FIRST_TOKEN_LATENCY pcmToResultMs=${resultAt - pcmAt} " +
                "pcmToUiMs=${uiAt - pcmAt} acceptToResultMs=${resultAt - acceptAt} " +
                "decodeToResultMs=${resultAt - decodeAt} resultToUiMs=${uiAt - resultAt} " +
                "encoderWarmupMs=${encoderWarmupMs()} firstHypothesisMs=${firstHypothesisMs()}"
        )
    }

    private fun firstTokenLatencyMs(): Long {
        val pcmAt = firstPcmReceivedAt.get()
        val resultAt = firstNonEmptyResultAt.get()
        return if (pcmAt > 0L && resultAt > 0L) resultAt - pcmAt else -1L
    }

    private fun encoderWarmupMs(): Long {
        val pcmAt = firstPcmReceivedAt.get()
        val decodeAt = firstReadyDecodeAt.get()
        return if (pcmAt > 0L && decodeAt > 0L) decodeAt - pcmAt else -1L
    }

    private fun firstHypothesisMs(): Long {
        val decodeAt = firstReadyDecodeAt.get()
        val resultAt = firstNonEmptyResultAt.get()
        return if (decodeAt > 0L && resultAt > 0L) resultAt - decodeAt else -1L
    }

    private fun average(total: Long, count: Long): Long {
        return if (count > 0L) total / count else 0L
    }

    private fun Long.toMb(): Long = this / (1024L * 1024L)

    private fun AtomicLong.updateMax(value: Long) {
        while (true) {
            val current = get()
            if (value <= current || compareAndSet(current, value)) {
                return
            }
        }
    }

    private class DurationStats {
        private val count = AtomicLong(0L)
        private val totalNs = AtomicLong(0L)
        private val maxNs = AtomicLong(0L)

        fun add(durationNs: Long) {
            count.incrementAndGet()
            totalNs.addAndGet(durationNs)
            maxNs.updateMax(durationNs)
        }

        fun averageMs(): Long {
            val currentCount = count.get()
            return if (currentCount > 0L) {
                totalNs.get() / currentCount / NANOS_PER_MS
            } else {
                0L
            }
        }

        fun maxMs(): Long = maxNs.get() / NANOS_PER_MS

        fun reset() {
            count.set(0L)
            totalNs.set(0L)
            maxNs.set(0L)
        }
    }
}

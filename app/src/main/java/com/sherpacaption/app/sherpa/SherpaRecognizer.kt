package com.sherpacaption.app.sherpa

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.VersionInfo
import com.sherpacaption.app.audio.AudioCaptureConfig
import com.sherpacaption.app.util.LogTags
import com.sherpacaption.app.util.PerformanceMetrics
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SherpaRecognizer(
    context: Context,
    private val listener: Listener,
    private val modelAssetDirectory: String = DEFAULT_MODEL_ASSET_DIRECTORY
) {
    private val appContext = context.applicationContext
    private val lock = Object()
    private val isRunning = AtomicBoolean(false)
    private val threadPriorityApplied = AtomicBoolean(false)
    private val acceptedFrames = AtomicLong(0L)
    private val lastAcceptedAtNs = AtomicLong(0L)
    private val decodeTickExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "SherpaDecodeTick").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY + 1
            }
        }
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var lastEmittedText = ""

    init {
        runCatching {
            val versionInfo = VersionInfo()
            Log.i(
                LogTags.SHERPA_CAPTION,
                "sherpa-onnx AAR loaded: ${versionInfo.javaClass.name}"
            )
        }.onFailure { error ->
            Log.e(LogTags.SHERPA_CAPTION, "sherpa-onnx native library load failed", error)
        }
        initialize()
    }

    fun acceptPcm16(
        samples: ShortArray,
        sampleCount: Int,
        config: AudioCaptureConfig
    ) {
        val currentRecognizer: OnlineRecognizer
        val currentStream: OnlineStream
        synchronized(lock) {
            if (!isRunning.get()) {
                return
            }
            currentRecognizer = recognizer ?: return
            currentStream = stream ?: return
        }

        val input = runCatching {
            AsrInputPreparer.prepare(samples, sampleCount, config)
        }.onFailure { error ->
            listener.onError("ASR input failed: ${error.description()}")
        }.getOrNull() ?: return

        if (input.samples.isEmpty()) {
            return
        }

        applyDecodeThreadPriorityIfNeeded()
        synchronized(lock) {
            if (!isRunning.get()) {
                return
            }
            runCatching {
                input.samples.feedInLowLatencyChunks { chunk ->
                    val acceptStartNs = SystemClock.elapsedRealtimeNanos()
                    currentStream.acceptWaveform(chunk, input.sampleRate)
                    val acceptedAtNs = SystemClock.elapsedRealtimeNanos()
                    lastAcceptedAtNs.set(acceptedAtNs)
                    PerformanceMetrics.markAcceptWaveform(acceptedAtNs - acceptStartNs)
                    val frames = acceptedFrames.addAndGet(chunk.size.toLong())
                    decodeReadyChunks(
                        recognizer = currentRecognizer,
                        stream = currentStream,
                        frames = frames,
                        acceptedAtNs = acceptedAtNs
                    )
                }
            }.onFailure { error ->
                val message = "ASR failed: ${error.description()}"
                Log.e(LogTags.SHERPA_CAPTION, message, error)
                listener.onError(message)
            }
        }
    }

    fun release() {
        synchronized(lock) {
            if (!isRunning.getAndSet(false)) {
                return
            }
            runCatching { stream?.release() }
                .onFailure { Log.w(LogTags.SHERPA_CAPTION, "OnlineStream release failed", it) }
            runCatching { recognizer?.release() }
                .onFailure { Log.w(LogTags.SHERPA_CAPTION, "OnlineRecognizer release failed", it) }
            stream = null
            recognizer = null
            lastEmittedText = ""
            decodeTickExecutor.shutdownNow()
            Log.i(LogTags.SHERPA_CAPTION, "ASR state=IDLE")
        }
    }

    private fun initialize() {
        synchronized(lock) {
            runCatching {
                val modelFiles = prepareModelFiles()
                val createdRecognizer = createRecognizer(modelFiles)
                recognizer = createdRecognizer
                stream = createdRecognizer.createStream()
                acceptedFrames.set(0L)
                lastAcceptedAtNs.set(0L)
                lastEmittedText = ""
                isRunning.set(true)
                startDecodeTick()
                Log.i(LogTags.SHERPA_CAPTION, "ASR state=RUNNING")
            }.onFailure { error ->
                val message = "ASR init failed: ${error.description()}"
                Log.e(LogTags.SHERPA_CAPTION, message, error)
                listener.onError(message)
            }
        }
    }

    private fun prepareModelFiles(): ModelFiles {
        val assetNames = appContext.assets.list(modelAssetDirectory).orEmpty().toSet()
        val encoderName = ENCODER_FILE
        val decoderName = selectAvailableFile(assetNames, DECODER_FILES)
        val joinerName = selectAvailableFile(assetNames, JOINER_FILES)
        val requiredNames = listOf(TOKENS_FILE, encoderName, decoderName, joinerName)
        val missingNames = requiredNames.filterNot(assetNames::contains)

        if (missingNames.isNotEmpty()) {
            error("missing model file(s): ${missingNames.joinToString()}")
        }

        val outputDirectory = File(appContext.filesDir, modelAssetDirectory)
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            error("cannot create model directory: ${outputDirectory.absolutePath}")
        }

        requiredNames.forEach { fileName ->
            copyAssetIfNeeded(
                assetPath = "$modelAssetDirectory/$fileName",
                outputFile = File(outputDirectory, fileName)
            )
        }
        OPTIONAL_BPE_FILES.filter(assetNames::contains).forEach { fileName ->
            copyAssetIfNeeded(
                assetPath = "$modelAssetDirectory/$fileName",
                outputFile = File(outputDirectory, fileName)
            )
        }

        return ModelFiles(
            encoder = File(outputDirectory, encoderName).absolutePath,
            decoder = File(outputDirectory, decoderName).absolutePath,
            joiner = File(outputDirectory, joinerName).absolutePath,
            tokens = File(outputDirectory, TOKENS_FILE).absolutePath
        )
    }

    private fun selectAvailableFile(
        assetNames: Set<String>,
        candidates: List<String>
    ): String {
        return candidates.firstOrNull(assetNames::contains) ?: candidates.first()
    }

    private fun copyAssetIfNeeded(assetPath: String, outputFile: File) {
        appContext.assets.open(assetPath).use { input ->
            if (outputFile.isFile && outputFile.length() == input.available().toLong()) {
                return
            }
            outputFile.outputStream().buffered().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun createRecognizer(modelFiles: ModelFiles): OnlineRecognizer {
        val profileConfig = SELECTED_PROFILE.toConfig()
        val transducerConfig = OnlineTransducerModelConfig().apply {
            encoder = modelFiles.encoder
            decoder = modelFiles.decoder
            joiner = modelFiles.joiner
        }
        val modelConfig = OnlineModelConfig().apply {
            transducer = transducerConfig
            tokens = modelFiles.tokens
            numThreads = profileConfig.numThreads
            provider = PROVIDER
            modelType = MODEL_TYPE
        }
        val recognizerConfig = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = FEATURE_DIM
            }
            this.modelConfig = modelConfig
            decodingMethod = profileConfig.decodingMethod
            maxActivePaths = profileConfig.maxActivePaths
            hotwordsScore = profileConfig.hotwordsScore
            hotwordsFile = ""
            enableEndpoint = true
        }

        Log.i(
            LogTags.SHERPA_CAPTION,
            "ASR decoder profile=${profileConfig.profile} " +
                "decodingMethod=${profileConfig.decodingMethod} " +
                "maxActivePaths=${profileConfig.maxActivePaths} " +
                "numThreads=${profileConfig.numThreads} hotwords=disabled"
        )
        return OnlineRecognizer(null, recognizerConfig)
    }

    private fun decodeReadyChunks(
        recognizer: OnlineRecognizer,
        stream: OnlineStream,
        frames: Long,
        acceptedAtNs: Long
    ) {
        var decodedAny = false
        while (recognizer.isReady(stream)) {
            val decodeStartNs = SystemClock.elapsedRealtimeNanos()
            PerformanceMetrics.markDecodeQueue(decodeStartNs - acceptedAtNs)
            recognizer.decode(stream)
            decodedAny = true
            PerformanceMetrics.markDecode(SystemClock.elapsedRealtimeNanos() - decodeStartNs)
            emitResultIfChanged(recognizer, stream, frames, acceptedAtNs)
        }

        if (!decodedAny) {
            emitResultIfChanged(recognizer, stream, frames, acceptedAtNs)
        }
    }

    private fun runTimeDrivenDecodeTick() {
        val currentRecognizer: OnlineRecognizer
        val currentStream: OnlineStream
        synchronized(lock) {
            if (!isRunning.get()) {
                return
            }
            currentRecognizer = recognizer ?: return
            currentStream = stream ?: return
            val acceptedAtNs = lastAcceptedAtNs.get()
            if (acceptedAtNs == 0L) {
                return
            }
            val tickStartNs = SystemClock.elapsedRealtimeNanos()
            PerformanceMetrics.markDecodeGatingDelay(tickStartNs - acceptedAtNs)
            decodeReadyChunks(
                recognizer = currentRecognizer,
                stream = currentStream,
                frames = acceptedFrames.get(),
                acceptedAtNs = acceptedAtNs
            )
        }
    }

    private fun emitResultIfChanged(
        recognizer: OnlineRecognizer,
        stream: OnlineStream,
        frames: Long,
        acceptedAtNs: Long
    ) {
        val getResultStartNs = SystemClock.elapsedRealtimeNanos()
        val text = recognizer.getResult(stream).text.trim()
        val resultAtNs = SystemClock.elapsedRealtimeNanos()
        PerformanceMetrics.markGetResult(resultAtNs - getResultStartNs, text)
        if (text.isBlank() || text == lastEmittedText) {
            return
        }

        lastEmittedText = text
        PerformanceMetrics.markResultAvailability(resultAtNs - acceptedAtNs)
        Log.d(LogTags.SHERPA_CAPTION, "PCM_RECEIVED frames=$frames")
        Log.d(LogTags.SHERPA_CAPTION, "ASR_RESULT text=$text")
        listener.onResult(text, frames)
    }

    private fun FloatArray.feedInLowLatencyChunks(block: (FloatArray) -> Unit) {
        if (size <= LOW_LATENCY_FEED_CHUNK_SAMPLES) {
            block(this)
            return
        }

        var offset = 0
        while (offset < size) {
            val nextOffset = (offset + LOW_LATENCY_FEED_CHUNK_SAMPLES).coerceAtMost(size)
            block(copyOfRange(offset, nextOffset))
            offset = nextOffset
        }
    }

    private fun applyDecodeThreadPriorityIfNeeded() {
        if (!threadPriorityApplied.compareAndSet(false, true)) {
            return
        }

        runCatching {
            Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
            Log.i(LogTags.SHERPA_CAPTION, "ASR decode thread priority=more_favorable")
        }.onFailure { error ->
            Log.w(LogTags.SHERPA_CAPTION, "ASR thread priority update failed", error)
        }
    }

    private fun startDecodeTick() {
        decodeTickExecutor.scheduleAtFixedRate(
            {
                applyDecodeThreadPriorityIfNeeded()
                runCatching { runTimeDrivenDecodeTick() }
                    .onFailure { error ->
                        Log.w(LogTags.SHERPA_CAPTION, "ASR decode tick failed", error)
                    }
            },
            DECODE_TICK_INTERVAL_MS,
            DECODE_TICK_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        )
        Log.i(LogTags.SHERPA_CAPTION, "ASR decode tick interval=${DECODE_TICK_INTERVAL_MS}ms")
    }

    private fun Throwable.description(): String {
        return message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
    }

    interface Listener {
        fun onResult(text: String, frames: Long)
        fun onError(message: String)
    }

    private data class ModelFiles(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String
    )

    enum class AsrProfile {
        LOW_LATENCY,
        BALANCED,
        ACCURACY
    }

    private data class DecoderProfileConfig(
        val profile: AsrProfile,
        val decodingMethod: String,
        val maxActivePaths: Int,
        val numThreads: Int,
        val hotwordsScore: Float
    )

    private fun AsrProfile.toConfig(): DecoderProfileConfig {
        return when (this) {
            AsrProfile.LOW_LATENCY -> DecoderProfileConfig(
                profile = this,
                decodingMethod = DECODING_METHOD_GREEDY,
                maxActivePaths = 1,
                numThreads = 2,
                hotwordsScore = 0.0f
            )

            AsrProfile.BALANCED -> DecoderProfileConfig(
                profile = this,
                decodingMethod = DECODING_METHOD_MODIFIED_BEAM_SEARCH,
                maxActivePaths = 2,
                numThreads = 2,
                hotwordsScore = 0.0f
            )

            AsrProfile.ACCURACY -> DecoderProfileConfig(
                profile = this,
                decodingMethod = DECODING_METHOD_MODIFIED_BEAM_SEARCH,
                maxActivePaths = 4,
                numThreads = 4,
                hotwordsScore = 0.0f
            )
        }
    }

    companion object {
        const val DEFAULT_MODEL_ASSET_DIRECTORY =
            "sherpa-onnx/streaming-zipformer-en-2023-06-26"

        private const val TOKENS_FILE = "tokens.txt"
        private const val BPE_MODEL_FILE = "bpe.model"
        private const val BPE_VOCAB_FILE = "bpe.vocab"
        private val OPTIONAL_BPE_FILES = listOf(BPE_MODEL_FILE, BPE_VOCAB_FILE)
        private const val ENCODER_FILE =
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx"
        private val DECODER_FILES = listOf(
            "decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "decoder-epoch-99-avg-1-chunk-16-left-128.onnx"
        )
        private val JOINER_FILES = listOf(
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "joiner-epoch-99-avg-1-chunk-16-left-128.onnx"
        )

        private const val SAMPLE_RATE = 16_000
        private const val FEATURE_DIM = 80
        private const val PROVIDER = "cpu"
        private const val MODEL_TYPE = "zipformer2"
        private val SELECTED_PROFILE = AsrProfile.BALANCED
        private const val DECODING_METHOD_GREEDY = "greedy_search"
        private const val DECODING_METHOD_MODIFIED_BEAM_SEARCH = "modified_beam_search"
        private const val LOW_LATENCY_FEED_CHUNK_SAMPLES = 640
        private const val DECODE_TICK_INTERVAL_MS = 40L
    }
}

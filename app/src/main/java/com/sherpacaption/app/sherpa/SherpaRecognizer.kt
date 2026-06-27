package com.sherpacaption.app.sherpa

import android.content.Context
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
import com.sherpacaption.app.util.ASRStateController
import com.sherpacaption.app.util.AsrRuntimeState
import com.sherpacaption.app.util.LogTags
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class SherpaRecognizer(
    context: Context,
    private val listener: Listener,
    private val modelAssetDirectory: String = DEFAULT_MODEL_ASSET_DIRECTORY
) {
    private val appContext = context.applicationContext
    private val isRunning = AtomicBoolean(true)
    private val acceptsInput = AtomicBoolean(true)
    private val acceptedFrames = AtomicLong(0L)
    private val inputQueue = LinkedBlockingQueue<PreparedAsrInput>(MAX_QUEUED_INPUTS)
    private val workerThread = Thread(::recognitionLoop, "SherpaRecognizer").apply {
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, error ->
            acceptsInput.set(false)
            val message = initFailureMessage(error)
            Log.e(LogTags.SHERPA_CAPTION, "ASR worker uncaught exception", error)
            notifyStatus(
                state = SherpaRecognizerState.ERROR,
                errorMessage = message
            )
        }
    }

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
        workerThread.start()
    }

    fun acceptPcm16(
        samples: ShortArray,
        sampleCount: Int,
        config: AudioCaptureConfig
    ) {
        if (!isRunning.get() || !acceptsInput.get()) {
            return
        }

        val preparedInput = runCatching {
            AsrInputPreparer.prepare(samples, sampleCount, config)
        }.onFailure { error ->
            acceptsInput.set(false)
            val message = "ASR input preparation failed: ${error.description()}"
            Log.e(LogTags.SHERPA_CAPTION, message, error)
            notifyStatus(
                state = SherpaRecognizerState.ERROR,
                errorMessage = message
            )
        }.getOrNull() ?: return

        if (preparedInput.samples.isEmpty()) {
            return
        }

        if (!inputQueue.offer(preparedInput)) {
            Log.w(LogTags.SHERPA_CAPTION, "ASR input queue full; dropped newest PCM chunk")
        }
    }

    fun clearPendingInput(reason: String) {
        val cleared = inputQueue.size
        inputQueue.clear()
        Log.i(
            LogTags.SHERPA_CAPTION,
            "ASR_QUEUE_CLEARED size=$cleared reason=$reason"
        )
    }

    fun release() {
        if (!isRunning.getAndSet(false)) {
            return
        }

        acceptsInput.set(false)
        inputQueue.clear()
        workerThread.interrupt()
        if (Thread.currentThread() !== workerThread) {
            runCatching { workerThread.join(RELEASE_JOIN_TIMEOUT_MS) }
                .onFailure {
                    Log.w(LogTags.SHERPA_CAPTION, "Waiting for SherpaRecognizer failed", it)
                }
        }
        Log.i(LogTags.SHERPA_CAPTION, "SherpaRecognizer released")
        ASRStateController.setAsrState(AsrRuntimeState.STOPPED)
    }

    private fun recognitionLoop() {
        var recognizer: OnlineRecognizer? = null
        var stream: OnlineStream? = null
        var initialized = false
        var lastLatencyHintLogTime = 0L

        try {
            Log.i(LogTags.SHERPA_CAPTION, "ASR init start")
            notifyStatus(state = SherpaRecognizerState.INITIALIZING)
            val modelFiles = prepareModelFiles()
            val decoderMode = selectedDecoderMode()
            Log.i(LogTags.SHERPA_CAPTION, "DECODER_MODE=${decoderMode.name}")
            val hotwordPreparation = HotwordManager(
                outputDirectory = File(appContext.filesDir, HOTWORDS_DIRECTORY)
            ).prepare(
                bpeModelFile = modelFiles.bpeModel?.let(::File),
                bpeVocabFile = modelFiles.bpeVocab?.let(::File)
            )
            val recognizerSetup = createRecognizerWithFallback(
                modelFiles = modelFiles,
                hotwordPreparation = hotwordPreparation,
                decoderMode = decoderMode
            )
            recognizer = recognizerSetup.recognizer
            ASRStateController.setRecognitionConfig(
                recognitionMode = recognizerSetup.modeName,
                hotwordsEnabled = recognizerSetup.hotwordsEnabled
            )
            recognizerSetup.warning?.let { warning ->
                Log.w(LogTags.SHERPA_CAPTION, warning)
                notifyStatus(
                    state = SherpaRecognizerState.INITIALIZING,
                    noticeMessage = warning
                )
            }
            Log.i(
                LogTags.SHERPA_CAPTION,
                "ASR config: modelType=$MODEL_TYPE, " +
                    "decoding=${recognizerSetup.decodingMethod}, " +
                    "mode=${recognizerSetup.modeName}, " +
                    "maxActivePaths=${recognizerSetup.maxActivePaths}, " +
                    "threads=$NUM_THREADS, sampleRate=$SAMPLE_RATE, " +
                    "featureDim=$FEATURE_DIM, hotwords=${recognizerSetup.hotwordsEnabled}, " +
                    "hotwordsScore=${recognizerSetup.hotwordsScore}"
            )
            stream = recognizer.createStream()
            initialized = true
            Log.i(LogTags.SHERPA_CAPTION, "ASR init success")
            Log.i(LogTags.SHERPA_CAPTION, "ASR_LIFECYCLE_FIXED")
            Log.i(LogTags.SHERPA_CAPTION, "ASR_INPUT_FLOW_ONLY")
            Log.i(LogTags.SHERPA_CAPTION, "ASR_NO_RESET_POLICY")
            Log.i(LogTags.SHERPA_CAPTION, "ASR decode loop start")
            notifyStatus(state = SherpaRecognizerState.RUNNING)

            while (isRunning.get()) {
                val input = inputQueue.poll(INPUT_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    ?: continue

                try {
                    stream.acceptWaveform(input.samples, input.sampleRate)
                    val frames = acceptedFrames.addAndGet(input.samples.size.toLong())
                    ASRStateController.setAsrState(AsrRuntimeState.RUNNING)
                    ASRStateController.setFrames(frames)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLatencyHintLogTime >= LATENCY_HINT_LOG_INTERVAL_MS) {
                        Log.d(
                            LogTags.SHERPA_CAPTION,
                            "ASR_LATENCY_HINT frames=$frames queue=${inputQueue.size}"
                        )
                        lastLatencyHintLogTime = now
                    }
                    while (recognizer.isReady(stream)) {
                        recognizer.decode(stream)
                    }

                    val text = recognizer.getResult(stream).text.trim()
                    val isFinal = recognizer.isEndpoint(stream)
                    notifyStatus(
                        state = SherpaRecognizerState.RUNNING,
                        partialText = text,
                        isFinal = isFinal
                    )

                } catch (error: Throwable) {
                    acceptsInput.set(false)
                    val message = "ASR decode failed: ${error.description()}"
                    Log.e(LogTags.SHERPA_CAPTION, "ASR decode loop fail", error)
                    notifyStatus(
                        state = SherpaRecognizerState.ERROR,
                        errorMessage = message
                    )
                    break
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: MissingModelException) {
            acceptsInput.set(false)
            val message = error.message.orEmpty()
            Log.e(LogTags.SHERPA_CAPTION, "ASR init fail: $message")
            notifyStatus(
                state = SherpaRecognizerState.ERROR,
                errorMessage = message
            )
        } catch (error: Throwable) {
            acceptsInput.set(false)
            val message = if (initialized) {
                "ASR decode failed: ${error.description()}"
            } else {
                initFailureMessage(error)
            }
            Log.e(
                LogTags.SHERPA_CAPTION,
                if (initialized) "ASR decode loop fail" else "ASR init fail",
                error
            )
            notifyStatus(
                state = SherpaRecognizerState.ERROR,
                errorMessage = message
            )
        } finally {
            runCatching { stream?.release() }
                .onFailure { Log.w(LogTags.SHERPA_CAPTION, "OnlineStream release failed", it) }
            runCatching { recognizer?.release() }
                .onFailure { Log.w(LogTags.SHERPA_CAPTION, "OnlineRecognizer release failed", it) }
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
            throw MissingModelException(
                "ASR init failed: missing model file(s): ${missingNames.joinToString()}"
            )
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
            tokens = File(outputDirectory, TOKENS_FILE).absolutePath,
            bpeModel = File(outputDirectory, BPE_MODEL_FILE)
                .takeIf(File::isFile)
                ?.absolutePath,
            bpeVocab = File(outputDirectory, BPE_VOCAB_FILE)
                .takeIf(File::isFile)
                ?.absolutePath
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

    private fun createRecognizerWithFallback(
        modelFiles: ModelFiles,
        hotwordPreparation: HotwordManager.HotwordPreparation,
        decoderMode: DecoderMode
    ): RecognizerSetup {
        if (!decoderMode.hotwordsEnabled) {
            return RecognizerSetup(
                recognizer = createRecognizer(
                    modelFiles = modelFiles,
                    decodingMethod = decoderMode.decodingMethod,
                    hotwordPreparation = null,
                    maxActivePaths = decoderMode.maxActivePaths,
                    hotwordsScore = decoderMode.hotwordsScore
                ).also(::requireValidNativePointer),
                decodingMethod = decoderMode.decodingMethod,
                hotwordsEnabled = false,
                modeName = decoderMode.name,
                maxActivePaths = decoderMode.maxActivePaths,
                hotwordsScore = decoderMode.hotwordsScore
            )
        }

        if (hotwordPreparation is HotwordManager.HotwordPreparation.Enabled) {
            val hotwordAttempt = runCatching {
                createRecognizer(
                    modelFiles = modelFiles,
                    decodingMethod = decoderMode.decodingMethod,
                    hotwordPreparation = hotwordPreparation,
                    maxActivePaths = decoderMode.maxActivePaths,
                    hotwordsScore = decoderMode.hotwordsScore
                ).also(::requireValidNativePointer)
            }
            hotwordAttempt.getOrNull()?.let { recognizer ->
                return RecognizerSetup(
                    recognizer = recognizer,
                    decodingMethod = decoderMode.decodingMethod,
                    hotwordsEnabled = true,
                    modeName = decoderMode.name,
                    maxActivePaths = decoderMode.maxActivePaths,
                    hotwordsScore = decoderMode.hotwordsScore
                )
            }

            val reason = hotwordAttempt.exceptionOrNull()?.description()
                ?: "native recognizer initialization returned an invalid handle"
            return RecognizerSetup(
                recognizer = createRecognizer(
                    modelFiles = modelFiles,
                    decodingMethod = decoderMode.decodingMethod,
                    hotwordPreparation = null,
                    maxActivePaths = decoderMode.maxActivePaths,
                    hotwordsScore = decoderMode.hotwordsScore
                ).also(::requireValidNativePointer),
                decodingMethod = decoderMode.decodingMethod,
                hotwordsEnabled = false,
                modeName = decoderMode.name,
                maxActivePaths = decoderMode.maxActivePaths,
                hotwordsScore = decoderMode.hotwordsScore,
                warning = "Hotwords disabled: $reason"
            )
        }

        val reason = (hotwordPreparation as HotwordManager.HotwordPreparation.Disabled).reason
        return RecognizerSetup(
            recognizer = createRecognizer(
                modelFiles = modelFiles,
                decodingMethod = decoderMode.decodingMethod,
                hotwordPreparation = null,
                maxActivePaths = decoderMode.maxActivePaths,
                hotwordsScore = decoderMode.hotwordsScore
            ).also(::requireValidNativePointer),
            decodingMethod = decoderMode.decodingMethod,
            hotwordsEnabled = false,
            modeName = decoderMode.name,
            maxActivePaths = decoderMode.maxActivePaths,
            hotwordsScore = decoderMode.hotwordsScore,
            warning = "Hotwords disabled: $reason"
        )
    }

    private fun createRecognizer(
        modelFiles: ModelFiles,
        decodingMethod: String,
        hotwordPreparation: HotwordManager.HotwordPreparation.Enabled?,
        maxActivePaths: Int,
        hotwordsScore: Float
    ): OnlineRecognizer {
        val transducerConfig = OnlineTransducerModelConfig().apply {
            encoder = modelFiles.encoder
            decoder = modelFiles.decoder
            joiner = modelFiles.joiner
        }
        val modelConfig = OnlineModelConfig().apply {
            transducer = transducerConfig
            tokens = modelFiles.tokens
            numThreads = NUM_THREADS
            provider = PROVIDER
            modelType = MODEL_TYPE
            modelingUnit = if (hotwordPreparation != null) MODELING_UNIT_BPE else ""
            bpeVocab = hotwordPreparation?.bpeVocabFile?.absolutePath.orEmpty()
        }
        val recognizerConfig = OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = FEATURE_DIM
            }
            this.modelConfig = modelConfig
            this.decodingMethod = decodingMethod
            this.maxActivePaths = maxActivePaths
            this.hotwordsScore = hotwordsScore
            hotwordsFile = hotwordPreparation?.hotwordsFile?.absolutePath.orEmpty()
            enableEndpoint = true
        }

        return OnlineRecognizer(null, recognizerConfig)
    }

    private fun requireValidNativePointer(recognizer: OnlineRecognizer) {
        val pointer = runCatching {
            OnlineRecognizer::class.java
                .getDeclaredField("ptr")
                .apply { isAccessible = true }
                .getLong(recognizer)
        }.getOrElse { error ->
            recognizer.release()
            error("cannot validate native recognizer handle: ${error.description()}")
        }
        if (pointer == 0L) {
            recognizer.release()
            error("native recognizer initialization returned a null handle")
        }
    }

    private fun selectedDecoderMode(): DecoderMode {
        return when (DECODER_MODE) {
            DECODER_MODE_LOW_LATENCY -> DecoderMode(
                name = DECODER_MODE_LOW_LATENCY,
                decodingMethod = DECODING_METHOD_GREEDY,
                maxActivePaths = LOW_LATENCY_MAX_ACTIVE_PATHS,
                hotwordsEnabled = false,
                hotwordsScore = HOTWORDS_SCORE
            )

            DECODER_MODE_ACCURACY -> DecoderMode(
                name = DECODER_MODE_ACCURACY,
                decodingMethod = DECODING_METHOD_MODIFIED_BEAM_SEARCH,
                maxActivePaths = ACCURACY_MAX_ACTIVE_PATHS,
                hotwordsEnabled = true,
                hotwordsScore = HOTWORDS_SCORE
            )

            else -> DecoderMode(
                name = DECODER_MODE_BALANCED,
                decodingMethod = DECODING_METHOD_MODIFIED_BEAM_SEARCH,
                maxActivePaths = BALANCED_MAX_ACTIVE_PATHS,
                hotwordsEnabled = true,
                hotwordsScore = HOTWORDS_SCORE
            )
        }
    }

    private fun notifyStatus(
        state: SherpaRecognizerState,
        partialText: String = "",
        errorMessage: String? = null,
        noticeMessage: String? = null,
        isFinal: Boolean = false
    ) {
        if (!isRunning.get()) {
            return
        }

        runCatching {
            listener.onRecognitionStatus(
                SherpaRecognitionStatus(
                    state = state,
                    partialText = partialText,
                    errorMessage = errorMessage,
                    noticeMessage = noticeMessage,
                    acceptedFrames = acceptedFrames.get(),
                    isFinal = isFinal
                )
            )
        }.onFailure { error ->
            Log.e(LogTags.SHERPA_CAPTION, "ASR status callback failed", error)
        }
    }

    private fun initFailureMessage(error: Throwable): String {
        return "ASR init failed: ${error.description()}"
    }

    private fun Throwable.description(): String {
        return message?.takeIf(String::isNotBlank) ?: javaClass.simpleName
    }

    fun interface Listener {
        fun onRecognitionStatus(status: SherpaRecognitionStatus)
    }

    private data class ModelFiles(
        val encoder: String,
        val decoder: String,
        val joiner: String,
        val tokens: String,
        val bpeModel: String?,
        val bpeVocab: String?
    )

    private data class RecognizerSetup(
        val recognizer: OnlineRecognizer,
        val decodingMethod: String,
        val hotwordsEnabled: Boolean,
        val modeName: String,
        val maxActivePaths: Int,
        val hotwordsScore: Float,
        val warning: String? = null
    )

    private data class DecoderMode(
        val name: String,
        val decodingMethod: String,
        val maxActivePaths: Int,
        val hotwordsEnabled: Boolean,
        val hotwordsScore: Float
    )

    private class MissingModelException(message: String) : IllegalStateException(message)

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
        private const val NUM_THREADS = 2
        private const val PROVIDER = "cpu"
        private const val MODEL_TYPE = "zipformer2"
        private const val DECODER_MODE = "balanced"
        private const val DECODER_MODE_LOW_LATENCY = "low_latency"
        private const val DECODER_MODE_BALANCED = "balanced"
        private const val DECODER_MODE_ACCURACY = "accuracy"
        private const val DECODING_METHOD_MODIFIED_BEAM_SEARCH = "modified_beam_search"
        private const val DECODING_METHOD_GREEDY = "greedy_search"
        private const val MODELING_UNIT_BPE = "bpe"
        private const val LOW_LATENCY_MAX_ACTIVE_PATHS = 1
        private const val BALANCED_MAX_ACTIVE_PATHS = 2
        private const val ACCURACY_MAX_ACTIVE_PATHS = 4
        private const val HOTWORDS_SCORE = 1.5f
        private const val HOTWORDS_DIRECTORY = "sherpa-onnx/hotwords"
        private const val MAX_QUEUED_INPUTS = 6
        private const val INPUT_POLL_TIMEOUT_MS = 100L
        private const val LATENCY_HINT_LOG_INTERVAL_MS = 1_000L
        private const val RELEASE_JOIN_TIMEOUT_MS = 2_000L
    }
}

data class SherpaRecognitionStatus(
    val state: SherpaRecognizerState,
    val partialText: String,
    val errorMessage: String?,
    val noticeMessage: String?,
    val acceptedFrames: Long,
    val isFinal: Boolean
)

enum class SherpaRecognizerState {
    INITIALIZING,
    RUNNING,
    ERROR
}

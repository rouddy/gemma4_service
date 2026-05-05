package com.rouddy.gemma4service.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import io.reactivex.rxjava3.core.Observable
import kotlinx.coroutines.rx3.asObservable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps the MediaPipe LLM Inference API for the Gemma 4 4BE model.
 *
 * The model file (gemma4-4be.task) must be placed in the app's files directory
 * before the engine is initialised.  The expected path is:
 *   /data/data/<package>/files/gemma4-4be.task
 */
class GemmaInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "GemmaInferenceEngine"

        /** Filename of the Gemma 4 4BE model placed in the app's files directory. */
        const val MODEL_FILE = "gemma-4-E4B-it.litertlm"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.8f
        private const val TOP_K = 40
        private const val TOP_P = 0.95f
    }

    private lateinit var engine: Engine
    private val isCancelled = AtomicBoolean(false)

    /**
     * Initialises the LlmInference engine.  Must be called once before [generate].
     * Throws if the model file is not found.
     */
    fun initialize() {
        Engine.setNativeMinLogSeverity(LogSeverity.DEBUG) // Hide log for TUI app

        val engineConfig = EngineConfig(modelPath = getAssetFilePath(context))
        engine = Engine(engineConfig)
        engine.initialize()
        Log.i(TAG, "GemmaInferenceEngine initialised with model: ${engineConfig.modelPath}")
    }

    private fun getAssetFilePath(context: Context): String {
        val file = File(context.cacheDir, MODEL_FILE)
        if (!file.exists()) {
            context.assets.open(MODEL_FILE).use { inputStream ->
                file.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
        }
        return file.absolutePath
    }

    /**
     * Generates a response for [prompt] and emits partial tokens as [Observable] strings.
     * Completes when the model finishes or [cancelGeneration] is called.
     */
    fun generate(prompt: String): Observable<String> {
        isCancelled.set(false)
        if (!engine.isInitialized()) {
            return Observable.error(IllegalStateException("GemmaInferenceEngine must be initialized before generating"))
        }
        return engine.createConversation().sendMessageAsync(prompt).asObservable()
            .map { message -> message.contents.contents.joinToString(" ") { it.toString() } }
            .startWithItem("").scan { acc, token -> acc + token }
//        return Observable.just("Not implemented yet")
    }

    /** Signals the ongoing generation to stop early. */
    fun cancelGeneration() {
        isCancelled.set(true)
//        llmInference?.cancel()
    }

    /** Releases native resources. */
    fun close() {
        engine.close()
    }
}

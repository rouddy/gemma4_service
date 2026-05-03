package com.example.gemma4service.inference

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.subjects.PublishSubject
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
        const val MODEL_FILE = "gemma4-4be.task"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.8f
        private const val TOP_K = 40
        private const val TOP_P = 0.95f
    }

    private var llmInference: LlmInference? = null
    private val isCancelled = AtomicBoolean(false)

    /**
     * Initialises the LlmInference engine.  Must be called once before [generate].
     * Throws if the model file is not found.
     */
    fun initialize() {
        val modelPath = context.filesDir.absolutePath + "/" + MODEL_FILE
        val options = LlmInferenceOptions.builder()
            .setModelPath(modelPath)
            .setMaxTokens(MAX_TOKENS)
            .setTemperature(TEMPERATURE)
            .setTopK(TOP_K)
            .setTopP(TOP_P)
            .setResultListener { partialResult, done ->
                // handled via the streaming callback set per-request
            }
            .build()
        llmInference = LlmInference.createFromOptions(context, options)
        Log.i(TAG, "GemmaInferenceEngine initialised with model: $modelPath")
    }

    /**
     * Generates a response for [prompt] and emits partial tokens as [Observable] strings.
     * Completes when the model finishes or [cancelGeneration] is called.
     */
    fun generate(prompt: String): Observable<String> {
        isCancelled.set(false)
        val subject = PublishSubject.create<String>()
        val engine = llmInference ?: run {
            subject.onError(IllegalStateException("LLM engine not initialised"))
            return subject
        }

        Thread {
            try {
                val accumulated = StringBuilder()
                engine.generateAsync(prompt) { partialResult, done ->
                    if (isCancelled.get()) {
                        // Notify subject with what we have and complete
                        if (!subject.hasComplete() && !subject.hasThrowable()) {
                            subject.onComplete()
                        }
                        return@generateAsync
                    }
                    if (partialResult != null) {
                        accumulated.append(partialResult)
                        subject.onNext(accumulated.toString())
                    }
                    if (done) {
                        subject.onComplete()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Generation error", e)
                if (!subject.hasComplete() && !subject.hasThrowable()) {
                    subject.onError(e)
                }
            }
        }.start()

        return subject
    }

    /** Signals the ongoing generation to stop early. */
    fun cancelGeneration() {
        isCancelled.set(true)
        llmInference?.cancel()
    }

    /** Releases native resources. */
    fun close() {
        llmInference?.close()
        llmInference = null
    }
}

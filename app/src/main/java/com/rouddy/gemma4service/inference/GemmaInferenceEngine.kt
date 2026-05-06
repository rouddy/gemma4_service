package com.rouddy.gemma4service.inference

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.*
import com.jakewharton.rxrelay3.BehaviorRelay
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.rx3.asObservable
import java.io.File
import java.net.URL
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * Wraps the MediaPipe LLM Inference API for the Gemma 4 4BE model.
 *
 * On [initialize], the model is downloaded from [MODEL_URL] into the app's
 * files directory (if not already present) and the engine is started
 * asynchronously on an IO thread.  Callers obtain a ready [Engine] via
 * [engineRelay] as a [Single].
 */
class GemmaInferenceEngine(private val context: Context) {

    companion object {
        private const val TAG = "GemmaInferenceEngine"

        /** Filename of the Gemma 4 4BE model stored in the app's files directory. */
        const val MODEL_FILE = "gemma-4-E4B-it.litertlm"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true"
        private const val MAX_TOKENS = 1024
        private const val TEMPERATURE = 0.8f
        private const val TOP_K = 40
        private const val TOP_P = 0.95f
    }

    /**
     * Emits the [Engine] once it has been downloaded, created, and initialised.
     * Subscribers that arrive after initialisation immediately receive the value.
     */
    val engineRelay: BehaviorRelay<Engine> = BehaviorRelay.create()

    /** Set of [Conversation]s that are currently streaming a response. */
    private val activeConversations: MutableSet<Conversation> =
        Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    /** Disposable for the in-flight initialisation subscription; disposed in [close]. */
    private var initDisposable: Disposable? = null

    /**
     * Starts downloading the model (if needed) and initialising the [Engine]
     * on an IO thread.  Once ready, the engine is pushed into [engineRelay].
     */
    fun initialize() {
        initDisposable = Single.fromCallable { downloadModelIfNeeded() }
            .subscribeOn(Schedulers.io())
            .subscribe(
                { modelPath ->
                    Engine.setNativeMinLogSeverity(LogSeverity.DEBUG)
                    val engineConfig = EngineConfig(modelPath = modelPath)
                    val engine = Engine(engineConfig)
                    engine.initialize()
                    Log.i(TAG, "GemmaInferenceEngine initialised with model: $modelPath")
                    engineRelay.accept(engine)
                },
                { error ->
                    Log.e(TAG, "Failed to initialise GemmaInferenceEngine: ${error.message}", error)
                }
            )
    }

    /** Downloads the model from [MODEL_URL] to the app's files directory if not already present. */
    private fun downloadModelIfNeeded(): String {
        val file = File(context.filesDir, MODEL_FILE)
        if (!file.exists()) {
            Log.i(TAG, "Downloading model from $MODEL_URL")
            val tmp = File(context.filesDir, "$MODEL_FILE.tmp")
            try {
                URL(MODEL_URL).openStream().use { input ->
                    tmp.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                tmp.renameTo(file)
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
            Log.i(TAG, "Model downloaded to ${file.absolutePath}")
        }
        return file.absolutePath
    }

    /**
     * Returns a [Single] that emits a new [Conversation] as soon as the engine
     * is ready.  The conversation preserves context across multiple [sendMessage] calls.
     * Times out after 5 minutes to prevent indefinite blocking if initialisation fails.
     */
    fun createConversation(): Single<Conversation> {
        return engineRelay.firstOrError()
            .timeout(5, TimeUnit.MINUTES)
            .map { engine -> engine.createConversation() }
    }

    /**
     * Sends [prompt] to an existing [conversation] and emits accumulated partial tokens
     * as [Observable] strings.  Completes when the model finishes or [cancelGeneration] is called.
     * The conversation's context (previous turns) is preserved by the engine.
     */
    fun sendMessage(conversation: Conversation, prompt: String): Observable<String> {
        activeConversations.add(conversation)
        return conversation.sendMessageAsync(prompt).asObservable()
            .map { message -> message.contents.contents.joinToString(" ") { it.toString() } }
            .startWithItem("").scan { acc, token -> acc + token }
            .doFinally { activeConversations.remove(conversation) }
    }

    /**
     * Closes a [Conversation] and releases its native resources.
     * The conversation must not be used after this call.
     */
    fun closeConversation(conversation: Conversation) {
        try {
            conversation.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing conversation", e)
        }
    }

    /** Signals the given [conversation]'s ongoing generation to stop early. */
    fun cancelGeneration(conversation: Conversation) {
        if (activeConversations.remove(conversation)) {
            conversation.cancelProcess()
        }
    }

    /** Cancels all ongoing generations (e.g. on service shutdown). */
    fun cancelAllGenerations() {
        val snapshot = activeConversations.toList()
        activeConversations.clear()
        snapshot.forEach { it.cancelProcess() }
    }

    /** Releases native resources. */
    fun close() {
        initDisposable?.dispose()
        engineRelay.value?.close()
    }
}

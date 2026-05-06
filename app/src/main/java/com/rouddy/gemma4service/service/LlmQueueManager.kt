package com.rouddy.gemma4service.service

import android.util.Log
import com.rouddy.gemma4service.inference.GemmaInferenceEngine
import com.rouddy.gemma4service.model.LlmRequest
import com.rouddy.gemma4service.model.LlmState
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages concurrent LLM requests and drives the [GemmaInferenceEngine].
 *
 * All incoming requests are processed immediately in parallel so that
 * multiple client apps (and multiple conversations) can generate responses
 * at the same time.  In-flight requests are tracked in [inFlight] and can
 * be individually cancelled via [cancel].
 */
class LlmQueueManager(private val engine: GemmaInferenceEngine) {

    companion object {
        private const val TAG = "LlmQueueManager"
    }

    /** requestId → (request, disposable) for every in-flight generation. */
    private val inFlight = ConcurrentHashMap<String, Pair<LlmRequest, Disposable>>()

    /**
     * A [BehaviorSubject] that emits [LlmState] updates for ALL requests.
     * Observers can filter by [LlmState.requestId].
     */
    val stateStream: BehaviorSubject<LlmState> = BehaviorSubject.create()

    /** Starts processing [request] immediately (no waiting queue). */
    fun enqueue(request: LlmRequest) {
        processRequest(request)
    }

    /**
     * Cancels a request by ID.
     * - If the request is currently being processed, cancels generation.
     * @return true if found and cancelled.
     */
    fun cancel(requestId: String): Boolean {
        val (request, disposable) = inFlight.remove(requestId) ?: run {
            Log.w(TAG, "cancel: unknown requestId=$requestId")
            return false
        }
        Log.d(TAG, "Cancelling in-progress request: $requestId")
        engine.cancelGeneration(request.conversation)
        disposable.dispose()
        stateStream.onNext(LlmState.Error(requestId, "cancelled"))
        return true
    }

    /** Returns the number of requests currently in-flight. */
    fun queueSize(): Int = inFlight.size

    private fun processRequest(request: LlmRequest) {
        Log.d(TAG, "Processing request: ${request.requestId}")
        stateStream.onNext(LlmState.Processing(request.requestId, ""))

        val disposable = engine.sendMessage(request.conversation, request.prompt)
            .subscribeOn(Schedulers.io())
            .subscribe(
                { partialText ->
                    val state = LlmState.Processing(request.requestId, partialText)
                    stateStream.onNext(state)
                    try {
                        request.callback.onProcessing(request.requestId, partialText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Callback onProcessing failed", e)
                    }
                },
                { error ->
                    Log.e(TAG, "Generation error for ${request.requestId}", error)
                    inFlight.remove(request.requestId)
                    val state = LlmState.Error(request.requestId, error.message ?: "unknown error")
                    stateStream.onNext(state)
                    try {
                        request.callback.onError(request.requestId, error.message ?: "unknown error")
                    } catch (e: Exception) {
                        Log.w(TAG, "Callback onError failed", e)
                    }
                },
                {
                    // onComplete
                    inFlight.remove(request.requestId)
                    val lastText = (stateStream.value as? LlmState.Processing)?.partialText ?: ""
                    val state = LlmState.Completed(request.requestId, lastText)
                    stateStream.onNext(state)
                    try {
                        request.callback.onCompleted(request.requestId, lastText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Callback onCompleted failed", e)
                    }
                }
            )

        // Store only if the request hasn't already completed synchronously
        inFlight.putIfAbsent(request.requestId, Pair(request, disposable))
    }

    /** Shuts down and cancels all in-flight requests. */
    fun shutdown() {
        val snapshot = inFlight.entries.toList()
        inFlight.clear()
        snapshot.forEach { (_, pair) ->
            val (request, disposable) = pair
            engine.cancelGeneration(request.conversation)
            disposable.dispose()
        }
    }
}

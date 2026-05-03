package com.example.gemma4service.service

import android.util.Log
import com.example.gemma4service.ILlmCallback
import com.example.gemma4service.inference.GemmaInferenceEngine
import com.example.gemma4service.model.LlmRequest
import com.example.gemma4service.model.LlmState
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages the FIFO queue of LLM requests and drives the [GemmaInferenceEngine].
 *
 * Thread-safety:  All mutation of the queue is performed on a single-threaded
 * Schedulers.single() scheduler so that add/remove/process operations are serialised.
 */
class LlmQueueManager(private val engine: GemmaInferenceEngine) {

    companion object {
        private const val TAG = "LlmQueueManager"
    }

    private val queue = ConcurrentLinkedQueue<LlmRequest>()
    private val isProcessing = AtomicBoolean(false)
    private var currentRequestId: String? = null
    private var currentDisposable: Disposable? = null

    /**
     * A [BehaviorSubject] that emits [LlmState] updates for ALL requests.
     * Observers can filter by [LlmState.requestId].
     */
    val stateStream: BehaviorSubject<LlmState> = BehaviorSubject.create()

    /** Adds a request to the queue and notifies it of its position, then kicks off processing. */
    fun enqueue(request: LlmRequest) {
        queue.add(request)
        // Notify this request of its queue position
        val position = queue.size - 1
        notifyWaiting(request, position)
        // Re-notify all waiting requests of their updated positions
        updateWaitingPositions()
        tryProcessNext()
    }

    /**
     * Cancels a request by ID.
     * - If the request is currently being processed, cancels generation.
     * - If it is in the queue, removes it and updates positions for the rest.
     * @return true if found and cancelled.
     */
    fun cancel(requestId: String): Boolean {
        if (currentRequestId == requestId) {
            Log.d(TAG, "Cancelling in-progress request: $requestId")
            engine.cancelGeneration()
            currentDisposable?.dispose()
            currentRequestId = null
            isProcessing.set(false)
            stateStream.onNext(LlmState.Error(requestId, "cancelled"))
            tryProcessNext()
            return true
        }

        val removed = queue.removeIf { it.requestId == requestId }
        if (removed) {
            Log.d(TAG, "Removed queued request: $requestId")
            stateStream.onNext(LlmState.Error(requestId, "cancelled"))
            updateWaitingPositions()
        }
        return removed
    }

    /** Returns the number of requests currently waiting (not in-progress). */
    fun queueSize(): Int = queue.size

    private fun tryProcessNext() {
        if (isProcessing.get()) return
        val next = queue.poll() ?: return
        isProcessing.set(true)
        currentRequestId = next.requestId
        processRequest(next)
    }

    private fun processRequest(request: LlmRequest) {
        Log.d(TAG, "Processing request: ${request.requestId}")

        // Notify remaining waiting requests of new positions
        updateWaitingPositions()

        stateStream.onNext(LlmState.Processing(request.requestId, ""))

        currentDisposable = engine.generate(request.prompt)
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
                    val state = LlmState.Error(request.requestId, error.message ?: "unknown error")
                    stateStream.onNext(state)
                    try {
                        request.callback.onError(request.requestId, error.message ?: "unknown error")
                    } catch (e: Exception) {
                        Log.w(TAG, "Callback onError failed", e)
                    }
                    finishCurrent()
                },
                {
                    // onComplete
                    val lastText = (stateStream.value as? LlmState.Processing)?.partialText ?: ""
                    val state = LlmState.Completed(request.requestId, lastText)
                    stateStream.onNext(state)
                    try {
                        request.callback.onCompleted(request.requestId, lastText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Callback onCompleted failed", e)
                    }
                    finishCurrent()
                }
            )
    }

    private fun finishCurrent() {
        currentRequestId = null
        currentDisposable = null
        isProcessing.set(false)
        tryProcessNext()
    }

    private fun notifyWaiting(request: LlmRequest, position: Int) {
        stateStream.onNext(LlmState.Waiting(request.requestId, position))
        try {
            request.callback.onWaiting(request.requestId, position)
        } catch (e: Exception) {
            Log.w(TAG, "Callback onWaiting failed", e)
        }
    }

    private fun updateWaitingPositions() {
        queue.forEachIndexed { index, request ->
            notifyWaiting(request, index)
        }
    }

    /** Shuts down and cancels all pending requests. */
    fun shutdown() {
        currentDisposable?.dispose()
        engine.cancelGeneration()
        queue.clear()
        isProcessing.set(false)
        currentRequestId = null
    }
}

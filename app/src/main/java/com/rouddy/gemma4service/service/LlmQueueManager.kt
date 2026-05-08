package com.rouddy.gemma4service.service

import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.rouddy.gemma4service.inference.GemmaInferenceEngine
import com.rouddy.gemma4service.model.LlmRequest
import com.rouddy.gemma4service.model.LlmState
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.util.concurrent.ConcurrentHashMap

class LlmQueueManager(private val engine: GemmaInferenceEngine) {

    companion object {
        private const val TAG = "LlmQueueManager"
    }

    private val inFlight = ConcurrentHashMap<String, Pair<LlmRequest, Disposable>>()
    private val latestPartialResponses = ConcurrentHashMap<String, String>()

    val stateStream: BehaviorSubject<LlmState> = BehaviorSubject.create()

    fun enqueue(request: LlmRequest) {
        processRequest(request)
    }

    fun cancel(requestId: String): Boolean {
        val (request, disposable) = inFlight.remove(requestId) ?: run {
            Log.w(TAG, "cancel: unknown requestId=$requestId")
            return false
        }
        latestPartialResponses.remove(requestId)
        Log.d(TAG, "Cancelling in-progress request: $requestId")
        engine.cancelGeneration(request.conversation)
        disposable.dispose()
        stateStream.onNext(LlmState.Error(requestId, "cancelled"))
        return true
    }

    fun cancelConversationRequests(conversation: Conversation) {
        val requestIds = inFlight.entries
            .filter { it.value.first.conversation == conversation }
            .map { it.key }
        requestIds.forEach(::cancel)
    }

    fun queueSize(): Int = inFlight.size

    private fun processRequest(request: LlmRequest) {
        Log.d(TAG, "Processing request: ${request.requestId}")
        stateStream.onNext(LlmState.Processing(request.requestId, ""))

        val disposable = engine.sendMessage(request.conversation, request.prompt)
            .subscribeOn(Schedulers.io())
            .subscribe(
                { partialText ->
                    latestPartialResponses[request.requestId] = partialText
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
                    latestPartialResponses.remove(request.requestId)
                    val state = LlmState.Error(request.requestId, error.message ?: "unknown error")
                    stateStream.onNext(state)
                    try {
                        request.callback.onError(request.requestId, error.message ?: "unknown error")
                    } catch (e: Exception) {
                        Log.w(TAG, "Callback onError failed", e)
                    }
                },
                {
                    inFlight.remove(request.requestId)
                    val lastText = latestPartialResponses.remove(request.requestId).orEmpty()
                    val state = LlmState.Completed(request.requestId, lastText)
                    stateStream.onNext(state)
                    try {
                        request.callback.onCompleted(request.requestId, lastText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Callback onCompleted failed", e)
                    }
                }
            )

        inFlight.putIfAbsent(request.requestId, Pair(request, disposable))
    }

    fun shutdown() {
        val snapshot = inFlight.entries.toList()
        inFlight.clear()
        latestPartialResponses.clear()
        snapshot.forEach { (_, pair) ->
            val (request, disposable) = pair
            engine.cancelGeneration(request.conversation)
            disposable.dispose()
        }
    }
}

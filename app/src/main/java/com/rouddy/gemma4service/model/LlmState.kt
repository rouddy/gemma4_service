package com.rouddy.gemma4service.model

/**
 * Sealed class representing the possible states of an LLM request,
 * streamed as RxKotlin Observable items.
 */
sealed class LlmState {
    /**
     * The request is waiting in the queue.
     * @param queuePosition Number of requests ahead in queue (0 = next).
     */
    data class Waiting(val requestId: String, val queuePosition: Int) : LlmState()

    /**
     * The LLM is actively generating a response.
     * @param partialText Accumulated response text so far.
     */
    data class Processing(val requestId: String, val partialText: String) : LlmState()

    /**
     * The LLM has finished generating.
     * @param fullText The complete response text.
     */
    data class Completed(val requestId: String, val fullText: String) : LlmState()

    /**
     * The request was cancelled or failed.
     */
    data class Error(val requestId: String, val reason: String) : LlmState()
}

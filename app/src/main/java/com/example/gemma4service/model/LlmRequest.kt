package com.example.gemma4service.model

import com.example.gemma4service.ILlmCallback

/**
 * Represents a single LLM inference request queued in the service.
 */
data class LlmRequest(
    val requestId: String,
    val prompt: String,
    val callback: ILlmCallback
)

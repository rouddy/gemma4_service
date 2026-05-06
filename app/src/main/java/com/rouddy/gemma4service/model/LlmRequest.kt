package com.rouddy.gemma4service.model

import com.google.ai.edge.litertlm.Conversation
import com.rouddy.gemma4service.ILlmCallback

/**
 * Represents a single LLM inference request queued in the service.
 *
 * @param conversation  The [Conversation] whose context should be used.  This is always
 *                      supplied by the service layer after looking up the conversationId.
 */
data class LlmRequest(
    val requestId: String,
    val prompt: String,
    val callback: ILlmCallback,
    val conversation: Conversation
)

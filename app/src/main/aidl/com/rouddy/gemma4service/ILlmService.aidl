// ILlmService.aidl
package com.rouddy.gemma4service;

import com.rouddy.gemma4service.ILlmCallback;

/**
 * AIDL interface for the Gemma4 LLM foreground service.
 * Other applications bind to this service to submit and manage LLM requests.
 *
 * Conversation-based flow:
 *   1. createConversation()  – obtain a conversationId (hashCode of the Conversation object)
 *   2. sendMessage(conversationId, …) – send one or more messages; context is preserved
 *   3. closeConversation(conversationId) – release resources when the conversation ends
 */
interface ILlmService {
    /**
     * Create a new conversation and return its identifier.
     * @return Conversation ID (hashCode of the underlying Conversation object),
     *         or -1 if the engine is not ready.
     */
    int createConversation();

    /**
     * Send a message to an existing conversation.
     * Context from previous messages in the same conversation is preserved.
     * @param conversationId  ID obtained from createConversation()
     * @param requestId       A client-provided unique ID for this request
     * @param message         The user message text
     * @param callback        Callback to receive state updates (oneway)
     * @return true if the request was accepted, false if the conversation was not found
     */
    boolean sendMessage(int conversationId, String requestId, String message, ILlmCallback callback);

    /**
     * Close a conversation and release its resources.
     * @param conversationId  ID obtained from createConversation()
     * @return true if the conversation was found and closed
     */
    boolean closeConversation(int conversationId);

    /**
     * Cancel a queued or in-progress request.
     * @param requestId  The request ID to cancel
     * @return true if the request was found and cancelled
     */
    boolean cancelRequest(String requestId);

    /**
     * Returns the current number of requests in the queue (excluding any in-progress request).
     */
    int getQueueSize();
}

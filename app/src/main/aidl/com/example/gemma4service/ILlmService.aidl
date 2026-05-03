// ILlmService.aidl
package com.example.gemma4service;

import com.example.gemma4service.ILlmCallback;

/**
 * AIDL interface for the Gemma4 LLM foreground service.
 * Other applications bind to this service to submit and manage LLM requests.
 */
interface ILlmService {
    /**
     * Submit a new LLM prompt to the queue.
     * @param requestId  A client-provided unique ID for this request
     * @param prompt     The user prompt text
     * @param callback   Callback to receive state updates (oneway)
     * @return true if the request was accepted, false otherwise
     */
    boolean submitRequest(String requestId, String prompt, ILlmCallback callback);

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

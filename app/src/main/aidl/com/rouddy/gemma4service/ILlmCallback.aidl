// ILlmCallback.aidl
package com.rouddy.gemma4service;

/**
 * Callback interface for LLM response streaming.
 * Clients implement this to receive state updates.
 */
oneway interface ILlmCallback {
    /**
     * Called when the request is waiting in queue.
     * @param requestId  The unique request identifier
     * @param queuePosition  Remaining requests ahead in queue (0 = next to be processed)
     */
    void onWaiting(String requestId, int queuePosition);

    /**
     * Called repeatedly as the LLM generates tokens.
     * @param requestId  The unique request identifier
     * @param partialText  The full response text so far
     */
    void onProcessing(String requestId, String partialText);

    /**
     * Called when the LLM has finished generating.
     * @param requestId  The unique request identifier
     * @param fullText  The complete response text
     */
    void onCompleted(String requestId, String fullText);

    /**
     * Called if the request was cancelled or an error occurred.
     * @param requestId  The unique request identifier
     * @param reason  Description of the error or "cancelled"
     */
    void onError(String requestId, String reason);
}

package com.example.gemma4service.ui

/**
 * Simple data model for a single chat bubble.
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean,
    /** True while the AI is still generating this message. */
    val isStreaming: Boolean = false
)

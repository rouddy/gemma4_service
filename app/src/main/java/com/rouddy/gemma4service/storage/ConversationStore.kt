package com.rouddy.gemma4service.storage

import android.content.Context
import android.content.SharedPreferences
import com.rouddy.gemma4service.ui.ChatMessage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ConversationStore(context: Context) {

    data class ConversationSummary(
        val id: Int,
        val title: String,
        val preview: String,
        val updatedAt: Long,
        val messageCount: Int
    )

    companion object {
        private const val PREFS_NAME = "conversation_store"
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_ID = "id"
        private const val KEY_TITLE = "title"
        private const val KEY_UPDATED_AT = "updatedAt"
        private const val KEY_MESSAGES = "messages"
        private const val KEY_MESSAGE_ID = "messageId"
        private const val KEY_REQUEST_ID = "requestId"
        private const val KEY_TEXT = "text"
        private const val KEY_IS_USER = "isUser"
        private const val KEY_IS_STREAMING = "isStreaming"
        private const val DEFAULT_TITLE = "새 대화"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    @Synchronized
    fun createConversation(conversationId: Int) {
        val conversations = loadConversations()
        if (findConversation(conversations, conversationId) != null) return
        conversations.put(
            JSONObject()
                .put(KEY_ID, conversationId)
                .put(KEY_TITLE, DEFAULT_TITLE)
                .put(KEY_UPDATED_AT, System.currentTimeMillis())
                .put(KEY_MESSAGES, JSONArray())
        )
        saveConversations(conversations)
    }

    @Synchronized
    fun appendPendingExchange(conversationId: Int, requestId: String, userText: String) {
        val conversations = loadConversations()
        val conversation = findConversation(conversations, conversationId) ?: return
        val messages = conversation.optJSONArray(KEY_MESSAGES) ?: JSONArray()
        messages.put(
            JSONObject()
                .put(KEY_MESSAGE_ID, UUID.randomUUID().toString())
                .put(KEY_TEXT, userText)
                .put(KEY_IS_USER, true)
                .put(KEY_IS_STREAMING, false)
        )
        messages.put(
            JSONObject()
                .put(KEY_MESSAGE_ID, UUID.randomUUID().toString())
                .put(KEY_REQUEST_ID, requestId)
                .put(KEY_TEXT, "")
                .put(KEY_IS_USER, false)
                .put(KEY_IS_STREAMING, true)
        )
        conversation.put(KEY_MESSAGES, messages)
        conversation.put(KEY_TITLE, buildTitle(messages))
        conversation.put(KEY_UPDATED_AT, System.currentTimeMillis())
        saveConversations(conversations)
    }

    @Synchronized
    fun updateAssistantMessage(
        conversationId: Int,
        requestId: String,
        text: String,
        isStreaming: Boolean
    ) {
        val conversations = loadConversations()
        val conversation = findConversation(conversations, conversationId) ?: return
        val messages = conversation.optJSONArray(KEY_MESSAGES) ?: return
        val message = findAssistantMessage(messages, requestId) ?: run {
            val fallback = JSONObject()
                .put(KEY_MESSAGE_ID, UUID.randomUUID().toString())
                .put(KEY_REQUEST_ID, requestId)
                .put(KEY_TEXT, text)
                .put(KEY_IS_USER, false)
                .put(KEY_IS_STREAMING, isStreaming)
            messages.put(fallback)
            fallback
        }
        message.put(KEY_TEXT, text)
        message.put(KEY_IS_STREAMING, isStreaming)
        conversation.put(KEY_UPDATED_AT, System.currentTimeMillis())
        saveConversations(conversations)
    }

    @Synchronized
    fun removeConversation(conversationId: Int) {
        val current = loadConversations()
        val updated = JSONArray()
        for (index in 0 until current.length()) {
            val item = current.optJSONObject(index) ?: continue
            if (item.optInt(KEY_ID) != conversationId) {
                updated.put(item)
            }
        }
        saveConversations(updated)
    }

    fun getConversationSummaries(): List<ConversationSummary> {
        val conversations = loadConversations()
        return buildList {
            for (index in 0 until conversations.length()) {
                val item = conversations.optJSONObject(index) ?: continue
                val messages = item.optJSONArray(KEY_MESSAGES) ?: JSONArray()
                add(
                    ConversationSummary(
                        id = item.optInt(KEY_ID),
                        title = item.optString(KEY_TITLE, DEFAULT_TITLE),
                        preview = buildPreview(messages),
                        updatedAt = item.optLong(KEY_UPDATED_AT),
                        messageCount = messages.length()
                    )
                )
            }
        }.sortedByDescending { it.updatedAt }
    }

    fun getConversationMessages(conversationId: Int): List<ChatMessage> {
        val conversations = loadConversations()
        val conversation = findConversation(conversations, conversationId) ?: return emptyList()
        val messages = conversation.optJSONArray(KEY_MESSAGES) ?: return emptyList()
        return buildList {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                add(
                    ChatMessage(
                        id = message.optString(KEY_MESSAGE_ID),
                        text = message.optString(KEY_TEXT),
                        isUser = message.optBoolean(KEY_IS_USER),
                        isStreaming = message.optBoolean(KEY_IS_STREAMING)
                    )
                )
            }
        }
    }

    private fun buildTitle(messages: JSONArray): String {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optBoolean(KEY_IS_USER)) {
                return message.optString(KEY_TEXT).trim().ifEmpty { DEFAULT_TITLE }.take(24)
            }
        }
        return DEFAULT_TITLE
    }

    private fun buildPreview(messages: JSONArray): String {
        for (index in messages.length() - 1 downTo 0) {
            val message = messages.optJSONObject(index) ?: continue
            val text = message.optString(KEY_TEXT).trim()
            if (text.isNotEmpty()) return text
        }
        return "대화가 비어 있습니다"
    }

    private fun findConversation(conversations: JSONArray, conversationId: Int): JSONObject? {
        for (index in 0 until conversations.length()) {
            val item = conversations.optJSONObject(index) ?: continue
            if (item.optInt(KEY_ID) == conversationId) return item
        }
        return null
    }

    private fun findAssistantMessage(messages: JSONArray, requestId: String): JSONObject? {
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (!message.optBoolean(KEY_IS_USER) && message.optString(KEY_REQUEST_ID) == requestId) {
                return message
            }
        }
        return null
    }

    private fun loadConversations(): JSONArray {
        val raw = prefs.getString(KEY_CONVERSATIONS, null).orEmpty()
        return if (raw.isBlank()) JSONArray() else JSONArray(raw)
    }

    private fun saveConversations(conversations: JSONArray) {
        prefs.edit().putString(KEY_CONVERSATIONS, conversations.toString()).apply()
    }
}

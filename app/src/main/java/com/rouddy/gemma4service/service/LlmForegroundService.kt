package com.rouddy.gemma4service.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.ai.edge.litertlm.Conversation
import com.rouddy.gemma4service.ILlmCallback
import com.rouddy.gemma4service.ILlmService
import com.rouddy.gemma4service.R
import com.rouddy.gemma4service.inference.GemmaInferenceEngine
import com.rouddy.gemma4service.model.LlmRequest
import com.rouddy.gemma4service.storage.ConversationStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class LlmForegroundService : Service() {

    companion object {
        private const val TAG = "LlmForegroundService"
        private const val NOTIFICATION_CHANNEL_ID = "gemma4_llm_service"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var inferenceEngine: GemmaInferenceEngine
    private lateinit var queueManager: LlmQueueManager
    private lateinit var conversationStore: ConversationStore

    private val conversationIdCounter = AtomicInteger(0)
    private val conversations = ConcurrentHashMap<Int, Conversation>()

    private val binder = object : ILlmService.Stub() {

        override fun createConversation(): Int {
            return try {
                val conversation = inferenceEngine.createConversation().blockingGet()
                val id = conversationIdCounter.incrementAndGet()
                conversations[id] = conversation
                conversationStore.createConversation(id)
                Log.d(TAG, "createConversation: id=$id")
                id
            } catch (e: Exception) {
                Log.e(TAG, "createConversation failed", e)
                -1
            }
        }

        override fun sendMessage(
            conversationId: Int,
            requestId: String,
            message: String,
            callback: ILlmCallback
        ): Boolean {
            if (requestId.isBlank() || message.isBlank()) return false
            val conversation = conversations[conversationId] ?: run {
                Log.w(TAG, "sendMessage: unknown conversationId=$conversationId")
                return false
            }
            conversationStore.appendPendingExchange(conversationId, requestId, message)
            Log.d(TAG, "sendMessage: conversationId=$conversationId requestId=$requestId")
            val request = LlmRequest(
                requestId = requestId,
                prompt = message,
                callback = callbackWithPersistence(conversationId, callback),
                conversation = conversation
            )
            queueManager.enqueue(request)
            return true
        }

        override fun closeConversation(conversationId: Int): Boolean {
            val conversation = conversations.remove(conversationId) ?: run {
                Log.w(TAG, "closeConversation: unknown conversationId=$conversationId")
                return false
            }
            Log.d(TAG, "closeConversation: id=$conversationId")
            queueManager.cancelConversationRequests(conversation)
            inferenceEngine.closeConversation(conversation)
            conversationStore.removeConversation(conversationId)
            return true
        }

        override fun cancelRequest(requestId: String): Boolean {
            Log.d(TAG, "cancelRequest: $requestId")
            return queueManager.cancel(requestId)
        }

        override fun getQueueSize(): Int = queueManager.queueSize()
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        conversationStore = ConversationStore(applicationContext)
        conversationIdCounter.set(conversationStore.getConversationSummaries().maxOfOrNull { it.id } ?: 0)
        inferenceEngine = GemmaInferenceEngine(applicationContext)
        try {
            inferenceEngine.initialize()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize inference engine", e)
        }
        queueManager = LlmQueueManager(inferenceEngine)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        super.onDestroy()
        queueManager.shutdown()
        conversations.values.forEach { conversation ->
            inferenceEngine.closeConversation(conversation)
        }
        conversations.clear()
        inferenceEngine.close()
        Log.i(TAG, "Service destroyed")
    }

    private fun callbackWithPersistence(
        conversationId: Int,
        delegate: ILlmCallback
    ) = object : ILlmCallback.Stub() {
        override fun onWaiting(requestId: String, queuePosition: Int) {
            delegate.onWaiting(requestId, queuePosition)
        }

        override fun onProcessing(requestId: String, partialText: String) {
            conversationStore.updateAssistantMessage(conversationId, requestId, partialText, true)
            delegate.onProcessing(requestId, partialText)
        }

        override fun onCompleted(requestId: String, fullText: String) {
            conversationStore.updateAssistantMessage(conversationId, requestId, fullText, false)
            delegate.onCompleted(requestId, fullText)
        }

        override fun onError(requestId: String, reason: String) {
            val displayText = if (reason == "cancelled") {
                getString(R.string.msg_cancelled)
            } else {
                getString(R.string.msg_error, reason)
            }
            conversationStore.updateAssistantMessage(conversationId, requestId, displayText, false)
            delegate.onError(requestId, reason)
        }
    }

    private fun buildNotification(): Notification {
        createNotificationChannel()
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setSmallIcon(R.drawable.ic_service_running)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Gemma4 LLM Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Foreground service running the Gemma4 LLM inference engine"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}

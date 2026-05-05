package com.rouddy.gemma4service.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.rouddy.gemma4service.ILlmCallback
import com.rouddy.gemma4service.ILlmService
import com.rouddy.gemma4service.R
import com.rouddy.gemma4service.inference.GemmaInferenceEngine
import com.rouddy.gemma4service.model.LlmRequest

/**
 * Foreground service that hosts the Gemma 4 LLM inference engine.
 * Exposed via AIDL so other apps can bind and submit requests.
 *
 * android:exported="true" is set in AndroidManifest.xml
 */
class LlmForegroundService : Service() {

    companion object {
        private const val TAG = "LlmForegroundService"
        private const val NOTIFICATION_CHANNEL_ID = "gemma4_llm_service"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var inferenceEngine: GemmaInferenceEngine
    private lateinit var queueManager: LlmQueueManager

    // ---- AIDL stub implementation ----
    private val binder = object : ILlmService.Stub() {

        override fun submitRequest(
            requestId: String,
            prompt: String,
            callback: ILlmCallback
        ): Boolean {
            if (requestId.isBlank() || prompt.isBlank()) return false
            Log.d(TAG, "submitRequest: $requestId")
            val request = LlmRequest(requestId, prompt, callback)
            queueManager.enqueue(request)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        queueManager.shutdown()
        inferenceEngine.close()
        Log.i(TAG, "Service destroyed")
    }

    // ---- Notification helpers ----

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

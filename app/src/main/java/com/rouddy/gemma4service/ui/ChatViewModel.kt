package com.rouddy.gemma4service.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.rouddy.gemma4service.R
import com.rouddy.gemma4service.ILlmCallback
import com.rouddy.gemma4service.ILlmService
import com.rouddy.gemma4service.service.LlmForegroundService
import com.rouddy.gemma4service.storage.ConversationStore
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import java.util.UUID

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val store = ConversationStore(application)
    private val compositeDisposable = CompositeDisposable()

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _statusText = MutableLiveData<String>("")
    val statusText: LiveData<String> = _statusText

    private var llmService: ILlmService? = null
    private var isBound = false
    private var creatingConversation = false
    private var attached = false
    private var shouldCreateConversation = false
    private var conversationId: Int = -1
    private var lastRequestId: String? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            llmService = ILlmService.Stub.asInterface(binder)
            isBound = true
            _statusText.postValue(getApplication<Application>().getString(R.string.status_service_connected))
            if (conversationId != -1) {
                reloadMessages()
            } else {
                createConversationIfNeeded()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            llmService = null
            isBound = false
            _statusText.postValue(getApplication<Application>().getString(R.string.status_service_disconnected))
        }
    }

    init {
        bindToService()
    }

    fun attachConversation(requestedConversationId: Int) {
        if (attached) return
        attached = true
        if (requestedConversationId > 0) {
            conversationId = requestedConversationId
            reloadMessages()
        } else {
            shouldCreateConversation = true
            createConversationIfNeeded()
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val service = llmService ?: run {
            _statusText.value = getApplication<Application>().getString(R.string.status_service_not_connected)
            return
        }
        if (conversationId == -1) {
            _statusText.value = getApplication<Application>().getString(R.string.status_conversation_failed)
            return
        }

        val requestId = UUID.randomUUID().toString()
        lastRequestId = requestId
        val accepted = service.sendMessage(conversationId, requestId, text, callbackForRequest())
        if (accepted) {
            reloadMessages()
        } else {
            _statusText.value = getApplication<Application>().getString(R.string.status_conversation_missing)
        }
    }

    fun cancelLastRequest() {
        val service = llmService ?: return
        val requestId = lastRequestId ?: return
        service.cancelRequest(requestId)
    }

    private fun bindToService() {
        val intent = Intent(getApplication(), LlmForegroundService::class.java)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        getApplication<Application>().startService(intent)
    }

    private fun createConversationIfNeeded() {
        val service = llmService ?: return
        if (!shouldCreateConversation || conversationId != -1 || creatingConversation) return

        creatingConversation = true
        val disposable = Single.fromCallable { service.createConversation() }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { id ->
                    creatingConversation = false
                    if (id == -1) {
                        _statusText.value = getApplication<Application>().getString(R.string.status_conversation_failed)
                    } else {
                        conversationId = id
                        shouldCreateConversation = false
                        reloadMessages()
                    }
                },
                { error ->
                    creatingConversation = false
                    Log.e(TAG, "createConversation error", error)
                    _statusText.value = getApplication<Application>().getString(R.string.status_conversation_failed)
                }
            )
        compositeDisposable.add(disposable)
    }

    private fun callbackForRequest() = object : ILlmCallback.Stub() {
        override fun onWaiting(requestId: String, queuePosition: Int) {
            _statusText.postValue(
                getApplication<Application>().getString(R.string.status_waiting, queuePosition)
            )
        }

        override fun onProcessing(requestId: String, partialText: String) {
            reloadMessages()
            _statusText.postValue(getApplication<Application>().getString(R.string.status_processing))
        }

        override fun onCompleted(requestId: String, fullText: String) {
            reloadMessages()
            _statusText.postValue(getApplication<Application>().getString(R.string.status_completed))
        }

        override fun onError(requestId: String, reason: String) {
            reloadMessages()
            val app = getApplication<Application>()
            _statusText.postValue(
                if (reason == "cancelled") app.getString(R.string.status_cancelled)
                else app.getString(R.string.status_error, reason)
            )
        }
    }

    private fun reloadMessages() {
        if (conversationId == -1) return
        _messages.postValue(store.getConversationMessages(conversationId))
    }

    override fun onCleared() {
        super.onCleared()
        compositeDisposable.dispose()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
    }
}

package com.example.gemma4service.ui

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
import com.example.gemma4service.R
import com.example.gemma4service.ILlmCallback
import com.example.gemma4service.ILlmService
import com.example.gemma4service.model.LlmState
import com.example.gemma4service.service.LlmForegroundService
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.subjects.PublishSubject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "ChatViewModel"
    }

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _statusText = MutableLiveData<String>("")
    val statusText: LiveData<String> = _statusText

    /** RxKotlin subject that emits LlmState updates from the service (for the UI layer). */
    private val stateSubject = PublishSubject.create<LlmState>()
    val llmStateStream: Observable<LlmState> = stateSubject.hide()

    private val compositeDisposable = CompositeDisposable()

    // Maps requestId -> ChatMessage id in the list
    private val requestToMessageId = ConcurrentHashMap<String, String>()

    private var llmService: ILlmService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            llmService = ILlmService.Stub.asInterface(binder)
            isBound = true
            Log.d(TAG, "Service connected")
            _statusText.postValue("Service connected")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            llmService = null
            isBound = false
            Log.d(TAG, "Service disconnected")
            _statusText.postValue("Service disconnected")
        }
    }

    init {
        bindToService()
        observeLlmStates()
    }

    private fun bindToService() {
        val intent = Intent(getApplication(), LlmForegroundService::class.java)
        getApplication<Application>().bindService(
            intent, serviceConnection, Context.BIND_AUTO_CREATE
        )
        // Also start the service so it becomes a foreground service
        getApplication<Application>().startService(intent)
    }

    private fun observeLlmStates() {
        val disposable = llmStateStream
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe { state ->
                handleLlmState(state)
            }
        compositeDisposable.add(disposable)
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        val service = llmService ?: run {
            _statusText.value = getApplication<Application>().getString(R.string.status_service_not_connected)
            return
        }

        // Add user message to list
        val userMsg = ChatMessage(id = UUID.randomUUID().toString(), text = text, isUser = true)
        appendMessage(userMsg)

        // Add placeholder AI message
        val aiMsgId = UUID.randomUUID().toString()
        val aiMsg = ChatMessage(id = aiMsgId, text = "", isUser = false, isStreaming = true)
        appendMessage(aiMsg)

        val requestId = UUID.randomUUID().toString()
        requestToMessageId[requestId] = aiMsgId

        val callback = object : ILlmCallback.Stub() {
            override fun onWaiting(requestId: String, queuePosition: Int) {
                val state = LlmState.Waiting(requestId, queuePosition)
                stateSubject.onNext(state)
            }

            override fun onProcessing(requestId: String, partialText: String) {
                val state = LlmState.Processing(requestId, partialText)
                stateSubject.onNext(state)
            }

            override fun onCompleted(requestId: String, fullText: String) {
                val state = LlmState.Completed(requestId, fullText)
                stateSubject.onNext(state)
            }

            override fun onError(requestId: String, reason: String) {
                val state = LlmState.Error(requestId, reason)
                stateSubject.onNext(state)
            }
        }

        service.submitRequest(requestId, text, callback)
    }

    fun cancelLastRequest() {
        val service = llmService ?: return
        val lastRequestId = requestToMessageId.keys.lastOrNull() ?: return
        service.cancelRequest(lastRequestId)
    }

    private fun handleLlmState(state: LlmState) {
        val res = getApplication<Application>().resources
        when (state) {
            is LlmState.Waiting -> {
                val msgId = requestToMessageId[state.requestId] ?: return
                updateMessage(msgId) { msg ->
                    msg.copy(
                        text = res.getString(R.string.status_waiting_queue, state.queuePosition),
                        isStreaming = true
                    )
                }
                _statusText.value = res.getString(R.string.status_waiting, state.queuePosition)
            }
            is LlmState.Processing -> {
                val msgId = requestToMessageId[state.requestId] ?: return
                updateMessage(msgId) { msg ->
                    msg.copy(text = state.partialText, isStreaming = true)
                }
                _statusText.value = res.getString(R.string.status_processing)
            }
            is LlmState.Completed -> {
                val msgId = requestToMessageId[state.requestId] ?: return
                updateMessage(msgId) { msg ->
                    msg.copy(text = state.fullText, isStreaming = false)
                }
                requestToMessageId.remove(state.requestId)
                _statusText.value = res.getString(R.string.status_completed)
            }
            is LlmState.Error -> {
                val msgId = requestToMessageId[state.requestId] ?: return
                val displayText = if (state.reason == "cancelled") {
                    res.getString(R.string.msg_cancelled)
                } else {
                    res.getString(R.string.msg_error, state.reason)
                }
                updateMessage(msgId) { msg ->
                    msg.copy(text = displayText, isStreaming = false)
                }
                requestToMessageId.remove(state.requestId)
                _statusText.value = if (state.reason == "cancelled") {
                    res.getString(R.string.status_cancelled)
                } else {
                    res.getString(R.string.status_error, state.reason)
                }
            }
        }
    }

    private fun appendMessage(msg: ChatMessage) {
        val current = _messages.value.orEmpty()
        _messages.value = current + msg
    }

    private fun updateMessage(msgId: String, transform: (ChatMessage) -> ChatMessage) {
        val current = _messages.value.orEmpty()
        _messages.value = current.map { if (it.id == msgId) transform(it) else it }
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

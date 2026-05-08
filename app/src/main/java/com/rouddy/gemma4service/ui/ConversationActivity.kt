package com.rouddy.gemma4service.ui

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.rouddy.gemma4service.R
import com.rouddy.gemma4service.databinding.ActivityConversationBinding

class ConversationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }

    private lateinit var binding: ActivityConversationBinding
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var chatAdapter: ChatAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConversationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        binding.toolbar.setNavigationOnClickListener { finish() }
        setupRecyclerView()
        setupInput()
        observeViewModel()
        viewModel.attachConversation(intent.getIntExtra(EXTRA_CONVERSATION_ID, -1))
    }

    private fun setupRecyclerView() {
        chatAdapter = ChatAdapter()
        binding.recyclerViewChat.apply {
            adapter = chatAdapter
            layoutManager = LinearLayoutManager(this@ConversationActivity).apply {
                stackFromEnd = true
            }
        }
    }

    private fun setupInput() {
        binding.btnSend.setOnClickListener { sendMessage() }
        binding.btnCancel.setOnClickListener {
            viewModel.cancelLastRequest()
            Toast.makeText(this, R.string.toast_cancelling, Toast.LENGTH_SHORT).show()
        }
        binding.etInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                sendMessage()
                true
            } else {
                false
            }
        }
    }

    private fun sendMessage() {
        val text = binding.etInput.text.toString().trim()
        if (text.isEmpty()) return
        binding.etInput.text?.clear()
        viewModel.sendMessage(text)
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this) { messages ->
            chatAdapter.submitList(messages.toList()) {
                if (messages.isNotEmpty()) {
                    binding.recyclerViewChat.smoothScrollToPosition(messages.size - 1)
                }
            }
        }
        viewModel.statusText.observe(this) { binding.tvStatus.text = it }
    }
}

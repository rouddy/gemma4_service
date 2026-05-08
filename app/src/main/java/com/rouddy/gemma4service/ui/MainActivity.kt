package com.rouddy.gemma4service.ui

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rouddy.gemma4service.ILlmService
import com.rouddy.gemma4service.R
import com.rouddy.gemma4service.databinding.ActivityMainBinding
import com.rouddy.gemma4service.service.LlmForegroundService
import com.rouddy.gemma4service.storage.ConversationStore
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var conversationAdapter: ConversationListAdapter
    private lateinit var conversationStore: ConversationStore

    private var llmService: ILlmService? = null
    private var isBound = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permission result handled silently */ }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            llmService = ILlmService.Stub.asInterface(service)
            isBound = true
            binding.tvStatus.text = getString(R.string.status_service_connected)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            llmService = null
            isBound = false
            binding.tvStatus.text = getString(R.string.status_service_disconnected)
        }
    }

    private val storeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshConversationList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        conversationStore = ConversationStore(applicationContext)
        requestNotificationPermissionIfNeeded()
        setupRecyclerView()
        setupActions()
        refreshConversationList()
    }

    override fun onStart() {
        super.onStart()
        conversationStore.registerListener(storeListener)
        val intent = Intent(this, LlmForegroundService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        conversationStore.unregisterListener(storeListener)
        conversationAdapter.closeDelete()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
            llmService = null
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun setupRecyclerView() {
        conversationAdapter = ConversationListAdapter(
            onConversationClick = { conversation ->
                startActivity(
                    Intent(this, ConversationActivity::class.java)
                        .putExtra(ConversationActivity.EXTRA_CONVERSATION_ID, conversation.id)
                )
            },
            onDeleteClick = { conversation ->
                val service = llmService
                if (service == null) {
                    Toast.makeText(this, R.string.conversation_delete_unavailable, Toast.LENGTH_SHORT)
                        .show()
                } else {
                    val closed = service.closeConversation(conversation.id)
                    if (!closed) {
                        Toast.makeText(this, R.string.conversation_delete_failed, Toast.LENGTH_SHORT)
                            .show()
                    }
                }
                conversationAdapter.closeDelete()
            }
        )
        binding.recyclerViewConversations.apply {
            adapter = conversationAdapter
            layoutManager = LinearLayoutManager(this@MainActivity)
            ItemTouchHelper(ConversationSwipeCallback()).attachToRecyclerView(this)
        }
    }

    private fun setupActions() {
        binding.btnNewConversation.setOnClickListener {
            startActivity(Intent(this, ConversationActivity::class.java))
        }
    }

    private fun refreshConversationList() {
        val conversations = conversationStore.getConversationSummaries()
        conversationAdapter.submitList(conversations)
        binding.tvEmpty.isVisible = conversations.isEmpty()
    }

    private inner class ConversationSwipeCallback : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ) = false

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 0.2f

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            val conversation = conversationAdapter.currentList.getOrNull(viewHolder.bindingAdapterPosition)
                ?: return
            conversationAdapter.openDelete(conversation.id)
        }

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            val maxSwipeOffset = resources.getDimension(R.dimen.conversation_delete_width)
            val clampedDx = max(dX, -maxSwipeOffset)
            super.onChildDraw(c, recyclerView, viewHolder, clampedDx, dY, actionState, isCurrentlyActive)
        }
    }
}

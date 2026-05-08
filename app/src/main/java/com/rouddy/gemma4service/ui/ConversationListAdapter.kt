package com.rouddy.gemma4service.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.rouddy.gemma4service.R
import com.rouddy.gemma4service.databinding.ItemConversationBinding
import com.rouddy.gemma4service.storage.ConversationStore
import java.text.DateFormat
import java.util.Date

class ConversationListAdapter(
    private val onConversationClick: (ConversationStore.ConversationSummary) -> Unit,
    private val onDeleteClick: (ConversationStore.ConversationSummary) -> Unit
) : ListAdapter<ConversationStore.ConversationSummary, ConversationListAdapter.ConversationViewHolder>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<ConversationStore.ConversationSummary>() {
            override fun areItemsTheSame(
                oldItem: ConversationStore.ConversationSummary,
                newItem: ConversationStore.ConversationSummary
            ) = oldItem.id == newItem.id

            override fun areContentsTheSame(
                oldItem: ConversationStore.ConversationSummary,
                newItem: ConversationStore.ConversationSummary
            ) = oldItem == newItem
        }
    }

    var openedConversationId: Int? = null
        private set

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConversationViewHolder {
        val binding = ItemConversationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ConversationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ConversationViewHolder, position: Int) {
        holder.bind(getItem(position), getItem(position).id == openedConversationId)
    }

    fun openDelete(conversationId: Int) {
        val previous = openedConversationId
        openedConversationId = conversationId
        previous?.let(::notifyConversationChanged)
        notifyConversationChanged(conversationId)
    }

    fun closeDelete() {
        val previous = openedConversationId ?: return
        openedConversationId = null
        notifyConversationChanged(previous)
    }

    private fun notifyConversationChanged(conversationId: Int) {
        val index = currentList.indexOfFirst { it.id == conversationId }
        if (index >= 0) notifyItemChanged(index)
    }

    inner class ConversationViewHolder(
        private val binding: ItemConversationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ConversationStore.ConversationSummary, isOpened: Boolean) {
            val context = binding.root.context
            binding.tvTitle.text = item.title
            binding.tvPreview.text = item.preview
            binding.tvTimestamp.text = DateFormat.getDateTimeInstance(
                DateFormat.SHORT,
                DateFormat.SHORT
            ).format(Date(item.updatedAt))
            binding.tvCount.text = context.resources.getQuantityString(
                R.plurals.conversation_message_count,
                item.messageCount,
                item.messageCount
            )
            binding.foregroundView.translationX = if (isOpened) {
                -binding.root.resources.displayMetrics.density * 88f
            } else {
                0f
            }
            binding.foregroundView.setOnClickListener {
                when {
                    openedConversationId == item.id -> closeDelete()
                    openedConversationId != null -> closeDelete()
                    else -> onConversationClick(item)
                }
            }
            binding.deleteButton.setOnClickListener { onDeleteClick(item) }
        }
    }
}

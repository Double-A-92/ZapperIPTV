package com.zapperiptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zapperiptv.R
import com.zapperiptv.databinding.ItemChannelBinding
import com.zapperiptv.model.Channel

class ChannelListAdapter(
    private val onChannelSelected: (Int) -> Unit
) : ListAdapter<Channel, ChannelListAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    private val indicatorColors = listOf(
        R.color.channel_indicator_1,
        R.color.channel_indicator_2,
        R.color.channel_indicator_3,
        R.color.channel_indicator_4
    )

    private val sourceColorMap = mutableMapOf<String, Int>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = getItem(position)
        holder.bind(channel)
    }

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = absoluteAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelSelected(position)
                }
            }

            // Handle focus scaling for Android TV with animation cancellation
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) 1.04f else 1f
                view.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(150)
                    .start()
            }
        }

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name

            // Assign a consistent color to each sourceId efficiently
            val color = sourceColorMap.getOrPut(channel.sourceId) {
                val colorRes = indicatorColors[sourceColorMap.size % indicatorColors.size]
                ContextCompat.getColor(binding.root.context, colorRes)
            }
            ImageLoader.load(channel.logoUrl, binding.channelLogo, R.drawable.ic_placeholder_logo)
            binding.playlistIndicator.setTextColor(color)
        }
    }

    class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.streamUrl == newItem.streamUrl && oldItem.sourceId == newItem.sourceId
        }

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
}

package com.zapperiptv.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
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
        holder.bind(channel, position)
    }

    inner class ChannelViewHolder(private val binding: ItemChannelBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = absoluteAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelSelected(position)
                }
            }
            
            // Handle focus scaling for Android TV
            binding.root.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                } else {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                }
            }
        }

        fun bind(channel: Channel, position: Int) {
            binding.channelName.text = channel.name
            binding.channelNumber.text = channel.displayNumber.toString()

            // Assign a consistent color to each sourceId
            if (!sourceColorMap.containsKey(channel.sourceId)) {
                val colorRes = indicatorColors[sourceColorMap.size % indicatorColors.size]
                sourceColorMap[channel.sourceId] = binding.root.context.getColor(colorRes)
            }
            binding.playlistIndicator.setBackgroundColor(sourceColorMap[channel.sourceId] ?: Color.GRAY)

            ImageLoader.load(channel.logoUrl, binding.channelLogo, R.drawable.ic_placeholder_logo)
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

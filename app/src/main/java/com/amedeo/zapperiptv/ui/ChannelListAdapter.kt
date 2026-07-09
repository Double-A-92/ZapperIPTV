package com.amedeo.zapperiptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.amedeo.zapperiptv.R
import com.amedeo.zapperiptv.databinding.ItemChannelBinding
import com.amedeo.zapperiptv.model.Channel

class ChannelListAdapter(
    private val onChannelSelected: (Int) -> Unit,
) : ListAdapter<Channel, ChannelListAdapter.ChannelViewHolder>(ChannelDiffCallback()) {
    companion object {
        private const val FOCUS_SCALE = 1.04f
        private const val ANIMATION_DURATION = 150L

        // Use a large virtual count to simulate an infinite list.
        private const val VIRTUAL_INFINITY = Int.MAX_VALUE / 2
    }

    private val indicatorColorResIds =
        intArrayOf(
            R.color.channel_indicator_1,
            R.color.channel_indicator_5,
            R.color.channel_indicator_2,
            R.color.channel_indicator_4,
            R.color.channel_indicator_3,
        )

    private var indicatorColors: IntArray? = null
    private val sourceColorMap = mutableMapOf<String, Int>()

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(
        holder: ChannelViewHolder,
        position: Int,
    ) {
        val size = currentList.size
        if (size == 0) return

        // Map the virtual position to the actual channel index
        val actualPosition = position % size
        val channel = getItem(actualPosition)
        holder.bind(channel)
    }

    override fun getItemCount(): Int {
        val size = currentList.size
        // Only loop if we have more than one channel
        return if (size > 1) VIRTUAL_INFINITY else size
    }

    /**
     * Maps a virtual adapter position to the actual list index.
     */
    fun getActualPosition(position: Int): Int {
        val size = currentList.size
        return if (size > 0) position % size else position
    }

    /**
     * Calculates a starting position in the middle of the virtual list
     * that aligns perfectly with the desired channel index.
     */
    fun getStartOffset(actualIndex: Int): Int {
        val size = currentList.size
        if (size <= 1) return actualIndex

        val half = VIRTUAL_INFINITY / 2
        // Ensure the offset is a perfect multiple of the list size
        val offset = half - (half % size)
        return offset + actualIndex
    }

    override fun onCurrentListChanged(
        previousList: List<Channel>,
        currentList: List<Channel>,
    ) {
        super.onCurrentListChanged(previousList, currentList)
        // Refresh the virtual list to ensure modulo calculations are updated
        notifyDataSetChanged()
    }

    override fun onViewRecycled(holder: ChannelViewHolder) {
        holder.binding.root
            .animate()
            .cancel()
        super.onViewRecycled(holder)
    }

    inner class ChannelViewHolder(
        val binding: ItemChannelBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val position = absoluteAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    onChannelSelected(getActualPosition(position))
                }
            }

            binding.root.setOnFocusChangeListener { view, hasFocus ->
                val scale = if (hasFocus) FOCUS_SCALE else 1f
                view
                    .animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(ANIMATION_DURATION)
                    .start()
            }
        }

        fun bind(channel: Channel) {
            binding.channelName.text = channel.name

            val colors =
                indicatorColors ?: IntArray(indicatorColorResIds.size).also { arr ->
                    indicatorColorResIds.forEachIndexed { i, resId ->
                        arr[i] = ContextCompat.getColor(binding.root.context, resId)
                    }
                    indicatorColors = arr
                }

            val color =
                sourceColorMap.getOrPut(channel.sourceId) {
                    colors[sourceColorMap.size % colors.size]
                }

            ImageLoader.load(channel.logoUrl, binding.channelLogo, R.drawable.ic_placeholder_logo)
            binding.playlistIndicator.setTextColor(color)
        }
    }

    class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(
            oldItem: Channel,
            newItem: Channel,
        ): Boolean = oldItem.streamUrl == newItem.streamUrl && oldItem.sourceId == newItem.sourceId

        override fun areContentsTheSame(
            oldItem: Channel,
            newItem: Channel,
        ): Boolean = oldItem == newItem
    }
}

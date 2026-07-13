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
    private val onChannelSelected: (Channel) -> Unit,
    private val onChannelFocused: (Channel) -> Unit,
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
    private var favoriteChecker: (Channel) -> Boolean = { false }

    // How many channel rows fit on screen. 0 until measured by the activity.
    private var visibleCapacity: Int = 0

    // Whether infinite looping is currently active. Assumed on until measured so
    // the list always renders; the activity disables it for short lists.
    private var looping: Boolean = true

    fun setFavoriteChecker(checker: (Channel) -> Boolean) {
        favoriteChecker = checker
        notifyDataSetChanged()
    }

    /**
     * Reports how many item rows fit in the viewport. When the real channel
     * list is taller than this, looping is enabled so items can scroll past
     * the end instead of being repeated on a single screen.
     */
    fun setVisibleCapacity(capacity: Int) {
        visibleCapacity = capacity
        syncLoopState()
    }

    fun isLooping(): Boolean = looping

    // Looping is active only when enabled and there is more than one channel.
    private fun shouldLoop(): Boolean = looping && currentList.size > 1

    private fun computeLooping(): Boolean {
        val size = currentList.size
        return size > 1 && (visibleCapacity <= 0 || size > visibleCapacity)
    }

    // Returns true when the looping decision changed (and the list was refreshed).
    private fun syncLoopState(): Boolean {
        val shouldLoop = computeLooping()
        if (shouldLoop == looping) return false
        looping = shouldLoop
        notifyDataSetChanged()
        return true
    }

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
        if (currentList.isEmpty()) return
        holder.bind(getItem(getActualPosition(position)))
    }

    override fun getItemCount(): Int {
        // Loop only when the list overflows the viewport; otherwise show it
        // once so items are never repeated.
        return if (shouldLoop()) VIRTUAL_INFINITY else currentList.size
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
        if (!shouldLoop()) return actualIndex

        val size = currentList.size
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
        // Re-evaluate looping for the new size, refreshing only if the
        // decision didn't already flip (and thus already notify).
        if (!syncLoopState()) {
            notifyDataSetChanged()
        }
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
                    onChannelSelected(getItem(getActualPosition(position)))
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
                if (hasFocus) {
                    val position = absoluteAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onChannelFocused(getItem(getActualPosition(position)))
                    }
                }
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

            if (favoriteChecker(channel)) {
                binding.playlistIndicator.text = "★"
                binding.playlistIndicator.setTextColor(
                    ContextCompat.getColor(binding.root.context, R.color.accent_amber),
                )
            } else {
                binding.playlistIndicator.text = "●"
                binding.playlistIndicator.setTextColor(color)
            }
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

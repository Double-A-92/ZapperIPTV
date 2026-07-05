package com.zapperiptv.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.zapperiptv.databinding.ItemPlaylistBinding
import com.zapperiptv.model.Playlist
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PlaylistAdapter(
    private val onToggle: (String) -> Unit,
    private val onDelete: (String) -> Unit
) : ListAdapter<Playlist, PlaylistAdapter.PlaylistViewHolder>(PlaylistDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaylistViewHolder {
        val binding = ItemPlaylistBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlaylistViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaylistViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaylistViewHolder(private val binding: ItemPlaylistBinding) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(playlist: Playlist) {
            binding.playlistName.text = playlist.name
            binding.playlistUrl.text = playlist.url
            
            val statusText = if (playlist.lastUpdated > 0L) {
                val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                "Last updated: ${sdf.format(Date(playlist.lastUpdated))}"
            } else {
                "Never updated"
            }
            binding.playlistStatus.text = statusText
            
            // Detach listener before setting checked state to avoid false triggers
            binding.playlistEnabled.setOnCheckedChangeListener(null)
            binding.playlistEnabled.isChecked = playlist.enabled
            binding.playlistEnabled.setOnCheckedChangeListener { _, _ ->
                onToggle(playlist.id)
            }
            
            binding.btnDelete.setOnClickListener {
                onDelete(playlist.id)
            }
        }
    }

    class PlaylistDiffCallback : DiffUtil.ItemCallback<Playlist>() {
        override fun areItemsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Playlist, newItem: Playlist): Boolean {
            return oldItem == newItem
        }
    }
}

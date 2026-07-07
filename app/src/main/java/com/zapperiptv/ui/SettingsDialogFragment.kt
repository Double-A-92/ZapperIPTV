package com.zapperiptv.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import com.zapperiptv.R
import com.zapperiptv.databinding.DialogSettingsBinding
import com.zapperiptv.model.Playlist
import com.zapperiptv.viewmodel.MainViewModel

class SettingsDialogFragment : DialogFragment() {
    companion object {
        private const val DIALOG_WIDTH_MAX_DP = 600
        private const val DIALOG_WIDTH_RATIO = 0.85f
        private const val DIM_AMOUNT = 0.7f
    }

    private var _binding: DialogSettingsBinding? = null
    val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: PlaylistAdapter

    private var playlistsObserver: Observer<List<Playlist>>? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), R.style.SettingsDialogTheme)
        _binding = DialogSettingsBinding.inflate(LayoutInflater.from(requireContext()))
        dialog.setContentView(binding.root)

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#1A1A1A")))
            setDimAmount(DIM_AMOUNT)
        }

        setupContent()
        return dialog
    }

    private fun setupContent() {
        adapter =
            PlaylistAdapter(
                onEdit = { playlist ->
                    AddPlaylistDialogFragment
                        .newInstance(playlist.id, playlist.name, playlist.url)
                        .show(childFragmentManager, "EditPlaylist")
                },
                onDelete = { id -> viewModel.removePlaylist(id) },
            )

        binding.playlistRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SettingsDialogFragment.adapter
        }

        val currentPlaylists = viewModel.playlists.value.orEmpty()
        adapter.submitList(currentPlaylists)
        toggleEmptyMessage(currentPlaylists.isEmpty())

        binding.btnAddUrl.setOnClickListener {
            AddPlaylistDialogFragment().show(childFragmentManager, "AddPlaylist")
        }
        binding.btnReload.setOnClickListener {
            viewModel.loadPlaylists(forceReload = true)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let { window ->
            val screenWidth = resources.displayMetrics.widthPixels
            val density = resources.displayMetrics.density
            val maxWidth = (DIALOG_WIDTH_MAX_DP * density).toInt()
            val width = (screenWidth * DIALOG_WIDTH_RATIO).toInt().coerceAtMost(maxWidth)
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        playlistsObserver =
            Observer { playlists ->
                adapter.submitList(playlists)
                toggleEmptyMessage(playlists.isEmpty())
            }
        playlistsObserver?.let { viewModel.playlists.observe(this, it) }
    }

    override fun onStop() {
        super.onStop()
        playlistsObserver?.let { viewModel.playlists.removeObserver(it) }
        playlistsObserver = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun toggleEmptyMessage(isEmpty: Boolean) {
        binding.emptyPlaylistsMessage.visibility = if (isEmpty) View.VISIBLE else View.GONE
    }
}

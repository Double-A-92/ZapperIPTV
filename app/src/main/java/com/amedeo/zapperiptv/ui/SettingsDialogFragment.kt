package com.amedeo.zapperiptv.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.amedeo.zapperiptv.databinding.DialogSettingsBinding
import com.amedeo.zapperiptv.viewmodel.MainViewModel

class SettingsDialogFragment : DialogFragment() {
    private var _binding: DialogSettingsBinding? = null
    val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: PlaylistAdapter

    companion object {
        private const val DIALOG_WIDTH_MAX_DP = 600
        private const val DIALOG_WIDTH_RATIO = 0.85f
        private const val DIM_AMOUNT = 0.7f
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#1A1A1A")))
            setDimAmount(DIM_AMOUNT)
        }
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupListeners()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter =
            PlaylistAdapter(
                onEdit = { playlist ->
                    AddPlaylistDialogFragment
                        .newInstance(playlist.id, playlist.name, playlist.url, playlist.epgUrl)
                        .show(childFragmentManager, "EditPlaylist")
                },
                onDelete = { id -> viewModel.removePlaylist(id) },
            )

        binding.playlistRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SettingsDialogFragment.adapter
        }
    }

    private fun setupListeners() {
        binding.btnAddUrl.setOnClickListener {
            AddPlaylistDialogFragment().show(childFragmentManager, "AddPlaylist")
        }
        binding.btnReload.setOnClickListener {
            viewModel.loadPlaylists(forceReload = true)
        }
    }

    private fun observeViewModel() {
        viewModel.playlists.observe(viewLifecycleOwner) { playlists ->
            adapter.submitList(playlists)
            binding.emptyPlaylistsMessage.visibility =
                if (playlists.isEmpty()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
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
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

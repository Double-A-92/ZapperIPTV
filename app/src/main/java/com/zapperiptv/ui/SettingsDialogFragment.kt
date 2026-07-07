package com.zapperiptv.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
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
        private const val TAG = "SettingsDialog"
        private const val DIALOG_WIDTH_MAX_DP = 600
        private const val DIALOG_WIDTH_RATIO = 0.85f
        private const val DIM_AMOUNT = 0.7f
    }

    private var _binding: DialogSettingsBinding? = null
    val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: PlaylistAdapter

    private val localPlaylistLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                handleLocalPlaylistSelected(uri)
            }
        }

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
                onToggle = { id -> viewModel.togglePlaylist(id) },
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
        binding.btnAddLocal.setOnClickListener {
            localPlaylistLauncher.launch(arrayOf("*/*"))
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

    private fun handleLocalPlaylistSelected(uri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not take persistable permission for $uri", e)
        }

        val fileName = getFileName(uri) ?: "Local Playlist"
        AddPlaylistDialogFragment
            .newInstance(fileName, uri.toString(), true)
            .show(childFragmentManager, "AddLocalPlaylist")
    }

    private fun getFileName(uri: Uri): String? {
        var result = getFileNameFromContent(uri)
        if (result == null) {
            result =
                uri.path?.let { path ->
                    val cut = path.lastIndexOf('/')
                    if (cut != -1) path.substring(cut + 1) else path
                }
        }
        return result?.removeSuffix(".m3u")?.removeSuffix(".m3u8")
    }

    private fun getFileNameFromContent(uri: Uri): String? {
        if (uri.scheme != "content") return null
        return requireContext().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) cursor.getString(index) else null
            } else {
                null
            }
        }
    }
}

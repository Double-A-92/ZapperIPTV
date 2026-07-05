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
import android.view.Window
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
    }

    private var _binding: DialogSettingsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: PlaylistAdapter

    private val localPlaylistLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            handleLocalPlaylistSelected(uri)
        }
    }

    // Observer reference so we can add/remove it safely
    private var playlistsObserver: Observer<List<Playlist>>? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        Log.d(TAG, "onCreateDialog")

        // Use a custom theme that removes the title and makes the window background transparent
        val dialog = Dialog(requireContext(), R.style.SettingsDialogTheme)

        // Inflate the custom layout – you must ensure this layout has light‑colored text
        _binding = DialogSettingsBinding.inflate(LayoutInflater.from(requireContext()))
        dialog.setContentView(binding.root)

        // Apply the dark background directly to the window
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#1A1A1A")))
            setDimAmount(0.7f)
        }

        setupContent()
        return dialog
    }

    private fun setupContent() {
        // Create adapter and set it immediately with current data (observer not yet active)
        adapter = PlaylistAdapter(
            onToggle = { id ->
                Log.d(TAG, "Toggle playlist: $id")
                viewModel.togglePlaylist(id)
            },
            onDelete = { id ->
                Log.d(TAG, "Delete playlist: $id")
                viewModel.removePlaylist(id)
            }
        )

        binding.playlistRecyclerView.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SettingsDialogFragment.adapter
        }

        // Show current data immediately
        val currentPlaylists = viewModel.playlists.value.orEmpty()
        adapter.submitList(currentPlaylists)
        toggleEmptyMessage(currentPlaylists.isEmpty())

        // Setup buttons
        binding.btnAddUrl.setOnClickListener {
            Log.d(TAG, "Add URL clicked")
            AddPlaylistDialogFragment().show(childFragmentManager, "AddPlaylist")
        }
        binding.btnAddLocal.setOnClickListener {
            Log.d(TAG, "Add Local clicked - launching picker")
            localPlaylistLauncher.launch(arrayOf("*/*"))
        }
        binding.btnReload.setOnClickListener {
            Log.d(TAG, "Reload clicked")
            viewModel.loadPlaylists(forceReload = true)
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")

        // Adjust dialog width here (window is already created)
        dialog?.window?.let { window ->
            val screenWidth = resources.displayMetrics.widthPixels
            val density = resources.displayMetrics.density
            val maxWidth = (600 * density).toInt()
            val width = (screenWidth * 0.85f).toInt().coerceAtMost(maxWidth)
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // Start observing the LiveData – this will update the UI when data changes
        playlistsObserver = Observer { playlists ->
            adapter.submitList(playlists)
            toggleEmptyMessage(playlists.isEmpty())
        }
        playlistsObserver?.let { viewModel.playlists.observe(this, it) }
    }

    override fun onStop() {
        super.onStop()
        // Remove observer to avoid leaks and updates when dialog is dismissed
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
        // Persist read permission so the URI remains readable across restarts.
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not take persistable permission for $uri", e)
        }

        val fileName = getFileName(uri) ?: "Local Playlist"
        AddPlaylistDialogFragment.newInstance(fileName, uri.toString(), true)
            .show(childFragmentManager, "AddLocalPlaylist")
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result?.removeSuffix(".m3u")?.removeSuffix(".m3u8")
    }
}

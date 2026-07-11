package com.amedeo.zapperiptv.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.drawable.toDrawable
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.amedeo.zapperiptv.R
import com.amedeo.zapperiptv.databinding.DialogAddPlaylistBinding
import com.amedeo.zapperiptv.viewmodel.MainViewModel

class AddPlaylistDialogFragment : DialogFragment() {
    private var _binding: DialogAddPlaylistBinding? = null
    val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    private val filePickerLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        it,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // Ignore if we can't take persistable permission
                }
                binding.inputUrl.setText(it.toString())
            }
        }

    companion object {
        private const val DIALOG_WIDTH_MAX_DP = 600
        private const val DIALOG_WIDTH_RATIO = 0.85f

        private const val ARG_ID = "arg_id"
        private const val ARG_NAME = "arg_name"
        private const val ARG_URL = "arg_url"
        private const val ARG_EPG_URL = "arg_epg_url"

        fun newInstance(
            id: String? = null,
            name: String? = null,
            url: String? = null,
            epgUrl: String? = null,
        ): AddPlaylistDialogFragment {
            val fragment = AddPlaylistDialogFragment()
            fragment.arguments =
                Bundle().apply {
                    putString(ARG_ID, id)
                    putString(ARG_NAME, name)
                    putString(ARG_URL, url)
                    putString(ARG_EPG_URL, epgUrl)
                }
            return fragment
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = DialogAddPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        return dialog
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

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        val initialId = arguments?.getString(ARG_ID)
        val initialName = arguments?.getString(ARG_NAME)
        val initialUrl = arguments?.getString(ARG_URL)
        val initialEpgUrl = arguments?.getString(ARG_EPG_URL)

        if (initialName != null) binding.inputName.setText(initialName)
        if (initialUrl != null) binding.inputUrl.setText(initialUrl)
        if (initialEpgUrl != null) binding.inputEpgUrl.setText(initialEpgUrl)
        if (initialId != null) binding.dialogTitle.setText(R.string.edit_playlist)

        binding.btnBrowse.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        binding.btnCancel.setOnClickListener { dismiss() }

        binding.btnSave.setOnClickListener {
            val name =
                binding.inputName.text
                    .toString()
                    .trim()
            val url =
                binding.inputUrl.text
                    .toString()
                    .trim()
            val epgUrl =
                binding.inputEpgUrl.text
                    .toString()
                    .trim()
                    .ifEmpty { null }

            if (name.isEmpty()) {
                Toast.makeText(context, R.string.error_empty_name, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!isValid(url)) {
                Toast.makeText(context, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (epgUrl != null && !isValid(epgUrl)) {
                Toast.makeText(context, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (initialId != null) {
                viewModel.updatePlaylist(initialId, name, url, epgUrl)
            } else {
                viewModel.addPlaylist(name, url, epgUrl)
            }
            dismiss()
        }
    }

    private fun isValid(url: String): Boolean {
        if (url.isEmpty()) return false
        return url.startsWith("http://") || url.startsWith("https://") || url.startsWith("content://")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

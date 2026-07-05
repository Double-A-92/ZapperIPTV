package com.zapperiptv.ui

import android.app.Dialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.activityViewModels
import com.zapperiptv.R
import com.zapperiptv.databinding.DialogAddPlaylistBinding
import com.zapperiptv.viewmodel.MainViewModel
import androidx.core.graphics.drawable.toDrawable

class AddPlaylistDialogFragment : DialogFragment() {

    private var _binding: DialogAddPlaylistBinding? = null
    private val binding get() = _binding!!

    private val viewModel: MainViewModel by activityViewModels()

    companion object {
        private const val ARG_NAME = "arg_name"
        private const val ARG_URL = "arg_url"
        private const val ARG_IS_LOCAL = "arg_is_local"

        fun newInstance(name: String? = null, url: String? = null, isLocal: Boolean = false): AddPlaylistDialogFragment {
            val fragment = AddPlaylistDialogFragment()
            val args = Bundle().apply {
                putString(ARG_NAME, name)
                putString(ARG_URL, url)
                putBoolean(ARG_IS_LOCAL, isLocal)
            }
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogAddPlaylistBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
        return dialog
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val initialName = arguments?.getString(ARG_NAME)
        val initialUrl = arguments?.getString(ARG_URL)
        val isLocal = arguments?.getBoolean(ARG_IS_LOCAL) ?: false

        if (initialName != null) {
            binding.inputName.setText(initialName)
        }
        if (initialUrl != null) {
            binding.inputUrl.setText(initialUrl)
        }
        
        if (isLocal) {
            binding.dialogTitle.setText(R.string.add_local_playlist)
            // If it's local, the URL is already set by the picker, 
            // but we might want to show it as read-only or just let them see it.
            // Many users prefer to see what was picked.
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnSave.setOnClickListener {
            val name = binding.inputName.text.toString().trim()
            val url = binding.inputUrl.text.toString().trim()

            if (name.isEmpty()) {
                Toast.makeText(context, R.string.error_empty_name, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (url.isEmpty()) {
                Toast.makeText(context, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Skip http validation for local files (content://)
            if (!isLocal && !url.startsWith("http://") && !url.startsWith("https://")) {
                Toast.makeText(context, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.addPlaylist(name, url)
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

package com.amedeo.zapperiptv.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.amedeo.zapperiptv.repository.PlaylistRepository
import com.amedeo.zapperiptv.storage.PreferencesManager

class MainViewModelFactory(
    private val repository: PlaylistRepository,
    private val preferencesManager: PreferencesManager,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(repository, preferencesManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

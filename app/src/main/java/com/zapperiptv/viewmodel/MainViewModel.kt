package com.zapperiptv.viewmodel

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zapperiptv.model.Channel
import com.zapperiptv.model.PlaybackState
import com.zapperiptv.model.Playlist
import com.zapperiptv.repository.PlaylistRepository
import com.zapperiptv.storage.PreferencesManager
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: PlaylistRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    companion object {
        private const val TAG = "MainViewModel"
        private const val DEBOUNCE_DELAY_MS = 300L
        private const val OVERLAY_DELAY_MS = 3000L
        private const val ERROR_RECOVERY_DELAY_MS = 2000L
    }

    private val _channels = MutableLiveData<List<Channel>>(emptyList())
    val channels: LiveData<List<Channel>> = _channels

    private val _currentChannel = MutableLiveData<Channel?>()
    val currentChannel: LiveData<Channel?> = _currentChannel

    private val _currentIndex = MutableLiveData<Int>(-1)
    val currentIndex: LiveData<Int> = _currentIndex

    private val _playbackState = MutableLiveData<PlaybackState>(PlaybackState.Idle)
    val playbackState: LiveData<PlaybackState> = _playbackState

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage

    private val _showOverlay = MutableLiveData<Boolean>(false)
    val showOverlay: LiveData<Boolean> = _showOverlay

    private val _showChannelList = MutableLiveData<Boolean>(false)
    val showChannelList: LiveData<Boolean> = _showChannelList

    private val _playlists = MutableLiveData<List<Playlist>>(emptyList())
    val playlists: LiveData<List<Playlist>> = _playlists

    private val handler = Handler(Looper.getMainLooper())
    
    // Runnables for debouncing and delays
    private val playChannelRunnable = Runnable { executePlayChannel() }
    private val hideOverlayRunnable = Runnable { _showOverlay.value = false }
    private val errorRecoveryRunnable = Runnable { attemptErrorRecovery() }

    // Error recovery state
    private val attemptedIndicesInCycle = mutableSetOf<Int>()
    private var isRecovering = false

    init {
        loadPlaylists()
    }

    fun loadPlaylists(forceReload: Boolean = false) {
        _isLoading.value = true
        _errorMessage.value = null
        viewModelScope.launch {
            _playlists.value = repository.getPlaylists()
            val loadedChannels = repository.loadChannels(forceReload)
            _channels.value = loadedChannels
            _isLoading.value = false

            if (loadedChannels.isEmpty()) {
                _currentChannel.value = null
                _currentIndex.value = -1
                if (_playlists.value.isNullOrEmpty()) {
                    // Handled in activity (no playlists)
                }
            } else if (_currentIndex.value == -1) {
                restoreLastChannel(loadedChannels)
            }
        }
    }

    private fun restoreLastChannel(channels: List<Channel>) {
        val lastChannel = preferencesManager.loadLastChannel()
        if (lastChannel != null) {
            val (sourceId, url) = lastChannel
            val index = channels.indexOfFirst { it.sourceId == sourceId && it.streamUrl == url }
            if (index != -1) {
                setIndexAndPlay(index)
                return
            }
        }
        // Fallback to first channel
        setIndexAndPlay(0)
    }

    fun channelUp() {
        val list = _channels.value ?: return
        if (list.isEmpty()) return
        
        cancelErrorRecovery()
        
        val newIndex = if ((_currentIndex.value ?: 0) < list.size - 1) {
            (_currentIndex.value ?: 0) + 1
        } else {
            0
        }
        setIndexDebounced(newIndex)
    }

    fun channelDown() {
        val list = _channels.value ?: return
        if (list.isEmpty()) return
        
        cancelErrorRecovery()

        val newIndex = if ((_currentIndex.value ?: 0) > 0) {
            (_currentIndex.value ?: 0) - 1
        } else {
            list.size - 1
        }
        setIndexDebounced(newIndex)
    }

    fun selectChannel(index: Int) {
        val list = _channels.value ?: return
        if (list.isEmpty() || index < 0 || index >= list.size) return
        
        cancelErrorRecovery()
        setIndexAndPlay(index)
    }

    private fun setIndexDebounced(index: Int) {
        _currentIndex.value = index
        _currentChannel.value = _channels.value?.getOrNull(index)
        showOverlayTemporarily()
        
        handler.removeCallbacks(playChannelRunnable)
        handler.postDelayed(playChannelRunnable, DEBOUNCE_DELAY_MS)
    }

    private fun setIndexAndPlay(index: Int) {
        _currentIndex.value = index
        _currentChannel.value = _channels.value?.getOrNull(index)
        showOverlayTemporarily()
        
        handler.removeCallbacks(playChannelRunnable)
        executePlayChannel()
    }

    private fun executePlayChannel() {
        val channel = _currentChannel.value ?: return
        Log.d(TAG, "Requesting playback for: ${channel.name}")
        preferencesManager.saveLastChannel(channel.sourceId, channel.streamUrl)
        
        // Activity observes currentChannel to actually pass URL to ExoPlayer
        // Activity will then update playbackState
        
        attemptedIndicesInCycle.clear()
        attemptedIndicesInCycle.add(_currentIndex.value ?: 0)
    }

    fun setPlaybackState(state: PlaybackState) {
        _playbackState.value = state
        when (state) {
            is PlaybackState.Error -> {
                Log.e(TAG, "Playback error: ${state.message}")
                showOverlayTemporarily() // To show the error message
                startErrorRecovery()
            }
            PlaybackState.Playing -> {
                cancelErrorRecovery()
            }
            else -> {}
        }
    }

    private fun startErrorRecovery() {
        if (isRecovering) return
        isRecovering = true
        handler.postDelayed(errorRecoveryRunnable, ERROR_RECOVERY_DELAY_MS)
    }

    private fun attemptErrorRecovery() {
        val list = _channels.value ?: return
        if (list.isEmpty()) {
            isRecovering = false
            return
        }

        var nextIndex = (_currentIndex.value ?: 0) + 1
        if (nextIndex >= list.size) nextIndex = 0

        while (attemptedIndicesInCycle.contains(nextIndex)) {
            nextIndex++
            if (nextIndex >= list.size) nextIndex = 0
            
            // Checked all channels
            if (nextIndex == (_currentIndex.value ?: 0)) {
                _errorMessage.value = "All channels failed to play."
                isRecovering = false
                return
            }
        }

        Log.d(TAG, "Auto-advancing to next channel for error recovery: index $nextIndex")
        setIndexAndPlay(nextIndex)
        // Keep recovering flag true until Playing state resets it
    }

    private fun cancelErrorRecovery() {
        handler.removeCallbacks(errorRecoveryRunnable)
        isRecovering = false
        _errorMessage.value = null
    }

    fun toggleOverlay() {
        if (_showOverlay.value == true) {
            _showOverlay.value = false
            handler.removeCallbacks(hideOverlayRunnable)
        } else {
            showOverlayTemporarily()
        }
    }

    private fun showOverlayTemporarily() {
        _showOverlay.value = true
        handler.removeCallbacks(hideOverlayRunnable)
        handler.postDelayed(hideOverlayRunnable, OVERLAY_DELAY_MS)
    }

    fun toggleChannelList() {
        _showChannelList.value = _showChannelList.value != true
    }

    fun setChannelListVisible(visible: Boolean) {
        _showChannelList.value = visible
    }

    // Playlist Management
    fun addPlaylist(name: String, url: String) {
        repository.addPlaylist(name, url)
        loadPlaylists() // Reloads data
    }

    fun togglePlaylist(id: String) {
        repository.togglePlaylist(id)
        loadPlaylists()
    }

    fun removePlaylist(id: String) {
        repository.removePlaylist(id)
        loadPlaylists()
    }

    override fun onCleared() {
        super.onCleared()
        handler.removeCallbacksAndMessages(null)
    }
}

package com.amedeo.zapperiptv.viewmodel

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amedeo.zapperiptv.R
import com.amedeo.zapperiptv.model.Channel
import com.amedeo.zapperiptv.model.PlaybackState
import com.amedeo.zapperiptv.model.Playlist
import com.amedeo.zapperiptv.repository.PlaylistRepository
import com.amedeo.zapperiptv.storage.PreferencesManager
import kotlinx.coroutines.launch

class MainViewModel(
    private val repository: PlaylistRepository,
    private val preferencesManager: PreferencesManager,
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

    private val _errorMessage = MutableLiveData<Int?>(null)
    val errorMessage: LiveData<Int?> = _errorMessage

    private val _showOverlay = MutableLiveData<Boolean>(false)
    val showOverlay: LiveData<Boolean> = _showOverlay

    private val _showChannelList = MutableLiveData<Boolean>(false)
    val showChannelList: LiveData<Boolean> = _showChannelList

    private val _playlists = MutableLiveData<List<Playlist>>(emptyList())
    val playlists: LiveData<List<Playlist>> = _playlists

    private val handler = Handler(Looper.getMainLooper())

    private val playChannelRunnable = Runnable { executePlayChannel() }
    private val hideOverlayRunnable = Runnable { _showOverlay.value = false }
    private val errorRecoveryRunnable = Runnable { attemptErrorRecovery() }

    private val attemptedIndicesInCycle = mutableSetOf<Int>()
    private var isRecovering = false

    private var pendingChannelUrl: String? = null

    init {
        loadPlaylists()
    }

    fun setPendingChannelUrl(url: String?) {
        val channels = _channels.value
        if (channels != null && channels.isNotEmpty() && url != null) {
            selectChannelByUrl(url, channels)
        } else {
            pendingChannelUrl = url
        }
    }

    private fun selectChannelByUrl(url: String, channels: List<Channel>) {
        val index = channels.indexOfFirst { it.streamUrl == url }
        if (index != -1) {
            selectChannel(index)
        }
    }

    fun loadPlaylists(forceReload: Boolean = false) {
        _errorMessage.value = null
        viewModelScope.launch {
            _playlists.value = repository.getPlaylists()
            val loadedChannels = repository.loadChannels(forceReload)
            _channels.value = loadedChannels

            if (loadedChannels.isEmpty()) {
                _currentChannel.value = null
                _currentIndex.value = -1
            } else {
                val pendingUrl = pendingChannelUrl
                if (pendingUrl != null) {
                    selectChannelByUrl(pendingUrl, loadedChannels)
                    pendingChannelUrl = null
                } else if (_currentIndex.value == -1) {
                    restoreLastChannel(loadedChannels)
                }
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
        setIndexAndPlay(0)
    }

    fun channelUp() {
        val list = _channels.value ?: return
        if (list.isEmpty()) return
        cancelErrorRecovery()
        val current = _currentIndex.value ?: 0
        val newIndex = if (current < list.size - 1) current + 1 else 0
        setIndexDebounced(newIndex)
    }

    fun channelDown() {
        val list = _channels.value ?: return
        if (list.isEmpty()) return
        cancelErrorRecovery()
        val current = _currentIndex.value ?: 0
        val newIndex = if (current > 0) current - 1 else list.size - 1
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
        attemptedIndicesInCycle.clear()
        attemptedIndicesInCycle.add(_currentIndex.value ?: 0)
    }

    fun setPlaybackState(state: PlaybackState) {
        _playbackState.value = state
        when (state) {
            is PlaybackState.Error -> {
                Log.e(TAG, "Playback error: ${state.message}")
                showOverlayTemporarily()
                startErrorRecovery()
            }
            PlaybackState.Playing -> cancelErrorRecovery()
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
        } else {
            val nextIndex = findNextAvailableIndex(list)
            if (nextIndex != -1) {
                Log.d(TAG, "Auto-advancing to next channel for error recovery: index $nextIndex")
                setIndexAndPlay(nextIndex)
            } else {
                _errorMessage.value = R.string.error_all_channels_failed
                isRecovering = false
            }
        }
    }

    private fun findNextAvailableIndex(list: List<Channel>): Int {
        var next = (_currentIndex.value ?: 0) + 1
        if (next >= list.size) next = 0
        while (attemptedIndicesInCycle.contains(next)) {
            next++
            if (next >= list.size) next = 0
            if (next == (_currentIndex.value ?: 0)) return -1
        }
        return next
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

    fun setChannelListVisible(visible: Boolean) {
        _showChannelList.value = visible
    }

    fun addPlaylist(
        name: String,
        url: String,
    ) {
        repository.addPlaylist(name, url)
        loadPlaylists()
    }

    fun updatePlaylist(
        id: String,
        name: String,
        url: String,
    ) {
        val playlists = repository.getPlaylists()
        val playlist = playlists.find { it.id == id }
        if (playlist != null) {
            val updatedPlaylist = playlist.copy(name = name, url = url)
            repository.updatePlaylist(updatedPlaylist)
            loadPlaylists()
        }
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

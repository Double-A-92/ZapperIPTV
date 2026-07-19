package com.amedeo.zapperiptv.viewmodel

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.amedeo.zapperiptv.R
import com.amedeo.zapperiptv.model.Channel
import com.amedeo.zapperiptv.model.CurrentProgrammeState
import com.amedeo.zapperiptv.model.PlaybackState
import com.amedeo.zapperiptv.model.Playlist
import com.amedeo.zapperiptv.repository.PlaylistRepository
import com.amedeo.zapperiptv.storage.PreferencesManager
import kotlinx.coroutines.launch

class MainViewModel(
    application: Application,
    private val repository: PlaylistRepository,
    private val preferencesManager: PreferencesManager,
) : AndroidViewModel(application) {
    companion object {
        private const val TAG = "MainViewModel"
        private const val DEBOUNCE_DELAY_MS = 300L
        private const val OVERLAY_DELAY_MS = 3000L
        private const val LOAD_TIMEOUT_MS = 10000L
        private const val MAX_RETRIES = 5
        private const val RETRY_DELAY_MS = 2000L
        private const val EPG_TICK_INTERVAL_MS = 60 * 60 * 1000L
        const val FAVORITES_TAB_ID = "__favorites__"
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

    private val _favorites =
        MutableLiveData<Set<Pair<String, String>>>(
            preferencesManager.getFavorites().map { it.sourceId to it.streamUrl }.toSet(),
        )
    val favorites: LiveData<Set<Pair<String, String>>> = _favorites

    private val _selectedPlaylistId = MutableLiveData<String?>(preferencesManager.loadLastSelectedPlaylist())
    val selectedPlaylistId: LiveData<String?> = _selectedPlaylistId

    private val _selectedPlaylistLabel =
        MutableLiveData<String>(getApplication<Application>().getString(R.string.all_playlists))
    val selectedPlaylistLabel: LiveData<String> = _selectedPlaylistLabel

    private val _currentProgrammeState = MutableLiveData<CurrentProgrammeState>(CurrentProgrammeState(null))
    val currentProgrammeState: LiveData<CurrentProgrammeState> = _currentProgrammeState

    val filteredChannels: LiveData<List<Channel>> =
        MediatorLiveData<List<Channel>>().also { mediator ->
            mediator.addSource(_channels) { computeFiltered() }
            mediator.addSource(_selectedPlaylistId) { computeFiltered() }
            mediator.addSource(_favorites) { computeFiltered() }
        }

    private val handler = Handler(Looper.getMainLooper())

    private val playChannelRunnable = Runnable { executePlayChannel() }
    private val hideOverlayRunnable = Runnable { _showOverlay.value = false }
    private val loadTimeoutRunnable = Runnable { onLoadTimeout() }
    private val retryRunnable = Runnable { retryCurrentChannel() }
    private val epgTickRunnable =
        Runnable {
            updateCurrentProgramme()
            startEpgTick()
        }

    private var retryCount = 0
    private var isRecovering = false

    private var pendingChannelUrl: String? = null

    init {
        loadPlaylists()
    }

    private fun computeFiltered() {
        val all = _channels.value ?: emptyList()
        val selectedId = _selectedPlaylistId.value
        val favSet = _favorites.value ?: emptySet()
        val filtered =
            when {
                selectedId == null -> all
                selectedId == FAVORITES_TAB_ID ->
                    all.filter { favSet.contains(it.sourceId to it.streamUrl) }
                else -> all.filter { it.sourceId == selectedId }
            }
        (filteredChannels as MutableLiveData<List<Channel>>).value = filtered
    }

    private fun updateSelectedPlaylistLabel() {
        val id = _selectedPlaylistId.value
        val label =
            when (id) {
                null -> getApplication<Application>().getString(R.string.all_playlists)
                FAVORITES_TAB_ID -> getApplication<Application>().getString(R.string.favorites)
                else ->
                    _playlists.value?.find { it.id == id }?.name
                        ?: getApplication<Application>().getString(R.string.all_playlists)
            }
        _selectedPlaylistLabel.value = label
    }

    fun setPendingChannelUrl(url: String?) {
        val channels = _channels.value
        if (channels != null && channels.isNotEmpty() && url != null) {
            selectChannelByUrl(url, channels)
        } else {
            pendingChannelUrl = url
        }
    }

    private fun selectChannelByUrl(
        url: String,
        channels: List<Channel>,
    ) {
        val index = channels.indexOfFirst { it.streamUrl == url }
        if (index != -1) {
            selectChannel(index)
        }
    }

    fun buildTabs(): List<String?> {
        val playlistIds = (_playlists.value ?: emptyList()).map { it.id }
        return if (playlistIds.size > 1) {
            listOf<String?>(null) + playlistIds + listOf(FAVORITES_TAB_ID)
        } else {
            playlistIds + listOf(FAVORITES_TAB_ID)
        }
    }

    fun cyclePlaylist(direction: Int) {
        val tabs = buildTabs()
        if (tabs.size <= 1) return

        val currentIndex = tabs.indexOf(_selectedPlaylistId.value).coerceAtLeast(0)
        val nextIndex = (currentIndex + direction + tabs.size) % tabs.size
        val nextTabId = tabs[nextIndex]

        _selectedPlaylistId.value = nextTabId
        preferencesManager.saveLastSelectedPlaylist(nextTabId)
        updateSelectedPlaylistLabel()
    }

    fun toggleFavorite(
        sourceId: String,
        streamUrl: String,
    ): Boolean {
        val added = preferencesManager.toggleFavorite(sourceId, streamUrl)
        _favorites.value =
            preferencesManager.getFavorites().map { it.sourceId to it.streamUrl }.toSet()
        return added
    }

    fun isFavorite(channel: Channel): Boolean =
        (_favorites.value ?: emptySet()).contains(channel.sourceId to channel.streamUrl)

    fun selectChannel(channel: Channel) {
        val list = _channels.value ?: return
        if (list.isEmpty()) return
        cancelErrorRecovery()
        val idx = list.indexOfFirst { it.streamUrl == channel.streamUrl && it.sourceId == channel.sourceId }
        if (idx >= 0) {
            val tabKey = _selectedPlaylistId.value ?: PreferencesManager.ALL_TAB_KEY
            preferencesManager.savePlaylistCursor(tabKey, channel.sourceId, channel.streamUrl)
            selectChannel(idx)
        }
    }

    fun savePlaylistCursor(
        tabKey: String,
        sourceId: String,
        streamUrl: String,
    ) {
        preferencesManager.savePlaylistCursor(tabKey, sourceId, streamUrl)
    }

    fun getPlaylistCursor(tabKey: String): PreferencesManager.PlaylistCursor? =
        preferencesManager.loadPlaylistCursor(tabKey)

    fun loadPlaylists(forceReload: Boolean = false) {
        _errorMessage.value = null

        // Phase 1: show the cached channel list immediately so the UI is
        // usable without waiting for the (slow) remote refresh to finish.
        viewModelScope.launch {
            _playlists.value = repository.getPlaylists()
            validateSelectedPlaylist()
            updateSelectedPlaylistLabel()

            if (_channels.value.isNullOrEmpty()) {
                val cached = repository.getCachedChannels()
                if (cached.isNotEmpty()) {
                    _channels.value = cached
                    resumeChannelSelection(cached)
                }
            }
        }

        // Phase 2: refresh playlists and channels from the network in the
        // background, then swap in the fresh data once it is ready.
        viewModelScope.launch {
            _playlists.value = repository.getPlaylists()
            val loadedChannels = repository.loadChannels(forceReload)
            repository.loadEpg(forceReload)
            _channels.value = loadedChannels
            validateSelectedPlaylist()
            pruneFavorites()
            updateSelectedPlaylistLabel()
            resumeChannelSelection(loadedChannels)
            startEpgTick()
        }
    }

    private fun validateSelectedPlaylist() {
        val currentSelectedId = _selectedPlaylistId.value
        val playlistIds = _playlists.value.orEmpty().map { it.id }
        when {
            currentSelectedId == null && playlistIds.size == 1 -> {
                val singleId = playlistIds.first()
                _selectedPlaylistId.value = singleId
                preferencesManager.saveLastSelectedPlaylist(singleId)
                preferencesManager.removePlaylistCursorsForPlaylist(PreferencesManager.ALL_TAB_KEY)
            }
            currentSelectedId != null && !playlistIds.contains(currentSelectedId) -> {
                if (playlistIds.size == 1) {
                    val singleId = playlistIds.first()
                    _selectedPlaylistId.value = singleId
                    preferencesManager.saveLastSelectedPlaylist(singleId)
                    preferencesManager.removePlaylistCursorsForPlaylist(currentSelectedId)
                } else {
                    _selectedPlaylistId.value = null
                    preferencesManager.saveLastSelectedPlaylist(null)
                    preferencesManager.removePlaylistCursorsForPlaylist(currentSelectedId)
                }
            }
        }
    }

    private fun pruneFavorites() {
        val validSourceIds =
            _playlists.value
                .orEmpty()
                .map { it.id }
                .toSet()
        val before = _favorites.value ?: emptySet()
        preferencesManager.pruneFavoritesForMissingPlaylists(validSourceIds)
        val after =
            preferencesManager.getFavorites().map { it.sourceId to it.streamUrl }.toSet()
        if (before != after) {
            _favorites.value = after
        }
    }

    private fun resumeChannelSelection(channels: List<Channel>) {
        if (channels.isEmpty()) {
            _currentChannel.value = null
            _currentIndex.value = -1
            return
        }

        val pendingUrl = pendingChannelUrl
        if (pendingUrl != null) {
            selectChannelByUrl(pendingUrl, channels)
            pendingChannelUrl = null
            return
        }

        val playing = _currentChannel.value
        if (playing != null) {
            // Keep the currently playing channel, only re-sync its index in
            // case the refreshed list reordered it.
            val idx =
                channels.indexOfFirst {
                    it.streamUrl == playing.streamUrl && it.sourceId == playing.sourceId
                }
            if (idx != -1) {
                _currentIndex.value = idx
            } else {
                restoreLastChannel(channels)
            }
            return
        }

        if (_currentIndex.value == -1) {
            restoreLastChannel(channels)
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
        val filtered = filteredChannels.value ?: emptyList()
        if (filtered.isEmpty()) return
        cancelErrorRecovery()
        val current = _currentChannel.value
        val currentFilteredIdx =
            if (current != null) {
                filtered
                    .indexOfFirst { it.streamUrl == current.streamUrl && it.sourceId == current.sourceId }
                    .coerceAtLeast(0)
            } else {
                -1
            }
        val newFilteredIdx = if (currentFilteredIdx < 0) 0 else (currentFilteredIdx + 1) % filtered.size
        setFilteredIndexDebounced(newFilteredIdx)
    }

    fun channelDown() {
        val filtered = filteredChannels.value ?: emptyList()
        if (filtered.isEmpty()) return
        cancelErrorRecovery()
        val current = _currentChannel.value
        val currentFilteredIdx =
            if (current != null) {
                filtered
                    .indexOfFirst { it.streamUrl == current.streamUrl && it.sourceId == current.sourceId }
                    .coerceAtLeast(0)
            } else {
                -1
            }
        val newFilteredIdx = if (currentFilteredIdx > 0) currentFilteredIdx - 1 else filtered.size - 1
        setFilteredIndexDebounced(newFilteredIdx)
    }

    fun selectChannel(index: Int) {
        val list = _channels.value ?: return
        if (list.isEmpty() || index < 0 || index >= list.size) return
        cancelErrorRecovery()
        setIndexAndPlay(index)
    }

    private fun setFilteredIndexDebounced(filteredIndex: Int) {
        val filtered = filteredChannels.value ?: return
        if (filteredIndex !in filtered.indices) return
        val channel = filtered[filteredIndex]
        val globalIdx =
            (_channels.value ?: emptyList()).indexOfFirst {
                it.streamUrl == channel.streamUrl &&
                    it.sourceId == channel.sourceId
            }
        if (globalIdx >= 0) {
            _currentIndex.value = globalIdx
            _currentChannel.value = channel
            updateCurrentProgramme()
            showOverlayTemporarily()
            handler.removeCallbacks(playChannelRunnable)
            handler.postDelayed(playChannelRunnable, DEBOUNCE_DELAY_MS)
        }
    }

    private fun setIndexDebounced(index: Int) {
        _currentIndex.value = index
        _currentChannel.value = _channels.value?.getOrNull(index)
        updateCurrentProgramme()
        showOverlayTemporarily()
        handler.removeCallbacks(playChannelRunnable)
        handler.postDelayed(playChannelRunnable, DEBOUNCE_DELAY_MS)
    }

    private fun setIndexAndPlay(index: Int) {
        _currentIndex.value = index
        _currentChannel.value = _channels.value?.getOrNull(index)
        updateCurrentProgramme()
        showOverlayTemporarily()
        handler.removeCallbacks(playChannelRunnable)
        executePlayChannel()
    }

    private fun executePlayChannel() {
        val channel = _currentChannel.value ?: return
        Log.d(TAG, "Requesting playback for: ${channel.name}")
        preferencesManager.saveLastChannel(channel.sourceId, channel.streamUrl)
        startLoadWatchdog()
    }

    fun setPlaybackState(state: PlaybackState) {
        _playbackState.value = state
        when (state) {
            is PlaybackState.Loading -> startLoadWatchdog()
            PlaybackState.Playing -> stopLoadWatchdog()
            is PlaybackState.Error -> {
                Log.e(TAG, "Playback error: ${state.message}")
                showOverlayTemporarily()
                handleFailure()
            }
            PlaybackState.Idle -> stopLoadWatchdog()
        }
    }

    private fun startLoadWatchdog() {
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
    }

    private fun stopLoadWatchdog() {
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.removeCallbacks(retryRunnable)
        retryCount = 0
        isRecovering = false
        _errorMessage.value = null
    }

    fun cancelErrorRecovery() {
        stopLoadWatchdog()
    }

    private fun onLoadTimeout() {
        if (isRecovering) return
        handleFailure()
    }

    private fun handleFailure() {
        if (isRecovering) return
        retryCount++
        showErrorState()
        if (retryCount < MAX_RETRIES) {
            isRecovering = true
            Log.d(TAG, "Retrying channel (attempt $retryCount of $MAX_RETRIES)")
            handler.postDelayed(retryRunnable, RETRY_DELAY_MS)
        } else {
            isRecovering = false
        }
    }

    private fun retryCurrentChannel() {
        isRecovering = false
        val index = _currentIndex.value ?: return
        setIndexAndPlay(index)
    }

    private fun showErrorState() {
        _errorMessage.value = R.string.error_channel_failed
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

    private fun updateCurrentProgramme() {
        val channel = _currentChannel.value
        val programme =
            if (channel != null) {
                repository.getCurrentProgramme(channel.tvgId)
            } else {
                null
            }
        _currentProgrammeState.value = CurrentProgrammeState(programme)
    }

    private fun startEpgTick() {
        handler.removeCallbacks(epgTickRunnable)
        handler.post(epgTickRunnable)
        handler.postDelayed(epgTickRunnable, EPG_TICK_INTERVAL_MS)
    }

    private fun stopEpgTick() {
        handler.removeCallbacks(epgTickRunnable)
    }

    fun addPlaylist(
        name: String,
        url: String,
        epgUrl: String? = null,
    ) {
        repository.addPlaylist(name, url, epgUrl)
        loadPlaylists()
    }

    fun updatePlaylist(
        id: String,
        name: String,
        url: String,
        epgUrl: String? = null,
    ) {
        val playlists = repository.getPlaylists()
        val playlist = playlists.find { it.id == id }
        if (playlist != null) {
            val updatedPlaylist = playlist.copy(name = name, url = url, epgUrl = epgUrl)
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
        stopEpgTick()
    }
}

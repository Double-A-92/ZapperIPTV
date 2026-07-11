package com.amedeo.zapperiptv.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.amedeo.zapperiptv.model.Channel
import com.amedeo.zapperiptv.model.EpgProgramme
import com.amedeo.zapperiptv.model.Playlist
import com.amedeo.zapperiptv.network.PlaylistDownloader
import com.amedeo.zapperiptv.parser.EpgParser
import com.amedeo.zapperiptv.parser.M3uParser
import com.amedeo.zapperiptv.storage.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class PlaylistRepository(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val playlistDownloader: PlaylistDownloader,
    private val m3uParser: M3uParser,
    private val epgParser: EpgParser = EpgParser(),
) {
    companion object {
        private const val TAG = "PlaylistRepository"
        private const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    private val epgCache = mutableMapOf<String, List<EpgProgramme>>()

    fun getPlaylists(): List<Playlist> = preferencesManager.loadPlaylists()

    fun addPlaylist(
        name: String,
        url: String,
        epgUrl: String? = null,
    ) {
        val playlists = getPlaylists().toMutableList()
        val newPlaylist =
            Playlist(
                id = UUID.randomUUID().toString(),
                name = name,
                url = url,
                lastUpdated = 0,
                epgUrl = epgUrl,
            )
        playlists.add(newPlaylist)
        preferencesManager.savePlaylists(playlists)
    }

    fun updatePlaylist(playlist: Playlist) {
        val playlists = getPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == playlist.id }
        if (index != -1) {
            playlists[index] = playlist
            preferencesManager.savePlaylists(playlists)
        }
    }

    fun removePlaylist(id: String) {
        val playlists = getPlaylists().toMutableList()
        playlists.removeAll { it.id == id }
        preferencesManager.savePlaylists(playlists)
    }

    suspend fun loadChannels(forceReload: Boolean = false): List<Channel> =
        withContext(Dispatchers.IO) {
            val playlists = getPlaylists()
            val allChannels = mutableListOf<Channel>()
            var globalChannelIndex = 1

            for (playlist in playlists) {
                val channels = loadPlaylistChannels(playlist, forceReload)
                // Assign display numbers
                channels.forEach { channel ->
                    channel.displayNumber = channel.tvgChNo ?: globalChannelIndex++
                }
                allChannels.addAll(channels)
            }
            allChannels
        }

    private suspend fun loadPlaylistChannels(
        playlist: Playlist,
        forceReload: Boolean,
    ): List<Channel> =
        try {
            if (playlist.url.startsWith("http")) {
                loadRemotePlaylist(playlist, forceReload)
            } else {
                loadLocalPlaylist(playlist)
            }
        } catch (e: IOException) {
            Log.e(TAG, "IO Error processing playlist ${playlist.name}", e)
            emptyList()
        }

    private suspend fun loadRemotePlaylist(
        playlist: Playlist,
        forceReload: Boolean,
    ): List<Channel> {
        val shouldRefresh = forceReload || isStale(playlist.lastUpdated)

        if (shouldRefresh) {
            // Attempt a fresh download; on success return immediately.
            downloadAndParse(playlist)?.let { return it }
            // Refresh failed: serve the previous cache if present, otherwise give up.
            return playlistDownloader
                .getCached(playlist.id)
                ?.let { m3uParser.parse(it, playlist.id) }
                .orEmpty()
        }

        // Playlist is still fresh: serve the cached copy. If the cache is missing
        // for a known playlist, fetch once so we don't return an empty list.
        val cachedStream = playlistDownloader.getCached(playlist.id)
        if (cachedStream != null) {
            return m3uParser.parse(cachedStream, playlist.id)
        }
        return if (playlist.lastUpdated > 0L) {
            downloadAndParse(playlist).orEmpty()
        } else {
            emptyList()
        }
    }

    private fun isStale(lastUpdated: Long): Boolean =
        lastUpdated == 0L ||
            (System.currentTimeMillis() - lastUpdated) > STALE_AFTER_MS

    private suspend fun downloadAndParse(playlist: Playlist): List<Channel>? =
        try {
            val stream = playlistDownloader.download(playlist.url, playlist.id)
            val channels = m3uParser.parse(stream, playlist.id)
            playlist.lastUpdated = System.currentTimeMillis()
            updatePlaylist(playlist)
            channels
        } catch (e: IOException) {
            Log.e(TAG, "Failed to download ${playlist.name}", e)
            null
        }

    private fun loadLocalPlaylist(playlist: Playlist): List<Channel> =
        try {
            val uri = Uri.parse(playlist.url)
            val stream: InputStream? = context.contentResolver.openInputStream(uri)
            if (stream != null) {
                val channels = m3uParser.parse(stream, playlist.id)
                playlist.lastUpdated = System.currentTimeMillis()
                updatePlaylist(playlist)
                channels
            } else {
                emptyList()
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for local playlist ${playlist.name}", e)
            emptyList()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to read local playlist ${playlist.name}", e)
            emptyList()
        }

    suspend fun loadEpg(forceReload: Boolean = false) {
        withContext(Dispatchers.IO) {
            val playlists = getPlaylists()
            playlists.forEach { playlist ->
                val epgUrl = playlist.epgUrl ?: return@forEach
                try {
                    val cacheFile =
                        if (forceReload || playlist.lastUpdated == 0L) {
                            playlistDownloader.downloadEpg(epgUrl, playlist.id)
                        } else {
                            playlistDownloader.getCachedEpg(playlist.id)
                                ?: playlistDownloader.downloadEpg(epgUrl, playlist.id)
                        }
                    val programmes = epgParser.parse(cacheFile)
                    epgCache.putAll(programmes)
                    Log.d(TAG, "Loaded ${programmes.size} EPG channels for ${playlist.name}")
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to load EPG for ${playlist.name}: ${e.message}", e)
                }
            }
        }
    }

    fun getCurrentProgramme(tvgId: String?): EpgProgramme? {
        if (tvgId == null) return null
        val now = System.currentTimeMillis()
        val list = epgCache[tvgId] ?: return null
        for (programme in list) {
            if (now in programme.startMillis until programme.endMillis) {
                return programme
            }
        }
        return null
    }
}

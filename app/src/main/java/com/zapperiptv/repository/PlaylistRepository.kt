package com.zapperiptv.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.zapperiptv.model.Channel
import com.zapperiptv.model.Playlist
import com.zapperiptv.network.PlaylistDownloader
import com.zapperiptv.parser.M3uParser
import com.zapperiptv.storage.PreferencesManager
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
) {
    companion object {
        private const val TAG = "PlaylistRepository"
    }

    fun getPlaylists(): List<Playlist> = preferencesManager.loadPlaylists()

    fun addPlaylist(
        name: String,
        url: String,
    ) {
        val playlists = getPlaylists().toMutableList()
        val newPlaylist =
            Playlist(
                id = UUID.randomUUID().toString(),
                name = name,
                url = url,
                enabled = true,
                lastUpdated = 0,
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

    fun togglePlaylist(id: String) {
        val playlists = getPlaylists().toMutableList()
        val index = playlists.indexOfFirst { it.id == id }
        if (index != -1) {
            playlists[index].enabled = !playlists[index].enabled
            preferencesManager.savePlaylists(playlists)
        }
    }

    suspend fun loadChannels(forceReload: Boolean = false): List<Channel> =
        withContext(Dispatchers.IO) {
            val playlists = getPlaylists().filter { it.enabled }
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
        var channels: List<Channel>? = null
        if (forceReload || playlist.lastUpdated == 0L) {
            channels = downloadAndParse(playlist)
        }

        if (channels == null) {
            val cachedStream = playlistDownloader.getCached(playlist.id)
            if (cachedStream != null) {
                channels = m3uParser.parse(cachedStream, playlist.id)
            } else if (playlist.lastUpdated > 0L) {
                channels = downloadAndParse(playlist)
            }
        }

        return channels ?: emptyList()
    }

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
}

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
import java.io.InputStream
import java.util.UUID

class PlaylistRepository(
    private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val playlistDownloader: PlaylistDownloader,
    private val m3uParser: M3uParser
) {

    companion object {
        private const val TAG = "PlaylistRepository"
    }

    fun getPlaylists(): List<Playlist> {
        return preferencesManager.loadPlaylists()
    }

    fun addPlaylist(name: String, url: String) {
        val playlists = getPlaylists().toMutableList()
        val newPlaylist = Playlist(
            id = UUID.randomUUID().toString(),
            name = name,
            url = url,
            enabled = true,
            lastUpdated = 0
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

    suspend fun loadChannels(forceReload: Boolean = false): List<Channel> = withContext(Dispatchers.IO) {
        val playlists = getPlaylists()
        val allChannels = mutableListOf<Channel>()
        var globalChannelIndex = 1

        for (playlist in playlists.filter { it.enabled }) {
            var channels: List<Channel> = emptyList()
            var loadSuccess = false

            try {
                // Try remote download or file read
                if (playlist.url.startsWith("http")) {
                    if (forceReload || playlist.lastUpdated == 0L) {
                        try {
                            val stream = playlistDownloader.download(playlist.url, playlist.id)
                            channels = m3uParser.parse(stream, playlist.id)
                            loadSuccess = true
                            playlist.lastUpdated = System.currentTimeMillis()
                            updatePlaylist(playlist)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to download ${playlist.name}, falling back to cache", e)
                        }
                    }

                    // Fallback to cache if download failed or not forced
                    if (!loadSuccess) {
                        val cachedStream = playlistDownloader.getCached(playlist.id)
                        if (cachedStream != null) {
                            channels = m3uParser.parse(cachedStream, playlist.id)
                            loadSuccess = true
                        } else if (playlist.lastUpdated > 0L) {
                            // We have no cache but lastUpdated > 0 means we used to have it, download must have failed
                            // Try download one more time if we skipped it earlier
                             try {
                                val stream = playlistDownloader.download(playlist.url, playlist.id)
                                channels = m3uParser.parse(stream, playlist.id)
                                loadSuccess = true
                                playlist.lastUpdated = System.currentTimeMillis()
                                updatePlaylist(playlist)
                            } catch (e: Exception) {
                                Log.e(TAG, "Retry download failed for ${playlist.name}", e)
                            }
                        } else {
                             Log.w(TAG, "No cache and never downloaded for ${playlist.name}")
                        }
                    }
                } else {
                    // Local file (content:// or file://)
                    try {
                        val uri = Uri.parse(playlist.url)
                        val stream: InputStream? = context.contentResolver.openInputStream(uri)
                        if (stream != null) {
                            channels = m3uParser.parse(stream, playlist.id)
                            loadSuccess = true
                            playlist.lastUpdated = System.currentTimeMillis()
                            updatePlaylist(playlist)
                        } else {
                            Log.w(TAG, "Could not open input stream for ${playlist.name}: ${playlist.url}")
                        }
                    } catch (e: SecurityException) {
                        Log.e(TAG, "Permission denied for local playlist ${playlist.name}", e)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to read local playlist ${playlist.name}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing playlist ${playlist.name}", e)
            }

            if (loadSuccess) {
                // Assign display numbers
                channels.forEach { channel ->
                    channel.displayNumber = channel.tvgChNo ?: globalChannelIndex++
                }
                allChannels.addAll(channels)
            }
        }

        // Return merged and numbered channels, preserving order
        allChannels
    }
}

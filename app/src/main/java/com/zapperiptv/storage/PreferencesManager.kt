package com.zapperiptv.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.zapperiptv.model.Playlist

class PreferencesManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val TAG = "PreferencesManager"
        private const val PREF_NAME = "zapperiptv_prefs"
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_LAST_CHANNEL_URL = "last_channel_url"
        private const val KEY_LAST_CHANNEL_SOURCE_ID = "last_channel_source_id"
        private const val KEY_LAST_SELECTED_PLAYLIST = "last_selected_playlist"
    }

    fun savePlaylists(playlists: List<Playlist>) {
        try {
            val json = gson.toJson(playlists)
            prefs.edit().putString(KEY_PLAYLISTS, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving playlists", e)
        }
    }

    fun loadPlaylists(): List<Playlist> {
        return try {
            val json = prefs.getString(KEY_PLAYLISTS, null)
            if (json != null) {
                val type = object : TypeToken<List<Playlist>>() {}.type
                gson.fromJson(json, type) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading playlists, corrupt data. Resetting.", e)
            prefs.edit().remove(KEY_PLAYLISTS).apply()
            emptyList()
        }
    }

    fun saveLastChannel(sourceId: String, streamUrl: String) {
        prefs.edit()
            .putString(KEY_LAST_CHANNEL_SOURCE_ID, sourceId)
            .putString(KEY_LAST_CHANNEL_URL, streamUrl)
            .apply()
    }

    fun loadLastChannel(): Pair<String, String>? {
        val sourceId = prefs.getString(KEY_LAST_CHANNEL_SOURCE_ID, null)
        val url = prefs.getString(KEY_LAST_CHANNEL_URL, null)
        return if (sourceId != null && url != null) {
            Pair(sourceId, url)
        } else {
            null
        }
    }

    fun saveLastSelectedPlaylist(playlistId: String?) {
        if (playlistId == null) {
            prefs.edit().remove(KEY_LAST_SELECTED_PLAYLIST).apply()
        } else {
            prefs.edit().putString(KEY_LAST_SELECTED_PLAYLIST, playlistId).apply()
        }
    }

    fun loadLastSelectedPlaylist(): String? {
        return prefs.getString(KEY_LAST_SELECTED_PLAYLIST, null)
    }
}

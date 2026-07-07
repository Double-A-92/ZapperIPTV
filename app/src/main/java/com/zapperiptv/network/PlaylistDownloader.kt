package com.zapperiptv.network

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class PlaylistDownloader(
    private val context: Context,
) {
    companion object {
        private const val TAG = "PlaylistDownloader"
        private const val TIMEOUT_MS = 10000
        private const val READ_TIMEOUT_MS = 15000
        private const val CACHE_DIR_NAME = "playlist_cache"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val cacheDir =
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }

    fun download(
        urlString: String,
        playlistId: String,
    ): InputStream {
        Log.d(TAG, "Downloading playlist: $urlString")

        val cleanUrlString = urlString.substringBefore("|")
        val url = URL(cleanUrlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")

        try {
            connection.connect()
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP Error: ${connection.responseCode}")
            }

            val cacheFile = File(cacheDir, "$playlistId.m3u")
            connection.inputStream.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            return FileInputStream(cacheFile)
        } catch (e: IOException) {
            Log.e(TAG, "Download failed for $urlString", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    fun getCached(playlistId: String): InputStream? {
        val cacheFile = File(cacheDir, "$playlistId.m3u")
        return if (cacheFile.exists()) FileInputStream(cacheFile) else null
    }
}

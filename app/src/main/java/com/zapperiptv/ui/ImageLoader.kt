package com.zapperiptv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

object ImageLoader {
    private const val TAG = "ImageLoader"
    
    // Cache up to ~20MB of images (1/8th of available max memory)
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = maxMemory / 8

    private val memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, bitmap: Bitmap): Int {
            return bitmap.byteCount / 1024
        }
    }

    private val currentJobs = ConcurrentHashMap<ImageView, Job>()
    private val scope = CoroutineScope(Dispatchers.Main)

    fun load(url: String?, imageView: ImageView, placeholderResId: Int) {
        // Cancel any pending load for this ImageView
        currentJobs[imageView]?.cancel()
        
        if (url.isNullOrEmpty()) {
            imageView.setImageResource(placeholderResId)
            return
        }

        val cachedBitmap = memoryCache.get(url)
        if (cachedBitmap != null) {
            imageView.setImageBitmap(cachedBitmap)
            return
        }

        imageView.setImageResource(placeholderResId)

        val job = scope.launch {
            val bitmap = downloadImage(url)
            if (bitmap != null) {
                memoryCache.put(url, bitmap)
                imageView.setImageBitmap(bitmap)
            }
        }
        currentJobs[imageView] = job
    }

    private suspend fun downloadImage(urlString: String): Bitmap? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.doInput = true
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val inputStream = connection.inputStream
                // Use BitmapFactory.Options to potentially downsample if needed, but for now simple decode
                return@withContext BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to load image: $urlString", e)
        } finally {
            connection?.disconnect()
        }
        null
    }
}

package com.amedeo.zapperiptv.util

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.amedeo.zapperiptv.PlayerActivity
import com.amedeo.zapperiptv.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object TvLauncherHelper {
    private const val TAG = "TvLauncherHelper"

    @OptIn(UnstableApi::class)
    fun updateWatchNext(context: Context, channel: Channel) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Check if already in Watch Next
                val existingProgramId = findWatchNextProgramId(context, channel)

                if (existingProgramId != -1L) {
                    // Update existing by removing it first (so it moves to the front)
                    val uri = ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, existingProgramId)
                    context.contentResolver.delete(uri, null, null)
                }

                // Limit the number of Watch Next programs to 5
                limitWatchNextPrograms(context, 4) // Limit to 4 before adding the 5th

                val intent = Intent(context, PlayerActivity::class.java).apply {
                    data = channel.streamUrl.toUri()
                    // We can add more info here if needed
                }

                @Suppress("RestrictedApi")
                val program = WatchNextProgram.Builder()
                    .setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
                    .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                    .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
                    .setTitle(channel.name)
                    .setDescription("IPTV Channel")
                    .setPosterArtUri(channel.logoUrl?.toUri())
                    .setIntentUri(Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME)))
                    .setInternalProviderId(channel.streamUrl)
                    .build()

                val uri = context.contentResolver.insert(
                    TvContractCompat.WatchNextPrograms.CONTENT_URI,
                    program.toContentValues(),
                )
                Log.d(TAG, "Added program to Watch Next: $uri")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating Watch Next", e)
            }
        }
    }

    private fun findWatchNextProgramId(context: Context, channel: Channel): Long {
        val cursor = context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
            @Suppress("RestrictedApi")
            val providerIdIndex = it.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            if (idIndex != -1 && providerIdIndex != -1) {
                while (it.moveToNext()) {
                    val providerId = it.getString(providerIdIndex)
                    if (providerId == channel.streamUrl) {
                        return it.getLong(idIndex)
                    }
                }
            }
        }
        return -1L
    }

    private fun limitWatchNextPrograms(context: Context, maxCount: Int) {
        val cursor = context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS),
            null,
            null,
            "${TvContractCompat.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS} DESC"
        )

        cursor?.use {
            if (it.count > maxCount) {
                val idIndex = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
                if (idIndex != -1) {
                    var count = 0
                    while (it.moveToNext()) {
                        count++
                        if (count > maxCount) {
                            val id = it.getLong(idIndex)
                            val uri = ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, id)
                            context.contentResolver.delete(uri, null, null)
                        }
                    }
                }
            }
        }
    }
}

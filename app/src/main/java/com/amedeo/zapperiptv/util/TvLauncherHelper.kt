package com.amedeo.zapperiptv.util

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.tvprovider.media.tv.Channel as TvChannel
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.amedeo.zapperiptv.R
import com.amedeo.zapperiptv.model.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object TvLauncherHelper {
    private const val TAG = "TvLauncherHelper"

    @OptIn(UnstableApi::class)
    fun updateWatchNext(context: Context, channel: Channel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val channelId = getOrCreateDefaultChannelId(context)

                // 1. Update system "Watch Next" row (Continue Watching)
                updateWatchNextRow(context, channel)

                // 2. Update our app's dedicated Preview Channel row
                if (channelId != -1L) {
                    updatePreviewRow(context, channelId, channel)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating TV launcher", e)
            }
        }
    }

    private fun updateWatchNextRow(context: Context, channel: Channel) {
        val existingProgramId = findWatchNextProgramId(context, channel)
        if (existingProgramId != -1L) {
            val uri = ContentUris.withAppendedId(TvContractCompat.WatchNextPrograms.CONTENT_URI, existingProgramId)
            context.contentResolver.delete(uri, null, null)
        }

        limitPrograms(context, TvContractCompat.WatchNextPrograms.CONTENT_URI, 5)

        val deepLinkUri = Uri.Builder()
            .scheme("zapperiptv")
            .authority("play")
            .appendQueryParameter("url", channel.streamUrl)
            .build()

        @Suppress("RestrictedApi")
        val program = WatchNextProgram.Builder()
            .setType(TvContractCompat.WatchNextPrograms.TYPE_MOVIE)
            .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
            .setLastEngagementTimeUtcMillis(System.currentTimeMillis())
            .setTitle(channel.name)
            .setDescription(context.getString(R.string.tv_launcher_program_description))
            .setPosterArtUri(channel.logoUrl?.toUri())
            .setIntentUri(deepLinkUri)
            .setInternalProviderId(channel.streamUrl)
            .setPackageName(context.packageName)
            .build()

        context.contentResolver.insert(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            program.toContentValues()
        )
    }

    private fun updatePreviewRow(context: Context, channelId: Long, channel: Channel) {
        val existingProgramId = findPreviewProgramId(context, channelId, channel)
        if (existingProgramId != -1L) {
            val uri = ContentUris.withAppendedId(TvContractCompat.PreviewPrograms.CONTENT_URI, existingProgramId)
            context.contentResolver.delete(uri, null, null)
        }

        limitPrograms(context, TvContractCompat.PreviewPrograms.CONTENT_URI, 10, channelId)

        val deepLinkUri = Uri.Builder()
            .scheme("zapperiptv")
            .authority("play")
            .appendQueryParameter("url", channel.streamUrl)
            .build()

        @Suppress("RestrictedApi")
        val program = PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
            .setTitle(channel.name)
            .setDescription(context.getString(R.string.tv_launcher_program_description))
            .setPosterArtUri(channel.logoUrl?.toUri())
            .setIntentUri(deepLinkUri)
            .setInternalProviderId(channel.streamUrl)
            .build()

        val uri = context.contentResolver.insert(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            program.toContentValues()
        )
        Log.i(TAG, "Inserted program into Preview Channel: $uri")
    }

    private fun getOrCreateDefaultChannelId(context: Context): Long {
        val cursor = context.contentResolver.query(
            TvContractCompat.Channels.CONTENT_URI,
            arrayOf(TvContractCompat.Channels._ID, TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID),
            null,
            null,
            null
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(TvContractCompat.Channels._ID)
            val providerIdIdx = it.getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)

            if (idIdx != -1 && providerIdIdx != -1) {
                while (it.moveToNext()) {
                    if (it.getString(providerIdIdx) == "recently_watched_channel") {
                        val id = it.getLong(idIdx)
                        Log.d(TAG, "Found existing Preview Channel ID: $id")
                        return id
                    }
                }
            }
        }

        // Create new
        @Suppress("RestrictedApi")
        val channel = TvChannel.Builder()
            .setType(TvContractCompat.Channels.TYPE_PREVIEW)
            .setDisplayName(context.getString(R.string.app_name))
            .setDescription(context.getString(R.string.tv_launcher_channel_description))
            .setAppLinkIntentUri("zapperiptv://home".toUri())
            .setInternalProviderId("recently_watched_channel")
            .build()

        val contentValues = channel.toContentValues().apply {
            put(TvContractCompat.Channels.COLUMN_PACKAGE_NAME, context.packageName)
            put(TvContractCompat.Channels.COLUMN_SEARCHABLE, 1)
        }

        val uri = context.contentResolver.insert(TvContractCompat.Channels.CONTENT_URI, contentValues)
        return if (uri != null) {
            val id = ContentUris.parseId(uri)
            Log.i(TAG, "Successfully created new Preview Channel with ID: $id")
            id
        } else {
            Log.e(TAG, "Failed to create Preview Channel")
            -1L
        }
    }

    private fun findWatchNextProgramId(context: Context, channel: Channel): Long {
        @Suppress("RestrictedApi")
        val projection = arrayOf(TvContractCompat.WatchNextPrograms._ID, TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
        val cursor = context.contentResolver.query(
            TvContractCompat.WatchNextPrograms.CONTENT_URI,
            projection,
            null,
            null,
            null
        )
        cursor?.use {
            val idIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms._ID)
            @Suppress("RestrictedApi")
            val providerIdIdx = it.getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)

            if (idIdx != -1 && providerIdIdx != -1) {
                while (it.moveToNext()) {
                    if (it.getString(providerIdIdx) == channel.streamUrl) return it.getLong(idIdx)
                }
            }
        }
        return -1L
    }

    private fun findPreviewProgramId(context: Context, channelId: Long, channel: Channel): Long {
        @Suppress("RestrictedApi")
        val projection = arrayOf(
            TvContractCompat.PreviewPrograms._ID,
            TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID
        )
        val cursor = context.contentResolver.query(
            TvContractCompat.PreviewPrograms.CONTENT_URI,
            projection,
            null,
            null,
            null
        )
        cursor?.use {
            val idIdx = it.getColumnIndex(TvContractCompat.PreviewPrograms._ID)
            @Suppress("RestrictedApi")
            val providerIdIdx = it.getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
            val channelIdIdx = it.getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)

            if (idIdx != -1 && providerIdIdx != -1 && channelIdIdx != -1) {
                while (it.moveToNext()) {
                    if (it.getLong(channelIdIdx) == channelId && it.getString(providerIdIdx) == channel.streamUrl) {
                        return it.getLong(idIdx)
                    }
                }
            }
        }
        return -1L
    }

    private fun limitPrograms(context: Context, contentUri: Uri, maxCount: Int, channelId: Long? = null) {
        val projection = if (channelId != null) {
            arrayOf(TvContractCompat.BaseTvColumns._ID, TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
        } else {
            arrayOf(TvContractCompat.BaseTvColumns._ID)
        }

        val cursor = context.contentResolver.query(
            contentUri,
            projection,
            null,
            null,
            "${TvContractCompat.BaseTvColumns._ID} DESC"
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(TvContractCompat.BaseTvColumns._ID)
            val chanIdIdx = if (channelId != null) it.getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID) else -1

            if (idIdx != -1) {
                var count = 0
                while (it.moveToNext()) {
                    val matchesChannel = channelId == null || (chanIdIdx != -1 && it.getLong(chanIdIdx) == channelId)
                    if (matchesChannel) {
                        count++
                        if (count > maxCount) {
                            val id = it.getLong(idIdx)
                            context.contentResolver.delete(ContentUris.withAppendedId(contentUri, id), null, null)
                        }
                    }
                }
            }
        }
    }

    fun ensureDefaultChannelExists(context: Context) {
        getOrCreateDefaultChannelId(context)
    }
}

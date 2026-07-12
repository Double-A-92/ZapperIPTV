package com.amedeo.zapperiptv.util

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.RemoteException
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import com.amedeo.zapperiptv.R
import com.amedeo.zapperiptv.model.Channel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import androidx.tvprovider.media.tv.Channel as TvChannel

object TvLauncherHelper {
    private const val TAG = "TvLauncherHelper"
    private const val DEFAULT_CHANNEL_PROVIDER_ID = "recently_watched_channel"
    private const val WATCH_NEXT_LIMIT = 5
    private const val PREVIEW_LIMIT = 10

    @OptIn(UnstableApi::class)
    fun updateWatchNext(
        context: Context,
        channel: Channel,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!isContextAlive(context)) return

        val supervisor = SupervisorJob()
        val exceptionHandler =
            CoroutineExceptionHandler { _, throwable ->
                Log.w(TAG, "TV provider update failed: ${throwable.message}", throwable)
            }
        CoroutineScope(Dispatchers.IO + supervisor + exceptionHandler).launch {
            try {
                val channelId = getOrCreateDefaultChannelId(context)

                updateWatchNextRow(context, channel)

                if (channelId != -1L) {
                    updatePreviewRow(context, channelId, channel)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security error updating TV launcher", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "State error updating TV launcher", e)
            } catch (e: RemoteException) {
                Log.w(TAG, "TV provider unavailable (DeadObjectException); skipping", e)
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun ensureDefaultChannelExists(context: Context) {
        if (!isContextAlive(context)) return
        try {
            getOrCreateDefaultChannelId(context)
        } catch (e: RemoteException) {
            Log.w(TAG, "TV provider unavailable during ensureDefaultChannelExists; skipping", e)
        }
    }

    private fun isContextAlive(context: Context): Boolean =
        context !is android.app.Activity ||
            (!(context as android.app.Activity).isFinishing && !(context as android.app.Activity).isDestroyed)

    private fun updateWatchNextRow(
        context: Context,
        channel: Channel,
    ) {
        val existingProgramId = findWatchNextProgramId(context, channel)
        if (existingProgramId != -1L) {
            val uri =
                ContentUris
                    .withAppendedId(
                        TvContractCompat.WatchNextPrograms.CONTENT_URI,
                        existingProgramId,
                    )
            context
                .contentResolver
                .delete(uri, null, null)
        }

        limitPrograms(
            context,
            TvContractCompat
                .WatchNextPrograms
                .CONTENT_URI,
            WATCH_NEXT_LIMIT,
        )

        val deepLinkUri =
            Uri
                .Builder()
                .scheme("zapperiptv")
                .authority("play")
                .appendQueryParameter("url", channel.streamUrl)
                .build()

        @Suppress("RestrictedApi")
        val program =
            WatchNextProgram
                .Builder()
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

        context
            .contentResolver
            .insert(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                program.toContentValues(),
            )
    }

    private fun updatePreviewRow(
        context: Context,
        channelId: Long,
        channel: Channel,
    ) {
        val existingProgramId = findPreviewProgramId(context, channelId, channel)
        if (existingProgramId != -1L) {
            val uri =
                ContentUris
                    .withAppendedId(
                        TvContractCompat.PreviewPrograms.CONTENT_URI,
                        existingProgramId,
                    )
            context
                .contentResolver
                .delete(uri, null, null)
        }

        limitPrograms(
            context,
            TvContractCompat
                .PreviewPrograms
                .CONTENT_URI,
            PREVIEW_LIMIT,
            channelId,
        )

        val deepLinkUri =
            Uri
                .Builder()
                .scheme("zapperiptv")
                .authority("play")
                .appendQueryParameter("url", channel.streamUrl)
                .build()

        @Suppress("RestrictedApi")
        val program =
            PreviewProgram
                .Builder()
                .setChannelId(channelId)
                .setType(TvContractCompat.PreviewPrograms.TYPE_MOVIE)
                .setTitle(channel.name)
                .setDescription(context.getString(R.string.tv_launcher_program_description))
                .setPosterArtUri(channel.logoUrl?.toUri())
                .setIntentUri(deepLinkUri)
                .setInternalProviderId(channel.streamUrl)
                .build()

        val uri =
            context
                .contentResolver
                .insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    program.toContentValues(),
                )
        Log.i(TAG, "Inserted program into Preview Channel: $uri")
    }

    private fun getOrCreateDefaultChannelId(context: Context): Long {
        val existingId = findExistingDefaultChannel(context)
        if (existingId != -1L) return existingId

        // Create new
        @Suppress("RestrictedApi")
        val channel =
            TvChannel
                .Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(context.getString(R.string.app_name))
                .setDescription(context.getString(R.string.tv_launcher_channel_description))
                .setAppLinkIntentUri("zapperiptv://home".toUri())
                .setInternalProviderId(DEFAULT_CHANNEL_PROVIDER_ID)
                .build()

        val contentValues =
            channel.toContentValues().apply {
                put(TvContractCompat.Channels.COLUMN_PACKAGE_NAME, context.packageName)
                put(TvContractCompat.Channels.COLUMN_SEARCHABLE, 1)
            }

        val uri =
            context
                .contentResolver
                .insert(
                    TvContractCompat.Channels.CONTENT_URI,
                    contentValues,
                )
        return if (uri != null) {
            val id = ContentUris.parseId(uri)
            Log.i(TAG, "Successfully created new Preview Channel with ID: $id")
            id
        } else {
            Log.e(TAG, "Failed to create Preview Channel")
            -1L
        }
    }

    private fun findExistingDefaultChannel(context: Context): Long =
        context
            .contentResolver
            .query(
                TvContractCompat.Channels.CONTENT_URI,
                arrayOf(
                    TvContractCompat.Channels._ID,
                    TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                searchForChannelId(cursor)
            } ?: -1L

    private fun searchForChannelId(cursor: Cursor): Long {
        val idIdx =
            cursor
                .getColumnIndex(TvContractCompat.Channels._ID)
        val providerIdIdx =
            cursor
                .getColumnIndex(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_ID)
        var result = -1L

        if (idIdx != -1 && providerIdIdx != -1) {
            while (cursor.moveToNext()) {
                if (cursor.getString(providerIdIdx) == DEFAULT_CHANNEL_PROVIDER_ID) {
                    result = cursor.getLong(idIdx)
                    Log.d(TAG, "Found existing Preview Channel ID: $result")
                    break
                }
            }
        }
        return result
    }

    private fun findWatchNextProgramId(
        context: Context,
        channel: Channel,
    ): Long {
        @Suppress("RestrictedApi")
        val projection =
            arrayOf(
                TvContractCompat.WatchNextPrograms._ID,
                TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID,
            )
        return context
            .contentResolver
            .query(
                TvContractCompat.WatchNextPrograms.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                searchForProgramId(cursor, channel.streamUrl)
            } ?: -1L
    }

    private fun searchForProgramId(
        cursor: Cursor,
        streamUrl: String,
    ): Long {
        val idIdx =
            cursor
                .getColumnIndex(TvContractCompat.WatchNextPrograms._ID)

        @Suppress("RestrictedApi")
        val providerIdIdx =
            cursor
                .getColumnIndex(TvContractCompat.WatchNextPrograms.COLUMN_INTERNAL_PROVIDER_ID)
        var result = -1L

        if (idIdx != -1 && providerIdIdx != -1) {
            while (cursor.moveToNext()) {
                if (cursor.getString(providerIdIdx) == streamUrl) {
                    result = cursor.getLong(idIdx)
                    break
                }
            }
        }
        return result
    }

    private fun findPreviewProgramId(
        context: Context,
        channelId: Long,
        channel: Channel,
    ): Long {
        @Suppress("RestrictedApi")
        val projection =
            arrayOf(
                TvContractCompat.PreviewPrograms._ID,
                TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID,
                TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
            )
        return context
            .contentResolver
            .query(
                TvContractCompat.PreviewPrograms.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                searchForPreviewProgramId(cursor, channelId, channel.streamUrl)
            } ?: -1L
    }

    private fun searchForPreviewProgramId(
        cursor: Cursor,
        channelId: Long,
        streamUrl: String,
    ): Long {
        val idIdx =
            cursor
                .getColumnIndex(TvContractCompat.PreviewPrograms._ID)

        @Suppress("RestrictedApi")
        val providerIdIdx =
            cursor
                .getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_INTERNAL_PROVIDER_ID)
        val channelIdIdx =
            cursor
                .getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
        var result = -1L

        if (idIdx != -1 && providerIdIdx != -1 && channelIdIdx != -1) {
            while (cursor.moveToNext()) {
                val matches =
                    cursor.getLong(channelIdIdx) == channelId &&
                        cursor.getString(providerIdIdx) == streamUrl
                if (matches) {
                    result = cursor.getLong(idIdx)
                    break
                }
            }
        }
        return result
    }

    private fun limitPrograms(
        context: Context,
        contentUri: Uri,
        maxCount: Int,
        channelId: Long? = null,
    ) {
        val projection =
            if (channelId != null) {
                arrayOf(
                    TvContractCompat.BaseTvColumns._ID,
                    TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID,
                )
            } else {
                arrayOf(TvContractCompat.BaseTvColumns._ID)
            }

        val cursor =
            context
                .contentResolver
                .query(
                    contentUri,
                    projection,
                    null,
                    null,
                    "${TvContractCompat.BaseTvColumns._ID} DESC",
                )

        cursor?.use {
            processLimit(context, it, contentUri, maxCount, channelId)
        }
    }

    private fun processLimit(
        context: Context,
        cursor: Cursor,
        contentUri: Uri,
        maxCount: Int,
        channelId: Long?,
    ) {
        val idIdx =
            cursor
                .getColumnIndex(TvContractCompat.BaseTvColumns._ID)
        val chanIdIdx =
            if (channelId != null) {
                cursor.getColumnIndex(TvContractCompat.PreviewPrograms.COLUMN_CHANNEL_ID)
            } else {
                -1
            }

        if (idIdx == -1) return

        var count = 0
        while (cursor.moveToNext()) {
            val matchesChannel =
                channelId == null ||
                    (chanIdIdx != -1 && cursor.getLong(chanIdIdx) == channelId)
            if (matchesChannel) {
                count++
                deleteIfOverLimit(context, contentUri, count, maxCount, cursor.getLong(idIdx))
            }
        }
    }

    private fun deleteIfOverLimit(
        context: Context,
        contentUri: Uri,
        count: Int,
        maxCount: Int,
        id: Long,
    ) {
        if (count > maxCount) {
            context
                .contentResolver
                .delete(
                    ContentUris.withAppendedId(contentUri, id),
                    null,
                    null,
                )
        }
    }
}

package com.amedeo.zapperiptv.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat

class TvInitializationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TvContractCompat.ACTION_INITIALIZE_PROGRAMS) {
            Log.d("TvInitReceiver", "Initializing TV programs/channels")
            // This will trigger the creation of the default channel
            // so it shows up in the "Customize Channels" menu immediately.
            TvLauncherHelper.ensureDefaultChannelExists(context)
        }
    }
}

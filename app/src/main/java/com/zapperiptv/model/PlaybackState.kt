package com.zapperiptv.model

sealed class PlaybackState {
    object Idle : PlaybackState()

    object Loading : PlaybackState()

    object Playing : PlaybackState()

    data class Error(
        val message: String,
    ) : PlaybackState()
}

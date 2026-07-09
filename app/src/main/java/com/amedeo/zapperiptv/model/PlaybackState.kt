package com.amedeo.zapperiptv.model

sealed class PlaybackState {
    data object Idle : PlaybackState()

    data object Loading : PlaybackState()

    data object Playing : PlaybackState()

    data class Error(
        val message: String,
    ) : PlaybackState()
}

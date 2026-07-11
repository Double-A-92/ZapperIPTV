package com.amedeo.zapperiptv.model

data class Playlist(
    val id: String, // UUID
    val name: String,
    val url: String, // URL or file URI
    var lastUpdated: Long, // epoch millis, 0 if never
    val epgUrl: String? = null,
)

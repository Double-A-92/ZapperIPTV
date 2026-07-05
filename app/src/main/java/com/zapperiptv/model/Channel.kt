package com.zapperiptv.model

data class Channel(
    val name: String,
    val streamUrl: String,
    val group: String,
    val logoUrl: String?,
    val sourceId: String,       // playlist ID this channel came from
    val tvgName: String?,
    val tvgChNo: Int?,          // channel number from M3U, nullable
    var displayNumber: Int = 0  // assigned after merge
)

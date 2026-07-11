package com.amedeo.zapperiptv.model

data class EpgProgramme(
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
)

package com.amedeo.zapperiptv.model

data class CurrentProgrammeState(
    val programme: EpgProgramme?,
    val tickMillis: Long = System.currentTimeMillis(),
)

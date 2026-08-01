package com.connorb.omnitv

data class EpgProgram(
    val channelId: String,
    val startMillis: Long,
    val stopMillis: Long,
    val title: String,
    val description: String? = null
)

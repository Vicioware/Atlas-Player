package com.connorb.omnitv

data class Playlist(
    val channels: List<Channel>,
    val epgUrl: String? = null
)

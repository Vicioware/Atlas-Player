package com.connorb.omnitv

data class Channel(
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null
)

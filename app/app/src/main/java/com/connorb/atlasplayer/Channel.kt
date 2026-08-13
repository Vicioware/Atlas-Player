package com.connorb.atlasplayer

/**
 * Un canal reproducible. Varias entradas #EXTINF con el mismo `group-title`
 * no vacío representan fuentes del mismo canal: la primera aparece en la lista
 * principal y las demás quedan en [alternates] (fuentes alternativas).
 */
data class Channel(
    val id: String,
    val position: Int,
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val group: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val alternates: List<ChannelAlternate> = emptyList(),
    val normalizedName: String = normalizeKey(name)
)

/**
 * Fuente alternativa para reproducir el canal actual.
 * No aparece en la lista principal de canales, solo en el drawer derecho.
 */
data class ChannelAlternate(
    val id: String,
    val name: String,
    val url: String,
    val logoUrl: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
    val tvgId: String? = null,
    val tvgName: String? = null
)

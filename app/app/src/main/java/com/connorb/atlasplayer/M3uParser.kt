package com.connorb.atlasplayer

import java.io.IOException
import java.security.MessageDigest

object M3uParser {
    private const val MAX_PLAYLIST_BYTES = 8L * 1024L * 1024L
    private const val MAX_DECODED_PLAYLIST_BYTES = 16L * 1024L * 1024L
    private const val MAX_CHANNELS = 20_000
    private const val MAX_LINE_LENGTH = 16_384
    private const val MAX_USER_AGENT_LENGTH = 512

    fun fetchAndParse(playlistUrl: String): Playlist {
        val initialUrl = NetworkPolicy.playlistUrl(playlistUrl)

        val response = SafeHttp.open(
            initialUrl = initialUrl,
            allowedSchemes = setOf("https"),
            resourceName = "playlist"
        )

        try {
            if (response.connection.responseCode !in 200..299) {
                throw IOException("No se pudo cargar la playlist")
            }

            val content = SafeHttp.readUtf8(
                connection = response.connection,
                maxCompressedBytes = MAX_PLAYLIST_BYTES,
                maxDecodedBytes = MAX_DECODED_PLAYLIST_BYTES
            )

            return parse(
                m3uContent = content,
                sourceUrl = response.finalUrl.toExternalForm()
            )
        } finally {
            response.connection.disconnect()
        }
    }

    fun parse(
        m3uContent: String,
        sourceUrl: String = ""
    ): Playlist {
        val parsed = mutableListOf<Channel>()
        var pending: PendingChannel? = null

        m3uContent
            .lineSequence()
            .forEach { rawLine ->
                if (parsed.size >= MAX_CHANNELS) {
                    throw IOException("La playlist excede el máximo de $MAX_CHANNELS canales")
                }

                if (rawLine.length > MAX_LINE_LENGTH) {
                    throw IOException("La playlist contiene una línea demasiado larga")
                }

                val line = rawLine.trim()
                if (line.isEmpty()) return@forEach

                when {
                    line.startsWith("#EXTINF", ignoreCase = true) -> {
                        pending = PendingChannel(
                            name = extractName(line),
                            logoUrl = extractAttribute(line, "tvg-logo")
                                ?: extractAttribute(line, "logo"),
                            group = extractAttribute(line, "group-title"),
                            tvgId = extractAttribute(line, "tvg-id"),
                            tvgName = extractAttribute(line, "tvg-name"),
                            userAgent = sanitizeUserAgent(extractAttribute(line, "user-agent")),
                            referrer = extractAttribute(line, "referrer")
                        )
                    }

                    line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                        val current = pending ?: return@forEach
                        val option = line.substringAfter(":", "").trim()

                        when {
                            option.startsWith("http-user-agent=", ignoreCase = true) -> {
                                pending = current.copy(
                                    userAgent = sanitizeUserAgent(
                                        option.substringAfter("=", "").trim()
                                            .takeIf { it.isNotEmpty() }
                                    )
                                )
                            }

                            option.startsWith("http-referrer=", ignoreCase = true) -> {
                                pending = current.copy(
                                    referrer = option.substringAfter("=", "").trim()
                                        .takeIf { it.isNotEmpty() }
                                )
                            }
                        }
                    }

                    line.startsWith("#") -> Unit

                    else -> {
                        val current = pending ?: return@forEach
                        val streamUrl = line.trim()

                        try {
                            NetworkPolicy.streamUrl(streamUrl)

                            val safeLogo = current.logoUrl
                                ?.takeIf { isValidLogoUrl(it) }
                                ?.trim()

                            val safeReferrer = current.referrer
                                ?.let { NetworkPolicy.referrerOrNull(it) }

                            val position = parsed.size + 1
                            parsed += Channel(
                                id = stableChannelId(
                                    position = position,
                                    name = current.name,
                                    streamUrl = streamUrl,
                                    tvgId = current.tvgId
                                ),
                                position = position,
                                name = current.name,
                                url = streamUrl,
                                logoUrl = safeLogo,
                                userAgent = current.userAgent,
                                referrer = safeReferrer,
                                group = current.group?.take(512),
                                tvgId = current.tvgId?.take(512),
                                tvgName = current.tvgName?.take(512)
                            )
                        } catch (_: IOException) {
                            // Canal inválido: se omite, pero la playlist sigue siendo utilizable.
                        } finally {
                            pending = null
                        }
                    }
                }
            }

        return Playlist(
            sourceUrl = sourceUrl,
            channels = buildChannelsWithStreamOptions(parsed)
        )
    }

    /**
     * En esta playlist, `group-title` identifica al canal: todas las entradas
     * con el mismo valor no vacío son fuentes del mismo canal. Solo cuando no
     * existe se usa una identidad de respaldo, con espacios de claves separados
     * para no mezclar atributos de distinta naturaleza.
     */
    private fun buildChannelsWithStreamOptions(parsed: List<Channel>): List<Channel> {
        val grouped = LinkedHashMap<String, MutableList<Channel>>()

        parsed.forEach { channel ->
            val identity = channel.group
                ?.takeIf { it.isNotBlank() }
                ?.let { "group:${normalizeKey(it)}" }
                ?: channel.tvgId
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "fallback-id:${normalizeKey(it)}" }
                ?: channel.tvgName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "fallback-tvg-name:${normalizeKey(it)}" }
                ?: channel.normalizedName
                    .takeIf { it.isNotBlank() && it != normalizeKey("Canal sin nombre") }
                    ?.let { "fallback-name:$it" }
                ?: "fallback-entry:${channel.id}"

            grouped.getOrPut(identity) { mutableListOf() }.add(channel)
        }

        return grouped.values.mapIndexed { index, matchingStreams ->
            val uniqueStreams = matchingStreams.distinctBy { it.url.trim() }
            val primary = uniqueStreams.first()

            primary.copy(
                position = index + 1,
                alternates = uniqueStreams.drop(1).map { it.toAlternate() }
            )
        }
    }

    private fun Channel.toAlternate(): ChannelAlternate {
        return ChannelAlternate(
            id = id,
            name = name,
            url = url,
            logoUrl = logoUrl,
            userAgent = userAgent,
            referrer = referrer,
            tvgId = tvgId,
            tvgName = tvgName
        )
    }

    private fun sanitizeUserAgent(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty()) return null

        val withoutControlChars = trimmed.filter { char ->
            char.code >= 0x20 && char.code != 0x7F
        }

        return withoutControlChars
            .take(MAX_USER_AGENT_LENGTH)
            .takeIf { it.isNotBlank() }
    }

    private fun isValidLogoUrl(value: String): Boolean {
        return runCatching { NetworkPolicy.logoUrlOrNull(value) }.isSuccess
    }

    private fun stableChannelId(
        position: Int,
        name: String,
        streamUrl: String,
        tvgId: String?
    ): String {
        val material = "$position\u0000${tvgId.orEmpty()}\u0000$name\u0000$streamUrl"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun extractName(extinfLine: String): String {
        val commaIndex = extinfLine.lastIndexOf(',')
        val rawName = if (commaIndex >= 0) {
            extinfLine.substring(commaIndex + 1).trim()
        } else {
            ""
        }

        return rawName.ifBlank {
            extractAttribute(extinfLine, "tvg-name") ?: "Canal sin nombre"
        }
    }

    private fun extractAttribute(line: String, attribute: String): String? {
        val escaped = Regex.escape(attribute)
        val quoted = Regex(
            """$escaped\s*=\s*("([^"]*)"|'([^']*)')""",
            RegexOption.IGNORE_CASE
        )

        val quotedMatch = quoted.find(line)
        if (quotedMatch != null) {
            return quotedMatch.groupValues[2]
                .ifBlank { quotedMatch.groupValues[3] }
                .trim()
                .takeIf { it.isNotEmpty() }
        }

        val unquoted = Regex(
            """$escaped\s*=\s*([^\s,]+)""",
            RegexOption.IGNORE_CASE
        ).find(line)

        return unquoted?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private data class PendingChannel(
        val name: String,
        val logoUrl: String?,
        val group: String?,
        val tvgId: String?,
        val tvgName: String?,
        val userAgent: String?,
        val referrer: String?
    )
}
package com.connorb.atlasplayer

import java.io.IOException
import java.net.URI
import java.net.URL

object NetworkPolicy {
    private const val MAX_REDIRECTS = 5

    fun playlistUrl(value: String): URL = secureHttpUrl(value, "playlist")

    fun logoUrlOrNull(value: String?): URL? {
        val clean = value?.trim().orEmpty()
        if (clean.isEmpty()) return null
        return secureHttpUrl(clean, "logo")
    }

    fun streamUrl(value: String): URL {
        val url = parseUrl(value, "stream")
        requireAllowedScheme(url, setOf("http", "https"), "stream")
        requireNoCredentials(url, "stream")
        return url
    }

    fun referrerOrNull(value: String?): String? {
        val clean = value?.trim().orEmpty()
        if (clean.isEmpty()) return null

        val url = parseUrl(clean, "referer")
        requireAllowedScheme(url, setOf("https"), "referer")
        requireNoCredentials(url, "referer")
        return url.toExternalForm()
    }

    fun resolveRedirect(
        current: URL,
        location: String,
        allowedSchemes: Set<String>,
        resourceName: String
    ): URL {
        val redirected = URL(current, location)
        requireAllowedScheme(redirected, allowedSchemes, resourceName)
        requireNoCredentials(redirected, resourceName)
        return redirected
    }

    fun maxRedirects(): Int = MAX_REDIRECTS

    private fun secureHttpUrl(value: String, resourceName: String): URL {
        val url = parseUrl(value, resourceName)
        requireAllowedScheme(url, setOf("https"), resourceName)
        requireNoCredentials(url, resourceName)
        return url
    }

    private fun parseUrl(value: String, resourceName: String): URL {
        return try {
            URL(value.trim())
        } catch (e: Exception) {
            throw IOException("URL de $resourceName inválida", e)
        }
    }

    private fun requireAllowedScheme(
        url: URL,
        allowedSchemes: Set<String>,
        resourceName: String
    ) {
        val scheme = url.protocol.lowercase()
        if (scheme !in allowedSchemes) {
            throw IOException(
                "El recurso $resourceName debe usar ${allowedSchemes.joinToString(" o ").uppercase()}"
            )
        }
    }

    private fun requireNoCredentials(url: URL, resourceName: String) {
        val userInfo = try {
            URI(url.toExternalForm()).userInfo
        } catch (_: Exception) {
            null
        }

        if (!userInfo.isNullOrBlank()) {
            throw IOException("La URL de $resourceName no puede incluir credenciales")
        }
    }
}
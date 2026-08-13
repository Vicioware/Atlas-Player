package com.connorb.atlasplayer

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object SafeHttp {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val BUFFER_SIZE = 64 * 1024

    data class Response(
        val connection: HttpURLConnection,
        val finalUrl: URL,
        val etag: String?,
        val lastModified: String?
    )

    fun open(
        initialUrl: URL,
        allowedSchemes: Set<String>,
        resourceName: String,
        headers: Map<String, String> = emptyMap(),
        ifNoneMatch: String? = null,
        ifModifiedSince: String? = null
    ): Response {
        var currentUrl = initialUrl
        var redirects = 0

        while (true) {
            val connection = (currentUrl.openConnection() as? HttpURLConnection)
                ?: throw IOException("La URL de $resourceName no usa HTTP")

            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty(
                "User-Agent",
                "Atlas Player/1.0 (Android TV)"
            )
            connection.setRequestProperty("Accept-Encoding", "gzip")

            headers.forEach { (key, value) ->
                if (value.isNotBlank()) {
                    connection.setRequestProperty(key, value)
                }
            }

            ifNoneMatch?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("If-None-Match", it)
            }

            ifModifiedSince?.takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("If-Modified-Since", it)
            }

            try {
                val code = connection.responseCode

                if (code in 300..399 && code != HttpURLConnection.HTTP_NOT_MODIFIED) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Redirección de $resourceName sin Location")

                    if (redirects++ >= NetworkPolicy.maxRedirects()) {
                        throw IOException("Demasiadas redirecciones al cargar $resourceName")
                    }

                    currentUrl = NetworkPolicy.resolveRedirect(
                        current = currentUrl,
                        location = location,
                        allowedSchemes = allowedSchemes,
                        resourceName = resourceName
                    )

                    connection.disconnect()
                    continue
                }

                return Response(
                    connection = connection,
                    finalUrl = currentUrl,
                    etag = connection.getHeaderField("ETag"),
                    lastModified = connection.getHeaderField("Last-Modified")
                )
            } catch (e: Exception) {
                connection.disconnect()
                throw e
            }
        }
    }

    fun decodedStream(
        connection: HttpURLConnection,
        maxCompressedBytes: Long,
        maxDecodedBytes: Long
    ): InputStream {
        val declaredLength = connection.contentLengthLong
        if (declaredLength > maxCompressedBytes) {
            throw IOException(
                "La respuesta comprimida supera el límite de ${maxCompressedBytes / 1024 / 1024} MB"
            )
        }

        val raw = LimitedInputStream(
            BufferedInputStream(connection.inputStream, BUFFER_SIZE),
            maxCompressedBytes
        )

        val encodedAsGzip = connection.contentEncoding
            ?.contains("gzip", ignoreCase = true) == true

        val decoded = if (encodedAsGzip || hasGzipSignature(raw)) {
            GZIPInputStream(raw, BUFFER_SIZE)
        } else {
            raw
        }

        return LimitedInputStream(decoded, maxDecodedBytes)
    }

    fun readUtf8(
        connection: HttpURLConnection,
        maxCompressedBytes: Long,
        maxDecodedBytes: Long
    ): String {
        decodedStream(connection, maxCompressedBytes, maxDecodedBytes).use { input ->
            val output = ByteArrayOutputStream()
            input.copyTo(output, BUFFER_SIZE)
            return output.toString(Charsets.UTF_8.name())
        }
    }

    private fun hasGzipSignature(input: InputStream): Boolean {
        if (input !is BufferedInputStream) return false

        input.mark(2)
        val first = input.read()
        val second = input.read()
        input.reset()

        return first == 0x1F && second == 0x8B
    }

    private class LimitedInputStream(
        private val source: InputStream,
        private val maxBytes: Long
    ) : InputStream() {
        private var bytesRead = 0L

        override fun read(): Int {
            val value = source.read()
            if (value >= 0) count(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = source.read(buffer, offset, length)
            if (count > 0) count(count)
            return count
        }

        override fun close() {
            source.close()
        }

        private fun count(delta: Int) {
            bytesRead += delta
            if (bytesRead > maxBytes) {
                throw IOException(
                    "La descarga supera el límite de ${maxBytes / 1024 / 1024} MB"
                )
            }
        }
    }
}
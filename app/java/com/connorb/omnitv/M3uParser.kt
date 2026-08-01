package com.connorb.omnitv

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object M3uParser {

    private const val CONNECT_TIMEOUT_MS =
        15_000

    private const val READ_TIMEOUT_MS =
        30_000

    private const val MAX_REDIRECTS = 5

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 11) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    fun parse(
        m3uContent: String
    ): Playlist {
        val channels =
            mutableListOf<Channel>()

        val content =
            m3uContent.removePrefix(
                "\uFEFF"
            )

        val lines =
            content.lines()

        var epgUrl: String? = null
        var index = 0

        while (index < lines.size) {
            val line =
                lines[index].trim()

            if (
                line.startsWith(
                    "#EXTM3U",
                    ignoreCase = true
                )
            ) {
                epgUrl =
                    extractAttribute(
                        line,
                        "url-tvg"
                    )
                        ?: extractAttribute(
                            line,
                            "x-tvg-url"
                        )
                        ?: extractAttribute(
                            line,
                            "tvg-url"
                        )
            }

            if (
                line.startsWith(
                    "#EXTINF:",
                    ignoreCase = true
                )
            ) {
                val name =
                    extractName(line)

                val logo =
                    extractAttribute(
                        line,
                        "tvg-logo"
                    )
                        ?: extractAttribute(
                            line,
                            "logo"
                        )

                val group =
                    extractAttribute(
                        line,
                        "group-title"
                    )

                val tvgId =
                    extractAttribute(
                        line,
                        "tvg-id"
                    )

                val tvgName =
                    extractAttribute(
                        line,
                        "tvg-name"
                    )

                var userAgent =
                    extractAttribute(
                        line,
                        "user-agent"
                    )

                var referrer =
                    extractAttribute(
                        line,
                        "referrer"
                    )

                var nextIndex =
                    index + 1

                var streamUrl: String? = null

                while (
                    nextIndex < lines.size
                ) {
                    val next =
                        lines[nextIndex]
                            .trim()

                    when {
                        next.isEmpty() -> Unit

                        next.startsWith(
                            "#EXTVLCOPT:",
                            ignoreCase = true
                        ) -> {
                            val option =
                                next
                                    .substringAfter(
                                        ":"
                                    )
                                    .trim()

                            when {
                                option.startsWith(
                                    "http-user-agent=",
                                    ignoreCase = true
                                ) -> {
                                    val value =
                                        option
                                            .substringAfter(
                                                "="
                                            )
                                            .trim()

                                    if (
                                        value.isNotEmpty()
                                    ) {
                                        userAgent =
                                            value
                                    }
                                }

                                option.startsWith(
                                    "http-referrer=",
                                    ignoreCase = true
                                ) -> {
                                    val value =
                                        option
                                            .substringAfter(
                                                "="
                                            )
                                            .trim()

                                    if (
                                        value.isNotEmpty()
                                    ) {
                                        referrer =
                                            value
                                    }
                                }
                            }
                        }

                        next.startsWith("#") ->
                            Unit

                        else -> {
                            streamUrl = next
                        }
                    }

                    if (streamUrl != null) {
                        break
                    }

                    nextIndex++
                }

                if (
                    !streamUrl.isNullOrBlank()
                ) {
                    channels.add(
                        Channel(
                            name = name,
                            url = streamUrl,
                            logoUrl = logo,
                            userAgent = userAgent,
                            referrer = referrer,
                            group = group,
                            tvgId = tvgId,
                            tvgName = tvgName
                        )
                    )

                    index = nextIndex
                }
            }

            index++
        }

        return Playlist(
            channels = channels,
            epgUrl =
                epgUrl
                    ?.trim()
                    ?.takeIf {
                        it.isNotEmpty()
                    }
        )
    }

    fun fetchAndParse(
        playlistUrl: String
    ): Playlist {
        return parse(
            fetchContent(
                playlistUrl
            )
        )
    }

    fun fetchContent(
        urlString: String
    ): String {
        var currentUrl =
            urlString

        var redirects = 0

        while (true) {
            val url =
                URL(currentUrl)

            val connection =
                url.openConnection()
                    as HttpURLConnection

            connection.connectTimeout =
                CONNECT_TIMEOUT_MS

            connection.readTimeout =
                READ_TIMEOUT_MS

            connection.instanceFollowRedirects =
                false

            connection.setRequestProperty(
                "User-Agent",
                USER_AGENT
            )

            connection.setRequestProperty(
                "Accept-Encoding",
                "gzip"
            )

            try {
                val code =
                    connection.responseCode

                if (
                    code in 300..399
                ) {
                    val location =
                        connection
                            .getHeaderField(
                                "Location"
                            )
                            ?: throw java.io.IOException(
                                "Redirección HTTP sin Location"
                            )

                    if (
                        redirects >=
                        MAX_REDIRECTS
                    ) {
                        throw java.io.IOException(
                            "Demasiadas redirecciones HTTP"
                        )
                    }

                    currentUrl =
                        URL(
                            url,
                            location
                        ).toString()

                    redirects++
                    continue
                }

                if (code !in 200..299) {
                    val errorText =
                        connection
                            .errorStream
                            ?.bufferedReader()
                            ?.use {
                                it
                                    .readText()
                                    .take(1000)
                            }
                            .orEmpty()

                    throw java.io.IOException(
                        "HTTP $code: $errorText"
                    )
                }

                val rawInput =
                    BufferedInputStream(
                        connection.inputStream
                    )

                val input:
                    InputStream =
                    if (
                        connection
                            .contentEncoding
                            ?.contains(
                                "gzip",
                                ignoreCase = true
                            ) == true ||
                        currentUrl
                            .substringBefore("?")
                            .endsWith(
                                ".gz",
                                ignoreCase = true
                            )
                    ) {
                        GZIPInputStream(
                            rawInput
                        )
                    } else {
                        rawInput
                    }

                return input.use {
                    BufferedReader(
                        InputStreamReader(
                            it,
                            Charsets.UTF_8
                        )
                    ).use { reader ->
                        reader.readText()
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun extractName(
        extinfLine: String
    ): String {
        val commaIndex =
            extinfLine.lastIndexOf(',')

        val raw =
            if (commaIndex >= 0) {
                extinfLine
                    .substring(
                        commaIndex + 1
                    )
                    .trim()
            } else {
                ""
            }

        return raw.ifBlank {
            extractAttribute(
                extinfLine,
                "tvg-name"
            )
                ?: "Canal sin nombre"
        }
    }

    private fun extractAttribute(
        line: String,
        attribute: String
    ): String? {
        val escapedAttribute =
            Regex.escape(attribute)

        val pattern =
            Regex(
                """$escapedAttribute\s*=\s*"([^"]*?)"""",
                RegexOption.IGNORE_CASE
            )

        return pattern
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf {
                it.isNotEmpty()
            }
    }
}

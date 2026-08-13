package com.connorb.atlasplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

object PlaylistStore {
    private const val FILE_NAME = "last_playlist.json"
    private const val MAX_CHANNELS = 20_000

    fun save(context: Context, playlist: Playlist) {
        require(playlist.channels.size <= MAX_CHANNELS)

        val root = JSONObject()
            .put("sourceUrl", playlist.sourceUrl)

        val channels = JSONArray()

        playlist.channels.forEach { channel ->
            val alternates = JSONArray()

            channel.alternates.forEach { alternate ->
                alternates.put(
                    JSONObject()
                        .put("id", alternate.id)
                        .put("name", alternate.name)
                        .put("url", alternate.url)
                        .put("logoUrl", alternate.logoUrl)
                        .put("userAgent", alternate.userAgent)
                        .put("referrer", alternate.referrer)
                        .put("tvgId", alternate.tvgId)
                        .put("tvgName", alternate.tvgName)
                )
            }

            channels.put(
                JSONObject()
                    .put("id", channel.id)
                    .put("position", channel.position)
                    .put("name", channel.name)
                    .put("url", channel.url)
                    .put("logoUrl", channel.logoUrl)
                    .put("userAgent", channel.userAgent)
                    .put("referrer", channel.referrer)
                    .put("group", channel.group)
                    .put("tvgId", channel.tvgId)
                    .put("tvgName", channel.tvgName)
                    .put("alternates", alternates)
            )
        }

        root.put("channels", channels)

        val target = File(context.filesDir, FILE_NAME)
        val temporary = File(context.filesDir, "$FILE_NAME.tmp")

        temporary.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(root.toString())
            writer.flush()
        }

        if (target.exists() && !target.delete()) {
            throw IOException("No se pudo reemplazar la playlist almacenada")
        }

        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            if (!temporary.delete()) {
                temporary.deleteOnExit()
            }
        }
    }

    fun load(context: Context): Playlist? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile || file.length() == 0L) return null

        return runCatching {
            val root = file.inputStream().bufferedReader(Charsets.UTF_8).use {
                JSONObject(it.readText())
            }

            val channelsJson = root.optJSONArray("channels") ?: return null
            if (channelsJson.length() !in 1..MAX_CHANNELS) return null

            val channels = buildList {
                for (index in 0 until channelsJson.length()) {
                    val item = channelsJson.getJSONObject(index)
                    val id = item.optString("id").trim()
                    val name = item.optString("name").trim()
                    val url = item.optString("url").trim()

                    if (id.isBlank() || name.isBlank() || url.isBlank()) continue
                    if (runCatching { NetworkPolicy.streamUrl(url) }.isFailure) continue

                    val alternates = buildList {
                        val alternatesJson = item.optJSONArray("alternates")

                        if (alternatesJson != null) {
                            for (alternateIndex in 0 until alternatesJson.length()) {
                                val alternate = alternatesJson.getJSONObject(alternateIndex)
                                val alternateId = alternate.optString("id").trim()
                                val alternateName = alternate.optString("name").trim()
                                val alternateUrl = alternate.optString("url").trim()

                                if (alternateId.isBlank() ||
                                    alternateName.isBlank() ||
                                    alternateUrl.isBlank()
                                ) {
                                    continue
                                }

                                if (runCatching { NetworkPolicy.streamUrl(alternateUrl) }.isFailure) {
                                    continue
                                }

                                add(
                                    ChannelAlternate(
                                        id = alternateId,
                                        name = alternateName,
                                        url = alternateUrl,
                                        logoUrl = alternate.optStringOrNull("logoUrl")
                                            ?.takeIf { runCatching { NetworkPolicy.logoUrlOrNull(it) }.isSuccess },
                                        userAgent = alternate.optStringOrNull("userAgent"),
                                        referrer = alternate.optStringOrNull("referrer")
                                            ?.takeIf { runCatching { NetworkPolicy.referrerOrNull(it) }.isSuccess },
                                        tvgId = alternate.optStringOrNull("tvgId"),
                                        tvgName = alternate.optStringOrNull("tvgName")
                                    )
                                )
                            }
                        }
                    }

                    add(
                        Channel(
                            id = id,
                            position = item.optInt("position", index + 1),
                            name = name,
                            url = url,
                            logoUrl = item.optStringOrNull("logoUrl")
                                ?.takeIf { runCatching { NetworkPolicy.logoUrlOrNull(it) }.isSuccess },
                            userAgent = item.optStringOrNull("userAgent"),
                            referrer = item.optStringOrNull("referrer")
                                ?.takeIf { runCatching { NetworkPolicy.referrerOrNull(it) }.isSuccess },
                            group = item.optStringOrNull("group"),
                            tvgId = item.optStringOrNull("tvgId"),
                            tvgName = item.optStringOrNull("tvgName"),
                            alternates = alternates
                        )
                    )
                }
            }

            if (channels.isEmpty()) return null

            Playlist(
                sourceUrl = root.optString("sourceUrl"),
                channels = channels
            )
        }.getOrNull()
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        return optString(key)
            .trim()
            .takeIf { it.isNotEmpty() && it != "null" }
    }
}
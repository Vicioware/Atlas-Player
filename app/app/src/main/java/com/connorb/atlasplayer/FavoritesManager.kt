package com.connorb.atlasplayer

import android.content.Context

object FavoritesManager {
    private const val PREFS = "omnitv_prefs"
    private const val KEY_FAVORITE_IDS = "favorite_channel_ids"
    private const val KEY_LAST_CHANNEL_ID = "last_channel_id"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getFavoriteIds(context: Context): Set<String> {
        return prefs(context)
            .getStringSet(KEY_FAVORITE_IDS, emptySet())
            ?.toSet()
            ?: emptySet()
    }

    fun isFavorite(context: Context, channelId: String): Boolean {
        return channelId in getFavoriteIds(context)
    }

    fun toggle(context: Context, channelId: String): Boolean {
        val ids = getFavoriteIds(context).toMutableSet()

        val nowFavorite = if (channelId in ids) {
            ids.remove(channelId)
            false
        } else {
            ids.add(channelId)
            true
        }

        prefs(context)
            .edit()
            .putStringSet(KEY_FAVORITE_IDS, ids)
            .apply()

        return nowFavorite
    }

    fun saveLastChannelId(context: Context, channelId: String) {
        prefs(context)
            .edit()
            .putString(KEY_LAST_CHANNEL_ID, channelId)
            .apply()
    }

    fun getLastChannelId(context: Context): String? {
        return prefs(context)
            .getString(KEY_LAST_CHANNEL_ID, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun removeMissingChannels(context: Context, validIds: Set<String>) {
        val favorites = getFavoriteIds(context)
        val validFavorites = favorites.intersect(validIds)

        if (favorites != validFavorites) {
            prefs(context)
                .edit()
                .putStringSet(KEY_FAVORITE_IDS, validFavorites)
                .apply()
        }
    }
}
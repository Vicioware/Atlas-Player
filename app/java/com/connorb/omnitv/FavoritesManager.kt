package com.connorb.omnitv

import android.content.Context

object FavoritesManager {

    private const val PREFS = "omnitv_prefs"
    private const val KEY_FAVORITES = "favorite_urls"
    private const val KEY_LAST_INDEX = "last_channel_index"

    private fun prefs(context: Context) =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getFavoriteUrls(context: Context): Set<String> =
        prefs(context)
            .getStringSet(KEY_FAVORITES, emptySet())
            ?.toSet()
            ?: emptySet()

    fun isFavorite(context: Context, url: String): Boolean =
        getFavoriteUrls(context).contains(url)

    /** Alterna el estado y devuelve true si el canal quedó marcado. */
    fun toggle(context: Context, url: String): Boolean {
        val current = getFavoriteUrls(context).toMutableSet()
        val nowFavorite = if (current.contains(url)) {
            current.remove(url); false
        } else {
            current.add(url); true
        }

        prefs(context).edit()
            .putStringSet(KEY_FAVORITES, current)
            .apply()

        return nowFavorite
    }

    fun saveLastIndex(context: Context, index: Int) {
        prefs(context).edit()
            .putInt(KEY_LAST_INDEX, index)
            .apply()
    }

    fun getLastIndex(context: Context): Int =
        prefs(context).getInt(KEY_LAST_INDEX, 0)
}

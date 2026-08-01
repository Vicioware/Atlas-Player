package com.connorb.omnitv

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val playlistUrl =
        "https://github.com/Vicioware/OmniTV/raw/refs/heads/main/master.m3u"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_boot)
        loadPlaylist(playlistUrl)
    }

    private fun loadPlaylist(url: String) {
        thread {
            try {
                val playlist = M3uParser.fetchAndParse(url)
                runOnUiThread {
                    if (playlist.channels.isNotEmpty()) {
                        launchPlayer(playlist)
                    } else {
                        Toast.makeText(
                            this,
                            "No se encontraron canales en la playlist",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Error al cargar playlist: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun launchPlayer(playlist: Playlist) {
        PlayerActivity.sharedChannels = playlist.channels
        PlayerActivity.sharedEpgUrl = playlist.epgUrl

        val lastIndex = FavoritesManager.getLastIndex(this)
            .coerceIn(0, playlist.channels.lastIndex)

        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_CHANNEL_INDEX, lastIndex)
        startActivity(intent)
        finish()
    }
}

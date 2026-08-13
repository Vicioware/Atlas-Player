package com.connorb.atlasplayer

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

        loadPlaylist()
    }

    private fun loadPlaylist() {
        thread(name = "playlist-loader") {
            val result = runCatching {
                M3uParser.fetchAndParse(playlistUrl)
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                result.onSuccess { playlist ->
                    if (playlist.channels.isEmpty()) {
                        showError("No se encontraron canales válidos en la playlist")
                        return@onSuccess
                    }

                    runCatching {
                        PlaylistStore.save(this, playlist)
                        FavoritesManager.removeMissingChannels(
                            this,
                            playlist.channels.mapTo(mutableSetOf()) { it.id }
                        )
                    }.onFailure {
                        showError("No se pudo guardar la playlist localmente")
                        return@onSuccess
                    }

                    startActivity(
                        Intent(this, PlayerActivity::class.java)
                    )
                    finish()
                }.onFailure {
                    val cached = PlaylistStore.load(this)

                    if (cached != null) {
                        Toast.makeText(
                            this,
                            "No se pudo actualizar la playlist; se usará la copia local",
                            Toast.LENGTH_LONG
                        ).show()

                        startActivity(Intent(this, PlayerActivity::class.java))
                        finish()
                    } else {
                        showError("No se pudo cargar la playlist. Revisa tu conexión.")
                    }
                }
            }
        }
    }

    private fun showError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
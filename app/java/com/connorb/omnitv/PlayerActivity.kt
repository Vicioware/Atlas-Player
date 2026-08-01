package com.connorb.omnitv

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_INDEX = "channel_index"
        private const val FADE_DURATION = 500L
        private const val OVERLAY_TIMEOUT = 3000L
        private const val LIVE_TARGET_OFFSET_MS = 8000L
        private const val DRAWER_ANIM_MS = 200L
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1500L
        private const val DRAWER_MARGIN_ITEMS = 3
        private const val CHANNEL_INPUT_TIMEOUT = 3000L

        var sharedChannels: List<Channel> = emptyList()
        var sharedEpgUrl: String? = null
    }

    private lateinit var playerView: PlayerView
    private lateinit var playerRoot: View
    private lateinit var loadingSpinner: CircularProgressIndicator
    private lateinit var tvChannelUnavailable: TextView
    private lateinit var ivPauseIcon: ImageView
    private lateinit var ivPlayIcon: ImageView
    private lateinit var tvChannelNameOverlay: TextView
    private lateinit var tvChannelNumberOsd: TextView

    private lateinit var sideDrawer: View
    private lateinit var rvDrawerChannels: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var tvFavoritesOption: TextView
    private lateinit var favoritesDrawer: View
    private lateinit var rvFavorites: RecyclerView
    private lateinit var tvFavoritesEmpty: TextView

    private lateinit var epgDrawer: View
    private lateinit var rvEpg: RecyclerView
    private lateinit var tvEpgEmpty: TextView
    private lateinit var epgLoadingContainer: View

    private var player: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val interpolator = DecelerateInterpolator()

    private var channels: List<Channel> = emptyList()
    private var filteredChannels: List<Channel> = emptyList()
    private var globalNumbers: Map<String, Int> = emptyMap()

    private var currentIndex = 0
    private var isDrawerOpen = false
    private var isEpgOpen = false
    private var isFavoritesOpen = false
    private var lastDrawerFocus = 0

    private lateinit var drawerAdapter: DrawerChannelAdapter
    private lateinit var favoritesAdapter: DrawerChannelAdapter

    private var didSeekToLive = false
    private var retryCount = 0

    private var epg: EpgData = EpgData.EMPTY
    private var epgLoaded = false
    private var epgLoading = false

    private val channelInputBuffer = StringBuilder()
    private val channelInputRunnable = Runnable { commitChannelInput() }

    private val hideChannelNameRunnable = Runnable { fadeOut(tvChannelNameOverlay) }
    private val hideOsdRunnable = Runnable { fadeOut(tvChannelNumberOsd) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        hideSystemUI()

        playerRoot = findViewById(R.id.playerRoot)
        playerView = findViewById(R.id.playerView)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        tvChannelUnavailable = findViewById(R.id.tvChannelUnavailable)
        ivPauseIcon = findViewById(R.id.ivPauseIcon)
        ivPlayIcon = findViewById(R.id.ivPlayIcon)
        tvChannelNameOverlay = findViewById(R.id.tvChannelNameOverlay)
        tvChannelNumberOsd = findViewById(R.id.tvChannelNumberOsd)
        sideDrawer = findViewById(R.id.sideDrawer)
        rvDrawerChannels = findViewById(R.id.rvDrawerChannels)
        etSearch = findViewById(R.id.etSearch)
        tvFavoritesOption = findViewById(R.id.tvFavoritesOption)
        favoritesDrawer = findViewById(R.id.favoritesDrawer)
        rvFavorites = findViewById(R.id.rvFavorites)
        tvFavoritesEmpty = findViewById(R.id.tvFavoritesEmpty)
        epgDrawer = findViewById(R.id.epgDrawer)
        rvEpg = findViewById(R.id.rvEpg)
        tvEpgEmpty = findViewById(R.id.tvEpgEmpty)
        epgLoadingContainer = findViewById(R.id.epgLoadingContainer)

        findViewById<TextView>(R.id.tvChannelsHeader).typeface = Fonts.regular(this)
        findViewById<TextView>(R.id.tvFavoritesHeader).typeface = Fonts.regular(this)
        findViewById<TextView>(R.id.tvEpgHeader).typeface = Fonts.regular(this)
        tvChannelUnavailable.typeface = Fonts.regular(this)
        tvChannelNameOverlay.typeface = Fonts.extraLight(this)
        tvChannelNumberOsd.typeface = Fonts.regular(this)
        tvEpgEmpty.typeface = Fonts.extraLight(this)
        tvFavoritesEmpty.typeface = Fonts.extraLight(this)
        tvFavoritesOption.typeface = Fonts.regular(this)
        findViewById<TextView>(R.id.tvEpgLoadingText).typeface = Fonts.extraLight(this)

        channels = sharedChannels
        if (channels.isEmpty()) { finish(); return }

        filteredChannels = channels
        globalNumbers = channels.mapIndexed { i, c -> c.url to (i + 1) }.toMap()

        currentIndex = intent.getIntExtra(EXTRA_CHANNEL_INDEX, 0)
            .coerceIn(0, channels.lastIndex)
        lastDrawerFocus = currentIndex
        playerView.useController = false

        setupDrawers()
        setupSearch()
        setupFavoritesOption()
        rvEpg.layoutManager = LinearLayoutManager(this)
        initPlayer()
        playerRoot.requestFocus()
        playChannel(currentIndex)
        preloadEpg()
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun initPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(20_000, 60_000, 3_000, 6_000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(20_000, true)
            .build()

        player = ExoPlayer.Builder(this).setLoadControl(loadControl).build()
        playerView.player = player

        player?.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> loadingSpinner.show()
                    Player.STATE_READY -> {
                        loadingSpinner.hide()
                        tvChannelUnavailable.visibility = View.GONE
                        retryCount = 0
                        val p = player ?: return
                        if (!didSeekToLive && p.isCurrentMediaItemLive) {
                            p.seekToDefaultPosition(); didSeekToLive = true
                        }
                    }
                    Player.STATE_ENDED, Player.STATE_IDLE -> loadingSpinner.hide()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    handler.postDelayed({
                        val p = player ?: return@postDelayed
                        p.seekToDefaultPosition(); p.prepare(); p.playWhenReady = true
                    }, RETRY_DELAY_MS)
                } else {
                    loadingSpinner.hide()
                    tvChannelUnavailable.visibility = View.VISIBLE
                }
            }
        })
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildMediaSource(channel: Channel): MediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(channel.userAgent?.ifBlank { null } ?: "OmniTV/1.0")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        if (!channel.referrer.isNullOrBlank())
            httpFactory.setDefaultRequestProperties(mapOf("Referer" to channel.referrer))

        val mediaItem = MediaItem.Builder()
            .setUri(channel.url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            ).build()

        return if (channel.url.contains(".m3u8", ignoreCase = true)) {
            HlsMediaSource.Factory(httpFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(httpFactory).createMediaSource(mediaItem)
        }
    }

    private fun playChannel(index: Int) {
        val channel = channels.getOrNull(index) ?: return
        currentIndex = index
        didSeekToLive = false
        retryCount = 0

        FavoritesManager.saveLastIndex(this, index)

        tvChannelUnavailable.visibility = View.GONE
        loadingSpinner.show()
        ivPauseIcon.visibility = View.GONE
        ivPlayIcon.visibility = View.GONE

        player?.apply {
            stop(); setMediaSource(buildMediaSource(channel)); prepare(); playWhenReady = true
        }

        showChannelNameOverlay(channel.name)
        showChannelNumberOsd(index + 1)
        if (::drawerAdapter.isInitialized) drawerAdapter.updateCurrentUrl(channel.url)
        if (::favoritesAdapter.isInitialized) favoritesAdapter.updateCurrentUrl(channel.url)
        if (isEpgOpen) refreshEpgForCurrentChannel()
    }

    /** Reproduce por objeto (usado desde ambos drawers). */
    private fun playChannelObject(
        channel: Channel
    ) {
        val index =
            channels.indexOfFirst {
                it.url == channel.url
            }

        if (index < 0) return

        /*
         * Si ya se reproduce, solo se cierra la interfaz.
         * No se toca ExoPlayer.
         */
        if (index == currentIndex) {
            closeAllDrawers()
            return
        }

        playChannel(index)
        closeAllDrawers()
    }

    private fun showChannelNameOverlay(name: String) {
        handler.removeCallbacks(hideChannelNameRunnable)
        tvChannelNameOverlay.text = name
        tvChannelNameOverlay.alpha = 1f
        tvChannelNameOverlay.visibility = View.VISIBLE
        handler.postDelayed(hideChannelNameRunnable, OVERLAY_TIMEOUT)
    }

    private fun showChannelNumberOsd(number: Int) =
        showChannelNumberOsdText(number.toString(), autoHide = true)

    private fun showChannelNumberOsdText(text: String, autoHide: Boolean) {
        handler.removeCallbacks(hideOsdRunnable)
        tvChannelNumberOsd.text = text
        tvChannelNumberOsd.alpha = 1f
        tvChannelNumberOsd.visibility = View.VISIBLE
        if (autoHide) handler.postDelayed(hideOsdRunnable, OVERLAY_TIMEOUT)
    }

    private fun switchChannel(delta: Int) {
        if (channels.isEmpty()) return
        playChannel((currentIndex + delta + channels.size) % channels.size)
    }

    // --- Entrada numérica ---

    private fun onNumberPressed(digit: Int) {
        handler.removeCallbacks(channelInputRunnable)
        if (channelInputBuffer.length >= 3) channelInputBuffer.setLength(0)
        channelInputBuffer.append(digit)
        showChannelNumberOsdText(channelInputBuffer.toString(), autoHide = false)

        // Con 3 dígitos se cambia al instante, sin esperar.
        if (channelInputBuffer.length == 3) {
            commitChannelInput()
        } else {
            handler.postDelayed(channelInputRunnable, CHANNEL_INPUT_TIMEOUT)
        }
    }

    private fun commitChannelInput() {
        handler.removeCallbacks(channelInputRunnable)
        val number = channelInputBuffer.toString().toIntOrNull()
        channelInputBuffer.setLength(0)
        if (number == null) return

        val index = number - 1

        // Canal inexistente o el mismo actual: no cambia nada.
        if (index !in channels.indices || index == currentIndex) {
            showChannelNumberOsd(currentIndex + 1)
            return
        }

        playChannel(index)
    }

    // --- Búsqueda ---

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterChannels(s?.toString().orEmpty())
            }
        })
    }

    private fun filterChannels(query: String) {
        val q = normalizeKey(query)
        filteredChannels = if (q.isEmpty()) {
            channels
        } else {
            channels.filter { normalizeKey(it.name).contains(q) }
        }
        drawerAdapter.updateChannels(filteredChannels)
    }

    // --- Favoritos ---

    private fun setupFavoritesOption() {
        tvFavoritesOption.setOnClickListener { openFavorites() }
        tvFavoritesOption.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_RIGHT -> { openFavorites(); true }
                else -> false
            }
        }
    }

    private fun favoriteChannels(): List<Channel> {
        val favs = FavoritesManager.getFavoriteUrls(this)
        return channels.filter { favs.contains(it.url) }
    }

    private fun toggleFavorite(channel: Channel): Boolean {
        val nowFav = FavoritesManager.toggle(this, channel.url)
        // Si estamos en el drawer de favoritos y se quitó, refresca la lista.
        if (isFavoritesOpen) {
            handler.postDelayed({ refreshFavoritesList() }, 220L)
        }
        return nowFav
    }

    private fun refreshFavoritesList() {
        val favs = favoriteChannels()
        favoritesAdapter.updateChannels(favs)
        tvFavoritesEmpty.visibility = if (favs.isEmpty()) View.VISIBLE else View.GONE
        rvFavorites.visibility = if (favs.isEmpty()) View.GONE else View.VISIBLE
    }

    // --- Drawers ---

    private fun setupDrawers() {
        drawerAdapter = DrawerChannelAdapter(
            channels = filteredChannels,
            currentUrl = channels.getOrNull(currentIndex)?.url,
            isFavorite = { FavoritesManager.isFavorite(this, it.url) },
            onFocusIndex = { index -> keepDrawerMargin(index) },
            onChannelClick = { channel -> playChannelObject(channel); closeDrawer() },
            onToggleFavorite = { channel -> toggleFavorite(channel) }
        )
        drawerAdapter.setGlobalNumbers(globalNumbers)
        rvDrawerChannels.layoutManager = LinearLayoutManager(this)
        rvDrawerChannels.adapter = drawerAdapter

        favoritesAdapter = DrawerChannelAdapter(
            channels = favoriteChannels(),
            currentUrl = channels.getOrNull(currentIndex)?.url,
            isFavorite = { FavoritesManager.isFavorite(this, it.url) },
            onFocusIndex = { },
            onChannelClick = { channel -> playChannelObject(channel); closeAllDrawers() },
            onToggleFavorite = { channel -> toggleFavorite(channel) }
        )
        favoritesAdapter.setGlobalNumbers(globalNumbers)
        rvFavorites.layoutManager = LinearLayoutManager(this)
        rvFavorites.adapter = favoritesAdapter
    }

    private fun keepDrawerMargin(index: Int) {
        val target = if (index >= lastDrawerFocus)
            (index + DRAWER_MARGIN_ITEMS).coerceAtMost(filteredChannels.lastIndex)
        else
            (index - DRAWER_MARGIN_ITEMS).coerceAtLeast(0)
        lastDrawerFocus = index
        rvDrawerChannels.smoothScrollToPosition(target.coerceAtLeast(0))
    }

    private fun openDrawer() {
        if (isEpgOpen) {
            closeEpg(returnFocus = false)
        }

        isDrawerOpen = true

        sideDrawer.animate().cancel()

        sideDrawer.post {
            val width =
                sideDrawer.width.toFloat()
                    .takeIf { it > 0f }
                    ?: resources
                        .getDimensionPixelSize(
                            R.dimen.drawer_width
                        )
                        .toFloat()

            positionChannelListBeforeOpening()

            /*
             * Obliga a RecyclerView a aplicar el scroll antes de que
             * el drawer pase a visible.
             */
            rvDrawerChannels.post {
                sideDrawer.translationX = -width
                sideDrawer.alpha = 1f
                sideDrawer.visibility = View.VISIBLE

                sideDrawer.animate()
                    .translationX(0f)
                    .setDuration(DRAWER_ANIM_MS)
                    .setInterpolator(interpolator)
                    .withEndAction {
                        val currentUrl =
                            channels.getOrNull(
                                currentIndex
                            )?.url

                        val position =
                            filteredChannels
                                .indexOfFirst {
                                    it.url == currentUrl
                                }
                                .coerceAtLeast(0)

                        rvDrawerChannels
                            .findViewHolderForAdapterPosition(
                                position
                            )
                            ?.itemView
                            ?.requestFocus()
                    }
                    .start()
            }
        }
    }

    private fun closeDrawer(
        returnFocus: Boolean = true
    ) {
        isDrawerOpen = false

        if (isFavoritesOpen) {
            closeFavorites()
        }

        val width =
            sideDrawer.width.toFloat()
                .takeIf { it > 0f }
                ?: resources
                    .getDimensionPixelSize(
                        R.dimen.drawer_width
                    )
                    .toFloat()

        sideDrawer.animate()
            .translationX(-width)
            .setDuration(DRAWER_ANIM_MS)
            .setInterpolator(interpolator)
            .withEndAction {
                sideDrawer.visibility =
                    View.INVISIBLE
            }
            .start()

        if (returnFocus) {
            playerRoot.requestFocus()
        }
    }

    private fun openFavorites() {
        if (isFavoritesOpen) return

        isFavoritesOpen = true
        refreshFavoritesList()
        favoritesDrawer.animate().cancel()

        favoritesDrawer.post {
            val width =
                favoritesDrawer.width.toFloat()
                    .takeIf { it > 0f }
                    ?: resources
                        .getDimensionPixelSize(
                            R.dimen.drawer_width
                        )
                        .toFloat()

            favoritesDrawer.translationX =
                -width

            favoritesDrawer.alpha = 0f
            favoritesDrawer.visibility =
                View.VISIBLE

            favoritesDrawer.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(DRAWER_ANIM_MS)
                .setInterpolator(interpolator)
                .withEndAction {
                    rvFavorites.post {
                        rvFavorites
                            .findViewHolderForAdapterPosition(
                                0
                            )
                            ?.itemView
                            ?.requestFocus()
                    }
                }
                .start()
        }
    }

    private fun closeFavorites() {
        if (!isFavoritesOpen) return

        isFavoritesOpen = false
        favoritesDrawer.animate().cancel()

        val width =
            favoritesDrawer.width.toFloat()
                .takeIf { it > 0f }
                ?: resources
                    .getDimensionPixelSize(
                        R.dimen.drawer_width
                    )
                    .toFloat()

        favoritesDrawer.animate()
            .translationX(-width)
            .alpha(0f)
            .setDuration(150L)
            .setInterpolator(interpolator)
            .withEndAction {
                favoritesDrawer.visibility =
                    View.INVISIBLE

                favoritesDrawer.alpha = 1f
                tvFavoritesOption.requestFocus()
            }
            .start()
    }

    private fun closeAllDrawers() {
        closeFavorites()
        closeDrawer()
    }

    private fun openEpg() {
        if (isDrawerOpen) {
            closeDrawer(returnFocus = false)
        }

        if (isEpgOpen) return

        isEpgOpen = true
        epgDrawer.animate().cancel()

        /*
         * Prepara el estado del panel antes de hacerlo visible.
         */
        when {
            epgLoaded ->
                refreshEpgForCurrentChannel()

            epgLoading ->
                showEpgLoading()

            else -> {
                showEpgLoading()
                preloadEpg()
            }
        }

        epgDrawer.post {
            val width =
                epgDrawer.width.toFloat()
                    .takeIf { it > 0f }
                    ?: resources
                        .getDimensionPixelSize(
                            R.dimen.drawer_width
                        )
                        .toFloat()

            epgDrawer.translationX = width
            epgDrawer.alpha = 1f
            epgDrawer.visibility = View.INVISIBLE

            /*
             * Espera un frame para que RecyclerView mida el adaptador
             * y el panel no haga trabajo pesado durante la animación.
             */
            epgDrawer.post {
                epgDrawer.visibility = View.VISIBLE

                epgDrawer.animate()
                    .translationX(0f)
                    .setDuration(240L)
                    .setInterpolator(interpolator)
                    .start()
            }
        }
    }

    private fun closeEpg(
        returnFocus: Boolean = true
    ) {
        isEpgOpen = false

        val width =
            epgDrawer.width.toFloat()
                .takeIf { it > 0f }
                ?: resources
                    .getDimensionPixelSize(
                        R.dimen.drawer_width
                    )
                    .toFloat()

        epgDrawer.animate()
            .translationX(width)
            .setDuration(DRAWER_ANIM_MS)
            .setInterpolator(interpolator)
            .withEndAction {
                epgDrawer.visibility =
                    View.INVISIBLE
            }
            .start()

        if (returnFocus) {
            playerRoot.requestFocus()
        }
    }

    // --- EPG (sin cambios) ---

    private fun preloadEpg() {
        val url = sharedEpgUrl?.trim()?.takeIf { it.isNotEmpty() }
            ?: run { epgLoaded = true; return }
        if (epgLoaded || epgLoading) return
        epgLoading = true
        if (isEpgOpen) showEpgLoading()

        EpgRepository.load(context = this, url = url) { result ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                epgLoading = false
                result.onSuccess { parsed ->
                    epg = parsed; epgLoaded = true
                    if (isEpgOpen) refreshEpgForCurrentChannel()
                }.onFailure {
                    epgLoaded = false
                    if (isEpgOpen) showEmptyEpg()
                }
            }
        }
    }

    private fun resolveChannelEpg(channel: Channel): List<EpgProgram> {
        val rawId = channel.tvgId?.trim()?.takeIf { it.isNotEmpty() }
        if (rawId != null) {
            val idCandidates = linkedSetOf(rawId, rawId.lowercase(Locale.ROOT), rawId.uppercase(Locale.ROOT))
            for (candidate in idCandidates) {
                val programs = epg.programsById[candidate]
                if (!programs.isNullOrEmpty()) return programs
            }
        }
        val nameCandidates = listOfNotNull(channel.tvgName, channel.name)
            .map(::normalizeKey).filter { it.isNotEmpty() }.distinct()
        for (name in nameCandidates) {
            val mappedId = epg.nameToId[name] ?: continue
            val programs = epg.programsById[mappedId]
            if (!programs.isNullOrEmpty()) return programs
        }
        return emptyList()
    }

    private fun refreshEpgForCurrentChannel() {
        if (epgLoading && !epgLoaded) { showEpgLoading(); return }
        if (!epgLoaded) { showEmptyEpg(); preloadEpg(); return }

        val channel = channels.getOrNull(currentIndex) ?: return
        val now = System.currentTimeMillis()
        val programs = resolveChannelEpg(channel)
            .asSequence()
            .filter { it.stopMillis == 0L || it.stopMillis >= now }
            .sortedBy { it.startMillis }.toList()

        if (programs.isEmpty()) showEmptyEpg()
        else {
            epgLoadingContainer.visibility = View.GONE
            tvEpgEmpty.visibility = View.GONE
            rvEpg.visibility = View.VISIBLE
            rvEpg.adapter = EpgAdapter(programs)
        }
    }

    private fun showEpgLoading() {
        epgLoadingContainer.visibility = View.VISIBLE
        tvEpgEmpty.visibility = View.GONE
        rvEpg.visibility = View.GONE
        rvEpg.adapter = null
    }

    private fun showEmptyEpg() {
        epgLoadingContainer.visibility = View.GONE
        tvEpgEmpty.visibility = View.VISIBLE
        rvEpg.visibility = View.GONE
        rvEpg.adapter = null
    }

    private fun fadeOut(view: View) {
        val anim = AlphaAnimation(view.alpha, 0f)
        anim.duration = FADE_DURATION
        anim.fillAfter = true
        anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(a: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
            override fun onAnimationEnd(a: android.view.animation.Animation?) {
                view.visibility = View.GONE; view.alpha = 1f; view.clearAnimation()
            }
        })
        view.startAnimation(anim)
    }

    private fun positionChannelListBeforeOpening() {
        val currentUrl =
            channels.getOrNull(currentIndex)?.url

        val position =
            filteredChannels.indexOfFirst {
                it.url == currentUrl
            }

        val layoutManager =
            rvDrawerChannels.layoutManager
                    as? LinearLayoutManager
                ?: return

        if (position >= 0) {
            /*
             * El offset intenta situar el canal actual en el centro.
             * Se ejecuta antes de hacer visible el drawer, por lo que
             * no se observa ningún reacomodo.
             */
            val itemHeight =
                resources.getDimensionPixelSize(
                    R.dimen.drawer_item_height
                )

            val centerOffset =
                (rvDrawerChannels.height -
                        itemHeight) / 2

            layoutManager.scrollToPositionWithOffset(
                position,
                centerOffset.coerceAtLeast(0)
            )
        } else {
            layoutManager.scrollToPositionWithOffset(
                0,
                0
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Los dígitos solo cambian de canal cuando ningún drawer está abierto.
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 &&
            !isDrawerOpen && !isEpgOpen && !isFavoritesOpen
        ) {
            onNumberPressed(keyCode - KeyEvent.KEYCODE_0)
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                isFavoritesOpen -> { closeFavorites(); true }
                isEpgOpen -> { closeEpg(); true }
                isDrawerOpen -> { closeDrawer(); true }
                else -> { openDrawer(); true }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                isFavoritesOpen -> {
                    /*
                     * Dentro de Favoritos, DERECHA no cierra el panel.
                     * La navegación interna sigue su curso.
                     */
                    super.onKeyDown(keyCode, event)
                }

                isDrawerOpen -> {
                    if (tvFavoritesOption.hasFocus()) {
                        openFavorites()
                    } else {
                        closeDrawer()
                    }
                    true
                }

                isEpgOpen -> {
                    closeEpg()
                    true
                }

                else -> {
                    openEpg()
                    true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP ->
                if (isDrawerOpen || isEpgOpen || isFavoritesOpen)
                    super.onKeyDown(keyCode, event)
                else { switchChannel(-1); true }
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (isDrawerOpen || isEpgOpen || isFavoritesOpen)
                    super.onKeyDown(keyCode, event)
                else { switchChannel(1); true }
            KeyEvent.KEYCODE_BACK -> when {
                isFavoritesOpen -> { closeFavorites(); true }
                isDrawerOpen -> { closeDrawer(); true }
                isEpgOpen -> { closeEpg(); true }
                else -> super.onKeyDown(keyCode, event)
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() { super.onPause(); player?.pause() }

    override fun onResume() {
        super.onResume(); hideSystemUI()
        player?.let { if (it.isCurrentMediaItemLive) it.seekToDefaultPosition(); it.play() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release(); player = null
        super.onDestroy()
    }
}

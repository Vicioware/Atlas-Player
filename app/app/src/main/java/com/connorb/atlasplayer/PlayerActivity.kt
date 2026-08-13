package com.connorb.atlasplayer

import android.content.Intent
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
import androidx.media3.common.util.UnstableApi
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
        private const val FADE_DURATION = 500L
        private const val OVERLAY_TIMEOUT = 3_000L
        private const val LIVE_TARGET_OFFSET_MS = 8_000L
        private const val DRAWER_ANIM_MS = 180L
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1_500L
        private const val DRAWER_MARGIN_ITEMS = 3
        private const val CHANNEL_INPUT_TIMEOUT = 3_000L
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

    private lateinit var optionsDrawer: View
    private lateinit var rvOptions: RecyclerView
    private lateinit var tvOptionsEmpty: TextView

    private var player: ExoPlayer? = null

    private val handler = Handler(Looper.getMainLooper())
    private val interpolator = DecelerateInterpolator()

    private var channels: List<Channel> = emptyList()
    private var filteredChannels: List<Channel> = emptyList()
    private var currentChannelId: String? = null

    private var isDrawerOpen = false
    private var isOptionsOpen = false
    private var isFavoritesOpen = false
    private var lastDrawerFocus = 0
    private var activeAlternateId: String? = null

    private lateinit var drawerAdapter: DrawerChannelAdapter
    private lateinit var favoritesAdapter: DrawerChannelAdapter
    private var optionsAdapter: ChannelOptionsAdapter? = null

    private var didSeekToLive = false
    private var retryCount = 0
    private var playbackGeneration = 0L
    private var retryRunnable: Runnable? = null
    private var resumePlaybackOnForeground = false
    private var userPausedPlayback = false

    private val channelInputBuffer = StringBuilder()

    private val channelInputRunnable = Runnable {
        commitChannelInput()
    }

    private val hideChannelNameRunnable = Runnable {
        fadeOut(tvChannelNameOverlay)
    }

    private val hideOsdRunnable = Runnable {
        fadeOut(tvChannelNumberOsd)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_player)
        hideSystemUI()
        bindViews()
        applyFonts()

        val playlist = PlaylistStore.load(this)

        if (playlist == null || playlist.channels.isEmpty()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        channels = playlist.channels
        filteredChannels = channels
        FavoritesManager.removeMissingChannels(
            this,
            channels.mapTo(mutableSetOf()) { it.id }
        )

        val storedChannelId = FavoritesManager.getLastChannelId(this)

        currentChannelId = channels
            .firstOrNull { it.id == storedChannelId }
            ?.id
            ?: channels.first().id

        lastDrawerFocus = indexOfCurrentChannel().coerceAtLeast(0)

        playerView.useController = false

        setupDrawers()
        setupSearch()
        setupFavoritesOption()

        rvOptions.layoutManager = LinearLayoutManager(this)

        initPlayer()

        playerRoot.requestFocus()
        currentChannelId?.let(::playChannel)
    }

    private fun bindViews() {
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

        optionsDrawer = findViewById(R.id.optionsDrawer)
        rvOptions = findViewById(R.id.rvOptions)
        tvOptionsEmpty = findViewById(R.id.tvOptionsEmpty)
    }

    private fun applyFonts() {
        findViewById<TextView>(R.id.tvChannelsHeader).typeface = Fonts.regular(this)
        findViewById<TextView>(R.id.tvFavoritesHeader).typeface = Fonts.regular(this)
        findViewById<TextView>(R.id.tvOptionsHeader).typeface = Fonts.regular(this)

        tvChannelUnavailable.typeface = Fonts.regular(this)
        tvChannelNameOverlay.typeface = Fonts.extraLight(this)
        tvChannelNumberOsd.typeface = Fonts.regular(this)
        tvFavoritesEmpty.typeface = Fonts.extraLight(this)
        tvFavoritesOption.typeface = Fonts.regular(this)
        tvOptionsEmpty.typeface = Fonts.extraLight(this)
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val controller = WindowInsetsControllerCompat(
            window,
            window.decorView
        )

        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun indexOfCurrentChannel(): Int {
        return channels.indexOfFirst { it.id == currentChannelId }
    }

    private fun currentChannel(): Channel? {
        return channels.firstOrNull { it.id == currentChannelId }
    }

    private fun channelById(channelId: String): Channel? {
        return channels.firstOrNull { it.id == channelId }
    }

    private fun cancelPendingRetry() {
        retryRunnable?.let(handler::removeCallbacks)
        retryRunnable = null
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                20_000,
                60_000,
                3_000,
                6_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(20_000, true)
            .build()

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build()

        playerView.player = player

        player?.addListener(object : Player.Listener {

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        loadingSpinner.show()
                    }

                    Player.STATE_READY -> {
                        loadingSpinner.hide()
                        tvChannelUnavailable.visibility = View.GONE
                        retryCount = 0

                        val activePlayer = player ?: return

                        if (
                            !didSeekToLive &&
                            activePlayer.isCurrentMediaItemLive
                        ) {
                            activePlayer.seekToDefaultPosition()
                            didSeekToLive = true
                        }
                    }

                    Player.STATE_ENDED,
                    Player.STATE_IDLE -> {
                        loadingSpinner.hide()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                loadingSpinner.hide()

                if (
                    userPausedPlayback ||
                    isFinishing ||
                    isDestroyed
                ) {
                    return
                }

                if (retryCount >= MAX_RETRIES) {
                    tvChannelUnavailable.visibility = View.VISIBLE
                    return
                }

                retryCount += 1

                val expectedGeneration = playbackGeneration

                cancelPendingRetry()

                retryRunnable = Runnable {
                    if (
                        isFinishing ||
                        isDestroyed ||
                        userPausedPlayback ||
                        expectedGeneration != playbackGeneration
                    ) {
                        return@Runnable
                    }

                    player?.apply {
                        prepare()
                        playWhenReady = true
                    }
                }

                handler.postDelayed(
                    retryRunnable!!,
                    RETRY_DELAY_MS
                )
            }
        })
    }

    @OptIn(UnstableApi::class)
    private fun buildMediaSource(
        url: String,
        userAgent: String?,
        referrer: String?
    ): MediaSource {
        val streamUrl = NetworkPolicy.streamUrl(url)

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(
                userAgent
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.take(512)
                    ?: "Atlas Player/1.0"
            )
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)

        referrer
            ?.let(NetworkPolicy::referrerOrNull)
            ?.let { referrer ->
                dataSourceFactory.setDefaultRequestProperties(
                    mapOf("Referer" to referrer)
                )
            }

        val mediaItem = MediaItem.Builder()
            .setUri(streamUrl.toExternalForm())
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setMinPlaybackSpeed(0.97f)
                    .setMaxPlaybackSpeed(1.03f)
                    .build()
            )
            .build()

        val path = streamUrl.path.lowercase(Locale.ROOT)

        return if (path.endsWith(".m3u8")) {
            HlsMediaSource.Factory(dataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)
        } else {
            DefaultMediaSourceFactory(dataSourceFactory)
                .createMediaSource(mediaItem)
        }
    }

    private fun playChannel(channelId: String) {
        val channel = channelById(channelId) ?: return

        playbackGeneration += 1L
        cancelPendingRetry()

        currentChannelId = channel.id
        retryCount = 0
        didSeekToLive = false
        userPausedPlayback = false
        resumePlaybackOnForeground = true
        activeAlternateId = null

        FavoritesManager.saveLastChannelId(this, channel.id)

        tvChannelUnavailable.visibility = View.GONE
        loadingSpinner.show()

        ivPauseIcon.visibility = View.GONE
        ivPlayIcon.visibility = View.GONE

        runCatching {
            buildMediaSource(
                url = channel.url,
                userAgent = channel.userAgent,
                referrer = channel.referrer
            )
        }.onSuccess { mediaSource ->
            player?.apply {
                stop()
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
        }.onFailure {
            loadingSpinner.hide()
            tvChannelUnavailable.visibility = View.VISIBLE
        }

        showChannelNameOverlay(channel.name)
        showChannelNumberOsd(channel.position)

        if (::drawerAdapter.isInitialized) {
            drawerAdapter.updateCurrentChannelId(channel.id)
        }

        if (::favoritesAdapter.isInitialized) {
            favoritesAdapter.updateCurrentChannelId(channel.id)
        }

        if (isOptionsOpen) {
            refreshOptionsForCurrentChannel()
        }
    }

    private fun playAlternate(alternate: ChannelAlternate) {
        val channel = currentChannel() ?: return

        playbackGeneration += 1L
        cancelPendingRetry()

        retryCount = 0
        didSeekToLive = false
        userPausedPlayback = false
        resumePlaybackOnForeground = true
        activeAlternateId = alternate.id

        tvChannelUnavailable.visibility = View.GONE
        loadingSpinner.show()

        ivPauseIcon.visibility = View.GONE
        ivPlayIcon.visibility = View.GONE

        runCatching {
            buildMediaSource(
                url = alternate.url,
                userAgent = alternate.userAgent,
                referrer = alternate.referrer
            )
        }.onSuccess { mediaSource ->
            player?.apply {
                stop()
                setMediaSource(mediaSource)
                prepare()
                playWhenReady = true
            }
        }.onFailure {
            loadingSpinner.hide()
            tvChannelUnavailable.visibility = View.VISIBLE
        }

        showChannelNameOverlay(alternate.name)
        showChannelNumberOsd(channel.position)

        if (isOptionsOpen) {
            refreshOptionsForCurrentChannel()
        }
    }

    private fun showChannelNameOverlay(name: String) {
        handler.removeCallbacks(hideChannelNameRunnable)

        tvChannelNameOverlay.text = name
        tvChannelNameOverlay.alpha = 1f
        tvChannelNameOverlay.visibility = View.VISIBLE

        handler.postDelayed(
            hideChannelNameRunnable,
            OVERLAY_TIMEOUT
        )
    }

    private fun showChannelNumberOsd(number: Int) {
        showChannelNumberOsdText(
            text = number.toString(),
            autoHide = true
        )
    }

    private fun showChannelNumberOsdText(
        text: String,
        autoHide: Boolean
    ) {
        handler.removeCallbacks(hideOsdRunnable)

        tvChannelNumberOsd.text = text
        tvChannelNumberOsd.alpha = 1f
        tvChannelNumberOsd.visibility = View.VISIBLE

        if (autoHide) {
            handler.postDelayed(
                hideOsdRunnable,
                OVERLAY_TIMEOUT
            )
        }
    }

    private fun switchChannel(delta: Int) {
        if (channels.isEmpty()) return

        val currentIndex = indexOfCurrentChannel()
            .takeIf { it >= 0 }
            ?: 0

        val targetIndex = Math.floorMod(
            currentIndex + delta,
            channels.size
        )

        playChannel(channels[targetIndex].id)
    }

    private fun onNumberPressed(digit: Int) {
        handler.removeCallbacks(channelInputRunnable)

        if (channelInputBuffer.length >= 3) {
            channelInputBuffer.setLength(0)
        }

        channelInputBuffer.append(digit)

        showChannelNumberOsdText(
            text = channelInputBuffer.toString(),
            autoHide = false
        )

        if (channelInputBuffer.length >= 3) {
            commitChannelInput()
        } else {
            handler.postDelayed(
                channelInputRunnable,
                CHANNEL_INPUT_TIMEOUT
            )
        }
    }

    private fun commitChannelInput() {
        handler.removeCallbacks(channelInputRunnable)

        val selectedNumber = channelInputBuffer
            .toString()
            .toIntOrNull()

        channelInputBuffer.setLength(0)

        val selectedChannel = channels.firstOrNull {
            it.position == selectedNumber
        }

        if (selectedChannel == null) {
            currentChannel()?.let { showChannelNumberOsd(it.position) }
            return
        }

        if (selectedChannel.id != currentChannelId) {
            playChannel(selectedChannel.id)
        } else {
            showChannelNumberOsd(selectedChannel.position)
        }
    }

    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) = Unit

            override fun onTextChanged(
                s: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(s: Editable?) {
                filterChannels(s?.toString().orEmpty())
            }
        })
    }

    private fun filterChannels(query: String) {
        val normalizedQuery = normalizeKey(query)

        filteredChannels = if (normalizedQuery.isBlank()) {
            channels
        } else {
            channels.filter { channel ->
                channel.normalizedName.contains(normalizedQuery)
            }
        }

        drawerAdapter.updateChannels(filteredChannels)
    }

    private fun setupFavoritesOption() {
        tvFavoritesOption.setOnClickListener {
            openFavorites()
        }

        tvFavoritesOption.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) {
                return@setOnKeyListener false
            }

            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    openFavorites()
                    true
                }

                else -> false
            }
        }
    }

    private fun favoriteChannels(): List<Channel> {
        val favoriteIds = FavoritesManager.getFavoriteIds(this)

        return channels.filter { channel ->
            channel.id in favoriteIds
        }
    }

    private fun toggleFavorite(channel: Channel): Boolean {
        val nowFavorite = FavoritesManager.toggle(
            context = this,
            channelId = channel.id
        )

        if (isFavoritesOpen) {
            refreshFavoritesList()
        }

        return nowFavorite
    }

    private fun refreshFavoritesList() {
        val favorites = favoriteChannels()

        favoritesAdapter.updateChannels(favorites)

        tvFavoritesEmpty.visibility = if (favorites.isEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }

        rvFavorites.visibility = if (favorites.isEmpty()) {
            View.GONE
        } else {
            View.VISIBLE
        }
    }

    private fun setupDrawers() {
        drawerAdapter = DrawerChannelAdapter(
            channels = filteredChannels,
            currentChannelId = currentChannelId,
            isFavorite = { channel ->
                FavoritesManager.isFavorite(this, channel.id)
            },
            onFocusChannel = { channel ->
                val masterIndex = channels.indexOfFirst {
                    it.id == channel.id
                }

                if (masterIndex >= 0) {
                    keepDrawerMargin(masterIndex)
                }
            },
            onChannelClick = { channel ->
                playChannel(channel.id)
                closeDrawer()
            },
            onToggleFavorite = { channel ->
                toggleFavorite(channel)
            }
        )

        rvDrawerChannels.layoutManager = LinearLayoutManager(this)
        rvDrawerChannels.setHasFixedSize(true)
        rvDrawerChannels.itemAnimator = null
        rvDrawerChannels.adapter = drawerAdapter

        favoritesAdapter = DrawerChannelAdapter(
            channels = favoriteChannels(),
            currentChannelId = currentChannelId,
            isFavorite = { channel ->
                FavoritesManager.isFavorite(this, channel.id)
            },
            onFocusChannel = {},
            onChannelClick = { channel ->
                playChannel(channel.id)
                closeAllDrawers()
            },
            onToggleFavorite = { channel ->
                toggleFavorite(channel)
            }
        )

        rvFavorites.layoutManager = LinearLayoutManager(this)
        rvFavorites.setHasFixedSize(true)
        rvFavorites.itemAnimator = null
        rvFavorites.adapter = favoritesAdapter

        rvOptions.setHasFixedSize(true)
        rvOptions.itemAnimator = null
    }

    private fun keepDrawerMargin(masterIndex: Int) {
        if (filteredChannels.isEmpty()) return

        val focusedChannel = channels.getOrNull(masterIndex) ?: return

        val filteredIndex = filteredChannels.indexOfFirst {
            it.id == focusedChannel.id
        }

        if (filteredIndex < 0) return

        val target = if (filteredIndex > lastDrawerFocus) {
            (filteredIndex + DRAWER_MARGIN_ITEMS)
                .coerceAtMost(filteredChannels.lastIndex)
        } else {
            (filteredIndex - DRAWER_MARGIN_ITEMS)
                .coerceAtLeast(0)
        }

        lastDrawerFocus = filteredIndex
        rvDrawerChannels.smoothScrollToPosition(target)
    }

    private fun openDrawer() {
        if (isDrawerOpen) return

        if (isOptionsOpen) {
            closeOptions(returnFocus = false)
        }

        isDrawerOpen = true

        sideDrawer.animate().cancel()

        sideDrawer.post {
            positionChannelListBeforeOpening()

            val width = sideDrawer.width
                .takeIf { it > 0 }
                ?.toFloat()
                ?: resources.getDimensionPixelSize(
                    R.dimen.drawer_width
                ).toFloat()

            sideDrawer.translationX = -width
            sideDrawer.alpha = 1f
            sideDrawer.visibility = View.VISIBLE

            sideDrawer.animate()
                .translationX(0f)
                .setDuration(DRAWER_ANIM_MS)
                .withLayer()
                .setInterpolator(interpolator)
                .withEndAction {
                    val position = filteredChannels
                        .indexOfFirst { it.id == currentChannelId }
                        .coerceAtLeast(0)

                    rvDrawerChannels.post {
                        rvDrawerChannels
                            .findViewHolderForAdapterPosition(position)
                            ?.itemView
                            ?.requestFocus()
                    }
                }
                .start()
        }
    }

    private fun closeDrawer(returnFocus: Boolean = true) {
        if (!isDrawerOpen) return

        isDrawerOpen = false

        if (isFavoritesOpen) {
            closeFavorites()
        }

        val width = sideDrawer.width
            .takeIf { it > 0 }
            ?.toFloat()
            ?: resources.getDimensionPixelSize(
                R.dimen.drawer_width
            ).toFloat()

        sideDrawer.animate().cancel()

        sideDrawer.animate()
            .translationX(-width)
            .setDuration(DRAWER_ANIM_MS)
            .withLayer()
            .setInterpolator(interpolator)
            .withEndAction {
                sideDrawer.visibility = View.INVISIBLE
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
            val width = favoritesDrawer.width
                .takeIf { it > 0 }
                ?.toFloat()
                ?: resources.getDimensionPixelSize(
                    R.dimen.drawer_width
                ).toFloat()

            favoritesDrawer.translationX = -width
            favoritesDrawer.alpha = 0f
            favoritesDrawer.visibility = View.VISIBLE

            favoritesDrawer.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(DRAWER_ANIM_MS)
                .setInterpolator(interpolator)
                .withEndAction {
                    if (favoriteChannels().isNotEmpty()) {
                        rvFavorites.post {
                            rvFavorites
                                .findViewHolderForAdapterPosition(0)
                                ?.itemView
                                ?.requestFocus()
                        }
                    } else {
                        tvFavoritesOption.requestFocus()
                    }
                }
                .start()
        }
    }

    private fun closeFavorites() {
        if (!isFavoritesOpen) return

        isFavoritesOpen = false

        val width = favoritesDrawer.width
            .takeIf { it > 0 }
            ?.toFloat()
            ?: resources.getDimensionPixelSize(
                R.dimen.drawer_width
            ).toFloat()

        favoritesDrawer.animate().cancel()

        favoritesDrawer.animate()
            .translationX(-width)
            .alpha(0f)
            .setDuration(150L)
            .setInterpolator(interpolator)
            .withEndAction {
                favoritesDrawer.visibility = View.INVISIBLE
                favoritesDrawer.alpha = 1f
            }
            .start()

        tvFavoritesOption.requestFocus()
    }

    private fun closeAllDrawers() {
        closeFavorites()
        closeDrawer()
        closeOptions()
    }

    private fun openOptions() {
        if (isOptionsOpen) return

        if (isDrawerOpen) {
            closeDrawer(returnFocus = false)
        }

        isOptionsOpen = true
        refreshOptionsForCurrentChannel()

        optionsDrawer.animate().cancel()

        optionsDrawer.post {
            val width = optionsDrawer.width
                .takeIf { it > 0 }
                ?.toFloat()
                ?: resources.getDimensionPixelSize(
                    R.dimen.drawer_width
                ).toFloat()

            optionsDrawer.translationX = width
            optionsDrawer.alpha = 1f
            optionsDrawer.visibility = View.VISIBLE

            optionsDrawer.animate()
                .translationX(0f)
                .setDuration(DRAWER_ANIM_MS)
                .withLayer()
                .setInterpolator(interpolator)
                .withEndAction {
                    if (currentChannel()?.alternates?.isNotEmpty() == true) {
                        rvOptions.post {
                            rvOptions
                                .findViewHolderForAdapterPosition(0)
                                ?.itemView
                                ?.requestFocus()
                        }
                    }
                }
                .start()
        }
    }

    private fun closeOptions(returnFocus: Boolean = true) {
        if (!isOptionsOpen) return

        isOptionsOpen = false

        val width = optionsDrawer.width
            .takeIf { it > 0 }
            ?.toFloat()
            ?: resources.getDimensionPixelSize(
                R.dimen.drawer_width
            ).toFloat()

        optionsDrawer.animate().cancel()

        optionsDrawer.animate()
            .translationX(width)
            .setDuration(DRAWER_ANIM_MS)
            .withLayer()
            .setInterpolator(interpolator)
            .withEndAction {
                optionsDrawer.visibility = View.INVISIBLE
            }
            .start()

        if (returnFocus) {
            playerRoot.requestFocus()
        }
    }

    private fun refreshOptionsForCurrentChannel() {
        val options = currentChannel()?.alternates.orEmpty()

        val adapter = optionsAdapter

        if (adapter == null) {
            optionsAdapter = ChannelOptionsAdapter(
                options = options,
                activeId = activeAlternateId,
                onOptionClick = { alternate ->
                    playAlternate(alternate)
                }
            ).also {
                rvOptions.adapter = it
            }
        } else {
            adapter.updateOptions(options, activeAlternateId)
        }

        val hasOptions = options.isNotEmpty()

        tvOptionsEmpty.visibility = if (hasOptions) View.GONE else View.VISIBLE
        rvOptions.visibility = if (hasOptions) View.VISIBLE else View.GONE
    }

    private fun positionChannelListBeforeOpening() {
        val layoutManager = rvDrawerChannels.layoutManager as? LinearLayoutManager
            ?: return

        val position = filteredChannels.indexOfFirst {
            it.id == currentChannelId
        }

        if (position >= 0) {
            val itemHeight = resources.getDimensionPixelSize(
                R.dimen.drawer_item_height
            )

            val centerOffset = (
                    rvDrawerChannels.height - itemHeight
                    ) / 2

            layoutManager.scrollToPositionWithOffset(
                position,
                centerOffset.coerceAtLeast(0)
            )
        } else {
            layoutManager.scrollToPositionWithOffset(0, 0)
        }
    }

    private fun pausePlaybackByUser() {
        userPausedPlayback = true
        resumePlaybackOnForeground = false

        player?.pause()

        ivPauseIcon.visibility = View.VISIBLE
        ivPlayIcon.visibility = View.GONE
    }

    private fun resumePlaybackByUser() {
        userPausedPlayback = false
        resumePlaybackOnForeground = true

        player?.play()

        ivPauseIcon.visibility = View.GONE
        ivPlayIcon.visibility = View.VISIBLE
    }

    private fun fadeOut(view: View) {
        val animation = AlphaAnimation(view.alpha, 0f).apply {
            duration = FADE_DURATION
            fillAfter = true

            setAnimationListener(
                object : android.view.animation.Animation.AnimationListener {
                    override fun onAnimationStart(
                        animation: android.view.animation.Animation?
                    ) = Unit

                    override fun onAnimationRepeat(
                        animation: android.view.animation.Animation?
                    ) = Unit

                    override fun onAnimationEnd(
                        animation: android.view.animation.Animation?
                    ) {
                        view.visibility = View.GONE
                        view.alpha = 1f
                        view.clearAnimation()
                    }
                }
            )
        }

        view.startAnimation(animation)
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent?
    ): Boolean {
        if (
            keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 &&
            !isDrawerOpen &&
            !isOptionsOpen &&
            !isFavoritesOpen
        ) {
            onNumberPressed(keyCode - KeyEvent.KEYCODE_0)
            return true
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                when {
                    isFavoritesOpen -> {
                        closeFavorites()
                        true
                    }

                    isOptionsOpen -> {
                        closeOptions()
                        true
                    }

                    isDrawerOpen -> {
                        if (etSearch.hasFocus()) {
                            closeDrawer()
                        } else {
                            etSearch.requestFocus()
                        }

                        true
                    }

                    else -> {
                        openDrawer()
                        true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                when {
                    isFavoritesOpen -> {
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

                    isOptionsOpen -> {
                        closeOptions()
                        true
                    }

                    else -> {
                        openOptions()
                        true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isDrawerOpen || isOptionsOpen || isFavoritesOpen) {
                    super.onKeyDown(keyCode, event)
                } else {
                    switchChannel(-1)
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isDrawerOpen || isOptionsOpen || isFavoritesOpen) {
                    super.onKeyDown(keyCode, event)
                } else {
                    switchChannel(1)
                    true
                }
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (
                    !isDrawerOpen &&
                    !isOptionsOpen &&
                    !isFavoritesOpen
                ) {
                    val activePlayer = player

                    if (activePlayer?.isPlaying == true) {
                        pausePlaybackByUser()
                    } else {
                        resumePlaybackByUser()
                    }

                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }

            KeyEvent.KEYCODE_BACK -> {
                when {
                    isFavoritesOpen -> {
                        closeFavorites()
                        true
                    }

                    isDrawerOpen -> {
                        closeDrawer()
                        true
                    }

                    isOptionsOpen -> {
                        closeOptions()
                        true
                    }

                    else -> super.onKeyDown(keyCode, event)
                }
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onPause() {
        resumePlaybackOnForeground = player?.isPlaying == true
        player?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()

        if (resumePlaybackOnForeground && !userPausedPlayback) {
            player?.play()
        }
    }

    override fun onDestroy() {
        playbackGeneration += 1L
        cancelPendingRetry()
        handler.removeCallbacksAndMessages(null)

        playerView.player = null
        player?.release()
        player = null

        super.onDestroy()
    }
}
package com.connorb.atlasplayer

import android.graphics.Typeface
import android.graphics.drawable.PictureDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class DrawerChannelAdapter(
    private var channels: List<Channel>,
    private var currentChannelId: String?,
    private val isFavorite: (Channel) -> Boolean,
    private val onFocusChannel: (Channel) -> Unit,
    private val onChannelClick: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Boolean
) : RecyclerView.Adapter<DrawerChannelAdapter.ViewHolder>() {

    companion object {
        private const val LONG_PRESS_MS = 2_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    init {
        setHasStableIds(true)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val tvNumber: TextView = view.findViewById(R.id.tvChannelNumber)
        val tvName: TextView = view.findViewById(R.id.tvChannelName)
        val ivLogo: ImageView = view.findViewById(R.id.ivDrawerLogo)
        val ivStar: ImageView = view.findViewById(R.id.ivFavStar)

        var pendingLongPress: Runnable? = null
        var pressedChannelId: String? = null
        var longPressFired = false
    }

    override fun getItemId(position: Int): Long {
        return channels[position].id.hashCode().toLong()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_drawer_channel, parent, false)

        return ViewHolder(view).apply {
            tvNumber.typeface = Fonts.regular(parent.context)
            tvName.typeface = Fonts.extraLight(parent.context)
        }
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        cancelPendingLongPress(holder)

        val channel = channels[position]
        val channelId = channel.id

        holder.tvNumber.text = channel.position.toString()
        holder.tvName.text = channel.name

        holder.tvName.setTypeface(
            Fonts.extraLight(holder.tvName.context),
            if (channel.id == currentChannelId) {
                Typeface.BOLD
            } else {
                Typeface.NORMAL
            }
        )

        holder.ivStar.visibility = if (isFavorite(channel)) {
            View.VISIBLE
        } else {
            View.GONE
        }

        loadLogo(holder.ivLogo, channel.logoUrl)

        // En Kotlin, View.hasFocus() es una función.
        applyBackground(
            holder = holder,
            channel = channel,
            hasFocus = holder.root.hasFocus()
        )

        holder.root.setOnFocusChangeListener { _, hasFocus ->
            applyBackground(
                holder = holder,
                channel = channel,
                hasFocus = hasFocus
            )

            if (
                hasFocus &&
                holder.bindingAdapterPosition != RecyclerView.NO_POSITION
            ) {
                onFocusChannel(channel)
            }
        }

        holder.root.setOnKeyListener { _, keyCode, event ->
            val isConfirm = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER

            if (!isConfirm) {
                return@setOnKeyListener false
            }

            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        holder.longPressFired = false
                        holder.pressedChannelId = channelId

                        val longPress = Runnable {
                            val stillBoundToSameChannel =
                                holder.pressedChannelId == channelId

                            val stillValid =
                                holder.bindingAdapterPosition !=
                                        RecyclerView.NO_POSITION

                            if (stillBoundToSameChannel && stillValid) {
                                holder.longPressFired = true

                                val nowFavorite = onToggleFavorite(channel)

                                updateFavoriteVisual(
                                    holder = holder,
                                    favorite = nowFavorite
                                )
                            }
                        }

                        holder.pendingLongPress = longPress

                        handler.postDelayed(
                            longPress,
                            LONG_PRESS_MS
                        )
                    }

                    true
                }

                KeyEvent.ACTION_UP -> {
                    val pressedMatches =
                        holder.pressedChannelId == channelId

                    val wasLongPress = holder.longPressFired

                    cancelPendingLongPress(holder)
                    holder.pressedChannelId = null

                    if (pressedMatches && !wasLongPress) {
                        onChannelClick(channel)
                    }

                    true
                }

                else -> {
                    // KeyEvent no expone ACTION_CANCEL; cancelar en reciclado
                    // y en ACTION_UP es suficiente para este control de TV.
                    true
                }
            }
        }

        holder.root.setOnClickListener {
            if (!holder.longPressFired) {
                onChannelClick(channel)
            }
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        cancelPendingLongPress(holder)

        holder.pressedChannelId = null
        holder.longPressFired = false

        holder.root.setOnClickListener(null)
        holder.root.setOnKeyListener(null)
        holder.root.onFocusChangeListener = null

        Glide.with(holder.ivLogo).clear(holder.ivLogo)

        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int {
        return channels.size
    }

    fun updateChannels(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    fun updateCurrentChannelId(channelId: String?) {
        if (currentChannelId == channelId) return

        val previousPosition = channels.indexOfFirst { it.id == currentChannelId }
        val newPosition = channels.indexOfFirst { it.id == channelId }
        currentChannelId = channelId

        if (previousPosition >= 0) notifyItemChanged(previousPosition)
        if (newPosition >= 0 && newPosition != previousPosition) notifyItemChanged(newPosition)
    }

    private fun cancelPendingLongPress(holder: ViewHolder) {
        holder.pendingLongPress?.let { runnable ->
            handler.removeCallbacks(runnable)
        }

        holder.pendingLongPress = null
    }

    private fun updateFavoriteVisual(
        holder: ViewHolder,
        favorite: Boolean
    ) {
        if (favorite) {
            holder.ivStar.visibility = View.VISIBLE
            holder.ivStar.alpha = 0f
            holder.ivStar.scaleX = 1.4f
            holder.ivStar.scaleY = 1.4f

            holder.ivStar.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(250L)
                .setInterpolator(OvershootInterpolator())
                .start()
        } else {
            holder.ivStar.animate()
                .alpha(0f)
                .setDuration(150L)
                .withEndAction {
                    holder.ivStar.visibility = View.GONE
                    holder.ivStar.alpha = 1f
                    holder.ivStar.scaleX = 1f
                    holder.ivStar.scaleY = 1f
                }
                .start()
        }
    }

    private fun applyBackground(
        holder: ViewHolder,
        channel: Channel,
        hasFocus: Boolean
    ) {
        holder.root.isActivated = channel.id == currentChannelId
        holder.root.isSelected = hasFocus
        holder.root.refreshDrawableState()

        when {
            hasFocus -> {
                holder.root.setBackgroundResource(
                    R.color.focused_channel_bg
                )
            }

            channel.id == currentChannelId -> {
                holder.root.setBackgroundResource(
                    R.color.current_channel_bg
                )
            }

            else -> {
                holder.root.setBackgroundColor(0x00000000)
            }
        }
    }

    private fun loadLogo(
        imageView: ImageView,
        rawUrl: String?
    ) {
        val url = runCatching {
            NetworkPolicy.logoUrlOrNull(rawUrl)?.toExternalForm()
        }.getOrNull()

        if (url == null) {
            Glide.with(imageView).clear(imageView)
            imageView.setImageResource(R.drawable.ic_channel_placeholder)
            return
        }

        val uri = Uri.parse(url)

        val isSvg = uri.path
            .orEmpty()
            .substringBefore('?')
            .endsWith(".svg", ignoreCase = true)

        if (isSvg) {
            Glide.with(imageView)
                .`as`(PictureDrawable::class.java)
                .load(uri)
                .listener(SvgSoftwareLayerSetter())
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(imageView)
        } else {
            Glide.with(imageView)
                .load(url)
                .fitCenter()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(imageView)
        }
    }
}
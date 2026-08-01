package com.connorb.omnitv

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
    private var currentUrl: String?,
    private val isFavorite: (Channel) -> Boolean,
    private val onFocusIndex: (Int) -> Unit,
    private val onChannelClick: (Channel) -> Unit,
    private val onToggleFavorite: (Channel) -> Boolean
) : RecyclerView.Adapter<DrawerChannelAdapter.ViewHolder>() {

    companion object {
        private const val LONG_PRESS_MS = 2000L
    }

    private val handler = Handler(Looper.getMainLooper())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val tvNumber: TextView = view.findViewById(R.id.tvChannelNumber)
        val tvName: TextView = view.findViewById(R.id.tvChannelName)
        val ivLogo: ImageView = view.findViewById(R.id.ivDrawerLogo)
        val ivStar: ImageView = view.findViewById(R.id.ivFavStar)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_drawer_channel, parent, false)
        val vh = ViewHolder(view)
        vh.tvNumber.typeface = Fonts.regular(parent.context)
        vh.tvName.typeface = Fonts.extraLight(parent.context)
        return vh
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]

        // Número sin punto a la derecha.
        holder.tvNumber.text = (channelGlobalNumber(channel)).toString()

        holder.tvName.text = channel.name
        holder.tvName.setTypeface(
            Fonts.extraLight(holder.tvName.context),
            if (channel.url == currentUrl) Typeface.BOLD else Typeface.NORMAL
        )

        loadLogo(holder.ivLogo, channel.logoUrl)

        holder.ivStar.visibility =
            if (isFavorite(channel)) View.VISIBLE else View.GONE

        applyBackground(holder, channel, holder.root.hasFocus())

        holder.root.setOnFocusChangeListener { _, hasFocus ->
            applyBackground(holder, channel, hasFocus)
            val pos = holder.bindingAdapterPosition
            if (hasFocus && pos != RecyclerView.NO_POSITION) onFocusIndex(pos)
        }

        var longPressFired = false
        var keyDownAt = 0L

        val longPressRunnable = Runnable {
            if (
                holder.bindingAdapterPosition !=
                RecyclerView.NO_POSITION
            ) {
                longPressFired = true

                val nowFavorite =
                    onToggleFavorite(channel)

                if (nowFavorite) {
                    showStarWithGlow(holder.ivStar)
                } else {
                    hideStarWithFade(holder.ivStar)
                }
            }
        }

        holder.root.setOnKeyListener { _, keyCode, event ->
            val isCenter =
                keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                        keyCode == KeyEvent.KEYCODE_ENTER

            if (!isCenter) {
                return@setOnKeyListener false
            }

            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        longPressFired = false
                        keyDownAt =
                            android.os.SystemClock.uptimeMillis()

                        handler.removeCallbacks(
                            longPressRunnable
                        )

                        handler.postDelayed(
                            longPressRunnable,
                            LONG_PRESS_MS
                        )
                    }

                    true
                }

                KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(
                        longPressRunnable
                    )

                    val elapsed =
                        android.os.SystemClock.uptimeMillis() -
                                keyDownAt

                    /*
                     * Algunos controles no mantienen el evento ACTION_DOWN
                     * de forma fiable. Si ACTION_UP llega después de 2 s,
                     * se ejecuta aquí el toggle si todavía no ocurrió.
                     */
                    if (
                        elapsed >= LONG_PRESS_MS &&
                        !longPressFired
                    ) {
                        longPressFired = true

                        val nowFavorite =
                            onToggleFavorite(channel)

                        if (nowFavorite) {
                            showStarWithGlow(holder.ivStar)
                        } else {
                            hideStarWithFade(holder.ivStar)
                        }
                    } else if (!longPressFired) {
                        onChannelClick(channel)
                    }

                    true
                }

                else -> {
                    handler.removeCallbacks(
                        longPressRunnable
                    )
                    true
                }
            }
        }

        holder.root.setOnClickListener {
            if (!longPressFired) onChannelClick(channel)
        }
    }

    private fun channelGlobalNumber(channel: Channel): Int =
        globalNumbers[channel.url] ?: (channels.indexOf(channel) + 1)

    // Mapa opcional para mostrar el número real del canal en la lista maestra.
    private var globalNumbers: Map<String, Int> = emptyMap()

    fun setGlobalNumbers(numbers: Map<String, Int>) {
        globalNumbers = numbers
        notifyDataSetChanged()
    }

    private fun showStarWithGlow(star: ImageView) {
        star.visibility = View.VISIBLE
        star.alpha = 0f
        star.scaleX = 1.9f
        star.scaleY = 1.9f
        star.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(380L)
            .setInterpolator(OvershootInterpolator())
            .start()
    }

    private fun hideStarWithFade(star: ImageView) {
        star.animate()
            .alpha(0f)
            .setDuration(180L)
            .withEndAction {
                star.visibility = View.GONE
                star.alpha = 1f
                star.scaleX = 1f
                star.scaleY = 1f
            }.start()
    }

    private fun loadLogo(imageView: ImageView, url: String?) {
        val clean = url?.trim()?.takeIf { it.isNotEmpty() }
        if (clean == null) {
            Glide.with(imageView).clear(imageView)
            imageView.setImageResource(R.drawable.ic_channel_placeholder)
            return
        }

        val isSvg = Uri.parse(clean).path.orEmpty()
            .endsWith(".svg", ignoreCase = true)

        if (isSvg) {
            Glide.with(imageView)
                .`as`(PictureDrawable::class.java)
                .load(Uri.parse(clean))
                .listener(SvgSoftwareLayerSetter())
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(imageView)
        } else {
            Glide.with(imageView)
                .load(clean)
                .fitCenter()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .placeholder(R.drawable.ic_channel_placeholder)
                .error(R.drawable.ic_channel_placeholder)
                .into(imageView)
        }
    }

    private fun applyBackground(
        holder: ViewHolder,
        channel: Channel,
        hasFocus: Boolean
    ) {
        holder.root.isActivated =
            channel.url == currentUrl

        holder.root.isSelected = hasFocus
        holder.root.refreshDrawableState()

        when {
            hasFocus ->
                holder.root.setBackgroundResource(
                    R.color.focused_channel_bg
                )

            channel.url == currentUrl ->
                holder.root.setBackgroundResource(
                    R.color.current_channel_bg
                )

            else ->
                holder.root.setBackgroundColor(
                    0x00000000
                )
        }
    }

    override fun getItemCount(): Int = channels.size

    fun updateChannels(newChannels: List<Channel>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    fun updateCurrentUrl(url: String?) {
        currentUrl = url
        notifyDataSetChanged()
    }
}

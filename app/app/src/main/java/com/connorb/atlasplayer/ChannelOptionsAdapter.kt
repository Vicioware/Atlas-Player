package com.connorb.atlasplayer

import android.graphics.drawable.PictureDrawable
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class ChannelOptionsAdapter(
    private var options: List<ChannelAlternate>,
    private var activeId: String?,
    private val onOptionClick: (ChannelAlternate) -> Unit
) : RecyclerView.Adapter<ChannelOptionsAdapter.ViewHolder>() {

    init {
        setHasStableIds(true)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val ivLogo: ImageView = view.findViewById(R.id.ivOptionLogo)
        val tvName: TextView = view.findViewById(R.id.tvOptionName)
        val tvLabel: TextView = view.findViewById(R.id.tvOptionLabel)
    }

    override fun getItemId(position: Int): Long {
        return options[position].id.hashCode().toLong()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_option, parent, false)

        return ViewHolder(view).apply {
            tvName.typeface = Fonts.extraLight(parent.context)
            tvLabel.typeface = Fonts.regular(parent.context)
        }
    }

    override fun onBindViewHolder(
        holder: ViewHolder,
        position: Int
    ) {
        val option = options[position]
        val isActive = option.id == activeId

        holder.tvName.text = option.name
        holder.tvLabel.text = holder.tvLabel.context.getString(
            R.string.channel_option_label,
            position + 2
        )

        loadLogo(holder.ivLogo, option.logoUrl)

        applyBackground(holder, isActive, holder.root.hasFocus())

        holder.root.setOnFocusChangeListener { _, hasFocus ->
            applyBackground(holder, isActive, hasFocus)
        }

        holder.root.setOnClickListener {
            onOptionClick(option)
        }
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.root.setOnClickListener(null)
        holder.root.onFocusChangeListener = null

        Glide.with(holder.ivLogo).clear(holder.ivLogo)

        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int {
        return options.size
    }

    fun updateOptions(
        newOptions: List<ChannelAlternate>,
        newActiveId: String?
    ) {
        options = newOptions
        activeId = newActiveId
        notifyDataSetChanged()
    }

    private fun applyBackground(
        holder: ViewHolder,
        isActive: Boolean,
        hasFocus: Boolean
    ) {
        holder.root.isActivated = isActive
        holder.root.isSelected = hasFocus
        holder.root.refreshDrawableState()

        holder.root.setBackgroundResource(
            when {
                hasFocus -> R.color.focused_channel_bg
                isActive -> R.color.current_channel_bg
                else -> android.R.color.transparent
            }
        )
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

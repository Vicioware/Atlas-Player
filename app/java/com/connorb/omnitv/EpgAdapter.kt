package com.connorb.omnitv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EpgAdapter(
    private val programs: List<EpgProgram>
) : RecyclerView.Adapter<EpgAdapter.ViewHolder>() {

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val now = System.currentTimeMillis()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTime: TextView = view.findViewById(R.id.tvProgTime)
        val tvTitle: TextView = view.findViewById(R.id.tvProgTitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_epg_program, parent, false)

        val vh = ViewHolder(view)
        vh.tvTime.typeface = Fonts.extraLight(parent.context)
        vh.tvTitle.typeface = Fonts.regular(parent.context)
        return vh

        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val p = programs[position]
        val start = if (p.startMillis > 0) timeFmt.format(Date(p.startMillis)) else "--:--"
        val stop = if (p.stopMillis > 0) timeFmt.format(Date(p.stopMillis)) else "--:--"
        holder.tvTime.text = "$start – $stop"
        holder.tvTitle.text = p.title

        val isLive = now in p.startMillis..p.stopMillis
        holder.tvTitle.alpha = if (isLive || p.startMillis > now) 1f else 0.5f
        holder.itemView.setBackgroundResource(
            if (isLive) R.color.current_channel_bg else android.R.color.transparent
        )
    }

    override fun getItemCount(): Int = programs.size
}

package com.example.superplayer.player

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.superplayer.R
import com.example.superplayer.model.Stream

/**
 * Carrusel horizontal de emisoras (logo + nombre) de la pantalla de radio:
 * acceso rápido a las demás emisoras de la lista actual. La emisora que suena
 * se marca con un contorno naranja.
 */
class RadioCarouselAdapter(
    private val streams: List<Stream>,
    private val currentIdProvider: () -> String?,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<RadioCarouselAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val logo: ImageView = v.findViewById(R.id.radioItemLogo)
        val name: TextView = v.findViewById(R.id.radioItemName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_radio_carousel, parent, false))

    override fun getItemCount() = streams.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val s = streams[position]
        holder.name.text = s.name
        holder.logo.load(s.icon) {
            placeholder(R.drawable.ic_radio_small)
            error(R.drawable.ic_radio_small)
        }
        holder.itemView.alpha = if (s.id == currentIdProvider()) 1f else 0.7f
        holder.itemView.isSelected = s.id == currentIdProvider()
        holder.itemView.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onPick(pos)
        }
    }
}

package com.example.superplayer.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.databinding.ItemStreamBinding
import com.example.superplayer.model.Stream

class StreamAdapter(
    private var items: List<Stream>,
    private val isFavorite: (Stream) -> Boolean,
    private val onClick: (Stream) -> Unit,
    private val onToggleFavorite: (Stream) -> Unit
) : RecyclerView.Adapter<StreamAdapter.ViewHolder>() {

    fun submit(newItems: List<Stream>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStreamBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemStreamBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(stream: Stream) {
            binding.streamName.text = stream.name
            binding.streamIcon.load(stream.icon) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }
            binding.favoriteIcon.setImageResource(
                if (isFavorite(stream)) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            )

            val context = binding.root.context
            val schedule = EpgRepository.schedule(stream.tvgId)

            bindEpgLine(binding.streamNowPlaying, R.string.epg_now_playing, schedule.now, context)
            bindEpgLine(binding.streamNextPlaying, R.string.epg_next_playing, schedule.next, context)
            bindEpgLine(binding.streamTonightPlaying, R.string.epg_tonight_playing, schedule.tonight, context)

            binding.root.setOnClickListener { onClick(stream) }
            binding.favoriteIcon.setOnClickListener { onToggleFavorite(stream) }
        }
    }
}

/** Pinta una línea de EPG ("Ahora"/"Después"/"Esta noche") o la esconde si `entry` es null. */
private fun bindEpgLine(view: TextView, formatRes: Int, entry: EpgRepository.EpgEntry?, context: Context) {
    if (entry != null) {
        view.text = context.getString(formatRes, EpgRepository.formatRange(entry), entry.title)
        view.visibility = View.VISIBLE
    } else {
        view.visibility = View.GONE
    }
}

package com.example.superplayer.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.data.TmdbRepository
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
            bindIconAndMovieInfo(stream)
            binding.favoriteIcon.setImageResource(
                if (isFavorite(stream)) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            )

            val context = binding.root.context
            val schedule = EpgRepository.schedule(stream.tvgId)

            bindEpgLine(binding.streamNowPlaying, R.string.epg_now_playing, schedule.now, context)
            bindEpgLine(binding.streamNextPlaying, R.string.epg_next_playing, schedule.next, context)
            bindEpgLine(binding.streamTonightPlaying, R.string.epg_tonight_playing, schedule.tonight, context)

            binding.root.setOnClickListener { onClick(stream) }
            // Alternativa para el mando de TV al corazón de favorito (ver el
            // comentario de streamRow/favoriteIcon en item_stream.xml):
            // mantener pulsado el OK/centro sobre la fila entera, sin tener
            // que llegar nunca al corazón. No interfiere con el toque normal
            // en móvil: solo se dispara con una pulsación larga de verdad, la
            // pulsación corta de siempre sigue abriendo el canal.
            binding.root.setOnLongClickListener {
                onToggleFavorite(stream)
                true
            }
            binding.favoriteIcon.setOnClickListener { onToggleFavorite(stream) }
        }

        /**
         * Icono normal del canal, o -si es una película que TMDb conoce- su
         * póster, año, nota y sinopsis. Si aún no se ha consultado, se
         * pide en segundo plano y esta misma fila se vuelve a pintar al
         * llegar (solo si sigue mostrando esa película: las filas se
         * reciclan al hacer scroll).
         */
        private fun bindIconAndMovieInfo(stream: Stream) {
            val context = binding.root.context
            val dp = context.resources.displayMetrics.density
            val info = if (TmdbRepository.isMovieCandidate(stream)) {
                TmdbRepository.cached(context, stream).also {
                    if (it == null && !TmdbRepository.isResolved(context, stream)) {
                        TmdbRepository.request(context, stream) {
                            val pos = bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION && items.getOrNull(pos)?.id == stream.id) {
                                notifyItemChanged(pos)
                            }
                        }
                    }
                }
            } else null

            val icon = binding.streamIcon
            val posterUrl = info?.posterUrl
            // Las filas se reciclan: tamaño y escala se fijan siempre, no solo para películas.
            val lp = icon.layoutParams
            lp.width = ((if (posterUrl != null) 60 else 44) * dp).toInt()
            lp.height = ((if (posterUrl != null) 90 else 44) * dp).toInt()
            icon.layoutParams = lp
            icon.scaleType = if (posterUrl != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.CENTER_INSIDE
            icon.setPadding(if (posterUrl != null) 0 else (6 * dp).toInt(), if (posterUrl != null) 0 else (6 * dp).toInt(),
                if (posterUrl != null) 0 else (6 * dp).toInt(), if (posterUrl != null) 0 else (6 * dp).toInt())
            icon.load(posterUrl ?: stream.icon) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }

            if (info != null) {
                val parts = mutableListOf<String>()
                info.year?.let { parts.add(it) }
                info.rating?.let { parts.add("★ " + String.format(java.util.Locale.US, "%.1f", it)) }
                binding.streamMovieMeta.text = parts.joinToString(" · ")
                binding.streamMovieMeta.visibility = if (parts.isEmpty()) View.GONE else View.VISIBLE
                binding.streamOverview.text = info.overview.orEmpty()
                binding.streamOverview.visibility = if (info.overview.isNullOrBlank()) View.GONE else View.VISIBLE
            } else {
                binding.streamMovieMeta.visibility = View.GONE
                binding.streamOverview.visibility = View.GONE
            }
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

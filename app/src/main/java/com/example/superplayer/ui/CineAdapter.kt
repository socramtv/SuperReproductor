package com.example.superplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.MovieInfo
import com.example.superplayer.databinding.ItemCineBinding
import com.example.superplayer.model.Stream

/**
 * Parrilla de carátulas de Cine (ver CineActivity). La carátula sale de
 * MovieInfo (iTunes/TVMaze) cuando ya se conoce; mientras tanto -o si no se
 * encuentra- se usa el logo que traiga la propia lista. Al pintar una
 * película cuya info no se conoce, se pide en segundo plano y se repinta esa
 * celda cuando llega.
 */
class CineAdapter(
    private var items: List<Stream>,
    private val posterHeightPx: () -> Int,
    private val onClick: (Stream) -> Unit
) : RecyclerView.Adapter<CineAdapter.ViewHolder>() {

    fun submit(newItems: List<Stream>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class ViewHolder(private val binding: ItemCineBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(stream: Stream) {
            val context = binding.root.context
            val params = binding.cinePoster.layoutParams
            val height = posterHeightPx()
            if (height > 0 && params.height != height) {
                params.height = height
                binding.cinePoster.layoutParams = params
            }
            val info = MovieInfo.peek(context, stream.name)
            binding.cineTitle.text = info?.title?.takeIf { it.isNotBlank() } ?: MovieInfo.cleanTitle(stream.name).first
            binding.cinePoster.load(info?.posterUrl ?: stream.icon) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }
            binding.root.setOnClickListener { onClick(stream) }

            if (info == null && !MovieInfo.isKnownMissing(context, stream.name)) {
                val streamId = stream.id
                MovieInfo.request(context, stream.name) { found ->
                    if (found == null) return@request
                    binding.root.post {
                        val pos = bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION && items.getOrNull(pos)?.id == streamId) {
                            notifyItemChanged(pos)
                        }
                    }
                }
            }
        }
    }
}

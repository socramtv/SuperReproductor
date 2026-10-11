package com.example.superplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superplayer.databinding.ItemCineRowBinding
import com.example.superplayer.model.Stream

/**
 * Pantalla principal de Cine al estilo Netflix / Prime Video: una fila por
 * categoría (título + carátulas en horizontal). Cada fila usa un CineAdapter.
 */
class CineRowsAdapter(
    private var rows: List<CineActivity.Row>,
    private val posterHeightPx: () -> Int,
    private val itemWidthPx: () -> Int,
    private val onClick: (Stream) -> Unit
) : RecyclerView.Adapter<CineRowsAdapter.ViewHolder>() {

    private val pool = RecyclerView.RecycledViewPool()

    fun submit(newRows: List<CineActivity.Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCineRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(rows[position])

    override fun getItemCount() = rows.size

    inner class ViewHolder(private val binding: ItemCineRowBinding) : RecyclerView.ViewHolder(binding.root) {
        private val inner = CineAdapter(emptyList(), posterHeightPx, itemWidthPx, onClick)

        init {
            binding.cineRowList.layoutManager =
                LinearLayoutManager(binding.root.context, LinearLayoutManager.HORIZONTAL, false)
            binding.cineRowList.adapter = inner
            binding.cineRowList.setRecycledViewPool(pool)
        }

        fun bind(row: CineActivity.Row) {
            binding.cineRowTitle.text = row.title
            inner.submit(row.streams)
        }
    }
}

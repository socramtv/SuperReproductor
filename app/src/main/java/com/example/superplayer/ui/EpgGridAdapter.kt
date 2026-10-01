package com.example.superplayer.ui

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.superplayer.R
import com.example.superplayer.databinding.ItemEpgGridRowBinding
import com.example.superplayer.model.Stream

/**
 * Una fila por canal. Las celdas de programas de cada fila se generan a
 * mano (addView) en cada bind, a partir de la franja horaria actual
 * ([windowProvider]) y EpgGridMath.buildCells/xForTime: el ancho en píxeles
 * de cada celda es la diferencia entre la posición de su inicio y la de su
 * fin, calculadas con la misma fórmula en todas las filas, así que todas
 * miden lo mismo de ancho en total y quedan alineadas entre sí sin importar
 * cuántos programas tenga cada una.
 *
 * El HorizontalScrollView de cada fila (rowScroll) se registra/retira para
 * el scroll horizontal compartido (ver registerSyncedScroll en
 * EpgGridActivity) al engancharse/desengancharse de la ventana, no al hacer
 * bind: con el RecyclerView reciclando vistas, bind puede llamarse varias
 * veces para una misma fila sin que cambie si está en pantalla o no.
 */
class EpgGridAdapter(
    private val channels: List<Stream>,
    private val windowProvider: () -> Pair<Long, Long>,
    private val densityProvider: () -> Float,
    private val onRowScrollAttached: (HorizontalScrollView) -> Unit,
    private val onRowScrollDetached: (HorizontalScrollView) -> Unit,
    private val onChannelClick: (Stream) -> Unit
) : RecyclerView.Adapter<EpgGridAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemEpgGridRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(channels[position])
    }

    override fun getItemCount() = channels.size

    override fun onViewAttachedToWindow(holder: ViewHolder) {
        super.onViewAttachedToWindow(holder)
        onRowScrollAttached(holder.binding.rowScroll)
    }

    override fun onViewDetachedFromWindow(holder: ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        onRowScrollDetached(holder.binding.rowScroll)
    }

    inner class ViewHolder(val binding: ItemEpgGridRowBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(stream: Stream) {
            val context = binding.root.context
            binding.channelName.text = stream.name
            binding.channelIcon.load(stream.icon) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }
            binding.channelColumn.setOnClickListener { onChannelClick(stream) }

            val (windowStart, windowEnd) = windowProvider()
            val density = densityProvider()
            val cells = EpgGridMath.buildCells(stream.tvgId, windowStart, windowEnd, System.currentTimeMillis())

            val container = binding.cellsContainer
            container.removeAllViews()
            for (cell in cells) {
                val left = EpgGridMath.xForTime(cell.startMillis, windowStart, density)
                val right = EpgGridMath.xForTime(cell.stopMillis, windowStart, density)
                container.addView(buildCellView(context, cell, (right - left).coerceAtLeast(1)))
            }
        }

        private fun buildCellView(context: Context, cell: EpgGridMath.Cell, widthPx: Int): TextView {
            return TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.MATCH_PARENT)
                textSize = 11f
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                val horizontalPadding = (6 * resources.displayMetrics.density).toInt()
                setPadding(horizontalPadding, 0, horizontalPadding, 0)
                if (cell.entry != null) {
                    text = cell.entry.title
                    setTextColor(
                        ContextCompat.getColor(
                            context,
                            if (cell.isNow) R.color.on_background else R.color.on_surface_muted
                        )
                    )
                    background = ContextCompat.getDrawable(
                        context,
                        if (cell.isNow) R.drawable.bg_epg_cell_now else R.drawable.bg_epg_cell
                    )
                } else {
                    text = ""
                    background = ContextCompat.getDrawable(context, R.drawable.bg_epg_cell_empty)
                }
            }
        }
    }
}

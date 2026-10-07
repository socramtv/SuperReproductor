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
import com.example.superplayer.data.EpgRepository
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
 *
 * [searchQuery] (ver EpgGridActivity.applySearch) resalta, dentro de la
 * franja visible, la celda cuyo título coincide con la búsqueda en curso
 * (bg_epg_cell_match, por delante del estilo de "ahora" si coinciden las
 * dos cosas) y pinta en dorado el nombre de cualquier canal que tenga una
 * coincidencia en TODA su guía, esté o no dentro de la franja de horas que
 * se ve ahora mismo (ver EpgRepository.allEntries/EpgGridMath.matchesSearch).
 */
class EpgGridAdapter(
    private var channels: List<Stream>,
    private val windowProvider: () -> Pair<Long, Long>,
    private val densityProvider: () -> Float,
    private val onRowScrollAttached: (HorizontalScrollView) -> Unit,
    private val onRowScrollDetached: (HorizontalScrollView) -> Unit,
    private val onChannelClick: (Stream) -> Unit,
    private val onCellClick: (EpgGridMath.Cell) -> Unit
) : RecyclerView.Adapter<EpgGridAdapter.ViewHolder>() {

    var searchQuery: String = ""

    /** Reemplaza la lista de canales mostrada (ver EpgGridActivity.applySearch) y repinta. */
    fun submit(newChannels: List<Stream>) {
        channels = newChannels
        notifyDataSetChanged()
    }

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
            val query = searchQuery
            binding.channelName.text = stream.name
            val channelHasMatch = query.isNotBlank() &&
                EpgGridMath.matchesSearch(stream.name, EpgRepository.allEntries(stream.tvgId), query)
            binding.channelName.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (channelHasMatch) R.color.favorite else R.color.on_background
                )
            )
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
                container.addView(buildCellView(context, cell, (right - left).coerceAtLeast(1), query))
            }
        }

        /**
         * Celda de un programa: título arriba (hasta 2 líneas) y debajo, más
         * pequeño y apagado, su horario ("06:00–10:55"). Las celdas sin
         * datos de guía quedan vacías.
         */
        private fun buildCellView(context: Context, cell: EpgGridMath.Cell, widthPx: Int, query: String): LinearLayout {
            val density = resources.displayMetrics.density
            return LinearLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(widthPx, LinearLayout.LayoutParams.MATCH_PARENT)
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                val horizontalPadding = (8 * density).toInt()
                setPadding(horizontalPadding, 0, horizontalPadding, 0)
                val entry = cell.entry
                if (entry != null) {
                    val isMatch = query.isNotBlank() && entry.title.contains(query, ignoreCase = true)
                    val title = TextView(context).apply {
                        text = entry.title
                        textSize = 12f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(
                            ContextCompat.getColor(
                                context,
                                if (isMatch || cell.isNow) R.color.on_background else R.color.on_surface_muted
                            )
                        )
                    }
                    val time = TextView(context).apply {
                        text = EpgRepository.formatRange(entry)
                        textSize = 11f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(ContextCompat.getColor(context, R.color.on_surface_muted))
                        setPadding(0, (2 * density).toInt(), 0, 0)
                    }
                    addView(title)
                    addView(time)
                    background = ContextCompat.getDrawable(
                        context,
                        when {
                            isMatch -> R.drawable.bg_epg_cell_match
                            cell.isNow -> R.drawable.bg_epg_cell_now
                            else -> R.drawable.bg_epg_cell
                        }
                    )
                    // Solo las celdas CON programa abren el diálogo de
                    // detalles (ver EpgGridActivity.showProgrammeDetails):
                    // un hueco sin datos de guía no tiene nada que mostrar.
                    setOnClickListener { onCellClick(cell) }
                } else {
                    background = ContextCompat.getDrawable(context, R.drawable.bg_epg_cell_empty)
                }
            }
        }
    }
}

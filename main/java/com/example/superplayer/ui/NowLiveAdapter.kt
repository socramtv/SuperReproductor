package com.example.superplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.databinding.ItemNowLiveBinding
import com.example.superplayer.model.Stream

/**
 * Tarjetas de "Ahora en directo" (ver NowLiveActivity): canal, programa de
 * ahora con su barra de progreso y el siguiente. La seleccionada (la que
 * tiene la vista previa) se resalta; [onSelect] se llama al tocar o al
 * enfocar con el mando, [onOpen] al tocar la que ya estaba seleccionada.
 */
class NowLiveAdapter(
    private val channels: List<Stream>,
    private val selectedIdProvider: () -> String?,
    private val onSelect: (Stream) -> Unit,
    private val onOpen: (Stream) -> Unit
) : RecyclerView.Adapter<NowLiveAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemNowLiveBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(channels[position])

    override fun getItemCount() = channels.size

    inner class ViewHolder(private val binding: ItemNowLiveBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(stream: Stream) {
            val context = binding.root.context
            binding.liveName.text = stream.name
            binding.liveLogo.load(stream.icon) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }
            val selected = stream.id == selectedIdProvider()
            binding.root.setBackgroundResource(
                if (selected) R.drawable.bg_now_live_card_selected else R.drawable.bg_now_live_card
            )

            val schedule = EpgRepository.schedule(stream.tvgId)
            val now = schedule.now
            if (now != null) {
                binding.liveNow.text = now.title
                val total = (now.stopMillis - now.startMillis).coerceAtLeast(1L)
                val done = (System.currentTimeMillis() - now.startMillis).coerceIn(0L, total)
                binding.liveProgress.progress = (done * 100 / total).toInt()
                binding.liveProgress.visibility = View.VISIBLE
            } else {
                binding.liveNow.text = context.getString(R.string.guide_no_data)
                binding.liveProgress.visibility = View.INVISIBLE
            }
            val next = schedule.next
            if (next != null) {
                binding.liveNext.text = context.getString(R.string.guide_next, EpgRepository.formatRange(next), next.title)
                binding.liveNext.visibility = View.VISIBLE
            } else {
                binding.liveNext.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                if (stream.id == selectedIdProvider()) onOpen(stream) else onSelect(stream)
            }
            // Con el mando de TV: al enfocar una tarjeta empieza su vista previa (OK la abre entera).
            binding.root.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && stream.id != selectedIdProvider()) onSelect(stream)
            }
        }
    }
}

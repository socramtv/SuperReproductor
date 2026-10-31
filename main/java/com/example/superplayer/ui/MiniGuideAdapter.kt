package com.example.superplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.example.superplayer.R
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.databinding.ItemMiniGuideBinding
import com.example.superplayer.model.Stream

/**
 * Filas de la mini-guía del reproductor (ver PlayerActivity.showMiniGuide):
 * cada canal de la lista actual con su programa de ahora (y una barrita de
 * cuánto lleva), el siguiente, y el canal que se está viendo resaltado. Al
 * elegir uno, [onPick] recibe su posición en [channels].
 */
class MiniGuideAdapter(
    private val channels: List<Stream>,
    private val currentIdProvider: () -> String?,
    private val onPick: (Int) -> Unit
) : RecyclerView.Adapter<MiniGuideAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMiniGuideBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(channels[position])

    override fun getItemCount() = channels.size

    inner class ViewHolder(private val binding: ItemMiniGuideBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(stream: Stream) {
            val context = binding.root.context
            binding.guideName.text = stream.name
            binding.guideLogo.load(stream.icon) {
                placeholder(R.drawable.ic_placeholder)
                error(R.drawable.ic_placeholder)
            }
            val isCurrent = stream.id == currentIdProvider()
            binding.root.setBackgroundResource(
                if (isCurrent) R.drawable.bg_mini_guide_current else R.drawable.bg_mini_guide_row
            )

            val schedule = EpgRepository.schedule(stream.tvgId)
            val now = schedule.now
            if (now != null) {
                binding.guideNow.text = context.getString(R.string.guide_now, now.title)
                val total = (now.stopMillis - now.startMillis).coerceAtLeast(1L)
                val done = (System.currentTimeMillis() - now.startMillis).coerceIn(0L, total)
                binding.guideProgress.progress = (done * 100 / total).toInt()
                binding.guideProgress.visibility = View.VISIBLE
            } else {
                binding.guideNow.text = context.getString(R.string.guide_no_data)
                binding.guideProgress.visibility = View.GONE
            }
            val next = schedule.next
            if (next != null) {
                binding.guideNext.text = context.getString(R.string.guide_next, EpgRepository.formatRange(next), next.title)
                binding.guideNext.visibility = View.VISIBLE
            } else {
                binding.guideNext.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) onPick(pos)
            }
        }
    }
}

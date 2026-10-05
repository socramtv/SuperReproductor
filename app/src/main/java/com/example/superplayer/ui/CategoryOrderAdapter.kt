package com.example.superplayer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.superplayer.R
import com.example.superplayer.databinding.ItemCategoryOrderBinding

/**
 * Lista reordenable de nombres de categoría para la ventana "Ordenar
 * categorías" (ver MainActivity.showCategoryOrderDialog). [names] se
 * modifica en sitio al mover filas; las ocultas por el filtro salen con la
 * marca "(oculta)" pero también se pueden mover.
 */
class CategoryOrderAdapter(
    val names: MutableList<String>,
    private val hidden: Set<String>
) : RecyclerView.Adapter<CategoryOrderAdapter.ViewHolder>() {

    fun move(from: Int, to: Int) {
        if (from == to || from !in names.indices || to !in names.indices) return
        names.add(to, names.removeAt(from))
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCategoryOrderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(names[position])

    override fun getItemCount() = names.size

    inner class ViewHolder(private val binding: ItemCategoryOrderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(name: String) {
            val context = binding.root.context
            binding.orderName.text =
                if (name in hidden) "$name ${context.getString(R.string.category_order_hidden_suffix)}" else name
            binding.orderName.alpha = if (name in hidden) 0.5f else 1f
            binding.orderUp.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) move(pos, pos - 1)
            }
            binding.orderDown.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) move(pos, pos + 1)
            }
        }
    }
}

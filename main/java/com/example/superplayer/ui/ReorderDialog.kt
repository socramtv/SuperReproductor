package com.example.superplayer.ui

import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.superplayer.R

/**
 * Ventana genérica para ordenar una lista a mano: arrastrando una fila
 * (pulsación larga) o con las flechas ▲ ▼ de cada fila (para el mando de la
 * TV). Es la misma mecánica que "Ordenar categorías" (ver MainActivity.
 * showCategoryOrderDialog), usada aquí para "Ordenar favoritos". [items]
 * son los identificadores; [labelOf] da el texto de cada uno.
 */
fun showReorderDialog(
    activity: AppCompatActivity,
    titleRes: Int,
    items: List<String>,
    labelOf: (String) -> String,
    onSave: (List<String>) -> Unit
) {
    val density = activity.resources.displayMetrics.density
    val orderAdapter = CategoryOrderAdapter(items.toMutableList(), emptySet(), labelOf)

    val recycler = RecyclerView(activity).apply {
        layoutManager = LinearLayoutManager(activity)
        adapter = orderAdapter
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        )
    }
    ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            orderAdapter.move(from.bindingAdapterPosition, to.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
    }).attachToRecyclerView(recycler)

    val container = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        val hint = TextView(activity).apply {
            setText(R.string.category_order_hint)
            textSize = 12f
            alpha = 0.7f
            val pad = (16 * density).toInt()
            setPadding(pad, 0, pad, (8 * density).toInt())
        }
        addView(hint)
        addView(recycler)
    }

    AlertDialog.Builder(activity)
        .setTitle(titleRes)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ -> onSave(orderAdapter.names.toList()) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

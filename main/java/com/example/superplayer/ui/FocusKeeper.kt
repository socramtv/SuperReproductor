package com.example.superplayer.ui

import androidx.recyclerview.widget.RecyclerView

/**
 * Con el mando de TV, repintar una lista (notifyDataSetChanged) quita la vista que tenía el foco y
 * Android lo manda al primer elemento de la pantalla (la lupa de la barra). Estas ayudas devuelven
 * el foco a la fila en la que estabas.
 */
object FocusKeeper {

    /** Lleva el foco a la fila [pos], reintentando hasta que la lista esté pintada. */
    fun restore(rv: RecyclerView, pos: Int, tries: Int = 0) {
        val count = rv.adapter?.itemCount ?: 0
        if (count == 0 || pos < 0) return
        val target = pos.coerceAtMost(count - 1)
        rv.scrollToPosition(target)
        rv.postDelayed({
            val h = rv.findViewHolderForAdapterPosition(target)
            if (h != null) h.itemView.requestFocus() else if (tries < 6) restore(rv, target, tries + 1)
        }, if (tries == 0) 40L else 120L)
    }

    /** Ejecuta [change] (que repinta la lista) y devuelve el foco a la misma posición si lo tenía un elemento. */
    fun keep(rv: RecyclerView, change: () -> Unit) {
        val focused = rv.focusedChild
        val pos = if (focused != null) rv.getChildAdapterPosition(focused) else RecyclerView.NO_POSITION
        change()
        if (pos != RecyclerView.NO_POSITION) restore(rv, pos)
    }
}

package com.example.superplayer.ui

import android.view.View
import android.widget.EditText
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.superplayer.R

/**
 * Empuja [top] hacia abajo lo que ocupe la barra de estado y [bottom] hacia
 * arriba lo que ocupe la barra de navegación, sumado al padding que ya
 * tuvieran, para que ningún contenido quede tapado por las barras del
 * sistema cuando la ventana dibuja "edge-to-edge".
 */
fun applySystemBarInsets(top: View, bottom: View) {
    val topStartPadding = top.paddingTop
    val bottomStartPadding = bottom.paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(top) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(v.paddingLeft, topStartPadding + bars.top, v.paddingRight, v.paddingBottom)
        insets
    }
    ViewCompat.setOnApplyWindowInsetsListener(bottom) { v, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, bottomStartPadding + bars.bottom)
        insets
    }
}

/**
 * El texto interno del SearchView de AppCompat no sigue los colores del
 * tema oscuro de la app por defecto (queda apagado y cuesta leerlo). Se
 * fuerza aquí el texto que escribes a "on_background" (casi blanco) y el
 * texto de ejemplo ("Buscar canal…") a "on_surface_muted" (gris claro).
 */
fun styleSearchView(context: android.content.Context, searchView: SearchView) {
    val textColor = ContextCompat.getColor(context, R.color.on_background)
    val hintColor = ContextCompat.getColor(context, R.color.on_surface_muted)
    val searchText = searchView.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)
    searchText?.setTextColor(textColor)
    searchText?.setHintTextColor(hintColor)
}

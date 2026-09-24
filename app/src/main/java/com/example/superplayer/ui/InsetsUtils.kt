package com.example.superplayer.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

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

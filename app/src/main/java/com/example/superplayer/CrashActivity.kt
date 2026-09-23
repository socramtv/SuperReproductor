package com.example.superplayer

import android.graphics.Color
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Pantalla de "algo se rompió": muestra el error completo, seleccionable,
 * para poder hacer captura de pantalla y compartirlo.
 */
class CrashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val trace = intent.getStringExtra(EXTRA_TRACE) ?: "Error desconocido (sin detalle)."

        val textView = TextView(this).apply {
            text = "La app se cerró por este error.\nHaz captura de esta pantalla y compártela:\n\n$trace"
            setTextIsSelectable(true)
            setPadding(32, 96, 32, 64)
            textSize = 12f
            setTextColor(Color.WHITE)
        }
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(textView)
        }
        setContentView(scrollView)
    }

    companion object {
        const val EXTRA_TRACE = "trace"
    }
}

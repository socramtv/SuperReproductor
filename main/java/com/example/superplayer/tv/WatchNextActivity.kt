package com.example.superplayer.tv

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.superplayer.data.ContinueWatching
import com.example.superplayer.player.PlayerActivity

/**
 * Punto de entrada de la fila "Continuar viendo" del inicio de la TV (ver
 * WatchNextSync). El inicio del sistema es otra app y solo puede abrir
 * pantallas exportadas, y el reproductor no lo es; esta pantalla invisible
 * busca el canal por su id en "Continuar viendo", abre el reproductor (que
 * ya retoma solo por donde se quedó) y se cierra.
 */
class WatchNextActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_STREAM_ID)
        val stream = ContinueWatching.getAll(this).firstOrNull { it.stream.id == id }?.stream
        if (stream != null) {
            PlayerActivity.pendingStream = stream
            PlayerActivity.pendingChannelList = emptyList()
            startActivity(Intent(this, PlayerActivity::class.java))
        } else {
            // Ya no está en la lista (se terminó de ver o se vació): se abre la app normal.
            packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
        }
        finish()
    }

    companion object {
        const val EXTRA_STREAM_ID = "watch_next_stream_id"
    }
}

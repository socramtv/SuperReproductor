package com.example.superplayer.widget

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.superplayer.model.streamFromJson
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.player.ShortcutsHelper
import com.example.superplayer.sports.GoalAlerts
import com.example.superplayer.ui.MainActivity

/**
 * Pantalla invisible a la que apuntan las filas del widget (ver
 * HomeWidgetService): una lista de widget solo admite UN destino para todas
 * sus filas, y cada fila necesita abrir una cosa distinta. Según lo que
 * traiga el Intent abre el canal en el reproductor o busca el canal de un
 * partido (ver MainActivity.handleMatchIntent), y se cierra.
 */
class WidgetClickActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val streamJson = intent.getStringExtra(EXTRA_STREAM_JSON)
        val home = intent.getStringExtra(GoalAlerts.EXTRA_HOME)
        val away = intent.getStringExtra(GoalAlerts.EXTRA_AWAY)
        when {
            streamJson != null && streamFromJson(streamJson) != null -> {
                val play = Intent(this, PlayerActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(ShortcutsHelper.EXTRA_SHORTCUT_STREAM_JSON, streamJson)
                }
                startActivity(play)
            }
            home != null && away != null -> {
                val open = Intent(this, MainActivity::class.java).apply {
                    putExtra(GoalAlerts.EXTRA_HOME, home)
                    putExtra(GoalAlerts.EXTRA_AWAY, away)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(open)
            }
            else -> {
                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_STREAM_JSON = "widget_stream_json"
    }
}

package com.example.superplayer.ui

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.superplayer.R
import com.example.superplayer.model.Stream
import com.example.superplayer.model.toJson
import com.example.superplayer.remote.RemoteClient
import com.example.superplayer.remote.RemoteDevice

/**
 * «Enviar a la TV» para un stream que la tele puede abrir por sí sola (una dirección pública, p. ej. un vídeo
 * detectado en la Web): elige la tele guardada del «Mando de tele» (si hay varias) y se lo manda con /open.
 */
object TvSender {
    fun send(activity: Activity, stream: Stream) {
        val devices = RemoteClient.savedDevices(activity)
        if (devices.isEmpty()) {
            Toast.makeText(activity, R.string.tgtv_no_tv, Toast.LENGTH_LONG).show()
            activity.startActivity(Intent(activity, RemoteActivity::class.java))
            return
        }
        val last = RemoteClient.lastDevice(activity)
        val sorted = devices.sortedBy { if (it.key == last?.key) 0 else 1 }
        if (sorted.size == 1) {
            doSend(activity, sorted[0], stream)
        } else {
            AlertDialog.Builder(activity)
                .setTitle(R.string.remote_choose_title)
                .setItems(sorted.map { "📺 ${it.name}  ·  ${it.host}" }.toTypedArray()) { _, which -> doSend(activity, sorted[which], stream) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun doSend(activity: Activity, d: RemoteDevice, stream: Stream) {
        Toast.makeText(activity, activity.getString(R.string.tgtv_sending, d.name), Toast.LENGTH_SHORT).show()
        Thread {
            val (code, text) = RemoteClient.call(d, "/open", stream.toJson())
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                val ok = code in 200..299 && try { org.json.JSONObject(text).optBoolean("ok", true) } catch (e: Exception) { true }
                Toast.makeText(
                    activity,
                    when {
                        code == 401 -> activity.getString(R.string.remote_wrong_pin)
                        code < 0 -> activity.getString(R.string.remote_unreachable)
                        !ok -> activity.getString(R.string.tgtv_failed)
                        else -> activity.getString(R.string.web_tv_sent, d.name)
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }.start()
    }
}

package com.example.superplayer.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.example.superplayer.R
import com.example.superplayer.remote.RemoteServer

/**
 * Ajustes del control remoto de ESTA pantalla (normalmente la tele): si se
 * puede controlar desde otro móvil, la dirección y el código que hay que
 * escribir en el móvil-mando. Ver remote/RemoteServer.
 */
fun showRemoteControlDialog(activity: Activity) {
    val enabled = RemoteServer.isEnabled(activity)
    if (enabled) RemoteServer.ensureStarted(activity)
    val message = if (enabled) {
        val address = RemoteServer.localAddress()?.let { "$it:${RemoteServer.port}" } ?: "?"
        activity.getString(
            R.string.remote_server_on,
            RemoteServer.deviceName(), address, RemoteServer.pin(activity)
        )
    } else {
        activity.getString(R.string.remote_server_off)
    }
    val builder = AlertDialog.Builder(activity)
        .setTitle(R.string.remote_server_title)
        .setMessage(message)
        .setPositiveButton(if (enabled) R.string.remote_server_disable else R.string.remote_server_enable) { _, _ ->
            RemoteServer.setEnabled(activity, !enabled)
            showRemoteControlDialog(activity)
        }
        .setNegativeButton(R.string.channel_check_close, null)
    if (enabled) {
        builder.setNeutralButton(R.string.remote_server_new_pin) { _, _ ->
            RemoteServer.newPin(activity)
            showRemoteControlDialog(activity)
        }
    }
    builder.show()
}

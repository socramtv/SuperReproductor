package com.example.superplayer.ui

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.superplayer.R
import com.example.superplayer.model.Stream
import com.example.superplayer.reminder.ProgramAlerts

/**
 * Avisos por programa: lista de los avisos guardados (tocar uno para
 * quitarlo) y "Añadir aviso…". [streams] son los canales entre los que
 * buscar el programa en la guía.
 */
fun showProgramAlertsDialog(activity: Activity, streams: List<Stream>) {
    val keywords = ProgramAlerts.getAll(activity)
    val labels = ArrayList<String>()
    for (k in keywords) labels.add("🔔 $k")
    labels.add(activity.getString(R.string.program_alert_add_item))
    AlertDialog.Builder(activity)
        .setTitle(R.string.program_alerts_title)
        .setItems(labels.toTypedArray()) { _, which ->
            if (which == keywords.size) {
                showAddProgramAlertDialog(activity, "", streams)
            } else {
                val keyword = keywords[which]
                AlertDialog.Builder(activity)
                    .setTitle(keyword)
                    .setMessage(R.string.program_alert_remove_message)
                    .setPositiveButton(R.string.program_alert_remove) { _, _ ->
                        ProgramAlerts.remove(activity, keyword)
                        Toast.makeText(activity, R.string.program_alert_removed, Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        .setNegativeButton(R.string.channel_check_close, null)
        .show()
}

/** Pide la palabra o título a vigilar (con [initial] ya escrito) y, al aceptar, busca ya en la guía. */
fun showAddProgramAlertDialog(activity: Activity, initial: String, streams: List<Stream>) {
    val input = EditText(activity)
    input.inputType = InputType.TYPE_CLASS_TEXT
    input.setHint(R.string.program_alert_hint)
    input.setText(initial)
    input.setSelection(input.text.length)
    val container = FrameLayout(activity)
    val pad = (20 * activity.resources.displayMetrics.density).toInt()
    container.setPadding(pad, pad / 2, pad, 0)
    container.addView(input)
    AlertDialog.Builder(activity)
        .setTitle(R.string.program_alert_add_title)
        .setMessage(R.string.program_alert_add_message)
        .setView(container)
        .setPositiveButton(R.string.program_alert_save) { _, _ ->
            val keyword = input.text.toString().trim()
            if (keyword.isEmpty()) return@setPositiveButton
            if (Build.VERSION.SDK_INT >= 33 &&
                activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                activity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 4711)
            }
            if (!ProgramAlerts.add(activity, keyword)) {
                Toast.makeText(activity, R.string.program_alert_exists, Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val appContext = activity.applicationContext
            Thread {
                val added = try { ProgramAlerts.scan(appContext, streams) } catch (e: Exception) { 0 }
                activity.runOnUiThread {
                    if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.program_alert_saved_toast, keyword, added),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }.start()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

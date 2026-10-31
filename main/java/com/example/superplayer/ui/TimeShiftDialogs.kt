package com.example.superplayer.ui

import android.app.Activity
import androidx.appcompat.app.AlertDialog
import com.example.superplayer.R
import com.example.superplayer.data.AppPrefs

private val TIME_SHIFT_OPTIONS = intArrayOf(0, 10, 30, 60)

/**
 * Pausa en directo: elige cuántos minutos de directo se guardan para poder
 * pausar y retroceder (0 = desactivada). [onChanged] se llama solo si el
 * valor cambia (el reproductor reinicia el canal para que empiece a
 * guardar).
 */
fun showTimeShiftDialog(activity: Activity, onChanged: () -> Unit) {
    val current = AppPrefs.getTimeShiftMinutes(activity)
    val labels = arrayOf(
        activity.getString(R.string.timeshift_off),
        activity.getString(R.string.timeshift_minutes, 10),
        activity.getString(R.string.timeshift_minutes, 30),
        activity.getString(R.string.timeshift_minutes, 60)
    )
    val checked = TIME_SHIFT_OPTIONS.indexOf(current).let { if (it < 0) 0 else it }
    AlertDialog.Builder(activity)
        .setTitle(R.string.timeshift_title)
        .setSingleChoiceItems(labels, checked) { dialog, which ->
            val minutes = TIME_SHIFT_OPTIONS[which]
            dialog.dismiss()
            if (minutes != current) {
                AppPrefs.setTimeShiftMinutes(activity, minutes)
                if (minutes > 0) {
                    android.widget.Toast.makeText(activity, R.string.timeshift_message, android.widget.Toast.LENGTH_LONG).show()
                }
                onChanged()
            }
        }
        .setNegativeButton(R.string.channel_check_close, null)
        .show()
}

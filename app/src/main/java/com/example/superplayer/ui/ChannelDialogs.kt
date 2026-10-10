package com.example.superplayer.ui

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.superplayer.R
import com.example.superplayer.data.ChannelChecker
import com.example.superplayer.data.ChannelOverrides
import com.example.superplayer.model.Stream

/**
 * Menú de un canal (mantener pulsado en cualquier lista): favorito,
 * cambiar nombre, volver al nombre original y ocultar. [onChanged] se llama
 * cuando hay que repintar la lista (los cambios de nombre/ocultar ya están
 * guardados en ChannelOverrides).
 */
fun showChannelOptionsDialog(
    activity: Activity,
    stream: Stream,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onChanged: () -> Unit
) {
    val hasCustomName = ChannelOverrides.hasCustomName(activity, stream.id)
    val labels = ArrayList<String>()
    val actions = ArrayList<() -> Unit>()

    labels.add(activity.getString(if (isFavorite) R.string.channel_opt_unfavorite else R.string.channel_opt_favorite))
    actions.add { onToggleFavorite() }

    labels.add(activity.getString(R.string.channel_opt_rename))
    actions.add { showRenameDialog(activity, stream, onChanged) }

    if (hasCustomName) {
        labels.add(activity.getString(R.string.channel_opt_reset_name))
        actions.add {
            ChannelOverrides.rename(activity, stream.id, "")
            onChanged()
        }
    }

    labels.add(activity.getString(R.string.channel_opt_hide))
    actions.add {
        ChannelOverrides.hide(activity, listOf(stream))
        Toast.makeText(activity, R.string.channel_hidden_toast, Toast.LENGTH_SHORT).show()
        onChanged()
    }

    AlertDialog.Builder(activity)
        .setTitle(stream.name)
        .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
        .show()
}

private fun showRenameDialog(activity: Activity, stream: Stream, onChanged: () -> Unit) {
    val input = EditText(activity)
    input.inputType = InputType.TYPE_CLASS_TEXT
    input.setText(stream.name)
    input.setSelection(input.text.length)
    val container = FrameLayout(activity)
    val pad = (20 * activity.resources.displayMetrics.density).toInt()
    container.setPadding(pad, pad / 2, pad, 0)
    container.addView(input)
    AlertDialog.Builder(activity)
        .setTitle(R.string.channel_opt_rename)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty()) {
                ChannelOverrides.rename(activity, stream.id, newName)
                onChanged()
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/** Canales ocultos: lista con casillas para volver a mostrar los que se marquen. */
fun showHiddenChannelsDialog(activity: Activity, onChanged: () -> Unit) {
    val hidden = ChannelOverrides.getHidden(activity).entries.toList()
    if (hidden.isEmpty()) {
        Toast.makeText(activity, R.string.channel_hidden_none, Toast.LENGTH_SHORT).show()
        return
    }
    val checked = BooleanArray(hidden.size)
    AlertDialog.Builder(activity)
        .setTitle(R.string.channel_hidden_title)
        .setMultiChoiceItems(hidden.map { it.value }.toTypedArray(), checked) { _, which, isChecked ->
            checked[which] = isChecked
        }
        .setPositiveButton(R.string.channel_hidden_show) { _, _ ->
            val ids = hidden.filterIndexed { i, _ -> checked[i] }.map { it.key }
            if (ids.isNotEmpty()) {
                ChannelOverrides.unhide(activity, ids)
                onChanged()
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

/**
 * Comprobador de canales caídos: prueba todos los [streams] con una barra de
 * progreso y al final enseña los que no responden, marcados, para ocultar los
 * que se quiera (ver ChannelChecker: puede haber falsos positivos, por eso
 * se puede desmarcar antes).
 */
fun showChannelCheckDialog(activity: Activity, streams: List<Stream>, onChanged: () -> Unit) {
    if (streams.isEmpty()) return
    val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
    progress.max = streams.size
    val label = TextView(activity)
    label.text = activity.getString(R.string.channel_check_progress, 0, streams.size)
    val box = android.widget.LinearLayout(activity)
    box.orientation = android.widget.LinearLayout.VERTICAL
    val pad = (20 * activity.resources.displayMetrics.density).toInt()
    box.setPadding(pad, pad, pad, pad / 2)
    box.addView(label)
    box.addView(progress)

    var job: ChannelChecker.Job? = null
    val dialog = AlertDialog.Builder(activity)
        .setTitle(R.string.channel_check_title)
        .setView(box)
        .setNegativeButton(android.R.string.cancel) { _, _ -> job?.cancel() }
        .setOnCancelListener { job?.cancel() }
        .create()
    dialog.show()

    job = ChannelChecker.check(
        streams,
        onProgress = { done, total ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                progress.progress = done
                label.text = activity.getString(R.string.channel_check_progress, done, total)
            }
        },
        onDone = { down ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                dialog.dismiss()
                showCheckResult(activity, streams.size, down, onChanged)
            }
        }
    )
}

private fun showCheckResult(activity: Activity, total: Int, down: List<Stream>, onChanged: () -> Unit) {
    if (down.isEmpty()) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.channel_check_title)
            .setMessage(activity.getString(R.string.channel_check_all_ok, total))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return
    }
    val checked = BooleanArray(down.size) { true }
    AlertDialog.Builder(activity)
        .setTitle(activity.getString(R.string.channel_check_result_title, down.size, total))
        .setMultiChoiceItems(down.map { it.name }.toTypedArray(), checked) { _, which, isChecked ->
            checked[which] = isChecked
        }
        .setPositiveButton(R.string.channel_check_hide_marked) { _, _ ->
            val toHide = down.filterIndexed { i, _ -> checked[i] }
            if (toHide.isNotEmpty()) {
                ChannelOverrides.hide(activity, toHide)
                Toast.makeText(
                    activity,
                    activity.getString(R.string.channel_check_hidden_toast, toHide.size),
                    Toast.LENGTH_LONG
                ).show()
                onChanged()
            }
        }
        .setNegativeButton(R.string.channel_check_close, null)
        .show()
}

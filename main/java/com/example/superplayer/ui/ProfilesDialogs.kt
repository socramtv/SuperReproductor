package com.example.superplayer.ui

import android.app.Activity
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.superplayer.R
import com.example.superplayer.data.Profiles

/**
 * Perfiles: elegir con cuál se usa la app (cada uno con sus favoritos,
 * ocultos, "Continuar viendo" y categorías), crear uno nuevo y gestionar
 * (renombrar / borrar). [onSwitched] se llama cuando se cambia de perfil:
 * la pantalla principal debe reiniciarse entera para cargar los datos del
 * nuevo.
 */
fun showProfilesDialog(activity: Activity, onSwitched: () -> Unit) {
    val profiles = Profiles.all(activity)
    val activeId = Profiles.activeId(activity)
    val labels = ArrayList<String>()
    for (p in profiles) labels.add((if (p.id == activeId) "✓ " else "     ") + p.name)
    labels.add(activity.getString(R.string.profile_new_item))
    labels.add(activity.getString(R.string.profile_manage_item))
    AlertDialog.Builder(activity)
        .setTitle(R.string.profiles_title)
        .setItems(labels.toTypedArray()) { _, which ->
            when {
                which < profiles.size -> {
                    val chosen = profiles[which]
                    if (chosen.id != activeId) {
                        Profiles.setActive(activity, chosen.id)
                        Toast.makeText(
                            activity, activity.getString(R.string.profile_switched, chosen.name), Toast.LENGTH_SHORT
                        ).show()
                        onSwitched()
                    }
                }
                which == profiles.size -> showNewProfileDialog(activity, onSwitched)
                else -> showManageProfilesDialog(activity)
            }
        }
        .setNegativeButton(R.string.channel_check_close, null)
        .show()
}

private fun nameInput(activity: Activity, initial: String): Pair<EditText, FrameLayout> {
    val input = EditText(activity)
    input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    input.setText(initial)
    input.setSelection(input.text.length)
    val container = FrameLayout(activity)
    val pad = (20 * activity.resources.displayMetrics.density).toInt()
    container.setPadding(pad, pad / 2, pad, 0)
    container.addView(input)
    return input to container
}

private fun showNewProfileDialog(activity: Activity, onSwitched: () -> Unit) {
    val (input, container) = nameInput(activity, "")
    input.setHint(R.string.profile_name_hint)
    AlertDialog.Builder(activity)
        .setTitle(R.string.profile_new_title)
        .setView(container)
        .setPositiveButton(R.string.profile_create) { _, _ ->
            val name = input.text.toString().trim()
            if (name.isEmpty()) return@setPositiveButton
            val profile = Profiles.add(activity, name)
            Profiles.setActive(activity, profile.id)
            Toast.makeText(activity, activity.getString(R.string.profile_switched, profile.name), Toast.LENGTH_SHORT).show()
            onSwitched()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun showManageProfilesDialog(activity: Activity) {
    val profiles = Profiles.all(activity)
    AlertDialog.Builder(activity)
        .setTitle(R.string.profile_manage_title)
        .setItems(profiles.map { it.name }.toTypedArray()) { _, which ->
            val profile = profiles[which]
            AlertDialog.Builder(activity)
                .setTitle(profile.name)
                .setItems(
                    arrayOf(
                        activity.getString(R.string.profile_rename),
                        activity.getString(R.string.profile_delete)
                    )
                ) { _, option ->
                    if (option == 0) {
                        val (input, container) = nameInput(activity, profile.name)
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.profile_rename)
                            .setView(container)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                Profiles.rename(activity, profile.id, input.text.toString())
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        confirmDeleteProfile(activity, profile)
                    }
                }
                .show()
        }
        .setNegativeButton(R.string.channel_check_close, null)
        .show()
}

private fun confirmDeleteProfile(activity: Activity, profile: Profiles.Profile) {
    if (profile.id == Profiles.DEFAULT_ID || profile.id == Profiles.activeId(activity)) {
        Toast.makeText(activity, R.string.profile_cannot_delete, Toast.LENGTH_LONG).show()
        return
    }
    AlertDialog.Builder(activity)
        .setTitle(profile.name)
        .setMessage(R.string.profile_delete_message)
        .setPositiveButton(R.string.profile_delete) { _, _ ->
            Profiles.delete(activity, profile.id)
            Toast.makeText(activity, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

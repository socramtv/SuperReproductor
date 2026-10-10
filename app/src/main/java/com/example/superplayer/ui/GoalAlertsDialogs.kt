package com.example.superplayer.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.superplayer.R
import com.example.superplayer.sports.GoalAlerts
import com.example.superplayer.sports.MatchFeed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Menú de "Avisos de goles": activar/desactivar, mis equipos, partidos de hoy (para comprobar que llegan datos) y probar un aviso. */
fun showGoalAlertsDialog(activity: Activity) {
    val enabled = GoalAlerts.isEnabled(activity)
    val teams = GoalAlerts.getTeams(activity)
    val labels = arrayOf(
        activity.getString(if (enabled) R.string.goal_disable else R.string.goal_enable),
        activity.getString(R.string.goal_my_teams, teams.size),
        activity.getString(R.string.goal_today),
        activity.getString(R.string.goal_test)
    )
    AlertDialog.Builder(activity)
        .setTitle(
            activity.getString(
                R.string.goal_title,
                activity.getString(if (enabled) R.string.goal_state_on else R.string.goal_state_off)
            )
        )
        .setItems(labels) { _, which ->
            when (which) {
                0 -> toggleGoalAlerts(activity, !enabled)
                1 -> showTeamsDialog(activity)
                2 -> showTodayMatchesDialog(activity)
                3 -> {
                    requestNotificationPermission(activity)
                    GoalAlerts.sendTest(activity)
                }
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun requestNotificationPermission(activity: Activity) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2001)
    }
}

private fun toggleGoalAlerts(activity: Activity, enable: Boolean) {
    if (enable) {
        requestNotificationPermission(activity)
        GoalAlerts.setEnabled(activity, true)
        if (GoalAlerts.getTeams(activity).isEmpty()) {
            Toast.makeText(activity, R.string.goal_pick_first, Toast.LENGTH_LONG).show()
            showTeamsDialog(activity)
        } else {
            Toast.makeText(activity, R.string.goal_enabled_toast, Toast.LENGTH_LONG).show()
        }
    } else {
        GoalAlerts.setEnabled(activity, false)
        Toast.makeText(activity, R.string.goal_disabled_toast, Toast.LENGTH_SHORT).show()
    }
}

private fun showTeamsDialog(activity: Activity) {
    val teams = GoalAlerts.getTeams(activity)
    val builder = AlertDialog.Builder(activity).setTitle(R.string.goal_teams_title)
    if (teams.isEmpty()) {
        builder.setMessage(R.string.goal_no_teams)
    } else {
        val checked = BooleanArray(teams.size) { true }
        builder.setMultiChoiceItems(teams.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
        builder.setPositiveButton(R.string.goal_save) { _, _ ->
            GoalAlerts.setTeams(activity, teams.filterIndexed { i, _ -> checked[i] })
        }
    }
    builder.setNeutralButton(R.string.goal_add) { _, _ -> showAddTeamDialog(activity) }
    builder.setNegativeButton(android.R.string.cancel, null)
    builder.show()
}

private fun showAddTeamDialog(activity: Activity) {
    AlertDialog.Builder(activity)
        .setTitle(R.string.goal_add)
        .setItems(
            arrayOf(activity.getString(R.string.goal_add_from_today), activity.getString(R.string.goal_add_typed))
        ) { _, which ->
            if (which == 0) pickTeamsFromToday(activity) else typeTeamName(activity)
        }
        .show()
}

private fun typeTeamName(activity: Activity) {
    val input = EditText(activity)
    input.inputType = InputType.TYPE_CLASS_TEXT
    input.hint = activity.getString(R.string.goal_type_hint)
    val container = FrameLayout(activity)
    val pad = (20 * activity.resources.displayMetrics.density).toInt()
    container.setPadding(pad, pad / 2, pad, 0)
    container.addView(input)
    AlertDialog.Builder(activity)
        .setTitle(R.string.goal_add_typed)
        .setView(container)
        .setPositiveButton(android.R.string.ok) { _, _ ->
            val name = input.text.toString().trim()
            if (name.length >= 3) {
                GoalAlerts.setTeams(activity, GoalAlerts.getTeams(activity) + name)
                showTeamsDialog(activity)
            }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
}

private fun pickTeamsFromToday(activity: Activity) {
    Toast.makeText(activity, R.string.goal_searching, Toast.LENGTH_SHORT).show()
    Thread {
        val result = MatchFeed.fetchAll()
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            val names = result.matches.flatMap { listOf(it.home, it.away) }.distinct().sorted()
            if (names.isEmpty()) {
                Toast.makeText(activity, R.string.goal_no_data, Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }
            val checked = BooleanArray(names.size)
            AlertDialog.Builder(activity)
                .setTitle(R.string.goal_add_from_today)
                .setMultiChoiceItems(names.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
                .setPositiveButton(R.string.goal_save) { _, _ ->
                    val chosen = names.filterIndexed { i, _ -> checked[i] }
                    if (chosen.isNotEmpty()) {
                        GoalAlerts.setTeams(activity, GoalAlerts.getTeams(activity) + chosen)
                        showTeamsDialog(activity)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }.start()
}

/** Los partidos que devuelve el servicio de marcadores ahora mismo: sirve de comprobación de que llegan datos. */
private fun showTodayMatchesDialog(activity: Activity) {
    Toast.makeText(activity, R.string.goal_searching, Toast.LENGTH_SHORT).show()
    Thread {
        val result = MatchFeed.fetchAll()
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            if (result.matches.isEmpty()) {
                val message = if (result.failedLeagues >= MatchFeed.LEAGUES.size) R.string.goal_no_connection else R.string.goal_no_matches
                AlertDialog.Builder(activity)
                    .setTitle(R.string.goal_today)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@runOnUiThread
            }
            val followed = GoalAlerts.getTeams(activity)
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            val lines = result.matches.sortedBy { it.startMillis }.map { m ->
                val star = if (GoalAlerts.matchesTeam(followed, m.home) || GoalAlerts.matchesTeam(followed, m.away)) "⭐ " else ""
                val status = when (m.state) {
                    "in" -> "${m.homeScore}-${m.awayScore} · ${m.clock.ifBlank { "en juego" }}"
                    "post" -> "${m.homeScore}-${m.awayScore} · final"
                    else -> if (m.startMillis > 0) fmt.format(Date(m.startMillis)) else "—"
                }
                "$star${m.home} - ${m.away}\n$status · ${m.league}"
            }
            AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.goal_today_count, result.matches.size))
                .setItems(lines.toTypedArray(), null)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }.start()
}

package com.example.superplayer.sports

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.superplayer.R
import com.example.superplayer.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Avisos de goles y eventos de los equipos que sigues (ver README, "Avisos
 * de goles y eventos"). Cada pocos minutos una alarma despierta a
 * [GoalAlertReceiver], que pide los marcadores (ver [MatchFeed]), compara con
 * lo que ya sabía de cada partido y lanza una notificación por cada novedad:
 * pronto empieza, empieza, gol (o gol anulado), expulsión y final.
 *
 * El ritmo se adapta: con un partido tuyo en juego, cada minuto; si no, se
 * despierta justo antes del siguiente (o cada 30 min como mucho). Android
 * puede retrasar las alarmas con el móvil quieto y la pantalla apagada (modo
 * Doze), así que con el móvil guardado puede llegar con algo de retraso.
 *
 * Al tocar un aviso se abre la app, que busca en la guía de tus canales cuál
 * está emitiendo ese partido (ver MainActivity.handleMatchIntent).
 */
object GoalAlerts {
    const val ACTION_POLL = "com.example.superplayer.GOAL_ALERT_POLL"
    const val CHANNEL_ID = "match_alerts"
    const val EXTRA_HOME = "match_home"
    const val EXTRA_AWAY = "match_away"

    private const val PREFS_NAME = "goal_alerts"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TEAMS = "teams"
    private const val KEY_SEEN = "seen"

    private const val LIVE_INTERVAL_MS = 60_000L
    private const val MAX_IDLE_MS = 30 * 60_000L
    private const val FALLBACK_MS = 5 * 60_000L
    private const val PRE_NOTICE_MS = 15 * 60_000L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- ajustes

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            scheduleNext(context, 3_000L)
        } else {
            cancel(context)
            prefs(context).edit().remove(KEY_SEEN).apply()
        }
    }

    fun getTeams(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_TEAMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setTeams(context: Context, teams: List<String>) {
        prefs(context).edit().putString(KEY_TEAMS, JSONArray(teams.distinct()).toString()).apply()
    }

    /** Se llama al arrancar la app y al reiniciar el móvil: si los avisos están activos y no hay alarma pendiente, la pone. */
    fun ensureScheduled(context: Context) {
        if (!isEnabled(context)) return
        if (pendingIntent(context, create = false) == null) scheduleNext(context, 10_000L)
    }

    // ------------------------------------------------------------ comparación

    private fun norm(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9]"), "")

    /** true si [teamName] (el de la API) es uno de los [followed] (lo que escribió o eligió el usuario): coincidencia por contener, sin tildes. */
    fun matchesTeam(followed: List<String>, teamName: String): Boolean {
        val n = norm(teamName)
        return followed.any { f ->
            val nf = norm(f)
            nf.length >= 3 && (n.contains(nf) || nf.contains(n))
        }
    }

    private fun followsMatch(followed: List<String>, m: MatchFeed.Match) =
        matchesTeam(followed, m.home) || matchesTeam(followed, m.away)

    // -------------------------------------------------------------- consulta

    /** Una vuelta de comprobación (la llama el receptor, en un hilo de fondo). */
    fun poll(context: Context) {
        if (!isEnabled(context)) return
        val teams = getTeams(context)
        if (teams.isEmpty()) {
            scheduleNext(context, MAX_IDLE_MS)
            return
        }
        // Red de seguridad por si el proceso se corta a mitad: otra vuelta en unos minutos.
        scheduleNext(context, FALLBACK_MS)

        val result = MatchFeed.fetchAll()
        if (result.matches.isEmpty() && result.failedLeagues >= MatchFeed.LEAGUES.size) {
            return // sin conexión o la API caída: se reintenta con la alarma de seguridad
        }
        val followed = result.matches.filter { followsMatch(teams, it) }
        val previous = loadSeen(context)
        val updated = JSONObject()
        val now = System.currentTimeMillis()

        for (m in followed) {
            val prev = previous.optJSONObject(m.id)
            val prevKeys = HashSet<String>()
            prev?.optJSONArray("keys")?.let { a -> for (i in 0 until a.length()) prevKeys.add(a.getString(i)) }
            var preNotified = prev?.optBoolean("pre", false) ?: false

            if (prev == null) {
                // Primera vez que se ve este partido: si ya está en juego se toma como base sin avisar de lo anterior.
                if (m.state == "pre" && m.startMillis - now in 0..PRE_NOTICE_MS) {
                    notifyPreMatch(context, m, ((m.startMillis - now) / 60_000L).toInt().coerceAtLeast(1))
                    preNotified = true
                }
            } else {
                val prevState = prev.optString("state")
                val prevTotal = prev.optInt("hs") + prev.optInt("as")
                val total = m.homeScore + m.awayScore

                if (prevState == "pre" && m.state == "in") notifyStart(context, m)
                if (prevState == "pre" && m.state == "post") { /* partido que no se vio empezar: sin aviso */ }

                val newGoals = m.details.filter { it.kind == MatchFeed.Kind.GOAL && it.key !in prevKeys }
                if (total > prevTotal) {
                    notifyGoal(context, m, newGoals.lastOrNull())
                } else if (total < prevTotal) {
                    notifyGoalDisallowed(context, m)
                }
                for (red in m.details.filter { it.kind == MatchFeed.Kind.RED_CARD && it.key !in prevKeys }) {
                    notifyRedCard(context, m, red)
                }
                if (prevState != "post" && m.state == "post" && prevState == "in") notifyFinal(context, m)

                if (m.state == "pre" && !preNotified && m.startMillis - now in 0..PRE_NOTICE_MS) {
                    notifyPreMatch(context, m, ((m.startMillis - now) / 60_000L).toInt().coerceAtLeast(1))
                    preNotified = true
                }
            }

            val keys = JSONArray()
            m.details.forEach { keys.put(it.key) }
            updated.put(
                m.id,
                JSONObject().put("state", m.state).put("hs", m.homeScore).put("as", m.awayScore)
                    .put("keys", keys).put("pre", preNotified)
            )
        }
        prefs(context).edit().putString(KEY_SEEN, updated.toString()).apply()

        // Próxima vuelta: cada minuto con un partido en juego; si no, justo antes de que empiece el siguiente.
        val delay = when {
            followed.any { it.state == "in" } -> LIVE_INTERVAL_MS
            else -> {
                val nextStart = followed.filter { it.state == "pre" && it.startMillis > now }.minOfOrNull { it.startMillis }
                if (nextStart == null) MAX_IDLE_MS
                else (nextStart - PRE_NOTICE_MS - 60_000L - now).coerceIn(LIVE_INTERVAL_MS, MAX_IDLE_MS)
            }
        }
        scheduleNext(context, delay)
    }

    private fun loadSeen(context: Context): JSONObject =
        try {
            JSONObject(prefs(context).getString(KEY_SEEN, "{}") ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }

    // ----------------------------------------------------------------- avisos

    private fun score(m: MatchFeed.Match) = "${m.home} ${m.homeScore}-${m.awayScore} ${m.away}"

    private fun notifyPreMatch(c: Context, m: MatchFeed.Match, minutes: Int) =
        notify(c, m, "⏰ Empieza en $minutes min", "${m.home} - ${m.away} · ${m.league}", "pre")

    private fun notifyStart(c: Context, m: MatchFeed.Match) =
        notify(c, m, "🟢 ¡Empieza el partido!", "${m.home} - ${m.away} · ${m.league}", "start")

    private fun notifyGoal(c: Context, m: MatchFeed.Match, goal: MatchFeed.Detail?) {
        val text = if (goal != null) {
            val extra = when {
                goal.ownGoal -> " (en propia)"
                goal.penalty -> " (penalti)"
                else -> ""
            }
            val who = goal.player.ifBlank { goal.team }
            "$who$extra" + (if (goal.minute.isNotBlank()) " · min ${goal.minute}" else "")
        } else {
            m.league + (if (m.clock.isNotBlank()) " · ${m.clock}" else "")
        }
        notify(c, m, "⚽ ¡GOL! ${score(m)}", text, "goal${m.homeScore}-${m.awayScore}")
    }

    private fun notifyGoalDisallowed(c: Context, m: MatchFeed.Match) =
        notify(c, m, "🚫 Gol anulado", "Ahora: ${score(m)}", "var${m.homeScore}-${m.awayScore}")

    private fun notifyRedCard(c: Context, m: MatchFeed.Match, d: MatchFeed.Detail) =
        notify(
            c, m, "🟥 Expulsión: ${m.home} - ${m.away}",
            d.player.ifBlank { d.team } + (if (d.minute.isNotBlank()) " · min ${d.minute}" else ""),
            "red${d.key}"
        )

    private fun notifyFinal(c: Context, m: MatchFeed.Match) =
        notify(c, m, "🏁 Final: ${score(m)}", m.league, "final")

    /** Aviso de prueba (ver el diálogo de avisos de goles). */
    fun sendTest(context: Context) {
        val m = MatchFeed.Match(
            "test", "Prueba", "Equipo A", "Equipo B", 1, 0, "in", "23'", System.currentTimeMillis(), emptyList()
        )
        notify(context, m, "⚽ ¡GOL! ${score(m)}", "Así se verá un aviso · min 23", "test")
    }

    private fun notify(context: Context, m: MatchFeed.Match, title: String, text: String, tag: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.goal_channel_name), NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_HOME, m.home)
            putExtra(EXTRA_AWAY, m.away)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val code = (m.id + tag).hashCode()
        val contentIntent = PendingIntent.getActivity(
            context, code, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            NotificationManagerCompat.from(context).notify(code, notification)
        } catch (e: SecurityException) {
            // Sin permiso de notificaciones: nada más que hacer.
        }
    }

    // ----------------------------------------------------------------- alarma

    private fun pendingIntent(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, GoalAlertReceiver::class.java).apply { action = ACTION_POLL }
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context.applicationContext, 7301, intent, flags)
    }

    fun scheduleNext(context: Context, delayMs: Long) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(app, create = true) ?: return
        val triggerAt = System.currentTimeMillis() + delayMs
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        pendingIntent(app, create = false)?.let { am.cancel(it) }
    }
}

/** Salta con cada alarma de GoalAlerts: hace una vuelta de comprobación en un hilo de fondo. */
class GoalAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        Thread {
            try {
                GoalAlerts.poll(app)
            } catch (e: Exception) {
                // Nunca debe romper nada: se reintenta con la próxima alarma.
            } finally {
                pending.finish()
            }
        }.start()
    }
}

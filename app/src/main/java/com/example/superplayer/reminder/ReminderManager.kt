package com.example.superplayer.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.superplayer.R
import com.example.superplayer.data.EpgRepository
import com.example.superplayer.model.Stream
import com.example.superplayer.model.toJson
import com.example.superplayer.player.PlayerActivity
import com.example.superplayer.player.ShortcutsHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recordatorios de programas de la guía EPG (ver EpgGridActivity.
 * showProgrammeDetails): avisan con una notificación unos minutos antes de
 * que empiece el programa; tocarla abre el canal en el reproductor.
 *
 * Se guardan en SharedPreferences (para poder volver a programarlos al
 * reiniciar el móvil o abrir la app, ver rescheduleAll) y se programan con
 * AlarmManager. Si Android no deja usar alarmas exactas (permiso de
 * "alarmas y recordatorios" en Android 12+), se usa una alarma normal que
 * puede retrasarse unos minutos.
 */
object ReminderManager {
    private const val PREFS_NAME = "reminders"
    private const val KEY_LIST = "list"
    const val ACTION_REMINDER = "com.example.superplayer.REMINDER"
    const val CHANNEL_ID = "epg_reminders"

    /** Minutos de antelación con los que se avisa. */
    const val LEAD_MINUTES = 5
    private const val LEAD_MS = LEAD_MINUTES * 60_000L

    data class Reminder(val streamJson: String, val streamId: String, val channelName: String, val title: String, val startMillis: Long)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<Reminder> {
        val raw = prefs(context).getString(KEY_LIST, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Reminder(
                    o.getString("stream"), o.getString("id"), o.getString("channel"),
                    o.getString("title"), o.getLong("start")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(context: Context, list: List<Reminder>) {
        val arr = JSONArray()
        for (r in list) {
            arr.put(
                JSONObject().put("stream", r.streamJson).put("id", r.streamId)
                    .put("channel", r.channelName).put("title", r.title).put("start", r.startMillis)
            )
        }
        prefs(context).edit().putString(KEY_LIST, arr.toString()).apply()
    }

    fun isSet(context: Context, streamId: String, startMillis: Long): Boolean =
        getAll(context).any { it.streamId == streamId && it.startMillis == startMillis }

    /** Crea el recordatorio y programa su alarma. Devuelve false si el programa ya empezó. */
    fun add(context: Context, stream: Stream, entry: EpgRepository.EpgEntry): Boolean {
        if (entry.startMillis <= System.currentTimeMillis()) return false
        val reminder = Reminder(stream.toJson(), stream.id, stream.name, entry.title, entry.startMillis)
        val list = getAll(context).filterNot { it.streamId == reminder.streamId && it.startMillis == reminder.startMillis }
        write(context, list + reminder)
        schedule(context, reminder)
        return true
    }

    fun remove(context: Context, streamId: String, startMillis: Long) {
        val list = getAll(context)
        val target = list.firstOrNull { it.streamId == streamId && it.startMillis == startMillis } ?: return
        cancel(context, target)
        write(context, list.filter { it !== target })
    }

    /** Vuelve a programar todas las alarmas (tras reiniciar o al abrir la app) y descarta los programas que ya pasaron. */
    fun rescheduleAll(context: Context) {
        val now = System.currentTimeMillis()
        val all = getAll(context)
        val alive = all.filter { it.startMillis > now }
        if (alive.size != all.size) write(context, alive)
        for (r in alive) schedule(context, r)
    }

    private fun pendingIntent(context: Context, r: Reminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            data = Uri.parse("reminder://${Uri.encode(r.streamId)}/${r.startMillis}")
            putExtra("stream", r.streamJson)
            putExtra("channel", r.channelName)
            putExtra("title", r.title)
            putExtra("start", r.startMillis)
        }
        val code = (r.streamId + r.startMillis).hashCode()
        return PendingIntent.getBroadcast(
            context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun schedule(context: Context, r: Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        // 5 minutos antes; si ya falta menos que eso, a la hora exacta de inicio.
        val triggerAt = if (r.startMillis - LEAD_MS > now) r.startMillis - LEAD_MS else r.startMillis
        val pi = pendingIntent(context, r)
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

    private fun cancel(context: Context, r: Reminder) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, r))
    }

    /** Muestra la notificación del aviso (la llama ReminderReceiver al saltar la alarma). */
    fun notifyNow(context: Context, streamJson: String, channel: String, title: String, startMillis: Long) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.reminder_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val open = Intent(context, PlayerActivity::class.java).apply {
            putExtra(ShortcutsHelper.EXTRA_SHORTCUT_STREAM_JSON, streamJson)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val code = (streamJson + startMillis).hashCode()
        val contentIntent = PendingIntent.getActivity(
            context, code, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(startMillis))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(title)
            .setContentText(context.getString(R.string.reminder_notification_text, time, channel))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
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
            // Sin permiso de notificaciones: no hay nada más que hacer.
        }
    }
}

/** Salta la alarma de un recordatorio: muestra la notificación y lo quita de la lista. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderManager.ACTION_REMINDER) return
        val streamJson = intent.getStringExtra("stream") ?: return
        val channel = intent.getStringExtra("channel").orEmpty()
        val title = intent.getStringExtra("title").orEmpty()
        val start = intent.getLongExtra("start", 0L)
        ReminderManager.notifyNow(context, streamJson, channel, title, start)
        val id = try { JSONObject(streamJson).optString("url") } catch (e: Exception) { "" }
        if (id.isNotEmpty()) ReminderManager.remove(context, id, start)
    }
}

/** Tras reiniciar el móvil se pierden las alarmas: se vuelven a programar. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReminderManager.rescheduleAll(context)
    }
}

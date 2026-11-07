package com.example.superplayer.telegram

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.superplayer.R

/**
 * Mantiene vivo el móvil mientras emite un vídeo de Telegram a la tele («Reproducir en TV» desde este móvil):
 * servicio en primer plano con notificación (y un botón para parar), y wifi y CPU despiertas aunque se apague la pantalla.
 * Si se cierra la app deslizándola fuera de las recientes, se para igualmente.
 */
class TvCastService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val name = intent?.getStringExtra(EXTRA_NAME).orEmpty()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.tgtv_notif_channel), NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, TvCastService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.tgtv_notif_title))
            .setContentText(name)
            .setOngoing(true)
            .addAction(0, getString(R.string.tgtv_notif_stop), stop)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        try {
            if (wake == null) {
                wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "socramtv:tvcast").apply { setReferenceCounted(false); acquire(6 * 60 * 60 * 1000L) }
            }
            if (wifi == null) {
                @Suppress("DEPRECATION")
                wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "socramtv:tvcast").apply { setReferenceCounted(false); acquire() }
            }
        } catch (e: Exception) {
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
    }

    override fun onDestroy() {
        try { wake?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        try { wifi?.takeIf { it.isHeld }?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "tv_cast"
        private const val NOTIF_ID = 4711
        private const val ACTION_STOP = "com.example.superplayer.TVCAST_STOP"
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(
                    context, Intent(context, TvCastService::class.java).putExtra(EXTRA_NAME, name)
                )
            } catch (e: Exception) {
            }
        }
    }
}

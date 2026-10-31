package com.example.superplayer.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Temporizador de apagado. Vive en el proceso (no en la pantalla del
 * reproductor) para que siga contando con la pantalla bloqueada o la app en
 * segundo plano: al llegar a cero, PlaybackService para la reproducción
 * (serviceCallback) y, si la pantalla del reproductor está abierta, se
 * cierra (activityListener).
 */
object SleepTimer {
    private val handler = Handler(Looper.getMainLooper())
    private var endAtElapsed = 0L

    /** Lo registra PlaybackService: para la reproducción. */
    var serviceCallback: (() -> Unit)? = null

    /** Lo registra PlayerActivity mientras está visible: cierra la pantalla. */
    var activityListener: (() -> Unit)? = null

    private val fire = Runnable {
        endAtElapsed = 0L
        serviceCallback?.invoke()
        activityListener?.invoke()
    }

    fun start(minutes: Int) {
        handler.removeCallbacks(fire)
        endAtElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L
        handler.postDelayed(fire, minutes * 60_000L)
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        endAtElapsed = 0L
    }

    fun isActive(): Boolean = endAtElapsed != 0L

    /** Minutos que quedan, redondeados hacia arriba (0 si no hay temporizador). */
    fun remainingMinutes(): Int {
        if (endAtElapsed == 0L) return 0
        val ms = (endAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        return ((ms + 59_999L) / 60_000L).toInt()
    }
}

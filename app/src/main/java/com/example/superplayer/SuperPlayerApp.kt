package com.example.superplayer

import android.app.Application
import android.content.Intent
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import com.example.superplayer.data.AppPrefs
import kotlin.system.exitProcess

/**
 * Si algo lanza una excepción no controlada en cualquier punto de la app,
 * en vez de simplemente "cerrarse sola" (que es lo que hace Android por
 * defecto y no deja rastro visible en el teléfono), abrimos una pantalla
 * sencilla con el motivo exacto para poder hacerle una captura y arreglarlo.
 */
class SuperPlayerApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Modo claro/oscuro (ver MainActivity.toggleTheme y
        // Storage.kt/AppPrefs.isDarkMode): hay que fijarlo aquí, antes de
        // crear ninguna pantalla, para que la app arranque ya en el modo
        // guardado desde el primer fotograma, sin un parpadeo del modo por
        // defecto antes de cambiar al elegido.
        AppCompatDelegate.setDefaultNightMode(
            if (AppPrefs.isDarkMode(this)) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val trace = throwable.stackTraceToString()
                val intent = Intent(this, CrashActivity::class.java).apply {
                    putExtra(CrashActivity.EXTRA_TRACE, trace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(intent)
            } catch (inner: Throwable) {
                previousHandler?.uncaughtException(thread, throwable)
            }
            Process.killProcess(Process.myPid())
            exitProcess(1)
        }
    }
}

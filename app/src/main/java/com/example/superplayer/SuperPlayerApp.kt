package com.example.superplayer

import android.app.Application
import android.content.Intent
import android.os.Process
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

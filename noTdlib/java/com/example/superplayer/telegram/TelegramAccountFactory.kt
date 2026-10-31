package com.example.superplayer.telegram

import android.content.Context

/** Versión sin TDLib (la librería no está incluida en esta compilación). */
object TelegramAccountFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context): TgAccountBackend? = null
}

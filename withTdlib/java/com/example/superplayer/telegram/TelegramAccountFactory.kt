package com.example.superplayer.telegram

import android.content.Context

/** Versión con TDLib incluida. */
object TelegramAccountFactory {
    fun create(context: Context): TgAccountBackend? = TdBackend.get(context)
}

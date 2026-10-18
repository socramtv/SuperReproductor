package com.example.superplayer.data

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan

/**
 * Muchas guías EPG (las pensadas para Kodi) traen etiquetas de formato dentro de los textos:
 * [COLOR teal]…[/COLOR], [B]…[/B], [I]…[/I], [CR] (salto de línea), [UPPERCASE]…
 * [spanned] las convierte en colores y negritas de verdad; [strip] las quita (para títulos y listas).
 * Solo se tocan las etiquetas conocidas: cosas como «[TP]» o «[16]» (edad) se dejan tal cual.
 */
object EpgFormat {
    private val TAG = Regex("\\[(/?)(COLOR|B|I|CR|UPPERCASE|LOWERCASE|CAPITALIZE|LIGHT)(?:\\s+([^\\]]*))?\\]", RegexOption.IGNORE_CASE)

    private val NAMED = mapOf(
        "aqua" to 0x00FFFF, "black" to 0x000000, "blue" to 0x0000FF, "brown" to 0xA52A2A, "coral" to 0xFF7F50,
        "crimson" to 0xDC143C, "cyan" to 0x00FFFF, "darkblue" to 0x00008B, "darkcyan" to 0x008B8B,
        "darkgray" to 0xA9A9A9, "darkgrey" to 0xA9A9A9, "darkgreen" to 0x006400, "darkorange" to 0xFF8C00,
        "darkred" to 0x8B0000, "deeppink" to 0xFF1493, "deepskyblue" to 0x00BFFF, "dodgerblue" to 0x1E90FF,
        "firebrick" to 0xB22222, "forestgreen" to 0x228B22, "fuchsia" to 0xFF00FF, "gold" to 0xFFD700,
        "goldenrod" to 0xDAA520, "gray" to 0x808080, "grey" to 0x808080, "green" to 0x008000,
        "greenyellow" to 0xADFF2F, "hotpink" to 0xFF69B4, "indigo" to 0x4B0082, "khaki" to 0xF0E68C,
        "lavender" to 0xE6E6FA, "lightblue" to 0xADD8E6, "lightgreen" to 0x90EE90, "lightgray" to 0xD3D3D3,
        "lightgrey" to 0xD3D3D3, "lightpink" to 0xFFB6C1, "lightyellow" to 0xFFFFE0, "lime" to 0x00FF00,
        "limegreen" to 0x32CD32, "magenta" to 0xFF00FF, "maroon" to 0x800000, "mediumpurple" to 0x9370DB,
        "mediumseagreen" to 0x3CB371, "navy" to 0x000080, "olive" to 0x808000, "orange" to 0xFFA500,
        "orangered" to 0xFF4500, "orchid" to 0xDA70D6, "pink" to 0xFFC0CB, "plum" to 0xDDA0DD,
        "purple" to 0x800080, "red" to 0xFF0000, "royalblue" to 0x4169E1, "salmon" to 0xFA8072,
        "seagreen" to 0x2E8B57, "silver" to 0xC0C0C0, "skyblue" to 0x87CEEB, "slateblue" to 0x6A5ACD,
        "slategray" to 0x708090, "slategrey" to 0x708090, "springgreen" to 0x00FF7F, "steelblue" to 0x4682B4,
        "tan" to 0xD2B48C, "teal" to 0x008080, "tomato" to 0xFF6347, "turquoise" to 0x40E0D0,
        "violet" to 0xEE82EE, "white" to 0xFFFFFF, "yellow" to 0xFFFF00, "yellowgreen" to 0x9ACD32
    )

    /** Nombre CSS, "#RRGGBB" o "AARRGGBB"/"RRGGBB" de Kodi -> color ARGB, o null si no se entiende. */
    private fun parseColor(arg: String?): Int? {
        val a = arg?.trim()?.trim('"', '\'')?.lowercase() ?: return null
        if (a.isEmpty()) return null
        NAMED[a]?.let { return 0xFF000000.toInt() or it }
        val hex = a.removePrefix("#")
        if (hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            try {
                if (hex.length == 6) return 0xFF000000.toInt() or hex.toLong(16).toInt()
                if (hex.length == 8) return 0xFF000000.toInt() or (hex.toLong(16).toInt() and 0x00FFFFFF)
            } catch (e: Exception) { }
        }
        return try { Color.parseColor(if (a.startsWith("#")) a else "#$a") or 0xFF000000.toInt() } catch (e: Exception) { null }
    }

    /** Aclara/oscurece los colores que no se leerían sobre el fondo (azul marino sobre negro, amarillo sobre blanco…). */
    private fun readable(color: Int, darkBg: Boolean): Int {
        val lum = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        val target = when {
            darkBg && lum < 0.35 -> 0xFFFFFFFF.toInt()
            !darkBg && lum > 0.75 -> 0xFF000000.toInt()
            else -> return color
        }
        val k = 0.5f
        return Color.rgb(
            (Color.red(color) * (1 - k) + Color.red(target) * k).toInt(),
            (Color.green(color) * (1 - k) + Color.green(target) * k).toInt(),
            (Color.blue(color) * (1 - k) + Color.blue(target) * k).toInt()
        )
    }

    /** Quita las etiquetas ([CR] pasa a espacio); texto plano para títulos, categorías y comparaciones. */
    fun strip(raw: String): String {
        if (raw.indexOf('[') < 0) return raw
        return TAG.replace(raw) { m -> if (m.groupValues[2].equals("CR", true)) " " else "" }
            .replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    /** Texto con las etiquetas aplicadas (colores, negrita, cursiva, saltos de línea). */
    fun spanned(raw: String, darkBg: Boolean): CharSequence {
        if (raw.indexOf('[') < 0) return raw
        val out = SpannableStringBuilder()
        class Open(val kind: String, val start: Int, val color: Int?)
        val stack = ArrayList<Open>()
        var upper = 0
        var lower = 0
        fun append(text: String) {
            if (text.isEmpty()) return
            out.append(if (upper > 0) text.uppercase() else if (lower > 0) text.lowercase() else text)
        }
        fun close(kind: String) {
            val i = stack.indexOfLast { it.kind == kind }
            if (i < 0) return
            val o = stack.removeAt(i)
            if (out.length <= o.start) return
            when (kind) {
                "COLOR" -> o.color?.let { out.setSpan(ForegroundColorSpan(it), o.start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                "B" -> out.setSpan(StyleSpan(Typeface.BOLD), o.start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                "I" -> out.setSpan(StyleSpan(Typeface.ITALIC), o.start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (kind == "UPPERCASE") upper--
            if (kind == "LOWERCASE") lower--
        }
        var last = 0
        for (m in TAG.findAll(raw)) {
            append(raw.substring(last, m.range.first))
            last = m.range.last + 1
            val closing = m.groupValues[1] == "/"
            val kind = m.groupValues[2].uppercase()
            if (kind == "CR") { if (!closing) out.append('\n'); continue }
            if (kind == "CAPITALIZE" || kind == "LIGHT") continue
            if (closing) { close(kind); continue }
            if (kind == "UPPERCASE") upper++
            if (kind == "LOWERCASE") lower++
            val color = if (kind == "COLOR") parseColor(m.groupValues[3])?.let { readable(it, darkBg) } else null
            stack.add(Open(kind, out.length, color))
        }
        append(raw.substring(last))
        for (k in stack.map { it.kind }.reversed()) close(k)
        return out
    }
}

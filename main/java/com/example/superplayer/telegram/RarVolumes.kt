package com.example.superplayer.telegram

import java.io.ByteArrayOutputStream

/** Un trozo del vídeo dentro de una parte del RAR: [length] bytes que empiezan en [offset] del archivo [fileId]; [start] = posición en el vídeo completo. */
class RarSegment(val fileId: Int, val offset: Long, val length: Long, val start: Long)

/** Vídeo repartido en varias partes de un RAR, visto como un único archivo de [total] bytes. */
class RarVideo(val name: String, val ext: String, val segments: List<RarSegment>, val total: Long)

/**
 * Soporte de vídeos en RAR por partes (nombre.part1.rar, nombre.part2.rar… o .rar, .r00, .r01…).
 *
 * Solo RAR SIN compresión (modo «almacenar», lo normal en vídeos) y sin contraseña: en ese caso los
 * bytes del vídeo están tal cual dentro de cada parte, justo después de la cabecera, y basta
 * con leer las cabeceras y unir los trozos. Es RAR4 y RAR5. Una sola película por archivo.
 */
object RarVolumes {
    private val rePart = Regex("(?i)^(.*)\\.part0*(\\d+)\\.rar$")
    private val reRar = Regex("(?i)^(.*)\\.rar$")
    private val reOld = Regex("(?i)^(.*)\\.r(\\d{2,3})$")
    private val videoExts = setOf("mp4", "mkv", "avi", "ts", "mov", "webm", "m4v", "wmv", "flv", "mpg", "mpeg", "m2ts")

    sealed class Result {
        class Ok(val video: RarVideo) : Result()
        /** [code]: "read", "encrypted", "compressed", "novideo". */
        class Fail(val code: String) : Result()
    }

    fun isRar(name: String?): Boolean =
        name != null && (rePart.matches(name) || reRar.matches(name) || reOld.matches(name))

    /** Nombre sin «.partN.rar» / «.rar» / «.rNN»: igual en todas las partes. */
    fun baseName(name: String): String =
        rePart.find(name)?.groupValues?.get(1)
            ?: reRar.find(name)?.groupValues?.get(1)
            ?: reOld.find(name)?.groupValues?.get(1)
            ?: name

    /** Orden de la parte (part1 = 1; en el formato antiguo .rar = 0, .r00 = 1, .r01 = 2…). */
    fun index(name: String): Int {
        val p = rePart.find(name)
        if (p != null) return p.groupValues[2].toIntOrNull() ?: 0
        if (reRar.matches(name)) return 0
        val o = reOld.find(name)
        return (o?.groupValues?.get(2)?.toIntOrNull() ?: -1) + 1
    }

    fun mimeFor(ext: String): String = when (ext.lowercase()) {
        "mkv" -> "video/x-matroska"
        "avi" -> "video/x-msvideo"
        "ts", "m2ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> "video/mp4"
    }

    private class Entry(val name: String, val method: Int, val encrypted: Boolean, val dataOffset: Long, val dataSize: Long)

    private class Cur(val b: ByteArray) {
        var p = 0
        fun u8(): Int {
            if (p >= b.size) throw IndexOutOfBoundsException()
            return b[p++].toInt() and 0xFF
        }
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        fun vint(): Long {
            var r = 0L
            var shift = 0
            while (true) {
                val x = u8()
                r = r or ((x and 0x7F).toLong() shl shift)
                if (x and 0x80 == 0) return r
                shift += 7
                if (shift > 63) throw IndexOutOfBoundsException()
            }
        }
    }

    private fun hasPrefix(buf: ByteArray, sig: IntArray): Boolean =
        buf.size >= sig.size && sig.indices.all { (buf[it].toInt() and 0xFF) == sig[it] }

    /** Cabecera de la primera entrada (el vídeo) de una parte, o un código de error (String). */
    private fun parseHead(buf: ByteArray): Any {
        val rar5 = hasPrefix(buf, intArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00))
        val rar4 = !rar5 && hasPrefix(buf, intArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00))
        if (!rar5 && !rar4) return "read"
        return try {
            if (rar5) parseRar5(buf) else parseRar4(buf)
        } catch (e: IndexOutOfBoundsException) {
            "read"
        }
    }

    private fun parseRar5(buf: ByteArray): Any {
        val c = Cur(buf)
        c.p = 8
        while (true) {
            c.p += 4 // CRC
            val hsize = c.vint()
            val hstart = c.p
            val hend = hstart + hsize
            val type = c.vint()
            val flags = c.vint()
            val extraSize = if ((flags and 1L) != 0L) c.vint() else 0L
            val dsize = if ((flags and 2L) != 0L) c.vint() else 0L
            when (type) {
                4L -> return "encrypted"
                5L -> return "novideo"
                2L -> {
                    val ff = c.vint()
                    c.vint() // tamaño sin comprimir
                    c.vint() // atributos
                    if ((ff and 2L) != 0L) c.p += 4
                    if ((ff and 4L) != 0L) c.p += 4
                    val ci = c.vint()
                    c.vint() // sistema
                    val nl = c.vint().toInt()
                    if (c.p + nl > buf.size) return "read"
                    val name = String(buf, c.p, nl, Charsets.UTF_8)
                    if ((ff and 1L) == 0L) {
                        // ¿Cifrado? Registro «1» en el área extra de la cabecera.
                        var enc = false
                        val ec = Cur(buf)
                        ec.p = (hend - extraSize).toInt()
                        while (extraSize > 0 && ec.p < hend && ec.p < buf.size) {
                            val rs = ec.vint()
                            val next = ec.p + rs
                            if (ec.vint() == 1L) enc = true
                            ec.p = next.toInt()
                        }
                        return Entry(name, ((ci shr 7) and 7L).toInt(), enc, hend, dsize)
                    }
                }
            }
            val next = hend + dsize
            if (next >= buf.size) return "read"
            c.p = next.toInt()
        }
    }

    private fun parseRar4(buf: ByteArray): Any {
        val c = Cur(buf)
        c.p = 7
        while (true) {
            val start = c.p
            c.p += 2 // CRC
            val type = c.u8()
            val flags = c.u16()
            val hsize = c.u16()
            if (type == 0x73 && (flags and 0x80) != 0) return "encrypted"
            if (type == 0x7B) return "novideo"
            var add = 0L
            if (type == 0x74) {
                val packLow = c.u32()
                c.u32() // tamaño sin comprimir
                c.u8()  // sistema
                c.u32() // CRC
                c.u32() // fecha
                c.u8()  // versión
                val method = c.u8()
                val nl = c.u16()
                c.u32() // atributos
                var pack = packLow
                if ((flags and 0x100) != 0) {
                    val hp = c.u32()
                    c.u32()
                    pack = packLow or (hp shl 32)
                }
                if (c.p + nl > buf.size) return "read"
                var nameBytes = buf.copyOfRange(c.p, c.p + nl)
                val z = nameBytes.indexOf(0.toByte())
                if (z >= 0) nameBytes = nameBytes.copyOfRange(0, z)
                val name = String(nameBytes, Charsets.UTF_8)
                val dir = ((flags shr 5) and 7) == 7
                if (!dir) {
                    return Entry(name, method - 0x30, (flags and 0x04) != 0, (start + hsize).toLong(), pack)
                }
                add = pack
            } else if ((flags and 0x8000) != 0) {
                c.p = start + 7
                add = c.u32()
            }
            val next = start + hsize + add
            if (next >= buf.size) return "read"
            c.p = next.toInt()
        }
    }

    /** Lee hasta [len] bytes del principio de un archivo de Telegram (pide solo ese trozo). */
    private fun readHead(b: TgAccountBackend, fileId: Int, len: Int): ByteArray? {
        val out = ByteArrayOutputStream()
        var pos = 0L
        while (out.size() < len) {
            val chunk = b.readBytes(fileId, pos, len - out.size()) ?: break
            if (chunk.isEmpty()) break
            out.write(chunk)
            pos += chunk.size
        }
        return if (out.size() > 0) out.toByteArray() else null
    }

    /**
     * Lee la cabecera de cada parte (en orden) y arma el vídeo. Bloquea: llamar desde un hilo de fondo.
     * [parts]: (id del archivo, tamaño) de cada parte, ya ordenadas.
     */
    fun build(b: TgAccountBackend, parts: List<Pair<Int, Long>>): Result {
        var name = ""
        var total = 0L
        val segs = ArrayList<RarSegment>()
        for ((i, p) in parts.withIndex()) {
            val head = readHead(b, p.first, 8192) ?: return Result.Fail("read")
            val r = parseHead(head)
            if (r is String) return Result.Fail(r)
            val e = r as Entry
            if (e.encrypted) return Result.Fail("encrypted")
            if (e.method != 0) return Result.Fail("compressed")
            if (i == 0) name = e.name.substringAfterLast('/').substringAfterLast('\\')
            val len = minOf(e.dataSize, p.second - e.dataOffset).coerceAtLeast(0L)
            segs.add(RarSegment(p.first, e.dataOffset, len, total))
            total += len
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in videoExts || total <= 0) return Result.Fail("novideo")
        return Result.Ok(RarVideo(name, ext, segs, total))
    }
}

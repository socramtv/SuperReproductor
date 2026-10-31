package com.example.superplayer.telegram

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/**
 * Álbumes de música en un archivo comprimido (ZIP, o RAR sin compresión) con las pistas dentro (mp3, flac, m4a…).
 * Se saca la música del archivo ya descargado a la caché de la app y se devuelve como [Album], que abre la pantalla
 * de música (ver ui/MusicPlayerActivity). Los archivos con contraseña o los RAR comprimidos no se pueden leer.
 */
object MusicArchive {
    val audioExts = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav")
    private val imageExts = setOf("jpg", "jpeg", "png", "webp")

    class Track(val title: String, val path: String)
    class Album(val name: String, val tracks: List<Track>, val cover: String?)

    sealed class Result {
        class Ok(val album: Album) : Result()
        /** [code]: "read", "encrypted", "compressed", "multipart", "nomusic", "cancel". */
        class Fail(val code: String) : Result()
    }

    fun isZip(name: String?) = name != null && name.lowercase().endsWith(".zip")

    private fun extOf(name: String) = name.substringAfterLast('.', "").lowercase()

    /** "03 - Mi canción_final.mp3" -> "Mi canción final". */
    fun titleOf(entryName: String): String {
        val base = entryName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
        val cleaned = base.replace(Regex("^\\s*\\d{1,3}\\s*[-._)\\]]*\\s*"), "").replace('_', ' ').trim()
        return cleaned.ifEmpty { base.replace('_', ' ').trim() }
    }

    private val natural = Comparator<String> { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        var i = 0
        while (i < ra.size && i < rb.size) {
            val x = ra[i]
            val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val d = x.trimStart('0').length.compareTo(y.trimStart('0').length)
                if (d != 0) d else x.trimStart('0').compareTo(y.trimStart('0'))
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
            i++
        }
        ra.size.compareTo(rb.size)
    }

    private fun isNoise(name: String): Boolean {
        val base = name.substringAfterLast('/')
        return name.startsWith("__MACOSX") || base.startsWith("._")
    }

    /** La mejor imagen para usar de portada: la que se llame cover/folder/front…, si no la primera. */
    private fun pickCover(names: List<String>): String? {
        val images = names.filter { extOf(it) in imageExts && !isNoise(it) }
        return images.firstOrNull { Regex("(?i)(cover|folder|front|portada|caratula|album)").containsMatchIn(it.substringAfterLast('/')) }
            ?: images.firstOrNull()
    }

    // ---- Caché ----

    /** Un álbum ya sacado antes (no hace falta volver a descargar el archivo). */
    fun cached(dir: File): Album? {
        return try {
            val f = File(dir, "album.json")
            if (!f.exists()) return null
            val o = JSONObject(f.readText())
            val arr = o.getJSONArray("tracks")
            val tracks = (0 until arr.length()).map {
                val t = arr.getJSONObject(it)
                Track(t.getString("title"), File(dir, t.getString("file")).absolutePath)
            }.filter { File(it.path).exists() }
            if (tracks.isEmpty()) null
            else Album(o.optString("name"), tracks, o.optString("cover").ifEmpty { null }?.let { File(dir, it).absolutePath })
        } catch (e: Exception) {
            null
        }
    }

    private fun writeIndex(dir: File, album: Album) {
        val arr = JSONArray()
        for (t in album.tracks) arr.put(JSONObject().put("title", t.title).put("file", File(t.path).name))
        val o = JSONObject().put("name", album.name).put("tracks", arr)
        album.cover?.let { o.put("cover", File(it).name) }
        File(dir, "album.json").writeText(o.toString())
    }

    /** Deja solo los [keep] álbumes más recientes de la caché. */
    fun prune(root: File, keep: Int) {
        try {
            val dirs = root.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.lastModified() } ?: return
            for (d in dirs.drop(keep)) d.deleteRecursively()
        } catch (e: Exception) { }
    }

    // ---- Extraer ----

    /**
     * Saca las pistas (y la portada) de [archive] a [outDir]. Bloquea: llamar desde un hilo de fondo.
     * [progress] (hecho, total); [cancelled] para abortar.
     */
    fun extract(archive: File, outDir: File, albumName: String, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): Result {
        return try {
            outDir.deleteRecursively()
            outDir.mkdirs()
            val magic = ByteArray(4)
            RandomAccessFile(archive, "r").use { it.readFully(magic) }
            val r = if (magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) extractZip(archive, outDir, albumName, progress, cancelled)
            else if (magic[0] == 'R'.code.toByte() && magic[1] == 'a'.code.toByte() && magic[2] == 'r'.code.toByte()) extractRar(archive, outDir, albumName, progress, cancelled)
            else Result.Fail("read")
            if (r is Result.Ok) writeIndex(outDir, r.album) else outDir.deleteRecursively()
            r
        } catch (e: Exception) {
            outDir.deleteRecursively()
            Result.Fail("read")
        }
    }

    private fun copyTo(input: java.io.InputStream, out: File) {
        out.outputStream().buffered().use { o -> input.copyTo(o, 64 * 1024) }
    }

    private fun extractZip(archive: File, outDir: File, albumName: String, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): Result {
        ZipFile(archive).use { zf ->
            val entries = zf.entries().toList().filter { !it.isDirectory && !isNoise(it.name) }
            val audio = entries.filter { extOf(it.name) in audioExts }.sortedWith { a, b -> natural.compare(a.name, b.name) }
            if (audio.isEmpty()) return Result.Fail("nomusic")
            val coverName = pickCover(entries.map { it.name })
            val total = audio.size + 1
            val tracks = ArrayList<Track>()
            for ((i, e) in audio.withIndex()) {
                if (cancelled()) return Result.Fail("cancel")
                val f = File(outDir, String.format("%03d.%s", i + 1, extOf(e.name)))
                copyTo(zf.getInputStream(e), f)
                tracks.add(Track(titleOf(e.name), f.absolutePath))
                progress(i + 1, total)
            }
            var cover: String? = null
            if (coverName != null) {
                try {
                    val e = zf.getEntry(coverName)
                    if (e != null && e.size < 15_000_000) {
                        val f = File(outDir, "cover." + extOf(coverName))
                        copyTo(zf.getInputStream(e), f)
                        cover = f.absolutePath
                    }
                } catch (ex: Exception) { }
            }
            progress(total, total)
            return Result.Ok(Album(albumName, tracks, cover))
        }
    }

    // ---- RAR (solo sin compresión) ----

    private class RarEntry(val name: String, val method: Int, val dataOffset: Long, val dataSize: Long)

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

    /** Todas las entradas de un RAR de un solo volumen, o un código de error (String). */
    private fun listRar(raf: RandomAccessFile): Any {
        val len = raf.length()
        if (len < 20) return "read"
        val sig = ByteArray(8)
        raf.seek(0)
        raf.readFully(sig)
        val rar5 = sig[6].toInt() == 1 && sig[7].toInt() == 0
        var pos = if (rar5) 8L else 7L
        val out = ArrayList<RarEntry>()
        while (pos < len) {
            val n = minOf(70_000L, len - pos).toInt()
            val buf = ByteArray(n)
            raf.seek(pos)
            raf.readFully(buf)
            try {
                val c = Cur(buf)
                if (rar5) {
                    c.p += 4
                    val hsize = c.vint()
                    val hend = c.p + hsize
                    if (hend > n) return if (out.isEmpty()) "read" else out
                    val type = c.vint()
                    val flags = c.vint()
                    val extraSize = if ((flags and 1L) != 0L) c.vint() else 0L
                    val dsize = if ((flags and 2L) != 0L) c.vint() else 0L
                    if (type == 4L) return "encrypted"
                    if (type == 5L) break
                    if (type == 2L) {
                        if ((flags and 0x18L) != 0L) return "multipart"
                        val ff = c.vint()
                        c.vint()
                        c.vint()
                        if ((ff and 2L) != 0L) c.p += 4
                        if ((ff and 4L) != 0L) c.p += 4
                        val ci = c.vint()
                        c.vint()
                        val nl = c.vint().toInt()
                        if (c.p + nl > n) return "read"
                        val name = String(buf, c.p, nl, Charsets.UTF_8)
                        if ((ff and 1L) == 0L) {
                            var enc = false
                            val ec = Cur(buf)
                            ec.p = (hend - extraSize).toInt()
                            while (extraSize > 0 && ec.p < hend && ec.p < n) {
                                val rs = ec.vint()
                                val next = ec.p + rs
                                if (ec.vint() == 1L) enc = true
                                ec.p = next.toInt()
                            }
                            if (enc) return "encrypted"
                            out.add(RarEntry(name, ((ci shr 7) and 7L).toInt(), pos + hend, dsize))
                        }
                    }
                    pos += hend + dsize
                } else {
                    c.p += 2
                    val type = c.u8()
                    val flags = c.u16()
                    val hsize = c.u16()
                    if (type == 0x73 && (flags and 0x80) != 0) return "encrypted"
                    if (type == 0x7B) break
                    var add = 0L
                    if (type == 0x74) {
                        val packLow = c.u32()
                        c.u32()
                        c.u8()
                        c.u32()
                        c.u32()
                        c.u8()
                        val method = c.u8()
                        val nl = c.u16()
                        c.u32()
                        var pack = packLow
                        if ((flags and 0x100) != 0) {
                            val hp = c.u32()
                            c.u32()
                            pack = packLow or (hp shl 32)
                        }
                        if (c.p + nl > n) return "read"
                        var nameBytes = buf.copyOfRange(c.p, c.p + nl)
                        val z = nameBytes.indexOf(0.toByte())
                        if (z >= 0) nameBytes = nameBytes.copyOfRange(0, z)
                        val name = String(nameBytes, Charsets.UTF_8)
                        if ((flags and 0x03) != 0) return "multipart"
                        if ((flags and 0x04) != 0) return "encrypted"
                        val dir = ((flags shr 5) and 7) == 7
                        if (!dir) out.add(RarEntry(name, method - 0x30, pos + hsize, pack))
                        add = pack
                    } else if ((flags and 0x8000) != 0) {
                        c.p = 7
                        add = c.u32()
                    }
                    pos += hsize + add
                }
            } catch (e: IndexOutOfBoundsException) {
                return if (out.isEmpty()) "read" else out
            }
        }
        return out
    }

    private fun extractRar(archive: File, outDir: File, albumName: String, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): Result {
        RandomAccessFile(archive, "r").use { raf ->
            val listed = listRar(raf)
            if (listed is String) return Result.Fail(listed)
            @Suppress("UNCHECKED_CAST")
            val entries = (listed as List<RarEntry>).filter { !isNoise(it.name) }
            val audio = entries.filter { extOf(it.name) in audioExts }.sortedWith { a, b -> natural.compare(a.name, b.name) }
            if (audio.isEmpty()) return Result.Fail("nomusic")
            if (audio.any { it.method != 0 }) return Result.Fail("compressed")
            val coverEntry = pickCover(entries.map { it.name })?.let { n -> entries.firstOrNull { it.name == n && it.method == 0 } }
            fun copyEntry(e: RarEntry, f: File) {
                raf.seek(e.dataOffset)
                f.outputStream().buffered().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var left = e.dataSize
                    while (left > 0) {
                        val r = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (r < 0) break
                        o.write(buf, 0, r)
                        left -= r
                    }
                }
            }
            val total = audio.size + 1
            val tracks = ArrayList<Track>()
            for ((i, e) in audio.withIndex()) {
                if (cancelled()) return Result.Fail("cancel")
                val f = File(outDir, String.format("%03d.%s", i + 1, extOf(e.name)))
                copyEntry(e, f)
                tracks.add(Track(titleOf(e.name), f.absolutePath))
                progress(i + 1, total)
            }
            var cover: String? = null
            if (coverEntry != null && coverEntry.dataSize < 15_000_000) {
                try {
                    val f = File(outDir, "cover." + extOf(coverEntry.name))
                    copyEntry(coverEntry, f)
                    cover = f.absolutePath
                } catch (e: Exception) { }
            }
            progress(total, total)
            return Result.Ok(Album(albumName, tracks, cover))
        }
    }
}

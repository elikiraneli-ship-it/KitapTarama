package tr.alihoca.turku.yeni.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.max
import kotlin.math.min

/** Onarım sonucu: [log] yalnızca yapılan düzeltmeleri içerir (sağlam dosyada boştur). */
class Repaired(val bytes: ByteArray, val log: List<String>, val changed: Boolean)

object ImageSources {
    val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "tif", "tiff")
    const val PDF_EXT = "pdf"
    private const val MAX_SIDE = 4200
    const val PDF_RENDER_WIDTH = PageCleaner.A4_W

    fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isSupported(name: String) = ext(name) in IMAGE_EXT || ext(name) == PDF_EXT

    /** "1 (2)" dosyası "1 (10)" dosyasından önce gelsin diye doğal sıralama. */
    fun natural(a: String, b: String): Int {
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                val nx = x.trimStart('0'); val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size - rb.size
    }

    /** JPEG/PNG/WEBP/BMP/GIF/HEIC baytlarını (EXIF yönüyle) çözer; büyük görselleri küçültür. */
    fun decodeBytes(b: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, bounds)
        if (bounds.outWidth <= 0) return null
        var s = 1
        val m = max(bounds.outWidth, bounds.outHeight)
        while (m / s > MAX_SIDE) s *= 2
        val bmp = BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val o = try {
            ExifInterface(ByteArrayInputStream(b)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) { ExifInterface.ORIENTATION_NORMAL }
        return rotateByExif(o, bmp)
    }

    private fun rotateByExif(orientation: Int, bmp: Bitmap): Bitmap {
        val deg = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        if (r !== bmp) bmp.recycle()
        return r
    }

    fun renderPdfPage(r: PdfRenderer, index: Int): Bitmap {
        r.openPage(index).use { page ->
            val scale = PDF_RENDER_WIDTH / page.width.toFloat()
            val bmp = Bitmap.createBitmap(PDF_RENDER_WIDTH, (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bmp
        }
    }
}

/** Başlık ve sonluk baytlarını ikili düzeyde onarır: JPEG (SOI ve EOI), TIFF (II ve MM başlığı), PDF (%PDF- ve %%EOF). */
object FileRepair {
    private val FF = 0xFF.toByte()
    private val D8 = 0xD8.toByte()
    private val D9 = 0xD9.toByte()

    fun detect(b: ByteArray, name: String): String? {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        if (at(0) == 0xFF && at(1) == 0xD8) return "jpg"
        if (at(0) == 0x89 && at(1) == 0x50) return "png"
        if (at(0) == 0x25 && at(1) == 0x50 && at(2) == 0x44) return "pdf"
        if ((at(0) == 0x49 && at(1) == 0x49 && at(2) == 0x2A) || (at(0) == 0x4D && at(1) == 0x4D && at(3) == 0x2A)) return "tif"
        return when (ImageSources.ext(name)) {
            "jpg", "jpeg" -> "jpg"
            "png" -> "png"
            "tif", "tiff" -> "tif"
            "pdf" -> "pdf"
            "webp", "bmp", "gif", "heic", "heif" -> "other"
            else -> null
        }
    }

    private fun indexOf(b: ByteArray, sig: ByteArray, from: Int = 0, to: Int = b.size): Int {
        val end = min(to, b.size) - sig.size
        var i = from
        while (i <= end) {
            var j = 0
            while (j < sig.size && b[i + j] == sig[j]) j++
            if (j == sig.size) return i
            i++
        }
        return -1
    }

    fun repairJpeg(input: ByteArray): Repaired {
        var b = input
        val log = ArrayList<String>()
        if (!(b.size > 1 && b[0] == FF && b[1] == D8)) {
            val i = indexOf(b, byteArrayOf(FF, D8, FF), 0, 65536)
            if (i > 0) { b = b.copyOfRange(i, b.size); log.add("SOI (FFD8) öncesindeki $i bayt çöp kesildi") }
            else { b = byteArrayOf(FF, D8) + b; log.add("SOI (FFD8) yoktu, başa eklendi") }
        }
        var e = b.size
        while (e > 2 && b[e - 1] == 0.toByte()) e--
        if (b.size > 3 && b[e - 2] == FF && b[e - 1] == D9) {
            if (e < b.size) { log.add("EOI sonrası ${b.size - e} null bayt temizlendi"); b = b.copyOfRange(0, e) }
        } else {
            var l = -1
            var i = b.size - 2
            val lo = max(2, b.size - 4096)
            while (i >= lo) { if (b[i] == FF && b[i + 1] == D9) { l = i; break }; i-- }
            if (l > 0) { b = b.copyOfRange(0, l + 2); log.add("EOI (FFD9) sonrası artık baytlar kesildi") }
            else { b = b.copyOfRange(0, e) + byteArrayOf(FF, D9); log.add("EOI (FFD9) eksikti, sona eklendi") }
        }
        return Repaired(b, log, log.isNotEmpty())
    }

    fun repairTiff(input: ByteArray): Repaired {
        var b = input
        val log = ArrayList<String>()
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 0xFF else -1
        val ok = (at(0) == 0x49 && at(1) == 0x49 && at(2) == 0x2A && at(3) == 0) ||
            (at(0) == 0x4D && at(1) == 0x4D && at(2) == 0 && at(3) == 0x2A)
        if (!ok) {
            val i = indexOf(b, byteArrayOf(0x49, 0x49, 0x2A, 0), 0, 4096)
            val j = indexOf(b, byteArrayOf(0x4D, 0x4D, 0, 0x2A), 0, 4096)
            val f = listOf(i, j).filter { it > 0 }.minOrNull()
            if (f != null) { b = b.copyOfRange(f, b.size); log.add("TIFF başlığı $f. bayta kadar çöp kesildi") }
            else if ((at(0) == 0x49 && at(1) == 0x49) || (at(0) == 0x4D && at(1) == 0x4D)) {
                val le = at(0) == 0x49
                b = b.copyOf()
                b[2] = if (le) 42 else 0
                b[3] = if (le) 0 else 42
                log.add("Bozuk TIFF sihirli sayısı (42) düzeltildi")
            } else {
                b = byteArrayOf(0x49, 0x49, 0x2A, 0, 8, 0, 0, 0) + b
                log.add("TIFF başlığı (II*) yoktu, eklendi; ilk IFD ofseti 8 varsayıldı")
            }
        }
        if (b.size > 10) {
            val le = b[0] == 0x49.toByte()
            val bb = ByteBuffer.wrap(b).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
            val o = bb.getInt(4).toLong() and 0xFFFFFFFFL
            if (o < 8 || o > b.size - 2) { bb.putInt(4, 8); log.add("İlk IFD ofseti geçersizdi ($o), 8 yapıldı") }
        }
        return Repaired(b, log, log.isNotEmpty())
    }

    fun repairPdf(input: ByteArray): Repaired {
        var b = input
        val log = ArrayList<String>()
        val head = "%PDF-".toByteArray(Charsets.ISO_8859_1)
        val i = indexOf(b, head, 0, 1024)
        if (i < 0) { b = "%PDF-1.7\n".toByteArray(Charsets.ISO_8859_1) + b; log.add("%PDF- başlığı yoktu, eklendi") }
        else if (i > 0) { b = b.copyOfRange(i, b.size); log.add("%PDF- öncesindeki $i bayt çöp kesildi") }
        val eof = "%%EOF".toByteArray(Charsets.ISO_8859_1)
        var l = -1
        var q = b.size - 5
        val lo = max(0, b.size - 4096)
        while (q >= lo) { if (indexOf(b, eof, q, q + 5) == q) { l = q; break }; q-- }
        if (l < 0) { b = b + "\n%%EOF\n".toByteArray(Charsets.ISO_8859_1); log.add("%%EOF eksikti, eklendi") }
        else {
            var junk = false
            for (k in l + 5 until b.size) if (b[k] > 32) { junk = true; break }
            if (junk) { val n = b.size - (l + 5); b = b.copyOfRange(0, l + 5) + "\n".toByteArray(); log.add("%%EOF sonrasındaki $n artık bayt kesildi") }
        }
        return Repaired(b, log, log.isNotEmpty())
    }

    /** xref tablosu bozuksa: "N G obj" konumlarını tarayıp dosya sonuna yeni xref + trailer ekler. */
    fun rebuildPdfXref(b: ByteArray): ByteArray {
        val text = String(b, Charsets.ISO_8859_1)
        val offs = HashMap<Int, Int>()
        val gens = HashMap<Int, Int>()
        var maxNum = 0
        var lastObjStart = ArrayList<IntArray>()   // [offset, num]
        for (m in Regex("(?m)(?:^|[\\r\\n\\s])(\\d{1,9})\\s+(\\d{1,5})\\s+obj\\b").findAll(text)) {
            val g = m.groups[1]!!
            val n = g.value.toInt()
            offs[n] = g.range.first
            gens[n] = m.groups[2]!!.value.toInt()
            lastObjStart.add(intArrayOf(g.range.first, n))
            if (n > maxNum) maxNum = n
        }
        var rootNum = -1
        var rootGen = 0
        val rm = Regex("/Root\\s+(\\d+)\\s+(\\d+)\\s+R").findAll(text).lastOrNull()
        if (rm != null) { rootNum = rm.groups[1]!!.value.toInt(); rootGen = rm.groups[2]!!.value.toInt() }
        else {
            val c = Regex("/Type\\s*/Catalog").find(text)
            if (c != null) {
                val s = lastObjStart.lastOrNull { it[0] <= c.range.first }
                if (s != null) { rootNum = s[1]; rootGen = gens[s[1]] ?: 0 }
            }
        }
        if (rootNum < 0 || maxNum == 0) return b
        val sb = StringBuilder()
        sb.append("\n")
        val xrefPos = b.size + 1
        sb.append("xref\n0 ").append(maxNum + 1).append("\n")
        sb.append("0000000000 65535 f \n")
        for (n in 1..maxNum) {
            val o = offs[n]
            if (o == null) sb.append("0000000000 65535 f \n")
            else sb.append(String.format("%010d %05d n \n", o, gens[n] ?: 0))
        }
        sb.append("trailer\n<< /Size ${maxNum + 1} /Root $rootNum $rootGen R >>\nstartxref\n$xrefPos\n%%EOF\n")
        return b + sb.toString().toByteArray(Charsets.ISO_8859_1)
    }
}

/** Temel TIFF çözücü: çok sayfalı (çok IFD), şeritli; sıkıştırma: yok, PackBits, LZW, Deflate. Gri/1-bit/RGB/palet. */
class TiffReader(private val b: ByteArray) {
    private val le = b.size > 8 && b[0] == 0x49.toByte()
    private val bb: ByteBuffer = ByteBuffer.wrap(b).order(if (le) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN)
    private val ifds = ArrayList<Int>()
    private fun u16(o: Int) = bb.getShort(o).toInt() and 0xFFFF
    private fun u32(o: Int) = bb.getInt(o).toLong() and 0xFFFFFFFFL

    init {
        try {
            var off = u32(4)
            var guard = 0
            while (off in 8L until (b.size - 2).toLong() && guard++ < 5000) {
                ifds.add(off.toInt())
                val n = u16(off.toInt())
                val next = off.toInt() + 2 + n * 12
                if (next + 4 > b.size) break
                off = u32(next)
            }
        } catch (e: Exception) { /* bulunan IFD'lerle devam */ }
    }

    val pageCount: Int get() = ifds.size

    private fun values(ifd: Int, tag: Int): LongArray? {
        val n = u16(ifd)
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            if (u16(e) != tag) continue
            val type = u16(e + 2)
            val cnt = u32(e + 4).toInt()
            val sz = when (type) { 1, 2, 6, 7 -> 1; 3, 8 -> 2; 4, 9, 11 -> 4; else -> 8 }
            val base = if (sz.toLong() * cnt <= 4) e + 8 else u32(e + 8).toInt()
            return LongArray(cnt) { k ->
                when (sz) {
                    1 -> (b[base + k].toLong() and 0xFF)
                    2 -> u16(base + 2 * k).toLong()
                    4 -> u32(base + 4 * k)
                    else -> 0L
                }
            }
        }
        return null
    }

    fun decodePage(i: Int): Bitmap {
        val ifd = ifds[i]
        fun v(t: Int) = values(ifd, t)
        val w = v(256)?.get(0)?.toInt() ?: throw IllegalStateException("Genişlik yok")
        val h = v(257)?.get(0)?.toInt() ?: throw IllegalStateException("Yükseklik yok")
        val bits = v(258)?.get(0)?.toInt() ?: 1
        val comp = v(259)?.get(0)?.toInt() ?: 1
        val photo = v(262)?.get(0)?.toInt() ?: 1
        val spp = v(277)?.get(0)?.toInt() ?: 1
        val rps = v(278)?.get(0)?.toInt() ?: h
        val pred = v(317)?.get(0)?.toInt() ?: 1
        val offs = v(273) ?: throw IllegalStateException("Şerit konumları yok")
        val counts = v(279)
        if (v(322) != null) throw IllegalStateException("Döşemeli (tiled) TIFF desteklenmiyor")
        if (spp > 1 && v(284)?.get(0) == 2L) throw IllegalStateException("Ayrık (planar) TIFF desteklenmiyor")
        if (w <= 0 || h <= 0 || w.toLong() * h > 60_000_000L) throw IllegalStateException("Görsel boyutu geçersiz/çok büyük")
        val rowBytes = (w * spp * bits + 7) / 8
        val raw = ByteArray(rowBytes * h)
        var rowsDone = 0
        for (s in offs.indices) {
            val rows = min(rps, h - rowsDone)
            if (rows <= 0) break
            val need = rows * rowBytes
            val start = offs[s].toInt()
            if (start < 0 || start >= b.size) break
            val len = counts?.getOrNull(s)?.toInt() ?: (b.size - start)
            val src = b.copyOfRange(start, min(b.size, start + len))
            val dec = when (comp) {
                1 -> src
                32773 -> packBits(src, need)
                5 -> lzw(src, need)
                8, 32946 -> inflate(src, need)
                else -> throw IllegalStateException("Sıkıştırma türü $comp desteklenmiyor (CCITT/JPEG TIFF)")
            }
            val limit = min(dec.size, need)
            if (pred == 2 && bits == 8) {
                for (r in 0 until rows) {
                    val ro = r * rowBytes
                    for (x in spp until rowBytes) {
                        val idx = ro + x
                        if (idx >= limit) break
                        dec[idx] = (dec[idx] + dec[idx - spp]).toByte()
                    }
                }
            }
            System.arraycopy(dec, 0, raw, rowsDone * rowBytes, limit)
            rowsDone += rows
        }
        val px = IntArray(w * h)
        val black = 0xFF000000.toInt()
        when {
            bits == 1 && spp == 1 -> for (y in 0 until h) for (x in 0 until w) {
                val bit = (raw[y * rowBytes + (x shr 3)].toInt() shr (7 - (x and 7))) and 1
                val white = if (photo == 0) bit == 0 else bit == 1
                px[y * w + x] = if (white) -1 else black
            }
            bits == 8 && spp == 1 && photo == 3 -> {
                val cm = v(320) ?: throw IllegalStateException("Renk paleti yok")
                for (k in 0 until w * h) {
                    val g = raw[(k / w) * rowBytes + (k % w)].toInt() and 0xFF
                    val r = (cm.getOrElse(g) { 0L } shr 8).toInt() and 0xFF
                    val gg = (cm.getOrElse(256 + g) { 0L } shr 8).toInt() and 0xFF
                    val bl = (cm.getOrElse(512 + g) { 0L } shr 8).toInt() and 0xFF
                    px[k] = black or (r shl 16) or (gg shl 8) or bl
                }
            }
            bits == 8 && spp == 1 -> for (y in 0 until h) for (x in 0 until w) {
                var g = raw[y * rowBytes + x].toInt() and 0xFF
                if (photo == 0) g = 255 - g
                px[y * w + x] = black or (g shl 16) or (g shl 8) or g
            }
            bits == 8 && spp >= 3 -> for (y in 0 until h) for (x in 0 until w) {
                val o = y * rowBytes + x * spp
                px[y * w + x] = black or ((raw[o].toInt() and 0xFF) shl 16) or ((raw[o + 1].toInt() and 0xFF) shl 8) or (raw[o + 2].toInt() and 0xFF)
            }
            else -> throw IllegalStateException("Bit derinliği $bits / örnek $spp desteklenmiyor")
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun packBits(src: ByteArray, need: Int): ByteArray {
        val out = ByteArray(need)
        var i = 0
        var o = 0
        while (i < src.size && o < need) {
            val n = src[i++].toInt()
            if (n >= 0) {
                val c = n + 1
                val m = min(c, min(src.size - i, need - o))
                System.arraycopy(src, i, out, o, m)
                i += c; o += m
            } else if (n != -128) {
                val c = 1 - n
                if (i >= src.size) break
                val v = src[i++]
                val m = min(c, need - o)
                java.util.Arrays.fill(out, o, o + m, v)
                o += m
            }
        }
        return out
    }

    private fun inflate(src: ByteArray, need: Int): ByteArray {
        val inf = Inflater()
        inf.setInput(src)
        val out = ByteArray(need)
        var o = 0
        try {
            while (o < need && !inf.finished()) {
                val n = inf.inflate(out, o, need - o)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                o += n
            }
        } finally { inf.end() }
        return out
    }

    /** TIFF LZW (MSB ilk, "early change"). */
    private fun lzw(src: ByteArray, need: Int): ByteArray {
        val out = ByteArray(need)
        var o = 0
        val prefix = IntArray(4096)
        val suffix = ByteArray(4096)
        val len = IntArray(4096)
        for (i in 0 until 256) { suffix[i] = i.toByte(); len[i] = 1; prefix[i] = -1 }
        var next = 258
        var codeLen = 9
        var bitPos = 0
        var prev = -1
        val total = src.size * 8
        fun first(code: Int): Byte { var c = code; while (prefix[c] >= 0) c = prefix[c]; return suffix[c] }
        fun write(code: Int) {
            val l = len[code]
            var c = code
            var p = o + l - 1
            while (c >= 0) { if (p in 0 until need) out[p] = suffix[c]; p--; c = prefix[c] }
            o += l
        }
        while (o < need) {
            if (bitPos + codeLen > total) break
            val bp = bitPos shr 3
            val v = ((src[bp].toInt() and 0xFF) shl 16) or
                (if (bp + 1 < src.size) (src[bp + 1].toInt() and 0xFF) shl 8 else 0) or
                (if (bp + 2 < src.size) (src[bp + 2].toInt() and 0xFF) else 0)
            val code = (v shr (24 - (bitPos and 7) - codeLen)) and ((1 shl codeLen) - 1)
            bitPos += codeLen
            if (code == 257) break
            if (code == 256) { next = 258; codeLen = 9; prev = -1; continue }
            if (prev < 0) {
                if (code >= 256) break
                write(code)
            } else {
                if (code < next) {
                    if (next < 4096) { prefix[next] = prev; suffix[next] = first(code); len[next] = len[prev] + 1; next++ }
                    write(code)
                } else {
                    if (next >= 4096) break
                    prefix[next] = prev; suffix[next] = first(prev); len[next] = len[prev] + 1; next++
                    write(code)
                }
            }
            prev = code
            codeLen = if (next >= 2047) 12 else if (next >= 1023) 11 else if (next >= 511) 10 else 9
        }
        return out
    }
}

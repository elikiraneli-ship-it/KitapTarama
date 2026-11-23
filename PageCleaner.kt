package tr.alihoca.turku.yeni.scan

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Tek bir sayfa görselini bulur, kırpar, gölgesini temizler ve 300 DPI A4 tuvale oturtur. */
object PageCleaner {
    const val A4_W = 2480
    const val A4_H = 3508
    private const val MARGIN = 100
    private const val BLOCK = 16

    fun process(src: Bitmap): Bitmap {
        val r = detectPage(src)
        val cw = r[2] - r[0] + 1
        val ch = r[3] - r[1] + 1
        val scale = min((A4_W - 2 * MARGIN) / cw.toFloat(), (A4_H - 2 * MARGIN) / ch.toFloat())
        val nw = max(1, (cw * scale).toInt())
        val nh = max(1, (ch * scale).toInt())

        val cropped = Bitmap.createBitmap(src, r[0], r[1], cw, ch)
        val scaled = Bitmap.createScaledBitmap(cropped, nw, nh, true)
        val px = IntArray(nw * nh)
        scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        if (scaled !== cropped) cropped.recycle()
        scaled.recycle()

        flatten(px, nw, nh)

        val out = Bitmap.createBitmap(A4_W, A4_H, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.WHITE)
        out.setPixels(px, 0, nw, (A4_W - nw) / 2, (A4_H - nh) / 2, nw, nh)
        return out
    }

    /** Orta bölgenin parlaklık profiline göre en geniş "beyaz" şeridi (sayfayı) bulur. [sol, üst, sağ, alt] */
    private fun detectPage(src: Bitmap): IntArray {
        val w = src.width
        val h = src.height
        val s = min(1f, 600f / max(w, h))
        val sw = max(8, (w * s).toInt())
        val sh = max(8, (h * s).toInt())
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        if (small !== src) small.recycle()
        val lum = IntArray(sw * sh) {
            val p = px[it]
            (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000
        }

        val x0 = sw / 4; val x1 = sw * 3 / 4
        val y0 = sh / 4; val y1 = sh * 3 / 4
        val hist = IntArray(256)
        for (y in y0 until y1) for (x in x0 until x1) hist[lum[y * sw + x]]++
        val total = (x1 - x0) * (y1 - y0)
        var acc = 0
        var p90 = 255
        for (v in 0..255) { acc += hist[v]; if (acc >= total * 0.9) { p90 = v; break } }

        val thrCol = max(90f, p90 * 0.55f)
        val thrRow = max(80f, p90 * 0.50f)
        val colOk = BooleanArray(sw) { x ->
            var sum = 0
            for (y in y0 until y1) sum += lum[y * sw + x]
            sum.toFloat() / (y1 - y0) > thrCol
        }
        val rowOk = BooleanArray(sh) { y ->
            var sum = 0
            for (x in x0 until x1) sum += lum[y * sw + x]
            sum.toFloat() / (x1 - x0) > thrRow
        }
        var cx = longestRun(colOk, max(1, sw * 15 / 1000))
        var cy = longestRun(rowOk, max(2, sh * 6 / 100))
        // Tespit başarısızsa tüm görseli kullan
        if (cx[1] - cx[0] < sw * 0.35f) cx = intArrayOf(0, sw - 1)
        if (cy[1] - cy[0] < sh * 0.35f) cy = intArrayOf(0, sh - 1)

        val fx = w / sw.toFloat()
        val fy = h / sh.toFloat()
        val inset = max(3, (min(w, h) * 0.006f).toInt())
        val l = min(w - 2, (cx[0] * fx).toInt() + inset)
        val t = min(h - 2, (cy[0] * fy).toInt() + inset)
        val rr = max(l + 1, min(w - 1, ((cx[1] + 1) * fx).toInt() - inset))
        val bb = max(t + 1, min(h - 1, ((cy[1] + 1) * fy).toInt() - inset))
        return intArrayOf(l, t, rr, bb)
    }

    private fun longestRun(ok: BooleanArray, maxGap: Int): IntArray {
        var bestS = 0
        var bestE = ok.size - 1
        var bestLen = -1
        var s = -1
        var last = -1
        for (i in ok.indices) {
            if (ok[i]) {
                if (s < 0) s = i
                last = i
            } else if (s >= 0 && i - last > maxGap) {
                if (last - s + 1 > bestLen) { bestLen = last - s + 1; bestS = s; bestE = last }
                s = -1
            }
        }
        if (s >= 0 && last - s + 1 > bestLen) { bestS = s; bestE = last }
        return intArrayOf(bestS, bestE)
    }

    /** Kağıt rengini/gölgeyi kanal kanal düzleştirir, beyaz noktayı ve siyah noktayı ayarlar. Renk korunur. */
    private fun flatten(px: IntArray, w: Int, h: Int) {
        val gw = (w + BLOCK - 1) / BLOCK
        val gh = (h + BLOCK - 1) / BLOCK
        val mx = Array(3) { FloatArray(gw * gh) }
        var colorPix = 0L
        var sampled = 0L
        for (y in 0 until h) {
            val gy = y / BLOCK
            val row = y * w
            for (x in 0 until w) {
                val p = px[row + x]
                val gi = gy * gw + x / BLOCK
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                if (r > mx[0][gi]) mx[0][gi] = r.toFloat()
                if (g > mx[1][gi]) mx[1][gi] = g.toFloat()
                if (b > mx[2][gi]) mx[2][gi] = b.toFloat()
                if ((x and 7) == 0 && (y and 7) == 0) {
                    sampled++
                    if (abs(r - b) > 40) colorPix++
                }
            }
        }
        val colored = sampled > 0 && colorPix > sampled * 0.002
        val bg = Array(3) { boxBlur(maxFilter(mx[it], gw, gh, 3), gw, gh, 2) }

        val black = if (colored) 70f else 110f
        val white = if (colored) 240f else 235f
        val k = 255f / (white - black)

        val xi = IntArray(w)
        val xt = FloatArray(w)
        for (x in 0 until w) {
            val f = ((x + 0.5f) / BLOCK - 0.5f).coerceIn(0f, (gw - 1).toFloat())
            val i0 = min(floor(f).toInt(), gw - 1)
            xi[x] = i0
            xt[x] = f - i0
        }
        for (y in 0 until h) {
            val f = ((y + 0.5f) / BLOCK - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val ya = min(floor(f).toInt(), gh - 1)
            val yb = min(ya + 1, gh - 1)
            val ty = f - ya
            val row = y * w
            for (x in 0 until w) {
                val xa = xi[x]
                val xb = min(xa + 1, gw - 1)
                val tx = xt[x]
                val p = px[row + x]
                // Çok koyu bölgelerde (büyük resim/siyah alan) arka planı en az 140 say: resimler beyazlamasın
                val bgR = max(sample(bg[0], gw, ya, yb, xa, xb, tx, ty), 140f)
                val bgG = max(sample(bg[1], gw, ya, yb, xa, xb, tx, ty), 140f)
                val bgB = max(sample(bg[2], gw, ya, yb, xa, xb, tx, ty), 140f)
                val r = (((p shr 16) and 255) * 255f / bgR - black) * k
                val g = (((p shr 8) and 255) * 255f / bgG - black) * k
                val b = ((p and 255) * 255f / bgB - black) * k
                px[row + x] = (0xFF shl 24) or
                    (r.coerceIn(0f, 255f).toInt() shl 16) or
                    (g.coerceIn(0f, 255f).toInt() shl 8) or
                    b.coerceIn(0f, 255f).toInt()
            }
        }
    }

    private fun sample(g: FloatArray, gw: Int, ya: Int, yb: Int, xa: Int, xb: Int, tx: Float, ty: Float): Float {
        val top = g[ya * gw + xa] * (1 - tx) + g[ya * gw + xb] * tx
        val bot = g[yb * gw + xa] * (1 - tx) + g[yb * gw + xb] * tx
        return top * (1 - ty) + bot * ty
    }

    private fun maxFilter(src: FloatArray, gw: Int, gh: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            var m = 0f
            for (k in max(0, x - r)..min(gw - 1, x + r)) m = max(m, src[y * gw + k])
            tmp[y * gw + x] = m
        }
        for (y in 0 until gh) for (x in 0 until gw) {
            var m = 0f
            for (k in max(0, y - r)..min(gh - 1, y + r)) m = max(m, tmp[k * gw + x])
            out[y * gw + x] = m
        }
        return out
    }

    private fun boxBlur(src: FloatArray, gw: Int, gh: Int, r: Int): FloatArray {
        val tmp = FloatArray(src.size)
        val out = FloatArray(src.size)
        for (y in 0 until gh) for (x in 0 until gw) {
            var s = 0f; var n = 0
            for (k in max(0, x - r)..min(gw - 1, x + r)) { s += src[y * gw + k]; n++ }
            tmp[y * gw + x] = s / n
        }
        for (y in 0 until gh) for (x in 0 until gw) {
            var s = 0f; var n = 0
            for (k in max(0, y - r)..min(gh - 1, y + r)) { s += tmp[k * gw + x]; n++ }
            out[y * gw + x] = s / n
        }
        return out
    }
}

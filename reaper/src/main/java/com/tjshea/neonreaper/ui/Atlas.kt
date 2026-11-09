package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.tjshea.neonreaper.engine.ReaperConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the reel-face texture atlas for the 3D renderer: an 8×4 grid of [cell]-pixel squares — sharp symbols in cells
 * 0..SYMBOL_COUNT-1, motion-blurred copies in SYMBOL_COUNT..2*SYMBOL_COUNT-1 — returned as tightly packed RGBA bytes.
 *
 * The blurred copies are shown while a reel spins fast, so they are a true vertical motion blur: a cascade of three passes of three
 * equal-weight taps (spacings d, 3d, 9d = 27 evenly spread samples of +-0.16 cell) combined with ADD so the weights sum to exactly
 * one. That keeps the colours and the brightness of the symbol (and its alpha) with no visible ghost bands, for only nine blits per
 * symbol; a small gain brings back the punch that any blur takes out of highlights.
 */
object Atlas {
    const val COLS = 8
    const val ROWS = 4

    class Data(val width: Int, val height: Int, val rgba: ByteBuffer)

    private const val SPACING = 0.012f     // first-pass tap spacing in cells; the later passes use 3x and 9x that

    /** True when this platform stores ARGB_8888 bitmaps as R,G,B,A bytes (Android does; desktop Skia, e.g. Robolectric, uses B,G,R,A). */
    private val nativeIsRgba: Boolean by lazy {
        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        probe.setPixel(0, 0, 0xFF112233.toInt())
        val bb = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
        probe.copyPixelsToBuffer(bb); probe.recycle()
        (bb.get(0).toInt() and 255) == 0x11
    }

    /**
     * Paints the twelve bodies on up to three worker threads (each symbol writes only its own slot; the painting toolkit keeps no
     * shared mutable state). Anything that fails here is simply built lazily, as before, by the caller's thread.
     */
    private fun prebuild(ch: Characters) {
        val workers = minOf(3, Runtime.getRuntime().availableProcessors())
        if (workers < 2) return
        val pool = java.util.concurrent.Executors.newFixedThreadPool(workers)
        try {
            // heavy portraits first so the workers finish together
            val order = intArrayOf(ReaperConfig.WILD, ReaperConfig.SKULL, ReaperConfig.DOCTOR, ReaperConfig.WRAITH, ReaperConfig.RAVEN, ReaperConfig.DEMON,
                ReaperConfig.SCATTER, ReaperConfig.ACE, ReaperConfig.KING, ReaperConfig.QUEEN, ReaperConfig.JACK, ReaperConfig.TEN)
            val jobs = order.map { s -> pool.submit { ch.body(s) } }
            for (j in jobs) try { j.get() } catch (e: Exception) { /* built lazily below */ }
        } finally { pool.shutdown() }
    }

    fun build(cell: Int = 256): Data {
        val bmp = Bitmap.createBitmap(cell * COLS, cell * ROWS, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val ch = Characters(cell, cell)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val a = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888); val ac = Canvas(a)
        val b = Bitmap.createBitmap(cell, cell, Bitmap.Config.ARGB_8888); val bc = Canvas(b)
        val tap = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = Kit.ADD; alpha = 85 }   // 3 x 85/255 = 1
        val gain = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = Kit.ADD; alpha = 46 }
        val d = cell * SPACING
        prebuild(ch)
        for (s in 0 until ReaperConfig.SYMBOL_COUNT) {
            val body = ch.body(s)
            c.drawBitmap(body, ((s % COLS) * cell).toFloat(), ((s / COLS) * cell).toFloat(), p)
            a.eraseColor(0); b.eraseColor(0)
            for (k in -1..1) ac.drawBitmap(body, 0f, k * d, tap)              // pass 1 (spacing d) into a
            for (k in -1..1) bc.drawBitmap(a, 0f, k * 3 * d, tap)             // pass 2 (3d) into b
            a.eraseColor(0)
            for (k in -1..1) ac.drawBitmap(b, 0f, k * 9 * d, tap)             // pass 3 (9d) back into a
            val i = s + ReaperConfig.SYMBOL_COUNT
            val bx = ((i % COLS) * cell).toFloat(); val by = ((i / COLS) * cell).toFloat()
            c.drawBitmap(a, bx, by, p)
            c.drawBitmap(a, bx, by, gain)                                      // +18% punch (the blur flattens highlights)
        }
        a.recycle(); b.recycle()
        ch.recycle()
        val buf = ByteBuffer.allocateDirect(bmp.byteCount).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf)
        buf.position(0)
        if (!nativeIsRgba) {                       // desktop Skia: swap B and R so the bytes are RGBA as the GL upload expects
            val ib = buf.asIntBuffer()
            for (i in 0 until ib.limit()) { val v = ib.get(i); ib.put(i, (v and 0xFF00FF00.toInt()) or ((v shr 16) and 0xFF) or ((v and 0xFF) shl 16)) }
        }
        buf.position(0)
        val out = Data(bmp.width, bmp.height, buf)
        bmp.recycle()
        return out
    }
}

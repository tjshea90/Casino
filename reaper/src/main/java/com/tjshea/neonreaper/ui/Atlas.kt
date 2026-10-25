package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.tjshea.neonreaper.engine.ReaperConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds the reel-face texture atlas for the 3D renderer: an 8×4 grid of
 * [cell]-pixel squares — sharp symbols in cells 0..10, motion-blurred copies in
 * 11..21 — returned as tightly packed RGBA bytes.
 */
object Atlas {
    const val COLS = 8
    const val ROWS = 4

    class Data(val width: Int, val height: Int, val rgba: ByteBuffer)

    fun build(cell: Int = 256): Data {
        val bmp = Bitmap.createBitmap(cell * COLS, cell * ROWS, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val ch = Characters(cell, cell)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (s in 0 until ReaperConfig.SYMBOL_COUNT) {
            val body = ch.body(s)
            c.drawBitmap(body, ((s % COLS) * cell).toFloat(), ((s / COLS) * cell).toFloat(), p)
            val b = s + ReaperConfig.SYMBOL_COUNT
            val bx = ((b % COLS) * cell).toFloat(); val by = ((b / COLS) * cell).toFloat()
            c.save(); c.clipRect(bx, by, bx + cell, by + cell)
            for (k in 0 until 7) { p.alpha = 70; c.drawBitmap(body, bx, by + (k - 3) * cell * 0.06f, p) }
            p.alpha = 255
            c.restore()
        }
        ch.recycle()
        val buf = ByteBuffer.allocateDirect(bmp.byteCount).order(ByteOrder.nativeOrder())
        bmp.copyPixelsToBuffer(buf)   // ARGB_8888 is stored as R,G,B,A bytes (premultiplied)
        buf.position(0)
        val out = Data(bmp.width, bmp.height, buf)
        bmp.recycle()
        return out
    }
}

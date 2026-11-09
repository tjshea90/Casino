package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.tjshea.neonreaper.engine.ReaperConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.DataOutputStream
import java.io.File

/**
 * Writes the reel atlas to build/atlas.bin (w, h, RGBA) for the headless GL scene tests, plus PNGs for visual QA (the atlas itself,
 * a mock of five reels stopped and spinning at ~130 px) and build timings (build/screens/perf.txt).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AtlasExportTest {
    @Test fun export() {
        val out = File("build/screens").apply { mkdirs() }
        val log = StringBuilder()
        // per-body build time (the first build in this JVM includes class loading / noise setup)
        for (size in intArrayOf(256, 256, 256, 140, 140, 100)) {
            val ch = Characters(size, size)
            var total = 0.0
            val line = StringBuilder()
            for (s in 0 until ReaperConfig.SYMBOL_COUNT) {
                val t0 = System.nanoTime(); ch.body(s); val ms = (System.nanoTime() - t0) / 1e6; total += ms
                line.append("%.0f ".format(ms))
            }
            log.append("bodies size=$size: $line total ${"%.0f".format(total)} ms\n")
            ch.recycle()
        }
        // byte order probe: a pure red pixel must come out of Atlas.build as R=255,G=0,B=0
        run {
            val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); probe.setPixel(0, 0, 0xFFFF0000.toInt())
            val bb = java.nio.ByteBuffer.allocateDirect(4).order(java.nio.ByteOrder.nativeOrder()); probe.copyPixelsToBuffer(bb)
            log.append("native bytes of opaque red: ${(0..3).joinToString(" ") { (bb.get(it).toInt() and 255).toString() }}\n")
        }
        val t0 = System.nanoTime()
        val a = Atlas.build(256)
        log.append("Atlas.build(256) ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms\n")
        val t1 = System.nanoTime()
        val a2 = Atlas.build(256)
        log.append("Atlas.build(256) warm ${"%.0f".format((System.nanoTime() - t1) / 1e6)} ms (${a2.width}x${a2.height})\n")

        val bytes = ByteArray(a.rgba.remaining()); a.rgba.get(bytes)
        run {   // sample the demon's forehead (cell 4) from the source bitmap and from the atlas bytes: they must agree as R,G,B
            val ch = Characters(256, 256); val src = ch.body(ReaperConfig.DEMON).getPixel(128, 90)
            val o = ((90) * a.width + (4 * 256 + 128)) * 4
            log.append("demon px src argb=${Integer.toHexString(src)} atlas bytes=${(0..3).joinToString(" ") { (bytes[o + it].toInt() and 255).toString() }}\n")
            ch.recycle()
        }
        DataOutputStream(File("build/atlas.bin").outputStream().buffered()).use { it.writeInt(a.width); it.writeInt(a.height); it.write(bytes) }

        // PNG of the atlas (RGBA bytes -> ARGB ints; the buffer is premultiplied, which Bitmap.createBitmap(int[]) treats as straight alpha: fine for QA)
        val px = IntArray(a.width * a.height)
        for (i in px.indices) {
            var r = bytes[i * 4].toInt() and 255; var g = bytes[i * 4 + 1].toInt() and 255
            var b = bytes[i * 4 + 2].toInt() and 255; val al = bytes[i * 4 + 3].toInt() and 255
            if (al in 1..254) { r = minOf(255, r * 255 / al); g = minOf(255, g * 255 / al); b = minOf(255, b * 255 / al) }   // un-premultiply
            px[i] = (al shl 24) or (r shl 16) or (g shl 8) or b
        }
        val atlas = Bitmap.createBitmap(px, a.width, a.height, Bitmap.Config.ARGB_8888)
        val view = Bitmap.createBitmap(a.width, a.height, Bitmap.Config.ARGB_8888)
        Canvas(view).apply { drawColor(0xFF0B130B.toInt()); drawBitmap(atlas, 0f, 0f, null) }
        File(out, "atlas.png").outputStream().use { view.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // mock reels at ~130 px: stopped (characters would be live 3D models; shown here with their sharp cell) and spinning (blurred cells)
        val cell = 256; val face = 130
        val strips = arrayOf(intArrayOf(6, 0, 7), intArrayOf(1, 8, 2), intArrayOf(9, 11, 3), intArrayOf(4, 10, 5), intArrayOf(7, 6, 0))
        val mock = Bitmap.createBitmap(face * 5 * 2 + 20, face * 3, Bitmap.Config.ARGB_8888)
        val mc = Canvas(mock); mc.drawColor(0xFF0B130B.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (pass in 0..1) for (r in 0 until 5) for (row in 0 until 3) {
            var idx = strips[r][row]; if (pass == 1) idx += ReaperConfig.SYMBOL_COUNT
            val sx = (idx % Atlas.COLS) * cell; val sy = (idx / Atlas.COLS) * cell
            val dx = pass * (face * 5 + 20) + r * face
            mc.drawBitmap(atlas, android.graphics.Rect(sx, sy, sx + cell, sy + cell), android.graphics.Rect(dx, row * face, dx + face, row * face + face), p)
        }
        File(out, "reel_mock.png").outputStream().use { mock.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(out, "perf.txt").writeText(log.toString())
    }
}

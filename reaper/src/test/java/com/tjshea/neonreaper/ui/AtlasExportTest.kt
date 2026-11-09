package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import com.tjshea.neonreaper.engine.ReaperConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.DataOutputStream
import java.io.File

/** Writes the reel atlas to build/atlas.bin (w, h, RGBA) for the headless GL scene tests, plus timing numbers. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AtlasExportTest {
    @Test fun export() {
        val out = File("build/screens").apply { mkdirs() }
        val log = StringBuilder()
        // per-body build time (cold = first build in this JVM includes class loading / font / noise setup)
        for (size in intArrayOf(256, 140, 100)) {
            val ch = Characters(size, size)
            for (s in 0 until ReaperConfig.SYMBOL_COUNT) {
                val t0 = System.nanoTime(); ch.body(s); val ms = (System.nanoTime() - t0) / 1e6
                log.append("body size=$size sym=$s ${"%.1f".format(ms)} ms\n")
            }
            ch.recycle()
        }
        val t0 = System.nanoTime()
        val a = Atlas.build(256)
        log.append("Atlas.build(256) ${"%.0f".format((System.nanoTime() - t0) / 1e6)} ms\n")
        val t1 = System.nanoTime()
        val a2 = Atlas.build(256)
        log.append("Atlas.build(256) warm ${"%.0f".format((System.nanoTime() - t1) / 1e6)} ms (${a2.width}x${a2.height})\n")
        File(out, "perf.txt").writeText(log.toString())
        val bytes = ByteArray(a.rgba.remaining()); a.rgba.get(bytes)
        DataOutputStream(File("build/atlas.bin").outputStream().buffered()).use { it.writeInt(a.width); it.writeInt(a.height); it.write(bytes) }
        // PNG of the atlas for visual QA (RGBA bytes -> ARGB ints)
        val px = IntArray(a.width * a.height)
        for (i in px.indices) {
            val r = bytes[i * 4].toInt() and 255; val g = bytes[i * 4 + 1].toInt() and 255
            val b = bytes[i * 4 + 2].toInt() and 255; val al = bytes[i * 4 + 3].toInt() and 255
            px[i] = (al shl 24) or (r shl 16) or (g shl 8) or b
        }
        val bmp = Bitmap.createBitmap(px, a.width, a.height, Bitmap.Config.ARGB_8888)
        File(out, "atlas.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

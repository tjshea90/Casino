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
import java.io.File

/** Contact sheets of the 2D icons (Art.symbol), relic icons and a few legacy helpers. PNGs land in build/screens/ for visual QA. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ArtSheetTest {
    private fun save(b: Bitmap, name: String) {
        val out = File("build/screens").apply { mkdirs() }
        File(out, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun sheet() {
        val cell = 180
        val bmp = Bitmap.createBitmap(cell * 6, cell * 4, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF000000.toInt())
        for (s in 0 until ReaperConfig.SYMBOL_COUNT) c.drawBitmap(Art.symbol(s, cell), (s % 6) * cell.toFloat(), (s / 6) * cell.toFloat(), null)
        for (i in 0 until 5) {
            c.drawBitmap(Art.relic(i, 90, true), i * 90f, cell * 2f, null)
            c.drawBitmap(Art.relic(i, 90, false), i * 90f, cell * 2f + 90, null)
        }
        Art.orb(c, 520f, 420f, 40f, Neon.GREEN); Art.orb(c, 620f, 420f, 40f, Neon.RED); Art.orb(c, 720f, 420f, 40f, Neon.PURPLE)
        Art.reaper(c, 810f, 360f, 260f, 340f)
        save(bmp, "art_sheet.png")
        val g = Bitmap.createBitmap(1280, 800, Bitmap.Config.ARGB_8888)
        Art.graveyard(Canvas(g), 1280f, 800f, Neon.RED)
        save(g, "graveyard.png")
    }

    /** Royals + coffin at 360 px on the dark drum colour: material / detail check. */
    @Test fun royalsBig() {
        val cell = 360
        val syms = intArrayOf(ReaperConfig.ACE, ReaperConfig.KING, ReaperConfig.QUEEN, ReaperConfig.JACK, ReaperConfig.TEN, ReaperConfig.SCATTER)
        val bmp = Bitmap.createBitmap(cell * 3, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFF0B130B.toInt())
        for ((i, s) in syms.withIndex()) c.drawBitmap(Art.symbol(s, cell), (i % 3) * cell.toFloat(), (i / 3) * cell.toFloat(), null)
        save(bmp, "royals_big.png")
    }

    /** Small sizes (dialog icons 64..96 px, reel faces ~130 px) side by side. */
    @Test fun smallSizes() {
        val sizes = intArrayOf(130, 96, 64)
        val bmp = Bitmap.createBitmap(130 * ReaperConfig.SYMBOL_COUNT, 130 + 96 + 64, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFF0B130B.toInt())
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        var y = 0f
        for (sz in sizes) {
            for (s in 0 until ReaperConfig.SYMBOL_COUNT) c.drawBitmap(Art.symbol(s, sz), s * 130f + (130 - sz) / 2f, y, p)
            y += sz
        }
        save(bmp, "art_small.png")
    }
}

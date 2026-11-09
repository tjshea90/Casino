package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import com.tjshea.neonreaper.engine.ReaperConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ArtSheetTest {
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
        val out = File("build/screens").apply { mkdirs() }
        File(out, "art_sheet.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val g = Bitmap.createBitmap(1280, 800, Bitmap.Config.ARGB_8888)
        Art.graveyard(Canvas(g), 1280f, 800f, Neon.RED)
        File(out, "graveyard.png").outputStream().use { g.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

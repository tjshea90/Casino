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

/** Contact sheets of the 2D cast as [Characters] draws it (static and live poses). PNGs land in build/screens/ for visual QA. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class Art3DSheetTest {
    private fun save(b: Bitmap, name: String) {
        val out = File("build/screens").apply { mkdirs() }
        File(out, name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** All 12 symbols idle, then the six characters excited (laugh, chatter, caw, snarl, wail) at two moments. */
    @Test fun sheet() {
        val cell = 200
        val ch = Characters(cell, cell)
        val bmp = Bitmap.createBitmap(cell * 6, cell * 4, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0B130B.toInt())
        for (s in 0 until ReaperConfig.SYMBOL_COUNT) {
            c.save(); c.translate((s % 6) * cell.toFloat(), (s / 6) * cell.toFloat()); ch.draw(c, s, 1.0, 0.0, Characters.IDLE); c.restore()
        }
        for (s in 0 until 6) {
            c.save(); c.translate(s * cell.toFloat(), 2f * cell); ch.draw(c, s, 1.13, 0.2, Characters.EXCITED); c.restore()
            c.save(); c.translate(s * cell.toFloat(), 3f * cell); ch.draw(c, s, 1.21, 0.6, Characters.EXCITED); c.restore()
        }
        save(bmp, "art3d_sheet.png")
    }

    /** The painted portraits straight from [Portraits] at large size (detail check). */
    @Test fun portraitsBig() {
        val cell = 420
        val syms = (ReaperConfig.WILD..ReaperConfig.WRAITH).toList()
        val bmp = Bitmap.createBitmap(cell * 3, cell * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFF0B130B.toInt())
        for ((i, s) in syms.withIndex()) {
            c.save(); c.translate((i % 3) * cell.toFloat(), (i / 3) * cell.toFloat()); c.drawBitmap(Art.symbol(s, cell), 0f, 0f, null); c.restore()
        }
        save(bmp, "portraits_big.png")
    }

    /** The tall boxes the 2D fallback uses for the side characters and celebrations (260x340). */
    @Test fun tallBoxes() {
        val big = Characters(260, 340, plates = false)
        val b2 = Bitmap.createBitmap(260 * 6, 340, Bitmap.Config.ARGB_8888)
        val c2 = Canvas(b2); c2.drawColor(0xFF000000.toInt())
        for (s in 0 until 6) { c2.save(); c2.translate(s * 260f, 0f); big.draw(c2, s, 2.0 + s * 0.1, 0.0, if (s % 2 == 0) Characters.EXCITED else Characters.IDLE); c2.restore() }
        save(b2, "art3d_big.png")
    }
}

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
class Art3DSheetTest {
    @Test fun sheet() {
        val cell = 200
        val ch = Characters(cell, cell)
        val bmp = Bitmap.createBitmap(cell * 6, cell * 4, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0A120A.toInt())
        for (s in 0 until ReaperConfig.SYMBOL_COUNT) {
            c.save(); c.translate((s % 6) * cell.toFloat(), (s / 6) * cell.toFloat()); ch.draw(c, s, 1.0, 0.0, Characters.IDLE); c.restore()
        }
        for (s in 0 until 6) {   // excited poses
            c.save(); c.translate(s * cell.toFloat(), 2f * cell); ch.draw(c, s, 1.13, 0.2, Characters.EXCITED); c.restore()
        }
        val big = Characters(260, 340)
        c.save(); c.translate(0f, 0f); c.restore()
        val out = File("build/screens").apply { mkdirs() }
        File(out, "art3d_sheet.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val b2 = Bitmap.createBitmap(560, 360, Bitmap.Config.ARGB_8888)
        val c2 = Canvas(b2); c2.drawColor(0xFF000000.toInt())
        big.draw(c2, ReaperConfig.WILD, 2.0, 0.0, Characters.EXCITED)
        c2.translate(280f, 0f); big.draw(c2, ReaperConfig.HOODED, 2.0, 0.0, Characters.IDLE)
        File(out, "art3d_big.png").outputStream().use { b2.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

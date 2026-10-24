package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Launches the real activity (catches startup crashes) and renders its dialogs. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ActivityTest {
    @Test fun launchAndDialogs() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val out = File("build/screens").apply { mkdirs() }
        fun snap(name: String) {
            ShadowLooper.idleMainLooper()
            val dlg = ShadowDialog.getLatestDialog()
            val root = dlg.window!!.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(0xFF000000.toInt()); root.draw(c)
            File(out, "d_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dlg.dismiss()
        }
        snap("setup")
        act.wallet.setInitialPassword("abcd".toCharArray())
        act.openDeposit(); snap("deposit")
        act.openInfo(); snap("info")
        act.openStats(); snap("stats")
    }
}

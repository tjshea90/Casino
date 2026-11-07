package com.tjshea.dragonhoard.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import com.tjshea.dragonhoard.audio.SoundFx
import com.tjshea.dragonhoard.data.Wallet
import com.tjshea.dragonhoard.engine.GameConfig
import com.tjshea.dragonhoard.engine.Money
import com.tjshea.dragonhoard.engine.SlotEngine
import com.tjshea.dragonhoard.engine.SpinResult
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.Random

/**
 * Renders the cabinet off-screen to PNGs in app/build/screens for visual review
 * (no emulator needed). Not an assertion test.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ScreenshotTest {

    private val ctx = RuntimeEnvironment.getApplication()
    private val out = File("build/screens").apply { mkdirs() }

    private fun newView(): SlotView {
        val w = Wallet(ctx).also { it.load() }
        val host = object : SlotView.Host {
            override val wallet = w
            override val sfx = SoundFx(ctx)
            override fun openDeposit() {}
            override fun openWithdraw() {}
            override fun openPaytable() {}
            override fun openStats() {}
            override fun openAutoPicker(onPicked: (Int) -> Unit) {}
        }
        return SlotView(ctx, host)
    }

    private fun size(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun frame(v: View): Bitmap {
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(bmp))
        return bmp
    }

    private fun shot(v: View, name: String) {
        File(out, "$name.png").outputStream().use { frame(v).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Advance the (shadowed) clock, drawing a frame every 40 ms so game logic runs. */
    private fun step(v: View, seconds: Double) {
        var t = 0.0
        while (t < seconds) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(40))
            frame(v)
            t += 0.04
        }
    }

    private fun find(free: Boolean, pred: (SpinResult) -> Boolean): SpinResult {
        val rng = Random(99)
        val strips = if (free) GameConfig.FREE_STRIPS else GameConfig.BASE_STRIPS
        repeat(2_000_000) {
            val stops = IntArray(5) { rng.nextInt(strips[it].size) }
            val r = SlotEngine.evaluate(stops, 1, 10, free)
            if (pred(r)) return r
        }
        error("not found")
    }

    @Test fun cabinetSizes() {
        val view = newView()
        size(view, 1280, 800); shot(view, "idle_1280x800")
        size(view, 1920, 1080); shot(view, "idle_1920x1080")
        size(view, 800, 1280); shot(view, "portrait_800x1280")
    }

    @Test fun gameStates() {
        SystemClock.setCurrentTimeMillis(1_000_000)
        val view = newView()
        size(view, 1280, 800)
        frame(view)

        val small = find(false) { it.lineWins.size >= 2 && it.totalWinMacks in 4..30 && it.scatterCount < 3 }
        view.animateSpin(small)
        step(view, 0.35); shot(view, "spin_blur")
        step(view, 1.6); shot(view, "win_all_lines")
        step(view, 2.0); shot(view, "win_line_cycle")

        val tease = find(false) { it.scatterCount >= 3 && it.grid[0].contains(GameConfig.SCATTER) && it.grid[1].contains(GameConfig.SCATTER) }
        view.animateSpin(tease)
        step(view, 1.25); shot(view, "anticipation")
        step(view, 2.0); shot(view, "scatter_land")
        step(view, 2.0); shot(view, "feature_intro")

        val big = find(false) { it.totalWinMacks >= 10L * 60 && it.scatterCount < 3 }
        view.animateSpin(big)
        step(view, 2.6); shot(view, "big_win_start")
        step(view, 4.0); shot(view, "big_win_end")
        println("big win macks=" + big.totalWinMacks + " " + Money.describe(big.totalWinTokens))
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class DialogScreenshotTest {
    @Test fun dialogs() {
        val act = org.robolectric.Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val out = File("build/screens").apply { mkdirs() }
        fun snap(name: String) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            val dlg = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            val root = dlg.window!!.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, root.measuredWidth, root.measuredHeight)
            val bmp = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp); c.drawColor(0xFF200000.toInt()); root.draw(c)
            File(out, "dlg_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            dlg.dismiss()
        }
        snap("setup")
        act.wallet.setInitialPassword("abcd".toCharArray())
        act.openDeposit(); snap("deposit")
        act.openPaytable(); snap("paytable")
        act.openStats(); snap("stats")
    }
}

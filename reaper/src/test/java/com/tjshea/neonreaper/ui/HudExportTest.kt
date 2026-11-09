package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import com.tjshea.neonreaper.audio.Audio
import com.tjshea.neonreaper.data.SpinPlay
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.Mini
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.ReaperEngine
import com.tjshea.neonreaper.engine.SpinOutcome
import com.tjshea.neonreaper.engine.VaultResult
import com.tjshea.neonreaper.gl.RecordingSink
import com.tjshea.neonreaper.gl.SceneState
import com.tjshea.neonreaper.gl.SceneStateIO
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
 * Records the HUD (transparent where the 3D world shows through) plus the scene state it
 * publishes, so SceneRenderTest can composite exactly what the device shows.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class HudExportTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val out = File("build/hud").apply { mkdirs() }
    private val audio by lazy { Audio(ctx) }

    private fun frame(v: View): Bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    private fun step(v: View, sec: Double) { var t = 0.0; while (t < sec) { ShadowSystemClock.advanceBy(Duration.ofMillis(40)); frame(v); t += 0.04 } }

    private fun record(v: View, sink: RecordingSink, name: String) {
        val bmp = frame(v)
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val snap = SceneState(); synchronized(sink.lock) { snap.copyFrom(sink.state) }
        SceneStateIO.write(File(out, "$name.bin"), bmp.width, bmp.height, sink.window, snap, px)
    }

    private fun find(mini: Mini, pred: (SpinOutcome) -> Boolean): SpinOutcome {
        val r = Random(7)
        repeat(3_000_000) {
            val stops = IntArray(5) { r.nextInt(ReaperConfig.STRIPS[it].size) }
            val o = ReaperEngine.outcome(10, stops, mini)
            if (pred(o)) return o
        }
        error("none")
    }

    @Test fun export() {
        SystemClock.setCurrentTimeMillis(1_000_000)
        val w = Wallet(ctx).also { it.load() }
        val host = object : ReaperView.Host {
            override val wallet = w
            override val audio = this@HudExportTest.audio
            override fun openDeposit() {}
            override fun openWithdraw() {}
            override fun openInfo() {}
            override fun openStats() {}
            override fun openAutoPicker(onPicked: (Int) -> Unit) {}
            override fun openVault(result: VaultResult) {}
        }
        val v = ReaperView(ctx, host)
        val sink = RecordingSink()
        v.gl3d = sink
        v.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1280, 800)
        step(v, 0.3); record(v, sink, "a_idle")
        val small = find(Mini.NONE) { it.lineWins.size >= 2 && it.totalMult in 3..8 }
        v.animateSpin(SpinPlay(small, 2, null))
        step(v, 0.35); record(v, sink, "b_spin")
        step(v, 2.4); record(v, sink, "c_win")
        step(v, 5.0)
        val feat = find(Mini(ReaperConfig.MINI_REAPER, wildReels = intArrayOf(1, 3))) { it.totalMult >= 15 }
        v.animateSpin(SpinPlay(feat, -1, null))
        step(v, 0.95); record(v, sink, "d_reaper_touch")
        step(v, 4.4); record(v, sink, "e_big_win")
        step(v, 5.0)
        val rr = Random(ReaperConfig.MINI_PUMPKIN.toLong())
        var o: SpinOutcome
        do {
            val stops = IntArray(5) { rr.nextInt(ReaperConfig.STRIPS[it].size) }
            o = ReaperEngine.outcome(10, stops, ReaperEngine.rollMini(rr, ReaperConfig.MINI_PUMPKIN))
        } while (o.totalMult < 6 || o.totalMult > 60)
        v.animateSpin(SpinPlay(o, -1, null))
        step(v, 1.0); record(v, sink, "f_mini_flyin")
        step(v, 3.0); record(v, sink, "g_mini_action")
    }
}

package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.tjshea.neonreaper.audio.Audio
import com.tjshea.neonreaper.data.SpinPlay
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.ReaperEngine
import com.tjshea.neonreaper.engine.SoulVault
import com.tjshea.neonreaper.engine.SpinOutcome
import com.tjshea.neonreaper.engine.VaultResult
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

/** Renders game states to reaper/build/screens for visual review (no emulator needed). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ScreensTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val out = File("build/screens").apply { mkdirs() }
    private val audio by lazy { Audio(ctx) }

    private fun size(v: View, w: Int = 1280, h: Int = 800) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun frame(v: View): Bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    private fun shot(v: View, name: String) = File(out, "$name.png").outputStream().use { frame(v).compress(Bitmap.CompressFormat.PNG, 100, it) }
    private fun step(v: View, sec: Double) { var t = 0.0; while (t < sec) { ShadowSystemClock.advanceBy(Duration.ofMillis(40)); frame(v); t += 0.04 } }
    private fun tap(v: View) {
        val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 5f, 5f, 0); v.onTouchEvent(e); e.recycle()
    }

    private fun find(wild: IntArray, pred: (SpinOutcome) -> Boolean): SpinOutcome {
        val r = Random(7)
        repeat(3_000_000) {
            val stops = IntArray(5) { r.nextInt(ReaperConfig.STRIPS[it].size) }
            val o = ReaperEngine.outcome(10, stops, if (wild.isEmpty()) com.tjshea.neonreaper.engine.Mini.NONE else com.tjshea.neonreaper.engine.Mini(ReaperConfig.MINI_REAPER, wildReels = wild))
            if (pred(o)) return o
        }
        error("none")
    }

    private fun baseView(): ReaperView {
        val w = Wallet(ctx).also { it.load() }
        val host = object : ReaperView.Host {
            override val wallet = w
            override val audio = this@ScreensTest.audio
            override fun openDeposit() {}
            override fun openWithdraw() {}
            override fun openInfo() {}
            override fun openStats() {}
            override fun openAutoPicker(onPicked: (Int) -> Unit) {}
            override fun openVault(result: VaultResult) {}
        }
        return ReaperView(ctx, host)
    }

    @Test fun baseGame() {
        SystemClock.setCurrentTimeMillis(1_000_000)
        val v = baseView(); size(v); step(v, 0.2); shot(v, "r_idle")
        size(v, 800, 1280); shot(v, "r_portrait"); size(v)
        val small = find(IntArray(0)) { it.lineWins.size >= 2 && it.totalMult in 3..8 }
        v.animateSpin(SpinPlay(small, 2, null))
        step(v, 0.35); shot(v, "r_spin")
        step(v, 1.25); shot(v, "r_relic_flight")
        step(v, 2.2); shot(v, "r_win_cycle")
        val feat = find(intArrayOf(1, 3)) { it.totalMult >= 15 }
        v.animateSpin(SpinPlay(feat, -1, null))
        step(v, 0.95); shot(v, "r_reaper_touch")
        step(v, 1.4); shot(v, "r_after_touch")
        step(v, 3.0); shot(v, "r_big_win")
        step(v, 5.0)
        val five = find(IntArray(0)) { o -> o.lineWins.any { it.count == 5 && it.symbol == ReaperConfig.HOODED } }
        v.animateSpin(SpinPlay(five, -1, null))
        step(v, 2.3); shot(v, "r_showcase")
        step(v, 6.0)
        val trig = find(IntArray(0)) { it.totalMult == 0 }
        v.animateSpin(SpinPlay(trig, 4, SoulVault.play(11L, 10)))
        step(v, 4.6); shot(v, "r_vault_trigger")
    }

    @Test fun vault() {
        SystemClock.setCurrentTimeMillis(1_000_000)
        // pick a seed with a reaper orb and a jackpot for a lively screenshot
        var seed = 1L
        while (true) {
            val r = SoulVault.play(seed, 10)
            if (r.steps.any { s -> s.landings.any { it.orb.kind == com.tjshea.neonreaper.engine.Orb.REAPER } } && r.totalMult in 60..300) break
            seed++
        }
        val res = SoulVault.play(seed, 10)
        val v = VaultView(ctx, audio, res) {}
        size(v); step(v, 1.0); shot(v, "v_intro")
        tap(v); step(v, 4.0); shot(v, "v_respins")
        step(v, 6.0); shot(v, "v_mid")
        step(v, 60.0); shot(v, "v_summary")
    }
}

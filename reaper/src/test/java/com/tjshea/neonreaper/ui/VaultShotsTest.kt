package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.tjshea.neonreaper.audio.Audio
import com.tjshea.neonreaper.engine.Landing
import com.tjshea.neonreaper.engine.Orb
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.SoulVault
import com.tjshea.neonreaper.engine.VaultResult
import com.tjshea.neonreaper.engine.VaultStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration

/**
 * Visual QA for the Soul Vault crypt: renders every key moment (intro, waiting for SPIN, respin, jackpot orb landing,
 * Reaper collector beams, tally, GRAND, summaries, outro) in landscape 1280x800 and portrait 800x1280 to
 * reaper/build/screens/vs_<land|port>_NN_<moment>.png. Results are built by hand so every orb type shows up deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class VaultShotsTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val out = File("build/screens").apply { mkdirs() }
    private val audio by lazy { Audio(ctx) }
    private var buf: Bitmap? = null

    // ------------------------------------------------------------------ harness
    private fun size(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }
    private fun frame(v: View): Bitmap {
        val b = buf?.takeIf { it.width == v.width && it.height == v.height } ?: Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { buf = it }
        b.eraseColor(0); v.draw(Canvas(b)); return b
    }
    private fun shot(v: View, name: String) { File(out, "$name.png").outputStream().use { frame(v).compress(Bitmap.CompressFormat.PNG, 100, it) } }
    private fun step(v: View, sec: Double, dtMs: Long = 40) { var t = 0.0; while (t < sec - 1e-9) { ShadowSystemClock.advanceBy(Duration.ofMillis(dtMs)); frame(v); t += dtMs / 1000.0 } }
    private fun until(sec: Double, v: View, dtMs: Long = 40, cond: () -> Boolean): Boolean {
        var t = 0.0
        while (t < sec) { if (cond()) return true; ShadowSystemClock.advanceBy(Duration.ofMillis(dtMs)); frame(v); t += dtMs / 1000.0 }
        return cond()
    }
    private fun tap(v: View, x: Float = 5f, y: Float = 5f) { val e = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, x, y, 0); v.onTouchEvent(e); e.recycle() }
    private fun press(v: VaultView) { val r = v.spinButton; tap(v, r.centerX(), r.centerY()) }
    private fun nextRespin(v: VaultView) { assertTrue("waiting for spin", until(12.0, v) { v.awaitingSpin }); step(v, 0.3); press(v) }

    // ------------------------------------------------------------------ hand-made results
    private class VB(val bet: Int) {
        val cells = arrayOfNulls<Orb>(SoulVault.CELLS)
        val initial = ArrayList<Landing>()
        val steps = ArrayList<VaultStep>()
        var respins = ReaperConfig.VAULT_RESPINS
        fun start(vararg p: Pair<Int, Int>) { for ((c, v) in p) { val o = Orb(Orb.SOUL, v); cells[c] = o; initial += Landing(c, o, IntArray(0)) }; initial.sortBy { it.cell } }
        /** landings are (cell, kind, soulValue); jackpot values and the Reaper's harvest are filled in like the engine does. */
        fun step(vararg l: Triple<Int, Int, Int>) {
            val landed = ArrayList<Landing>()
            for ((cell, kind, value) in l.sortedWith(compareBy({ it.first % VAULT_COLS }, { it.first / VAULT_COLS }))) {
                val o = when (kind) {
                    Orb.SOUL -> Orb(Orb.SOUL, value); Orb.MINI -> Orb(Orb.MINI, ReaperConfig.MINI); Orb.MINOR -> Orb(Orb.MINOR, ReaperConfig.MINOR)
                    Orb.MAJOR -> Orb(Orb.MAJOR, ReaperConfig.MAJOR); else -> Orb(Orb.REAPER, 0)
                }
                var collected = IntArray(0)
                if (kind == Orb.REAPER) {
                    val src = (0 until SoulVault.CELLS).filter { cells[it] != null && (cells[it]!!.kind == Orb.SOUL || cells[it]!!.kind == Orb.REAPER) }
                    collected = src.toIntArray(); o.value = src.sumOf { cells[it]!!.value }
                }
                cells[cell] = o; landed += Landing(cell, o, collected)
            }
            if (landed.isNotEmpty()) respins = ReaperConfig.VAULT_RESPINS else respins--
            steps += VaultStep(landed, respins)
        }
        fun build() = VaultResult(1L, bet, initial, steps, cells, cells.all { it != null })
    }

    private val S = Orb.SOUL
    /** Every orb type: souls of all tiers, MINI, MINOR, MAJOR and a Reaper that harvests nine souls. */
    private fun showcase(bet: Int = 5): VaultResult = VB(bet).apply {
        start(1 to 2, 6 to 5, 9 to 1, 13 to 10, 17 to 3)
        step(Triple(3, S, 8), Triple(11, Orb.MINI, 0), Triple(15, S, 1))
        step(Triple(8, Orb.MINOR, 0), Triple(12, S, 25), Triple(18, S, 4))
        step(Triple(4, Orb.REAPER, 0))
        step(Triple(19, Orb.MAJOR, 0), Triple(0, S, 15))
        step(); step(); step()
    }.build()

    private fun small(): VaultResult = VB(10).apply {
        start(2 to 1, 7 to 1, 10 to 2, 14 to 1, 18 to 3)
        step(); step(); step()
    }.build()

    private fun grand(): VaultResult = VB(5).apply {
        start(0 to 2, 7 to 5, 12 to 1, 14 to 3, 19 to 10)
        val rest = (0 until SoulVault.CELLS).filter { it !in intArrayOf(0, 7, 12, 14, 19) }
        step(*rest.map { c -> when (c) { 3 -> Triple(c, Orb.MAJOR, 0); 8 -> Triple(c, Orb.MINOR, 0); 16 -> Triple(c, Orb.MINI, 0); else -> Triple(c, S, intArrayOf(1, 2, 3, 4, 5, 8, 10, 15)[c % 8]) } }.toTypedArray())
    }.build()

    /** One of the engine's own seeds, for realistic number lengths. */
    private fun natural(wantTotal: IntRange): VaultResult {
        var seed = 1L
        while (true) { val r = SoulVault.play(seed, 10); if (r.totalMult in wantTotal && r.steps.size >= 6) return r; seed++ }
    }

    private fun begin(tag: String, w: Int, h: Int, res: VaultResult): VaultView {
        SystemClock.setCurrentTimeMillis(1_000_000)
        val v = VaultView(ctx, audio, res) {}
        size(v, w, h)
        step(v, 1.0)
        return v
    }

    private fun showcaseJourney(tag: String, w: Int, h: Int) {
        val v = begin(tag, w, h, showcase())
        shot(v, "vs_${tag}_01_intro")
        tap(v)
        step(v, 0.95); shot(v, "vs_${tag}_02_first_orbs")
        nextRespin(v)                                                  // step 1: soul 8, MINI, soul 1
        step(v, 0.55); shot(v, "vs_${tag}_03_respin_spinning")
        assertTrue(until(8.0, v) { v.jackpotAge in 0.18..0.5 }); shot(v, "vs_${tag}_04_jackpot_mini")
        nextRespin(v)                                                  // step 2: MINOR, soul 25, soul 4
        assertTrue(until(8.0, v) { v.awaitingSpin }); step(v, 0.4); shot(v, "vs_${tag}_05_wait_spin")
        press(v)
        assertTrue(until(10.0, v) { v.reaperAge in 0.35..0.6 }); shot(v, "vs_${tag}_06_reaper_beams")
        assertTrue(until(4.0, v) { v.reaperAge in 1.5..1.7 }); shot(v, "vs_${tag}_07_reaper_after")
        nextRespin(v)                                                  // step 4: MAJOR, soul 15
        assertTrue(until(10.0, v) { v.jackpotAge in 0.18..0.5 }); shot(v, "vs_${tag}_08_jackpot_major")
        for (i in 0 until 3) nextRespin(v)                              // three empty respins
        assertTrue(until(20.0, v) { v.talliedCount in 6..7 }); shot(v, "vs_${tag}_09_tally")
        assertTrue(until(30.0, v) { v.summaryAge in 1.3..1.5 }); shot(v, "vs_${tag}_10_summary_big")
        tap(v); step(v, 0.45); shot(v, "vs_${tag}_11_outro")
        assertTrue(v.leaving)
    }

    private fun smallJourney(tag: String, w: Int, h: Int) {
        val v = begin(tag, w, h, small())
        tap(v)
        until(8.0, v) { v.awaitingSpin }; step(v, 0.4); shot(v, "vs_${tag}_12_small_board")
        repeat(3) { nextRespin(v) }
        assertTrue(until(30.0, v) { v.summaryAge in 1.3..1.5 }); shot(v, "vs_${tag}_13_summary_small")
    }

    private fun grandJourney(tag: String, w: Int, h: Int) {
        val v = begin(tag, w, h, grand())
        tap(v)
        nextRespin(v)
        assertTrue(until(30.0, v) { v.grandAge in 1.0..1.2 }); shot(v, "vs_${tag}_14_grand")
        assertTrue(until(30.0, v) { v.summaryAge in 1.3..1.5 }); shot(v, "vs_${tag}_15_summary_grand")
    }

    private fun naturalJourney(tag: String, w: Int, h: Int) {
        val v = begin(tag, w, h, natural(30..70))
        tap(v)
        var guard = 0
        while (guard++ < 40 && !(v.summaryAge >= 0)) { step(v, 0.5); if (v.awaitingSpin) press(v) }
        assertTrue(until(30.0, v) { v.summaryAge in 1.3..1.5 }); shot(v, "vs_${tag}_16_summary_natural")
    }

    @Test fun landShowcase() = showcaseJourney("land", 1280, 800)
    @Test fun portShowcase() = showcaseJourney("port", 800, 1280)
    @Test fun landSmall() = smallJourney("land", 1280, 800)
    @Test fun portSmall() = smallJourney("port", 800, 1280)
    @Test fun landGrand() = grandJourney("land", 1280, 800)
    @Test fun portGrand() = grandJourney("port", 800, 1280)
    @Test fun landNatural() = naturalJourney("land", 1280, 800)
    @Test fun portNatural() = naturalJourney("port", 800, 1280)

    /** The replay must reach exactly the engine's total, whatever the visuals do. */
    @Test fun replayMatchesResult() {
        val res = natural(30..70)
        SystemClock.setCurrentTimeMillis(1_000_000)
        val v = VaultView(ctx, audio, res) {}
        size(v, 1280, 800); step(v, 1.0); tap(v)
        var guard = 0
        while (guard++ < 80 && v.summaryAge < 0) { step(v, 0.5, 100); if (v.awaitingSpin) press(v) }
        assertTrue(v.summaryAge >= 0)
        assertEquals(res.totalMult, v.tallyMult)
        for (i in 0 until SoulVault.CELLS) {
            assertEquals("cell $i kind", res.cells[i]?.kind, v.liveCells[i]?.kind)
            assertEquals("cell $i value", res.cells[i]?.value ?: 0, v.liveValue(i))
        }
    }

    private companion object { const val VAULT_COLS = ReaperConfig.VAULT_COLS }
}

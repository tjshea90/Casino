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
import com.tjshea.neonreaper.engine.Seance
import com.tjshea.neonreaper.engine.SeanceResult
import com.tjshea.neonreaper.engine.VaultResult
import com.tjshea.neonreaper.gl.RecordingSink
import com.tjshea.neonreaper.gl.SceneState
import com.tjshea.neonreaper.gl.SceneStateIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.util.Random

/**
 * Drives the REAL ReaperView state machine through THE SÉANCE (resume after a restart, and the full path from a spin with three
 * coffins), stepping a simulated clock, and checks it ends cleanly: every free spin is played once, nothing stays hidden in the
 * credit meter, the view returns to IDLE. Also records composite frames (HUD + scene state) for the Séance screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class SeanceFlowTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val audio by lazy { Audio(ctx) }
    private val hudOut = File("build/hud").apply { mkdirs() }

    private fun frame(v: View): Bitmap = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    private fun newView(w: Wallet, sink: RecordingSink): ReaperView {
        val host = object : ReaperView.Host {
            override val wallet = w
            override val audio = this@SeanceFlowTest.audio
            override fun openDeposit() {}
            override fun openWithdraw() {}
            override fun openInfo() {}
            override fun openStats() {}
            override fun openAutoPicker(onPicked: (AutoPlan) -> Unit) {}
            override fun openVault(result: VaultResult) {}
            override fun openBuy(stake: Int, onChoice: (com.tjshea.neonreaper.data.Feature) -> Unit) {}
            override fun openSettings() {}
            override fun openRank() {}
            override fun openReality(onContinue: () -> Unit, onStop: () -> Unit) {}
        }
        val v = ReaperView(ctx, host)
        v.gl3d = sink
        v.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1280, 800)
        return v
    }

    private fun record(v: View, sink: RecordingSink, name: String) {
        val bmp = frame(v)
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val snap = SceneState(); synchronized(sink.lock) { snap.copyFrom(sink.state) }
        SceneStateIO.write(File(hudOut, "$name.bin"), bmp.width, bmp.height, sink.window, snap, px)
    }

    private fun findSeance(minRetriggers: Int, maxSpins: Int): SeanceResult {
        var seed = 1L
        while (seed < 20000) {
            val se = Seance.play(seed, 10, 3)
            if (se.retriggers >= minRetriggers && se.totalSpins <= maxSpins && se.payTokens > 0) return se
            seed++
        }
        error("no suitable Séance seed")
    }

    /** Runs frames until [done] or [maxSec] of simulated time; returns the list of distinct phases seen. */
    private fun run(v: ReaperView, maxSec: Double, snapshots: Map<String, (ReaperView) -> Boolean> = emptyMap(), sink: RecordingSink? = null, done: (ReaperView) -> Boolean): List<String> {
        val seen = ArrayList<String>()
        val taken = HashSet<String>()
        var t = 0.0
        while (t < maxSec) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(40))
            frame(v)
            t += 0.04
            val ph = v.debugPhase()
            if (seen.isEmpty() || seen.last() != ph) seen += ph
            if (sink != null) for ((name, cond) in snapshots) if (name !in taken && cond(v)) { taken += name; record(v, sink, name) }
            if (done(v)) return seen
        }
        error("did not finish within $maxSec s; phases: $seen, state ${v.debugPhase()} hidden=${v.debugHidden()} played=${v.debugFreeSpinsPlayed()}")
    }

    @Test fun resumedSeancePlaysEveryFreeSpinAndEndsClean() {
        SystemClock.setCurrentTimeMillis(1_000_000)
        val w = Wallet(ctx).also { it.load() }
        val sink = RecordingSink()
        val v = newView(w, sink)
        val se = findSeance(minRetriggers = 1, maxSpins = 22)
        v.resumeSeance(se)
        assertEquals("SEANCE_TRIGGER", v.debugPhase())
        assertTrue(v.debugSeanceActive())
        var midSnap = false
        val seen = run(v, 400.0, mapOf(
            "s_trigger" to { x -> x.debugPhase() == "SEANCE_TRIGGER" && x.debugFreeSpinsPlayed() == 0 && !midSnap.also { midSnap = false } && false },
        ), sink) { x -> !x.debugSeanceActive() && x.debugPhase() == "IDLE" }
        assertTrue("trigger cinematic seen: $seen", "SEANCE_TRIGGER" in seen)
        assertTrue("free spins ran: $seen", "SPINNING" in seen && "PRESENT" in seen)
        assertTrue("end recap seen: $seen", "SEANCE_END" in seen)
        assertEquals("every free spin was played exactly once", se.spins.size, v.debugFreeSpinsPlayed())
        assertEquals("no unrevealed money is left in the credit meter", 0L, v.debugHidden())
        assertFalse(w.bonusPending)
    }

    @Test fun threeCoffinsOnARealSpinStartTheSeance() {
        SystemClock.setCurrentTimeMillis(2_000_000)
        val w = Wallet(ctx).also { it.load() }
        val sink = RecordingSink()
        val v = newView(w, sink)
        // find a stop combination with three coffins and build the Séance exactly as Round does for it
        val rnd = Random(99)
        var outcome: com.tjshea.neonreaper.engine.SpinOutcome? = null
        for (i in 0 until 2_000_000) {
            val stops = IntArray(5) { rnd.nextInt(ReaperConfig.STRIPS[it].size) }
            val o = ReaperEngine.outcome(10, stops, Mini.NONE)
            if (o.scatterCount == 3) { outcome = o; break }
        }
        val o = outcome ?: error("no 3-coffin spin found")
        val se = Seance.play(12345L, 10, 3)
        v.animateSpin(SpinPlay(o, -1, null, se))
        val shots = HashMap<String, (ReaperView) -> Boolean>()
        shots["s_spin"] = { x -> x.debugPhase() == "SPINNING" }
        shots["s_trigger"] = { x -> x.debugPhase() == "SEANCE_TRIGGER" && x.debugPhaseAge() > 2.3 }
        shots["s_trigger_early"] = { x -> x.debugPhase() == "SEANCE_TRIGGER" && x.debugPhaseAge() > 0.9 }
        var freeShots = 0
        shots["s_free"] = { x -> x.debugSeanceActive() && x.debugPhase() == "PRESENT" && x.debugFreeSpinsPlayed() >= 2 && freeShots++ == 3 }
        shots["s_end"] = { x -> x.debugPhase() == "SEANCE_END" }
        val seen = run(v, 400.0, shots, sink) { x -> !x.debugSeanceActive() && x.debugPhase() == "IDLE" }
        assertTrue("spin -> trigger -> free spins -> end: $seen", seen.indexOf("SEANCE_TRIGGER") > seen.indexOf("SPINNING") && "SEANCE_END" in seen)
        assertEquals(0L, v.debugHidden())
    }
}

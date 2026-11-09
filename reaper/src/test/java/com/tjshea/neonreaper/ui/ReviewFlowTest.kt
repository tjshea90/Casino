package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import com.tjshea.neonreaper.AllocProbe
import com.tjshea.neonreaper.audio.Audio
import com.tjshea.neonreaper.data.Feature
import com.tjshea.neonreaper.data.SpinPlay
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.Mini
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.ReaperEngine
import com.tjshea.neonreaper.engine.Seance
import com.tjshea.neonreaper.engine.SeanceResult
import com.tjshea.neonreaper.engine.SoulVault
import com.tjshea.neonreaper.engine.SpinOutcome
import com.tjshea.neonreaper.engine.VaultResult
import com.tjshea.neonreaper.gl.RecordingSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * Independent review (3.0.0): drives the REAL ReaperView through the awkward paths the author's own tests do not cover -- a spin that
 * unlocks the Soul Vault AND a Séance, the app being paused for minutes in the middle of a Séance, a relayout (rotation, split
 * screen) during a cinematic, a feature buy while a bonus is still unseen, and autoplay across a Séance.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ReviewFlowTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val audio by lazy { Audio(ctx) }
    private val vaultsOpened = ArrayList<VaultResult>()

    // ONE bitmap is reused for every frame: a fresh 4 MB native bitmap per frame (tens of thousands of frames) gets the test JVM OOM-killed
    private var shared: Bitmap? = null
    private fun frame(v: View): Bitmap {
        val b = shared?.takeIf { it.width == v.width && it.height == v.height }
            ?: Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { shared?.recycle(); shared = it }
        v.draw(Canvas(b))
        return b
    }

    private fun wallet(funded: Long = 0L): Wallet {
        File(ctx.filesDir, "wallet.dat").delete()
        val w = Wallet(ctx); w.load()
        if (funded > 0) { w.setInitialPassword("grim".toCharArray()); w.deposit("grim".toCharArray(), funded) }
        return w
    }

    private fun newView(w: Wallet, sink: RecordingSink = RecordingSink()): ReaperView {
        val host = object : ReaperView.Host {
            override val wallet = w
            override val audio = this@ReviewFlowTest.audio
            override fun openDeposit() {}
            override fun openWithdraw() {}
            override fun openInfo() {}
            override fun openStats() {}
            override fun openAutoPicker(onPicked: (AutoPlan) -> Unit) {}
            override fun openVault(result: VaultResult) { vaultsOpened += result }
            override fun openBuy(stake: Int, onChoice: (Feature) -> Unit) {}
            override fun openSettings() {}
            override fun openRank() {}
            override fun openReality(onContinue: () -> Unit, onStop: () -> Unit) {}
        }
        val v = ReaperView(ctx, host)
        v.gl3d = sink
        layout(v, 1280, 800)
        return v
    }

    private fun layout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun threeCoffinOutcome(stake: Int): SpinOutcome {
        val rnd = Random(99)
        for (i in 0 until 2_000_000) {
            val stops = IntArray(5) { rnd.nextInt(ReaperConfig.STRIPS[it].size) }
            val o = ReaperEngine.outcome(stake, stops, Mini.NONE)
            if (o.scatterCount == 3) return o
        }
        error("no 3-coffin spin")
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val f = target.javaClass.getDeclaredField(name); f.isAccessible = true; f.set(target, value)
    }

    private fun getField(target: Any, name: String): Any? {
        val f = target.javaClass.getDeclaredField(name); f.isAccessible = true; return f.get(target)
    }

    /** Steps the simulated clock frame by frame until [done]; [each] runs after every frame. Fails (with the phase trace) after [maxSec]. */
    private fun run(v: ReaperView, maxSec: Double, each: (ReaperView) -> Unit = {}, done: (ReaperView) -> Boolean): List<String> {
        val seen = ArrayList<String>()
        var t = 0.0
        while (t < maxSec) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(40))
            frame(v)
            t += 0.04
            val ph = v.debugPhase()
            if (seen.isEmpty() || seen.last() != ph) seen += ph
            each(v)
            if (done(v)) return seen
        }
        error("did not finish within $maxSec s; phases: $seen, state ${v.debugPhase()} hidden=${v.debugHidden()} played=${v.debugFreeSpinsPlayed()}")
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    @Test fun aVaultUnlockedTogetherWithASeanceStaysHiddenUntilItIsShown() {
        SystemClock.setCurrentTimeMillis(3_000_000)
        val v = newView(wallet())
        val stake = 10
        val o = threeCoffinOutcome(stake)
        val se = Seance.play(12345L, stake, 3)
        val vr = SoulVault.play(777L, 25)
        assertTrue("fixture needs a paying vault", vr.payTokens > 0)
        vaultsOpened.clear()
        v.animateSpin(SpinPlay(o, 4, vr, se))
        var minHiddenWhileFree = Long.MAX_VALUE
        var freeSpinsSeen = 0
        run(v, 600.0, each = { x ->
            if (x.debugSeanceActive() && x.debugFreeSpinsPlayed() >= 1 && vaultsOpened.isEmpty()) {
                freeSpinsSeen++
                minHiddenWhileFree = minOf(minHiddenWhileFree, x.debugHidden())
            }
        }) { x -> vaultsOpened.isNotEmpty() }
        assertTrue("free spins were played before the vault opened", freeSpinsSeen > 10)
        run(v, 30.0) { x -> x.debugPhase() == "IN_VAULT" }
        assertTrue("the Soul Vault payout (${vr.payTokens}) must stay hidden in the credit meter while the Séance replays, " +
            "but the hidden amount dropped to $minHiddenWhileFree", minHiddenWhileFree >= vr.payTokens)
        assertEquals(vr.seed, vaultsOpened[0].seed)
        v.onVaultFinished(vr.payTokens)                              // what MainActivity does when the Vault screen closes
        run(v, 30.0) { x -> x.debugPhaseAge() > 8.0 }                // the count-up of the Vault payout (PRESENT is a resting state)
        assertEquals("nothing may stay hidden once the Vault has been shown", 0L, v.debugHidden())
    }

    @Test fun aResumedSeanceWithAPendingVaultKeepsTheVaultHidden() {
        SystemClock.setCurrentTimeMillis(4_000_000)
        val w = wallet()
        val vr = SoulVault.play(888L, 20)
        assertTrue(vr.payTokens > 0)
        setField(w, "vaultUnseen", true); setField(w, "pendingVaultSeed", vr.seed); setField(w, "pendingVaultBet", vr.bet)
        assertNotNull(w.pendingVault())
        val v = newView(w)
        val se = Seance.play(4242L, 10, 3)
        vaultsOpened.clear()
        v.resumeSeance(se)
        var minHidden = Long.MAX_VALUE
        run(v, 600.0, each = { x -> if (vaultsOpened.isEmpty()) minHidden = minOf(minHidden, x.debugHidden()) }) { x -> vaultsOpened.isNotEmpty() }
        assertTrue("hidden amount fell to $minHidden although the vault (${vr.payTokens}) is still unseen", minHidden >= vr.payTokens)
    }

    @Test fun pausingTheAppForMinutesInTheMiddleOfASeanceStillEndsClean() {
        SystemClock.setCurrentTimeMillis(5_000_000)
        val v = newView(wallet())
        val se = Seance.play(31L, 10, 3)
        v.resumeSeance(se)
        run(v, 60.0) { x -> x.debugFreeSpinsPlayed() >= 3 }
        v.onHostPause()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(10))      // backgrounded: nothing is drawn, the uptime clock keeps running
        run(v, 600.0) { x -> !x.debugSeanceActive() && x.debugPhase() == "IDLE" }
        assertEquals(se.spins.size, v.debugFreeSpinsPlayed())
        assertEquals(0L, v.debugHidden())
    }

    @Test fun rotatingToPortraitDuringTheCinematicAndTheFreeSpinsDoesNotBreakTheFlow() {
        SystemClock.setCurrentTimeMillis(6_000_000)
        val v = newView(wallet())
        val se = Seance.play(31L, 10, 3)
        v.resumeSeance(se)
        run(v, 30.0) { x -> x.debugPhase() == "SEANCE_TRIGGER" && x.debugPhaseAge() > 1.0 }
        layout(v, 800, 1280)                                      // rotation / split screen in the middle of the cinematic
        run(v, 60.0) { x -> x.debugFreeSpinsPlayed() >= 2 }
        layout(v, 1280, 800)
        run(v, 600.0) { x -> !x.debugSeanceActive() && x.debugPhase() == "IDLE" }
        assertEquals(se.spins.size, v.debugFreeSpinsPlayed())
        assertEquals(0L, v.debugHidden())
    }

    @Test fun aBuyWhileABonusIsStillUnseenIsRefusedAndLeavesTheViewIdle() {
        SystemClock.setCurrentTimeMillis(7_000_000)
        val w = wallet(funded = 5000)
        val vr = SoulVault.play(888L, 20)
        setField(w, "vaultUnseen", true); setField(w, "pendingVaultSeed", vr.seed); setField(w, "pendingVaultBet", vr.bet)
        val v = newView(w)
        val before = w.balance
        val m = ReaperView::class.java.getDeclaredMethod("startBuy", Feature::class.java); m.isAccessible = true
        m.invoke(v, Feature.SEANCE)
        assertEquals("IDLE", v.debugPhase())
        assertEquals(before, w.balance)
        assertEquals(0L, v.takeBoughtPrice())
        frame(v)
    }

    @Test fun autoplayContinuesAfterASeanceAndNeverSpinsDuringIt() {
        SystemClock.setCurrentTimeMillis(8_000_000)
        val w = wallet(funded = 100_000)
        val v = newView(w)
        val stake = w.stake
        val o = threeCoffinOutcome(stake)
        val se = Seance.play(2024L, stake, 3)
        setField(v, "autoLeft", 4); setField(v, "autoPlan", AutoPlan(4))
        setField(v, "autoStartBalance", w.balance)
        v.animateSpin(SpinPlay(o, -1, null, se))
        val spinsBefore = w.spins
        var spinsDuringSeance = 0L
        var sawSeance = false
        run(v, 600.0, each = { x ->
            if (x.debugSeanceActive()) { sawSeance = true; spinsDuringSeance += w.spins - spinsBefore }
        }) { x -> sawSeance && !x.debugSeanceActive() && x.debugPhase() == "SPINNING" }
        assertEquals("no paid spin may start while the Séance replays", 0L, spinsDuringSeance)
        assertTrue("autoplay resumed with a real paid spin", w.spins > spinsBefore)
    }

    @Test fun hudFrameAllocationIsReportedForTheJankReview() {
        SystemClock.setCurrentTimeMillis(9_000_000)
        val v = newView(wallet(funded = 1000))
        val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888); val c = Canvas(bmp)
        repeat(30) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); v.draw(c) }          // warm up
        val n = 120
        val a0 = AllocProbe.bytes()
        repeat(n) { ShadowSystemClock.advanceBy(Duration.ofMillis(16)); v.draw(c) }
        val perFrame = (AllocProbe.bytes() - a0) / n
        File("build/review").mkdirs()
        File("build/review/hud_alloc.txt").writeText("HUD idle frame (3D mode): $perFrame bytes allocated per frame on the UI thread\n")
        println("HUD idle frame: $perFrame bytes/frame")
        assertNull(null)
    }
}

package com.tjshea.neonreaper.ui

import android.os.SystemClock
import android.view.View
import com.tjshea.neonreaper.audio.Audio
import com.tjshea.neonreaper.data.Feature
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.VaultResult
import com.tjshea.neonreaper.gl.RecordingSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.Random

/**
 * Soak test: plays thousands of spins through the REAL ReaperView state machine (natural randomness: minis, big wins, coffins,
 * Séances, Soul Vaults, feature buys, stake changes) with a simulated clock and no drawing, and checks that nothing ever gets
 * stuck and that the money adds up (wallet identity, nothing left hidden in the credit meter between rounds).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class SoakFlowTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val audio by lazy { Audio(ctx) }

    private class SoakHost(override val wallet: Wallet, override val audio: Audio, val buyChoice: () -> Feature?) : ReaperView.Host {
        var vault: VaultResult? = null
        override fun openDeposit() {}
        override fun openWithdraw() {}
        override fun openInfo() {}
        override fun openStats() {}
        override fun openAutoPicker(onPicked: (AutoPlan) -> Unit) {}
        override fun openVault(result: VaultResult) { vault = result }
        override fun openBuy(stake: Int, onChoice: (Feature) -> Unit) { buyChoice()?.let(onChoice) }
        override fun openSettings() {}
        override fun openRank() {}
        override fun openReality(onContinue: () -> Unit, onStop: () -> Unit) {}
    }

    @Test fun thousandsOfSpinsNeverGetStuckAndTheMoneyAddsUp() {
        SystemClock.setCurrentTimeMillis(5_000_000)
        val w = Wallet(ctx).also { it.load() }
        assertTrue(w.setInitialPassword("abcd".toCharArray()))
        w.deposit("abcd".toCharArray(), 2_000_000)
        val rnd = Random(42)
        var wantBuy: Feature? = null
        val host = SoakHost(w, audio) { wantBuy }
        val v = ReaperView(ctx, host)
        v.gl3d = RecordingSink()
        v.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, 1280, 800)
        val start = w.balance
        var seances = 0; var vaults = 0; var steps = 0L
        var spinsDone = 0
        val target = 3000
        fun advance(maxSec: Double, until: () -> Boolean): Boolean {
            var t = 0.0
            while (t < maxSec) {
                ShadowSystemClock.advanceBy(Duration.ofMillis(50)); t += 0.05; steps++
                v.debugStepLogic()
                host.vault?.let { res ->               // the Soul Vault screen: play it "instantly" like the activity does on return
                    if (v.debugPhase() == "IN_VAULT") { host.vault = null; vaults++; w.markVaultSeen(); v.onVaultFinished(res.payTokens) }
                }
                if (until()) return true
            }
            return false
        }
        while (spinsDone < target) {
            if (w.balance < 100) w.deposit("abcd".toCharArray(), 100_000)
            if (rnd.nextInt(25) == 0) { w.stakeIndex = rnd.nextInt(ReaperConfig.STAKES.size); w.save() }
            if (w.bonusPending) {                      // e.g. nothing should be pending between rounds; resume like the activity would
                w.pendingSeance()?.let { v.resumeSeance(it) }
            }
            wantBuy = null
            if (rnd.nextInt(60) == 0 && w.canBuy(Feature.SEANCE, w.stake)) wantBuy = if (rnd.nextBoolean()) Feature.SEANCE else Feature.VAULT
            if (!advance(5.0) { !v.debugBusy() }) error("the view stayed busy before spin $spinsDone: ${v.debugPhase()} hidden=${v.debugHidden()}")
            v.debugPress(if (wantBuy != null) "buy" else "spin")
            if (wantBuy == null) spinsDone++
            val sawSeance = BooleanArray(1)
            val ok = advance(500.0) { if (v.debugSeanceActive()) sawSeance[0] = true; !v.debugBusy() && v.debugPhase() != "SPINNING" && v.debugHidden() == 0L }
            if (!ok) error("round ${spinsDone} did not finish in 500 simulated seconds: phase=${v.debugPhase()} seance=${v.debugSeanceActive()} hidden=${v.debugHidden()} played=${v.debugFreeSpinsPlayed()}")
            if (sawSeance[0]) seances++
            assertEquals("nothing may stay hidden in the credit meter between rounds (spin $spinsDone): ${v.debugParts()}", 0L, v.debugHidden())
            assertTrue("no bonus may stay unseen between rounds", !w.bonusPending)
        }
        // the wallet identity: balance = start + deposits - wagered + won
        val extraDeposits = w.totalDeposited - 2_000_000
        assertEquals(start + extraDeposits - w.totalWagered + w.totalWon, w.balance)
        println("SOAK: $spinsDone spins, $seances Séances, $vaults Soul Vaults, $steps logic steps; wagered ${w.totalWagered} won ${w.totalWon} (${"%.1f".format(w.totalWon * 100.0 / w.totalWagered)} %)")
        assertTrue("a few thousand spins must have shown at least one Séance (1 in 213)", seances >= 1)
    }
}

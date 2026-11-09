package com.tjshea.neonreaper.ui

import android.os.SystemClock
import com.tjshea.neonreaper.data.Feature
import com.tjshea.neonreaper.data.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

/**
 * Crash / kill recovery through the REAL activity: a Séance that was paid and saved (here: bought) but never shown is replayed when
 * the app starts again, pays nothing a second time and leaves nothing pending.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ResumeTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun anUnseenSeanceIsReplayedAfterARestartAndPaysOnce() {
        SystemClock.setCurrentTimeMillis(9_000_000)
        val w = Wallet(ctx).also { it.load() }
        assertTrue(w.setInitialPassword("abcd".toCharArray()))
        w.deposit("abcd".toCharArray(), 5_000)
        val balanceBefore = w.balance
        val bought = w.buy(Feature.SEANCE, 10)
        assertNotNull(bought)
        val paidAtBuy = w.balance                      // price debited and the whole Séance already credited
        assertTrue(w.bonusPending)
        assertEquals(balanceBefore - bought!!.price + bought.seance!!.payTokens, paidAtBuy)

        // "the app was killed here": a fresh activity loads the same files
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        ShadowLooper.idleMainLooper()
        val game = act.debugGame()
        assertEquals("the unseen Séance replays first", "SEANCE_TRIGGER", game.debugPhase())
        var t = 0.0
        while (t < 400.0 && game.debugSeanceActive() || game.debugPhase() != "IDLE" && t < 400.0) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(50)); t += 0.05
            game.debugStepLogic()
            if (!game.debugSeanceActive() && game.debugPhase() == "IDLE") break
        }
        assertFalse("the replay finished", game.debugSeanceActive())
        assertEquals(0L, game.debugHidden())

        val after = Wallet(ctx).also { it.load() }
        assertFalse("nothing is left pending", after.bonusPending)
        assertEquals("the replay paid nothing a second time", paidAtBuy, after.balance)
    }
}

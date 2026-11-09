package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Independent full-game simulation (base + mini features + coffins + THE SÉANCE + relics + Soul Vault) at several constant
 * stakes. It plays through [Round.play], the very function the Wallet calls, so it exercises the shipped logic and RNG order.
 */
class FullGameTest {
    @Test fun rtpHoldsAtEveryStake() {
        val stakes = ReaperConfig.STAKES
        for (stake in intArrayOf(1, 5, 10, 25, 40)) {
            val rng = Random(stake * 7919L)
            val spins = 4_000_000
            var bet = 0L; var won = 0L; var hits = 0; var vaults = 0; var seances = 0; var maxWin = 0L
            val meter = RelicMeter()
            var sumSq = 0.0
            for (i in 0 until spins) {
                bet += stake
                val r = Round.play(rng, stake, meter)
                if (r.vault != null) vaults++
                if (r.seance != null) seances++
                val w = r.totalTokens
                if (r.outcome.payTokens > 0) hits++
                if (w > maxWin) maxWin = w
                won += w
                val x = w.toDouble() / stake
                sumSq += x * x
            }
            val rtp = won.toDouble() / bet
            val se = Math.sqrt(sumSq / spins - rtp * rtp) / Math.sqrt(spins.toDouble())
            val expected = ReaperConfig.RTP_BY_STAKE[stakes.indexOf(stake)]
            println("stake %2d: RTP %.4f (theory %.4f, 1 sigma %.4f) -> house kept %.2f%% of %d tokens wagered".format(stake, rtp, expected, se, 100 * (1 - rtp), bet))
            println("stake %2d: hit %.3f (1 in %.2f)  Soul Vault every %.0f spins  Seance every %.0f spins  max win %d tokens"
                .format(stake, hits.toDouble() / spins, spins.toDouble() / hits, spins.toDouble() / vaults, spins.toDouble() / seances, maxWin))
            assertEquals("stake $stake", expected, rtp, Math.max(5 * se, 0.003))
            assertTrue("the house must keep at least ~6% at stake $stake", rtp <= ReaperConfig.TARGET_RTP + 5 * se)
            assertTrue("published win frequency at stake $stake", hits.toDouble() / spins in ReaperConfig.HIT_RATE_RANGE)
            assertTrue("a Soul Vault about every ${ReaperConfig.VAULT_EVERY_TEXT} spins at stake $stake", spins.toDouble() / vaults in ReaperConfig.VAULT_EVERY_RANGE)
            assertTrue("a Seance about every ${ReaperConfig.SEANCE_EVERY_TEXT} spins at stake $stake", spins.toDouble() / seances in ReaperConfig.SEANCE_EVERY_RANGE)
        }
    }
}

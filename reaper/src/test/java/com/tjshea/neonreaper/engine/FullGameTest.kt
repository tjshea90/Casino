package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Independent full-game simulation (base + mini features + relics + Soul Vault) at several constant stakes. */
class FullGameTest {
    @Test fun rtpHoldsAtEveryStake() {
        val stakes = ReaperConfig.STAKES
        for (stake in intArrayOf(1, 5, 10, 25, 40)) {
            val rng = Random(stake * 7919L)
            val spins = 4_000_000
            var bet = 0L; var won = 0L; var hits = 0; var bonuses = 0; var maxWin = 0L
            val meter = RelicMeter()
            var sumSq = 0.0
            for (i in 0 until spins) {
                bet += stake
                val o = ReaperEngine.spin(rng, stake)
                var w = o.payTokens
                if (rng.nextDouble() < ReaperConfig.RELIC_CHANCE) {
                    val vaultBet = meter.collect(stake)
                    if (vaultBet > 0) {
                        bonuses++
                        w += SoulVault.play(rng.nextLong(), vaultBet).payTokens
                    }
                }
                if (w > 0) hits++
                if (w > maxWin) maxWin = w
                won += w
                val x = w.toDouble() / stake
                sumSq += x * x
            }
            val rtp = won.toDouble() / bet
            val se = Math.sqrt(sumSq / spins - rtp * rtp) / Math.sqrt(spins.toDouble())
            val expected = ReaperConfig.RTP_BY_STAKE[stakes.indexOf(stake)]
            println("stake %2d: RTP %.4f (theory %.4f, 1 sigma %.4f) -> house kept %.2f%% of %d tokens wagered".format(stake, rtp, expected, se, 100 * (1 - rtp), bet))
            println("stake %2d: hit %.3f (1 in %.2f)  bonus every %.0f spins  max win %d tokens"
                .format(stake, hits.toDouble() / spins, spins.toDouble() / hits, spins.toDouble() / bonuses, maxWin))
            assertEquals("stake $stake", expected, rtp, Math.max(5 * se, 0.003))
            assertTrue("the house must keep at least ~6% at stake $stake", rtp <= ReaperConfig.TARGET_RTP + 5 * se)
            assertTrue("about 1 win in 3 spins at stake $stake", hits.toDouble() / spins in 0.31..0.345)
            assertTrue("a Soul Vault about every 125 spins at stake $stake", spins.toDouble() / bonuses in 110.0..140.0)
        }
    }
}

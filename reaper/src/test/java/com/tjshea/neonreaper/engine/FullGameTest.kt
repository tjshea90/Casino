package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Independent full-game simulation (base + Reaper's Touch + relics + Soul Vault) at several stakes. */
class FullGameTest {
    @Test fun rtpHoldsAtEveryStake() {
        for (stake in intArrayOf(1, 5, 10, 25, 40)) {
            val rng = Random(stake * 7919L)
            val spins = 4_000_000
            var bet = 0L; var won = 0L; var relics = 0; var hits = 0; var bonuses = 0; var maxWin = 0L
            for (i in 0 until spins) {
                bet += stake
                val o = ReaperEngine.spin(rng, stake)
                var w = o.payTokens
                if (rng.nextDouble() < ReaperConfig.RELIC_CHANCE && ++relics == ReaperConfig.RELIC_COUNT) {
                    relics = 0; bonuses++
                    w += SoulVault.play(rng.nextLong(), stake).payTokens
                }
                if (w > 0) hits++
                if (w > maxWin) maxWin = w
                won += w
            }
            val rtp = won.toDouble() / bet
            println("stake %2d: house kept %.2f%% of %d tokens wagered".format(stake, 100 * (1 - rtp), bet))
            println("stake %2d: RTP %.4f  hit %.3f  bonus every %.0f spins  max win %d tokens"
                .format(stake, rtp, hits.toDouble() / spins, spins.toDouble() / bonuses, maxWin))
            assertEquals("stake $stake", 0.87, rtp, 0.02)
            assertTrue("about 1 win in 3.5 spins at stake $stake", hits.toDouble() / spins in 0.27..0.31)
        }
    }
}

package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The headline guarantee, calculated exactly (no simulation noise): Neon Reaper's theoretical RTP is 94.00 % and
 * the house edge is therefore 6.00 % of every token wagered. Fast and deterministic apart from the vault
 * cross-check, so CI runs it on every push.
 */
class RtpExactTest {

    private val m = ExactMath.means()

    @Test fun printParSheet() {
        val f = ReaperConfig.FEATURE_RATE
        println("PAR: base %.4f (per-line hit %.5f) | mini: reaper %.3f raven %.3f pumpkin %.3f skull %.3f -> mix %.3f | vault %.3f (P full board 1/%.0f)"
            .format(m.base, m.hitPerLine, m.reaper, m.raven, m.pumpkin, m.skull, m.mini, m.vault, 1 / m.vaultFullBoard))
        println("RTP budget: base %.3f%% + mini features %.3f%% + Soul Vault %.3f%% = %.4f%%   (bonus every %.0f spins, mini feature every %.0f spins)"
            .format(100 * (1 - f) * m.base, 100 * f * m.mini, 100 * m.trig * m.vault, 100 * m.uncappedRtp, 1 / m.trig, 1 / f))
    }

    @Test fun theoreticalRtpIs94Percent() {
        assertEquals(ReaperConfig.TARGET_RTP, m.uncappedRtp, 0.0002)
        // the house edge must never be smaller than advertised
        assertTrue("RTP ${m.uncappedRtp} must not exceed ${ReaperConfig.TARGET_RTP}", m.uncappedRtp <= ReaperConfig.TARGET_RTP + 1e-4)
    }

    /** The Soul Vault's exact mean must match the real implementation (guards against a wrong derivation). */
    @Test fun vaultExactMeanMatchesTheRealCode() {
        val rng = Random(99)
        val n = 600_000
        var sum = 0.0; var sumSq = 0.0
        for (i in 0 until n) { val v = SoulVault.play(rng.nextLong(), 1).totalMult.toDouble(); sum += v; sumSq += v * v }
        val mean = sum / n
        val sd = Math.sqrt(sumSq / n - mean * mean)
        val se = sd / Math.sqrt(n.toDouble())
        println("Vault: exact %.4f  simulated %.4f +/- %.4f (SD %.1f)".format(m.vault, mean, se, sd))
        assertEquals(m.vault, mean, 5 * se)
    }

    /** Same check for each mini feature type against the real rollMini + outcome code. */
    @Test fun miniFeatureExactMeansMatchTheRealCode() {
        val rng = Random(7)
        val n = 400_000
        val stops = IntArray(5)
        val sums = DoubleArray(5); val sq = DoubleArray(5)
        for (type in 1..4) for (i in 0 until n) {
            for (r in 0 until 5) stops[r] = rng.nextInt(ReaperConfig.STRIPS[r].size)
            val x = ReaperEngine.outcome(1, stops, ReaperEngine.rollMini(rng, type)).totalMult.toDouble()
            sums[type] += x; sq[type] += x * x
        }
        val exact = doubleArrayOf(0.0, m.reaper, m.raven, m.pumpkin, m.skull)
        for (type in 1..4) {
            val mean = sums[type] / n
            val se = Math.sqrt(sq[type] / n - mean * mean) / Math.sqrt(n.toDouble())
            println("mini %s: exact %.4f simulated %.4f +/- %.4f".format(ReaperConfig.MINI_NAMES[type], exact[type], mean, se))
            assertEquals(ReaperConfig.MINI_NAMES[type], exact[type], mean, 5 * se)
        }
    }

    @Test fun baseGameExactMeanMatchesTheRealCode() {
        val rng = Random(21)
        val n = 3_000_000
        val stops = IntArray(5)
        var sum = 0.0; var sq = 0.0; var hits = 0
        for (i in 0 until n) {
            for (r in 0 until 5) stops[r] = rng.nextInt(ReaperConfig.STRIPS[r].size)
            val x = ReaperEngine.outcome(1, stops).totalMult.toDouble()
            sum += x; sq += x * x; if (x > 0) hits++
        }
        val mean = sum / n
        val se = Math.sqrt(sq / n - mean * mean) / Math.sqrt(n.toDouble())
        println("Base game: exact %.4f simulated %.4f +/- %.4f | hit rate %.4f (1 in %.2f)".format(m.base, mean, se, hits.toDouble() / n, n.toDouble() / hits))
        assertEquals(m.base, mean, 5 * se)
        // published win frequency ("about 1 in 3"): every win pays at least the stake
        assertTrue(hits.toDouble() / n in 0.31..0.345)
    }

    @Test fun everyWinPaysAtLeastTheStake() {
        for (sym in 0 until ReaperConfig.SYMBOL_COUNT) for (pay in ReaperConfig.PAYS[sym]) assertTrue(pay >= 1)
        assertTrue(ReaperConfig.COIN_VALUES.all { it >= 1 } && ReaperConfig.ORB_VALUES.all { it >= 1 })
    }

    @Test fun configIsInternallyConsistent() {
        assertEquals(ReaperConfig.STAKES.size, ReaperConfig.RTP_BY_STAKE.size)
        assertTrue(ReaperConfig.RTP_BY_STAKE.all { it <= ReaperConfig.TARGET_RTP + 1e-9 })
        assertTrue(ReaperConfig.RTP_BY_STAKE.toList().zipWithNext().all { (a, b) -> b <= a + 1e-9 })   // the cap only lowers RTP
        // characters stay a third of every reel so the 3D cast keeps appearing
        for (strip in ReaperConfig.STRIPS) {
            val chars = strip.count { it in ReaperConfig.HOODED..ReaperConfig.GHOST }
            assertTrue(chars.toDouble() / strip.size >= 0.32)
        }
    }
}

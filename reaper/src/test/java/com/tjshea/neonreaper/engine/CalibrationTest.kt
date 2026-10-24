package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Measures each RTP component per stake (with rounding + 25-book cap) and
 * derives the Reaper's Touch rate that makes every stake exactly 87 %.
 * Prints the FEATURE_RATE table for ReaperConfig and checks the stored table.
 */
class CalibrationTest {

    private fun mean(total: Long, n: Int, stake: Int) = total.toDouble() / n / stake

    @Test fun calibrateFeatureRates() {
        val rng = Random(2024)
        val stakes = ReaperConfig.STAKES
        val nBase = 4_000_000
        val nFeat = 800_000
        val nBonus = 400_000
        val base = LongArray(stakes.size)
        val hits = LongArray(stakes.size)
        val feat = LongArray(stakes.size)
        val bonus = LongArray(stakes.size)
        val stops = IntArray(5)
        val none = IntArray(0)
        for (i in 0 until nBase) {
            for (r in 0 until 5) stops[r] = rng.nextInt(ReaperConfig.STRIPS[r].size)
            val m = ReaperEngine.outcome(1, stops, none).totalMult.toLong()
            if (m == 0L) continue
            for (k in stakes.indices) {
                val p = ReaperEngine.settle(m * stakes[k]); base[k] += p; if (p > 0) hits[k]++
            }
        }
        for (i in 0 until nFeat) {
            for (r in 0 until 5) stops[r] = rng.nextInt(ReaperConfig.STRIPS[r].size)
            val m = ReaperEngine.outcome(1, stops, ReaperEngine.pickFeature(rng)).totalMult.toLong()
            for (k in stakes.indices) feat[k] += ReaperEngine.settle(m * stakes[k])
        }
        var sumBonus = 0.0
        for (i in 0 until nBonus) {
            val m = SoulVault.play(rng.nextLong(), 1).totalMult.toLong()
            sumBonus += m
            for (k in stakes.indices) bonus[k] += ReaperEngine.settle(m * stakes[k])
        }
        val trig = ReaperConfig.RELIC_CHANCE / ReaperConfig.RELIC_COUNT
        val sb = StringBuilder("FEATURE_RATE = ")
        println("bonus mean multiplier %.2f, bonus every %.0f spins".format(sumBonus / nBonus, 1 / trig))
        for (k in stakes.indices) {
            val s = stakes[k]
            val rn = mean(base[k], nBase, s); val rf = mean(feat[k], nFeat, s); val rb = trig * mean(bonus[k], nBonus, s)
            val f = (ReaperConfig.TARGET_RTP - rb - rn) / (rf - rn)
            val stored = ReaperConfig.FEATURE_RATE[k]
            val rtp = (1 - stored) * rn + stored * rf + rb
            println("stake %2d: base %.4f feature %.2fx bonus %.4f -> f=%.4f (1 in %.0f) | stored f gives RTP %.4f | hit %.3f"
                .format(s, rn, rf, rb, f, 1 / f, rtp, hits[k].toDouble() / nBase))
            sb.append("%.4f, ".format(f))
            assertTrue("feature rate must be feasible at stake $s", f in 0.0..0.3)
            assertEquals("stake $s RTP", ReaperConfig.TARGET_RTP, rtp, 0.006)
        }
        println(sb)
    }

    @Test fun lineRules() {
        val W = ReaperConfig.WILD; val H = ReaperConfig.HOODED; val A = ReaperConfig.ACE
        assertEquals(Triple(W, 5, 250), ReaperEngine.evaluateLine(intArrayOf(W, W, W, W, W)))
        assertEquals(Triple(H, 5, 100), ReaperEngine.evaluateLine(intArrayOf(W, H, W, H, H)))
        assertEquals(Triple(W, 3, 5), ReaperEngine.evaluateLine(intArrayOf(W, W, W, A, H)))
        assertEquals(null, ReaperEngine.evaluateLine(intArrayOf(H, A, H, H, H)))
    }

    @Test fun payoutsRoundDownAndCap() {
        assertEquals(30L, ReaperEngine.settle(37))
        assertEquals(0L, ReaperEngine.settle(9))
        assertEquals(2000L, ReaperEngine.settle(99_999))
    }

    @Test fun vaultIsDeterministicPerSeed() {
        val a = SoulVault.play(42L, 7); val b = SoulVault.play(42L, 7)
        assertEquals(a.totalMult, b.totalMult)
        assertEquals(a.steps.size, b.steps.size)
    }
}

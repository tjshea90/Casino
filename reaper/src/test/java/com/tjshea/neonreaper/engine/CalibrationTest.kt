package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Per-stake RTP including the 25-book cap. The means of every component are exact (ExactMath); only the cap
 * (a tail effect: E[(X - 2000/stake)+]) is measured, with millions of samples of the REAL engine code, so the
 * result is accurate to a few hundredths of a percent. Checks ReaperConfig.RTP_BY_STAKE and that no stake can
 * ever return more than 94 %.
 */
class CalibrationTest {

    @Test fun perStakeRtpMatchesDocumentedTableAndNeverExceedsTarget() {
        val m = ExactMath.means()
        val rng = Random(2024)
        val stakes = ReaperConfig.STAKES
        val thr = DoubleArray(stakes.size) { ReaperConfig.MAX_WIN_TOKENS.toDouble() / stakes[it] }
        val nBase = 3_000_000; val nMini = 600_000; val nVault = 500_000
        val tailB = DoubleArray(stakes.size); val tailM = DoubleArray(stakes.size); val tailV = DoubleArray(stakes.size)
        val stops = IntArray(5)
        for (i in 0 until nBase) {
            for (r in 0 until 5) stops[r] = rng.nextInt(ReaperConfig.STRIPS[r].size)
            val x = ReaperEngine.outcome(1, stops).totalMult.toDouble()
            if (x > thr.last()) for (k in stakes.indices) if (x > thr[k]) tailB[k] += x - thr[k]
        }
        for (i in 0 until nMini) {
            for (r in 0 until 5) stops[r] = rng.nextInt(ReaperConfig.STRIPS[r].size)
            val x = ReaperEngine.outcome(1, stops, ReaperEngine.rollMini(rng)).totalMult.toDouble()
            for (k in stakes.indices) if (x > thr[k]) tailM[k] += x - thr[k]
        }
        for (i in 0 until nVault) {
            val x = SoulVault.play(rng.nextLong(), 1).totalMult.toDouble()
            for (k in stakes.indices) if (x > thr[k]) tailV[k] += x - thr[k]
        }
        val f = ReaperConfig.FEATURE_RATE
        val sb = StringBuilder("RTP_BY_STAKE = ")
        for (k in stakes.indices) {
            val rb = m.base - tailB[k] / nBase
            val rm = m.mini - tailM[k] / nMini
            val rv = m.vault - tailV[k] / nVault
            val rtp = (1 - f) * rb + f * rm + m.trig * rv
            println("stake %2d: base %.4f mini %.3fx vault %.3fx -> RTP %.4f (documented %.4f)"
                .format(stakes[k], rb, rm, rv, rtp, ReaperConfig.RTP_BY_STAKE[k]))
            sb.append("%.4f, ".format(rtp))
            assertEquals("documented RTP at stake ${stakes[k]}", ReaperConfig.RTP_BY_STAKE[k], rtp, 0.002)
            assertTrue("stake ${stakes[k]} RTP $rtp must not exceed the 94% target", rtp <= ReaperConfig.TARGET_RTP + 0.0015)
        }
        println(sb)
        assertEquals("stake 1 is exactly the headline RTP", ReaperConfig.TARGET_RTP, m.uncappedRtp, 0.0002)
    }

    @Test fun lineRules() {
        val W = ReaperConfig.WILD; val H = ReaperConfig.HOODED; val A = ReaperConfig.ACE
        assertEquals(Triple(W, 5, 100), ReaperEngine.evaluateLine(intArrayOf(W, W, W, W, W)))
        assertEquals(Triple(H, 5, 40), ReaperEngine.evaluateLine(intArrayOf(W, H, W, H, H)))
        assertEquals(Triple(W, 3, 6), ReaperEngine.evaluateLine(intArrayOf(W, W, W, A, H)))
        assertEquals(null, ReaperEngine.evaluateLine(intArrayOf(H, A, H, H, H)))
    }

    @Test fun payoutsAreExactAndCapped() {
        assertEquals(37L, ReaperEngine.settle(37))
        assertEquals(9L, ReaperEngine.settle(9))
        assertEquals(2000L, ReaperEngine.settle(99_999))
    }

    @Test fun vaultIsDeterministicPerSeed() {
        val a = SoulVault.play(42L, 7); val b = SoulVault.play(42L, 7)
        assertEquals(a.totalMult, b.totalMult)
        assertEquals(a.steps.size, b.steps.size)
    }
}

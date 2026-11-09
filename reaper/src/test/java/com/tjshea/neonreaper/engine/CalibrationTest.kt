package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Per-stake RTP including the 25-book cap. The means of every component are exact (ExactMath); only the cap
 * (a tail effect: E[(X - 2000/stake)+]) is measured, with millions of samples of the REAL engine code, so the
 * result is accurate to a few hundredths of a percent. Checks ReaperConfig.RTP_BY_STAKE, that no stake can
 * ever return more than 94 %, and that a feature BUY never returns more than 93 % at any stake.
 */
class CalibrationTest {

    @Test fun perStakeRtpMatchesDocumentedTableAndNeverExceedsTarget() {
        val m = ExactMath.means()
        val rng = Random(2024)
        val stakes = ReaperConfig.STAKES
        val thr = DoubleArray(stakes.size) { ReaperConfig.MAX_WIN_TOKENS.toDouble() / stakes[it] }
        val nBase = 3_000_000; val nMini = 600_000; val nVault = 500_000
        val tailB = DoubleArray(stakes.size); val tailM = DoubleArray(stakes.size); val tailV = DoubleArray(stakes.size)
        val tailS = Array(6) { DoubleArray(stakes.size) }                 // [coffins][stake]
        val nSeance = intArrayOf(0, 0, 0, 400_000, 250_000, 150_000)
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
        for (c in 3..5) for (i in 0 until nSeance[c]) {
            val x = Seance.play(rng.nextLong(), 1, c).totalMult.toDouble()
            for (k in stakes.indices) if (x > thr[k]) tailS[c][k] += x - thr[k]
        }
        val f = ReaperConfig.FEATURE_RATE
        val w = ReaperConfig.MINI_WEIGHTS
        fun miniP(c: Int) = (w[ReaperConfig.MINI_REAPER] * m.scat.reaper[c] + w[ReaperConfig.MINI_RAVEN] * m.scat.raven[c] +
            w[ReaperConfig.MINI_DEMON] * m.scat.demon[c] + w[ReaperConfig.MINI_SKULL] * m.scat.skull[c]) / w.sum()
        val sb = StringBuilder("RTP_BY_STAKE = ")
        val buyLine = StringBuilder()
        for (k in stakes.indices) {
            val rb = m.base + m.scatPayBase - tailB[k] / nBase
            val rm = m.miniLines + m.scatPayMini - tailM[k] / nMini
            val rv = m.vault - tailV[k] / nVault
            val seanceBase = (3..5).sumOf { c -> m.scat.base[c] * (m.seance.value[c] - tailS[c][k] / nSeance[c]) }
            val seanceMini = (3..5).sumOf { c -> miniP(c) * (m.seance.value[c] - tailS[c][k] / nSeance[c]) }
            val rtp = (1 - f) * (rb + seanceBase) + f * (rm + seanceMini) + m.trigVault * rv
            println("stake %2d: lines %.4f mini %.3fx seance(plain spin) %.4f vault %.3fx -> RTP %.4f (documented %.4f)"
                .format(stakes[k], rb, rm, seanceBase, rv, rtp, ReaperConfig.RTP_BY_STAKE[k]))
            sb.append("%.4f, ".format(rtp))
            assertEquals("documented RTP at stake ${stakes[k]}", ReaperConfig.RTP_BY_STAKE[k], rtp, 0.002)
            assertTrue("stake ${stakes[k]} RTP $rtp must not exceed the 94% target", rtp <= ReaperConfig.TARGET_RTP + 0.0015)
            // feature buys
            val buySeance = (m.seance.value[3] - tailS[3][k] / nSeance[3]) / ReaperConfig.BUY_SEANCE_X
            val buyVault = (m.vault - tailV[k] / nVault) / ReaperConfig.BUY_VAULT_X
            buyLine.append("stake %d: buy Seance %.4f, buy Vault %.4f | ".format(stakes[k], buySeance, buyVault))
            assertTrue("buying the Seance at stake ${stakes[k]} returns $buySeance", buySeance <= 0.93 + 0.003)
            assertTrue("buying the Vault at stake ${stakes[k]} returns $buyVault", buyVault <= 0.93 + 0.003)
        }
        println(sb)
        println(buyLine)
        assertEquals("stake 1 is exactly the headline RTP", ReaperConfig.TARGET_RTP, m.uncappedRtp, 0.0002)
    }

    @Test fun lineRules() {
        val W = ReaperConfig.WILD; val H = ReaperConfig.DOCTOR; val A = ReaperConfig.ACE; val S = ReaperConfig.SCATTER
        assertEquals(Triple(W, 5, 100), ReaperEngine.evaluateLine(intArrayOf(W, W, W, W, W)))
        assertEquals(Triple(H, 5, ReaperConfig.PAYS[H][2]), ReaperEngine.evaluateLine(intArrayOf(W, H, W, H, H)))
        assertEquals(Triple(W, 3, ReaperConfig.PAYS[W][0]), ReaperEngine.evaluateLine(intArrayOf(W, W, W, A, H)))
        assertNull(ReaperEngine.evaluateLine(intArrayOf(H, A, H, H, H)))
        // a coffin never pays on a line and a wild never stands in for it
        assertNull(ReaperEngine.evaluateLine(intArrayOf(S, S, S, S, S)))
        assertNull(ReaperEngine.evaluateLine(intArrayOf(S, W, S, W, S)))
        assertNull(ReaperEngine.evaluateLine(intArrayOf(W, W, S, S, S)))
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

    @Test fun seanceIsDeterministicPerSeedAndFollowsTheRules() {
        for (coffins in 3..5) for (seed in 1L..40L) {
            val a = Seance.play(seed, 5, coffins); val b = Seance.play(seed, 5, coffins)
            assertEquals(a.totalMult, b.totalMult)
            assertEquals(a.spins.size, b.spins.size)
            assertEquals(ReaperConfig.FS_SPINS[coffins], a.startSpins)
            var left = a.startSpins
            for ((i, s) in a.spins.withIndex()) {
                assertEquals(i + 1, s.number)
                assertEquals(minOf(ReaperConfig.FS_RAMP_CAP, i + 1), s.multiplier)          // x1, x2, ... capped
                assertEquals(ReaperConfig.FS_EXTRA[s.outcome.scatterCount], s.extraSpins)    // retrigger rule
                assertEquals(0, s.outcome.scatterMult)                                       // coffins pay nothing in the Seance
                assertEquals(0, s.outcome.mini.coins.sum())                                  // no minis in the Seance
                left += s.extraSpins - 1
                assertEquals(left, s.spinsLeftAfter)
            }
            assertEquals(0, left)
            assertEquals(a.spins.sumOf { it.winMult.toLong() }, a.totalMult)
            assertTrue(a.payTokens <= ReaperConfig.MAX_WIN_TOKENS)
        }
    }
}

package com.tjshea.dragonhoard.engine

import com.tjshea.dragonhoard.engine.GameConfig.REELS
import com.tjshea.dragonhoard.engine.GameConfig.SCATTER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * RTP verification, three independent ways:
 *  1. exact: every symbol combination of one payline is enumerated with its exact probability
 *     (this file) -- the same maths as tools/dragon_model.py (closed form) and a casino PAR sheet;
 *  2. Monte-Carlo: millions of spins through the real engine incl. free spins and retriggers;
 *  3. rules tests that pin the pay/feature behaviour the exact maths assumes.
 */
class RtpTest {

    private class Breakdown(
        val line: Double, val scatter: Double, val trigger: Double, val hitPerLine: Double,
        /** expected free spins awarded per spin (base: per base spin; free reels: retrigger per free spin) */
        val awarded: Double,
    )

    /** Exact expectation per 1-mack line bet (units of total bet for the scatter part). */
    private fun exact(strips: Array<IntArray>): Breakdown {
        val freq = Array(REELS) { r ->
            val f = DoubleArray(GameConfig.SYMBOL_COUNT)
            strips[r].forEach { f[it] += 1.0 / strips[r].size }
            f
        }
        var ev = 0.0
        var hit = 0.0
        val s = IntArray(REELS)
        fun rec(r: Int, p: Double) {
            if (p == 0.0) return
            if (r == REELS) {
                val res = SlotEngine.evaluateLine(s) ?: return
                ev += p * res.third; hit += p
                return
            }
            for (sym in 0 until GameConfig.SYMBOL_COUNT) {
                s[r] = sym; rec(r + 1, p * freq[r][sym])
            }
        }
        rec(0, 1.0)
        // scatters are spaced >= 3 apart (asserted below), so a reel shows at most one: P = 3 * count / length
        var dist = doubleArrayOf(1.0)
        for (r in 0 until REELS) {
            val p = GameConfig.ROWS * freq[r][SCATTER]
            val nd = DoubleArray(dist.size + 1)
            for (k in dist.indices) { nd[k] += dist[k] * (1 - p); nd[k + 1] += dist[k] * p }
            dist = nd
        }
        val scat = (0..5).sumOf { dist[it] * GameConfig.SCATTER_PAYS[it] }
        val trig = (3..5).sumOf { dist[it] }
        val awarded = (3..5).sumOf { dist[it] * GameConfig.FREE_SPINS_BY_SCATTERS[it] }
        return Breakdown(ev, scat, trig, hit, awarded)
    }

    private fun exactRtp(): Double {
        val b = exact(GameConfig.BASE_STRIPS)
        val f = exact(GameConfig.FREE_STRIPS)
        // expected free spins per base spin, including retriggers: N = awarded_b / (1 - awarded_f)
        val n = b.awarded / (1 - f.awarded)
        val fs = n * GameConfig.FREE_SPIN_MULTIPLIER * (f.line + f.scatter)
        val rtp = b.line + b.scatter + fs
        println("Exact: line=%.5f scatter=%.5f freeSpins=%.5f RTP=%.5f trigger=1/%.1f hit/line=%.4f FS/trigger=%.2f"
            .format(b.line, b.scatter, fs, rtp, 1 / b.trigger, b.hitPerLine, n / b.trigger))
        return rtp
    }

    @Test fun stripsHaveExactCountsAndSpacedScatters() {
        for ((strips, counts) in listOf(GameConfig.BASE_STRIPS to GameConfig.BASE_COUNTS,
                                        GameConfig.FREE_STRIPS to GameConfig.FREE_COUNTS)) {
            for (r in 0 until REELS) {
                val c = IntArray(GameConfig.SYMBOL_COUNT)
                strips[r].forEach { c[it]++ }
                assertTrue(c.contentEquals(counts[r]))
                val len = strips[r].size
                for (i in 0 until len) if (strips[r][i] == SCATTER) {
                    assertTrue(strips[r][(i + 1) % len] != SCATTER && strips[r][(i + 2) % len] != SCATTER)
                }
            }
        }
    }

    @Test fun theoreticalRtpIs94Percent() {
        val rtp = exactRtp()
        assertEquals(GameConfig.THEORETICAL_RTP, rtp, 0.0002)
        // the house edge must never be smaller than advertised
        assertTrue("RTP $rtp must not exceed ${GameConfig.THEORETICAL_RTP}", rtp <= GameConfig.THEORETICAL_RTP + 1e-9)
    }

    /** RTP is a fraction of the TOTAL bet, so it must not depend on how many lines are played. */
    @Test fun everyPaylineHasTheSameExactValue() {
        val perLine = (0 until GameConfig.PAYLINES.size).map { l ->
            // symbol marginals seen by line l: reel r, row PAYLINES[l][r]  (uniform stop => count/length for any row)
            val freq = Array(REELS) { r ->
                val strip = GameConfig.BASE_STRIPS[r]
                DoubleArray(GameConfig.SYMBOL_COUNT).also { f ->
                    for (stop in strip.indices) f[strip[(stop + GameConfig.PAYLINES[l][r]) % strip.size]] += 1.0 / strip.size
                }
            }
            var ev = 0.0
            val s = IntArray(REELS)
            fun rec(r: Int, p: Double) {
                if (p == 0.0) return
                if (r == REELS) { ev += p * (SlotEngine.evaluateLine(s)?.third ?: 0); return }
                for (sym in 0 until GameConfig.SYMBOL_COUNT) { s[r] = sym; rec(r + 1, p * freq[r][sym]) }
            }
            rec(0, 1.0)
            ev
        }
        for (v in perLine) assertEquals(perLine[0], v, 1e-12)
    }

    @Test fun lineEvaluationRules() {
        val W = GameConfig.WILD; val T = GameConfig.TIGER; val A = GameConfig.ACE; val S = GameConfig.SCATTER
        val K = GameConfig.KOI; val L = GameConfig.LANTERN
        assertEquals(Triple(W, 5, 1000), SlotEngine.evaluateLine(intArrayOf(W, W, W, W, W)))
        assertEquals(Triple(T, 5, 300), SlotEngine.evaluateLine(intArrayOf(W, T, W, T, T)))
        // 3 wilds then ace: wild pay 25 beats AAAAA? (W W W A A = 5 aces = 50) -> aces win
        assertEquals(Triple(A, 5, 50), SlotEngine.evaluateLine(intArrayOf(W, W, W, A, A)))
        assertEquals(Triple(W, 3, 25), SlotEngine.evaluateLine(intArrayOf(W, W, W, A, T)))
        assertNull(SlotEngine.evaluateLine(intArrayOf(T, A, T, T, T)))
        assertEquals(Triple(W, 3, 25), SlotEngine.evaluateLine(intArrayOf(W, W, W, S, T)))
        // pair pays: only Tiger and Koi, 1x line bet; wild substitutes in the pair
        assertEquals(Triple(T, 2, 1), SlotEngine.evaluateLine(intArrayOf(T, T, A, A, A)))
        assertEquals(Triple(K, 2, 1), SlotEngine.evaluateLine(intArrayOf(W, K, A, L, S)))
        assertEquals(Triple(T, 2, 1), SlotEngine.evaluateLine(intArrayOf(T, W, S, A, L)))
        assertNull(SlotEngine.evaluateLine(intArrayOf(A, A, T, K, L)))      // no pair pay for ace
        assertNull(SlotEngine.evaluateLine(intArrayOf(L, L, T, K, A)))      // ... or lantern
        assertNull(SlotEngine.evaluateLine(intArrayOf(W, W, S, T, K)))      // two wilds alone do not pay
        assertNull(SlotEngine.evaluateLine(intArrayOf(T, S, T, T, T)))      // scatter breaks the run
        assertEquals(Triple(T, 3, 20), SlotEngine.evaluateLine(intArrayOf(W, W, T, A, K)))
        assertEquals(Triple(T, 3, 20), SlotEngine.evaluateLine(intArrayOf(T, T, T, A, K)))
    }

    @Test fun moneyRounding() {
        assertEquals(30L, Money.floorToMack(39))
        assertEquals(80L, Money.toTokens(1, 0, 0))
        assertEquals("1 book 2 macks 5 tokens", Money.describe(105))
    }

    /** Picks stops so that exactly the first [k] reels show a scatter and the rest show none. */
    private fun stopsWithScatters(strips: Array<IntArray>, k: Int): IntArray = IntArray(REELS) { r ->
        val strip = strips[r]
        val withScatter = strip.indices.first { strip[it] == SCATTER }
        if (r < k) (withScatter - 1 + strip.size) % strip.size
        else strip.indices.first { stop -> (0 until GameConfig.ROWS).none { strip[(stop + it) % strip.size] == SCATTER } }
    }

    @Test fun freeSpinsScaleWithScatterCount() {
        val expected = mapOf(2 to 0, 3 to 10, 4 to 15, 5 to 25)
        for ((k, spins) in expected) {
            val r = SlotEngine.evaluate(stopsWithScatters(GameConfig.BASE_STRIPS, k), 1, 10, false)
            assertEquals("scatters", k, r.scatterCount)
            assertEquals("free spins for $k scatters", spins, r.freeSpinsAwarded)
            assertEquals("scatter pay for $k", GameConfig.SCATTER_PAYS[k].toLong() * 10, r.scatterWinMacks)
        }
        // retriggers inside free spins use the same table and the x3 multiplier applies to scatter pays too
        val fr = SlotEngine.evaluate(stopsWithScatters(GameConfig.FREE_STRIPS, 4), 1, 10, true)
        assertEquals(15, fr.freeSpinsAwarded)
        assertEquals(GameConfig.SCATTER_PAYS[4].toLong() * 10 * GameConfig.FREE_SPIN_MULTIPLIER, fr.scatterWinMacks)
    }

    /** Full-game Monte Carlo including free spins and retriggers. */
    @Test fun simulatedRtpAndVolatility() {
        val rng = Random(12345)
        val spins = 3_000_000
        val lines = 10
        var bet = 0L; var won = 0L; var hits = 0; var stakeWins = 0; var features = 0
        var sumSq = 0.0
        var maxWin = 0L
        for (i in 0 until spins) {
            bet += lines
            val r = SlotEngine.spin(rng, 1, lines, false)
            var w = r.totalWinMacks
            var fsLeft = r.freeSpinsAwarded
            if (fsLeft > 0) features++
            while (fsLeft > 0) {
                fsLeft--
                val f = SlotEngine.spin(rng, 1, lines, true)
                w += f.totalWinMacks
                fsLeft += f.freeSpinsAwarded
            }
            if (r.totalWinMacks > 0) hits++            // "hit frequency" counts the base spin's own win, like a casino PAR sheet
            if (w >= lines) stakeWins++                // the spin (with its bonus) returned at least the whole stake
            won += w
            val x = w.toDouble() / lines
            sumSq += x * x
            if (w > maxWin) maxWin = w
        }
        val rtp = won.toDouble() / bet
        val sd = Math.sqrt(sumSq / spins - rtp * rtp)
        val hit = hits.toDouble() / spins
        val stakeWin = stakeWins.toDouble() / spins
        println("Sim: RTP=%.4f hitFreq=%.3f (1 in %.2f) stakeWin=%.3f (1 in %.1f) featureEvery=%.0f spins SD=%.2f maxWin=%dx totalBet"
            .format(rtp, hit, 1 / hit, stakeWin, 1 / stakeWin, spins.toDouble() / features, sd, maxWin / lines))
        // standard error of the mean here is ~0.0032 (SD 5.5 / sqrt(3M)); 5 sigma = 0.016
        assertEquals(GameConfig.THEORETICAL_RTP, rtp, 0.016)
        // Medium volatility: standard deviation per unit bet roughly 3..12
        assertTrue(sd in 3.0..12.0)
        // the figures printed in the in-app rules (GameConfig.*_TEXT) must be true
        assertTrue("any-win frequency $hit should be about 1 in 2.3", hit in 0.40..0.476)
        assertTrue("stake-win frequency $stakeWin should be about 1 in 6", stakeWin in 0.143..0.20)
        val featureEvery = spins.toDouble() / features
        assertTrue("free spins about every 100-120 spins, got $featureEvery", featureEvery in 95.0..125.0)
    }
}

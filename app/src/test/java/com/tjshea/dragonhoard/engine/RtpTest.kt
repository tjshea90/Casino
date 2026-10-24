package com.tjshea.dragonhoard.engine

import com.tjshea.dragonhoard.engine.GameConfig.REELS
import com.tjshea.dragonhoard.engine.GameConfig.SCATTER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RtpTest {

    private class Breakdown(val line: Double, val scatter: Double, val trigger: Double, val hitPerLine: Double)

    /** Exact expectation per 1-mack line bet (units of total bet for scatter part). */
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
        var dist = doubleArrayOf(1.0)
        for (r in 0 until REELS) {
            val p = GameConfig.ROWS * freq[r][SCATTER]
            val nd = DoubleArray(dist.size + 1)
            for (k in dist.indices) { nd[k] += dist[k] * (1 - p); nd[k + 1] += dist[k] * p }
            dist = nd
        }
        val scat = (0..5).sumOf { dist[it] * GameConfig.SCATTER_PAYS[it] }
        val trig = (3..5).sumOf { dist[it] }
        return Breakdown(ev, scat, trig, hit)
    }

    private fun exactRtp(): Double {
        val b = exact(GameConfig.BASE_STRIPS)
        val f = exact(GameConfig.FREE_STRIPS)
        val n = GameConfig.FREE_SPINS_AWARD / (1 - GameConfig.FREE_SPINS_AWARD * f.trigger)
        val fs = b.trigger * n * GameConfig.FREE_SPIN_MULTIPLIER * (f.line + f.scatter)
        val rtp = b.line + b.scatter + fs
        println("Exact: line=%.5f scatter=%.5f freeSpins=%.5f RTP=%.5f trigger=1/%.1f hit/line=%.4f fsAvgSpins=%.2f"
            .format(b.line, b.scatter, fs, rtp, 1 / b.trigger, b.hitPerLine, n))
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

    @Test fun theoreticalRtpIs80Percent() {
        assertEquals(0.80, exactRtp(), 0.005)
    }

    @Test fun lineEvaluationRules() {
        val W = GameConfig.WILD; val T = GameConfig.TIGER; val A = GameConfig.ACE
        assertEquals(Triple(W, 5, 1000), SlotEngine.evaluateLine(intArrayOf(W, W, W, W, W)))
        assertEquals(Triple(T, 5, 300), SlotEngine.evaluateLine(intArrayOf(W, T, W, T, T)))
        // 3 wilds then ace: wild pay 25 beats AAAAA? (W W W A A = 5 aces = 50) -> aces win
        assertEquals(Triple(A, 5, 50), SlotEngine.evaluateLine(intArrayOf(W, W, W, A, A)))
        assertEquals(Triple(W, 3, 25), SlotEngine.evaluateLine(intArrayOf(W, W, W, A, T)))
        assertEquals(null, SlotEngine.evaluateLine(intArrayOf(T, A, T, T, T)))
        assertEquals(Triple(W, 3, 25), SlotEngine.evaluateLine(intArrayOf(W, W, W, SCATTER, T)))
    }

    @Test fun moneyRounding() {
        assertEquals(30L, Money.floorToMack(39))
        assertEquals(80L, Money.toTokens(1, 0, 0))
        assertEquals("1 book 2 macks 5 tokens", Money.describe(105))
    }

    /** Full-game Monte Carlo including free spins and retriggers. */
    @Test fun simulatedRtpAndVolatility() {
        val rng = Random(12345)
        val spins = 3_000_000
        val lines = 10
        var bet = 0L; var won = 0L; var hits = 0; var features = 0
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
            if (w > 0) hits++
            won += w
            val x = w.toDouble() / lines
            sumSq += x * x
            if (w > maxWin) maxWin = w
        }
        val rtp = won.toDouble() / bet
        val sd = Math.sqrt(sumSq / spins - rtp * rtp)
        println("Sim: RTP=%.4f hitFreq=%.3f featureEvery=%.0f spins SD=%.2f maxWin=%dx totalBet"
            .format(rtp, hits.toDouble() / spins, spins.toDouble() / features, sd, maxWin / lines))
        assertEquals(0.80, rtp, 0.02)
        // Medium volatility: standard deviation per unit bet roughly 3..12
        assertTrue(sd in 3.0..12.0)
    }
}

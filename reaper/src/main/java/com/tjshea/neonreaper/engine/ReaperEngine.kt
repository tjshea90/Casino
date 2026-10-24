package com.tjshea.neonreaper.engine

import com.tjshea.neonreaper.engine.ReaperConfig.PAYLINES
import com.tjshea.neonreaper.engine.ReaperConfig.PAYS
import com.tjshea.neonreaper.engine.ReaperConfig.REELS
import com.tjshea.neonreaper.engine.ReaperConfig.ROWS
import com.tjshea.neonreaper.engine.ReaperConfig.WILD
import java.util.Random

class LineWin(val line: Int, val symbol: Int, val count: Int, val mult: Int)

class SpinOutcome(
    val stake: Int,
    val stops: IntArray,
    /** grid[reel][row] after any Reaper's Touch wild reels are applied. */
    val grid: Array<IntArray>,
    /** grid before wild reels (what the reels physically landed on). */
    val landed: Array<IntArray>,
    val wildReels: IntArray,
    val lineWins: List<LineWin>,
) {
    val totalMult: Int = lineWins.sumOf { it.mult }
    val rawTokens: Long get() = totalMult.toLong() * stake
    /** Paid amount: rounded down to the mack, capped at 25 books. */
    val payTokens: Long get() = ReaperEngine.settle(rawTokens)
    val capped: Boolean get() = Money.floorToMack(rawTokens) > ReaperConfig.MAX_WIN_TOKENS
    val roundedAway: Long get() = if (capped) 0 else rawTokens - payTokens
    val feature: Boolean get() = wildReels.isNotEmpty()
}

object ReaperEngine {

    fun settle(rawTokens: Long): Long = Money.floorToMack(rawTokens).coerceAtMost(ReaperConfig.MAX_WIN_TOKENS)

    /** Returns symbol, count, multiplier for one line, or null. */
    fun evaluateLine(s: IntArray): Triple<Int, Int, Int>? {
        var w = 0
        while (w < REELS && s[w] == WILD) w++
        val wildPay = if (w >= 3) PAYS[WILD][w - 3] else 0
        if (w == REELS) return Triple(WILD, 5, wildPay)
        val target = s[w]
        var n = w
        while (n < REELS && (s[n] == target || s[n] == WILD)) n++
        val symPay = if (n >= 3) PAYS[target][n - 3] else 0
        return when {
            symPay == 0 && wildPay == 0 -> null
            wildPay > symPay -> Triple(WILD, w, wildPay)
            else -> Triple(target, n, symPay)
        }
    }

    fun evaluate(grid: Array<IntArray>): List<LineWin> {
        val wins = ArrayList<LineWin>()
        val buf = IntArray(REELS)
        for (l in PAYLINES.indices) {
            val line = PAYLINES[l]
            for (r in 0 until REELS) buf[r] = grid[r][line[r]]
            val res = evaluateLine(buf) ?: continue
            wins += LineWin(l, res.first, res.second, res.third)
        }
        return wins
    }

    fun gridFor(stops: IntArray): Array<IntArray> = Array(REELS) { r ->
        val strip = ReaperConfig.STRIPS[r]
        IntArray(ROWS) { row -> strip[(stops[r] + row) % strip.size] }
    }

    fun outcome(stake: Int, stops: IntArray, wildReels: IntArray): SpinOutcome {
        val landed = gridFor(stops)
        val grid = Array(REELS) { landed[it].clone() }
        for (r in wildReels) for (row in 0 until ROWS) grid[r][row] = WILD
        return SpinOutcome(stake, stops, grid, landed, wildReels, evaluate(grid))
    }

    fun pickFeature(rng: Random): IntArray {
        var x = rng.nextDouble()
        for (i in ReaperConfig.FEATURE_PATTERNS.indices) {
            x -= ReaperConfig.FEATURE_WEIGHTS[i]
            if (x < 0) return ReaperConfig.FEATURE_PATTERNS[i]
        }
        return ReaperConfig.FEATURE_PATTERNS.last()
    }

    fun spin(rng: Random, stake: Int): SpinOutcome {
        val stops = IntArray(REELS) { rng.nextInt(ReaperConfig.STRIPS[it].size) }
        val rate = ReaperConfig.FEATURE_RATE[ReaperConfig.stakeIndex(stake)]
        val wild = if (rng.nextDouble() < rate) pickFeature(rng) else IntArray(0)
        return outcome(stake, stops, wild)
    }
}

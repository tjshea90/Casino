package com.tjshea.dragonhoard.engine

import com.tjshea.dragonhoard.engine.GameConfig.PAYLINES
import com.tjshea.dragonhoard.engine.GameConfig.PAYS
import com.tjshea.dragonhoard.engine.GameConfig.REELS
import com.tjshea.dragonhoard.engine.GameConfig.ROWS
import com.tjshea.dragonhoard.engine.GameConfig.SCATTER
import com.tjshea.dragonhoard.engine.GameConfig.WILD
import java.util.Random

class LineWin(
    val line: Int,
    val symbol: Int,
    val count: Int,
    /** Pay in macks per 1-mack line bet (before line bet and multiplier). */
    val pay: Int,
)

class SpinResult(
    val stops: IntArray,
    val freeSpin: Boolean,
    /** grid[reel][row] */
    val grid: Array<IntArray>,
    val lineWins: List<LineWin>,
    val scatterCount: Int,
    val lineBetMacks: Int,
    val lines: Int,
    val multiplier: Int,
    val freeSpinsAwarded: Int,
) {
    val totalBetMacks: Int get() = lineBetMacks * lines
    val lineWinMacks: Long get() = lineWins.sumOf { it.pay.toLong() } * lineBetMacks * multiplier
    val scatterWinMacks: Long get() =
        GameConfig.SCATTER_PAYS[scatterCount].toLong() * totalBetMacks * multiplier
    val totalWinMacks: Long get() = lineWinMacks + scatterWinMacks
    /** Payout in tokens, floored to a whole mack per the house rule. */
    val totalWinTokens: Long get() = Money.floorToMack(totalWinMacks * Money.TOKENS_PER_MACK)

    fun winMacksFor(w: LineWin): Long = w.pay.toLong() * lineBetMacks * multiplier
}

object SlotEngine {

    /** Evaluates one line of 5 symbols. Returns symbol/count/pay or null. */
    fun evaluateLine(s: IntArray): Triple<Int, Int, Int>? {
        var w = 0
        while (w < REELS && s[w] == WILD) w++
        val wildPay = if (w >= 3) PAYS[WILD][w - 3] else 0
        if (w == REELS) return Triple(WILD, 5, wildPay)
        val target = s[w]
        var symPay = 0
        var n = w
        if (target != SCATTER) {
            while (n < REELS && (s[n] == target || s[n] == WILD)) n++
            symPay = if (n >= 3) PAYS[target][n - 3] else 0
        }
        return when {
            symPay == 0 && wildPay == 0 -> null
            wildPay > symPay -> Triple(WILD, w, wildPay)
            else -> Triple(target, n, symPay)
        }
    }

    fun gridFor(stops: IntArray, strips: Array<IntArray>): Array<IntArray> =
        Array(REELS) { r ->
            val strip = strips[r]
            IntArray(ROWS) { row -> strip[(stops[r] + row) % strip.size] }
        }

    fun spin(rng: Random, lineBetMacks: Int, lines: Int, freeSpin: Boolean): SpinResult {
        val strips = if (freeSpin) GameConfig.FREE_STRIPS else GameConfig.BASE_STRIPS
        val stops = IntArray(REELS) { rng.nextInt(strips[it].size) }
        return evaluate(stops, lineBetMacks, lines, freeSpin)
    }

    fun evaluate(stops: IntArray, lineBetMacks: Int, lines: Int, freeSpin: Boolean): SpinResult {
        val strips = if (freeSpin) GameConfig.FREE_STRIPS else GameConfig.BASE_STRIPS
        val grid = gridFor(stops, strips)
        val wins = ArrayList<LineWin>()
        val buf = IntArray(REELS)
        for (l in 0 until lines) {
            val line = PAYLINES[l]
            for (r in 0 until REELS) buf[r] = grid[r][line[r]]
            val res = evaluateLine(buf) ?: continue
            wins += LineWin(l, res.first, res.second, res.third)
        }
        var scatters = 0
        for (r in 0 until REELS) for (row in 0 until ROWS) if (grid[r][row] == SCATTER) scatters++
        val awarded = if (scatters >= GameConfig.MIN_SCATTERS_FOR_FEATURE) GameConfig.FREE_SPINS_AWARD else 0
        return SpinResult(
            stops, freeSpin, grid, wins, scatters, lineBetMacks, lines,
            if (freeSpin) GameConfig.FREE_SPIN_MULTIPLIER else 1, awarded
        )
    }
}

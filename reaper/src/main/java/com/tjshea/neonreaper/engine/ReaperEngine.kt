package com.tjshea.neonreaper.engine

import com.tjshea.neonreaper.engine.ReaperConfig.PAYLINES
import com.tjshea.neonreaper.engine.ReaperConfig.PAYS
import com.tjshea.neonreaper.engine.ReaperConfig.REELS
import com.tjshea.neonreaper.engine.ReaperConfig.ROWS
import com.tjshea.neonreaper.engine.ReaperConfig.WILD
import java.util.Random

class LineWin(val line: Int, val symbol: Int, val count: Int, val mult: Int)

/** What a mini feature does to one spin. Cells are indexed reel * ROWS + row. */
class Mini(
    val type: Int = ReaperConfig.MINI_NONE,
    val wildReels: IntArray = IntArray(0),
    val wildCells: IntArray = IntArray(0),
    /** coins[cell] = coin value in multiples of the stake (0 = no coin). */
    val coins: IntArray = IntArray(REELS * ROWS),
    val multiplier: Int = 1,
) {
    companion object { val NONE = Mini() }
}

class SpinOutcome(
    val stake: Int,
    val stops: IntArray,
    /** grid[reel][row] after the mini feature's wilds are applied. */
    val grid: Array<IntArray>,
    /** grid before any wilds (what the reels physically landed on). */
    val landed: Array<IntArray>,
    val mini: Mini,
    val lineWins: List<LineWin>,
) {
    val wildReels: IntArray get() = mini.wildReels
    val feature: Boolean get() = mini.type != ReaperConfig.MINI_NONE
    val lineMult: Int = lineWins.sumOf { it.mult }
    val coinMult: Int = mini.coins.sum()
    /** Total in multiples of the stake: line wins × multiplier + soul coins. */
    val totalMult: Int = lineMult * mini.multiplier + coinMult
    val rawTokens: Long get() = totalMult.toLong() * stake
    /** Paid amount in exact tokens, capped at 25 books. */
    val payTokens: Long get() = ReaperEngine.settle(rawTokens)
    val capped: Boolean get() = rawTokens > ReaperConfig.MAX_WIN_TOKENS
}

object ReaperEngine {

    /** In-game wins are exact tokens (no rounding); only the 25-book cap applies. */
    fun settle(rawTokens: Long): Long = rawTokens.coerceIn(0, ReaperConfig.MAX_WIN_TOKENS)

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

    fun outcome(stake: Int, stops: IntArray, mini: Mini = Mini.NONE): SpinOutcome {
        val landed = gridFor(stops)
        val grid = Array(REELS) { landed[it].clone() }
        for (r in mini.wildReels) for (row in 0 until ROWS) grid[r][row] = WILD
        for (cell in mini.wildCells) grid[cell / ROWS][cell % ROWS] = WILD
        return SpinOutcome(stake, stops, grid, landed, mini, evaluate(grid))
    }

    private fun pick(rng: Random, weights: DoubleArray): Int {
        var x = rng.nextDouble() * weights.sum()
        for (i in weights.indices) { x -= weights[i]; if (x < 0) return i }
        return weights.size - 1
    }

    private fun randomCells(rng: Random, n: Int): IntArray {
        val all = (0 until REELS * ROWS).toMutableList()
        return IntArray(n) { all.removeAt(rng.nextInt(all.size)) }.sortedArray()
    }

    fun pickFeature(rng: Random): IntArray = ReaperConfig.FEATURE_PATTERNS[pick(rng, ReaperConfig.FEATURE_WEIGHTS)]

    /** Rolls a random mini feature of the given type (or a random type). */
    fun rollMini(rng: Random, type: Int = pick(rng, ReaperConfig.MINI_WEIGHTS)): Mini = when (type) {
        ReaperConfig.MINI_REAPER -> Mini(type, wildReels = pickFeature(rng))
        ReaperConfig.MINI_RAVEN -> Mini(type, wildCells = randomCells(rng, ReaperConfig.RAVEN_WILDS[pick(rng, ReaperConfig.RAVEN_WILD_WEIGHTS)]))
        ReaperConfig.MINI_PUMPKIN -> {
            val coins = IntArray(REELS * ROWS)
            for (c in randomCells(rng, ReaperConfig.COIN_COUNTS[pick(rng, ReaperConfig.COIN_COUNT_WEIGHTS)]))
                coins[c] = ReaperConfig.COIN_VALUES[pick(rng, ReaperConfig.COIN_WEIGHTS)]
            Mini(type, coins = coins)
        }
        ReaperConfig.MINI_SKULL -> Mini(type, wildCells = randomCells(rng, ReaperConfig.SKULL_WILDS),
            multiplier = ReaperConfig.SKULL_MULTS[pick(rng, ReaperConfig.SKULL_MULT_WEIGHTS)])
        else -> Mini.NONE
    }

    fun spin(rng: Random, stake: Int): SpinOutcome {
        val stops = IntArray(REELS) { rng.nextInt(ReaperConfig.STRIPS[it].size) }
        val rate = ReaperConfig.FEATURE_RATE[ReaperConfig.stakeIndex(stake)]
        val mini = if (rng.nextDouble() < rate) rollMini(rng) else Mini.NONE
        return outcome(stake, stops, mini)
    }
}

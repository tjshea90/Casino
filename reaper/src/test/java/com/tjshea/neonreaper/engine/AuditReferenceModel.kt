package com.tjshea.neonreaper.engine

import java.util.Random

/**
 * Test-side re-implementation of Neon Reaper's rules, written from the written rules (docs / ReaperConfig comments) and NOT by
 * calling ReaperEngine / Seance / SoulVault, so that [AuditDifferentialTest] can demand EXACT equality with the shipped engine on
 * hundreds of thousands of random inputs. Where a result depends on the random generator, the same java.util.Random call order is
 * used (that order is part of the replay contract: a saved seed must regenerate the same bonus).
 */
object AuditRef {
    private val C = ReaperConfig

    /** Pay (x stake) of one payline: the best pay over every symbol that could head the line; coffins never pay on a line. */
    fun linePay(a: IntArray): Int {
        var best = 0
        var wilds = 0
        while (wilds < 5 && a[wilds] == C.WILD) wilds++
        if (wilds >= 3) best = C.PAYS[C.WILD][wilds - 3]
        for (t in 0 until C.SYMBOL_COUNT) {
            if (t == C.WILD || t == C.SCATTER) continue
            var n = 0
            while (n < 5 && (a[n] == t || a[n] == C.WILD)) n++
            if (n >= 3) best = maxOf(best, C.PAYS[t][n - 3])
        }
        return best
    }

    /** What a spin shows: 5 reels x 3 consecutive strip symbols from each stop, then the mini feature's wilds on top. */
    fun grid(strips: Array<IntArray>, stops: IntArray, wildReels: IntArray, wildCells: IntArray): Array<IntArray> {
        val g = Array(5) { r -> IntArray(3) { row -> strips[r][(stops[r] + row) % strips[r].size] } }
        for (r in wildReels) for (row in 0 until 3) g[r][row] = C.WILD
        for (cell in wildCells) g[cell / 3][cell % 3] = C.WILD
        return g
    }

    fun lines(g: Array<IntArray>): Int {
        var sum = 0
        for (line in C.PAYLINES) sum += linePay(IntArray(5) { r -> g[r][line[r]] })
        return sum
    }

    fun coffins(g: Array<IntArray>): Int = g.sumOf { col -> col.count { it == C.SCATTER } }

    class Spin(val lines: Int, val coffins: Int, val totalMult: Int)

    /** Total multiple of the stake of one base spin (lines x mini multiplier + soul coins + coffin pay). */
    fun spin(strips: Array<IntArray>, stops: IntArray, mini: Mini, inSeance: Boolean): Spin {
        val g = grid(strips, stops, mini.wildReels, mini.wildCells)
        val l = lines(g); val k = coffins(g)
        val scatterPay = if (inSeance) 0 else C.SCATTER_PAY[minOf(k, 5)]
        return Spin(l, k, l * mini.multiplier + mini.coins.sum() + scatterPay)
    }

    class Seance(val totalMult: Long, val spinsPlayed: Int, val leftAfter: IntArray)

    /** THE SEANCE: spin n pays its line wins x min(10, n); 3/4/5 coffins add 4/8/12 spins; every spin uses one stop per reel. */
    fun seance(seed: Long, coffinsToStart: Int): Seance {
        val rng = Random(seed)
        var left = C.FS_SPINS[coffinsToStart.coerceIn(3, 5)]
        var n = 0; var total = 0L
        val after = ArrayList<Int>()
        while (left > 0 && n < 150) {
            n++
            val stops = IntArray(5) { rng.nextInt(C.FS_STRIPS[it].size) }
            val s = spin(C.FS_STRIPS, stops, Mini.NONE, inSeance = true)
            total += s.lines.toLong() * minOf(10, n)
            left += C.FS_EXTRA[minOf(s.coffins, 5)] - 1
            after += maxOf(left, 0)
        }
        return Seance(total, n, after.toIntArray())
    }

    /** SOUL VAULT: 20 niches, 5 soul orbs to start, 3 respins (reset to 3 by every landing), reaper orbs collect the soul sum, full board = GRAND. */
    fun vault(seed: Long): Int {
        val rng = Random(seed)
        val value = IntArray(20); val kind = IntArray(20)          // kind: 0 empty, 1 soul, 2 jackpot, 3 reaper
        fun soul(): Int {
            var w = rng.nextInt(C.ORB_WEIGHTS.sum())
            for (i in C.ORB_VALUES.indices) { w -= C.ORB_WEIGHTS[i]; if (w < 0) return C.ORB_VALUES[i] }
            return C.ORB_VALUES[0]
        }
        val free = (0 until 20).toMutableList()
        repeat(C.VAULT_START_ORBS) { val c = free.removeAt(rng.nextInt(free.size)); kind[c] = 1; value[c] = soul() }
        var respins = C.VAULT_RESPINS
        while (respins > 0 && kind.any { it == 0 }) {
            respins--
            var landed = 0
            for (col in 0 until 5) for (row in 0 until 4) {
                val c = row * 5 + col
                if (kind[c] != 0 || rng.nextDouble() >= C.VAULT_ORB_CHANCE) continue
                var x = rng.nextDouble()
                if (x < C.P_MAJOR) { kind[c] = 2; value[c] = C.MAJOR }
                else if ((x - C.P_MAJOR).also { x = it } < C.P_MINOR) { kind[c] = 2; value[c] = C.MINOR }
                else if ((x - C.P_MINOR).also { x = it } < C.P_MINI) { kind[c] = 2; value[c] = C.MINI }
                else if ((x - C.P_MINI).also { x = it } < C.P_REAPER) {
                    kind[c] = 3; value[c] = (0 until 20).filter { kind[it] == 1 || kind[it] == 3 }.sumOf { value[it] }
                } else { kind[c] = 1; value[c] = soul() }
                landed++
            }
            if (landed > 0) respins = C.VAULT_RESPINS
        }
        return value.sum() + if (kind.all { it != 0 }) C.GRAND else 0
    }
}

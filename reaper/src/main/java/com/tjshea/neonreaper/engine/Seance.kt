package com.tjshea.neonreaper.engine

import java.util.Random

/**
 * One free spin of THE SÉANCE. [multiplier] is the rising multiplier of this spin (x1, x2, ... capped at
 * [ReaperConfig.FS_RAMP_CAP]); [extraSpins] is what this spin's coffins added (retrigger); [spinsLeftAfter] counts the
 * spins still to come once this one is played.
 */
class SeanceSpin(val outcome: SpinOutcome, val number: Int, val multiplier: Int, val extraSpins: Int, val spinsLeftAfter: Int) {
    /** Win of this spin in multiples of the stake that started the Séance. */
    val winMult: Int get() = outcome.lineMult * multiplier
}

/**
 * A complete Séance, generated up front from a seed (credited atomically with the triggering spin, replayed by the UI;
 * identical replay after an app restart, exactly like [VaultResult]). Free spins use richer reels
 * ([ReaperConfig.FS_STRIPS]), no mini features and no relics; the whole session pays in proportion to [bet] (the stake
 * of the spin that started it), so no staking pattern can change its return.
 */
class SeanceResult(val seed: Long, val bet: Int, val coffins: Int, val startSpins: Int, val spins: List<SeanceSpin>) {
    val totalMult: Long = spins.sumOf { it.winMult.toLong() }
    val rawTokens: Long get() = totalMult * bet
    /** Paid amount in exact tokens, capped at 25 books for the whole Séance. */
    val payTokens: Long get() = ReaperEngine.settle(rawTokens)
    val capped: Boolean get() = rawTokens > ReaperConfig.MAX_WIN_TOKENS
    val totalSpins: Int get() = spins.size
    val retriggers: Int get() = spins.count { it.extraSpins > 0 }
}

object Seance {
    /** Safety stop. Reaching it needs ~dozens of consecutive retriggers: probability below 1e-25 (the exact model assumes none). */
    const val MAX_SPINS = 150

    /** Spins awarded by [coffins] coffins (3, 4 or 5). */
    fun spinsFor(coffins: Int): Int = ReaperConfig.FS_SPINS[coffins.coerceIn(3, 5)]

    /** A Séance started by [coffins] coffins on a spin staked at [bet]. */
    fun play(seed: Long, bet: Int, coffins: Int): SeanceResult = playSpins(seed, bet, coffins, spinsFor(coffins))

    /** A bought Séance: the 3-coffin award (see [ReaperConfig.BUY_SEANCE_X]). */
    fun playBought(seed: Long, bet: Int): SeanceResult = play(seed, bet, 3)

    private fun playSpins(seed: Long, bet: Int, coffins: Int, startSpins: Int): SeanceResult {
        val rng = Random(seed)
        val spins = ArrayList<SeanceSpin>()
        var left = startSpins
        var n = 0
        while (left > 0 && n < MAX_SPINS) {
            n++
            val stops = IntArray(ReaperConfig.REELS) { rng.nextInt(ReaperConfig.FS_STRIPS[it].size) }
            val o = ReaperEngine.outcome(bet, stops, Mini.NONE, ReaperConfig.FS_STRIPS, inSeance = true)
            val extra = ReaperConfig.FS_EXTRA[o.scatterCount.coerceIn(0, 5)]
            left += extra - 1
            spins += SeanceSpin(o, n, minOf(ReaperConfig.FS_RAMP_CAP, n), extra, left.coerceAtLeast(0))
        }
        return SeanceResult(seed, bet, coffins, startSpins, spins)
    }
}

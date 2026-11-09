package com.tjshea.neonreaper.engine

import com.tjshea.neonreaper.engine.ReaperConfig.REELS

/**
 * Exact expectations (multiples of the stake, before the 25-book cap) of every Neon Reaper component,
 * derived from the REAL engine rules and config -- this is the Kotlin twin of tools/reaper_model.py and the
 * Neon Reaper equivalent of a casino PAR sheet.
 *
 *  - base game: every one of the 11^5 symbol combinations of a payline is enumerated through
 *    ReaperEngine.evaluateLine with its exact probability (a uniform stop on a strip gives every row the
 *    strip's symbol frequencies, and all 20 lines see the same frequencies, so E[spin] = 20 x E[line]);
 *  - Reaper's Touch: whole reels are wild (every payline crosses every reel);
 *  - Raven's Flock / Skull's Curse: k random wild cells out of 15 -- for one payline the chance that exactly
 *    the cells in S (a subset of its 5) are wild is C(10, k-|S|) / C(15, k);
 *  - Pumpkin's Treats: base game + E[coins] x E[coin value];
 *  - Soul Vault: Markov DP over (filled cells, respins left).
 */
object ExactMath {
    private val SYMS = ReaperConfig.SYMBOL_COUNT
    private val LINES = ReaperConfig.PAYLINES.size

    fun comb(n: Int, k: Int): Double {
        if (k < 0 || k > n) return 0.0
        var r = 1.0
        for (i in 1..k) r = r * (n - k + i) / i
        return r
    }

    /** Symbol probabilities per reel from the real strips; reels in [wildReels] always show the wild. */
    fun marginals(wildReels: Set<Int> = emptySet()): Array<DoubleArray> = Array(REELS) { r ->
        DoubleArray(SYMS).also { f ->
            if (r in wildReels) f[ReaperConfig.WILD] = 1.0
            else ReaperConfig.STRIPS[r].forEach { f[it] += 1.0 / ReaperConfig.STRIPS[r].size }
        }
    }

    /** (E[pay], P(win)) of one payline, x stake, by enumerating all symbol combinations. */
    fun lineStats(p: Array<DoubleArray>): Pair<Double, Double> {
        var ev = 0.0
        var hit = 0.0
        val s = IntArray(REELS)
        fun rec(r: Int, pr: Double) {
            if (pr == 0.0) return
            if (r == REELS) {
                val res = ReaperEngine.evaluateLine(s) ?: return
                ev += pr * res.third; hit += pr
                return
            }
            for (sym in 0 until SYMS) { s[r] = sym; rec(r + 1, pr * p[r][sym]) }
        }
        rec(0, 1.0)
        return ev to hit
    }

    private fun mean(values: IntArray, weights: DoubleArray) =
        values.indices.sumOf { values[it] * weights[it] } / weights.sum()

    class Means(
        val base: Double, val hitPerLine: Double, val reaper: Double, val raven: Double, val pumpkin: Double,
        val skull: Double, val mini: Double, val vault: Double, val vaultFullBoard: Double,
    ) {
        val trig = ReaperConfig.RELIC_CHANCE / ReaperConfig.RELIC_COUNT
        /** Theoretical RTP with no win cap (equals the real RTP at the lowest stakes). */
        val uncappedRtp get() = (1 - ReaperConfig.FEATURE_RATE) * base + ReaperConfig.FEATURE_RATE * mini + trig * vault
    }

    fun means(): Means {
        val (evLine, hitLine) = lineStats(marginals())
        val base = LINES * evLine

        var reaper = 0.0
        for (i in ReaperConfig.FEATURE_PATTERNS.indices)
            reaper += ReaperConfig.FEATURE_WEIGHTS[i] * LINES * lineStats(marginals(ReaperConfig.FEATURE_PATTERNS[i].toSet())).first
        reaper /= ReaperConfig.FEATURE_WEIGHTS.sum()

        // expected pay of a line when exactly the line cells in `mask` (bit r = reel r) are wild
        val evMask = DoubleArray(1 shl REELS) { mask ->
            lineStats(marginals((0 until REELS).filter { mask shr it and 1 == 1 }.toSet())).first
        }
        fun randomWildLineEv(k: Int): Double {
            var t = 0.0
            for (mask in evMask.indices) {
                val j = Integer.bitCount(mask)
                if (j <= k) t += comb(10, k - j) / comb(15, k) * evMask[mask]
            }
            return t
        }
        var raven = 0.0
        for (i in ReaperConfig.RAVEN_WILDS.indices)
            raven += ReaperConfig.RAVEN_WILD_WEIGHTS[i] * LINES * randomWildLineEv(ReaperConfig.RAVEN_WILDS[i])
        raven /= ReaperConfig.RAVEN_WILD_WEIGHTS.sum()

        val coins = mean(ReaperConfig.COIN_COUNTS, ReaperConfig.COIN_COUNT_WEIGHTS) *
            mean(ReaperConfig.COIN_VALUES, ReaperConfig.COIN_WEIGHTS)
        val pumpkin = base + coins
        val skull = mean(ReaperConfig.SKULL_MULTS, ReaperConfig.SKULL_MULT_WEIGHTS) * LINES * randomWildLineEv(ReaperConfig.SKULL_WILDS)

        val w = ReaperConfig.MINI_WEIGHTS   // index = MINI_* constant (0 = none)
        val mini = (w[ReaperConfig.MINI_REAPER] * reaper + w[ReaperConfig.MINI_RAVEN] * raven +
            w[ReaperConfig.MINI_PUMPKIN] * pumpkin + w[ReaperConfig.MINI_SKULL] * skull) / w.sum()

        val (vaultMean, pFull) = vault()
        return Means(base, hitLine, reaper, raven, pumpkin, skull, mini, vaultMean, pFull)
    }

    /**
     * Exact E[total multiplier] of the Soul Vault and P(board full). Cells are i.i.d. per respin, so only the
     * NUMBER of filled cells matters. State (k filled, r respins left) carries P, E[S] and E[J] where S is the sum of
     * collectable orbs (soul + reaper) and J the sum of jackpot orbs. A landing: soul orb S += v, reaper orb S -> 2S
     * (it takes the whole sum and counts as an orb itself), jackpot orb J += its value.
     */
    fun vault(): Pair<Double, Double> {
        val cells = SoulVault.CELLS
        val muSoul = mean(ReaperConfig.ORB_VALUES, ReaperConfig.ORB_WEIGHTS.map { it.toDouble() }.toDoubleArray())
        val qR = ReaperConfig.P_REAPER
        val qS = 1.0 - qR - ReaperConfig.P_MAJOR - ReaperConfig.P_MINOR - ReaperConfig.P_MINI
        val jBar = ReaperConfig.P_MAJOR * ReaperConfig.MAJOR + ReaperConfig.P_MINOR * ReaperConfig.MINOR + ReaperConfig.P_MINI * ReaperConfig.MINI
        val rho = ReaperConfig.VAULT_ORB_CHANCE
        // pending[k][r] = doubleArrayOf(P, E[S*1], E[J*1])
        val pending = Array(cells + 1) { Array(ReaperConfig.VAULT_RESPINS + 1) { DoubleArray(3) } }
        pending[ReaperConfig.VAULT_START_ORBS][ReaperConfig.VAULT_RESPINS] =
            doubleArrayOf(1.0, ReaperConfig.VAULT_START_ORBS * muSoul, 0.0)
        var eS = 0.0; var eJ = 0.0; var pFull = 0.0
        for (k in ReaperConfig.VAULT_START_ORBS until cells) for (r in ReaperConfig.VAULT_RESPINS downTo 1) {
            val st = pending[k][r]
            val p0 = st[0]
            if (p0 == 0.0) continue
            val e = cells - k
            for (landings in 0..e) {
                val pl = comb(e, landings) * Math.pow(rho, landings.toDouble()) * Math.pow(1 - rho, (e - landings).toDouble())
                val growth = Math.pow(1 + qR, landings.toDouble())
                val ms = (st[1] * growth + p0 * qS * muSoul * (growth - 1) / qR) * pl
                val mj = (st[2] + p0 * landings * jBar) * pl
                val p = p0 * pl
                val k2 = k + landings
                val r2 = if (landings > 0) ReaperConfig.VAULT_RESPINS else r - 1
                when {
                    k2 >= cells -> { pFull += p; eS += ms; eJ += mj }
                    r2 <= 0 -> { eS += ms; eJ += mj }
                    else -> { val t = pending[k2][r2]; t[0] += p; t[1] += ms; t[2] += mj }
                }
            }
        }
        return (eS + eJ + ReaperConfig.GRAND * pFull) to pFull
    }
}

package com.tjshea.neonreaper.engine

import com.tjshea.neonreaper.engine.ReaperConfig.REELS
import com.tjshea.neonreaper.engine.ReaperConfig.ROWS

/**
 * Exact expectations (multiples of the stake, before the 25-book cap) of every Neon Reaper component,
 * derived from the REAL engine rules and config -- this is the Kotlin twin of tools/reaper_model.py and the
 * Neon Reaper equivalent of a casino PAR sheet.
 *
 *  - base game: every one of the 12^5 symbol combinations of a payline is enumerated through
 *    ReaperEngine.evaluateLine with its exact probability (a uniform stop on a strip gives every
 *    row the strip's symbol frequencies, and all 20 lines see the same frequencies, so E[spin] = 20 x E[line]);
 *  - Reaper's Touch: whole reels are wild (every payline crosses every reel);
 *  - Murder of Crows / Skull's Curse: k random wild cells out of 15 -- for one payline the chance that exactly
 *    the cells in S (a subset of its 5) are wild is C(10, k-|S|) / C(15, k);
 *  - Demon's Bargain: base game + E[coins] x E[coin value];
 *  - coffins: at most one per reel window (asserted), so the count is Poisson-binomial over the reels; reels that turn wild
 *    lose their coffin, k random wild cells thin the coffins hypergeometrically;
 *  - THE SÉANCE: backward DP over (spin number, spins left) for E[sum of multipliers];
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
    fun marginals(strips: Array<IntArray> = ReaperConfig.STRIPS, wildReels: Set<Int> = emptySet()): Array<DoubleArray> = Array(REELS) { r ->
        DoubleArray(SYMS).also { f ->
            if (r in wildReels) f[ReaperConfig.WILD] = 1.0
            else strips[r].forEach { f[it] += 1.0 / strips[r].size }
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

    // ---------------------------------------------------------------- coffins
    /** P(a reel window of 3 consecutive strip positions shows a coffin); fails if two coffins ever share a window. */
    fun presence(strip: IntArray): Double {
        var hit = 0
        for (s in strip.indices) {
            val c = (0 until ROWS).count { strip[(s + it) % strip.size] == ReaperConfig.SCATTER }
            check(c <= 1) { "two coffins share a reel window" }
            hit += c
        }
        return hit.toDouble() / strip.size
    }

    fun pbDist(ps: List<Double>): DoubleArray {
        var d = DoubleArray(ps.size + 1).also { it[0] = 1.0 }
        for (p in ps) {
            val nd = DoubleArray(d.size) { d[it] * (1 - p) }
            for (i in 1 until d.size) nd[i] += d[i - 1] * p
            d = nd
        }
        return d
    }

    /** pre[n] = P(n coffins); k wild cells uniformly among [cells] erase some of them. */
    fun thinHyper(pre: DoubleArray, cells: Int, k: Int): DoubleArray {
        val out = DoubleArray(pre.size)
        for (n in pre.indices) for (j in 0..minOf(n, k)) {
            if (k - j > cells - n) continue
            out[n - j] += pre[n] * comb(n, j) * comb(cells - n, k - j) / comb(cells, k)
        }
        return out
    }

    /** Coffin-count distribution on the FINAL grid, per spin type (plain, and each mini feature). */
    class ScatterDists(val base: DoubleArray, val reaper: DoubleArray, val raven: DoubleArray, val demon: DoubleArray, val skull: DoubleArray)

    fun scatterDists(): ScatterDists {
        val ps = ReaperConfig.STRIPS.map { presence(it) }
        val pre = pbDist(ps)
        val rs = ReaperConfig.FEATURE_WEIGHTS.sum()
        val reaper = DoubleArray(REELS + 1)
        for ((i, pat) in ReaperConfig.FEATURE_PATTERNS.withIndex()) {
            val d = pbDist(ps.mapIndexed { r, p -> if (r in pat) 0.0 else p })
            for (k in d.indices) reaper[k] += ReaperConfig.FEATURE_WEIGHTS[i] / rs * d[k]
        }
        val raven = DoubleArray(REELS + 1)
        val ws = ReaperConfig.RAVEN_WILD_WEIGHTS.sum()
        for ((i, k) in ReaperConfig.RAVEN_WILDS.withIndex()) {
            val d = thinHyper(pre, REELS * ROWS, k)
            for (n in d.indices) raven[n] += ReaperConfig.RAVEN_WILD_WEIGHTS[i] / ws * d[n]
        }
        return ScatterDists(pre, reaper, raven, pre, thinHyper(pre, REELS * ROWS, ReaperConfig.SKULL_WILDS))
    }

    // ---------------------------------------------------------------- THE SÉANCE
    class SeanceMeans(val mu: Double, val hitPerLine: Double, val pk: DoubleArray, val value: DoubleArray, val spins: DoubleArray)

    /** E[sum of multipliers] of a Séance that starts with [n0] spins (m_t = min(cap, t), retriggers add spins). */
    fun seanceSumMult(n0: Int, pk: DoubleArray, tmax: Int = Seance.MAX_SPINS, rmax: Int = 300): Double {
        var nxt = DoubleArray(rmax + 1)
        for (t in tmax downTo 1) {
            val cur = DoubleArray(rmax + 1)
            val m = minOf(ReaperConfig.FS_RAMP_CAP, t).toDouble()
            for (r in 1..rmax) {
                var acc = m
                for (k in pk.indices) {
                    val r2 = minOf(rmax, r - 1 + ReaperConfig.FS_EXTRA[k])
                    if (r2 >= 1) acc += pk[k] * nxt[r2]
                }
                cur[r] = acc
            }
            nxt = cur
        }
        return nxt[n0]
    }

    fun seance(): SeanceMeans {
        val (ev, hit) = lineStats(marginals(ReaperConfig.FS_STRIPS))
        val pk = pbDist(ReaperConfig.FS_STRIPS.map { presence(it) })
        val mu = LINES * ev
        val value = DoubleArray(6)
        val spins = DoubleArray(6)
        for (k in 3..5) {
            value[k] = mu * seanceSumMult(ReaperConfig.FS_SPINS[k], pk)
            val rho = (3..5).sumOf { pk[it] * ReaperConfig.FS_EXTRA[it] }
            spins[k] = ReaperConfig.FS_SPINS[k] / (1 - rho)
        }
        return SeanceMeans(mu, hit, pk, value, spins)
    }

    // ---------------------------------------------------------------- everything
    class Means(
        val base: Double, val hitPerLine: Double, val reaper: Double, val raven: Double, val demon: Double,
        val skull: Double, val miniLines: Double, val vault: Double, val vaultFullBoard: Double,
        val scatPayBase: Double, val scatPayMini: Double, val trigBase: Double, val trigMini: Double,
        val seanceBase: Double, val seanceMini: Double, val seance: SeanceMeans, val scat: ScatterDists,
    ) {
        val trigVault = ReaperConfig.RELIC_CHANCE / ReaperConfig.RELIC_COUNT
        /** Expected non-vault payout of a plain spin: lines + coffin pay + Séance (x stake, uncapped). */
        val aBase get() = base + scatPayBase + seanceBase
        /** Same for a spin that got a mini feature. */
        val aMini get() = miniLines + scatPayMini + seanceMini
        /** Theoretical RTP with no win cap (equals the real RTP at the lowest stakes). */
        val uncappedRtp get() = (1 - ReaperConfig.FEATURE_RATE) * aBase + ReaperConfig.FEATURE_RATE * aMini + trigVault * vault
        /** The mini-feature rate that would give exactly TARGET_RTP. */
        val exactFeatureRate get() = (ReaperConfig.TARGET_RTP - trigVault * vault - aBase) / (aMini - aBase)
        val seanceEvery get() = 1 / ((1 - ReaperConfig.FEATURE_RATE) * trigBase + ReaperConfig.FEATURE_RATE * trigMini)
    }

    fun means(): Means {
        val (evLine, hitLine) = lineStats(marginals())
        val base = LINES * evLine

        var reaper = 0.0
        for (i in ReaperConfig.FEATURE_PATTERNS.indices)
            reaper += ReaperConfig.FEATURE_WEIGHTS[i] * LINES * lineStats(marginals(wildReels = ReaperConfig.FEATURE_PATTERNS[i].toSet())).first
        reaper /= ReaperConfig.FEATURE_WEIGHTS.sum()

        // expected pay of a line when exactly the line cells in `mask` (bit r = reel r) are wild
        val evMask = DoubleArray(1 shl REELS) { mask ->
            lineStats(marginals(wildReels = (0 until REELS).filter { mask shr it and 1 == 1 }.toSet())).first
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
        val demon = base + coins
        val skull = mean(ReaperConfig.SKULL_MULTS, ReaperConfig.SKULL_MULT_WEIGHTS) * LINES * randomWildLineEv(ReaperConfig.SKULL_WILDS)

        val w = ReaperConfig.MINI_WEIGHTS   // index = MINI_* constant (0 = none)
        val miniLines = (w[ReaperConfig.MINI_REAPER] * reaper + w[ReaperConfig.MINI_RAVEN] * raven +
            w[ReaperConfig.MINI_DEMON] * demon + w[ReaperConfig.MINI_SKULL] * skull) / w.sum()

        val sc = scatterDists()
        val se = seance()
        fun pay(d: DoubleArray) = (3..5).sumOf { d[it] * ReaperConfig.SCATTER_PAY[it] }
        fun fs(d: DoubleArray) = (3..5).sumOf { d[it] * se.value[it] }
        fun trig(d: DoubleArray) = (3..5).sumOf { d[it] }
        fun mix(f: (DoubleArray) -> Double) = (w[ReaperConfig.MINI_REAPER] * f(sc.reaper) + w[ReaperConfig.MINI_RAVEN] * f(sc.raven) +
            w[ReaperConfig.MINI_DEMON] * f(sc.demon) + w[ReaperConfig.MINI_SKULL] * f(sc.skull)) / w.sum()

        val (vaultMean, pFull) = vault()
        return Means(base, hitLine, reaper, raven, demon, skull, miniLines, vaultMean, pFull,
            pay(sc.base), mix(::pay), trig(sc.base), mix(::trig), fs(sc.base), mix(::fs), se, sc)
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

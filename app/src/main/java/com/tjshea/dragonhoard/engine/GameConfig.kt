package com.tjshea.dragonhoard.engine

/**
 * All slot math lives here. Pays are whole macks per 1-mack line bet, so every
 * payout is a whole number of macks and the RTP is identical at every bet.
 *
 * Reel strip symbol counts were tuned so the theoretical RTP is 80.00 %
 * (verified exactly by RtpTest). Do not edit without re-running that test.
 */
object GameConfig {
    const val WILD = 0
    const val SCATTER = 1
    const val TIGER = 2
    const val KOI = 3
    const val LANTERN = 4
    const val ENVELOPE = 5
    const val ACE = 6
    const val KING = 7
    const val QUEEN = 8
    const val JACK = 9
    const val TEN = 10
    const val SYMBOL_COUNT = 11

    const val REELS = 5
    const val ROWS = 3

    val SYMBOL_NAMES = arrayOf(
        "Dragon Wild", "Gold Coin Scatter", "Tiger", "Koi", "Lantern", "Red Envelope",
        "Ace", "King", "Queen", "Jack", "Ten"
    )

    /** PAYS[symbol] = pay for 3, 4, 5 of a kind (macks per 1-mack line bet). */
    val PAYS: Array<IntArray> = arrayOf(
        intArrayOf(25, 200, 1000), // WILD
        intArrayOf(0, 0, 0),       // SCATTER (pays via SCATTER_PAYS)
        intArrayOf(20, 75, 300),   // TIGER
        intArrayOf(15, 50, 200),   // KOI
        intArrayOf(10, 40, 150),   // LANTERN
        intArrayOf(8, 25, 100),    // ENVELOPE
        intArrayOf(4, 12, 50),     // ACE
        intArrayOf(4, 10, 40),     // KING
        intArrayOf(2, 8, 30),      // QUEEN
        intArrayOf(2, 6, 25),      // JACK
        intArrayOf(1, 5, 20),      // TEN
    )

    /** Scatter pays, multiples of TOTAL bet, indexed by scatter count (0..5). */
    val SCATTER_PAYS = intArrayOf(0, 0, 0, 2, 10, 50)

    const val FREE_SPINS_AWARD = 10
    const val FREE_SPIN_MULTIPLIER = 3
    const val MIN_SCATTERS_FOR_FEATURE = 3

    /** 10 classic paylines; value = row index (0 top .. 2 bottom) on each reel. */
    val PAYLINES: Array<IntArray> = arrayOf(
        intArrayOf(1, 1, 1, 1, 1),
        intArrayOf(0, 0, 0, 0, 0),
        intArrayOf(2, 2, 2, 2, 2),
        intArrayOf(0, 1, 2, 1, 0),
        intArrayOf(2, 1, 0, 1, 2),
        intArrayOf(0, 0, 1, 2, 2),
        intArrayOf(2, 2, 1, 0, 0),
        intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 2, 2, 2, 1),
        intArrayOf(1, 2, 1, 0, 1),
    )

    // Symbol counts per reel:      W  S  TIG KOI LAN ENV A  K  Q  J  T
    val BASE_COUNTS: Array<IntArray> = arrayOf(
        intArrayOf(3, 2, 4, 3, 4, 3, 5, 3, 4, 7, 6),
        intArrayOf(2, 1, 3, 3, 4, 4, 3, 3, 5, 5, 7),
        intArrayOf(4, 1, 3, 3, 3, 3, 3, 4, 4, 4, 4),
        intArrayOf(6, 1, 4, 4, 4, 4, 4, 4, 5, 5, 6),
        intArrayOf(5, 2, 3, 3, 3, 2, 5, 5, 3, 7, 5),
    )
    val FREE_COUNTS: Array<IntArray> = arrayOf(
        intArrayOf(5, 1, 3, 2, 4, 4, 4, 5, 4, 5, 8),
        intArrayOf(7, 2, 3, 3, 3, 3, 3, 4, 5, 7, 7),
        intArrayOf(6, 1, 4, 3, 4, 4, 5, 5, 7, 4, 7),
        intArrayOf(5, 2, 3, 2, 1, 4, 4, 6, 6, 6, 6),
        intArrayOf(6, 1, 2, 3, 4, 3, 2, 4, 5, 6, 5),
    )

    val BASE_STRIPS: Array<IntArray> = Array(REELS) { buildStrip(BASE_COUNTS[it], it) }
    val FREE_STRIPS: Array<IntArray> = Array(REELS) { buildStrip(FREE_COUNTS[it], it + 7) }

    val LINE_BET_OPTIONS = intArrayOf(1, 2, 3, 4, 5, 8, 10, 16, 20, 40) // macks per line
    val LINE_OPTIONS = intArrayOf(1, 3, 5, 7, 9, 10)

    /**
     * Deterministically lays out a strip with exactly [counts] of each symbol.
     * Scatters are evenly spaced (so a 3-row window never shows two from one
     * reel); other symbols are interleaved smoothly with a small seeded twist so
     * reels don't look identical. Only the counts affect the math.
     */
    fun buildStrip(counts: IntArray, seed: Int): IntArray {
        val len = counts.sum()
        val strip = IntArray(len) { -1 }
        val sc = counts[SCATTER]
        for (i in 0 until sc) strip[(i * len / sc + seed) % len] = SCATTER
        val placed = IntArray(counts.size)
        placed[SCATTER] = sc
        val order = (0 until counts.size).filter { it != SCATTER }
            .sortedBy { (it * 7 + seed * 3) % counts.size }
        var filled = sc
        var prev = -1
        for (pos in 0 until len) {
            if (strip[pos] != -1) { prev = strip[pos]; continue }
            filled++
            var best = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (s in order) {
                if (placed[s] >= counts[s]) continue
                var score = counts[s].toDouble() * filled / len - placed[s]
                if (s == prev) score -= 0.75
                if (score > bestScore) { bestScore = score; best = s }
            }
            strip[pos] = best
            placed[best]++
            prev = best
        }
        return strip
    }
}

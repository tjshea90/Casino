package com.tjshea.neonreaper.engine

/**
 * Neon Reaper math. Stake is in TOKENS (1..40). Line pays are whole multiples of
 * the stake, so every win is an exact number of tokens (rounding to the mack only
 * happens at cash-out). Wins are capped at 25 books.
 *
 * The cap costs more at high stakes, so the mini features (Reaper / Raven /
 * Pumpkin / Skull) trigger at a per-stake rate (FEATURE_RATE) that brings every
 * stake to 87 % RTP.
 * Values come from CalibrationTest; do not edit pays/reels without re-running it
 * and pasting the new table.
 */
object ReaperConfig {
    const val WILD = 0
    const val HOODED = 1
    const val SKULL = 2
    const val RAVEN = 3
    const val PUMPKIN = 4
    const val GHOST = 5
    const val ACE = 6
    const val KING = 7
    const val QUEEN = 8
    const val JACK = 9
    const val TEN = 10
    const val SYMBOL_COUNT = 11
    const val REELS = 5
    const val ROWS = 3

    const val TARGET_RTP = 0.87
    const val MAX_WIN_TOKENS = 25 * Money.TOKENS_PER_BOOK // 2000 tokens = 25 books

    val SYMBOL_NAMES = arrayOf("Grim Reaper Wild", "Hooded Watcher", "Laughing Skull", "Blood Raven", "Jack-o'-Lantern",
        "Grinning Ghost", "Ace", "King", "Queen", "Jack", "Ten")

    /** PAYS[symbol] = multiples of the STAKE for 3, 4, 5 of a kind on a line. */
    val PAYS: Array<IntArray> = arrayOf(
        intArrayOf(6, 20, 100),  // WILD
        intArrayOf(3, 10, 40),   // HOODED
        intArrayOf(3, 6, 25),    // SKULL
        intArrayOf(2, 5, 15),    // RAVEN
        intArrayOf(2, 4, 10),    // PUMPKIN
        intArrayOf(2, 3, 8),     // GHOST
        intArrayOf(1, 2, 6),     // ACE
        intArrayOf(1, 2, 5),     // KING
        intArrayOf(1, 2, 4),     // QUEEN
        intArrayOf(1, 2, 4),     // JACK
        intArrayOf(1, 2, 3),     // TEN
    )

    val PAYLINES: Array<IntArray> = arrayOf(
        intArrayOf(1, 1, 1, 1, 1), intArrayOf(0, 0, 0, 0, 0), intArrayOf(2, 2, 2, 2, 2), intArrayOf(0, 1, 2, 1, 0),
        intArrayOf(2, 1, 0, 1, 2), intArrayOf(0, 0, 1, 2, 2), intArrayOf(2, 2, 1, 0, 0), intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 2, 2, 2, 1), intArrayOf(1, 0, 1, 2, 1), intArrayOf(1, 2, 1, 0, 1), intArrayOf(0, 1, 1, 1, 0),
        intArrayOf(2, 1, 1, 1, 2), intArrayOf(0, 1, 0, 1, 0), intArrayOf(2, 1, 2, 1, 2), intArrayOf(1, 1, 0, 1, 1),
        intArrayOf(1, 1, 2, 1, 1), intArrayOf(0, 0, 2, 0, 0), intArrayOf(2, 2, 0, 2, 2), intArrayOf(0, 2, 2, 2, 0),
    )

    //                          W  HD SK RV PK GH A  K  Q  J  10
    private val COUNTS = intArrayOf(1, 3, 3, 4, 5, 5, 7, 7, 8, 8, 9)
    val STRIPS: Array<IntArray> = Array(REELS) { buildStrip(COUNTS, it) }

    // ---- mini features: a character floats out and changes this one spin ----
    const val MINI_NONE = 0
    const val MINI_REAPER = 1   // Reaper's Touch: 1-3 middle reels turn fully wild
    const val MINI_RAVEN = 2    // Raven's Flock: 3-6 random symbols become wild
    const val MINI_PUMPKIN = 3  // Pumpkin's Treats: 3-6 soul coins land, each paying 1x-50x the stake
    const val MINI_SKULL = 4    // Skull's Curse: 2 wilds plus a x2 / x3 / x5 multiplier on line wins
    val MINI_NAMES = arrayOf("", "REAPER'S TOUCH", "RAVEN'S FLOCK", "PUMPKIN'S TREATS", "SKULL'S CURSE")
    val MINI_WEIGHTS = doubleArrayOf(0.0, 0.25, 0.30, 0.30, 0.15)
    val RAVEN_WILDS = intArrayOf(3, 4, 5, 6); val RAVEN_WILD_WEIGHTS = doubleArrayOf(0.5, 0.3, 0.15, 0.05)
    val COIN_COUNTS = intArrayOf(3, 4, 5, 6); val COIN_COUNT_WEIGHTS = doubleArrayOf(0.45, 0.3, 0.17, 0.08)
    val COIN_VALUES = intArrayOf(1, 2, 3, 5, 10, 20, 50); val COIN_WEIGHTS = doubleArrayOf(30.0, 25.0, 18.0, 13.0, 9.0, 4.0, 1.0)
    val SKULL_MULTS = intArrayOf(2, 3, 5); val SKULL_MULT_WEIGHTS = doubleArrayOf(0.6, 0.3, 0.1)
    const val SKULL_WILDS = 2

    /** Reaper's Touch: which reels turn wild, and how likely each pattern is. */
    val FEATURE_PATTERNS: Array<IntArray> = arrayOf(intArrayOf(1), intArrayOf(2), intArrayOf(3), intArrayOf(1, 3), intArrayOf(1, 2, 3))
    val FEATURE_WEIGHTS = doubleArrayOf(0.45, 0.25, 0.15, 0.08, 0.07)

    val STAKES = intArrayOf(1, 2, 3, 4, 5, 8, 10, 15, 20, 25, 30, 40)

    /** Per-stake chance a spin gets a mini feature (from CalibrationTest). */
    val FEATURE_RATE = doubleArrayOf(
        0.0110, 0.0110, 0.0110, 0.0110, 0.0110, 0.0110, 0.0110, 0.0114, 0.0117, 0.0121, 0.0126, 0.0138
    )

    // ---- relics & Soul Vault bonus ----
    const val RELIC_COUNT = 5
    /** Chance per spin to collect the next relic: a relic about every 28 spins, bonus about every 140. */
    const val RELIC_CHANCE = 5.0 / 140.0
    val RELIC_NAMES = arrayOf("Scythe", "Hourglass", "Skull Candle", "Raven Feather", "Cursed Tome")

    const val VAULT_COLS = 5
    const val VAULT_ROWS = 4
    const val VAULT_START_ORBS = 5
    const val VAULT_RESPINS = 3
    const val VAULT_ORB_CHANCE = 0.03
    val ORB_VALUES = intArrayOf(1, 2, 3, 4, 5, 8, 10, 15, 25)        // × bonus bet
    val ORB_WEIGHTS = intArrayOf(36, 24, 14, 9, 6, 5, 3, 2, 1)
    const val P_MAJOR = 0.003
    const val P_MINOR = 0.012
    const val P_MINI = 0.03
    const val P_REAPER = 0.02
    const val MINI = 15
    const val MINOR = 40
    const val MAJOR = 150
    const val GRAND = 1000

    fun stakeIndex(stake: Int): Int = STAKES.indexOf(stake).coerceAtLeast(0)

    fun buildStrip(counts: IntArray, seed: Int): IntArray {
        val len = counts.sum()
        val strip = IntArray(len)
        val placed = IntArray(counts.size)
        val order = (counts.indices).sortedBy { (it * 7 + seed * 3) % counts.size }
        var prev = -1
        for (pos in 0 until len) {
            val filled = pos + 1
            var best = -1
            var bestScore = Double.NEGATIVE_INFINITY
            for (s in order) {
                if (placed[s] >= counts[s]) continue
                var score = counts[s].toDouble() * filled / len - placed[s]
                if (s == prev) score -= 0.75
                if (score > bestScore) { bestScore = score; best = s }
            }
            strip[pos] = best; placed[best]++; prev = best
        }
        return strip
    }
}

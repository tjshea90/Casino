package com.tjshea.neonreaper.engine

/**
 * Neon Reaper 3.0 math. Stake is in TOKENS (1..40). Line pays are whole multiples of
 * the stake, so every win is an exact number of tokens (rounding to the mack only
 * happens at cash-out). Wins are capped at 25 books (2000 tokens) per spin and per bonus (Séance, Soul Vault).
 *
 * RTP design (94.00 %, house edge 6 %), stake 1, exact (tools/reaper_model.py, RtpExactTest):
 * lines + mini features 57.77 % + coffin pays 1.11 % + THE SÉANCE 14.12 % + Soul Vault 21.00 % = 94.000 %.
 * plain-spin win frequency 31.3 % (1 in 3.2), a Séance every ~213 spins (avg 30x the stake), a Soul Vault every ~135 spins
 * (avg 28x the bonus bet), a mini feature every ~154 spins.
 * The mini-feature rate is the SAME at every stake on purpose: the 25-book cap can then only ever LOWER the
 * return at high stakes (RTP_BY_STAKE). THE SÉANCE (3+ coffins) and the feature buys pay in proportion to the stake that
 * started them, so they cannot be gamed either; buys are priced above their exact expected payout.
 *   The Soul Vault bet is the average stake of the five spins that revealed the relics, rounded down (RelicMeter):
 *   that makes the expected bonus per token wagered identical for EVERY staking policy, so no pattern of stakes can beat
 *   the headline RTP. Two earlier rules were beaten in testing: rounding the average of the whole collection window up
 *   (mixing stakes 1 and 2 reached ~98 %), and flooring it (betting big only when relics arrive fast reached 101-104 %).
 * Do not edit pays/reels/features without re-running tools/reaper_model.py and the tests in
 * reaper/src/test/.../engine, and pasting the new numbers here.
 */
object ReaperConfig {
    const val WILD = 0       // Grim Reaper
    const val DOCTOR = 1     // Plague Doctor
    const val SKULL = 2      // Cursed Skull
    const val RAVEN = 3      // Blood Raven
    const val DEMON = 4      // Demon
    const val WRAITH = 5     // Wraith (a wailing banshee)
    const val ACE = 6
    const val KING = 7
    const val QUEEN = 8
    const val JACK = 9
    const val TEN = 10
    const val SCATTER = 11   // Coffin: pays 2x / 10x / 50x for 3 / 4 / 5 anywhere and starts THE SÉANCE
    const val SYMBOL_COUNT = 12
    // transitional aliases for code that still uses the pre-3.0 names (removed once the UI migration is done)
    const val HOODED = DOCTOR
    const val PUMPKIN = DEMON
    const val GHOST = WRAITH
    const val REELS = 5
    const val ROWS = 3

    const val TARGET_RTP = 0.94
    const val MAX_WIN_TOKENS = 25 * Money.TOKENS_PER_BOOK // 2000 tokens = 25 books

    val SYMBOL_NAMES = arrayOf("Grim Reaper Wild", "Plague Doctor", "Cursed Skull", "Blood Raven", "Demon",
        "Wraith", "Ace", "King", "Queen", "Jack", "Ten", "Coffin Scatter")

    /** PAYS[symbol] = multiples of the STAKE for 3, 4, 5 of a kind on a line. */
    val PAYS: Array<IntArray> = arrayOf(
        intArrayOf(6, 20, 100),  // WILD
        intArrayOf(3, 10, 40),   // HOODED
        intArrayOf(3, 6, 25),    // SKULL
        intArrayOf(2, 5, 15),    // RAVEN
        intArrayOf(2, 4, 12),    // DEMON
        intArrayOf(2, 3, 8),     // GHOST
        intArrayOf(1, 2, 6),     // ACE
        intArrayOf(1, 2, 5),     // KING
        intArrayOf(1, 2, 4),     // QUEEN
        intArrayOf(1, 1, 4),     // JACK
        intArrayOf(1, 1, 3),     // TEN
        intArrayOf(0, 0, 0),     // SCATTER: no line pay (coffins pay anywhere, see SCATTER_PAY)
    )

    val PAYLINES: Array<IntArray> = arrayOf(
        intArrayOf(1, 1, 1, 1, 1), intArrayOf(0, 0, 0, 0, 0), intArrayOf(2, 2, 2, 2, 2), intArrayOf(0, 1, 2, 1, 0),
        intArrayOf(2, 1, 0, 1, 2), intArrayOf(0, 0, 1, 2, 2), intArrayOf(2, 2, 1, 0, 0), intArrayOf(1, 0, 0, 0, 1),
        intArrayOf(1, 2, 2, 2, 1), intArrayOf(1, 0, 1, 2, 1), intArrayOf(1, 2, 1, 0, 1), intArrayOf(0, 1, 1, 1, 0),
        intArrayOf(2, 1, 1, 1, 2), intArrayOf(0, 1, 0, 1, 0), intArrayOf(2, 1, 2, 1, 2), intArrayOf(1, 1, 0, 1, 1),
        intArrayOf(1, 1, 2, 1, 1), intArrayOf(0, 0, 2, 0, 0), intArrayOf(2, 2, 0, 2, 2), intArrayOf(0, 2, 2, 2, 0),
    )

    //                          W  DC SK RV DE WR A  K  Q  J  10 SC  (same on all 5 reels)
    private val COUNTS = intArrayOf(1, 4, 4, 6, 5, 5, 7, 6, 9, 11, 14, 2)
    val STRIPS: Array<IntArray> = Array(REELS) { buildStrip(COUNTS, it) }

    // ---- scatter coffins + THE SÉANCE (free spins) ----
    /** Coffin pay in multiples of the stake, indexed by the number of coffins on the final grid (3, 4, 5). */
    val SCATTER_PAY = intArrayOf(0, 0, 0, 2, 10, 50)
    /** Free spins started by 3 / 4 / 5 coffins. */
    val FS_SPINS = intArrayOf(0, 0, 0, 8, 12, 20)
    /** Extra spins when a Séance spin itself shows 3 / 4 / 5 coffins (indexed by coffin count). */
    val FS_EXTRA = intArrayOf(0, 0, 0, 4, 8, 12)
    /** The multiplier of Séance spin number t is min(FS_RAMP_CAP, t): x1, x2, x3 ... Retrigger spins continue the climb. */
    const val FS_RAMP_CAP = 10
    //                              W  DC SK RV DE WR A  K  Q  J  10 SC  (richer reels: more wilds and characters)
    private val FS_COUNTS = intArrayOf(2, 4, 3, 4, 6, 5, 6, 6, 6, 6, 8, 2)
    val FS_STRIPS: Array<IntArray> = Array(REELS) { buildStrip(FS_COUNTS, it) }
    /** Feature buy prices in multiples of the stake: just above the exact expected payout / 0.93 (a buy returns <= 93 %). */
    const val BUY_SEANCE_X = 31
    const val BUY_VAULT_X = 31

    // ---- mini features: a character floats out and changes this one spin ----
    const val MINI_NONE = 0
    const val MINI_REAPER = 1   // Reaper's Touch: 1-3 middle reels turn fully wild
    const val MINI_RAVEN = 2    // Murder of Crows: 3-6 random symbols become wild
    const val MINI_DEMON = 3    // Demon's Bargain: 3-6 soul coins land, each paying 1x-50x the stake
    const val MINI_PUMPKIN = MINI_DEMON   // transitional alias (pre-3.0 name)
    const val MINI_SKULL = 4    // Skull's Curse: 2 wilds plus a x2 / x3 / x5 multiplier on line wins
    val MINI_NAMES = arrayOf("", "REAPER'S TOUCH", "MURDER OF CROWS", "DEMON'S BARGAIN", "SKULL'S CURSE")
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

    /** Chance a spin gets a mini feature (about 1 spin in 100), the same at every stake. Solved so RTP at stake 1 is exactly 94 %. */
    const val FEATURE_RATE = 0.006486

    /**
     * Theoretical RTP at each stake in STAKES (exact means, 25-book cap measured with millions of samples;
     * tools/reaper_model.py, checked by CalibrationTest). Identical to 94.00 % up to stake 5; the cap trims the top stakes.
     */
    val RTP_BY_STAKE = doubleArrayOf(0.9400, 0.9400, 0.9400, 0.9399, 0.9398, 0.9391, 0.9382, 0.9326, 0.9260, 0.9184, 0.9097, 0.8890)

    /** Published figures (verified by FullGameTest): base-game win frequency; every win returns at least the stake. */
    const val HIT_FREQUENCY_TEXT = "about 1 in 3"
    /** Range the measured plain-spin win frequency must stay inside (FullGameTest / RtpExactTest). */
    val HIT_RATE_RANGE = 0.295..0.33
    /** Published bonus frequencies (average spins between bonuses) and the range the simulation must stay inside. */
    const val VAULT_EVERY_TEXT = "135"
    val VAULT_EVERY_RANGE = 115.0..155.0
    const val SEANCE_EVERY_TEXT = "213"
    val SEANCE_EVERY_RANGE = 180.0..250.0

    // ---- relics & Soul Vault bonus ----
    const val RELIC_COUNT = 5
    /** Chance per spin to collect the next relic: a relic about every 27 spins, a Soul Vault bonus about every 135. */
    const val RELIC_CHANCE = 5.0 / 135.0
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

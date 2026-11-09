package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Independent audit invariants (docs/AUDIT_v3.md). Fast (a few seconds): the long Monte-Carlo runs live in tools/audit and in
 * [AuditSimTest]. Everything here is written from the rules, not copied from the engine or from ExactMath.
 */
class AuditTest {
    private val C = ReaperConfig

    // ------------------------------------------------------------------------------------------------ audit tool inputs

    @Test fun configSnapshotIsCurrent() {
        val text = AuditConfigSnapshot.text()
        File("build/audit").mkdirs(); File("build/audit/config_snapshot.txt").writeText(text)
        val committed = AuditConfigSnapshot.committed()
        assertTrue("tools/audit/config_snapshot.txt is missing: copy reaper/build/audit/config_snapshot.txt there", committed != null)
        assertEquals("tools/audit/config_snapshot.txt is stale (the audit tools would audit old numbers): copy reaper/build/audit/config_snapshot.txt over it",
            committed!!.readText(), text)
    }

    // ------------------------------------------------------------------------------------------------ the payline rule

    /** Reference rule, structured differently from ReaperEngine.evaluateLine: the best pay over EVERY symbol that could head the line. */
    private fun refPay(a: IntArray): Int {
        var best = 0
        var wilds = 0
        while (wilds < 5 && a[wilds] == C.WILD) wilds++
        if (wilds >= 3) best = C.PAYS[C.WILD][wilds - 3]
        for (t in 0 until C.SYMBOL_COUNT) {
            if (t == C.WILD || t == C.SCATTER) continue          // a coffin never pays on a line and wilds never stand in for it
            var n = 0
            while (n < 5 && (a[n] == t || a[n] == C.WILD)) n++
            if (n >= 3) best = maxOf(best, C.PAYS[t][n - 3])
        }
        return best
    }

    @Test fun engineLineRuleEqualsTheReferenceOnAll12ToThe5SymbolCombinations() {
        val a = IntArray(5)
        var positive = 0
        for (i in 0 until 248_832) {
            var x = i
            for (r in 4 downTo 0) { a[r] = x % 12; x /= 12 }
            val engine = ReaperEngine.evaluateLine(a)?.third ?: 0
            if (engine != refPay(a)) throw AssertionError("line ${a.joinToString(" ")}: engine pays $engine, reference ${refPay(a)}")
            if (engine > 0) { positive++; assertTrue("a line win pays less than 1 x stake", engine >= 1) }
        }
        assertTrue(positive > 10_000)
    }

    @Test fun paylinesAreTwentyDistinctRowPathsInsideTheGrid() {
        assertEquals(20, C.PAYLINES.size)
        assertEquals(20, C.PAYLINES.map { it.joinToString(",") }.toSet().size)
        for (l in C.PAYLINES) { assertEquals(5, l.size); assertTrue(l.all { it in 0..2 }) }
    }

    // ------------------------------------------------------------------------------------------------ claim: every win >= stake

    @Test fun noWinIsEverSmallerThanTheStakeAtAnyStake() {
        val rng = Random(11)
        repeat(300_000) {
            val stake = C.STAKES[rng.nextInt(C.STAKES.size)]
            val o = ReaperEngine.spin(rng, stake)
            assertTrue("spin paid ${o.payTokens} on a stake of $stake", o.payTokens == 0L || o.payTokens >= stake)
        }
        for (type in 1..4) repeat(20_000) {
            val stake = C.STAKES[rng.nextInt(C.STAKES.size)]
            val stops = IntArray(5) { r -> rng.nextInt(C.STRIPS[r].size) }
            val o = ReaperEngine.outcome(stake, stops, ReaperEngine.rollMini(rng, type))
            assertTrue(o.payTokens == 0L || o.payTokens >= stake)
        }
        for (seed in 1L..20_000L) for (bet in intArrayOf(1, 3, 7, 40)) {
            val s = Seance.play(seed, bet, 3 + (seed % 3).toInt())
            assertTrue(s.payTokens == 0L || s.payTokens >= bet)
            val v = SoulVault.play(seed, bet)
            assertTrue("a vault always holds its five starting orbs", v.payTokens >= minOf(5L * bet, C.MAX_WIN_TOKENS.toLong()))
        }
        assertTrue(C.MAX_WIN_TOKENS >= C.STAKES.last())       // so the cap itself can never turn a win into less than the stake
    }

    // ------------------------------------------------------------------------------------------------ claim: linear in the stake or concave

    @Test fun everyMoneyComponentIsLinearInTheStakeUntilTheCapAndConcaveAfterIt() {
        val rng = Random(2024)
        fun check(label: String, mult: IntArray, tokens: LongArray) {
            for (i in C.STAKES.indices) {
                val s = C.STAKES[i]
                assertEquals("$label stake $s", minOf(C.MAX_WIN_TOKENS.toLong(), mult[i].toLong() * s), tokens[i])
                assertEquals("$label: the multiple of the stake must not depend on the stake", mult[0], mult[i])
                if (i > 0) assertTrue("$label: return per token staked must not grow with the stake",
                    tokens[i].toDouble() / s <= tokens[i - 1].toDouble() / C.STAKES[i - 1] + 1e-12)
            }
        }
        repeat(60_000) {
            val stops = IntArray(5) { r -> rng.nextInt(C.STRIPS[r].size) }
            val mini = if (rng.nextInt(3) == 0) ReaperEngine.rollMini(rng) else Mini.NONE
            val os = C.STAKES.map { ReaperEngine.outcome(it, stops, mini) }
            check("spin", IntArray(os.size) { os[it].totalMult }, LongArray(os.size) { os[it].payTokens })
        }
        for (k in 3..5) repeat(2_000) {
            val seed = rng.nextLong()
            val rs = C.STAKES.map { Seance.play(seed, it, k) }
            check("seance", IntArray(rs.size) { rs[it].totalMult.toInt() }, LongArray(rs.size) { rs[it].payTokens })
        }
        repeat(4_000) {
            val seed = rng.nextLong()
            val vs = C.STAKES.map { SoulVault.play(seed, it) }
            check("vault", IntArray(vs.size) { vs[it].totalMult }, LongArray(vs.size) { vs[it].payTokens })
        }
    }

    // ------------------------------------------------------------------------------------------------ claim: the cap

    @Test fun theCapIsAppliedToASpinASeanceAndAVaultSeparately() {
        // a spin that is capped: every cell wild (5 wilds on all 20 lines = 2000x) with a x5 multiplier
        val stops = IntArray(5)
        val jackpot = ReaperEngine.outcome(40, stops, Mini(C.MINI_SKULL, wildReels = IntArray(5) { it }, multiplier = 5))
        assertEquals(10_000, jackpot.totalMult)
        assertEquals(C.MAX_WIN_TOKENS.toLong(), jackpot.payTokens); assertTrue(jackpot.capped)
        assertEquals(C.MAX_WIN_TOKENS.toLong(), ReaperEngine.outcome(1, stops, Mini(C.MINI_SKULL, wildReels = IntArray(5) { it }, multiplier = 5)).payTokens)

        var seance: SeanceResult? = null; var vault: VaultResult? = null
        var seed = 1L
        while (seance == null || vault == null) {
            if (seance == null) Seance.play(seed, 40, 3).let { if (it.capped) seance = it }
            if (vault == null) SoulVault.play(seed, 40).let { if (it.capped) vault = it }
            seed++
        }
        assertEquals(C.MAX_WIN_TOKENS.toLong(), seance!!.payTokens); assertEquals(C.MAX_WIN_TOKENS.toLong(), vault!!.payTokens)
        assertTrue(seance!!.rawTokens > C.MAX_WIN_TOKENS && vault!!.rawTokens > C.MAX_WIN_TOKENS)

        // AUD-3: the cap is per component. One paid spin that unlocks both bonuses can therefore return three caps.
        val round = RoundResult(jackpot, 4, vault, seance)
        assertEquals(3L * C.MAX_WIN_TOKENS, round.totalTokens)
        assertTrue("a single round can exceed MAX_WIN_TOKENS (documented behaviour: 'per spin and per bonus')", round.totalTokens > C.MAX_WIN_TOKENS)
        assertEquals(6_000L, round.totalTokens)
    }

    @Test fun settleIsExactAndTheCapIsTwentyFiveBooks() {
        assertEquals(2000, C.MAX_WIN_TOKENS); assertEquals(25L * 80, C.MAX_WIN_TOKENS.toLong())
        for (raw in longArrayOf(0, 1, 79, 80, 1999, 2000, 2001, 1_000_000, Long.MAX_VALUE)) assertEquals(minOf(raw, 2000L), ReaperEngine.settle(raw))
        assertEquals(0L, ReaperEngine.settle(-1)); assertEquals(0L, ReaperEngine.settle(Long.MIN_VALUE))
    }

    // ------------------------------------------------------------------------------------------------ claim: relic bonus bet cannot be gamed (Wald)

    /**
     * The whole strategy-proofness argument rests on one fact: the chance that a spin reveals a relic does not depend on the stake or
     * on anything the player did before. Then the sum of the stakes on relic-revealing spins is p x (tokens wagered) in expectation
     * for EVERY adaptive staking policy (Wald), and the bonus bet (their floored mean) cannot beat the headline RTP.
     * z = (sum of relic-spin stakes - p x sum of stakes) / sqrt(p (1-p) sum of stakes^2) must be N(0,1) for an arbitrary adaptive policy.
     */
    @Test fun relicSpinsAreRandomSamplesOfTheStakesWaldIdentityHolds() {
        val p = C.RELIC_CHANCE
        for ((name, policy) in listOf<Pair<String, (Int, Int, Boolean) -> Int>>(
            "40 when 4 relics held, 25 right after a relic, 15 every 5th spin, else 1" to { relics, since, last -> if (relics == 4) 40 else if (last) 25 else if (since % 5 == 0) 15 else 1 },
            "ramp 1..40 within each relic gap" to { _, since, _ -> C.STAKES[minOf(since / 3, C.STAKES.size - 1)] },
            "40 only when exactly 3 relics and fewer than 10 spins since the last one" to { relics, since, _ -> if (relics == 3 && since < 10) 40 else 2 },
        )) {
            val rng = Random(name.hashCode().toLong())
            val meter = RelicMeter()
            var sumStake = 0.0; var sumSq = 0.0; var relicStake = 0.0
            var since = 0; var last = false
            val n = 600_000
            for (i in 0 until n) {
                val stake = policy(meter.relics, since, last)
                val r = Round.play(rng, stake, meter)
                sumStake += stake; sumSq += stake.toDouble() * stake
                last = r.relicIndex >= 0
                if (last) { relicStake += stake; since = 0 } else since++
            }
            val z = (relicStake - p * sumStake) / sqrt(p * (1 - p) * sumSq)
            println("Wald: $name -> relic-spin stake sum %.0f vs expected %.0f (z %.2f)".format(relicStake, p * sumStake, z))
            assertTrue("$name: z = $z", abs(z) < 4.5)
        }
    }

    @Test fun bonusBetIsNeverAboveTheMeanOfTheFiveRelicStakesOrBelowTheSmallest() {
        val rng = Random(9)
        repeat(100_000) {
            val m = RelicMeter(); var sum = 0L; var min = Int.MAX_VALUE; var bet = 0
            for (i in 1..5) { val s = C.STAKES[rng.nextInt(C.STAKES.size)]; sum += s; min = minOf(min, s); bet = m.collect(s) }
            assertTrue(bet * 5L <= sum); assertTrue(bet >= min); assertTrue(bet in 1..40)
        }
    }

    // ------------------------------------------------------------------------------------------------ claim: buys are priced above EV / 0.93

    @Test fun buyPricesCoverTheMeasuredExpectedPayoutDividedBy093() {
        // reference means from the exhaustive enumeration + 10^9-sample C Monte-Carlo in tools/audit (docs/AUDIT_v3.md section 1)
        val seanceEv = AuditReference.SEANCE_MEAN[3]; val vaultEv = AuditReference.VAULT_MEAN
        assertTrue("Seance buy ${C.BUY_SEANCE_X} vs EV/0.93 = ${seanceEv / 0.93}", C.BUY_SEANCE_X >= seanceEv / 0.93)
        assertTrue("Vault buy ${C.BUY_VAULT_X} vs EV/0.93 = ${vaultEv / 0.93}", C.BUY_VAULT_X >= vaultEv / 0.93)
        // and a bought round is the stake-linear thing the price says it is
        assertEquals(31L * 7, 7L * C.BUY_SEANCE_X)
    }

    // ------------------------------------------------------------------------------------------------ Money

    @Test fun moneyConversionsAreExactAndCashOutsRoundDown() {
        assertEquals(80L, Money.TOKENS_PER_BOOK)
        for (t in longArrayOf(0, 1, 9, 10, 19, 79, 80, 1999, 2000, Long.MAX_VALUE)) {
            val f = Money.floorToMack(t)
            assertTrue(f <= t && f >= 0 && f % 10 == 0L && t - f < 10)
        }
        assertEquals(0L, Money.floorToMack(-1)); assertEquals(0L, Money.floorToMack(Long.MIN_VALUE))
        assertEquals(1_234L, Money.toTokens(15, 3, 4))
        assertEquals("0 tokens", Money.describe(0)); assertEquals("1 token", Money.describe(1)); assertEquals("1 book 1 mack 1 token", Money.describe(91))
        assertEquals("25 books", Money.describe(2000)); assertTrue(Money.describe(Long.MAX_VALUE).startsWith("115292150460684697 books"))
        assertEquals("1B 1M 1T", Money.short(91)); assertEquals("5T", Money.short(5)); assertEquals("3M", Money.short(30))
        assertEquals("1,234,567", Money.fmt(1_234_567))
    }
}

/**
 * Reference values measured by tools/audit (python3 tools/audit/run_audit.py): exhaustive enumeration of every base-game
 * (74^5) and free-spin (58^5) reel combination, 10^8..10^9-sample C Monte-Carlo for the rest. See docs/AUDIT_v3.md section 1.
 */
object AuditReference {
    /** Plain spin (lines + coffin pay), x stake: EXACT, from the enumeration of all 2,219,006,624 stop combinations. */
    const val PLAIN_MEAN = 0.5177095316322949
    /** P(0..5 coffins) on a plain spin: EXACT. A Seance starts at 3+. */
    val PLAIN_COFFINS = doubleArrayOf(0.6552182189430004, 0.2890668612983825, 0.05101179905265574, 0.004501041092881388, 0.00019857534233300243, 3.504270747052984e-06)
    /** Mean total multiple of a spin carrying mini feature 1..4 (Reaper's Touch and Demon's Bargain exact, Crows and Skull MC). */
    val MINI_MEAN = doubleArrayOf(0.0, 8.46531, 10.7727, 16.77491, 7.3358)
    val MINI_SE = doubleArrayOf(0.0, 0.0, 0.00075, 0.0, 0.00052)
    /** Mean total multiple (x stake, uncapped) of THE SEANCE started by 3 / 4 / 5 coffins (10^9 / 6x10^8 / 4x10^8 sessions). */
    val SEANCE_MEAN = doubleArrayOf(0.0, 0.0, 0.0, 28.7649, 58.620, 119.689)
    val SEANCE_SE = doubleArrayOf(0.0, 0.0, 0.0, 0.00093, 0.0019, 0.0035)
    /** Mean total multiple (x bonus bet, uncapped) of the Soul Vault (3x10^8 vaults). */
    const val VAULT_MEAN = 28.3489
    const val VAULT_SE = 0.0013
    /** Chance per paid spin (plain and mini-feature spins mixed) that THE SEANCE starts: one per 213.16 spins. */
    const val SEANCE_PER_SPIN = 0.0046913204
    /** Measured RTP of a constant stake at each ReaperConfig.STAKES entry, 25-book cap included (SE <= 0.0011 percentage points). */
    val RTP_BY_STAKE = doubleArrayOf(0.93998, 0.93997, 0.93994, 0.93990, 0.93982, 0.93915, 0.93828, 0.93275, 0.92616, 0.91863, 0.90994, 0.88931)
    const val RTP_SE = 0.00002
}

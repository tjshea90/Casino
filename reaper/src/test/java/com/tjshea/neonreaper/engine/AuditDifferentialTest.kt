package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Differential tests of the shipped engine against [AuditRef], a second implementation written from the rules: results must be
 * EXACTLY equal on every random input (no statistics involved), plus statistical checks that the random content of the mini
 * features follows the configured weights. Together with the exhaustive enumeration in tools/audit this ties the exact maths
 * to the code that really runs.
 */
class AuditDifferentialTest {
    private val C = ReaperConfig

    @Test fun plainAndMiniSpinsEqualTheReferenceExactly() {
        val rng = Random(77)
        var withWins = 0; var withCoffins = 0
        repeat(500_000) { i ->
            val stake = C.STAKES[rng.nextInt(C.STAKES.size)]
            val stops = IntArray(5) { rng.nextInt(C.STRIPS[it].size) }
            val mini = if (i % 5 == 0) Mini.NONE else ReaperEngine.rollMini(rng, 1 + i % 5 % 4)
            val o = ReaperEngine.outcome(stake, stops, mini)
            val ref = AuditRef.spin(C.STRIPS, stops, mini, inSeance = false)
            assertEquals("lines", ref.lines, o.lineMult)
            assertEquals("coffins", ref.coffins, o.scatterCount)
            assertEquals("total multiple", ref.totalMult, o.totalMult)
            assertEquals("tokens", minOf(C.MAX_WIN_TOKENS.toLong(), ref.totalMult.toLong() * stake), o.payTokens)
            assertEquals("seance spins", if (ref.coffins >= 3) C.FS_SPINS[minOf(ref.coffins, 5)] else 0, o.seanceSpins)
            if (ref.totalMult > 0) withWins++
            if (ref.coffins >= 3) withCoffins++
        }
        assertTrue(withWins > 100_000 && withCoffins > 100)
    }

    @Test fun seancesEqualTheReferenceExactly() {
        var retriggered = 0; var capped = 0
        for (coffins in 3..5) for (seed in 1L..120_000L) {
            val s = Seance.play(seed * 7919 + coffins, 3, coffins)
            val ref = AuditRef.seance(seed * 7919 + coffins, coffins)
            assertEquals("total multiple (seed $seed, $coffins coffins)", ref.totalMult, s.totalMult)
            assertEquals("spins played", ref.spinsPlayed, s.totalSpins)
            assertEquals("spins left after each spin", ref.leftAfter.toList(), s.spins.map { it.spinsLeftAfter })
            assertEquals("tokens", minOf(C.MAX_WIN_TOKENS.toLong(), ref.totalMult * 3), s.payTokens)
            if (s.retriggers > 0) retriggered++
            if (s.capped) capped++
        }
        assertTrue("retriggers must occur in this sample", retriggered > 1_000)
    }

    @Test fun soulVaultsEqualTheReferenceExactly() {
        var grands = 0; var reapers = 0
        for (seed in 1L..400_000L) {
            val v = SoulVault.play(seed * 104729, 5)
            val ref = AuditRef.vault(seed * 104729)
            assertEquals("seed $seed", ref, v.totalMult)
            if (v.grand) grands++
            if (v.cells.any { it?.kind == Orb.REAPER }) reapers++
        }
        assertTrue(reapers > 1_000)
    }

    // ------------------------------------------------------------------------------------------------ random content of the mini features

    private fun z(observed: Double, n: Double, p: Double) = (observed - n * p) / sqrt(n * p * (1 - p))
    private fun assertClose(label: String, z: Double) { assertTrue("$label: z = $z", abs(z) < 5.0) }

    @Test fun miniFeatureContentFollowsTheConfiguredWeights() {
        val rng = Random(5150)
        val n = 1_200_000
        val w = C.MINI_WEIGHTS; val ws = w.sum()
        // which feature
        val typeCount = IntArray(5)
        repeat(n) { typeCount[ReaperEngine.rollMini(rng).type]++ }
        assertEquals(0, typeCount[0])
        for (t in 1..4) assertClose("feature type $t", z(typeCount[t].toDouble(), n.toDouble(), w[t] / ws))

        // Reaper's Touch: which reels turn wild
        val pat = IntArray(C.FEATURE_PATTERNS.size)
        repeat(n) {
            val m = ReaperEngine.rollMini(rng, C.MINI_REAPER)
            assertTrue(m.wildCells.isEmpty() && m.multiplier == 1 && m.coins.all { it == 0 })
            pat[C.FEATURE_PATTERNS.indexOfFirst { p -> p.toList() == m.wildReels.toList() }]++
        }
        for (i in pat.indices) assertClose("reaper pattern $i", z(pat[i].toDouble(), n.toDouble(), C.FEATURE_WEIGHTS[i] / C.FEATURE_WEIGHTS.sum()))

        // Murder of Crows / Skull's Curse: how many cells, and every cell equally likely
        for (type in intArrayOf(C.MINI_RAVEN, C.MINI_SKULL)) {
            val cellHits = DoubleArray(15); val countHits = IntArray(7); val multHits = IntArray(C.SKULL_MULTS.size)
            repeat(n) {
                val m = ReaperEngine.rollMini(rng, type)
                assertTrue(m.wildReels.isEmpty() && m.wildCells.toSet().size == m.wildCells.size && m.wildCells.all { it in 0..14 })
                countHits[m.wildCells.size]++
                for (c in m.wildCells) cellHits[c]++
                if (type == C.MINI_SKULL) multHits[C.SKULL_MULTS.indexOf(m.multiplier)]++ else assertEquals(1, m.multiplier)
            }
            val counts = if (type == C.MINI_RAVEN) C.RAVEN_WILDS else intArrayOf(C.SKULL_WILDS)
            val cw = if (type == C.MINI_RAVEN) C.RAVEN_WILD_WEIGHTS else doubleArrayOf(1.0)
            for (i in counts.indices) assertClose("type $type wild count ${counts[i]}", z(countHits[counts[i]].toDouble(), n.toDouble(), cw[i] / cw.sum()))
            val meanCells = counts.indices.sumOf { counts[it] * cw[it] } / cw.sum()
            for (c in 0 until 15) assertClose("type $type cell $c", z(cellHits[c], n.toDouble() * meanCells, 1.0 / 15))
            if (type == C.MINI_SKULL) for (i in multHits.indices)
                assertClose("skull multiplier ${C.SKULL_MULTS[i]}", z(multHits[i].toDouble(), n.toDouble(), C.SKULL_MULT_WEIGHTS[i] / C.SKULL_MULT_WEIGHTS.sum()))
        }

        // Demon's Bargain: number of coins, their values, uniform cells
        val coinN = IntArray(10); val valHits = IntArray(C.COIN_VALUES.size); val cellHits = DoubleArray(15); var coinsTotal = 0.0
        repeat(n) {
            val m = ReaperEngine.rollMini(rng, C.MINI_DEMON)
            assertTrue(m.wildReels.isEmpty() && m.wildCells.isEmpty() && m.multiplier == 1)
            var cnt = 0
            for (c in 0 until 15) if (m.coins[c] > 0) { cnt++; cellHits[c]++; valHits[C.COIN_VALUES.indexOf(m.coins[c])]++ }
            coinN[cnt]++; coinsTotal += cnt
        }
        for (i in C.COIN_COUNTS.indices) assertClose("coin count ${C.COIN_COUNTS[i]}", z(coinN[C.COIN_COUNTS[i]].toDouble(), n.toDouble(), C.COIN_COUNT_WEIGHTS[i] / C.COIN_COUNT_WEIGHTS.sum()))
        for (i in C.COIN_VALUES.indices) assertClose("coin value ${C.COIN_VALUES[i]}", z(valHits[i].toDouble(), coinsTotal, C.COIN_WEIGHTS[i] / C.COIN_WEIGHTS.sum()))
        for (c in 0 until 15) assertClose("coin cell $c", z(cellHits[c], coinsTotal, 1.0 / 15))
    }

    @Test fun spinRatesFollowTheConfiguration() {
        val rng = Random(31337)
        val n = 3_000_000
        var minis = 0
        repeat(n) { if (ReaperEngine.spin(rng, 1).feature) minis++ }
        assertClose("mini feature rate", z(minis.toDouble(), n.toDouble(), C.FEATURE_RATE))

        // relic roll: independent of the stake, 5/135 per paid spin; the fifth relic opens a vault
        val meter = RelicMeter()
        var relics = 0; var vaults = 0
        val m = 2_000_000
        for (i in 0 until m) {
            val stake = C.STAKES[i % C.STAKES.size]
            val r = Round.play(rng, stake, meter)
            if (r.relicIndex >= 0) relics++
            if (r.vault != null) vaults++
        }
        assertClose("relic rate", z(relics.toDouble(), m.toDouble(), C.RELIC_CHANCE))
        // the meter starts empty and counts every relic: a vault opens exactly at every fifth one
        assertEquals(relics / 5, vaults)
    }
}

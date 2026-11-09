package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The headline guarantee, calculated exactly (no simulation noise): Neon Reaper's theoretical RTP is 94.00 % and
 * the house edge is therefore 6.00 % of every token wagered. Every exact mean is cross-checked against the REAL engine code
 * with a simulation (so a wrong derivation cannot hide), and the published figures in ReaperConfig must stay true.
 */
class RtpExactTest {

    private val m = ExactMath.means()

    @Test fun printParSheet() {
        val f = ReaperConfig.FEATURE_RATE
        val lines = (1 - f) * m.base + f * m.miniLines
        val scat = (1 - f) * m.scatPayBase + f * m.scatPayMini
        val seance = (1 - f) * m.seanceBase + f * m.seanceMini
        println("PAR: base lines %.4f (per-line hit %.5f) | mini: reaper %.3f crows %.3f demon %.3f skull %.3f -> mix %.3f | vault %.3f (P full board 1/%.0f)"
            .format(m.base, m.hitPerLine, m.reaper, m.raven, m.demon, m.skull, m.miniLines, m.vault, 1 / m.vaultFullBoard))
        println("PAR: Seance spin mu %.3f | 3/4/5 coffins -> %.2f / %.2f / %.2f x stake (%.1f / %.1f / %.1f spins played) | one every %.0f spins"
            .format(m.seance.mu, m.seance.value[3], m.seance.value[4], m.seance.value[5], m.seance.spins[3], m.seance.spins[4], m.seance.spins[5], m.seanceEvery))
        println("RTP budget: lines+minis %.3f%% + coffin pays %.3f%% + SEANCE %.3f%% + Soul Vault %.3f%% = %.4f%%   (vault every %.0f spins, mini feature every %.0f spins)"
            .format(100 * lines, 100 * scat, 100 * seance, 100 * m.trigVault * m.vault, 100 * m.uncappedRtp, 1 / m.trigVault, 1 / f))
    }

    @Test fun theoreticalRtpIs94Percent() {
        assertEquals(ReaperConfig.TARGET_RTP, m.uncappedRtp, 0.0002)
        // the house edge must never be smaller than advertised
        assertTrue("RTP ${m.uncappedRtp} must not exceed ${ReaperConfig.TARGET_RTP}", m.uncappedRtp <= ReaperConfig.TARGET_RTP + 1e-4)
        // the published mini-feature rate is the exact solution of the budget equation
        assertEquals("FEATURE_RATE is not the exact solution", m.exactFeatureRate, ReaperConfig.FEATURE_RATE, 2e-6)
    }

    /** The Soul Vault's exact mean must match the real implementation (guards against a wrong derivation). */
    @Test fun vaultExactMeanMatchesTheRealCode() {
        val rng = Random(99)
        val n = 600_000
        var sum = 0.0; var sumSq = 0.0
        for (i in 0 until n) { val v = SoulVault.play(rng.nextLong(), 1).totalMult.toDouble(); sum += v; sumSq += v * v }
        val mean = sum / n
        val sd = Math.sqrt(sumSq / n - mean * mean)
        val se = sd / Math.sqrt(n.toDouble())
        println("Vault: exact %.4f  simulated %.4f +/- %.4f (SD %.1f)".format(m.vault, mean, se, sd))
        assertEquals(m.vault, mean, 5 * se)
    }

    /** Same check for each mini feature type (lines x multiplier + coins; coffin pay is checked separately). */
    @Test fun miniFeatureExactMeansMatchTheRealCode() {
        val rng = Random(7)
        val n = 400_000
        val stops = IntArray(5)
        val sums = DoubleArray(5); val sq = DoubleArray(5)
        for (type in 1..4) for (i in 0 until n) {
            for (r in 0 until 5) stops[r] = rng.nextInt(ReaperConfig.STRIPS[r].size)
            val o = ReaperEngine.outcome(1, stops, ReaperEngine.rollMini(rng, type))
            val x = (o.totalMult - o.scatterMult).toDouble()
            sums[type] += x; sq[type] += x * x
        }
        val exact = doubleArrayOf(0.0, m.reaper, m.raven, m.demon, m.skull)
        for (type in 1..4) {
            val mean = sums[type] / n
            val se = Math.sqrt(sq[type] / n - mean * mean) / Math.sqrt(n.toDouble())
            println("mini %s: exact %.4f simulated %.4f +/- %.4f".format(ReaperConfig.MINI_NAMES[type], exact[type], mean, se))
            assertEquals(ReaperConfig.MINI_NAMES[type], exact[type], mean, 5 * se)
        }
    }

    @Test fun baseGameExactMeanMatchesTheRealCode() {
        val rng = Random(21)
        val n = 3_000_000
        val stops = IntArray(5)
        var sum = 0.0; var sq = 0.0; var hits = 0
        for (i in 0 until n) {
            for (r in 0 until 5) stops[r] = rng.nextInt(ReaperConfig.STRIPS[r].size)
            val o = ReaperEngine.outcome(1, stops)
            val x = (o.totalMult - o.scatterMult).toDouble()
            sum += x; sq += x * x; if (o.totalMult > 0) hits++
        }
        val mean = sum / n
        val se = Math.sqrt(sq / n - mean * mean) / Math.sqrt(n.toDouble())
        println("Base game: exact %.4f simulated %.4f +/- %.4f | hit rate %.4f (1 in %.2f)".format(m.base, mean, se, hits.toDouble() / n, n.toDouble() / hits))
        assertEquals(m.base, mean, 5 * se)
        // published win frequency: every win pays at least the stake
        assertTrue("hit rate ${hits.toDouble() / n} outside the published range", hits.toDouble() / n in ReaperConfig.HIT_RATE_RANGE)
    }

    /** Coffin counts on the FINAL grid (after wilds) match the exact Poisson-binomial / thinning distributions. */
    @Test fun coffinDistributionsMatchTheRealCode() {
        val rng = Random(31)
        val n = 1_500_000
        val stops = IntArray(5)
        val names = listOf("plain", "reaper", "crows", "demon", "skull")
        val exact = listOf(m.scat.base, m.scat.reaper, m.scat.raven, m.scat.demon, m.scat.skull)
        for ((idx, name) in names.withIndex()) {
            val counts = IntArray(6)
            for (i in 0 until n) {
                for (r in 0 until 5) stops[r] = rng.nextInt(ReaperConfig.STRIPS[r].size)
                val mini = if (idx == 0) Mini.NONE else ReaperEngine.rollMini(rng, idx)
                counts[ReaperEngine.outcome(1, stops, mini).scatterCount.coerceAtMost(5)]++
            }
            for (k in 0..4) {
                val p = exact[idx][k]
                val se = Math.sqrt(p * (1 - p) / n) + 1e-9
                assertEquals("$name: P($k coffins)", p, counts[k].toDouble() / n, 5 * se)
            }
        }
    }

    /** THE SÉANCE: exact expected payout and spins played match the real session generator, for 3, 4 and 5 coffins. */
    @Test fun seanceExactMeansMatchTheRealCode() {
        val rng = Random(41)
        for (coffins in 3..5) {
            val n = if (coffins == 3) 400_000 else 250_000
            var sum = 0.0; var sq = 0.0; var spins = 0.0
            for (i in 0 until n) {
                val r = Seance.play(rng.nextLong(), 1, coffins)
                val x = r.totalMult.toDouble()
                sum += x; sq += x * x; spins += r.totalSpins
            }
            val mean = sum / n
            val se = Math.sqrt(sq / n - mean * mean) / Math.sqrt(n.toDouble())
            println("Seance %d coffins: exact %.3f simulated %.3f +/- %.3f | spins played exact %.2f simulated %.2f"
                .format(coffins, m.seance.value[coffins], mean, se, m.seance.spins[coffins], spins / n))
            assertEquals("Seance $coffins coffins", m.seance.value[coffins], mean, 5 * se)
            assertEquals("spins played with $coffins coffins", m.seance.spins[coffins], spins / n, 0.03 * m.seance.spins[coffins])
        }
    }

    @Test fun everyWinPaysAtLeastTheStake() {
        for (sym in 0 until ReaperConfig.SYMBOL_COUNT) if (sym != ReaperConfig.SCATTER) for (pay in ReaperConfig.PAYS[sym]) assertTrue(pay >= 1)
        for (k in 3..5) assertTrue(ReaperConfig.SCATTER_PAY[k] >= 1)
        assertTrue(ReaperConfig.COIN_VALUES.all { it >= 1 } && ReaperConfig.ORB_VALUES.all { it >= 1 })
        // the coffin never pays on a line
        assertTrue(ReaperConfig.PAYS[ReaperConfig.SCATTER].all { it == 0 })
    }

    @Test fun configIsInternallyConsistent() {
        assertEquals(ReaperConfig.STAKES.size, ReaperConfig.RTP_BY_STAKE.size)
        assertTrue(ReaperConfig.RTP_BY_STAKE.all { it <= ReaperConfig.TARGET_RTP + 1e-9 })
        assertTrue(ReaperConfig.RTP_BY_STAKE.toList().zipWithNext().all { (a, b) -> b <= a + 1e-9 })   // the cap only lowers RTP
        // 3D models (wild, five characters, coffin) stay a third of every reel so the cast keeps appearing
        for (strip in ReaperConfig.STRIPS) {
            val cast = strip.count { it in ReaperConfig.WILD..ReaperConfig.WRAITH || it == ReaperConfig.SCATTER }
            assertTrue(cast.toDouble() / strip.size >= 0.33)
        }
        // coffins are spread out: at most one per 3-row window (the exact model relies on it)
        for (strip in ReaperConfig.STRIPS + ReaperConfig.FS_STRIPS) ExactMath.presence(strip)
        // Seance awards grow with the coffin count, and the multiplier ramp starts at x1
        assertTrue(ReaperConfig.FS_SPINS[3] < ReaperConfig.FS_SPINS[4] && ReaperConfig.FS_SPINS[4] < ReaperConfig.FS_SPINS[5])
        assertEquals(1, Seance.play(1L, 1, 3).spins[0].multiplier)
    }

    /** Feature buys are priced above their exact expected payout: a buy can never return more than 93 %. */
    @Test fun buyPricesAreAboveExactExpectedPayout() {
        val seanceEv = m.seance.value[3]       // a bought Seance is the 3-coffin award
        assertTrue("Seance buy RTP ${seanceEv / ReaperConfig.BUY_SEANCE_X}", seanceEv / ReaperConfig.BUY_SEANCE_X <= 0.93 + 1e-9)
        assertTrue("Seance buy is not absurdly overpriced", seanceEv / ReaperConfig.BUY_SEANCE_X >= 0.88)
        assertTrue("Vault buy RTP ${m.vault / ReaperConfig.BUY_VAULT_X}", m.vault / ReaperConfig.BUY_VAULT_X <= 0.93 + 1e-9)
        assertTrue("Vault buy is not absurdly overpriced", m.vault / ReaperConfig.BUY_VAULT_X >= 0.88)
    }
}

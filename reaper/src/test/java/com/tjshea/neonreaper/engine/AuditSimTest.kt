package com.tjshea.neonreaper.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Monte-Carlo of the SHIPPED engine against the reference values produced by the independent audit tools (tools/audit: exhaustive
 * enumeration of 74^5 base and 58^5 free-spin reel combinations, 10^8..10^9-sample C Monte-Carlo of the rest; docs/AUDIT_v3.md).
 * Set AUDIT_SCALE (e.g. 50) for the long run quoted in the report; the default finishes in about half a minute.
 */
class AuditSimTest {
    private val C = ReaperConfig
    private val scale = System.getenv("AUDIT_SCALE")?.toDoubleOrNull() ?: 1.0
    private fun n(base: Int) = (base * scale).toInt()

    private class Stat { var n = 0L; var sum = 0.0; var sq = 0.0
        fun add(x: Double) { n++; sum += x; sq += x * x }
        val mean get() = sum / n
        val se get() = sqrt(maxOf(sq / n - mean * mean, 0.0) / n)
    }

    private fun check(label: String, st: Stat, reference: Double, referenceSe: Double = 0.0) {
        val z = (st.mean - reference) / sqrt(st.se * st.se + referenceSe * referenceSe)
        println("%-44s engine %.4f +/- %.4f   reference %.4f   z %+.2f   (N=%,d, SD of one sample %.2f)".format(label, st.mean, st.se, reference, z, st.n, st.se * sqrt(st.n.toDouble())))
        assertTrue("$label: engine ${st.mean} vs reference $reference (z = $z)", abs(z) < 5.0)
    }

    @Test fun componentMeansOfTheRealEngineMatchTheIndependentReference() {
        val rng = Random(20261109)
        // plain spin (lines + coffin pay), reference from the exhaustive enumeration of every stop combination
        val plain = Stat()
        for (i in 0 until n(2_000_000)) {
            val stops = IntArray(5) { rng.nextInt(C.STRIPS[it].size) }
            plain.add(ReaperEngine.outcome(1, stops).totalMult.toDouble())
        }
        check("plain spin", plain, AuditReference.PLAIN_MEAN)

        // the four mini features (lines x multiplier + soul coins + coffin pay)
        for (type in 1..4) {
            val st = Stat()
            for (i in 0 until n(700_000)) {
                val stops = IntArray(5) { rng.nextInt(C.STRIPS[it].size) }
                st.add(ReaperEngine.outcome(1, stops, ReaperEngine.rollMini(rng, type)).totalMult.toDouble())
            }
            check("mini feature: ${C.MINI_NAMES[type]}", st, AuditReference.MINI_MEAN[type], AuditReference.MINI_SE[type])
        }

        // THE SEANCE started by 3 / 4 / 5 coffins
        for (k in 3..5) {
            val st = Stat()
            for (i in 0 until n(if (k == 3) 600_000 else if (k == 4) 300_000 else 200_000)) st.add(Seance.play(rng.nextLong(), 1, k).totalMult.toDouble())
            check("Seance started by $k coffins", st, AuditReference.SEANCE_MEAN[k], AuditReference.SEANCE_SE[k])
        }

        // Soul Vault
        val vault = Stat()
        for (i in 0 until n(700_000)) vault.add(SoulVault.play(rng.nextLong(), 1).totalMult.toDouble())
        check("Soul Vault", vault, AuditReference.VAULT_MEAN, AuditReference.VAULT_SE)
    }

    /** Coffin counts of plain spins follow the exhaustive-enumeration distribution (the Seance trigger chance). */
    @Test fun coffinCountsOfThePlainGridMatchTheEnumeration() {
        val rng = Random(99)
        val total = n(4_000_000)
        val counts = IntArray(6)
        for (i in 0 until total) {
            val stops = IntArray(5) { rng.nextInt(C.STRIPS[it].size) }
            counts[ReaperEngine.outcome(1, stops).scatterCount.coerceAtMost(5)]++
        }
        for (k in 0..4) {
            val p = AuditReference.PLAIN_COFFINS[k]
            val z = (counts[k] - total * p) / sqrt(total * p * (1 - p))
            println("P($k coffins): engine %.6f exhaustive %.6f z %+.2f".format(counts[k].toDouble() / total, p, z))
            assertTrue("P($k coffins) z = $z", abs(z) < 5.0)
        }
    }

    /** The composition: Round.play, the very function the Wallet calls, at several stakes against the audited RTP. */
    @Test fun fullRoundsAtSeveralStakesMatchTheAuditedRtp() {
        for (stake in intArrayOf(1, 10, 40)) {
            val rng = Random(1000L + stake)
            val meter = RelicMeter()
            val perSpin = Stat()
            var seances = 0L; var vaults = 0L
            val spins = n(2_000_000)
            for (i in 0 until spins) {
                val r = Round.play(rng, stake, meter)
                if (r.seance != null) seances++
                if (r.vault != null) vaults++
                perSpin.add(r.totalTokens.toDouble() / stake)
            }
            val ref = AuditReference.RTP_BY_STAKE[C.STAKES.indexOf(stake)]
            check("Round.play RTP at stake $stake", perSpin, ref, AuditReference.RTP_SE)
            assertTrue("RTP at stake $stake must not exceed 94 %", perSpin.mean <= C.TARGET_RTP + 4.5 * perSpin.se)
            val seanceZ = (seances - spins * AuditReference.SEANCE_PER_SPIN) / sqrt(spins * AuditReference.SEANCE_PER_SPIN)
            val vaultZ = (vaults - spins / 135.0) / sqrt(spins / 135.0)
            println("   stake %d: Seances %d (z %+.2f vs 1 in %.1f), Vaults %d (z %+.2f vs 1 in 135)".format(stake, seances, seanceZ, 1 / AuditReference.SEANCE_PER_SPIN, vaults, vaultZ))
            assertTrue(abs(seanceZ) < 5.0 && abs(vaultZ) < 5.0)
        }
    }

    @Test fun publishedRtpTableIsConservativeAndWithinTolerance() {
        // docs/AUDIT_v3.md: measured per-stake RTP (SE <= 0.0011 pp) vs ReaperConfig.RTP_BY_STAKE
        for (i in C.STAKES.indices) {
            val measured = AuditReference.RTP_BY_STAKE[i]; val published = C.RTP_BY_STAKE[i]
            assertTrue("stake ${C.STAKES[i]}: measured $measured vs published $published", abs(measured - published) < 0.0012)
            assertTrue("measured RTP must not exceed the 94 % target", measured <= C.TARGET_RTP + 1e-9)
        }
        assertEquals(0.94, AuditReference.RTP_BY_STAKE[0], 0.00005)
    }
}

package com.tjshea.neonreaper.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

/** Fast checks of the DSP primitives the whole sound set stands on. */
class AudioDspTest {
    @Test fun reverbGainIsNormalisedAcrossDecayTimes() {
        val r = Rng(1); val n = SR * 12
        val src = FloatArray(n) { r.signed() * 0.3f }
        val inRms = rmsOf(src)
        for ((rt, size) in listOf(0.45 to 0.7, 1.7 to 1.3, 3.0 to 1.55, 5.2 to 2.05)) {
            val l = FloatArray(n); val rr = FloatArray(n)
            Reverb.run(src, n, ReverbSpec(rt, size, 2700.0), 0, 1f, l, rr)
            val g = 20 * log10(((rmsOf(l, SR * 8, n) + rmsOf(rr, SR * 8, n)) / 2) / inRms)
            assertTrue("rt=$rt size=$size wet gain $g dB should be about -1 dB", g in -2.5..0.8)
        }
    }

    @Test fun loopedReverbTailFoldsOntoTheStart() {
        // an impulse at the very end of a loop must leave its tail at the start: the result is periodic
        val n = SR * 4; val src = FloatArray(n); src[n - 10] = 1f
        val l = FloatArray(n); val r = FloatArray(n)
        Reverb.run(src, n, ReverbSpec(1.0, 1.0, 3000.0), frames(1.2), 1f, l, r, true)
        var head = 0.0; for (i in 0 until SR / 4) head += abs(l[i])
        assertTrue("tail folded onto the loop start", head > 1e-3)
        assertTrue("no jump across the seam", abs(l[0] - l[n - 1]) < 0.05)
    }

    @Test fun limiterHonoursTheCeilingWithoutHardClipping() {
        val st = Stereo(SR); for (i in 0 until SR) { val v = (kotlin.math.sin(i * 0.05) * 2.0).toFloat(); st.l[i] = v; st.r[i] = v * 0.5f }
        val red = Master.limit(st, 0.84f)
        assertTrue(Master.peak(st.l, st.r) <= 0.84f + 1e-4f)
        assertTrue("gain reduction reported", red > 6.0)
    }

    @Test fun mastersHitTheLoudnessTarget() {
        val st = Stereo(SR); val r = Rng(3)
        for (i in 0 until SR) { st.l[i] = r.signed() * 0.05f; st.r[i] = r.signed() * 0.05f }
        Master.toLoudness(st, -18.0, -1.5)
        assertEquals(-18.0, Master.rmsDb(st), 0.3)
        assertTrue(Master.peak(st.l, st.r) <= 0.85f)
    }

    @Test fun wavRoundTripsAndRejectsGarbage() {
        val f = File.createTempFile("neon", ".wav")
        val pcm = Pcm(ShortArray(100) { (it * 300).toShort() }, ShortArray(100) { (-it * 200).toShort() })
        Wav.write(f, pcm)
        val back = Wav.read(f)!!
        assertTrue(back.l.contentEquals(pcm.l) && back.r!!.contentEquals(pcm.r!!))
        f.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(Wav.read(f) == null)
        f.delete()
    }

    @Test fun sawTablesNeverAliasAndNoiseIsDeterministic() {
        // the highest harmonic of the table chosen for f0 stays below Nyquist
        for (f in doubleArrayOf(30.0, 73.4, 220.0, 880.0, 2000.0, 5000.0)) {
            val tab = Tables.sawFor(f)
            val osc = SawOsc(0.0); val x = DoubleArray(4096) { osc.tick(f / SRD, tab).toDouble() }
            var hi = 0.0; var tot = 0.0
            for (k in 0 until 2048) {
                var re = 0.0; var im = 0.0
                if (k < 5 || k % 8 != 0) continue
                for (i in x.indices) { re += x[i] * kotlin.math.cos(2 * Math.PI * k * i / x.size); im += x[i] * kotlin.math.sin(2 * Math.PI * k * i / x.size) }
                val e = re * re + im * im
                tot += e
                if (k > 1900) hi += e
            }
            assertTrue("f0=$f energy near Nyquist ${hi / max(tot, 1e-9)}", hi / max(tot, 1e-9) < 0.02)
        }
        val a = Rng(42); val b = Rng(42)
        for (i in 0 until 100) assertEquals(a.next(), b.next())
    }

    @Test fun scaleRatesAreMusical() {
        assertEquals(1f, Audio.scaleRate(0), 1e-6f)
        assertEquals(1.1224621f, Audio.scaleRate(1), 1e-4f)       // E over D
        assertEquals(1.1892071f, Audio.scaleRate(2), 1e-4f)       // F
        assertEquals(1.4983071f, Audio.scaleRate(4), 1e-4f)       // A
        assertEquals(2f, Audio.scaleRate(7), 1e-5f)
        assertEquals(0.5f, Audio.scaleRate(-7), 1e-5f)
        assertEquals(2f, Audio.scaleRate(30), 0f)                 // clamped to the SoundPool range
    }
}

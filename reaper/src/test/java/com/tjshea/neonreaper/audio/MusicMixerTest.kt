package com.tjshea.neonreaper.audio

import com.tjshea.neonreaper.audio.Audio.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/** Behaviour of the streaming mixer core (adaptive layers, duck, cross-fade, pause, wrap) with synthetic DC layers. */
class MusicMixerTest {
    private val loop = 4 * 4 * SR / 2                 // 16 beats at 120 BPM = 8 s
    private fun spec(track: Track = Track.BASE) = TrackSpec(track, 120.0, 4, 4, floatArrayOf(0f, 0.15f, 0.4f, 0.7f), floatArrayOf(0f, 0.2f, 0.2f, 0.2f),
        List(4) { LayerSpec(track, it, "l$it", -28.0, true) })

    /** Layer k = constant 1000 * 2^k so each layer's contribution can be separated (and the sum never clips). */
    private fun track(track: Track = Track.BASE): MixTrack {
        val mt = MixTrack(spec(track))
        for (k in 0 until 4) { val v = (1000 shl k).toShort(); mt.put(k, MixLayer(ShortArray(loop) { v }, ShortArray(loop) { v }, 0.5f)) }
        return mt
    }

    private fun run(m: MusicMixer, seconds: Double): FloatArray {
        val n = (seconds * SR).toInt(); val buf = ShortArray(2 * 1024); val out = FloatArray(n); var i = 0
        while (i < n) { val c = minOf(1024, n - i); m.render(buf, c); for (j in 0 until c) out[i + j] = buf[2 * j] / 32768f; i += c }
        return out
    }

    private fun mean(a: FloatArray, from: Double, to: Double): Double { var s = 0.0; val i0 = (from * SR).toInt(); val i1 = (to * SR).toInt(); for (i in i0 until i1) s += a[i]; return s / (i1 - i0) }

    @Test fun layersFadeInCumulativelyWithIntensity() {
        val m = MusicMixer(); m.setTrack(track())
        val level = DoubleArray(5)
        val ints = floatArrayOf(0f, 0.1f, 0.3f, 0.6f, 1f)
        val gains = DoubleArray(4) { 1000.0 * (1 shl it) / 32768.0 * 0.95 }
        for ((k, i) in ints.withIndex()) { m.intensity = i; val o = run(m, 14.0); level[k] = mean(o, 12.0, 14.0) }
        assertEquals("only L0 below 0.15", gains[0], level[0], 0.004); assertEquals(gains[0], level[1], 0.004)
        assertTrue("L1 on at 0.3", level[2] > gains[0] + 0.5 * gains[1])
        assertTrue("L2 on at 0.6", level[3] > level[2] + 0.4 * gains[2])
        assertEquals("all four layers at 1.0", gains.sum(), level[4], 0.01)
        for (k in 1 until 5) assertTrue("monotonic", level[k] >= level[k - 1] - 1e-4)
    }

    @Test fun layerFadeTimeConstantIsAtLeast1_2s_andNeverOvershoots() {
        val m = MusicMixer(); m.setTrack(track()); m.intensity = 0f; run(m, 14.0)
        m.intensity = 0.55f                                   // L1 fully in (>= 0.35), L2 half way (0.4..0.6)
        val o = run(m, 12.0)
        val l1 = 2000.0 / 32768 * 0.95
        val base = 1000.0 / 32768 * 0.95
        fun frac(t: Double) = (mean(o, t, t + 0.05) - base) / (mean(o, 11.0, 11.95) - base)
        // after one time constant a first-order response is at 63 %; with TAU_UP = 1.3 s allow 55..70 % (the L2 ramp rides on top)
        val f13 = frac(1.3)
        assertTrue("63% point at 1.3 s: $f13", f13 in 0.5..0.75)
        var prev = -1.0
        for (t in 0 until 100) { val v = mean(o, t * 0.1, t * 0.1 + 0.1); assertTrue("overshoot at $t", v >= prev - 1e-4); prev = v }
        assertTrue(l1 > 0)
    }

    @Test fun duckHasFastAttackAndSlowRelease() {
        val m = MusicMixer(); m.setTrack(track(Track.VAULT)); run(m, 3.0)
        val ref = mean(run(m, 0.5), 0.0, 0.5)
        m.duck(1.0f, 0.3f)
        val o = run(m, 3.0)
        val at150 = mean(o, 0.14, 0.16) / ref; val hold = mean(o, 0.6, 0.9) / ref
        assertTrue("ducked within ~150 ms: $at150", at150 < 0.45)
        assertEquals("hold level", 0.3, hold, 0.03)
        val after600 = mean(o, 1.55, 1.65) / ref      // 1.0 s hold + ~0.6 s release
        assertTrue("released ~600 ms after the hold: $after600", after600 > 0.85)
        assertTrue("not instantly back right after the hold: ${mean(o, 1.02, 1.06) / ref}", mean(o, 1.02, 1.06) / ref < 0.6)
        assertEquals("back to normal", 1.0, mean(o, 2.6, 2.9) / ref, 0.02)
    }

    @Test fun trackChangeCrossFadesWithoutLevelDip() {
        val a = MixTrack(spec(Track.BASE)); val b = MixTrack(spec(Track.VAULT))
        a.put(0, MixLayer(ShortArray(loop) { 6000 }, null, 0f)); b.put(0, MixLayer(ShortArray(loop) { 6000 }, null, 0f))
        val m = MusicMixer(); m.setTrack(a); run(m, 4.0)
        m.setTrack(b)
        val o = run(m, 3.0)
        val ref = 6000 / 32768.0 * 0.95
        assertEquals(Track.VAULT, m.currentTrack)
        var worst = 10.0
        for (t in 0 until 28) worst = minOf(worst, mean(o, t * 0.1, t * 0.1 + 0.1) / ref)
        // identical material cross-faded with equal-power curves swells by up to +3 dB but must never dip
        assertTrue("dip during the cross-fade: $worst", worst > 0.97)
        assertTrue("completes within ~1.6 s", abs(mean(o, 2.0, 2.9) / ref - 1.0) < 0.02)
    }

    @Test fun pauseFadesOutAndResumes() {
        val m = MusicMixer(); m.setTrack(track(Track.VAULT)); run(m, 3.0)
        m.paused = true
        val o = run(m, 1.0)
        assertTrue(m.silentAndPaused())
        assertEquals(0.0, mean(o, 0.5, 1.0), 1e-4)
        m.paused = false
        val p = run(m, 1.5)
        assertTrue("resumes: ${mean(p, 1.0, 1.5)}", mean(p, 1.0, 1.5) > 0.9 * 1000.0 / 32768 * 0.95)
    }

    @Test fun loopWrapsSampleAccurately() {
        val f = 16.0 / (loop.toDouble() / SR)                         // exactly 16 cycles per loop
        val mt = MixTrack(spec(Track.VAULT))
        val data = ShortArray(loop) { (sin(2 * PI * 16 * it / loop) * 8000).toInt().toShort() }
        mt.put(0, MixLayer(data, data, 0f))
        val m = MusicMixer(); m.setTrack(mt); run(m, 3.0)
        val o = run(m, 20.0)                                          // crosses the wrap twice
        var worst = 0f
        for (i in 1 until o.size) worst = max(worst, abs(o[i] - o[i - 1]))
        assertTrue("max sample step $worst for a smooth sine (expected < ${2 * PI * f / SR * 8000 / 32768 * 0.95 * 1.2})", worst < 2 * PI * f / SR * 8000 / 32768 * 0.95 * 1.2 + 1e-4)
    }

    @Test fun missingLayersAreSkippedSilentlyAndPickedUpLater() {
        val mt = MixTrack(spec(Track.BASE)); mt.put(0, MixLayer(ShortArray(loop) { 4000 }, ShortArray(loop) { 4000 }, 0.5f))
        val m = MusicMixer(); m.setTrack(mt); m.intensity = 1f
        val a = run(m, 6.0)
        assertEquals(4000 / 32768.0 * 0.95, mean(a, 4.0, 6.0), 0.003)
        mt.put(3, MixLayer(ShortArray(loop) { 4000 }, ShortArray(loop) { 4000 }, 0.5f))
        val b = run(m, 12.0)
        assertTrue("late layer fades in", mean(b, 10.0, 12.0) > 2 * 4000 / 32768.0 * 0.95 * 0.9)
    }
}

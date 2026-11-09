package com.tjshea.neonreaper.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Building blocks of the sound design: every function returns a mono FloatArray (peak-normalised to ~1.0 unless noted) so the
 * recipes can mix them with plain gains. All loops are allocation-free; slow modulations are evaluated at control rate.
 */

internal fun FloatArray.norm(peak: Double = 1.0): FloatArray {
    val p = peakOf(this)
    if (p > 1e-9f) scale(peak / p)
    return this
}

/** Piecewise-linear envelope: pairs (seconds, value); holds the last value. */
internal class Env(private vararg val pts: Double) {
    fun array(n: Int): FloatArray {
        val out = FloatArray(n)
        var seg = 0
        val last = pts.size / 2 - 1
        for (i in 0 until n) {
            val t = i / SRD
            while (seg < last && t >= pts[seg * 2 + 2]) seg++
            out[i] = if (seg >= last) pts[last * 2 + 1].toFloat() else {
                val t0 = pts[seg * 2]; val t1 = pts[seg * 2 + 2]; val v0 = pts[seg * 2 + 1]; val v1 = pts[seg * 2 + 3]
                (v0 + (v1 - v0) * ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)).toFloat()
            }
        }
        return out
    }
}

internal fun FloatArray.times(e: FloatArray): FloatArray { for (i in indices) this[i] *= if (i < e.size) e[i] else e[e.size - 1]; return this }

/** Vowel = first formant frequencies (Hz). */
internal class Vowel(val f1: Double, val f2: Double, val f3: Double, val f4: Double = 3400.0) {
    fun mix(o: Vowel, t: Double) = Vowel(lerp(f1, o.f1, t), lerp(f2, o.f2, t), lerp(f3, o.f3, t), lerp(f4, o.f4, t))
}

internal object Vowels {
    val A = Vowel(800.0, 1150.0, 2900.0)
    val O = Vowel(450.0, 800.0, 2830.0)
    val U = Vowel(325.0, 700.0, 2530.0)
    val E = Vowel(550.0, 1770.0, 2490.0)
    val I = Vowel(280.0, 2250.0, 2890.0)
    val AW = Vowel(610.0, 900.0, 2540.0)         // "aw" / dark ah
    val BASS_A = Vowel(600.0, 1040.0, 2250.0)
    val BASS_O = Vowel(400.0, 750.0, 2400.0)
    val BASS_U = Vowel(350.0, 600.0, 2400.0)
}

/** Parallel band-pass formant bank on TPT state-variable filters. */
internal class FormantBank(private val q1: Double = 5.0, private val q2: Double = 7.0, private val q3: Double = 9.0) {
    private val a = Svf(); private val b = Svf(); private val c = Svf(); private val d = Svf()
    private var k1 = 1.0 / q1; private var k2 = 1.0 / q2; private var k3 = 1.0 / q3; private val k4 = 1.0 / 10.0

    fun set(v: Vowel, shift: Double = 1.0) {
        a.set(v.f1 * shift, q1); b.set(v.f2 * shift, q2); c.set(v.f3 * shift, q3); d.set(v.f4 * shift, 10.0)
    }

    fun process(x: Double): Double {
        a.process(x); b.process(x); c.process(x); d.process(x)
        return a.bp * k1 * 1.0 + b.bp * k2 * 0.8 + c.bp * k3 * 0.5 + d.bp * k4 * 0.25
    }
}

// =============================================================================================== percussion / impacts

/** Pitched sub drop: sine gliding f0 -> f1 (time constant [pitchTau]) under an exponential decay, optional saturation. */
internal fun thump(dur: Double, f0: Double, f1: Double, pitchTau: Double, ampTau: Double, drive: Double = 1.0, attack: Double = 0.0015): FloatArray {
    val n = frames(dur); val out = FloatArray(n)
    var ph = 0.0; var f = f0
    val kp = exp(-1.0 / (pitchTau * SR)); val ka = exp(-1.0 / (ampTau * SR)); val kr = exp(-1.0 / (attack * SR))
    var dec = 1.0; var rise = 1.0
    for (i in 0 until n) {
        ph += f / SRD; if (ph >= 1.0) ph -= 1.0
        f = f1 + (f - f1) * kp
        val e = (1.0 - rise) * dec
        rise *= kr; dec *= ka
        out[i] = (softClip(drive * Tables.cyc(ph)) * e).toFloat()
    }
    return out.norm()
}

/** Exponentially decaying noise burst, optionally low-passed ([lp] Hz) and/or high-passed ([hp] Hz). */
internal fun noiseBurst(dur: Double, seed: Long, tau: Double, lp: Double = 0.0, hp: Double = 0.0, attack: Double = 0.0004): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val la = if (lp > 0) onePoleCoef(lp) else 1.0; val ha = if (hp > 0) onePoleCoef(hp) else 0.0
    var ly = 0.0; var hy = 0.0
    val ka = exp(-1.0 / (tau * SR)); val kr = exp(-1.0 / (attack * SR))
    var dec = 1.0; var rise = 1.0
    for (i in 0 until n) {
        ly += la * (r.signed() - ly)
        hy += ha * (ly - hy)
        val s = if (hp > 0) ly - hy else ly
        out[i] = (s * (1.0 - rise) * dec).toFloat()
        rise *= kr; dec *= ka
    }
    return out.norm()
}

/** Band-passed noise burst at fixed centre [f] (resonant clicks, slaps, rattles). */
internal fun bandBurst(dur: Double, seed: Long, f: Double, q: Double, tau: Double, attack: Double = 0.0004): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val s = Svf(); s.set(f, q); val k = 1.0 / q
    val ka = exp(-1.0 / (tau * SR)); val kr = exp(-1.0 / (attack * SR))
    var dec = 1.0; var rise = 1.0
    for (i in 0 until n) {
        s.process(r.signed().toDouble())
        out[i] = (s.bp * k * (1.0 - rise) * dec).toFloat()
        rise *= kr; dec *= ka
    }
    return out.norm()
}

/** Noise through a band-pass whose centre sweeps exponentially f0 -> f1; amplitude from [amp] (normalised time 0..1). */
internal fun sweepNoise(dur: Double, seed: Long, f0: Double, f1: Double, q: Double, amp: (Double) -> Double): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val s = Svf(); val k = 1.0 / q
    val ratio = ln(f1 / f0)
    var a = 0.0
    for (i in 0 until n) {
        if (i and 7 == 0) { val p = i.toDouble() / n; s.set(f0 * exp(ratio * p), q); a = amp(p) }
        s.process(r.signed().toDouble())
        out[i] = (s.bp * k * a).toFloat()
    }
    return out.norm()
}

/** Quasi-periodic noise rumble (low-passed, amplitude-modulated by slow noise) — thunder tails, stone grinding. */
internal fun rumble(dur: Double, seed: Long, lp: Double, modHz: Double, modDepth: Double, amp: (Double) -> Double): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed); val r2 = Rng(seed + 99)
    val a1 = onePoleCoef(lp); val am = onePoleCoef(modHz)
    var y = 0.0; var y2 = 0.0; var m = 0.0
    for (i in 0 until n) {
        y += a1 * (r.signed() - y); y2 += a1 * (y - y2)
        m += am * (r2.signed() - m)
        val mod = 1.0 + modDepth * (m * 6.0).coerceIn(-1.0, 1.0)
        out[i] = (y2 * mod * amp(i.toDouble() / n)).toFloat()
    }
    return out.norm()
}

/** Membrane drum hit: pitched body with membrane overtones, stick noise. [vel] 0..1 brightens and strengthens. */
internal fun drum(f0: Double, dur: Double, seed: Long, vel: Double = 0.8, ring: Double = 1.0, snap: Double = 1.0): FloatArray {
    val n = frames(dur)
    val body = thump(dur, f0 * (1.5 + 0.5 * vel), f0, 0.03, 0.17 * ring, 1.5 + vel)
    val m2 = thump(min(dur, 0.35 * ring), f0 * 1.59 * 1.2, f0 * 1.59, 0.02, 0.07 * ring, 1.0)
    val m3 = thump(min(dur, 0.25 * ring), f0 * 2.14 * 1.15, f0 * 2.14, 0.015, 0.045 * ring, 1.0)
    val stick = bandBurst(0.05, seed, 2200.0 + 800.0 * vel, 1.2, 0.009)
    val slap = noiseBurst(0.08, seed + 1, 0.02, lp = 900.0)
    val out = FloatArray(n)
    for (i in 0 until n) {
        out[i] = body[i] * 1.0f + (if (i < m2.size) m2[i] * (0.32f) else 0f) + (if (i < m3.size) m3[i] * 0.16f else 0f)
    }
    for (i in stick.indices) if (i < n) out[i] += stick[i] * (0.38f * vel.toFloat() * snap.toFloat()) + slap[i] * 0.3f * snap.toFloat()
    return out.norm()
}

// ================================================================================================= bells and resonators

internal class BellSet(val ratios: DoubleArray, val amps: DoubleArray, val taus: DoubleArray)

internal object Bells {
    /** Dark tolling church bell: hum, prime, minor-third tierce, quint, nominal and an inharmonic upper series. */
    val CHURCH = BellSet(
        doubleArrayOf(0.5, 1.0, 1.19, 1.5, 2.0, 2.51, 2.97, 4.1, 5.4),
        doubleArrayOf(1.0, 0.9, 0.5, 0.38, 0.42, 0.22, 0.18, 0.1, 0.06),
        doubleArrayOf(9.0, 7.0, 5.0, 3.5, 2.6, 1.7, 1.1, 0.7, 0.45))
    /** Glass-harmonica / ghost bell: near-harmonic with slightly sharp upper partials, long ring. */
    val GLASS = BellSet(
        doubleArrayOf(1.0, 2.0, 2.76, 4.07, 5.4, 6.8),
        doubleArrayOf(1.0, 0.55, 0.32, 0.2, 0.1, 0.06),
        doubleArrayOf(3.5, 2.6, 1.8, 1.2, 0.8, 0.5))
    /** Old coin / small metal: bright but short, inharmonic. */
    val COIN = BellSet(
        doubleArrayOf(1.0, 2.32, 3.87, 5.43, 7.1),
        doubleArrayOf(1.0, 0.7, 0.45, 0.25, 0.12),
        doubleArrayOf(0.09, 0.055, 0.035, 0.02, 0.012))
    /** Struck low gong / anvil: dense inharmonic cluster. */
    val GONG = BellSet(
        doubleArrayOf(1.0, 1.47, 2.09, 2.56, 3.14, 3.82, 4.5, 5.31, 6.2, 7.4),
        doubleArrayOf(1.0, 0.8, 0.7, 0.55, 0.5, 0.4, 0.3, 0.24, 0.16, 0.1),
        doubleArrayOf(6.0, 5.0, 4.2, 3.6, 3.0, 2.4, 1.9, 1.5, 1.1, 0.8))
    /** Mallet on wood / bone, pitched. */
    val BLOCK = BellSet(
        doubleArrayOf(1.0, 2.7, 5.2),
        doubleArrayOf(1.0, 0.4, 0.15),
        doubleArrayOf(0.09, 0.04, 0.02))
    /** Dark mallet: harmonic partials, so the note stays musical when the player pitches it up a scale step. */
    val MALLET = BellSet(
        doubleArrayOf(1.0, 2.0, 3.0, 4.0),
        doubleArrayOf(1.0, 0.5, 0.22, 0.12),
        doubleArrayOf(0.1, 0.065, 0.04, 0.025))
}

/**
 * Struck resonator bank: each partial is a decaying sine produced by a 2-pole recurrence (3 flops per sample).
 * [bright] < 1 darkens (upper partials fade), [decay] scales every ring time, [beat] adds a detuned twin for slow beating.
 */
internal fun bell(f0: Double, dur: Double, set: BellSet, bright: Double = 1.0, decay: Double = 1.0, beat: Double = 0.0, soft: Double = 0.0): FloatArray {
    val n = frames(dur); val out = FloatArray(n)
    val cap = set.ratios.size * 2
    val c = DoubleArray(cap); val r2 = DoubleArray(cap); val y1 = DoubleArray(cap); val y2 = DoubleArray(cap)
    var cnt = 0
    fun add(f: Double, a: Double, tau: Double) {
        val w = TWO_PI * f / SRD
        val r = exp(-1.0 / (tau * SR))
        c[cnt] = 2.0 * r * cos(w); r2[cnt] = r * r; y1[cnt] = a * r * sin(w); y2[cnt] = 0.0
        cnt++
    }
    for (p in set.ratios.indices) {
        val f = f0 * set.ratios[p]
        if (f > 0.46 * SRD) continue
        val a = set.amps[p] * bright.pow(ln2(set.ratios[p]).coerceAtLeast(0.0))
        if (a < 1e-3) continue
        val tau = set.taus[p] * decay
        add(f, a, tau)
        if (beat != 0.0 && p < 5) add(f * (1.0 + beat * (1.0 + 0.3 * p)), a * 0.7, tau * 0.9)
    }
    resonateBank(out, c, r2, y1, y2, cnt)
    if (soft > 0.0) { val kr = exp(-1.0 / (soft * SR)); var rise = 1.0; for (i in 0 until n) { out[i] *= (1.0 - rise).toFloat(); rise *= kr } }
    out.fadeOut(min(0.03, dur * 0.2))
    return out.norm()
}

/** Adds a decaying sine of frequency [f], amplitude [a], 1/e time [tau] into [out] starting at sample [start] (single chain, stops when inaudible). */
internal fun resonate(out: FloatArray, f: Double, a: Double, tau: Double, start: Int) {
    val w = TWO_PI * f / SRD
    val r = exp(-1.0 / (tau * SR))
    val c = 2.0 * r * cos(w); val r2 = r * r
    var y2 = 0.0; var y1 = a * r * sin(w)
    val n = out.size
    if (start + 1 < n) out[start + 1] += y1.toFloat()
    var i = start + 2
    while (i < n) {
        val y = c * y1 - r2 * y2
        out[i] += y.toFloat()
        y2 = y1; y1 = y
        if (abs(y1) < 1e-6 && abs(y2) < 1e-6) break
        i++
    }
}

/**
 * Runs [count] two-pole decaying-sine resonators (state y1 = value at n=1, y2 = value at n=0) over [out], adding the result.
 * Four chains are interleaved per pass so the dependent recurrences overlap in the CPU pipeline.
 */
internal fun resonateBank(out: FloatArray, c: DoubleArray, r2: DoubleArray, y1: DoubleArray, y2: DoubleArray, count: Int) {
    val n = out.size
    if (n > 1) for (p in 0 until count) out[1] += y1[p].toFloat()
    var p = 0
    while (p + 4 <= count) {
        val ca = c[p]; val cb = c[p + 1]; val cc = c[p + 2]; val cd = c[p + 3]
        val ra = r2[p]; val rb = r2[p + 1]; val rc = r2[p + 2]; val rd = r2[p + 3]
        var a1 = y1[p]; var b1 = y1[p + 1]; var c1 = y1[p + 2]; var d1 = y1[p + 3]
        var a2 = y2[p]; var b2 = y2[p + 1]; var c2 = y2[p + 2]; var d2 = y2[p + 3]
        for (i in 2 until n) {
            val a = ca * a1 - ra * a2; a2 = a1; a1 = a
            val b = cb * b1 - rb * b2; b2 = b1; b1 = b
            val cx = cc * c1 - rc * c2; c2 = c1; c1 = cx
            val d = cd * d1 - rd * d2; d2 = d1; d1 = d
            out[i] += (a + b + cx + d).toFloat()
        }
        p += 4
    }
    while (p < count) {
        val cc = c[p]; val rr = r2[p]
        var a1 = y1[p]; var a2 = y2[p]
        for (i in 2 until n) { val a = cc * a1 - rr * a2; a2 = a1; a1 = a; out[i] += a.toFloat() }
        p++
    }
}

/** Slow "bowed" additive tone: inharmonic partials with a shared swell, flutter and detuned beating pairs (glass harmonica / bowed metal). */
internal fun bowed(f0: Double, dur: Double, seed: Long, attack: Double, release: Double, ratios: DoubleArray = doubleArrayOf(1.0, 2.0, 3.0, 4.2, 5.9), amps: DoubleArray = doubleArrayOf(1.0, 0.5, 0.3, 0.16, 0.08), flutter: Double = 0.12, beat: Double = 0.0035): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val np = ratios.size
    val ph = DoubleArray(np * 2) { r.unit().toDouble() }
    val inc = DoubleArray(np * 2) { val p = it / 2; f0 * ratios[p] * (1.0 + (if (it % 2 == 1) beat * (1 + 0.4 * p) else 0.0)) / SRD }
    val amp = DoubleArray(np * 2) { amps[it / 2] * (if (it % 2 == 1) 0.7 else 1.0) }
    val fl = DoubleArray(np * 2) { 0.17 + 0.23 * r.unit() }       // flutter rates (Hz)
    val fph = DoubleArray(np * 2) { r.unit().toDouble() }
    for (p in 0 until np * 2) if (inc[p] * SRD > 0.46 * SRD) amp[p] = 0.0
    val atk = max(1, frames(attack)); val rel = max(1, frames(release))
    for (i in 0 until n) {
        var s = 0.0
        for (p in 0 until np * 2) {
            if (amp[p] == 0.0) continue
            ph[p] += inc[p]; if (ph[p] >= 1.0) ph[p] -= 1.0
            fph[p] += fl[p] / SRD; if (fph[p] >= 1.0) fph[p] -= 1.0
            s += Tables.cyc(ph[p]) * amp[p] * (1.0 - flutter + flutter * Tables.cyc(fph[p]))
        }
        val e = min(1.0, i.toDouble() / atk).let { smooth(it) } * smooth(min(1.0, (n - 1 - i).toDouble() / rel))
        out[i] = (s * e).toFloat()
    }
    return out.norm()
}

// ============================================================================================ pads, choirs, brass, strings

/**
 * Detuned-saw pad through two cascaded resonant low-passes. Cutoff moves between [cutLo] and [cutHi] following the swell envelope
 * plus a slow LFO of [lfoHz]. [notes] are MIDI (fractional ok). Frequencies can be snapped by [snap] Hz (use 1/loopSeconds
 * for perfectly periodic loops).
 */
internal fun sawPad(notes: DoubleArray, dur: Double, seed: Long, attack: Double, release: Double, cutLo: Double, cutHi: Double,
                    q: Double = 0.9, lfoHz: Double = 0.0, lfoDepth: Double = 0.0, unison: Int = 3, detune: Double = 0.006, snap: Double = 0.0, t0: Double = 0.0): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val voices = notes.size * unison
    val osc = Array(voices) { SawOsc(0.0) }
    val inc = DoubleArray(voices); val tabs = Array(voices) { Tables.saw[0] }
    for (k in notes.indices) for (u in 0 until unison) {
        val v = k * unison + u
        var f = hz(notes[k]) * (1.0 + detune * (if (unison == 1) 0.0 else (u - (unison - 1) / 2.0) / ((unison - 1) / 2.0).coerceAtLeast(1.0)))
        if (snap > 0.0) f = Math.round(f / snap) * snap
        inc[v] = f / SRD; tabs[v] = Tables.sawFor(f)
        // random but fixed per-voice phase: with snapped frequencies f*T is an integer, so the result stays loop-periodic
        val ph = f * t0 + r.unit()
        osc[v].phase = ph - kotlin.math.floor(ph)
    }
    val f1 = Svf(); val f2 = Svf()
    val atk = max(1, frames(attack)); val rel = max(1, frames(release))
    var env = 0.0; var cut = cutLo
    val gain = 1.0 / sqrt(voices.toDouble())
    for (i in 0 until n) {
        if (i and 31 == 0) {
            val e = smooth(min(1.0, i.toDouble() / atk)) * smooth(min(1.0, (n - 1 - i).toDouble() / rel))
            env = e
            val lfo = if (lfoHz > 0) Tables.cyc(lfoHz * (i / SRD + t0)).toDouble() * lfoDepth else 0.0
            cut = (cutLo + (cutHi - cutLo) * e) * (1.0 + lfo)
            f1.set(cut, q); f2.set(cut, 0.7071)
        }
        var s = 0.0
        for (v in 0 until voices) s += osc[v].tick(inc[v], tabs[v])
        f1.process(s * gain); f2.process(f1.lp)
        out[i] = (f2.lp * env).toFloat()
    }
    return out.norm()
}

/**
 * Ghost choir chord: per-note band-limited saw unisons (shared vibrato) summed, then ONE formant bank morphing [v0] -> [v1]
 * (the filter is linear and identical for every voice, so summing first is exact), plus breath. Oscillators run voice by
 * voice with block-rate vibrato, which keeps the inner loop tight.
 */
internal fun choirChord(notes: DoubleArray, dur: Double, seed: Long, attack: Double, release: Double, v0: Vowel, v1: Vowel,
                        unison: Int = if (notes.size > 4) 2 else 3, detune: Double = 0.009, vibHz: Double = 5.2, vibDepth: Double = 0.004, breath: Double = 0.06,
                        shift: Double = 1.0, bright: Double = 1.0): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val src = FloatArray(n)
    val voices = notes.size * unison
    for (k in notes.indices) {
        val f0 = hz(notes[k])
        val vp = r.unit().toDouble(); val vr = vibHz * (0.9 + 0.2 * r.unit())
        for (u in 0 until unison) {
            val f = f0 * (1.0 + detune * (if (unison == 1) 0.0 else (u - (unison - 1) / 2.0) / ((unison - 1) / 2.0)))
            val tab = Tables.sawFor(f * 1.03)
            val osc = SawOsc(r.unit().toDouble())
            var i = 0
            while (i < n) {
                val vib = 1.0 + vibDepth * smooth(i / (0.8 * SRD)) * Tables.cyc(vp + vr * i / SRD)
                val inc = SawOsc.incOf(f * vib / SRD)
                val end = min(n, i + 16)
                for (j in i until end) src[j] += osc.tickI(inc, tab)
                i = end
            }
        }
    }
    val bank = FormantBank(4.5 / bright.coerceAtLeast(0.5), 6.0, 8.0)
    val nz = Rng(seed + 7)
    val atk = max(1, frames(attack)); val rel = max(1, frames(release))
    val gain = 1.0 / sqrt(voices.toDouble())
    var env = 0.0
    for (i in 0 until n) {
        if (i and 31 == 0) {
            bank.set(v0.mix(v1, smooth(i.toDouble() / n)), shift)
            env = smooth(min(1.0, i.toDouble() / atk)) * smooth(min(1.0, (n - 1 - i).toDouble() / rel))
        }
        out[i] = (bank.process(src[i] * gain + breath * nz.signed()) * env).toFloat()
    }
    return out.norm()
}

/** Low brass / horn note: detuned saws, cutoff and level swell, mild drive. [bite] opens the filter more on the attack. */
internal fun brass(f: Double, dur: Double, seed: Long, attack: Double = 0.12, release: Double = 0.2, cutLo: Double = 350.0, cutHi: Double = 2400.0,
                   unison: Int = 3, detune: Double = 0.004, drive: Double = 1.6, vibHz: Double = 0.0, vibDepth: Double = 0.0): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val osc = Array(unison) { SawOsc(r.unit().toDouble()) }
    val tab = Tables.sawFor(f * 1.02)
    val flt = Svf(); val flt2 = Svf()
    val atk = max(1, frames(attack)); val rel = max(1, frames(release))
    var cut = cutLo; var e = 0.0
    for (i in 0 until n) {
        if (i and 15 == 0) {
            val a = smooth(min(1.0, i.toDouble() / atk))
            e = a * smooth(min(1.0, (n - 1 - i).toDouble() / rel))
            cut = cutLo + (cutHi - cutLo) * a * (0.55 + 0.45 * exp(-i / (0.4 * SRD)))
            flt.set(cut, 1.1); flt2.set(cut * 1.4, 0.7071)
        }
        val vib = if (vibHz > 0) 1.0 + vibDepth * Tables.cyc(vibHz * i / SRD) else 1.0
        var s = 0.0
        for (u in 0 until unison) s += osc[u].tick(f * vib * (1.0 + detune * (u - (unison - 1) / 2.0)) / SRD, tab)
        flt.process(s / unison); flt2.process(flt.lp)
        out[i] = (softClip(drive * flt2.lp) * e).toFloat()
    }
    return out.norm()
}

/**
 * Cinematic "braam": stacked detuned saws (root, fifth, octave) with a fast downward pitch fall, a closing low-pass,
 * saturation and a sub sine. The mother of all hits. Voice-major loops with block-rate pitch for speed.
 */
internal fun braam(root: Double, dur: Double, seed: Long, fall: Double = 5.0, fallTau: Double = 0.09, ampTau: Double = 1.1, cutHi: Double = 5200.0, cutLo: Double = 700.0,
                   drive: Double = 2.4, fifth: Double = 0.7, octave: Double = 0.5, sub: Double = 0.8): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val ratios = doubleArrayOf(1.0, 1.5, 2.0)
    val gains = doubleArrayOf(1.0, fifth, octave)
    val detunes = doubleArrayOf(-0.010, -0.003, 0.004, 0.011)
    val blocks = (n + 15) / 16
    // block-rate fundamental (Hz): root * 2^(fall * exp(-t/fallTau) / 12)
    val fb = DoubleArray(blocks) { b -> root * exp(ln(2.0) / 12.0 * fall * exp(-(b * 16.0) / (fallTau * SR))) }
    val src = FloatArray(n)
    val tab = Tables.sawFor(root * 2.2)
    for (q in ratios.indices) for (d in detunes.indices) {
        if (gains[q] <= 0.0) continue
        val osc = SawOsc(r.unit().toDouble())
        val g = gains[q].toFloat()
        val mult = ratios[q] * (1.0 + detunes[d])
        for (b in 0 until blocks) {
            val inc = SawOsc.incOf(fb[b] * mult / SRD)
            val end = min(n, b * 16 + 16)
            for (j in b * 16 until end) src[j] += osc.tickI(inc, tab) * g
        }
    }
    val flt = Svf(); val flt2 = Svf()
    val ka = exp(-1.0 / (ampTau * SR))
    var dec = 1.0
    var subPh = 0.0
    val atk = frames(0.012)
    var e = 0.0
    for (i in 0 until n) {
        if (i and 15 == 0) {
            val cut = cutLo + (cutHi - cutLo) * exp(-i / (0.35 * SRD))
            flt.set(cut, 1.2); flt2.set(cut * 1.3, 0.7071)
            e = min(1.0, i.toDouble() / atk).let { it * it } * dec
        }
        flt.process(src[i] * 0.14); flt2.process(flt.lp)
        subPh += fb[i shr 4] * 0.5 / SRD; if (subPh >= 1.0) subPh -= 1.0
        out[i] = ((softClip(drive * flt2.lp) + sub * Tables.cyc(subPh) * 0.9) * e).toFloat()
        dec *= ka
    }
    return out.norm()
}

/** Tremolo string section: three-saw unison through a low-pass with amplitude tremolo; for ritual ostinati. */
internal fun strings(f: Double, dur: Double, seed: Long, tremHz: Double = 11.0, tremDepth: Double = 0.45, attack: Double = 0.03, release: Double = 0.08,
                     cut: Double = 2600.0, bite: Double = 1.0): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val osc = Array(4) { SawOsc(r.unit().toDouble()) }
    val tab = Tables.sawFor(f * 1.02)
    val flt = Svf(); flt.set(cut, 0.9); val hp = Svf(); hp.set(180.0, 0.7071)
    val atk = max(1, frames(attack)); val rel = max(1, frames(release))
    val trem = r.unit().toDouble()
    for (i in 0 until n) {
        var s = 0.0
        for (u in 0 until 4) s += osc[u].tick(f * (1.0 + 0.0055 * (u - 1.5)) / SRD, tab)
        flt.process(s * 0.25 * bite); hp.process(flt.lp)
        val e = min(1.0, i.toDouble() / atk) * min(1.0, (n - 1 - i).toDouble() / rel)
        val tr = 1.0 - tremDepth * (0.5 + 0.5 * Tables.cyc(tremHz * i / SRD + trem))
        out[i] = (hp.hp * e * tr).toFloat()
    }
    return out.norm()
}

/** Pipe organ: additive stops (8', 4', 2 2/3', 2', 1 3/5') with slow tremulant. */
internal fun organ(f: Double, dur: Double, attack: Double = 0.03, release: Double = 0.15, trem: Double = 0.06, tremHz: Double = 5.3, mix: DoubleArray = doubleArrayOf(1.0, 0.7, 0.4, 0.3, 0.18, 0.1)): FloatArray {
    val n = frames(dur); val out = FloatArray(n)
    val parts = doubleArrayOf(0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
    val ph = DoubleArray(parts.size)
    val atk = max(1, frames(attack)); val rel = max(1, frames(release))
    for (i in 0 until n) {
        var s = 0.0
        for (p in parts.indices) {
            val fp = f * parts[p]; if (fp > 0.45 * SRD) continue
            ph[p] += fp / SRD; if (ph[p] >= 1.0) ph[p] -= 1.0
            s += Tables.cyc(ph[p]) * mix[p]
        }
        val e = min(1.0, i.toDouble() / atk) * min(1.0, (n - 1 - i).toDouble() / rel)
        out[i] = (s * e * (1.0 - trem + trem * Tables.cyc(tremHz * i / SRD))).toFloat()
    }
    return out.norm()
}

// ======================================================================================================== voices

/**
 * Glottal-source formant voice. [f0], [vowel], [amp] are evaluated at control rate (seconds -> value).
 * [growl] adds amplitude roughness, [drive] saturates before the formants, [shift] scales the vocal tract (0.8 = a giant).
 */
internal fun formantVoice(dur: Double, seed: Long, f0: (Double) -> Double, vowel: (Double) -> Vowel, amp: (Double) -> Double,
                          jitter: Double = 0.02, breath: Double = 0.08, growl: Double = 0.0, growlHz: Double = 30.0,
                          vibDepth: Double = 0.0, vibHz: Double = 5.5, drive: Double = 1.0, shift: Double = 1.0, sub: Double = 0.0,
                          qScale: Double = 1.0): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val rng = Rng(seed)
    val osc = SawOsc(rng.unit().toDouble()); val bank = FormantBank(5.0 * qScale, 7.0 * qScale, 9.0 * qScale)
    val subOsc = SawOsc(0.0)
    var inc = 0.0; var jit = 1.0; var a = 0.0; var f = 100.0
    var tab = Tables.saw[0]
    val gph = rng.unit().toDouble()
    val nz = Rng(seed + 3)
    for (i in 0 until n) {
        if (i and 15 == 0) {
            val t = i / SRD
            f = f0(t) * (1.0 + vibDepth * Tables.cyc(vibHz * t))
            inc = f / SRD
            tab = Tables.sawFor(f * 1.5)
            a = amp(t)
            if (i and 31 == 0) bank.set(vowel(t), shift)
        }
        val before = osc.phase
        var s = osc.tick(inc * jit, tab).toDouble()
        if (osc.phase < before) jit = 1.0 + jitter * rng.signed()
        if (sub > 0.0) s += sub * subOsc.tick(inc * 0.5, tab)
        if (growl > 0.0) s *= 1.0 - growl * (0.5 + 0.5 * Tables.cyc(growlHz * i / SRD + gph))
        val src = softClip(drive * s) + breath * nz.signed()
        out[i] = (bank.process(src) * a).toFloat()
    }
    return out.norm()
}

/** Whisper: noise through a breathy formant bank; [amp] is the syllable envelope. */
internal fun whisper(dur: Double, seed: Long, vowel: (Double) -> Vowel, amp: (Double) -> Double, hp: Double = 500.0, shift: Double = 1.0, q: Double = 0.7): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val bank = FormantBank(3.0 * q + 0.5, 4.0 * q + 0.5, 5.0 * q + 0.5)
    val h = Svf(); h.set(hp, 0.7071)
    var a = 0.0
    for (i in 0 until n) {
        if (i and 31 == 0) { val t = i / SRD; bank.set(vowel(t), shift); a = amp(t) }
        h.process(r.signed().toDouble())
        out[i] = (bank.process(h.hp) * a).toFloat()
    }
    return out.norm()
}

// ========================================================================================== risers, coins, textures

/**
 * Rising tension: band-passed noise sweeping up with growing level, plus an optional rising saw tone with a closing tremolo.
 */
internal fun riser(dur: Double, seed: Long, f0: Double, f1: Double, tone0: Double = 0.0, tone1: Double = 0.0, toneAmt: Double = 0.0, q: Double = 1.2, curve: Double = 2.0): FloatArray {
    val n = frames(dur)
    val nz = sweepNoise(dur, seed, f0, f1, q) { p -> p.pow(curve) }
    if (toneAmt <= 0.0) return nz
    val out = FloatArray(n)
    val osc = SawOsc(0.0); val fl = Svf()
    val lnr = ln(tone1 / tone0)
    var tab = Tables.saw[0]
    var ph2 = 0.0
    for (i in 0 until n) {
        val p = i.toDouble() / n
        val f = tone0 * exp(lnr * p * p)
        if (i and 31 == 0) { tab = Tables.sawFor(f); fl.set(f * 3.0, 1.0) }
        fl.process(osc.tick(f / SRD, tab).toDouble())
        ph2 += (4.0 + 18.0 * p * p) / SRD
        val trem = 0.65 + 0.35 * Tables.cyc(ph2)
        out[i] = (fl.lp * p.pow(curve) * trem).toFloat()
    }
    out.norm()
    for (i in 0 until n) out[i] = nz[i] * 0.8f + out[i] * toneAmt.toFloat()
    return out.norm()
}

/** Dark "soul coin" ping: short inharmonic metal at [f] Hz. [dark] < 1 removes the top. */
internal fun coin(f: Double, seed: Long, dark: Double = 0.7, decay: Double = 1.0): FloatArray {
    val b = bell(f, 0.09 * 5.0 * decay, Bells.COIN, bright = dark, decay = decay)
    val click = noiseBurst(0.004, seed, 0.0012, hp = 1200.0)
    for (i in click.indices) b[i] += click[i] * 0.25f
    return b.norm()
}

/** Wood / stone creak: stick-slip pulse train with wandering pitch through two resonances. */
internal fun creak(dur: Double, seed: Long, f0: Double, f1: Double, res1: Double, res2: Double, rough: Double = 0.5): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    val a = Svf(); val b = Svf(); a.set(res1, 7.0); b.set(res2, 8.0)
    var ph = 0.0; var f = f0; var wander = 0.0
    val wa = onePoleCoef(6.0)
    var slip = 1.0; var slipT = 0
    for (i in 0 until n) {
        val p = i.toDouble() / n
        wander += wa * (r.signed() - wander)
        f = lerp(f0, f1, p) * (1.0 + rough * wander * 2.5)
        val before = ph
        ph += f / SRD
        var imp = 0.0
        if (ph >= 1.0) { ph -= 1.0; imp = 1.0 + 0.6 * r.signed() }
        if (before > ph) { slip = 0.35 + 0.65 * r.unit(); }
        val drive = imp * slip * 3.0 + r.signed() * 0.02
        a.process(drive); b.process(drive)
        val env = sin(PI * p).pow(0.6) * (0.55 + 0.45 * smooth(1.0 - abs(2 * p - 1.0)))
        out[i] = ((a.bp / 7.0 + b.bp / 8.0 * 0.7) * env).toFloat()
    }
    return out.norm()
}

/** Metal clinks: random impulses exciting a few high-Q resonators (chains, rattles). Density is clinks per second. */
internal fun clinks(dur: Double, seed: Long, density: Double, fLo: Double, fHi: Double, tau: Double, envShape: (Double) -> Double = { 1.0 }): FloatArray {
    val n = frames(dur); val out = FloatArray(n); val r = Rng(seed)
    var i = 0
    while (i < n) {
        val p = i.toDouble() / n
        val gap = -ln(1.0 - r.unit().toDouble() * 0.999) / (density * envShape(p).coerceAtLeast(0.05))
        i += max(1, (gap * SR).toInt())
        if (i >= n) break
        val f = fLo * (fHi / fLo).pow(r.unit().toDouble())
        val a = (0.3 + 0.7 * r.unit()) * envShape(p)
        resonate(out, f, a, tau * (0.5 + r.unit()), i)
        resonate(out, f * (1.5 + 0.7 * r.unit()), a * 0.5, tau * 0.6, i)
    }
    return out.norm()
}

/** Pass-by whoosh: band-passed noise sweeping f0 -> f1 with a bell-shaped (skewed) level. */
internal fun whoosh(dur: Double, seed: Long, f0: Double, f1: Double, q: Double = 0.9, peak: Double = 0.5, sharp: Double = 1.0): FloatArray =
    sweepNoise(dur, seed, f0, f1, q) { p -> (sin(PI * p.pow(ln(0.5) / ln(peak.coerceIn(0.05, 0.95))))).pow(sharp) }

/** Soft-clipped, pre-emphasised "grind" / distortion helper for growls etc. */
internal fun FloatArray.drive(amount: Double): FloatArray { for (i in indices) this[i] = softClip(this[i] * amount).toFloat(); return this }
internal fun FloatArray.lowpass(fc: Double): FloatArray { val a = onePoleCoef(fc); var y = 0.0; for (i in indices) { y += a * (this[i] - y); this[i] = y.toFloat() }; return this }
internal fun FloatArray.highpass(fc: Double): FloatArray { val a = onePoleCoef(fc); var y = 0.0; for (i in indices) { y += a * (this[i] - y); this[i] = (this[i] - y).toFloat() }; return this }

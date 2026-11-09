package com.tjshea.neonreaper.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Pure-Kotlin DSP core of the "DREAD" sound engine. No android.* imports on purpose: everything in this file (and in
 * Signal/Instruments/SfxRecipes/Scores/MusicMixer/AudioRender) runs under plain JUnit and is deterministic (seeded RNG).
 */

/** Sample rate of every rendered asset (22.05 kHz keeps the set small and the tops un-fatiguing). */
internal const val SR = 22050
internal const val SRD = 22050.0
internal const val TWO_PI = 2.0 * PI

internal fun hz(midi: Double): Double = 440.0 * 2.0.pow((midi - 69.0) / 12.0)
internal fun semi(n: Double): Double = 2.0.pow(n / 12.0)
internal fun dbGain(db: Double): Double = 10.0.pow(db / 20.0)
internal fun gainDb(g: Double): Double = 20.0 * log10(max(g, 1e-12))
internal fun frames(sec: Double): Int = (sec * SR + 0.5).toInt()
internal fun clamp01(x: Double): Double = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x
internal fun smooth(x: Double): Double { val t = clamp01(x); return t * t * (3.0 - 2.0 * t) }
internal fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

/** Cheap tanh-like soft clipper (rational approximation, |error| < 0.02), the workhorse of every "drive". */
internal fun softClip(x: Double): Double {
    if (x >= 3.0) return 1.0
    if (x <= -3.0) return -1.0
    val x2 = x * x
    return x * (27.0 + x2) / (27.0 + 9.0 * x2)
}

/** xorshift32 white-noise / randomness source: tiny, fast, deterministic. */
internal class Rng(seed: Long) {
    private var x: Int

    init {
        var z = seed * -0x61c8864680b583ebL + 0x2545F4914F6CDD1DL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        z = z xor (z ushr 31)
        val i = z.toInt()
        x = if (i == 0) 0x1badf00d else i
    }

    fun next(): Int { var v = x; v = v xor (v shl 13); v = v xor (v ushr 17); v = v xor (v shl 5); x = v; return v }
    /** White noise in [-1, 1). */
    fun signed(): Float = next() * 4.656613e-10f
    /** Uniform in [0, 1). */
    fun unit(): Float = (next() ushr 8) * 5.9604645e-8f
    fun range(a: Double, b: Double): Double = a + (b - a) * unit()
    fun chance(p: Double): Boolean = unit() < p
    /** Roughly gaussian (sum of four uniforms), unit variance-ish. */
    fun gauss(): Double = (unit() + unit() + unit() + unit() - 2.0) * 1.7320508
    fun pick(n: Int): Int = ((next() ushr 1) % n)
}

/** Shared lookup tables: a sine and band-limited saw tables (one per octave of harmonic count) so oscillators never alias. */
internal object Tables {
    const val SIN_N = 4096
    const val SAW_N = 2048
    @JvmField val sine = FloatArray(SIN_N + 2)
    /** saw[lg] holds a falling ramp with 2^lg harmonics (Lanczos-sigma smoothed, peak-normalised). */
    @JvmField val saw: Array<FloatArray>

    init {
        for (i in 0 until SIN_N + 2) sine[i] = sin(TWO_PI * i / SIN_N).toFloat()
        val base = DoubleArray(SAW_N) { sin(TWO_PI * it / SAW_N) }
        saw = Array(8) { lg ->
            val h = 1 shl lg
            val t = DoubleArray(SAW_N)
            for (k in 1..h) {
                val sg = if (h == 1) 1.0 else { val a = PI * k / (h + 1); sin(a) / a }
                val amp = sg / k
                var idx = 0
                for (i in 0 until SAW_N) { t[i] += amp * base[idx]; idx = (idx + k) and (SAW_N - 1) }
            }
            var m = 0.0
            for (v in t) m = max(m, abs(v))
            FloatArray(SAW_N + 2).also { f ->
                for (i in 0 until SAW_N) f[i] = (t[i] / m).toFloat()
                f[SAW_N] = f[0]; f[SAW_N + 1] = f[1]
            }
        }
    }

    /** Sine of a phase given in cycles (any value, fraction is used). */
    fun cyc(c: Double): Float {
        val p = (c - floor(c)) * SIN_N
        val i = p.toInt()
        val fr = (p - i).toFloat()
        return sine[i] + (sine[i + 1] - sine[i]) * fr
    }

    /** The saw table that is alias-free at fundamental [f0] (Hz). */
    fun sawFor(f0: Double): FloatArray {
        val k = (0.46 * SRD / max(f0, 1.0)).toInt().coerceIn(1, 255)
        return saw[31 - Integer.numberOfLeadingZeros(k)]
    }

    fun lookup(tab: FloatArray, c: Double): Float {
        val p = (c - floor(c)) * SAW_N
        val i = p.toInt()
        val fr = (p - i).toFloat()
        return tab[i] + (tab[i + 1] - tab[i]) * fr
    }
}

/**
 * Band-limited saw oscillator (falling ramp) with a wrap-free 32-bit phase accumulator. Call [tick] with the phase increment
 * (f0/SR) and a table from [Tables.sawFor]; for tight loops convert once per block with [incOf] and use [tickI].
 */
internal class SawOsc(startPhase: Double = 0.0) {
    private var ph: Int = ((startPhase - kotlin.math.floor(startPhase)) * 4294967296.0).toLong().toInt()
    var phase: Double
        get() = (ph.toLong() and 0xFFFFFFFFL) / 4294967296.0
        set(v) { ph = ((v - kotlin.math.floor(v)) * 4294967296.0).toLong().toInt() }

    fun tick(inc: Double, tab: FloatArray): Float = tickI(incOf(inc), tab)

    fun tickI(incI: Int, tab: FloatArray): Float {
        ph += incI
        val i = ph ushr 21
        val fr = (ph and 0x1FFFFF) * 4.7683716e-7f
        val a = tab[i]
        return a + (tab[i + 1] - a) * fr
    }

    /** Pulse = difference of two phase-shifted saws; [width] 0..1 (0.5 = square). */
    fun pulse(inc: Double, tab: FloatArray, width: Double): Float {
        ph += incOf(inc)
        val c = phase
        return (Tables.lookup(tab, c) - Tables.lookup(tab, c + width)) * 0.5f
    }

    companion object {
        /** Phase increment per sample (fraction of a cycle) as a 32-bit fixed-point value. */
        fun incOf(inc: Double): Int = (inc * 4294967296.0).toLong().toInt()
    }
}

/** RBJ biquad (transposed direct form II), double precision so low-frequency resonators stay clean. */
internal class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
    private var z1 = 0.0; private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset(): Biquad { z1 = 0.0; z2 = 0.0; return this }

    private fun set(nb0: Double, nb1: Double, nb2: Double, na0: Double, na1: Double, na2: Double): Biquad {
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
        return this
    }

    private fun w0(f: Double) = TWO_PI * min(f, 0.47 * SRD) / SRD

    fun lowpass(f: Double, q: Double = 0.7071): Biquad {
        val w = w0(f); val c = kotlin.math.cos(w); val al = sin(w) / (2 * q)
        return set((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al)
    }

    fun highpass(f: Double, q: Double = 0.7071): Biquad {
        val w = w0(f); val c = kotlin.math.cos(w); val al = sin(w) / (2 * q)
        return set((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
    }

    /** Band-pass with constant 0 dB peak gain (formant filter). */
    fun bandpass(f: Double, q: Double): Biquad {
        val w = w0(f); val c = kotlin.math.cos(w); val al = sin(w) / (2 * q)
        return set(al, 0.0, -al, 1 + al, -2 * c, 1 - al)
    }

    fun notch(f: Double, q: Double): Biquad {
        val w = w0(f); val c = kotlin.math.cos(w); val al = sin(w) / (2 * q)
        return set(1.0, -2 * c, 1.0, 1 + al, -2 * c, 1 - al)
    }

    fun peaking(f: Double, q: Double, db: Double): Biquad {
        val a = 10.0.pow(db / 40); val w = w0(f); val c = kotlin.math.cos(w); val al = sin(w) / (2 * q)
        return set(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
    }

    fun lowShelf(f: Double, db: Double): Biquad {
        val a = 10.0.pow(db / 40); val w = w0(f); val c = kotlin.math.cos(w); val s = sin(w)
        val al = s / 2 * sqrt(2.0); val sq = 2 * sqrt(a) * al
        return set(a * ((a + 1) - (a - 1) * c + sq), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sq),
            (a + 1) + (a - 1) * c + sq, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sq)
    }

    fun highShelf(f: Double, db: Double): Biquad {
        val a = 10.0.pow(db / 40); val w = w0(f); val c = kotlin.math.cos(w); val s = sin(w)
        val al = s / 2 * sqrt(2.0); val sq = 2 * sqrt(a) * al
        return set(a * ((a + 1) + (a - 1) * c + sq), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sq),
            (a + 1) - (a - 1) * c + sq, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sq)
    }
}

/** Zavalishin TPT state-variable filter: stable under fast cutoff modulation; set() every few samples when sweeping. */
internal class Svf {
    private var ic1 = 0.0; private var ic2 = 0.0
    private var k = 1.0; private var a1 = 0.0; private var a2 = 0.0; private var a3 = 0.0
    @JvmField var lp = 0.0
    @JvmField var bp = 0.0
    @JvmField var hp = 0.0

    fun set(fc: Double, q: Double) {
        val g = tan(PI * min(fc, 0.45 * SRD) / SRD)
        k = 1.0 / q
        a1 = 1.0 / (1.0 + g * (g + k)); a2 = g * a1; a3 = g * a2
    }

    fun process(x: Double) {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2 * v1 - ic1
        ic2 = 2 * v2 - ic2
        lp = v2; bp = v1; hp = x - k * v1 - v2
    }

    fun reset() { ic1 = 0.0; ic2 = 0.0; lp = 0.0; bp = 0.0; hp = 0.0 }
}

internal fun onePoleCoef(fc: Double): Double = 1.0 - exp(-TWO_PI * fc / SRD)

/** Reverb description. [rt60] seconds; [size] scales the delay lines; [damp] is the in-loop low-pass (Hz). */
internal class ReverbSpec(
    val rt60: Double,
    val size: Double = 1.0,
    val damp: Double = 3200.0,
    val pre: Double = 0.012,
    val hp: Double = 140.0,
    val mod: Double = 5.0,
    val diff: Double = 0.6,
    /** number of input allpass diffusers (2..4): fewer = cheaper and less smeared (small rooms) */
    val stages: Int = 4
)

/** Plain helpers on mono float buffers. */
internal fun FloatArray.scale(g: Double): FloatArray { val f = g.toFloat(); for (i in indices) this[i] *= f; return this }

internal fun FloatArray.fadeIn(sec: Double): FloatArray {
    val n = min(size, frames(sec))
    for (i in 0 until n) { val w = 0.5f - 0.5f * kotlin.math.cos(PI * i / n).toFloat(); this[i] *= w }
    return this
}

internal fun FloatArray.fadeOut(sec: Double): FloatArray {
    val n = min(size, frames(sec))
    for (i in 0 until n) { val w = 0.5f - 0.5f * kotlin.math.cos(PI * i / n).toFloat(); this[size - 1 - i] *= w }
    return this
}

internal fun FloatArray.reversed2(): FloatArray { val o = FloatArray(size); for (i in indices) o[i] = this[size - 1 - i]; return o }

/** Multiplies by a raised-cosine hump 0..1..0 over the whole buffer, shaped by [power] (1 = plain). */
internal fun FloatArray.hump(power: Double = 1.0): FloatArray {
    for (i in indices) { val w = sin(PI * (i + 0.5) / size); this[i] *= (if (power == 1.0) w else w.pow(power)).toFloat() }
    return this
}

internal fun mixDown(vararg parts: FloatArray): FloatArray {
    val out = FloatArray(parts.maxOf { it.size })
    for (p in parts) for (i in p.indices) out[i] += p[i]
    return out
}

internal fun FloatArray.padTo(n: Int): FloatArray = if (size >= n) this else copyOf(n)

internal fun peakOf(a: FloatArray): Float { var m = 0f; for (v in a) { val x = abs(v); if (x > m) m = x }; return m }
internal fun rmsOf(a: FloatArray, from: Int = 0, to: Int = a.size): Double {
    var s = 0.0; for (i in from until to) s += a[i].toDouble() * a[i]
    return sqrt(s / max(1, to - from))
}

internal fun ln2(x: Double) = ln(x) / ln(2.0)

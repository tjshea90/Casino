package com.tjshea.neonreaper.audio

import com.tjshea.neonreaper.audio.Audio.Track
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * The adaptive dark score. Every layer is rendered as a perfectly periodic loop (oscillator frequencies snapped to the loop,
 * notes wrapped around the end, reverb tails folded back onto the start), so layers stay sample aligned and the loops are
 * seamless. D minor / D Phrygian over a D pedal; tempo grids are exact in samples (75 / 90 / 108 BPM).
 *
 *   BASE   75 BPM, 20 bars = 64.0 s   L0 drone+wind+whispers+bell | L1 heartbeat+sub+metal | L2 choir+bowed metal+shimmer | L3 war drums+brass+hook
 *   SEANCE 90 BPM, 16 bars = 42.67 s  L0 drone+chant | L1 toms+hah+shaker | L2 tremolo strings+chant | L3 frenzy rolls+rising strings+brass
 *   VAULT  108 BPM, 16 bars = 35.56 s one epic mix: organ, choir melody, war drums, brass
 */
internal object Scores {
    val BASE = TrackSpec(Track.BASE, 75.0, 20, 4, floatArrayOf(0f, 0.15f, 0.40f, 0.70f), floatArrayOf(0f, 0.20f, 0.20f, 0.20f), listOf(
        LayerSpec(Track.BASE, 0, "drone", -28.0, true), LayerSpec(Track.BASE, 1, "pulse", -30.0, false, 0.55f),
        LayerSpec(Track.BASE, 2, "choir", -28.0, true), LayerSpec(Track.BASE, 3, "war", -27.0, false, 0.65f)))
    val SEANCE = TrackSpec(Track.SEANCE, 90.0, 16, 4, floatArrayOf(0f, 0f, 0.30f, 0.65f), floatArrayOf(0f, 0f, 0.25f, 0.27f), listOf(
        LayerSpec(Track.SEANCE, 0, "ritual", -28.0, true), LayerSpec(Track.SEANCE, 1, "toms", -27.0, false, 0.6f),
        LayerSpec(Track.SEANCE, 2, "strings", -27.5, true), LayerSpec(Track.SEANCE, 3, "frenzy", -26.5, true)))
    val VAULT = TrackSpec(Track.VAULT, 108.0, 16, 4, floatArrayOf(0f), floatArrayOf(0f), listOf(LayerSpec(Track.VAULT, 0, "mix", -22.0, true)))

    val tracks: Map<Track, TrackSpec> = mapOf(Track.BASE to BASE, Track.SEANCE to SEANCE, Track.VAULT to VAULT)

    fun renderLayer(track: Track, index: Int): Pcm = when (track) {
        Track.BASE -> when (index) { 0 -> baseL0(); 1 -> baseL1(); 2 -> baseL2(); else -> baseL3() }
        Track.SEANCE -> when (index) { 0 -> seanceL0(); 1 -> seanceL1(); 2 -> seanceL2(); else -> seanceL3() }
        Track.VAULT -> vault()
    }

    // ------------------------------------------------------------------------------------------------- loop helpers
    private const val WARM = 2.0

    /** Periodic detuned-saw pad: frequencies snapped to the loop and rendered with a warm-up that is discarded. */
    private fun loopPad(notes: DoubleArray, n: Int, seed: Long, cutLo: Double, cutHi: Double, q: Double, lfoCycles: Double, lfoDepth: Double,
                        unison: Int = 3, detune: Double = 0.005): FloatArray {
        val loopSec = n.toDouble() / SR; val w = frames(WARM)
        val full = sawPad(notes, loopSec + WARM, seed, 0.0, 0.0, cutLo, cutHi, q, lfoCycles / loopSec, lfoDepth, unison, detune, 1.0 / loopSec, -WARM)
        return full.copyOfRange(w, w + n).also { it.scale(0.2 / rmsOf(it)) }
    }

    private fun loopSine(f: Double, n: Int, phase: Double = 0.0): FloatArray {
        val loopSec = n.toDouble() / SR
        val fs = Math.round(f * loopSec) / loopSec
        val o = SineOsc(phase); val inc = SineOsc.incOf(fs)
        return FloatArray(n) { o.tickI(inc) * 0.28f }
    }

    /** Equal-power fold of the overshoot back onto the start: turns a noise texture of length n+x into a seamless loop of n. */
    private fun foldLoop(raw: FloatArray, n: Int): FloatArray {
        val x = raw.size - n
        val out = raw.copyOf(n)
        for (i in 0 until x) { val p = PI / 2 * (i + 0.5) / x; out[i] = raw[i] * sin(p).toFloat() + raw[n + i] * cos(p).toFloat() }
        return out
    }

    private fun windBed(n: Int, seed: Long, fLo: Double, fHi: Double, q: Double, cycles: Double, gustCycles: Double): FloatArray {
        val total = n + frames(2.0); val raw = FloatArray(total); val r = Rng(seed); val s = Svf()
        val loopSec = n.toDouble() / SR
        var gust = 1.0
        for (i in 0 until total) {
            if (i and 15 == 0) {
                s.set(fLo + (fHi - fLo) * (0.5 + 0.5 * Tables.cyc(cycles * i / SRD / loopSec)), q)
                gust = 0.55 + 0.45 * Tables.cyc(gustCycles * i / SRD / loopSec + 0.25)
            }
            s.process(r.signed().toDouble())
            raw[i] = (s.bp * gust).toFloat()
        }
        return foldLoop(raw, n).also { it.scale(0.2 / rmsOf(it)) }
    }

    private fun softLimit(st: Stereo) {
        for (a in arrayOf(st.l, st.r)) for (i in a.indices) {
            val v = a[i]; val m = abs(v)
            if (m > 0.7f) a[i] = Math.signum(v) * (0.7f + 0.25f * tanh((m - 0.7f) / 0.25f))
        }
    }

    /** Circular EQ: the filter is pre-rolled on the end of the loop so the state at the seam is the true steady state. */
    private fun eqLoop(st: Stereo, airDb: Double) {
        if (airDb == 0.0) return
        for (a in arrayOf(st.l, st.r)) {
            val hi = Biquad().highShelf(5000.0, airDb)
            val w = min(a.size, frames(0.5))
            for (i in a.size - w until a.size) hi.process(a[i].toDouble())
            for (i in a.indices) a[i] = hi.process(a[i].toDouble()).toFloat()
        }
    }

    private fun finish(bus: Bus, spec: LayerSpec, hall: ReverbSpec?, room: ReverbSpec? = null, airDb: Double = -3.0): Pcm {
        val st = bus.render(hall, room, (hall?.rt60 ?: 0.0) * 1.2, (room?.rt60 ?: 0.0) * 1.2)
        if (!spec.stereo) Master.monoize(st)
        Master.removeDc(st)
        eqLoop(st, airDb)
        Master.gain(st, dbGain(spec.rms) / Master.rms(st.l, st.r))
        softLimit(st)
        return Master.toPcm(st, spec.stereo)
    }

    private fun bus(ts: TrackSpec) = Bus(ts.loopFrames, true)
    private fun s(t: Double) = frames(t)

    // ------------------------------------------------------------------------------------------------- BASE
    private fun baseL0(): Pcm {
        val ts = BASE; val n = ts.loopFrames; val b = bus(ts)
        b.add(loopPad(doubleArrayOf(N.D2, N.A2), n, 11, 150.0, 380.0, 1.2, 2.0, 0.45), 0, 0.55, 0.0, 0.2)
        b.add(loopPad(doubleArrayOf(N.D3, N.A3), n, 12, 260.0, 900.0, 1.0, 3.0, 0.5, 2, 0.006), 0, 0.22, 0.0, 0.3)
        b.add(loopSine(hz(N.D1), n), 0, 0.5)
        b.add(windBed(n, 21, 260.0, 950.0, 1.1, 3.0, 5.0), 0, 0.45, 0.0, 0.35)
        val wt = doubleArrayOf(5.5, 14.0, 26.5, 38.0, 47.0, 58.5)
        for (k in wt.indices) {
            val len = 2.4 + 0.35 * (k % 3)
            val w = whisper(len, 40L + k, { t -> if (k % 2 == 0) Vowels.O.mix(Vowels.A, clamp01(t / len)) else Vowels.U.mix(Vowels.E, clamp01(t / len)) },
                { t -> sin(PI * t / len).pow(2.0) * (0.65 + 0.35 * sin(2 * PI * (2.6 + 0.3 * k) * t)) }, 450.0, 0.88).lowpass(4200.0)
            b.add(w, s(wt[k]), 0.12, if (k % 2 == 0) -0.55 else 0.55, 0.9)
        }
        val tolls = arrayOf(doubleArrayOf(0.0, N.D3, 0.8), doubleArrayOf(19.2, N.D3, 0.55), doubleArrayOf(38.4, N.A2, 0.7), doubleArrayOf(51.2, N.D3, 0.5))
        for (t in tolls) b.add(bell(hz(t[1]), 9.0, Bells.CHURCH, bright = 0.55, decay = 0.9, beat = 0.0015), s(t[0]), 0.14 * t[2], 0.0, 0.9 * t[2])
        return finish(b, ts.layers[0], Rv.HALL)
    }

    private fun baseL1(): Pcm {
        val ts = BASE; val n = ts.loopFrames; val b = bus(ts); val bar = 3.2; val beat = 0.8
        val lub = thump(0.45, 64.0, 38.0, 0.035, 0.11, 1.7); val dub = thump(0.35, 56.0, 36.0, 0.03, 0.09, 1.6)
        val lubH = thump(0.2, 165.0, 92.0, 0.02, 0.05, 2.0)
        val barGain = doubleArrayOf(1.0, 1.0, 0.9, 0.35, 1.0, 1.0, 1.0, 0.45, 0.0, 1.0, 1.0, 1.0, 0.5, 1.0, 1.0, 1.0, 0.4, 1.0, 0.9, 0.6)
        for (i in 0 until 20) for (k in 0 until 4) {
            val g = barGain[i] * (0.8 + 0.2 * sin(2 * PI * (i * 4 + k) / 80.0))
            if (g <= 0.0) continue
            val t = i * bar + k * beat
            b.add(lub, s(t), 0.7 * g, 0.0, 0.12); b.add(lubH, s(t), 0.12 * g)
            b.add(dub, s(t + 0.30), 0.5 * g, 0.0, 0.1); b.add(lubH, s(t + 0.30), 0.08 * g)
        }
        for (i in 0 until 20 step 2) {
            val f1 = if (i == 6 || i == 18) 38.9 else 36.7
            b.add(thump(2.4, 58.0, f1, 0.22, 0.85, 1.25), s(i * bar), 0.55, 0.0, 0.15)
        }
        for ((k, t) in doubleArrayOf(9.6, 28.8, 41.6, 57.6).withIndex())
            b.add(sweepNoise(3.6, 60L + k, 280.0 + 40 * k, 720.0 - 30 * k, 9.0) { p -> sin(PI * p).pow(1.6) }, s(t), 0.5, if (k % 2 == 0) -0.4 else 0.4, 0.5)
        b.add(clinks(3.2, 66, 3.0, 500.0, 2600.0, 0.08) { p -> sin(PI * p) }, s(20.5), 0.4, 0.3, 0.5)
        for (t in doubleArrayOf(12.8, 44.8)) b.add(bell(hz(N.D2), 4.0, Bells.GONG, bright = 0.35, decay = 0.7), s(t), 0.22, 0.0, 0.8)
        return finish(b, ts.layers[1], Rv.CRYPT)
    }

    private val baseChords = arrayOf(
        doubleArrayOf(N.D3, N.A3, N.D4, N.F4), doubleArrayOf(N.Bb3, N.D4, N.F4, N.A4), doubleArrayOf(N.G3, N.Bb3, N.D4, N.G4), doubleArrayOf(N.Eb3, N.G3, N.Bb3, N.Eb4),
        doubleArrayOf(N.D3, N.A3, N.D4, N.F4), doubleArrayOf(N.A3, N.C4, N.E4, N.A4), doubleArrayOf(N.Bb3, N.D4, N.F4, N.Bb4), doubleArrayOf(N.C4, N.E4, N.G4, N.C5),
        doubleArrayOf(N.A3, N.Cs4, N.E4, N.A4), doubleArrayOf(N.Eb3, N.G3, N.Bb3, N.Eb4))

    private fun baseL2(): Pcm {
        val ts = BASE; val n = ts.loopFrames; val b = bus(ts); val bar = 3.2
        val on = booleanArrayOf(true, true, true, true, true, false, true, true, true, true)
        for (c in 0 until 10) {
            if (!on[c]) continue
            val t0 = c * 2 * bar - 0.6
            val ch = baseChords[c]
            b.add(choirChord(ch, 7.2, 100L + c, 2.6, 2.6, if (c % 2 == 0) Vowels.O else Vowels.U, Vowels.A, unison = 2), s(t0), 0.5, if (c % 2 == 0) -0.25 else 0.25, 0.7)
            b.add(choirChord(doubleArrayOf(ch[0] - 12.0, ch[1] - 12.0), 7.2, 120L + c, 2.8, 2.8, Vowels.BASS_O, Vowels.BASS_A, unison = 2, shift = 0.9), s(t0), 0.32, 0.0, 0.6)
        }
        for ((k, t) in doubleArrayOf(3 * bar, 11 * bar, 17 * bar).withIndex())
            b.add(bowed(hz(if (k == 1) N.Eb4 else N.D4), 9.0, 130L + k, 3.4, 3.4, flutter = 0.3), s(t), 0.34, if (k == 1) 0.3 else -0.3, 0.8)
        for ((k, t) in doubleArrayOf(5 * bar, 11 * bar, 17 * bar).withIndex())
            b.add(reverseSwell(bell(hz(N.A5), 0.8, Bells.GLASS, bright = 0.5, decay = 0.5), Rv.HALL, 2.6), s(t - 2.9), 0.5, (k - 1) * 0.5, 0.0)
        for ((k, t) in doubleArrayOf(6.4, 24.0, 33.6, 44.8, 57.6).withIndex())
            b.add(bell(hz(if (k % 2 == 0) N.A5 else N.D6), 2.4, Bells.GLASS, bright = 0.45, decay = 0.6, beat = 0.002), s(t), 0.12, if (k % 2 == 0) -0.6 else 0.6, 0.9)
        return finish(b, ts.layers[2], Rv.HALL)
    }

    private fun baseL3(): Pcm {
        val ts = BASE; val n = ts.loopFrames; val b = bus(ts); val bar = 3.2; val sx = 0.2
        val dl = drum(55.0, 1.0, 301, 0.95, 1.4); val dm = drum(73.4, 0.7, 302, 0.85, 1.1); val dh = drum(110.0, 0.5, 303, 0.75, 1.0, 1.3)
        val kit = arrayOf(dl, dm, dh)
        // two-bar cell: (slot, drum, velocity)
        val cellA = arrayOf(intArrayOf(0, 0, 10), intArrayOf(3, 0, 4), intArrayOf(6, 1, 7), intArrayOf(8, 0, 9), intArrayOf(11, 0, 4), intArrayOf(14, 2, 6), intArrayOf(15, 2, 4))
        val cellB = arrayOf(intArrayOf(0, 0, 10), intArrayOf(3, 0, 4), intArrayOf(6, 1, 7), intArrayOf(8, 0, 9), intArrayOf(10, 1, 6), intArrayOf(12, 0, 10), intArrayOf(14, 1, 6), intArrayOf(15, 2, 5))
        for (i in 0 until 20) {
            val fill = i % 4 == 3
            for (h in (if (i % 2 == 0) cellA else cellB)) {
                if (fill && h[0] >= 8) continue
                b.add(kit[h[1]], s(i * bar + h[0] * sx), 0.1 * h[2], (h[1] - 1) * 0.2, 0.3)
            }
            if (fill) for (k in 0 until 8) b.add(kit[intArrayOf(0, 1, 2, 1, 0, 1, 2, 2)[k]], s(i * bar + (8 + k) * sx), 0.35 + 0.09 * k, (k % 2) * 0.3 - 0.15, 0.35)
        }
        // low brass: one long swell per chord, root follows the harmony
        val roots = doubleArrayOf(38.0, 34.0, 31.0, 39.0, 38.0, 33.0, 34.0, 36.0, 33.0, 39.0)
        for (c in 0 until 10) b.add(brass(hz(roots[c]), 7.0, 150L + c, 1.8, 1.8, 260.0, 1700.0, 3, 0.004, 1.5), s(c * 2 * bar - 0.4), 0.32, 0.0, 0.25)
        // dark rhythmic hook: staccato brass on D (Phrygian colour), two-bar riff
        val notes = intArrayOf(38, 39, 41, 43, 33, 45)           // D2 Eb2 F2 G2 A1 A2
        val stab = Array(notes.size) { brass(hz(notes[it].toDouble()), 0.32, 170L + it, 0.012, 0.14, 300.0, 2300.0, 3, 0.004, 2.0) }
        val riffA = arrayOf(intArrayOf(0, 0), intArrayOf(2, 0), intArrayOf(3, 1), intArrayOf(6, 0), intArrayOf(8, 5), intArrayOf(10, 2), intArrayOf(11, 1), intArrayOf(14, 0))
        val riffB = arrayOf(intArrayOf(0, 0), intArrayOf(2, 0), intArrayOf(3, 1), intArrayOf(6, 2), intArrayOf(8, 3), intArrayOf(10, 2), intArrayOf(11, 1), intArrayOf(12, 0), intArrayOf(14, 1))
        for (i in 0 until 20) {
            if (i % 4 == 3) continue
            for (h in (if (i % 2 == 0) riffA else riffB)) b.add(stab[h[1]], s(i * bar + h[0] * sx), 0.36, 0.0, 0.25)
        }
        val hah = Array(2) { formantVoice(0.3, 180L + it, { t -> 112.0 - 22.0 * t }, { t -> Vowels.BASS_A.mix(Vowels.BASS_O, clamp01(t / 0.3)) }, { t -> exp(-t * 11.0) * min(1.0, t / 0.01) }, 0.04, 0.3, 0.3, 28.0, 0.0, 5.0, 2.2, 0.82, 0.5) }
        for (i in 0 until 20 step 2) { b.add(hah[0], s(i * bar), 0.5, -0.2, 0.5); b.add(hah[1], s(i * bar + 8 * sx), 0.4, 0.2, 0.5) }
        return finish(b, ts.layers[3], Rv.HALL)
    }

    // ------------------------------------------------------------------------------------------------- SEANCE
    private val seanceChords = arrayOf(
        doubleArrayOf(N.D3, N.A3, N.D4, N.F4), doubleArrayOf(N.D3, N.A3, N.D4, N.F4), doubleArrayOf(N.Eb3, N.Bb3, N.Eb4, N.G4), doubleArrayOf(N.D3, N.A3, N.D4, N.F4),
        doubleArrayOf(N.Bb3, N.D4, N.F4, N.Bb4), doubleArrayOf(N.A3, N.E4, N.A4, N.Cs4 + 12.0), doubleArrayOf(N.D3, N.A3, N.D4, N.F4), doubleArrayOf(N.A3, N.E4, N.A4, N.Cs4 + 12.0))

    private fun seanceL0(): Pcm {
        val ts = SEANCE; val n = ts.loopFrames; val b = bus(ts); val bar = 8.0 / 3.0
        b.add(loopPad(doubleArrayOf(N.D2, N.A2), n, 211, 160.0, 460.0, 1.2, 2.0, 0.4), 0, 0.5, 0.0, 0.2)
        b.add(loopSine(hz(N.D1), n), 0, 0.45)
        b.add(windBed(n, 221, 300.0, 1100.0, 1.2, 2.0, 4.0), 0, 0.35, 0.0, 0.3)
        for (c in 0 until 8) {
            val v = seanceChords[c].map { it - 12.0 }.toDoubleArray()
            b.add(choirChord(v, 6.6, 230L + c, 1.8, 2.0, Vowels.O, Vowels.A, unison = 2, shift = 0.92), s(c * 2 * bar - 0.3), 0.5, (c % 2) * 0.3 - 0.15, 0.7)
        }
        b.add(bell(hz(N.D3), 8.0, Bells.CHURCH, bright = 0.55, decay = 0.9, beat = 0.0015), 0, 0.14, 0.0, 0.8)
        b.add(bell(hz(N.A2), 8.0, Bells.CHURCH, bright = 0.55, decay = 0.9, beat = 0.0015), s(8 * bar), 0.12, 0.0, 0.8)
        return finish(b, ts.layers[0], Rv.HALL)
    }

    private fun seanceL1(): Pcm {
        val ts = SEANCE; val n = ts.loopFrames; val b = bus(ts); val bar = 8.0 / 3.0; val sx = bar / 16
        val dl = drum(55.0, 0.9, 311, 0.95, 1.2); val dm = drum(73.4, 0.6, 312, 0.85, 1.0); val dh = drum(110.0, 0.45, 313, 0.8, 0.9, 1.3)
        val hand = drum(220.0, 0.3, 314, 0.9, 0.5, 1.6)
        val kit = arrayOf(dl, dm, dh)
        val pat = arrayOf(intArrayOf(0, 0, 10), intArrayOf(3, 0, 5), intArrayOf(4, 1, 7), intArrayOf(6, 0, 8), intArrayOf(8, 0, 9), intArrayOf(10, 0, 5), intArrayOf(12, 1, 8), intArrayOf(14, 2, 7), intArrayOf(15, 2, 5))
        val tick = noiseBurst(0.05, 315, 0.014, hp = 5200.0).norm()
        val hah = Array(2) { formantVoice(0.26, 320L + it, { t -> 132.0 - 18.0 * t }, { Vowels.A.mix(Vowels.O, clamp01(it / 0.26)) }, { t -> exp(-t * 13.0) * min(1.0, t / 0.008) }, 0.04, 0.35, 0.2, 30.0, 0.0, 5.0, 1.8, 0.85, 0.3) }
        for (i in 0 until 16) {
            for (h in pat) b.add(kit[h[1]], s(i * bar + h[0] * sx), 0.1 * h[2], (h[1] - 1) * 0.25, 0.3)
            b.add(hand, s(i * bar + 4 * sx), 0.3, 0.3, 0.3); b.add(hand, s(i * bar + 12 * sx), 0.3, -0.3, 0.3)
            for (k in 0 until 16 step 2) b.add(tick, s(i * bar + k * sx), 0.06 + 0.02 * (k % 4 == 0).compareTo(false), if (k % 4 == 0) -0.3 else 0.3, 0.2)
            b.add(hah[i % 2], s(i * bar + 4 * sx), 0.55, -0.25, 0.5); b.add(hah[(i + 1) % 2], s(i * bar + 12 * sx), 0.5, 0.25, 0.5)
        }
        return finish(b, ts.layers[1], Rv.CRYPT)
    }

    private fun seanceL2(): Pcm {
        val ts = SEANCE; val n = ts.loopFrames; val b = bus(ts); val bar = 8.0 / 3.0; val sx = bar / 16
        val fig = intArrayOf(0, 7, 12, 7, 3, 7, 12, 7)                     // tremolo ostinato (semitones over the chord root)
        val cache = HashMap<Int, FloatArray>()
        val shift = intArrayOf(0, 0, 1, 0, -4, -5, 0, -5)
        for (c in 0 until 8) for (bb in 0 until 2) for (k in 0 until 8) {
            val m = 50 + shift[c] + fig[k]
            val smp = cache.getOrPut(m) { strings(hz(m.toDouble()), 0.3, 340L + m, 12.0, 0.5, 0.02, 0.1, 2400.0) }
            b.add(smp, s((c * 2 + bb) * bar + k * 2 * sx), 0.5, (k % 2) * 0.4 - 0.2, 0.3)
        }
        val syl = Array(3) { j -> formantVoice(0.22, 360L + j, { 196.0 * semi(intArrayOf(0, 3, 7)[j].toDouble()) }, { Vowels.A }, { t -> exp(-t * 9.0) * min(1.0, t / 0.01) }, 0.02, 0.25, 0.0, 30.0, 0.0, 5.0, 1.4, 1.0, 0.0) }
        for (c in 0 until 8 step 2) for (j in 0 until 3) b.add(syl[j], s(c * 2 * bar + (10 + j * 2) * sx), 0.6, (j - 1) * 0.3, 0.6)
        return finish(b, ts.layers[2], Rv.HALL)
    }

    private fun seanceL3(): Pcm {
        val ts = SEANCE; val n = ts.loopFrames; val b = bus(ts); val bar = 8.0 / 3.0; val sx = bar / 16
        val dl = drum(55.0, 0.7, 411, 0.95, 1.0); val dm = drum(73.4, 0.5, 412, 0.9, 0.9); val dh = drum(110.0, 0.4, 413, 0.85, 0.8, 1.3)
        val kit = arrayOf(dl, dm, dh)
        for (i in 0 until 16) for (k in 0 until 16) {
            val d = if (k % 4 == 0) 0 else if (k % 2 == 0) 1 else 2
            val g = (if (k % 4 == 0) 0.85 else if (k % 2 == 0) 0.6 else 0.4) * (0.7 + 0.3 * sin(PI * (i % 8) / 8))
            b.add(kit[d], s(i * bar + k * sx), 0.1 * g, (d - 1) * 0.25, 0.25)
        }
        for ((j, i0) in intArrayOf(4, 12).withIndex()) {
            val len = 4 * bar; val rs = frames(len)
            val str = FloatArray(rs); val osc = Array(4) { SawOsc(0.2 * it) }
            val flt = Svf(); var ph = 0.0
            for (x in 0 until rs) {
                val p = x.toDouble() / rs
                val f = hz(N.D4) * 2.0.pow(p)
                if (x and 31 == 0) flt.set(f * 3.0, 0.9)
                var sm = 0.0; val tab = Tables.sawFor(f)
                for (u in 0 until 4) sm += osc[u].tick(f * (1 + 0.006 * (u - 1.5)) / SRD, tab)
                flt.process(sm * 0.25); ph += (13.0 + 3.0 * p) / SRD
                str[x] = (flt.lp * sin(PI * p).pow(0.8) * (0.6 + 0.4 * Tables.cyc(ph))).toFloat()
            }
            b.add(str.norm(), s(i0 * bar), 0.55, if (j == 0) -0.3 else 0.3, 0.6)
        }
        val st = Array(2) { brass(hz(intArrayOf(38, 39)[it].toDouble()), 0.3, 420L + it, 0.012, 0.12, 300.0, 2400.0, 3, 0.004, 2.0) }
        for (i in 0 until 16) for (k in intArrayOf(6, 14)) b.add(st[(i / 4) % 2], s(i * bar + k * sx), 0.45, 0.0, 0.25)
        for (i in intArrayOf(7, 15)) b.add(riser(bar, 430L + i, 300.0, 4200.0, 73.0, 440.0, 0.3, 1.2, 2.2), s(i * bar), 0.7, 0.0, 0.4)
        for (i in intArrayOf(0, 8)) b.add(bell(hz(N.D2), 5.0, Bells.GONG, bright = 0.45, decay = 0.7), s(i * bar), 0.3, 0.0, 0.7)
        return finish(b, ts.layers[3], Rv.HALL)
    }

    // ------------------------------------------------------------------------------------------------- VAULT
    private fun vault(): Pcm {
        val ts = VAULT; val n = ts.loopFrames; val b = bus(ts); val beat = 60.0 / 108.0; val bar = beat * 4
        val roots = doubleArrayOf(38.0, 34.0, 31.0, 33.0, 38.0, 34.0, 33.0, 33.0, 38.0, 29.0, 31.0, 34.0, 31.0, 33.0, 38.0, 33.0)
        val third = doubleArrayOf(41.0, 38.0, 34.0, 37.0, 41.0, 38.0, 37.0, 37.0, 41.0, 33.0, 34.0, 38.0, 34.0, 37.0, 41.0, 37.0)
        val fifth = doubleArrayOf(45.0, 41.0, 38.0, 40.0, 45.0, 41.0, 40.0, 40.0, 45.0, 36.0, 38.0, 41.0, 38.0, 40.0, 45.0, 40.0)
        val orgCache = HashMap<Int, FloatArray>()
        for (i in 0 until 16) {
            val t = i * bar
            for ((j, m) in doubleArrayOf(roots[i] + 12, third[i] + 12, fifth[i] + 12, roots[i] + 24).withIndex()) {
                val key = (m * 10).toInt()
                b.add(orgCache.getOrPut(key) { organ(hz(m), bar + 0.15, 0.04, 0.2) }, s(t), 0.11 * (if (j == 3) 0.7 else 1.0), (j - 1.5) * 0.2, 0.4)
            }
            val bassNote = orgCache.getOrPut((roots[i] * 10).toInt() + 1) { organ(hz(roots[i]), beat * 0.5, 0.01, 0.06, 0.0) }
            for (k in 0 until 8) b.add(bassNote, s(t + k * beat / 2), if (k % 2 == 0) 0.2 else 0.12, 0.0, 0.1)
        }
        val dl = drum(55.0, 0.9, 511, 0.95, 1.3); val dm = drum(73.4, 0.6, 512, 0.9, 1.0); val dh = drum(110.0, 0.4, 513, 0.85, 0.9, 1.3)
        for (i in 0 until 16) {
            val fill = i % 4 == 3
            for (k in 0 until 4) {
                if (fill && k >= 2) continue
                b.add(if (k % 2 == 0) dl else dm, s(i * bar + k * beat), if (k == 0) 1.0 else 0.62, 0.0, 0.3)
                b.add(dh, s(i * bar + k * beat + beat / 2), 0.28, if (k % 2 == 0) -0.3 else 0.3, 0.3)
            }
            if (fill) for (k in 0 until 8) b.add(if (k % 2 == 0) dm else dh, s(i * bar + 2 * beat + k * beat / 4), 0.35 + 0.08 * k, 0.0, 0.35)
        }
        val mel = arrayOf(intArrayOf(0, 0, 69, 2), intArrayOf(0, 2, 67, 1), intArrayOf(0, 3, 65, 1), intArrayOf(1, 0, 74, 3), intArrayOf(1, 3, 72, 1),
            intArrayOf(2, 0, 70, 2), intArrayOf(2, 2, 69, 1), intArrayOf(2, 3, 67, 1), intArrayOf(3, 0, 73, 4), intArrayOf(4, 0, 74, 2), intArrayOf(4, 2, 77, 2),
            intArrayOf(5, 0, 74, 2), intArrayOf(5, 2, 70, 2), intArrayOf(6, 0, 73, 2), intArrayOf(6, 2, 76, 2), intArrayOf(7, 0, 76, 4), intArrayOf(8, 0, 77, 2),
            intArrayOf(8, 2, 76, 1), intArrayOf(8, 3, 74, 1), intArrayOf(9, 0, 72, 3), intArrayOf(9, 3, 69, 1), intArrayOf(10, 0, 70, 2), intArrayOf(10, 2, 74, 2),
            intArrayOf(11, 0, 74, 3), intArrayOf(11, 3, 72, 1), intArrayOf(12, 0, 70, 2), intArrayOf(12, 2, 67, 2), intArrayOf(13, 0, 73, 2), intArrayOf(13, 2, 69, 2),
            intArrayOf(14, 0, 74, 4), intArrayOf(15, 0, 73, 2), intArrayOf(15, 2, 76, 2))
        for (m in mel) {
            val dur = m[3] * beat + 0.25
            b.add(choirChord(doubleArrayOf(m[2].toDouble(), m[2] - 12.0), dur, 520L + m[0] * 7 + m[1], 0.07, 0.25, Vowels.A, Vowels.O, 3, 0.007, 5.4, 0.006, 0.05), s(m[0] * bar + m[1] * beat), 0.45, 0.0, 0.7)
        }
        val stab = Array(4) { brass(hz(doubleArrayOf(38.0, 34.0, 31.0, 33.0)[it]), 0.9, 560L + it, 0.03, 0.4, 320.0, 2800.0, 3, 0.004, 1.8) }
        for (i in 0 until 16 step 2) b.add(stab[(i / 2) % 4], s(i * bar), 0.34, 0.0, 0.3)
        for (i in intArrayOf(3, 7, 11, 15)) b.add(sweepNoise(bar, 570L + i, 700.0, 5200.0, 1.3) { p -> p.pow(2.2) }, s((i) * bar), 0.32, 0.0, 0.3)
        for (i in intArrayOf(0, 8)) b.add(bell(hz(N.D3), 6.0, Bells.CHURCH, bright = 0.6, decay = 0.9, beat = 0.0015), s(i * bar), 0.16, 0.0, 0.8)
        return finish(b, ts.layers[0], Rv.HALL)
    }
}

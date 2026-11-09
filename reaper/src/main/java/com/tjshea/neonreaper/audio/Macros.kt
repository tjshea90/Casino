package com.tjshea.neonreaper.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** MIDI note numbers around the tonal centre (D minor / D Phrygian). */
internal object N {
    const val D1 = 26.0; const val A1 = 33.0
    const val D2 = 38.0; const val Eb2 = 39.0; const val F2 = 41.0; const val G2 = 43.0; const val A2 = 45.0; const val Bb2 = 46.0; const val C3 = 48.0
    const val D3 = 50.0; const val Eb3 = 51.0; const val F3 = 53.0; const val Fs3 = 54.0; const val G3 = 55.0; const val A3 = 57.0; const val Bb3 = 58.0; const val C4 = 60.0; const val Cs4 = 61.0
    const val D4 = 62.0; const val Eb4 = 63.0; const val F4 = 65.0; const val Fs4 = 66.0; const val G4 = 67.0; const val A4 = 69.0; const val Bb4 = 70.0; const val C5 = 72.0
    const val D5 = 74.0; const val Eb5 = 75.0; const val F5 = 77.0; const val Fs5 = 78.0; const val G5 = 79.0; const val A5 = 81.0; const val D6 = 86.0
}

/** Places [x] at time [t] seconds. */
internal fun Bus.at(t: Double, x: FloatArray, g: Double = 1.0, pan: Double = 0.0, hall: Double = 0.0, room: Double = 0.0) =
    add(x, frames(t), g, pan, hall, room)

/** Reverb-only (wet) version of a sound: runs [src] through [spec] and returns the tail without the dry signal (mono). */
internal fun wetOnly(src: FloatArray, spec: ReverbSpec, tailSec: Double): FloatArray {
    val tail = frames(tailSec)
    val l = FloatArray(src.size + tail); val r = FloatArray(src.size + tail)
    Reverb.run(src, src.size, spec, tail, 1f, l, r, false)
    for (i in l.indices) l[i] = (l[i] + r[i]) * 0.5f
    return l
}

/** Reverse-reverb swell: the wet tail of [src] played backwards, so it blooms and ends exactly where [src] would have started. */
internal fun reverseSwell(src: FloatArray, spec: ReverbSpec, tailSec: Double): FloatArray = wetOnly(src, spec, tailSec).reversed2().fadeIn(0.04)

/** Layered impact: sub drop + body + air crack. [size] stretches the weight; sends add the room. */
internal fun Bus.boom(t: Double, g: Double = 1.0, size: Double = 1.0, hall: Double = 0.25, seed: Long = 11, pan: Double = 0.0) {
    at(t, thump(0.6 + 1.1 * size, 96.0, 34.0, 0.05 + 0.03 * size, 0.2 + 0.28 * size, 1.9), g, pan, hall * 0.6)
    at(t, thump(0.35 + 0.3 * size, 205.0, 74.0, 0.03, 0.09 + 0.05 * size, 2.2), g * 0.6, pan, hall)
    at(t, noiseBurst(0.18 + 0.2 * size, seed, 0.05 + 0.04 * size, lp = 2200.0 - 400.0 * size), g * 0.4, pan, hall * 1.3)
    at(t, noiseBurst(0.04, seed + 1, 0.008, hp = 1800.0, lp = 7000.0), g * 0.16, pan, hall * 0.6)
}

/** Choir chord (ghost choir, "ah" by default). Returns nothing; adds to the bus with reverb send. */
internal fun Bus.choir(t: Double, notes: DoubleArray, dur: Double, g: Double, hall: Double, attack: Double = 0.25, release: Double = 0.5,
                       v0: Vowel = Vowels.O, v1: Vowel = Vowels.A, seed: Long = 5, pan: Double = 0.0, shift: Double = 1.0, bright: Double = 1.0) {
    at(t, choirChord(notes, dur, seed, attack, release, v0, v1, shift = shift, bright = bright), g, pan, hall * 0.5, hall)
}

/** A dark brass note with room send. */
internal fun Bus.horn(t: Double, midi: Double, dur: Double, g: Double, hall: Double = 0.25, attack: Double = 0.08, release: Double = 0.2, seed: Long = 3,
                      cutHi: Double = 2600.0, pan: Double = 0.0) {
    at(t, brass(hz(midi), dur, seed, attack, release, 330.0, cutHi), g, pan, hall * 0.4, hall)
}

/** Random dark coin pings between [t0] and [t1]; density follows [shape] (0..1 over the span). */
internal fun Bus.coinRain(t0: Double, t1: Double, count: Int, fLo: Double, fHi: Double, g: Double, seed: Long, hall: Double = 0.3,
                          dark: Double = 0.65, spread: Double = 0.7, shape: (Double) -> Double = { 1.0 }) {
    val r = Rng(seed)
    var placed = 0; var guard = 0
    while (placed < count && guard < count * 40) {
        guard++
        val p = r.unit().toDouble()
        if (r.unit() > shape(p)) continue
        placed++
        val f = fLo * (fHi / fLo).pow(r.unit().toDouble())
        val c = coin(f, seed + placed, dark, 0.7 + 0.8 * r.unit())
        at(t0 + (t1 - t0) * p, c, g * (0.35 + 0.65 * r.unit()), (r.unit() * 2 - 1) * spread, hall * 0.5, hall)
    }
}

/** Accelerating, crescendo drum roll of [hits] strokes between [t0] and [t1] (two alternating pitches). */
internal fun Bus.drumRoll(t0: Double, t1: Double, hits: Int, f: Double, g0: Double, g1: Double, hall: Double, seed: Long = 40, curve: Double = 1.6) {
    for (k in 0 until hits) {
        val p = k.toDouble() / max(1, hits - 1)
        val tt = t0 + (t1 - t0) * p.pow(curve)
        val gg = lerp(g0, g1, p)
        val d = drum(if (k % 2 == 0) f else f * 1.19, 0.5, seed + k, 0.5 + 0.4 * p, 0.8)
        at(tt, d, gg, if (k % 2 == 0) -0.15 else 0.15, hall * 0.5, hall)
    }
}

/** Shimmering high bells cluster (restrained "soul sparkle"). */
internal fun Bus.sparkle(t: Double, notes: DoubleArray, spacing: Double, g: Double, hall: Double, seed: Long = 21, dark: Double = 0.6) {
    for ((k, m) in notes.withIndex()) {
        at(t + k * spacing, bell(hz(m), 1.1, Bells.GLASS, bright = dark, decay = 0.6, beat = 0.002), g * (1.0 - 0.07 * k), (k % 3 - 1) * 0.35, hall * 0.5, hall)
    }
}

/** Tolling church bell. */
internal fun Bus.toll(t: Double, midi: Double, dur: Double, g: Double, hall: Double, decay: Double = 1.0, bright: Double = 0.8, pan: Double = 0.0) {
    at(t, bell(hz(midi), dur, Bells.CHURCH, bright = bright, decay = decay, beat = 0.0015), g, pan, hall * 0.4, hall)
}

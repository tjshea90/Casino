package com.tjshea.neonreaper.audio

import com.tjshea.neonreaper.audio.Audio.Sfx as S
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Sum of exponentially decaying syllable bursts (start, shared attack/decay, per-burst amplitude). */
private fun bursts(t: Double, starts: DoubleArray, atk: Double, tau: Double, amps: DoubleArray): Double {
    var a = 0.0
    for (k in starts.indices) { val u = t - starts[k]; if (u < 0) continue; a += amps[k] * (if (u < atk) u / atk else exp(-(u - atk) / tau)) }
    return a
}

private fun humpAt(t: Double, s: Double, len: Double): Double { val u = (t - s) / len; return if (u in 0.0..1.0) sin(PI * u) else 0.0 }

/** Index of the last element of [starts] that is <= t (or 0). */
private fun segOf(t: Double, starts: DoubleArray): Int { var k = 0; for (i in starts.indices) if (t >= starts[i]) k = i; return k }

/** Characters, atmosphere, scatter / seance, tension and feature sounds. */
internal fun registerWorldSfx(r: SfxRegistry) {
    val major = doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4)
    val minor = doubleArrayOf(N.D3, N.A3, N.D4, N.F4)

    // ============================================================================================ voices
    r.add(S.REAPER_WHISPER, 1.8, -19.0, 2, fadeOut = 0.45, air = -6.0) { b ->
        val st = doubleArrayOf(0.05, 0.62, 1.12)
        val ln = doubleArrayOf(0.5, 0.42, 0.5)
        val vw = { t: Double -> if (t < 0.55) Vowels.U.mix(Vowels.O, t / 0.55) else if (t < 1.1) Vowels.A.mix(Vowels.E, (t - 0.55) / 0.55) else Vowels.O.mix(Vowels.U, (t - 1.1) / 0.6) }
        val amp = { t: Double -> (0..2).sumOf { humpAt(t, st[it], ln[it]) * (1.0 - 0.15 * it) } }
        b.at(0.0, whisper(1.7, 101, vw, amp, 420.0, 0.9), 0.9, -0.45, 0.35, 0.1)
        b.at(0.02, whisper(1.7, 102, vw, amp, 420.0, 0.85), 0.7, 0.45, 0.35, 0.1)
        b.at(0.0, formantVoice(1.7, 103, { t -> 86.0 - 6.0 * t }, vw, { t -> amp(t) * 0.35 }, jitter = 0.06, breath = 0.35, drive = 1.6, shift = 0.85), 0.5, 0.0, 0.3, 0.1)
        Wet(hall = Rv.HALL, room = Rv.BOX)
    }
    /** Deep, slow, menacing laugh: [count] falling "HA" syllables of a giant vocal tract with vocal-fry roughness. */
    fun reaperLaugh(sfx: Audio.Sfx, sec: Double, count: Int, base: Double, gap: Double, seed: Long, hall: ReverbSpec) {
        r.add(sfx, sec, -17.5, 2, fadeOut = 0.6) { b ->
            val st = DoubleArray(count) { it * gap + 0.01 * it * it }
            val f0s = DoubleArray(count) { base * (1.0 - 0.06 * it - 0.012 * it * it) }
            val am = DoubleArray(count) { 1.0 - 0.14 * it - 0.02 * it * it }
            val f0 = { t: Double -> val k = segOf(t, st); val u = t - st[k]; f0s[k] * (1.0 - 0.15 * min(u / 0.32, 1.0)) }
            val vw = { t: Double -> val k = segOf(t, st); val u = (t - st[k]) / 0.34; Vowels.BASS_A.mix(Vowels.BASS_O, clamp01(u)) }
            val amp = { t: Double -> val k = segOf(t, st); val u = t - st[k]; if (u > 0.4) 0.0 else am[k] * (if (u < 0.012) u / 0.012 else exp(-(u - 0.012) / 0.15)) }
            b.at(0.0, formantVoice(sec, seed, f0, vw, amp, jitter = 0.05, breath = 0.14, growl = 0.3, growlHz = 26.0, drive = 2.2, shift = 0.82, sub = 0.8), 1.0, 0.0, 0.3, 0.15)
            for (k in st.indices) {
                b.at(st[k], bandBurst(0.06, seed + 10 + k, 1300.0, 1.2, 0.016), 0.2 * am[k], 0.0, 0.2)
                b.at(st[k], thump(0.2, f0s[k] * 1.1, f0s[k] * 0.7, 0.08, 0.1, 1.6), 0.35 * am[k], 0.0, 0.1)
            }
            Wet(hall = hall, room = Rv.BOX)
        }
    }
    reaperLaugh(S.REAPER_LAUGH, 2.5, 5, 84.0, 0.46, 111, Rv.CRYPT)
    reaperLaugh(S.LAUGH, 2.0, 4, 90.0, 0.43, 112, Rv.CRYPT)
    r.add(S.CACKLE, 1.3, -19.0, 2, fadeOut = 0.35) { b ->
        val n = 8
        val st = DoubleArray(n) { 0.04 + 0.108 * it + 0.002 * it * it }
        val f0 = { t: Double -> val k = segOf(t, st); val u = t - st[k]; (116.0 + 4.5 * k) * (1.0 + 0.16 * min(u / 0.05, 1.0)) * (1.0 - 0.012 * k) }
        val vw = { t: Double -> val k = segOf(t, st); Vowels.E.mix(Vowels.O, clamp01((t - st[k]) / 0.07)) }
        val amp = { t: Double -> val k = segOf(t, st); val u = t - st[k]; if (u > 0.09) 0.0 else (1.0 - 0.07 * k) * (if (u < 0.006) u / 0.006 else exp(-(u - 0.006) / 0.035)) }
        b.at(0.0, formantVoice(1.3, 131, f0, vw, amp, jitter = 0.08, breath = 0.28, growl = 0.5, growlHz = 44.0, drive = 1.8, shift = 0.84, sub = 0.5), 1.0, 0.0, 0.3, 0.2)
        Wet(hall = Rv.CRYPT, room = Rv.BOX)
    }
    r.add(S.GIGGLE, 1.6, -25.0, 2, fadeOut = 0.5) { b ->
        val n = 6
        val st = DoubleArray(n) { 0.08 + 0.2 * it }
        val vw = { t: Double -> Vowels.I.mix(Vowels.E, clamp01((t % 0.2) / 0.1)) }
        val amp = { t: Double -> bursts(t, st, 0.025, 0.05, DoubleArray(n) { 1.0 - 0.13 * it }) }
        val w = whisper(1.5, 141, vw, amp, 900.0, 0.82).lowpass(4500.0)
        b.at(0.0, w, 0.9, -0.5, 0.8, 0.0)
        val v = formantVoice(1.5, 142, { t -> 250.0 + 40.0 * sin(7.0 * t) }, vw, { t -> amp(t) * 0.2 }, jitter = 0.1, breath = 0.3, shift = 0.85).lowpass(3800.0)
        b.at(0.1, v, 0.6, 0.5, 0.9, 0.0)
        Wet(hall = Rv.CATHEDRAL)
    }
    r.add(S.DOCTOR_RASP, 1.9, -20.0, 2, fadeOut = 0.4, air = -4.5) { b ->
        b.at(0.0, sweepNoise(0.78, 150, 600.0, 1700.0, 2.0) { p -> sin(PI * p.pow(0.75)).pow(1.2) }, 0.85, 0.0, 0.0, 0.2)
        b.at(0.92, sweepNoise(0.86, 151, 1500.0, 520.0, 2.0) { p -> sin(PI * p.pow(0.6)).pow(1.2) }, 0.95, 0.0, 0.0, 0.2)
        b.at(0.0, sweepNoise(0.78, 152, 1100.0, 1190.0, 22.0) { p -> sin(PI * p) }, 0.22, 0.0, 0.0, 0.2)
        b.at(0.92, sweepNoise(0.86, 153, 1180.0, 1080.0, 22.0) { p -> sin(PI * p) }, 0.22, 0.0, 0.0, 0.2)
        b.at(0.0, rumble(0.78, 154, 240.0, 30.0, 0.9) { p -> sin(PI * p) }, 0.35, 0.0, 0.0, 0.1)
        b.at(0.92, rumble(0.86, 155, 240.0, 30.0, 0.9) { p -> sin(PI * p) }, 0.35, 0.0, 0.0, 0.1)
        Wet(room = Rv.ROOM)
    }
    r.add(S.DEMON_ROAR, 2.1, -15.5, 2, hitAt = 0.0, fadeOut = 0.6, duckSec = 1.7, duckTo = 0.6) { b ->
        val f0 = { t: Double -> 60.0 - 14.0 * clamp01(t / 1.8) }
        val vw = { t: Double -> if (t < 0.9) Vowels.BASS_A.mix(Vowels.BASS_O, t / 0.9) else Vowels.BASS_O.mix(Vowels.BASS_A, clamp01((t - 0.9) / 0.9)) }
        val amp = { t: Double -> smooth(t / 0.28) * smooth((2.0 - t) / 0.7) }
        b.at(0.0, formantVoice(2.1, 161, f0, vw, amp, jitter = 0.07, breath = 0.3, growl = 0.65, growlHz = 36.0, drive = 3.0, shift = 0.7, sub = 0.8), 1.0, 0.0, 0.25, 0.1)
        b.at(0.0, rumble(2.0, 162, 900.0, 36.0, 0.9) { p -> sin(PI * p.pow(0.7)) }, 0.45, 0.0, 0.2, 0.1)
        b.at(0.0, sweepNoise(2.0, 163, 1500.0, 950.0, 3.0) { p -> sin(PI * p).pow(1.4) * (0.6 + 0.4 * sin(2 * PI * 38.0 * p * 2.0)) }, 0.18, 0.0, 0.2)
        b.boom(0.0, 0.9, 0.9)
        Wet(hall = Rv.CRYPT, room = Rv.BOX)
    }
    r.add(S.GROWL, 1.4, -17.0, 2, fadeOut = 0.45) { b ->
        val f0 = { t: Double -> 72.0 - 18.0 * clamp01(t / 1.2) }
        val vw = { t: Double -> Vowels.BASS_A.mix(Vowels.BASS_O, clamp01(t / 1.2)) }
        val amp = { t: Double -> smooth(t / 0.18) * smooth((1.35 - t) / 0.5) }
        b.at(0.0, formantVoice(1.4, 171, f0, vw, amp, jitter = 0.07, breath = 0.3, growl = 0.6, growlHz = 30.0, drive = 2.6, shift = 0.75, sub = 0.7), 1.0, 0.0, 0.2, 0.1)
        b.at(0.0, rumble(1.3, 172, 800.0, 30.0, 0.9) { p -> sin(PI * p) }, 0.4, 0.0, 0.1)
        Wet(hall = Rv.CRYPT, room = Rv.BOX)
    }
    r.add(S.SKULL_RATTLE, 1.3, -18.0, 2, fadeOut = 0.3, air = -4.0) { b ->
        val rg = Rng(181)
        var t = 0.02
        var k = 0
        while (t < 1.1) {
            val p = t / 1.1
            val f = 700.0 + 2500.0 * rg.unit()
            b.at(t, bandBurst(0.06, 190L + k, f, 10.0, 0.012), 0.45 + 0.55 * rg.unit(), (rg.unit() * 2 - 1) * 0.5, 0.0, 0.3)
            if (k % 3 == 0) b.at(t, bell(180.0 + 80.0 * rg.unit(), 0.12, Bells.BLOCK, decay = 0.7), 0.25, 0.0, 0.0, 0.3)
            t += 0.09 - 0.065 * sin(PI * p) + 0.02 * rg.unit()
            k++
        }
        Wet(room = Rv.ROOM)
    }
    r.add(S.CHATTER, 0.95, -19.0, 2, fadeOut = 0.2) { b ->
        val rg = Rng(201)
        for (k in 0 until 13) {
            val t = 0.02 + k * 0.058 + 0.004 * rg.signed()
            val f = 1400.0 + 1200.0 * rg.unit()
            b.at(t, bandBurst(0.04, 210L + k, f, 8.0, 0.009), 0.7 * (1.0 - 0.03 * k), (rg.unit() * 2 - 1) * 0.3, 0.0, 0.3)
            b.at(t, thump(0.04, 210.0, 150.0, 0.01, 0.02), 0.3, 0.0, 0.0, 0.2)
        }
        Wet(room = Rv.ROOM)
    }
    fun caw(b: Bus, t: Double, f: Double, dur: Double, g: Double, pan: Double, seed: Long) {
        val f0 = { u: Double -> f * (1.12 - 0.3 * clamp01(u / dur)) }
        val amp = { u: Double -> smooth(u / 0.02) * smooth((dur - u) / (dur * 0.5)) * (0.75 + 0.25 * sin(2 * PI * u / dur * 2.0)) }
        val vw = { u: Double -> Vowel(820.0, 1350.0, 2700.0).mix(Vowel(700.0, 1150.0, 2500.0), clamp01(u / dur)) }
        b.at(t, formantVoice(dur + 0.02, seed, f0, vw, amp, jitter = 0.12, breath = 0.4, growl = 0.5, growlHz = 62.0, drive = 2.0, shift = 0.92), g, pan, 0.3, 0.2)
    }
    r.add(S.CAW, 0.95, -19.0, 2, fadeOut = 0.3) { b ->
        caw(b, 0.0, 400.0, 0.3, 0.95, -0.25, 220)
        caw(b, 0.42, 360.0, 0.36, 0.85, 0.25, 221)
        Wet(hall = Rv.CRYPT, room = Rv.BOX)
    }
    r.add(S.RAVEN_FLOCK, 2.5, -19.0, 2, fadeOut = 0.7) { b ->
        val ts = doubleArrayOf(0.0, 0.26, 0.58, 0.92, 1.22, 1.62)
        val fs = doubleArrayOf(420.0, 380.0, 470.0, 350.0, 440.0, 330.0)
        val ds = doubleArrayOf(0.26, 0.3, 0.24, 0.32, 0.28, 0.34)
        val ps = doubleArrayOf(-0.7, 0.4, -0.2, 0.7, -0.5, 0.2)
        for (k in ts.indices) caw(b, ts[k], fs[k], ds[k], 0.75 - 0.05 * k, ps[k], 230L + k)
        for ((j, t0) in doubleArrayOf(0.15, 0.75, 1.3).withIndex()) for (f in 0 until 6)
            b.at(t0 + f * 0.13, noiseBurst(0.1, 240L + j * 7 + f, 0.03, lp = 320.0), 0.35, if (j % 2 == 0) -0.5 else 0.5, 0.1, 0.3)
        Wet(hall = Rv.CRYPT, room = Rv.ROOM)
    }
    r.add(S.WRAITH_WAIL, 2.5, -17.0, 2, fadeOut = 0.9, duckSec = 2.0, duckTo = 0.6) { b ->
        val f0 = { t: Double -> if (t < 0.9) lerp(420.0, 880.0, smooth(t / 0.9)) else lerp(880.0, 600.0, smooth((t - 0.9) / 1.4)) }
        val vw = { t: Double -> when { t < 0.7 -> Vowels.O.mix(Vowels.A, t / 0.7); t < 1.5 -> Vowels.A.mix(Vowels.E, (t - 0.7) / 0.8); else -> Vowels.E.mix(Vowels.U, clamp01((t - 1.5) / 0.9)) } }
        val amp = { t: Double -> smooth(t / 0.5) * smooth((2.5 - t) / 0.8) }
        b.at(0.0, formantVoice(2.6, 251, f0, vw, amp, jitter = 0.012, breath = 0.3, vibDepth = 0.03, vibHz = 5.2, drive = 1.2, shift = 1.05), 0.9, -0.35, 0.5, 0.1)
        b.at(0.12, formantVoice(2.5, 252, { t -> f0(t) * 1.0595 }, vw, amp, jitter = 0.012, breath = 0.3, vibDepth = 0.03, vibHz = 4.7, drive = 1.2, shift = 1.05), 0.5, 0.35, 0.6, 0.1)
        b.at(0.3, bowed(hz(N.D5), 2.2, 253, 0.8, 1.0, flutter = 0.3), 0.18, 0.0, 0.7)
        Wet(hall = Rv.CATHEDRAL, room = Rv.ROOM)
    }
    r.add(S.HEARTBEAT, 0.75, -20.0, 1, fadeOut = 0.2) { b ->
        b.at(0.0, thump(0.2, 72.0, 43.0, 0.03, 0.075, 1.6), 0.6, 0.0, 0.1)
        b.at(0.0, thump(0.12, 210.0, 120.0, 0.02, 0.05, 2.2), 0.9)
        b.at(0.0, bandBurst(0.03, 91, 600.0, 1.5, 0.008), 0.3)
        b.at(0.26, thump(0.2, 66.0, 40.0, 0.03, 0.07, 1.6), 0.45, 0.0, 0.1)
        b.at(0.26, thump(0.12, 190.0, 110.0, 0.02, 0.05, 2.2), 0.65)
        Wet(room = Rv.CRYPT)
    }

    // ======================================================================================== atmosphere
    r.add(S.THUNDER, 2.8, -17.0, 2, hitAt = 0.05, fadeOut = 0.9) { b ->
        b.at(0.0, noiseBurst(0.25, 301, 0.06, hp = 300.0, lp = 3500.0), 0.7, 0.0, 0.3)
        b.at(0.02, rumble(2.7, 302, 260.0, 5.0, 0.85) { p -> smooth(p / 0.1) * exp(-1.7 * p) }, 1.0, 0.0, 0.25)
        b.boom(0.04, 0.5, 1.3)
        Wet(hall = Rv.HALL)
    }
    r.add(S.LIGHTNING_CRACK, 1.7, -16.0, 2, hitAt = 0.0, fadeOut = 0.6) { b ->
        b.at(0.0, noiseBurst(0.15, 311, 0.022, hp = 700.0, lp = 7000.0), 1.0, 0.0, 0.2)
        b.at(0.0, noiseBurst(0.55, 312, 0.13, hp = 2500.0, lp = 8000.0), 0.22, 0.0, 0.3)
        b.boom(0.03, 0.4, 0.9, seed = 313)
        b.at(0.05, rumble(1.5, 314, 240.0, 6.0, 0.85) { p -> smooth(p / 0.08) * exp(-2.2 * p) }, 0.45, 0.0, 0.25)
        Wet(hall = Rv.HALL)
    }
    r.add(S.DOOR_SLAM, 1.3, -16.0, 2, hitAt = 0.0, fadeOut = 0.45) { b ->
        b.at(0.0, thump(0.5, 130.0, 45.0, 0.03, 0.15, 2.0), 1.0, 0.0, 0.2)
        b.at(0.0, noiseBurst(0.09, 321, 0.02, hp = 500.0, lp = 3200.0), 0.7, 0.0, 0.3)
        b.at(0.0, bell(380.0, 1.0, Bells.GONG, bright = 0.4, decay = 0.35), 0.3, 0.0, 0.4)
        b.at(0.15, thump(0.25, 110.0, 50.0, 0.03, 0.08, 1.8), 0.45, 0.0, 0.2)
        Wet(hall = Rv.CRYPT)
    }
    r.add(S.WIND_GUST, 2.3, -22.0, 3, fadeOut = 0.8, air = -5.0) { b ->
        b.at(0.0, whoosh(2.2, 322, 350.0, 1100.0, 1.1, 0.45, 1.3), 1.0, 0.0, 0.3, 0.1)
        b.at(0.2, sweepNoise(2.0, 323, 1500.0, 2300.0, 14.0) { p -> sin(PI * p).pow(2.0) }, 0.1, 0.4, 0.4)
        b.at(0.0, rumble(2.2, 324, 200.0, 4.0, 0.7) { p -> sin(PI * p) }, 0.35, 0.0, 0.2)
        Wet(hall = Rv.HALL)
    }
    r.add(S.CANDLE_WHOOSH, 0.8, -22.0, 3, fadeOut = 0.3, air = -6.0) { b ->
        b.at(0.0, whoosh(0.7, 331, 800.0, 2600.0, 0.8, 0.25, 1.0), 0.8, 0.0, 0.0, 0.3)
        b.at(0.0, thump(0.35, 72.0, 44.0, 0.08, 0.1, 1.2), 0.45)
        b.at(0.05, clinks(0.6, 332, 38.0, 2500.0, 6000.0, 0.008) { p -> exp(-3.0 * p) }, 0.15, 0.0, 0.0, 0.2)
        Wet(room = Rv.ROOM)
    }
    r.add(S.SLASH, 0.42, -17.0, 2, hitAt = 0.0, fadeOut = 0.12, air = -5.0) { b ->
        b.at(0.0, whoosh(0.3, 341, 4500.0, 900.0, 0.9, 0.2, 1.0), 1.0, 0.0, 0.0, 0.2)
        b.at(0.08, bell(1250.0, 0.35, Bells.GONG, bright = 0.5, decay = 0.15), 0.22, 0.0, 0.0, 0.3)
        b.at(0.07, thump(0.14, 210.0, 90.0, 0.02, 0.05, 1.8), 0.45, 0.0, 0.0, 0.1)
        Wet(room = Rv.BOX)
    }
    r.add(S.WHOOSH, 0.62, -22.0, 2, fadeOut = 0.2, air = -5.0) { b ->
        b.at(0.0, whoosh(0.6, 351, 250.0, 1700.0, 0.9, 0.45, 1.1), 1.0, 0.0, 0.1, 0.2)
        Wet(room = Rv.ROOM)
    }
    r.add(S.BONE_CRACK, 0.45, -16.0, 2, hitAt = 0.0, fadeOut = 0.15) { b ->
        b.at(0.0, noiseBurst(0.02, 361, 0.004, hp = 1500.0), 0.9, 0.0, 0.0, 0.3)
        b.at(0.0, bandBurst(0.2, 362, 1400.0, 6.0, 0.05), 0.6, 0.0, 0.0, 0.3)
        b.at(0.0, thump(0.2, 230.0, 110.0, 0.01, 0.06, 2.0), 0.7)
        b.at(0.0, bell(900.0, 0.25, Bells.BLOCK, decay = 0.5), 0.25, 0.0, 0.0, 0.3)
        Wet(room = Rv.ROOM)
    }
    r.add(S.CHAIN_RATTLE, 1.15, -19.0, 2, fadeOut = 0.3, air = -5.0) { b ->
        b.at(0.0, clinks(1.05, 371, 62.0, 700.0, 4200.0, 0.03) { p -> sin(PI * p.pow(0.8)) * 0.8 + 0.25 }, 1.0, 0.0, 0.0, 0.3)
        b.at(0.0, rumble(1.0, 372, 400.0, 14.0, 0.8) { p -> sin(PI * p) }, 0.25)
        Wet(room = Rv.ROOM)
    }
    r.add(S.BELL, 2.9, -20.0, 2, fadeOut = 0.9) { b ->
        b.toll(0.0, N.D3, 2.9, 0.9, 0.4, decay = 0.65, bright = 1.15)
        b.at(0.0, thump(0.4, 80.0, 44.0, 0.05, 0.15, 1.4), 0.12, 0.0, 0.1)
        Wet(hall = Rv.HALL)
    }
    r.add(S.REALITY_CHECK, 2.4, -22.0, 3, fadeOut = 0.9) { b ->
        b.toll(0.0, N.A3, 2.4, 0.8, 0.45, decay = 0.7, bright = 0.55)
        b.at(0.0, bowed(hz(N.A3) * 0.5, 2.3, 381, 0.6, 1.0, ratios = doubleArrayOf(1.0, 2.0, 3.0), amps = doubleArrayOf(1.0, 0.3, 0.12)), 0.25, 0.0, 0.5)
        Wet(hall = Rv.HALL)
    }

    // ===================================================================================== scatter / seance
    r.add(S.SCATTER_LAND, 0.78, -18.0, 1, hitAt = 0.0, fadeOut = 0.25) { b ->
        b.at(0.0, thump(0.3, 112.0, 58.0, 0.025, 0.1, 1.9), 0.95, 0.0, 0.1, 0.2)
        b.at(0.0, noiseBurst(0.1, 401, 0.025, lp = 800.0), 0.5, 0.0, 0.0, 0.3)
        b.at(0.0, bell(170.0, 0.35, Bells.BLOCK, decay = 2.0), 0.3, 0.0, 0.0, 0.3)
        b.at(0.01, bell(hz(N.D4), 0.75, Bells.CHURCH, bright = 0.7, decay = 0.28), 0.5, 0.0, 0.35, 0.1)
        b.at(0.0, thump(0.25, 60.0, 36.0, 0.04, 0.1, 1.3), 0.4)
        Wet(hall = Rv.CRYPT, room = Rv.BOX)
    }
    r.add(S.COFFIN_OPEN, 2.6, -16.5, 2, hitAt = 1.5, fadeOut = 0.8, duckSec = 2.2, duckTo = 0.5) { b ->
        b.at(0.0, creak(1.3, 411, 38.0, 96.0, 430.0, 1150.0, 0.55), 0.85, 0.0, 0.0, 0.3)
        b.at(1.1, thump(0.35, 118.0, 62.0, 0.03, 0.1, 1.9), 0.7, 0.0, 0.1, 0.2)
        b.at(1.1, noiseBurst(0.12, 412, 0.03, lp = 900.0), 0.4, 0.0, 0.0, 0.3)
        b.choir(0.9, minor, 1.7, 0.5, 0.6, attack = 0.5, release = 0.8)
        b.at(1.25, whoosh(1.0, 413, 600.0, 3200.0, 0.9, 0.3, 1.0), 0.5, 0.0, 0.4)
        b.boom(1.5, 0.85, 1.0, seed = 414)
        b.toll(1.5, N.D3, 1.1, 0.4, 0.4, decay = 0.6)
        Wet(hall = Rv.HALL, room = Rv.BOX)
    }
    r.add(S.SEANCE_START, 4.7, -15.0, 1, hitAt = 2.9, fadeOut = 1.1, duckSec = 4.2, duckTo = 0.4) { b ->
        val hit = 2.9
        // whispered chant that builds
        val st = DoubleArray(10) { 0.1 + 0.26 * it }
        val vw = { t: Double -> Vowels.U.mix(Vowels.A, clamp01((t % 0.52) / 0.5)) }
        val amp = { t: Double -> bursts(t, st, 0.05, 0.12, DoubleArray(10) { 0.25 + 0.075 * it }) * (0.3 + 0.7 * clamp01(t / 2.5)) }
        b.at(0.0, whisper(2.8, 421, vw, amp, 380.0, 0.85), 0.9, -0.3, 0.4, 0.1)
        b.at(0.0, whisper(2.8, 422, vw, amp, 380.0, 0.9), 0.7, 0.3, 0.4, 0.1)
        val src = choirChord(doubleArrayOf(N.D3, N.A3, N.D4, N.F4, N.A4), 0.5, 423, 0.2, 0.3, Vowels.O, Vowels.A)
        val sw = reverseSwell(src, Rv.CATHEDRAL, 2.6)
        b.at(hit - sw.size / SRD, sw, 0.9, 0.0, 0.2)
        b.at(0.4, riser(2.45, 424, 250.0, 4200.0, 73.0, 440.0, 0.35, 1.2, 2.3), 0.55, 0.0, 0.3)
        b.drumRoll(1.2, hit - 0.02, 16, 110.0, 0.25, 0.95, 0.3, seed = 425, curve = 1.3)
        b.at(hit, braam(hz(N.D2), 3.2, 426, 4.5, 0.1, 1.3), 0.95, 0.0, 0.25)
        b.boom(hit, 1.2, 1.5)
        b.choir(hit, major, 2.0, 0.6, 0.7, attack = 0.08, release = 1.0, v0 = Vowels.A, v1 = Vowels.O)
        b.at(hit, whisper(0.4, 427, { Vowels.A }, { t -> sin(PI * t / 0.4).pow(0.5) }, 600.0, 0.8), 0.9, 0.0, 0.4)
        b.at(hit, bell(hz(N.D2), 3.0, Bells.GONG, bright = 0.55, decay = 0.7), 0.45, 0.0, 0.4)
        b.toll(hit + 0.45, N.D3, 1.6, 0.45, 0.5, decay = 0.7)
        b.toll(hit + 1.0, N.A2, 1.6, 0.4, 0.5, decay = 0.7)
        Wet(hall = Rv.CATHEDRAL, room = Rv.BOX)
    }
    r.add(S.SEANCE_MULT_UP, 0.62, -19.0, 1, hitAt = 0.0, fadeOut = 0.2) { b ->
        b.horn(0.0, N.D3, 0.32, 0.6, 0.3, attack = 0.02, release = 0.1, cutHi = 3200.0)
        b.at(0.0, bell(hz(N.D4), 0.6, Bells.GLASS, bright = 0.7, decay = 0.45, soft = 0.001), 0.55, 0.0, 0.3, 0.1)
        b.at(0.0, whoosh(0.35, 431, 500.0, 2400.0, 0.9, 0.3, 1.0), 0.4, 0.0, 0.2)
        b.at(0.0, thump(0.3, 100.0, 48.0, 0.03, 0.1, 1.7), 0.7, 0.0, 0.1)
        Wet(hall = Rv.CRYPT)
    }
    r.add(S.SEANCE_RETRIGGER, 1.3, -18.0, 1, hitAt = 0.0, fadeOut = 0.45, duckSec = 1.0, duckTo = 0.55) { b ->
        b.toll(0.0, N.D3, 1.3, 0.6, 0.4, decay = 0.5)
        b.toll(0.32, N.A3, 1.0, 0.45, 0.4, decay = 0.5)
        b.choir(0.3, doubleArrayOf(N.A3, N.D4, N.F4 + 1.0, N.A4), 0.95, 0.45, 0.6, attack = 0.08, release = 0.5)
        b.at(0.0, thump(0.4, 90.0, 42.0, 0.04, 0.15, 1.7), 0.75, 0.0, 0.1)
        b.sparkle(0.35, doubleArrayOf(N.D5, N.Fs5, N.A5), 0.08, 0.14, 0.5, 441)
        Wet(hall = Rv.HALL)
    }
    r.add(S.SEANCE_END, 3.1, -17.0, 1, hitAt = 0.0, fadeOut = 1.1, duckSec = 2.6, duckTo = 0.5) { b ->
        b.toll(0.0, N.D2, 3.1, 0.7, 0.45, decay = 0.9)
        b.boom(0.0, 0.8, 1.2, seed = 451)
        b.choir(0.1, doubleArrayOf(N.D3, N.A3, N.D4, N.A4), 2.2, 0.45, 0.65, attack = 0.1, release = 1.5, v0 = Vowels.A, v1 = Vowels.U)
        b.at(0.2, sweepNoise(2.4, 452, 2600.0, 300.0, 1.0) { p -> sin(PI * p.pow(0.6)).pow(1.3) }, 0.35, 0.0, 0.4)
        b.at(1.2, rumble(1.7, 453, 220.0, 4.0, 0.7) { p -> sin(PI * p) }, 0.25, 0.0, 0.3)
        Wet(hall = Rv.CATHEDRAL)
    }

    // ===================================================================================== tension
    r.add(S.ANTICIPATE, 2.5, -19.0, 1, hitAt = 2.5, fadeOut = 0.05, duckSec = 2.4, duckTo = 0.7) { b ->
        b.at(0.0, riser(2.5, 461, 300.0, 4500.0, 146.8, 440.0, 0.5, 1.5, 2.2), 0.9, 0.0, 0.25)
        var t = 0.15
        var gap = 0.6
        var k = 0
        while (t < 2.4) {
            val g = 0.35 + 0.65 * (t / 2.4)
            b.at(t, thump(0.16, 72.0, 44.0, 0.03, 0.06, 1.6), g, 0.0, 0.1)
            b.at(t + 0.2 * gap / 0.6, thump(0.14, 66.0, 42.0, 0.03, 0.055, 1.6), g * 0.7, 0.0, 0.1)
            t += gap; gap = max(0.28, gap - 0.065); k++
        }
        val src = bell(hz(N.A4), 0.6, Bells.GLASS, bright = 0.6, decay = 0.5)
        val sw = reverseSwell(src, Rv.HALL, 1.6)
        b.at(2.1 - sw.size / SRD + 0.0, sw, 0.5, 0.0, 0.2)
        b.at(1.0, bowed(hz(N.Eb4), 1.5, 462, 1.1, 0.2, flutter = 0.35), 0.18, 0.2, 0.6)
        Wet(hall = Rv.HALL, room = Rv.BOX)
    }
    r.add(S.ANTICIPATE_HIT, 2.1, -15.0, 1, hitAt = 0.0, fadeOut = 0.7, duckSec = 1.8, duckTo = 0.45) { b ->
        b.boom(0.0, 1.1, 1.3, seed = 471)
        b.at(0.0, braam(hz(N.D2), 1.8, 472, 4.0, 0.09, 0.8), 0.6, 0.0, 0.25)
        b.choir(0.0, major, 1.9, 0.6, 0.65, attack = 0.1, release = 0.9)
        b.at(0.0, whoosh(0.7, 473, 3500.0, 500.0, 0.9, 0.3, 1.0), 0.35, 0.0, 0.4)
        b.at(0.0, bell(hz(N.D2), 2.0, Bells.GONG, bright = 0.5, decay = 0.5), 0.35, 0.0, 0.4)
        b.sparkle(0.12, doubleArrayOf(N.D5, N.Fs5, N.A5, N.D6), 0.09, 0.15, 0.5, 474)
        Wet(hall = Rv.HALL)
    }
    r.add(S.ANTICIPATE_MISS, 0.85, -20.0, 1, fadeOut = 0.3) { b ->
        b.at(0.0, thump(0.75, 130.0, 27.0, 0.28, 0.26, 1.5), 1.0, 0.0, 0.1)
        b.at(0.0, sweepNoise(0.6, 481, 1300.0, 140.0, 0.9) { p -> (1 - p).pow(1.5) * smooth(p / 0.05) }, 0.4, 0.0, 0.0, 0.3)
        b.at(0.03, whisper(0.6, 482, { t -> Vowels.U.mix(Vowels.O, t / 0.6) }, { t -> sin(PI * t / 0.6).pow(0.8) * 0.9 }, 350.0, 0.85), 0.3, 0.0, 0.3)
        Wet(hall = Rv.CRYPT)
    }

    // ===================================================================================== features
    r.add(S.WILD_LAND, 0.9, -17.0, 1, hitAt = 0.08, fadeOut = 0.3) { b ->
        b.at(0.0, whoosh(0.28, 491, 4800.0, 800.0, 0.9, 0.2, 1.0), 0.9, 0.0, 0.0, 0.2)
        b.at(0.08, bell(1250.0, 0.6, Bells.GONG, bright = 0.5, decay = 0.2), 0.22, 0.0, 0.0, 0.3)
        b.at(0.08, thump(0.5, 100.0, 36.0, 0.04, 0.16, 2.0), 1.0, 0.0, 0.15)
        b.at(0.08, noiseBurst(0.1, 492, 0.02, lp = 1500.0), 0.5, 0.0, 0.1, 0.3)
        b.at(0.1, formantVoice(0.6, 493, { t -> 90.0 - 25.0 * t }, { Vowels.BASS_A }, { t -> exp(-t * 4.0) }, jitter = 0.07, breath = 0.3, growl = 0.5, drive = 2.4, shift = 0.78, sub = 0.6), 0.45, 0.0, 0.3)
        Wet(hall = Rv.CRYPT, room = Rv.BOX)
    }
    r.add(S.MINI_INTRO, 1.4, -18.0, 2, hitAt = 0.5, fadeOut = 0.5, air = -4.0) { b ->
        b.at(0.0, whoosh(0.6, 501, 300.0, 2600.0, 0.9, 0.7, 1.0), 0.6, 0.0, 0.3)
        b.at(0.35, whisper(0.5, 502, { t -> Vowels.O.mix(Vowels.A, t / 0.5) }, { t -> sin(PI * t / 0.5).pow(1.5) }, 500.0, 0.85), 0.8, 0.0, 0.4)
        b.boom(0.5, 0.5, 0.9, seed = 503)
        b.toll(0.5, N.D3, 1.0, 0.5, 0.4, decay = 0.45, bright = 1.0)
        Wet(hall = Rv.HALL)
    }
    r.add(S.ORB_LAND, 0.6, -21.0, 1, hitAt = 0.0, fadeOut = 0.25) { b ->
        b.at(0.0, bell(hz(N.D4), 0.6, Bells.GLASS, bright = 0.7, decay = 0.4, soft = 0.001), 0.8, 0.0, 0.35, 0.1)
        b.at(0.0, coin(hz(N.D5) * 2.0, 511, 0.6, 0.6), 0.3, 0.0, 0.3)
        b.at(0.0, thump(0.25, 120.0, 65.0, 0.02, 0.08, 1.6), 0.6, 0.0, 0.1)
        Wet(hall = Rv.CRYPT)
    }
    r.add(S.ORB, 0.62, -21.0, 1, hitAt = 0.0, fadeOut = 0.25) { b ->
        b.at(0.0, bell(hz(N.A4), 0.6, Bells.GLASS, bright = 0.7, decay = 0.4, soft = 0.001), 0.8, 0.0, 0.35, 0.1)
        b.at(0.0, coin(hz(N.A5) * 1.5, 521, 0.6, 0.6), 0.3, 0.0, 0.3)
        b.at(0.0, thump(0.25, 110.0, 60.0, 0.02, 0.08, 1.6), 0.55, 0.0, 0.1)
        Wet(hall = Rv.CRYPT)
    }
    r.add(S.VAULT_DOOR, 3.6, -16.0, 2, hitAt = 2.4, fadeOut = 1.0, duckSec = 3.2, duckTo = 0.5) { b ->
        b.at(0.0, rumble(2.5, 531, 260.0, 9.0, 0.95) { p -> smooth(p / 0.08) * (1 - 0.4 * p) }, 0.9, 0.0, 0.2)
        b.at(0.0, creak(2.2, 532, 30.0, 55.0, 260.0, 700.0, 0.4), 0.3, 0.0, 0.0, 0.3)
        b.at(0.1, clinks(2.2, 533, 26.0, 600.0, 3600.0, 0.045) { p -> 0.6 + 0.4 * sin(PI * p) }, 0.6, 0.0, 0.0, 0.3)
        for (k in 0 until 3) b.at(0.35 + 0.5 * k, thump(0.2, 150.0, 70.0, 0.02, 0.07, 2.0), 0.4, (k - 1) * 0.3, 0.0, 0.3)
        b.at(0.35, bell(210.0, 0.8, Bells.GONG, bright = 0.4, decay = 0.2), 0.12, 0.0, 0.0, 0.4)
        b.boom(2.4, 1.2, 1.6, seed = 534)
        b.toll(2.4, N.D2, 2.6, 0.5, 0.5, decay = 0.9)
        b.choir(2.2, doubleArrayOf(N.D3, N.A3, N.D4, N.F4), 1.4, 0.35, 0.6, attack = 0.35, release = 0.9)
        Wet(hall = Rv.CATHEDRAL, room = Rv.BOX)
    }
    r.add(S.VAULT, 3.4, -16.0, 2, hitAt = 2.7, fadeOut = 0.9, duckSec = 3.0, duckTo = 0.5) { b ->
        b.toll(0.0, N.D2, 3.4, 0.8, 0.45, decay = 1.0)
        b.toll(1.1, N.A2, 2.3, 0.5, 0.45, decay = 0.8)
        b.at(0.0, rumble(3.2, 541, 260.0, 4.0, 0.8) { p -> sin(PI * p.pow(0.8)) }, 0.35, 0.0, 0.3)
        val src = choirChord(minor, 0.6, 542, 0.25, 0.3, Vowels.O, Vowels.A)
        val sw = reverseSwell(src, Rv.CATHEDRAL, 2.2)
        b.at(2.7 - sw.size / SRD, sw, 0.9, 0.0, 0.2)
        b.boom(2.7, 1.2, 1.6, seed = 543)
        b.choir(2.7, doubleArrayOf(N.D3, N.A3, N.D4, N.F4, N.A4), 0.7, 0.55, 0.65, attack = 0.1, release = 0.5)
        for (k in 0 until 4) b.at(0.4 + 0.65 * k, thump(0.2, 70.0, 42.0, 0.03, 0.07, 1.6), 0.5 + 0.1 * k, 0.0, 0.1)
        Wet(hall = Rv.CATHEDRAL)
    }
    r.add(S.RELIC, 1.4, -19.0, 1, hitAt = 0.4, fadeOut = 0.5, air = -4.0) { b ->
        val seq = doubleArrayOf(N.A4, N.D5, N.F5, N.A5)
        for ((k, m) in seq.withIndex()) b.at(0.08 * k, bell(hz(m), 0.9, Bells.GLASS, bright = 0.65, decay = 0.55, soft = 0.001), 0.4, (k - 1.5) * 0.3, 0.35, 0.1)
        b.at(0.0, whoosh(0.4, 551, 500.0, 3000.0, 0.9, 0.7, 1.0), 0.35, 0.0, 0.3)
        b.at(0.38, bell(hz(N.D3), 1.0, Bells.CHURCH, bright = 0.6, decay = 0.35), 0.55, 0.0, 0.4)
        b.at(0.38, thump(0.4, 88.0, 40.0, 0.04, 0.15, 1.6), 0.6, 0.0, 0.1)
        b.at(0.2, whisper(0.5, 552, { t -> Vowels.I.mix(Vowels.O, t / 0.5) }, { t -> sin(PI * t / 0.5) * 0.7 }, 700.0, 0.85), 0.25, 0.4, 0.5)
        Wet(hall = Rv.HALL)
    }
    r.add(S.RELIC_FLY, 0.75, -22.0, 1, hitAt = 0.55, fadeOut = 0.25, air = -4.5) { b ->
        b.at(0.0, whoosh(0.6, 561, 400.0, 3400.0, 0.9, 0.85, 1.0), 0.6, 0.0, 0.3)
        b.at(0.55, bell(hz(N.A5), 0.45, Bells.GLASS, bright = 0.6, decay = 0.4, soft = 0.001), 0.5, 0.0, 0.4)
        b.at(0.0, bowed(hz(N.D5), 0.6, 562, 0.5, 0.1, flutter = 0.3), 0.18, 0.0, 0.3)
        Wet(hall = Rv.CRYPT)
    }
    r.add(S.BUY_CONFIRM, 1.6, -17.0, 1, hitAt = 0.5, fadeOut = 0.6, duckSec = 1.2, duckTo = 0.55) { b ->
        for (k in 0 until 6) b.at(0.07 * k, coin(700.0 + 90.0 * (k % 3), 570L + k, 0.5, 1.1), 0.5, (k % 3 - 1) * 0.3, 0.2, 0.2)
        b.at(0.0, thump(0.25, 150.0, 70.0, 0.02, 0.08, 2.0), 0.5)
        b.at(0.5, drum(73.4, 0.8, 580, 0.95, 1.2), 1.0, 0.0, 0.3, 0.2)
        b.horn(0.5, N.D3, 0.8, 0.5, 0.3, attack = 0.03, release = 0.4)
        b.choir(0.5, doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4), 1.0, 0.4, 0.55, attack = 0.1, release = 0.6)
        b.toll(0.5, N.D3, 1.1, 0.45, 0.4, decay = 0.5)
        Wet(hall = Rv.HALL, room = Rv.BOX)
    }
    r.add(S.RANK_UP, 2.4, -17.0, 3, hitAt = 0.9, fadeOut = 0.8, duckSec = 1.8, duckTo = 0.6) { b ->
        val seq = doubleArrayOf(N.D3, N.F3, N.A3, N.D4)
        for ((k, m) in seq.withIndex()) {
            val long = k == 3
            b.horn(0.25 * k, m, if (long) 1.4 else 0.3, 0.5, 0.35, attack = 0.04, release = if (long) 0.7 else 0.08)
        }
        b.choir(0.9, doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4), 1.5, 0.5, 0.6, attack = 0.1, release = 0.8)
        b.toll(0.9, N.D3, 1.8, 0.55, 0.45, decay = 0.8)
        b.at(0.9, thump(0.5, 85.0, 38.0, 0.04, 0.18, 1.6), 0.7, 0.0, 0.1)
        b.sparkle(1.0, doubleArrayOf(N.D5, N.Fs5, N.A5, N.D6), 0.1, 0.14, 0.5, 591)
        Wet(hall = Rv.HALL)
    }
    r.add(S.ACHIEVEMENT, 1.6, -19.0, 3, hitAt = 0.15, fadeOut = 0.6) { b ->
        b.toll(0.0, N.D3, 1.6, 0.6, 0.4, decay = 0.5)
        b.sparkle(0.1, doubleArrayOf(N.A4, N.D5, N.F5, N.A5), 0.1, 0.3, 0.5, 601)
        b.choir(0.15, doubleArrayOf(N.D4, N.A4, N.F4 + 1.0), 1.2, 0.35, 0.55, attack = 0.15, release = 0.7)
        b.at(0.0, thump(0.4, 85.0, 42.0, 0.04, 0.15, 1.5), 0.5, 0.0, 0.1)
        Wet(hall = Rv.HALL)
    }
}

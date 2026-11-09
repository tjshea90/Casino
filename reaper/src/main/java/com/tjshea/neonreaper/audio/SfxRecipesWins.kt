package com.tjshea.neonreaper.audio

import com.tjshea.neonreaper.audio.Audio.Sfx as S
import kotlin.math.exp
import kotlin.math.pow

/**
 * Win stingers. Every tier is "build-up -> big hit -> decaying tail", each tier longer, heavier and wider than the last:
 * sub drop + braam + minor-to-major choir lift + restrained coin / soul shimmer. [hitAt] tells the UI where the impact lands.
 */
internal fun registerWinSfx(r: SfxRegistry) {
    val minor = doubleArrayOf(N.D3, N.A3, N.D4, N.F4)
    val major = doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4)

    // ---------------------------------------------------------------------------------------------- small
    r.add(S.WIN_SMALL, 0.72, -19.0, 0, hitAt = 0.14, fadeOut = 0.24) { b ->
        b.at(0.0, bell(hz(N.D5), 0.5, Bells.GLASS, bright = 0.7, decay = 0.4, soft = 0.001), 0.5, -0.2, 0.2, 0.1)
        b.at(0.07, bell(hz(N.A5), 0.5, Bells.GLASS, bright = 0.6, decay = 0.35, soft = 0.001), 0.42, 0.2, 0.2, 0.1)
        b.at(0.14, bell(hz(N.D3), 0.6, Bells.CHURCH, bright = 0.7, decay = 0.2), 0.8, 0.0, 0.2, 0.05)
        b.at(0.14, thump(0.4, 94.0, 40.0, 0.04, 0.14, 1.7), 0.85, 0.0, 0.1)
        b.at(0.14, noiseBurst(0.08, 5, 0.02, lp = 1800.0), 0.3, 0.0, 0.1, 0.1)
        b.sparkle(0.17, doubleArrayOf(N.A5, N.D6, N.F5 + 12.0), 0.07, 0.14, 0.4, 7)
        Wet(hall = Rv.CRYPT)
    }
    r.add(S.WIN, 1.0, -18.0, 0, hitAt = 0.22, fadeOut = 0.3) { b ->
        val seq = doubleArrayOf(N.D4, N.F4, N.A4)
        for ((k, m) in seq.withIndex()) b.at(0.07 * k, bell(hz(m), 0.6, Bells.GLASS, bright = 0.7, decay = 0.45, soft = 0.001), 0.45, (k - 1) * 0.3, 0.25, 0.1)
        b.at(0.22, bell(hz(N.D3), 0.9, Bells.CHURCH, bright = 0.7, decay = 0.3), 0.8, 0.0, 0.3, 0.05)
        b.at(0.22, thump(0.45, 94.0, 38.0, 0.04, 0.17, 1.7), 0.9, 0.0, 0.1)
        b.choir(0.2, doubleArrayOf(N.D3, N.A3, N.D4), 0.8, 0.3, 0.5, attack = 0.12, release = 0.4)
        b.sparkle(0.25, doubleArrayOf(N.A5, N.D6, N.F5 + 12.0, N.A5 + 12.0), 0.065, 0.14, 0.45, 8)
        Wet(hall = Rv.CRYPT)
    }
    r.add(S.WIN_MED, 1.5, -17.0, 1, hitAt = 0.42, fadeOut = 0.4, duckSec = 1.1, duckTo = 0.6) { b ->
        val seq = doubleArrayOf(N.D4, N.F4, N.A4, N.D5)
        for ((k, m) in seq.withIndex()) b.at(0.09 * k, bell(hz(m), 0.7, Bells.GLASS, bright = 0.7, decay = 0.5, soft = 0.001), 0.42, (k - 1.5) * 0.25, 0.3, 0.1)
        b.choir(0.0, minor, 0.55, 0.3, 0.6, attack = 0.35, release = 0.05)
        b.choir(0.42, major, 1.05, 0.5, 0.6, attack = 0.08, release = 0.6)
        b.horn(0.42, N.D3, 0.6, 0.45, 0.3)
        b.at(0.42, braam(hz(N.D2), 1.1, 3, 3.5, 0.08, 0.5), 0.55, 0.0, 0.25)
        b.boom(0.42, 0.9, 0.5)
        b.toll(0.42, N.D3, 1.0, 0.5, 0.4, decay = 0.4)
        b.coinRain(0.46, 1.0, 7, 1100.0, 2800.0, 0.22, 77, 0.4)
        Wet(hall = Rv.HALL)
    }

    // ---------------------------------------------------------------------------------------------- big
    r.add(S.WIN_BIG, 3.0, -16.0, 1, hitAt = 1.0, fadeOut = 0.7, duckSec = 2.6, duckTo = 0.45) { b ->
        val hit = 1.0
        val src = choirChord(doubleArrayOf(N.D3, N.A3, N.D4, N.F4, N.A4), 0.5, 21, 0.2, 0.3, Vowels.O, Vowels.A)
        val sw = reverseSwell(src, Rv.HALL, 1.5)
        b.at(hit - sw.size / SRD, sw, 0.85, 0.0, 0.2, 0.0)
        b.at(0.05, riser(0.93, 31, 400.0, 3800.0, 150.0, 600.0, 0.3, 1.2, 2.0), 0.5, 0.0, 0.3)
        b.drumRoll(0.3, hit - 0.02, 7, 90.0, 0.3, 0.8, 0.3)
        b.at(hit, braam(hz(N.D2), 2.2, 4, 4.0, 0.09, 0.9), 0.8, 0.0, 0.25)
        b.boom(hit, 1.0, 1.0)
        b.choir(hit, major, 2.0, 0.55, 0.6, attack = 0.12, release = 1.0)
        b.toll(hit, N.D3, 2.0, 0.6, 0.45, decay = 0.7)
        b.coinRain(hit + 0.05, hit + 0.95, 12, 1100.0, 3000.0, 0.26, 78, 0.4)
        b.sparkle(hit + 0.2, doubleArrayOf(N.D5, N.Fs5, N.A5, N.D6), 0.1, 0.18, 0.5, 9)
        Wet(hall = Rv.HALL)
    }
    r.add(S.WIN_MEGA, 4.5, -15.0, 1, hitAt = 1.6, fadeOut = 1.0, duckSec = 4.0, duckTo = 0.4) { b ->
        val hit = 1.6
        val src = choirChord(doubleArrayOf(N.D3, N.A3, N.D4, N.F4, N.Bb4), 0.6, 22, 0.25, 0.35, Vowels.O, Vowels.A)
        val sw = reverseSwell(src, Rv.HALL, 1.9)
        b.at(hit - sw.size / SRD, sw, 0.9, 0.0, 0.2)
        val gs = reverseSwell(bell(hz(N.D2), 1.0, Bells.GONG, bright = 0.5, decay = 0.4), Rv.HALL, 1.6)
        b.at(hit - gs.size / SRD, gs, 0.5, 0.0, 0.2)
        b.at(0.05, riser(1.52, 32, 300.0, 4200.0, 73.0, 400.0, 0.35, 1.2, 2.2), 0.55, 0.0, 0.3)
        b.drumRoll(0.4, hit - 0.02, 12, 82.0, 0.25, 0.9, 0.3)
        b.at(hit, braam(hz(N.D2), 3.0, 5, 4.5, 0.09, 1.1), 0.85, 0.0, 0.25)
        b.at(hit, braam(hz(N.A2), 2.2, 6, 4.0, 0.08, 0.8, fifth = 0.3, octave = 0.3, sub = 0.2), 0.4, 0.0, 0.25)
        b.boom(hit, 1.1, 1.3)
        b.toll(hit, N.D2, 3.2, 0.5, 0.45, decay = 1.0)
        b.toll(hit + 0.02, N.D3, 2.5, 0.45, 0.45, decay = 0.8)
        b.choir(hit, doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4, N.D5), 2.6, 0.6, 0.6, attack = 0.15, release = 1.3)
        val motif = doubleArrayOf(N.D3, N.Fs3, N.A3, N.D4)
        for ((k, m) in motif.withIndex()) b.horn(hit + 0.45 + 0.22 * k, m, if (k == 3) 1.3 else 0.3, 0.42, 0.3, attack = 0.05, release = if (k == 3) 0.6 else 0.08)
        b.coinRain(hit + 0.1, hit + 1.9, 22, 1000.0, 3200.0, 0.26, 79, 0.45)
        b.sparkle(hit + 1.1, doubleArrayOf(N.D5, N.Fs5, N.A5, N.D6, N.Fs5 + 12.0), 0.09, 0.17, 0.5, 10)
        Wet(hall = Rv.HALL)
    }
    r.add(S.WIN_EPIC, 6.5, -14.5, 1, hitAt = 2.4, fadeOut = 1.4, duckSec = 5.8, duckTo = 0.36) { b ->
        val hit = 2.4
        val src = choirChord(doubleArrayOf(N.D3, N.A3, N.D4, N.F4, N.A4, N.Bb4), 0.7, 23, 0.3, 0.4, Vowels.U, Vowels.A)
        val sw = reverseSwell(src, Rv.CATHEDRAL, 2.4)
        b.at(hit - sw.size / SRD, sw, 0.95, 0.0, 0.2)
        val gs = reverseSwell(bell(hz(N.D2), 1.2, Bells.GONG, bright = 0.5, decay = 0.5), Rv.CATHEDRAL, 2.2)
        b.at(hit - gs.size / SRD, gs, 0.55, 0.0, 0.2)
        b.at(0.05, riser(2.32, 33, 250.0, 4500.0, 36.7, 330.0, 0.4, 1.2, 2.4), 0.6, 0.0, 0.3)
        b.drumRoll(0.4, hit - 0.02, 20, 70.0, 0.22, 1.0, 0.3)
        b.at(2.05, noiseBurst(0.5, 34, 0.1, hp = 900.0, lp = 5500.0), 0.5, 0.0, 0.4)           // distant crack
        b.at(hit, braam(hz(N.D2), 3.4, 7, 5.0, 0.1, 1.3), 0.9, 0.0, 0.25)
        b.at(hit, braam(hz(N.D1), 3.0, 8, 3.0, 0.07, 1.1, fifth = 0.2, octave = 0.9, sub = 1.0), 0.6, 0.0, 0.1)
        b.boom(hit, 1.2, 1.6)
        b.toll(hit, N.D2, 4.0, 0.55, 0.5, decay = 1.2)
        b.choir(hit, major, 2.2, 0.55, 0.7, attack = 0.15, release = 1.0)
        val motif = doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4)
        for ((k, m) in motif.withIndex()) {
            val t = hit + 0.65 + 0.24 * k
            b.horn(t, m, if (k == 4) 1.4 else 0.32, 0.4, 0.35, attack = 0.05, release = if (k == 4) 0.6 else 0.08)
            b.horn(t, m - 12.0, if (k == 4) 1.4 else 0.32, 0.3, 0.35, attack = 0.05, release = if (k == 4) 0.6 else 0.08, seed = 9)
        }
        val hit2 = 4.4
        b.at(hit2, braam(hz(N.D2), 3.6, 9, 5.0, 0.1, 1.5), 1.0, 0.0, 0.25)
        b.at(hit2, bell(hz(N.D2), 2.8, Bells.GONG, bright = 0.6, decay = 0.7), 0.5, 0.0, 0.4)
        b.boom(hit2, 1.3, 2.0)
        b.toll(hit2, N.D3, 2.1, 0.5, 0.5, decay = 1.0)
        b.choir(hit2, doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4, N.D5, N.Fs5), 2.2, 0.62, 0.7, attack = 0.1, release = 1.3)
        b.coinRain(hit + 0.1, hit2 + 1.4, 34, 1000.0, 3300.0, 0.25, 80, 0.5)
        b.sparkle(hit2 + 0.2, doubleArrayOf(N.D5, N.Fs5, N.A5, N.D6, N.Fs5 + 12.0), 0.1, 0.17, 0.5, 12)
        Wet(hall = Rv.CATHEDRAL)
    }
    r.add(S.WIN_APOCALYPSE, 9.0, -14.0, 1, hitAt = 3.2, fadeOut = 2.0, duckSec = 8.4, duckTo = 0.3) { b ->
        val hit = 3.2
        val src = choirChord(doubleArrayOf(N.D3, N.A3, N.D4, N.F4, N.A4, N.Bb4, N.D5), 0.8, 24, 0.3, 0.45, Vowels.U, Vowels.A)
        val sw = reverseSwell(src, Rv.VOID, 3.2)
        b.at(hit - sw.size / SRD, sw, 1.0, 0.0, 0.2)
        val gs = reverseSwell(bell(hz(N.D2), 1.4, Bells.GONG, bright = 0.5, decay = 0.6), Rv.VOID, 3.0)
        b.at(hit - gs.size / SRD, gs, 0.6, 0.0, 0.2)
        b.at(0.0, rumble(3.2, 35, 150.0, 5.0, 0.7) { p -> p.pow(2.0) }, 0.7, 0.0, 0.3)
        b.at(0.05, riser(3.1, 36, 200.0, 5000.0, 36.7, 440.0, 0.45, 1.2, 2.6), 0.6, 0.0, 0.3)
        b.drumRoll(0.5, hit - 0.02, 30, 62.0, 0.2, 1.0, 0.3)
        b.at(2.8, noiseBurst(0.6, 37, 0.12, hp = 700.0, lp = 6000.0), 0.55, 0.0, 0.4)
        b.at(hit, braam(hz(N.D2), 4.2, 10, 5.0, 0.11, 1.6), 1.0, 0.0, 0.25)
        b.at(hit, braam(hz(N.D1), 3.6, 11, 3.0, 0.07, 1.3, fifth = 0.2, octave = 0.9, sub = 1.0), 0.7, 0.0, 0.1)
        b.boom(hit, 1.3, 2.0)
        b.toll(hit, N.D2, 5.0, 0.6, 0.55, decay = 1.5)
        b.at(hit, bell(hz(N.D2), 4.0, Bells.GONG, bright = 0.6, decay = 0.9), 0.5, 0.0, 0.4)
        b.choir(hit, major, 2.4, 0.55, 0.7, attack = 0.15, release = 1.1)
        val hit2 = 5.0
        val motif = doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4)
        for ((k, m) in motif.withIndex()) {
            val t = hit + 0.7 + 0.22 * k
            b.horn(t, m, if (k == 4) 0.9 else 0.3, 0.42, 0.35, attack = 0.05, release = 0.1)
            b.horn(t, m - 12.0, if (k == 4) 0.9 else 0.3, 0.3, 0.35, attack = 0.05, release = 0.1, seed = 9)
        }
        b.at(hit2, braam(hz(N.A2), 3.4, 12, 4.5, 0.1, 1.3, fifth = 0.5, octave = 0.4), 0.9, 0.0, 0.25)
        b.boom(hit2, 1.1, 1.6)
        b.toll(hit2, N.A2, 2.6, 0.5, 0.5, decay = 1.0)
        b.choir(hit2, doubleArrayOf(N.A3, N.D4, N.Fs4, N.A4, N.D5), 1.8, 0.55, 0.7, attack = 0.1, release = 0.9)
        val arp = doubleArrayOf(N.D4, N.Fs4, N.A4, N.D5, N.Fs5, N.A5, N.D6)
        for ((k, m) in arp.withIndex()) b.at(5.5 + 0.13 * k, bell(hz(m), 1.4, Bells.GLASS, bright = 0.7, decay = 0.7, beat = 0.002), 0.35 + 0.04 * k, (k - 3) * 0.2, 0.35, 0.3)
        val hit3 = 6.4
        b.at(hit3, braam(hz(N.D2), 5.0, 13, 5.5, 0.12, 2.0), 1.0, 0.0, 0.3)
        b.at(hit3, braam(hz(N.D1), 4.5, 14, 3.0, 0.08, 1.6, fifth = 0.2, octave = 0.9, sub = 1.0), 0.8, 0.0, 0.1)
        b.at(hit3, bell(hz(N.D2), 5.0, Bells.GONG, bright = 0.6, decay = 1.2), 0.6, 0.0, 0.4)
        b.boom(hit3, 1.4, 2.4)
        b.toll(hit3, N.D2, 5.0, 0.6, 0.55, decay = 1.6)
        b.toll(hit3 + 0.02, N.D3, 4.0, 0.5, 0.5, decay = 1.3)
        b.choir(hit3, doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4, N.D5, N.Fs5, N.A5), 3.0, 0.62, 0.8, attack = 0.1, release = 1.8)
        b.coinRain(hit + 0.1, hit3 + 1.6, 46, 1000.0, 3300.0, 0.24, 81, 0.5)
        b.sparkle(hit3 + 0.3, doubleArrayOf(N.D5, N.Fs5, N.A5, N.D6, N.Fs5 + 12.0), 0.12, 0.16, 0.5, 13)
        Wet(hall = Rv.VOID)
    }

    // ------------------------------------------------------------------------------- jackpot stingers
    fun jackpot(s: Audio.Sfx, sec: Double, rms: Double, hit: Double, notes: Int, big: Double, coins: Int, fade: Double, duck: Double) {
        r.add(s, sec, rms, 2, hitAt = hit, fadeOut = fade, duckSec = duck, duckTo = 0.4) { b ->
            val arp = doubleArrayOf(N.D4, N.F4, N.A4, N.D5, N.F5, N.A5, N.D6)
            val step = (hit - 0.05) / notes
            for (k in 0 until notes) b.at(step * k, bell(hz(arp[k % arp.size]), 1.0, Bells.GLASS, bright = 0.7, decay = 0.5, soft = 0.001), 0.36 + 0.03 * k, (k - notes / 2.0) * 0.15, 0.3, 0.1)
            if (big >= 1.0) {
                val src = choirChord(doubleArrayOf(N.D3, N.A3, N.D4, N.F4, N.A4), 0.5, 25, 0.2, 0.3, Vowels.O, Vowels.A)
                val sw = reverseSwell(src, Rv.HALL, 1.2)
                b.at(hit - sw.size / SRD, sw, 0.7, 0.0, 0.2)
                b.drumRoll(max0(hit - 0.7), hit - 0.02, (4 + big * 4).toInt(), 85.0, 0.3, 0.8, 0.3)
            }
            b.at(hit, braam(hz(N.D2), 1.6 + big * 0.9, 20 + big.toLong(), 4.0, 0.09, 0.8 + 0.2 * big), 0.5 + 0.12 * big, 0.0, 0.25)
            b.boom(hit, 0.7 + 0.18 * big, 0.5 + 0.4 * big)
            b.toll(hit, N.D3, 1.4 + big, 0.55, 0.45, decay = 0.6 + 0.2 * big)
            b.choir(hit, if (big >= 2.0) doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4, N.D5) else major, 1.2 + 0.7 * big, 0.45 + 0.04 * big, 0.6, attack = 0.1, release = 0.8)
            if (big >= 2.0) {
                val motif = doubleArrayOf(N.D3, N.Fs3, N.A3, N.D4)
                for ((k, m) in motif.withIndex()) b.horn(hit + 0.45 + 0.2 * k, m, if (k == 3) 1.0 else 0.25, 0.4, 0.3, attack = 0.05, release = 0.1)
            }
            if (big >= 3.0) {
                val hit2 = hit + 1.2
                b.at(hit2, braam(hz(N.D2), 3.0, 30, 4.5, 0.1, 1.3), 0.8, 0.0, 0.25)
                b.at(hit2, bell(hz(N.D2), 3.5, Bells.GONG, bright = 0.6, decay = 0.8), 0.45, 0.0, 0.4)
                b.boom(hit2, 1.1, 1.6)
                b.choir(hit2, doubleArrayOf(N.D3, N.A3, N.D4, N.Fs4, N.A4, N.D5, N.Fs5), 2.4, 0.6, 0.7, attack = 0.1, release = 1.3)
            }
            b.coinRain(hit + 0.05, hit + 0.8 + 0.5 * big, coins, 1000.0, 3200.0, 0.25, 90L + big.toLong(), 0.4)
            b.sparkle(hit + 0.2, doubleArrayOf(N.D5, N.Fs5, N.A5, N.D6), 0.1, 0.16, 0.5, 14)
            Wet(hall = if (big >= 2.0) Rv.CATHEDRAL else Rv.HALL)
        }
    }
    jackpot(S.JACKPOT_MINI, 2.0, -16.5, 0.32, 4, 0.0, 8, 0.5, 1.7)
    jackpot(S.JACKPOT_MINOR, 3.0, -16.0, 0.5, 6, 1.0, 14, 0.8, 2.6)
    jackpot(S.JACKPOT_MAJOR, 4.5, -15.0, 0.8, 7, 2.0, 22, 1.2, 4.0)
    jackpot(S.JACKPOT_GRAND, 6.0, -14.0, 1.2, 7, 3.0, 34, 1.6, 5.5)
    // legacy jackpot ping (vault orb of a jackpot kind): between mini and minor
    jackpot(S.JACKPOT, 2.4, -16.5, 0.36, 5, 0.5, 10, 0.6, 2.0)

    // ----------------------------------------------------------------------------------- legacy wins
    r.add(S.BIG_WIN, 2.8, -16.0, 1, hitAt = 0.7, fadeOut = 0.7, duckSec = 2.4, duckTo = 0.5) { b ->
        val hit = 0.7
        val src = choirChord(minor, 0.4, 26, 0.15, 0.25, Vowels.O, Vowels.A)
        val sw = reverseSwell(src, Rv.HALL, 1.1)
        b.at(hit - sw.size / SRD, sw, 0.8, 0.0, 0.2)
        b.drumRoll(0.2, hit - 0.02, 5, 90.0, 0.3, 0.8, 0.3)
        b.at(hit, braam(hz(N.D2), 2.0, 27, 4.0, 0.09, 0.9), 0.8, 0.0, 0.25)
        b.boom(hit, 1.0, 1.0)
        b.choir(hit, major, 1.8, 0.5, 0.6, attack = 0.1, release = 0.9)
        b.toll(hit, N.D3, 1.8, 0.5, 0.45, decay = 0.7)
        b.coinRain(hit + 0.05, hit + 0.8, 9, 1100.0, 3000.0, 0.24, 28, 0.4)
        Wet(hall = Rv.HALL)
    }
    r.add(S.FANFARE, 2.3, -17.0, 1, hitAt = 0.0, fadeOut = 0.6, duckSec = 2.0, duckTo = 0.5) { b ->
        val motif = doubleArrayOf(N.D3, N.A3, N.D4, N.F4)
        for ((k, m) in motif.withIndex()) {
            b.horn(0.26 * k, m, 0.3, 0.55, 0.3, attack = 0.04, release = 0.08)
            b.horn(0.26 * k, m - 12.0, 0.3, 0.4, 0.3, attack = 0.04, release = 0.08, seed = 8)
        }
        b.at(0.0, thump(0.5, 80.0, 38.0, 0.04, 0.2, 1.6), 0.6, 0.0, 0.1)
        val t = 1.05
        b.horn(t, N.D3, 1.1, 0.6, 0.35, attack = 0.05, release = 0.5)
        b.horn(t, N.A3, 1.1, 0.5, 0.35, attack = 0.05, release = 0.5, seed = 8)
        b.horn(t, N.D4, 1.1, 0.5, 0.35, attack = 0.05, release = 0.5, seed = 9)
        b.horn(t, N.Fs4, 1.1, 0.42, 0.35, attack = 0.05, release = 0.5, seed = 10)
        b.choir(t, major, 1.2, 0.4, 0.6, attack = 0.1, release = 0.6)
        b.boom(t, 0.8, 0.8)
        b.toll(t, N.D3, 1.2, 0.4, 0.4, decay = 0.6)
        Wet(hall = Rv.HALL)
    }
    r.add(S.COINS, 0.9, -21.0, 1, fadeOut = 0.25) { b ->
        b.coinRain(0.0, 0.5, 9, 800.0, 2300.0, 0.4, 51, 0.35, dark = 0.5, spread = 0.6) { p -> 1.0 - 0.5 * p }
        b.at(0.0, noiseBurst(0.3, 52, 0.08, lp = 700.0), 0.3, 0.0, 0.1, 0.2)
        b.at(0.0, thump(0.15, 140.0, 80.0, 0.02, 0.05), 0.3)
        Wet(hall = Rv.ROOM)
    }
    r.add(S.COIN_SHOWER, 3.0, -20.0, 1, fadeOut = 0.7) { b ->
        b.coinRain(0.0, 2.6, 78, 900.0, 3000.0, 0.34, 53, 0.4, dark = 0.55, spread = 0.9) { p -> if (p < 0.12) 0.6 + 4.0 * p else exp(-2.2 * (p - 0.12)).coerceIn(0.08, 1.0) }
        for (k in 0 until 7) b.at(0.05 + 0.3 * k, noiseBurst(0.25, 60L + k, 0.06, lp = 800.0), 0.2, (k % 3 - 1) * 0.5, 0.1, 0.2)
        b.at(0.0, rumble(2.0, 54, 220.0, 9.0, 0.7) { p -> (1 - p).pow(1.5) }, 0.25, 0.0, 0.15)
        Wet(hall = Rv.CRYPT)
    }
}

private fun max0(x: Double) = if (x < 0.0) 0.0 else x

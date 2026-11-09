package com.tjshea.neonreaper.audio

import com.tjshea.neonreaper.audio.Audio.Sfx as S
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** UI, reels and counting: the sounds heard every few hundred milliseconds, so short, dry-ish and un-fatiguing. */
internal fun registerCoreSfx(r: SfxRegistry) {

    // ------------------------------------------------------------------------------------------------ UI
    r.add(S.CLICK, 0.09, -24.0, 0, stereo = false, fadeOut = 0.02) { b ->
        b.at(0.0, bandBurst(0.035, 1, 2400.0, 2.5, 0.006), 0.8, room = 0.12)
        b.at(0.0, thump(0.05, 520.0, 310.0, 0.012, 0.02), 0.55)
        Wet(room = Rv.TINY)
    }
    r.add(S.PRESS, 0.24, -21.0, 0, fadeOut = 0.05) { b ->
        b.at(0.0, thump(0.14, 270.0, 120.0, 0.02, 0.05, 1.8), 0.9, room = 0.15)
        b.at(0.0, thump(0.12, 70.0, 50.0, 0.02, 0.06), 0.25)
        b.at(0.0, bandBurst(0.03, 2, 3000.0, 1.6, 0.005), 0.5, room = 0.1)
        b.at(0.012, bell(1650.0, 0.2, Bells.BLOCK, decay = 0.8), 0.22, room = 0.3)
        Wet(room = Rv.BOX)
    }
    r.add(S.STAKE_CHANGE, 0.12, -24.0, 0, stereo = false, fadeOut = 0.03) { b ->
        b.at(0.0, bell(hz(N.D4), 0.11, Bells.MALLET, decay = 0.7), 0.9, room = 0.15)
        b.at(0.0, bandBurst(0.012, 3, 3200.0, 3.0, 0.003), 0.4)
        Wet(room = Rv.TINY)
    }
    r.add(S.UI_OPEN, 0.42, -23.0, 0, fadeOut = 0.12) { b ->
        b.at(0.0, rumble(0.38, 4, 420.0, 12.0, 0.8) { p -> sin(PI * p).pow(0.7) }, 0.7, room = 0.1)
        b.at(0.0, whoosh(0.38, 5, 350.0, 1700.0, 0.8, 0.45), 0.4, room = 0.2)
        b.at(0.26, thump(0.14, 130.0, 70.0, 0.02, 0.06, 1.4), 0.45)
        Wet(room = Rv.BOX)
    }
    r.add(S.UI_CLOSE, 0.32, -24.0, 0, fadeOut = 0.08) { b ->
        b.at(0.0, whoosh(0.28, 6, 1500.0, 320.0, 0.8, 0.55), 0.35, room = 0.2)
        b.at(0.0, rumble(0.26, 14, 380.0, 12.0, 0.8) { p -> sin(PI * p).pow(0.8) }, 0.5, room = 0.1)
        b.at(0.2, thump(0.1, 150.0, 80.0, 0.015, 0.045, 1.4), 0.55)
        Wet(room = Rv.BOX)
    }

    // ----------------------------------------------------------------------------------------- reels
    r.add(S.SPIN_START, 0.55, -18.0, 0, fadeOut = 0.07, hitAt = 0.0) { b ->
        b.at(0.0, thump(0.3, 88.0, 38.0, 0.04, 0.12, 2.0), 0.85, room = 0.15)
        val ticks = doubleArrayOf(0.0, 0.05, 0.095, 0.135, 0.17)
        for ((k, t) in ticks.withIndex()) b.at(t, bandBurst(0.02, 10L + k, 1800.0 + k * 220.0, 2.0, 0.005), 0.4 + 0.1 * k, room = 0.1)
        b.at(0.03, clinks(0.35, 7, 70.0, 900.0, 3800.0, 0.02), 0.32, room = 0.2)
        b.at(0.06, riser(0.46, 8, 220.0, 1500.0, 70.0, 175.0, 0.4, 0.9, 0.9), 0.55, room = 0.15)
        Wet(room = Rv.BOX)
    }
    r.add(S.SPIN, 0.42, -22.0, 0, fadeOut = 0.1) { b ->
        b.at(0.0, whoosh(0.4, 9, 150.0, 950.0, 0.9, 0.5), 0.8, room = 0.15)
        b.at(0.02, clinks(0.3, 21, 45.0, 900.0, 3000.0, 0.02), 0.22, room = 0.2)
        b.at(0.0, thump(0.18, 70.0, 45.0, 0.03, 0.08), 0.4)
        Wet(room = Rv.BOX)
    }
    r.add(S.STOP, 0.3, -20.0, 0, fadeOut = 0.08) { b ->
        b.at(0.0, thump(0.26, 112.0, 52.0, 0.025, 0.075, 1.8), 0.6, room = 0.15)
        b.at(0.0, thump(0.14, 330.0, 170.0, 0.02, 0.05, 2.2), 0.9, room = 0.2)
        b.at(0.0, bandBurst(0.03, 12, 900.0, 1.6, 0.008), 0.55, room = 0.1)
        b.at(0.0, bell(520.0, 0.12, Bells.BLOCK, decay = 0.8), 0.25, room = 0.2)
        Wet(room = Rv.BOX)
    }
    r.add(S.REEL_THUD, 0.48, -17.0, 0, fadeOut = 0.1, hitAt = 0.0) { b ->
        b.at(0.0, thump(0.45, 96.0, 33.0, 0.05, 0.17, 2.0), 1.0, room = 0.2)
        b.at(0.0, thump(0.3, 165.0, 82.0, 0.03, 0.09, 2.2), 0.6, room = 0.2)
        b.at(0.0, noiseBurst(0.07, 13, 0.02, lp = 1100.0), 0.5, room = 0.3)
        b.at(0.004, bell(410.0, 0.34, Bells.GONG, bright = 0.5, decay = 0.1), 0.2, room = 0.4)
        Wet(room = Rv.ROOM)
    }

    // --------------------------------------------------------------------------------- counting ticks
    r.add(S.WIN_TICK, 0.12, -24.0, 0, stereo = false, fadeOut = 0.03) { b ->
        b.at(0.0, bell(hz(N.D4), 0.11, Bells.MALLET, decay = 1.0, soft = 0.0008), 0.9, room = 0.12)
        b.at(0.0, bandBurst(0.01, 31, 2600.0, 2.0, 0.003), 0.18)
        Wet(room = Rv.TINY)
    }
    r.add(S.TICK, 0.09, -24.0, 0, stereo = false, fadeOut = 0.02) { b ->
        b.at(0.0, bell(hz(N.D5), 0.08, Bells.MALLET, decay = 0.7, soft = 0.0006), 0.9, room = 0.1)
        b.at(0.0, bandBurst(0.008, 32, 3400.0, 2.5, 0.0025), 0.3)
        Wet(room = Rv.TINY)
    }
    r.add(S.COUNT_END, 0.95, -19.0, 0, fadeOut = 0.25, hitAt = 0.0) { b ->
        b.toll(0.0, N.D3, 0.9, 0.8, 0.35, decay = 0.28, bright = 0.7)
        b.at(0.0, thump(0.5, 75.0, 40.0, 0.04, 0.2, 1.5), 0.5, hall = 0.1)
        b.sparkle(0.03, doubleArrayOf(N.A5, N.D6, N.F5 + 12.0), 0.07, 0.16, 0.5, 91)
        Wet(hall = Rv.HALL)
    }
    r.add(S.ERROR, 0.36, -21.0, 0, fadeOut = 0.08) { b ->
        b.at(0.0, thump(0.3, 190.0, 80.0, 0.03, 0.09, 1.8), 0.6, room = 0.2)
        b.at(0.0, brass(hz(N.D3), 0.3, 41, 0.01, 0.1, 300.0, 1500.0, 3, 0.003, 2.0), 0.8, room = 0.15)
        b.at(0.0, brass(hz(N.Eb3), 0.3, 42, 0.01, 0.1, 300.0, 1500.0, 3, 0.003, 2.0), 0.7, room = 0.15)
        b.at(0.0, bandBurst(0.03, 43, 900.0, 2.0, 0.008), 0.35)
        Wet(room = Rv.BOX)
    }
}

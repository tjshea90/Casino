package com.tjshea.neonreaper.audio

/**
 * Static description of every rendered asset: loudness targets, nominal lengths, lazy-render priority, auto-duck.
 * Recipes ([SfxRecipes]) and music scores ([Scores]) are registered next to their spec so a sound is defined in one place.
 */

/** Reverb presets shared by the recipes (all dark: the in-loop damping keeps the tails warm). */
internal object Rv {
    val TINY = ReverbSpec(0.22, size = 0.45, damp = 5200.0, pre = 0.002, hp = 200.0, stages = 2)
    val BOX = ReverbSpec(0.45, size = 0.7, damp = 4600.0, pre = 0.004, hp = 180.0, stages = 2)
    val ROOM = ReverbSpec(0.8, size = 0.95, damp = 4000.0, pre = 0.008, stages = 2)
    val CRYPT = ReverbSpec(1.7, size = 1.3, damp = 3100.0, pre = 0.014, stages = 3)
    val HALL = ReverbSpec(3.0, size = 1.55, damp = 2700.0, pre = 0.02)
    val CATHEDRAL = ReverbSpec(5.2, size = 2.05, damp = 2300.0, pre = 0.032)
    val VOID = ReverbSpec(7.5, size = 2.3, damp = 2000.0, pre = 0.04, hp = 160.0)
}

/** Which reverbs a one-shot recipe wants applied to its sends (send 0 = hall, send 1 = room). */
internal class Wet(val hall: ReverbSpec? = null, val room: ReverbSpec? = null)

/**
 * @property sec nominal length of the rendered file (tails included).
 * @property rms target whole-file RMS in dBFS (the mastering stage scales to this and limits peaks to -1.5 dBFS).
 * @property tier lazy-render priority: 0 = UI / core, 1 = win + reel feedback, 2 = features, 3 = rest.
 * @property hitAt time of the main impact (seconds): the UI can sync flashes / banners to it.
 * @property duckSec auto-duck of the music while this plays (0 = none), duckTo = music level during the duck.
 */
internal class SfxSpec(
    val sfx: Audio.Sfx, val sec: Double, val rms: Double, val tier: Int, val stereo: Boolean = true,
    val hitAt: Double = 0.0, val duckSec: Double = 0.0, val duckTo: Double = 1.0, val fadeOut: Double = 0.06,
    val minSec: Double = 0.0, val maxSec: Double = 0.0, val bass: Double = -3.0, val air: Double = -3.0
)

internal class SfxDef(val spec: SfxSpec, val build: (Bus) -> Wet)

/** Lazy registry of every SFX recipe. */
internal object SfxBank {
    val defs: Map<Audio.Sfx, SfxDef> by lazy {
        val m = LinkedHashMap<Audio.Sfx, SfxDef>()
        val reg = SfxRegistry(m)
        registerCoreSfx(reg)
        registerWinSfx(reg)
        registerWorldSfx(reg)
        m
    }

    fun spec(s: Audio.Sfx): SfxSpec? = defs[s]?.spec

    /** Renders one effect to 16-bit PCM (deterministic). */
    fun render(s: Audio.Sfx): Pcm {
        val d = defs[s] ?: throw IllegalArgumentException("no recipe for $s")
        val sp = d.spec
        val bus = Bus(frames(sp.sec))
        val wet = d.build(bus)
        val st = bus.render(wet.hall, wet.room)
        Master.removeDc(st)
        Master.eq(st, sp.bass, sp.air)
        Master.fades(st, 0.004, sp.fadeOut)
        if (!sp.stereo) Master.monoize(st)
        Master.toLoudness(st, sp.rms, -1.5)
        Master.fades(st, 0.004, 0.004)
        return Master.toPcm(st, sp.stereo)
    }
}

internal class SfxRegistry(private val m: MutableMap<Audio.Sfx, SfxDef>) {
    fun add(sfx: Audio.Sfx, sec: Double, rms: Double, tier: Int, stereo: Boolean = true, hitAt: Double = 0.0, duckSec: Double = 0.0,
            duckTo: Double = 1.0, fadeOut: Double = 0.06, minSec: Double = sec * 0.8, maxSec: Double = sec * 1.25,
            bass: Double = -3.0, air: Double = -3.0, build: (Bus) -> Wet) {
        m[sfx] = SfxDef(SfxSpec(sfx, sec, rms, tier, stereo, hitAt, duckSec, duckTo, fadeOut, minSec, maxSec, bass, air), build)
    }
}

/** Music layer description. [stereo] layers carry a true stereo reverb; mono layers get widened at playback. */
internal class LayerSpec(val track: Audio.Track, val index: Int, val name: String, val rms: Double, val stereo: Boolean, val width: Float = 0.5f)

/** Track-level description: loop length and when each layer fades in (cumulative with intensity). */
internal class TrackSpec(
    val track: Audio.Track, val bpm: Double, val bars: Int, val beatsPerBar: Int,
    /** intensity at which layer k starts to fade in (layer 0 always on) */
    val fadeStart: FloatArray, /** intensity span over which it reaches full level */ val fadeSpan: FloatArray,
    val layers: List<LayerSpec>
) {
    val loopFrames: Int get() = Math.round(bars * beatsPerBar * 60.0 / bpm * SR).toInt()
    val loopSeconds: Double get() = loopFrames.toDouble() / SR
}

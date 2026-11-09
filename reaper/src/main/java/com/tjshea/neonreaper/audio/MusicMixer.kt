package com.tjshea.neonreaper.audio

import com.tjshea.neonreaper.audio.Audio.Track
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** One decoded loop layer (mono layers are widened at playback by [width] 0..1). */
internal class MixLayer(@JvmField val l: ShortArray, @JvmField val r: ShortArray?, @JvmField val width: Float) {
    val frames: Int get() = l.size
}

/** All layers of one track; layers may arrive one by one (lazy rendering) and are picked up by the mixer at the next block. */
internal class MixTrack(val spec: TrackSpec) {
    val layers = AtomicReferenceArray<MixLayer?>(spec.layers.size)
    val frames: Int = spec.loopFrames
    fun put(i: Int, layer: MixLayer) { layers.set(i, layer) }
    fun has(i: Int): Boolean = layers.get(i) != null
    fun complete(): Boolean { for (i in 0 until layers.length()) if (layers.get(i) == null) return false; return true }
    val track: Track get() = spec.track
}

/**
 * Real-time music mixer (pure Kotlin, no allocation in [render]): sample-aligned layers share one read position, layer gains follow
 * the adaptive intensity with slow one-pole smoothing, tracks cross-fade, [duck] lowers everything with a fast attack / slow release.
 * Control methods are thread-safe; [render] is called from the single audio thread and writes interleaved stereo 16-bit.
 */
internal class MusicMixer {
    // ------------------------------------------------------------------ control (any thread)
    @Volatile var intensity = 0f
    @Volatile var volume = 1f
    @Volatile var paused = false
    @Volatile var currentTrack: Track? = null; private set

    @Volatile private var request: MixTrack? = null
    @Volatile private var requestSeq = 0
    @Volatile private var duckSeq = 0
    @Volatile private var duckTo = 1f
    @Volatile private var duckSec = 0f

    /** Cross-fades to [t] (null = fade out to silence). */
    fun setTrack(t: MixTrack?) { request = t; requestSeq++ }

    fun duck(seconds: Float, to: Float) { duckTo = to.coerceIn(0f, 1f); duckSec = seconds.coerceAtLeast(0f); duckSeq++ }

    /** True when paused and the fade-out has finished (the audio thread may park). */
    fun silentAndPaused(): Boolean = paused && pauseGain < 0.002f

    // ------------------------------------------------------------------ audio-thread state
    private class Slot(val track: MixTrack) {
        var pos = 0
        val gain = FloatArray(track.layers.length())
        val apL = FloatArray(track.layers.length() * 2)     // widener allpass state per layer: x1, y1 (left chain)
        val apR = FloatArray(track.layers.length() * 2)
    }

    private var cur: Slot? = null
    private var prev: Slot? = null
    private var xf = 1f                  // 0..1 cross-fade progress (cur gain = sin, prev gain = cos)
    private var xfRate = 0f
    private var seenRequest = 0
    private var seenDuck = 0
    private var duckGain = 1f
    private var duckTarget = 1f
    private var duckHold = 0f
    private var pauseGain = 0f
    private var masterPrev = 0f
    private var volSmooth = 1f
    private val accL = FloatArray(BLOCK)
    private val accR = FloatArray(BLOCK)

    /** Renders [frames] stereo frames into [out] (interleaved L,R). */
    fun render(out: ShortArray, frames: Int) {
        var done = 0
        while (done < frames) {
            val nb = min(BLOCK, frames - done)
            block(out, done, nb)
            done += nb
        }
    }

    private fun block(out: ShortArray, o: Int, nb: Int) {
        val dt = nb.toFloat() / SR
        // ---- requests
        if (seenRequest != requestSeq) {
            seenRequest = requestSeq
            val t = request
            if (t == null || cur?.track !== t) {
                prev = cur
                cur = if (t == null) null else Slot(t)
                currentTrack = t?.track
                xf = 0f
                xfRate = 1f / (if (prev == null) FADE_IN else FADE_X)
            }
        }
        if (seenDuck != duckSeq) {
            seenDuck = duckSeq
            if (duckHold > 0f) { duckTarget = min(duckTarget, duckTo); duckHold = max(duckHold, duckSec) } else { duckTarget = duckTo; duckHold = duckSec }
        }
        if (duckHold > 0f) { duckHold -= dt; if (duckHold <= 0f) { duckHold = 0f; duckTarget = 1f } }
        val duckA = 1f - exp(-dt / (if (duckTarget < duckGain) DUCK_ATTACK else DUCK_RELEASE))
        duckGain += (duckTarget - duckGain) * duckA
        val pauseTarget = if (paused) 0f else 1f
        pauseGain += (pauseTarget - pauseGain) * (1f - exp(-dt / (if (paused) 0.02f else 0.08f)))
        volSmooth += (volume - volSmooth) * (1f - exp(-dt / 0.05f))
        if (xf < 1f) { xf = min(1f, xf + xfRate * dt); if (xf >= 1f) prev = null }

        java.util.Arrays.fill(accL, 0, nb, 0f); java.util.Arrays.fill(accR, 0, nb, 0f)
        val c = cur
        if (c != null) mixSlot(c, nb, sin(xf * HALF_PI))
        val p = prev
        if (p != null) mixSlot(p, nb, kotlin.math.cos(xf * HALF_PI))

        // ---- master
        val m1 = volSmooth * duckGain * pauseGain * OUT_TRIM
        val m0 = masterPrev
        val step = (m1 - m0) / nb
        var m = m0
        for (j in 0 until nb) {
            m += step
            var l = accL[j] * m; var r = accR[j] * m
            if (l > 1f) l = 1f else if (l < -1f) l = -1f
            if (r > 1f) r = 1f else if (r < -1f) r = -1f
            out[(o + j) * 2] = (l * 32767f).toInt().toShort()
            out[(o + j) * 2 + 1] = (r * 32767f).toInt().toShort()
        }
        masterPrev = m1
    }

    private fun mixSlot(s: Slot, nb: Int, slotGain: Float) {
        val t = s.track; val spec = t.spec; val frames = t.frames
        val inten = intensity
        val dt = nb.toFloat() / SR
        for (k in 0 until t.layers.length()) {
            val layer = t.layers.get(k) ?: continue
            if (layer.frames != frames) continue
            val tgt = layerTarget(spec, k, inten)
            val g0 = s.gain[k]
            val a = 1f - exp(-dt / (if (tgt > g0) TAU_UP else TAU_DOWN))
            val g1 = g0 + (tgt - g0) * a
            s.gain[k] = g1
            val ga = g0 * slotGain; val gb = g1 * slotGain
            if (ga < 1e-5f && gb < 1e-5f) continue
            val step = (gb - ga) / nb
            var g = ga
            var pos = s.pos
            val l = layer.l; val r = layer.r
            if (r != null) {
                for (j in 0 until nb) {
                    g += step
                    accL[j] += l[pos] * g * INV16; accR[j] += r[pos] * g * INV16
                    if (++pos == frames) pos = 0
                }
            } else {
                val w = layer.width
                val wa = 0.55f; val i0 = k * 2
                var xl = s.apL[i0]; var yl = s.apL[i0 + 1]; var xr = s.apR[i0]; var yr = s.apR[i0 + 1]
                for (j in 0 until nb) {
                    g += step
                    val x = l[pos] * INV16
                    val ol = -wa * x + xl + wa * yl; xl = x; yl = ol          // allpass a=+0.55
                    val or = wa * x + xr - wa * yr; xr = x; yr = or           // allpass a=-0.55
                    accL[j] += (x + w * (ol - x)) * g; accR[j] += (x + w * (or - x)) * g
                    if (++pos == frames) pos = 0
                }
                s.apL[i0] = xl; s.apL[i0 + 1] = yl; s.apR[i0] = xr; s.apR[i0 + 1] = yr
            }
        }
        s.pos = (s.pos + nb) % frames
    }

    companion object {
        const val BLOCK = 64
        private const val INV16 = 1f / 32768f
        private const val HALF_PI = (PI / 2).toFloat()
        private const val OUT_TRIM = 0.95f
        const val TAU_UP = 1.3f          // layer fade-in time constant (s)
        const val TAU_DOWN = 2.2f        // layer fade-out time constant (s)
        const val FADE_X = 1.5f          // track cross-fade (s)
        const val FADE_IN = 1.2f
        const val DUCK_ATTACK = 0.05f    // ~150 ms to 95 %
        const val DUCK_RELEASE = 0.2f    // ~600 ms to 95 %

        /** Target gain of layer [k] at adaptive intensity [i] (layer 0 is always on; later layers fade in cumulatively). */
        fun layerTarget(spec: TrackSpec, k: Int, i: Float): Float {
            if (k == 0 || k >= spec.fadeStart.size) return 1f
            val start = spec.fadeStart[k]; val span = spec.fadeSpan[k]
            if (span <= 0f) return if (i >= start) 1f else 0f
            val x = ((i - start) / span).coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }
    }
}

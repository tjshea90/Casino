package com.tjshea.dragonhoard.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * All sound effects are synthesized on first launch into small WAV files in the
 * cache dir and played through SoundPool (low latency, overlapping playback).
 * No audio assets ship in the APK.
 */
class SoundFx(context: Context) {
    enum class Sfx { CLICK, SPIN, STOP, TICK, WIN, BIG_WIN, SCATTER, TEASE, FEATURE, COIN, ERROR }

    var enabled = true
    private val pool = SoundPool.Builder().setMaxStreams(8).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = IntArray(Sfx.values().size)
    private val dir = File(context.cacheDir, "sfx_v1").apply { mkdirs() }

    init {
        Thread {
            for (s in Sfx.values()) {
                val f = File(dir, "${s.name}.wav")
                if (!f.exists() || f.length() < 100) writeWav(f, synth(s))
                ids[s.ordinal] = pool.load(f.path, 1)
            }
        }.start()
    }

    fun play(s: Sfx, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled) return
        val id = ids[s.ordinal]
        if (id != 0) pool.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun release() = pool.release()

    // ------------------------------------------------------------------ synthesis
    private val sr = 22050

    private fun synth(s: Sfx): FloatArray = when (s) {
        Sfx.CLICK -> tone(0.04, 1400.0, decay = 90.0, square = true, vol = 0.35)
        Sfx.SPIN -> sweep(0.35, 200.0, 900.0, 0.25)
        Sfx.STOP -> mix(tone(0.12, 110.0, decay = 35.0, vol = 0.8), noise(0.05, 0.25, 60.0))
        Sfx.TICK -> tone(0.05, 2600.0, decay = 70.0, vol = 0.35)
        Sfx.COIN -> mix(tone(0.18, 1975.0, decay = 18.0, vol = 0.4), tone(0.18, 2637.0, decay = 22.0, vol = 0.3))
        Sfx.WIN -> arpeggio(doubleArrayOf(523.25, 659.25, 783.99, 1046.5), 0.09, 0.3)
        Sfx.BIG_WIN -> concat(
            arpeggio(doubleArrayOf(392.0, 523.25, 659.25, 783.99, 1046.5, 1318.5), 0.08, 0.3),
            chord(doubleArrayOf(523.25, 659.25, 783.99, 1046.5), 0.9, 0.22)
        )
        Sfx.SCATTER -> mix(tone(0.5, 880.0, decay = 6.0, vol = 0.35), tone(0.5, 1320.0, decay = 8.0, vol = 0.25),
            tone(0.5, 1760.0, decay = 10.0, vol = 0.18))
        Sfx.TEASE -> tremolo(sweep(1.2, 300.0, 1200.0, 0.22), 14.0)
        Sfx.FEATURE -> concat(gong(1.4), arpeggio(doubleArrayOf(587.33, 739.99, 880.0, 1174.66, 1479.98), 0.1, 0.3))
        Sfx.ERROR -> concat(tone(0.12, 220.0, square = true, vol = 0.25), tone(0.18, 165.0, square = true, vol = 0.25))
    }

    private fun n(sec: Double) = (sec * sr).toInt()

    private fun tone(sec: Double, f: Double, decay: Double = 4.0, square: Boolean = false, vol: Double = 0.5): FloatArray {
        val out = FloatArray(n(sec))
        for (i in out.indices) {
            val t = i.toDouble() / sr
            var v = sin(2 * PI * f * t)
            if (square) v = if (v >= 0) 0.6 else -0.6
            val attack = (i / (sr * 0.004)).coerceAtMost(1.0)
            out[i] = (v * vol * exp(-decay * t) * attack).toFloat()
        }
        return out
    }

    private fun sweep(sec: Double, f0: Double, f1: Double, vol: Double): FloatArray {
        val out = FloatArray(n(sec)); var ph = 0.0
        for (i in out.indices) {
            val p = i.toDouble() / out.size
            ph += 2 * PI * (f0 + (f1 - f0) * p) / sr
            val env = sin(PI * p)
            out[i] = (sin(ph) * vol * env).toFloat()
        }
        return out
    }

    private fun noise(sec: Double, vol: Double, decay: Double): FloatArray {
        val r = java.util.Random(7); val out = FloatArray(n(sec))
        for (i in out.indices) out[i] = ((r.nextDouble() * 2 - 1) * vol * exp(-decay * i / sr)).toFloat()
        return out
    }

    private fun tremolo(a: FloatArray, hz: Double): FloatArray {
        for (i in a.indices) a[i] *= (0.6 + 0.4 * sin(2 * PI * hz * i / sr)).toFloat()
        return a
    }

    private fun arpeggio(fs: DoubleArray, step: Double, vol: Double): FloatArray {
        val out = FloatArray(n(step * fs.size + 0.35))
        fs.forEachIndexed { k, f ->
            val t = tone(0.4, f, decay = 7.0, vol = vol)
            val off = n(step * k)
            for (i in t.indices) if (off + i < out.size) out[off + i] += t[i]
        }
        return out
    }

    private fun chord(fs: DoubleArray, sec: Double, vol: Double): FloatArray =
        mix(*fs.map { tone(sec, it, decay = 2.5, vol = vol) }.toTypedArray())

    private fun gong(sec: Double): FloatArray = mix(
        tone(sec, 98.0, decay = 2.0, vol = 0.5), tone(sec, 147.0, decay = 2.5, vol = 0.3),
        tone(sec, 233.0, decay = 3.0, vol = 0.2), tone(sec, 311.0, decay = 4.0, vol = 0.12)
    )

    private fun mix(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.maxOf { it.size })
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }

    private fun concat(a: FloatArray, b: FloatArray): FloatArray = a + b

    private fun writeWav(f: File, data: FloatArray) {
        val pcm = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in data) pcm.putShort((v.coerceIn(-1f, 1f) * 32000).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2)
            .putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.capacity())
        FileOutputStream(f).use { it.write(h.array()); it.write(pcm.array()) }
    }
}

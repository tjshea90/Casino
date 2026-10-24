package com.tjshea.neonreaper.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * All audio is synthesized on first launch into WAV files in the cache dir
 * (short effects → SoundPool, the two music loops → MediaPlayer). Nothing ships
 * in the APK. Music is composed in code: pads, sub bass, music-box melody,
 * ghost choir and taiko drums, all through a Freeverb-style reverb.
 */
class Audio(context: Context) {
    enum class Sfx {
        CLICK, SPIN, STOP, WIN, WIN_MED, BIG_WIN, THUNDER, RELIC, VAULT, ORB, HEARTBEAT, JACKPOT, GROWL, SLASH, TICK,
        ERROR, BELL, LAUGH, CACKLE, GIGGLE, CAW, CHATTER, WHOOSH, COINS
    }
    enum class Track { BASE, VAULT }

    var sfxOn = true
    var musicOn = true
        set(v) { field = v; if (v) resumeMusic() else pauseMusic() }

    private val pool = SoundPool.Builder().setMaxStreams(12).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = IntArray(Sfx.values().size)
    private val dir = File(context.cacheDir, "audio_v2").apply { mkdirs() }
    private var player: MediaPlayer? = null
    private var track: Track? = null
    @Volatile private var musicReady = false
    private var wantTrack = Track.BASE
    private val sr = 22050

    init {
        File(context.cacheDir, "audio_v1").deleteRecursively()
        Thread {
            for (s in Sfx.values()) {
                val f = File(dir, "${s.name}.wav")
                if (!f.exists() || f.length() < 100) writeWav(f, master(synth(s), 0.92f))
                ids[s.ordinal] = pool.load(f.path, 1)
            }
            for (t in Track.values()) {
                val f = File(dir, "music_${t.name}.wav")
                if (!f.exists() || f.length() < 100) writeWav(f, if (t == Track.BASE) baseMusic() else vaultMusic())
            }
            musicReady = true
            android.os.Handler(android.os.Looper.getMainLooper()).post { playTrack(wantTrack) }
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    fun play(s: Sfx, volume: Float = 1f, rate: Float = 1f) {
        if (!sfxOn) return
        val id = ids[s.ordinal]
        if (id != 0) pool.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun playTrack(t: Track) {
        wantTrack = t
        if (!musicReady) return
        if (track == t && player != null) { resumeMusic(); return }
        player?.release()
        player = try {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                setDataSource(File(dir, "music_${t.name}.wav").path)
                isLooping = true
                setVolume(0.45f, 0.45f)
                prepare()
            }
        } catch (e: Exception) { null }
        track = t
        resumeMusic()
    }

    private fun resumeMusic() { if (musicOn) try { player?.start() } catch (_: Exception) {} }
    fun pauseMusic() { try { player?.pause() } catch (_: Exception) {} }
    fun release() { pool.release(); player?.release(); player = null }

    // ================================================================== DSP building blocks
    private fun n(sec: Double) = (sec * sr).toInt()
    private fun midi(m: Double) = 440.0 * 2.0.pow((m - 69) / 12)

    private fun tone(sec: Double, f: Double, decay: Double = 4.0, vol: Double = 0.5, attack: Double = 0.004): FloatArray {
        val out = FloatArray(n(sec))
        for (i in out.indices) {
            val t = i.toDouble() / sr
            out[i] = (sin(2 * PI * f * t) * vol * exp(-decay * t) * (t / attack).coerceAtMost(1.0)).toFloat()
        }
        return out
    }

    private fun bell(sec: Double, f: Double, vol: Double, decay: Double = 3.0): FloatArray =
        mix(tone(sec, f, decay, vol), tone(sec, f * 2.0, decay * 1.6, vol * 0.25), tone(sec, f * 2.76, decay * 2.2, vol * 0.3),
            tone(sec, f * 5.4, decay * 3.5, vol * 0.12))

    private fun noise(sec: Double, vol: Double, decay: Double, lowpass: Double = 1.0, seed: Long = 7): FloatArray {
        val r = Random(seed); val out = FloatArray(n(sec)); var y = 0.0
        for (i in out.indices) {
            y += lowpass * ((r.nextDouble() * 2 - 1) - y)
            out[i] = (y * vol * exp(-decay * i / sr)).toFloat()
        }
        return out
    }

    private fun sweep(sec: Double, f0: Double, f1: Double, vol: Double): FloatArray {
        val out = FloatArray(n(sec)); var ph = 0.0
        for (i in out.indices) {
            val p = i.toDouble() / out.size
            ph += 2 * PI * (f0 + (f1 - f0) * p) / sr
            out[i] = (sin(ph) * vol * sin(PI * p)).toFloat()
        }
        return out
    }

    private fun mix(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.maxOf { it.size })
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }

    private fun at(out: FloatArray, src: FloatArray, offset: Int, gain: Float = 1f) {
        for (i in src.indices) { val j = offset + i; if (j in out.indices) out[j] += src[i] * gain }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size }); var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun pad(a: FloatArray, sec: Double) = a.copyOf(a.size + n(sec))

    /** RBJ band-pass (constant peak gain) used for vocal formants. */
    private class BandPass(sr: Int, f: Double, q: Double) {
        private val b0: Double; private val b2: Double; private val a1: Double; private val a2: Double
        private var x1 = 0.0; private var x2 = 0.0; private var y1 = 0.0; private var y2 = 0.0
        init {
            val w = 2 * PI * f / sr; val alpha = sin(w) / (2 * q); val a0 = 1 + alpha
            b0 = alpha / a0; b2 = -alpha / a0; a1 = -2 * cos(w) / a0; a2 = (1 - alpha) / a0
        }
        fun process(x: Double): Double {
            val y = b0 * x + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x; y2 = y1; y1 = y
            return y
        }
    }

    /** Freeverb-style reverb: 4 damped combs in parallel, 2 all-passes in series. */
    private fun reverb(input: FloatArray, wet: Float, room: Double = 0.84, damp: Double = 0.25, tail: Double = 2.5): FloatArray {
        val a = input.copyOf(input.size + n(tail))
        val combLen = intArrayOf(1116, 1188, 1277, 1356).map { (it * sr / 44100.0).toInt() }
        val apLen = intArrayOf(556, 441).map { (it * sr / 44100.0).toInt() }
        val combs = combLen.map { FloatArray(it) }; val idx = IntArray(4); val store = DoubleArray(4)
        val aps = apLen.map { FloatArray(it) }; val apIdx = IntArray(2)
        val out = FloatArray(a.size)
        for (i in a.indices) {
            val x = a[i] * 0.25
            var sum = 0.0
            for (k in 0 until 4) {
                val buf = combs[k]; val y = buf[idx[k]].toDouble()
                store[k] = y * (1 - damp) + store[k] * damp
                buf[idx[k]] = (x + store[k] * room).toFloat()
                idx[k] = (idx[k] + 1) % buf.size
                sum += y
            }
            for (k in 0 until 2) {
                val buf = aps[k]; val b = buf[apIdx[k]].toDouble()
                buf[apIdx[k]] = (sum + b * 0.5).toFloat()
                sum = b - sum
                apIdx[k] = (apIdx[k] + 1) % buf.size
            }
            out[i] = a[i] + (sum * wet).toFloat()
        }
        return out
    }

    /** Soft-clip and normalize to [peak]. */
    private fun master(a: FloatArray, peak: Float): FloatArray {
        var m = 0f
        for (i in a.indices) { a[i] = tanh(a[i].toDouble() * 1.2).toFloat(); m = maxOf(m, abs(a[i])) }
        if (m > 0f) for (i in a.indices) a[i] *= peak / m
        return a
    }

    /**
     * Formant voice: a buzzy glottal source through three vowel formants, with
     * pitch glide, vibrato, jitter (roughness) and breath noise.
     */
    private fun voice(sec: Double, f0a: Double, f0b: Double, formants: DoubleArray, vol: Double,
                      breath: Double = 0.15, vibrato: Double = 0.0, jitter: Double = 0.0, seed: Long = 1): FloatArray {
        val out = FloatArray(n(sec)); val r = Random(seed)
        val bps = (0 until 3).map { BandPass(sr, formants[it], 6.0 + it * 3) }
        val gains = doubleArrayOf(1.0, 0.6, 0.25)
        var ph = 0.0
        for (i in out.indices) {
            val p = i.toDouble() / out.size; val t = i.toDouble() / sr
            val f0 = (f0a + (f0b - f0a) * p) * (1 + vibrato * sin(2 * PI * 6.0 * t)) * (1 + jitter * (r.nextDouble() - 0.5))
            ph = (ph + f0 / sr) % 1.0
            val src = (1 - 2 * ph) * 0.7 + (r.nextDouble() * 2 - 1) * breath
            var y = 0.0
            for (k in 0 until 3) y += bps[k].process(src) * gains[k]
            val env = (t / 0.02).coerceAtMost(1.0) * ((sec - t) / 0.05).coerceIn(0.0, 1.0)
            out[i] = (y * vol * env * 3).toFloat()
        }
        return out
    }

    private val vowelA = doubleArrayOf(730.0, 1090.0, 2440.0)
    private val vowelE = doubleArrayOf(530.0, 1840.0, 2480.0)
    private val vowelI = doubleArrayOf(300.0, 2300.0, 3000.0)
    private val vowelO = doubleArrayOf(400.0, 800.0, 2600.0)
    private val vowelU = doubleArrayOf(320.0, 870.0, 2250.0)

    /** "Ha-ha-ha" syllables: breathy h then voiced vowel, each lower than the last. */
    private fun laughSyllables(count: Int, f0: Double, drop: Double, vowel: DoubleArray, syl: Double, gap: Double,
                               vol: Double, vib: Double, jitter: Double): FloatArray {
        val parts = ArrayList<FloatArray>()
        for (k in 0 until count) {
            val f = f0 * (1 - drop * k / count)
            parts += noise(0.04, vol * 0.25, 20.0, 0.5, 30L + k)
            parts += voice(syl, f * 1.08, f * 0.92, vowel, vol, 0.12, vib, jitter, k.toLong())
            parts += FloatArray(n(gap))
        }
        return concat(*parts.toTypedArray())
    }

    // ================================================================== effects
    private fun synth(s: Sfx): FloatArray = when (s) {
        Sfx.CLICK -> mix(tone(0.05, 900.0, 80.0, 0.3), noise(0.02, 0.2, 200.0))
        Sfx.SPIN -> mix(sweep(0.4, 110.0, 380.0, 0.22), noise(0.4, 0.14, 5.0, 0.08))
        Sfx.STOP -> mix(tone(0.2, 62.0, 22.0, 0.9), tone(0.12, 124.0, 30.0, 0.3), noise(0.06, 0.35, 45.0, 0.25))
        Sfx.TICK -> tone(0.04, 1900.0, 90.0, 0.25)
        Sfx.COINS -> reverb(concat(*Array(6) { k -> mix(tone(0.07, 2300.0 + k * 140, 40.0, 0.25), tone(0.07, 3400.0 + k * 90, 55.0, 0.12)) }), 0.3f, tail = 0.6)
        Sfx.WIN -> reverb(concat(bell(0.16, midi(76.0), 0.28), bell(0.16, midi(79.0), 0.28), bell(0.7, midi(84.0), 0.32, 2.5)), 0.45f, tail = 1.2)
        Sfx.WIN_MED -> reverb(concat(bell(0.12, midi(69.0), 0.25), bell(0.12, midi(72.0), 0.25), bell(0.12, midi(76.0), 0.25),
            bell(0.12, midi(81.0), 0.25), mix(bell(1.0, midi(84.0), 0.3, 2.0), bell(1.0, midi(88.0), 0.22, 2.0))), 0.5f, tail = 1.5)
        Sfx.BIG_WIN -> reverb(mix(thunder(2.4, 0.5), choir(2.8, doubleArrayOf(57.0, 60.0, 64.0, 69.0), 0.1),
            pad(laughSyllables(5, 95.0, 0.3, vowelA, 0.17, 0.05, 0.5, 0.0, 0.08), 1.5)), 0.4f, tail = 1.5)
        Sfx.THUNDER -> thunder(2.8, 0.6)
        Sfx.RELIC -> reverb(mix(sweep(0.6, 300.0, 1400.0, 0.2), bell(0.8, midi(88.0), 0.3, 4.0), choir(0.9, doubleArrayOf(76.0, 81.0), 0.06)), 0.55f, tail = 1.2)
        Sfx.VAULT -> reverb(mix(bell(3.0, midi(43.0), 0.6, 1.0), bell(3.0, midi(50.0), 0.3, 1.2), choir(3.2, doubleArrayOf(50.0, 53.0, 57.0, 62.0), 0.1), thunder(3.0, 0.4)), 0.4f)
        Sfx.ORB -> reverb(mix(bell(0.6, midi(84.0), 0.35, 6.0), tone(0.15, 130.0, 20.0, 0.4)), 0.4f, tail = 0.8)
        Sfx.HEARTBEAT -> heartbeat(0.9)
        Sfx.JACKPOT -> reverb(concat(bell(0.1, midi(72.0), 0.3), bell(0.1, midi(76.0), 0.3), bell(0.1, midi(79.0), 0.3), bell(0.1, midi(84.0), 0.3),
            mix(bell(1.3, midi(84.0), 0.3, 2.0), bell(1.3, midi(88.0), 0.25, 2.0), bell(1.3, midi(91.0), 0.2, 2.0))), 0.45f, tail = 1.2)
        Sfx.GROWL -> growl(1.3)
        Sfx.SLASH -> mix(noise(0.35, 0.5, 9.0, 0.6, 3), sweep(0.3, 2400.0, 500.0, 0.12))
        Sfx.WHOOSH -> noise(0.6, 0.45, 3.0, 0.12, 9).also { for (i in it.indices) it[i] *= sin(PI * i / it.size).toFloat() }
        Sfx.ERROR -> concat(tone(0.14, 110.0, 6.0, 0.4), tone(0.2, 82.0, 6.0, 0.4))
        Sfx.BELL -> reverb(bell(2.5, midi(43.0), 0.5, 1.2), 0.5f)
        // deep, slow, evil "HA HA HA HA HA" (Grim Reaper)
        Sfx.LAUGH -> reverb(mix(laughSyllables(6, 105.0, 0.35, vowelA, 0.2, 0.06, 0.55, 0.0, 0.06),
            laughSyllables(6, 52.0, 0.35, vowelO, 0.2, 0.06, 0.3, 0.0, 0.05)), 0.45f, tail = 1.5)
        // high, quick, witchy cackle (Jack-o'-lantern)
        Sfx.CACKLE -> reverb(laughSyllables(9, 420.0, -0.25, vowelE, 0.085, 0.03, 0.35, 0.03, 0.05), 0.35f, tail = 1.0)
        // breathy, airy "hee-hee-hee" with echo (ghost)
        Sfx.GIGGLE -> reverb(laughSyllables(5, 620.0, 0.2, vowelI, 0.1, 0.06, 0.25, 0.04, 0.02), 0.65f, room = 0.9, tail = 1.8)
        // two rough caws (raven)
        Sfx.CAW -> reverb(concat(voice(0.28, 520.0, 430.0, doubleArrayOf(820.0, 1450.0, 2700.0), 0.45, 0.35, 0.0, 0.35, 4),
            FloatArray(n(0.12)), voice(0.32, 500.0, 400.0, doubleArrayOf(820.0, 1450.0, 2700.0), 0.45, 0.35, 0.0, 0.4, 5)), 0.3f, tail = 0.8)
        // bony teeth chatter (skull)
        Sfx.CHATTER -> reverb(concat(*Array(9) { k -> concat(mix(noise(0.012, 0.6, 120.0, 0.9, 40L + k), tone(0.03, 1700.0 + (k % 3) * 120, 90.0, 0.3)), FloatArray(n(0.035))) }), 0.25f, tail = 0.4)
    }

    private fun thunder(sec: Double, vol: Double): FloatArray {
        val out = mix(noise(0.25, vol, 12.0, 0.7, 11), noise(sec, vol * 1.4, 1.6, 0.025, 12))
        for (i in out.indices) out[i] *= (0.7 + 0.3 * sin(2 * PI * 3.1 * i / sr) * sin(2 * PI * 0.7 * i / sr)).toFloat()
        return out
    }

    /** Ghost choir: formant voices on "ooh" with slow swell. */
    private fun choir(sec: Double, notes: DoubleArray, vol: Double): FloatArray {
        val parts = notes.mapIndexed { k, m ->
            val v = voice(sec, midi(m), midi(m) * 1.003, vowelU, vol, 0.08, 0.008, 0.002, 100L + k)
            for (i in v.indices) { val t = i.toDouble() / sr; v[i] *= ((t / (sec * 0.35)).coerceAtMost(1.0) * ((sec - t) / (sec * 0.3)).coerceIn(0.0, 1.0)).toFloat() }
            v
        }
        return mix(*parts.toTypedArray())
    }

    private fun growl(sec: Double): FloatArray {
        val r = Random(5); val out = FloatArray(n(sec)); var y = 0.0; var y2 = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val am = 0.5 + 0.5 * sin(2 * PI * (28 + 10 * sin(2 * PI * 0.8 * t)) * t)
            y += 0.06 * ((r.nextDouble() * 2 - 1) - y); y2 += 0.1 * (y - y2)
            val env = (t / 0.15).coerceAtMost(1.0) * ((sec - t) / 0.5).coerceIn(0.0, 1.0)
            out[i] = ((y2 * 3.5 * am + 0.35 * sin(2 * PI * 48 * t) * am) * env * 0.8).toFloat()
        }
        return out
    }

    private fun heartbeat(vol: Double): FloatArray {
        val beat = mix(tone(0.16, 55.0, 22.0, vol), noise(0.05, vol * 0.3, 50.0, 0.05))
        val out = FloatArray(n(0.5))
        at(out, beat, 0); at(out, beat, n(0.22), 0.75f)
        return out
    }

    // ================================================================== music
    /** Detuned-saw pad chord through a slowly opening low-pass, with swell envelope. */
    private fun padChord(sec: Double, notes: IntArray, vol: Double, cutLo: Double, cutHi: Double, seed: Long): FloatArray {
        val out = FloatArray(n(sec)); val r = Random(seed)
        val phases = DoubleArray(notes.size * 3) { r.nextDouble() }
        var lp1 = 0.0; var lp2 = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / sr; val p = t / sec
            var s = 0.0
            for ((k, m) in notes.withIndex()) for (d in 0 until 3) {
                val f = midi(m.toDouble()) * (1 + (d - 1) * 0.004)
                val idx = k * 3 + d
                phases[idx] = (phases[idx] + f / sr) % 1.0
                s += 1 - 2 * phases[idx]
            }
            val cut = cutLo + (cutHi - cutLo) * sin(PI * p)
            val a = (2 * PI * cut / sr).coerceAtMost(0.9)
            lp1 += a * (s - lp1); lp2 += a * (lp1 - lp2)
            val env = (t / (sec * 0.25)).coerceAtMost(1.0) * ((sec - t) / (sec * 0.2)).coerceIn(0.0, 1.0)
            out[i] = (lp2 * vol * env).toFloat()
        }
        return out
    }

    private fun taiko(vol: Double): FloatArray {
        val out = FloatArray(n(0.9)); var ph = 0.0
        for (i in out.indices) {
            val t = i.toDouble() / sr
            ph += 2 * PI * (42 + 50 * exp(-t * 18)) / sr
            out[i] = (sin(ph) * vol * exp(-t * 4.5)).toFloat()
        }
        return mix(out, noise(0.12, vol * 0.35, 30.0, 0.15, 77))
    }

    private fun subBass(sec: Double, m: Int, vol: Double): FloatArray {
        val f = midi(m.toDouble()); val out = FloatArray(n(sec))
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val env = (t / 0.05).coerceAtMost(1.0) * exp(-t * 0.6) * ((sec - t) / 0.3).coerceIn(0.0, 1.0)
            out[i] = (tanh(1.6 * sin(2 * PI * f * t)) * vol * env).toFloat()
        }
        return out
    }

    /** 32 s loop (60 BPM, 8 bars): Am F Dm E | Am C Dm E7 with music-box melody, ghost choir and taiko. */
    private fun baseMusic(): FloatArray {
        val bar = 4.0; val bars = 8; val total = n(bar * bars)
        val dry = FloatArray(total + n(3.0)); val wetSend = FloatArray(dry.size)
        val chords = arrayOf(intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(50, 53, 57), intArrayOf(52, 56, 59),
            intArrayOf(57, 60, 64), intArrayOf(55, 60, 64), intArrayOf(50, 53, 57), intArrayOf(52, 56, 62))
        val roots = intArrayOf(33, 29, 26, 28, 33, 36, 26, 28)
        for (b in 0 until bars) {
            val o = n(b * bar)
            at(dry, padChord(bar + 1.2, chords[b], 0.05, 280.0, 1100.0, b.toLong()), o - n(0.3).coerceAtMost(o))
            at(dry, subBass(bar, roots[b] + 12, 0.22), o)
            at(wetSend, taiko(0.55), o)
            if (b % 4 == 3) { at(wetSend, taiko(0.35), o + n(2.5)); at(wetSend, taiko(0.4), o + n(3.0)) }
            if (b >= 4) at(wetSend, choir(bar + 0.5, chords[b].map { it.toDouble() }.toDoubleArray(), 0.05), o)
        }
        // music-box melody: (bar, beat, midi)
        val mel = arrayOf(
            doubleArrayOf(0.0, 0.0, 76.0), doubleArrayOf(0.0, 1.0, 81.0), doubleArrayOf(0.0, 2.0, 84.0), doubleArrayOf(0.0, 2.5, 83.0), doubleArrayOf(0.0, 3.0, 81.0),
            doubleArrayOf(1.0, 0.0, 84.0), doubleArrayOf(1.0, 1.0, 81.0), doubleArrayOf(1.0, 2.0, 77.0),
            doubleArrayOf(2.0, 0.0, 74.0), doubleArrayOf(2.0, 1.0, 77.0), doubleArrayOf(2.0, 1.5, 81.0), doubleArrayOf(2.0, 2.5, 80.0), doubleArrayOf(2.0, 3.0, 81.0),
            doubleArrayOf(3.0, 0.0, 83.0), doubleArrayOf(3.0, 1.0, 80.0), doubleArrayOf(3.0, 2.0, 76.0),
            doubleArrayOf(4.0, 0.0, 81.0), doubleArrayOf(4.0, 1.0, 84.0), doubleArrayOf(4.0, 2.0, 88.0), doubleArrayOf(4.0, 2.5, 86.0), doubleArrayOf(4.0, 3.0, 84.0),
            doubleArrayOf(5.0, 0.0, 79.0), doubleArrayOf(5.0, 1.0, 76.0), doubleArrayOf(5.0, 2.0, 84.0),
            doubleArrayOf(6.0, 0.0, 77.0), doubleArrayOf(6.0, 0.5, 81.0), doubleArrayOf(6.0, 1.0, 86.0), doubleArrayOf(6.0, 2.0, 84.0), doubleArrayOf(6.0, 3.0, 83.0),
            doubleArrayOf(7.0, 0.0, 80.0), doubleArrayOf(7.0, 1.0, 83.0), doubleArrayOf(7.0, 2.0, 86.0), doubleArrayOf(7.0, 3.5, 88.0))
        for (e in mel) at(wetSend, bell(2.0, midi(e[2]), 0.075, 2.2), n(e[0] * bar + e[1]))
        // wind
        val wind = noise(bar * bars + 3, 0.12, 0.0, 0.01, 21)
        for (i in wind.indices) wind[i] *= (0.4 + 0.6 * sin(PI * i / n(16.0)).let { it * it }).toFloat()
        at(dry, wind, 0)
        val wet = reverb(wetSend, 0.9f, room = 0.88, damp = 0.3, tail = 0.0)
        val mixed = mix(dry, wet, reverb(dry, 0.25f, tail = 0.0))
        return seamless(mixed, total)
    }

    /** 19.2 s loop (100 BPM): pulsing A ostinato, tense strings, taiko + heartbeat, rising choir. */
    private fun vaultMusic(): FloatArray {
        val beat = 0.6; val bars = 8; val total = n(beat * 4 * bars)
        val dry = FloatArray(total + n(3.0)); val send = FloatArray(dry.size)
        val roots = intArrayOf(45, 45, 41, 41, 43, 43, 40, 40)
        val chords = arrayOf(intArrayOf(57, 60, 64), intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(53, 57, 60),
            intArrayOf(55, 59, 62), intArrayOf(55, 59, 62), intArrayOf(52, 56, 59), intArrayOf(52, 56, 62))
        for (b in 0 until bars) {
            val o = n(b * 4 * beat)
            for (k in 0 until 8) {   // eighth-note ostinato
                val m = roots[b] + if (k % 4 == 3) 7 else 0
                at(dry, padChord(beat / 2, intArrayOf(m - 12, m), if (k % 2 == 0) 0.07 else 0.045, 300.0, 900.0, (b * 8 + k).toLong()), o + n(k * beat / 2))
            }
            at(dry, padChord(4 * beat + 0.3, chords[b].map { it + 12 }.toIntArray(), 0.03, 600.0, 2200.0, 50L + b), o)
            at(send, taiko(0.5), o); at(send, taiko(0.35), o + n(2 * beat))
            at(dry, heartbeat(0.35), o + n(beat))
            if (b >= 4) at(send, choir(4 * beat + 0.3, chords[b].map { it + 12.0 }.toDoubleArray(), 0.04), o)
        }
        val wet = reverb(send, 0.8f, tail = 0.0)
        return seamless(mix(dry, wet, reverb(dry, 0.2f, tail = 0.0)), total)
    }

    /** Folds the tail past [total] onto the start (crossfaded) so the loop is seamless, then masters. */
    private fun seamless(buf: FloatArray, total: Int): FloatArray {
        val out = buf.copyOf(total)
        val fade = minOf(buf.size - total, n(2.0))
        for (i in 0 until fade) out[i] += buf[total + i] * (1 - i.toFloat() / fade)
        return master(out, 0.9f)
    }

    private fun writeWav(f: File, data: FloatArray) {
        val pcm = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in data) pcm.putShort((v.coerceIn(-1f, 1f) * 32000).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + pcm.capacity()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(sr).putInt(sr * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(pcm.capacity())
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(h.array()); it.write(pcm.array()) }
        tmp.renameTo(f)
    }

    /** Test hook: synthesizes everything into [out] (used to inspect audio off-device). */
    internal fun renderAllTo(out: File) {
        out.mkdirs()
        for (s in Sfx.values()) writeWav(File(out, "${s.name}.wav"), master(synth(s), 0.92f))
        writeWav(File(out, "music_BASE.wav"), baseMusic())
        writeWav(File(out, "music_VAULT.wav"), vaultMusic())
    }
}

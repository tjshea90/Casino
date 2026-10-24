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
import kotlin.math.exp
import kotlin.math.sin

/**
 * All audio is synthesized on first launch into WAV files in the cache dir:
 * short effects go through SoundPool, the two ambient music loops through
 * MediaPlayer. Nothing ships in the APK.
 */
class Audio(context: Context) {
    enum class Sfx { CLICK, SPIN, STOP, WIN, WIN_MED, BIG_WIN, THUNDER, RELIC, VAULT, ORB, HEARTBEAT, JACKPOT, GROWL, SLASH, TICK, ERROR, BELL }
    enum class Track { BASE, VAULT }

    var sfxOn = true
    var musicOn = true
        set(v) { field = v; if (v) resumeMusic() else player?.pause() }

    private val pool = SoundPool.Builder().setMaxStreams(10).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    ).build()
    private val ids = IntArray(Sfx.values().size)
    private val dir = File(context.cacheDir, "audio_v1").apply { mkdirs() }
    private var player: MediaPlayer? = null
    private var track: Track? = null
    @Volatile private var musicReady = false
    private var wantTrack = Track.BASE
    private val sr = 22050

    init {
        Thread {
            for (s in Sfx.values()) {
                val f = File(dir, "${s.name}.wav")
                if (!f.exists() || f.length() < 100) writeWav(f, synth(s))
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
                setVolume(0.5f, 0.5f)
                prepare()
            }
        } catch (e: Exception) { null }
        track = t
        resumeMusic()
    }

    private fun resumeMusic() { if (musicOn) try { player?.start() } catch (_: Exception) {} }
    fun pauseMusic() { try { player?.pause() } catch (_: Exception) {} }

    fun release() { pool.release(); player?.release(); player = null }

    // ================================================================== synthesis helpers
    private fun n(sec: Double) = (sec * sr).toInt()

    private fun tone(sec: Double, f: Double, decay: Double = 4.0, vol: Double = 0.5, attack: Double = 0.004): FloatArray {
        val out = FloatArray(n(sec))
        for (i in out.indices) {
            val t = i.toDouble() / sr
            out[i] = (sin(2 * PI * f * t) * vol * exp(-decay * t) * (t / attack).coerceAtMost(1.0)).toFloat()
        }
        return out
    }

    /** Bell / music-box timbre: inharmonic partials. */
    private fun bell(sec: Double, f: Double, vol: Double, decay: Double = 3.0): FloatArray =
        mix(tone(sec, f, decay, vol), tone(sec, f * 2.76, decay * 1.8, vol * 0.4), tone(sec, f * 5.4, decay * 3, vol * 0.18))

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

    private fun echo(a: FloatArray, delaySec: Double, fbD: Double, tail: Double = 1.0): FloatArray {
        val fb = fbD.toFloat()
        val d = n(delaySec); val out = a.copyOf(a.size + n(tail))
        for (i in d until out.size) out[i] += out[i - d] * fb
        return out
    }

    private fun heartbeat(vol: Double): FloatArray {
        val beat = mix(tone(0.16, 55.0, 22.0, vol), noise(0.05, vol * 0.3, 50.0, 0.05))
        val out = FloatArray(n(0.5))
        at(out, beat, 0); at(out, beat, n(0.22), 0.75f)
        return out
    }

    // ================================================================== effects
    private fun synth(s: Sfx): FloatArray = when (s) {
        Sfx.CLICK -> mix(tone(0.05, 900.0, 80.0, 0.3), noise(0.02, 0.2, 200.0))
        Sfx.SPIN -> mix(sweep(0.35, 120.0, 420.0, 0.22), noise(0.35, 0.12, 6.0, 0.08))
        Sfx.STOP -> mix(tone(0.18, 70.0, 25.0, 0.8), noise(0.08, 0.35, 45.0, 0.2))
        Sfx.TICK -> tone(0.04, 1900.0, 90.0, 0.25)
        Sfx.WIN -> echo(concat(bell(0.18, 659.25, 0.25), bell(0.5, 880.0, 0.28)), 0.22, 0.3, 0.6)
        Sfx.WIN_MED -> echo(concat(bell(0.14, 440.0, 0.25), bell(0.14, 523.25, 0.25), bell(0.14, 659.25, 0.25), bell(0.7, 830.6, 0.3)), 0.25, 0.35, 0.8)
        Sfx.BIG_WIN -> mix(thunder(2.2, 0.55), choir(2.4, doubleArrayOf(220.0, 261.63, 329.63, 415.3), 0.12))
        Sfx.THUNDER -> thunder(2.6, 0.6)
        Sfx.RELIC -> mix(sweep(0.6, 300.0, 1400.0, 0.2), echo(bell(0.6, 1318.5, 0.3, 5.0), 0.18, 0.4, 0.8))
        Sfx.VAULT -> mix(bell(3.0, 98.0, 0.6, 1.0), bell(3.0, 146.8, 0.3, 1.2), choir(3.0, doubleArrayOf(146.8, 174.6, 220.0, 277.2), 0.1), thunder(3.0, 0.4))
        Sfx.ORB -> echo(mix(bell(0.6, 1046.5, 0.35, 6.0), tone(0.15, 130.0, 20.0, 0.4)), 0.15, 0.35, 0.6)
        Sfx.HEARTBEAT -> heartbeat(0.9)
        Sfx.JACKPOT -> echo(concat(bell(0.12, 523.25, 0.3), bell(0.12, 659.25, 0.3), bell(0.12, 783.99, 0.3), bell(0.12, 1046.5, 0.3),
            mix(bell(1.2, 1046.5, 0.3, 2.0), bell(1.2, 1318.5, 0.25, 2.0), bell(1.2, 1568.0, 0.2, 2.0))), 0.2, 0.35, 1.0)
        Sfx.GROWL -> growl(1.3)
        Sfx.SLASH -> mix(noise(0.35, 0.5, 9.0, 0.6, 3), sweep(0.3, 2400.0, 500.0, 0.12))
        Sfx.ERROR -> concat(tone(0.14, 110.0, 6.0, 0.4), tone(0.2, 82.0, 6.0, 0.4))
        Sfx.BELL -> echo(bell(2.5, 196.0, 0.5, 1.2), 0.4, 0.3, 1.5)
    }

    private fun thunder(sec: Double, vol: Double): FloatArray {
        val crack = noise(0.25, vol, 12.0, 0.7, 11)
        val rumble = noise(sec, vol * 1.4, 1.6, 0.025, 12)
        val out = mix(crack, rumble)
        // irregular rolling
        for (i in out.indices) out[i] *= (0.7 + 0.3 * sin(2 * PI * 3.1 * i / sr) * sin(2 * PI * 0.7 * i / sr)).toFloat()
        return out
    }

    /** Ghostly "ooh" choir: detuned sines with vibrato and slow swell. */
    private fun choir(sec: Double, notes: DoubleArray, vol: Double): FloatArray {
        val out = FloatArray(n(sec))
        for ((k, f) in notes.withIndex()) {
            var ph1 = 0.0; var ph2 = 0.0
            for (i in out.indices) {
                val t = i.toDouble() / sr
                val vib = 1 + 0.006 * sin(2 * PI * (5.0 + k * 0.3) * t)
                ph1 += 2 * PI * f * vib / sr; ph2 += 2 * PI * f * 1.004 * vib / sr
                val env = (t / 0.4).coerceAtMost(1.0) * ((sec - t) / 0.6).coerceIn(0.0, 1.0)
                out[i] += ((sin(ph1) + sin(ph2) + 0.3 * sin(2 * ph1)) * vol * env).toFloat()
            }
        }
        return out
    }

    /** Low monstrous growl: AM noise through a resonant-ish filter plus a sub tone. */
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

    // ================================================================== music
    /** 32 s seamless loop: drone, wind, sparse music-box in A harmonic minor, distant bell. */
    private fun baseMusic(): FloatArray {
        val len = 32.0; val total = n(len)
        val out = FloatArray(total + n(2.0))
        // drone (periods divide the loop length)
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val swell = 0.75 + 0.25 * sin(2 * PI * t / 16.0)
            out[i] += (0.10 * sin(2 * PI * 55.0 * t) + 0.07 * sin(2 * PI * 55.25 * t) + 0.04 * sin(2 * PI * 82.5 * t) +
                0.025 * sin(2 * PI * 110.5 * t)).toFloat() * swell.toFloat()
        }
        // wind
        val wind = noise(len + 2, 0.22, 0.0, 0.012, 21)
        for (i in wind.indices) {
            val t = i.toDouble() / sr
            wind[i] *= (0.35 + 0.65 * sin(PI * t / 8.0).let { it * it }).toFloat()
        }
        at(out, wind, 0)
        // music box phrase (A harmonic minor), sparse
        val notes = doubleArrayOf(880.0, 1046.5, 987.8, 830.6, 659.3, 698.5, 830.6, 880.0, 1318.5, 1174.7, 1046.5, 987.8)
        val times = doubleArrayOf(1.0, 2.2, 3.4, 4.6, 8.5, 9.7, 10.9, 12.1, 17.0, 18.2, 19.4, 20.6)
        val box = FloatArray(out.size)
        for (k in notes.indices) at(box, bell(2.2, notes[k], 0.07, 2.4), n(times[k]))
        at(box, bell(2.5, 659.3, 0.05, 1.8), n(25.0)); at(box, bell(3.0, 622.3, 0.05, 1.5), n(27.0))
        at(out, echo(box, 0.42, 0.38, 0.0), 0)
        // distant funeral bell every 16 s
        at(out, bell(5.0, 73.4, 0.18, 0.7), n(6.0)); at(out, bell(5.0, 69.3, 0.16, 0.7), n(22.0))
        return seamless(out, total)
    }

    /** 16 s loop for the Soul Vault: heartbeat pulse, tense rising choir, ticking. */
    private fun vaultMusic(): FloatArray {
        val len = 16.0; val total = n(len)
        val out = FloatArray(total + n(2.0))
        val beat = heartbeat(0.55)
        var t = 0.0
        while (t < len) { at(out, beat, n(t)); t += 0.75 }
        val tick = tone(0.03, 2600.0, 120.0, 0.08)
        t = 0.375
        while (t < len) { at(out, tick, n(t)); t += 0.75 }
        at(out, choir(8.0, doubleArrayOf(220.0, 233.1, 311.1), 0.05), 0)
        at(out, choir(8.0, doubleArrayOf(233.1, 246.9, 329.6), 0.05), n(8.0))
        for (i in out.indices) {
            val tt = i.toDouble() / sr
            out[i] += (0.09 * sin(2 * PI * 41.2 * tt) * (0.6 + 0.4 * sin(2 * PI * tt / 4.0))).toFloat()
        }
        return seamless(out, total)
    }

    /** Folds the overhang past [total] back onto the start with a crossfade so the loop is click-free. */
    private fun seamless(buf: FloatArray, total: Int): FloatArray {
        val out = buf.copyOf(total)
        val fade = minOf(buf.size - total, n(1.5))
        for (i in 0 until fade) {
            val a = i.toFloat() / fade
            out[i] = out[i] * a + buf[total + i] * (1 - a)
        }
        var peak = 0f
        for (v in out) peak = maxOf(peak, kotlin.math.abs(v))
        if (peak > 0.95f) for (i in out.indices) out[i] *= 0.95f / peak
        return out
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
}

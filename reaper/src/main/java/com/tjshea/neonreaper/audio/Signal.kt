package com.tjshea.neonreaper.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Floating-point stereo buffer. */
internal class Stereo(val n: Int) {
    @JvmField val l = FloatArray(n)
    @JvmField val r = FloatArray(n)
}

/** 16-bit PCM clip (mono when [r] is null). */
internal class Pcm(@JvmField val l: ShortArray, @JvmField val r: ShortArray?) {
    val frames: Int get() = l.size
    val channels: Int get() = if (r == null) 1 else 2
    val seconds: Double get() = l.size.toDouble() / SR
    val bytes: Long get() = (l.size.toLong() * channels) * 2
}

/**
 * A mixing bus: a dry stereo buffer plus two mono reverb sends (0 = long "hall", 1 = short "room").
 * In [loop] mode every add wraps modulo [n] and the reverb tail folds onto the start, so the result is a seamless loop.
 */
internal class Bus(val n: Int, private val loop: Boolean = false) {
    @JvmField val dry = Stereo(n)
    private val s0 = FloatArray(n)
    private val s1 = FloatArray(n)
    private var used0 = false
    private var used1 = false

    /**
     * Mixes [x] in at sample [at]: [g] to the dry bus (constant-power [pan] -1..1), [hall]/[room] gains to the reverb sends.
     * Out-of-range samples are dropped (one-shot) or wrapped (loop).
     */
    fun add(x: FloatArray, at: Int, g: Double = 1.0, pan: Double = 0.0, hall: Double = 0.0, room: Double = 0.0) {
        val a = (pan.coerceIn(-1.0, 1.0) + 1.0) * PI / 4.0
        val gl = (cos(a) * g).toFloat(); val gr = (sin(a) * g).toFloat()
        val hf = hall.toFloat(); val rf = room.toFloat()
        if (hall != 0.0) used0 = true
        if (room != 0.0) used1 = true
        val dl = dry.l; val dr = dry.r
        val size = x.size
        // 5 ms safety ramp on the tail of every source: a sound that is cut while still ringing can never click
        val ramp = min(size / 4, 110)
        val rampFrom = size - ramp
        if (!loop) {
            val from = max(0, -at); val to = min(size, n - at)
            for (i in from until to) {
                var v = x[i]
                if (i >= rampFrom) v *= (size - i) / (ramp + 1f)
                val j = at + i
                dl[j] += v * gl; dr[j] += v * gr
                if (hf != 0f) s0[j] += v * hf
                if (rf != 0f) s1[j] += v * rf
            }
        } else {
            var j = ((at % n) + n) % n
            for (i in 0 until size) {
                var v = x[i]
                if (i >= rampFrom) v *= (size - i) / (ramp + 1f)
                dl[j] += v * gl; dr[j] += v * gr
                if (hf != 0f) s0[j] += v * hf
                if (rf != 0f) s1[j] += v * rf
                if (++j == n) j = 0
            }
        }
    }

    /** Wet-only convenience (no dry signal). */
    fun wet(x: FloatArray, at: Int, hall: Double, room: Double = 0.0) = add(x, at, 0.0, 0.0, hall, room)

    /**
     * Applies the reverbs and returns the finished stereo mix. For one-shots the tail is cut at the buffer end (the caller
     * fades out); for loops the tail ([hallTail]/[roomTail] seconds) is folded around the loop.
     */
    fun render(hall: ReverbSpec? = null, room: ReverbSpec? = null, hallTail: Double = 0.0, roomTail: Double = 0.0): Stereo {
        if (hall != null && used0) runReverb(s0, hall, hallTail)
        if (room != null && used1) runReverb(s1, room, roomTail)
        return dry
    }

    /** One-shots only process up to the last send sample plus ~1.2 decay times (the rest would be below -70 dB or cut anyway). */
    private fun runReverb(send: FloatArray, spec: ReverbSpec, loopTail: Double) {
        if (loop) { Reverb.run(send, n, spec, frames(loopTail), 1f, dry.l, dry.r, true); return }
        var last = n
        while (last > 0 && send[last - 1] == 0f) last--
        if (last == 0) return
        val proc = min(n, last + frames(spec.rt60 * 1.2))
        Reverb.run(send, last, spec, proc - last, 1f, dry.l, dry.r, false)
    }
}

/** Loudness / peak measurement, mastering and format conversion. */
internal object Master {
    /** Combined RMS of both channels (mono for a null right channel), linear. */
    fun rms(l: FloatArray, r: FloatArray?, from: Int = 0, to: Int = l.size): Double {
        var s = 0.0
        for (i in from until to) s += l[i].toDouble() * l[i]
        if (r != null) for (i in from until to) s += r[i].toDouble() * r[i]
        return sqrt(s / (max(1, to - from) * (if (r != null) 2 else 1)))
    }

    fun peak(l: FloatArray, r: FloatArray?): Float {
        var m = 0f
        for (v in l) { val a = abs(v); if (a > m) m = a }
        if (r != null) for (v in r) { val a = abs(v); if (a > m) m = a }
        return m
    }

    fun rmsDb(st: Stereo): Double = gainDb(rms(st.l, st.r))

    fun gain(st: Stereo, g: Double) {
        val f = g.toFloat()
        for (i in 0 until st.n) { st.l[i] *= f; st.r[i] *= f }
    }

    /** Removes the (global) DC offset; exact and seam-safe for loops. */
    fun removeDc(st: Stereo) {
        var a = 0.0; var b = 0.0
        for (i in 0 until st.n) { a += st.l[i]; b += st.r[i] }
        val ma = (a / st.n).toFloat(); val mb = (b / st.n).toFloat()
        for (i in 0 until st.n) { st.l[i] -= ma; st.r[i] -= mb }
    }

    /** Mastering EQ for one-shots: low shelf (bass management for small speakers) + high shelf (keeps the top un-fatiguing). */
    fun eq(st: Stereo, bassDb: Double, airDb: Double) {
        if (bassDb == 0.0 && airDb == 0.0) return
        for (ch in 0 until 2) {
            val a = if (ch == 0) st.l else st.r
            val lo = if (bassDb != 0.0) Biquad().lowShelf(100.0, bassDb) else null
            val hi = if (airDb != 0.0) Biquad().highShelf(5000.0, airDb) else null
            for (i in 0 until st.n) {
                var v = a[i].toDouble()
                if (lo != null) v = lo.process(v)
                if (hi != null) v = hi.process(v)
                a[i] = v.toFloat()
            }
        }
    }

    /** Raised-cosine fade in / out (seconds). */
    fun fades(st: Stereo, inSec: Double, outSec: Double) {
        val ni = min(st.n / 2, frames(inSec)); val no = min(st.n / 2, frames(outSec))
        for (i in 0 until ni) { val w = (0.5 - 0.5 * cos(PI * i / ni)).toFloat(); st.l[i] *= w; st.r[i] *= w }
        for (i in 0 until no) { val w = (0.5 - 0.5 * cos(PI * i / no)).toFloat(); st.l[st.n - 1 - i] *= w; st.r[st.n - 1 - i] *= w }
    }

    /**
     * Transparent look-ahead peak limiter (offline): gain = sliding minimum, smoothed with a box of the same width so it
     * never exceeds the required gain, then released slowly. Returns the maximum gain reduction in dB.
     */
    fun limit(st: Stereo, ceiling: Float, lookaheadSec: Double = 0.0025, releaseSec: Double = 0.08): Double {
        val n = st.n
        var pk = 0f
        for (i in 0 until n) { val a = max(abs(st.l[i]), abs(st.r[i])); if (a > pk) pk = a }
        if (pk <= ceiling) return 0.0
        val a = max(2, frames(lookaheadSec))
        val need = FloatArray(n)
        for (i in 0 until n) { val v = max(abs(st.l[i]), abs(st.r[i])); need[i] = if (v > ceiling) ceiling / v else 1f }
        // sliding minimum over [i-a, i+a] with a monotone deque
        val mn = FloatArray(n)
        val dq = IntArray(n + 1); var head = 0; var tail = 0
        var nextIn = 0
        for (i in 0 until n) {
            val hi = min(n - 1, i + a)
            while (nextIn <= hi) {
                while (tail > head && need[dq[tail - 1]] >= need[nextIn]) tail--
                dq[tail++] = nextIn; nextIn++
            }
            while (dq[head] < i - a) head++
            mn[i] = need[dq[head]]
        }
        // box smoothing with the same half width
        val sm = FloatArray(n)
        var acc = 0.0; var cnt = 0
        for (i in 0..min(n - 1, a)) { acc += mn[i]; cnt++ }
        for (i in 0 until n) {
            sm[i] = (acc / cnt).toFloat()
            val addIdx = i + a + 1; val subIdx = i - a
            if (addIdx < n) { acc += mn[addIdx]; cnt++ }
            if (subIdx >= 0) { acc -= mn[subIdx]; cnt-- }
        }
        val rel = 1.0 - exp(-1.0 / (releaseSec * SR))
        var g = 1.0f; var worst = 1f
        for (i in 0 until n) {
            val up = g + (1f - g) * rel.toFloat()
            g = min(sm[i], up)
            if (g < worst) worst = g
            st.l[i] *= g; st.r[i] *= g
        }
        return -gainDb(worst.toDouble())
    }

    /**
     * Brings [st] to [targetDb] RMS with the peak under [ceilingDb] (limiter does the rest). Returns max limiter reduction (dB).
     */
    fun toLoudness(st: Stereo, targetDb: Double, ceilingDb: Double = -1.5): Double {
        val ceiling = dbGain(ceilingDb).toFloat()
        var worst = 0.0
        for (pass in 0 until 3) {
            val cur = rms(st.l, st.r)
            if (cur < 1e-9) return 0.0
            val g = dbGain(targetDb) / cur
            if (abs(gainDb(g)) < 0.2 && pass > 0) break
            gain(st, g)
            worst = max(worst, limit(st, ceiling))
        }
        return worst
    }

    private fun s16(v: Float): Short = (v.coerceIn(-1f, 1f) * 32767f).roundToInt().toShort()

    fun toPcm(st: Stereo, stereo: Boolean): Pcm {
        val n = st.n
        return if (stereo) Pcm(ShortArray(n) { s16(st.l[it]) }, ShortArray(n) { s16(st.r[it]) })
        else Pcm(ShortArray(n) { s16((st.l[it] + st.r[it]) * 0.5f) }, null)
    }

    /** Folds a stereo buffer to mono in place (left = right = mid). */
    fun monoize(st: Stereo) { for (i in 0 until st.n) { val m = (st.l[i] + st.r[i]) * 0.5f; st.l[i] = m; st.r[i] = m } }
}

/** Minimal 16-bit PCM WAV reader/writer (mono or stereo, 22.05 kHz). */
internal object Wav {
    fun encode(p: Pcm): ByteArray {
        val ch = p.channels; val data = p.frames * ch * 2
        val b = ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort()).putInt(SR).putInt(SR * ch * 2)
            .putShort((ch * 2).toShort()).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        val r = p.r
        if (r == null) for (v in p.l) b.putShort(v) else for (i in p.l.indices) { b.putShort(p.l[i]); b.putShort(r[i]) }
        return b.array()
    }

    /** Atomic write (tmp + rename) so a killed process never leaves a truncated asset behind. */
    fun write(f: File, p: Pcm) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(encode(p)) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** Reads a WAV written by [write]; returns null when missing or malformed. */
    fun read(f: File): Pcm? {
        try {
            if (!f.isFile || f.length() < 46) return null
            val all = f.readBytes()
            val b = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN)
            if (String(all, 0, 4) != "RIFF" || String(all, 8, 4) != "WAVE" || String(all, 36, 4) != "data") return null
            val ch = b.getShort(22).toInt(); val rate = b.getInt(24); val bits = b.getShort(34).toInt()
            val dataLen = b.getInt(40)
            if (rate != SR || bits != 16 || (ch != 1 && ch != 2) || dataLen <= 0 || 44 + dataLen > all.size) return null
            val n = dataLen / (2 * ch)
            val l = ShortArray(n); val r = if (ch == 2) ShortArray(n) else null
            var o = 44
            for (i in 0 until n) {
                l[i] = b.getShort(o); o += 2
                if (r != null) { r[i] = b.getShort(o); o += 2 }
            }
            return Pcm(l, r)
        } catch (_: Throwable) { return null }
    }
}

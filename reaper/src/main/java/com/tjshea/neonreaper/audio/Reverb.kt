package com.tjshea.neonreaper.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Stereo feedback-delay-network reverb: input diffusers, 8 slowly modulated delay lines with in-loop damping, a Householder
 * feedback matrix and decorrelated stereo taps. Output is normalised so that, for noise-like input, the wet RMS is about the
 * input RMS whatever the decay time or size (a send of 0.3 means roughly -10 dB of reverb).
 *
 * Processed in blocks of [B] samples (every line is longer than a block, so reads never depend on writes of the same block):
 * the inner loop is branch-free and keeps the eight line states in registers (~4x faster than a per-sample version).
 * Long dark tails ([ReverbSpec.half]) run the network at half the sample rate (decimate / linear interpolate): same sound for
 * a damped tail, half the CPU.
 */
internal object Reverb {
    private const val K = 8
    private const val B = 64
    private const val SZ = 4096
    private const val MASK = SZ - 1
    private val BASE = intArrayOf(499, 619, 743, 863, 997, 1129, 1277, 1439)
    private val AP = intArrayOf(89, 139, 197, 263)
    private val LFO = doubleArrayOf(0.31, 0.43, 0.53, 0.67, 0.37, 0.47, 0.59, 0.71)

    /** Measured raw network gain for white noise: about CAL * rt60^0.19 * size^-0.2 (see AudioDspTest.reverbGainIsNormalised). */
    private const val CAL = 0.30
    private const val CAL_HALF = 0.30

    /**
     * Runs [src] (first [srcN] samples, then [tailN] samples of silence) through the reverb and ADDS the result times [gain]
     * into [dstL]/[dstR]. With [wrap] the output index wraps modulo dst length (circular loops: the tail folds onto the start,
     * which is exactly the periodic steady state of a linear reverb).
     */
    fun run(src: FloatArray, srcN: Int, spec: ReverbSpec, tailN: Int, gain: Float, dstL: FloatArray, dstR: FloatArray, wrap: Boolean = false) {
        if (!spec.half) { core(src, srcN, spec, tailN, gain, dstL, dstR, wrap, 1); return }
        // decimate by 2 with a [1 2 1]/4 filter, run the network at 11.025 kHz, upsample linearly while adding
        val total = srcN + tailN
        val hs = (srcN + 1) / 2; val ht = (total + 1) / 2
        val dec = FloatArray(hs)
        for (k in 0 until hs) {
            val a = if (2 * k - 1 >= 0) src[2 * k - 1] else 0f
            val c = if (2 * k + 1 < srcN) src[2 * k + 1] else 0f
            val b = if (2 * k < srcN) src[2 * k] else 0f
            dec[k] = 0.25f * a + 0.5f * b + 0.25f * c
        }
        val hl = FloatArray(ht + 2); val hr = FloatArray(ht + 2)
        core(dec, hs, spec, ht - hs, 1f, hl, hr, false, 2)
        val dn = dstL.size
        var idx = if (wrap) 0 else 0
        for (i in 0 until total) {
            val k = i shr 1
            val f = if (i and 1 == 0) 0f else 0.5f
            val l = (hl[k] + (hl[k + 1] - hl[k]) * f) * gain
            val r = (hr[k] + (hr[k + 1] - hr[k]) * f) * gain
            if (wrap) { val j = i % dn; dstL[j] += l; dstR[j] += r }
            else { if (i >= dn) break; dstL[i] += l; dstR[i] += r }
            idx++
        }
    }

    /** The network itself; [d] = 1 (22.05 kHz) or 2 (11.025 kHz): all times and lengths scale by 1/d. */
    private fun core(src: FloatArray, srcN: Int, spec: ReverbSpec, tailN: Int, gain: Float, dstL: FloatArray, dstR: FloatArray, wrap: Boolean, d: Int) {
        val sr = SRD / d
        val len = IntArray(K) { max(B + 16, (BASE[it] * spec.size / d).roundToInt()) }
        val bufs = Array(K) { FloatArray(SZ) }
        val reg = FloatArray(K * (B + 1))
        val ws = FloatArray(K * B)
        val xin = FloatArray(B)
        val oLb = FloatArray(B); val oRb = FloatArray(B)
        val fbg = FloatArray(K) { 10.0.pow(-3.0 * len[it] / (spec.rt60 * sr)).toFloat() }
        val g0 = fbg[0]; val g1 = fbg[1]; val g2 = fbg[2]; val g3 = fbg[3]; val g4 = fbg[4]; val g5 = fbg[5]; val g6 = fbg[6]; val g7 = fbg[7]
        val aD = (1.0 - kotlin.math.exp(-TWO_PI * spec.damp / sr)).toFloat()
        var lp0 = 0f; var lp1 = 0f; var lp2 = 0f; var lp3 = 0f; var lp4 = 0f; var lp5 = 0f; var lp6 = 0f; var lp7 = 0f
        val lfoPh = DoubleArray(K) { it * 0.173 }
        val lfoInc = DoubleArray(K) { LFO[it] * B / sr }
        val fr = FloatArray(K)
        val apBuf = Array(4) { FloatArray(max(16, AP[it] / d)) }
        val apPos = IntArray(4)
        val pdN = max(1, (spec.pre * sr).toInt()); val pd = FloatArray(pdN); var pdPos = 0
        val hpA = (1.0 - kotlin.math.exp(-TWO_PI * spec.hp / sr)).toFloat(); var hpS = 0f
        val dg = spec.diff.toFloat()
        val stages = spec.stages.coerceIn(1, 4)
        val norm = gain / (CAL * spec.rt60.pow(0.19) * spec.size.pow(-0.2)).toFloat() * 0.5f * (if (d == 2) HALF_FIX else 1f)
        val dn = dstL.size
        val total = srcN + tailN
        val modAmp = spec.mod / d
        val stride = B + 1
        var wp = 0
        var done = 0
        while (done < total) {
            // ---- input conditioning: high-pass, pre-delay, allpass diffusers
            for (t in 0 until B) {
                val i = done + t
                val x0 = if (i < srcN) src[i] else 0f
                hpS += hpA * (x0 - hpS)
                var x = x0 - hpS
                val xd = pd[pdPos]; pd[pdPos] = x; if (++pdPos == pdN) pdPos = 0
                x = xd
                for (a in 0 until stages) {
                    val b = apBuf[a]; val p = apPos[a]
                    val dd = b[p]
                    val w = x + dg * dd
                    b[p] = w
                    x = dd - dg * w
                    if (p + 1 == b.size) apPos[a] = 0 else apPos[a] = p + 1
                }
                xin[t] = x * 0.3f
            }
            // ---- gather each line's (modulated) delayed window
            for (j in 0 until K) {
                var ph = lfoPh[j] + lfoInc[j]; if (ph >= 1.0) ph -= 1.0; lfoPh[j] = ph
                val dl = len[j] + modAmp * Tables.sine[(ph * Tables.SIN_N).toInt()]
                val di = dl.toInt()
                fr[j] = (dl - di).toFloat()
                val s = (wp - di - 1) and MASK
                val b = bufs[j]; val rb = j * stride
                if (s + stride <= SZ) System.arraycopy(b, s, reg, rb, stride)
                else { val first = SZ - s; System.arraycopy(b, s, reg, rb, first); System.arraycopy(b, 0, reg, rb + first, stride - first) }
            }
            val f0 = fr[0]; val f1 = fr[1]; val f2 = fr[2]; val f3 = fr[3]; val f4 = fr[4]; val f5 = fr[5]; val f6 = fr[6]; val f7 = fr[7]
            for (t in 0 until B) {
                val y0 = reg[t + 1] + (reg[t] - reg[t + 1]) * f0
                val y1 = reg[stride + t + 1] + (reg[stride + t] - reg[stride + t + 1]) * f1
                val y2 = reg[2 * stride + t + 1] + (reg[2 * stride + t] - reg[2 * stride + t + 1]) * f2
                val y3 = reg[3 * stride + t + 1] + (reg[3 * stride + t] - reg[3 * stride + t + 1]) * f3
                val y4 = reg[4 * stride + t + 1] + (reg[4 * stride + t] - reg[4 * stride + t + 1]) * f4
                val y5 = reg[5 * stride + t + 1] + (reg[5 * stride + t] - reg[5 * stride + t + 1]) * f5
                val y6 = reg[6 * stride + t + 1] + (reg[6 * stride + t] - reg[6 * stride + t + 1]) * f6
                val y7 = reg[7 * stride + t + 1] + (reg[7 * stride + t] - reg[7 * stride + t + 1]) * f7
                lp0 += aD * (y0 - lp0); lp1 += aD * (y1 - lp1); lp2 += aD * (y2 - lp2); lp3 += aD * (y3 - lp3)
                lp4 += aD * (y4 - lp4); lp5 += aD * (y5 - lp5); lp6 += aD * (y6 - lp6); lp7 += aD * (y7 - lp7)
                oLb[t] = lp0 - lp1 + lp2 - lp3 + lp4 - lp5 + lp6 - lp7
                oRb[t] = lp0 + lp1 - lp2 - lp3 + lp4 + lp5 - lp6 - lp7
                val fb = (lp0 + lp1 + lp2 + lp3 + lp4 + lp5 + lp6 + lp7) * 0.25f
                val inj = xin[t]
                ws[t] = inj + (lp0 - fb) * g0
                ws[B + t] = inj + (lp1 - fb) * g1
                ws[2 * B + t] = inj + (lp2 - fb) * g2
                ws[3 * B + t] = inj + (lp3 - fb) * g3
                ws[4 * B + t] = inj + (lp4 - fb) * g4
                ws[5 * B + t] = inj + (lp5 - fb) * g5
                ws[6 * B + t] = inj + (lp6 - fb) * g6
                ws[7 * B + t] = inj + (lp7 - fb) * g7
            }
            // ---- write the new block into the delay lines
            val s = wp and MASK
            for (j in 0 until K) {
                val b = bufs[j]
                if (s + B <= SZ) System.arraycopy(ws, j * B, b, s, B)
                else { val first = SZ - s; System.arraycopy(ws, j * B, b, s, first); System.arraycopy(ws, j * B + first, b, 0, B - first) }
            }
            // ---- output
            val cnt = min(B, total - done)
            if (!wrap) {
                val m = min(cnt, dn - done)
                for (t in 0 until m) { dstL[done + t] += oLb[t] * norm; dstR[done + t] += oRb[t] * norm }
            } else {
                var idx = done % dn
                for (t in 0 until cnt) {
                    dstL[idx] += oLb[t] * norm; dstR[idx] += oRb[t] * norm
                    if (++idx == dn) idx = 0
                }
            }
            wp += B; done += B
        }
    }

    /** Empirical level correction of the half-rate network (decimation filter + shorter lines), see AudioDspTest. */
    private const val HALF_FIX = 1.25f
}

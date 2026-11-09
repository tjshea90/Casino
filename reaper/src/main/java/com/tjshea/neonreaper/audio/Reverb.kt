package com.tjshea.neonreaper.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Stereo feedback-delay-network reverb: 4 input diffusers, 8 slowly modulated delay lines with in-loop damping, a Householder
 * feedback matrix and decorrelated stereo taps. Output is normalised so that, for noise-like input, the wet RMS is about the
 * input RMS whatever the decay time or size (a send of 0.3 means roughly -10 dB of reverb).
 *
 * Processed in blocks of [B] samples (every line is longer than a block, so reads never depend on writes of the same block):
 * the inner loop is branch-free and keeps the eight line states in registers, which is ~4x faster than a per-sample version.
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
    @JvmField var debugSamples = 0L

    /**
     * Runs [src] (first [srcN] samples, then [tailN] samples of silence) through the reverb and ADDS the result times [gain]
     * into [dstL]/[dstR]. With [wrap] the output index wraps modulo dst length (circular loops: the tail folds onto the start,
     * which is exactly the periodic steady state of a linear reverb).
     */
    fun run(src: FloatArray, srcN: Int, spec: ReverbSpec, tailN: Int, gain: Float, dstL: FloatArray, dstR: FloatArray, wrap: Boolean = false) {
        val len = IntArray(K) { max(B + 16, (BASE[it] * spec.size).roundToInt()) }
        val bufs = Array(K) { FloatArray(SZ) }
        val reg = FloatArray(K * (B + 1))
        val ws = FloatArray(K * B)
        val xin = FloatArray(B)
        val oLb = FloatArray(B); val oRb = FloatArray(B)
        val g0 = 10.0.pow(-3.0 * len[0] / (spec.rt60 * SR)).toFloat(); val g1 = 10.0.pow(-3.0 * len[1] / (spec.rt60 * SR)).toFloat()
        val g2 = 10.0.pow(-3.0 * len[2] / (spec.rt60 * SR)).toFloat(); val g3 = 10.0.pow(-3.0 * len[3] / (spec.rt60 * SR)).toFloat()
        val g4 = 10.0.pow(-3.0 * len[4] / (spec.rt60 * SR)).toFloat(); val g5 = 10.0.pow(-3.0 * len[5] / (spec.rt60 * SR)).toFloat()
        val g6 = 10.0.pow(-3.0 * len[6] / (spec.rt60 * SR)).toFloat(); val g7 = 10.0.pow(-3.0 * len[7] / (spec.rt60 * SR)).toFloat()
        val aD = onePoleCoef(spec.damp).toFloat()
        var lp0 = 0f; var lp1 = 0f; var lp2 = 0f; var lp3 = 0f; var lp4 = 0f; var lp5 = 0f; var lp6 = 0f; var lp7 = 0f
        val lfoPh = DoubleArray(K) { it * 0.173 }
        val lfoInc = DoubleArray(K) { LFO[it] * B / SRD }
        val fr = FloatArray(K)
        val apBuf = Array(4) { FloatArray(AP[it]) }
        val apPos = IntArray(4)
        val pdN = max(1, frames(spec.pre)); val pd = FloatArray(pdN); var pdPos = 0
        val hpA = onePoleCoef(spec.hp).toFloat(); var hpS = 0f
        val dg = spec.diff.toFloat()
        val stages = spec.stages.coerceIn(1, 4)
        val norm = gain / (CAL * spec.rt60.pow(0.19) * spec.size.pow(-0.2)).toFloat() * 0.5f
        val dn = dstL.size
        val total = srcN + tailN
        debugSamples += total
        val modAmp = spec.mod
        val stride = B + 1
        var wp = 0
        var done = 0
        while (done < total) {
            // ---- input conditioning: high-pass, pre-delay, 4 allpass diffusers
            for (t in 0 until B) {
                val i = done + t
                val x0 = if (i < srcN) src[i] else 0f
                hpS += hpA * (x0 - hpS)
                var x = x0 - hpS
                val xd = pd[pdPos]; pd[pdPos] = x; if (++pdPos == pdN) pdPos = 0
                x = xd
                for (a in 0 until stages) {
                    val b = apBuf[a]; val p = apPos[a]
                    val d = b[p]
                    val w = x + dg * d
                    b[p] = w
                    x = d - dg * w
                    if (p + 1 == b.size) apPos[a] = 0 else apPos[a] = p + 1
                }
                xin[t] = x * 0.3f
            }
            // ---- gather each line's (modulated) delayed window
            for (j in 0 until K) {
                var ph = lfoPh[j] + lfoInc[j]; if (ph >= 1.0) ph -= 1.0; lfoPh[j] = ph
                val d = len[j] + modAmp * Tables.sine[(ph * Tables.SIN_N).toInt()]
                val di = d.toInt()
                fr[j] = (d - di).toFloat()
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
}

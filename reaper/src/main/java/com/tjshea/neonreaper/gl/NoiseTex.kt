package com.tjshea.neonreaper.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A small tileable procedural noise texture generated once on the CPU. Shaders sample it instead of evaluating hash-based
 * fbm per pixel (a texture fetch is ~20x cheaper than 4 octaves of 3D value noise on a mobile GPU).
 *
 *   R  soft fbm (clouds, fog, cloth mottling)        G  finer fbm (stone / bone grain, bump)
 *   B  ridged noise (cracks, veins, wood grain)      A  cellular / Worley F1 (pits, scales, skin)
 */
object NoiseTex {
    const val SIZE = 256

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 144665
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }

    /** Tileable value noise with [freq] lattice cells across the texture. */
    private fun value(u: Float, v: Float, freq: Int, seed: Int): Float {
        val x = u * freq; val y = v * freq
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        var fx = x - xi; var fy = y - yi
        fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
        fun h(a: Int, b: Int) = hash(Math.floorMod(a, freq), Math.floorMod(b, freq), seed)
        val a = h(xi, yi); val b = h(xi + 1, yi); val c = h(xi, yi + 1); val d = h(xi + 1, yi + 1)
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy
    }

    private fun fbm(u: Float, v: Float, baseFreq: Int, octaves: Int, seed: Int): Float {
        var amp = 0.5f; var sum = 0f; var norm = 0f; var f = baseFreq
        for (o in 0 until octaves) { sum += amp * value(u, v, f, seed + o * 17); norm += amp; amp *= 0.5f; f *= 2 }
        return sum / norm
    }

    /** Worley F1 distance (0..1) with one jittered point per cell, wrapped so it tiles. */
    private fun cellular(u: Float, v: Float, freq: Int, seed: Int): Float {
        val x = u * freq; val y = v * freq
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        var best = 9f
        for (j in -1..1) for (i in -1..1) {
            val cx = xi + i; val cy = yi + j
            val px = cx + hash(Math.floorMod(cx, freq), Math.floorMod(cy, freq), seed)
            val py = cy + hash(Math.floorMod(cx, freq), Math.floorMod(cy, freq), seed + 91)
            val dx = px - x; val dy = py - y
            best = min(best, dx * dx + dy * dy)
        }
        return min(1f, sqrt(best))
    }

    fun build(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder())
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val u = (x + 0.5f) / SIZE; val v = (y + 0.5f) / SIZE
            val r = fbm(u, v, 4, 5, 11)
            val g = fbm(u, v, 8, 4, 23)
            val b = 1f - abs(2f * fbm(u, v, 4, 4, 37) - 1f)
            val a = cellular(u, v, 10, 53)
            fun enc(f: Float) = (f.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            buf.put(enc(r)).put(enc(g)).put(enc(b)).put(enc(a))
        }
        buf.position(0)
        return buf
    }
}

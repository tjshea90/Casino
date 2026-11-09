package com.tjshea.neonreaper.gl.chars

import com.tjshea.neonreaper.gl.Mat4
import com.tjshea.neonreaper.gl.MeshData
import com.tjshea.neonreaper.gl.PartBuilder
import com.tjshea.neonreaper.gl.Sculpt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared helpers of the CHARS-A models (plague doctor, demon, cursed skull): curve paths for [Sculpt.tube], matrix pivots and a
 * few cheap procedural pieces. Build-time helpers allocate freely (meshes are built once); the per-frame helpers do not allocate.
 */
internal object KitA {
    const val TAU = (2 * PI).toFloat()

    fun v3(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    /** Smooth Catmull-Rom path through [pts], parameter t in 0..1 (for [Sculpt.tube]). */
    fun spline(pts: List<FloatArray>): (Float) -> FloatArray {
        val n = pts.size - 1
        return { t ->
            val f = (t * n).coerceIn(0f, n - 1e-4f)
            val i = f.toInt(); val u = f - i
            val p0 = pts[max(i - 1, 0)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[min(i + 2, n)]
            val u2 = u * u; val u3 = u2 * u
            FloatArray(3) { c -> 0.5f * (2f * p1[c] + (p2[c] - p0[c]) * u + (2f * p0[c] - 5f * p1[c] + 4f * p2[c] - p3[c]) * u2 + (3f * p1[c] - p0[c] - 3f * p2[c] + p3[c]) * u3) }
        }
    }

    /** Straight segments through [pts] (sharp corners), parameter t in 0..1. */
    fun poly(pts: List<FloatArray>): (Float) -> FloatArray {
        val n = pts.size - 1
        return { t ->
            val f = (t * n).coerceIn(0f, n - 1e-4f)
            val i = f.toInt(); val u = f - i
            FloatArray(3) { c -> pts[i][c] + (pts[i + 1][c] - pts[i][c]) * u }
        }
    }

    /** Horizontal ring path (ellipse rx x rz) centred at (cx, cy, cz). */
    fun ring(cx: Float, cy: Float, cz: Float, rx: Float, rz: Float): (Float) -> FloatArray =
        { t -> floatArrayOf(cx + rx * cos(TAU * t), cy, cz + rz * sin(TAU * t)) }

    /** Polar radius scale that turns a circle into an ellipse with depth/width ratio [zr] (x semi-axis stays 1, z semi-axis is zr). */
    fun ell(ang: Float, zr: Float): Float { val c = cos(ang); val s = sin(ang); return zr / sqrt(zr * zr * c * c + s * s) }

    /** A cheap chain link (wire loop in the XY plane, 36 triangles). */
    fun link(len: Float, w: Float, r: Float): MeshData =
        Sculpt.tube(6, 3, { t -> val a = TAU * t; floatArrayOf((w / 2) * cos(a), (len / 2) * sin(a), 0f) }, { r })

    /**
     * Baked shading for low-poly parts: multiplies each vertex colour by [f](x, y, z, nx, ny, nz) (part space, before placement).
     * This is how the characters get depth (hem grime, contact shadow, sky occlusion) from a few hundred triangles.
     */
    fun shade(d: MeshData, f: (Float, Float, Float, Float, Float, Float) -> Float): MeshData {
        val s = MeshData.STRIDE
        for (k in 0 until d.vertexCount) {
            val p = k * s
            val m = f(d.v[p], d.v[p + 1], d.v[p + 2], d.v[p + 3], d.v[p + 4], d.v[p + 5])
            d.v[p + 8] *= m; d.v[p + 9] *= m; d.v[p + 10] *= m
        }
        return d
    }

    /** Adds an already tinted composite (several materials) with the same T*Ry*Rx*Rz*S order as [PartBuilder.add]. */
    fun addRaw(pb: PartBuilder, d: MeshData, x: Float = 0f, y: Float = 0f, z: Float = 0f, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f,
               sx: Float = 1f, sy: Float = sx, sz: Float = sx) {
        val m = Mat4.identity()
        Mat4.translate(m, x, y, z)
        if (ry != 0f) Mat4.rotate(m, ry, 0f, 1f, 0f)
        if (rx != 0f) Mat4.rotate(m, rx, 1f, 0f, 0f)
        if (rz != 0f) Mat4.rotate(m, rz, 0f, 0f, 1f)
        Mat4.scale(m, sx, sy, sz)
        pb.data.append(d, m)
    }

    // ------------------------------------------------------------------------------------------ per frame (no allocation)
    fun set(dst: FloatArray, src: FloatArray) { System.arraycopy(src, 0, dst, 0, 16) }

    /** dst = src * T(p) * Ry * Rx * Rz * T(-p): rotation (degrees) about the pivot point p, in src's local space. */
    fun pivot(dst: FloatArray, src: FloatArray, px: Float, py: Float, pz: Float, rx: Float, ry: Float, rz: Float) {
        System.arraycopy(src, 0, dst, 0, 16)
        Mat4.translate(dst, px, py, pz)
        if (ry != 0f) Mat4.rotate(dst, ry, 0f, 1f, 0f)
        if (rx != 0f) Mat4.rotate(dst, rx, 1f, 0f, 0f)
        if (rz != 0f) Mat4.rotate(dst, rz, 0f, 0f, 1f)
        Mat4.translate(dst, -px, -py, -pz)
    }

    fun frac(x: Float): Float = x - floor(x)

    /** Deterministic pseudo random 0..1 for an integer-ish seed. */
    fun hash(n: Float): Float = frac(sin(n * 12.9898f + 4.1414f) * 43758.547f)

    /** Smooth 0..1 flicker used for flames and embers: sum of incommensurate sines, never below ~0.55. */
    fun flicker(t: Float, seed: Float = 0f): Float =
        (0.78f + 0.12f * sin(t * 11.3f + seed) * sin(t * 4.7f + seed * 2f) + 0.10f * sin(t * 23.1f + seed * 3f)).coerceIn(0.5f, 1.1f)
}

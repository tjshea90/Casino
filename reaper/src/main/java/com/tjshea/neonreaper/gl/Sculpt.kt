package com.tjshea.neonreaper.gl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Procedural sculpting toolkit for the 3D cast. Everything returns plain [MeshData] (position, normal, uv, vertex colour/material)
 * so characters can be merged into a handful of meshes (see [PartBuilder]) instead of dozens of tiny draws.
 *
 * Conventions: a character is modelled in "character space": origin at the feet, +Y up, facing +Z, about 1 unit tall.
 * Material ids (vertex alpha = id / 15) are decoded by the lit shader's vertex-material mode ([Mat]).
 */
object Mat {
    const val MATTE = 0
    const val CLOTH = 1
    const val BONE = 2
    const val METAL = 3
    const val LEATHER = 4
    const val EMISSIVE = 5      // self-lit: eyes, flames, runes (brightness from the draw call's emissive factor)
    const val STONE = 6
    const val FLESH = 7         // demon skin with ember veins
    const val GLASS = 8
    const val WOOD = 9
    const val ECTO = 10         // translucent ghost matter
    const val FEATHER = 11
    const val WAX = 12
}

object Sculpt {
    private const val TAU = (2 * PI).toFloat()

    fun sstep(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    fun gauss(d2: Float, s: Float): Float = exp(-d2 / (s * s))
    fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
    fun v3(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private fun norm(v: FloatArray): FloatArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-6f); return floatArrayOf(v[0] / l, v[1] / l, v[2] / l) }
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun perp(t: FloatArray): FloatArray {
        val a = if (abs(t[1]) < 0.9f) floatArrayOf(0f, 1f, 0f) else floatArrayOf(1f, 0f, 0f)
        return norm(cross(t, a))
    }

    // ------------------------------------------------------------------------------------------ mesh clean-up
    /** Flips any triangle whose winding disagrees with its vertex normals so back-face culling is always right. */
    fun MeshData.fixWinding(): MeshData {
        var k = 0
        while (k < inN) {
            val a = i[k].toInt() and 0xFFFF; val b = i[k + 1].toInt() and 0xFFFF; val c = i[k + 2].toInt() and 0xFFFF
            val S = MeshData.STRIDE
            val ux = v[b * S] - v[a * S]; val uy = v[b * S + 1] - v[a * S + 1]; val uz = v[b * S + 2] - v[a * S + 2]
            val wx = v[c * S] - v[a * S]; val wy = v[c * S + 1] - v[a * S + 1]; val wz = v[c * S + 2] - v[a * S + 2]
            val gx = uy * wz - uz * wy; val gy = uz * wx - ux * wz; val gz = ux * wy - uy * wx
            val nx = v[a * S + 3] + v[b * S + 3] + v[c * S + 3]; val ny = v[a * S + 4] + v[b * S + 4] + v[c * S + 4]; val nz = v[a * S + 5] + v[b * S + 5] + v[c * S + 5]
            if (gx * nx + gy * ny + gz * nz < 0f) { val t = i[k + 1]; i[k + 1] = i[k + 2]; i[k + 2] = t }
            k += 3
        }
        return this
    }

    /** Averages the normals of vertices that share a position (hides the seam of lathes / spheres). */
    fun MeshData.weldNormals(): MeshData {
        val S = MeshData.STRIDE
        val acc = HashMap<Long, FloatArray>()
        fun key(x: Float, y: Float, z: Float): Long {
            val qx = Math.round(x * 2000f).toLong() and 0x1FFFFF; val qy = Math.round(y * 2000f).toLong() and 0x1FFFFF; val qz = Math.round(z * 2000f).toLong() and 0x1FFFFF
            return qx or (qy shl 21) or (qz shl 42)
        }
        for (q in 0 until vertexCount) {
            val a = acc.getOrPut(key(v[q * S], v[q * S + 1], v[q * S + 2])) { FloatArray(3) }
            a[0] += v[q * S + 3]; a[1] += v[q * S + 4]; a[2] += v[q * S + 5]
        }
        for (q in 0 until vertexCount) {
            val a = acc[key(v[q * S], v[q * S + 1], v[q * S + 2])]!!
            val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).coerceAtLeast(1e-6f)
            v[q * S + 3] = a[0] / l; v[q * S + 4] = a[1] / l; v[q * S + 5] = a[2] / l
        }
        return this
    }

    /** Recomputes smooth normals, welds seams and fixes winding: the finishing pass for every custom mesh. */
    fun MeshData.finish(): MeshData { computeNormals(); weldNormals(); fixWinding(); return this }

    // ------------------------------------------------------------------------------------------ tube (swept circle)
    /**
     * Sweeps a circle of radius [radius](t) along [path](t) (t = 0..1, [steps] segments) with parallel-transport frames.
     * A zero radius at an end gives a pointed tip (horns, thorns, claws); [cap] closes blunt ends.
     */
    fun tube(steps: Int, sides: Int, path: (Float) -> FloatArray, radius: (Float) -> Float, cap: Boolean = false): MeshData {
        val m = MeshData()
        val pts = Array(steps + 1) { path(it.toFloat() / steps) }
        val tan = Array(steps + 1) { idx ->
            val a = pts[max(idx - 1, 0)]; val b = pts[min(idx + 1, steps)]
            norm(floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2]))
        }
        var n = perp(tan[0])
        val ring = IntArray(steps + 1)
        for (idx in 0..steps) {
            if (idx > 0) { val d = dot(n, tan[idx]); n = norm(floatArrayOf(n[0] - tan[idx][0] * d, n[1] - tan[idx][1] * d, n[2] - tan[idx][2] * d)) }
            val b = cross(tan[idx], n)
            val r = radius(idx.toFloat() / steps)
            ring[idx] = m.vertexCount
            for (s in 0..sides) {
                val a = TAU * s / sides; val ca = cos(a); val sa = sin(a)
                val nx = n[0] * ca + b[0] * sa; val ny = n[1] * ca + b[1] * sa; val nz = n[2] * ca + b[2] * sa
                m.vertex(pts[idx][0] + nx * r, pts[idx][1] + ny * r, pts[idx][2] + nz * r, nx, ny, nz, s.toFloat() / sides, idx.toFloat() / steps)
            }
        }
        for (idx in 0 until steps) for (s in 0 until sides) { val p = ring[idx] + s; val q = ring[idx + 1] + s; m.quad(p, p + 1, q + 1, q) }
        if (cap) for (end in intArrayOf(0, steps)) {
            val c = m.vertex(pts[end][0], pts[end][1], pts[end][2], tan[end][0] * (if (end == 0) -1f else 1f), tan[end][1] * (if (end == 0) -1f else 1f), tan[end][2] * (if (end == 0) -1f else 1f), 0.5f, 0.5f)
            for (s in 0 until sides) m.tri(c, ring[end] + s, ring[end] + s + 1)
        }
        return m.finish()
    }

    /** A straight-ish bone / limb segment from [a] to [b] with end radii [ra], [rb] and a slight swelling in the middle. */
    fun limb(a: FloatArray, b: FloatArray, ra: Float, rb: Float, swell: Float = 0.12f, sides: Int = 8): MeshData =
        tube(6, sides, { t -> floatArrayOf(mix(a[0], b[0], t), mix(a[1], b[1], t), mix(a[2], b[2], t)) },
            { t -> mix(ra, rb, t) * (1f + swell * sin(PI.toFloat() * t)) }, cap = true)

    // ------------------------------------------------------------------------------------------ displaced sphere
    /**
     * A sphere whose radius in direction (dx,dy,dz) is [shape](dx,dy,dz), then scaled by (sx,sy,sz). +Z is the face direction.
     * Used for skulls, heads and any organic lump: sockets, brows and cheekbones are just smooth radial dents/bumps.
     */
    fun blob(lat: Int, lon: Int, sx: Float, sy: Float, sz: Float, ao: ((Float, Float) -> Float)? = null, shape: (Float, Float, Float) -> Float): MeshData {
        val m = MeshData()
        for (a in 0..lat) {
            val th = PI * a / lat
            for (b in 0..lon) {
                val ph = 2 * PI * b / lon
                val dx = (sin(th) * cos(ph)).toFloat(); val dy = cos(th).toFloat(); val dz = (sin(th) * sin(ph)).toFloat()
                val r = shape(dx, dy, dz)
                val o = ao?.invoke(r, dy) ?: 1f                     // baked occlusion from the displacement (dents are dark)
                m.vertex(dx * r * sx, dy * r * sy, dz * r * sz, dx, dy, dz, b.toFloat() / lon, a.toFloat() / lat, o, o, o, 0f)
            }
        }
        for (a in 0 until lat) for (b in 0 until lon) { val p = a * (lon + 1) + b; val q = p + lon + 1; m.quad(p, p + 1, q + 1, q) }
        return m.finish()
    }

    fun ellipsoid(sx: Float, sy: Float, sz: Float, lat: Int = 12, lon: Int = 18): MeshData = blob(lat, lon, sx, sy, sz, null) { _, _, _ -> 1f }

    // ------------------------------------------------------------------------------------------ cloth shells
    /**
     * Surface of revolution around Y (profile = (radius, y) top to bottom) with per-vertex modulation:
     * [radial](angle, row, t) scales the radius (folds, hood peak), [dy](angle, row, t) moves the vertex vertically (ragged hem).
     * Angles in [a0, a1] only, so a hood can leave its front open (+Z is angle PI/2).
     */
    fun shell(profile: List<Pair<Float, Float>>, segs: Int, a0: Float = 0f, a1: Float = TAU,
              radial: (Float, Int, Float) -> Float = { _, _, _ -> 1f }, dy: (Float, Int, Float) -> Float = { _, _, _ -> 0f }): MeshData {
        val m = MeshData()
        val rows = profile.size
        for ((k, pr) in profile.withIndex()) {
            val t = k.toFloat() / (rows - 1)
            for (s in 0..segs) {
                val ang = a0 + (a1 - a0) * s / segs
                val r = pr.first * radial(ang, k, t)
                m.vertex(r * cos(ang), pr.second + dy(ang, k, t), r * sin(ang), 0f, 1f, 0f, s.toFloat() / segs, t)
            }
        }
        for (k in 0 until rows - 1) for (s in 0 until segs) { val p = k * (segs + 1) + s; val q = p + segs + 1; m.quad(p, p + 1, q + 1, q) }
        return m.finish()
    }

    /** A hanging tattered strip (flat tapered ribbon with a slight curl) used for rags, banners and shreds. Top edge at y = 0. */
    fun rag(width: Float, length: Float, curl: Float = 0.12f, seg: Int = 5): MeshData {
        val m = MeshData()
        for (k in 0..seg) {
            val t = k.toFloat() / seg
            val w = width * (1f - 0.75f * t * t) * 0.5f
            val z = curl * t * t
            m.vertex(-w, -length * t, z, 0f, 0f, 1f, 0f, t); m.vertex(w, -length * t, z, 0f, 0f, 1f, 1f, t)
        }
        for (k in 0 until seg) { val p = k * 2; m.quad(p, p + 1, p + 3, p + 2) }
        return m.finish()
    }

    /** A chain link: a flattened torus in the XY plane, [len] long, [w] wide, wire radius [r]. */
    fun link(len: Float, w: Float, r: Float, steps: Int = 14, sides: Int = 6): MeshData =
        tube(steps, sides, { t -> val a = TAU * t; floatArrayOf((w / 2) * cos(a), (len / 2) * sin(a), 0f) }, { r })

    // ------------------------------------------------------------------------------------------ simple solids
    /** Box centred on the origin with half-extents (hx, hy, hz); flat shaded. */
    fun box(hx: Float, hy: Float, hz: Float): MeshData {
        val m = MeshData()
        fun face(nx: Float, ny: Float, nz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float) {
            val c = floatArrayOf(nx * hx, ny * hy, nz * hz)
            val a = m.vertex(c[0] - ux * hx - vx * hx, c[1] - uy * hy - vy * hy, c[2] - uz * hz - vz * hz, nx, ny, nz, 0f, 0f)
            val b = m.vertex(c[0] + ux * hx - vx * hx, c[1] + uy * hy - vy * hy, c[2] + uz * hz - vz * hz, nx, ny, nz, 1f, 0f)
            val d = m.vertex(c[0] + ux * hx + vx * hx, c[1] + uy * hy + vy * hy, c[2] + uz * hz + vz * hz, nx, ny, nz, 1f, 1f)
            val e = m.vertex(c[0] - ux * hx + vx * hx, c[1] - uy * hy + vy * hy, c[2] - uz * hz + vz * hz, nx, ny, nz, 0f, 1f)
            m.quad(a, b, d, e)
        }
        face(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f); face(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)
        face(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f); face(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f)
        face(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f); face(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        return m.fixWinding()
    }

    /** Cone / spike from y = 0 (radius [r]) to y = [h] (point), [sides] facets. */
    fun spike(r: Float, h: Float, sides: Int = 8): MeshData =
        tube(4, sides, { t -> floatArrayOf(0f, h * t, 0f) }, { t -> r * (1f - t) }, cap = true)
}

/**
 * Accumulates parts into ONE merged mesh: each part is transformed, tinted, given a material id and darkened by a baked
 * ambient-occlusion factor. Add the parts of a rigid piece (a torso, a head, a scythe) and upload the result as a single [Mesh].
 */
class PartBuilder {
    val data = MeshData()
    private val m = FloatArray(16)

    /** Adds [part] placed at (x,y,z) with rotations (degrees, applied X then Y then Z) and scale (sx,sy,sz). */
    fun add(part: MeshData, tint: Int, mat: Int, x: Float = 0f, y: Float = 0f, z: Float = 0f,
            rx: Float = 0f, ry: Float = 0f, rz: Float = 0f, sx: Float = 1f, sy: Float = sx, sz: Float = sx, ao: Float = 1f): PartBuilder {
        Mat4.identity(m)
        Mat4.translate(m, x, y, z)
        if (ry != 0f) Mat4.rotate(m, ry, 0f, 1f, 0f)
        if (rx != 0f) Mat4.rotate(m, rx, 1f, 0f, 0f)
        if (rz != 0f) Mat4.rotate(m, rz, 0f, 0f, 1f)
        Mat4.scale(m, sx, sy, sz)
        data.append(part, m, tint, mat, ao)
        return this
    }

    fun build(): Mesh = Mesh(data)
}

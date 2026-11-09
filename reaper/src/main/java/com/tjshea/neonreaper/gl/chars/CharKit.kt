package com.tjshea.neonreaper.gl.chars

import com.tjshea.neonreaper.gl.Bones
import com.tjshea.neonreaper.gl.MeshData
import com.tjshea.neonreaper.gl.PartBuilder
import com.tjshea.neonreaper.gl.Sculpt
import com.tjshea.neonreaper.gl.Sculpt.finish
import com.tjshea.neonreaper.gl.Sculpt.fixWinding
import com.tjshea.neonreaper.gl.Sculpt.sstep
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A convex polygon in the XY plane, counter-clockwise (x right, y up). */
typealias Poly = List<Pair<Float, Float>>

/**
 * Small modelling helpers shared by the CHARS-B models (coffin, wraith, raven): convex-polygon prisms and bevels, surface
 * strips, chains laid along a path, rune glyphs and a few cheap parts. Everything returns plain [MeshData].
 */
internal object CharKit {
    private const val TAU = (2 * PI).toFloat()

    // ------------------------------------------------------------------------------------------ tiny vector helpers
    fun unit(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f); return floatArrayOf(x / l, y / l, z / l) }
    fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    // ------------------------------------------------------------------------------------------ polygons
    private fun inNormal(a: Pair<Float, Float>, b: Pair<Float, Float>): Pair<Float, Float> {
        val dx = b.first - a.first; val dy = b.second - a.second
        val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
        return Pair(-dy / l, dx / l)                       // left of the travel direction = inwards for a CCW polygon
    }

    /** Parallel offset of a convex CCW polygon by [d] inwards (negative = outwards), mitred corners. */
    fun inset(poly: Poly, d: Float): Poly {
        val n = poly.size
        val out = ArrayList<Pair<Float, Float>>(n)
        for (i in 0 until n) {
            val p0 = poly[(i + n - 1) % n]; val p1 = poly[i]; val p2 = poly[(i + 1) % n]
            val n1 = inNormal(p0, p1); val n2 = inNormal(p1, p2)
            val k = d / (1f + n1.first * n2.first + n1.second * n2.second)
            out += Pair(p1.first + (n1.first + n2.first) * k, p1.second + (n1.second + n2.second) * k)
        }
        return out
    }

    /** Distance from (x, y) to the nearest edge, positive inside a convex CCW polygon. */
    fun insideDist(poly: Poly, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        val n = poly.size
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[(i + 1) % n]
            val nn = inNormal(a, b)
            best = min(best, (x - a.first) * nn.first + (y - a.second) * nn.second)
        }
        return best
    }

    /** Horizontal extent (minX, maxX) of a convex polygon at height [y]. */
    fun xRange(poly: Poly, y: Float): Pair<Float, Float> {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        val n = poly.size
        for (i in 0 until n) {
            val a = poly[i]; val b = poly[(i + 1) % n]
            if ((a.second - y) * (b.second - y) <= 0f && a.second != b.second) {
                val x = a.first + (b.first - a.first) * (y - a.second) / (b.second - a.second)
                lo = min(lo, x); hi = max(hi, x)
            }
        }
        return Pair(lo, hi)
    }

    /** Closed path (t = 0..1) around a polygon at depth [z]; sample with a multiple of the vertex count for crisp corners. */
    fun loopPath(poly: Poly, z: Float): (Float) -> FloatArray = { t ->
        val n = poly.size
        val f = (t - floor(t)) * n
        val i = f.toInt().coerceAtMost(n - 1); val u = f - i
        val a = poly[i]; val b = poly[(i + 1) % n]
        floatArrayOf(a.first + (b.first - a.first) * u, a.second + (b.second - a.second) * u, z)
    }

    // ------------------------------------------------------------------------------------------ prisms and bevels
    /**
     * Flat-shaded band between polygon [a] at depth [za] and polygon [b] (same vertex count) at depth [zb]: the side walls of a prism,
     * or a bevel when [b] is inset. Normals point outwards (inwards when [outward] is false). [shadeA] / [shadeB] darken the two rims
     * (vertex colour), which gives cheap gradients on emissive interiors.
     */
    fun walls(a: Poly, za: Float, b: Poly, zb: Float, outward: Boolean = true, shadeA: Float = 1f, shadeB: Float = 1f): MeshData {
        val m = MeshData()
        val n = a.size
        val s = (if (zb >= za) 1f else -1f) * (if (outward) 1f else -1f)
        for (k in 0 until n) {
            val k1 = (k + 1) % n
            val ux = a[k1].first - a[k].first; val uy = a[k1].second - a[k].second
            val vx = b[k].first - a[k].first; val vy = b[k].second - a[k].second; val vz = zb - za
            val nx = uy * vz; val ny = -(ux * vz); val nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
            val fx = s * nx / l; val fy = s * ny / l; val fz = s * nz / l
            val i0 = m.vertex(a[k].first, a[k].second, za, fx, fy, fz, 0f, 0f, shadeA, shadeA, shadeA)
            val i1 = m.vertex(a[k1].first, a[k1].second, za, fx, fy, fz, 1f, 0f, shadeA, shadeA, shadeA)
            val i2 = m.vertex(b[k1].first, b[k1].second, zb, fx, fy, fz, 1f, 1f, shadeB, shadeB, shadeB)
            val i3 = m.vertex(b[k].first, b[k].second, zb, fx, fy, fz, 0f, 1f, shadeB, shadeB, shadeB)
            m.quad(i0, i1, i2, i3)
        }
        return m.fixWinding()
    }

    /** Flat polygon at depth [z] facing +Z ([nz] > 0) or -Z. */
    fun cap(poly: Poly, z: Float, nz: Float): MeshData {
        val m = MeshData()
        val n = poly.size
        var cx = 0f; var cy = 0f
        for (p in poly) { cx += p.first; cy += p.second }
        cx /= n; cy /= n
        val c = m.vertex(cx, cy, z, 0f, 0f, nz, 0.5f, 0.5f)
        val ids = IntArray(n) { m.vertex(poly[it].first, poly[it].second, z, 0f, 0f, nz, 0f, 0f) }
        for (k in 0 until n) m.tri(c, ids[k], ids[(k + 1) % n])
        return m.fixWinding()
    }

    /** Flat ring between an outer and an inner polygon at depth [z]. */
    fun ring(outer: Poly, inner: Poly, z: Float, nz: Float): MeshData {
        val m = MeshData()
        val n = outer.size
        val o = IntArray(n) { m.vertex(outer[it].first, outer[it].second, z, 0f, 0f, nz, 0f, 0f) }
        val i = IntArray(n) { m.vertex(inner[it].first, inner[it].second, z, 0f, 0f, nz, 1f, 1f) }
        for (k in 0 until n) { val k1 = (k + 1) % n; m.quad(o[k], o[k1], i[k1], i[k]) }
        return m.fixWinding()
    }

    /** A grid surface over [x0, x1] x [y0, y1] following height(x, y) (a +Z facing strip that hugs a bevelled surface). */
    fun grid(x0: Float, x1: Float, y0: Float, y1: Float, nx: Int, ny: Int, height: (Float, Float) -> Float): MeshData {
        val m = MeshData()
        for (j in 0..ny) for (i in 0..nx) {
            val x = x0 + (x1 - x0) * i / nx; val y = y0 + (y1 - y0) * j / ny
            m.vertex(x, y, height(x, y), 0f, 0f, 1f, i.toFloat() / nx, j.toFloat() / ny)
        }
        for (j in 0 until ny) for (i in 0 until nx) { val p = j * (nx + 1) + i; m.quad(p, p + 1, p + nx + 2, p + nx + 1) }
        return m.finish()
    }

    // ------------------------------------------------------------------------------------------ placement
    /** Appends [part] to [pb] with an arbitrary orthonormal basis (columns xa, ya, za) at position [p]. */
    fun place(pb: PartBuilder, part: MeshData, p: FloatArray, xa: FloatArray, ya: FloatArray, za: FloatArray, tint: Int, mat: Int, ao: Float = 1f) {
        val m = FloatArray(16)
        m[0] = xa[0]; m[1] = xa[1]; m[2] = xa[2]
        m[4] = ya[0]; m[5] = ya[1]; m[6] = ya[2]
        m[8] = za[0]; m[9] = za[1]; m[10] = za[2]
        m[12] = p[0]; m[13] = p[1]; m[14] = p[2]; m[15] = 1f
        pb.data.append(part, m, tint, mat, ao)
    }

    /** A chain link (flattened torus in the XY plane, long axis Y); lower poly than [Sculpt.link]. */
    fun link(len: Float, w: Float, r: Float, steps: Int = 6, sides: Int = 3): MeshData =
        Sculpt.tube(steps, sides, { t -> val a = TAU * t; floatArrayOf((w / 2) * cos(a), (len / 2) * sin(a), 0f) }, { r })

    /**
     * Lays [link] meshes along the 3D polyline [pts] every [pitch], alternating flat (plane parallel to the surface) and on edge.
     * [hint] returns the surface normal at a point; links are lifted off the surface by [liftFlat] / [liftEdge]; [target] picks the
     * [PartBuilder] a link goes into (lets a chain be split between two rigid parts).
     */
    fun chain(pts: List<FloatArray>, pitch: Float, link: MeshData, tint: Int, mat: Int, ao: Float, hint: (FloatArray) -> FloatArray,
              liftFlat: Float, liftEdge: Float, target: (FloatArray) -> PartBuilder, first: Float = 0.5f) {
        val n = pts.size
        val cum = FloatArray(n)
        for (i in 1 until n) {
            val dx = pts[i][0] - pts[i - 1][0]; val dy = pts[i][1] - pts[i - 1][1]; val dz = pts[i][2] - pts[i - 1][2]
            cum[i] = cum[i - 1] + sqrt(dx * dx + dy * dy + dz * dz)
        }
        val total = cum[n - 1]
        fun at(s: Float): FloatArray {
            var i = 1
            while (i < n - 1 && cum[i] < s) i++
            val u = ((s - cum[i - 1]) / (cum[i] - cum[i - 1]).coerceAtLeast(1e-6f)).coerceIn(0f, 1f)
            return floatArrayOf(pts[i - 1][0] + (pts[i][0] - pts[i - 1][0]) * u, pts[i - 1][1] + (pts[i][1] - pts[i - 1][1]) * u, pts[i - 1][2] + (pts[i][2] - pts[i - 1][2]) * u)
        }
        var s = first * pitch; var k = 0
        while (s < total) {
            val c = at(s); val p0 = at(max(s - 0.012f, 0f)); val p1 = at(min(s + 0.012f, total))
            val a = unit(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2])
            var nh = hint(c)
            val d = dot(nh, a)
            nh = unit(nh[0] - a[0] * d, nh[1] - a[1] * d, nh[2] - a[2] * d)
            val b = cross(a, nh)                                   // flat link: X' = a x n, Y' = a, Z' = n
            val lift = if (k % 2 == 0) liftFlat else liftEdge
            val pos = floatArrayOf(c[0] + nh[0] * lift, c[1] + nh[1] * lift, c[2] + nh[2] * lift)
            if (k % 2 == 0) place(target(pos), link, pos, b, a, nh, tint, mat, ao)
            else place(target(pos), link, pos, floatArrayOf(-nh[0], -nh[1], -nh[2]), a, b, tint, mat, ao)
            k++; s += pitch
        }
    }

    // ------------------------------------------------------------------------------------------ strokes, runes, cracks
    /** A flat quad stroke (square ends) from (x0, y0) to (x1, y1) of width [w] at depth [z], facing +Z. */
    fun stroke(m: MeshData, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, z: Float) {
        val dx = x1 - x0; val dy = y1 - y0
        val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
        val tx = dx / l * w * 0.5f; val ty = dy / l * w * 0.5f          // extend the ends so joints close
        val px = -dy / l * w * 0.5f; val py = dx / l * w * 0.5f
        val a = m.vertex(x0 - tx + px, y0 - ty + py, z, 0f, 0f, 1f, 0f, 0f); val b = m.vertex(x1 + tx + px, y1 + ty + py, z, 0f, 0f, 1f, 1f, 0f)
        val c = m.vertex(x1 + tx - px, y1 + ty - py, z, 0f, 0f, 1f, 1f, 1f); val d = m.vertex(x0 - tx - px, y0 - ty - py, z, 0f, 0f, 1f, 0f, 1f)
        m.quad(a, d, c, b)
    }

    // each rune: line segments x0,y0,x1,y1 in a [-0.5, 0.5] x [-1, 1] box (Elder Futhark-ish glyphs)
    private val RUNES: Array<FloatArray> = arrayOf(
        floatArrayOf(0f, -1f, 0f, 1f, 0f, 0.35f, 0.5f, 0.85f, 0f, -0.15f, 0.5f, 0.35f),
        floatArrayOf(-0.4f, -1f, -0.4f, 1f, -0.4f, 1f, 0.4f, 0.35f, 0.4f, 0.35f, 0.4f, -1f),
        floatArrayOf(0f, -1f, 0f, 1f, 0f, 0.55f, 0.5f, 0.15f, 0.5f, 0.15f, 0f, -0.3f),
        floatArrayOf(0f, -1f, 0f, 1f, 0f, 1f, 0.5f, 0.55f, 0f, 0.35f, 0.5f, -0.1f),
        floatArrayOf(0f, -1f, 0f, 1f, 0f, 1f, 0.5f, 0.5f, 0.5f, 0.5f, 0f, 0f, 0f, 0f, 0.5f, -1f),
        floatArrayOf(0.4f, 0.85f, -0.35f, 0f, -0.35f, 0f, 0.4f, -0.85f),
        floatArrayOf(-0.45f, -1f, 0.45f, 1f, -0.45f, 1f, 0.45f, -1f),
        floatArrayOf(-0.4f, -1f, -0.4f, 1f, 0.4f, -1f, 0.4f, 1f, -0.4f, 0.25f, 0.4f, -0.25f),
        floatArrayOf(0f, -1f, 0f, 1f, -0.4f, 0.35f, 0f, 1f, 0f, 1f, 0.4f, 0.35f),
        floatArrayOf(0f, -1f, 0f, 1f, 0f, 1f, 0.45f, 0.5f, 0.45f, 0.5f, 0f, 0f, 0f, 0f, 0.45f, -0.5f, 0.45f, -0.5f, 0f, -1f),
        floatArrayOf(0f, 0.3f, 0.5f, 0.85f, 0f, 0.3f, -0.5f, 0.85f, 0f, -0.2f, 0.5f, 0.35f, 0f, -0.2f, -0.5f, 0.35f, 0f, -1f, 0f, 0.3f),
        floatArrayOf(-0.4f, 0.9f, 0.4f, 0.3f, 0.4f, 0.3f, -0.4f, -0.3f, -0.4f, -0.3f, 0.4f, -0.9f),
    )
    val RUNE_COUNT get() = RUNES.size

    /** A rune glyph centred at (cx, cy) of height [h] (width h/2), stroke width [w], rotated by [rot] radians. */
    fun rune(m: MeshData, idx: Int, cx: Float, cy: Float, h: Float, w: Float, z: Float, rot: Float = 0f) {
        val g = RUNES[idx % RUNES.size]
        val cr = cos(rot); val sr = sin(rot)
        var k = 0
        while (k < g.size) {
            val ax = g[k] * h * 0.5f; val ay = g[k + 1] * h * 0.5f; val bx = g[k + 2] * h * 0.5f; val by = g[k + 3] * h * 0.5f
            stroke(m, cx + ax * cr - ay * sr, cy + ax * sr + ay * cr, cx + bx * cr - by * sr, cy + bx * sr + by * cr, w, z)
            k += 4
        }
    }

    /** A jagged crack: a random walk of [steps] segments from (x, y) heading [ang], tapering from width [w0] to [w1] (flat, +Z). */
    fun crack(m: MeshData, rnd: java.util.Random, x: Float, y: Float, ang: Float, steps: Int, seg: Float, w0: Float, w1: Float, zAt: (Float, Float) -> Float): FloatArray {
        var cx = x; var cy = y; var a = ang
        var px = cx; var py = cy
        for (i in 0 until steps) {
            a += (rnd.nextFloat() - 0.5f) * 1.5f
            val l = seg * (0.6f + rnd.nextFloat() * 0.8f)
            cx += cos(a) * l; cy += sin(a) * l
            val w = w0 + (w1 - w0) * i / max(1, steps - 1)
            stroke(m, px, py, cx, cy, w, zAt((px + cx) / 2f, (py + cy) / 2f))
            px = cx; py = cy
        }
        return floatArrayOf(cx, cy)
    }
    // ------------------------------------------------------------------------------------------ cheap parts
    /** Circle polygon (CCW) with [n] vertices centred at (cx, cy). */
    fun circle(cx: Float, cy: Float, r: Float, n: Int): Poly = List(n) { val a = TAU * it / n; Pair(cx + r * cos(a), cy + r * sin(a)) }

    /** Low-poly cone: base ring (radius [r]) at y = 0, apex at y = [h], flat shaded; optional base cap. */
    fun cone(r: Float, h: Float, sides: Int = 5, baseCap: Boolean = true): MeshData {
        val m = MeshData()
        val slope = r / h
        for (s in 0 until sides) {
            val a0 = TAU * s / sides; val a1 = TAU * (s + 1) / sides; val am = (a0 + a1) / 2f
            val n = unit(cos(am), slope, sin(am))
            val v0 = m.vertex(r * cos(a0), 0f, r * sin(a0), n[0], n[1], n[2], 0f, 0f)
            val v1 = m.vertex(r * cos(a1), 0f, r * sin(a1), n[0], n[1], n[2], 1f, 0f)
            val va = m.vertex(0f, h, 0f, n[0], n[1], n[2], 0.5f, 1f)
            m.tri(v0, v1, va)
        }
        if (baseCap) {
            val c = m.vertex(0f, 0f, 0f, 0f, -1f, 0f, 0.5f, 0.5f)
            val ids = IntArray(sides) { m.vertex(r * cos(TAU * it / sides), 0f, r * sin(TAU * it / sides), 0f, -1f, 0f, 0f, 0f) }
            for (s in 0 until sides) m.tri(c, ids[(s + 1) % sides], ids[s])
        }
        return m.fixWinding()
    }

    /** A tiny rivet / stud facing +Z: a [sides]-sided pyramid with soft normals (6 triangles for 6 sides). */
    fun stud(r: Float, h: Float, sides: Int = 6): MeshData {
        val m = MeshData()
        val apex = m.vertex(0f, 0f, h, 0f, 0f, 1f, 0.5f, 0.5f)
        val ring = IntArray(sides) { val a = TAU * it / sides; m.vertex(r * cos(a), r * sin(a), 0f, cos(a) * 0.8f, sin(a) * 0.8f, 0.6f, 0f, 0f) }
        for (s in 0 until sides) m.tri(apex, ring[s], ring[(s + 1) % sides])
        return m.fixWinding()
    }

    /**
     * Only the FRONT of the anatomical skull (azimuth a bit more than a half sphere), for emblems and reliefs: the same radial shape
     * as [Bones.skull] with half the triangles. Head units, +Z = face.
     */
    fun frontSkull(lat: Int, lon: Int): MeshData {
        val m = MeshData()
        val ph0 = (-0.12 * PI).toFloat(); val span = (1.24 * PI).toFloat()
        for (a in 0..lat) {
            val th = PI.toFloat() * a / lat
            for (b in 0..lon) {
                val ph = ph0 + span * b / lon
                val dx = sin(th) * cos(ph); val dy = cos(th); val dz = sin(th) * sin(ph)
                val r = Bones.skullShape(dx, dy, dz)
                val o = (sstep(0.58f, 0.98f, r) * (0.66f + 0.34f * sstep(-1f, 0.3f, dy))).coerceIn(0.03f, 1f)
                m.vertex(dx * r * 0.86f, dy * r * 1.08f, dz * r * 1.06f, dx, dy, dz, b.toFloat() / lon, a.toFloat() / lat, o, o, o, 0f)
            }
        }
        for (a in 0 until lat) for (b in 0 until lon) { val p = a * (lon + 1) + b; val q = p + lon + 1; m.quad(p, p + 1, q + 1, q) }
        return m.finish()
    }

    /** A light jaw bone (U-shaped tube) in head units around the skull centre, same path as [Bones.jaw] but ~100 triangles. */
    fun jawLite(steps: Int = 12, sides: Int = 4): MeshData {
        val pts = listOf(floatArrayOf(-0.60f, -0.02f, 0.02f), floatArrayOf(-0.58f, -0.46f, 0.10f), floatArrayOf(-0.46f, -0.80f, 0.42f),
            floatArrayOf(-0.24f, -0.98f, 0.74f), floatArrayOf(0f, -1.04f, 0.84f), floatArrayOf(0.24f, -0.98f, 0.74f),
            floatArrayOf(0.46f, -0.80f, 0.42f), floatArrayOf(0.58f, -0.46f, 0.10f), floatArrayOf(0.60f, -0.02f, 0.02f))
        fun at(t: Float): FloatArray {
            val f = t * (pts.size - 1); val i = f.toInt().coerceAtMost(pts.size - 2); val u = f - i
            val s = (1f - cos(u * PI.toFloat())) * 0.5f
            val a = pts[i]; val b = pts[i + 1]
            return floatArrayOf(a[0] + (b[0] - a[0]) * s, a[1] + (b[1] - a[1]) * s, a[2] + (b[2] - a[2]) * s)
        }
        return Sculpt.tube(steps, sides, ::at, { t -> val c = kotlin.math.abs(t - 0.5f) * 2f; 0.075f + 0.045f * (1f - c) * (1f - c) - 0.02f * sstep(0.7f, 1f, c) }, cap = true)
    }

    /** A row of flat triangular teeth: [n] teeth across x in [-half, half], bases on y = 0, tips at y = [dir] * [len]; z follows an arch. */
    fun teeth(n: Int, half: Float, len: Float, dir: Float, z0: Float, arch: Float): MeshData {
        val m = MeshData()
        for (i in 0 until n) {
            val xc = -half + 2f * half * (i + 0.5f) / n; val w = half / n * 0.9f
            fun zz(x: Float) = z0 - arch * (x / half) * (x / half)
            val a = m.vertex(xc - w, 0f, zz(xc - w), 0f, 0f, 1f, 0f, 0f); val b = m.vertex(xc + w, 0f, zz(xc + w), 0f, 0f, 1f, 1f, 0f)
            val c = m.vertex(xc, dir * len, zz(xc), 0f, 0f, 1f, 0.5f, 1f)
            m.tri(a, b, c)
        }
        return m.fixWinding()
    }
}

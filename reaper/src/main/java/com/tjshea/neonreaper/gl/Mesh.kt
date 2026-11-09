package com.tjshea.neonreaper.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** CPU-side mesh: interleaved position(3) normal(3) uv(2), 16-bit indices. */
class MeshData {
    var v = FloatArray(1024); var vn = 0          // floats used
    var i = ShortArray(1024); var inN = 0          // indices used
    val vertexCount get() = vn / 8

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, w: Float): Int {
        if (vn + 8 > v.size) v = v.copyOf(v.size * 2)
        v[vn++] = x; v[vn++] = y; v[vn++] = z; v[vn++] = nx; v[vn++] = ny; v[vn++] = nz; v[vn++] = u; v[vn++] = w
        return vertexCount - 1
    }

    fun tri(a: Int, b: Int, c: Int) {
        if (inN + 3 > i.size) i = i.copyOf(i.size * 2)
        i[inN++] = a.toShort(); i[inN++] = b.toShort(); i[inN++] = c.toShort()
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    /** Appends [o] transformed by column-major matrix [m] (normals by its normal matrix). */
    fun append(o: MeshData, m: FloatArray): MeshData {
        val nm = Mat4.normalMatrix(FloatArray(9), m)
        val base = vertexCount
        for (k in 0 until o.vertexCount) {
            val p = k * 8
            val x = o.v[p]; val y = o.v[p + 1]; val z = o.v[p + 2]
            val nx = o.v[p + 3]; val ny = o.v[p + 4]; val nz = o.v[p + 5]
            var tx = nm[0] * nx + nm[3] * ny + nm[6] * nz; var ty = nm[1] * nx + nm[4] * ny + nm[7] * nz; var tz = nm[2] * nx + nm[5] * ny + nm[8] * nz
            val l = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f); tx /= l; ty /= l; tz /= l
            vertex(m[0] * x + m[4] * y + m[8] * z + m[12], m[1] * x + m[5] * y + m[9] * z + m[13], m[2] * x + m[6] * y + m[10] * z + m[14],
                tx, ty, tz, o.v[p + 6], o.v[p + 7])
        }
        for (k in 0 until o.inN) tri0(o.i[k] + base)
        return this
    }

    private fun tri0(idx: Int) { if (inN + 1 > i.size) i = i.copyOf(i.size * 2); i[inN++] = idx.toShort() }

    /** Recomputes smooth normals from faces (used for displaced shapes). */
    fun computeNormals(): MeshData {
        val acc = FloatArray(vertexCount * 3)
        var k = 0
        while (k < inN) {
            val a = i[k].toInt() and 0xFFFF; val b = i[k + 1].toInt() and 0xFFFF; val c = i[k + 2].toInt() and 0xFFFF
            val ax = v[a * 8]; val ay = v[a * 8 + 1]; val az = v[a * 8 + 2]
            val ux = v[b * 8] - ax; val uy = v[b * 8 + 1] - ay; val uz = v[b * 8 + 2] - az
            val wx = v[c * 8] - ax; val wy = v[c * 8 + 1] - ay; val wz = v[c * 8 + 2] - az
            val nx = uy * wz - uz * wy; val ny = uz * wx - ux * wz; val nz = ux * wy - uy * wx
            for (q in intArrayOf(a, b, c)) { acc[q * 3] += nx; acc[q * 3 + 1] += ny; acc[q * 3 + 2] += nz }
            k += 3
        }
        for (q in 0 until vertexCount) {
            val x = acc[q * 3]; val y = acc[q * 3 + 1]; val z = acc[q * 3 + 2]
            val l = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
            v[q * 8 + 3] = x / l; v[q * 8 + 4] = y / l; v[q * 8 + 5] = z / l
        }
        return this
    }
}

/** GPU mesh. Upload is lazy so meshes can be built off the GL thread. */
class Mesh(private val data: MeshData) {
    private var vbo = 0
    private var ibo = 0
    val indexCount = data.inN

    fun upload(gl: Gl) {
        if (vbo != 0) return
        val fb: FloatBuffer = ByteBuffer.allocateDirect(data.vn * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data.v, 0, data.vn).position(0)
        val sb: ShortBuffer = ByteBuffer.allocateDirect(data.inN * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        sb.put(data.i, 0, data.inN).position(0)
        vbo = gl.genBuffer(); gl.bindBuffer(Gl.ARRAY_BUFFER, vbo); gl.bufferData(Gl.ARRAY_BUFFER, fb, Gl.STATIC_DRAW)
        ibo = gl.genBuffer(); gl.bindBuffer(Gl.ELEMENT_ARRAY_BUFFER, ibo); gl.bufferData(Gl.ELEMENT_ARRAY_BUFFER, sb, Gl.STATIC_DRAW)
    }

    fun draw(gl: Gl) {
        upload(gl)
        gl.bindBuffer(Gl.ARRAY_BUFFER, vbo)
        gl.bindBuffer(Gl.ELEMENT_ARRAY_BUFFER, ibo)
        gl.vertexAttribPointer(0, 3, 32, 0); gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(1, 3, 32, 12); gl.enableVertexAttribArray(1)
        gl.vertexAttribPointer(2, 2, 32, 24); gl.enableVertexAttribArray(2)
        gl.drawElements(Gl.TRIANGLES, indexCount)
    }

    /** Binds the vertex buffer and attributes without drawing (for GL_POINTS via drawArrays). */
    fun drawPointsSetup(gl: Gl) {
        upload(gl)
        gl.bindBuffer(Gl.ARRAY_BUFFER, vbo)
        gl.vertexAttribPointer(0, 3, 32, 0); gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(1, 3, 32, 12); gl.enableVertexAttribArray(1)
        gl.disableVertexAttribArray(2)
    }

    fun release(gl: Gl) { if (vbo != 0) { gl.deleteBuffer(vbo); gl.deleteBuffer(ibo); vbo = 0; ibo = 0 } }
}

/** Procedural geometry. All shapes are centred on the origin unless noted. */
object Shapes {
    private const val TAU = (2 * PI).toFloat()

    fun sphere(lat: Int = 18, lon: Int = 28): MeshData {
        val m = MeshData()
        for (a in 0..lat) {
            val th = PI * a / lat
            for (b in 0..lon) {
                val ph = 2 * PI * b / lon
                val x = (sin(th) * cos(ph)).toFloat(); val y = cos(th).toFloat(); val z = (sin(th) * sin(ph)).toFloat()
                m.vertex(x, y, z, x, y, z, b.toFloat() / lon, a.toFloat() / lat)
            }
        }
        for (a in 0 until lat) for (b in 0 until lon) {
            val p = a * (lon + 1) + b; val q = p + lon + 1
            m.quad(p, p + 1, q + 1, q)
        }
        return m
    }

    /**
     * Surface of revolution around Y. [profile] is (radius, y) pairs from top to bottom.
     * [radial] optionally modulates the radius by angle (e.g. pumpkin ribs, ragged hems).
     */
    fun lathe(profile: List<Pair<Float, Float>>, segments: Int = 32, radial: ((Float, Int) -> Float)? = null): MeshData {
        val m = MeshData()
        for ((k, pr) in profile.withIndex()) {
            for (s in 0..segments) {
                val ang = TAU * s / segments
                val r = pr.first * (radial?.invoke(ang, k) ?: 1f)
                m.vertex(r * cos(ang), pr.second, r * sin(ang), 0f, 1f, 0f, s.toFloat() / segments, k.toFloat() / (profile.size - 1))
            }
        }
        for (k in 0 until profile.size - 1) for (s in 0 until segments) {
            val p = k * (segments + 1) + s; val q = p + segments + 1
            m.quad(p, p + 1, q + 1, q)
        }
        return m.computeNormals()
    }

    fun cylinder(r: Float, h: Float, seg: Int = 16): MeshData =
        lathe(listOf(0f to h / 2, r to h / 2, r to h / 2, r to -h / 2, r to -h / 2, 0f to -h / 2), seg)

    /** Extruded convex outline (x,y pairs, counter-clockwise) with [depth] along Z. */
    fun extrude(outline: List<Pair<Float, Float>>, depth: Float): MeshData {
        val m = MeshData()
        val n = outline.size
        val cx = outline.sumOf { it.first.toDouble() }.toFloat() / n; val cy = outline.sumOf { it.second.toDouble() }.toFloat() / n
        for (side in intArrayOf(1, -1)) {
            val z = side * depth / 2
            val c = m.vertex(cx, cy, z, 0f, 0f, side.toFloat(), 0.5f, 0.5f)
            val first = m.vertexCount
            for ((x, y) in outline) m.vertex(x, y, z, 0f, 0f, side.toFloat(), 0.5f + x, 0.5f - y)
            for (k in 0 until n) { val a = first + k; val b = first + (k + 1) % n; if (side > 0) m.tri(c, a, b) else m.tri(c, b, a) }
        }
        for (k in 0 until n) {
            val (x0, y0) = outline[k]; val (x1, y1) = outline[(k + 1) % n]
            var nx = y1 - y0; var ny = -(x1 - x0); val l = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-6f); nx /= l; ny /= l
            val a = m.vertex(x0, y0, depth / 2, nx, ny, 0f, 0f, 0f); val b = m.vertex(x1, y1, depth / 2, nx, ny, 0f, 1f, 0f)
            val c = m.vertex(x1, y1, -depth / 2, nx, ny, 0f, 1f, 1f); val d = m.vertex(x0, y0, -depth / 2, nx, ny, 0f, 0f, 1f)
            m.quad(a, d, c, b)
        }
        return m
    }

    /** Arched tombstone outline (width 1, height 1.4, bottom at y = 0). */
    fun tombOutline(): List<Pair<Float, Float>> {
        val pts = ArrayList<Pair<Float, Float>>()
        pts += 0.5f to 0f
        for (k in 0..12) { val a = PI * k / 12; pts += (0.5f * cos(a)).toFloat() to (0.9f + 0.5f * sin(a)).toFloat() }
        pts += -0.5f to 0f
        return pts
    }

    /**
     * One face of a reel drum: an arc of [arc] radians on a cylinder of radius [r] around the X axis,
     * centred facing +Z, [w] wide. v runs top→bottom.
     */
    fun drumFace(r: Float, w: Float, arc: Float, div: Int = 6): MeshData {
        val m = MeshData()
        for (k in 0..div) {
            val a = arc / 2 - arc * k / div      // top (+) to bottom (-)
            val y = r * sin(a); val z = r * cos(a)
            val ny = sin(a); val nz = cos(a)
            m.vertex(-w / 2, y, z, 0f, ny, nz, 0f, k.toFloat() / div)
            m.vertex(w / 2, y, z, 0f, ny, nz, 1f, k.toFloat() / div)
        }
        for (k in 0 until div) { val p = k * 2; m.quad(p, p + 2, p + 3, p + 1) }
        return m
    }

    /** A full drum around the X axis (closed with end caps). */
    fun drum(r: Float, w: Float, seg: Int = 40): MeshData {
        val m = MeshData()
        for (k in 0..seg) {
            val a = TAU * k / seg; val y = r * sin(a); val z = r * cos(a)
            m.vertex(-w / 2, y, z, 0f, sin(a), cos(a), 0f, k.toFloat() / seg)
            m.vertex(w / 2, y, z, 0f, sin(a), cos(a), 1f, k.toFloat() / seg)
        }
        for (k in 0 until seg) { val p = k * 2; m.quad(p, p + 1, p + 3, p + 2) }
        for (side in intArrayOf(-1, 1)) {
            val c = m.vertex(side * w / 2, 0f, 0f, side.toFloat(), 0f, 0f, 0.5f, 0.5f)
            val first = m.vertexCount
            for (k in 0..seg) { val a = TAU * k / seg; m.vertex(side * w / 2, r * sin(a), r * cos(a), side.toFloat(), 0f, 0f, 0.5f + 0.5f * cos(a), 0.5f + 0.5f * sin(a)) }
            for (k in 0 until seg) if (side > 0) m.tri(c, first + k, first + k + 1) else m.tri(c, first + k + 1, first + k)
        }
        return m
    }

    /** Unit quad in XY facing +Z, from (-0.5,-0.5) to (0.5,0.5). */
    fun quad(): MeshData {
        val m = MeshData()
        val a = m.vertex(-0.5f, -0.5f, 0f, 0f, 0f, 1f, 0f, 1f); val b = m.vertex(0.5f, -0.5f, 0f, 0f, 0f, 1f, 1f, 1f)
        val c = m.vertex(0.5f, 0.5f, 0f, 0f, 0f, 1f, 1f, 0f); val d = m.vertex(-0.5f, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f)
        m.quad(a, b, c, d)
        return m
    }

    /** Ground: a grid in XZ with a height function. */
    fun terrain(sizeX: Float, sizeZ: Float, nx: Int, nz: Int, height: (Float, Float) -> Float): MeshData {
        val m = MeshData()
        for (j in 0..nz) for (i in 0..nx) {
            val x = -sizeX / 2 + sizeX * i / nx; val z = -sizeZ * j / nz
            m.vertex(x, height(x, z), z, 0f, 1f, 0f, i.toFloat() / nx * 8, j.toFloat() / nz * 8)
        }
        for (j in 0 until nz) for (i in 0 until nx) {
            val p = j * (nx + 1) + i; val q = p + nx + 1
            m.quad(p, p + 1, q + 1, q)
        }
        return m.computeNormals()
    }

    /** Bat / raven wing: a membrane in the XZ plane extending to +X, root at the origin. */
    fun wing(span: Float, chord: Float, scallops: Int = 3): MeshData {
        val m = MeshData()
        val tips = ArrayList<Pair<Float, Float>>()
        tips += 0f to chord * 0.5f
        tips += span * 0.55f to chord * 0.65f
        tips += span to chord * 0.1f
        for (k in 1..scallops) { val x = span * (1 - k.toFloat() / scallops); tips += (x + span * 0.08f) to -chord * 0.25f; tips += x to -chord * (if (k == scallops) 0.35f else 0.5f) }
        val c = m.vertex(span * 0.3f, 0f, 0f, 0f, 1f, 0f, 0.3f, 0.5f)
        val first = m.vertexCount
        for ((x, z) in tips) m.vertex(x, 0f, z, 0f, 1f, 0f, x / span, 0.5f + z / chord)
        for (k in 0 until tips.size) m.tri(c, first + k, first + (k + 1) % tips.size)
        // double-sided: duplicate with flipped normals
        val c2 = m.vertex(span * 0.3f, 0f, 0f, 0f, -1f, 0f, 0.3f, 0.5f)
        val f2 = m.vertexCount
        for ((x, z) in tips) m.vertex(x, 0f, z, 0f, -1f, 0f, x / span, 0.5f + z / chord)
        for (k in 0 until tips.size) m.tri(c2, f2 + (k + 1) % tips.size, f2 + k)
        return m
    }

    /** A gnarled dead tree made of tapered cylinders. */
    fun deadTree(seed: Long, height: Float): MeshData {
        val out = MeshData()
        val rnd = java.util.Random(seed)
        val unit = cylinder(1f, 1f, 7)
        fun branch(m: FloatArray, len: Float, rad: Float, depth: Int) {
            if (depth == 0 || rad < 0.004f) return
            val seg = m.copyOf()
            Mat4.translate(seg, 0f, len / 2, 0f); Mat4.scale(seg, rad, len, rad)
            out.append(unit, seg)
            val tip = m.copyOf(); Mat4.translate(tip, 0f, len * 0.95f, 0f)
            val kids = 2 + rnd.nextInt(2)
            for (k in 0 until kids) {
                val c = tip.copyOf()
                Mat4.rotate(c, rnd.nextFloat() * 360f, 0f, 1f, 0f)
                Mat4.rotate(c, 25f + rnd.nextFloat() * 30f, 0f, 0f, 1f)
                branch(c, len * (0.6f + rnd.nextFloat() * 0.2f), rad * 0.62f, depth - 1)
            }
        }
        branch(Mat4.identity(), height * 0.38f, height * 0.045f, 5)
        return out
    }

    /** Coin: a short bevelled disc facing +Z. */
    fun coin(): MeshData = lathe(listOf(0f to 0.12f, 0.8f to 0.12f, 0.95f to 0.08f, 1f to 0f, 0.95f to -0.08f, 0.8f to -0.12f, 0f to -0.12f), 36)
        .let { d -> MeshData().append(d, Mat4.rotate(Mat4.identity(), 90f, 1f, 0f, 0f)) }

    /** Angle helper used by procedural shading. */
    fun angle(x: Float, z: Float) = atan2(z, x)
}

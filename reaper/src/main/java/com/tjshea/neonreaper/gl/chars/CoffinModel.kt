package com.tjshea.neonreaper.gl.chars

import com.tjshea.neonreaper.gl.Mat
import com.tjshea.neonreaper.gl.Mat4
import com.tjshea.neonreaper.gl.Mesh
import com.tjshea.neonreaper.gl.MeshData
import com.tjshea.neonreaper.gl.PartBuilder
import com.tjshea.neonreaper.gl.Scene3D
import com.tjshea.neonreaper.gl.Sculpt
import com.tjshea.neonreaper.gl.Sculpt.fixWinding
import com.tjshea.neonreaper.gl.Sculpt.sstep
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Coffin (the SCATTER / bonus symbol): an upright, iron-bound gothic coffin with a hexagonal tapered outline, a bevelled
 * dark-wood lid with a strap cross and a skull medallion, chains wrapped across it with a heavy padlock, glowing green runes
 * and cracks, and a lid hinged on one side. Idle: floats and drifts, runes pulse, green mist at the foot. Excited: the lid
 * creaks and rattles open, blinding green-white light and souls pour out, the loose chains rattle.
 *
 * Budget: 3 meshes / 4 draws ([box], [lid], the loose chain tail drawn twice), ~3,000 triangles; the detail is vertex colour,
 * baked occlusion and the wood / metal / emissive shader materials.
 */
class CoffinModel : CharacterModel {
    // hexagonal coffin outline, counter-clockwise from the top-left (head end up)
    private val O: Poly = listOf(-0.145f to 1.14f, -0.315f to 0.82f, -0.185f to 0.02f, 0.185f to 0.02f, 0.315f to 0.82f, 0.145f to 1.14f)
    private val L0: Poly = CharKit.inset(O, 0.004f)            // lid slab
    private val L1: Poly = CharKit.inset(O, 0.056f)            // flat top of the lid (after the chamfer)
    private val R: Poly = CharKit.inset(O, 0.034f)             // inner wall of the box

    private val ZB = -0.15f      // back of the box
    private val ZR = 0.04f       // front rim of the box
    private val ZS0 = 0.046f     // underside of the lid
    private val ZS1 = 0.092f     // top of the lid slab (start of the chamfer)
    private val ZT = 0.132f      // flat top of the lid
    private val HX = -0.30f; private val HZ = 0.05f   // hinge axis (vertical), at the left shoulder

    private val IRON = 0x3A4A44; private val IRON_L = 0x5C7068; private val IRON_D = 0x222A27
    private val GREEN = 0x57FF5C; private val GREEN_HOT = 0xC4FFB4; private val SEAM = 0x2FA83A
    private val BONE = 0xBFD0BC

    // skull medallion on the lid
    private val mcx = 0f; private val mcy = 0.70f
    private val skS = 0.112f; private val skSZ = 0.112f * 0.46f; private val skZ = ZT + 0.044f

    private val stud = CharKit.stud(0.016f, 0.012f)
    private val box: Mesh
    private val lid: Mesh
    private val tail: Mesh
    val triangles: Int

    init {
        val boxPb = PartBuilder(); val lidPb = PartBuilder()
        buildBox(boxPb); buildLid(lidPb); buildChains(boxPb, lidPb)
        box = boxPb.build(); lid = lidPb.build(); tail = buildTail()
        triangles = (box.indexCount + lid.indexCount + tail.indexCount * 2) / 3
    }

    // ---------------------------------------------------------------------------------------------- helpers
    /** Height of the lid's top surface at (x, y): slab walls, then the chamfer, then the flat top. */
    private fun lidZ(x: Float, y: Float): Float = ZS1 + (ZT - ZS1) * ((CharKit.insideDist(O, x, y) - 0.004f) / 0.052f).coerceIn(0f, 1f)

    private fun lidNormal(x: Float, y: Float): FloatArray {
        val e = 0.004f
        return CharKit.unit(-(lidZ(x + e, y) - lidZ(x - e, y)) / (2 * e), -(lidZ(x, y + e) - lidZ(x, y - e)) / (2 * e), 1f)
    }

    private fun lidLoop(y: Float): List<FloatArray> {
        val a = CharKit.xRange(L0, y); val b = CharKit.xRange(L1, y)
        return listOf(floatArrayOf(a.first, ZS0), floatArrayOf(a.first, ZS1), floatArrayOf(b.first, ZT), floatArrayOf(b.second, ZT), floatArrayOf(a.second, ZS1), floatArrayOf(a.second, ZS0))
    }

    private fun boxLoop(y: Float): List<FloatArray> {
        val a = CharKit.xRange(O, y)
        return listOf(floatArrayOf(a.second, ZR), floatArrayOf(a.second, ZB), floatArrayOf(a.first, ZB), floatArrayOf(a.first, ZR))
    }

    /** Offsets an open (x, z) polyline outwards by [o] (outward = (-dz, dx) of the travel direction). */
    private fun offsetXZ(pts: List<FloatArray>, o: Float): List<FloatArray> {
        fun segN(a: FloatArray, b: FloatArray): FloatArray { val dx = b[0] - a[0]; val dz = b[1] - a[1]; val l = sqrt(dx * dx + dz * dz).coerceAtLeast(1e-6f); return floatArrayOf(-dz / l, dx / l) }
        val n = pts.size
        return List(n) { i ->
            val n1 = if (i > 0) segN(pts[i - 1], pts[i]) else segN(pts[0], pts[1])
            val n2 = if (i < n - 1) segN(pts[i], pts[i + 1]) else segN(pts[n - 2], pts[n - 1])
            val k = o / (1f + n1[0] * n2[0] + n1[1] * n2[1])
            floatArrayOf(pts[i][0] + (n1[0] + n2[0]) * k, pts[i][1] + (n1[1] + n2[1]) * k)
        }
    }

    /** A strap wrapped round the coffin between heights [y0] and [y1], following the loop returned by [loopAt]. */
    private fun strap(loopAt: (Float) -> List<FloatArray>, y0: Float, y1: Float, o: Float): MeshData {
        val m = MeshData()
        val lo = offsetXZ(loopAt(y0), o); val hi = offsetXZ(loopAt(y1), o)
        for (j in 0 until lo.size - 1) {
            val ax = lo[j][0]; val az = lo[j][1]; val bx = lo[j + 1][0]; val bz = lo[j + 1][1]
            val cx = hi[j + 1][0]; val cz = hi[j + 1][1]; val dx = hi[j][0]; val dz = hi[j][1]
            val ux = bx - ax; val uz = bz - az
            val vx = dx - ax; val vy = y1 - y0; val vz = dz - az
            var nx = -uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy      // (B - A) x (D - A)
            if (nx * (-uz) + nz * ux < 0f) { nx = -nx; ny = -ny; nz = -nz }
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f); nx /= l; ny /= l; nz /= l
            val i0 = m.vertex(ax, y0, az, nx, ny, nz, 0f, 0f); val i1 = m.vertex(bx, y0, bz, nx, ny, nz, 1f, 0f)
            val i2 = m.vertex(cx, y1, cz, nx, ny, nz, 1f, 1f); val i3 = m.vertex(dx, y1, dz, nx, ny, nz, 0f, 1f)
            m.quad(i0, i1, i2, i3)
        }
        return m.fixWinding()
    }

    private fun studAt(pb: PartBuilder, x: Float, y: Float, lift: Float = 0.010f) {
        pb.add(stud, IRON_L, Mat.METAL, x = x, y = y, z = lidZ(x, y) + lift, ao = 1f)
    }

    // ---------------------------------------------------------------------------------------------- the box
    private fun buildBox(pb: PartBuilder) {
        pb.add(CharKit.walls(O, ZB, O, ZR), 0x241C17, Mat.WOOD, ao = 0.95f)                         // outer walls
        pb.add(CharKit.cap(O, ZB, -1f), 0x1A120E, Mat.WOOD, ao = 0.8f)                              // back
        pb.add(CharKit.ring(O, R, ZR, 1f), 0x1E1612, Mat.WOOD, ao = 0.85f)                          // rim the lid rests on
        // interior: an emissive cavity, brightest at the floor, that blazes through the gap when the lid opens
        pb.add(CharKit.walls(R, ZR, R, -0.12f, outward = false, shadeA = 0.30f, shadeB = 1f), 0x66FF6A, Mat.EMISSIVE)
        pb.add(CharKit.cap(R, -0.12f, 1f), 0xDCFFD2, Mat.EMISSIVE)
        // a thin line of light leaking round the whole lid
        pb.add(Sculpt.tube(12, 4, CharKit.loopPath(O, ZR + 0.003f), { 0.0075f }), SEAM, Mat.EMISSIVE)
        // iron bands wrap the sides (the lid carries the matching front part)
        for (yc in floatArrayOf(0.70f, 0.20f)) {
            val hb = if (yc > 0.5f) 0.036f else 0.030f
            pb.add(strap(::boxLoop, yc - hb, yc + hb, 0.010f), IRON, Mat.METAL, ao = 0.9f)
        }
        // shoulder spikes (the chains hang from them) and head / foot points
        for (sx in floatArrayOf(-1f, 1f)) {
            pb.add(CharKit.cone(0.028f, 0.105f, 5), IRON_D, Mat.METAL, x = sx * 0.305f, y = 0.815f, z = -0.055f, rz = -sx * 66f)
            pb.add(CharKit.cone(0.017f, 0.065f, 4), IRON_D, Mat.METAL, x = sx * 0.138f, y = 1.135f, z = -0.055f, rz = -sx * 18f)
            pb.add(CharKit.cone(0.017f, 0.055f, 4), IRON_D, Mat.METAL, x = sx * 0.180f, y = 0.03f, z = -0.055f, rz = sx * 160f)
        }
    }

    // ---------------------------------------------------------------------------------------------- the lid
    private fun buildLid(pb: PartBuilder) {
        pb.add(CharKit.walls(L0, ZS0, L0, ZS1), 0x2A201A, Mat.WOOD, ao = 0.95f)
        pb.add(CharKit.walls(L0, ZS1, L1, ZT), 0x3A2D23, Mat.WOOD)                                  // chamfer (catches the light)
        pb.add(CharKit.cap(L1, ZT, 1f), 0x2A211B, Mat.WOOD)                                         // flat top
        pb.add(CharKit.cap(L0, ZS0, -1f), 0x1C140F, Mat.WOOD, ao = 0.6f)                            // underside
        // inlaid iron trim round the panel (a raised flat frame) with a stud in every corner
        val trimO = CharKit.inset(O, 0.074f); val trimI = CharKit.inset(O, 0.088f)
        pb.add(CharKit.ring(trimO, trimI, ZT + 0.007f, 1f), IRON, Mat.METAL)
        for (p in CharKit.inset(O, 0.081f)) pb.add(stud, IRON_L, Mat.METAL, x = p.first, y = p.second, z = ZT + 0.007f)

        // the strap cross: two bands that wrap round the sides + a long vertical band
        for (yc in floatArrayOf(0.70f, 0.20f)) {
            val hb = if (yc > 0.5f) 0.036f else 0.030f
            pb.add(strap(::lidLoop, yc - hb, yc + hb, 0.010f), IRON, Mat.METAL, ao = 0.95f)
        }
        pb.add(CharKit.grid(-0.032f, 0.032f, 0.09f, 1.06f, 1, 1) { _, _ -> ZT + 0.010f }, IRON, Mat.METAL, ao = 0.95f)
        for (x in floatArrayOf(-0.215f, 0.215f)) studAt(pb, x, 0.70f)
        for (x in floatArrayOf(-0.115f, 0.115f)) studAt(pb, x, 0.20f)
        for (y in floatArrayOf(0.12f, 0.29f, 0.95f, 1.03f)) studAt(pb, 0f, y)

        // medallion: a bevelled iron boss, a ring of toxic light, the skull
        val plate0 = CharKit.circle(mcx, mcy, 0.182f, 20); val plate1 = CharKit.circle(mcx, mcy, 0.166f, 20)
        pb.add(CharKit.walls(plate0, ZT + 0.002f, plate1, ZT + 0.028f), IRON, Mat.METAL, ao = 0.95f)
        pb.add(CharKit.cap(plate1, ZT + 0.028f, 1f), IRON_D, Mat.METAL, ao = 0.9f)
        pb.add(CharKit.ring(CharKit.circle(mcx, mcy, 0.152f, 20), CharKit.circle(mcx, mcy, 0.128f, 20), ZT + 0.032f, 1f), GREEN, Mat.EMISSIVE)
        pb.add(CharKit.frontSkull(18, 24), BONE, Mat.BONE, x = mcx, y = mcy, z = skZ, sx = skS, sy = skS, sz = skSZ, ao = 1f)
        pb.add(CharKit.jawLite(), 0xC2C6B0, Mat.BONE, x = mcx, y = mcy, z = skZ, sx = skS, sy = skS, sz = skSZ, ao = 0.95f)
        pb.add(CharKit.teeth(9, 0.42f, 0.14f, -1f, 0.93f, 0.40f), 0xEEF0DC, Mat.BONE, x = mcx, y = mcy - 0.62f * skS, z = skZ - 0.04f * skSZ, sx = skS, sy = skS, sz = skSZ)
        pb.add(CharKit.teeth(9, 0.46f, 0.14f, 1f, 0.82f, 0.32f), 0xEEF0DC, Mat.BONE, x = mcx, y = mcy - 0.80f * skS, z = skZ + 0.01f * skSZ, sx = skS, sy = skS, sz = skSZ)
        for (sx in floatArrayOf(-1f, 1f)) {
            pb.add(Sculpt.ellipsoid(0.085f, 0.085f, 0.085f, 3, 6), GREEN_HOT, Mat.EMISSIVE,
                x = mcx + sx * 0.237f * skS * 0.98f, y = mcy + 0.075f * skS, z = skZ + 0.607f * skSZ, sx = skS, sy = skS, sz = skSZ)
        }

        // padlock where the chains cross
        pb.add(padlock(), IRON_D, Mat.METAL, x = 0f, y = 0.415f, z = ZT + 0.040f, ao = 1f)
        pb.add(stud, GREEN_HOT, Mat.EMISSIVE, x = 0f, y = 0.405f, z = ZT + 0.040f + 0.0225f)

        // runes on the straps and glowing cracks in the wood
        val rm = MeshData()
        val rz = { x: Float, y: Float -> lidZ(x, y) + 0.016f }
        var ri = 0
        for (x in floatArrayOf(-0.255f, -0.19f, 0.19f, 0.255f)) { CharKit.rune(rm, ri++, x, 0.70f, 0.050f, 0.0075f, rz(x, 0.70f)) }
        for (x in floatArrayOf(-0.14f, 0.14f)) { CharKit.rune(rm, ri++, x, 0.20f, 0.044f, 0.0070f, rz(x, 0.20f)) }
        for (y in floatArrayOf(0.12f, 0.30f, 0.95f, 1.03f)) { CharKit.rune(rm, ri++, 0f, y, 0.040f, 0.0065f, rz(0f, y), 0f) }
        val rnd = Random(11)
        CharKit.crack(rm, rnd, -0.13f, 0.86f, 2.2f, 5, 0.044f, 0.0105f, 0.0035f) { x, y -> rz(x, y) }
        CharKit.crack(rm, rnd, 0.14f, 0.86f, 0.7f, 5, 0.040f, 0.0095f, 0.0035f) { x, y -> rz(x, y) }
        CharKit.crack(rm, rnd, -0.15f, 0.56f, 3.5f, 5, 0.040f, 0.0095f, 0.0035f) { x, y -> rz(x, y) }
        CharKit.crack(rm, rnd, 0.15f, 0.55f, -0.6f, 4, 0.038f, 0.0090f, 0.0035f) { x, y -> rz(x, y) }
        CharKit.crack(rm, rnd, -0.08f, 0.31f, 4.3f, 4, 0.038f, 0.0090f, 0.0030f) { x, y -> rz(x, y) }
        CharKit.crack(rm, rnd, 0.09f, 0.115f, 0.3f, 4, 0.038f, 0.0090f, 0.0030f) { x, y -> rz(x, y) }
        pb.add(rm, GREEN, Mat.EMISSIVE)
    }

    private fun padlock(): MeshData {
        val pb = PartBuilder()
        pb.add(Sculpt.box(0.047f, 0.040f, 0.022f), 0x44504A, Mat.METAL)
        pb.add(Sculpt.box(0.034f, 0.028f, 0.003f), 0x2A322E, Mat.METAL, z = 0.0215f)
        val ra = 0.027f; val top = 0.040f
        pb.add(Sculpt.tube(12, 4, { t ->
            when {                                                       // two legs and an arch
                t < 0.2f -> { val u = t / 0.2f; floatArrayOf(-ra, top - 0.012f + u * 0.034f, 0f) }
                t < 0.8f -> { val a = PI.toFloat() * (1f - (t - 0.2f) / 0.6f); floatArrayOf(ra * cos(a), top + 0.022f + ra * sin(a), 0f) }
                else -> { val u = (t - 0.8f) / 0.2f; floatArrayOf(ra, top + 0.022f - u * 0.034f, 0f) }
            }
        }, { 0.0095f }), IRON_L, Mat.METAL)
        return pb.data
    }

    // ---------------------------------------------------------------------------------------------- chains
    private fun wallX(y: Float, side: Float): Float { val r = CharKit.xRange(O, y); return if (side < 0f) r.first else r.second }

    /** A chain that comes up the side wall, crosses the lid diagonally from (sx, sy) to (ex, ey) and goes down the other wall. */
    private fun chainPath(sx: Float, sy: Float, ex: Float, ey: Float): List<FloatArray> {
        val pts = ArrayList<FloatArray>()
        val s0 = if (sx < 0f) -1f else 1f; val s1 = if (ex < 0f) -1f else 1f
        for (z in floatArrayOf(-0.01f, 0.03f)) pts += floatArrayOf(wallX(sy, s0) + s0 * 0.002f, sy, z)
        pts += floatArrayOf(wallX(sy, s0) + s0 * 0.002f, sy, ZS1)
        val n = 24
        for (i in 0..n) { val x = sx + (ex - sx) * i / n; val y = sy + (ey - sy) * i / n; pts += floatArrayOf(x, y, lidZ(x, y)) }
        pts += floatArrayOf(wallX(ey, s1) + s1 * 0.002f, ey, ZS1)
        for (z in floatArrayOf(0.03f, -0.01f)) pts += floatArrayOf(wallX(ey, s1) + s1 * 0.002f, ey, z)
        return pts
    }

    private fun buildChains(boxPb: PartBuilder, lidPb: PartBuilder) {
        val link = CharKit.link(0.096f, 0.048f, 0.0115f)
        val hint = { p: FloatArray -> if (p[2] >= ZS1 - 0.001f) lidNormal(p[0], p[1]) else CharKit.unit(if (p[0] < 0f) -1f else 1f, 0f, 0f) }
        val target = { p: FloatArray -> if (p[2] < ZS0 - 0.004f) boxPb else lidPb }
        val c1 = chainPath(-0.268f, 0.625f, 0.208f, 0.255f)
        val c2 = chainPath(0.268f, 0.625f, -0.208f, 0.255f)
        for (c in listOf(c1, c2)) CharKit.chain(c, 0.072f, link, IRON, Mat.METAL, 0.95f, hint, 0.0125f, 0.0365f, target)
    }

    /** Loose chain tail: hangs straight down from the origin (the shoulder spike), swung by the draw code. */
    private fun buildTail(): Mesh {
        val pb = PartBuilder()
        val link = CharKit.link(0.096f, 0.048f, 0.0115f)
        pb.add(CharKit.link(0.070f, 0.060f, 0.0125f), IRON_L, Mat.METAL, ry = 90f)
        for (i in 0 until 5) pb.add(link, IRON, Mat.METAL, y = -0.060f - i * 0.072f, ry = if (i % 2 == 0) 0f else 90f, ao = 0.95f)
        pb.add(CharKit.cone(0.020f, 0.085f, 4), IRON_D, Mat.METAL, y = -0.060f - 5 * 0.072f + 0.02f, rx = 180f)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- animation
    private val tailAnchor = floatArrayOf(0.372f, 0.838f, -0.055f)

    override fun draw(sc: Scene3D, mm: FloatArray, t: Float, ex: Float, dim: Float) {
        val pulse = 0.80f + 0.20f * sin(t * 2.4f) + 0.10f * sin(t * 6.1f + 1f)
        val rim = 0x0A2A14
        val shake = ex * (sin(t * 41f) * 0.6f + sin(t * 27f + 1f) * 0.4f)

        // ---- float and drift, leaning back a little so the lid faces the viewer; shudders when excited
        val root = mm.copyOf()
        Mat4.translate(root, 0f, 0.05f + 0.022f * sin(t * 1.2f) + ex * 0.010f * sin(t * 33f), 0f)
        Mat4.translate(root, 0f, 0.55f, 0f)
        Mat4.rotate(root, 2.2f * sin(t * 0.55f) + shake * 1.6f, 0f, 0f, 1f)
        Mat4.rotate(root, -4f + 1.4f * sin(t * 0.41f + 1f) - ex * 2f + shake * 0.8f, 1f, 0f, 0f)
        Mat4.rotate(root, 3.5f * sin(t * 0.33f) + shake, 0f, 1f, 0f)
        Mat4.translate(root, 0f, -0.55f, 0f)

        // ---- the lid creaks open when excited
        val creak = 0.5f + 0.5f * sin(t * 1.9f)
        val open = max(0f, ex * (7f + 11f * creak * creak) + ex * 1.8f * sin(t * 26f) + 0.35f * (0.5f + 0.5f * sin(t * 0.8f)))
        val lidM = root.copyOf()
        Mat4.translate(lidM, HX, 0f, HZ); Mat4.rotate(lidM, -open, 0f, 1f, 0f); Mat4.translate(lidM, -HX, 0f, -HZ)
        val glowK = (0.75f + 0.45f * pulse + 0.95f * ex)
        sc.drawV(box, root, emis = glowK, dim = dim, rim = rim, spec = 0.35f)
        sc.drawV(lid, lidM, emis = glowK, dim = dim, rim = rim, spec = 0.35f)

        // ---- loose chains rattle
        for (sx in floatArrayOf(-1f, 1f)) {
            val tm = root.copyOf()
            Mat4.translate(tm, sx * tailAnchor[0], tailAnchor[1], tailAnchor[2])
            val ph = if (sx < 0f) 0f else 1.7f
            Mat4.rotate(tm, sx * 6f + 4f * sin(t * 1.4f + ph) + ex * 13f * sin(t * 17f + ph), 0f, 0f, 1f)
            Mat4.rotate(tm, 3f * sin(t * 1.1f + ph) + ex * 9f * sin(t * 21f + ph * 2f), 1f, 0f, 0f)
            sc.drawV(tail, tm, dim = dim, rim = rim, spec = 0.35f)
        }

        // ---- green light spilling over the lid (only when it opens: idle stays dark and wicked)
        sc.pointLightAt(root, 0f, 0.62f, 0.60f, 1.9f, 0x3CFF6A, (0.07f + 0.05f * pulse + 0.9f * ex) * dim)

        // ---- glow sprites: halo behind, mist at the foot, the eyes / lock / medallion, the leak around the lid
        sc.glowAt(root, 0f, 0.58f, -0.40f, 1.8f + 0.5f * ex, 0x2FFF6A, (0.15f + 0.07f * pulse + 0.22f * ex) * dim, 0f)
        for (k in 0 until 2) {
            val ph = t * 0.33f + k * 2.9f
            sc.glowAt(root, sin(ph) * 0.24f, 0.08f + 0.04f * sin(ph * 1.3f), 0.18f + 0.1f * cos(ph * 0.8f), 0.80f + 0.2f * k, 0x2DFF6E, (0.15f + 0.04f * pulse + 0.08f * ex) * dim, 0f)
        }
        val eyeA = (0.55f + 0.45f * pulse + 0.8f * ex) * dim
        for (sx in floatArrayOf(-1f, 1f)) {
            val ex0 = mcx + sx * 0.237f * skS; val ey0 = mcy + 0.075f * skS; val ez0 = skZ + (0.607f + 0.30f) * skSZ
            sc.glowAt(lidM, ex0, ey0, ez0, 0.060f + 0.05f * ex, 0x39FF55, 0.85f * eyeA, 0.9f)
        }
        sc.glowAt(lidM, mcx, mcy, ZT + 0.07f, 0.44f + 0.1f * ex, 0x39FF55, (0.17f + 0.10f * pulse + 0.2f * ex) * dim, 0f)
        sc.glowAt(lidM, 0f, 0.405f, ZT + 0.07f, 0.07f + 0.03f * ex, 0xE8FFE0, 0.8f * eyeA, 0.8f)

        // ---- light spilling from the gap between the lid and the box (blinding when open)
        val gap = sstep(0f, 9f, open)
        for (k in 0 until 4) {
            val y = 0.17f + k * 0.27f
            val x = CharKit.xRange(O, y).second - 0.006f
            sc.glowAt(lidM, x, y, ZS0 + 0.01f, 0.16f + 0.34f * gap, 0xC8FFC0, (0.10f + 0.85f * gap) * dim, 0.6f * gap)
        }

        // ---- souls pouring out of the gap (each one trails a fainter wisp)
        if (ex > 0.03f) for (i in 0 until 5) {
            val rate = 0.28f + 0.035f * (i % 4)
            for (w in 0 until 2) {
                val tau = (t * rate + i * 0.2f - w * 0.045f + 1f) % 1f
                val env = sin(PI.toFloat() * tau)
                val a = ex * env * dim * (if (w == 0) 1f else 0.55f)
                if (a < 0.03f) continue
                val y0 = 0.22f + 0.19f * i
                val sx = 0.27f + 0.20f * tau + 0.06f * sin(t * 2.3f + i * 1.9f + tau * 4f) * tau
                val sy = y0 + 0.50f * tau + 0.05f * sin(t * 3.1f + i)
                val sz = ZS0 + 0.06f + 0.40f * tau
                sc.glowAt(root, sx, sy, sz, (0.10f + 0.14f * env) * (if (w == 0) 1f else 0.7f), 0x39FF7F, 0.85f * a, if (w == 0) 0.9f else 0.2f)
            }
        }
    }
}

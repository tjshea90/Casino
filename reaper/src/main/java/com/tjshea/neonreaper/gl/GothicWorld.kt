package com.tjshea.neonreaper.gl

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Meshes of the gothic crypt world around the cabinet: carved pillars crowned with burning-eyed skulls, iron-strapped beams,
 * braziers, broken arches, lamps, fences and skull candles. Every piece is ONE merged vertex-material mesh (tint + material id
 * per vertex, see [Mat]) built once on the CPU; a few hundred to a few thousand triangles each.
 *
 * Conventions: pieces stand on y = 0 with their front toward +Z unless noted.
 */
internal object GothicWorld {
    private const val TAU = (2 * PI).toFloat()

    const val STONE = 0x6A6D74
    const val STONE_D = 0x474A51
    const val IRON = 0x3C3E45
    const val BRASS = 0xB08E3C
    const val BONE = 0xD2C9B0
    const val WAX = 0xEADFC6
    const val WOOD = 0x4B2F1D
    const val EMBER = 0xFF7A20
    const val BLOOD = 0xFF2236
    const val SICK = 0x9CFF57

    private fun p3(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    /** Adds an already tinted multi-material part (keeps its own colours and material ids). */
    private fun PartBuilder.addKeep(part: MeshData, x: Float = 0f, y: Float = 0f, z: Float = 0f, rx: Float = 0f, ry: Float = 0f, rz: Float = 0f, s: Float = 1f) {
        val m = Mat4.identity(); Mat4.translate(m, x, y, z)
        if (ry != 0f) Mat4.rotate(m, ry, 0f, 1f, 0f)
        if (rx != 0f) Mat4.rotate(m, rx, 1f, 0f, 0f)
        if (rz != 0f) Mat4.rotate(m, rz, 0f, 0f, 1f)
        Mat4.scale(m, s, s, s)
        data.append(part, m)
    }

    /** Iron / stone ring around Y (radius [r], height [h], bulging by [grow]) — an open shell. */
    private fun band(r: Float, h: Float, grow: Float = 0.03f, segs: Int = 20): MeshData =
        Sculpt.shell(listOf(r to h / 2, (r + grow) to h * 0.2f, (r + grow) to -h * 0.2f, r to -h / 2), segs)

    private fun studs(pb: PartBuilder, r: Float, y: Float, n: Int, s: Float = 0.026f) {
        for (k in 0 until n) { val a = TAU * k / n; pb.add(Sculpt.ellipsoid(s, s, s, 3, 5), BRASS, Mat.METAL, x = cos(a) * r, y = y, z = sin(a) * r) }
    }

    /** Wax candle with a bright flame core (the flickering halo is a glow sprite drawn by the scene). */
    private fun candle(pb: PartBuilder, x: Float, y: Float, z: Float, h: Float, r: Float = 0.035f) {
        pb.add(Sculpt.tube(3, 8, { t -> p3(0f, h * t, 0f) }, { r }, cap = true), WAX, Mat.WAX, x = x, y = y, z = z)
        pb.add(Sculpt.spike(r * 0.6f, r * 3.4f, 6), 0xFFE9B0, Mat.EMISSIVE, x = x, y = y + h, z = z)
    }

    /** Low-poly chain link (a 4-sided wire loop): decorative chains need no more. */
    private fun lowLink(len: Float, w: Float, r: Float): MeshData = Sculpt.link(len, w, r, 8, 4)

    /**
     * A skull in HEAD UNITS (radius ~1) with a slightly open jaw and glowing eye sparks in the sockets, at three levels of detail
     * (0 = background filler ~250 tris, 1 = near the camera ~1k tris). Teeth are tiny ellipsoids (the anatomy kit's are too heavy here).
     */
    fun skullHead(open: Float, detail: Int = 1, eyes: Int = BLOOD): MeshData {
        val pb = PartBuilder()
        val hi = detail >= 1
        pb.add(if (hi) Bones.skull(12, 18) else Bones.skull(8, 12), BONE, Mat.BONE)
        if (hi) for (i in -3..3) {
            val x = i * 0.1f; val fang = if (kotlin.math.abs(i) == 3) 1.9f else 1f
            val z = 0.93f - 0.40f * (kotlin.math.abs(x) / 0.5f) * (kotlin.math.abs(x) / 0.5f) - 0.05f
            pb.add(Sculpt.ellipsoid(0.04f, 0.066f * fang, 0.035f, 4, 6), 0xDCD2B8, Mat.BONE, x = x, y = -0.64f - (fang - 1f) * 0.06f, z = z)
        }
        // jaw: one flattened ellipsoid hinged at the cheek (origin of the part = hinge)
        val h = Bones.JAW_HINGE
        val jaw = MeshData().append(Sculpt.ellipsoid(0.50f, 0.20f, 0.48f, if (hi) 6 else 4, if (hi) 10 else 6), Mat4.translate(Mat4.identity(), 0f, -0.46f, 0.38f))
        pb.add(jaw, 0xBFB59C, Mat.BONE, x = h[0], y = h[1], z = h[2], rx = open, ao = 0.9f)
        for (sx in floatArrayOf(-1f, 1f)) pb.add(Sculpt.ellipsoid(0.1f, 0.085f, 0.085f, 4, 6), eyes, Mat.EMISSIVE, x = sx * 0.235f, y = 0.075f, z = 0.60f)
        return pb.data
    }

    // ================================================================================================ cabinet
    /**
     * Carved stone pillar, origin at its centre bottom, 3.95 tall: plinth, fluted shaft with iron bands and brass studs, a capital,
     * a hanging chain, a candelabra on the front and a skull crown (turned toward the reels). [side] = -1 left, +1 right.
     */
    fun pillar(side: Float): MeshData {
        val pb = PartBuilder()
        pb.add(Sculpt.box(0.62f, 0.16f, 0.62f), STONE_D, Mat.STONE, y = 0.16f)
        pb.add(Sculpt.box(0.50f, 0.10f, 0.50f), STONE, Mat.STONE, y = 0.42f)
        pb.add(Sculpt.shell(listOf(0.285f to 3.2f, 0.30f to 2.6f, 0.32f to 1.6f, 0.35f to 0.52f), 24,
            radial = { ang, _, _ -> 1f + 0.05f * cos(ang * 8f) }), STONE, Mat.STONE)
        for (y in floatArrayOf(0.95f, 1.85f, 2.75f)) {
            val r = 0.35f - (y - 0.52f) / 2.68f * 0.065f
            pb.add(band(r + 0.004f, 0.11f), IRON, Mat.METAL, y = y)
            studs(pb, r + 0.034f, y, 6)
        }
        // capital + abacus
        pb.add(Sculpt.shell(listOf(0.285f to 3.2f, 0.36f to 3.28f, 0.44f to 3.37f, 0.48f to 3.47f), 24), STONE, Mat.STONE)
        pb.add(Sculpt.box(0.52f, 0.06f, 0.52f), STONE_D, Mat.STONE, y = 3.52f)
        // skull crown
        pb.addKeep(skullHead(10f, 1), y = 3.86f, z = 0.04f, ry = -side * 22f, s = 0.27f)
        pb.add(Sculpt.spike(0.05f, 0.24f, 6), IRON, Mat.METAL, x = -0.3f, y = 3.58f, rz = 30f)
        pb.add(Sculpt.spike(0.05f, 0.24f, 6), IRON, Mat.METAL, x = 0.3f, y = 3.58f, rz = -30f)
        // chain hanging from the outer side of the capital
        for (k in 0 until 9) pb.add(lowLink(0.10f, 0.062f, 0.011f), IRON, Mat.METAL, x = side * 0.43f, y = 3.38f - 0.082f * k, z = 0.05f, ry = if (k % 2 == 0) 0f else 90f)
        // candelabra on the front: iron arm, a dish and three candles
        pb.add(Sculpt.tube(6, 6, { t -> p3(0f, 2.15f + 0.05f * sin(t * PI.toFloat()), 0.30f + 0.42f * t) }, { 0.022f }, cap = true), IRON, Mat.METAL)
        pb.add(Sculpt.tube(2, 14, { t -> p3(0f, 2.19f + 0.03f * t, 0.74f) }, { 0.13f - 0.02f * it }, cap = true), IRON, Mat.METAL)
        candle(pb, -0.07f, 2.22f, 0.74f, 0.20f); candle(pb, 0.0f, 2.22f, 0.76f, 0.30f); candle(pb, 0.07f, 2.22f, 0.73f, 0.16f)
        return pb.data
    }

    /** Wood-and-iron beam across the top of the reels: strapped, studded, with a row of short iron teeth hanging in front of the reels. */
    fun lintel(width: Float, teeth: Boolean): MeshData {
        val pb = PartBuilder()
        val hw = width / 2
        pb.add(Sculpt.box(hw, 0.20f, 0.46f), WOOD, Mat.WOOD)
        pb.add(Sculpt.box(hw, 0.035f, 0.47f), BRASS, Mat.METAL, y = 0.15f)
        pb.add(Sculpt.box(hw, 0.035f, 0.47f), BRASS, Mat.METAL, y = -0.15f)
        var x = -hw + 0.45f
        while (x < hw - 0.2f) {
            pb.add(Sculpt.box(0.065f, 0.205f, 0.475f), IRON, Mat.METAL, x = x)
            pb.add(Sculpt.ellipsoid(0.03f, 0.03f, 0.03f, 4, 6), BRASS, Mat.METAL, x = x, y = 0.08f, z = 0.475f)
            pb.add(Sculpt.ellipsoid(0.03f, 0.03f, 0.03f, 4, 6), BRASS, Mat.METAL, x = x, y = -0.08f, z = 0.475f)
            x += 0.9f
        }
        if (teeth) {
            var tx = -hw + 0.9f
            while (tx < hw - 0.8f) { pb.add(Sculpt.spike(0.04f, 0.15f, 5), IRON, Mat.METAL, x = tx, y = -0.2f, z = 0.38f, rx = 180f); tx += 0.28f }
        }
        return pb.data
    }

    // ================================================================================================ environment
    /** Iron brazier on three legs with a bed of embers and geometric flames (animated halo + light come from the scene). Origin = ground. */
    fun brazier(): MeshData {
        val pb = PartBuilder()
        pb.add(Sculpt.shell(listOf(0.36f to 0.66f, 0.33f to 0.55f, 0.22f to 0.42f, 0.07f to 0.35f), 16), IRON, Mat.METAL)
        pb.add(band(0.358f, 0.06f, 0.03f, 16), BRASS, Mat.METAL, y = 0.64f)
        for (k in 0 until 3) {
            val a = TAU * k / 3 + 0.5f
            pb.add(Sculpt.tube(5, 6, { t -> p3(cos(a) * (0.16f + 0.2f * t), 0.40f * (1f - t), sin(a) * (0.16f + 0.2f * t)) }, { 0.032f - 0.008f * it }, cap = true), IRON, Mat.METAL)
        }
        for (k in 0 until 9) {
            val a = TAU * k / 9 * 1.3f; val d = 0.05f + 0.19f * ((k * 37) % 10) / 10f
            pb.add(Sculpt.ellipsoid(0.07f, 0.04f, 0.07f, 5, 7), EMBER, Mat.EMISSIVE, x = cos(a) * d, y = 0.55f + 0.02f * (k % 3), z = sin(a) * d)
        }
        for (k in 0 until 5) {
            val a = TAU * k / 5; val d = if (k == 0) 0f else 0.12f
            pb.add(Sculpt.spike(0.07f, 0.34f + 0.12f * ((k * 7) % 3), 6), 0xFFB040, Mat.EMISSIVE, x = cos(a) * d, y = 0.56f, z = sin(a) * d, rx = 6f * (k % 3 - 1), rz = 5f * (k % 2 * 2 - 1))
        }
        return pb.data
    }

    /** A pointed gothic arch of weathered stone on two columns, the right side broken off; width [w] between the column centres. */
    fun archRuin(w: Float = 4.2f, height: Float = 3.4f): MeshData {
        val pb = PartBuilder()
        val y0 = height
        fun arch(t: Float): FloatArray = if (t < 0.5f) {
            val a = PI - (t / 0.5f) * (PI / 3); p3(w / 2 + w * cos(a).toFloat(), y0 + w * sin(a).toFloat(), 0f)
        } else {
            val a = PI / 3 - ((t - 0.5f) / 0.5f) * (PI / 3); p3(-w / 2 + w * cos(a).toFloat(), y0 + w * sin(a).toFloat(), 0f)
        }
        // the arch stops at 78 % (the rest has fallen)
        pb.add(Sculpt.tube(20, 6, { t -> arch(t * 0.78f) }, { 0.26f }, cap = true), STONE, Mat.STONE)
        pb.add(Sculpt.tube(20, 6, { t -> val q = arch(t * 0.78f); p3(q[0] * 0.97f, (q[1] - y0) * 0.9f + y0 - 0.0f, -0.12f) }, { 0.13f }, cap = true), STONE_D, Mat.STONE)
        // columns on bases (left full height, right broken)
        for ((i, sx) in floatArrayOf(-1f, 1f).withIndex()) {
            val h = if (i == 0) y0 else y0 * 0.62f
            pb.add(Sculpt.box(0.42f, 0.2f, 0.42f), STONE_D, Mat.STONE, x = sx * w / 2, y = 0.2f)
            pb.add(Sculpt.shell(listOf(0.27f to h, 0.30f to h * 0.6f, 0.34f to 0.4f), 14, radial = { ang, _, _ -> 1f + 0.05f * cos(ang * 6f) }), STONE, Mat.STONE, x = sx * w / 2)
            if (i == 1) pb.add(Sculpt.box(0.3f, 0.12f, 0.28f), STONE, Mat.STONE, x = sx * w / 2 + 0.05f, y = h + 0.02f, rz = 14f, ry = 20f)
        }
        // rubble
        pb.add(Sculpt.box(0.34f, 0.18f, 0.26f), STONE, Mat.STONE, x = w / 2 + 0.65f, y = 0.18f, ry = 28f, rz = 8f)
        pb.add(Sculpt.box(0.26f, 0.14f, 0.2f), STONE_D, Mat.STONE, x = w / 2 + 1.15f, y = 0.14f, ry = -40f)
        pb.add(Sculpt.box(0.3f, 0.16f, 0.22f), STONE, Mat.STONE, x = w / 2 + 0.3f, y = 0.16f, z = 0.55f, ry = 70f, rz = -12f)
        return pb.data
    }

    /** Iron lamp post with a hooked arm and a lantern whose glass burns sickly green. Origin = ground. */
    fun lamp(): MeshData {
        val pb = PartBuilder()
        pb.add(Sculpt.tube(8, 8, { t -> p3(0f, 2.3f * t, 0f) }, { 0.06f - 0.03f * it }, cap = true), IRON, Mat.METAL)
        pb.add(Sculpt.box(0.12f, 0.1f, 0.12f), IRON, Mat.METAL, y = 0.1f)
        pb.add(band(0.05f, 0.08f, 0.03f, 10), BRASS, Mat.METAL, y = 1.2f)
        pb.add(Sculpt.tube(8, 6, { t -> val a = t * PI.toFloat() * 0.62f; p3(0.28f * (1f - cos(a).toFloat()), 2.3f + 0.18f * sin(a).toFloat(), 0f) }, { 0.026f }, cap = true), IRON, Mat.METAL)
        val lx = 0.30f; val ly = 2.05f
        pb.add(Sculpt.box(0.09f, 0.115f, 0.09f), SICK, Mat.EMISSIVE, x = lx, y = ly)
        for (cx in floatArrayOf(-1f, 1f)) for (cz in floatArrayOf(-1f, 1f))
            pb.add(Sculpt.tube(2, 4, { t -> p3(0f, 0.27f * t, 0f) }, { 0.014f }, cap = true), IRON, Mat.METAL, x = lx + cx * 0.105f, y = ly - 0.135f, z = cz * 0.105f)
        pb.add(Sculpt.spike(0.17f, 0.15f, 4), IRON, Mat.METAL, x = lx, y = ly + 0.135f, ry = 45f)
        pb.add(Sculpt.box(0.12f, 0.02f, 0.12f), IRON, Mat.METAL, x = lx, y = ly - 0.14f)
        return pb.data
    }

    /** A 2.4 m run of spiked iron fence along X. Origin = ground at the segment centre. */
    fun fence(): MeshData {
        val pb = PartBuilder()
        pb.add(Sculpt.box(1.2f, 0.022f, 0.022f), IRON, Mat.METAL, y = 0.28f)
        pb.add(Sculpt.box(1.2f, 0.022f, 0.022f), IRON, Mat.METAL, y = 0.72f)
        for (k in 0 until 11) {
            val x = -1.1f + 0.22f * k
            pb.add(Sculpt.box(0.016f, 0.45f, 0.016f), IRON, Mat.METAL, x = x, y = 0.45f)
            pb.add(Sculpt.spike(0.03f, 0.13f, 4), IRON, Mat.METAL, x = x, y = 0.9f, ry = 45f)
        }
        for (x in floatArrayOf(-1.2f, 0f, 1.2f)) {
            pb.add(Sculpt.box(0.04f, 0.55f, 0.04f), IRON, Mat.METAL, x = x, y = 0.55f)
            pb.add(Sculpt.ellipsoid(0.05f, 0.05f, 0.05f, 5, 7), BRASS, Mat.METAL, x = x, y = 1.12f)
        }
        return pb.data
    }

    /** A skull with a melting candle on its crown, wax running down over the bone. Origin = ground. */
    fun skullCandle(): MeshData {
        val pb = PartBuilder()
        pb.addKeep(skullHead(2f, 1, 0xFF7A20), y = 0.30f, ry = 20f, rz = -4f, s = 0.27f)
        pb.add(Sculpt.tube(4, 9, { t -> p3(0f, 0.5f + 0.27f * t, 0.0f) }, { 0.062f }, cap = true), WAX, Mat.WAX)
        for (k in 0 until 4) {
            val a = TAU * k / 4 + 0.3f; val len = 0.1f + 0.05f * (k % 3)
            pb.add(Sculpt.tube(3, 6, { t -> p3(cos(a) * (0.065f + 0.02f * t), 0.78f - len * t, sin(a) * (0.065f + 0.02f * t)) }, { 0.016f - 0.004f * it }, cap = true), WAX, Mat.WAX)
        }
        pb.add(Sculpt.spike(0.04f, 0.13f, 6), 0xFFE9B0, Mat.EMISSIVE, y = 0.77f)
        return pb.data
    }

    /** Tall iron-bound cross for the graveyard skyline. Origin = ground. */
    fun gravecross(): MeshData {
        val pb = PartBuilder()
        pb.add(Sculpt.box(0.55f, 0.12f, 0.4f), STONE_D, Mat.STONE, y = 0.12f)
        pb.add(Sculpt.box(0.2f, 0.12f, 0.2f), STONE, Mat.STONE, y = 0.34f)
        pb.add(Sculpt.box(0.09f, 0.95f, 0.09f), STONE, Mat.STONE, y = 1.38f)
        pb.add(Sculpt.box(0.40f, 0.085f, 0.085f), STONE, Mat.STONE, y = 1.78f)
        pb.add(band(0.09f, 0.06f, 0.03f, 8), IRON, Mat.METAL, y = 1.0f)
        pb.add(Sculpt.spike(0.06f, 0.15f, 4), STONE, Mat.STONE, y = 2.34f, ry = 45f)
        return pb.data
    }

    /** Hanging iron cage with a lit skull inside, for chains from the sky. Origin = the hook. */
    fun hangingCage(): MeshData {
        val pb = PartBuilder()
        for (k in 0 until 6) pb.add(lowLink(0.12f, 0.07f, 0.012f), IRON, Mat.METAL, y = -0.07f * k - 0.05f, ry = if (k % 2 == 0) 0f else 90f)
        val cy = -0.85f
        pb.addKeep(skullHead(18f, 0, 0x9CFF57), y = cy, s = 0.2f, ry = 10f)
        for (k in 0 until 6) {
            val a = TAU * k / 6
            pb.add(Sculpt.tube(6, 5, { t -> val ang = (t - 0.5f) * PI.toFloat() * 0.9f; p3(cos(a) * 0.3f * cos(ang).toFloat(), cy + 0.38f * sin(ang).toFloat(), sin(a) * 0.3f * cos(ang).toFloat()) }, { 0.012f }, cap = true), IRON, Mat.METAL)
        }
        pb.add(band(0.3f, 0.04f, 0.015f, 14), IRON, Mat.METAL, y = cy + 0.34f)
        pb.add(band(0.3f, 0.04f, 0.015f, 14), IRON, Mat.METAL, y = cy - 0.34f)
        return pb.data
    }

    /** Count helper for budgets in tests. */
    fun triangles(d: MeshData) = d.inN / 3
    @Suppress("unused") private fun unusedAbs(x: Float) = abs(x)
}

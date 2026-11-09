package com.tjshea.neonreaper.gl.chars

import com.tjshea.neonreaper.gl.Bones
import com.tjshea.neonreaper.gl.Mat
import com.tjshea.neonreaper.gl.Mat4
import com.tjshea.neonreaper.gl.Mesh
import com.tjshea.neonreaper.gl.MeshData
import com.tjshea.neonreaper.gl.PartBuilder
import com.tjshea.neonreaper.gl.Scene3D
import com.tjshea.neonreaper.gl.Sculpt
import com.tjshea.neonreaper.gl.Sculpt.gauss
import com.tjshea.neonreaper.gl.Sculpt.sstep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Cursed Skull: a large, cracked, dingy human skull floating above the ground in a ghostly green haze. A rusted iron nail is
 * hammered through its temples, a crown of thorns bites into the cranium, dark roots crawl over the bone and into a socket, wax
 * drips down the brow, and sickly green flames burn in the sockets. A hinged jaw chatters; chains hang from the nail and tatters
 * of ectoplasm trail below.
 *
 * Budget: 5 mesh draws (skull, jaw, two flames, hanging tatters + chains) + 3 glow sprites, about 4,000 triangles.
 * The bone meshes are built K times larger and drawn at 1/K so the shader's bone noise (pits, cracks) is K times finer.
 */
class SkullModel : CharacterModel {
    private val c0 = floatArrayOf(0f, 0.74f, 0f)                    // skull centre (design space)
    private val ss = 0.34f                                          // head units -> design space
    private val us = 1.22f                                          // the whole figure is drawn this much larger ("a large skull")
    private val kk = 3f                                             // bone-noise refinement (see class doc)
    private val hinge = floatArrayOf(Bones.JAW_HINGE[0] * ss, c0[1] + Bones.JAW_HINGE[1] * ss, Bones.JAW_HINGE[2] * ss)
    private val nailY = 0.82f
    private val nailZ = 0.03f
    private val fb = floatArrayOf(0.082f, 0.742f, 0.12f)            // flame base (socket floor), mirrored for the left eye

    private val skull: Mesh
    private val jaw: Mesh
    private val flameL: Mesh
    private val flameR: Mesh
    private val hang: Mesh

    val drawCalls = 5
    val glowSprites = 3
    val triangleCount: Int get() = (skull.indexCount + jaw.indexCount + flameL.indexCount + flameR.indexCount + hang.indexCount) / 3

    private fun sq(x: Float) = x * x

    // ---------------------------------------------------------------------------------------------- skull shape
    /** Bones.skullShape with deeper, wider orbits and nose and stronger cheekbones/brow, so it still reads at ~2,000 triangles. */
    private fun orbit(dx: Float, dy: Float, dz: Float, sx: Float): Float {
        fun g(cx: Float, cy: Float, cz: Float): Float = gauss(0.50f * sq(dx - cx * sx) + 1.15f * sq(dy - cy) + 0.32f * sq(dz - cz), 0.17f)
        return g(0.50f, 0.17f, 0.84f) + g(0.31f, 0.06f, 0.92f) * 0.9f
    }

    private fun shape(dx: Float, dy: Float, dz: Float): Float {
        var r = 1f
        val front = sstep(-0.1f, 0.55f, dz)
        r *= 1f - 0.30f * sstep(-0.05f, -0.85f, dy) * front
        r *= 1f - 0.10f * sstep(-0.2f, -1f, dy)
        r -= 0.54f * (orbit(dx, dy, dz, -1f) + orbit(dx, dy, dz, 1f))
        r -= 0.60f * gauss(1.5f * dx * dx + 0.45f * sq(dy + 0.30f) + 0.35f * sq(dz - 0.93f), 0.22f)
        r += 0.10f * gauss(sq(dy - 0.40f), 0.10f) * sstep(0.15f, 0.8f, dz) * sstep(0.98f, 0.35f, abs(dx))
        for (sx in floatArrayOf(-1f, 1f)) {
            r += 0.20f * gauss(sq(dx - 0.66f * sx) + sq(dy + 0.22f) + 0.4f * sq(dz - 0.64f), 0.19f)
            r -= 0.08f * gauss(sq(dx - 0.97f * sx) + sq(dy - 0.10f) + sq(dz - 0.18f), 0.22f)
        }
        r += 0.035f * gauss(dx * dx, 0.12f) * sstep(0.1f, 0.8f, dy)
        return r
    }

    /** Point on the skull surface along direction (dx, dy, dz), pushed out by factor [k] (design space). */
    private fun sp(dx: Float, dy: Float, dz: Float, k: Float): FloatArray {
        val l = sqrt(dx * dx + dy * dy + dz * dz); val ux = dx / l; val uy = dy / l; val uz = dz / l
        val r = shape(ux, uy, uz) * k
        return floatArrayOf(c0[0] + ux * r * 0.86f * ss, c0[1] + uy * r * 1.08f * ss, c0[2] + uz * r * 1.06f * ss)
    }

    private fun path(k: Float, vararg v: Float): List<FloatArray> = (0 until v.size / 3).map { sp(v[it * 3], v[it * 3 + 1], v[it * 3 + 2], k) }

    private fun cone(r: Float, len: Float, sides: Int = 4): MeshData = Sculpt.tube(2, sides, { t -> floatArrayOf(0f, len * t, 0f) }, { t -> r * (1f - t) }, cap = true)

    // ---------------------------------------------------------------------------------------------- skull
    private fun buildSkull(): Mesh {
        val pb = PartBuilder()
        val bone = Sculpt.blob(26, 38, 0.86f, 1.08f, 1.06f, ao = { r, dy -> (sstep(0.52f, 0.97f, r) * (0.72f + 0.28f * sstep(-1f, 0.3f, dy))).coerceIn(0.04f, 1f) }) { dx, dy, dz -> shape(dx, dy, dz) }
        KitA.shade(bone) { x, y, z, _, _, _ ->
            val px = x / 0.86f; val py = y / 1.08f; val pz = z / 1.06f; val l = sqrt(px * px + py * py + pz * pz).coerceAtLeast(1e-4f)
            val o = (orbit(px / l, py / l, pz / l, -1f) + orbit(px / l, py / l, pz / l, 1f)).coerceAtMost(1f)
            (0.70f + 0.30f * sstep(-1.0f, 0.5f, y)) * (1f - 0.45f * o)                                      // grime gathers low, sockets ringed dark
        }
        pb.add(bone, 0xA08C62, Mat.BONE, x = c0[0], y = c0[1], z = c0[2], sx = ss)
        // upper teeth: squared, chunky, the canines longer
        for (i in -4..4) {
            val x = i * 0.105f; val z = 0.93f - 0.40f * sq(abs(x) / 0.5f) - 0.03f
            val len = 0.11f * (if (abs(i) == 3) 1.5f else 1f)
            pb.add(Sculpt.tube(2, 4, { t -> floatArrayOf(0f, -len * t, 0f) }, { t -> 0.050f * (1f - 0.5f * t) }, cap = true), 0xC4B58A, Mat.BONE,
                x = c0[0] + x * ss, y = c0[1] - 0.58f * ss, z = c0[2] + z * ss, rz = i * 1.5f, ry = 45f, sx = ss, ao = 0.95f)
        }
        // two roots crawling over the bone, one diving into the right socket
        for (rt in listOf(
            path(1.035f, 0.75f, 0.65f, -0.10f, 0.85f, 0.45f, 0.15f, 0.80f, 0.30f, 0.35f, 0.70f, 0.18f, 0.55f, 0.55f, 0.10f, 0.68f),
            path(1.035f, -0.70f, -0.40f, 0.45f, -0.78f, -0.20f, 0.50f, -0.66f, -0.02f, 0.66f, -0.52f, 0.05f, 0.78f)))
            pb.add(Sculpt.tube(rt.size * 2, 4, KitA.spline(rt), { t -> 0.016f - 0.008f * t }, cap = true), 0x2B2018, Mat.MATTE)
        // crown of thorns biting into the cranium, with wax running down the brow
        val ring = (0..24).map { i -> val a = KitA.TAU * i / 24f; floatArrayOf(0.232f * cos(a), 0.985f + 0.012f * sin(5f * a) - 0.022f * max(0f, sin(a)), -0.01f + 0.282f * sin(a)) }
        pb.add(Sculpt.tube(24, 4, KitA.poly(ring), { t -> 0.012f + 0.002f * sin(KitA.TAU * 9f * t) }), 0x2A1E16, Mat.MATTE)
        for (i in 0 until 14) {
            val a = KitA.TAU * i / 14f + 0.2f; val p = ring[(i * 24 / 14).coerceAtMost(23)]
            pb.add(cone(0.009f, 0.050f + 0.020f * KitA.hash(i.toFloat()), 3), 0x2A1E16, Mat.MATTE, x = p[0], y = p[1], z = p[2],
                rx = 55f, ry = Math.toDegrees((PI / 2 - a).toDouble()).toFloat())
        }
        for ((i, a) in floatArrayOf(0.95f, 1.30f, 1.80f, 2.20f).withIndex()) {
            val x = 0.232f * cos(a) * 0.97f; val z = -0.01f + 0.282f * sin(a) * 0.97f
            val len = 0.07f + 0.05f * KitA.hash(i + 11f)
            pb.add(Sculpt.tube(3, 5, { t -> floatArrayOf(0f, -len * t, 0.012f * t) }, { t -> 0.007f - 0.002f * t + 0.005f * sstep(0.7f, 1f, t) }, cap = true), 0xCFC49A, Mat.WAX, x = x, y = 0.975f, z = z + 0.012f)
        }
        // the rusted nail driven through the temples
        val nail = 0x7A4A2E
        pb.add(Sculpt.tube(1, 8, { t -> floatArrayOf(-0.014f + 0.014f * t, 0f, 0f) }, { 0.052f }, cap = true), nail, Mat.METAL, x = -0.375f, y = nailY, z = nailZ, rz = -5f)
        pb.add(Sculpt.tube(1, 8, { t -> floatArrayOf(0.030f * t, 0f, 0f) }, { t -> 0.026f - 0.010f * t }, cap = true), nail, Mat.METAL, x = -0.375f, y = nailY, z = nailZ, rz = -5f)
        pb.add(Sculpt.tube(4, 6, { t -> floatArrayOf(0.030f + 0.870f * t, 0f, 0f) }, { t -> 0.016f * (1f - 0.92f * t) }, cap = true), nail, Mat.METAL, x = -0.375f, y = nailY, z = nailZ, rz = -5f)
        KitA.xform(pb.data, ORIGIN, ORIGIN, kk)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- jaw (built around the hinge)
    private fun buildJaw(): Mesh {
        val pb = PartBuilder()
        val h = Bones.JAW_HINGE
        val pts = listOf(floatArrayOf(-0.60f, -0.02f, 0.02f), floatArrayOf(-0.58f, -0.40f, 0.12f), floatArrayOf(-0.48f, -0.76f, 0.40f), floatArrayOf(-0.26f, -0.91f, 0.70f),
            floatArrayOf(0f, -0.95f, 0.80f), floatArrayOf(0.26f, -0.91f, 0.70f), floatArrayOf(0.48f, -0.76f, 0.40f), floatArrayOf(0.58f, -0.40f, 0.12f), floatArrayOf(0.60f, -0.02f, 0.02f))
            .map { floatArrayOf(it[0] - h[0], it[1] - h[1], it[2] - h[2]) }
        val bar = Sculpt.tube(14, 7, KitA.spline(pts), { t -> val c = abs(t - 0.5f) * 2f; 0.060f + 0.075f * (1f - c) * (1f - c) }, cap = true)
        KitA.shade(bar) { _, y, _, _, _, _ -> 0.75f + 0.25f * sstep(-0.9f, 0.1f, y) }
        pb.add(bar, 0x92805A, Mat.BONE, sx = ss)
        for (i in -3..3) {
            val x = i * 0.105f; val z = 0.78f - 0.30f * sq(x / 0.55f)
            val by = -0.84f + 0.2f * sq(x / 0.4f)
            val len = 0.11f * (if (abs(i) == 2) 1.25f else 1f)
            pb.add(Sculpt.tube(2, 4, { t -> floatArrayOf(0f, len * t, 0f) }, { t -> 0.046f * (1f - 0.5f * t) }, cap = true), 0xC4B58A, Mat.BONE,
                x = x * ss, y = (by - h[1]) * ss, z = (z - h[2]) * ss, ry = 45f, sx = ss, ao = 0.95f)
        }
        KitA.xform(pb.data, ORIGIN, ORIGIN, kk)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- flames, tatters
    private fun buildFlame(sx: Float): Mesh {
        val pb = PartBuilder()
        val path = KitA.spline(listOf(floatArrayOf(sx * fb[0], fb[1], fb[2]), floatArrayOf(sx * 0.088f, 0.766f, 0.21f), floatArrayOf(sx * 0.092f, 0.800f, 0.29f),
            floatArrayOf(sx * 0.088f, 0.852f, 0.335f), floatArrayOf(sx * 0.080f, 0.915f, 0.33f)))
        val main = Sculpt.tube(7, 8, path, { t -> 0.027f * sin(PI.toFloat() * t.pow(0.6f)).pow(0.9f) * (1f - 0.3f * t) + 0.001f }, cap = true)
        KitA.shade(main) { _, y, _, _, _, _ -> 1.15f - 0.50f * sstep(0.74f, 0.90f, y) }
        pb.add(main, 0x38E83A, Mat.EMISSIVE)
        val tongue = KitA.spline(listOf(floatArrayOf(sx * 0.072f, 0.752f, 0.16f), floatArrayOf(sx * 0.054f, 0.785f, 0.24f), floatArrayOf(sx * 0.040f, 0.830f, 0.27f)))
        pb.add(Sculpt.tube(3, 4, tongue, { t -> 0.018f * sin(PI.toFloat() * t.pow(0.7f)) + 0.001f }, cap = true), 0x66F25A, Mat.EMISSIVE)
        return pb.build()
    }

    /** Pivot = the nail axis: tatters of ectoplasm trail behind and below the jaw, a chain hangs from each end of the nail. */
    private fun buildHang(): Mesh {
        val pb = PartBuilder()
        for (i in 0 until 11) {
            val x = -0.34f + 0.068f * i
            val len = (0.30f + 0.24f * KitA.hash(i * 3f + 1f)) * (1.15f - 0.6f * abs(x) / 0.34f)
            pb.add(Sculpt.rag(0.052f, len, 0.10f), 0x4C6A52, Mat.CLOTH, ao = 0.9f, x = x, y = 0.56f - 0.03f * KitA.hash(i + 7f), z = -0.04f - 0.05f * abs(x) / 0.34f, ry = 180f + x * 40f)
        }
        for (cx in floatArrayOf(-0.385f, 0.445f)) {
            for (i in 0 until 3) pb.add(KitA.link(0.034f, 0.018f, 0.0042f), 0x5A5A66, Mat.METAL, x = cx, y = nailY - 0.040f - i * 0.030f, z = nailZ, ry = if (i % 2 == 0) 0f else 90f)
            pb.add(cone(0.013f, 0.042f), 0x4A4A55, Mat.METAL, x = cx, y = nailY - 0.040f - 3 * 0.030f + 0.004f, z = nailZ, rx = 180f)
        }
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- animation
    private val stage = FloatArray(16); private val km = FloatArray(16); private val sm = FloatArray(16); private val jm = FloatArray(16)
    private val fL = FloatArray(16); private val fR = FloatArray(16); private val hm = FloatArray(16)

    init {
        skull = buildSkull(); jaw = buildJaw(); flameL = buildFlame(-1f); flameR = buildFlame(1f); hang = buildHang()
    }

    override fun draw(sc: Scene3D, mm: FloatArray, t: Float, ex: Float, dim: Float) {
        val fl = KitA.flicker(t, 2.1f); val fl2 = KitA.flicker(t * 1.13f + 3f, 5.3f)
        // ---- poses first (local lights need them before the first drawV)
        KitA.set(stage, mm)
        val sh = ex * 0.007f
        Mat4.translate(stage, sh * sin(t * 47f), 0.022f * sin(t * 1.1f) + sh * sin(t * 41f), 0f)           // floats, bobbing; shakes when excited
        Mat4.translate(stage, c0[0], c0[1], c0[2]); Mat4.scale(stage, us, us, us); Mat4.translate(stage, -c0[0], -c0[1], -c0[2])
        val yaw = sin(t * 0.45f) * 11f * (1f - ex) + ex * sin(t * 19f) * 4f
        val roll = sin(t * 0.6f + 0.7f) * 3f + ex * sin(t * 33f) * 4f
        val pitch = sin(t * 0.8f) * 2f - ex * 5f
        KitA.set(km, stage)
        Mat4.translate(km, c0[0], c0[1], c0[2]); Mat4.rotate(km, yaw, 0f, 1f, 0f); Mat4.rotate(km, pitch, 1f, 0f, 0f); Mat4.rotate(km, roll, 0f, 0f, 1f)
        Mat4.translate(km, -c0[0], -c0[1], -c0[2])
        KitA.set(sm, km); Mat4.scale(sm, 1f / kk, 1f / kk, 1f / kk)
        val open = 1.5f + 1.2f * sin(t * 0.7f) + ex * 24f * sstep(-0.35f, 0.35f, sin(t * 21f))             // barely moves; clacks rapidly when excited
        KitA.set(jm, km); Mat4.translate(jm, hinge[0], hinge[1], hinge[2]); Mat4.rotate(jm, open, 1f, 0f, 0f); Mat4.scale(jm, 1f / kk, 1f / kk, 1f / kk)
        val ww = 0.9f + 0.15f * fl + 0.3f * ex
        KitA.set(fL, km); Mat4.translate(fL, -fb[0], fb[1], fb[2]); Mat4.rotate(fL, sin(t * 7f) * 5f * (1f + ex) - ex * 8f, 1f, 0f, 0f); Mat4.scale(fL, ww, 0.85f + 0.35f * fl2 + 0.5f * ex, ww); Mat4.translate(fL, fb[0], -fb[1], -fb[2])
        KitA.set(fR, km); Mat4.translate(fR, fb[0], fb[1], fb[2]); Mat4.rotate(fR, sin(t * 6.3f + 1.7f) * 5f * (1f + ex) - ex * 8f, 1f, 0f, 0f); Mat4.scale(fR, ww, 0.85f + 0.35f * fl + 0.5f * ex, ww); Mat4.translate(fR, -fb[0], -fb[1], -fb[2])
        KitA.pivot(hm, stage, 0f, nailY, nailZ, sin(t * 1.3f) * 4f + ex * sin(t * 9f) * 8f, 0f, sin(t * 0.9f + 1f) * 2f)

        // ---- two local lights: the flames light the face, a second one burns inside the skull and lights the sockets' inner walls
        sc.beginLocalLights()
        sc.pointLightAt(km, 0f, 0.80f, 0.45f, 1.0f, 0x4CFF3A, (0.28f + 0.14f * fl + 0.40f * ex) * dim)
        sc.pointLightAt(km, 0f, 0.74f, 0.04f, 0.45f, 0x3CFF3A, (0.24f + 0.16f * fl2 + 0.30f * ex) * dim)

        // ---- draws
        sc.drawV(skull, sm, dim = dim, rim = 0x103A1C, spec = 0.35f, shine = 22f)
        sc.drawV(jaw, jm, dim = dim, rim = 0x103A1C, spec = 0.35f, shine = 22f)
        sc.drawV(flameL, fL, emis = 0.95f + 0.30f * fl2 + 0.6f * ex, dim = dim)
        sc.drawV(flameR, fR, emis = 0.95f + 0.30f * fl + 0.6f * ex, dim = dim)
        sc.drawV(hang, hm, dim = dim, rim = 0x103A1C, spec = 0.4f, shine = 24f, wave = 1.4f + ex)
        // glow sprites: the two flames and a ghostly aura behind the skull
        sc.glowAt(km, -0.088f, 0.79f, 0.27f, 0.17f * (0.8f + 0.25f * fl2 + 0.8f * ex), 0x4CFF3A, 0.55f * dim, 0.5f)
        sc.glowAt(km, 0.088f, 0.79f, 0.27f, 0.17f * (0.8f + 0.25f * fl + 0.8f * ex), 0x4CFF3A, 0.55f * dim, 0.5f)
        sc.glowAt(km, 0f, 0.74f, -0.20f, 1.9f, 0x2FFF3A, (0.26f + 0.08f * fl + 0.14f * ex) * dim, 0f)
    }

    private companion object { val ORIGIN = floatArrayOf(0f, 0f, 0f) }
}

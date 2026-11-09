package com.tjshea.neonreaper.gl.chars

import com.tjshea.neonreaper.gl.Mat
import com.tjshea.neonreaper.gl.Mat4
import com.tjshea.neonreaper.gl.Mesh
import com.tjshea.neonreaper.gl.MeshData
import com.tjshea.neonreaper.gl.PartBuilder
import com.tjshea.neonreaper.gl.Scene3D
import com.tjshea.neonreaper.gl.Sculpt
import com.tjshea.neonreaper.gl.Sculpt.gauss
import com.tjshea.neonreaper.gl.Sculpt.sstep
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Blood Raven: a big, menacing raven perched on a gnarled branch with a skull, glossy black layered plumage (feather shader:
 * sharp sheen, blood-dark tips, torn feathers), a heavy hooked beak, red burning eyes and sharp talons.
 * Idle: the body sits in three-quarter view, wings folded; the head tilts and twitches, it blinks, now and then it shrugs its wings
 * open and croaks. Excited: it turns to face you, rears up, spreads and beats its wings wide, caws with the beak wide open and
 * shakes embers and ash loose.
 *
 * Bird space: facing +X, +Y up, the bird's right side is +Z; the branch runs along Z. The whole model is yawed in [draw].
 * 6 meshes: body (+ tail, feet), head (+ neck, beak, eyes), jaw, branch (+ skull), and one mesh per wing (the wing folds by
 * compressing its span and rolling, no extra parts). ~3,900 triangles.
 */
class RavenModel : CharacterModel {
    private val bodyC = floatArrayOf(0f, 0.40f, 0f)
    private val headC = floatArrayOf(0.35f, 0.80f, 0f)
    private val neckPivot = floatArrayOf(0.20f, 0.60f, 0f)
    private val jawHinge = floatArrayOf(0.455f, 0.760f, 0f)
    private val shoulder = floatArrayOf(0.00f, 0.56f, 0.13f)          // right wing root; the left one is mirrored
    private val feet = floatArrayOf(0.02f, 0.19f)                      // x, y of the toes' perch point

    private val BLACK = 0x30304A; private val BLACK_T = 0x4A4A68; private val BLOOD_T = 0x9A1C2C
    private val body: Mesh
    private val head: Mesh
    private val jaw: Mesh
    private val branch: Mesh
    private val wingR: Mesh
    private val wingL: Mesh
    val triangles: Int

    // eye direction in head units (+X forward)
    private val eyeDir = CharKit.unit(0.55f, 0.20f, 0.80f)

    init {
        body = buildBody(); head = buildHead(); jaw = buildJaw(); branch = buildBranch()
        val w = buildWing(); wingR = Mesh(w); wingL = Mesh(CharKit.mirrorZ(w))
        triangles = (body.indexCount + head.indexCount + jaw.indexCount + branch.indexCount + wingR.indexCount + wingL.indexCount) / 3
    }

    private fun rad(d: Float) = d * (PI / 180.0).toFloat()

    // ---------------------------------------------------------------------------------------------- body
    private fun bodyShape(dx: Float, dy: Float, dz: Float): Float {
        var r = 1f
        r += 0.14f * sstep(-0.1f, 0.8f, dx)                              // deep chest
        r *= 1f - 0.34f * sstep(-0.2f, -1f, dx)                          // tapering to the rump
        r *= 1f - 0.08f * sstep(0.0f, -1f, dy)
        return r
    }

    private fun buildBody(): Mesh {
        val bp = PartBuilder()                                           // body frame: centre at the origin, +X forward, untilted
        bp.add(Sculpt.blob(8, 12, 0.34f, 0.185f, 0.16f, shape = ::bodyShape), 0x20202E, Mat.FEATHER, ao = 0.9f)
        // layered plumage: rows of overlapping feathers lying along the body, tips towards the tail
        val rnd = Random(3)
        val rowsDx = floatArrayOf(0.88f, 0.68f, 0.48f, 0.28f, 0.08f, -0.12f, -0.32f, -0.52f, -0.72f)
        val feather = ArrayList<MeshData>()
        for (v in 0 until 5) feather += CharKit.feather(0.135f, 0.092f, 0.55f, 0.015f, 0.35f, 100 + v, BLACK, if (v >= 3) BLOOD_T else BLACK_T, 2)
        var count = 0
        for ((i, dx) in rowsDx.withIndex()) {
            val rho = sqrt(1f - dx * dx)
            val n = 12
            for (j in 0 until n) {
                val beta = (2 * PI * (j + 0.5f * (i % 2)) / n).toFloat()
                val dy = rho * cos(beta); val dz = rho * sin(beta)
                if (dz < -0.5f || dy < -0.62f) continue                    // the far side and the belly are never seen
                val r = bodyShape(dx, dy, dz)
                val p = floatArrayOf(dx * r * 0.34f, dy * r * 0.185f, dz * r * 0.16f)
                val nrm = CharKit.unit(dx / 0.34f, dy / 0.185f, dz / 0.16f)
                val tx = -1f - nrm[0] * (-nrm[0]); val ty = -nrm[1] * (-nrm[0]); val tz = -nrm[2] * (-nrm[0])
                if (tx * tx + ty * ty + tz * tz < 0.04f) continue
                val pk = 0.22f + 0.65f * rnd.nextFloat().pow(3)
                val ya = CharKit.unit(tx + pk * nrm[0], ty + pk * nrm[1], tz + pk * nrm[2])
                val d = CharKit.dot(nrm, ya)
                val za = CharKit.unit(nrm[0] - ya[0] * d, nrm[1] - ya[1] * d, nrm[2] - ya[2] * d)
                val xa = CharKit.cross(ya, za)
                val k = rnd.nextInt(5)
                CharKit.place(bp, feather[k], floatArrayOf(p[0] - nrm[0] * 0.006f, p[1] - nrm[1] * 0.006f, p[2] - nrm[2] * 0.006f), xa, ya, za, 0xFFFFFF, Mat.FEATHER, 1f)
                count++
            }
        }
        // the tail: nine long feathers fanned behind the rump, a little drooping
        for (i in 0 until 9) {
            val ang = rad(-34f + 8.5f * i)
            val pitch = rad(-3f + 2f * abs(i - 4))
            val dd = floatArrayOf(-cos(ang) * cos(pitch), -sin(pitch), sin(ang) * cos(pitch))
            val up = floatArrayOf(0f, 1f, 0f)
            val d = CharKit.dot(up, dd)
            val za = CharKit.unit(up[0] - dd[0] * d, up[1] - dd[1] * d, up[2] - dd[2] * d)
            val xa = CharKit.cross(dd, za)
            val f = CharKit.feather(0.44f + 0.03f * (4 - abs(i - 4)), 0.095f, 0.3f, 0.05f, 0.55f, 200 + i, BLACK, if (i % 4 == 1) BLOOD_T else BLACK_T, 6)
            CharKit.place(bp, f, floatArrayOf(-0.27f, 0.0f + 0.004f * i, 0.0f), xa, dd, za, 0xFFFFFF, Mat.FEATHER, 1f)
        }
        // feathered thighs
        for (sz in floatArrayOf(-1f, 1f)) bp.add(Sculpt.ellipsoid(0.058f, 0.085f, 0.05f, 4, 6), 0x15151C, Mat.FEATHER, x = -0.01f, y = -0.14f, z = sz * 0.095f)

        val pb = PartBuilder()
        val m = Mat4.identity(); Mat4.translate(m, bodyC[0], bodyC[1], bodyC[2]); Mat4.rotate(m, 24f, 0f, 0f, 1f)
        pb.data.append(bp.data, m)
        // scaly legs and gripping talons, in bird space (the toes curl across the branch)
        val claw = CharKit.cone(0.0075f, 0.032f, 4, false)
        for (sz in floatArrayOf(-1f, 1f)) {
            val z0 = sz * 0.095f
            pb.add(Sculpt.tube(2, 4, { t -> floatArrayOf(0.045f - 0.020f * t, 0.275f - 0.075f * t, z0) }, { 0.0125f }, cap = true), 0x1C1C22, Mat.LEATHER)
            for (k in 0 until 3) {
                val zz = z0 + (k - 1) * 0.026f
                val pts = listOf(floatArrayOf(feet[0] - 0.01f, feet[1] + 0.012f, zz), floatArrayOf(feet[0] + 0.035f, feet[1] + 0.014f, zz), floatArrayOf(feet[0] + 0.062f, feet[1] - 0.012f, zz), floatArrayOf(feet[0] + 0.060f, feet[1] - 0.045f, zz))
                pb.add(Sculpt.tube(4, 3, { t -> CharKit.polyAt(pts, t) }, { t -> 0.0095f * (1f - 0.4f * t) }), 0x1C1C22, Mat.LEATHER)
                pb.add(claw, 0x3A3A44, Mat.METAL, x = feet[0] + 0.060f, y = feet[1] - 0.040f, z = zz, rx = 0f, rz = 175f)
            }
            val bt = listOf(floatArrayOf(feet[0] - 0.005f, feet[1] + 0.012f, z0), floatArrayOf(feet[0] - 0.045f, feet[1] + 0.008f, z0), floatArrayOf(feet[0] - 0.060f, feet[1] - 0.030f, z0))
            pb.add(Sculpt.tube(3, 3, { t -> CharKit.polyAt(bt, t) }, { 0.0095f }), 0x1C1C22, Mat.LEATHER)
            pb.add(claw, 0x3A3A44, Mat.METAL, x = feet[0] - 0.060f, y = feet[1] - 0.028f, z = z0, rz = 170f)
        }
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- head, beak, jaw
    private fun headShape(dx: Float, dy: Float, dz: Float): Float {
        var r = 1f
        r *= 1f - 0.18f * sstep(0.2f, 1f, dx)                                          // narrowing to the beak
        r *= 1f - 0.12f * sstep(0f, -1f, dy)
        r *= 1f - 0.10f * sstep(0.55f, 1f, dy)                                         // flat crown
        val az = abs(dz)
        r += 0.16f * gauss((dx - 0.50f).pow(2) + 2.0f * (dy - 0.50f).pow(2) + 1.5f * (az - 0.78f).pow(2), 0.28f)    // heavy, angry brow
        r -= 0.06f * gauss((dx - 0.55f).pow(2) + 1.6f * (dy - 0.18f).pow(2) + (az - 0.80f).pow(2), 0.20f)           // eye socket
        r += 0.07f * gauss((dx - 0.62f).pow(2) + 1.5f * (dy - 0.45f).pow(2) + 3f * dz * dz, 0.30f)                  // forehead
        return r
    }

    private fun buildHead(): Mesh {
        val pb = PartBuilder()
        pb.add(Sculpt.blob(10, 14, 0.148f, 0.100f, 0.092f, shape = ::headShape), 0x2A2A3C, Mat.FEATHER, x = headC[0], y = headC[1], z = headC[2])
        // neck: from the pivot up into the head, wrapped in shaggy throat hackles
        pb.add(Sculpt.tube(5, 5, { t -> floatArrayOf(neckPivot[0] + 0.15f * t, neckPivot[1] + 0.20f * t, 0f) }, { t -> 0.108f - 0.020f * t }, cap = true), 0x1E1E2C, Mat.FEATHER)
        val hf = CharKit.feather(0.11f, 0.050f, 0.3f, 0.02f, 0.6f, 300, BLACK, BLOOD_T, 3)
        for (i in 0 until 12) {
            val a = (2 * PI * i / 12).toFloat()
            val ty = 0.71f
            val dd = CharKit.unit(-0.25f + 0.3f * cos(a) * 0.0f, -1f, 0f)
            val nrm = CharKit.unit(0.4f + 0.2f * cos(a), 0.25f, sin(a))
            val d = CharKit.dot(nrm, dd)
            val za = CharKit.unit(nrm[0] - dd[0] * d, nrm[1] - dd[1] * d, nrm[2] - dd[2] * d)
            val xa = CharKit.cross(dd, za)
            CharKit.place(pb, hf, floatArrayOf(0.305f + 0.06f * cos(a), ty + 0.012f * cos(a), 0.095f * sin(a)), xa, dd, za, 0xFFFFFF, Mat.FEATHER, 1f)
        }
        // the heavy, hooked upper beak (built around its own origin so it can be flattened)
        val beak = Sculpt.tube(6, 5, { t -> floatArrayOf(0.225f * t, -0.008f * t - 0.065f * t * t * t, 0f) }, { t -> 0.054f * (1f - 0.80f * t.pow(1.4f)) + 0.004f }, cap = true)
        pb.add(beak, 0x17171C, Mat.METAL, x = 0.465f, y = 0.782f, z = 0f, sy = 1.0f, sz = 0.82f)
        // burning eyes (both sides) under the dark brow
        for (sz in floatArrayOf(-1f, 1f)) {
            val r = headShape(eyeDir[0], eyeDir[1], sz * eyeDir[2]) * 0.93f
            pb.add(Sculpt.ellipsoid(0.022f, 0.022f, 0.018f, 3, 6), 0xFF2A30, Mat.EMISSIVE,
                x = headC[0] + eyeDir[0] * r * 0.148f, y = headC[1] + eyeDir[1] * r * 0.100f, z = headC[2] + sz * eyeDir[2] * r * 0.092f)
        }
        return pb.build()
    }

    /** Lower mandible built around its hinge (the origin): shorter than the upper beak, with a blood-red mouth lining. */
    private fun buildJaw(): Mesh {
        val pb = PartBuilder()
        pb.add(Sculpt.tube(5, 4, { t -> floatArrayOf(0.185f * t, -0.006f * t + 0.016f * t * t, 0f) }, { t -> 0.042f * (1f - 0.82f * t.pow(1.2f)) + 0.004f }, cap = true), 0x17171C, Mat.METAL, sy = 0.8f, sz = 0.85f)
        pb.add(Sculpt.box(0.07f, 0.004f, 0.022f), 0xA0101A, Mat.EMISSIVE, x = 0.08f, y = 0.021f)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- branch and skull
    private fun buildBranch(): Mesh {
        val pb = PartBuilder()
        pb.add(Sculpt.tube(12, 5, { t -> floatArrayOf(0.03f * sin(5f * t + 1f), 0.14f + 0.018f * sin(7f * t) + 0.012f * sin(13f * t + 1f), -0.45f + 0.95f * t) },
            { t -> 0.054f - 0.016f * t + 0.007f * sin(11f * t) }, cap = true), 0x3A2A20, Mat.WOOD, ao = 0.95f)
        // two twigs and thorns
        pb.add(Sculpt.tube(4, 4, { t -> floatArrayOf(-0.03f - 0.10f * t, 0.15f + 0.20f * t, -0.20f - 0.12f * t) }, { t -> 0.016f * (1f - 0.85f * t) }), 0x2B1F18, Mat.WOOD)
        pb.add(Sculpt.tube(4, 4, { t -> floatArrayOf(0.02f + 0.06f * t, 0.15f + 0.16f * t, 0.30f + 0.10f * t) }, { t -> 0.014f * (1f - 0.85f * t) }), 0x2B1F18, Mat.WOOD)
        pb.add(CharKit.cone(0.012f, 0.06f, 4), 0x2B1F18, Mat.WOOD, x = 0.04f, y = 0.17f, z = -0.30f, rz = -50f)
        // the skull on the near end of the branch, watching with dying embers
        val ss = 0.085f; val sc0 = floatArrayOf(0.02f, 0.255f, 0.40f)
        pb.add(CharKit.frontSkull(10, 14), 0x7A7767, Mat.BONE, x = sc0[0], y = sc0[1], z = sc0[2], sx = ss, ao = 0.95f)
        pb.add(CharKit.jawLite(8, 4), 0x6C6959, Mat.BONE, x = sc0[0], y = sc0[1], z = sc0[2], sx = ss, ao = 0.9f)
        for (sx in floatArrayOf(-1f, 1f)) pb.add(Sculpt.ellipsoid(0.085f, 0.085f, 0.085f, 3, 6), 0xB01822, Mat.EMISSIVE, x = sc0[0] + sx * 0.237f * ss * 0.98f, y = sc0[1] + 0.075f * ss, z = sc0[2] + 0.607f * ss, sx = ss)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- wing
    /** The right wing in bird space around its shoulder: arm along +Z, flight feathers trailing along -X, surface up. */
    private fun buildWing(): MeshData {
        val pb = PartBuilder()
        val arm = 0.22f
        fun fan(pb: PartBuilder, f: MeshData, p: FloatArray, a: Float) {
            val ya = floatArrayOf(-cos(a), 0f, sin(a)); val za = floatArrayOf(0f, 1f, 0f)
            val xa = CharKit.cross(ya, za)
            CharKit.place(pb, f, p, xa, ya, za, 0xFFFFFF, Mat.FEATHER, 1f)
        }
        // secondaries along the forearm
        for (i in 0 until 9) {
            val zr = 0.03f + i * 0.021f
            fan(pb, CharKit.feather(0.31f - 0.004f * i, 0.056f, 0.10f, 0.04f, 0.55f, 400 + i, BLACK, if (i % 3 == 2) BLOOD_T else BLACK_T, 5), floatArrayOf(0f, 0.0015f * i, zr), rad(-8f + 2.2f * i))
        }
        // primaries from the wrist, fanning outwards (the longest in the middle, the outer ones shorter)
        val pl = floatArrayOf(0.38f, 0.42f, 0.44f, 0.45f, 0.44f, 0.41f, 0.37f, 0.33f, 0.27f)
        for (i in 0 until 9) {
            fan(pb, CharKit.feather(pl[i], 0.056f, 0.10f, 0.045f, 0.65f, 420 + i, BLACK, if (i % 2 == 0) BLOOD_T else BLACK_T, 5), floatArrayOf(0.002f, 0.0016f * (i + 9), arm + 0.004f * i), rad(6f + 6.8f * i))
        }
        // two rows of coverts hide the feather bases
        for (i in 0 until 9) fan(pb, CharKit.feather(0.135f, 0.050f, 0.12f, 0.03f, 0.4f, 440 + i, BLACK, BLACK_T, 3), floatArrayOf(0.065f, 0.012f + 0.001f * i, 0.03f + i * 0.0215f), rad(-6f + 1.8f * i))
        for (i in 0 until 7) fan(pb, CharKit.feather(0.085f, 0.046f, 0.12f, 0.02f, 0.3f, 460 + i, BLACK, if (i == 3) BLOOD_T else BLACK_T, 2), floatArrayOf(0.12f, 0.022f + 0.001f * i, 0.04f + i * 0.026f), rad(-4f + 1.5f * i))
        // the arm itself, the leading edge
        pb.add(Sculpt.tube(4, 4, { t -> floatArrayOf(0.115f, 0.026f, 0.0f + (arm - 0.02f) * t) }, { t -> 0.015f - 0.005f * t }, cap = true), 0x14141B, Mat.FEATHER)
        return pb.data
    }

    // ---------------------------------------------------------------------------------------------- animation
    // scratch matrices (draw runs on the GL thread only): a small ring so nothing is allocated per frame
    private val pool = Array(8) { FloatArray(16) }
    private var poolI = 0
    private fun copy(m: FloatArray): FloatArray { val d = pool[poolI]; poolI = (poolI + 1) and 7; System.arraycopy(m, 0, d, 0, 16); return d }
    private val WING_SIDES = floatArrayOf(1f, -1f)
    // glow-sprite positions of the two eyes in head space (constant)
    private val eyeP = FloatArray(6).also { e -> for (i in 0..1) { val sz = if (i == 0) -1f else 1f; val r = headShape(eyeDir[0], eyeDir[1], sz * eyeDir[2]); e[i * 3] = headC[0] + eyeDir[0] * r * 0.148f * 1.12f; e[i * 3 + 1] = headC[1] + eyeDir[1] * r * 0.100f; e[i * 3 + 2] = headC[2] + sz * eyeDir[2] * r * 0.092f * 1.12f } }

    override fun draw(sc: Scene3D, mm: FloatArray, t: Float, ex: Float, dim: Float) {
        val rim = 0xC83040

        // occasional pulses: head twitches and wing shuffles (smooth gates)
        val twitch = sstep(0.90f, 0.985f, sin(t * 1.9f) * sin(t * 0.71f + 0.5f))
        val twitch2 = sstep(0.93f, 0.99f, sin(t * 1.3f + 2f) * sin(t * 0.57f))
        val shuffle = sstep(0.82f, 0.96f, sin(t * 0.83f) * sin(t * 0.37f + 1f))
        val caw = abs(sin(t * 10f)).pow(0.8f)

        // ---- the whole bird turns from three-quarter view to face the viewer when excited
        val root = copy(mm)
        Mat4.translate(root, -0.10f, 0f, 0f)
        Mat4.scale(root, 1.22f, 1.22f, 1.22f)            // the bird is compact: fill the reel cell like the others
        Mat4.rotate(root, -52f * (1f - ex) - 72f * ex + 2f * sin(t * 0.4f), 0f, 1f, 0f)
        val bodyM = copy(root)
        Mat4.translate(bodyM, feet[0], feet[1], 0f)
        Mat4.translate(bodyM, 0f, ex * 0.03f * abs(sin(t * 5f)) + 0.006f * sin(t * 1.6f), 0f)
        Mat4.rotate(bodyM, 1.5f * sin(t * 0.9f) + ex * (9f + 3f * sin(t * 5f)), 0f, 0f, 1f)
        Mat4.translate(bodyM, -feet[0], -feet[1], 0f)

        // ---- head: tilts and twitches, snaps to the viewer and throws back when excited
        val yawH = (-46f + 20f * sin(t * 0.37f) + 22f * twitch) * (1f - ex) + ex * 6f * sin(t * 5f)
        val pitchH = 4f * sin(t * 0.8f) + 10f * twitch2 + ex * (24f + 6f * sin(t * 10f))
        val rollH = (15f * sin(t * 0.55f + 1f) + 12f * twitch) * (1f - ex)
        val headM = copy(bodyM)
        Mat4.translate(headM, neckPivot[0], neckPivot[1], neckPivot[2])
        Mat4.rotate(headM, yawH, 0f, 1f, 0f); Mat4.rotate(headM, pitchH, 0f, 0f, 1f); Mat4.rotate(headM, rollH, 1f, 0f, 0f)
        Mat4.translate(headM, -neckPivot[0], -neckPivot[1], -neckPivot[2])
        val open = 2f + 12f * twitch2 * (1f - ex) + ex * (8f + 28f * caw)
        val jawM = copy(headM)
        Mat4.translate(jawM, jawHinge[0], jawHinge[1], jawHinge[2]); Mat4.rotate(jawM, -open, 0f, 0f, 1f)
        val blink = if (((t * 0.27f) % 1f) < 0.03f && ex < 0.2f) 0f else 1f
        val eyeK = (1.1f + 0.2f * sin(t * 3.1f) + ex * 1.2f) * blink

        // ---- red eye-light on the beak and feathers; a cold fill so the sheen reads (before the first drawV)
        sc.beginLocalLights()
        sc.pointLightAt(headM, 0.50f, 0.84f, 0.16f, 0.85f, 0xFF2A28, (0.25f + 0.55f * ex) * dim)
        sc.pointLightAt(root, -0.5f, 1.2f, 0.7f, 2.4f, 0x8A84E0, 0.30f * dim)

        sc.drawV(branch, root, emis = 0.6f + 0.3f * sin(t * 2.3f), dim = dim, rim = 0x25080C, spec = 0.2f)
        sc.drawV(body, bodyM, dim = dim, rim = rim, spec = 0.5f)
        sc.drawV(head, headM, emis = eyeK * 1.4f, dim = dim, rim = rim, spec = 0.6f)
        sc.drawV(jaw, jawM, emis = 0.5f + 0.7f * ex, dim = dim, rim = rim, spec = 0.6f)

        // ---- wings: folded against the flanks; spread, raised and beating when excited
        val spread = (0.28f + 0.30f * shuffle * (1f - ex) + 0.72f * ex).coerceAtMost(1f)
        val k = ((spread - 0.28f) / 0.72f).coerceIn(0f, 1f)
        val flap = ex * (22f * sin(t * 9f) + 8f * sin(t * 23f))
        for (side in WING_SIDES) {
            val wm = copy(bodyM)
            Mat4.translate(wm, shoulder[0], shoulder[1], side * shoulder[2])
            Mat4.rotate(wm, side * (84f * (1f - k) + (-24f) * k + 0.4f * flap + 3f * shuffle * sin(t * 30f)), 1f, 0f, 0f)
            Mat4.rotate(wm, -74f * k + 0.7f * flap, 0f, 0f, 1f)
            Mat4.scale(wm, 1f + 0.45f * k, 1f + 0.2f * k, spread * (1f + 0.25f * k))
            sc.drawV(if (side > 0f) wingR else wingL, wm, dim = dim, rim = rim, spec = 0.6f)
        }

        // ---- glow sprites: a blood-red halo behind, the eyes, the skull's ember sockets, and embers / ash shaken loose
        sc.glowAt(root, 0.1f, 0.55f, -0.03f, 1.9f + 0.4f * ex, 0xC01020, (0.15f + 0.07f * sin(t * 1.3f) + 0.16f * ex) * dim, 0f)
        val eyeA = (0.8f + 0.3f * sin(t * 3.1f) + 0.6f * ex) * blink * dim
        for (i in 0..1) {
            sc.glowAt(headM, eyeP[i * 3], eyeP[i * 3 + 1], eyeP[i * 3 + 2], 0.045f + 0.03f * ex, 0xFF2A30, 0.9f * eyeA, 0.8f)
            sc.glowAt(headM, eyeP[i * 3], eyeP[i * 3 + 1], eyeP[i * 3 + 2], 0.15f + 0.08f * ex, 0xFF1020, 0.30f * eyeA, 0f)
        }
        sc.glowAt(root, 0.02f, 0.26f, 0.42f, 0.16f, 0xC01828, (0.20f + 0.10f * sin(t * 2.1f)) * dim, 0.3f)
        if (ex > 0.04f) for (i in 0 until 8) {
            val tau = (t * (0.35f + 0.05f * (i % 3)) + i * 0.125f) % 1f
            val a = ex * sin(PI.toFloat() * tau) * dim
            if (a < 0.03f) continue
            val sx = ((i * 37) % 11 - 5) * 0.12f + 0.08f * sin(t * 1.7f + i)
            val sy = 1.0f - 0.9f * tau
            val sz = ((i * 53) % 7 - 3) * 0.07f
            sc.glowAt(root, sx, sy, sz, 0.035f + 0.03f * sin(t * 9f + i * 2f).let { abs(it) }, if (i % 3 == 0) 0xFF3A28 else 0xB01828, 0.9f * a, 0.8f)
        }
    }
}

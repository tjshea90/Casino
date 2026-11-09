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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Plague Doctor: a tall, gaunt figure in a long waxed-leather coat and shoulder mantle, a wide-brimmed hat and a cracked,
 * stitched leather bird-beak mask with a hinged mandible and two toxic-green lenses glowing from inside. A bandolier of glowing
 * vials crosses the chest, the right gloved hand leans on a hooked cane topped with a tiny skull, the left holds a swinging iron
 * cage lantern with a green corpse-flame.
 *
 * Budget: 5 mesh draws (body incl. left arm, head, beak mandible, right arm + cane, lantern incl. flame) + 4 glow sprites,
 * about 4,000 triangles. Detail comes from baked vertex shading and the shader materials, not from polygon count.
 */
class DoctorModel : CharacterModel {
    private val neck = floatArrayOf(0f, 0.955f, 0.02f)
    private val shoulderR = floatArrayOf(0.170f, 0.895f, 0f)
    private val shoulderL = floatArrayOf(-0.170f, 0.895f, 0f)
    private val handR = floatArrayOf(0.255f, 0.575f, 0.235f)       // fist around the cane
    private val handL = floatArrayOf(-0.280f, 0.700f, 0.270f)      // fist around the lantern ring (pendulum pivot)
    private val hinge = floatArrayOf(0f, 0.990f, 0.080f)           // mandible hinge
    private val lensP = floatArrayOf(0.048f, 1.040f, 0.095f)       // lens cup base (x, y, z); lenses toe out by lensYaw
    private val lensYaw = 9f

    private val body: Mesh
    private val head: Mesh
    private val beakLow: Mesh
    private val armR: Mesh
    private val lantern: Mesh

    val drawCalls = 5
    val glowSprites = 4          // + 1 breath puff while excited
    val triangleCount: Int get() = (body.indexCount + head.indexCount + beakLow.indexCount + armR.indexCount + lantern.indexCount) / 3

    // ---------------------------------------------------------------------------------------------- shared shapes
    private val coatProf = listOf(0.066f to 0.990f, 0.085f to 0.962f, 0.150f to 0.918f, 0.172f to 0.850f, 0.150f to 0.775f,
        0.134f to 0.690f, 0.150f to 0.590f, 0.190f to 0.420f, 0.240f to 0.230f, 0.280f to 0.075f)

    private fun coatZr(t: Float) = 0.74f + 0.20f * t
    private fun coatRad(ang: Float, t: Float): Float {
        var f = KitA.ell(ang, coatZr(t))
        f *= 1f + (0.02f + 0.05f * t) * sin(5f * ang + 3f * t)                         // long folds
        f += 0.16f * sstep(0.5f, 0f, t) * max(0f, -sin(ang))                            // stooped back
        return f
    }

    /** Centre line and radius of the upper beak (shared by the beak, its cracks and its stitches), and of the mandible. */
    private fun beakC(t: Float) = floatArrayOf(0f, 0.995f - 0.02f * t - 0.085f * t * t, 0.095f + 0.31f * t)
    private fun beakR(t: Float) = 0.050f * (1f - t).pow(0.85f) + 0.0045f
    private fun lowR(t: Float) = 0.031f * (1f - t).pow(0.9f) + 0.003f
    private fun lowC(t: Float): FloatArray {
        val s = t * 0.93f
        val u = beakC(s)
        return floatArrayOf(0f, u[1] - (beakR(s) + lowR(t)) * 0.72f, u[2])
    }

    /** Point on the upper beak (x squeezed 0.88) at length t and angle a (degrees; 0 = right side, 90 = top). */
    private fun beakPt(t: Float, aDeg: Float, off: Float): FloatArray {
        val c = beakC(t); val c1 = beakC((t - 0.01f).coerceAtLeast(0f)); val c2 = beakC((t + 0.01f).coerceAtMost(1f))
        val dy = c2[1] - c1[1]; val dz = c2[2] - c1[2]; val l = sqrt(dy * dy + dz * dz)
        val uy = dz / l; val uz = -dy / l
        val a = Math.toRadians(aDeg.toDouble()).toFloat(); val r = beakR(t) + off
        return floatArrayOf(0.88f * r * cos(a), c[1] + r * sin(a) * uy, c[2] + r * sin(a) * uz)
    }

    // ---------------------------------------------------------------------------------------------- body
    private fun buildBody(): Mesh {
        val pb = PartBuilder()
        val rows = coatProf.size
        val halfPi = (PI / 2).toFloat()
        // long waxed-leather coat: scalloped hem, grimy toward the ground, darker on downward-facing folds
        val coat = Sculpt.shell(coatProf, 28, radial = { ang, _, t -> coatRad(ang, t) },
            dy = { ang, k, _ -> if (k >= rows - 2) -((if (k == rows - 1) 1f else 0.3f) * (0.004f + 0.040f * abs(sin(3f * ang + 0.7f)).pow(1.4f) + 0.024f * abs(sin(7f * ang)))) else 0f })
        KitA.shade(coat) { _, y, _, _, ny, _ -> (0.72f + 0.28f * sstep(0.02f, 0.75f, y)) * (0.88f + 0.12f * (ny * 0.5f + 0.5f)) }
        pb.add(coat, 0x1D1915, Mat.MATTE)
        // front seam + a double row of tarnished buttons
        val pk = ArrayList<FloatArray>()
        for (k in 3 until rows) { val t = k.toFloat() / (rows - 1); pk += floatArrayOf(0f, coatProf[k].second, coatProf[k].first * coatRad(halfPi, t) + 0.0025f) }
        pb.add(Sculpt.tube(pk.size - 1, 3, KitA.poly(pk), { 0.0042f }), 0x0A0807, Mat.MATTE)
        for (k in intArrayOf(3, 4, 7, 8)) for (sx in SGN) {
            val t = k.toFloat() / (rows - 1)
            pb.add(Sculpt.box(0.0075f, 0.0075f, 0.0045f), 0x8E7C52, Mat.METAL, x = sx * 0.034f, y = coatProf[k].second, z = coatProf[k].first * coatRad(halfPi, t) + 0.003f)
        }
        // shoulder mantle, longer at the back, with a V-shaped front
        val mProf = listOf(0.088f to 0.976f, 0.140f to 0.945f, 0.190f to 0.900f, 0.218f to 0.835f, 0.214f to 0.775f)
        val mRows = mProf.size
        val mantle = Sculpt.shell(mProf, 24,
            radial = { ang, _, t -> KitA.ell(ang, 0.84f) * (1f + 0.04f * sin(5f * ang + 2f * t)) + 0.10f * sstep(0.6f, 0f, t) * max(0f, -sin(ang)) },
            dy = { ang, k, t ->
                val rag = if (k >= mRows - 2) -((if (k == mRows - 1) 1f else 0.3f) * (0.004f + 0.032f * abs(sin(3f * ang + 0.4f)).pow(1.3f))) else 0f
                rag + 0.075f * sstep(0.3f, 1f, t) * sstep(0f, 1f, sin(ang))
            })
        KitA.shade(mantle) { _, y, _, _, _, _ -> 0.65f + 0.35f * sstep(0.77f, 0.95f, y) }
        pb.add(mantle, 0x2A221D, Mat.CLOTH, ao = 0.9f)
        // belt, buckle and two pouches
        val beltY = 0.690f
        pb.add(Sculpt.tube(18, 4, { t -> val a = KitA.TAU * t; val r = 0.140f * KitA.ell(a, 0.88f); floatArrayOf(r * cos(a), beltY, r * sin(a)) }, { 0.0125f }), 0x45331F, Mat.MATTE)
        pb.add(Sculpt.box(0.02f, 0.017f, 0.006f), 0x9A8A60, Mat.METAL, y = beltY, z = 0.140f * 0.88f + 0.012f)
        for (sx in SGN) {
            pb.add(Sculpt.box(0.030f, 0.040f, 0.022f), 0x2B2018, Mat.LEATHER, x = sx * 0.104f, y = 0.636f, z = 0.088f, rz = sx * -8f, ao = 0.9f)
            pb.add(Sculpt.box(0.028f, 0.012f, 0.0235f), 0x3A2A1D, Mat.LEATHER, x = sx * 0.104f, y = 0.670f, z = 0.088f, rz = sx * -8f)
            pb.add(Sculpt.box(0.034f, 0.042f, 0.0035f), 0x15110E, Mat.MATTE, x = sx * 0.088f, y = 0.440f, z = 0.139f, rx = -7f, rz = sx * 4f)   // skirt pocket flap
        }
        // bandolier across the chest with four glowing vials
        fun frontZ(x: Float, y: Float): Float {
            var k = 0
            while (k < rows - 2 && coatProf[k + 1].second > y) k++
            val a = coatProf[k]; val b = coatProf[k + 1]
            val u = ((a.second - y) / (a.second - b.second)).coerceIn(0f, 1f)
            val r = a.first + (b.first - a.first) * u
            return coatZr((k + u) / (rows - 1)) * sqrt(max(r * r - x * x, 0f))
        }
        val sp = (0..5).map { i -> val u = i / 5f; val x = 0.120f - 0.270f * u; val y = 0.835f - 0.250f * u; floatArrayOf(x, y, frontZ(x, y) + 0.013f) }
        pb.add(Sculpt.tube(5, 4, KitA.poly(sp), { 0.0115f }), 0x45331F, Mat.MATTE)
        val vialCols = intArrayOf(0x3CE03A, 0xB3141F, 0x9ED020, 0x3CE03A)
        for ((i, uu) in floatArrayOf(0.18f, 0.42f, 0.66f, 0.90f).withIndex()) {
            val x = 0.120f - 0.270f * uu; val y = 0.835f - 0.250f * uu; val z = frontZ(x, y) + 0.022f
            val tilt = (i - 1.5f) * 3f
            pb.add(Sculpt.tube(3, 5, { t -> floatArrayOf(0f, -0.068f * t, 0f) },
                { t -> 0.0125f * (if (t < 0.7f) 1f else sqrt(max(0f, 1f - ((t - 0.7f) / 0.3f).pow(2)))) + 0.0005f }, cap = true), vialCols[i], Mat.GLASS, x = x, y = y - 0.008f, z = z, rz = tilt)
            pb.add(Sculpt.tube(1, 4, { t -> floatArrayOf(0f, 0.014f * t, 0f) }, { 0.0070f }, cap = true), 0x6B4A2A, Mat.WOOD, x = x, y = y - 0.002f, z = z, rz = tilt)
        }
        // pointed boots peeking out of the hem
        for (sx in SGN) pb.add(Sculpt.ellipsoid(0.042f, 0.034f, 0.115f, 4, 7), 0x1C1612, Mat.MATTE, x = sx * 0.070f, y = 0.034f, z = 0.232f, ao = 0.9f)
        // left arm: sleeve + the fist that holds the lantern ring
        pb.add(sleeve(shoulderL, floatArrayOf(-0.265f, 0.735f, 0.070f), floatArrayOf(-0.278f, 0.712f, 0.236f), 0.050f, 0.042f, 0.055f), 0x241D18, Mat.MATTE, ao = 0.95f)
        KitA.addRaw(pb, fist(0.004f, 0.0135f, 0.03f, 1), x = handL[0], y = handL[1], z = handL[2], rz = 90f)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- head
    private fun lens(): MeshData {
        val pb = PartBuilder()
        pb.add(Sculpt.tube(1, 10, { t -> floatArrayOf(0f, 0f, 0.046f * t) }, { 0.0325f }, cap = true), 0x2A211A, Mat.MATTE, ao = 0.9f)
        pb.add(Sculpt.tube(10, 4, { t -> val a = KitA.TAU * t; floatArrayOf(0.0345f * cos(a), 0.0345f * sin(a), 0.047f) }, { 0.0060f }), 0xA28E5E, Mat.METAL)
        pb.add(Sculpt.ellipsoid(0.0295f, 0.0295f, 0.0125f, 3, 10), 0x2FC832, Mat.GLASS, z = 0.040f)
        pb.add(Sculpt.ellipsoid(0.017f, 0.017f, 0.0045f, 2, 6), 0x5ACB3A, Mat.EMISSIVE, z = 0.0518f)
        return pb.data
    }

    private fun buildHead(): Mesh {
        val pb = PartBuilder()
        // cloth hood over the skull and neck, leather mask dome with a heavy brow ridge
        val hoodProf = listOf(0.050f to 1.135f, 0.085f to 1.080f, 0.094f to 1.010f, 0.115f to 0.945f)
        pb.add(Sculpt.shell(hoodProf, 14), 0x100D0D, Mat.CLOTH, z = 0.02f, ao = 0.9f)
        val mask = Sculpt.blob(7, 12, 0.074f, 0.088f, 0.092f) { _, dy, dz -> 1f + 0.10f * gauss((dy - 0.28f) * (dy - 0.28f), 0.16f) * sstep(0f, 0.55f, dz) - 0.05f * sstep(-0.3f, -1f, dy) }
        KitA.shade(mask) { _, y, _, _, ny, _ -> (0.7f + 0.3f * sstep(-0.9f, 0.4f, y / 0.088f)) * (0.85f + 0.15f * (ny * 0.5f + 0.5f)) }
        pb.add(mask, 0x4A392C, Mat.MATTE, y = 1.035f, z = 0.035f)
        val lensMesh = lens()
        for (sx in SGN) KitA.addRaw(pb, lensMesh, x = sx * lensP[0], y = lensP[1], z = lensP[2], ry = sx * lensYaw)
        // the beak: aged leather, darker where it joins the mask and on its underside
        val beak = Sculpt.tube(10, 8, { t -> beakC(t) }, { t -> beakR(t) }, cap = true)
        KitA.shade(beak) { _, _, z, _, ny, _ -> (0.72f + 0.28f * sstep(0.10f, 0.22f, z)) * (0.8f + 0.2f * (ny * 0.5f + 0.5f)) }
        pb.add(beak, 0x7C5C3C, Mat.MATTE, sx = 0.88f, sy = 1f, sz = 1f)
        // cracks (dark), crossed by pale stitches; the top seam is stitched all the way
        val cracks = listOf(
            listOf(0.12f to 40f, 0.24f to 50f, 0.36f to 44f, 0.50f to 58f),
            listOf(0.22f to 140f, 0.34f to 130f, 0.48f to 138f),
            listOf(0.05f to 90f, 0.30f to 90f, 0.55f to 90f, 0.80f to 90f))
        for ((ci, cr) in cracks.withIndex()) {
            val pts = cr.map { (t, a) -> beakPt(t, a, 0.0012f) }
            pb.add(Sculpt.tube(pts.size - 1, 3, KitA.spline(pts), { 0.0027f }), 0x0B0807, Mat.MATTE)
            val n = if (ci == 2) 9 else 4
            for (i in 0 until n) {
                val f = (i + 0.5f) / n * (cr.size - 1)
                val k = f.toInt().coerceAtMost(cr.size - 2); val u = f - k
                val t = cr[k].first + (cr[k + 1].first - cr[k].first) * u; val a = cr[k].second + (cr[k + 1].second - cr[k].second) * u
                val pA = beakPt(t, a - 16f, 0.0028f); val pB = beakPt(t, a + 16f, 0.0028f)
                pb.add(Sculpt.tube(1, 3, { s -> floatArrayOf(pA[0] + (pB[0] - pA[0]) * s, pA[1] + (pB[1] - pA[1]) * s, pA[2] + (pB[2] - pA[2]) * s) }, { 0.0021f }), 0xC9B98F, Mat.MATTE)
            }
        }
        // wide-brimmed hat, built in hat space (brim plane y = 0) and tilted back a little so the lenses stay visible from below
        val hat = PartBuilder()
        val brimProf = listOf(0.095f to 0.006f, 0.160f to -0.002f, 0.225f to -0.012f, 0.262f to -0.024f)
        val brim = Sculpt.shell(brimProf, 24, radial = { ang, _, t -> KitA.ell(ang, 0.94f) * (1f + 0.025f * sin(3f * ang + 1f) * t) },
            dy = { ang, _, t -> 0.014f * sin(3f * ang + 1f) * t * t })
        KitA.shade(brim) { x, _, z, _, _, _ -> 0.85f + 0.30f * sstep(0.19f, 0.26f, sqrt(x * x + z * z)) }      // scuffed, lighter edge
        hat.add(brim, 0x1E1814, Mat.MATTE)
        val crownProf = listOf(0.098f to 0.0f, 0.098f to 0.050f, 0.092f to 0.120f, 0.078f to 0.168f, 0.040f to 0.186f, 0.0f to 0.189f)
        val crown = Sculpt.shell(crownProf, 20, radial = { ang, _, t -> 1f - 0.05f * sstep(0.4f, 1f, t) * (0.5f - 0.5f * cos(2f * ang)) + 0.015f * sin(3f * ang) })
        KitA.shade(crown) { _, y, _, _, _, _ -> 0.75f + 0.25f * sstep(0.0f, 0.19f, y) }
        hat.add(crown, 0x231C17, Mat.MATTE)
        hat.add(Sculpt.shell(listOf(0.1012f to 0.040f, 0.1012f to 0.012f), 20), 0x4A3826, Mat.MATTE)         // hat band
        hat.add(Sculpt.box(0.022f, 0.016f, 0.005f), 0xA39468, Mat.METAL, y = 0.026f, z = 0.1075f)
        KitA.addRaw(pb, hat.data, y = 1.115f, rx = -2f)
        return pb.build()
    }

    private fun buildBeakLow(): Mesh {
        val pb = PartBuilder()
        pb.add(Sculpt.tube(8, 6, { t -> lowC(t) }, { t -> lowR(t) }, cap = true), 0x6E5236, Mat.MATTE, sx = 0.9f, sy = 1f, sz = 1f)
        // needle teeth along the inside edge, and a green glow leaking from the throat
        for (i in 0 until 4) {
            val t = 0.20f + i * 0.17f; val c = lowC(t)
            pb.add(Sculpt.spike(0.0042f * (1f - 0.5f * t), 0.018f, 4), 0xCFC4A6, Mat.BONE, y = c[1] + lowR(t) * 0.78f, z = c[2], ao = 0.95f)
        }
        pb.add(Sculpt.tube(5, 4, { t -> val c = lowC(0.04f + 0.78f * t); floatArrayOf(0f, c[1] + lowR(0.04f + 0.78f * t) * 0.62f, c[2]) },
            { t -> 0.5f * lowR(0.04f + 0.78f * t) }), 0x4CFF3C, Mat.EMISSIVE)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- arms, cane, lantern
    private fun sleeve(a: FloatArray, b: FloatArray, c: FloatArray, r0: Float, r1: Float, r2: Float): MeshData =
        Sculpt.tube(8, 6, KitA.spline(listOf(a, b, c)), { t -> if (t < 0.5f) r0 + (r1 - r0) * (t * 2f) else r1 + (r2 - r1) * sstep(0.5f, 1f, t) }, cap = true)

    /** A gloved fist wrapped around a vertical shaft of radius [shaftR] at the origin: finger [bands], a palm block behind, a thumb. */
    private fun fist(shaftR: Float, bandR: Float, gap: Float, bands: Int): MeshData {
        val pb = PartBuilder()
        val rr = shaftR + bandR * 0.8f
        for (i in 0 until bands) {
            val y = (i - (bands - 1) / 2f) * gap
            pb.add(Sculpt.tube(6, 4, { t -> floatArrayOf(rr * cos(KitA.TAU * t), y, 0.9f * rr * sin(KitA.TAU * t)) }, { bandR }), 0x231B16, Mat.LEATHER, ao = 0.95f)
        }
        pb.add(Sculpt.ellipsoid(0.031f, 0.040f, 0.026f, 4, 6), 0x1E1713, Mat.LEATHER, z = -0.018f, ao = 0.95f)
        pb.add(Sculpt.tube(2, 4, { t -> floatArrayOf(-0.012f + 0.010f * t, -0.012f + 0.052f * t, 0.016f + 0.005f * t) }, { t -> 0.0085f - 0.002f * t }, cap = true), 0x231B16, Mat.LEATHER)
        return pb.data
    }

    /** Right arm and cane as ONE rigid piece (the whole arm lifts when excited): sleeve, fist, cane shaft + crook, iron ferrule, skull pommel. */
    private fun buildArmR(): Mesh {
        val pb = PartBuilder()
        pb.add(sleeve(shoulderR, floatArrayOf(0.235f, 0.715f, 0.045f), floatArrayOf(0.252f, 0.612f, 0.205f), 0.050f, 0.042f, 0.055f), 0x241D18, Mat.MATTE, ao = 0.95f)
        val cx = handR[0]; val cz = handR[2]
        val pts = ArrayList<FloatArray>()
        for (i in 0..6) pts += floatArrayOf(cx, 0.045f + i * 0.158f, cz)
        for (i in 1..6) { val a = PI.toFloat() * 1.12f * i / 6f; pts += floatArrayOf(cx - 0.048f + 0.048f * cos(a), 0.992f + 0.052f * sin(a), cz) }
        pb.add(Sculpt.tube(pts.size - 1, 6, KitA.poly(pts), { t -> 0.0108f - 0.0012f * t }, cap = true), 0x33241A, Mat.WOOD)
        pb.add(Sculpt.tube(2, 6, { t -> floatArrayOf(cx, 0.050f * t, cz) }, { t -> 0.0125f - 0.006f * t * t * t }, cap = true), 0x6E6E78, Mat.METAL)
        val end = pts.last()
        pb.add(Bones.skull(6, 9), 0xBDB39C, Mat.BONE, x = end[0] - 0.004f, y = end[1] - 0.020f, z = cz, sx = 0.026f, ao = 0.9f)
        KitA.addRaw(pb, fist(0.0105f, 0.0135f, 0.034f, 2), x = cx, y = handR[1], z = cz)
        return pb.build()
    }

    /** Pendulum frame: origin = the fist holding the ring; +Y up. Ring, chain, roof, square iron cage, candle stub and the flame. */
    private fun buildLantern(): Mesh {
        val pb = PartBuilder()
        val iron = 0x7C7C86
        pb.add(Sculpt.tube(8, 4, { t -> floatArrayOf(0.030f * cos(KitA.TAU * t), -0.030f + 0.030f * sin(KitA.TAU * t), 0f) }, { 0.0042f }), iron, Mat.METAL)
        for (i in 0 until 2) pb.add(KitA.link(0.030f, 0.016f, 0.0035f), iron, Mat.METAL, y = -0.074f - i * 0.026f, ry = if (i % 2 == 0) 0f else 90f)
        pb.add(Sculpt.tube(2, 4, { t -> floatArrayOf(0f, -0.130f - 0.055f * t, 0f) }, { t -> 0.068f * t }, cap = true), 0x55555F, Mat.METAL, ry = 45f)
        val h = 0.042f
        for (y in floatArrayOf(-0.190f, -0.300f)) {
            val sq = listOf(floatArrayOf(h, y, h), floatArrayOf(-h, y, h), floatArrayOf(-h, y, -h), floatArrayOf(h, y, -h), floatArrayOf(h, y, h))
            pb.add(Sculpt.tube(4, 3, KitA.poly(sq), { 0.0042f }), iron, Mat.METAL)
        }
        for (sx in SGN) for (sz in SGN) pb.add(Sculpt.tube(1, 3, { t -> floatArrayOf(sx * h, -0.190f - 0.110f * t, sz * h) }, { 0.0038f }), iron, Mat.METAL)
        pb.add(Sculpt.box(0.052f, 0.006f, 0.052f), 0x55555F, Mat.METAL, y = -0.306f)
        pb.add(Sculpt.tube(1, 5, { t -> floatArrayOf(0f, -0.300f + 0.040f * t, 0f) }, { 0.0125f }, cap = true), 0xD8CFA8, Mat.WAX)
        // the corpse-flame (emissive; brightness flickers through the draw call's emissive factor)
        pb.add(Sculpt.tube(5, 6, { t -> floatArrayOf(0.006f * t * t, -0.262f + 0.085f * t, 0f) },
            { t -> 0.020f * sin(PI.toFloat() * t.pow(0.65f)) * (1f - 0.25f * t) + 0.0005f }, cap = true), 0x7DFF4A, Mat.EMISSIVE)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- animation
    private val root = FloatArray(16); private val hm = FloatArray(16); private val jm = FloatArray(16)
    private val aR = FloatArray(16); private val lm = FloatArray(16)

    // meshes are built last: every property they read (coat profile, pivots) is initialised above
    init {
        body = buildBody(); head = buildHead(); beakLow = buildBeakLow()
        armR = buildArmR(); lantern = buildLantern()
    }

    override fun draw(sc: Scene3D, mm: FloatArray, t: Float, ex: Float, dim: Float) {
        val fl = KitA.flicker(t, 1.3f)
        // ---- poses first: the two local lights below need the lantern and head positions before the first drawV
        KitA.set(root, mm)
        Mat4.rotate(root, sin(t * 0.52f) * 1.1f + ex * sin(t * 6.3f) * 1.4f, 0f, 0f, 1f)          // slow sway, leans in when excited
        Mat4.rotate(root, ex * 5f, 1f, 0f, 0f)
        // head: long stillness, then sudden bird-like snaps; stares into the camera when excited
        val seg = t / 3.4f; val k = floor(seg); val u = sstep(0f, 0.15f, seg - k)
        val look = (KitA.hash(k + 0.5f) * 2f - 1f) * (1f - u) + (KitA.hash(k + 1.5f) * 2f - 1f) * u
        val yaw = look * 26f * (1f - ex) + ex * sin(t * 8f) * 2.5f
        val roll = (sin(t * 0.43f) * 6f + look * 5f) * (1f - 0.6f * ex)
        val pitch = 2f + sin(t * 0.8f) * 1.5f + ex * 9f
        KitA.set(hm, root)
        Mat4.translate(hm, neck[0], neck[1], neck[2]); Mat4.rotate(hm, yaw, 0f, 1f, 0f); Mat4.rotate(hm, pitch, 1f, 0f, 0f); Mat4.rotate(hm, roll, 0f, 0f, 1f)
        Mat4.translate(hm, -neck[0], -neck[1], -neck[2])
        val eye = 1.5f + 0.2f * sin(t * 1.9f) + ex * (1.3f + 0.25f * sin(t * 17f))
        val open = 1.2f + 0.8f * sin(t * 0.9f) + ex * 24f * sstep(-0.35f, 0.35f, sin(t * 13f))
        KitA.pivot(jm, hm, hinge[0], hinge[1], hinge[2], open, 0f, 0f)
        // swinging lantern (the left arm is part of the body mesh)
        val swz = sin(t * 1.6f) * (3.5f + 11f * ex); val swx = sin(t * 1.15f + 1.2f) * (2.5f + 7f * ex)
        KitA.set(lm, root); Mat4.translate(lm, handL[0], handL[1], handL[2]); Mat4.rotate(lm, swz, 0f, 0f, 1f); Mat4.rotate(lm, swx, 1f, 0f, 0f)
        // right arm + cane, one rigid piece: the arm lifts and the cane is brandished forward when excited
        KitA.pivot(aR, root, shoulderR[0], shoulderR[1], shoulderR[2], -ex * 30f + sin(t * 0.8f) * 1f + ex * 3f * sin(t * 9f), 0f, -ex * 8f)

        // ---- two local lights (budget: 2 per character): the corpse-flame lights the coat, the lenses wash the mantle and beak green
        sc.beginLocalLights()
        sc.pointLightAt(lm, 0f, -0.23f, 0.05f, 1.25f, 0x4CFF3A, (0.55f + 0.35f * fl + 0.9f * ex) * dim)
        sc.pointLightAt(hm, 0f, lensP[1] - 0.03f, 0.24f, 0.55f, 0x35FF3A, (0.16f + 0.10f * ex) * eye * dim)

        // ---- draws
        sc.drawV(body, root, emis = 0.5f, dim = dim, rim = 0x0A1A10, spec = 0.5f, shine = 34f, wave = 1.2f + ex)
        sc.drawV(head, hm, emis = eye, dim = dim, rim = 0x1B4A24, spec = 0.28f, shine = 18f)
        sc.drawV(beakLow, jm, emis = 1f + ex * 1.2f, dim = dim, rim = 0x1B4A24, spec = 0.28f, shine = 18f)
        sc.drawV(lantern, lm, emis = 1.2f + 0.7f * fl + 1.0f * ex, dim = dim, rim = 0x2E8A2E, spec = 0.5f, shine = 34f)
        sc.drawV(armR, aR, dim = dim, rim = 0x0A1A10, spec = 0.5f, shine = 34f)
        // glow sprites: one per lens (halo with a hot core), the flame core and its wide halo
        for (sx in SGN) sc.glowAt(hm, sx * (lensP[0] + 0.012f), lensP[1], lensP[2] + 0.075f, 0.10f * eye + 0.03f, 0x35FF3A, 0.7f * dim, 0.5f)
        val fh = 0.9f + 0.45f * fl + 0.6f * ex
        if (ex > 0.05f) { val ph = KitA.frac(t * 0.9f); val tip = beakC(1f); sc.glowAt(hm, tip[0], tip[1] - 0.01f + 0.06f * ph, tip[2] + 0.03f + 0.10f * ph, 0.10f + 0.20f * ph, 0x7CFF6A, 0.30f * ex * sin(PI.toFloat() * ph) * dim, 0f) }
        sc.glowAt(lm, 0f, -0.228f, 0.012f, 0.16f * fh + 0.05f * ex, 0x5CFF3A, 0.85f * dim, 0.9f)
        sc.glowAt(lm, 0f, -0.226f, 0.012f, 0.70f * fh + 0.35f * ex, 0x35FF3A, (0.20f + 0.10f * fl + 0.2f * ex) * dim, 0f)
    }

    private companion object { val SGN = floatArrayOf(-1f, 1f) }
}

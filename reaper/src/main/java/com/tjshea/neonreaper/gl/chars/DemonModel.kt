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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Demon: a hulking horned fiend rising out of hellfire. Dark crimson skin with glowing ember veins (Mat.FLESH), ringed charcoal
 * horns with white-hot tips, burning eyes in a heavy snarling brow, a hinged fanged jaw with lower tusks, spiked pauldrons, clawed
 * arms and tattered bat wings. Idle: breathing, ember flicker, slow head turn. Excited: roars (head back, jaw wide, eyes and horn
 * tips flare), arms rise with spread claws, wings open, the whole body shakes.
 *
 * Budget: 6 mesh draws (body, head, jaw, left arm, right arm, wings) + 4 glow sprites, about 4,000 triangles.
 */
class DemonModel : CharacterModel {
    private val neck = floatArrayOf(0f, 0.765f, 0.03f)
    private val hc = floatArrayOf(0f, 0.915f, 0.06f)             // head centre in DESIGN space (the head and jaw are modelled around it)
    private val hn = floatArrayOf(0f, 0.862f, 0.080f)            // where the head really sits: lowered into the shoulders and enlarged by HS
    private val HS = 1.22f
    private val jawHinge = floatArrayOf(0f, 0.862f + (0.872f - 0.915f) * 1.22f, 0.080f + (0.018f - 0.06f) * 1.22f)
    private val shoulderR = floatArrayOf(0.30f, 0.62f, 0f)
    private val shoulderL = floatArrayOf(-0.30f, 0.62f, 0f)

    private val body: Mesh
    private val head: Mesh
    private val jaw: Mesh
    private val armR: Mesh
    private val armL: Mesh
    private val wings: Mesh

    val drawCalls = 6
    val glowSprites = 4
    val triangleCount: Int get() = (body.indexCount + head.indexCount + jaw.indexCount + armR.indexCount + armL.indexCount + wings.indexCount) / 3

    private fun sq(x: Float) = x * x
    /** design-space head point -> final character space */
    private fun dp(x: Float, y: Float, z: Float) = floatArrayOf(hn[0] + (x - hc[0]) * HS, hn[1] + (y - hc[1]) * HS, hn[2] + (z - hc[2]) * HS)
    private val half = (PI / 2).toFloat()
    // glow positions (head space), precomputed so the frame loop does not allocate
    private val eyeGlow = arrayOf(dp(-0.040f, 0.923f, 0.175f), dp(0.040f, 0.923f, 0.175f))
    private val mouthGlow = dp(0f, 0.832f, 0.15f)

    /** Glowing ember crack: a thin emissive tube along [pts]. */
    private fun vein(pb: PartBuilder, pts: List<FloatArray>, r: Float = 0.0042f) {
        pb.add(Sculpt.tube(pts.size - 1, 3, KitA.poly(pts), { r }), 0xFF2A14, Mat.EMISSIVE)
    }
    private fun h(x: Float, y: Float, z: Float) = floatArrayOf(hc[0] + x, hc[1] + y, hc[2] + z)

    /** A short pointed cone along +Y (20 triangles with 4 sides). */
    private fun spk(r: Float, len: Float, sides: Int = 4): MeshData = Sculpt.tube(2, sides, { t -> floatArrayOf(0f, len * t, 0f) }, { t -> r * (1f - t) }, cap = true)

    // ---------------------------------------------------------------------------------------------- body
    private val prof = listOf(0.115f to 0.800f, 0.130f to 0.740f, 0.215f to 0.695f, 0.295f to 0.640f, 0.288f to 0.585f, 0.272f to 0.530f,
        0.250f to 0.470f, 0.228f to 0.405f, 0.210f to 0.335f, 0.198f to 0.255f, 0.196f to 0.170f, 0.200f to 0.080f, 0.0f to 0.0f)

    private fun zr(t: Float) = 0.60f + 0.22f * t

    private fun torsoRad(ang: Float, t: Float): Float {
        var f = KitA.ell(ang, zr(t))
        val chest = gauss(sq(t - 0.42f), 0.12f)
        f *= 1f + 0.15f * chest * (gauss(sq(ang - (half - 0.58f)), 0.34f) + gauss(sq(ang - (half + 0.58f)), 0.34f))     // pecs
        f *= 1f + 0.09f * max(0f, -sin(ang)) * gauss(sq(t - 0.20f), 0.10f)                                           // hunched trapezius
        return f
    }

    /** z of the torso's front surface at (x, y) (used to lay the glowing cracks on it). */
    private fun torsoZ(x: Float, y: Float): Float {
        var k = 0
        while (k < prof.size - 2 && prof[k + 1].second > y) k++
        val a = prof[k]; val b = prof[k + 1]
        val u = ((a.second - y) / (a.second - b.second)).coerceIn(0f, 1f)
        val rr = a.first + (b.first - a.first) * u
        val t = (k + u) / (prof.size - 1)
        var ang = half
        for (i in 0 until 4) { val r = rr * torsoRad(ang, t); ang = acos((x / r).coerceIn(-0.98f, 0.98f)) }
        return rr * torsoRad(ang, t) * sin(ang)
    }

    private fun buildBody(): Mesh {
        val pb = PartBuilder()
        val torso = Sculpt.shell(prof, 28, radial = { ang, _, t -> torsoRad(ang, t) })
        KitA.shade(torso) { x, y, z, _, _, _ ->
            val fade = 0.30f + 0.70f * sstep(0f, 0.42f, y)                                                  // fades into smoke at the base
            val sternum = 1f - 0.32f * gauss(x * x, 0.025f) * sstep(0f, 0.08f, z) * sstep(0.30f, 0.46f, y) * sstep(0.70f, 0.56f, y)
            val underPec = 1f - 0.30f * gauss(sq(y - (0.50f - 0.30f * abs(x))), 0.045f) * sstep(0f, 0.06f, z) * sstep(0.30f, 0.12f, abs(x))
            val abs3 = 1f - 0.30f * (gauss(sq(y - 0.405f), 0.022f) + gauss(sq(y - 0.335f), 0.022f) + gauss(sq(y - 0.255f), 0.022f)) * gauss(x * x, 0.14f) * sstep(0f, 0.05f, z)
            fade * sternum * underPec * abs3
        }
        pb.add(torso, 0x4A1411, SKIN)
        // spiked collar around the neck base
        for (i in 0 until 6) {
            val a = KitA.TAU * i / 6f + 0.35f
            pb.add(spk(0.016f, 0.075f), 0x3A332D, Mat.BONE, x = 0.135f * kotlin.math.cos(a), y = 0.765f, z = 0.03f + 0.135f * sin(a), rx = 42f, ry = Math.toDegrees((PI / 2 - a).toDouble()).toFloat())
        }
        // glowing cracks across the chest and belly
        fun cv(x: Float, y: Float) = floatArrayOf(x, y, torsoZ(x, y) + 0.008f)
        for (v in listOf(
            listOf(0.000f to 0.60f, 0.012f to 0.53f, -0.014f to 0.46f, 0.006f to 0.39f, -0.010f to 0.32f, 0.012f to 0.25f),
            listOf(0.006f to 0.49f, 0.070f to 0.515f, 0.115f to 0.485f, 0.175f to 0.515f, 0.225f to 0.49f),
            listOf(-0.014f to 0.46f, -0.070f to 0.48f, -0.120f to 0.45f, -0.185f to 0.475f),
            listOf(0.070f to 0.515f, 0.085f to 0.575f, 0.140f to 0.605f, 0.190f to 0.59f),
            listOf(-0.070f to 0.48f, -0.095f to 0.545f, -0.150f to 0.57f),
            listOf(0.006f to 0.39f, 0.070f to 0.37f, 0.110f to 0.33f, 0.150f to 0.30f),
            listOf(-0.010f to 0.32f, -0.070f to 0.30f, -0.120f to 0.26f))) vein(pb, v.map { cv(it.first, it.second) })
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- head
    private fun headShape(dx: Float, dy: Float, dz: Float): Float {
        val ax = abs(dx)
        val front = sstep(0f, 0.55f, dz)
        var r = 1f
        r *= 1f - 0.22f * sstep(-0.15f, -0.95f, dy) * front                                            // the lower face narrows into a snout
        r += 0.20f * gauss(sq(dy - (0.14f + 0.30f * ax)), 0.17f) * sstep(0.2f, 0.7f, dz) * sstep(0.98f, 0.3f, ax)   // angry brow: inner ends low
        r -= 0.38f * (gauss(1.4f * sq(dx - 0.43f) + 1.8f * sq(dy) + 0.8f * sq(dz - 0.90f), 0.27f) +
            gauss(1.4f * sq(dx + 0.43f) + 1.8f * sq(dy) + 0.8f * sq(dz - 0.90f), 0.27f))                // deep eye sockets
        r += 0.17f * gauss(3.0f * dx * dx + 3.2f * sq(dy + 0.36f), 0.38f) * sstep(0.3f, 0.85f, dz)       // muzzle
        r -= 0.12f * (gauss(6f * sq(dx - 0.14f) + 6f * sq(dy + 0.28f), 0.2f) + gauss(6f * sq(dx + 0.14f) + 6f * sq(dy + 0.28f), 0.2f)) * sstep(0.7f, 0.95f, dz)   // nostrils
        r += 0.13f * gauss(sq(ax - 0.80f) + sq(dy + 0.10f) + 0.5f * sq(dz - 0.45f), 0.24f)                // cheekbones
        r += 0.05f * gauss(dx * dx, 0.10f) * sstep(0.3f, 0.9f, dy)                                       // sagittal ridge
        return r
    }

    private fun hornPath(sx: Float): (Float) -> FloatArray = KitA.spline(listOf(
        h(sx * 0.055f, 0.105f, 0.045f), h(sx * 0.100f, 0.135f, 0.010f), h(sx * 0.150f, 0.180f, -0.030f),
        h(sx * 0.188f, 0.250f, -0.050f), h(sx * 0.168f, 0.355f, -0.020f)))

    private fun hornR(t: Float): Float = 0.030f * (1f - t).pow(0.75f) * (1f + 0.10f * sin(KitA.TAU * 2.5f * t))

    private fun buildHead(): Mesh {
        val pb = PartBuilder()
        val skull = Sculpt.blob(16, 22, 0.100f, 0.118f, 0.112f) { dx, dy, dz -> headShape(dx, dy, dz) }
        KitA.shade(skull) { x, y, z, _, ny, _ -> (0.70f + 0.30f * sstep(-0.12f, 0.06f, z)) * (0.80f + 0.20f * (ny * 0.5f + 0.5f)) * (0.85f + 0.15f * sstep(-0.12f, 0.10f, y)) * (1f - 0.60f * gauss(sq(abs(x) - 0.030f) + sq(y) + sq(z - 0.068f), 0.030f)) }
        pb.add(skull, 0x52150F, SKIN, x = hc[0], y = hc[1], z = hc[2])
        // ringed horns with white-hot tips (the tips glow harder when the demon roars)
        for (sx in SGN) {
            val path = hornPath(sx)
            pb.add(Sculpt.tube(12, 6, { t -> path(0.72f * t) }, { t -> hornR(0.72f * t) }, cap = true), 0x3A332D, Mat.BONE)
            pb.add(Sculpt.tube(3, 6, { t -> path(0.72f + 0.28f * t) }, { t -> hornR(0.72f + 0.28f * t) }, cap = true), 0xFF3A22, Mat.EMISSIVE)
        }
        // brow horns, cheek and jaw spikes, swept-back ears
        for (sx in SGN) {
            pb.add(spk(0.012f, 0.050f), 0x3A332D, Mat.BONE, x = hc[0] + sx * 0.060f, y = hc[1] + 0.058f, z = hc[2] + 0.090f, rx = 38f, rz = -sx * 24f)
            pb.add(spk(0.010f, 0.036f), 0x3A332D, Mat.BONE, x = hc[0] + sx * 0.092f, y = hc[1] + 0.040f, z = hc[2] + 0.050f, rx = 20f, rz = -sx * 70f)
            pb.add(spk(0.012f, 0.044f), 0x3A332D, Mat.BONE, x = hc[0] + sx * 0.090f, y = hc[1] - 0.050f, z = hc[2] + 0.010f, rx = 70f, rz = -sx * 80f)
            pb.add(spk(0.010f, 0.030f), 0x3A332D, Mat.BONE, x = hc[0] + sx * 0.080f, y = hc[1] - 0.082f, z = hc[2] + 0.040f, rx = 80f, rz = -sx * 40f)
            pb.add(Sculpt.tube(3, 4, KitA.spline(listOf(h(sx * 0.094f, 0.020f, -0.010f), h(sx * 0.150f, 0.050f, -0.050f), h(sx * 0.205f, 0.110f, -0.085f))),
                { t -> 0.022f * (1f - t) + 0.001f }, cap = true), 0x3C100E, SKIN)
        }
        // heavy angry brow ridges over the eyes
        for (sx in SGN) pb.add(Sculpt.tube(3, 5, KitA.spline(listOf(h(sx * 0.0115f, 0.023f, 0.126f), h(sx * 0.054f, 0.039f, 0.114f), h(sx * 0.088f, 0.049f, 0.057f))), { t -> 0.015f - 0.005f * t }, cap = true), 0x3A100D, SKIN)
        // glowing cracks on the forehead and from the eye sockets
        fun hv(dx: Float, dy: Float, dz: Float): FloatArray { val l = sqrt(dx * dx + dy * dy + dz * dz); val k = 1.05f / l; return h(dx * k * 0.100f, dy * k * 0.118f, dz * k * 0.112f) }
        vein(pb, listOf(hv(0.03f, 0.60f, 0.80f), hv(-0.05f, 0.78f, 0.62f), hv(0.04f, 0.90f, 0.43f), hv(0f, 0.97f, 0.25f)), 0.0034f)
        for (sx in SGN) vein(pb, listOf(hv(sx * 0.70f, 0.05f, 0.71f), hv(sx * 0.78f, -0.15f, 0.60f), hv(sx * 0.80f, -0.32f, 0.50f), hv(sx * 0.76f, -0.50f, 0.42f)), 0.0034f)
        // burning eyes (molten yellow), set in the sockets and slanted angrily
        for (sx in SGN) pb.add(Sculpt.ellipsoid(0.026f, 0.0125f, 0.012f, 3, 6), 0xFFD860, Mat.EMISSIVE, x = hc[0] + sx * 0.036f, y = hc[1] + 0.006f, z = hc[2] + 0.078f, rz = sx * 24f)
        // upper fangs and a glowing palate
        for ((i, fx) in floatArrayOf(-0.034f, -0.014f, 0.014f, 0.034f).withIndex()) {
            val len = if (i == 0 || i == 3) 0.040f else 0.022f
            pb.add(Sculpt.tube(2, 4, { t -> floatArrayOf(0f, -len * t, 0.004f * t) }, { t -> 0.0085f * (1f - t) + 0.0004f }, cap = true), 0xC9BB96, Mat.BONE, x = hc[0] + fx, y = hc[1] - 0.058f, z = hc[2] + 0.104f)
        }
        pb.add(Sculpt.ellipsoid(0.040f, 0.007f, 0.055f, 3, 8), 0x7A0C0C, Mat.EMISSIVE, x = hc[0], y = hc[1] - 0.072f, z = hc[2] + 0.075f)
        KitA.xform(pb.data, hc, hn, HS)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- jaw
    private fun buildJaw(): Mesh {
        val pb = PartBuilder()
        val pts = listOf(floatArrayOf(-0.078f, 0.872f, 0.018f), floatArrayOf(-0.084f, 0.836f, 0.072f), floatArrayOf(-0.052f, 0.810f, 0.148f),
            floatArrayOf(0f, 0.798f, 0.178f), floatArrayOf(0.052f, 0.810f, 0.148f), floatArrayOf(0.084f, 0.836f, 0.072f), floatArrayOf(0.078f, 0.872f, 0.018f))
        val path = KitA.spline(pts)
        val bone = Sculpt.tube(10, 6, path, { t -> 0.024f - 0.006f * sin(PI.toFloat() * t) }, cap = true)
        KitA.shade(bone) { _, y, _, _, ny, _ -> 0.75f + 0.25f * (ny * 0.5f + 0.5f) + 0f * y }
        pb.add(bone, 0x46130F, SKIN)
        // lower teeth along the front, two tusks, a goatee spike
        for (i in 0 until 6) {
            val q = path(0.30f + 0.08f * i)
            pb.add(Sculpt.tube(1, 4, { t -> floatArrayOf(0f, 0.020f * t, 0f) }, { t -> 0.0065f * (1f - t) }, cap = true), 0xC9BB96, Mat.BONE, x = q[0], y = q[1] + 0.016f, z = q[2] + 0.004f)
        }
        for (sx in SGN) pb.add(spk(0.0095f, 0.050f), 0xD6C9A2, Mat.BONE, x = sx * 0.044f, y = 0.822f, z = 0.150f, rx = 14f, rz = -sx * 8f)
        pb.add(spk(0.012f, 0.036f), 0x3A332D, Mat.BONE, y = 0.800f, z = 0.185f, rx = 140f)
        pb.add(Sculpt.ellipsoid(0.034f, 0.006f, 0.050f, 3, 6), 0x8A0C0C, Mat.EMISSIVE, y = 0.832f, z = 0.095f)         // tongue / throat glow
        KitA.xform(pb.data, hc, hn, HS)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- arms
    private fun buildArm(sx: Float): Mesh {
        val pb = PartBuilder()
        val s = floatArrayOf(sx * 0.30f, 0.62f, 0f); val e = floatArrayOf(sx * 0.40f, 0.44f, 0.07f); val w = floatArrayOf(sx * 0.36f, 0.29f, 0.22f)
        val skin = 0x45120F
        // spiked pauldron
        pb.add(Sculpt.ellipsoid(0.085f, 0.075f, 0.080f, 3, 8), 0x34100F, SKIN, x = s[0], y = s[1] + 0.012f, z = s[2])
        for (k in 0 until 3) pb.add(spk(0.015f, 0.070f - 0.010f * k), 0x3A332D, Mat.BONE, x = s[0] + sx * (0.030f + 0.012f * k), y = s[1] + 0.045f - 0.020f * k, z = -0.012f + 0.030f * k, rz = -sx * (50f + 14f * k))
        // upper arm, forearm (muscular swell), elbow spike
        pb.add(Sculpt.tube(4, 6, KitA.poly(listOf(s, e)), { t -> 0.072f * (1f + 0.15f * sin(PI.toFloat() * t)) - 0.014f * t }, cap = true), skin, SKIN)
        pb.add(Sculpt.tube(4, 6, KitA.poly(listOf(e, w)), { t -> 0.058f * (1f + 0.12f * sin(PI.toFloat() * t)) - 0.012f * t }, cap = true), skin, SKIN)
        pb.add(spk(0.016f, 0.060f), 0x3A332D, Mat.BONE, x = e[0] + sx * 0.045f, y = e[1] - 0.005f, z = e[2] - 0.015f, rx = -20f, rz = -sx * 100f)
        // glowing cracks along the bicep and forearm
        vein(pb, listOf(floatArrayOf(s[0] + sx * 0.025f, s[1] - 0.030f, s[2] + 0.068f), floatArrayOf(e[0] + sx * 0.012f, e[1] + 0.045f, e[2] + 0.060f), floatArrayOf(e[0] + sx * 0.004f, e[1] - 0.010f, e[2] + 0.064f)))
        vein(pb, listOf(floatArrayOf(e[0] - sx * 0.010f, e[1] - 0.030f, e[2] + 0.060f), floatArrayOf((e[0] + w[0]) / 2f - sx * 0.004f, (e[1] + w[1]) / 2f, (e[2] + w[2]) / 2f + 0.052f), floatArrayOf(w[0], w[1] + 0.020f, w[2] + 0.050f)))
        // hand: palm and four hooked claws + a thumb claw
        val hx = w[0]; val hy = w[1] - 0.045f; val hz = w[2] + 0.035f
        pb.add(Sculpt.ellipsoid(0.048f, 0.040f, 0.036f, 3, 6), skin, SKIN, x = hx, y = hy, z = hz)
        for (i in 0 until 4) {
            val kx = hx + sx * (-0.030f + 0.020f * i)
            val k0 = floatArrayOf(kx, hy - 0.010f, hz + 0.025f); val k1 = floatArrayOf(kx + sx * 0.004f * (i - 1.5f), hy - 0.034f, hz + 0.074f); val k2 = floatArrayOf(kx + sx * 0.012f * (i - 1.5f), hy - 0.092f, hz + 0.106f)
            pb.add(Sculpt.tube(2, 4, KitA.spline(listOf(k0, k1, k2)), { t -> 0.0145f * (1f - t) + 0.0006f }, cap = true), 0x2C2622, Mat.BONE)
        }
        val t0 = floatArrayOf(hx - sx * 0.040f, hy, hz + 0.005f); val t1 = floatArrayOf(hx - sx * 0.064f, hy - 0.024f, hz + 0.036f); val t2 = floatArrayOf(hx - sx * 0.064f, hy - 0.066f, hz + 0.062f)
        pb.add(Sculpt.tube(2, 4, KitA.spline(listOf(t0, t1, t2)), { t -> 0.012f * (1f - t) + 0.0006f }, cap = true), 0x2C2622, Mat.BONE)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- wings
    /** One-sided membrane triangles are duplicated back to back (the renderer culls back faces). */
    private fun addMembrane(m: MeshData, c: FloatArray, o: List<FloatArray>) {
        for (i in o.indices) {
            val a = o[i]; val b = o[(i + 1) % o.size]
            val ux = a[0] - c[0]; val uy = a[1] - c[1]; val uz = a[2] - c[2]; val vx = b[0] - c[0]; val vy = b[1] - c[1]; val vz = b[2] - c[2]
            var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
            val l = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f); nx /= l; ny /= l; nz /= l
            val f0 = m.vertex(c[0], c[1], c[2], nx, ny, nz, 0.5f, 0.5f); val f1 = m.vertex(a[0], a[1], a[2], nx, ny, nz, 0f, 0f); val f2 = m.vertex(b[0], b[1], b[2], nx, ny, nz, 1f, 0f)
            m.tri(f0, f1, f2)
            val b0 = m.vertex(c[0], c[1], c[2], -nx, -ny, -nz, 0.5f, 0.5f); val b1 = m.vertex(a[0], a[1], a[2], -nx, -ny, -nz, 0f, 0f); val b2 = m.vertex(b[0], b[1], b[2], -nx, -ny, -nz, 1f, 0f)
            m.tri(b0, b2, b1)
        }
    }

    private fun buildWings(): Mesh {
        val pb = PartBuilder()
        for (sx in SGN) {
            fun p(x: Float, y: Float, z: Float) = floatArrayOf(sx * x, y, z)
            val root = p(0.10f, 0.66f, -0.10f); val elbow = p(0.30f, 1.02f, -0.20f); val wrist = p(0.46f, 0.96f, -0.24f)
            val tip1 = p(0.60f, 1.22f, -0.26f); val tip2 = p(0.68f, 0.90f, -0.27f); val tip3 = p(0.56f, 0.58f, -0.24f); val low = p(0.11f, 0.40f, -0.11f)
            val s1 = p(0.58f, 0.72f, -0.25f); val s2 = p(0.60f, 1.05f, -0.26f)
            val mem = MeshData()
            addMembrane(mem, wrist, listOf(tip1, elbow, root, low, tip3, s1, tip2, s2))
            KitA.shade(mem) { x, _, _, _, _, _ -> 0.45f + 0.55f * sstep(0.08f, 0.55f, abs(x)) }
            pb.add(mem, 0x401015, SKIN)
            for (end in listOf(s1, s2, low, tip3)) {
                val mid = floatArrayOf((wrist[0] + end[0]) / 2f, (wrist[1] + end[1]) / 2f + 0.01f, (wrist[2] + end[2]) / 2f + 0.006f)
                vein(pb, listOf(floatArrayOf(wrist[0], wrist[1], wrist[2] + 0.006f), mid, floatArrayOf(end[0], end[1], end[2] + 0.006f)), 0.0034f)
            }
            val bone = 0x2A2420
            pb.add(Sculpt.tube(3, 4, KitA.poly(listOf(root, elbow)), { t -> 0.024f - 0.008f * t }, cap = true), bone, Mat.BONE)
            pb.add(Sculpt.tube(3, 4, KitA.poly(listOf(elbow, wrist)), { t -> 0.016f - 0.004f * t }, cap = true), bone, Mat.BONE)
            for (tip in listOf(tip1, tip2, tip3)) pb.add(Sculpt.tube(3, 4, KitA.poly(listOf(wrist, tip)), { t -> 0.012f * (1f - t) + 0.002f }, cap = true), bone, Mat.BONE)
        }
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- animation
    private val root = FloatArray(16); private val hm = FloatArray(16); private val jm = FloatArray(16)
    private val aR = FloatArray(16); private val aL = FloatArray(16); private val wm = FloatArray(16)

    init {
        body = buildBody(); head = buildHead(); jaw = buildJaw()
        armR = buildArm(1f); armL = buildArm(-1f); wings = buildWings()
    }

    override fun draw(sc: Scene3D, mm: FloatArray, t: Float, ex: Float, dim: Float) {
        val fl = KitA.flicker(t, 0.7f)
        val breath = sin(t * 1.5f)
        // ---- poses first (the local lights need them before the first drawV)
        KitA.set(root, mm)
        val sh = ex * 0.006f
        Mat4.translate(root, sh * sin(t * 43f), 0f, sh * sin(t * 37f))
        Mat4.rotate(root, ex * 4f, 1f, 0f, 0f)
        Mat4.scale(root, 1f + 0.006f * breath + 0.03f * ex, 1f + 0.012f * breath + 0.04f * ex, 1f + 0.006f * breath + 0.03f * ex)
        val yaw = sin(t * 0.37f) * 18f * (1f - ex) + ex * sin(t * 6f) * 3f
        val pitch = -2f + sin(t * 0.8f) * 2f - ex * 15f
        val roll = sin(t * 0.5f + 1f) * 3f * (1f - ex)
        KitA.set(hm, root)
        Mat4.translate(hm, neck[0], neck[1], neck[2]); Mat4.rotate(hm, yaw, 0f, 1f, 0f); Mat4.rotate(hm, pitch, 1f, 0f, 0f); Mat4.rotate(hm, roll, 0f, 0f, 1f)
        Mat4.translate(hm, -neck[0], -neck[1], -neck[2])
        val open = 3f + 2f * sin(t * 0.9f) + ex * (28f + 6f * sin(t * 23f))
        KitA.pivot(jm, hm, jawHinge[0], jawHinge[1], jawHinge[2], open, 0f, 0f)
        KitA.pivot(aR, root, shoulderR[0], shoulderR[1], shoulderR[2], -10f + sin(t * 1.1f) * 2f - ex * 52f, 0f, 4f + ex * 40f)
        KitA.pivot(aL, root, shoulderL[0], shoulderL[1], shoulderL[2], -10f + sin(t * 1.1f + 1.7f) * 2f - ex * 52f, 0f, -4f - ex * 40f)
        KitA.set(wm, root)
        Mat4.scale(wm, 0.94f + 0.04f * sin(t * 0.9f) + ex * (0.12f + 0.05f * sin(t * 9f)), 1f + 0.02f * ex, 1f)
        val eye = 1.4f + 0.3f * fl + ex * 1.8f

        // ---- two local lights: the burning eyes light the brow and horns, the hellfire lights the body from below
        sc.beginLocalLights()
        sc.pointLightAt(hm, 0f, hn[1], hn[2] + 0.26f, 0.6f, 0xFF4A2A, (0.30f + 0.40f * ex) * dim)
        sc.pointLightAt(root, 0f, 0.10f, 0.40f, 1.3f, 0xFF3A18, (0.45f + 0.25f * fl + 0.40f * ex) * dim)

        // ---- draws
        sc.drawV(wings, wm, emis = 0.8f + 0.4f * fl + 0.6f * ex, dim = dim, rim = 0x8A1818, spec = 0.4f, shine = 20f)
        sc.drawV(body, root, emis = 0.8f + 0.4f * fl + 0.7f * ex, dim = dim, rim = 0x7A1414, spec = 0.5f, shine = 20f)
        sc.drawV(head, hm, emis = eye, dim = dim, rim = 0x7A1414, spec = 0.5f, shine = 20f)
        sc.drawV(jaw, jm, emis = 0.8f + 0.9f * ex, dim = dim, rim = 0x7A1414, spec = 0.5f, shine = 20f)
        sc.drawV(armR, aR, emis = 0.8f + 0.4f * fl + 0.7f * ex, dim = dim, rim = 0x7A1414, spec = 0.5f, shine = 20f)
        sc.drawV(armL, aL, emis = 0.8f + 0.4f * fl + 0.7f * ex, dim = dim, rim = 0x7A1414, spec = 0.5f, shine = 20f)
        // glow sprites: the eyes (hot core, red halo), the open mouth, the hellfire at the base
        for (g in eyeGlow) sc.glowAt(hm, g[0], g[1], g[2], 0.085f * eye + 0.02f, 0xFF3A1E, 0.8f * dim, 0.8f)
        val mouth = sstep(6f, 24f, open)
        if (mouth > 0.01f) sc.glowAt(hm, mouthGlow[0], mouthGlow[1], mouthGlow[2], 0.16f + 0.14f * mouth, 0xFF3010, 0.65f * mouth * dim, 0.3f)
        sc.glowAt(root, 0f, 0.07f, 0.10f, 0.95f + 0.2f * ex, 0xFF2A10, (0.22f + 0.10f * fl + 0.15f * ex) * dim, 0f)
    }

    private companion object {
        val SGN = floatArrayOf(-1f, 1f)
        /** Skin material. Mat.FLESH covers ~40% of the surface with glowing veins and ignores dim; matte + modelled cracks stay dark and dim correctly. */
        const val SKIN = Mat.MATTE
    }
}

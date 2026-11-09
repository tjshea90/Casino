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
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Wraith (a wailing banshee): a tall, gaunt, translucent spectre (ectoplasm shader: see-through body, hard cold rim light) in a
 * ragged hood with trailing scarves, a thin torso with a faint ribcage glimpsed inside, batwing sleeves over skeletal arms ending
 * in claws, and a lower half that dissolves into a fan of ragged strips. A gaunt face with hollow eye pits (dim red embers) and a
 * screaming mouth on a hinged jaw; long glowing hair streams behind.
 * Idle: floats and drifts leaning forward, shroud and hair sway (the shader's hem wave), slow head turns. Excited: it WAILS (head back,
 * jaw wide, arms thrown out, shroud and hair whip, glow flares, sound rings leave the mouth).
 *
 * 7 meshes: tails, hair (+ scarves), body (ribs, hood, shroud), head (+ embers, throat, teeth), jaw, two arms. ~3,500 triangles.
 */
class WraithModel : CharacterModel {
    private val head0 = floatArrayOf(0f, 1.085f, 0.05f)      // skull centre
    private val hs = 0.112f                                   // head units -> model units
    private val jawHinge = floatArrayOf(0f, -0.02f, 0.02f)   // head units
    private val shoulder = floatArrayOf(0.14f, 0.885f, 0f)
    private val hairOffY = 0.76f                              // hanging meshes are built with their roots at local y = 0.4 so the hem-wave shader sways them
    private val tailOffY = 0.02f

    private val ECTO = 0x7CA4D4; private val ECTO_V = 0x8AB0DA
    private val BONE = 0xB4C6D2

    private val tails: Mesh
    private val hair: Mesh
    private val body: Mesh
    private val head: Mesh
    private val jaw: Mesh
    private val armL: Mesh
    private val armR: Mesh
    val triangles: Int

    init {
        tails = buildTails(); hair = buildHair(); body = buildBody(); head = buildHead(); jaw = buildJaw()
        armL = buildArm(-1f); armR = buildArm(1f)
        triangles = (tails.indexCount + hair.indexCount + body.indexCount + head.indexCount + jaw.indexCount + armL.indexCount + armR.indexCount) / 3
    }

    // ---------------------------------------------------------------------------------------------- geometry
    /** The lower half dissolving: a fan of ragged strips that fade to nothing, plus a few thin streamers. */
    private fun buildTails(): Mesh {
        val pb = PartBuilder()
        val rnd = Random(5)
        val n = 17
        for (i in 0 until n) {
            val a = (2 * PI * i / n + (rnd.nextFloat() - 0.5f) * 0.3f).toFloat()
            val r0 = 0.14f + rnd.nextFloat() * 0.05f
            val len = 0.33f + rnd.nextFloat() * 0.10f + (if (sin(a) < 0f) 0.08f else 0f)
            val w0 = 0.075f + rnd.nextFloat() * 0.05f
            val ox = cos(a); val oz = sin(a)
            val x0 = r0 * ox; val z0 = r0 * oz
            val spread = 0.10f + rnd.nextFloat() * 0.10f
            val side = floatArrayOf(-oz, 0f, ox)
            val jit = i * 7
            pb.add(CharKit.flowRibbon(6, { t -> floatArrayOf(x0 + ox * spread * t * t, 0.40f - len * t, z0 + oz * spread * t * t - 0.09f * t) },
                { t -> w0 * (1f - 0.88f * t * t) * (0.7f + 0.6f * ((jit + (t * 6f).toInt() * 3) % 5) / 5f) }, side, { t -> (1f - t).pow(1.3f) * 0.95f + 0.05f }, both = true), ECTO, Mat.ECTO)
        }
        // thin streamers, longer than the strips, drifting backwards
        for (i in 0 until 5) {
            val a = (PI * (1.05 + 0.9 * i / 4.0)).toFloat()
            val ox = cos(a); val oz = sin(a)
            val x0 = 0.12f * ox; val z0 = 0.12f * oz
            val len = 0.46f + 0.04f * i
            pb.add(CharKit.fadeTube(7, 3, { t -> floatArrayOf(x0 + ox * 0.16f * t + 0.03f * sin(9f * t + i), 0.40f - len * t, z0 + oz * 0.16f * t - 0.14f * t) }, { t -> 0.007f * (1f - 0.8f * t) }, { t -> (1f - t).pow(1.2f) }), ECTO, Mat.ECTO)
        }
        return pb.build()
    }

    /** Long glowing hair streaming from the back and sides of the head, and two ragged scarves trailing from the hood. */
    private fun buildHair(): Mesh {
        val pb = PartBuilder()
        val rnd = Random(9)
        val n = 14
        for (i in 0 until n) {
            val b = -0.12f + 1.24f * i / (n - 1)
            val a = (PI + b * PI).toFloat()
            val ox = cos(a) * 1.5f; val oz = min(sin(a), 0.10f)
            val rx = 0.09f * cos(a); val ry = 1.16f - 0.03f * abs(cos(a)); val rz = 0.03f + 0.09f * sin(a)
            val len = 0.75f + rnd.nextFloat() * 0.25f
            val ph = rnd.nextFloat() * 6.28f
            pb.add(CharKit.fadeTube(6, 3, { t ->
                floatArrayOf(rx + ox * (0.05f + 0.20f * t.pow(1.2f)) + 0.03f * sin(8f * t + ph), ry - len * t - hairOffY, rz + oz * (0.04f + 0.16f * t.pow(1.2f)) - 0.12f * t + 0.025f * cos(7f * t + ph))
            }, { t -> 0.0095f * (1f - 0.8f * t) }, { t -> 0.95f - 0.9f * t }), 0xA8CCF5, Mat.EMISSIVE)
        }
        for (sx in floatArrayOf(-1f, 1f)) {
            val x0 = sx * 0.07f
            pb.add(CharKit.flowRibbon(8, { t -> floatArrayOf(x0 + sx * (0.04f + 0.30f * t), 1.20f - 0.78f * t - hairOffY, -0.08f - 0.22f * t + 0.05f * sin(6f * t)) },
                { t -> 0.09f * (1f - 0.8f * t) * (0.8f + 0.4f * ((t * 8f).toInt() % 3) / 3f) }, floatArrayOf(0f, 0f, 1f), { t -> (1f - t).pow(1.2f) * 0.9f + 0.1f }, both = true), ECTO_V, Mat.ECTO)
        }
        return pb.build()
    }

    private fun buildBody(): Mesh {
        val pb = PartBuilder()
        // a faint ribcage and spine glimpsed through the translucent chest (opaque inside, so it goes in first)
        for (k in 0 until 5) {
            val y = 0.865f - k * 0.034f
            val rw = 0.088f - 0.004f * k; val depth = 0.066f
            for (sx in floatArrayOf(-1f, 1f)) {
                pb.add(CharKit.fadeTube(6, 3, { t ->
                    val ph = t * 0.95f * PI.toFloat()
                    floatArrayOf(sx * rw * sin(ph), y - 0.03f * t, -depth * cos(ph))
                }, { 0.0065f }, { t -> 1f - 0.4f * t }), 0x9CC0E8, Mat.EMISSIVE, ao = 0.75f)
            }
        }
        pb.add(Sculpt.tube(6, 4, { t -> floatArrayOf(0f, 0.93f - 0.27f * t, -0.066f) }, { 0.011f }), 0x9CC0E8, Mat.EMISSIVE, ao = 0.7f)
        // the hood (open at the front so the face shows), ragged and swept back
        val vprof = listOf(0.012f to 1.28f, 0.075f to 1.265f, 0.14f to 1.225f, 0.19f to 1.155f, 0.215f to 1.08f, 0.225f to 1.00f, 0.250f to 0.93f, 0.285f to 0.875f)
        val open = 0.82f
        pb.add(CharKit.shadeY(Sculpt.shell(vprof, 20, a0 = (PI / 2 + open).toFloat(), a1 = (PI / 2 - open + 2 * PI).toFloat(),
            radial = { ang, _, t -> 1f + 0.07f * sin(4f * ang + 2f * t) + 0.14f * max(0f, -sin(ang)) * sstep(0.2f, 0.8f, t) },
            dy = { ang, _, t -> 0.08f * sstep(0.4f, 0f, t) * (0.5f - 0.5f * sin(ang)) - 0.06f * sstep(0.55f, 1f, t) * (0.4f + abs(sin(7f * ang))) }), { y -> 0.45f + 0.55f * sstep(0.88f, 1.1f, y) }), ECTO_V, Mat.ECTO)
        // the thin shroud, gaunt at the waist, then fraying into the strips
        val prof = listOf(0.028f to 0.975f, 0.070f to 0.952f, 0.125f to 0.915f, 0.150f to 0.865f, 0.130f to 0.790f, 0.100f to 0.715f,
            0.105f to 0.640f, 0.130f to 0.565f, 0.165f to 0.490f, 0.200f to 0.420f)
        val rows = prof.size
        pb.add(CharKit.shadeY(Sculpt.shell(prof, 22,
            radial = { ang, _, t -> 1f + (0.03f + 0.10f * t) * sin(5f * ang + 2.5f * t) + 0.04f * t * sin(9f * ang + 4f * t) + 0.12f * sstep(0.35f, 1f, t) * max(0f, -sin(ang)) },
            dy = { ang, k, _ -> if (k >= rows - 3) -((k - (rows - 4)).toFloat() / 3f) * (0.02f + 0.07f * abs(sin(7f * ang + 1f)).pow(1.5f) + 0.04f * abs(sin(3f * ang))) else 0f }),
            { y -> 0.30f + 0.70f * sstep(0.40f, 0.62f, y) }), ECTO, Mat.ECTO)
        return pb.build()
    }

    // ---- the face: a long gaunt skull with drooping, anguished eye pits and a tall screaming mouth carved into it
    private fun pit(dx: Float, dy: Float, dz: Float, sx: Float): Float {
        fun g(cx: Float, cy: Float, cz: Float): Float {
            val a = dx - cx * sx; val b = dy - cy; val c = dz - cz
            return gauss(0.8f * a * a + 1.6f * b * b + 0.45f * c * c, 0.17f)
        }
        return g(0.50f, 0.04f, 0.82f) + 0.95f * g(0.26f, 0.20f, 0.93f)
    }

    private fun faceShape(dx: Float, dy: Float, dz: Float): Float {
        var r = 1f
        val front = sstep(-0.1f, 0.6f, dz)
        r *= 1f - 0.30f * sstep(-0.1f, -0.95f, dy) * front
        r *= 1f - 0.18f * sstep(-0.3f, -1f, dy)
        for (s in floatArrayOf(-1f, 1f)) {
            r -= 0.62f * pit(dx, dy, dz, s)
            r -= 0.20f * gauss((dx - 0.58f * s).pow(2) + 0.8f * (dy + 0.32f).pow(2) + 0.5f * (dz - 0.68f).pow(2), 0.22f)      // sunken cheeks
        }
        r -= 0.16f * gauss(2.5f * dx * dx + 0.8f * (dy + 0.08f).pow(2) + 0.5f * (dz - 0.96f).pow(2), 0.14f)                     // nostrils
        r += 0.07f * gauss((dy - 0.34f).pow(2), 0.1f) * sstep(0.1f, 0.8f, dz)                                                    // brow
        r -= 0.80f * gauss(1.2f * dx * dx + 0.55f * (dy + 0.52f).pow(2) + 0.5f * (dz - 0.88f).pow(2), 0.26f)                    // the scream
        return r
    }

    private fun buildHead(): Mesh {
        val pb = PartBuilder()
        pb.add(Sculpt.blob(15, 22, 0.80f, 1.12f, 0.95f, ao = { r, dy -> (sstep(0.50f, 0.96f, r) * (0.70f + 0.30f * sstep(-1f, 0.3f, dy))).coerceIn(0.04f, 1f) }, shape = ::faceShape),
            0xA9BBC8, Mat.WAX, x = head0[0], y = head0[1], z = head0[2], sx = hs, ao = 1f)
        // the throat: a black hollow behind the jaw so the open mouth reads as a hole
        pb.add(Sculpt.ellipsoid(0.40f, 0.58f, 0.22f, 5, 10), 0x04050A, Mat.MATTE, x = head0[0], y = head0[1] - 0.82f * hs, z = head0[2] + 0.30f * hs, sx = hs)
        pb.add(CharKit.teeth(7, 0.30f, 0.24f, -1f, 0.84f, 0.22f), 0xD0DCE6, Mat.BONE, x = head0[0], y = head0[1] - 0.46f * hs, z = head0[2], sx = hs)
        // dim red embers deep in the pits
        for (s in floatArrayOf(-1f, 1f)) {
            val d = CharKit.unit(0.36f * s, 0.12f, 0.92f)
            val r = faceShape(d[0], d[1], d[2]) * 0.92f
            pb.add(Sculpt.ellipsoid(0.075f, 0.075f, 0.05f, 3, 6), 0xFF2A3A, Mat.EMISSIVE,
                x = head0[0] + d[0] * r * 0.80f * hs, y = head0[1] + d[1] * r * 1.12f * hs, z = head0[2] + d[2] * r * 0.95f * hs, sx = hs)
        }
        return pb.build()
    }

    private fun buildJaw(): Mesh {
        val pb = PartBuilder()
        pb.add(CharKit.jawLite(10, 4), BONE, Mat.BONE, x = head0[0], y = head0[1], z = head0[2], sx = hs * 0.92f, sy = hs * 1.18f, sz = hs, ao = 0.95f)
        pb.add(CharKit.teeth(7, 0.34f, 0.2f, 1f, 0.80f, 0.30f), 0xD0DCE6, Mat.BONE, x = head0[0], y = head0[1] - 0.92f * hs * 1.18f, z = head0[2], sx = hs * 0.92f, sy = hs * 1.18f, sz = hs)
        return pb.build()
    }

    /** A skeletal claw in hand units: fingers along +Z curling down (-Y), palm facing down, thumb on the [thumb] side (+-X). */
    private fun hand(thumb: Float, curl: Float): MeshData {
        val pb = PartBuilder()
        pb.add(Sculpt.ellipsoid(0.026f, 0.010f, 0.030f, 3, 6), BONE, Mat.BONE, ao = 0.95f)
        for (f in 0 until 4) {
            val x = (f - 1.5f) * 0.0165f
            val len = floatArrayOf(0.044f, 0.052f, 0.048f, 0.038f)[f]
            val pts = ArrayList<FloatArray>()
            var y = 0f; var z = 0.026f; var ang = 8f
            pts += floatArrayOf(x, y, z)
            for (k in 0 until 3) {
                ang += curl * (24f + 18f * k)
                val a = Math.toRadians(ang.toDouble()).toFloat(); val l = len * (1f - 0.18f * k)
                y -= l * sin(a); z += l * cos(a); pts += floatArrayOf(x, y, z)
            }
            pb.add(Sculpt.tube(6, 3, { t -> CharKit.polyAt(pts, t) }, { t -> 0.0085f * (1f - 0.8f * t) }), BONE, Mat.BONE, ao = 0.95f)
        }
        val tp = ArrayList<FloatArray>()
        tp += floatArrayOf(thumb * 0.022f, 0f, 0.005f); tp += floatArrayOf(thumb * 0.040f, -0.004f, 0.030f); tp += floatArrayOf(thumb * 0.040f, -0.020f, 0.052f)
        pb.add(Sculpt.tube(4, 3, { t -> CharKit.polyAt(tp, t) }, { t -> 0.0095f * (1f - 0.75f * t) }), BONE, Mat.BONE, ao = 0.95f)
        return pb.data
    }

    /** A skeletal arm (bone first) inside a long batwing sleeve with ragged hanging drapes, ending in a claw. */
    private fun buildArm(sx: Float): Mesh {
        val pb = PartBuilder()
        val s = floatArrayOf(sx * shoulder[0], shoulder[1], shoulder[2])
        val e = floatArrayOf(sx * 0.300f, 0.775f, 0.090f); val w = floatArrayOf(sx * 0.255f, 0.915f, 0.285f)
        pb.add(Sculpt.limb(s, e, 0.013f, 0.012f, 0.1f, 4), BONE, Mat.BONE, ao = 0.85f)
        pb.add(Sculpt.limb(e, w, 0.012f, 0.010f, 0.1f, 4), BONE, Mat.BONE, ao = 0.9f)
        pb.add(hand(-sx, 0.75f), BONE, Mat.BONE, x = w[0] - sx * 0.006f, y = w[1] + 0.004f, z = w[2] + 0.030f, rx = -22f, ry = sx * 8f, rz = sx * -8f, ao = 1f)
        pb.add(Sculpt.tube(10, 6, { t ->
            if (t < 0.5f) { val u = t * 2f; floatArrayOf(s[0] + (e[0] - s[0]) * u, s[1] + (e[1] - s[1]) * u, s[2] + (e[2] - s[2]) * u) }
            else { val u = (t - 0.5f) * 2f; floatArrayOf(e[0] + (w[0] - e[0]) * u, e[1] + (w[1] - e[1]) * u, e[2] + (w[2] + 0.03f - e[2]) * u) }
        }, { t -> 0.040f + 0.050f * sstep(0.45f, 1f, t) }, cap = false), ECTO, Mat.ECTO)
        // ragged drapes hanging from the forearm
        for (i in 0 until 6) {
            val u = 0.15f + 0.17f * i
            val px = e[0] + (w[0] - e[0]) * u; val py = e[1] + (w[1] - e[1]) * u - 0.03f; val pz = e[2] + (w[2] - e[2]) * u
            pb.add(Sculpt.rag(0.055f, 0.13f + 0.05f * ((i * 5) % 4), 0.08f, 3), ECTO, Mat.ECTO, x = px, y = py, z = pz, ry = sx * (22f + 14f * (i % 2)))
        }
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- animation
    // scratch matrices (draw runs on the GL thread only): a small ring so nothing is allocated per frame
    private val pool = Array(8) { FloatArray(16) }
    private var poolI = 0
    private fun copy(m: FloatArray): FloatArray { val d = pool[poolI]; poolI = (poolI + 1) and 7; System.arraycopy(m, 0, d, 0, 16); return d }
    private val SIDES = floatArrayOf(-1f, 1f)
    private val emberP = FloatArray(6).also { e -> for (i in 0..1) { val sx = if (i == 0) -1f else 1f; val d = CharKit.unit(0.36f * sx, 0.12f, 0.92f); e[i * 3] = head0[0] + d[0] * 0.60f * hs; e[i * 3 + 1] = head0[1] + d[1] * 0.55f * hs + 0.005f; e[i * 3 + 2] = head0[2] + 0.60f * hs } }

    override fun draw(sc: Scene3D, mm: FloatArray, t: Float, ex: Float, dim: Float) {
        val rim = 0x2A5A90
        val fade = 0.3f + 0.7f * dim
        val wail = ex * (0.75f + 0.25f * sin(t * 9f))

        // ---- float and drift, leaning forward to loom; throws its head back and leans away when wailing
        val root = copy(mm)
        Mat4.translate(root, 0.03f * sin(t * 0.5f), 0.05f + 0.045f * sin(t * 0.9f) + ex * 0.03f * sin(t * 11f), 0f)
        Mat4.translate(root, 0f, 0.6f, 0f)
        Mat4.rotate(root, 3f * sin(t * 0.45f) + ex * 2f * sin(t * 9f), 0f, 0f, 1f)
        Mat4.rotate(root, 7f + 4f * sin(t * 0.37f + 1f) - ex * 16f, 1f, 0f, 0f)
        Mat4.translate(root, 0f, -0.6f, 0f)

        // ---- head: slow look-around, snaps back and shakes when wailing
        val yaw = sin(t * 0.35f) * 12f * (1f - ex) + ex * sin(t * 14f) * 4f
        val pitch = -2f + 3f * sin(t * 0.6f) - ex * 24f
        val headM = copy(root)
        Mat4.translate(headM, 0f, 0.97f, 0.02f); Mat4.rotate(headM, yaw, 0f, 1f, 0f); Mat4.rotate(headM, pitch, 1f, 0f, 0f); Mat4.translate(headM, 0f, -0.97f, -0.02f)
        val open = 17f + 4f * sin(t * 1.3f) + ex * (20f + 6f * sin(t * 13f))
        val jawM = copy(headM)
        val hx = head0[0] + jawHinge[0] * hs; val hy = head0[1] + jawHinge[1] * hs * 1.18f; val hz = head0[2] + jawHinge[2] * hs
        Mat4.translate(jawM, hx, hy, hz); Mat4.rotate(jawM, open, 1f, 0f, 0f); Mat4.translate(jawM, -hx, -hy, -hz)
        val emberK = 0.9f + 0.2f * sin(t * 2.1f) + ex * 1.4f

        // ---- cold light on the face, a red ember light in the pits (before the first drawV)
        sc.beginLocalLights()
        sc.pointLightAt(headM, 0f, 1.05f, 0.55f, 1.1f, 0x9CC8FF, (0.25f + 0.55f * ex) * dim)
        sc.pointLightAt(headM, 0f, 1.10f, 0.20f, 0.5f, 0xFF3040, (0.10f + 0.35f * ex) * dim)

        // ---- back to front: strips, hair, body, then the opaque face and claws
        val tailM = copy(root); Mat4.translate(tailM, 0f, tailOffY, 0f)
        sc.drawV(tails, tailM, alpha = 1.5f * fade, dim = dim, rim = rim, wave = 2.4f + ex * 2.8f)
        sc.drawV(body, root, emis = 0.6f + 0.4f * ex, alpha = 1.5f * fade, dim = dim, rim = rim, wave = 0.8f + ex)
        val hairM = copy(root)
        Mat4.translate(hairM, 0f, 1.17f, 0f); Mat4.rotate(hairM, 3f * sin(t * 0.8f) - ex * 18f, 1f, 0f, 0f); Mat4.rotate(hairM, 4f * sin(t * 0.6f + 2f) + ex * 6f * sin(t * 12f), 0f, 0f, 1f); Mat4.translate(hairM, 0f, -1.17f + hairOffY, 0f)
        sc.drawV(hair, hairM, emis = 0.70f + 0.5f * ex, alpha = 1.4f * fade, dim = dim, rim = rim, wave = 1.6f + 2.2f * ex)
        sc.drawV(head, headM, emis = emberK, alpha = fade, dim = dim, rim = 0x10202E, spec = 0.35f)
        sc.drawV(jaw, jawM, alpha = fade, dim = dim, rim = 0x10202E, spec = 0.4f)
        for (sx in SIDES) {
            val am = copy(root)
            Mat4.translate(am, sx * shoulder[0], shoulder[1], shoulder[2])
            val flail = ex * sin(t * 7f + sx)
            Mat4.rotate(am, sx * (14f + ex * 40f + 4f * sin(t * 0.7f + sx) + flail * 8f), 0f, 1f, 0f)
            Mat4.rotate(am, sx * (ex * 36f + 3f * sin(t * 0.9f + 2f * sx) + flail * 6f), 0f, 0f, 1f)
            Mat4.rotate(am, -ex * 10f, 1f, 0f, 0f)
            Mat4.translate(am, -sx * shoulder[0], -shoulder[1], -shoulder[2])
            sc.drawV(if (sx < 0f) armL else armR, am, alpha = 1.3f * fade, dim = dim, rim = rim, spec = 0.35f)
        }

        // ---- glow sprites: a cold aura behind, the embers, a halo round the head, trailing wisps, sound rings from the mouth
        sc.glowAt(root, 0f, 0.72f, -0.03f, 1.8f + 0.4f * wail, 0x4F86E0, (0.09f + 0.06f * sin(t * 1.1f) + 0.22f * ex) * dim, 0f)
        sc.glowAt(headM, 0f, 1.12f, 0.0f, 0.5f + 0.2f * wail, 0x9CC4FF, (0.10f + 0.16f * ex) * dim, 0f)
        for (i in 0..1) sc.glowAt(headM, emberP[i * 3], emberP[i * 3 + 1], emberP[i * 3 + 2], 0.035f + 0.03f * emberK, 0xFF2030, 0.9f * dim, 0.5f)
        for (k in 0 until 3) {
            val ph = t * 0.4f + k * 2.1f
            sc.glowAt(root, sin(ph) * 0.20f, 0.10f + 0.10f * k + 0.05f * sin(ph * 1.7f), -0.02f + 0.10f * cos(ph), 0.45f, 0x5F94E8, (0.06f + 0.04f * ex) * dim, 0f)
        }
        if (ex > 0.05f) for (k in 0 until 3) {
            val tau = (t * 0.9f + k / 3f) % 1f
            sc.glowAt(headM, 0f, 1.0f - 0.05f * tau, 0.30f + 0.45f * tau, 0.25f + 1.1f * tau, 0x9CC8FF, 0.20f * ex * (1f - tau) * dim, 0f)
        }
    }
}

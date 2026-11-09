package com.tjshea.neonreaper.gl.chars

import com.tjshea.neonreaper.gl.Bones
import com.tjshea.neonreaper.gl.Mat
import com.tjshea.neonreaper.gl.Mat4
import com.tjshea.neonreaper.gl.Mesh
import com.tjshea.neonreaper.gl.MeshData
import com.tjshea.neonreaper.gl.PartBuilder
import com.tjshea.neonreaper.gl.Scene3D
import com.tjshea.neonreaper.gl.Sculpt
import com.tjshea.neonreaper.gl.Sculpt.finish
import com.tjshea.neonreaper.gl.Sculpt.fixWinding
import com.tjshea.neonreaper.gl.Sculpt.sstep
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Grim Reaper (the wild): a hunched, tattered black robe with a deep hood, an anatomical skull with burning red eyes and
 * a hinged jaw, skeletal hands and a tall scythe whose cutting edge glows toxic green.
 *
 * Rigid parts (each its own mesh): body (robe + cape + rags + belt + chain, wind-swept by the shader), hood, skull (+ eyes),
 * jaw, scythe arm (sleeve + hand + staff + blade) and the free arm.
 */
class ReaperModel : CharacterModel {
    private val neck = floatArrayOf(0f, 0.88f, 0.04f)
    private val head0 = floatArrayOf(0f, 0.985f, 0.0f)       // skull centre
    private val headScale = 0.118f
    private val shoulderR = floatArrayOf(0.255f, 0.80f, 0.0f)
    private val shoulderL = floatArrayOf(-0.255f, 0.80f, 0.0f)

    private val body: Mesh
    private val hood: Mesh
    private val skull: Mesh
    private val eyes: Mesh
    private val jaw: Mesh
    private val armScythe: Mesh
    private val armFree: Mesh
    private val blade: Mesh

    // eye positions in head units (socket floor)
    private val eyeX = 0.237f; private val eyeY = 0.075f; private val eyeZ = 0.607f

    init {
        body = buildBody(); hood = buildHood(); val sk = buildSkull(); skull = sk.first; eyes = sk.second; jaw = buildJaw()
        val a = buildArmScythe(); armScythe = a.first; blade = a.second
        armFree = buildArmFree()
    }

    // ---------------------------------------------------------------------------------------------- geometry
    private fun buildBody(): Mesh {
        val pb = PartBuilder()
        val rnd = Random(7)
        // robe: shoulders -> ragged hem, with folds, a hunched back and an uneven tattered edge
        val profile = listOf(0.0f to 0.88f, 0.12f to 0.87f, 0.225f to 0.835f, 0.285f to 0.775f, 0.275f to 0.68f, 0.25f to 0.56f,
            0.245f to 0.42f, 0.27f to 0.28f, 0.31f to 0.14f, 0.345f to 0.01f)
        val rows = profile.size
        val robe = Sculpt.shell(profile, 56,
            radial = { ang, _, t ->
                var f = 1f + (0.04f + 0.07f * t) * sin(7f * ang + 3f * t) + 0.04f * sin(3f * ang + 1f) * t
                f += 0.12f * sstep(0.45f, 0f, t) * max(0f, -sin(ang))          // hunched back (the back is -Z)
                f
            },
            dy = { ang, k, _ ->
                if (k >= rows - 2) -((if (k == rows - 1) 1f else 0.35f) * (0.02f + 0.07f + 0.12f * abs(sin(9f * ang + 0.6f)).pow(1.7f) + 0.05f * abs(sin(4f * ang)))) else 0f
            })
        pb.add(robe, 0x17171F, Mat.CLOTH, ao = 0.95f)
        // back cape: a longer, wider drape hanging from the shoulders
        val capeProfile = listOf(0.14f to 0.88f, 0.25f to 0.855f, 0.31f to 0.79f, 0.31f to 0.65f, 0.32f to 0.48f, 0.35f to 0.30f, 0.40f to 0.12f, 0.44f to -0.03f)
        val cape = Sculpt.shell(capeProfile, 40, a0 = (PI * 0.82).toFloat(), a1 = (PI * 2.18).toFloat(),
            radial = { ang, _, t -> 1f + (0.05f + 0.11f * t) * sin(6f * ang + 2f * t) },
            dy = { ang, k, _ -> if (k >= capeProfile.size - 2) -((if (k == capeProfile.size - 1) 1f else 0.3f) * (0.05f + 0.16f * abs(sin(8f * ang + 1.1f)).pow(1.5f))) else 0f })
        pb.add(cape, 0x0F0F15, Mat.CLOTH, ao = 0.85f)
        // hanging rags around the hem
        for (i in 0 until 16) {
            val a = (PI * 2 * i / 16 + rnd.nextFloat() * 0.3).toFloat()
            val r = 0.30f + rnd.nextFloat() * 0.05f
            val len = 0.13f + rnd.nextFloat() * 0.16f
            pb.add(Sculpt.rag(0.06f + rnd.nextFloat() * 0.03f, len, 0.12f), 0x0E0E14, Mat.CLOTH,
                x = r * cos(a), y = 0.09f + rnd.nextFloat() * 0.03f, z = r * sin(a), ry = 90f - Math.toDegrees(a.toDouble()).toFloat(), ao = 0.8f)
        }
        // rope belt, a small skull charm and a hanging chain
        pb.add(Sculpt.tube(40, 5, { t -> floatArrayOf(0.262f * cos((2 * PI * t).toFloat()), 0.50f, 0.262f * sin((2 * PI * t).toFloat())) }, { 0.015f }), 0x2B1E16, Mat.LEATHER, ao = 0.9f)
        pb.add(Bones.skull(14, 20), 0xBDB39C, Mat.BONE, x = 0f, y = 0.49f, z = 0.28f, sx = 0.034f, ao = 0.9f)
        for (i in 0 until 7) {
            pb.add(Sculpt.link(0.05f, 0.03f, 0.0065f), 0x5A5A66, Mat.METAL, x = 0.18f, y = 0.48f - i * 0.040f, z = 0.20f + 0.004f * i, ry = if (i % 2 == 0) 0f else 90f, ao = 1f)
        }
        // a shoulder pauldron of bone spikes on each side (menace)
        for (sx in floatArrayOf(-1f, 1f)) for (k in 0 until 3) {
            pb.add(Sculpt.spike(0.018f, 0.085f - k * 0.015f), 0xB8AE96, Mat.BONE, x = sx * (0.215f + k * 0.012f), y = 0.855f - k * 0.02f, z = -0.005f + k * 0.03f, rz = -sx * (55f + k * 12f), ao = 0.9f)
        }
        return pb.build()
    }

    private fun buildHood(): Mesh {
        val pb = PartBuilder()
        val profile = listOf(0.01f to 1.30f, 0.075f to 1.285f, 0.14f to 1.235f, 0.19f to 1.16f, 0.215f to 1.07f, 0.225f to 0.99f,
            0.23f to 0.92f, 0.25f to 0.86f, 0.28f to 0.80f)
        val open = 0.66f
        val hoodMesh = Sculpt.shell(profile, 40, a0 = (PI / 2 + open).toFloat(), a1 = (PI / 2 - open + 2 * PI).toFloat(),
            radial = { ang, _, t -> 1f + 0.05f * sin(5f * ang + 2f * t) + 0.08f * max(0f, -sin(ang)) * sstep(0.6f, 0.1f, t) },
            dy = { ang, _, t ->
                // peak swept back and the front edges drooping over the face
                0.09f * sstep(0.45f, 0f, t) * (0.5f - 0.5f * sin(ang)) - 0.025f * sstep(0.7f, 1f, t) * max(0f, sin(ang))
            })
        pb.add(hoodMesh, 0x15151C, Mat.CLOTH, z = 0.0f, ao = 0.9f)
        return pb.build()
    }

    private fun buildSkull(): Pair<Mesh, Mesh> {
        val pb = PartBuilder()
        pb.add(Bones.skull(), 0xB9AE95, Mat.BONE, x = head0[0], y = head0[1], z = head0[2], sx = headScale, ao = 1f)
        pb.add(Bones.upperTeeth(), 0xCFC5AA, Mat.BONE, x = head0[0], y = head0[1], z = head0[2], sx = headScale, ao = 1f)
        val eb = PartBuilder()
        for (sx in floatArrayOf(-1f, 1f)) {
            eb.add(Sculpt.ellipsoid(0.085f, 0.085f, 0.085f, 8, 10), 0xFF2A3A, Mat.EMISSIVE,
                x = head0[0] + sx * eyeX * headScale * 0.98f, y = head0[1] + eyeY * headScale, z = head0[2] + eyeZ * headScale, sx = headScale, ao = 1f)
        }
        return pb.build() to eb.build()
    }

    private fun buildJaw(): Mesh {
        val pb = PartBuilder()
        pb.add(Bones.jaw(), 0xB3A88F, Mat.BONE, sx = headScale, ao = 0.95f)
        return pb.build()
    }

    private fun sleeve(a: FloatArray, b: FloatArray, c: FloatArray, r0: Float, r1: Float, r2: Float): MeshData {
        // two-segment sleeve through shoulder a, elbow b, wrist c, flaring into a wide cuff
        return Sculpt.tube(16, 10, { t ->
            if (t < 0.5f) { val u = t * 2f; floatArrayOf(a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u) }
            else { val u = (t - 0.5f) * 2f; floatArrayOf(b[0] + (c[0] - b[0]) * u, b[1] + (c[1] - b[1]) * u, b[2] + (c[2] - b[2]) * u) }
        }, { t -> if (t < 0.5f) r0 + (r1 - r0) * (t * 2f) else r1 + (r2 - r1) * sstep(0.5f, 1f, t) * (0.5f + 0.5f * sstep(0.75f, 1f, t)) }, cap = true)
    }

    private fun buildArmScythe(): Pair<Mesh, Mesh> {
        val pb = PartBuilder()
        val sh = shoulderR; val el = floatArrayOf(0.34f, 0.62f, 0.09f); val wr = floatArrayOf(0.335f, 0.50f, 0.245f)
        pb.add(sleeve(sh, el, wr, 0.08f, 0.066f, 0.098f), 0x14141B, Mat.CLOTH, ao = 0.9f)
        for (i in 0 until 4) pb.add(Sculpt.rag(0.045f, 0.10f + 0.02f * i, 0.1f), 0x0E0E14, Mat.CLOTH, x = 0.335f + (i - 1.5f) * 0.03f, y = 0.50f, z = 0.255f, ry = (i - 1.5f) * 20f, ao = 0.8f)
        // bony forearm and the gripping hand
        pb.add(Sculpt.limb(floatArrayOf(0.335f, 0.50f, 0.245f), floatArrayOf(0.335f, 0.465f, 0.262f), 0.017f, 0.014f, 0.1f, 6), 0xCFC5AB, Mat.BONE, ao = 0.9f)
        pb.add(Bones.hand(1f), 0xD5CBB3, Mat.BONE, x = 0.335f, y = 0.47f, z = 0.265f, rx = 0f, ry = 90f, rz = 0f, sx = 1.5f, ao = 0.9f)
        // staff, bindings, bottom spike, blade socket
        val staff = Sculpt.tube(24, 8, { t -> floatArrayOf(0.335f + 0.012f * t, -0.07f + 1.58f * t, 0.265f - 0.02f * t * t) }, { t -> 0.0165f - 0.002f * t }, cap = true)
        pb.add(staff, 0x4A3320, Mat.WOOD, ao = 0.95f)
        for (y in floatArrayOf(0.40f, 0.43f, 0.60f, 0.63f, 1.38f, 1.41f)) pb.add(Sculpt.tube(12, 6, { t -> floatArrayOf(0.337f + 0.0166f * cos((2 * PI * t).toFloat()), y, 0.265f + 0.0166f * sin((2 * PI * t).toFloat())) }, { 0.0045f }), 0x2A1A14, Mat.LEATHER, ao = 0.9f)
        pb.add(Sculpt.spike(0.02f, 0.09f), 0x6A6A74, Mat.METAL, x = 0.335f, y = -0.04f, z = 0.265f, rx = 180f, ao = 1f)
        pb.add(Sculpt.tube(10, 8, { t -> floatArrayOf(0.349f, 1.475f + 0.06f * t, 0.245f) }, { 0.028f }, cap = true), 0x55555F, Mat.METAL, ao = 1f)
        pb.add(Bones.skull(14, 20), 0xBDB39C, Mat.BONE, x = 0.349f, y = 1.425f, z = 0.285f, sx = 0.036f, ao = 0.95f)

        // blade: a curved crescent with a diamond cross-section; the cutting edge (inner curve) glows
        val bp = PartBuilder()
        bp.data.append(crescent(), Mat4.identity())
        val edge = Sculpt.tube(40, 5, { t -> bladePoint(t, -1f) }, { t -> 0.0085f * (1f - 0.7f * t) })
        val edgePb = PartBuilder()
        edgePb.add(edge, 0x9CFF57, Mat.EMISSIVE, ao = 1f)
        pb.data.append(bp.data, Mat4.identity())
        val bladeMesh = edgePb.build()
        return pb.build() to bladeMesh
    }

    /** Centre line of the blade (x0,y0 = the staff top), `side` > 0 = back edge, < 0 = cutting edge, z fixed. */
    private fun bladePoint(t: Float, side: Float): FloatArray {
        val x0 = 0.349f; val y0 = 1.51f
        val cx = x0 - 0.80f * t + 0.05f * sin(PI.toFloat() * t)
        val cy = y0 + 0.17f * sin(PI.toFloat() * t * 0.92f) - 0.13f * t * t * t
        // normal of the centre line (2D), used to offset the edges
        val e = 0.01f
        val t2 = (t + e).coerceAtMost(1f); val t1 = (t - e).coerceAtLeast(0f)
        val dx = (x0 - 0.80f * t2 + 0.05f * sin(PI.toFloat() * t2)) - (x0 - 0.80f * t1 + 0.05f * sin(PI.toFloat() * t1))
        val dy = (y0 + 0.17f * sin(PI.toFloat() * t2 * 0.92f) - 0.13f * t2 * t2 * t2) - (y0 + 0.17f * sin(PI.toFloat() * t1 * 0.92f) - 0.13f * t1 * t1 * t1)
        val l = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6f)
        val nx = -dy / l; val ny = dx / l                         // left-hand normal of the travel direction (points up/outwards on this arc)
        val width = 0.105f * (1f - t).pow(0.75f) + 0.004f
        val off = if (side > 0) width * 0.35f else -width * 0.65f
        return floatArrayOf(cx + nx * off, cy + ny * off, 0.245f)
    }

    private fun crescent(): MeshData {
        val m = MeshData()
        val n = 36
        val th = 0.012f
        val rows = ArrayList<IntArray>()
        for (i in 0..n) {
            val t = i.toFloat() / n
            val back = bladePoint(t, 1f); val cut = bladePoint(t, -1f)
            val mid = floatArrayOf((back[0] * 0.6f + cut[0] * 0.4f), (back[1] * 0.6f + cut[1] * 0.4f), back[2])
            val thick = th * (1f - 0.8f * t)
            // 4 vertices per section: back edge, ridge top, ridge bottom, cutting edge (separate for crisp bevels)
            val vb = m.vertex(back[0], back[1], back[2], 0f, 1f, 0f, t, 0f)
            val vt = m.vertex(mid[0], mid[1], mid[2] + thick, 0f, 0f, 1f, t, 0.5f)
            val vm = m.vertex(mid[0], mid[1], mid[2] - thick, 0f, 0f, -1f, t, 0.5f)
            val vc = m.vertex(cut[0], cut[1], cut[2], 0f, -1f, 0f, t, 1f)
            rows += intArrayOf(vb, vt, vm, vc)
        }
        for (i in 0 until n) {
            val a = rows[i]; val b = rows[i + 1]
            // back edge -> ridge -> cutting edge on both faces (vertices are not shared between faces: crisp bevels)
            m.quad(a[0], b[0], b[1], a[1]); m.quad(a[1], b[1], b[3], a[3])
            m.quad(a[0], a[2], b[2], b[0]); m.quad(a[2], a[3], b[3], b[2])
        }
        m.computeNormals(); m.fixWinding()
        // tint: steel; applied by re-appending with a colour and material
        val out = MeshData(); out.append(m, Mat4.identity(), 0xA9B4B8, Mat.METAL, 1f)
        return out
    }

    private fun buildArmFree(): Mesh {
        val pb = PartBuilder()
        val sh = shoulderL; val el = floatArrayOf(-0.31f, 0.60f, 0.12f); val wr = floatArrayOf(-0.22f, 0.50f, 0.30f)
        pb.add(sleeve(sh, el, wr, 0.08f, 0.066f, 0.098f), 0x14141B, Mat.CLOTH, ao = 0.9f)
        for (i in 0 until 4) pb.add(Sculpt.rag(0.045f, 0.10f + 0.02f * i, 0.1f), 0x0E0E14, Mat.CLOTH, x = -0.22f + (i - 1.5f) * 0.03f, y = 0.50f, z = 0.28f, ry = (i - 1.5f) * 20f, ao = 0.8f)
        pb.add(Sculpt.limb(floatArrayOf(-0.22f, 0.50f, 0.27f), floatArrayOf(-0.20f, 0.47f, 0.31f), 0.017f, 0.014f, 0.1f, 6), 0xCFC5AB, Mat.BONE, ao = 0.9f)
        pb.add(Bones.hand(0.42f), 0xD5CBB3, Mat.BONE, x = -0.20f, y = 0.47f, z = 0.31f, rx = -25f, ry = -20f, rz = -8f, sx = 1.55f, ao = 0.9f)
        return pb.build()
    }

    // ---------------------------------------------------------------------------------------------- animation
    private val tmp = FloatArray(16)
    private fun copy(m: FloatArray) = m.copyOf()

    override fun draw(sc: Scene3D, mm: FloatArray, t: Float, ex: Float, dim: Float) {
        val rim = 0x14263A
        val sway = sin(t * 0.62f) * 1.6f + ex * sin(t * 7f) * 2.2f
        val root = copy(mm); Mat4.rotate(root, sway, 0f, 0f, 1f); Mat4.rotate(root, ex * 7f, 1f, 0f, 0f)
        val breathe = 1f + 0.012f * sin(t * 1.3f)
        Mat4.translate(root, 0f, 0f, 0f)

        // head: slow look-around, snaps toward the camera when excited
        val yaw = sin(t * 0.43f) * 20f * (1f - ex) + ex * sin(t * 5.5f) * 6f
        val pitch = -3f + sin(t * 0.9f) * 2f - ex * 10f
        val head = copy(root)
        Mat4.translate(head, neck[0], neck[1], neck[2]); Mat4.rotate(head, yaw, 0f, 1f, 0f); Mat4.rotate(head, pitch, 1f, 0f, 0f); Mat4.translate(head, -neck[0], -neck[1] * breathe, -neck[2])
        // the burning eyes light the bone around them (an ember glow that makes the face readable inside the dark hood);
        // lights must be added BEFORE the meshes they should light are drawn
        sc.beginLocalLights()
        sc.pointLightAt(head, head0[0], head0[1] + 0.02f, head0[2] + 0.30f, 0.80f, 0xFF4A38, (0.22f + 0.7f * ex) * dim)
        sc.drawV(body, root, dim = dim, rim = rim, spec = 0.2f, wave = 0.9f + ex * 1.2f)
        sc.drawV(hood, head, dim = dim, rim = rim, spec = 0.15f)
        sc.drawV(skull, head, dim = dim, rim = 0x10161C, spec = 0.45f)
        val blink = if (!(ex > 0.2f) && ((t * 0.21f) % 1f) < 0.025f) 0f else 1f
        val eyeK = (1.0f + 0.15f * sin(t * 2.3f) + ex * 1.6f + 0.3f * ex * sin(t * 19f)) * blink
        sc.drawV(eyes, head, emis = eyeK * 1.4f, dim = dim)
        // jaw hangs open when excited
        val open = ex * (14f + 22f * abs(sin(t * 13f))) + 1.5f
        val jawM = copy(head)
        Mat4.translate(jawM, head0[0] + Bones.JAW_HINGE[0] * headScale, head0[1] + Bones.JAW_HINGE[1] * headScale, head0[2] + Bones.JAW_HINGE[2] * headScale)
        Mat4.rotate(jawM, open, 1f, 0f, 0f)
        sc.drawV(jaw, jawM, dim = dim, rim = 0x10161C, spec = 0.4f)
        if (eyeK > 0.05f) for (sx in floatArrayOf(-1f, 1f)) {
            val ex0 = head0[0] + sx * eyeX * headScale; val ey0 = head0[1] + eyeY * headScale; val ez0 = head0[2] + (eyeZ + 0.28f) * headScale
            sc.glowAt(head, ex0, ey0, ez0, 0.035f * eyeK + 0.01f, 0xFFFFFF, 0.9f * dim, 1f)
            sc.glowAt(head, ex0, ey0, ez0, 0.11f * eyeK + 0.03f, 0xFF2030, 0.8f * dim, 0.2f)
        }

        // arms and the scythe
        val armR = copy(root)
        Mat4.translate(armR, shoulderR[0], shoulderR[1], shoulderR[2])
        Mat4.rotate(armR, sin(t * 0.9f) * 2f + ex * (-18f + sin(t * 6f) * 12f), 0f, 0f, 1f)
        Mat4.rotate(armR, ex * -10f, 1f, 0f, 0f)
        Mat4.translate(armR, -shoulderR[0], -shoulderR[1], -shoulderR[2])
        sc.drawV(armScythe, armR, dim = dim, rim = rim, spec = 0.3f)
        sc.drawV(blade, armR, emis = 1.2f + 0.8f * ex, dim = dim)
        // bloom sprites along the glowing edge
        for (k in 0..5) {
            val tt = 0.08f + k * 0.16f
            val p = bladePointPublic(tt)
            sc.glowAt(armR, p[0], p[1], p[2], 0.16f + 0.08f * ex, 0x9CFF57, (0.28f + 0.2f * ex) * dim, 0.1f)
        }
        val armL = copy(root)
        Mat4.translate(armL, shoulderL[0], shoulderL[1], shoulderL[2])
        Mat4.rotate(armL, ex * (15f + sin(t * 7f) * 10f) + sin(t * 0.8f + 1f) * 3f, 0f, 0f, 1f)
        Mat4.translate(armL, -shoulderL[0], -shoulderL[1], -shoulderL[2])
        sc.drawV(armFree, armL, dim = dim, rim = rim, spec = 0.3f)
    }

    private fun bladePointPublic(t: Float): FloatArray = bladePoint(t, -1f)
}

package com.tjshea.neonreaper.gl

import com.tjshea.neonreaper.gl.Sculpt.gauss
import com.tjshea.neonreaper.gl.Sculpt.sstep
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Anatomy kit: a skull with real geometric sockets, brow ridge, cheekbones and nasal cavity, a hinged jaw with teeth and a
 * skeletal hand. All meshes are in HEAD UNITS (cranium radius ~1, +Z = face, +Y = up) and carry no vertex colour: tint them
 * with [PartBuilder.add] (material [Mat.BONE]).
 */
object Bones {
    /** Hinge of the jaw in head units (the jaw mesh is built around this point). */
    val JAW_HINGE = floatArrayOf(0f, -0.30f, 0.02f)

    /** One eye socket: two overlapping dents (an outer-upper and an inner-lower one) give a slanted, angry orbit. */
    private fun orbit(dx: Float, dy: Float, dz: Float, sx: Float): Float {
        fun g(cx: Float, cy: Float, cz: Float): Float {
            val d2 = 0.50f * (dx - cx * sx) * (dx - cx * sx) + 1.15f * (dy - cy) * (dy - cy) + 0.32f * (dz - cz) * (dz - cz)
            return gauss(d2, 0.15f)
        }
        return g(0.50f, 0.17f, 0.84f) + g(0.31f, 0.06f, 0.92f) * 0.9f
    }

    /** Radial profile of the cranium + upper face (maxilla): sockets, nose, brow, cheekbones, temples. */
    fun skullShape(dx: Float, dy: Float, dz: Float): Float {
        var r = 1f
        val front = sstep(-0.1f, 0.55f, dz)
        // the face narrows hard towards the mouth: a muzzle below the cheekbones
        val low = sstep(-0.05f, -0.85f, dy)
        r *= 1f - 0.30f * low * front
        r *= 1f - 0.10f * sstep(-0.2f, -1f, dy)                    // flatter underside
        // eye orbits (deep), nasal aperture, heavy brow, high cheekbones, temple hollows
        r -= 0.50f * (orbit(dx, dy, dz, -1f) + orbit(dx, dy, dz, 1f))
        val nasal = gauss(2.8f * dx * dx + 0.45f * (dy + 0.30f) * (dy + 0.30f) + 0.35f * (dz - 0.93f) * (dz - 0.93f), 0.15f)
        r -= 0.36f * nasal
        r += 0.085f * gauss((dy - 0.40f) * (dy - 0.40f), 0.10f) * sstep(0.15f, 0.8f, dz) * sstep(0.98f, 0.35f, abs(dx))
        for (sx in floatArrayOf(-1f, 1f)) {
            val c = gauss((dx - 0.66f * sx) * (dx - 0.66f * sx) + (dy + 0.22f) * (dy + 0.22f) + 0.4f * (dz - 0.64f) * (dz - 0.64f), 0.19f)
            r += 0.15f * c
            r -= 0.07f * gauss((dx - 0.97f * sx) * (dx - 0.97f * sx) + (dy - 0.10f) * (dy - 0.10f) + (dz - 0.18f) * (dz - 0.18f), 0.22f)
        }
        // sagittal crest, brow to forehead slope
        r += 0.035f * gauss(dx * dx, 0.12f) * sstep(0.1f, 0.8f, dy)
        return r
    }

    /** Cranium + upper face. Roughly 0.95 wide, 1.0 tall, 1.08 deep. */
    fun skull(lat: Int = 34, lon: Int = 52): MeshData =
        Sculpt.blob(lat, lon, 0.86f, 1.08f, 1.06f, ao = { r, dy -> (sstep(0.52f, 0.97f, r) * (0.72f + 0.28f * sstep(-1f, 0.3f, dy))).coerceIn(0.04f, 1f) }, shape = ::skullShape)

    /** Lower jaw (mandible): a deep angular U plate with rising rami and fangs, built around [JAW_HINGE]. */
    fun jaw(): MeshData {
        val h = JAW_HINGE
        val pts = listOf(
            floatArrayOf(-0.60f, -0.02f, 0.02f), floatArrayOf(-0.58f, -0.46f, 0.10f), floatArrayOf(-0.46f, -0.80f, 0.42f),
            floatArrayOf(-0.24f, -0.98f, 0.74f), floatArrayOf(0f, -1.04f, 0.84f),
            floatArrayOf(0.24f, -0.98f, 0.74f), floatArrayOf(0.46f, -0.80f, 0.42f), floatArrayOf(0.58f, -0.46f, 0.10f), floatArrayOf(0.60f, -0.02f, 0.02f))
        fun at(t: Float): FloatArray {
            val f = t * (pts.size - 1); val i = f.toInt().coerceAtMost(pts.size - 2); val u = f - i
            val s = (1f - cos(u * PI.toFloat())) * 0.5f
            val a = pts[i]; val b = pts[i + 1]
            return floatArrayOf(a[0] + (b[0] - a[0]) * s - h[0], (a[1] + (b[1] - a[1]) * s - h[1]), a[2] + (b[2] - a[2]) * s - h[2])
        }
        val m = Sculpt.tube(44, 8, ::at, { t -> val c = abs(t - 0.5f) * 2f; 0.075f + 0.045f * (1f - c) * (1f - c) - 0.02f * sstep(0.7f, 1f, c) }, cap = true)
        val pb = PartBuilder()
        pb.data.append(m, Mat4.identity())
        // teeth along the upper edge of the jaw, with two long fangs
        for (i in -4..4) {
            val x = i * 0.105f
            val z = 0.80f - 0.30f * (abs(x) / 0.55f) * (abs(x) / 0.55f)
            val xz = if (abs(x) > 0.46f) 0.55f - (abs(x) - 0.46f) * 1.3f else z
            val fang = if (abs(i) == 3) 1.7f else 1f
            pb.add(tooth(0.036f, 0.062f * fang, 0.03f), 0xD9CEB2, Mat.BONE, x = x - h[0], y = -0.70f - h[1] + (fang - 1f) * 0.05f, z = xz - h[2], rz = i * -1.5f, ao = 0.9f)
        }
        return pb.data
    }

    /** Upper teeth (attached to the skull mesh's maxilla); the canines are long fangs. */
    fun upperTeeth(): MeshData {
        val pb = PartBuilder()
        for (i in -4..4) {
            val x = i * 0.1f
            val z = 0.93f - 0.40f * (abs(x) / 0.5f) * (abs(x) / 0.5f)
            val fang = if (abs(i) == 3) 1.9f else 1f
            pb.add(tooth(0.036f, 0.066f * fang, 0.03f), 0xDCD2B6, Mat.BONE, x = x, y = -0.64f - (fang - 1f) * 0.06f, z = z - 0.05f, rz = i * 1.5f, ao = 0.85f)
        }
        return pb.data
    }

    /** A slightly tapered, rounded tooth centred on the origin. */
    fun tooth(rx: Float, ry: Float, rz: Float): MeshData =
        Sculpt.blob(7, 10, rx, ry, rz, null) { _, dy, _ -> 1f - 0.18f * sstep(-0.2f, -1f, dy) }

    /**
     * A skeletal hand (palm + four fingers + thumb) in HAND UNITS: ~0.10 wide, fingers pointing -Y, palm facing +Z.
     * [curl] 0 = flat, 1 = tight fist (grip around a staff).
     */
    fun hand(curl: Float): MeshData {
        val pb = PartBuilder()
        val bone = 0xD9CFB8
        pb.add(Sculpt.ellipsoid(0.048f, 0.052f, 0.016f), bone, Mat.BONE, ao = 0.9f)
        for (f in 0 until 4) {
            val x = -0.036f + f * 0.024f
            val len = floatArrayOf(0.046f, 0.052f, 0.048f, 0.038f)[f]
            var y = -0.046f; var z = 0f
            var ang = 0f
            for (seg in 0 until 3) {
                val l = len * (1f - 0.16f * seg)
                ang += curl * (28f + 22f * seg)
                val a = Math.toRadians(ang.toDouble()).toFloat()
                val ny = y - l * cos(a); val nz = z + l * sin(a)
                pb.add(Sculpt.limb(floatArrayOf(x, y, z), floatArrayOf(x, ny, nz), 0.0085f - 0.0012f * seg, 0.0078f - 0.0012f * seg, 0.18f, 6), bone, Mat.BONE, ao = 0.9f)
                pb.add(Sculpt.ellipsoid(0.0105f, 0.0105f, 0.0105f, 6, 8), bone, Mat.BONE, x = x, y = y, z = z, ao = 0.85f)
                y = ny; z = nz
            }
        }
        // thumb
        var ty = -0.012f; var tz = 0.012f; val tx = 0.050f; var ta = -20f
        for (seg in 0 until 2) {
            ta += curl * 40f
            val a = Math.toRadians(ta.toDouble()).toFloat()
            val ny = ty - 0.034f * cos(a); val nz = tz + 0.034f * sin(a) + 0.01f
            pb.add(Sculpt.limb(floatArrayOf(tx, ty, tz), floatArrayOf(tx + 0.012f, ny, nz), 0.01f, 0.008f, 0.15f, 6), bone, Mat.BONE, ao = 0.9f)
            ty = ny; tz = nz
        }
        return pb.data
    }
}

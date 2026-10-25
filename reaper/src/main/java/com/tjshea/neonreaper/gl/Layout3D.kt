package com.tjshea.neonreaper.gl

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Camera + reel geometry shared by the 3D renderer and the 2D HUD. The camera is
 * placed so the front of the reel drums exactly fills the HUD's reel window
 * rectangle (winL..winR × winT..winB in pixels), letting the HUD draw win lines and
 * labels precisely over 3D cells.
 */
class Layout3D(val vw: Float, val vh: Float, winL: Float, winT: Float, winR: Float, winB: Float) {
    companion object {
        const val REEL_W = 1.0f
        const val GAP = 0.1f
        const val R = 1.62f                       // drum radius
        val STEP = Math.toRadians(34.0).toFloat() // angle between symbol faces
        const val FOV = 34f
        val WORLD_W = 5 * REEL_W + 4 * GAP
        fun reelX(r: Int) = (r - 2) * (REEL_W + GAP)
        /** Angle of a (fractional) row position: row 1 faces the camera, row 0 is above. */
        fun rowAngle(row: Float) = -(row - 1f) * STEP
    }

    val aspect = vw / vh
    private val tanH = tan(Math.toRadians(FOV / 2.0)).toFloat()
    val dist: Float = WORLD_W * vw / (2 * tanH * aspect * (winR - winL))
    val camX: Float
    val camY: Float
    val camZ: Float = R + dist
    val proj = Mat4.perspective(FloatArray(16), FOV, aspect, 0.1f, 120f)
    val view = FloatArray(16)
    val viewProj = FloatArray(16)
    private val tmp = FloatArray(4)

    init {
        val ndcX = ((winL + winR) / 2) / vw * 2 - 1
        val ndcY = 1 - ((winT + winB) / 2) / vh * 2
        camX = -ndcX * dist * tanH * aspect
        camY = -ndcY * dist * tanH
        Mat4.lookAt(view, camX, camY, camZ, camX, camY, camZ - 1)
        Mat4.mul(viewProj, proj, view)
    }

    /** World → screen pixels; returns false when behind the camera. Result in out[0], out[1]. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        Mat4.transform(tmp, viewProj, x, y, z)
        if (tmp[3] <= 0f) return false
        out[0] = (tmp[0] / tmp[3] * 0.5f + 0.5f) * vw
        out[1] = (0.5f - tmp[1] / tmp[3] * 0.5f) * vh
        return true
    }

    /** Screen point → world point on the plane z = [z]. */
    fun unproject(sx: Float, sy: Float, z: Float, out: FloatArray) {
        val d = camZ - z
        out[0] = camX + ((sx / vw) * 2 - 1) * d * tanH * aspect
        out[1] = camY + (1 - (sy / vh) * 2) * d * tanH
        out[2] = z
    }

    private val p = FloatArray(2)

    /** Screen Y of the centre of a reel face at fractional [row]. */
    fun rowY(row: Float): Float {
        val a = rowAngle(row)
        project(0f, R * sin(a), R * cos(a), p); return p[1]
    }

    /** Screen X of reel [r]'s centre (front of the drum). */
    fun reelScreenX(r: Int): Float { project(reelX(r), 0f, R, p); return p[0] }

    /** On-screen height of a face at [row] relative to the front face. */
    fun rowScale(row: Float): Float {
        val a = rowAngle(row); val h = STEP / 2
        project(0f, R * sin(a + h), R * cos(a + h), p); val top = p[1]
        project(0f, R * sin(a - h), R * cos(a - h), p); val bot = p[1]
        project(0f, R * sin(h), R * cos(h), p); val t0 = p[1]
        project(0f, -R * sin(h), R * cos(h), p); val b0 = p[1]
        return (bot - top) / (b0 - t0)
    }

    /** Screen-space size of one cell at the front (pixels). */
    fun cellPx(): Float { project(-REEL_W / 2, 0f, R, p); val a = p[0]; project(REEL_W / 2, 0f, R, p); return p[0] - a }
}

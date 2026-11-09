package com.tjshea.neonreaper.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * The Cursed Skull: a huge cracked, yellowed skull bound in iron chains and padlocked across the brow, toxic green fire
 * burning in the sockets, green light leaking out of the cracks.
 */
internal object SkullChar {
    private val GREEN = Portraits.GREEN
    private val OPT = Portraits.SkullOpt(0xFFDCD0AC.toInt(), GREEN, 0xFF54421A.toInt(), 5, 8)

    private const val U = 0.76f; private const val X0 = 0.12f; private const val Y0 = 0.085f
    /** Socket centres (unit) for the live flames, then jaw hinge. */
    val SOCKETS = floatArrayOf(X0 + U * 0.27f, Y0 + U * 0.51f, X0 + U * 0.73f, Y0 + U * 0.51f)

    fun paint(c: Canvas, s: Float, live: Boolean) {
        Kit.aura(c, s * 0.5f, s * 0.5f, s * 0.5f, GREEN, 0.34f)
        Kit.aura(c, s * 0.5f, s * 0.40f, s * 0.30f, 0xFF5EC81E.toInt(), 0.20f)
        Kit.groundShadow(c, s * 0.5f, s * 0.955f, s * 0.34f, s * 0.03f, 0.7f)
        val u = s * U; val x = s * X0; val y = s * Y0
        // chains hang from the brow band down both sides (behind the skull's outline)
        hang(c, s, x + u * 0.03f, y + u * 0.40f, -1f); hang(c, s, x + u * 0.97f, y + u * 0.40f, 1f)
        Portraits.skullUpper(c, x, y, u, OPT, !live)
        if (!live) {
            fire(c, s, 0)
            Portraits.skullJaw(c, x, y, u, OPT)
        }
        // glowing cracks
        val gl = GREEN
        Kit.crack(c, 41, x + u * 0.30f, y + u * 0.02f, x + u * 0.36f, y + u * 0.40f, u * 0.012f, gl, 0.08f, 2)
        Kit.crack(c, 43, x + u * 0.80f, y + u * 0.18f, x + u * 0.90f, y + u * 0.46f, u * 0.010f, gl, 0.08f, 1)
        Kit.crack(c, 47, x + u * 0.50f, y + u * 0.70f, x + u * 0.46f, y + u * 0.60f, u * 0.008f, 0, 0.1f, 0)
        brow(c, s, x, y, u)
    }

    /** Lower jaw sprite at its rest position. */
    fun jaw(c: Canvas, s: Float) = Portraits.skullJaw(c, s * X0, s * Y0, s * U, OPT)

    /** Green fire licking up out of the sockets (static version; [Characters] animates its own sprites). */
    fun fire(c: Canvas, s: Float, which: Int) {
        for (i in 0..1) {
            val cx = s * SOCKETS[i * 2]; val cy = s * SOCKETS[i * 2 + 1]
            Art.flame(c, cx, cy + s * 0.02f, s * 0.15f, GREEN, if (i == 0) -0.4f else 0.4f)
        }
    }

    private fun brow(c: Canvas, s: Float, x: Float, y: Float, u: Float) {
        // chain across the forehead with a padlock in the middle
        val n = 17; val pts = FloatArray(2 * n)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            val px = x + u * (0.0f + 1.0f * t)
            val py = y + u * (0.40f * (1 - t) * (1 - t) + 2f * t * (1 - t) * -0.02f + 0.40f * t * t)
            pts[i * 2] = px; pts[i * 2 + 1] = py
        }
        val sh = FloatArray(pts.size); for (i in pts.indices step 2) { sh[i] = pts[i] + s * 0.006f; sh[i + 1] = pts[i + 1] + s * 0.012f }
        Kit.chain(c, sh, s * 0.05f, 0xFF000000.toInt())
        Kit.chain(c, pts, s * 0.05f, 0xFF7B7680.toInt())
        // padlock
        val mx = x + u * 0.5f; val my = y + u * 0.205f; val w = u * 0.15f; val h = u * 0.13f
        val shackle = Path(); shackle.moveTo(mx - w * 0.3f, my); shackle.cubicTo(mx - w * 0.3f, my - h * 0.9f, mx + w * 0.3f, my - h * 0.9f, mx + w * 0.3f, my)
        c.drawPath(shackle, Kit.stroke(0xFF0A0A0C.toInt(), s * 0.022f, Paint.Cap.BUTT)); c.drawPath(shackle, Kit.stroke(0xFF8C8794.toInt(), s * 0.012f, Paint.Cap.BUTT))
        val body = RectF(mx - w / 2, my, mx + w / 2, my + h)
        c.drawRoundRect(RectF(body.left + s * 0.005f, body.top + s * 0.008f, body.right + s * 0.005f, body.bottom + s * 0.008f), s * 0.014f, s * 0.014f, Kit.fill(0xAA000000.toInt()))
        val bp = Kit.fill(); bp.shader = Kit.metal(body.left, body.top, body.right, body.bottom, 0xFF6A6672.toInt(), 0.55f, 0.28f)
        c.drawRoundRect(body, s * 0.014f, s * 0.014f, bp)
        c.drawRoundRect(body, s * 0.014f, s * 0.014f, Kit.stroke(0xFF050506.toInt(), s * 0.006f))
        Kit.halo(c, mx, my + h * 0.5f, s * 0.05f, GREEN, 0.7f)
        c.drawCircle(mx, my + h * 0.42f, s * 0.011f, Kit.fill(GREEN)); c.drawRect(mx - s * 0.004f, my + h * 0.42f, mx + s * 0.004f, my + h * 0.72f, Kit.fill(GREEN))
    }

    private fun hang(c: Canvas, s: Float, x0: Float, y0: Float, dir: Float) {
        val n = 11; val pts = FloatArray(2 * n)
        for (i in 0 until n) {
            val t = i / (n - 1f)
            pts[i * 2] = x0 + dir * s * (0.025f + 0.06f * kotlin.math.sin(t * 3.0f))
            pts[i * 2 + 1] = y0 + s * 0.40f * t
        }
        Kit.chain(c, pts, s * 0.05f, 0xFF6A6672.toInt())
        // spike hook at the end
        val ex = pts[(n - 1) * 2]; val ey = pts[(n - 1) * 2 + 1]
        Kit.thorn(c, ex, ey, 1.5708f + dir * 0.4f, s * 0.07f, s * 0.035f, 0xFF4A4650.toInt(), 0xFFB8B2C0.toInt())
    }
}

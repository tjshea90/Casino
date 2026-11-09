package com.tjshea.neonreaper.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * The Wraith (a wailing banshee): a tattered pale-blue shroud that dissolves into mist, a hollow gaunt face in a hood of
 * darkness with teal fire in its sockets and a screaming black mouth, long bony arms with clawed hands raised.
 */
internal object Wraith {
    private val TEAL = 0xFF5FE6E0.toInt()
    private val PALE = 0xFFC9D5EA.toInt()
    /** Eyes (unit): x1,y1,x2,y2,radius; mouth centre (unit) and size. */
    val EYES = floatArrayOf(0.455f, 0.292f, 0.545f, 0.292f, 0.016f)
    val MOUTH = floatArrayOf(0.5f, 0.385f, 0.036f, 0.056f)

    fun paint(c: Canvas, s: Float, live: Boolean) {
        Kit.aura(c, s * 0.5f, s * 0.48f, s * 0.5f, 0xFF4FA8D8.toInt(), 0.30f)
        Kit.aura(c, s * 0.5f, s * 0.32f, s * 0.30f, 0xFF8B5FBF.toInt(), 0.26f)
        streamers(c, s)
        arms(c, s)
        robe(c, s)
        face(c, s, live)
        Kit.mist(c, s * 0.04f, s * 0.96f, s * 0.95f, s * 0.12f, 0xFFBFD6F0.toInt(), 0.30f, 21, 8)
    }

    /** Wind-torn ribbons streaming behind the shroud. */
    private fun streamers(c: Canvas, s: Float) {
        val r = java.util.Random(4)
        for (i in 0 until 7) {
            val side = if (i % 2 == 0) -1f else 1f
            val x0 = s * (0.5f + side * (0.10f + r.nextFloat() * 0.12f)); val y0 = s * (0.40f + r.nextFloat() * 0.15f)
            val x1 = s * (0.5f + side * (0.36f + r.nextFloat() * 0.12f)); val y1 = s * (0.70f + r.nextFloat() * 0.25f)
            val p = Path(); p.moveTo(x0, y0)
            p.cubicTo(x0 + side * s * 0.20f, y0 + s * 0.02f, x1, y1 - s * 0.2f, x1, y1)
            p.cubicTo(x1 - side * s * 0.05f, y1 - s * 0.18f, x0 + side * s * 0.05f, y0 + s * 0.20f, x0 - side * s * 0.03f, y0 + s * 0.06f)
            p.close()
            val b = Kit.bounds(p)
            val fp = Kit.fill(); fp.shader = LinearGradient(0f, b.top, 0f, b.bottom, intArrayOf(Kit.al(0xFF8FA4C8.toInt(), 0.55f), Kit.al(0xFF5A6C98.toInt(), 0.30f), 0x00405070), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(p, fp)
        }
    }

    private fun robe(c: Canvas, s: Float) {
        val robe = Kit.path(s, "M.5,.07 C.40,.09 .32,.17 .30,.28 C.28,.38 .22,.44 .17,.52 C.10,.64 .08,.78 .04,.95 C.12,.90 .16,.97 .22,.92 C.28,.98 .34,.93 .40,.99 " +
            "C.46,.94 .54,.99 .60,.93 C.66,.98 .72,.92 .78,.97 C.84,.90 .88,.97 .96,.95 C.92,.78 .90,.64 .83,.52 C.78,.44 .72,.38 .70,.28 C.68,.17 .60,.09 .50,.07 Z")
        val rb = Kit.bounds(robe)
        val p = Kit.fill()
        p.shader = LinearGradient(0f, rb.top, 0f, rb.bottom, intArrayOf(0xFFD6E0F2.toInt(), 0xFF9AA8C8.toInt(), 0xCC5E6E98.toInt(), 0x00405070), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(robe, p)
        p.shader = LinearGradient(rb.left, 0f, rb.right, 0f, intArrayOf(0x44FFFFFF, 0x00000000, 0x66101830), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(robe, p); p.shader = null
        c.save(); c.clipPath(robe)
        // folds that flow upward and sideways as if in a gale
        val fold = Kit.stroke(0x4A18204A, s * 0.03f); val hi = Kit.stroke(0x40FFFFFF, s * 0.007f)
        val q = Path()
        for (i in 0 until 8) {
            val x = 0.16f + i * 0.095f
            q.reset(); q.moveTo(s * (0.5f + (x - 0.5f) * 0.5f), s * 0.46f); q.quadTo(s * (x + 0.03f), s * 0.70f, s * (0.5f + (x - 0.5f) * 1.25f), s * 0.97f)
            c.drawPath(q, fold); c.save(); c.translate(-s * 0.012f, 0f); c.drawPath(q, hi); c.restore()
        }
        Kit.halo(c, s * 0.5f, s * 0.62f, s * 0.26f, 0xFF101830.toInt(), 0.45f, s * 0.12f)
        Kit.weather(c, robe, s, 0.22f, 0.10f, 0xFF202850.toInt(), 0xFFFFFFFF.toInt(), 11)
        c.restore()
        Kit.edgeLight(c, robe, -s * 0.008f, -s * 0.008f, Kit.fill(0xAAFFFFFF.toInt()))
        Kit.edgeLight(c, robe, s * 0.011f, s * 0.011f, Kit.fill(Kit.al(TEAL, 0.45f)))
    }

    private fun face(c: Canvas, s: Float, live: Boolean) {
        val open = Kit.sym(s, "M.5,.115 C.42,.14 .365,.21 .365,.30 C.365,.39 .42,.46 .5,.48")
        val op = Kit.fill(); op.shader = RadialGradient(s * 0.5f, s * 0.32f, s * 0.22f, intArrayOf(0xFF010206.toInt(), 0xFF05060E.toInt(), 0xFF1C2440.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(open, op)
        c.drawPath(open, Kit.stroke(0xFF3A466C.toInt(), s * 0.009f))
        // the hollow face: long, gaunt, lit from within by the fire in the eyes
        val face = Kit.sym(s, "M.5,.17 C.44,.17 .405,.23 .405,.30 C.405,.35 .42,.39 .44,.43 C.46,.46 .48,.47 .5,.47")
        val fb = Kit.bounds(face)
        val fp = Kit.fill(); fp.shader = RadialGradient(s * 0.46f, s * 0.26f, s * 0.2f, intArrayOf(0xFFF0F4FA.toInt(), 0xFFB4C0D8.toInt(), 0xFF566486.toInt(), 0xFF1A2038.toInt()), floatArrayOf(0f, 0.4f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(face, fp)
        c.save(); c.clipPath(face)
        val blk = 0xFF0A1024.toInt()
        Kit.halo(c, s * 0.425f, s * 0.38f, s * 0.05f, blk, 0.6f, s * 0.06f); Kit.halo(c, s * 0.575f, s * 0.38f, s * 0.05f, blk, 0.6f, s * 0.06f)
        Kit.halo(c, s * 0.5f, s * 0.30f, s * 0.12f, TEAL, 0.22f, s * 0.08f)
        Kit.crack(c, 81, s * 0.52f, s * 0.17f, s * 0.55f, s * 0.26f, s * 0.004f, 0, 0.1f, 0, 0xFF1A2038.toInt())
        c.restore()
        c.drawPath(face, Kit.stroke(0xCC0A1024.toInt(), s * 0.005f))
        // sockets: long tear-shaped voids
        for (mir in 0..1) {
            val sock = Kit.path(s, "M.425,.265 C.445,.255 .48,.275 .485,.315 C.48,.345 .44,.345 .43,.31 C.425,.295 .42,.28 .425,.265 Z")
            val sk = if (mir == 0) sock else Kit.mirrored(sock, s * 0.5f)
            c.drawPath(sk, Kit.fill(0xFF02030A.toInt()))
        }
        if (!live) for (i in 0..1) Kit.eye(c, s * EYES[i * 2], s * (EYES[i * 2 + 1] + 0.008f), s * EYES[4], TEAL, 0xFFE8FFFF.toInt(), 1f, 0.8f)
        // nose slit
        c.drawLine(s * 0.5f, s * 0.335f, s * 0.5f, s * 0.36f, Kit.stroke(0xCC0A1024.toInt(), s * 0.006f))
        // the scream
        if (!live) mouth(c, s, 0.7f)
    }

    /** Screaming mouth (also drawn live with a varying [open] 0..1). */
    fun mouth(c: Canvas, s: Float, open: Float) {
        val cx = s * MOUTH[0]; val cy = s * MOUTH[1]; val rx = s * MOUTH[2] * (0.65f + 0.35f * open); val ry = s * MOUTH[3] * (0.45f + 0.55f * open)
        val p = Kit.fill(); p.shader = RadialGradient(cx, cy, ry * 1.2f, intArrayOf(0xFF000000.toInt(), 0xFF05081A.toInt(), 0xFF2A3560.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p)
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, Kit.stroke(0xFF0A1024.toInt(), s * 0.005f))
    }

    private fun arms(c: Canvas, s: Float) {
        for (sx in floatArrayOf(-1f, 1f)) {
            val shx = s * (0.5f + sx * 0.20f); val shy = s * 0.50f
            val wx = s * (0.5f + sx * 0.40f); val wy = s * 0.28f
            // sleeve: a torn bell of cloth from the shoulder to the wrist
            val sl = Path(); sl.moveTo(shx, shy - s * 0.04f)
            sl.cubicTo(shx + sx * s * 0.08f, shy - s * 0.16f, wx - sx * s * 0.02f, wy + s * 0.10f, wx, wy + s * 0.02f)
            sl.lineTo(wx - sx * s * 0.02f, wy + s * 0.12f)
            sl.cubicTo(wx - sx * s * 0.06f, wy + s * 0.22f, shx + sx * s * 0.02f, shy + s * 0.06f, shx - sx * s * 0.03f, shy + s * 0.12f)
            sl.close()
            val sp = Kit.fill(); sp.shader = LinearGradient(0f, wy, 0f, shy + s * 0.1f, intArrayOf(0xFFB4C2DC.toInt(), 0xFF6A7AA4.toInt(), 0x884A5A80.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(sl, sp); c.drawPath(sl, Kit.stroke(0x88101830.toInt(), s * 0.005f))
            // bony hand: palm and four curved claws spread upward
            val bone = Kit.stroke(0xFFE2E8F2.toInt(), s * 0.016f); val shade = Kit.stroke(0xFF3A4668.toInt(), s * 0.02f)
            for (i in 0..3) {
                val a = (-100f + i * 22f * sx - sx * 10f) * 0.01745f
                val len = s * (0.12f + (if (i == 1 || i == 2) 0.02f else 0f))
                val p = Path(); p.moveTo(wx, wy)
                val mx = wx + kotlin.math.sin(a.toDouble()).toFloat() * len * 0.55f; val my = wy - kotlin.math.cos(a.toDouble()).toFloat() * len * 0.55f
                val ex = wx + kotlin.math.sin(a.toDouble() * 1.6).toFloat() * len; val ey = wy - kotlin.math.cos(a.toDouble() * 1.3).toFloat() * len * 0.95f
                p.quadTo(mx, my, ex, ey)
                c.drawPath(p, shade); c.drawPath(p, bone)
                c.drawCircle(mx, my, s * 0.008f, Kit.fill(0xFFB8C2D8.toInt()))
                c.drawCircle(ex, ey, s * 0.005f, Kit.fill(0xFF1A2038.toInt()))
            }
            c.drawCircle(wx, wy, s * 0.02f, Kit.fill(0xFFD6DEEC.toInt()))
        }
    }
}

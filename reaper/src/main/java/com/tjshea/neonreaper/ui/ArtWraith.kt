package com.tjshea.neonreaper.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Wraith (a wailing banshee): a tattered slate-blue shroud that frays into tendrils and dissolves into mist, long pale hair
 * streaming sideways, a hollow gaunt face deep in a hood of darkness with teal fire in its sockets and a screaming black mouth,
 * long bony arms with clawed hands reaching out.
 */
internal object Wraith {
    private val TEAL = 0xFF5FE6E0.toInt()
    /** Eyes (unit): x1,y1,x2,y2,radius; mouth centre (unit) and size. */
    val EYES = floatArrayOf(0.455f, 0.292f, 0.545f, 0.292f, 0.016f)
    val MOUTH = floatArrayOf(0.5f, 0.385f, 0.036f, 0.056f)

    fun paint(c: Canvas, s: Float, live: Boolean) {
        Kit.aura(c, s * 0.5f, s * 0.48f, s * 0.5f, 0xFF3F78B8.toInt(), 0.26f)
        Kit.aura(c, s * 0.5f, s * 0.30f, s * 0.28f, 0xFF7A4FB8.toInt(), 0.24f)
        hair(c, s)
        streamers(c, s)
        arms(c, s)
        robe(c, s)
        face(c, s, live)
        Kit.mist(c, s * 0.06f, s * 0.94f, s * 0.95f, s * 0.12f, 0xFF9DB4D8.toInt(), 0.30f, 21, 8)
    }

    /** Long pale hair whipped sideways by an unseen wind. */
    private fun hair(c: Canvas, s: Float) {
        val r = java.util.Random(12)
        for (i in 0 until 9) {
            val side = if (i % 2 == 0) -1f else 1f
            val y0 = s * (0.24f + r.nextFloat() * 0.12f); val x0 = s * (0.5f + side * 0.14f)
            val len = 0.16f + r.nextFloat() * 0.16f
            val p = Path(); p.moveTo(x0, y0)
            p.cubicTo(x0 + side * s * len * 0.25f, y0 + s * 0.10f, x0 + side * s * len * 0.9f, y0 + s * (0.16f + r.nextFloat() * 0.08f), x0 + side * s * len * (0.7f + 0.4f * r.nextFloat()), y0 + s * (0.30f + r.nextFloat() * 0.14f))
            val w = s * (0.004f + r.nextFloat() * 0.005f)
            c.drawPath(p, Kit.stroke(Kit.al(0xFF8FA2C8.toInt(), 0.25f + r.nextFloat() * 0.2f), w * 2.4f))
            c.drawPath(p, Kit.stroke(Kit.al(0xFFD2DCF0.toInt(), 0.35f), w))
        }
    }

    /** Wind-torn ribbons streaming behind the shroud. */
    private fun streamers(c: Canvas, s: Float) {
        val r = java.util.Random(4)
        for (i in 0 until 8) {
            val side = if (i % 2 == 0) -1f else 1f
            val x0 = s * (0.5f + side * (0.10f + r.nextFloat() * 0.12f)); val y0 = s * (0.42f + r.nextFloat() * 0.15f)
            val x1 = s * (0.5f + side * (0.30f + r.nextFloat() * 0.16f)); val y1 = s * (0.72f + r.nextFloat() * 0.22f)
            val p = Path(); p.moveTo(x0, y0)
            p.cubicTo(x0 + side * s * 0.18f, y0 + s * 0.02f, x1, y1 - s * 0.2f, x1, y1)
            p.cubicTo(x1 - side * s * 0.05f, y1 - s * 0.18f, x0 + side * s * 0.05f, y0 + s * 0.20f, x0 - side * s * 0.03f, y0 + s * 0.06f)
            p.close()
            val b = Kit.bounds(p)
            val fp = Kit.fill(); fp.shader = LinearGradient(0f, b.top, 0f, b.bottom, intArrayOf(Kit.al(0xFF4A5C88.toInt(), 0.55f), Kit.al(0xFF2E3C62.toInt(), 0.35f), 0x00202A48), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(p, fp)
        }
    }

    /** Slender cloak with a hood like a gothic arch and a hem frayed into long tendrils. */
    private fun robePath(s: Float): Path {
        val p = Path()
        p.moveTo(s * 0.50f, s * 0.045f)
        p.cubicTo(s * 0.43f, s * 0.08f, s * 0.35f, s * 0.15f, s * 0.33f, s * 0.27f)
        p.cubicTo(s * 0.32f, s * 0.34f, s * 0.28f, s * 0.38f, s * 0.22f, s * 0.42f)
        p.cubicTo(s * 0.15f, s * 0.48f, s * 0.15f, s * 0.60f, s * 0.13f, s * 0.72f)
        p.cubicTo(s * 0.12f, s * 0.82f, s * 0.10f, s * 0.90f, s * 0.07f, s * 0.955f)
        val r = java.util.Random(9)
        var x = 0.07f; var k = 0
        while (x < 0.93f) {
            val w = 0.045f + r.nextFloat() * 0.06f
            val tip = if (k % 3 == 1) 0.995f else 0.90f + r.nextFloat() * 0.07f
            val bend = (r.nextFloat() - 0.5f) * 0.05f
            p.lineTo(s * (x + w * 0.3f), s * (0.88f + r.nextFloat() * 0.03f))
            p.quadTo(s * (x + w * 0.5f + bend), s * (tip - 0.04f), s * (x + w * 0.75f + bend * 1.6f), s * tip)
            x += w; k++
        }
        p.lineTo(s * 0.93f, s * 0.955f)
        p.cubicTo(s * 0.90f, s * 0.90f, s * 0.88f, s * 0.82f, s * 0.87f, s * 0.72f)
        p.cubicTo(s * 0.85f, s * 0.60f, s * 0.85f, s * 0.48f, s * 0.78f, s * 0.42f)
        p.cubicTo(s * 0.72f, s * 0.38f, s * 0.68f, s * 0.34f, s * 0.67f, s * 0.27f)
        p.cubicTo(s * 0.65f, s * 0.15f, s * 0.57f, s * 0.08f, s * 0.50f, s * 0.045f)
        p.close()
        return p
    }

    private fun robe(c: Canvas, s: Float) {
        val robe = robePath(s)
        val rb = Kit.bounds(robe)
        val p = Kit.fill()
        // cold slate cloth that fades into the mist towards the hem
        p.shader = LinearGradient(0f, rb.top, 0f, rb.bottom, intArrayOf(0xFF63749C.toInt(), 0xFF3A4668.toInt(), 0xE02A3454.toInt(), 0x00182038), floatArrayOf(0f, 0.38f, 0.72f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(robe, p)
        p.shader = LinearGradient(rb.left, 0f, rb.right, 0f, intArrayOf(0x55C8D8F8, 0x00000000, 0x77080C1C), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(robe, p); p.shader = null
        c.save(); c.clipPath(robe)
        // deep folds flowing upward and sideways as if in a gale, with cold light on their lit edges
        val fold = Kit.stroke(0x7010183A, s * 0.034f); val hi = Kit.stroke(0x38C8D8F8, s * 0.006f)
        val q = Path()
        for (i in 0 until 9) {
            val x = 0.15f + i * 0.088f
            q.reset(); q.moveTo(s * (0.5f + (x - 0.5f) * 0.45f), s * 0.44f); q.quadTo(s * (x + 0.03f * (if (i % 2 == 0) 1 else -1)), s * 0.70f, s * (0.5f + (x - 0.5f) * 1.25f), s * 0.98f)
            c.drawPath(q, fold); c.save(); c.translate(-s * 0.011f, 0f); c.drawPath(q, hi); c.restore()
        }
        Kit.halo(c, s * 0.5f, s * 0.62f, s * 0.26f, 0xFF050818.toInt(), 0.5f, s * 0.14f)
        Kit.weather(c, robe, s, 0.30f, 0.10f, 0xFF0A1030.toInt(), 0xFFB8C8F0.toInt(), 11)
        c.restore()
        Kit.edgeLight(c, robe, -s * 0.008f, -s * 0.008f, Kit.fill(0x99B8CCF0.toInt()))
        c.save(); c.clipRect(0f, 0f, s, s * 0.84f); Kit.edgeLight(c, robe, s * 0.011f, s * 0.011f, Kit.fill(Kit.al(TEAL, 0.35f))); c.restore()
    }

    private fun face(c: Canvas, s: Float, live: Boolean) {
        val open = Kit.sym(s, "M.5,.105 C.42,.14 .36,.21 .36,.30 C.36,.39 .42,.47 .5,.495")
        val op = Kit.fill(); op.shader = RadialGradient(s * 0.5f, s * 0.32f, s * 0.22f, intArrayOf(0xFF010206.toInt(), 0xFF04050C.toInt(), 0xFF1A2240.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(open, op)
        c.drawPath(open, Kit.stroke(0xFF3A466C.toInt(), s * 0.009f))
        // the hollow face: long, gaunt, only half lit
        val face = Kit.sym(s, "M.5,.165 C.445,.165 .405,.225 .405,.295 C.405,.35 .42,.39 .44,.435 C.46,.47 .48,.485 .5,.485")
        val fp = Kit.fill(); fp.shader = RadialGradient(s * 0.46f, s * 0.25f, s * 0.22f, intArrayOf(0xFFDDE6F2.toInt(), 0xFF9AA8C6.toInt(), 0xFF4A5878.toInt(), 0xFF141A30.toInt()), floatArrayOf(0f, 0.4f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(face, fp)
        c.save(); c.clipPath(face)
        val blk = 0xFF060A1C.toInt()
        Kit.halo(c, s * 0.425f, s * 0.39f, s * 0.05f, blk, 0.65f, s * 0.07f); Kit.halo(c, s * 0.575f, s * 0.39f, s * 0.05f, blk, 0.65f, s * 0.07f)
        Kit.halo(c, s * 0.5f, s * 0.30f, s * 0.12f, TEAL, 0.20f, s * 0.08f)
        fp.shader = LinearGradient(0f, s * 0.34f, 0f, s * 0.49f, 0x00000000, 0xAA000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(s * 0.3f, s * 0.15f, s * 0.7f, s * 0.5f, fp); fp.shader = null
        Kit.crack(c, 81, s * 0.52f, s * 0.165f, s * 0.555f, s * 0.26f, s * 0.004f, 0, 0.1f, 0, 0xFF141A30.toInt())
        c.restore()
        c.drawPath(face, Kit.stroke(0xCC0A1024.toInt(), s * 0.005f))
        // sockets: long tear-shaped voids
        for (mir in 0..1) {
            val sock = Kit.path(s, "M.42,.262 C.445,.25 .485,.272 .488,.312 C.482,.345 .438,.345 .428,.31 C.422,.294 .417,.278 .42,.262 Z")
            val sk = if (mir == 0) sock else Kit.mirrored(sock, s * 0.5f)
            c.drawPath(sk, Kit.fill(0xFF02030A.toInt()))
        }
        if (!live) for (i in 0..1) Kit.eye(c, s * EYES[i * 2], s * (EYES[i * 2 + 1] + 0.008f), s * EYES[4], TEAL, 0xFFE8FFFF.toInt(), 1f, 0.8f)
        c.drawLine(s * 0.5f, s * 0.335f, s * 0.5f, s * 0.36f, Kit.stroke(0xCC0A1024.toInt(), s * 0.006f))
        if (!live) mouth(c, s, 0.7f)
    }

    /** Screaming mouth (also drawn live with a varying [open] 0..1). */
    fun mouth(c: Canvas, s: Float, open: Float) {
        val cx = s * MOUTH[0]; val cy = s * MOUTH[1]; val rx = s * MOUTH[2] * (0.65f + 0.35f * open); val ry = s * MOUTH[3] * (0.45f + 0.55f * open)
        val p = Kit.fill(); p.shader = RadialGradient(cx, cy, ry * 1.2f, intArrayOf(0xFF000000.toInt(), 0xFF04061A.toInt(), 0xFF2A3560.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, p)
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, Kit.stroke(0xFF0A1024.toInt(), s * 0.005f))
    }

    /** Raised, ragged sleeves ending in long bony claws. */
    private fun arms(c: Canvas, s: Float) {
        for (sx in floatArrayOf(-1f, 1f)) {
            val shx = s * (0.5f + sx * 0.20f); val shy = s * 0.47f
            val wx = s * (0.5f + sx * 0.355f); val wy = s * 0.31f
            val sl = Path(); sl.moveTo(shx, shy - s * 0.04f)
            sl.cubicTo(shx + sx * s * 0.08f, shy - s * 0.17f, wx - sx * s * 0.02f, wy + s * 0.10f, wx, wy + s * 0.02f)
            sl.lineTo(wx - sx * s * 0.03f, wy + s * 0.13f)
            sl.cubicTo(wx - sx * s * 0.07f, wy + s * 0.24f, shx + sx * s * 0.02f, shy + s * 0.08f, shx - sx * s * 0.03f, shy + s * 0.14f)
            sl.close()
            val sp = Kit.fill(); sp.shader = LinearGradient(0f, wy, 0f, shy + s * 0.12f, intArrayOf(0xFF76889E.toInt(), 0xFF3A4668.toInt(), 0x88222C4A.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(sl, sp); c.drawPath(sl, Kit.stroke(0x99080C1C.toInt(), s * 0.005f))
            Kit.edgeLight(c, sl, -s * 0.006f, -s * 0.006f, Kit.fill(0x66B8CCF0))
            // bony hand: four long curved claws with joints
            val bone = Kit.stroke(0xFFDCE4F0.toInt(), s * 0.015f); val shade = Kit.stroke(0xFF2A3454.toInt(), s * 0.02f)
            for (i in 0..3) {
                val a = ((-100f + i * 22f * sx - sx * 10f) * 0.01745f).toDouble()
                val len = s * (0.13f + (if (i == 1 || i == 2) 0.025f else 0f))
                val mx = wx + sin(a).toFloat() * len * 0.55f; val my = wy - cos(a).toFloat() * len * 0.55f
                val ex = wx + sin(a * 1.6).toFloat() * len; val ey = wy - cos(a * 1.3).toFloat() * len * 0.95f
                val p = Path(); p.moveTo(wx, wy); p.quadTo(mx, my, ex, ey)
                c.drawPath(p, shade); c.drawPath(p, bone)
                c.drawCircle(mx, my, s * 0.0085f, Kit.fill(0xFFB4BED6.toInt()))
                Kit.thorn(c, ex, ey, kotlin.math.atan2((ey - my).toDouble(), (ex - mx).toDouble()).toFloat(), s * 0.025f, s * 0.012f, 0xFFC8D0E4.toInt(), 0xFFFFFFFF.toInt(), 0.1f)
            }
            c.drawCircle(wx, wy, s * 0.02f, Kit.fill(0xFFD6DEEC.toInt())); c.drawCircle(wx, wy, s * 0.02f, Kit.stroke(0xFF2A3454.toInt(), s * 0.004f))
        }
    }
}

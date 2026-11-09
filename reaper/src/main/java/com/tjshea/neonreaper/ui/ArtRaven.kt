package com.tjshea.neonreaper.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * The Blood Raven: a black raven in profile on a dead thorny branch against a blood moon, one ragged wing half open,
 * oil-slick sheen on the feathers, a red eye, a drop of blood on the beak.
 */
internal object Raven {
    private val RED = Portraits.RED
    private val MOON = 0xFFE02A20.toInt()
    // The whole bird is painted at 0.9 scale about (0.56, 0.5) to stay inside the cell: EYE0 is in painted space, EYE / BEAK_PIVOT
    // (what Characters uses for the live parts) are the same points after that transform.
    private const val K = 0.9f
    private val EYE0 = floatArrayOf(0.628f, 0.292f, 0.0125f)
    /** Eye (unit square: x, y, radius) and beak pivot (x, y) for the live parts. */
    val EYE = floatArrayOf(0.56f + K * (0.628f - 0.56f), 0.5f + K * (0.292f - 0.5f), 0.0125f * K)
    val BEAK_PIVOT = floatArrayOf(0.56f + K * (0.705f - 0.56f), 0.5f + K * (0.372f - 0.5f))

    fun paint(c: Canvas, s: Float, live: Boolean) {
        // blood moon
        val mx = s * 0.63f; val my = s * 0.30f; val mr = s * 0.215f
        Kit.aura(c, mx, my, s * 0.30f, MOON, 0.50f)
        val mp = Kit.fill(); mp.shader = RadialGradient(mx - mr * 0.3f, my - mr * 0.3f, mr * 1.5f, intArrayOf(0xFFFF8068.toInt(), 0xFFD02820.toInt(), 0xFF780C0E.toInt(), 0xFF3A0406.toInt()), floatArrayOf(0f, 0.4f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, mr, mp)
        c.save(); c.clipPath(Path().apply { addCircle(mx, my, mr, Path.Direction.CW) })
        val cr = Kit.fill(0x33200000)
        c.drawCircle(mx - mr * 0.35f, my - mr * 0.3f, mr * 0.16f, cr); c.drawCircle(mx + mr * 0.30f, my + mr * 0.22f, mr * 0.2f, cr); c.drawCircle(mx + mr * 0.05f, my - mr * 0.55f, mr * 0.1f, cr)
        // thin cloud streaks across the moon
        val cl = Kit.fill(0x66180004)
        c.drawOval(mx - mr * 1.2f, my + mr * 0.12f, mx + mr * 0.7f, my + mr * 0.30f, cl); c.drawOval(mx - mr * 0.3f, my - mr * 0.35f, mx + mr * 1.2f, my - mr * 0.2f, cl)
        c.restore()
        c.drawCircle(mx, my, mr, Kit.stroke(0x55FF9A80, s * 0.006f))
        c.save(); c.scale(K, K, s * 0.56f, s * 0.5f)      // keep wing, tail and beak well inside the cell
        Kit.groundShadow(c, s * 0.5f, s * 0.93f, s * 0.36f, s * 0.025f, 0.5f)
        branch(c, s)
        fallingFeathers(c, s)
        tail(c, s)
        // body silhouette
        val body = Kit.path(s, "M.735,.285 C.70,.21 .62,.17 .55,.20 C.47,.23 .43,.31 .38,.38 C.26,.48 .17,.62 .075,.86 L.17,.905 L.20,.80 C.30,.77 .42,.77 .50,.73 C.60,.68 .66,.57 .66,.46 C.67,.43 .70,.41 .735,.40 Z")
        val bb = Kit.bounds(body)
        val bp = Kit.fill(); bp.shader = RadialGradient(s * 0.5f, s * 0.35f, s * 0.5f, intArrayOf(0xFF3A3652.toInt(), 0xFF14121E.toInt(), 0xFF050408.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(body, bp)
        c.save(); c.clipPath(body)
        // oil-slick sheen on the head and back
        Kit.halo(c, s * 0.52f, s * 0.27f, s * 0.13f, 0xFF7A8CE0.toInt(), 0.35f, s * 0.07f)
        Kit.halo(c, s * 0.40f, s * 0.46f, s * 0.14f, 0xFF8A5AD0.toInt(), 0.25f, s * 0.07f)
        // blood-red moonlight along the breast
        Kit.halo(c, s * 0.69f, s * 0.46f, s * 0.12f, MOON, 0.55f, s * 0.14f)
        // neck and breast feather strokes
        val fs = Kit.stroke(0x33A8B4FF, s * 0.006f)
        val q = Path()
        for (i in 0 until 9) { val t = i / 8f; q.reset(); q.moveTo(s * (0.66f - 0.02f * t), s * (0.30f + 0.38f * t)); q.quadTo(s * (0.58f - 0.03f * t), s * (0.34f + 0.38f * t), s * (0.51f - 0.04f * t), s * (0.40f + 0.38f * t)); c.drawPath(q, fs) }
        c.restore()
        Kit.edgeLight(c, body, -s * 0.007f, -s * 0.007f, Kit.fill(0x7799A4E8.toInt()))
        c.save(); c.clipRect(s * 0.5f, s * 0.12f, s, s * 0.62f)      // blood-red moonlight only where the moon is behind the bird
        Kit.edgeLight(c, body, s * 0.011f, s * 0.011f, Kit.fill(Kit.al(MOON, 0.55f)))
        c.restore()
        wing(c, s)
        feet(c, s)
        beak(c, s, live)
        if (!live) Kit.eye(c, s * EYE0[0], s * EYE0[1], s * EYE0[2], RED, 0xFFFFE0D0.toInt(), 1f, 0.8f)
        c.restore()
    }

    /** The lower mandible as a sprite on an empty square, in the same transformed space as the finished painting. */
    fun jawSprite(c: Canvas, s: Float) { c.save(); c.scale(K, K, s * 0.56f, s * 0.5f); beakLow(c, s); c.restore() }

    private fun branch(c: Canvas, s: Float) {
        val br = Path(); br.moveTo(s * 0.0f, s * 0.835f); br.cubicTo(s * 0.3f, s * 0.80f, s * 0.6f, s * 0.80f, s * 1.0f, s * 0.735f)
        c.drawPath(br, Kit.stroke(0xFF07050A.toInt(), s * 0.058f, Paint.Cap.ROUND))
        c.drawPath(br, Kit.stroke(0xFF241A12.toInt(), s * 0.044f, Paint.Cap.ROUND))
        val hi = Path(); hi.moveTo(s * 0.0f, s * 0.82f); hi.cubicTo(s * 0.3f, s * 0.785f, s * 0.6f, s * 0.785f, s * 1.0f, s * 0.72f)
        c.drawPath(hi, Kit.stroke(0xFF5A4630.toInt(), s * 0.012f, Paint.Cap.ROUND))
        val twig = Path(); twig.moveTo(s * 0.80f, s * 0.77f); twig.quadTo(s * 0.88f, s * 0.84f, s * 0.90f, s * 0.93f)
        c.drawPath(twig, Kit.stroke(0xFF1A110B.toInt(), s * 0.022f));
        val twig2 = Path(); twig2.moveTo(s * 0.12f, s * 0.82f); twig2.quadTo(s * 0.08f, s * 0.72f, s * 0.12f, s * 0.64f)
        c.drawPath(twig2, Kit.stroke(0xFF1A110B.toInt(), s * 0.02f))
        for (i in 0..5) { val t = 0.1f + i * 0.16f; Kit.thorn(c, s * t, s * (0.835f - 0.1f * t) - s * 0.012f, -1.9f + (i % 2) * 0.8f, s * 0.045f, s * 0.02f, 0xFF1A110B.toInt(), 0xFF6A5238.toInt()) }
    }

    private fun tail(c: Canvas, s: Float) {
        for (i in 0..3) feather(c, s * 0.20f, s * 0.78f, s * (0.26f + i * 0.015f), s * 0.07f, 118f + i * 9f, 0xFF0C0A12.toInt(), 0xFF3C3A60.toInt(), true)
    }

    private fun wing(c: Canvas, s: Float) {
        val px = s * 0.47f; val py = s * 0.42f
        // primaries, ragged, fanning back and down
        for (i in 0..6) feather(c, px, py, s * (0.44f - kotlin.math.abs(i - 3) * 0.01f), s * 0.085f, 125f + i * 5.8f, 0xFF0B0911.toInt(), 0xFF4A4A86.toInt(), true)
        // secondaries and coverts
        for (i in 0..5) feather(c, px + s * 0.01f, py - s * 0.015f, s * (0.30f - i * 0.008f), s * 0.07f, 108f + i * 9f, 0xFF100E18.toInt(), 0xFF5A5AA8.toInt(), false)
        for (i in 0..4) feather(c, px + s * 0.04f, py - s * 0.03f, s * 0.17f, s * 0.055f, 98f + i * 10f, 0xFF15121F.toInt(), 0xFF6A6AB8.toInt(), false)
        // shoulder
        val sh = Kit.fill(); sh.shader = RadialGradient(px, py - s * 0.03f, s * 0.12f, intArrayOf(0xFF2A2842.toInt(), 0xFF0E0C16.toInt()), null, Shader.TileMode.CLAMP)
        c.drawOval(px - s * 0.10f, py - s * 0.09f, px + s * 0.10f, py + s * 0.05f, sh)
    }

    /** One feather: leaf shape from the pivot along [ang] degrees (0 = right, 90 = down); jagged tip when [ragged]. */
    private fun feather(c: Canvas, px: Float, py: Float, len: Float, wid: Float, ang: Float, body: Int, sheen: Int, ragged: Boolean) {
        c.save(); c.translate(px, py); c.rotate(ang)
        val p = Path()
        p.moveTo(0f, 0f)
        p.cubicTo(len * 0.22f, -wid * 0.62f, len * 0.7f, -wid * 0.55f, len * 0.93f, -wid * 0.12f)
        if (ragged) { p.lineTo(len * 0.99f, wid * 0.05f); p.lineTo(len * 0.86f, wid * 0.04f); p.lineTo(len * 1.0f, wid * 0.22f); p.lineTo(len * 0.80f, wid * 0.20f) } else p.lineTo(len, 0f)
        p.cubicTo(len * 0.6f, wid * 0.55f, len * 0.2f, wid * 0.6f, 0f, 0f)
        p.close()
        val fp = Kit.fill(); fp.shader = LinearGradient(0f, -wid * 0.6f, 0f, wid * 0.6f, intArrayOf(sheen, body, Kit.mul(body, 0.5f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(p, fp)
        c.drawPath(p, Kit.stroke(0xFF030204.toInt(), len * 0.012f + 0.4f))
        c.drawLine(len * 0.04f, 0f, len * 0.92f, -wid * 0.04f, Kit.stroke(Kit.al(sheen, 0.55f), len * 0.012f + 0.4f))
        c.restore()
    }

    private fun feet(c: Canvas, s: Float) {
        val st = Kit.stroke(0xFF120D0A.toInt(), s * 0.018f)
        for (x in floatArrayOf(0.44f, 0.52f)) {
            c.drawLine(s * x, s * 0.73f, s * (x + 0.005f), s * 0.795f, st)
            val toe = Path(); toe.moveTo(s * (x - 0.03f), s * 0.80f); toe.quadTo(s * x, s * 0.775f, s * (x + 0.045f), s * 0.80f)
            c.drawPath(toe, Kit.stroke(0xFF120D0A.toInt(), s * 0.014f))
            c.drawPath(toe, Kit.stroke(0xFF4A3A30.toInt(), s * 0.004f))
        }
    }

    private fun beak(c: Canvas, s: Float, live: Boolean) {
        val up = Kit.path(s, "M.70,.265 C.78,.265 .86,.32 .915,.39 C.86,.375 .80,.362 .735,.36 L.70,.36 Z")
        val ub = Kit.bounds(up)
        val bp = Kit.fill(); bp.shader = LinearGradient(0f, ub.top, 0f, ub.bottom, intArrayOf(0xFF6A6A78.toInt(), 0xFF2A2A34.toInt(), 0xFF0C0C10.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(up, bp); c.drawPath(up, Kit.stroke(0xFF030204.toInt(), s * 0.004f))
        c.drawCircle(s * 0.735f, s * 0.31f, s * 0.006f, Kit.fill(0xFF000000.toInt()))
        if (!live) {
            beakLow(c, s)
            // a drop of blood on the beak tip
            val d = Path(); d.moveTo(s * 0.895f, s * 0.405f); d.cubicTo(s * 0.885f, s * 0.43f, s * 0.885f, s * 0.46f, s * 0.90f, s * 0.46f); d.cubicTo(s * 0.915f, s * 0.46f, s * 0.915f, s * 0.43f, s * 0.895f, s * 0.405f)
            c.drawPath(d, Kit.fill(0xFFD01020.toInt())); c.drawCircle(s * 0.897f, s * 0.447f, s * 0.004f, Kit.fill(0xAAFFC0B0.toInt()))
        }
    }

    /** Lower mandible at rest (pivots at [BEAK_PIVOT] when the raven caws). */
    fun beakLow(c: Canvas, s: Float) {
        val lo = Kit.path(s, "M.70,.36 L.735,.36 C.80,.37 .85,.385 .885,.405 C.83,.41 .77,.405 .72,.405 L.69,.395 Z")
        val lb = Kit.bounds(lo)
        val bp = Kit.fill(); bp.shader = LinearGradient(0f, lb.top, 0f, lb.bottom, 0xFF4A4A58.toInt(), 0xFF0A0A0E.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(lo, bp); c.drawPath(lo, Kit.stroke(0xFF030204.toInt(), s * 0.004f))
    }

    private fun fallingFeathers(c: Canvas, s: Float) {
        feather(c, s * 0.20f, s * 0.22f, s * 0.09f, s * 0.03f, 70f, 0xFF0C0A12.toInt(), 0xFF8A3A4A.toInt(), false)
        feather(c, s * 0.10f, s * 0.46f, s * 0.07f, s * 0.025f, 110f, 0xFF0C0A12.toInt(), 0xFF5A4A8A.toInt(), false)
    }
}

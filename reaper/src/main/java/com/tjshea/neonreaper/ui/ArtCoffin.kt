package com.tjshea.neonreaper.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * The scatter: a dark mahogany coffin seen from above. Raised lid panel, brass straps with rivets and hinge barrels, crossed iron
 * chains, a skull medallion in a toxic-green ring, sickly green light leaking from the seam and out of the cracks.
 */
internal object Coffin {
    private val GREEN = 0xFF8CFF3A.toInt()
    private val BRASS = 0xFFB0893A.toInt()
    private val WOOD_LIT = 0xFF7E4230.toInt()
    private val WOOD = 0xFF40201A.toInt()
    private val WOOD_DK = 0xFF190908.toInt()

    fun paint(c: Canvas, s: Float) {
        val k = s / 256f
        Kit.aura(c, s * 0.5f, s * 0.52f, s * 0.5f, GREEN, 0.50f)
        val lid = Kit.sym(s, "M.5,.07 L.38,.07 L.19,.285 L.33,.915 L.5,.915")
        val b = Kit.bounds(lid)
        // ground: contact shadow with green light pooling around it
        Kit.halo(c, s * 0.52f, s * 0.955f, s * 0.36f, GREEN, 0.20f, s * 0.04f)
        Kit.groundShadow(c, s * 0.53f, s * 0.95f, s * 0.31f, s * 0.032f, 0.75f)
        // side wall
        val depth = (s * 0.04f).toInt().coerceAtLeast(3)
        val ink = Kit.stroke(0xFF050302.toInt(), s * 0.014f)
        c.save(); c.translate(0.35f * depth, 0.9f * depth); c.drawPath(lid, ink); c.restore()
        Kit.extrude(c, lid, 0.35f, 0.9f, depth, 0xFF2E160E.toInt(), 0xFF080403.toInt())
        // lid: lit wood with grain, planks and age
        val fp = Kit.fill()
        fp.shader = Kit.volume(b.left, b.top, b.right, b.bottom, WOOD_LIT, WOOD, WOOD_DK)
        c.drawPath(lid, fp); fp.shader = null
        c.save(); c.clipPath(lid)
        grain(c, b, k, 7)
        planks(c, b, s)
        c.restore()
        Kit.weather(c, lid, s, 0.5f, 0.12f, 0xFF120804.toInt(), 0xFFD79A70.toInt(), 3)
        // raised, carved centre panel
        val panel = Kit.sym(s, "M.5,.125 L.41,.125 L.26,.30 L.375,.875 L.5,.875")
        val pb = Kit.bounds(panel)
        c.drawPath(panel, Kit.stroke(0xCC050302.toInt(), s * 0.016f))
        fp.shader = Kit.volume(pb.left, pb.top, pb.right, pb.bottom, 0xFFB5704F.toInt(), 0xFF6A3524.toInt(), 0xFF2C140D.toInt())
        c.drawPath(panel, fp); fp.shader = null
        c.save(); c.clipPath(panel); grain(c, pb, k, 21); planks(c, pb, s); c.restore()
        Kit.edgeLight(c, panel, -s * 0.009f, -s * 0.009f, Kit.fill(0xD0FFD2AA.toInt()))
        Kit.edgeLight(c, panel, s * 0.011f, s * 0.011f, Kit.fill(0xCC090302.toInt()))
        carving(c, s)
        // sickly light: leaks along the seam on the shadow side (fading out towards the light) and out of the cracks
        c.save(); c.clipPath(lid)
        Kit.innerGlow(c, lid, 0xFF000000.toInt(), s * 0.05f, 0.55f)
        val seam = Kit.fill()
        seam.shader = LinearGradient(b.left, b.top, b.right, b.bottom, intArrayOf(0x00B8FF6A, 0x00B8FF6A, 0xFFB8FF6A.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        seam.maskFilter = BlurMaskFilter(s * 0.02f, BlurMaskFilter.Blur.NORMAL)
        Kit.edgeLight(c, lid, s * 0.02f, s * 0.02f, seam)
        seam.maskFilter = null
        Kit.edgeLight(c, lid, s * 0.009f, s * 0.009f, seam)
        c.restore()
        // straps, rivets, hinge barrels
        c.save(); c.clipPath(lid)
        strap(c, s, 0.385f); strap(c, s, 0.67f)
        c.restore()
        hinge(c, s, 0.385f, b); hinge(c, s, 0.67f, b)
        // crossed chains
        c.save(); c.clipPath(lid)
        chainRun(c, s, 0.23f, 0.31f, 0.68f, 0.80f); chainRun(c, s, 0.77f, 0.31f, 0.32f, 0.80f)
        c.restore()
        // cracks with green light behind them
        c.save(); c.clipPath(lid)
        Kit.crack(c, 31, s * 0.47f, s * 0.12f, s * 0.40f, s * 0.31f, s * 0.006f, GREEN, 0.07f, 1)
        Kit.crack(c, 47, s * 0.80f, s * 0.36f, s * 0.64f, s * 0.465f, s * 0.006f, GREEN, 0.07f, 1)
        Kit.crack(c, 59, s * 0.39f, s * 0.90f, s * 0.46f, s * 0.72f, s * 0.0055f, GREEN, 0.07f, 1)
        Kit.crack(c, 73, s * 0.62f, s * 0.90f, s * 0.58f, s * 0.78f, s * 0.0045f, GREEN, 0.06f, 0)
        c.restore()
        medallion(c, s)
        // rim: highlight on the lit edge, dark ink all round
        Kit.edgeLight(c, lid, -s * 0.007f, -s * 0.007f, Kit.fill(0xC0FFD9B0.toInt()))
        c.drawPath(lid, Kit.stroke(0xFF050302.toInt(), s * 0.011f))
    }

    private fun grain(c: Canvas, b: RectF, k: Float, seed: Long) {
        val r = java.util.Random(seed)
        val p = Kit.stroke(0, 1f, Paint.Cap.ROUND)
        val path = Path()
        for (i in 0 until 34) {
            val x = b.left + r.nextFloat() * b.width()
            val len = b.height() * (0.25f + r.nextFloat() * 0.5f)
            val y0 = b.top + r.nextFloat() * (b.height() - len)
            val wob = (r.nextFloat() - 0.5f) * b.width() * 0.05f
            path.reset(); path.moveTo(x, y0); path.quadTo(x + wob, y0 + len * 0.5f, x - wob * 0.3f, y0 + len)
            if (r.nextInt(3) == 0) { p.color = Kit.al(0xFFC4825A.toInt(), 0.10f + r.nextFloat() * 0.10f) } else { p.color = Kit.al(0xFF0E0603.toInt(), 0.22f + r.nextFloat() * 0.25f) }
            p.strokeWidth = (0.5f + r.nextFloat() * 1.5f) * k.coerceAtLeast(0.55f)
            c.drawPath(path, p)
        }
    }

    private fun planks(c: Canvas, b: RectF, s: Float) {
        val dark = Kit.stroke(0x99060302.toInt(), s * 0.006f, Paint.Cap.BUTT)
        val lite = Kit.stroke(0x22FFC9A0.toInt(), s * 0.004f, Paint.Cap.BUTT)
        for (i in 1..3) {
            val x = b.left + b.width() * i / 4f
            c.drawLine(x, b.top, x, b.bottom, dark); c.drawLine(x + s * 0.005f, b.top, x + s * 0.005f, b.bottom, lite)
        }
    }

    /** Engraved cross at the head and a spine with studs on the lid (carved lines: dark groove with a lit lower lip). */
    private fun carving(c: Canvas, s: Float) {
        val groove = Kit.stroke(0xEE0A0402.toInt(), s * 0.012f, Paint.Cap.BUTT); val lip = Kit.stroke(0x55FFC9A0.toInt(), s * 0.005f, Paint.Cap.BUTT)
        val cross = Path()
        cross.moveTo(s * 0.5f, s * 0.15f); cross.lineTo(s * 0.5f, s * 0.285f); cross.moveTo(s * 0.45f, s * 0.19f); cross.lineTo(s * 0.55f, s * 0.19f)
        c.drawPath(cross, groove); c.save(); c.translate(s * 0.005f, s * 0.006f); c.drawPath(cross, lip); c.restore()
        val spine = Path(); spine.moveTo(s * 0.5f, s * 0.66f); spine.lineTo(s * 0.5f, s * 0.80f)
        c.drawPath(spine, groove); c.save(); c.translate(s * 0.005f, s * 0.006f); c.drawPath(spine, lip); c.restore()
        val d = Path(); d.moveTo(s * 0.5f, s * 0.80f); d.lineTo(s * 0.525f, s * 0.835f); d.lineTo(s * 0.5f, s * 0.87f); d.lineTo(s * 0.475f, s * 0.835f); d.close()
        c.drawPath(d, Kit.stroke(0xEE0A0402.toInt(), s * 0.009f, Paint.Cap.BUTT, Paint.Join.MITER))
    }

    private fun strap(c: Canvas, s: Float, y: Float) {
        val h = s * 0.048f
        val r = RectF(0f, s * y - h / 2, s, s * y + h / 2)
        val p = Kit.fill()
        p.shader = Kit.metal(0f, r.top, 0f, r.bottom, BRASS, 0.55f, 0.28f)
        c.drawRect(r, p); p.shader = null
        c.drawLine(0f, r.top, s, r.top, Kit.stroke(0xDDFFE9A0.toInt(), s * 0.004f, Paint.Cap.BUTT))
        c.drawLine(0f, r.bottom, s, r.bottom, Kit.stroke(0xCC140A02.toInt(), s * 0.006f, Paint.Cap.BUTT))
        // grime on the brass
        c.save(); c.clipRect(r); Kit.tex(c, r, Kit.grime, s / 256f * 1.2f + 0.3f, 0.55f, 0xFF1A1405.toInt(), null, 5f, 9f); c.restore()
        // rivets
        val rp = Kit.fill()
        var x = s * 0.2f
        while (x < s * 0.85f) {
            rp.shader = RadialGradient(x - s * 0.004f, s * y - s * 0.004f, s * 0.014f, intArrayOf(0xFFFFF0B0.toInt(), BRASS, 0xFF2A1C06.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, s * y, s * 0.0125f, rp); x += s * 0.1f
        }
    }

    /** Hinge barrel on the right edge of the lid at strap height. */
    private fun hinge(c: Canvas, s: Float, y: Float, b: RectF) {
        val edgeX = 0.81f + (0.67f - 0.81f) * ((y - 0.285f) / (0.915f - 0.285f))
        val x = s * edgeX; val yy = s * y
        val p = Kit.fill()
        val knuckle = RectF(x - s * 0.02f, yy - s * 0.036f, x + s * 0.028f, yy + s * 0.036f)
        p.shader = Kit.metal(knuckle.left, 0f, knuckle.right, 0f, BRASS, 0.6f, 0.3f)
        c.drawRoundRect(knuckle, s * 0.018f, s * 0.018f, p); p.shader = null
        c.drawRoundRect(knuckle, s * 0.018f, s * 0.018f, Kit.stroke(0xFF1B1004.toInt(), s * 0.006f))
        c.drawLine(x - s * 0.006f, yy - s * 0.028f, x - s * 0.006f, yy + s * 0.028f, Kit.stroke(0xAAFFE9A0.toInt(), s * 0.004f))
        c.drawCircle(x + s * 0.004f, yy, s * 0.006f, Kit.fill(0xFF140A02.toInt()))
    }

    private fun chainRun(c: Canvas, s: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
        val pts = FloatArray(2 * 17)
        val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
        val nx = -(y1 - y0); val ny = (x1 - x0)
        val sag = 0.035f
        for (i in 0..16) {
            val t = i / 16f; val q = 4 * t * (1 - t)
            pts[i * 2] = (x0 + (x1 - x0) * t + nx * sag * q) * s
            pts[i * 2 + 1] = (y0 + (y1 - y0) * t + ny * sag * q) * s
        }
        // shadow under the chain
        val sh = FloatArray(pts.size); for (i in pts.indices step 2) { sh[i] = pts[i] + s * 0.008f; sh[i + 1] = pts[i + 1] + s * 0.012f }
        Kit.chain(c, sh, s * 0.046f, 0xFF050302.toInt())
        Kit.chain(c, pts, s * 0.046f, 0xFF6C6762.toInt())
    }

    private fun medallion(c: Canvas, s: Float) {
        val mx = s * 0.5f; val my = s * 0.515f; val R = s * 0.112f
        Kit.halo(c, mx, my, s * 0.24f, GREEN, 0.50f)
        val ds = Kit.fill()
        // shadow + iron disc
        c.drawCircle(mx + s * 0.006f, my + s * 0.01f, R * 1.04f, Kit.fill(0xAA000000.toInt()))
        ds.shader = RadialGradient(mx - R * 0.35f, my - R * 0.4f, R * 1.5f, intArrayOf(0xFF6A645F.toInt(), 0xFF2B2724.toInt(), 0xFF0C0A09.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, R, ds); ds.shader = null
        c.drawCircle(mx, my, R, Kit.stroke(BRASS, s * 0.014f))
        c.drawArc(RectF(mx - R, my - R, mx + R, my + R), 195f, 100f, false, Kit.stroke(0xFFFFE9A0.toInt(), s * 0.006f))
        // green ring
        val rr = R * 0.76f
        val ring = Kit.stroke(Kit.al(GREEN, 0.6f), s * 0.03f, Paint.Cap.BUTT); ring.maskFilter = BlurMaskFilter(s * 0.014f, BlurMaskFilter.Blur.NORMAL)
        c.drawCircle(mx, my, rr, ring); ring.maskFilter = null
        ring.color = Kit.lift(GREEN, 0.3f); ring.strokeWidth = s * 0.013f; c.drawCircle(mx, my, rr, ring)
        c.drawCircle(mx, my, R * 0.62f, Kit.fill(0xFF060807.toInt()))
        Kit.miniSkull(c, mx, my - s * 0.006f, R * 0.46f, 0xFFD9D2BE.toInt(), GREEN)
    }
}

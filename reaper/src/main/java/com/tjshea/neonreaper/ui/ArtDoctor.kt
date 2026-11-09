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
 * The Plague Doctor: wide-brimmed black hat with a brass-buckled band, a long stitched leather beak, round brass-ringed lenses
 * glowing sickly green, a high ruff, a long buttoned leather coat, a belt of glowing vials and a green lantern.
 * Matches the 3D model's palette (leather browns, brass, green lenses and lantern).
 */
internal object Doctor {
    private val GREEN = Portraits.GREEN
    private val BRASS = 0xFFB0893A.toInt()

    /** Lens centres and radius in the unit square (for the live glow). */
    val LENSES = floatArrayOf(0.425f, 0.318f, 0.575f, 0.318f, 0.048f)
    /** Lantern flame base (unit). */
    val LANTERN = floatArrayOf(0.165f, 0.905f)

    fun paint(c: Canvas, s: Float, live: Boolean, aura: Int = GREEN) {
        Kit.aura(c, s * 0.5f, s * 0.5f, s * 0.5f, aura, 0.26f)
        Kit.aura(c, s * 0.5f, s * 0.35f, s * 0.24f, 0xFF4A8A20.toInt(), 0.22f)
        Kit.groundShadow(c, s * 0.5f, s * 0.975f, s * 0.38f, s * 0.028f, 0.7f)
        staff(c, s)
        coat(c, s)
        rightArm(c, s)
        harness(c, s)
        belt(c, s)
        lantern(c, s, live)
        ruff(c, s)
        head(c, s, live)
        hat(c, s)
    }

    // ---------------------------------------------------------------- body
    private fun coatPath(s: Float) = Kit.path(s, "M.50,.45 C.40,.46 .30,.48 .24,.54 C.17,.61 .15,.74 .11,.975 C.20,.99 .28,.955 .36,.985 C.44,.96 .56,.99 .64,.965 C.72,.99 .80,.955 .89,.975 C.85,.74 .83,.61 .76,.54 C.70,.48 .60,.46 .50,.45 Z")

    private fun coat(c: Canvas, s: Float) {
        val coat = coatPath(s)
        val b = Kit.bounds(coat)
        val p = Kit.fill()
        p.shader = LinearGradient(b.left, 0f, b.right, 0f, intArrayOf(0xFF47322A.toInt(), 0xFF26170F.toInt(), 0xFF0B0604.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(coat, p)
        p.shader = LinearGradient(0f, b.top, 0f, b.bottom, 0x00000000, 0xAA000000.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(coat, p); p.shader = null
        c.save(); c.clipPath(coat)
        // leather sheen along the shoulders, vertical creases, centre seam
        Kit.halo(c, s * 0.33f, s * 0.575f, s * 0.22f, 0xFFD0A080.toInt(), 0.40f, s * 0.10f)
        val crease = Kit.stroke(0x55000000, s * 0.022f)
        val hi = Kit.stroke(0x2CE0B090, s * 0.006f)
        for (x in floatArrayOf(0.22f, 0.31f, 0.69f, 0.78f)) {
            val q = Path(); q.moveTo(s * (x + (0.5f - x) * 0.2f), s * 0.60f); q.quadTo(s * (x - (0.5f - x) * 0.05f), s * 0.80f, s * (x - (0.5f - x) * 0.12f), s * 0.975f)
            c.drawPath(q, crease); c.save(); c.translate(-s * 0.008f, 0f); c.drawPath(q, hi); c.restore()
        }
        c.drawLine(s * 0.5f, s * 0.52f, s * 0.5f, s * 0.975f, Kit.stroke(0xCC050302.toInt(), s * 0.008f, Paint.Cap.BUTT))
        // lantern light on the left side of the coat
        Kit.halo(c, s * 0.15f, s * 0.84f, s * 0.34f, GREEN, 0.30f, s * 0.30f)
        Kit.weather(c, coat, s, 0.3f, 0.12f, 0xFF000000.toInt(), 0xFFB08868.toInt(), 5)
        c.restore()
        Kit.edgeLight(c, coat, -s * 0.008f, -s * 0.008f, Kit.fill(0x66C09070.toInt()))
        c.save(); c.clipRect(0f, 0f, s, s * 0.92f)
        Kit.edgeLight(c, coat, s * 0.012f, s * 0.012f, Kit.fill(Kit.al(GREEN, 0.28f)))
        c.restore()
        // buttons
        val bp = Kit.fill()
        for (i in 0..5) {
            val y = s * (0.56f + i * 0.07f); val x = s * 0.5f
            bp.shader = RadialGradient(x - s * 0.004f, y - s * 0.004f, s * 0.016f, intArrayOf(0xFFF3D98A.toInt(), BRASS, 0xFF2A1C06.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, s * 0.0125f, bp)
        }
    }

    /** The arm that holds the staff: leather sleeve with a brass-studded cuff and a black glove closed round the shaft. */
    private fun rightArm(c: Canvas, s: Float) {
        val arm = Kit.path(s, "M.73,.52 C.78,.55 .84,.60 .875,.655 L.84,.70 C.80,.65 .75,.61 .70,.585 Z")
        val ap = Kit.fill(); ap.shader = LinearGradient(s * 0.70f, s * 0.52f, s * 0.88f, s * 0.70f, 0xFF3A2820.toInt(), 0xFF120A07.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(arm, ap); c.drawPath(arm, Kit.stroke(0xFF050302.toInt(), s * 0.005f))
        Kit.edgeLight(c, arm, -s * 0.006f, -s * 0.006f, Kit.fill(0x66C09070))
        val glove = RectF(s * 0.835f, s * 0.635f, s * 0.905f, s * 0.70f)
        val gp = Kit.fill(); gp.shader = RadialGradient(s * 0.86f, s * 0.65f, s * 0.06f, 0xFF3A2A22.toInt(), 0xFF0A0605.toInt(), Shader.TileMode.CLAMP)
        c.drawOval(glove, gp); c.drawOval(glove, Kit.stroke(0xFF050302.toInt(), s * 0.004f))
        for (i in 0..2) c.drawLine(s * 0.845f, s * (0.648f + i * 0.015f), s * 0.895f, s * (0.645f + i * 0.015f), Kit.stroke(0x66000000, s * 0.004f))
        c.drawCircle(s * 0.865f, s * 0.60f, s * 0.008f, Kit.fill(BRASS))
    }

    private fun harness(c: Canvas, s: Float) {
        val strap = Path(); strap.moveTo(s * 0.66f, s * 0.50f); strap.lineTo(s * 0.34f, s * 0.72f)
        c.drawPath(strap, Kit.stroke(0xFF0A0604.toInt(), s * 0.034f, Paint.Cap.BUTT))
        c.drawPath(strap, Kit.stroke(0xFF3B2A22.toInt(), s * 0.022f, Paint.Cap.BUTT))
        val st = Kit.fill(BRASS)
        for (i in 0..3) { val t = 0.15f + i * 0.23f; c.drawCircle(s * (0.66f - 0.32f * t), s * (0.50f + 0.22f * t), s * 0.007f, st) }
    }

    private fun belt(c: Canvas, s: Float) {
        val y = s * 0.715f
        c.save(); c.clipPath(coatPath(s))
        val bandP = Path(); bandP.moveTo(s * 0.10f, y - s * 0.012f); bandP.quadTo(s * 0.5f, y + s * 0.04f, s * 0.90f, y - s * 0.012f)
        c.drawPath(bandP, Kit.stroke(0xFF0A0604.toInt(), s * 0.046f, Paint.Cap.BUTT))
        val bs = Kit.stroke(0xFF4A362C.toInt(), s * 0.032f, Paint.Cap.BUTT); bs.shader = LinearGradient(0f, y - s * 0.02f, 0f, y + s * 0.03f, 0xFF5A4438.toInt(), 0xFF1A100B.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(bandP, bs)
        c.restore()
        // brass buckle
        val bp = Kit.fill()
        val bk = RectF(s * 0.465f, y + s * 0.0f, s * 0.535f, y + s * 0.052f)
        bp.shader = Kit.metal(bk.left, bk.top, bk.right, bk.bottom, BRASS, 0.6f, 0.3f); c.drawRoundRect(bk, s * 0.008f, s * 0.008f, bp); bp.shader = null
        c.drawRoundRect(RectF(bk.left + s * 0.014f, bk.top + s * 0.012f, bk.right - s * 0.014f, bk.bottom - s * 0.012f), s * 0.004f, s * 0.004f, Kit.fill(0xFF1A100B.toInt()))
        // vials hanging from the belt
        val cols = intArrayOf(0xFFE0182F.toInt(), GREEN, 0xFFE8B020.toInt())
        for ((i, col) in cols.withIndex()) {
            val x = s * (0.585f + i * 0.058f); val top = y + s * 0.03f
            Kit.halo(c, x, top + s * 0.04f, s * 0.06f, col, 0.38f)
            val v = RectF(x - s * 0.014f, top, x + s * 0.014f, top + s * 0.075f)
            val vp = Kit.fill(); vp.shader = LinearGradient(v.left, 0f, v.right, 0f, intArrayOf(Kit.lift(col, 0.5f), col, Kit.mul(col, 0.4f)), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(v, s * 0.012f, s * 0.012f, vp)
            c.drawRoundRect(v, s * 0.012f, s * 0.012f, Kit.stroke(0xFF0A0604.toInt(), s * 0.004f))
            c.drawRect(x - s * 0.01f, top - s * 0.008f, x + s * 0.01f, top + s * 0.006f, Kit.fill(0xFF6A4A2A.toInt()))
        }
        // pouch
        val pz = RectF(s * 0.30f, y + s * 0.03f, s * 0.38f, y + s * 0.105f)
        val pp = Kit.fill(); pp.shader = LinearGradient(pz.left, 0f, pz.right, 0f, 0xFF4A362C.toInt(), 0xFF140C08.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(pz, s * 0.012f, s * 0.012f, pp); c.drawRoundRect(pz, s * 0.012f, s * 0.012f, Kit.stroke(0xFF050302.toInt(), s * 0.004f))
    }

    private fun staff(c: Canvas, s: Float) {
        val x0 = s * 0.875f; val y0 = s * 0.985f; val x1 = s * 0.862f; val y1 = s * 0.36f
        c.drawLine(x0, y0, x1, y1, Kit.stroke(0xFF0E0805.toInt(), s * 0.026f, Paint.Cap.BUTT))
        c.drawLine(x0 - s * 0.005f, y0, x1 - s * 0.005f, y1, Kit.stroke(0xFF5A3F28.toInt(), s * 0.009f, Paint.Cap.BUTT))
        // crook with a skull hanging from it
        val crook = Path(); crook.moveTo(x1, y1); crook.cubicTo(x1, s * 0.30f, s * 0.80f, s * 0.30f, s * 0.80f, s * 0.355f)
        c.drawPath(crook, Kit.stroke(0xFF0E0805.toInt(), s * 0.024f)); c.drawPath(crook, Kit.stroke(0xFF5A3F28.toInt(), s * 0.008f))
        c.drawLine(s * 0.80f, s * 0.355f, s * 0.80f, s * 0.385f, Kit.stroke(0xFF6A6560.toInt(), s * 0.006f))
        Kit.miniSkull(c, s * 0.80f, s * 0.415f, s * 0.034f, 0xFFD0C7AE.toInt(), GREEN)
    }

    private fun lantern(c: Canvas, s: Float, live: Boolean) {
        val x = s * 0.165f; val top = s * 0.80f
        // sleeve and glove holding the ring
        val arm = Kit.path(s, "M.27,.54 C.22,.58 .17,.64 .13,.73 L.20,.76 C.23,.69 .27,.64 .31,.60 Z")
        val ap = Kit.fill(); ap.shader = LinearGradient(s * 0.13f, 0f, s * 0.31f, 0f, 0xFF2C1D16.toInt(), 0xFF120A07.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(arm, ap); c.drawPath(arm, Kit.stroke(0xFF050302.toInt(), s * 0.005f))
        Kit.edgeLight(c, arm, -s * 0.006f, -s * 0.006f, Kit.fill(0x66C09070.toInt()))
        val glove = RectF(s * 0.125f, s * 0.725f, s * 0.205f, s * 0.785f)
        val glp = Kit.fill(); glp.shader = RadialGradient(s * 0.15f, s * 0.74f, s * 0.06f, 0xFF3A2A22.toInt(), 0xFF0E0805.toInt(), Shader.TileMode.CLAMP)
        c.drawOval(glove, glp); c.drawOval(glove, Kit.stroke(0xFF050302.toInt(), s * 0.004f))
        c.drawLine(s * 0.165f, s * 0.775f, x, top, Kit.stroke(0xFF3A3028.toInt(), s * 0.006f))
        Kit.halo(c, x, s * 0.88f, s * 0.22f, GREEN, 0.55f)
        val body = RectF(x - s * 0.05f, top, x + s * 0.05f, s * 0.95f)
        // glass, cage bars, cap and base
        val gp = Kit.fill(); gp.shader = LinearGradient(body.left, 0f, body.right, 0f, intArrayOf(0xFF2E7A12.toInt(), 0xFFD6FFB0.toInt(), 0xFF2E7A12.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(body, gp)
        if (!live) Art.flame(c, x, s * 0.93f, s * 0.075f, GREEN, 0f)
        val bar = Kit.stroke(0xFF1A140E.toInt(), s * 0.007f, Paint.Cap.BUTT)
        for (i in 0..2) c.drawLine(body.left + body.width() * i / 2f, body.top, body.left + body.width() * i / 2f, body.bottom, bar)
        val cap = Path(); cap.moveTo(body.left - s * 0.01f, top); cap.lineTo(x, top - s * 0.04f); cap.lineTo(body.right + s * 0.01f, top); cap.close()
        c.drawPath(cap, Kit.fill(0xFF3A3128.toInt())); c.drawPath(cap, Kit.stroke(0xFF050302.toInt(), s * 0.004f))
        c.drawRect(body.left - s * 0.01f, body.bottom - s * 0.004f, body.right + s * 0.01f, body.bottom + s * 0.016f, Kit.fill(0xFF3A3128.toInt()))
    }

    private fun ruff(c: Canvas, s: Float) {
        // ragged feather collar around the neck
        val p = Path(); val r = java.util.Random(3)
        p.moveTo(s * 0.32f, s * 0.49f)
        var x = 0.32f
        while (x < 0.68f) { val w = 0.025f + r.nextFloat() * 0.02f; p.lineTo(s * (x + w * 0.5f), s * (0.43f - r.nextFloat() * 0.025f)); p.lineTo(s * (x + w), s * 0.475f); x += w }
        p.lineTo(s * 0.68f, s * 0.52f); p.quadTo(s * 0.5f, s * 0.57f, s * 0.32f, s * 0.52f); p.close()
        val fp = Kit.fill(); fp.shader = LinearGradient(0f, s * 0.42f, 0f, s * 0.56f, 0xFF3A2A22.toInt(), 0xFF0C0705.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(p, fp); c.drawPath(p, Kit.stroke(0xFF050302.toInt(), s * 0.005f))
        c.save(); c.clipPath(p); Kit.weather(c, p, s, 0.3f, 0.2f, 0xFF000000.toInt(), 0xFFB08868.toInt(), 8); c.restore()
    }

    // ---------------------------------------------------------------- head: cowl, lenses, beak
    private fun head(c: Canvas, s: Float, live: Boolean) {
        val cowl = Kit.sym(s, "M.5,.18 C.41,.19 .35,.25 .35,.34 C.35,.42 .40,.48 .5,.495")
        val fp = Kit.fill()
        fp.shader = RadialGradient(s * 0.44f, s * 0.30f, s * 0.2f, intArrayOf(0xFF3A2A22.toInt(), 0xFF1A100C.toInt(), 0xFF070403.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(cowl, fp); fp.shader = null
        c.save(); c.clipPath(cowl)
        // leather folds and a lit left edge so the hood reads as material, not a void
        for (sx in floatArrayOf(-1f, 1f)) { val q = Path(); q.moveTo(s * (0.5f + sx * 0.05f), s * 0.20f); q.quadTo(s * (0.5f + sx * 0.13f), s * 0.34f, s * (0.5f + sx * 0.10f), s * 0.49f); c.drawPath(q, Kit.stroke(0x66000000, s * 0.012f)) }
        Kit.halo(c, s * 0.40f, s * 0.26f, s * 0.07f, 0xFFD0A07A.toInt(), 0.30f, s * 0.08f)
        Kit.weather(c, cowl, s, 0.3f, 0.2f, 0xFF000000.toInt(), 0xFFB08868.toInt(), 4)
        c.restore()
        Kit.edgeLight(c, cowl, -s * 0.007f, -s * 0.007f, Kit.fill(0x66C09070))
        c.drawPath(cowl, Kit.stroke(0xFF050302.toInt(), s * 0.006f))
        // beak: long tapering leather cone with a lit ridge, stitched seam and vents
        val beak = Kit.sym(s, "M.5,.285 C.455,.285 .44,.34 .445,.40 C.45,.49 .478,.575 .5,.655")
        val bb = Kit.bounds(beak)
        val bp = Kit.fill(); bp.shader = LinearGradient(bb.left, 0f, bb.right, 0f, intArrayOf(0xFF8E6244.toInt(), 0xFF6A4631.toInt(), 0xFF2A180F.toInt(), 0xFF120A06.toInt()), floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(beak, bp)
        c.save(); c.clipPath(beak)
        Kit.halo(c, s * 0.475f, s * 0.38f, s * 0.05f, 0xFFD0A07A.toInt(), 0.35f, s * 0.08f)
        fp.shader = LinearGradient(0f, s * 0.5f, 0f, s * 0.66f, 0x00000000, 0x99000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(bb, fp); fp.shader = null
        Kit.weather(c, beak, s, 0.3f, 0.15f, 0xFF000000.toInt(), 0xFFD0A07A.toInt(), 6)
        c.restore()
        c.drawPath(beak, Kit.stroke(0xFF050302.toInt(), s * 0.005f))
        val seam = Path(); seam.moveTo(s * 0.5f, s * 0.31f); seam.lineTo(s * 0.5f, s * 0.64f)
        c.drawPath(seam, Kit.stroke(0xFF0A0604.toInt(), s * 0.0055f, Paint.Cap.BUTT))
        val th = Kit.stroke(0xFFD8C8A0.toInt(), s * 0.0035f)
        for (i in 0 until 9) { val y = s * (0.325f + i * 0.036f); val hw = s * (0.017f - i * 0.0012f).coerceAtLeast(0.004f); c.drawLine(s * 0.5f - hw, y, s * 0.5f + hw, y + s * 0.006f, th) }
        for (sx in floatArrayOf(-1f, 1f)) c.drawCircle(s * 0.5f + sx * s * 0.022f, s * 0.425f, s * 0.0065f, Kit.fill(0xFF0A0604.toInt()))
        // lenses: leather socket, brass ring, glowing glass
        for (i in 0..1) {
            val lx = s * LENSES[i * 2]; val ly = s * LENSES[i * 2 + 1]; val r = s * LENSES[4]
            c.drawCircle(lx + s * 0.003f, ly + s * 0.004f, r * 1.28f, Kit.fill(0xFF050302.toInt()))
            val ring = Kit.fill(); ring.shader = Kit.metal(lx - r, ly - r, lx + r, ly + r, BRASS, 0.65f, 0.28f)
            c.drawCircle(lx, ly, r * 1.18f, ring); ring.shader = null
            c.drawCircle(lx, ly, r * 1.18f, Kit.stroke(0xFF1A1004.toInt(), s * 0.0045f))
            c.drawCircle(lx, ly, r * 0.98f, Kit.fill(0xFF0A1206.toInt()))
            if (!live) glass(c, s, lx, ly, r * 0.88f, 1f)
            // rivets on the ring
            for (k in 0..3) { val a = k * 1.5708f + 0.4f; c.drawCircle(lx + kotlin.math.cos(a) * r * 1.08f, ly + kotlin.math.sin(a) * r * 1.08f, s * 0.0045f, Kit.fill(0xFFFFE9A0.toInt())) }
        }
    }

    /** Glowing lens glass (also used live by [Characters]); coordinates in pixels. */
    fun glass(c: Canvas, s: Float, lx: Float, ly: Float, r: Float, glow: Float) {
        Kit.halo(c, lx, ly, r * 3.2f, GREEN, 0.55f * glow)
        val g = Kit.fill(); g.shader = RadialGradient(lx - r * 0.2f, ly - r * 0.2f, r, intArrayOf(0xFFFFFFF0.toInt(), 0xFFB8FF8A.toInt(), 0xFF3C9A18.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(lx, ly, r, g)
        c.drawCircle(lx - r * 0.35f, ly - r * 0.38f, r * 0.22f, Kit.fill(0xCCFFFFFF.toInt()))
    }

    // ---------------------------------------------------------------- hat
    private fun hat(c: Canvas, s: Float) {
        val brimY = s * 0.205f
        // brim: thick ellipse with a lit upper rim
        val under = RectF(s * 0.12f, brimY - s * 0.045f + s * 0.012f, s * 0.88f, brimY + s * 0.055f + s * 0.012f)
        c.drawOval(under, Kit.fill(0xFF050706.toInt()))
        val brim = RectF(s * 0.12f, brimY - s * 0.05f, s * 0.88f, brimY + s * 0.05f)
        val bp = Kit.fill(); bp.shader = LinearGradient(brim.left, 0f, brim.right, 0f, intArrayOf(0xFF1B2620.toInt(), 0xFF111915.toInt(), 0xFF060907.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(brim, bp)
        val rim = Path(); rim.addArc(brim, 195f, 150f)
        c.drawPath(rim, Kit.stroke(0x88A8C8B0.toInt(), s * 0.006f))
        // crown
        val crown = Kit.sym(s, "M.5,.035 C.43,.035 .395,.04 .385,.06 L.37,.20")
        val cb = Kit.bounds(crown)
        bp.shader = LinearGradient(cb.left, 0f, cb.right, 0f, intArrayOf(0xFF22302A.toInt(), 0xFF111915.toInt(), 0xFF060907.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(crown, bp); bp.shader = null
        c.drawPath(crown, Kit.stroke(0xFF030504.toInt(), s * 0.005f))
        Kit.edgeLight(c, crown, -s * 0.007f, -s * 0.007f, Kit.fill(0x6690B098.toInt()))
        // band and buckle
        val band = Path(); band.moveTo(s * 0.373f, s * 0.145f); band.lineTo(s * 0.627f, s * 0.145f); band.lineTo(s * 0.63f, s * 0.19f); band.lineTo(s * 0.37f, s * 0.19f); band.close()
        c.drawPath(band, Kit.fill(0xFF2E2018.toInt())); c.drawPath(band, Kit.stroke(0xFF050302.toInt(), s * 0.004f))
        val bk = RectF(s * 0.472f, s * 0.14f, s * 0.528f, s * 0.195f)
        val kp = Kit.fill(); kp.shader = Kit.metal(bk.left, bk.top, bk.right, bk.bottom, BRASS, 0.6f, 0.3f); c.drawRoundRect(bk, s * 0.006f, s * 0.006f, kp)
        c.drawRect(bk.left + s * 0.012f, bk.top + s * 0.012f, bk.right - s * 0.012f, bk.bottom - s * 0.012f, Kit.fill(0xFF2E2018.toInt()))
        c.drawRoundRect(bk, s * 0.006f, s * 0.006f, Kit.stroke(0xFF1A1004.toInt(), s * 0.004f))
        // the brim overlaps the top of the cowl: dark shadow under it
        Kit.halo(c, s * 0.5f, s * 0.27f, s * 0.2f, 0xFF000000.toInt(), 0.55f, s * 0.04f)
    }
}

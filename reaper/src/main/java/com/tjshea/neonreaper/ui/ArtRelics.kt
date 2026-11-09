package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * The five Soul Vault relics: scythe, hourglass, potion, quill, grimoire. Chunky silhouettes so they read at ~50 px.
 * Lit = bright steel / brass / glass with a sickly green glow, unlit = a dark silhouette with a faint cold rim.
 */
internal object Relics {
    private val GREEN = Portraits.GREEN

    fun icon(index: Int, size: Int, lit: Boolean): Bitmap = Art.bitmap(size) { c, s ->
        if (lit) { Kit.aura(c, s / 2, s / 2, s * 0.5f, GREEN, 0.55f); Kit.aura(c, s / 2, s / 2, s * 0.30f, GREEN, 0.25f) }
        when (index) {
            0 -> scythe(c, s, lit)
            1 -> hourglass(c, s, lit)
            2 -> potion(c, s, lit)
            3 -> quill(c, s, lit)
            else -> grimoire(c, s, lit)
        }
    }

    private fun ink(lit: Boolean) = if (lit) 0xFF040504.toInt() else 0xFF020203.toInt()

    /** Fill + outline + light edges for one solid piece. */
    private fun solid(c: Canvas, p: Path, s: Float, lit: Boolean, base: Int, dark: Int = 0xFF0C0D10.toInt()) {
        val b = Kit.bounds(p)
        val fp = Kit.fill()
        fp.shader = if (lit) Kit.metal(b.left, b.top, b.right, b.bottom, base, 0.6f, 0.28f) else LinearGradient(b.left, b.top, b.right, b.bottom, 0xFF2A2D33.toInt(), dark, Shader.TileMode.CLAMP)
        c.drawPath(p, fp)
        if (lit) Kit.weather(c, p, s, 0.35f, 0.2f, 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 3)
        c.drawPath(p, Kit.stroke(ink(lit), s * 0.016f))
        Kit.edgeLight(c, p, -s * 0.012f, -s * 0.012f, Kit.fill(if (lit) 0xCCF2F4E8.toInt() else 0x66707884))
        if (lit) Kit.edgeLight(c, p, s * 0.015f, s * 0.015f, Kit.fill(Kit.al(GREEN, 0.55f)))
    }

    private fun scythe(c: Canvas, s: Float, lit: Boolean) {
        val staff = Kit.stroke(ink(lit), s * 0.07f, Paint.Cap.BUTT)
        c.drawLine(s * 0.30f, s * 0.95f, s * 0.70f, s * 0.12f, staff)
        staff.color = if (lit) 0xFF7A5634.toInt() else 0xFF22242A.toInt(); staff.strokeWidth = s * 0.045f
        c.drawLine(s * 0.30f, s * 0.95f, s * 0.70f, s * 0.12f, staff)
        c.drawLine(s * 0.285f, s * 0.95f, s * 0.685f, s * 0.12f, Kit.stroke(if (lit) 0xFFB08A58.toInt() else 0x33808890, s * 0.012f, Paint.Cap.BUTT))
        val blade = Kit.path(s, "M.70,.14 C.56,.03 .30,.06 .10,.30 C.32,.20 .54,.20 .72,.30 Z")
        solid(c, blade, s, lit, 0xFFB8C4CC.toInt())
        if (lit) { val edge = Kit.path(s, "M.10,.30 C.32,.20 .54,.20 .72,.30"); c.drawPath(edge, Kit.stroke(Kit.lift(GREEN, 0.5f), s * 0.016f)) }
        c.drawRoundRect(RectF(s * 0.64f, s * 0.14f, s * 0.76f, s * 0.24f), s * 0.02f, s * 0.02f, Kit.fill(if (lit) 0xFF6A665F.toInt() else 0xFF14151A.toInt()))
    }

    private fun hourglass(c: Canvas, s: Float, lit: Boolean) {
        val glass = Kit.path(s, "M.28,.17 L.72,.17 C.72,.31 .56,.42 .525,.5 C.56,.58 .72,.69 .72,.83 L.28,.83 C.28,.69 .44,.58 .475,.5 C.44,.42 .28,.31 .28,.17 Z")
        val b = Kit.bounds(glass)
        val gp = Kit.fill(); gp.shader = if (lit) LinearGradient(b.left, 0f, b.right, 0f, intArrayOf(0xAA9ADCC0.toInt(), 0x55DFFFF0, 0xAA4A8C70.toInt()), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP) else LinearGradient(b.left, 0f, b.right, 0f, 0xFF20232A.toInt(), 0xFF0C0D10.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(glass, gp)
        if (lit) {
            c.save(); c.clipPath(glass)
            val sand = Kit.fill(); sand.shader = LinearGradient(0f, s * 0.2f, 0f, s * 0.85f, intArrayOf(Kit.lift(GREEN, 0.3f), GREEN, 0xFF2E7A12.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRect(0f, s * 0.31f, s, s * 0.46f, sand)                                          // sand left in the top bulb
            val pile = Path(); pile.moveTo(s * 0.5f, s * 0.60f); pile.lineTo(s * 0.74f, s * 0.84f); pile.lineTo(s * 0.26f, s * 0.84f); pile.close(); c.drawPath(pile, sand)
            c.drawLine(s * 0.5f, s * 0.46f, s * 0.5f, s * 0.62f, Kit.stroke(Kit.lift(GREEN, 0.5f), s * 0.012f))
            c.restore()
            Kit.halo(c, s * 0.5f, s * 0.7f, s * 0.2f, GREEN, 0.5f)
        }
        c.drawPath(glass, Kit.stroke(ink(lit), s * 0.016f))
        Kit.edgeLight(c, glass, -s * 0.01f, -s * 0.01f, Kit.fill(if (lit) 0xDDF2FFF0.toInt() else 0x55707884))
        for (y in floatArrayOf(0.10f, 0.83f)) {
            val cap = Kit.path(s, "M.20,${y + 0.0} L.80,${y + 0.0} L.80,${y + 0.07} L.20,${y + 0.07} Z")
            solid(c, cap, s, lit, 0xFFB0893A.toInt())
        }
        for (x in floatArrayOf(0.235f, 0.765f)) c.drawLine(s * x, s * 0.17f, s * x, s * 0.83f, Kit.stroke(if (lit) 0xFF8A6C2C.toInt() else 0xFF1A1C21.toInt(), s * 0.018f, Paint.Cap.BUTT))
    }

    private fun potion(c: Canvas, s: Float, lit: Boolean) {
        val flask = Path()
        flask.addCircle(s * 0.5f, s * 0.63f, s * 0.27f, Path.Direction.CW)
        flask.addRect(s * 0.43f, s * 0.20f, s * 0.57f, s * 0.46f, Path.Direction.CW)
        flask.addRoundRect(RectF(s * 0.39f, s * 0.19f, s * 0.61f, s * 0.26f), s * 0.02f, s * 0.02f, Path.Direction.CW)
        val b = Kit.bounds(flask)
        val gp = Kit.fill(); gp.shader = if (lit) RadialGradient(s * 0.42f, s * 0.55f, s * 0.4f, intArrayOf(0xFFD6FFA8.toInt(), 0xFF5FC81E.toInt(), 0xFF1E5A0C.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP) else LinearGradient(b.left, b.top, b.right, b.bottom, 0xFF20232A.toInt(), 0xFF0A0B0E.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(flask, gp)
        if (lit) {
            Kit.halo(c, s * 0.5f, s * 0.63f, s * 0.3f, GREEN, 0.45f)
            c.drawCircle(s * 0.58f, s * 0.52f, s * 0.018f, Kit.fill(0xAAFFFFFF.toInt())); c.drawCircle(s * 0.45f, s * 0.60f, s * 0.012f, Kit.fill(0x88FFFFFF.toInt()))
        }
        c.drawPath(flask, Kit.stroke(ink(lit), s * 0.018f))
        Kit.edgeLight(c, flask, -s * 0.012f, -s * 0.012f, Kit.fill(if (lit) 0xDDF2FFF0.toInt() else 0x55707884))
        Kit.miniSkull(c, s * 0.5f, s * 0.64f, s * 0.12f, if (lit) 0xFF123408.toInt() else 0xFF2A2D33.toInt(), if (lit) 0xFFCFFF9A.toInt() else 0xFF2A2D33.toInt(), lit)
        val cork = Kit.path(s, "M.43,.10 L.57,.10 L.58,.2 L.42,.2 Z")
        solid(c, cork, s, lit, 0xFF9A7448.toInt())
    }

    private fun quill(c: Canvas, s: Float, lit: Boolean) {
        val vane = Kit.path(s, "M.22,.92 C.28,.62 .50,.30 .82,.10 C.88,.38 .70,.66 .40,.80 Z")
        solid(c, vane, s, lit, 0xFF4A4A78.toInt(), 0xFF08080C.toInt())
        // barbs and shaft
        c.save(); c.clipPath(vane)
        val barb = Kit.stroke(if (lit) 0x66000000 else 0x44000000, s * 0.012f)
        for (i in 0..7) { val t = 0.12f + i * 0.1f; c.drawLine(s * (0.22f + 0.6f * t), s * (0.92f - 0.82f * t), s * (0.22f + 0.6f * t + 0.14f), s * (0.92f - 0.82f * t + 0.16f), barb) }
        c.restore()
        c.drawLine(s * 0.2f, s * 0.94f, s * 0.82f, s * 0.10f, Kit.stroke(if (lit) 0xFFE8E2C8.toInt() else 0xFF3A3D45.toInt(), s * 0.018f))
        // nib and an ink drop
        val nib = Kit.path(s, "M.20,.96 L.24,.86 L.30,.92 Z"); solid(c, nib, s, lit, 0xFFB0893A.toInt())
        if (lit) { c.drawCircle(s * 0.16f, s * 0.96f, s * 0.03f, Kit.fill(Kit.lift(GREEN, 0.2f))); Kit.halo(c, s * 0.16f, s * 0.96f, s * 0.12f, GREEN, 0.7f) }
    }

    private fun grimoire(c: Canvas, s: Float, lit: Boolean) {
        val cover = Kit.path(s, "M.22,.16 L.80,.16 Q.84,.16 .84,.20 L.84,.80 Q.84,.84 .80,.84 L.22,.84 Q.17,.84 .17,.79 L.17,.21 Q.17,.16 .22,.16 Z")
        solid(c, cover, s, lit, 0xFF5A3E34.toInt(), 0xFF0E0F13.toInt())
        // spine bands and brass corners
        for (y in floatArrayOf(0.30f, 0.50f, 0.70f)) c.drawLine(s * 0.17f, s * y, s * 0.28f, s * y, Kit.stroke(ink(lit), s * 0.02f, Paint.Cap.BUTT))
        c.drawLine(s * 0.28f, s * 0.17f, s * 0.28f, s * 0.83f, Kit.stroke(ink(lit), s * 0.014f, Paint.Cap.BUTT))
        val brass = if (lit) 0xFFB0893A.toInt() else 0xFF2A2D33.toInt()
        for ((x, y) in listOf(0.20f to 0.19f, 0.81f to 0.19f, 0.20f to 0.81f, 0.81f to 0.81f)) c.drawCircle(s * x, s * y, s * 0.028f, Kit.fill(brass))
        // sigil: a ring with a five-pointed star, burning green when lit
        val cx = s * 0.56f; val cy = s * 0.5f; val rr = s * 0.17f
        val star = Path()
        for (i in 0..4) { val a = -1.5708 + i * 2.5133; val x = cx + (rr * kotlin.math.cos(a)).toFloat(); val y = cy + (rr * kotlin.math.sin(a)).toFloat(); if (i == 0) star.moveTo(x, y) else star.lineTo(x, y) }
        star.close()
        val col = if (lit) GREEN else 0xFF2A2D33.toInt()
        if (lit) Kit.halo(c, cx, cy, s * 0.26f, GREEN, 0.5f)
        c.drawCircle(cx, cy, rr * 1.15f, Kit.stroke(col, s * 0.016f)); c.drawPath(star, Kit.stroke(col, s * 0.016f, Paint.Cap.ROUND, Paint.Join.MITER))
        if (lit) c.drawPath(star, Kit.stroke(Kit.lift(GREEN, 0.6f), s * 0.006f, Paint.Cap.ROUND, Paint.Join.MITER))
        // clasp
        c.drawRoundRect(RectF(s * 0.80f, s * 0.44f, s * 0.89f, s * 0.56f), s * 0.02f, s * 0.02f, Kit.fill(brass))
        c.drawRoundRect(RectF(s * 0.80f, s * 0.44f, s * 0.89f, s * 0.56f), s * 0.02f, s * 0.02f, Kit.stroke(ink(lit), s * 0.01f))
    }
}

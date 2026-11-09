package com.tjshea.neonreaper.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface
import com.tjshea.neonreaper.engine.ReaperConfig
import kotlin.math.atan2

/**
 * The royals A K Q J 10: heavy carved letters (oxidised iron, tarnished brass, violet-black iron, bone, charred stone) with a deep
 * side wall, a bevelled metal frame, a recessed panel lit from inside in the symbol's accent colour, hairline cracks, thorns.
 * Readable at ~130 px and when smeared by the motion blur: big flat tonal masses, one bright accent line, strong silhouette.
 */
internal object Royals {
    private class Style(
        val label: String,
        val accent: Int,      // glow colour of the inner channel
        val lit: Int,         // metal highlight
        val body: Int,        // metal mid tone
        val panel: Int,       // recessed panel
        val wallNear: Int, val wallFar: Int,   // side wall at the face / far end
        val stain: Int,       // tint of the age blotches
        val cracks: Int,      // 0 = dark cracks, else colour leaking through them
        val seed: Int
    )

    private val STYLES = mapOf(
        ReaperConfig.ACE to Style("A", 0xFFFF2A3F.toInt(), 0xFFD5D8E2.toInt(), 0xFF7A8094.toInt(), 0xFF1A0709.toInt(),
            0xFF3A3D48.toInt(), 0xFF0A0A0D.toInt(), 0xFF3A0A0E.toInt(), 0xFFFF3A44.toInt(), 1),
        ReaperConfig.KING to Style("K", 0xFFFFC24A.toInt(), 0xFFFFEBA6.toInt(), 0xFFC49A40.toInt(), 0xFF150F05.toInt(),
            0xFF7A5A20.toInt(), 0xFF1A1105.toInt(), 0xFF2B3A10.toInt(), 0, 2),
        ReaperConfig.QUEEN to Style("Q", 0xFFB27CFF.toInt(), 0xFFD6C6F6.toInt(), 0xFF7A60B4.toInt(), 0xFF110820.toInt(),
            0xFF3E2D66.toInt(), 0xFF0C0816.toInt(), 0xFF1B0F3A.toInt(), 0xFFB27CFF.toInt(), 3),
        ReaperConfig.JACK to Style("J", 0xFF62F0EA.toInt(), 0xFFFFFBEA.toInt(), 0xFFD9D0B2.toInt(), 0xFF041214.toInt(),
            0xFF9A9078.toInt(), 0xFF25210F.toInt(), 0xFF3B4A3C.toInt(), 0, 4),
        ReaperConfig.TEN to Style("10", 0xFFFF8A2B.toInt(), 0xFFB9A294.toInt(), 0xFF76665C.toInt(), 0xFF130703.toInt(),
            0xFF3A271D.toInt(), 0xFF080403.toInt(), 0xFF2A0F06.toInt(), 0xFFFF7A1A.toInt(), 5)
    )

    private val serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    fun has(sym: Int) = STYLES.containsKey(sym)

    /**
     * Glyph outline in pixels: horizontally centred on [cx], the CAP HEIGHT centred on [cy] (a Q's tail hangs below the baseline).
     * All royals share one cap height so the set looks like a family.
     */
    private fun glyph(label: String, s: Float, cx: Float, cy: Float): Path {
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serifBold; textSize = 100f }
        val ref = Path(); tp.getTextPath("H", 0, 1, 0f, 0f, ref)
        val rb = Kit.bounds(ref)
        val raw = Path(); tp.getTextPath(label, 0, label.length, 0f, 0f, raw)
        val b = Kit.bounds(raw)
        var sc = s * 0.64f / rb.height()
        val maxW = if (label.length > 1) s * 0.80f else s * 0.66f
        if (b.width() * sc > maxW) sc = maxW / b.width()
        if (b.height() * sc > s * 0.70f) sc = s * 0.70f / b.height()      // J / Q hang below the baseline
        // baseline sits half a cap height below the centre line, then the whole glyph is kept inside [0.07 s, 0.795 s]
        var base = cy + rb.height() * sc / 2
        val bot = base + b.bottom * sc
        if (bot > s * 0.795f) base -= bot - s * 0.795f
        val top = base + b.top * sc
        if (top < s * 0.07f) base += s * 0.07f - top
        val m = Matrix()
        m.setScale(sc, sc); m.postTranslate(cx - (b.left + b.right) / 2 * sc, base)
        raw.transform(m)
        return Kit.grown(raw, s * 0.004f)
    }

    fun paint(c: Canvas, s: Float, sym: Int) {
        val st = STYLES[sym] ?: return
        val cx = s * 0.5f; val cy = s * 0.42f
        // 1. aura: the letter glows against the black drum
        Kit.aura(c, cx, s * 0.50f, s * 0.50f, st.accent, 0.50f)
        Kit.aura(c, cx, s * 0.48f, s * 0.30f, st.accent, 0.22f)
        val face = glyph(st.label, s, cx, cy)
        val b = Kit.bounds(face)
        val depth = (s * 0.05f).toInt().coerceAtLeast(3)
        val k = s / 256f
        // 2. contact shadow, then the thorn vine with a little skull behind the letter
        Kit.groundShadow(c, cx + s * 0.03f, s * 0.87f, b.width() * 0.58f, s * 0.04f)
        vine(c, s, cx, b.left - s * 0.03f, b.right + s * 0.03f, s * 0.86f, st)
        // 3. deep side wall running to the lower right
        val ink = Kit.stroke(0xFF050404.toInt(), s * 0.016f, Paint.Cap.ROUND, Paint.Join.ROUND)
        c.save(); c.translate(0.55f * depth, 0.85f * depth); c.drawPath(face, ink); c.restore()
        Kit.extrude(c, face, 0.55f, 0.85f, depth, st.wallNear, st.wallFar)
        val bounce = Kit.fill(Kit.al(st.accent, 0.22f))
        c.save(); c.translate(0.55f * depth, 0.85f * depth); c.drawPath(face, bounce); c.restore()
        c.drawPath(face, ink)
        // 4. face: metal ramp + age
        val fp = Kit.fill(); fp.shader = Kit.metal(b.left, b.top, b.right, b.bottom, st.body, 0.6f, 0.30f)
        c.drawPath(face, fp)
        Kit.weather(c, face, s, 0.45f, 0.28f, st.stain, Kit.lift(st.lit, 0.3f), st.seed)
        // 5. recessed, lit panel inside a metal frame (nested strokes clipped to the glyph)
        val band = s * 0.027f; val groove = s * 0.008f; val glowW = s * 0.017f
        c.save(); c.clipPath(face)
        fp.shader = Kit.volume(b.left, b.top, b.left + b.width() * 0.8f, b.bottom, Kit.mul(st.panel, 0.5f), st.panel, Kit.mix(st.panel, st.accent, 0.14f))
        c.drawPath(face, fp); fp.shader = null
        Kit.tex(c, b, Kit.noise, 1.1f * k + 0.3f, 0.10f, 0, Kit.ADD, st.seed * 9f, st.seed * 13f)
        val sp = Kit.stroke(Kit.al(st.accent, 0.48f), (band + groove + glowW) * 2, Paint.Cap.BUTT, Paint.Join.MITER)
        sp.maskFilter = BlurMaskFilter(glowW * 0.7f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(face, sp); sp.maskFilter = null
        sp.color = Kit.lift(st.accent, 0.3f); sp.strokeWidth = (band + groove) * 2; c.drawPath(face, sp)
        sp.shader = Kit.metal(b.left, b.top, b.right, b.bottom, st.body, 0.62f, 0.30f); sp.color = 0xFFFFFFFF.toInt(); sp.strokeWidth = band * 2
        c.drawPath(face, sp)
        c.restore()
        // 6. bevel: bright upper-left edge, dark lower-right edge, accent rim light on the far side
        val d = s * 0.0075f
        Kit.edgeLight(c, face, -d, -d, Kit.fill(Kit.al(Kit.lift(st.lit, 0.6f), 0.95f)))
        Kit.edgeLight(c, face, d * 1.2f, d * 1.2f, Kit.fill(Kit.al(Kit.mul(st.wallFar, 0.8f), 0.8f)))
        Kit.edgeLight(c, face, d * 0.55f, d * 0.55f, Kit.fill(Kit.al(Kit.lift(st.accent, 0.1f), 0.6f)))
        // 7. cracks across the metal
        c.save(); c.clipPath(face)
        val w = b.width(); val h = b.height()
        val cw = s * 0.0052f
        Kit.crack(c, st.seed * 101L, b.left + w * 0.16f, b.top + h * 0.04f, b.left + w * 0.44f, b.top + h * 0.60f, cw, st.cracks, 0.07f, 1)
        Kit.crack(c, st.seed * 211L, b.left + w * 0.88f, b.top + h * 0.34f, b.left + w * 0.60f, b.top + h * 0.97f, cw * 0.9f, st.cracks, 0.07f, 1)
        c.restore()
    }

    /** A wavy thorned vine running under the letter with a bone skull in the middle: stem, thorns, highlights. */
    private fun vine(c: Canvas, s: Float, cx: Float, x0: Float, x1: Float, y: Float, st: Style) {
        val p = Path()
        p.moveTo(x0, y + s * 0.015f)
        val n = 6
        for (i in 1..n) {
            val x = x0 + (x1 - x0) * i / n
            val cyy = y + (if (i % 2 == 0) -1f else 1f) * s * 0.032f
            p.quadTo(x0 + (x1 - x0) * (i - 0.5f) / n, cyy, x, y + s * 0.015f * (if (i % 2 == 0) 1f else -0.4f))
        }
        val stem = Kit.stroke(0xFF17130E.toInt(), s * 0.03f)
        c.drawPath(p, stem)
        stem.color = Kit.al(Kit.mix(st.body, 0xFF6A5638.toInt(), 0.65f), 0.9f); stem.strokeWidth = s * 0.01f
        c.save(); c.translate(-s * 0.002f, -s * 0.004f); c.drawPath(p, stem); c.restore()
        val pm = PathMeasure(p, false); val len = pm.length
        val pos = FloatArray(2); val tan = FloatArray(2)
        var d = len * 0.05f; var flip = 1f; var i = 0
        while (d < len * 0.97f) {
            pm.getPosTan(d, pos, tan)
            val ang = atan2(tan[1], tan[0]) + flip * 1.3f
            Kit.thorn(c, pos[0], pos[1], ang, s * (0.05f + 0.014f * (i % 2)), s * 0.024f, 0xFF211B14.toInt(), Kit.mix(st.body, 0xFF8A7650.toInt(), 0.5f))
            flip = -flip; d += len * 0.07f; i++
        }
        Kit.miniSkull(c, cx, y - s * 0.002f, s * 0.056f, 0xFFD6CFB8.toInt(), st.accent)
    }
}

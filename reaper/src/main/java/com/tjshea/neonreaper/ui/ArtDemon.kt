package com.tjshea.neonreaper.ui

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/**
 * The Demon: a charcoal-and-crimson horned head with a heavy brow, hot slit eyes, a fanged snarl with fire in its throat,
 * pointed ears and ember light leaking from cracks in its skin.
 */
internal object Demon {
    private val EMBER = 0xFFFF6A1A.toInt()
    private val HOT = 0xFFFFD060.toInt()
    /** Eyes (unit): x1,y1,x2,y2,radius. */
    val EYES = floatArrayOf(0.385f, 0.468f, 0.615f, 0.468f, 0.026f)

    fun paint(c: Canvas, s: Float, live: Boolean) {
        Kit.aura(c, s * 0.5f, s * 0.5f, s * 0.5f, 0xFFFF4A14.toInt(), 0.40f)
        Kit.aura(c, s * 0.5f, s * 0.55f, s * 0.30f, 0xFFD01818.toInt(), 0.30f)
        Kit.groundShadow(c, s * 0.5f, s * 0.97f, s * 0.38f, s * 0.026f, 0.7f)
        mantle(c, s)
        horns(c, s)
        ears(c, s)
        head(c, s, live)
    }

    private fun mantle(c: Canvas, s: Float) {
        val m = Kit.sym(s, "M.5,.76 C.38,.78 .30,.80 .22,.84 C.14,.88 .10,.94 .06,.99 L.5,.99")
        val mb = Kit.bounds(m)
        val p = Kit.fill(); p.shader = LinearGradient(0f, mb.top, 0f, mb.bottom, intArrayOf(0xFF3A2020.toInt(), 0xFF140808.toInt(), 0xFF050202.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(m, p)
        c.save(); c.clipPath(m); Kit.weather(c, m, s, 0.3f, 0.15f, 0xFF000000.toInt(), 0xFFB08070.toInt(), 3); c.restore()
        Kit.edgeLight(c, m, -s * 0.007f, -s * 0.007f, Kit.fill(0x88A06050.toInt()))
        c.drawPath(m, Kit.stroke(0xFF030101.toInt(), s * 0.006f))
        // spiked pauldrons
        for (sx in floatArrayOf(-1f, 1f)) {
            val cx = s * 0.5f + sx * s * 0.36f
            for (i in 0..2) Kit.thorn(c, cx - sx * s * i * 0.07f, s * (0.87f - i * 0.005f), -1.5708f + sx * (0.45f - i * 0.1f), s * (0.12f - i * 0.02f), s * 0.05f, 0xFF2A1612.toInt(), 0xFFC08A70.toInt(), sx * 0.1f)
        }
    }

    private fun horns(c: Canvas, s: Float) {
        val horn = Kit.path(s, "M.30,.37 C.18,.36 .08,.28 .08,.16 C.08,.09 .10,.04 .12,.015 C.15,.07 .16,.16 .22,.22 C.27,.25 .33,.25 .38,.26 Z")
        for (mir in 0..1) {
            val h = if (mir == 0) horn else Kit.mirrored(horn, s * 0.5f)
            val hb = Kit.bounds(h)
            val p = Kit.fill()
            val tipX = if (mir == 0) hb.left else hb.right; val baseX = if (mir == 0) hb.right else hb.left
            p.shader = LinearGradient(baseX, hb.bottom, tipX, hb.top, intArrayOf(0xFF1C0C0A.toInt(), 0xFF4A2418.toInt(), 0xFFB09878.toInt(), 0xFFE8DCC0.toInt()), floatArrayOf(0f, 0.45f, 0.85f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(h, p)
            c.save(); c.clipPath(h)
            // growth ridges with ember in the grooves
            val rg = Kit.stroke(0xCC180606.toInt(), s * 0.008f); val em = Kit.stroke(Kit.al(EMBER, 0.75f), s * 0.0035f)
            for (i in 0..4) {
                val t = 0.25f + i * 0.14f
                val x = if (mir == 0) hb.right - hb.width() * t * 0.9f else hb.left + hb.width() * t * 0.9f
                val y = hb.bottom - hb.height() * t * 1.05f
                c.drawLine(x - s * 0.03f, y + s * 0.03f, x + s * 0.03f, y - s * 0.03f, rg); c.drawLine(x - s * 0.026f, y + s * 0.03f, x + s * 0.034f, y - s * 0.026f, em)
            }
            Kit.weather(c, h, s, 0.35f, 0.15f, 0xFF000000.toInt(), 0xFFFFE0C0.toInt(), 9 + mir)
            c.restore()
            Kit.edgeLight(c, h, if (mir == 0) -s * 0.008f else s * 0.008f, -s * 0.008f, Kit.fill(0x99FFC8A0.toInt()))
            Kit.edgeLight(c, h, if (mir == 0) s * 0.01f else -s * 0.01f, s * 0.01f, Kit.fill(Kit.al(EMBER, 0.5f)))
            c.drawPath(h, Kit.stroke(0xFF050202.toInt(), s * 0.005f))
        }
    }

    private fun ears(c: Canvas, s: Float) {
        val ear = Kit.path(s, "M.27,.38 C.20,.35 .14,.33 .07,.30 C.11,.37 .16,.46 .27,.52 Z")
        for (mir in 0..1) {
            val e = if (mir == 0) ear else Kit.mirrored(ear, s * 0.5f)
            val eb = Kit.bounds(e)
            val p = Kit.fill(); p.shader = LinearGradient(eb.left, 0f, eb.right, 0f, if (mir == 0) intArrayOf(0xFF6A1A14.toInt(), 0xFF2A0807.toInt()) else intArrayOf(0xFF2A0807.toInt(), 0xFF6A1A14.toInt()), null, Shader.TileMode.CLAMP)
            c.drawPath(e, p); c.drawPath(e, Kit.stroke(0xFF050202.toInt(), s * 0.005f))
            Kit.edgeLight(c, e, if (mir == 0) -s * 0.006f else s * 0.006f, -s * 0.006f, Kit.fill(0x88FF9A70.toInt()))
        }
    }

    private fun head(c: Canvas, s: Float, live: Boolean) {
        val head = Kit.sym(s, "M.5,.18 C.38,.18 .28,.24 .26,.35 C.24,.46 .25,.56 .30,.65 C.34,.73 .41,.82 .5,.85")
        val hb = Kit.bounds(head)
        val p = Kit.fill()
        p.shader = RadialGradient(s * 0.42f, s * 0.38f, s * 0.46f, intArrayOf(0xFFD0503A.toInt(), 0xFF8C241A.toInt(), 0xFF3A0C0A.toInt(), 0xFF120404.toInt()), floatArrayOf(0f, 0.38f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(head, p)
        c.save(); c.clipPath(head)
        val blk = 0xFF000000.toInt()
        // sunken cheeks, heavy brow shadow, temple light
        Kit.halo(c, s * 0.31f, s * 0.60f, s * 0.12f, blk, 0.45f, s * 0.10f); Kit.halo(c, s * 0.69f, s * 0.60f, s * 0.12f, blk, 0.55f, s * 0.10f)
        Kit.halo(c, s * 0.5f, s * 0.40f, s * 0.20f, blk, 0.35f, s * 0.04f)
        Kit.halo(c, s * 0.40f, s * 0.26f, s * 0.14f, 0xFFFFB090.toInt(), 0.25f, s * 0.08f)
        // fire in the throat lights the chin from below
        Kit.halo(c, s * 0.5f, s * 0.78f, s * 0.22f, EMBER, 0.38f, s * 0.12f)
        Kit.weather(c, head, s, 0.4f, 0.18f, 0xFF1A0202.toInt(), 0xFFFFC0A0.toInt(), 6)
        c.restore()
        c.drawPath(head, Kit.stroke(0xFF050202.toInt(), s * 0.006f))
        // brows: angry slabs sloping to the nose
        for (mir in 0..1) {
            val brow = Kit.path(s, "M.255,.345 C.31,.335 .42,.385 .485,.445 L.485,.475 C.42,.44 .33,.405 .27,.41 Z")
            val b = if (mir == 0) brow else Kit.mirrored(brow, s * 0.5f)
            c.drawPath(b, Kit.fill(0xFF2A0807.toInt()))
            Kit.edgeLight(c, b, if (mir == 0) -s * 0.005f else s * 0.005f, -s * 0.006f, Kit.fill(0xCCFF9070.toInt()))
            c.drawPath(b, Kit.stroke(0xFF050202.toInt(), s * 0.004f))
        }
        // eyes: slanted slits of molten light
        if (!live) for (i in 0..1) eye(c, s, EYES[i * 2] * s, EYES[i * 2 + 1] * s, i == 1, 1f)
        // nose: ridge highlight and two nostril slits
        c.drawLine(s * 0.5f, s * 0.47f, s * 0.5f, s * 0.57f, Kit.stroke(0x33FFB090, s * 0.012f))
        for (sx in floatArrayOf(-1f, 1f)) { val n = Path(); n.moveTo(s * 0.5f + sx * s * 0.012f, s * 0.585f); n.quadTo(s * 0.5f + sx * s * 0.04f, s * 0.575f, s * 0.5f + sx * s * 0.05f, s * 0.59f); c.drawPath(n, Kit.stroke(0xFF050202.toInt(), s * 0.009f)) }
        // glowing cracks
        Kit.crack(c, 61, s * 0.5f, s * 0.19f, s * 0.46f, s * 0.35f, s * 0.006f, EMBER, 0.09f, 1)
        Kit.crack(c, 67, s * 0.66f, s * 0.52f, s * 0.72f, s * 0.64f, s * 0.006f, EMBER, 0.09f, 1)
        Kit.crack(c, 71, s * 0.33f, s * 0.54f, s * 0.29f, s * 0.64f, s * 0.005f, EMBER, 0.09f, 0)
        Kit.crack(c, 73, s * 0.40f, s * 0.20f, s * 0.34f, s * 0.30f, s * 0.004f, EMBER, 0.09f, 0)
        mouth(c, s)
    }

    /** One glowing slit eye centred at (x,y) pixels (also used live by [Characters] with a different [glow]). */
    fun eye(c: Canvas, s: Float, x: Float, y: Float, mirror: Boolean, glow: Float) {
        c.save(); c.translate(x, y); if (mirror) c.scale(-1f, 1f)
        Kit.halo(c, 0f, 0f, s * 0.12f, 0xFFFF3A10.toInt(), 0.55f * glow, s * 0.07f)
        val e = Path(); e.moveTo(-s * 0.075f, -s * 0.026f); e.quadTo(s * 0.0f, -s * 0.02f, s * 0.08f, s * 0.026f); e.quadTo(s * 0.0f, s * 0.03f, -s * 0.075f, -s * 0.026f); e.close()
        val ep = Kit.fill(); ep.shader = RadialGradient(0f, 0f, s * 0.08f, intArrayOf(HOT, 0xFFFF7A10.toInt(), 0xFFC01808.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(e, ep); c.drawPath(e, Kit.stroke(0xFF1A0202.toInt(), s * 0.004f))
        c.drawRect(-s * 0.005f, -s * 0.02f, s * 0.005f, s * 0.024f, Kit.fill(0xFF000000.toInt()))
        c.restore()
    }

    private fun mouth(c: Canvas, s: Float) {
        val m = Kit.path(s, "M.315,.665 Q.5,.695 .685,.665 Q.5,.80 .315,.665 Z")
        val mb = Kit.bounds(m)
        val p = Kit.fill(); p.shader = RadialGradient(s * 0.5f, s * 0.725f, s * 0.2f, intArrayOf(0xFFFFB040.toInt(), 0xFFE04810.toInt(), 0xFF380806.toInt(), 0xFF080202.toInt()), floatArrayOf(0f, 0.25f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(m, p)
        c.save(); c.clipPath(m)
        // teeth: upper fangs hang from the top lip, lower ones rise from the bottom lip
        val tp = Kit.fill()
        for (i in 0..6) {
            val x = s * (0.355f + i * 0.0475f); val big = i == 1 || i == 5
            val len = s * (if (big) 0.075f else 0.036f)
            tp.shader = LinearGradient(0f, s * 0.68f, 0f, s * 0.68f + len, 0xFFF0E4C8.toInt(), 0xFFA89A78.toInt(), Shader.TileMode.CLAMP)
            val t = Path(); t.moveTo(x - s * 0.017f, s * 0.678f); t.lineTo(x, s * 0.678f + len); t.lineTo(x + s * 0.017f, s * 0.678f); t.close(); c.drawPath(t, tp)
        }
        for (i in 0..4) {
            val x = s * (0.38f + i * 0.06f); val len = s * (if (i == 0 || i == 4) 0.05f else 0.028f)
            tp.shader = LinearGradient(0f, s * 0.745f, 0f, s * 0.745f - len, 0xFFA89A78.toInt(), 0xFFF0E4C8.toInt(), Shader.TileMode.CLAMP)
            val t = Path(); t.moveTo(x - s * 0.017f, s * 0.752f); t.lineTo(x, s * 0.752f - len); t.lineTo(x + s * 0.017f, s * 0.752f); t.close(); c.drawPath(t, tp)
        }
        c.restore()
        c.drawPath(m, Kit.stroke(0xFF050202.toInt(), s * 0.006f))
        Kit.halo(c, s * 0.5f, s * 0.73f, s * 0.2f, EMBER, 0.25f, s * 0.08f)
    }
}

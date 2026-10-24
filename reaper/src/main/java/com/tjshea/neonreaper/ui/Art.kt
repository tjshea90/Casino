package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.tjshea.neonreaper.engine.ReaperConfig
import kotlin.math.cos
import kotlin.math.sin

/** Neon palette. */
object Neon {
    const val GREEN = 0xFF76FF03.toInt()
    const val GREEN_DIM = 0xFF2E7D0A.toInt()
    const val RED = 0xFFFF1744.toInt()
    const val RED_DIM = 0xFF7F0019.toInt()
    const val PURPLE = 0xFFD500F9.toInt()
    const val CYAN = 0xFF00E5FF.toInt()
    const val ORANGE = 0xFFFF9100.toInt()
    const val BONE = 0xFFE8E4D8.toInt()
    const val BLACK = 0xFF050505.toInt()
}

/**
 * All artwork is painted procedurally with paths and glows. Static pieces are
 * rendered once into bitmaps; animated details (glowing eyes, flames) are drawn
 * live on top as cheap primitives.
 */
object Art {
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    fun bitmap(size: Int, h: Int = size, draw: (Canvas, Float) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(size, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), size.toFloat())
        return b
    }

    private fun glowPaint(color: Int, radius: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; maskFilter = BlurMaskFilter(radius.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
    }

    /** Neon outline: wide blurred glow, colored stroke, thin hot core. */
    fun neonStroke(c: Canvas, path: Path, color: Int, w: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        p.color = color; p.alpha = 150; p.strokeWidth = w * 3.2f; p.maskFilter = BlurMaskFilter(w * 2.5f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(path, p)
        p.maskFilter = null; p.alpha = 255; p.strokeWidth = w
        c.drawPath(path, p)
        p.color = Color.WHITE; p.alpha = 190; p.strokeWidth = w * 0.35f
        c.drawPath(path, p)
    }

    fun eyes(c: Canvas, x1: Float, x2: Float, y: Float, r: Float, color: Int = Neon.RED, intensity: Float = 1f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(0f, 0f, 1f, intArrayOf(color, 0), null, Shader.TileMode.CLAMP)
        for (x in floatArrayOf(x1, x2)) {
            c.save(); c.translate(x, y); c.scale(r * 3.5f * intensity, r * 2.2f * intensity)
            p.alpha = (140 * intensity.coerceAtMost(1f)).toInt()
            c.drawCircle(0f, 0f, 1f, p)
            c.restore()
        }
        p.shader = null
        for (x in floatArrayOf(x1, x2)) {
            p.color = color
            c.drawOval(x - r * 1.2f, y - r * 0.55f, x + r * 1.2f, y + r * 0.55f, p)
            p.color = 0xFFFFE0E0.toInt()
            c.drawOval(x - r * 0.45f, y - r * 0.22f, x + r * 0.45f, y + r * 0.22f, p)
        }
    }

    // ------------------------------------------------------------------ characters
    /** Hooded robe silhouette in a unit box scaled to w×h at (x,y). */
    private fun hoodPath(x: Float, y: Float, w: Float, h: Float): Path = Path().apply {
        moveTo(x + w * 0.50f, y + h * 0.02f)
        cubicTo(x + w * 0.20f, y + h * 0.04f, x + w * 0.12f, y + h * 0.30f, x + w * 0.14f, y + h * 0.48f)
        cubicTo(x + w * 0.02f, y + h * 0.60f, x + w * 0.0f, y + h * 0.85f, x + w * 0.02f, y + h * 1.0f)
        lineTo(x + w * 0.16f, y + h * 0.94f); lineTo(x + w * 0.28f, y + h * 1.0f); lineTo(x + w * 0.40f, y + h * 0.93f)
        lineTo(x + w * 0.52f, y + h * 1.0f); lineTo(x + w * 0.64f, y + h * 0.94f); lineTo(x + w * 0.76f, y + h * 1.0f)
        lineTo(x + w * 0.88f, y + h * 0.93f); lineTo(x + w * 0.98f, y + h * 1.0f)
        cubicTo(x + w * 1.0f, y + h * 0.85f, x + w * 0.98f, y + h * 0.60f, x + w * 0.86f, y + h * 0.48f)
        cubicTo(x + w * 0.88f, y + h * 0.30f, x + w * 0.80f, y + h * 0.04f, x + w * 0.50f, y + h * 0.02f)
        close()
    }

    private fun facePath(x: Float, y: Float, w: Float, h: Float): Path = Path().apply {
        moveTo(x + w * 0.5f, y + h * 0.13f)
        cubicTo(x + w * 0.30f, y + h * 0.14f, x + w * 0.27f, y + h * 0.30f, x + w * 0.30f, y + h * 0.42f)
        cubicTo(x + w * 0.34f, y + h * 0.50f, x + w * 0.42f, y + h * 0.53f, x + w * 0.5f, y + h * 0.53f)
        cubicTo(x + w * 0.58f, y + h * 0.53f, x + w * 0.66f, y + h * 0.50f, x + w * 0.70f, y + h * 0.42f)
        cubicTo(x + w * 0.73f, y + h * 0.30f, x + w * 0.70f, y + h * 0.14f, x + w * 0.5f, y + h * 0.13f)
        close()
    }

    /** Eye positions (relative to the character box) for live glowing overlays. */
    val REAPER_EYES = floatArrayOf(0.42f, 0.58f, 0.33f)
    val HOOD_EYES = floatArrayOf(0.42f, 0.58f, 0.335f)

    fun reaper(c: Canvas, x: Float, y: Float, w: Float, h: Float, rim: Int = Neon.GREEN, withScythe: Boolean = true) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (withScythe) {
            // staff
            p.color = 0xFF2A1E14.toInt(); p.strokeWidth = w * 0.045f; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            c.drawLine(x + w * 0.80f, y + h * 0.98f, x + w * 0.90f, y + h * 0.08f, p)
            // blade
            val blade = Path().apply {
                moveTo(x + w * 0.90f, y + h * 0.08f)
                cubicTo(x + w * 0.70f, y - h * 0.02f, x + w * 0.35f, y + h * 0.0f, x + w * 0.12f, y + h * 0.16f)
                cubicTo(x + w * 0.38f, y + h * 0.07f, x + w * 0.66f, y + h * 0.08f, x + w * 0.90f, y + h * 0.15f)
                close()
            }
            p.style = Paint.Style.FILL
            p.shader = LinearGradient(x + w * 0.12f, 0f, x + w * 0.9f, 0f, 0xFFB0BEC5.toInt(), 0xFF37474F.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(blade, p); p.shader = null
            neonStroke(c, blade, rim, w * 0.012f)
        }
        val robe = hoodPath(x + w * 0.08f, y + h * 0.1f, w * 0.84f, h * 0.9f)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, y, 0f, y + h, intArrayOf(0xFF1A1A22.toInt(), 0xFF0A0A0E.toInt(), 0xFF000000.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(robe, p); p.shader = null
        // fold lines
        p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.01f; p.color = 0x55303040
        for (k in 0..3) c.drawLine(x + w * (0.3f + k * 0.13f), y + h * 0.58f, x + w * (0.24f + k * 0.17f), y + h * 0.97f, p)
        neonStroke(c, robe, rim, w * 0.012f)
        // face void + faint skull
        val face = facePath(x + w * 0.08f, y + h * 0.1f, w * 0.84f, h * 0.9f)
        p.style = Paint.Style.FILL; p.color = 0xFF000000.toInt()
        c.drawPath(face, p)
        p.shader = RadialGradient(x + w * 0.5f, y + h * 0.36f, w * 0.14f, 0x66B0A890, 0x00000000, Shader.TileMode.CLAMP)
        c.drawOval(x + w * 0.38f, y + h * 0.25f, x + w * 0.62f, y + h * 0.48f, p); p.shader = null
        // teeth
        p.color = 0x88C8C0A8.toInt(); p.strokeWidth = w * 0.006f; p.style = Paint.Style.STROKE
        for (k in 0..4) c.drawLine(x + w * (0.45f + k * 0.025f), y + h * 0.43f, x + w * (0.45f + k * 0.025f), y + h * 0.46f, p)
        // bony hand on staff
        p.style = Paint.Style.FILL; p.color = 0xFFCFC8B4.toInt()
        if (withScythe) for (k in 0..3) c.drawRoundRect(x + w * 0.79f, y + h * (0.55f + k * 0.03f), x + w * 0.9f, y + h * (0.57f + k * 0.03f), w * 0.01f, w * 0.01f, p)
        eyes(c, x + w * REAPER_EYES[0], x + w * REAPER_EYES[1], y + h * REAPER_EYES[2], w * 0.022f)
    }

    fun hooded(c: Canvas, x: Float, y: Float, w: Float, h: Float, rim: Int = Neon.RED) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val robe = hoodPath(x + w * 0.06f, y + h * 0.1f, w * 0.88f, h * 0.9f)
        p.shader = LinearGradient(0f, y, 0f, y + h, intArrayOf(0xFF2A0612.toInt(), 0xFF12030A.toInt(), 0xFF000000.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(robe, p); p.shader = null
        neonStroke(c, robe, rim, w * 0.012f)
        val face = facePath(x + w * 0.06f, y + h * 0.1f, w * 0.88f, h * 0.9f)
        p.color = 0xFF000000.toInt(); c.drawPath(face, p)
        // hands clasping a candle
        p.color = 0xFF1A0508.toInt()
        c.drawOval(x + w * 0.36f, y + h * 0.66f, x + w * 0.64f, y + h * 0.78f, p)
        p.color = 0xFFEDE3C8.toInt()
        c.drawRect(x + w * 0.47f, y + h * 0.56f, x + w * 0.53f, y + h * 0.70f, p)
        eyes(c, x + w * HOOD_EYES[0], x + w * HOOD_EYES[1], y + h * HOOD_EYES[2], w * 0.024f)
    }

    fun flame(c: Canvas, cx: Float, base: Float, s: Float, color: Int, flicker: Float = 0f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = Path().apply {
            moveTo(cx, base)
            cubicTo(cx - s * 0.5f, base - s * 0.2f, cx - s * 0.3f, base - s * 0.8f, cx + flicker * s * 0.2f, base - s * 1.35f)
            cubicTo(cx + s * 0.3f, base - s * 0.8f, cx + s * 0.5f, base - s * 0.2f, cx, base)
            close()
        }
        p.color = color; p.maskFilter = BlurMaskFilter(s * 0.35f, BlurMaskFilter.Blur.NORMAL); p.alpha = 160
        c.drawPath(path, p)
        p.maskFilter = null; p.alpha = 255
        p.shader = LinearGradient(0f, base, 0f, base - s * 1.3f, intArrayOf(Color.WHITE, color, 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
    }

    // ------------------------------------------------------------------ reel symbols
    fun symbol(sym: Int, size: Int): Bitmap = bitmap(size) { c, s ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (sym) {
            ReaperConfig.WILD -> {
                p.shader = RadialGradient(s / 2, s / 2, s * 0.55f, intArrayOf(0x5576FF03, 0x00000000), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, s, s, p); p.shader = null
                reaper(c, s * 0.08f, s * 0.02f, s * 0.84f, s * 0.80f)
                label(c, s, "WILD", Neon.GREEN)
            }
            ReaperConfig.HOODED -> {
                p.shader = RadialGradient(s / 2, s / 2, s * 0.55f, intArrayOf(0x55FF1744, 0x00000000), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, s, s, p); p.shader = null
                hooded(c, s * 0.1f, s * 0.03f, s * 0.8f, s * 0.94f)
            }
            ReaperConfig.SKULL -> skull(c, s * 0.5f, s * 0.5f, s * 0.8f, Neon.GREEN)
            ReaperConfig.RAVEN -> raven(c, s)
            ReaperConfig.PUMPKIN -> lantern(c, s)
            ReaperConfig.GHOST -> tomb(c, s)
            else -> royal(c, s, sym)
        }
    }

    private fun label(c: Canvas, s: Float, text: String, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = serif; textAlign = Paint.Align.CENTER; textSize = s * 0.2f }
        val y = s * 0.95f
        p.color = Color.BLACK; p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.03f
        c.drawText(text, s / 2, y, p)
        p.style = Paint.Style.FILL; p.color = color; p.setShadowLayer(s * 0.05f, 0f, 0f, color)
        c.drawText(text, s / 2, y, p)
        p.clearShadowLayer(); p.color = Color.WHITE; p.alpha = 160; p.textSize = s * 0.2f
    }

    fun skull(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val head = Path().apply {
            addOval(RectF(cx - s * 0.36f, cy - s * 0.42f, cx + s * 0.36f, cy + s * 0.20f), Path.Direction.CW)
            addRoundRect(RectF(cx - s * 0.22f, cy + s * 0.08f, cx + s * 0.22f, cy + s * 0.38f), s * 0.06f, s * 0.06f, Path.Direction.CW)
        }
        head.fillType = Path.FillType.WINDING
        p.shader = RadialGradient(cx, cy - s * 0.15f, s * 0.5f, intArrayOf(0xFF1F2A18.toInt(), 0xFF070A05.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(head, p); p.shader = null
        neonStroke(c, head, color, s * 0.02f)
        // sockets with inner glow
        for (sx in floatArrayOf(-1f, 1f)) {
            val ex = cx + sx * s * 0.14f; val ey = cy - s * 0.04f
            p.color = Color.BLACK
            c.drawOval(ex - s * 0.10f, ey - s * 0.09f, ex + s * 0.10f, ey + s * 0.09f, p)
            p.shader = RadialGradient(ex, ey, s * 0.07f, intArrayOf(color, 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawCircle(ex, ey, s * 0.07f, p); p.shader = null
        }
        // nose
        val nose = Path().apply { moveTo(cx, cy + s * 0.05f); lineTo(cx - s * 0.05f, cy + s * 0.14f); lineTo(cx + s * 0.05f, cy + s * 0.14f); close() }
        p.color = Color.BLACK; c.drawPath(nose, p)
        // teeth
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.012f; p.color = color
        for (k in -3..3) c.drawLine(cx + k * s * 0.05f, cy + s * 0.22f, cx + k * s * 0.05f, cy + s * 0.34f, p)
        c.drawLine(cx - s * 0.18f, cy + s * 0.28f, cx + s * 0.18f, cy + s * 0.28f, p)
    }

    private fun raven(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // blood moon
        p.shader = RadialGradient(s * 0.62f, s * 0.38f, s * 0.34f, intArrayOf(0xFFFF5252.toInt(), 0xFF8B0000.toInt(), 0x00000000), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(s * 0.62f, s * 0.38f, s * 0.34f, p); p.shader = null
        // branch
        p.color = 0xFF1A120C.toInt(); p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.04f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(s * 0.05f, s * 0.80f, s * 0.95f, s * 0.72f, p)
        c.drawLine(s * 0.70f, s * 0.74f, s * 0.85f, s * 0.86f, p)
        p.style = Paint.Style.FILL
        val body = Path().apply {
            moveTo(s * 0.70f, s * 0.30f)                       // beak tip
            lineTo(s * 0.58f, s * 0.27f)
            cubicTo(s * 0.52f, s * 0.18f, s * 0.38f, s * 0.20f, s * 0.36f, s * 0.32f) // head
            cubicTo(s * 0.20f, s * 0.42f, s * 0.14f, s * 0.60f, s * 0.08f, s * 0.86f) // back to tail
            lineTo(s * 0.22f, s * 0.80f)
            cubicTo(s * 0.36f, s * 0.78f, s * 0.52f, s * 0.70f, s * 0.56f, s * 0.52f) // belly
            cubicTo(s * 0.58f, s * 0.42f, s * 0.58f, s * 0.36f, s * 0.60f, s * 0.33f)
            close()
        }
        p.color = 0xFF050308.toInt(); c.drawPath(body, p)
        neonStroke(c, body, Neon.PURPLE, s * 0.012f)
        // wing feathers
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.01f; p.color = 0x88B388FF.toInt()
        for (k in 0..3) c.drawLine(s * (0.30f + k * 0.04f), s * (0.45f + k * 0.04f), s * (0.18f + k * 0.03f), s * (0.72f + k * 0.02f), p)
        // legs
        p.color = 0xFF3A2A20.toInt(); p.strokeWidth = s * 0.015f
        c.drawLine(s * 0.40f, s * 0.74f, s * 0.42f, s * 0.79f, p); c.drawLine(s * 0.46f, s * 0.72f, s * 0.48f, s * 0.78f, p)
        p.style = Paint.Style.FILL
        eyes(c, s * 0.505f, s * 0.505f, s * 0.265f, s * 0.018f)
    }

    private fun lantern(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2, s * 0.55f, s * 0.5f, intArrayOf(0x6676FF03, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(s / 2, s * 0.55f, s * 0.5f, p); p.shader = null
        // ring + cap
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.03f; p.color = 0xFF4A4A4A.toInt()
        c.drawCircle(s / 2, s * 0.1f, s * 0.07f, p)
        p.style = Paint.Style.FILL; p.color = 0xFF2B2B2B.toInt()
        val cap = Path().apply { moveTo(s * 0.28f, s * 0.30f); lineTo(s * 0.5f, s * 0.15f); lineTo(s * 0.72f, s * 0.30f); close() }
        c.drawPath(cap, p)
        // glass
        p.shader = LinearGradient(s * 0.32f, 0f, s * 0.68f, 0f, intArrayOf(0x5533691E, 0x8876FF03.toInt(), 0x5533691E), null, Shader.TileMode.CLAMP)
        c.drawRect(s * 0.32f, s * 0.30f, s * 0.68f, s * 0.80f, p); p.shader = null
        flame(c, s / 2, s * 0.72f, s * 0.24f, Neon.GREEN)
        // iron bars
        p.color = 0xFF1C1C1C.toInt()
        for (bx in floatArrayOf(0.30f, 0.49f, 0.68f)) c.drawRect(s * bx, s * 0.30f, s * (bx + 0.03f), s * 0.80f, p)
        c.drawRect(s * 0.25f, s * 0.80f, s * 0.75f, s * 0.88f, p)
        c.drawRect(s * 0.27f, s * 0.27f, s * 0.73f, s * 0.31f, p)
    }

    private fun tomb(c: Canvas, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val stone = Path().apply {
            moveTo(s * 0.22f, s * 0.88f); lineTo(s * 0.22f, s * 0.38f)
            cubicTo(s * 0.22f, s * 0.08f, s * 0.78f, s * 0.08f, s * 0.78f, s * 0.38f)
            lineTo(s * 0.78f, s * 0.88f); close()
        }
        p.shader = LinearGradient(s * 0.2f, 0f, s * 0.8f, 0f, intArrayOf(0xFF5A5F63.toInt(), 0xFF8A9096.toInt(), 0xFF3A3E42.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(stone, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.015f; p.color = 0xFF202326.toInt()
        c.drawPath(stone, p)
        // crack
        val crack = Path().apply { moveTo(s * 0.62f, s * 0.20f); lineTo(s * 0.56f, s * 0.36f); lineTo(s * 0.63f, s * 0.48f); lineTo(s * 0.58f, s * 0.62f) }
        c.drawPath(crack, p)
        p.style = Paint.Style.FILL
        p.typeface = serif; p.textAlign = Paint.Align.CENTER; p.textSize = s * 0.2f; p.color = 0xFF1E2124.toInt()
        c.drawText("R.I.P", s / 2, s * 0.52f, p)
        // ground + mist
        p.shader = LinearGradient(0f, s * 0.80f, 0f, s, 0xFF0E1A08.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(s * 0.05f, s * 0.84f, s * 0.95f, s * 0.97f, p); p.shader = null
        val mist = glowPaint(0x6676FF03, s * 0.06f)
        c.drawOval(s * 0.1f, s * 0.80f, s * 0.9f, s * 0.92f, mist)
    }

    private val ROYAL = mapOf(
        ReaperConfig.ACE to ("A" to Neon.RED), ReaperConfig.KING to ("K" to Neon.GREEN),
        ReaperConfig.QUEEN to ("Q" to Neon.PURPLE), ReaperConfig.JACK to ("J" to Neon.CYAN), ReaperConfig.TEN to ("10" to Neon.ORANGE)
    )

    private fun royal(c: Canvas, s: Float, sym: Int) {
        val (label, neon) = ROYAL.getValue(sym)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif; textAlign = Paint.Align.CENTER
            textSize = s * (if (label.length > 1) 0.58f else 0.72f)
            if (label.length > 1) textScaleX = 0.8f
        }
        val path = Path()
        p.getTextPath(label, 0, label.length, s / 2, s / 2 - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2, path)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0A0A0A.toInt() }
        c.drawPath(path, fill)
        neonStroke(c, path, neon, s * 0.022f)
        // drip
        fill.color = neon; fill.alpha = 200
        val b = RectF(); path.computeBounds(b, true)
        c.drawRoundRect(b.centerX() - s * 0.012f, b.bottom - s * 0.02f, b.centerX() + s * 0.012f, b.bottom + s * 0.06f, s * 0.012f, s * 0.012f, fill)
        c.drawCircle(b.centerX(), b.bottom + s * 0.065f, s * 0.018f, fill)
    }

    // ------------------------------------------------------------------ relics
    fun relic(index: Int, size: Int, lit: Boolean): Bitmap = bitmap(size) { c, s ->
        val col = if (lit) Neon.GREEN else 0xFF2A332A.toInt()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (lit) {
            p.shader = RadialGradient(s / 2, s / 2, s * 0.5f, intArrayOf(0x8876FF03.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawCircle(s / 2, s / 2, s * 0.5f, p); p.shader = null
        }
        val path = Path()
        when (index) {
            0 -> { // scythe
                path.moveTo(s * 0.70f, s * 0.90f); path.lineTo(s * 0.76f, s * 0.18f)
                path.moveTo(s * 0.76f, s * 0.18f)
                path.cubicTo(s * 0.60f, s * 0.05f, s * 0.30f, s * 0.10f, s * 0.15f, s * 0.30f)
                path.cubicTo(s * 0.35f, s * 0.20f, s * 0.58f, s * 0.20f, s * 0.76f, s * 0.26f)
            }
            1 -> { // hourglass
                path.moveTo(s * 0.28f, s * 0.15f); path.lineTo(s * 0.72f, s * 0.15f); path.lineTo(s * 0.52f, s * 0.5f)
                path.lineTo(s * 0.72f, s * 0.85f); path.lineTo(s * 0.28f, s * 0.85f); path.lineTo(s * 0.48f, s * 0.5f); path.close()
                path.moveTo(s * 0.22f, s * 0.12f); path.lineTo(s * 0.78f, s * 0.12f)
                path.moveTo(s * 0.22f, s * 0.88f); path.lineTo(s * 0.78f, s * 0.88f)
            }
            2 -> { // skull candle
                path.addCircle(s * 0.5f, s * 0.58f, s * 0.2f, Path.Direction.CW)
                path.addRect(s * 0.44f, s * 0.22f, s * 0.56f, s * 0.40f, Path.Direction.CW)
                path.moveTo(s * 0.42f, s * 0.56f); path.lineTo(s * 0.46f, s * 0.56f)
                path.moveTo(s * 0.54f, s * 0.56f); path.lineTo(s * 0.58f, s * 0.56f)
            }
            3 -> { // raven feather
                path.moveTo(s * 0.25f, s * 0.88f); path.cubicTo(s * 0.4f, s * 0.6f, s * 0.55f, s * 0.35f, s * 0.78f, s * 0.12f)
                path.moveTo(s * 0.78f, s * 0.12f); path.cubicTo(s * 0.80f, s * 0.40f, s * 0.60f, s * 0.62f, s * 0.36f, s * 0.70f)
                path.moveTo(s * 0.78f, s * 0.12f); path.cubicTo(s * 0.52f, s * 0.16f, s * 0.36f, s * 0.38f, s * 0.36f, s * 0.70f)
            }
            else -> { // cursed tome
                path.addRoundRect(RectF(s * 0.2f, s * 0.2f, s * 0.8f, s * 0.82f), s * 0.04f, s * 0.04f, Path.Direction.CW)
                path.moveTo(s * 0.28f, s * 0.2f); path.lineTo(s * 0.28f, s * 0.82f)
                path.addCircle(s * 0.55f, s * 0.48f, s * 0.12f, Path.Direction.CW)
                path.moveTo(s * 0.55f, s * 0.36f); path.lineTo(s * 0.55f, s * 0.60f)
                path.moveTo(s * 0.45f, s * 0.42f); path.lineTo(s * 0.65f, s * 0.54f)
            }
        }
        if (lit) neonStroke(c, path, col, s * 0.04f)
        else {
            p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.04f; p.color = col; p.strokeCap = Paint.Cap.ROUND
            c.drawPath(path, p)
        }
        if (lit && index == 2) flame(c, s * 0.5f, s * 0.22f, s * 0.1f, Neon.GREEN)
    }

    // ------------------------------------------------------------------ orbs
    fun orb(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(cx, cy, r * 1.5f, intArrayOf(color and 0x88FFFFFF.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.5f, p)
        p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.2f, intArrayOf(Color.WHITE, color, darken(color)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        // wisps
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.06f; p.color = 0x88FFFFFF.toInt()
        for (k in 0..2) {
            val a = k * 2.1f
            c.drawArc(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, a * 57f, 70f, false, p)
        }
    }

    fun orbBitmap(size: Int, color: Int): Bitmap = bitmap(size) { c, s -> orb(c, s / 2, s / 2, s * 0.38f, color) }

    fun darken(c: Int) = Color.rgb(Color.red(c) / 5, Color.green(c) / 5, Color.blue(c) / 5)

    // ------------------------------------------------------------------ scenery
    /** Graveyard silhouette: blood moon, dead trees, tombstones, distant hooded figures. */
    fun graveyard(c: Canvas, w: Float, h: Float, moon: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF07020A.toInt(), 0xFF0B0F08.toInt(), 0xFF000000.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        val r = Random(13)
        // stars
        p.shader = null
        for (i in 0 until 140) {
            p.color = Color.argb(60 + r.nextInt(120), 220, 230, 255)
            c.drawCircle(r.nextFloat() * w, r.nextFloat() * h * 0.55f, 0.6f + r.nextFloat() * 1.4f, p)
        }
        // moon
        val mx = w * 0.82f; val my = h * 0.2f; val mr = h * 0.11f
        p.shader = RadialGradient(mx, my, mr * 3f, intArrayOf(moon and 0x66FFFFFF, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, mr * 3f, p)
        p.shader = RadialGradient(mx - mr * 0.3f, my - mr * 0.3f, mr * 1.3f, intArrayOf(lighten(moon), moon, darken(moon)), null, Shader.TileMode.CLAMP)
        c.drawCircle(mx, my, mr, p); p.shader = null
        // hills
        p.color = 0xFF050805.toInt()
        val hill = Path().apply {
            moveTo(0f, h * 0.78f)
            cubicTo(w * 0.2f, h * 0.70f, w * 0.35f, h * 0.80f, w * 0.55f, h * 0.74f)
            cubicTo(w * 0.75f, h * 0.68f, w * 0.9f, h * 0.76f, w, h * 0.72f)
            lineTo(w, h); lineTo(0f, h); close()
        }
        c.drawPath(hill, p)
        // dead trees
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFF040404.toInt()
        tree(c, p, w * 0.08f, h * 0.76f, h * 0.38f, r)
        tree(c, p, w * 0.93f, h * 0.73f, h * 0.30f, r)
        tree(c, p, w * 0.30f, h * 0.76f, h * 0.18f, r)
        p.style = Paint.Style.FILL
        // tombstones
        for (i in 0 until 14) {
            val x = r.nextFloat() * w; val gy = h * (0.76f + r.nextFloat() * 0.04f); val tw = h * (0.025f + r.nextFloat() * 0.03f)
            p.color = 0xFF0C0E0C.toInt()
            if (r.nextBoolean()) c.drawRoundRect(x - tw / 2, gy - tw * 1.6f, x + tw / 2, gy + 2f, tw / 2, tw / 2, p)
            else { c.drawRect(x - tw * 0.1f, gy - tw * 1.8f, x + tw * 0.1f, gy, p); c.drawRect(x - tw * 0.5f, gy - tw * 1.4f, x + tw * 0.5f, gy - tw * 1.2f, p) }
        }
        // ground fog band
        p.shader = LinearGradient(0f, h * 0.70f, 0f, h, intArrayOf(0x0076FF03, 0x2276FF03, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.70f, w, h, p); p.shader = null
    }

    private fun tree(c: Canvas, p: Paint, x: Float, y: Float, h: Float, r: Random) {
        fun branch(x0: Float, y0: Float, len: Float, ang: Double, wid: Float, depth: Int) {
            if (depth == 0 || len < 3) return
            val x1 = x0 + (len * cos(ang)).toFloat(); val y1 = y0 - (len * sin(ang)).toFloat()
            p.strokeWidth = wid
            c.drawLine(x0, y0, x1, y1, p)
            val n = 2 + r.nextInt(2)
            for (i in 0 until n) branch(x1, y1, len * (0.55f + r.nextFloat() * 0.2f), ang + (r.nextDouble() - 0.5) * 1.4, wid * 0.62f, depth - 1)
        }
        branch(x, y, h * 0.42f, Math.PI / 2 + (r.nextDouble() - 0.5) * 0.2, h * 0.05f, 6)
    }

    fun lighten(c: Int) = Color.rgb(minOf(255, Color.red(c) + 90), minOf(255, Color.green(c) + 90), minOf(255, Color.blue(c) + 90))

    private class Random(seed: Long) {
        private val r = java.util.Random(seed)
        fun nextFloat() = r.nextFloat()
        fun nextInt(n: Int) = r.nextInt(n)
        fun nextBoolean() = r.nextBoolean()
        fun nextDouble() = r.nextDouble()
    }
}

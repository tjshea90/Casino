package com.tjshea.dragonhoard.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.tjshea.dragonhoard.engine.GameConfig

/**
 * Procedurally paints every reel symbol into cached bitmaps (sharp + motion
 * blurred), so per-frame drawing is a single bitmap blit per cell.
 */
class SymbolArt(val size: Int) {
    val sharp: Array<Bitmap> = Array(GameConfig.SYMBOL_COUNT) { paint(it) }
    val blurred: Array<Bitmap> = Array(GameConfig.SYMBOL_COUNT) { blur(sharp[it]) }

    fun recycle() { sharp.forEach { it.recycle() }; blurred.forEach { it.recycle() } }

    companion object {
        val ROYAL_LABEL = mapOf(
            GameConfig.ACE to "A", GameConfig.KING to "K", GameConfig.QUEEN to "Q",
            GameConfig.JACK to "J", GameConfig.TEN to "10"
        )
        val ROYAL_COLOR = mapOf(
            GameConfig.ACE to intArrayOf(0xFFFF8A80.toInt(), 0xFFC62828.toInt()),
            GameConfig.KING to intArrayOf(0xFF82B1FF.toInt(), 0xFF1565C0.toInt()),
            GameConfig.QUEEN to intArrayOf(0xFFEA80FC.toInt(), 0xFF7B1FA2.toInt()),
            GameConfig.JACK to intArrayOf(0xFFB9F6CA.toInt(), 0xFF2E7D32.toInt()),
            GameConfig.TEN to intArrayOf(0xFFFFE57F.toInt(), 0xFFEF6C00.toInt()),
        )
        val EMOJI = mapOf(
            GameConfig.WILD to "🐉",      // dragon
            GameConfig.TIGER to "🐯",     // tiger face
            GameConfig.KOI to "🎏",       // carp streamer
            GameConfig.LANTERN to "🏮",   // red lantern
            GameConfig.ENVELOPE to "🧧",  // red envelope
        )
        val MEDALLION = mapOf(
            GameConfig.WILD to intArrayOf(0xFFFF5252.toInt(), 0xFF5D0000.toInt()),
            GameConfig.TIGER to intArrayOf(0xFFFFB74D.toInt(), 0xFF6D2A00.toInt()),
            GameConfig.KOI to intArrayOf(0xFF4DD0E1.toInt(), 0xFF003C47.toInt()),
            GameConfig.LANTERN to intArrayOf(0xFFFF8A65.toInt(), 0xFF5A1300.toInt()),
            GameConfig.ENVELOPE to intArrayOf(0xFFF06292.toInt(), 0xFF4A0024.toInt()),
        )
    }

    private fun paint(sym: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (sym) {
            GameConfig.SCATTER -> drawCoin(c, s, p)
            in ROYAL_LABEL -> drawRoyal(c, s, p, sym)
            else -> drawPremium(c, s, p, sym)
        }
        return bmp
    }

    private fun drawPremium(c: Canvas, s: Float, p: Paint, sym: Int) {
        val cx = s / 2; val cy = s / 2; val r = s * 0.44f
        val col = MEDALLION.getValue(sym)
        // soft glow
        p.shader = RadialGradient(cx, cy, r * 1.15f, intArrayOf(col[0] and 0x66FFFFFF, 0), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.15f, p)
        // gold ring
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r,
            intArrayOf(0xFFFFF59D.toInt(), 0xFFFFB300.toInt(), 0xFF8D5A00.toInt(), 0xFFFFD54F.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        // inner field
        p.shader = RadialGradient(cx, cy - r * 0.3f, r * 1.1f, col, null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.86f, p)
        p.shader = null
        // emoji
        p.textAlign = Paint.Align.CENTER
        p.textSize = s * (if (sym == GameConfig.WILD) 0.46f else 0.52f)
        val fm = p.fontMetrics
        val ty = (if (sym == GameConfig.WILD) cy - s * 0.04f else cy) - (fm.ascent + fm.descent) / 2
        c.drawText(EMOJI.getValue(sym), cx, ty, p)
        // gloss
        p.shader = LinearGradient(0f, cy - r, 0f, cy, 0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - r * 0.7f, cy - r * 0.84f, cx + r * 0.7f, cy - r * 0.05f), p)
        p.shader = null
        if (sym == GameConfig.WILD) banner(c, s, p, "WILD", 0xFFB71C1C.toInt())
    }

    private fun banner(c: Canvas, s: Float, p: Paint, text: String, fill: Int) {
        val h = s * 0.22f; val top = s * 0.72f
        val rect = RectF(s * 0.1f, top, s * 0.9f, top + h)
        p.shader = LinearGradient(0f, top, 0f, top + h,
            intArrayOf(0xFFFFF176.toInt(), 0xFFFFA000.toInt(), 0xFF7A4A00.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, h / 2, h / 2, p)
        p.shader = null; p.color = fill
        rect.inset(s * 0.018f, s * 0.018f)
        c.drawRoundRect(rect, h / 2, h / 2, p)
        p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = h * 0.78f
        val ty = top + h / 2 - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.02f; p.color = 0xFF3E0000.toInt()
        c.drawText(text, s / 2, ty, p)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, top, 0f, top + h, 0xFFFFFDE7.toInt(), 0xFFFFC107.toInt(), Shader.TileMode.CLAMP)
        c.drawText(text, s / 2, ty, p)
        p.shader = null
        p.typeface = Typeface.DEFAULT
    }

    private fun drawCoin(c: Canvas, s: Float, p: Paint) {
        val cx = s / 2; val cy = s * 0.46f; val r = s * 0.40f
        p.shader = RadialGradient(cx, cy, r * 1.3f, intArrayOf(0x88FFD54F.toInt(), 0), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.3f, p)
        p.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r,
            intArrayOf(0xFFFFF9C4.toInt(), 0xFFFFC107.toInt(), 0xFF8D6E00.toInt(), 0xFFFFE082.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = LinearGradient(cx + r, cy + r, cx - r, cy - r,
            intArrayOf(0xFFFFF59D.toInt(), 0xFFFFB300.toInt(), 0xFF6D4C00.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.80f, p)
        // square hole ring (classic cash coin) with 福
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.015f; p.color = 0xFF8D5A00.toInt()
        c.drawCircle(cx, cy, r * 0.70f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFFC62828.toInt()
        p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = s * 0.42f
        val ty = cy - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2
        p.setShadowLayer(s * 0.02f, 0f, s * 0.01f, 0xAA000000.toInt())
        c.drawText("福", cx, ty, p)
        p.clearShadowLayer()
        banner(c, s, p, "BONUS", 0xFF4A148C.toInt())
    }

    private fun drawRoyal(c: Canvas, s: Float, p: Paint, sym: Int) {
        val label = ROYAL_LABEL.getValue(sym)
        val col = ROYAL_COLOR.getValue(sym)
        p.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        p.textSize = s * (if (label.length > 1) 0.56f else 0.70f)
        if (label.length > 1) p.textScaleX = 0.82f
        val cy = s / 2
        val ty = cy - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2
        // glow
        p.maskFilter = BlurMaskFilter(s * 0.06f, BlurMaskFilter.Blur.NORMAL)
        p.color = col[1]
        c.drawText(label, s / 2, ty, p)
        p.maskFilter = null
        // dark outline
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.06f; p.strokeJoin = Paint.Join.ROUND
        p.color = 0xFF1A0A00.toInt()
        c.drawText(label, s / 2, ty, p)
        // gold rim
        p.strokeWidth = s * 0.03f
        p.shader = LinearGradient(0f, s * 0.2f, 0f, s * 0.8f, 0xFFFFF59D.toInt(), 0xFFB8860B.toInt(), Shader.TileMode.CLAMP)
        c.drawText(label, s / 2, ty, p)
        // gradient fill
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, s * 0.22f, 0f, s * 0.78f, intArrayOf(Color.WHITE, col[0], col[1]),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawText(label, s / 2, ty, p)
        p.shader = null
        p.textScaleX = 1f
    }

    private fun blur(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        val steps = 6
        for (i in 0 until steps) {
            p.alpha = (255 * 0.32f).toInt()
            c.drawBitmap(src, 0f, (i - steps / 2) * size * 0.05f, p)
        }
        return out
    }
}

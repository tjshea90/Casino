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
import com.tjshea.neonreaper.engine.ReaperConfig
import kotlin.math.cos
import kotlin.math.sin

/**
 * "DREAD" palette (3.0): near-black iron and stone, bone, BLOOD red and a sickly toxic green accent (the "neon"). The arcade
 * purple / cyan / orange of earlier versions are now ash violet, ghost teal and ember so nothing reads as a toy.
 * The object keeps its old name because the whole UI refers to it.
 */
object Neon {
    const val GREEN = 0xFF7CF52B.toInt()       // toxic green
    const val GREEN_DIM = 0xFF2B5E12.toInt()
    const val RED = 0xFFE0182F.toInt()         // blood
    const val RED_DIM = 0xFF6E0F1B.toInt()
    const val PURPLE = 0xFF8B5FBF.toInt()      // ash violet
    const val CYAN = 0xFF5FB3C4.toInt()        // ghost teal
    const val ORANGE = 0xFFD9812B.toInt()      // ember
    const val BONE = 0xFFD9D2BE.toInt()
    const val BLACK = 0xFF050505.toInt()
    const val IRON = 0xFF16181D.toInt()        // panel base
    const val IRON_LIGHT = 0xFF3A3F49.toInt()  // bevel highlight
    const val BRASS = 0xFFA8873A.toInt()       // tarnished brass trim
    const val BRASS_DIM = 0xFF5C4A1E.toInt()
    const val INK = 0xFF0B0C0F.toInt()         // deepest panel colour
}

/**
 * All artwork is painted procedurally with paths, gradients and one tiny noise texture (see [Kit]). Static pieces are rendered
 * once into bitmaps; animated details (glowing eyes, flames, jaws) are drawn live on top as cheap sprites by [Characters].
 * The portraits themselves live in [Portraits] (the cast), [Royals] (A K Q J 10) and [Coffin] (the scatter); this object keeps
 * the public entry points the UI calls.
 */
object Art {
    fun bitmap(size: Int, h: Int = size, draw: (Canvas, Float) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(size, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), size.toFloat())
        return b
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

    // ------------------------------------------------------------------ cached figure icons (cheap to draw every frame)
    private val icons = LinkedHashMap<String, Bitmap>()

    private fun icon(key: String, size: Int, draw: (Canvas, Float) -> Unit): Bitmap = synchronized(icons) {
        icons[key] ?: bitmap(size, size, draw).also {
            icons[key] = it
            if (icons.size > 24) { val oldest = icons.keys.first(); icons.remove(oldest) }   // not recycled: it may still be on screen
        }
    }

    private fun blit(c: Canvas, bmp: Bitmap, x: Float, y: Float, w: Float, h: Float) {
        c.drawBitmap(bmp, null, RectF(x, y, x + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG))
    }

    /** The Grim Reaper figure (bust with the hood, skull face, chain; scythe and hand optional) in the box (x,y,w,h); [rim] tints its aura. */
    fun reaper(c: Canvas, x: Float, y: Float, w: Float, h: Float, rim: Int = Neon.GREEN, withScythe: Boolean = true) =
        blit(c, icon("reaper:$rim:$withScythe", 192) { cc, s -> Portraits.paintReaper(cc, s, withScythe, rim) }, x, y, w, h)

    /** The plague doctor (this was the "hooded watcher" in earlier versions) in the box (x,y,w,h); [rim] tints its aura. */
    fun hooded(c: Canvas, x: Float, y: Float, w: Float, h: Float, rim: Int = Neon.RED) =
        blit(c, icon("doctor:$rim", 192) { cc, s -> Doctor.paint(cc, s, false, rim) }, x, y, w, h)

    /** A small skull icon centred at (cx,cy), [s] wide; [color] is the fire in its eyes (a dark colour = a dead, dark skull). */
    fun skull(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        val bmp = icon("skull:$color", 96) { cc, sz ->
            val on = Color.red(color) * 0.3f + Color.green(color) * 0.59f + Color.blue(color) * 0.11f > 70f
            if (on) Kit.aura(cc, sz / 2, sz / 2, sz * 0.5f, color, 0.55f)
            Kit.miniSkull(cc, sz * 0.5f, sz * 0.47f, sz * 0.40f, if (on) 0xFFD9D2BE.toInt() else 0xFF4A4040.toInt(), color, on)
        }
        blit(c, bmp, cx - s / 2, cy - s / 2, s, s)
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
    /** Icon of reel symbol [sym] (a portrait, a royal or the coffin) on a transparent [size]-pixel square. */
    fun symbol(sym: Int, size: Int): Bitmap = bitmap(size) { c, s ->
        when {
            Portraits.has(sym) -> {
                Portraits.paint(c, s, sym, false)
                if (sym == ReaperConfig.WILD) Kit.plate(c, s, "WILD", Neon.GREEN)
            }
            sym == ReaperConfig.SCATTER -> coffin(c, s)
            else -> Royals.paint(c, s, sym)
        }
    }

    /** The scatter: a dark mahogany coffin with brass straps, chains, a skull medallion and sickly green light leaking out. */
    fun coffin(c: Canvas, s: Float) = Coffin.paint(c, s)

    // ------------------------------------------------------------------ relics
    fun relic(index: Int, size: Int, lit: Boolean): Bitmap = Relics.icon(index, size, lit)

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

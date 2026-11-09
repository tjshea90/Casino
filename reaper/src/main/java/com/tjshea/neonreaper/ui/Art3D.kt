package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.tjshea.neonreaper.engine.ReaperConfig
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 2D cast. Every symbol is a dark painted portrait ([Portraits], [Royals], [Coffin]) rendered once into a bitmap and split into
 * layers: a static pose (everything painted, used for the reel atlas, dialog icons and the 2D fallback's blurred spin faces) and,
 * for live drawing, a pose without the parts that move (jaws, beak, eye glow, flames, wailing mouth) plus small sprites so the faces
 * can blink, glow, laugh, chatter, caw, burn and wail.
 *
 * A Characters set renders one box size (w×h); drawing uses that box at the canvas origin, so callers translate/scale the canvas
 * (e.g. for the reel drum). The art is a square of side min(w,h): characters stand on the bottom of the box, royals are centred.
 */
class Characters(val w: Int, val h: Int, private val plates: Boolean = true) {
    companion object {
        const val IDLE = 0
        const val EXCITED = 1   // winning / showing off: laugh, chatter, caw, snarl, wail
    }

    private val W = w.toFloat()
    private val H = h.toFloat()
    private val S = min(W, H)
    private val OX = (W - S) / 2f
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    private val bodies = arrayOfNulls<Bitmap>(ReaperConfig.SYMBOL_COUNT)   // complete still pose
    private val lives = arrayOfNulls<Bitmap>(ReaperConfig.SYMBOL_COUNT)    // pose without the animated parts
    private val jaws = arrayOfNulls<Bitmap>(ReaperConfig.SYMBOL_COUNT)     // jaw / lower beak sprites
    private val glows = HashMap<Int, Bitmap>()
    private val flames = HashMap<Int, Bitmap>()
    private var coinBmp: Bitmap? = null
    private var lensBmp: Bitmap? = null
    private var demonEyes: Array<Bitmap>? = null

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pb = Paint(Paint.FILTER_BITMAP_FLAG)
    private val r = RectF()

    fun body(sym: Int): Bitmap = bodies[sym] ?: build(sym, false).also { bodies[sym] = it }
    private fun live(sym: Int): Bitmap = lives[sym] ?: build(sym, true).also { lives[sym] = it }
    private fun jaw(sym: Int): Bitmap = jaws[sym] ?: Art.bitmap(w, h) { c, _ -> place(c, sym) { s -> Portraits.paintJaw(c, s, sym) } }.also { jaws[sym] = it }

    fun recycle() {
        bodies.forEach { it?.recycle() }; lives.forEach { it?.recycle() }; jaws.forEach { it?.recycle() }
        glows.values.forEach { it.recycle() }; flames.values.forEach { it.recycle() }
        coinBmp?.recycle(); lensBmp?.recycle(); demonEyes?.forEach { it.recycle() }
        glows.clear(); flames.clear(); coinBmp = null; lensBmp = null; demonEyes = null
        for (i in bodies.indices) { bodies[i] = null; lives[i] = null; jaws[i] = null }
    }

    // ------------------------------------------------------------------ placing the art square inside the box
    private fun oy(sym: Int) = if (sym <= ReaperConfig.WRAITH) H - S else (H - S) / 2f
    private fun px(u: Float) = OX + u * S
    private fun py(sym: Int, u: Float) = oy(sym) + u * S
    private inline fun place(c: Canvas, sym: Int, block: (Float) -> Unit) { c.save(); c.translate(OX, oy(sym)); block(S); c.restore() }

    private fun build(sym: Int, live: Boolean): Bitmap = Art.bitmap(w, h) { c, _ ->
        place(c, sym) { s ->
            when {
                Portraits.has(sym) -> {
                    Portraits.paint(c, s, sym, live)
                    if (sym == ReaperConfig.WILD && plates) Kit.plate(c, s, "WILD", Neon.GREEN)
                }
                sym == ReaperConfig.SCATTER -> Art.coffin(c, s)
                else -> Royals.paint(c, s, sym)
            }
        }
    }

    // ------------------------------------------------------------------ sprites
    private fun glow(color: Int): Bitmap = glows.getOrPut(color) {
        Art.bitmap(64) { c, s ->
            val q = Paint(Paint.ANTI_ALIAS_FLAG)
            q.shader = RadialGradient(s / 2, s / 2, s / 2, intArrayOf((color and 0x00FFFFFF) or 0xD0000000.toInt(), (color and 0x00FFFFFF) or 0x70000000, (color and 0x00FFFFFF) or 0x24000000, color and 0x00FFFFFF),
                floatArrayOf(0f, 0.3f, 0.65f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(s / 2, s / 2, s / 2, q)
        }
    }

    /** Cached flame sprite drawn with a live flicker (cheap: one bitmap blit). Base of the flame at (cx, base). */
    fun flame(c: Canvas, cx: Float, base: Float, size: Float, color: Int, flicker: Float) {
        val bmp = flames.getOrPut(color) { Art.bitmap(48, 96) { cc, _ -> Art.flame(cc, 24f, 86f, 40f, color) } }
        val sx = size * (1f + 0.08f * flicker); val sy = size * (1f + 0.15f * flicker)
        r.set(cx - sx * 0.6f, base - sy * 2.15f, cx + sx * 0.6f, base + sy * 0.25f)
        c.drawBitmap(bmp, null, r, pb)
    }

    /** Soul coin (tarnished brass rim, glowing green face) centred at (cx, cy) with its token value. */
    fun coin(c: Canvas, cx: Float, cy: Float, radius: Float, label: String, spin: Float = 1f) {
        val bmp = coinBmp ?: Art.bitmap(128) { cc, sz ->
            val q = Paint(Paint.ANTI_ALIAS_FLAG); val m = sz / 2
            q.shader = RadialGradient(m, m, m, intArrayOf(0x887CF52B.toInt(), 0x00000000), floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
            cc.drawCircle(m, m, m, q)
            q.shader = LinearGradient(m * 0.3f, m * 0.3f, m * 1.7f, m * 1.7f, intArrayOf(0xFFEAD28A.toInt(), 0xFF9A7A2E.toInt(), 0xFF3A2A0C.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            cc.drawCircle(m, m, m * 0.74f, q)
            q.shader = null; q.style = Paint.Style.STROKE; q.strokeWidth = m * 0.03f; q.color = 0xCC1A1004.toInt()
            cc.drawCircle(m, m, m * 0.66f, q); q.style = Paint.Style.FILL
            q.shader = RadialGradient(m * 0.8f, m * 0.75f, m * 0.7f, intArrayOf(0xFFC8FF9A.toInt(), 0xFF2E7D12.toInt(), 0xFF06200A.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            cc.drawCircle(m, m, m * 0.6f, q)
            q.shader = RadialGradient(m * 0.7f, m * 0.6f, m * 0.25f, intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            cc.drawCircle(m * 0.7f, m * 0.6f, m * 0.25f, q)
        }.also { coinBmp = it }
        val sx = radius * abs(spin).coerceAtLeast(0.12f)
        r.set(cx - sx * 1.35f, cy - radius * 1.35f, cx + sx * 1.35f, cy + radius * 1.35f)
        c.drawBitmap(bmp, null, r, pb)
        if (abs(spin) > 0.6f) {
            p.color = Color.WHITE; p.alpha = pb.alpha; p.textAlign = Paint.Align.CENTER; p.typeface = serif
            p.textSize = radius * (if (label.length > 3) 0.62f else 0.8f)
            p.setShadowLayer(radius * 0.15f, 0f, 0f, Color.BLACK)
            c.drawText(label, cx, cy - (p.fontMetrics.ascent + p.fontMetrics.descent) / 2, p)
            p.clearShadowLayer()
        }
    }

    // ================================================================== live drawing
    /**
     * Draws [sym] in the box (0,0)-(w,h). [t] seconds, [phase] 0..1 per cell so neighbours don't animate in sync, [mood] IDLE or
     * EXCITED, [alpha] for dimming.
     */
    fun draw(c: Canvas, sym: Int, t: Double, phase: Double, mood: Int, alpha: Int = 255) {
        pb.alpha = alpha
        val ex = mood == EXCITED
        when (sym) {
            ReaperConfig.WILD -> drawReaper(c, t, phase, ex)
            ReaperConfig.DOCTOR -> drawDoctor(c, t, phase, ex)
            ReaperConfig.SKULL -> drawSkull(c, t, phase, ex)
            ReaperConfig.RAVEN -> drawRaven(c, t, phase, ex)
            ReaperConfig.DEMON -> drawDemon(c, t, phase, ex)
            ReaperConfig.WRAITH -> drawWraith(c, t, phase, ex)
            else -> drawStill(c, sym, t, phase, ex)
        }
        pb.alpha = 255
    }

    private fun blinking(t: Double, phase: Double): Boolean = ((t * 0.23 + phase * 7.3) % 1.0) < 0.035

    /** A glowing eye: halo sprite, coloured slit, hot core. (x,y) and [size] in box pixels. */
    private fun eye(c: Canvas, x: Float, y: Float, size: Float, color: Int, intensity: Float, blink: Boolean) {
        if (blink) return
        val a = pb.alpha
        val g = glow(color)
        val gs = size * 3.4f * intensity
        pb.alpha = (a * min(1f, 0.55f + 0.45f * intensity)).toInt()
        r.set(x - gs, y - gs * 0.75f, x + gs, y + gs * 0.75f)
        c.drawBitmap(g, null, r, pb)
        pb.alpha = a
        p.color = color; p.alpha = a
        c.drawOval(x - size * 1.15f, y - size * 0.55f, x + size * 1.15f, y + size * 0.55f, p)
        p.color = 0xFFFFE8E0.toInt(); p.alpha = a
        c.drawOval(x - size * 0.5f, y - size * 0.22f, x + size * 0.5f, y + size * 0.22f, p)
    }

    // ---- Grim Reaper: sways, laughs (jaw), red eyes burn brighter and blink
    private fun drawReaper(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val sway = if (ex) sin(t * 9).toFloat() * 2.5f else sin(t * 1.3 + phase * 6).toFloat() * 0.8f
        c.save(); c.rotate(sway, W * 0.5f, H)
        c.drawBitmap(live(ReaperConfig.WILD), 0f, 0f, pb)
        val open = if (ex) abs(sin(t * 15)).toFloat() else max(0f, sin(t * 0.7 + phase * 9).toFloat() - 0.93f) * 6f
        c.drawBitmap(jaw(ReaperConfig.WILD), 0f, S * 0.03f * open, pb)
        val e = Portraits.eyes(ReaperConfig.WILD)!!
        val inten = if (ex) 1.5f + 0.3f * sin(t * 20).toFloat() else 0.85f + 0.2f * sin(t * 2 + phase * 5).toFloat()
        val blink = !ex && blinking(t, phase)
        eye(c, px(e[0]), py(ReaperConfig.WILD, e[1]), S * e[4], Neon.RED, inten, blink)
        eye(c, px(e[2]), py(ReaperConfig.WILD, e[3]), S * e[4], Neon.RED, inten, blink)
        c.restore()
    }

    // ---- Plague Doctor: glowing lenses flare, lantern flame flickers, coat breathes
    private fun drawDoctor(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val bob = sin(t * 1.1 + phase * 4).toFloat() * H * 0.006f
        c.save(); c.translate(0f, bob)
        c.drawBitmap(live(ReaperConfig.DOCTOR), 0f, 0f, pb)
        val l = Doctor.LENSES
        val inten = if (ex) 1.7f + 0.3f * sin(t * 25).toFloat() else 0.95f + 0.15f * sin(t * 2.3 + phase * 4).toFloat()
        val blink = !ex && blinking(t, phase + 0.3)
        val lens = lensBmp ?: Art.bitmap(64) { cc, sz -> Doctor.glass(cc, sz, sz / 2, sz / 2, sz * 0.46f, 0f) }.also { lensBmp = it }
        val a = pb.alpha
        for (i in 0..1) {
            val x = px(l[i * 2]); val y = py(ReaperConfig.DOCTOR, l[i * 2 + 1]); val rr = S * l[4]
            if (blink) { p.color = 0xFF0A1206.toInt(); p.alpha = a; c.drawCircle(x, y, rr * 0.95f, p); continue }
            val gs = rr * 2.3f * inten
            pb.alpha = (a * min(1f, 0.55f + 0.45f * inten)).toInt()
            r.set(x - gs, y - gs, x + gs, y + gs); c.drawBitmap(glow(Neon.GREEN), null, r, pb)
            pb.alpha = a
            r.set(x - rr * 0.95f, y - rr * 0.95f, x + rr * 0.95f, y + rr * 0.95f); c.drawBitmap(lens, null, r, pb)
        }
        flame(c, px(0.165f), py(ReaperConfig.DOCTOR, 0.93f), S * 0.04f, if (ex) Neon.RED else Neon.GREEN, sin(t * 9 + phase * 3).toFloat())
        c.restore()
    }

    // ---- Cursed Skull: green fire roars in the sockets, jaw chatters, head rattles
    private fun drawSkull(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val tilt = if (ex) sin(t * 12).toFloat() * 6f else sin(t * 0.9 + phase * 5).toFloat() * 2f
        c.save(); c.rotate(tilt, W * 0.5f, H * 0.6f)
        c.drawBitmap(live(ReaperConfig.SKULL), 0f, 0f, pb)
        val chatterIdle = ((t * 0.31 + phase * 3.7) % 1.0) < 0.06
        val open = when {
            ex -> abs(sin(t * 22)).toFloat()
            chatterIdle -> abs(sin(t * 30)).toFloat() * 0.6f
            else -> 0f
        }
        c.drawBitmap(jaw(ReaperConfig.SKULL), 0f, S * 0.06f * open, pb)
        val sk = Portraits.eyes(ReaperConfig.SKULL)!!
        val inten = if (ex) 1.6f else 0.85f + 0.25f * sin(t * 3 + phase * 4).toFloat()
        for (i in 0..1) {
            val x = px(sk[i * 2]); val y = py(ReaperConfig.SKULL, sk[i * 2 + 1])
            val a = pb.alpha
            val gs = S * 0.10f * inten
            pb.alpha = (a * min(1f, 0.5f + 0.4f * inten)).toInt()
            r.set(x - gs, y - gs * 0.8f, x + gs, y + gs * 0.8f); c.drawBitmap(glow(Neon.GREEN), null, r, pb)
            pb.alpha = a
            flame(c, x, y + S * 0.02f, S * (0.07f + 0.015f * inten), Neon.GREEN, sin(t * (9 + i * 3) + phase * 5 + i * 2).toFloat())
        }
        c.restore()
    }

    // ---- Blood Raven: caws (lower beak), red eye glints and blinks, breathes
    private fun drawRaven(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val breathe = sin(t * 1.5 + phase * 3).toFloat() * H * 0.004f
        c.save(); c.translate(0f, breathe)
        c.drawBitmap(live(ReaperConfig.RAVEN), 0f, 0f, pb)
        val cawIdle = ((t * 0.17 + phase * 5.1) % 1.0) < 0.05
        val open = if (ex || cawIdle) abs(sin(t * (if (ex) 11.0 else 8.0))).toFloat() else 0f
        c.save(); c.rotate(open * 20f, px(Raven.BEAK_PIVOT[0]), py(ReaperConfig.RAVEN, Raven.BEAK_PIVOT[1]))
        c.drawBitmap(jaw(ReaperConfig.RAVEN), 0f, 0f, pb)
        c.restore()
        eye(c, px(Raven.EYE[0]), py(ReaperConfig.RAVEN, Raven.EYE[1]), S * Raven.EYE[2], Neon.RED, if (ex) 1.6f else 1f, blinking(t, phase + 0.6))
        c.restore()
    }

    // ---- Demon: eyes flare, the whole head heaves and snarls when excited
    private fun drawDemon(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val heave = if (ex) 1f + 0.035f * abs(sin(t * 14)).toFloat() else 1f + 0.008f * sin(t * 1.4 + phase * 4).toFloat()
        c.save(); c.scale(heave, heave, W * 0.5f, H)
        c.drawBitmap(live(ReaperConfig.DEMON), 0f, 0f, pb)
        val e = Demon.EYES
        val inten = if (ex) 1.5f + 0.4f * sin(t * 17).toFloat() else 0.8f + 0.2f * sin(t * 2.6 + phase * 6).toFloat()
        val eyes = demonEyes ?: Array(2) { i ->
            val bw = (S * 0.26f).toInt().coerceAtLeast(8); val bh = (S * 0.16f).toInt().coerceAtLeast(6)
            Art.bitmap(bw, bh) { cc, _ -> Demon.eye(cc, S, bw / 2f, bh / 2f, i == 1, 1f) }
        }.also { demonEyes = it }
        val a = pb.alpha
        val blink = !ex && blinking(t, phase + 0.2)
        if (!blink) for (i in 0..1) {
            val x = px(e[i * 2]); val y = py(ReaperConfig.DEMON, e[i * 2 + 1]); val k = 1f + 0.25f * (inten - 1f)
            pb.alpha = (a * min(1f, 0.6f + 0.4f * inten)).toInt()
            r.set(x - S * 0.13f * k, y - S * 0.08f * k, x + S * 0.13f * k, y + S * 0.08f * k); c.drawBitmap(eyes[i], null, r, pb)
        }
        pb.alpha = a
        c.restore()
    }

    // ---- Wraith: floats and sways, wails (mouth opens wide), eyes burn teal
    private fun drawWraith(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val bob = sin(t * (if (ex) 6.0 else 1.8) + phase * 6).toFloat() * H * (if (ex) 0.04f else 0.022f)
        val lean = sin(t * 1.2 + phase * 3).toFloat() * 3f + if (ex) sin(t * 10).toFloat() * 4f else 0f
        c.save(); c.translate(0f, bob); c.rotate(lean, W / 2, H * 0.8f)
        c.drawBitmap(live(ReaperConfig.WRAITH), 0f, 0f, pb)
        val open = if (ex) 0.6f + 0.4f * abs(sin(t * 12)).toFloat() else 0.35f + 0.25f * sin(t * 1.5 + phase * 5).toFloat()
        c.save(); c.translate(OX, oy(ReaperConfig.WRAITH))
        Wraith.mouth(c, S, open)
        c.restore()
        val e = Wraith.EYES
        val inten = if (ex) 1.6f else 0.9f + 0.2f * sin(t * 2.2 + phase * 4).toFloat()
        val blink = !ex && blinking(t, phase + 0.1)
        eye(c, px(e[0]), py(ReaperConfig.WRAITH, e[1] + 0.008f), S * e[4], 0xFF5FE6E0.toInt(), inten, blink)
        eye(c, px(e[2]), py(ReaperConfig.WRAITH, e[3] + 0.008f), S * e[4], 0xFF5FE6E0.toInt(), inten, blink)
        c.restore()
    }

    // ---- royals and the coffin: a light sweep crosses them when excited
    private fun drawStill(c: Canvas, sym: Int, t: Double, phase: Double, ex: Boolean) {
        val s = if (ex) 1f + 0.06f * sin(t * 10).toFloat() else 1f
        c.save(); c.scale(s, s, W / 2, H / 2)
        c.drawBitmap(body(sym), 0f, 0f, pb)
        if (ex) {
            val x = ((t * 1.6 + phase) % 1.0).toFloat() * W * 1.6f - W * 0.3f
            p.shader = LinearGradient(x - W * 0.15f, 0f, x + W * 0.15f, 0f, intArrayOf(0x00FFFFFF, 0x77FFF0D0, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            p.alpha = pb.alpha
            c.drawRect(0f, H * 0.15f, W, H * 0.85f, p)
            p.shader = null
        }
        c.restore()
    }
}

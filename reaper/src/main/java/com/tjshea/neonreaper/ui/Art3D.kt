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
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pseudo-3D characters. Every character is lit from the top-left (sphere and
 * cylinder shading, specular highlights, rim light, contact shadow) and split
 * into layers — a static body bitmap plus live parts (eyes, jaw, beak, carved
 * face, mouth) — so faces can blink, glow, laugh, chatter, caw and grin.
 *
 * A Characters set renders one box size (w×h); drawing uses that box at the
 * canvas origin, so callers translate/scale the canvas (e.g. for the reel drum).
 */
class Characters(val w: Int, val h: Int, private val plates: Boolean = true) {
    companion object {
        const val IDLE = 0
        const val EXCITED = 1   // winning / showing off: laugh, chatter, caw, grin
    }

    private val W = w.toFloat()
    private val H = h.toFloat()
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    private val bodies = arrayOfNulls<Bitmap>(ReaperConfig.SYMBOL_COUNT)
    private var reaperJaw: Bitmap? = null
    private var skullJaw: Bitmap? = null
    private var ravenBeak: Bitmap? = null
    private var pumpkinEyes: Bitmap? = null
    private var pumpkinMouth: Bitmap? = null
    private var redGlow: Bitmap? = null
    private var greenGlow: Bitmap? = null
    private var orangeGlow: Bitmap? = null
    private val flames = HashMap<Int, Bitmap>()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pb = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val r = RectF()

    fun body(sym: Int): Bitmap = bodies[sym] ?: build(sym).also { bodies[sym] = it }

    fun recycle() {
        bodies.forEach { it?.recycle() }
        listOf(reaperJaw, skullJaw, ravenBeak, pumpkinEyes, pumpkinMouth, redGlow, greenGlow, orangeGlow).forEach { it?.recycle() }
        flames.values.forEach { it.recycle() }
    }

    private fun glow(color: Int): Bitmap = Art.bitmap(64) { c, s ->
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.shader = RadialGradient(s / 2, s / 2, s / 2, intArrayOf(Color.WHITE, color, color and 0x00FFFFFF), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(s / 2, s / 2, s / 2, q)
    }

    private fun glowSprite(color: Int): Bitmap = when (color) {
        Neon.RED -> redGlow ?: glow(Neon.RED).also { redGlow = it }
        Neon.GREEN -> greenGlow ?: glow(Neon.GREEN).also { greenGlow = it }
        else -> orangeGlow ?: glow(0xFFFFA000.toInt()).also { orangeGlow = it }
    }

    // ================================================================== live drawing
    /**
     * Draws [sym] in the box (0,0)-(w,h). [t] seconds, [phase] 0..1 per cell so
     * neighbours don't animate in sync, [mood] IDLE or EXCITED, [alpha] for dimming.
     */
    fun draw(c: Canvas, sym: Int, t: Double, phase: Double, mood: Int, alpha: Int = 255) {
        pb.alpha = alpha
        val ex = mood == EXCITED
        when (sym) {
            ReaperConfig.WILD -> drawReaper(c, t, phase, ex)
            ReaperConfig.HOODED -> drawHooded(c, t, phase, ex)
            ReaperConfig.SKULL -> drawSkull(c, t, phase, ex)
            ReaperConfig.RAVEN -> drawRaven(c, t, phase, ex)
            ReaperConfig.PUMPKIN -> drawPumpkin(c, t, phase, ex)
            ReaperConfig.GHOST -> drawGhost(c, t, phase, ex)
            else -> drawRoyal(c, sym, t, phase, ex)
        }
        pb.alpha = 255
    }

    /** Cached flame sprite drawn with a live flicker (cheap: one bitmap blit). Base of the flame at (cx, base). */
    fun flame(c: Canvas, cx: Float, base: Float, size: Float, color: Int, flicker: Float) {
        val bmp = flames.getOrPut(color) { Art.bitmap(48, 96) { cc, _ -> Art.flame(cc, 24f, 86f, 40f, color) } }
        val sx = size * (1f + 0.08f * flicker); val sy = size * (1f + 0.15f * flicker)
        r.set(cx - sx * 0.6f, base - sy * 2.15f, cx + sx * 0.6f, base + sy * 0.25f)
        c.drawBitmap(bmp, null, r, pb)
    }

    private fun blinking(t: Double, phase: Double): Boolean = ((t * 0.23 + phase * 7.3) % 1.0) < 0.035

    private fun eye(c: Canvas, x: Float, y: Float, size: Float, color: Int, intensity: Float, blink: Boolean) {
        if (blink) {
            p.color = 0xFF000000.toInt(); p.alpha = pb.alpha
            c.drawRect(x - size * 0.9f, y - size * 0.25f, x + size * 0.9f, y + size * 0.25f, p)
            return
        }
        val g = glowSprite(color)
        val gs = size * 3.2f * intensity
        pb.alpha = (pb.alpha * min(1f, 0.55f + 0.45f * intensity)).toInt()
        r.set(x - gs, y - gs * 0.7f, x + gs, y + gs * 0.7f)
        c.drawBitmap(g, null, r, pb)
        pb.alpha = 255.coerceAtMost(pb.alpha + 100)
        p.color = color; p.alpha = pb.alpha
        c.drawOval(x - size, y - size * 0.45f, x + size, y + size * 0.45f, p)
        p.color = 0xFFFFE4E4.toInt()
        c.drawOval(x - size * 0.35f, y - size * 0.18f, x + size * 0.35f, y + size * 0.18f, p)
    }

    // ---- Grim Reaper: skull in a hood, red eyes, laughing jaw
    private val reaperEyes = floatArrayOf(0.415f, 0.585f, 0.355f)

    private fun drawReaper(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val sway = if (ex) sin(t * 9).toFloat() * 2.5f else sin(t * 1.3 + phase * 6).toFloat() * 0.8f
        c.save(); c.rotate(sway, W * 0.5f, H)
        c.drawBitmap(body(ReaperConfig.WILD), 0f, 0f, pb)
        val open = if (ex) abs(sin(t * 15)).toFloat() else max(0f, sin(t * 0.7 + phase * 9).toFloat() - 0.93f) * 6f
        val jaw = reaperJaw ?: makeReaperJaw().also { reaperJaw = it }
        c.drawBitmap(jaw, 0f, H * 0.045f * open, pb)
        val inten = if (ex) 1.5f + 0.3f * sin(t * 20).toFloat() else 0.85f + 0.2f * sin(t * 2 + phase * 5).toFloat()
        val blink = !ex && blinking(t, phase)
        eye(c, W * reaperEyes[0], H * reaperEyes[2], W * 0.03f, Neon.RED, inten, blink)
        eye(c, W * reaperEyes[1], H * reaperEyes[2], W * 0.03f, Neon.RED, inten, blink)
        c.restore()
    }

    // ---- Hooded Watcher: void face, eyes that look around
    private fun drawHooded(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val bob = sin(t * 1.1 + phase * 4).toFloat() * H * 0.006f
        c.save(); c.translate(0f, bob)
        c.drawBitmap(body(ReaperConfig.HOODED), 0f, 0f, pb)
        val look = (if (ex) 0f else sin(t * 0.6 + phase * 11).toFloat().let { if (abs(it) > 0.6f) it else 0f }) * W * 0.03f
        val inten = if (ex) 1.8f + 0.3f * sin(t * 25).toFloat() else 0.9f
        val blink = !ex && blinking(t, phase + 0.3)
        eye(c, W * 0.42f + look, H * 0.36f, W * 0.032f, Neon.RED, inten, blink)
        eye(c, W * 0.58f + look, H * 0.36f, W * 0.032f, Neon.RED, inten, blink)
        // candle flame
        flame(c, W * 0.5f, H * 0.6f, W * 0.05f, if (ex) Neon.RED else Neon.GREEN, sin(t * 9 + phase * 3).toFloat())
        c.restore()
    }

    // ---- Laughing Skull: glowing sockets, chattering jaw
    private fun drawSkull(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val tilt = if (ex) sin(t * 12).toFloat() * 6f else sin(t * 0.9 + phase * 5).toFloat() * 2f
        c.save(); c.rotate(tilt, W * 0.5f, H * 0.6f)
        c.drawBitmap(body(ReaperConfig.SKULL), 0f, 0f, pb)
        val chatterIdle = ((t * 0.31 + phase * 3.7) % 1.0) < 0.06
        val open = when {
            ex -> abs(sin(t * 22)).toFloat()
            chatterIdle -> abs(sin(t * 30)).toFloat() * 0.6f
            else -> 0f
        }
        val jaw = skullJaw ?: makeSkullJaw().also { skullJaw = it }
        c.drawBitmap(jaw, 0f, H * 0.06f * open, pb)
        val inten = if (ex) 1.7f else 0.8f + 0.25f * sin(t * 3 + phase * 4).toFloat()
        eye(c, W * 0.385f, H * 0.43f, W * 0.04f, Neon.GREEN, inten, false)
        eye(c, W * 0.615f, H * 0.43f, W * 0.04f, Neon.GREEN, inten, false)
        c.restore()
    }

    // ---- Blood Raven: glossy feathers, glinting eye, cawing beak
    private fun drawRaven(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        c.drawBitmap(body(ReaperConfig.RAVEN), 0f, 0f, pb)
        val cawIdle = ((t * 0.17 + phase * 5.1) % 1.0) < 0.05
        val open = if (ex || cawIdle) abs(sin(t * (if (ex) 11.0 else 8.0))).toFloat() else 0f
        val beak = ravenBeak ?: makeRavenBeak().also { ravenBeak = it }
        c.save(); c.rotate(open * 22f, W * 0.58f, H * 0.305f)
        c.drawBitmap(beak, 0f, 0f, pb)
        c.restore()
        eye(c, W * 0.535f, H * 0.285f, W * 0.022f, Neon.RED, if (ex) 1.6f else 1f, blinking(t, phase + 0.6))
    }

    // ---- Jack-o'-lantern: flickering carved face that grins wider and bounces when excited
    private fun drawPumpkin(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val bounce = if (ex) -abs(sin(t * 9)).toFloat() * H * 0.06f else 0f
        val squash = if (ex) 1f + 0.05f * cos(t * 18).toFloat() else 1f
        c.save(); c.translate(0f, bounce); c.scale(1f / squash, squash, W / 2, H * 0.9f)
        c.drawBitmap(body(ReaperConfig.PUMPKIN), 0f, 0f, pb)
        val flicker = (0.75 + 0.25 * sin(t * 13 + phase * 9) * sin(t * 7.3 + phase)).toFloat()
        val eyes = pumpkinEyes ?: makePumpkinFace(false).also { pumpkinEyes = it }
        val mouth = pumpkinMouth ?: makePumpkinFace(true).also { pumpkinMouth = it }
        val a = pb.alpha
        pb.alpha = (a * (if (ex) 1f else flicker)).toInt()
        c.drawBitmap(eyes, 0f, 0f, pb)
        val grin = if (ex) 1.25f + 0.15f * abs(sin(t * 16)).toFloat() else 1f + 0.06f * max(0f, sin(t * 0.8 + phase * 7).toFloat())
        c.save(); c.scale(1f + (grin - 1f) * 0.4f, grin, W * 0.5f, H * 0.62f)
        c.drawBitmap(mouth, 0f, 0f, pb)
        c.restore()
        pb.alpha = a
        c.restore()
    }

    // ---- Grinning Ghost: floats, blinks, smiles; giggles with an open mouth when excited
    private fun drawGhost(c: Canvas, t: Double, phase: Double, ex: Boolean) {
        val bob = sin(t * (if (ex) 6.0 else 1.8) + phase * 6).toFloat() * H * (if (ex) 0.05f else 0.03f)
        val lean = sin(t * 1.2 + phase * 3).toFloat() * 4f + if (ex) sin(t * 10).toFloat() * 6f else 0f
        c.save(); c.translate(0f, bob); c.rotate(lean, W / 2, H * 0.5f)
        c.drawBitmap(body(ReaperConfig.GHOST), 0f, 0f, pb)
        val blink = !ex && blinking(t, phase + 0.1)
        p.color = 0xFF0A0A14.toInt(); p.alpha = pb.alpha
        for (sx in floatArrayOf(0.41f, 0.59f)) {
            if (blink) c.drawRect(W * sx - W * 0.04f, H * 0.39f, W * sx + W * 0.04f, H * 0.405f, p)
            else c.drawOval(W * sx - W * 0.04f, H * 0.34f, W * sx + W * 0.04f, H * 0.44f, p)
        }
        if (!blink) { p.color = Color.WHITE; p.alpha = pb.alpha
            for (sx in floatArrayOf(0.415f, 0.595f)) c.drawCircle(W * sx, H * 0.365f, W * 0.012f, p) }
        // mouth: a smile, or an open laughing "O" when excited
        p.color = 0xFF0A0A14.toInt(); p.alpha = pb.alpha
        if (ex) {
            val o = 0.6f + 0.4f * abs(sin(t * 14)).toFloat()
            c.drawOval(W * 0.44f, H * 0.48f, W * 0.56f, H * (0.48f + 0.1f * o), p)
        } else {
            p.style = Paint.Style.STROKE; p.strokeWidth = W * 0.022f; p.strokeCap = Paint.Cap.ROUND
            r.set(W * 0.41f, H * 0.42f, W * 0.59f, H * 0.53f)
            c.drawArc(r, 20f, 140f, false, p)
            p.style = Paint.Style.FILL
        }
        // cheeks
        p.color = 0x66FF6E9E; p.alpha = (0x66 * pb.alpha / 255)
        c.drawOval(W * 0.32f, H * 0.45f, W * 0.39f, H * 0.49f, p); c.drawOval(W * 0.61f, H * 0.45f, W * 0.68f, H * 0.49f, p)
        c.restore()
    }

    // ---- 3D extruded royals; a light sweep crosses them when excited
    private fun drawRoyal(c: Canvas, sym: Int, t: Double, phase: Double, ex: Boolean) {
        val s = if (ex) 1f + 0.06f * sin(t * 10).toFloat() else 1f
        c.save(); c.scale(s, s, W / 2, H / 2)
        c.drawBitmap(body(sym), 0f, 0f, pb)
        if (ex) {
            val x = ((t * 1.6 + phase) % 1.0).toFloat() * W * 1.6f - W * 0.3f
            p.shader = LinearGradient(x - W * 0.15f, 0f, x + W * 0.15f, 0f, intArrayOf(0x00FFFFFF, 0x99FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            p.alpha = pb.alpha
            c.drawRect(0f, H * 0.15f, W, H * 0.85f, p)
            p.shader = null
        }
        c.restore()
    }

    // ================================================================== bodies (built once)
    private fun build(sym: Int): Bitmap = Art.bitmap(w, h) { c, _ ->
        when (sym) {
            ReaperConfig.WILD -> buildReaper(c)
            ReaperConfig.HOODED -> buildHooded(c)
            ReaperConfig.SKULL -> buildSkull(c)
            ReaperConfig.RAVEN -> buildRaven(c)
            ReaperConfig.PUMPKIN -> buildPumpkin(c)
            ReaperConfig.GHOST -> buildGhost(c)
            else -> buildRoyal(c, sym)
        }
    }

    private fun q() = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun contactShadow(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float) {
        val s = q(); s.color = 0xAA000000.toInt(); s.maskFilter = BlurMaskFilter(max(1f, rx * 0.25f), BlurMaskFilter.Blur.NORMAL)
        c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, s)
    }

    private fun sphereShader(cx: Float, cy: Float, rx: Float, ry: Float, base: Int): Shader =
        RadialGradient(cx - rx * 0.38f, cy - ry * 0.42f, max(rx, ry) * 1.45f,
            intArrayOf(Art.lighten(base), base, mul(base, 0.45f), mul(base, 0.18f)), floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)

    private fun specular(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, a: Int = 170) {
        val s = q(); s.shader = RadialGradient(x, y, max(rx, ry), intArrayOf(Color.argb(a, 255, 255, 255), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.save(); c.scale(1f, ry / rx, x, y); c.drawCircle(x, y, rx, s); c.restore()
    }

    private fun rimLight(c: Canvas, shape: Path, color: Int, width: Float) {
        val s = q(); s.style = Paint.Style.STROKE; s.strokeWidth = width; s.color = color
        s.maskFilter = BlurMaskFilter(width, BlurMaskFilter.Blur.NORMAL)
        c.save(); c.clipPath(shape); c.translate(width * 0.8f, 0f); c.drawPath(shape, s); c.restore()
    }

    private fun mul(c: Int, f: Float) = Color.argb(Color.alpha(c), (Color.red(c) * f).toInt(), (Color.green(c) * f).toInt(), (Color.blue(c) * f).toInt())

    private fun hood(x: Float, y: Float, ww: Float, hh: Float): Path = Path().apply {
        moveTo(x + ww * 0.50f, y)
        cubicTo(x + ww * 0.16f, y + hh * 0.02f, x + ww * 0.08f, y + hh * 0.30f, x + ww * 0.12f, y + hh * 0.46f)
        cubicTo(x - ww * 0.02f, y + hh * 0.62f, x + ww * 0.0f, y + hh * 0.86f, x + ww * 0.04f, y + hh)
        lineTo(x + ww * 0.20f, y + hh * 0.95f); lineTo(x + ww * 0.34f, y + hh); lineTo(x + ww * 0.50f, y + hh * 0.94f)
        lineTo(x + ww * 0.66f, y + hh); lineTo(x + ww * 0.80f, y + hh * 0.95f); lineTo(x + ww * 0.96f, y + hh)
        cubicTo(x + ww * 1.0f, y + hh * 0.86f, x + ww * 1.02f, y + hh * 0.62f, x + ww * 0.88f, y + hh * 0.46f)
        cubicTo(x + ww * 0.92f, y + hh * 0.30f, x + ww * 0.84f, y + hh * 0.02f, x + ww * 0.50f, y)
        close()
    }

    private fun robe(c: Canvas, shape: Path, top: Int, mid: Int, rim: Int) {
        val s = q()
        // volume: light from the upper left, deep shadow on the right
        s.shader = LinearGradient(W * 0.1f, 0f, W * 0.95f, 0f, intArrayOf(top, mid, 0xFF000000.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(shape, s)
        s.shader = LinearGradient(0f, 0f, 0f, H, intArrayOf(0x00000000, 0x00000000, 0xCC000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(shape, s); s.shader = null
        // cloth folds: lit edge + shadow groove
        c.save(); c.clipPath(shape)
        s.style = Paint.Style.STROKE; s.strokeCap = Paint.Cap.ROUND
        for (k in 0..4) {
            val x0 = W * (0.26f + k * 0.12f); val x1 = W * (0.2f + k * 0.15f)
            s.strokeWidth = W * 0.035f; s.color = 0x66000000
            c.drawLine(x0 + W * 0.012f, H * 0.6f, x1 + W * 0.012f, H, s)
            s.strokeWidth = W * 0.012f; s.color = 0x33FFFFFF
            c.drawLine(x0, H * 0.6f, x1, H, s)
        }
        c.restore()
        rimLight(c, shape, rim, W * 0.03f)
    }

    private fun buildReaper(c: Canvas) {
        contactShadow(c, W * 0.5f, H * 0.97f, W * 0.4f, H * 0.035f)
        val s = q()
        // scythe behind the figure
        s.shader = LinearGradient(W * 0.84f, 0f, W * 0.9f, 0f, intArrayOf(0xFF6D4C33.toInt(), 0xFF2A1A10.toInt()), null, Shader.TileMode.CLAMP)
        s.strokeWidth = W * 0.045f; s.style = Paint.Style.STROKE; s.strokeCap = Paint.Cap.ROUND
        c.drawLine(W * 0.84f, H * 0.98f, W * 0.9f, H * 0.06f, s); s.shader = null; s.style = Paint.Style.FILL
        val blade = Path().apply {
            moveTo(W * 0.9f, H * 0.06f)
            cubicTo(W * 0.68f, -H * 0.04f, W * 0.3f, H * 0.0f, W * 0.06f, H * 0.17f)
            cubicTo(W * 0.34f, H * 0.07f, W * 0.65f, H * 0.08f, W * 0.9f, H * 0.14f); close()
        }
        s.shader = LinearGradient(0f, 0f, 0f, H * 0.15f, intArrayOf(0xFFECEFF1.toInt(), 0xFF78909C.toInt(), 0xFF263238.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(blade, s); s.shader = null
        Art.neonStroke(c, blade, Neon.GREEN, W * 0.008f)
        // robe + hood
        val robeP = hood(W * 0.1f, H * 0.12f, W * 0.8f, H * 0.88f)
        robe(c, robeP, 0xFF3A3A48.toInt(), 0xFF141419.toInt(), 0xAA76FF03.toInt())
        // hood opening (dark interior)
        val opening = Path().apply { addOval(RectF(W * 0.29f, H * 0.2f, W * 0.71f, H * 0.6f), Path.Direction.CW) }
        s.shader = RadialGradient(W * 0.5f, H * 0.42f, W * 0.24f, intArrayOf(0xFF000000.toInt(), 0xFF050507.toInt(), 0xFF1A1A22.toInt()), floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(opening, s); s.shader = null
        // skull face (bone, sphere shaded)
        val cx = W * 0.5f; val cy = H * 0.37f
        s.shader = sphereShader(cx, cy, W * 0.15f, H * 0.15f, 0xFFD9D2BE.toInt())
        c.drawOval(cx - W * 0.15f, cy - H * 0.15f, cx + W * 0.15f, cy + H * 0.13f, s); s.shader = null
        // cheekbones + temples shadow
        s.color = 0x55000000
        c.drawOval(cx - W * 0.15f, cy + H * 0.0f, cx - W * 0.07f, cy + H * 0.1f, s)
        c.drawOval(cx + W * 0.07f, cy + H * 0.0f, cx + W * 0.15f, cy + H * 0.1f, s)
        // eye sockets
        s.color = 0xFF050203.toInt()
        for (ex in floatArrayOf(reaperEyes[0], reaperEyes[1])) c.drawOval(W * ex - W * 0.055f, H * 0.32f, W * ex + W * 0.055f, H * 0.395f, s)
        // nose cavity
        path.reset(); path.moveTo(cx, H * 0.405f); path.lineTo(cx - W * 0.025f, H * 0.45f); path.lineTo(cx + W * 0.025f, H * 0.45f); path.close()
        c.drawPath(path, s)
        // upper teeth
        for (k in -3..3) {
            val tx = cx + k * W * 0.02f
            s.shader = LinearGradient(0f, H * 0.465f, 0f, H * 0.495f, 0xFFF3EEDF.toInt(), 0xFF8D8574.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(tx - W * 0.009f, H * 0.465f, tx + W * 0.009f, H * 0.495f, W * 0.004f, W * 0.004f, s)
        }
        s.shader = null
        specular(c, cx - W * 0.05f, cy - H * 0.08f, W * 0.05f, H * 0.035f, 140)
        // bony hand on the staff
        for (k in 0..3) {
            s.shader = LinearGradient(W * 0.8f, 0f, W * 0.92f, 0f, 0xFFE8E1CF.toInt(), 0xFF7A7262.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(W * 0.8f, H * (0.56f + k * 0.035f), W * 0.92f, H * (0.585f + k * 0.035f), W * 0.012f, W * 0.012f, s)
        }
        s.shader = null
        if (plates) plate(c, "WILD", Neon.GREEN)
    }

    private fun makeReaperJaw(): Bitmap = Art.bitmap(w, h) { c, _ ->
        val s = q(); val cx = W * 0.5f
        s.shader = LinearGradient(0f, H * 0.49f, 0f, H * 0.56f, 0xFFD9D2BE.toInt(), 0xFF6E6656.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(cx - W * 0.1f, H * 0.495f, cx + W * 0.1f, H * 0.55f, W * 0.04f, W * 0.04f, s)
        for (k in -3..3) {
            s.shader = LinearGradient(0f, H * 0.495f, 0f, H * 0.52f, 0xFF8D8574.toInt(), 0xFFF3EEDF.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(cx + k * W * 0.02f - W * 0.009f, H * 0.493f, cx + k * W * 0.02f + W * 0.009f, H * 0.52f, W * 0.004f, W * 0.004f, s)
        }
    }

    private fun plate(c: Canvas, text: String, color: Int) {
        val s = q()
        val top = H * 0.8f; val hh = H * 0.17f
        r.set(W * 0.14f, top, W * 0.86f, top + hh)
        s.shader = LinearGradient(0f, top, 0f, top + hh, intArrayOf(0xFF4A4F55.toInt(), 0xFF15171A.toInt(), 0xFF2B2F33.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(r, hh * 0.3f, hh * 0.3f, s); s.shader = null
        s.style = Paint.Style.STROKE; s.strokeWidth = W * 0.008f; s.color = 0x88FFFFFF.toInt()
        c.drawLine(r.left + hh * 0.3f, r.top + W * 0.006f, r.right - hh * 0.3f, r.top + W * 0.006f, s)
        s.style = Paint.Style.FILL
        s.typeface = serif; s.textAlign = Paint.Align.CENTER; s.textSize = hh * 0.82f
        val ty = r.centerY() - (s.fontMetrics.ascent + s.fontMetrics.descent) / 2
        s.color = Color.BLACK; c.drawText(text, r.centerX() + W * 0.006f, ty + W * 0.008f, s)
        s.color = color; s.setShadowLayer(W * 0.04f, 0f, 0f, color); c.drawText(text, r.centerX(), ty, s); s.clearShadowLayer()
        s.color = 0x99FFFFFF.toInt(); s.textSize = hh * 0.82f
        c.save(); c.clipRect(r.left, r.top, r.right, r.centerY()); c.drawText(text, r.centerX(), ty, s); c.restore()
    }

    private fun buildHooded(c: Canvas) {
        contactShadow(c, W * 0.5f, H * 0.97f, W * 0.38f, H * 0.035f)
        val robeP = hood(W * 0.08f, H * 0.08f, W * 0.84f, H * 0.92f)
        robe(c, robeP, 0xFF5A1020.toInt(), 0xFF22050C.toInt(), 0xAAFF1744.toInt())
        val s = q()
        val face = Path().apply { addOval(RectF(W * 0.3f, H * 0.18f, W * 0.7f, H * 0.56f), Path.Direction.CW) }
        s.shader = RadialGradient(W * 0.5f, H * 0.37f, W * 0.22f, intArrayOf(0xFF000000.toInt(), 0xFF030001.toInt(), 0xFF2A060E.toInt()), floatArrayOf(0f, 0.8f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(face, s); s.shader = null
        // hands holding a bone-white candle
        s.shader = sphereShader(W * 0.5f, H * 0.7f, W * 0.16f, H * 0.07f, 0xFF3A0A14.toInt())
        c.drawOval(W * 0.34f, H * 0.64f, W * 0.66f, H * 0.77f, s); s.shader = null
        s.shader = LinearGradient(W * 0.46f, 0f, W * 0.54f, 0f, intArrayOf(0xFFFFF8E1.toInt(), 0xFFD7CCB0.toInt(), 0xFF8D836B.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(W * 0.465f, H * 0.6f, W * 0.535f, H * 0.7f, s); s.shader = null
    }

    private fun skullShape(): Path = Path().apply {
        addOval(RectF(W * 0.18f, H * 0.12f, W * 0.82f, H * 0.66f), Path.Direction.CW)
        addRoundRect(RectF(W * 0.3f, H * 0.5f, W * 0.7f, H * 0.66f), W * 0.06f, W * 0.06f, Path.Direction.CW)
    }

    private fun buildSkull(c: Canvas) {
        contactShadow(c, W * 0.5f, H * 0.93f, W * 0.3f, H * 0.04f)
        val s = q()
        val shape = skullShape()
        s.shader = sphereShader(W * 0.5f, H * 0.38f, W * 0.32f, H * 0.3f, 0xFFE3DCC8.toInt())
        c.drawPath(shape, s); s.shader = null
        // temples, cheek hollows
        s.color = 0x44000000
        c.drawOval(W * 0.16f, H * 0.36f, W * 0.3f, H * 0.56f, s); c.drawOval(W * 0.7f, H * 0.36f, W * 0.84f, H * 0.56f, s)
        // eye sockets (deep)
        for (ex in floatArrayOf(0.385f, 0.615f)) {
            s.shader = RadialGradient(W * ex, H * 0.43f, W * 0.1f, intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF5A5446.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawOval(W * ex - W * 0.1f, H * 0.36f, W * ex + W * 0.1f, H * 0.5f, s)
        }
        s.shader = null; s.color = 0xFF0A0806.toInt()
        path.reset(); path.moveTo(W * 0.5f, H * 0.5f); path.lineTo(W * 0.46f, H * 0.58f); path.lineTo(W * 0.54f, H * 0.58f); path.close()
        c.drawPath(path, s)
        // crack
        s.style = Paint.Style.STROKE; s.strokeWidth = W * 0.008f; s.color = 0xAA3E3A30.toInt()
        path.reset(); path.moveTo(W * 0.56f, H * 0.14f); path.lineTo(W * 0.6f, H * 0.22f); path.lineTo(W * 0.56f, H * 0.29f); path.lineTo(W * 0.62f, H * 0.34f)
        c.drawPath(path, s); s.style = Paint.Style.FILL
        // upper teeth
        for (k in -3..3) {
            val tx = W * 0.5f + k * W * 0.043f
            s.shader = LinearGradient(0f, H * 0.61f, 0f, H * 0.67f, 0xFFFFFBEE.toInt(), 0xFF8D8574.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(tx - W * 0.019f, H * 0.61f, tx + W * 0.019f, H * 0.675f, W * 0.008f, W * 0.008f, s)
        }
        s.shader = null
        specular(c, W * 0.38f, H * 0.2f, W * 0.12f, H * 0.06f)
        rimLight(c, shape, 0x8876FF03.toInt(), W * 0.03f)
    }

    private fun makeSkullJaw(): Bitmap = Art.bitmap(w, h) { c, _ ->
        val s = q()
        val jaw = Path().apply {
            moveTo(W * 0.27f, H * 0.66f); lineTo(W * 0.73f, H * 0.66f)
            cubicTo(W * 0.72f, H * 0.78f, W * 0.62f, H * 0.84f, W * 0.5f, H * 0.84f)
            cubicTo(W * 0.38f, H * 0.84f, W * 0.28f, H * 0.78f, W * 0.27f, H * 0.66f); close()
        }
        s.shader = LinearGradient(0f, H * 0.66f, 0f, H * 0.84f, 0xFFD6CFBB.toInt(), 0xFF5C5546.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(jaw, s)
        for (k in -3..3) {
            val tx = W * 0.5f + k * W * 0.043f
            s.shader = LinearGradient(0f, H * 0.66f, 0f, H * 0.71f, 0xFF8D8574.toInt(), 0xFFFFFBEE.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(tx - W * 0.019f, H * 0.66f, tx + W * 0.019f, H * 0.715f, W * 0.008f, W * 0.008f, s)
        }
        rimLight(c, jaw, 0x8876FF03.toInt(), W * 0.02f)
    }

    private fun buildRaven(c: Canvas) {
        val s = q()
        // blood moon with craters
        s.shader = RadialGradient(W * 0.74f, H * 0.26f, W * 0.24f, intArrayOf(0xFFFF8A80.toInt(), 0xFFB71C1C.toInt(), 0xFF3A0000.toInt(), 0x00000000), floatArrayOf(0f, 0.55f, 0.92f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(W * 0.74f, H * 0.26f, W * 0.24f, s); s.shader = null
        s.color = 0x33000000
        c.drawCircle(W * 0.7f, H * 0.2f, W * 0.04f, s); c.drawCircle(W * 0.82f, H * 0.32f, W * 0.05f, s)
        // gnarled branch (cylinder shaded)
        s.style = Paint.Style.STROKE; s.strokeCap = Paint.Cap.ROUND; s.strokeWidth = H * 0.06f
        s.shader = LinearGradient(0f, H * 0.76f, 0f, H * 0.84f, intArrayOf(0xFF6D4C33.toInt(), 0xFF2A1A10.toInt(), 0xFF120A05.toInt()), null, Shader.TileMode.CLAMP)
        c.drawLine(W * 0.02f, H * 0.82f, W * 0.98f, H * 0.76f, s)
        s.strokeWidth = H * 0.03f; c.drawLine(W * 0.72f, H * 0.78f, W * 0.86f, H * 0.92f, s)
        s.shader = null; s.style = Paint.Style.FILL
        // body
        val body = Path().apply {
            moveTo(W * 0.62f, H * 0.32f)
            cubicTo(W * 0.55f, H * 0.17f, W * 0.38f, H * 0.18f, W * 0.36f, H * 0.33f)
            cubicTo(W * 0.2f, H * 0.42f, W * 0.13f, H * 0.6f, W * 0.07f, H * 0.88f)
            lineTo(W * 0.22f, H * 0.8f)
            cubicTo(W * 0.38f, H * 0.79f, W * 0.55f, H * 0.7f, W * 0.58f, H * 0.52f)
            cubicTo(W * 0.6f, H * 0.42f, W * 0.6f, H * 0.37f, W * 0.62f, H * 0.32f); close()
        }
        s.shader = RadialGradient(W * 0.36f, H * 0.36f, W * 0.5f, intArrayOf(0xFF6A5E9A.toInt(), 0xFF2A2240.toInt(), 0xFF07050C.toInt()), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(body, s); s.shader = null
        // iridescent wing sheen + feathers
        c.save(); c.clipPath(body)
        s.shader = LinearGradient(W * 0.2f, H * 0.4f, W * 0.5f, H * 0.75f, intArrayOf(0x00000000, 0x994FC3F7.toInt(), 0x88B388FF.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, s); s.shader = null
        s.style = Paint.Style.STROKE; s.strokeWidth = W * 0.012f
        for (k in 0..5) {
            s.color = 0x55B39DDB
            c.drawLine(W * (0.27f + k * 0.04f), H * (0.44f + k * 0.035f), W * (0.13f + k * 0.035f), H * (0.78f + k * 0.012f), s)
        }
        s.style = Paint.Style.FILL; c.restore()
        specular(c, W * 0.44f, H * 0.24f, W * 0.06f, H * 0.03f, 120)
        rimLight(c, body, 0xDDE040FB.toInt(), W * 0.035f)
        Art.neonStroke(c, body, 0xFF9C27B0.toInt(), W * 0.006f)
        // upper beak
        val beak = Path().apply { moveTo(W * 0.58f, H * 0.27f); lineTo(W * 0.78f, H * 0.3f); lineTo(W * 0.6f, H * 0.315f); close() }
        s.shader = LinearGradient(0f, H * 0.27f, 0f, H * 0.32f, 0xFF8A8A92.toInt(), 0xFF202024.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(beak, s); s.shader = null
        // talons
        s.color = 0xFF3A2A20.toInt(); s.style = Paint.Style.STROKE; s.strokeWidth = W * 0.015f
        c.drawLine(W * 0.4f, H * 0.74f, W * 0.42f, H * 0.8f, s); c.drawLine(W * 0.46f, H * 0.72f, W * 0.48f, H * 0.79f, s)
    }

    private fun makeRavenBeak(): Bitmap = Art.bitmap(w, h) { c, _ ->
        val s = q()
        val low = Path().apply { moveTo(W * 0.59f, H * 0.31f); lineTo(W * 0.74f, H * 0.315f); lineTo(W * 0.6f, H * 0.335f); close() }
        s.shader = LinearGradient(0f, H * 0.31f, 0f, H * 0.34f, 0xFF5A5A62.toInt(), 0xFF101012.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(low, s)
    }

    private fun buildPumpkin(c: Canvas) {
        contactShadow(c, W * 0.5f, H * 0.9f, W * 0.38f, H * 0.05f)
        val s = q()
        val cy = H * 0.56f
        // ribbed body: back ribs darker, front ribs lit
        val ribs = floatArrayOf(-0.27f, 0.27f, -0.15f, 0.15f, 0f)
        val widths = floatArrayOf(0.2f, 0.2f, 0.22f, 0.22f, 0.22f)
        for ((i, dx) in ribs.withIndex()) {
            val cx = W * (0.5f + dx); val rx = W * widths[i]; val ry = H * 0.3f
            val base = if (i < 2) 0xFFB34700.toInt() else if (i < 4) 0xFFE06A00.toInt() else 0xFFF57C00.toInt()
            s.shader = sphereShader(cx, cy, rx, ry, base)
            c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, s)
            s.shader = null
            s.style = Paint.Style.STROKE; s.strokeWidth = W * 0.008f; s.color = 0x55401800
            c.drawOval(cx - rx, cy - ry, cx + rx, cy + ry, s); s.style = Paint.Style.FILL
        }
        specular(c, W * 0.4f, H * 0.36f, W * 0.08f, H * 0.04f, 150)
        // stem
        path.reset()
        path.moveTo(W * 0.46f, H * 0.29f); path.cubicTo(W * 0.45f, H * 0.2f, W * 0.5f, H * 0.14f, W * 0.58f, H * 0.12f)
        path.lineTo(W * 0.6f, H * 0.16f); path.cubicTo(W * 0.55f, H * 0.18f, W * 0.54f, H * 0.23f, W * 0.55f, H * 0.29f); path.close()
        s.shader = LinearGradient(W * 0.45f, 0f, W * 0.58f, 0f, 0xFF7CB342.toInt(), 0xFF33691E.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, s); s.shader = null
        // carved holes (dark interior; the glow is drawn live on top)
        s.color = 0xFF1A0700.toInt()
        c.drawPath(pumpkinEyesPath(), s); c.drawPath(pumpkinMouthPath(), s)
    }

    private fun pumpkinEyesPath(): Path = Path().apply {
        moveTo(W * 0.31f, H * 0.53f); lineTo(W * 0.38f, H * 0.42f); lineTo(W * 0.45f, H * 0.53f); close()
        moveTo(W * 0.55f, H * 0.53f); lineTo(W * 0.62f, H * 0.42f); lineTo(W * 0.69f, H * 0.53f); close()
        moveTo(W * 0.47f, H * 0.6f); lineTo(W * 0.5f, H * 0.55f); lineTo(W * 0.53f, H * 0.6f); close()
    }

    private fun pumpkinMouthPath(): Path = Path().apply {
        moveTo(W * 0.27f, H * 0.64f)
        quadTo(W * 0.5f, H * 0.82f, W * 0.73f, H * 0.64f)
        lineTo(W * 0.66f, H * 0.66f); lineTo(W * 0.62f, H * 0.71f); lineTo(W * 0.57f, H * 0.67f); lineTo(W * 0.5f, H * 0.73f)
        lineTo(W * 0.43f, H * 0.67f); lineTo(W * 0.38f, H * 0.71f); lineTo(W * 0.34f, H * 0.66f); close()
    }

    private fun makePumpkinFace(mouth: Boolean): Bitmap = Art.bitmap(w, h) { c, _ ->
        val s = q()
        val shape = if (mouth) pumpkinMouthPath() else pumpkinEyesPath()
        s.color = 0xFFFFC107.toInt(); s.maskFilter = BlurMaskFilter(W * 0.04f, BlurMaskFilter.Blur.NORMAL); s.alpha = 200
        c.drawPath(shape, s); s.maskFilter = null; s.alpha = 255
        s.shader = LinearGradient(0f, H * 0.42f, 0f, H * 0.75f, intArrayOf(0xFFFFF59D.toInt(), 0xFFFFB300.toInt(), 0xFFFF6F00.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(shape, s)
    }

    private fun buildGhost(c: Canvas) {
        contactShadow(c, W * 0.5f, H * 0.95f, W * 0.24f, H * 0.03f)
        val s = q()
        val sheet = Path().apply {
            moveTo(W * 0.24f, H * 0.82f)
            lineTo(W * 0.24f, H * 0.42f)
            cubicTo(W * 0.24f, H * 0.12f, W * 0.76f, H * 0.12f, W * 0.76f, H * 0.42f)
            lineTo(W * 0.76f, H * 0.82f)
            quadTo(W * 0.7f, H * 0.9f, W * 0.64f, H * 0.82f); quadTo(W * 0.57f, H * 0.9f, W * 0.5f, H * 0.82f)
            quadTo(W * 0.43f, H * 0.9f, W * 0.36f, H * 0.82f); quadTo(W * 0.3f, H * 0.9f, W * 0.24f, H * 0.82f); close()
        }
        // ethereal aura
        s.color = 0x55B3E5FC; s.maskFilter = BlurMaskFilter(W * 0.06f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(sheet, s); s.maskFilter = null
        s.shader = RadialGradient(W * 0.4f, H * 0.3f, W * 0.55f, intArrayOf(0xFFFFFFFF.toInt(), 0xF0E3F2FD.toInt(), 0xD08FA8C8.toInt(), 0xB04A5A7A.toInt()), floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(sheet, s); s.shader = null
        // little arms
        s.shader = sphereShader(W * 0.2f, H * 0.55f, W * 0.06f, H * 0.04f, 0xFFDDE7F0.toInt())
        c.drawOval(W * 0.15f, H * 0.5f, W * 0.27f, H * 0.6f, s)
        s.shader = sphereShader(W * 0.8f, H * 0.52f, W * 0.06f, H * 0.04f, 0xFFB0C0D0.toInt())
        c.drawOval(W * 0.73f, H * 0.47f, W * 0.85f, H * 0.57f, s); s.shader = null
        specular(c, W * 0.38f, H * 0.24f, W * 0.08f, H * 0.04f, 200)
    }

    private val ROYAL = mapOf(
        ReaperConfig.ACE to ("A" to Neon.RED), ReaperConfig.KING to ("K" to Neon.GREEN),
        ReaperConfig.QUEEN to ("Q" to Neon.PURPLE), ReaperConfig.JACK to ("J" to Neon.CYAN), ReaperConfig.TEN to ("10" to Neon.ORANGE)
    )

    private fun buildRoyal(c: Canvas, sym: Int) {
        val (label, col) = ROYAL.getValue(sym)
        val tp = q().apply {
            typeface = serif; textAlign = Paint.Align.CENTER
            textSize = H * (if (label.length > 1) 0.6f else 0.74f)
            if (label.length > 1) textScaleX = 0.8f
        }
        val face = Path()
        tp.getTextPath(label, 0, label.length, W / 2 - W * 0.02f, H / 2 - (tp.fontMetrics.ascent + tp.fontMetrics.descent) / 2 - H * 0.02f, face)
        val b = RectF(); face.computeBounds(b, true)
        contactShadow(c, b.centerX() + W * 0.04f, b.bottom + H * 0.04f, b.width() * 0.45f, H * 0.03f)
        // extrusion: stacked offset copies, darker with depth
        val depth = (W * 0.06f).toInt().coerceAtLeast(3)
        val s = q()
        for (k in depth downTo 1) {
            s.color = mul(col, 0.12f + 0.25f * (1 - k.toFloat() / depth))
            c.save(); c.translate(k * 0.7f, k * 0.9f); c.drawPath(face, s); c.restore()
        }
        // front face: lit gradient
        s.shader = LinearGradient(0f, b.top, 0f, b.bottom, intArrayOf(Color.WHITE, Art.lighten(col), col, mul(col, 0.55f)), floatArrayOf(0f, 0.18f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(face, s); s.shader = null
        // bevel highlight + neon edge
        s.style = Paint.Style.STROKE; s.strokeWidth = W * 0.012f; s.color = 0xAAFFFFFF.toInt()
        c.save(); c.translate(-W * 0.004f, -W * 0.005f); c.clipRect(0f, 0f, W, b.centerY()); c.drawPath(face, s); c.restore()
        s.color = col; s.alpha = 140; s.strokeWidth = W * 0.02f; s.maskFilter = BlurMaskFilter(W * 0.03f, BlurMaskFilter.Blur.OUTER)
        c.drawPath(face, s)
    }
}

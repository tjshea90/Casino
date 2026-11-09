package com.tjshea.neonreaper.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Painting toolkit shared by the royals, the coffin, the six character portraits and the relic icons.
 * Everything is procedural (paths, gradients, one tiny tileable noise bitmap) so the APK stays small. All shapes are authored
 * in "unit space" (0..1 of the art square, y down) and scaled by the caller's size `s`; the light always comes from the upper
 * left, rim light from the lower right. Nothing here keeps mutable shared state (several threads build art at layout time).
 */
internal object Kit {
    // ------------------------------------------------------------------ colour maths
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * k).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt())
    }
    /** Scale the RGB channels (alpha kept). */
    fun mul(c: Int, f: Float) = Color.argb(Color.alpha(c), (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255), (Color.blue(c) * f).toInt().coerceIn(0, 255))
    /** Move RGB towards white by [f] (alpha kept). */
    fun lift(c: Int, f: Float) = Color.argb(Color.alpha(c), (Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * f).toInt())
    /** Same colour with alpha [a] (0..1). */
    fun al(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24)

    // ------------------------------------------------------------------ paints
    fun fill(color: Int = 0xFF000000.toInt()): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; this.color = color }
    fun stroke(color: Int, w: Float, cap: Paint.Cap = Paint.Cap.ROUND, join: Paint.Join = Paint.Join.ROUND): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; this.color = color; strokeWidth = w; strokeCap = cap; strokeJoin = join }
    private fun blurred(p: Paint, radius: Float): Paint { p.maskFilter = BlurMaskFilter(radius.coerceAtLeast(0.6f), BlurMaskFilter.Blur.NORMAL); return p }

    private val SRC_ATOP = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    val ADD = PorterDuffXfermode(PorterDuff.Mode.ADD)
    val SCREEN = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    val MULTIPLY = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)

    // ------------------------------------------------------------------ noise textures (built once, 3 x 64 KB)
    private const val NS = 128
    private fun fbm(seed: Long, octaves: Int, baseLattice: Int, gain: Float): FloatArray {
        val rnd = java.util.Random(seed)
        val acc = FloatArray(NS * NS); var amp = 1f; var tot = 0f
        for (o in 0 until octaves) {
            val n = baseLattice shl o
            val lat = FloatArray(n * n) { rnd.nextFloat() }
            for (y in 0 until NS) {
                val fy = y.toFloat() * n / NS; val y0 = fy.toInt(); var ty = fy - y0; ty = ty * ty * (3 - 2 * ty); val y1 = (y0 + 1) % n
                for (x in 0 until NS) {
                    val fx = x.toFloat() * n / NS; val x0 = fx.toInt(); var tx = fx - x0; tx = tx * tx * (3 - 2 * tx); val x1 = (x0 + 1) % n
                    val a = lat[y0 * n + x0] + (lat[y0 * n + x1] - lat[y0 * n + x0]) * tx
                    val b = lat[y1 * n + x0] + (lat[y1 * n + x1] - lat[y1 * n + x0]) * tx
                    acc[y * NS + x] += amp * (a + (b - a) * ty)
                }
            }
            tot += amp; amp *= gain
        }
        for (i in acc.indices) acc[i] /= tot
        return acc
    }
    private fun smooth(e0: Float, e1: Float, v: Float): Float { val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Mid-grey fbm (for OVERLAY/SOFT mottling). */
    val noise: Bitmap by lazy {
        val f = fbm(11, 5, 4, 0.55f); val px = IntArray(NS * NS)
        for (i in px.indices) { val g = (((f[i] - 0.5f) * 1.9f + 0.5f).coerceIn(0f, 1f) * 255).toInt(); px[i] = (255 shl 24) or (g shl 16) or (g shl 8) or g }
        Bitmap.createBitmap(px, NS, NS, Bitmap.Config.ARGB_8888)
    }
    /** Black blotches (alpha mask): grime, oxidation, age. */
    val grime: Bitmap by lazy {
        val f = fbm(23, 5, 4, 0.6f); val px = IntArray(NS * NS)
        for (i in px.indices) px[i] = (((smooth(0.42f, 0.78f, f[i]) * 235).toInt()) shl 24)
        Bitmap.createBitmap(px, NS, NS, Bitmap.Config.ARGB_8888)
    }
    /** White sparse specks (alpha mask): metal grit, bone pores, frost. */
    val flecks: Bitmap by lazy {
        val f = fbm(37, 4, 16, 0.5f); val px = IntArray(NS * NS)
        for (i in px.indices) { val a = smooth(0.60f, 0.86f, f[i]); px[i] = (((a * a * 255).toInt()) shl 24) or 0x00FFFFFF }
        Bitmap.createBitmap(px, NS, NS, Bitmap.Config.ARGB_8888)
    }

    /**
     * Fill [area] (under whatever clip is active) with a repeating noise mask. [grain] = pixels per texel.
     * [tint] recolours the alpha mask (grime/flecks); [mode] optional blend (null = normal).
     */
    fun tex(c: Canvas, area: RectF, bmp: Bitmap, grain: Float, alpha: Float, tint: Int = 0, mode: PorterDuffXfermode? = null, ox: Float = 0f, oy: Float = 0f) {
        val sh = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val m = Matrix(); m.setScale(grain, grain); m.postTranslate(ox, oy); sh.setLocalMatrix(m)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        p.shader = sh; p.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        if (tint != 0) p.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
        if (mode != null) p.xfermode = mode
        c.drawRect(area, p)
    }

    // ------------------------------------------------------------------ tiny SVG-like path language (unit space)
    private class Seg(val op: Char, val v: FloatArray, val sx: Float, val sy: Float)

    private fun parse(d: String): List<Seg> {
        val toks = ArrayList<Any>()
        var i = 0
        while (i < d.length) {
            val ch = d[i]
            when {
                ch.isLetter() -> { toks.add(ch); i++ }
                ch == '-' || ch == '.' || ch.isDigit() -> {
                    var j = i + 1
                    while (j < d.length && (d[j].isDigit() || d[j] == '.')) { if (d[j] == '.' && d.substring(i, j).contains('.')) break; j++ }
                    toks.add(d.substring(i, j).toFloat()); i = j
                }
                else -> i++
            }
        }
        val out = ArrayList<Seg>()
        var k = 0; var cmd = 'M'; var cx = 0f; var cy = 0f; var mx = 0f; var my = 0f
        fun num(): Float = toks[k++] as Float
        while (k < toks.size) {
            if (toks[k] is Char) { cmd = toks[k] as Char; k++; if (cmd == 'Z' || cmd == 'z') { out.add(Seg('Z', FloatArray(0), cx, cy)); cx = mx; cy = my; continue } }
            if (k >= toks.size || toks[k] !is Float) continue
            when (cmd) {
                'M' -> { val x = num(); val y = num(); out.add(Seg('M', floatArrayOf(x, y), cx, cy)); cx = x; cy = y; mx = x; my = y; cmd = 'L' }
                'L' -> { val x = num(); val y = num(); out.add(Seg('L', floatArrayOf(x, y), cx, cy)); cx = x; cy = y }
                'Q' -> { val a = num(); val b = num(); val x = num(); val y = num(); out.add(Seg('Q', floatArrayOf(a, b, x, y), cx, cy)); cx = x; cy = y }
                'C' -> { val a = num(); val b = num(); val c2 = num(); val d2 = num(); val x = num(); val y = num()
                    out.add(Seg('C', floatArrayOf(a, b, c2, d2, x, y), cx, cy)); cx = x; cy = y }
                else -> k++
            }
        }
        return out
    }

    private fun addSeg(p: Path, sg: Seg, s: Float) {
        val v = sg.v
        when (sg.op) {
            'M' -> p.moveTo(v[0] * s, v[1] * s)
            'L' -> p.lineTo(v[0] * s, v[1] * s)
            'Q' -> p.quadTo(v[0] * s, v[1] * s, v[2] * s, v[3] * s)
            'C' -> p.cubicTo(v[0] * s, v[1] * s, v[2] * s, v[3] * s, v[4] * s, v[5] * s)
            'Z' -> p.close()
        }
    }

    /** Path from an absolute-coordinate string ("M x y L x y Q cx cy x y C c1x c1y c2x c2y x y Z"), unit space scaled by [s]. */
    fun path(s: Float, d: String): Path { val p = Path(); for (sg in parse(d)) addSeg(p, sg, s); return p }

    /**
     * Symmetric closed shape: [d] describes the LEFT outline from the axis (top) to the axis (bottom); the right side is the
     * mirror image about x = [axis], traversed backwards, and the shape is closed.
     */
    fun sym(s: Float, d: String, axis: Float = 0.5f): Path {
        val segs = parse(d).filter { it.op != 'Z' }
        val p = Path()
        for (sg in segs) addSeg(p, sg, s)
        fun mx(x: Float) = (2 * axis - x) * s
        val last = segs.last()
        val ex = last.v[last.v.size - 2]; val ey = last.v[last.v.size - 1]
        p.lineTo(mx(ex), ey * s)
        for (k in segs.size - 1 downTo 1) {
            val sg = segs[k]; val v = sg.v
            when (sg.op) {
                'L' -> p.lineTo(mx(sg.sx), sg.sy * s)
                'Q' -> p.quadTo(mx(v[0]), v[1] * s, mx(sg.sx), sg.sy * s)
                'C' -> p.cubicTo(mx(v[2]), v[3] * s, mx(v[0]), v[1] * s, mx(sg.sx), sg.sy * s)
            }
        }
        p.close()
        return p
    }

    fun bounds(p: Path): RectF { val r = RectF(); p.computeBounds(r, true); return r }
    fun mirrored(p: Path, cx: Float): Path { val q = Path(p); val m = Matrix(); m.setScale(-1f, 1f, cx, 0f); q.transform(m); return q }
    fun moved(p: Path, dx: Float, dy: Float): Path { val q = Path(p); q.offset(dx, dy); return q }
    /** Outline of [p] grown by [w] on every side with sharp (mitered) corners. */
    fun grown(p: Path, w: Float): Path {
        val q = Path()
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL_AND_STROKE; strokeWidth = w * 2; strokeJoin = Paint.Join.MITER; strokeMiter = 4f }
        pt.getFillPath(p, q)
        return q
    }

    // ------------------------------------------------------------------ light and volume
    /** Soft round glow (radial gradient, one draw call). */
    fun halo(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, a: Float = 1f, ry: Float = r, mode: PorterDuffXfermode? = null) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(0f, 0f, r, intArrayOf(al(color, a), al(color, a * 0.45f), al(color, 0f)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        if (mode != null) p.xfermode = mode
        c.save(); c.translate(cx, cy); c.scale(1f, ry / r); c.drawCircle(0f, 0f, r, p); c.restore()
    }
    /** Aura that fades to exactly zero at radius [r] (so a cell-sized aura never shows a hard square edge on the drum). */
    fun aura(c: Canvas, cx: Float, cy: Float, r: Float, color: Int, a: Float, ry: Float = r) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(0f, 0f, r, intArrayOf(al(color, a), al(color, a * 0.62f), al(color, a * 0.30f), al(color, a * 0.09f), al(color, 0f)),
            floatArrayOf(0f, 0.25f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.save(); c.translate(cx, cy); c.scale(1f, ry / r); c.drawCircle(0f, 0f, r, p); c.restore()
    }
    /** Contact shadow under an object. */
    fun groundShadow(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, a: Float = 0.65f) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(0f, 0f, rx, intArrayOf(al(0xFF000000.toInt(), a), al(0xFF000000.toInt(), a * 0.6f), 0), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.save(); c.translate(cx, cy); c.scale(1f, ry / rx); c.drawCircle(0f, 0f, rx, p); c.restore()
    }
    /** Brushed-metal ramp from the light side to the shadow side with a dim bounce band (the reflection that sells metal). */
    fun metal(x0: Float, y0: Float, x1: Float, y1: Float, base: Int, hi: Float = 0.5f, lo: Float = 0.3f): Shader =
        LinearGradient(x0, y0, x1, y1,
            intArrayOf(lift(base, hi), base, mul(base, 0.62f), mul(base, lo), mix(base, lift(base, hi), 0.25f), mul(base, lo * 0.7f)),
            floatArrayOf(0f, 0.22f, 0.45f, 0.62f, 0.8f, 1f), Shader.TileMode.CLAMP)
    /** Three-stop volume gradient (lit -> body -> shade). */
    fun volume(x0: Float, y0: Float, x1: Float, y1: Float, lit: Int, body: Int, shade: Int): Shader =
        LinearGradient(x0, y0, x1, y1, intArrayOf(lit, body, shade), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)

    /** Offset copies behind a shape: a solid side wall running to the lower right, [near] at the face fading to [far]. */
    fun extrude(c: Canvas, p: Path, dx: Float, dy: Float, steps: Int, near: Int, far: Int) {
        val pt = fill()
        for (k in steps downTo 1) {
            pt.color = mix(near, far, k / steps.toFloat())
            c.save(); c.translate(dx * k, dy * k); c.drawPath(p, pt); c.restore()
        }
    }

    /**
     * Light crescent along the edges of [p] that face the offset direction: the part of [p] not covered by [p] shifted by
     * (-dx,-dy) lies on the (+dx,+dy) side, so pass the direction the EDGE faces. Pure clip work, no path ops.
     */
    fun edgeLight(c: Canvas, p: Path, dx: Float, dy: Float, paint: Paint) {
        val b = bounds(p); b.inset(-abs(dx) - 2f, -abs(dy) - 2f)
        c.save(); c.clipPath(p); c.clipOutPath(moved(p, -dx, -dy)); c.drawRect(b, paint); c.restore()
    }

    /** Glow that hugs the inside of [p]: soft stroke clipped to the shape. */
    fun innerGlow(c: Canvas, p: Path, color: Int, w: Float, a: Float) {
        val pt = blurred(stroke(al(color, a), w * 2, Paint.Cap.BUTT, Paint.Join.MITER), w * 0.9f)
        c.save(); c.clipPath(p); c.drawPath(p, pt); c.restore()
    }

    /** Fill [p] with [shader], then add a darker blotchy age layer and bright grit, both clipped to the shape. */
    fun weather(c: Canvas, p: Path, s: Float, grime: Float, grit: Float, grimeTint: Int = 0xFF000000.toInt(), gritTint: Int = 0xFFFFFFFF.toInt(), seed: Int = 0) {
        val b = bounds(p)
        c.save(); c.clipPath(p)
        val g = s / 256f
        if (grime > 0f) tex(c, b, Kit.grime, 1.3f * g + 0.4f, grime, grimeTint, null, seed * 37f, seed * 53f)
        if (grit > 0f) tex(c, b, Kit.flecks, 0.9f * g + 0.3f, grit, gritTint, null, seed * 71f, seed * 19f)
        c.restore()
    }

    // ------------------------------------------------------------------ cracks
    /** Jagged crack from (x0,y0) to (x1,y1) (pixels). [glow] != 0 lets coloured light leak out of it. */
    fun crack(c: Canvas, seed: Long, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, glow: Int = 0, jag: Float = 0.1f, branches: Int = 1, dark: Int = 0xFF060405.toInt()) {
        val r = java.util.Random(seed)
        val len = hypot(x1 - x0, y1 - y0); if (len < 1f) return
        val nx = -(y1 - y0) / len; val ny = (x1 - x0) / len
        val n = max(5, (len / (w * 5f)).toInt().coerceAtMost(11))
        val pts = FloatArray((n + 1) * 2)
        for (i in 0..n) {
            val t = i / n.toFloat(); val off = if (i == 0 || i == n) 0f else (r.nextFloat() - 0.5f) * 2f * jag * len
            pts[i * 2] = x0 + (x1 - x0) * t + nx * off; pts[i * 2 + 1] = y0 + (y1 - y0) * t + ny * off
        }
        val path = Path(); path.moveTo(pts[0], pts[1]); for (i in 1..n) path.lineTo(pts[i * 2], pts[i * 2 + 1])
        val main = atan2(y1 - y0, x1 - x0)
        for (b in 0 until branches) {
            val k = 2 + r.nextInt(max(1, n - 3)); val ang = main + (if (r.nextBoolean()) 1f else -1f) * (0.55f + r.nextFloat() * 0.6f)
            val bl = len * (0.22f + r.nextFloat() * 0.18f)
            var bx = pts[k * 2]; var by = pts[k * 2 + 1]; path.moveTo(bx, by)
            for (j in 1..3) { val a = ang + (r.nextFloat() - 0.5f) * 0.9f; bx += cos(a) * bl / 3f; by += sin(a) * bl / 3f; path.lineTo(bx, by) }
        }
        val pt = stroke(0, w, Paint.Cap.ROUND, Paint.Join.MITER)
        if (glow != 0) { pt.color = al(glow, 0.55f); pt.strokeWidth = w * 4.2f; pt.maskFilter = BlurMaskFilter(w * 2.2f, BlurMaskFilter.Blur.NORMAL); c.drawPath(path, pt); pt.maskFilter = null }
        pt.color = dark; pt.strokeWidth = w; c.drawPath(path, pt)
        if (glow != 0) { pt.color = lift(glow, 0.35f); pt.strokeWidth = w * 0.42f; c.drawPath(path, pt) }
        else { pt.color = 0x30FFFFFF; pt.strokeWidth = w * 0.45f; c.save(); c.translate(-w * 0.7f, -w * 0.7f); c.drawPath(path, pt); c.restore() }
    }

    // ------------------------------------------------------------------ small props
    /** Tapered curved spike with base centred at (x,y) pointing along [ang] (radians), [len] long, [wid] wide at the root. */
    fun thorn(c: Canvas, x: Float, y: Float, ang: Float, len: Float, wid: Float, color: Int, hi: Int = lift(color, 0.45f), curl: Float = 0.25f) {
        val ca = cos(ang); val sa = sin(ang)
        val tx = x + ca * len - sa * len * curl; val ty = y + sa * len + ca * len * curl
        val p = Path()
        p.moveTo(x - sa * wid / 2, y + ca * wid / 2)
        p.quadTo(x + ca * len * 0.55f - sa * wid * 0.12f, y + sa * len * 0.55f + ca * wid * 0.12f, tx, ty)
        p.quadTo(x + ca * len * 0.5f + sa * wid * 0.25f, y + sa * len * 0.5f - ca * wid * 0.25f, x + sa * wid / 2, y - ca * wid / 2)
        p.close()
        val pt = fill(color); pt.shader = LinearGradient(x - sa * wid / 2, y + ca * wid / 2, x + sa * wid / 2, y - ca * wid / 2, hi, mul(color, 0.45f), Shader.TileMode.CLAMP)
        c.drawPath(p, pt)
    }

    /** Dead-bone little skull used as an ornament (centre, radius). */
    fun miniSkull(c: Canvas, cx: Float, cy: Float, r: Float, bone: Int, glow: Int, glowOn: Boolean = true) {
        val p = fill()
        p.shader = RadialGradient(cx - r * 0.35f, cy - r * 0.45f, r * 1.6f, intArrayOf(lift(bone, 0.35f), bone, mul(bone, 0.35f)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        val skull = Path(); skull.addOval(RectF(cx - r, cy - r, cx + r, cy + r * 0.55f), Path.Direction.CW)
        skull.addRoundRect(RectF(cx - r * 0.58f, cy + r * 0.25f, cx + r * 0.58f, cy + r * 0.95f), r * 0.2f, r * 0.2f, Path.Direction.CW)
        c.drawPath(skull, p); p.shader = null
        p.color = 0xFF070505.toInt()
        c.drawOval(cx - r * 0.62f, cy - r * 0.32f, cx - r * 0.12f, cy + r * 0.28f, p)
        c.drawOval(cx + r * 0.12f, cy - r * 0.32f, cx + r * 0.62f, cy + r * 0.28f, p)
        val nose = Path(); nose.moveTo(cx, cy + r * 0.2f); nose.lineTo(cx - r * 0.12f, cy + r * 0.5f); nose.lineTo(cx + r * 0.12f, cy + r * 0.5f); nose.close(); c.drawPath(nose, p)
        if (glowOn) { p.color = glow; c.drawCircle(cx - r * 0.37f, cy - r * 0.02f, r * 0.14f, p); c.drawCircle(cx + r * 0.37f, cy - r * 0.02f, r * 0.14f, p) }
        val tp = stroke(0xCC070505.toInt(), max(0.8f, r * 0.07f)); for (k in -1..1) c.drawLine(cx + k * r * 0.28f, cy + r * 0.62f, cx + k * r * 0.28f, cy + r * 0.92f, tp)
    }

    /** Chain of alternating links along a polyline of pixel points; returns nothing, draws metal rings. */
    fun chain(c: Canvas, pts: FloatArray, link: Float, color: Int) {
        val n = pts.size / 2; if (n < 2) return
        var carry = 0f; var idx = 0
        val ring = stroke(color, link * 0.26f)
        val shade = stroke(mul(color, 0.4f), link * 0.26f)
        val hiP = stroke(lift(color, 0.55f), link * 0.1f)
        for (i in 0 until n - 1) {
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[i * 2 + 2]; val by = pts[i * 2 + 3]
            val seg = hypot(bx - ax, by - ay); if (seg < 0.01f) continue
            val dx = (bx - ax) / seg; val dy = (by - ay) / seg; val ang = (atan2(dy, dx) * 180f / PI).toFloat()
            var d = carry
            while (d < seg) {
                val x = ax + dx * d; val y = ay + dy * d
                c.save(); c.translate(x, y); c.rotate(ang)
                if (idx % 2 == 0) {
                    val rr = RectF(-link * 0.62f, -link * 0.36f, link * 0.62f, link * 0.36f)
                    c.drawRoundRect(rr, link * 0.34f, link * 0.34f, shade); c.save(); c.translate(-link * 0.04f, -link * 0.05f); c.drawRoundRect(rr, link * 0.34f, link * 0.34f, ring); c.restore()
                    c.drawArc(RectF(-link * 0.5f, -link * 0.3f, link * 0.1f, link * 0.3f), 200f, 110f, false, hiP)
                } else {
                    val rr = RectF(-link * 0.4f, -link * 0.14f, link * 0.4f, link * 0.14f)
                    c.drawRoundRect(rr, link * 0.14f, link * 0.14f, ring); c.drawLine(-link * 0.34f, -link * 0.1f, link * 0.3f, -link * 0.1f, hiP)
                }
                c.restore(); idx++; d += link * 0.78f
            }
            carry = d - seg
        }
    }

    /** Four-point glint star (specular catch-light). */
    fun glint(c: Canvas, x: Float, y: Float, r: Float, color: Int = Color.WHITE, a: Float = 0.95f) {
        val p = fill(al(color, a))
        val s = Path(); s.moveTo(x, y - r); s.quadTo(x + r * 0.12f, y - r * 0.12f, x + r, y); s.quadTo(x + r * 0.12f, y + r * 0.12f, x, y + r)
        s.quadTo(x - r * 0.12f, y + r * 0.12f, x - r, y); s.quadTo(x - r * 0.12f, y - r * 0.12f, x, y - r); s.close()
        c.drawPath(s, p)
        halo(c, x, y, r * 1.1f, color, a * 0.35f)
    }

    /** Wispy mist: overlapping soft ellipses along a horizontal band (cheap radial gradients). */
    fun mist(c: Canvas, x0: Float, x1: Float, y: Float, h: Float, color: Int, a: Float, seed: Long = 5, count: Int = 7) {
        val r = java.util.Random(seed)
        for (i in 0 until count) {
            val x = x0 + (x1 - x0) * (i + 0.5f + (r.nextFloat() - 0.5f) * 0.6f) / count
            val rr = (x1 - x0) / count * (0.9f + r.nextFloat() * 0.8f)
            halo(c, x, y + (r.nextFloat() - 0.5f) * h * 0.5f, rr, color, a * (0.6f + r.nextFloat() * 0.5f), h * (0.5f + r.nextFloat() * 0.4f))
        }
    }

    /** Glowing eye / lens: soft halo, coloured ellipse, hot core. */
    fun eye(c: Canvas, x: Float, y: Float, r: Float, color: Int, core: Int = 0xFFFFE8E0.toInt(), glow: Float = 1f, squash: Float = 0.55f) {
        halo(c, x, y, r * 3.4f, color, 0.55f * glow, r * 2.6f)
        val p = fill(color)
        c.drawOval(x - r * 1.15f, y - r * squash, x + r * 1.15f, y + r * squash, p)
        p.color = core; c.drawOval(x - r * 0.5f, y - r * squash * 0.5f, x + r * 0.5f, y + r * squash * 0.5f, p)
    }

    val IRON_D = 0xFF16181D.toInt()
    val BONE = 0xFFD9D2BE.toInt()
}

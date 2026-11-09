package com.tjshea.neonreaper.ui

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import com.tjshea.neonreaper.engine.ReaperConfig

/**
 * Dark painted portraits of the cast (Reaper, Plague Doctor, Cursed Skull, Blood Raven, Demon, Wraith), all drawn into the unit
 * square (0,0)-(s,s) with paths and gradients. Close crops (busts) so faces stay readable at ~130 px.
 * `live = true` leaves out the parts that [Characters] animates itself (jaws, beaks, eye glow, flames); `live = false` is the
 * complete still used for the atlas, icons and the blurred spin faces.
 */
internal object Portraits {
    val GREEN = 0xFF7CF52B.toInt()
    val RED = 0xFFFF2030.toInt()
    const val BONE = 0xFFD8D0B8.toInt()

    /** Where the live eyes go (unit square): x1,y1,x2,y2,radius. */
    fun eyes(sym: Int): FloatArray? = when (sym) {
        ReaperConfig.WILD -> floatArrayOf(0.4425f, 0.3715f, 0.5575f, 0.3715f, 0.0135f)
        ReaperConfig.DOCTOR -> Doctor.LENSES
        ReaperConfig.SKULL -> floatArrayOf(SkullChar.SOCKETS[0], SkullChar.SOCKETS[1], SkullChar.SOCKETS[2], SkullChar.SOCKETS[3], 0.04f)
        ReaperConfig.RAVEN -> Raven.EYE.let { floatArrayOf(it[0], it[1], it[0], it[1], it[2]) }
        ReaperConfig.DEMON -> Demon.EYES
        ReaperConfig.WRAITH -> Wraith.EYES
        else -> null
    }

    fun has(sym: Int) = sym in ReaperConfig.WILD..ReaperConfig.WRAITH

    fun paint(c: Canvas, s: Float, sym: Int, live: Boolean) {
        when (sym) {
            ReaperConfig.WILD -> reaper(c, s, live)
            ReaperConfig.DOCTOR -> Doctor.paint(c, s, live)
            ReaperConfig.SKULL -> SkullChar.paint(c, s, live)
            ReaperConfig.RAVEN -> Raven.paint(c, s, live)
            ReaperConfig.DEMON -> Demon.paint(c, s, live)
            ReaperConfig.WRAITH -> Wraith.paint(c, s, live)
        }
    }

    /** Jaw / lower-beak sprite at its rest position (drawn on an otherwise empty square). */
    fun paintJaw(c: Canvas, s: Float, sym: Int) {
        when (sym) {
            ReaperConfig.WILD -> reaperJaw(c, s)
            ReaperConfig.SKULL -> SkullChar.jaw(c, s)
            ReaperConfig.RAVEN -> Raven.jawSprite(c, s)
        }
    }

    // ================================================================== reusable front-view skull
    class SkullOpt(val tone: Int = BONE, val glow: Int = RED, val stain: Int = 0xFF4A3A18.toInt(), val seed: Int = 1, val teeth: Int = 8)

    private const val UPPER = "M.5,0 C.16,0 0,.20 0,.44 C0,.56 .07,.62 .13,.68 C.16,.72 .15,.76 .21,.80 L.5,.80"
    private const val SOCKET = "M.07,.40 C.18,.35 .33,.37 .44,.48 C.46,.58 .38,.66 .27,.65 C.14,.64 .05,.55 .07,.40 Z"
    private const val NOSE = "M.5,.56 L.455,.73 Q.5,.70 .545,.73 Z"

    /** Cranium + cheekbones + upper teeth in the box (x,y)-(x+u, y+0.9u). With [glowOn] the eyes are baked in. */
    fun skullUpper(c: Canvas, x: Float, y: Float, u: Float, o: SkullOpt, glowOn: Boolean) {
        c.save(); c.translate(x, y)
        val up = Kit.sym(u, UPPER)
        val fp = Kit.fill()
        fp.shader = RadialGradient(u * 0.36f, u * 0.26f, u * 0.95f, intArrayOf(Kit.lift(o.tone, 0.5f), o.tone, Kit.mul(o.tone, 0.5f), Kit.mul(o.tone, 0.18f)), floatArrayOf(0f, 0.38f, 0.76f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(up, fp); fp.shader = null
        c.save(); c.clipPath(up)
        val blk = 0xFF000000.toInt()
        // temple + cheek hollows, brow shadow
        Kit.halo(c, u * 0.04f, u * 0.50f, u * 0.17f, blk, 0.55f, u * 0.2f); Kit.halo(c, u * 0.96f, u * 0.50f, u * 0.17f, blk, 0.55f, u * 0.2f)
        Kit.halo(c, u * 0.17f, u * 0.74f, u * 0.14f, blk, 0.6f, u * 0.10f); Kit.halo(c, u * 0.83f, u * 0.74f, u * 0.14f, blk, 0.6f, u * 0.10f)
        // light on the forehead and cheekbones
        Kit.halo(c, u * 0.38f, u * 0.14f, u * 0.26f, 0xFFFFF6DC.toInt(), 0.34f, u * 0.14f)
        Kit.halo(c, u * 0.14f, u * 0.62f, u * 0.07f, 0xFFFFF6DC.toInt(), 0.30f, u * 0.05f)
        // the lower face sinks into the hood's darkness
        fp.shader = LinearGradient(0f, u * 0.45f, 0f, u * 0.86f, 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(-u * 0.1f, 0f, u * 1.1f, u * 0.9f, fp); fp.shader = null
        if (glowOn) { Kit.halo(c, u * 0.26f, u * 0.5f, u * 0.28f, o.glow, 0.34f); Kit.halo(c, u * 0.74f, u * 0.5f, u * 0.28f, o.glow, 0.34f) }
        Kit.weather(c, up, u, 0.4f, 0.22f, o.stain, 0xFFFFFFFF.toInt(), o.seed)
        c.restore()
        // sockets (deep: black centre, bone-lit lip)
        val sock = Kit.path(u, SOCKET); val sock2 = Kit.mirrored(sock, u / 2)
        for ((i, sp) in listOf(sock, sock2).withIndex()) {
            val cx = u * (if (i == 0) 0.27f else 0.73f)
            fp.shader = RadialGradient(cx, u * 0.51f, u * 0.2f, intArrayOf(0xFF000000.toInt(), 0xFF020102.toInt(), 0xFF3A3020.toInt()), floatArrayOf(0f, 0.72f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(sp, fp)
        }
        fp.shader = null
        val nose = Kit.path(u, NOSE); c.drawPath(nose, Kit.fill(0xFF030203.toInt()))
        c.drawPath(up, Kit.stroke(0xCC050303.toInt(), u * 0.012f))
        Kit.crack(c, 9L * o.seed, u * 0.55f, u * 0.0f, u * 0.66f, u * 0.32f, u * 0.011f, 0, 0.1f, 1, 0xFF0A0604.toInt())
        // upper teeth
        val n = o.teeth; val tw = u * 0.62f / n
        val tp = Kit.fill()
        for (i in 0 until n) {
            val tx = u * 0.19f + i * tw
            val len = u * (0.9f - ((i * 7 + o.seed) % 3) * 0.012f)
            tp.shader = LinearGradient(0f, u * 0.80f, 0f, len, 0xFFD8CFB0.toInt(), 0xFF5B523D.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(tx + tw * 0.1f, u * 0.79f, tx + tw * 0.9f, len, tw * 0.25f, tw * 0.25f, tp)
            c.drawLine(tx + tw * 0.05f, u * 0.80f, tx + tw * 0.05f, len - u * 0.01f, Kit.stroke(0xCC0A0805.toInt(), u * 0.008f, Paint.Cap.BUTT))
        }
        if (glowOn) for (cx in floatArrayOf(0.30f, 0.70f)) Kit.eye(c, u * cx, u * 0.535f, u * 0.045f, o.glow, 0xFFFFE6DE.toInt(), 1f, 0.75f)
        c.restore()
    }

    fun skullJaw(c: Canvas, x: Float, y: Float, u: Float, o: SkullOpt) {
        c.save(); c.translate(x, y)
        val jaw = Kit.sym(u, "M.5,.86 L.2,.86 C.2,.94 .3,1.0 .5,1.02")
        val jp = Kit.fill(); jp.shader = LinearGradient(0f, u * 0.86f, 0f, u * 1.02f, Kit.mul(o.tone, 0.9f), Kit.mul(o.tone, 0.2f), Shader.TileMode.CLAMP)
        c.drawPath(jaw, jp)
        val n = o.teeth; val tw = u * 0.62f / n
        val tp = Kit.fill()
        for (i in 0 until n) {
            val tx = u * 0.19f + i * tw
            tp.shader = LinearGradient(0f, u * 0.86f, 0f, u * 0.95f, 0xFF4F4836.toInt(), 0xFFC3B995.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(tx + tw * 0.1f, u * 0.86f, tx + tw * 0.9f, u * (0.94f - ((i * 5 + o.seed) % 3) * 0.01f), tw * 0.25f, tw * 0.25f, tp)
            c.drawLine(tx + tw * 0.05f, u * 0.87f, tx + tw * 0.05f, u * 0.93f, Kit.stroke(0xCC0A0805.toInt(), u * 0.008f, Paint.Cap.BUTT))
        }
        c.drawPath(jaw, Kit.stroke(0xCC050303.toInt(), u * 0.012f))
        c.restore()
    }

    // ================================================================== GRIM REAPER (WILD)
    private val REAPER_SKULL = SkullOpt(BONE, RED, 0xFF4A3A18.toInt(), 3, 8)

    /** Hooded cloak: pointed hood, hunched angular shoulders, falling straight, ragged irregular hem with a few long tatters. */
    private fun robePath(s: Float): Path {
        val p = Path()
        p.moveTo(s * 0.50f, s * 0.035f)
        p.cubicTo(s * 0.44f, s * 0.07f, s * 0.34f, s * 0.14f, s * 0.31f, s * 0.27f)
        p.cubicTo(s * 0.30f, s * 0.33f, s * 0.27f, s * 0.38f, s * 0.20f, s * 0.405f)
        p.lineTo(s * 0.16f, s * 0.43f)
        p.cubicTo(s * 0.11f, s * 0.46f, s * 0.11f, s * 0.54f, s * 0.11f, s * 0.62f)
        p.cubicTo(s * 0.10f, s * 0.76f, s * 0.08f, s * 0.86f, s * 0.06f, s * 0.955f)
        val r = java.util.Random(7)
        var x = 0.06f
        var k = 0
        while (x < 0.94f) {
            val w = 0.035f + r.nextFloat() * 0.06f
            val long = k % 4 == 2
            val tip = if (long) 0.995f else 0.925f + r.nextFloat() * 0.05f
            p.lineTo(s * (x + w * 0.35f), s * (0.90f + r.nextFloat() * 0.03f))
            p.quadTo(s * (x + w * 0.55f), s * (tip - 0.03f), s * (x + w * (0.8f + 0.2f * r.nextFloat())), s * tip)
            x += w; k++
        }
        p.lineTo(s * 0.94f, s * 0.955f)
        p.cubicTo(s * 0.92f, s * 0.86f, s * 0.90f, s * 0.76f, s * 0.89f, s * 0.62f)
        p.cubicTo(s * 0.89f, s * 0.54f, s * 0.89f, s * 0.46f, s * 0.84f, s * 0.43f)
        p.lineTo(s * 0.80f, s * 0.405f)
        p.cubicTo(s * 0.73f, s * 0.38f, s * 0.70f, s * 0.33f, s * 0.69f, s * 0.27f)
        p.cubicTo(s * 0.66f, s * 0.14f, s * 0.56f, s * 0.07f, s * 0.50f, s * 0.035f)
        p.close()
        return p
    }

    /** The Reaper bust for icons that want no scythe (Soul Vault orb) or a differently tinted aura. */
    fun paintReaper(c: Canvas, s: Float, scythe: Boolean, aura: Int) = reaper(c, s, false, scythe, aura)

    private fun reaper(c: Canvas, s: Float, live: Boolean, scythe: Boolean = true, aura: Int = GREEN) {
        Kit.aura(c, s * 0.5f, s * 0.5f, s * 0.5f, aura, 0.30f)
        Kit.aura(c, s * 0.5f, s * 0.34f, s * 0.26f, 0xFF9A1424.toInt(), 0.30f)
        Kit.groundShadow(c, s * 0.5f, s * 0.97f, s * 0.40f, s * 0.03f, 0.7f)
        // ---- scythe blade behind the hood (the staff is drawn in front of the cloak later), glowing green edge
        if (scythe) {
            val blade = Kit.path(s, "M.87,.045 C.72,-.05 .32,-.04 .05,.25 C.33,.12 .66,.10 .87,.17 Z")
            val bp = Kit.fill(); bp.shader = LinearGradient(0f, 0f, 0f, s * 0.2f, intArrayOf(0xFFD2DAE2.toInt(), 0xFF5D6874.toInt(), 0xFF1B2128.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(blade, bp)
            val edge = Kit.path(s, "M.05,.25 C.33,.12 .66,.10 .87,.17")
            val ep = Kit.stroke(Kit.al(GREEN, 0.75f), s * 0.055f); ep.maskFilter = BlurMaskFilter(s * 0.025f, BlurMaskFilter.Blur.NORMAL)
            c.save(); c.clipPath(blade); c.drawPath(edge, ep); c.restore()
            c.drawPath(edge, Kit.stroke(Kit.lift(GREEN, 0.6f), s * 0.009f))
            c.drawPath(blade, Kit.stroke(0xFF06080A.toInt(), s * 0.007f))
            val socket = RectF(s * 0.842f, s * 0.07f, s * 0.89f, s * 0.15f)
            val sp = Kit.fill(); sp.shader = Kit.metal(socket.left, 0f, socket.right, 0f, 0xFF59554F.toInt(), 0.5f, 0.3f)
            c.drawRoundRect(socket, s * 0.012f, s * 0.012f, sp)
        }
        // ---- robe: deep navy-black cloth, ragged hem, folds, cold rim on the lit side, faint green rim on the far side
        val robe = robePath(s)
        val rb = Kit.bounds(robe)
        val rp = Kit.fill()
        rp.shader = LinearGradient(0f, rb.top, 0f, rb.bottom, intArrayOf(0xFF303042.toInt(), 0xFF181822.toInt(), 0xFF09090D.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(robe, rp)
        rp.shader = LinearGradient(rb.left, 0f, rb.right, 0f, intArrayOf(0x44B0C0E0, 0x00000000, 0x88000000.toInt()), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(robe, rp); rp.shader = null
        c.save(); c.clipPath(robe)
        val fold = Kit.stroke(0x66000000, s * 0.04f, Paint.Cap.ROUND); val fl = Kit.stroke(0x2AAFC0E0, s * 0.009f, Paint.Cap.ROUND)
        val p = Path()
        val fx = floatArrayOf(0.13f, 0.22f, 0.31f, 0.40f, 0.60f, 0.69f, 0.78f)
        for ((i, x0) in fx.withIndex()) {
            val x1 = x0 + (x0 - 0.5f) * 0.28f
            p.reset(); p.moveTo(s * (x0 + (0.5f - x0) * 0.35f), s * 0.52f); p.quadTo(s * (x0 + 0.03f * (i % 3 - 1)), s * 0.76f, s * x1, s * 0.97f)
            c.drawPath(p, fold)
            c.save(); c.translate(-s * 0.014f, 0f); c.drawPath(p, fl); c.restore()
        }
        // hood folds converge on the apex
        for (sx in floatArrayOf(-1f, 1f)) {
            p.reset(); p.moveTo(s * 0.5f, s * 0.06f); p.quadTo(s * (0.5f + sx * 0.17f), s * 0.2f, s * (0.5f + sx * 0.24f), s * 0.42f)
            c.drawPath(p, Kit.stroke(0x55000000, s * 0.03f))
        }
        Kit.halo(c, s * 0.5f, s * 0.66f, s * 0.30f, 0xFF000000.toInt(), 0.55f, s * 0.12f)
        Kit.weather(c, robe, s, 0.28f, 0.12f, 0xFF000000.toInt(), 0xFF8090B0.toInt(), 1)
        c.restore()
        Kit.edgeLight(c, robe, -s * 0.008f, -s * 0.008f, Kit.fill(0x995B6C92.toInt()))
        Kit.edgeLight(c, robe, s * 0.011f, s * 0.011f, Kit.fill(Kit.al(GREEN, 0.4f)))
        // ---- hood opening (a gothic arch of darkness) and the skull inside
        val open = Kit.sym(s, "M.5,.095 C.42,.14 .335,.22 .335,.33 C.335,.45 .40,.56 .5,.59")
        val op = Kit.fill()
        op.shader = RadialGradient(s * 0.5f, s * 0.36f, s * 0.26f, intArrayOf(0xFF000000.toInt(), 0xFF030304.toInt(), 0xFF1A1A24.toInt()), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(open, op)
        c.drawPath(open, Kit.stroke(0xFF3A3A4C.toInt(), s * 0.011f))
        c.save(); c.clipPath(open); Kit.innerGlow(c, open, 0xFF000000.toInt(), s * 0.04f, 0.9f)
        val u = s * 0.30f
        skullUpper(c, s * 0.5f - u / 2, s * 0.215f, u, REAPER_SKULL, !live)
        if (!live) skullJaw(c, s * 0.5f - u / 2, s * 0.215f, u, REAPER_SKULL)
        c.restore()
        // ---- chain across the chest with a skull pendant
        val pts = FloatArray(2 * 13)
        for (i in 0..12) { val t = i / 12f; pts[i * 2] = s * (0.25f + 0.50f * t); pts[i * 2 + 1] = s * (0.60f + 0.11f * 4f * t * (1f - t)) }
        Kit.chain(c, pts, s * 0.038f, 0xFF6F6B78.toInt())
        Kit.miniSkull(c, s * 0.5f, s * 0.715f, s * 0.04f, 0xFFCFC6AC.toInt(), RED)
        // ---- staff in front of the cloak, gripped by a bony hand
        if (scythe) {
            val staff = Kit.stroke(0xFF140D08.toInt(), s * 0.03f, Paint.Cap.BUTT)
            c.drawLine(s * 0.795f, s * 0.995f, s * 0.865f, s * 0.04f, staff)
            staff.color = 0xFF6A4B2C.toInt(); staff.strokeWidth = s * 0.012f
            c.save(); c.translate(-s * 0.006f, 0f); c.drawLine(s * 0.795f, s * 0.995f, s * 0.865f, s * 0.04f, staff); c.restore()
            hand(c, s)
        }
        Kit.mist(c, s * 0.02f, s * 0.98f, s * 0.97f, s * 0.10f, GREEN, 0.22f, 11, 7)
    }

    /** Jaw sprite for [Characters]: lower jaw at its rest position in the Reaper's hood. */
    private fun reaperJaw(c: Canvas, s: Float) {
        val u = s * 0.30f
        skullJaw(c, s * 0.5f - u / 2, s * 0.215f, u, REAPER_SKULL)
    }

    /** Sleeve from the shoulder and bony fingers gripping the staff (knuckle joints, shaded undersides). */
    private fun hand(c: Canvas, s: Float) {
        // ragged sleeve cuff, then a fist: four knuckled fingers wrapped over the staff, thumb on the near side
        val cuff = Kit.path(s, "M.70,.585 C.75,.57 .82,.575 .865,.605 L.875,.68 L.835,.70 L.80,.685 L.765,.70 L.73,.675 C.71,.64 .70,.61 .70,.585 Z")
        val cp = Kit.fill(); cp.shader = LinearGradient(s * 0.70f, 0f, s * 0.9f, 0f, 0xFF2C2C3C.toInt(), 0xFF0A0A0F.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(cuff, cp); c.drawPath(cuff, Kit.stroke(0xFF050507.toInt(), s * 0.005f))
        val tone = 0xFFD6CDB2.toInt()
        val under = Kit.stroke(0xFF2E281A.toInt(), s * 0.027f)
        val bone = Kit.stroke(tone, s * 0.022f)
        val hi = Kit.stroke(0xFFF8F2DC.toInt(), s * 0.0065f)
        val joint = Kit.fill(0xFFBDB398.toInt())
        for (i in 0..3) {
            val y = s * (0.683f + i * 0.0255f)
            val x0 = s * 0.775f; val x1 = s * 0.905f
            val path = Path(); path.moveTo(x0, y); path.quadTo(s * 0.84f, y + s * 0.012f, x1, y - s * 0.006f)
            c.drawPath(path, under)
            c.drawPath(path, bone)
            c.save(); c.translate(0f, -s * 0.0065f); c.drawPath(path, hi); c.restore()
            c.drawCircle(s * 0.835f, y + s * 0.007f, s * 0.0115f, joint); c.drawCircle(x1, y - s * 0.006f, s * 0.0105f, joint)
            c.drawCircle(x1 + s * 0.002f, y - s * 0.006f, s * 0.0045f, Kit.fill(0xFF5A5240.toInt()))
        }
        // thumb
        val th = Path(); th.moveTo(s * 0.775f, s * 0.775f); th.quadTo(s * 0.80f, s * 0.735f, s * 0.835f, s * 0.70f)
        c.drawPath(th, under); c.drawPath(th, bone)
    }
}

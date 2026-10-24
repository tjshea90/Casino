package com.tjshea.neonreaper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import com.tjshea.neonreaper.audio.Audio
import com.tjshea.neonreaper.audio.Audio.Sfx
import com.tjshea.neonreaper.engine.Money
import com.tjshea.neonreaper.engine.Orb
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.ReaperConfig.VAULT_COLS
import com.tjshea.neonreaper.engine.ReaperConfig.VAULT_ROWS
import com.tjshea.neonreaper.engine.SoulVault
import com.tjshea.neonreaper.engine.VaultResult
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Soul Vault bonus: a Hold & Spin replay of an already-paid [VaultResult].
 * Everything is driven by a pre-built timeline so a tap can fast-forward safely.
 */
@SuppressLint("ViewConstructor")
class VaultView(context: Context, private val audio: Audio, private val result: VaultResult,
                private val onFinished: (Long) -> Unit) : View(context) {

    private enum class Kind { RESPIN_START, LAND, COUNT, TALLY, GRAND, SUMMARY }
    private class Ev(val time: Double, val kind: Kind, val cell: Int = -1, val orb: Orb? = null,
                     val collected: IntArray? = null, val value: Int = 0, val step: Int = -1)

    private val events = ArrayList<Ev>()
    private var evIndex = 0
    private val revealAt = DoubleArray(SoulVault.CELLS) { -1.0 }   // when a spinning cell stops (per current step)
    private var spinStepStart = -1.0

    // live state
    private val cells = arrayOfNulls<Orb>(SoulVault.CELLS)
    private val cellValue = IntArray(SoulVault.CELLS)
    private val landedAt = DoubleArray(SoulVault.CELLS) { -10.0 }
    private val tallied = BooleanArray(SoulVault.CELLS)
    private var respins = ReaperConfig.VAULT_RESPINS
    private var resetFlash = -10.0
    private var totalMult = 0
    private var grandAt = -1.0
    private var summaryAt = -1.0
    private var collectFrom: IntArray? = null
    private var collectTo = -1
    private var collectAt = -10.0
    private var jackpotFlash = -10.0
    private var jackpotName = ""

    // clocks
    private var started = false
    private var startReal = 0.0
    private var vt = 0.0             // virtual timeline time
    private var lastReal = 0.0
    private var speed = 1.0
    private var outroAt = -1.0
    private var finished = false

    // layout
    private var u = 1f
    private val gridRect = RectF()
    private var cellW = 0f
    private var cellH = 0f
    private val plaques = Array(4) { RectF() }
    private val totalRect = RectF()
    private val respinRect = RectF()
    private var bg: Bitmap? = null
    private var orbBmp: Array<Bitmap>? = null
    private val fx = Random()

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val tmp = RectF()
    private val path = Path()

    private val jpNames = arrayOf("MINI", "MINOR", "MAJOR", "GRAND")
    private val jpMult = intArrayOf(ReaperConfig.MINI, ReaperConfig.MINOR, ReaperConfig.MAJOR, ReaperConfig.GRAND)
    private val jpColor = intArrayOf(Neon.CYAN, Neon.PURPLE, Neon.RED, Neon.GREEN)

    init {
        isClickable = true
        buildTimeline()
    }

    private fun now() = SystemClock.uptimeMillis() / 1000.0

    private fun buildTimeline() {
        var t = 0.6
        for (l in result.initial) { events += Ev(t, Kind.LAND, l.cell, l.orb, null); t += 0.28 }
        t += 0.6
        for ((si, step) in result.steps.withIndex()) {
            events += Ev(t, Kind.RESPIN_START, step = si)
            val reveal = t + 0.9
            var extra = 0.0
            val byCol = step.landings.groupBy { it.cell % VAULT_COLS }
            for (col in 0 until VAULT_COLS) {
                val ct = reveal + col * 0.17 + extra
                for (l in byCol[col].orEmpty().sortedBy { it.cell }) {
                    events += Ev(ct, Kind.LAND, l.cell, l.orb, l.collected, step = si)
                    if (l.orb.kind == Orb.REAPER) extra += 1.3
                    else if (l.orb.kind != Orb.SOUL) extra += 0.7
                }
            }
            t = reveal + VAULT_COLS * 0.17 + extra + 0.25
            events += Ev(t, Kind.COUNT, value = step.respinsAfter, step = si)
            t += if (step.landings.isNotEmpty()) 0.7 else 0.45
        }
        t += 0.6
        // tally in reading order
        for (c in 0 until SoulVault.CELLS) {
            val o = result.cells[c] ?: continue
            events += Ev(t, Kind.TALLY, c)
            t += if (o.kind == Orb.SOUL) 0.16 else 0.5
        }
        if (result.grand) { t += 0.4; events += Ev(t, Kind.GRAND); t += 3.0 }
        events += Ev(t + 0.6, Kind.SUMMARY)
        events.sortBy { it.time }
    }

    // ================================================================== layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        val W = w.toFloat(); val H = h.toFloat()
        u = min(W / 1280f, H / 800f)
        val top = H * 0.2f; val bottom = H * 0.84f
        cellH = (bottom - top) / VAULT_ROWS
        cellW = min(cellH * 1.25f, W * 0.62f / VAULT_COLS)
        val gw = cellW * VAULT_COLS
        gridRect.set((W - gw) / 2, top, (W + gw) / 2, top + cellH * VAULT_ROWS)
        val pw = min(W * 0.17f, 230 * u); val pgap = 14 * u
        val px0 = W / 2 - (pw * 4 + pgap * 3) / 2
        for (i in 0 until 4) plaques[i].set(px0 + i * (pw + pgap), H * 0.035f, px0 + i * (pw + pgap) + pw, H * 0.155f)
        totalRect.set(W / 2 - 260 * u, H * 0.865f, W / 2 + 260 * u, H * 0.975f)
        respinRect.set(gridRect.left - 200 * u, gridRect.centerY() - 120 * u, gridRect.left - 24 * u, gridRect.centerY() + 120 * u)
        bg?.recycle()
        bg = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { paintCrypt(Canvas(it), W, H) }
        orbBmp?.forEach { it.recycle() }
        val os = (min(cellW, cellH)).toInt()
        orbBmp = arrayOf(Art.orbBitmap(os, Neon.GREEN), Art.orbBitmap(os, Neon.CYAN), Art.orbBitmap(os, Neon.PURPLE),
            Art.orbBitmap(os, Neon.RED), Art.orbBitmap(os, 0xFF101010.toInt()))
    }

    private fun paintCrypt(c: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF0B0E0C.toInt(), 0xFF141A15.toInt(), 0xFF050605.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // stone blocks
        val r = Random(9)
        val bh = 46 * u; val bw = 110 * u
        var y = 0f; var row = 0
        while (y < h) {
            var x = if (row % 2 == 0) 0f else -bw / 2
            while (x < w) {
                val shade = 18 + r.nextInt(14)
                p.color = Color.rgb(shade, shade + 4, shade)
                c.drawRect(x + 2 * u, y + 2 * u, x + bw - 2 * u, y + bh - 2 * u, p)
                x += bw
            }
            y += bh; row++
        }
        // arches
        p.color = 0xFF020302.toInt()
        for (ax in floatArrayOf(w * 0.1f, w * 0.9f)) {
            val a = Path().apply {
                moveTo(ax - 110 * u, h); lineTo(ax - 110 * u, h * 0.45f)
                cubicTo(ax - 110 * u, h * 0.2f, ax + 110 * u, h * 0.2f, ax + 110 * u, h * 0.45f)
                lineTo(ax + 110 * u, h); close()
            }
            c.drawPath(a, p)
        }
        // hooded silhouettes in the arches
        Art.hooded(c, w * 0.1f - 80 * u, h * 0.42f, 160 * u, 210 * u, Neon.RED_DIM)
        Art.hooded(c, w * 0.9f - 80 * u, h * 0.42f, 160 * u, 210 * u, Neon.RED_DIM)
        // vignette
        p.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.65f, intArrayOf(0x00000000, 0xEE000000.toInt()), floatArrayOf(0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // grid niches
        for (i in 0 until SoulVault.CELLS) {
            val cx = gridRect.left + (i % VAULT_COLS) * cellW; val cy = gridRect.top + (i / VAULT_COLS) * cellH
            tmp.set(cx + 5 * u, cy + 5 * u, cx + cellW - 5 * u, cy + cellH - 5 * u)
            p.shader = RadialGradient(tmp.centerX(), tmp.centerY(), cellW * 0.6f, 0xFF0E160C.toInt(), 0xFF020302.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(tmp, 14 * u, 14 * u, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * u; p.color = 0xFF27401F.toInt()
            c.drawRoundRect(tmp, 14 * u, 14 * u, p); p.style = Paint.Style.FILL
        }
        path.reset(); tmp.set(gridRect); tmp.inset(-8 * u, -8 * u); path.addRoundRect(tmp, 18 * u, 18 * u, Path.Direction.CW)
        Art.neonStroke(c, path, Neon.GREEN, 2.5f * u)
    }

    // ================================================================== timeline
    private fun process() {
        while (evIndex < events.size && events[evIndex].time <= vt) {
            val e = events[evIndex++]
            when (e.kind) {
                Kind.RESPIN_START -> {
                    spinStepStart = e.time
                    for (c in 0 until SoulVault.CELLS) revealAt[c] = if (cells[c] == null) e.time + 0.9 + (c % VAULT_COLS) * 0.17 else -1.0
                    audio.play(Sfx.SPIN, 0.4f, 0.8f)
                }
                Kind.LAND -> {
                    val o = e.orb!!
                    cells[e.cell] = o
                    landedAt[e.cell] = vt
                    revealAt[e.cell] = -1.0
                    when (o.kind) {
                        Orb.SOUL -> { cellValue[e.cell] = o.value; audio.play(Sfx.ORB, 0.8f, 0.9f + fx.nextFloat() * 0.3f) }
                        Orb.REAPER -> {
                            cellValue[e.cell] = 0
                            collectFrom = e.collected; collectTo = e.cell; collectAt = vt
                            audio.play(Sfx.GROWL); audio.play(Sfx.SLASH)
                        }
                        else -> {
                            cellValue[e.cell] = o.value
                            jackpotName = jpNames[o.kind - 1]; jackpotFlash = vt
                            audio.play(Sfx.JACKPOT)
                        }
                    }
                }
                Kind.COUNT -> {
                    if (result.steps[e.step].landings.isNotEmpty()) { resetFlash = vt; audio.play(Sfx.HEARTBEAT) }
                    respins = e.value
                    for (c in 0 until SoulVault.CELLS) revealAt[c] = -1.0
                    spinStepStart = -1.0
                }
                Kind.TALLY -> {
                    tallied[e.cell] = true
                    totalMult += result.cells[e.cell]!!.value
                    audio.play(if (result.cells[e.cell]!!.kind == Orb.SOUL) Sfx.TICK else Sfx.JACKPOT, 0.6f, 1.2f)
                }
                Kind.GRAND -> { grandAt = vt; totalMult += ReaperConfig.GRAND; audio.play(Sfx.BIG_WIN); audio.play(Sfx.JACKPOT) }
                Kind.SUMMARY -> { summaryAt = vt; audio.play(Sfx.WIN_MED); if (result.payTokens >= result.bet * 50L) audio.play(Sfx.GROWL) }
            }
        }
        // reaper collector: value grows while beams fly
        if (collectTo >= 0) {
            val q = ((vt - collectAt) / 1.0).coerceIn(0.0, 1.0)
            val target = cells[collectTo]!!.value
            cellValue[collectTo] = (target * q).toInt()
            if (q >= 1.0) { cellValue[collectTo] = target; collectTo = -1; collectFrom = null }
        }
    }

    // ================================================================== input
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        audio.play(Sfx.CLICK, 0.5f)
        when {
            !started -> { started = true; startReal = now(); lastReal = startReal }
            summaryAt >= 0 && outroAt < 0 -> if (vt - summaryAt > 0.8) outroAt = now()
            else -> speed = if (speed > 1.0) 1.0 else 3.0
        }
        return true
    }

    // ================================================================== draw
    override fun onDraw(c: Canvas) {
        val b = bg ?: return
        val real = now()
        if (introStart == 0.0) introStart = real
        if (!started && real - introStart > 4.0) { started = true; startReal = real; lastReal = real }
        if (started) { vt += (real - lastReal) * speed; lastReal = real; process() }
        c.drawBitmap(b, 0f, 0f, null)
        drawTorches(c, real)
        drawPlaques(c, real)
        drawRespins(c, real)
        drawGrid(c, real)
        drawBeams(c)
        drawTotal(c)
        if (vt - jackpotFlash < 1.4) drawJackpotFlash(c)
        if (grandAt >= 0 && vt - grandAt < 3.0) drawGrand(c)
        if (!started) drawIntro(c, real)
        if (summaryAt >= 0) drawSummary(c, real)
        // fade in from white (end of the trigger flash) and out to black
        val fin = real - introStart
        if (fin < 0.8) { pFill.color = Color.argb(((1 - fin / 0.8) * 255).toInt(), 255, 255, 255); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill) }
        if (outroAt > 0) {
            val q = ((real - outroAt) / 0.9).coerceIn(0.0, 1.0)
            pFill.color = Color.argb((q * 255).toInt(), 0, 0, 0); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
            if (q >= 1.0 && !finished) { finished = true; post { onFinished(result.payTokens) }; return }
        }
        postInvalidateOnAnimation()
    }

    private var introStart = 0.0

    private fun drawTorches(c: Canvas, t: Double) {
        for ((i, x) in floatArrayOf(width * 0.22f, width * 0.78f).withIndex()) {
            val y = height * 0.33f
            pFill.color = 0xFF1A1A1A.toInt()
            c.drawRect(x - 6 * u, y, x + 6 * u, y + 60 * u, pFill)
            pFill.shader = RadialGradient(x, y, 160 * u, intArrayOf(0x4476FF03, 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, 160 * u, pFill); pFill.shader = null
            Art.flame(c, x, y, 26 * u, Neon.GREEN, sin(t * 10 + i).toFloat())
        }
    }

    private fun tokens(mult: Int): Long = mult.toLong() * result.bet

    private fun drawPlaques(c: Canvas, t: Double) {
        for (i in 0 until 4) {
            val r = plaques[i]
            pFill.color = 0xE0050505.toInt(); c.drawRoundRect(r, 12 * u, 12 * u, pFill)
            val flash = (i < 3 && jackpotName == jpNames[i] && vt - jackpotFlash < 1.4) || (i == 3 && grandAt >= 0)
            path.reset(); path.addRoundRect(r, 12 * u, 12 * u, Path.Direction.CW)
            Art.neonStroke(c, path, jpColor[i], (if (flash && (t * 8).toInt() % 2 == 0) 4f else 2f) * u)
            pText.typeface = serif; pText.textSize = r.height() * 0.3f; pText.color = jpColor[i]
            c.drawText(jpNames[i], r.centerX(), r.top + r.height() * 0.38f, pText)
            val v = tokens(jpMult[i])
            val capped = v > ReaperConfig.MAX_WIN_TOKENS
            pText.typeface = cond; pText.textSize = r.height() * 0.28f; pText.color = Color.WHITE
            c.drawText(if (capped) "${Money.fmt(ReaperConfig.MAX_WIN_TOKENS)} MAX" else Money.fmt(v), r.centerX(), r.top + r.height() * 0.78f, pText)
        }
    }

    private fun drawRespins(c: Canvas, t: Double) {
        val r = respinRect
        pText.typeface = cond; pText.textSize = 26 * u; pText.color = 0xFF9CCC65.toInt()
        c.drawText("RESPINS", r.centerX(), r.top + 20 * u, pText)
        val flash = vt - resetFlash < 0.8
        for (i in 0 until ReaperConfig.VAULT_RESPINS) {
            val cy = r.top + 60 * u + i * 62 * u
            val on = i < respins
            val s = if (flash && on) (1 + 0.25 * (1 - (vt - resetFlash) / 0.8)).toFloat() else 1f
            c.save(); c.scale(s, s, r.centerX(), cy)
            Art.skull(c, r.centerX(), cy, 52 * u, if (on) Neon.RED else 0xFF3A2A2A.toInt())
            c.restore()
        }
        pText.typeface = cond; pText.textSize = 20 * u; pText.color = 0xFF8A9A80.toInt()
        c.drawText("BONUS BET ${result.bet}", r.centerX(), r.bottom + 30 * u, pText)
        if (speed > 1.0 && summaryAt < 0) { pText.color = Neon.ORANGE; c.drawText("FAST ▸▸", r.centerX(), r.bottom + 56 * u, pText) }
    }

    private fun cellRect(i: Int, out: RectF) {
        val x = gridRect.left + (i % VAULT_COLS) * cellW; val y = gridRect.top + (i / VAULT_COLS) * cellH
        out.set(x, y, x + cellW, y + cellH)
    }

    private fun drawGrid(c: Canvas, t: Double) {
        val ob = orbBmp ?: return
        for (i in 0 until SoulVault.CELLS) {
            cellRect(i, tmp)
            val o = cells[i]
            if (o == null) {
                // spinning niche: flickering ghost orbs
                if (revealAt[i] > 0 && vt < revealAt[i]) {
                    val k = ((vt * 14 + i * 3).toInt()) % 3
                    pBmp.alpha = 70
                    val s = min(cellW, cellH) * 0.8f
                    c.drawBitmap(ob[if (k == 0) 0 else if (k == 1) 4 else 2], null,
                        RectF(tmp.centerX() - s / 2, tmp.centerY() - s / 2 + ((vt * 9 % 1.0) * 20 * u).toFloat(), tmp.centerX() + s / 2, tmp.centerY() + s / 2), pBmp)
                    pBmp.alpha = 255
                }
                continue
            }
            val age = (vt - landedAt[i]).toFloat()
            val pop = if (age < 0.25f) 0.4f + age / 0.25f * 0.75f else if (age < 0.4f) 1.15f - (age - 0.25f) / 0.15f * 0.15f else 1f
            val idx = when (o.kind) { Orb.SOUL -> 0; Orb.MINI -> 1; Orb.MINOR -> 2; Orb.MAJOR -> 3; else -> 4 }
            val s = min(cellW, cellH) * pop * (if (tallied[i]) 0.92f else 1f)
            c.drawBitmap(ob[idx], null, RectF(tmp.centerX() - s / 2, tmp.centerY() - s / 2, tmp.centerX() + s / 2, tmp.centerY() + s / 2), pBmp)
            if (age < 0.35f) { pFill.color = Color.argb(((0.35f - age) / 0.35f * 200).toInt(), 200, 255, 180); c.drawRoundRect(tmp, 14 * u, 14 * u, pFill) }
            if (o.kind == Orb.REAPER) {
                val rs = s * 0.75f
                Art.reaper(c, tmp.centerX() - rs / 2, tmp.centerY() - rs * 0.62f, rs, rs * 1.1f, Neon.GREEN, withScythe = false)
            }
            val label = when (o.kind) { Orb.MINI -> "MINI"; Orb.MINOR -> "MINOR"; Orb.MAJOR -> "MAJOR"; else -> null }
            pText.typeface = cond
            if (label != null) {
                pText.textSize = cellH * 0.17f; pText.color = Color.WHITE
                pText.setShadowLayer(6 * u, 0f, 0f, Color.BLACK)
                c.drawText(label, tmp.centerX(), tmp.centerY() - cellH * 0.04f, pText)
            }
            pText.textSize = cellH * 0.2f; pText.color = Color.WHITE
            pText.setShadowLayer(8 * u, 0f, 0f, Color.BLACK)
            val v = tokens(cellValue[i])
            if (o.kind != Orb.REAPER || cellValue[i] > 0)
                c.drawText(Money.fmt(v), tmp.centerX(), tmp.centerY() + cellH * (if (label != null) 0.16f else if (o.kind == Orb.REAPER) 0.34f else 0.08f), pText)
            pText.clearShadowLayer()
            if (tallied[i]) { pFill.color = 0x5576FF03; c.drawRoundRect(tmp, 14 * u, 14 * u, pFill) }
        }
    }

    private fun drawBeams(c: Canvas) {
        val from = collectFrom ?: return
        val to = collectTo; if (to < 0) return
        val q = ((vt - collectAt) / 1.0).toFloat().coerceIn(0f, 1f)
        cellRect(to, tmp); val tx = tmp.centerX(); val ty = tmp.centerY()
        for (f in from) {
            cellRect(f, tmp)
            val x = tmp.centerX() + (tx - tmp.centerX()) * q; val y = tmp.centerY() + (ty - tmp.centerY()) * q
            pStroke.color = Neon.GREEN; pStroke.alpha = 110; pStroke.strokeWidth = 10 * u
            c.drawLine(tmp.centerX(), tmp.centerY(), x, y, pStroke)
            pStroke.alpha = 255; pStroke.strokeWidth = 3 * u; pStroke.color = Color.WHITE
            c.drawLine(tmp.centerX(), tmp.centerY(), x, y, pStroke)
            pFill.color = Neon.GREEN; c.drawCircle(x, y, 9 * u, pFill)
        }
    }

    private fun drawTotal(c: Canvas) {
        val r = totalRect
        pFill.color = 0xF0000000.toInt(); c.drawRoundRect(r, 16 * u, 16 * u, pFill)
        path.reset(); path.addRoundRect(r, 16 * u, 16 * u, Path.Direction.CW)
        Art.neonStroke(c, path, Neon.GREEN, 2f * u)
        pText.typeface = cond; pText.textSize = r.height() * 0.24f; pText.color = 0xFF9CCC65.toInt()
        c.drawText("SOULS HARVESTED", r.centerX(), r.top + r.height() * 0.3f, pText)
        pText.typeface = serif; pText.textSize = r.height() * 0.48f; pText.color = Color.WHITE
        pText.setShadowLayer(12 * u, 0f, 0f, Neon.GREEN)
        c.drawText("${Money.fmt(tokens(totalMult))} TOKENS", r.centerX(), r.bottom - r.height() * 0.14f, pText)
        pText.clearShadowLayer()
    }

    private fun drawJackpotFlash(c: Canvas) {
        val e = (vt - jackpotFlash).toFloat()
        val a = (1 - e / 1.4f).coerceIn(0f, 1f)
        pText.typeface = serif; pText.textSize = height * 0.12f * (1 + 0.2f * (1 - a))
        val col = jpColor[jpNames.indexOf(jackpotName).coerceAtLeast(0)]
        pText.color = col; pText.alpha = (255 * a).toInt()
        pText.setShadowLayer(30 * u, 0f, 0f, col)
        c.drawText("$jackpotName!", width / 2f, gridRect.centerY(), pText)
        pText.clearShadowLayer(); pText.alpha = 255
    }

    private fun drawGrand(c: Canvas) {
        val e = (vt - grandAt).toFloat()
        pFill.color = Color.argb((160 * min(1f, e * 3)).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        val s = 1 + 0.1f * sin(e * 8)
        c.save(); c.scale(s, s, width / 2f, height / 2f)
        pText.typeface = serif; pText.textSize = height * 0.18f; pText.color = Neon.GREEN
        pText.setShadowLayer(40 * u, 0f, 0f, Neon.GREEN)
        c.drawText("GRAND", width / 2f, height * 0.52f, pText)
        pText.clearShadowLayer(); c.restore()
        pText.typeface = cond; pText.textSize = height * 0.05f; pText.color = Color.WHITE
        c.drawText("THE VAULT IS FULL", width / 2f, height * 0.62f, pText)
    }

    private fun drawIntro(c: Canvas, t: Double) {
        val e = (t - introStart).toFloat()
        pFill.color = 0xD0000000.toInt(); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        val s = min(1f, e * 1.5f)
        c.save(); c.scale(s, s, width / 2f, height * 0.4f)
        pText.typeface = serif; pText.textSize = height * 0.11f; pText.color = Neon.GREEN
        pText.setShadowLayer(30 * u, 0f, 0f, Neon.GREEN)
        c.drawText("THE SOUL VAULT", width / 2f, height * 0.36f, pText)
        pText.clearShadowLayer(); c.restore()
        pText.typeface = cond; pText.textSize = height * 0.042f; pText.color = Color.WHITE
        val lines = arrayOf(
            "Soul orbs stick in the vault. Every new orb resets your respins to 3.",
            "Jackpot orbs: MINI ${ReaperConfig.MINI}×  •  MINOR ${ReaperConfig.MINOR}×  •  MAJOR ${ReaperConfig.MAJOR}×  •  fill all 20 for the GRAND (${ReaperConfig.GRAND}×)",
            "The Reaper orb harvests every soul on the board.",
            "Bonus bet: ${result.bet} tokens (your average stake while collecting relics)")
        for ((i, l) in lines.withIndex()) c.drawText(l, width / 2f, height * (0.5f + i * 0.065f), pText)
        pText.color = if ((t * 2).toInt() % 2 == 0) Neon.RED else 0xFF7F0019.toInt()
        pText.textSize = height * 0.045f
        c.drawText("TAP TO BEGIN", width / 2f, height * 0.82f, pText)
    }

    private fun drawSummary(c: Canvas, t: Double) {
        val e = (vt - summaryAt).toFloat()
        pFill.color = Color.argb((210 * min(1f, e * 2)).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        val s = min(1f, e * 2f)
        c.save(); c.scale(s, s, width / 2f, height / 2f)
        pText.typeface = serif; pText.textSize = height * 0.08f; pText.color = Neon.RED
        pText.setShadowLayer(20 * u, 0f, 0f, Neon.RED)
        c.drawText("SOUL VAULT COMPLETE", width / 2f, height * 0.3f, pText)
        pText.textSize = height * 0.15f; pText.color = Neon.GREEN; pText.setShadowLayer(34 * u, 0f, 0f, Neon.GREEN)
        c.drawText(Money.fmt(result.payTokens), width / 2f, height * 0.5f, pText)
        pText.clearShadowLayer()
        pText.typeface = cond; pText.textSize = height * 0.045f; pText.color = Color.WHITE
        c.drawText("TOKENS WON  •  ${Money.describe(result.payTokens)}", width / 2f, height * 0.58f, pText)
        pText.textSize = height * 0.034f; pText.color = 0xFFB0B0B0.toInt()
        val note = when {
            result.capped -> "Harvest of ${Money.fmt(result.rawTokens)} tokens capped at the 25-book maximum win"
            result.rawTokens != result.payTokens -> "${Money.fmt(result.rawTokens)} tokens rounded down to whole macks"
            else -> "${result.totalMult}× your bonus bet of ${result.bet}"
        }
        c.drawText(note, width / 2f, height * 0.65f, pText)
        c.drawText("Added to your credit", width / 2f, height * 0.7f, pText)
        if (e > 0.8f) {
            pText.color = if ((t * 2).toInt() % 2 == 0) Neon.GREEN else Neon.GREEN_DIM; pText.textSize = height * 0.045f
            c.drawText("TAP TO RETURN TO THE GRAVEYARD", width / 2f, height * 0.82f, pText)
        }
        c.restore()
    }

    fun release() { bg?.recycle(); orbBmp?.forEach { it.recycle() } }
}

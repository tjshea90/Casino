package com.tjshea.neonreaper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.tjshea.neonreaper.audio.Audio
import com.tjshea.neonreaper.audio.Audio.Sfx
import com.tjshea.neonreaper.data.SpinPlay
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.Money
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.ReaperConfig.REELS
import com.tjshea.neonreaper.engine.ReaperConfig.ROWS
import com.tjshea.neonreaper.engine.ReaperConfig.WILD
import com.tjshea.neonreaper.engine.ReaperEngine
import com.tjshea.neonreaper.engine.SpinOutcome
import com.tjshea.neonreaper.engine.VaultResult
import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** The base game cabinet: graveyard scene, reels, characters, meters and celebrations. */
@SuppressLint("ViewConstructor")
class ReaperView(context: Context, private val host: Host) : View(context) {

    interface Host {
        val wallet: Wallet
        val audio: Audio
        fun openDeposit()
        fun openWithdraw()
        fun openInfo()
        fun openStats()
        fun openAutoPicker(onPicked: (Int) -> Unit)
        fun openVault(result: VaultResult)
    }

    private enum class Phase { IDLE, SPINNING, MINI_ACTION, PRESENT, SHOWCASE, BIG_WIN, VAULT_TRIGGER, IN_VAULT }

    private val wallet get() = host.wallet
    private val audio get() = host.audio
    private val fx = Random()

    // ------------------------------------------------------------------ game state
    private var phase = Phase.IDLE
    private var grid: Array<IntArray> = Array(REELS) { IntArray(ROWS) }
    private var play: SpinPlay? = null
    private val outcome: SpinOutcome? get() = play?.outcome
    private var pendingWin = 0L
    private var pendingVault = 0L
    private var rollTarget = 0L
    private var rollStart = 0.0
    private var rollDur = 0.0
    private var lastTick = 0.0
    private var presentStart = 0.0
    private var phaseStart = 0.0
    private var nextAt = 0.0
    private var autoLeft = 0
    private var tier = 0
    private var showcaseSym = -1
    private var message: String? = null
    private var messageUntil = 0.0
    private var creditFlashUntil = 0.0
    private var vaultWinShown = 0L

    // reels
    private val tape = arrayOfNulls<IntArray>(REELS)
    private val reelS = DoubleArray(REELS)
    private val reelV = DoubleArray(REELS)
    private val liftEnd = DoubleArray(REELS)
    private val runEnd = DoubleArray(REELS)
    private val landed = BooleanArray(REELS)
    private val done = BooleanArray(REELS)
    private val isWildReel = BooleanArray(REELS)
    private val landTime = 0.28
    private val landDist = 2.0

    // mini features (Raven / Pumpkin / Skull fly out and act after the reels stop)
    private var miniType = ReaperConfig.MINI_NONE
    private var miniStart = -1.0
    private var actionStart = 0.0
    private var actionEvents = 0
    private val coinShown = BooleanArray(REELS * ROWS)
    private val cellFlash = DoubleArray(REELS * ROWS)
    private var multStampAt = -1.0

    // win presentation lock: a win can't be skipped until its amount has been shown
    private var winLockUntil = 0.0
    private var countEndPlayed = true

    // hold-to-spin
    private var spinHeldSince = -1.0
    private var holdMode = false

    // Reaper's Touch
    private var featureStart = -1.0
    private var slashAt = -1.0
    private var slashPlayed = false

    // relic flight
    private var relicFly = -1
    private var relicStart = 0.0
    private val relicDur = 1.5

    // ambient
    private var nextLightning = 0.0
    private var lightningAt = -10.0
    private var thunderAt = -1.0
    private val bolt = Path()
    private var nextBats = 0.0
    private var batsStart = -10.0
    private var batsY = 0f
    private var blinkUntil = 0.0
    private var nextBlink = 0.0
    private var shakeUntil = 0.0

    // ------------------------------------------------------------------ layout
    private var u = 1f
    private var cell = 0f
    private val reelRect = RectF()
    private val frameRect = RectF()
    private val titleRect = RectF()
    private val relicRect = RectF()
    private val panelRect = RectF()
    private val creditRect = RectF()
    private val stakeRect = RectF()
    private val winRect = RectF()
    private val leftChar = RectF()
    private val rightChar = RectF()
    private val relicSlots = Array(5) { RectF() }
    /** The OpenGL 3D world behind this HUD (null = classic 2D drawing). */
    var gl3d: com.tjshea.neonreaper.gl.SceneSink? = null
        set(v) { field = v; if (width > 0) layoutAll(width.toFloat(), height.toFloat()) }
    private var lay3d: com.tjshea.neonreaper.gl.Layout3D? = null
    private var hudChrome: Bitmap? = null
    private val idleStops = IntArray(REELS) { fx.nextInt(ReaperConfig.STRIPS[it].size) }

    private var chars: Characters? = null       // reel symbols (cell size)
    private var bigChars: Characters? = null    // side characters / celebrations
    private var blurred: Array<Bitmap>? = null
    private var bg: Bitmap? = null
    private var fog: Bitmap? = null
    private var cloud: Bitmap? = null

    // 3D reel drum: rows sit on a cylinder, each row STEP radians apart
    private val step = 0.48
    private var drumR = 0f

    // idle character events
    private var eventReel = -1
    private var eventRow = -1
    private var eventUntil = 0.0
    private var nextEvent = 0.0
    private var sideLaughUntil = 0.0
    private var nextSideLaugh = 0.0
    private var relicLit: Array<Bitmap>? = null
    private var relicDark: Array<Bitmap>? = null
    private val buttons = ArrayList<Btn>()
    private var pressed: Btn? = null

    private class Btn(val id: String, val label: String, val rect: RectF, val round: Boolean = false) { var enabled = true }

    // ------------------------------------------------------------------ paints
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val path = Path()
    private val tmp = RectF()
    private val mtx = Matrix()
    private var shadeTop: Shader? = null
    private var shadeBot: Shader? = null
    private var gloss: Shader? = null
    private var sepShader: Shader? = null

    private val lineColors = intArrayOf(Neon.GREEN, Neon.RED, Neon.PURPLE, Neon.CYAN, Neon.ORANGE)

    // particles: souls (green wisps) and embers (red)
    private val maxP = 140
    private val px = FloatArray(maxP); private val py = FloatArray(maxP)
    private val pvx = FloatArray(maxP); private val pvy = FloatArray(maxP)
    private val plife = FloatArray(maxP); private val pmax = FloatArray(maxP); private val pkind = IntArray(maxP)
    private var particleCount = 0
    private var soulRate = 0f
    private var soulAcc = 0f
    private var lastFrame = 0.0

    init {
        isClickable = true
        grid = ReaperEngine.gridFor(idleStops)
    }

    private fun now() = SystemClock.uptimeMillis() / 1000.0

    // ================================================================== layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) layoutAll(w.toFloat(), h.toFloat())
    }

    private fun layoutAll(w: Float, h: Float) {
        val land = w >= h
        u = if (land) min(w / 1280f, h / 800f) else min(w / 800f, h / 1280f)
        val m = 10 * u
        titleRect.set(w * 0.25f, m * 0.5f, w * 0.75f, h * (if (land) 0.11f else 0.07f))
        relicRect.set(w / 2 - 300 * u, titleRect.bottom + 2 * u, w / 2 + 300 * u, titleRect.bottom + 68 * u)
        val panelH = if (land) h * 0.19f else h * 0.24f
        panelRect.set(m, h - panelH, w - m, h - m * 0.5f)
        val sideW = if (land) w * 0.17f else 0f
        val availH = panelRect.top - relicRect.bottom - m * 3
        val availW = w - 2 * sideW - 2 * m
        val drumH = (2 * sin(1.5 * step) / step).toFloat()     // window height in cells
        cell = floor(min(availH / drumH, availW / REELS))
        drumR = (cell / step).toFloat()
        val rw = cell * REELS; val rh = cell * drumH
        val top = relicRect.bottom + m * 1.5f + (availH - rh) / 2
        reelRect.set((w - rw) / 2, top, (w + rw) / 2, top + rh)
        frameRect.set(reelRect); frameRect.inset(-12 * u, -12 * u)

        // characters on the sides (landscape) or peeking above the reels (portrait)
        if (land) {
            val cw = min(sideW * 1.05f, reelRect.left - 4 * u); val ch = cw * 1.32f
            leftChar.set(reelRect.left - cw - 6 * u, reelRect.bottom - ch, reelRect.left - 6 * u, reelRect.bottom)
            rightChar.set(reelRect.right + 6 * u, reelRect.bottom - ch, reelRect.right + 6 * u + cw, reelRect.bottom)
        } else {
            val cw = w * 0.22f; val ch = cw * 1.32f
            leftChar.set(m, relicRect.bottom - ch * 0.2f, m + cw, relicRect.bottom + ch * 0.8f)
            rightChar.set(w - m - cw, relicRect.bottom - ch * 0.2f, w - m, relicRect.bottom + ch * 0.8f)
        }
        val slot = relicRect.height() * 0.86f
        val gap = 10 * u
        val totalW = slot * 5 + gap * 4
        for (i in 0 until 5) {
            val x = relicRect.centerX() - totalW / 2 + i * (slot + gap)
            relicSlots[i].set(x, relicRect.centerY() - slot / 2, x + slot, relicRect.centerY() + slot / 2)
        }

        // bottom panel
        buttons.clear()
        val ph = panelRect.height()
        val spinR = min(ph * 0.82f, w * 0.1f)
        val spinCx = panelRect.right - spinR - 10 * u
        val spinCy = panelRect.bottom - spinR - 6 * u
        buttons += Btn("spin", "SPIN", RectF(spinCx - spinR, spinCy - spinR, spinCx + spinR, spinCy + spinR), round = true)
        val autoW = ph * 0.6f
        val auto = RectF(spinCx - spinR - autoW - 14 * u, panelRect.centerY() - ph * 0.2f, spinCx - spinR - 14 * u, panelRect.centerY() + ph * 0.2f)
        buttons += Btn("auto", "AUTO", auto)
        val mTop = panelRect.top + ph * 0.1f; val mBot = panelRect.bottom - ph * 0.1f
        val avail = auto.left - 14 * u - (panelRect.left + 12 * u)
        val cw = (avail - 2 * 12 * u) / 3.2f
        creditRect.set(panelRect.left + 12 * u, mTop, panelRect.left + 12 * u + cw * 1.2f, mBot)
        stakeRect.set(creditRect.right + 12 * u, mTop, creditRect.right + 12 * u + cw, mBot)
        winRect.set(stakeRect.right + 12 * u, mTop, auto.left - 14 * u, mBot)
        buttons += Btn("stake", "STAKE", stakeRect)

        // top buttons
        val bh = titleRect.height() * 0.62f
        val bw = if (land) (w * 0.25f - 3 * m) / 2 else (w * 0.25f - 3 * m) / 2
        val by = titleRect.centerY() - bh / 2
        buttons += Btn("deposit", "DEPOSIT", RectF(m, by, m + bw, by + bh))
        buttons += Btn("withdraw", "CASH OUT", RectF(2 * m + bw, by, 2 * m + 2 * bw, by + bh))
        val rw4 = (w * 0.25f - 5 * m) / 4
        val ids = listOf("info" to "INFO", "stats" to "STATS", "sound" to "SOUND", "turbo" to "TURBO")
        for ((i, pair) in ids.withIndex()) {
            val x = w * 0.75f + m + i * (rw4 + m)
            buttons += Btn(pair.first, pair.second, RectF(x, by, x + rw4, by + bh))
        }

        // bitmaps
        recycleBitmaps()
        val cs = cell.toInt().coerceAtLeast(16)
        chars = Characters(cs, cs)
        bigChars = Characters(leftChar.width().toInt().coerceAtLeast(8), leftChar.height().toInt().coerceAtLeast(8), plates = false)
        blurred = Array(ReaperConfig.SYMBOL_COUNT) { motionBlur(chars!!.body(it)) }
        bg = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888).also { paintBackground(Canvas(it), w, h) }
        val g = gl3d
        if (g != null) {
            g.setReelWindow(reelRect.left, reelRect.top, reelRect.right, reelRect.bottom)
            lay3d = com.tjshea.neonreaper.gl.Layout3D(w, h, reelRect.left, reelRect.top, reelRect.right, reelRect.bottom)
            hudChrome = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888).also { paintBackground(Canvas(it), w, h, chromeOnly = true) }
        } else { lay3d = null; hudChrome = null }
        fog = makeFog((w * 0.6f).toInt().coerceAtLeast(64), (h * 0.18f).toInt().coerceAtLeast(16))
        cloud = makeCloud((h * 0.5f).toInt().coerceAtLeast(32), (h * 0.16f).toInt().coerceAtLeast(16))
        val rs = relicSlots[0].width().toInt().coerceAtLeast(8)
        relicLit = Array(5) { Art.relic(it, rs, true) }
        relicDark = Array(5) { Art.relic(it, rs, false) }
        shadeTop = LinearGradient(0f, reelRect.top, 0f, reelRect.top + cell * 0.75f, 0xCC000000.toInt(), 0, Shader.TileMode.CLAMP)
        shadeBot = LinearGradient(0f, reelRect.bottom - cell * 0.75f, 0f, reelRect.bottom, 0, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
        gloss = LinearGradient(0f, reelRect.top + cell * 0.2f, 0f, reelRect.top + cell * 0.9f, intArrayOf(0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        sepShader = LinearGradient(-3 * u, 0f, 3 * u, 0f, intArrayOf(0xFF1A1F18.toInt(), 0xFF6A7A5A.toInt(), 0xFF0A0C08.toInt()), null, Shader.TileMode.CLAMP)
    }

    private fun motionBlur(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in 0 until 6) { p.alpha = 80; c.drawBitmap(src, 0f, (i - 3) * src.height * 0.05f, p) }
        return out
    }

    private fun makeCloud(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG); val r = Random(17)
        for (i in 0 until 14) {
            val x = w * (0.15f + r.nextFloat() * 0.7f); val y = h * (0.35f + r.nextFloat() * 0.35f); val rad = h * (0.2f + r.nextFloat() * 0.3f)
            p.shader = RadialGradient(x, y - rad * 0.2f, rad, intArrayOf(0xCC2A2A36.toInt(), 0x88121218.toInt(), 0x00000000), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, p)
        }
        return b
    }

    private fun makeFog(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); val p = Paint(Paint.ANTI_ALIAS_FLAG); val r = Random(3)
        for (i in 0 until 26) {
            val x = r.nextFloat() * w; val y = h * (0.3f + r.nextFloat() * 0.5f); val rad = h * (0.3f + r.nextFloat() * 0.5f)
            p.shader = RadialGradient(x, y, rad, intArrayOf(0x3398C8A0, 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, p)
            // wrap horizontally so the strip tiles
            c.drawCircle(x - w, y, rad, p); c.drawCircle(x + w, y, rad, p)
        }
        return b
    }

    private fun paintBackground(c: Canvas, w: Float, h: Float, chromeOnly: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (chromeOnly) { paintChrome(c, p); return }
        Art.graveyard(c, w, h, Neon.RED)
        // foreground 3D tombstones (perspective: nearer = larger and brighter)
        val rr = Random(5)
        for (i in 0 until 7) {
            val depth = rr.nextFloat()
            val tw = (40 + 70 * depth) * u; val th = tw * 1.3f
            val x = if (i % 2 == 0) w * (0.02f + rr.nextFloat() * 0.2f) else w * (0.78f + rr.nextFloat() * 0.2f)
            val gy = h * (0.62f + 0.16f * depth)
            val front = RectF(x, gy - th, x + tw, gy)
            p.color = 0xFF050605.toInt()
            c.drawRoundRect(front.left + tw * 0.12f, front.top + tw * 0.06f, front.right + tw * 0.12f, front.bottom, tw * 0.45f, tw * 0.45f, p)  // side face
            p.shader = LinearGradient(front.left, 0f, front.right, 0f, intArrayOf(0xFF4A5048.toInt(), 0xFF2A2E28.toInt(), 0xFF141612.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(front, tw * 0.45f, tw * 0.45f, p); p.shader = null
            p.color = 0x5576FF03; c.drawRect(front.left - tw * 0.1f, gy - 3 * u, front.right + tw * 0.25f, gy + 2 * u, p)
            p.color = 0x66000000; p.textAlign = Paint.Align.CENTER; p.textSize = tw * 0.3f; p.typeface = serif
            c.drawText("RIP", front.centerX(), front.top + th * 0.45f, p)
        }
        // vignette
        p.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.7f, intArrayOf(0x00000000, 0xCC000000.toInt()), floatArrayOf(0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        // relic bar plate
        p.color = 0xCC050805.toInt()
        c.drawRoundRect(relicRect, relicRect.height() / 2, relicRect.height() / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * u; p.color = 0x8876FF03.toInt()
        c.drawRoundRect(relicRect, relicRect.height() / 2, relicRect.height() / 2, p)
        p.style = Paint.Style.FILL
        // reel frame: iron + neon tube
        tmp.set(frameRect); tmp.inset(-6 * u, -6 * u)
        p.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, intArrayOf(0xFF2B2F33.toInt(), 0xFF0E0F10.toInt(), 0xFF22262A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(tmp, 16 * u, 16 * u, p); p.shader = null
        // rivets
        p.color = 0xFF4A4F55.toInt()
        var x = tmp.left + 20 * u
        while (x < tmp.right - 10 * u) { c.drawCircle(x, tmp.top + 7 * u, 3 * u, p); c.drawCircle(x, tmp.bottom - 7 * u, 3 * u, p); x += 40 * u }
        p.color = 0xFF020302.toInt()
        c.drawRoundRect(frameRect, 10 * u, 10 * u, p)
        val tube = Path().apply { addRoundRect(frameRect, 10 * u, 10 * u, Path.Direction.CW) }
        Art.neonStroke(c, tube, Neon.GREEN, 2.5f * u)
        // reel backs
        for (r in 0 until REELS) {
            val l = reelRect.left + r * cell
            p.shader = LinearGradient(0f, reelRect.top, 0f, reelRect.bottom,
                intArrayOf(0xFF000000.toInt(), 0xFF0D140B.toInt(), 0xFF101A0E.toInt(), 0xFF0D140B.toInt(), 0xFF000000.toInt()),
                floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(l + 2 * u, reelRect.top, l + cell - 2 * u, reelRect.bottom, p)
        }
        p.shader = null
        // bottom panel
        p.shader = LinearGradient(0f, panelRect.top, 0f, panelRect.bottom, 0xF0101410.toInt(), 0xF0020302.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(panelRect, 18 * u, 18 * u, p); p.shader = null
        Art.neonStroke(c, Path().apply { addRoundRect(panelRect, 18 * u, 18 * u, Path.Direction.CW) }, Neon.GREEN_DIM, 1.5f * u)
        for (r in listOf(creditRect, winRect)) {
            p.color = 0xFF000000.toInt(); c.drawRoundRect(r, 12 * u, 12 * u, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * u; p.color = 0xFF2E7D0A.toInt()
            c.drawRoundRect(r, 12 * u, 12 * u, p); p.style = Paint.Style.FILL
        }
    }

    /** HUD-only chrome (relic plate, bottom panel, meter boxes) for 3D mode: no scenery. */
    private fun paintChrome(c: Canvas, p: Paint) {
        p.shader = RadialGradient(width / 2f, height / 2f, max(width, height) * 0.75f, intArrayOf(0x00000000, 0x88000000.toInt()), floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p); p.shader = null
        p.color = 0xCC050805.toInt()
        c.drawRoundRect(relicRect, relicRect.height() / 2, relicRect.height() / 2, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * u; p.color = 0x8876FF03.toInt()
        c.drawRoundRect(relicRect, relicRect.height() / 2, relicRect.height() / 2, p)
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, panelRect.top, 0f, panelRect.bottom, 0xE8101410.toInt(), 0xF0020302.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(panelRect, 18 * u, 18 * u, p); p.shader = null
        Art.neonStroke(c, Path().apply { addRoundRect(panelRect, 18 * u, 18 * u, Path.Direction.CW) }, Neon.GREEN_DIM, 1.5f * u)
        for (r in listOf(creditRect, winRect)) {
            p.color = 0xFF000000.toInt(); c.drawRoundRect(r, 12 * u, 12 * u, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f * u; p.color = 0xFF2E7D0A.toInt()
            c.drawRoundRect(r, 12 * u, 12 * u, p); p.style = Paint.Style.FILL
        }
    }

    private fun recycleBitmaps() {
        chars?.recycle(); bigChars?.recycle(); blurred?.forEach { it.recycle() }
        relicLit?.forEach { it.recycle() }; relicDark?.forEach { it.recycle() }
        bg?.recycle(); fog?.recycle(); cloud?.recycle(); hudChrome?.recycle()
    }

    fun release() = recycleBitmaps()

    // ================================================================== flow
    fun onWalletChanged() { invalidate() }

    fun showMessage(text: String, sec: Double = 2.6) { message = text; messageUntil = now() + sec; invalidate() }

    fun onHostPause() { autoLeft = 0; holdMode = false; spinHeldSince = -1.0 }

    private fun enter(p: Phase) { phase = p; phaseStart = now(); invalidate() }

    private val busy get() = (phase != Phase.IDLE && phase != Phase.PRESENT) || now() < winLockUntil

    private fun startSpin() {
        if (busy) return
        val stake = wallet.stake
        if (!wallet.canAfford(stake)) {
            autoLeft = 0; holdMode = false; audio.play(Sfx.ERROR); creditFlashUntil = now() + 1.5
            showMessage(if (wallet.balance == 0L) "Deposit tokens to play" else "Not enough credit for this stake")
            return
        }
        settleRoll()
        val p = wallet.playSpin(stake) ?: run { audio.play(Sfx.ERROR); return }
        animateSpin(p)
    }

    /** Animate an already-settled spin. */
    internal fun animateSpin(p: SpinPlay) {
        play = p
        val o = p.outcome
        pendingWin = o.payTokens
        pendingVault = p.vault?.payTokens ?: 0L
        vaultWinShown = 0
        rollTarget = 0
        audio.play(Sfx.SPIN, 0.6f)
        val t = now()
        val turbo = wallet.turbo || holdMode
        val base = if (turbo) 0.32 else 0.6
        val step = if (turbo) 0.1 else 0.2
        miniType = o.mini.type
        java.util.Arrays.fill(coinShown, false); java.util.Arrays.fill(cellFlash, -10.0)
        multStampAt = -1.0; actionEvents = 0
        val reaper = miniType == ReaperConfig.MINI_REAPER
        val feature = o.feature
        featureStart = if (reaper) t + 0.3 else -1.0
        slashAt = if (reaper) t + 1.05 else -1.0
        miniStart = if (feature && !reaper) t + 0.25 else -1.0
        slashPlayed = false
        if (reaper) audio.play(Sfx.GROWL, 0.9f)
        if (feature && !reaper) { audio.play(Sfx.WHOOSH); miniVoice(miniType)?.let { audio.play(it, 0.9f) } }
        for (r in 0 until REELS) {
            isWildReel[r] = r in o.wildReels
            val stopAt = when {
                isWildReel[r] -> slashAt
                feature -> t + base + step * r + 1.3
                else -> t + base + step * r
            }
            liftEnd[r] = t + 0.09
            runEnd[r] = stopAt
            val speed = if (turbo) 30.0 else 24.0
            val run = stopAt - liftEnd[r]
            val s = max(4, ceil(landDist - 0.35 + speed * run).toInt())
            reelS[r] = s.toDouble(); reelV[r] = (s + 0.35 - landDist) / run
            val strip = ReaperConfig.STRIPS[r]
            val tp = IntArray(s + 3)
            for (i in 0 until s) tp[i] = strip[(o.stops[r] + i) % strip.size]
            if (isWildReel[r]) for (row in 0 until ROWS) tp[row] = WILD
            for (row in 0 until ROWS) tp[s + row] = grid[r][row]
            tape[r] = tp
            landed[r] = false; done[r] = false
        }
        enter(Phase.SPINNING)
    }

    private fun slamStop() {
        val t = now()
        for (r in 0 until REELS) if (!landed[r] && !isWildReel[r]) {
            val target = max(t + 0.05 * r, if (featureStart > 0) slashAt + 0.1 else 0.0)
            if (runEnd[r] > target) runEnd[r] = target
        }
    }

    private fun offset(r: Int, t: Double): Double {
        val s = reelS[r]
        if (t < liftEnd[r]) return s + 0.35 * sin(PI / 2 * (1 - (liftEnd[r] - t) / 0.09).coerceIn(0.0, 1.0))
        if (t < runEnd[r]) return min(s + 0.35, landDist + reelV[r] * (runEnd[r] - t))
        val q = (t - runEnd[r]) / landTime
        if (q >= 1) return 0.0
        val c = 1.5
        return landDist * (1 - (1 + (c + 1) * (q - 1) * (q - 1) * (q - 1) + c * (q - 1) * (q - 1)))
    }

    private fun tapeSym(r: Int, k: Int): Int {
        val tp = tape[r] ?: return grid[r][k.coerceIn(0, 2)]
        if (k in tp.indices) return tp[k]
        val strip = ReaperConfig.STRIPS[r]
        return strip[Math.floorMod((outcome?.stops?.get(r) ?: 0) + k, strip.size)]
    }

    private fun updateSpin(t: Double) {
        val o = outcome ?: return
        if (slashAt > 0 && !slashPlayed && t >= slashAt) {
            slashPlayed = true
            audio.play(Sfx.SLASH); audio.play(Sfx.THUNDER, 0.5f)
            lightningAt = t; makeBolt(); shakeUntil = t + 0.35
        }
        for (r in 0 until REELS) {
            if (!landed[r] && t >= runEnd[r]) {
                landed[r] = true
                if (!isWildReel[r]) audio.play(Sfx.STOP, 0.75f)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (!done[r] && t >= runEnd[r] + landTime) {
                done[r] = true
                for (row in 0 until ROWS) grid[r][row] = if (needsAction(o)) o.landed[r][row] else o.grid[r][row]
                tape[r] = null
            }
        }
        if (done.all { it }) {
            if (needsAction(o)) { actionStart = t; actionEvents = 0; enter(Phase.MINI_ACTION) } else onStopped(o)
        }
    }

    private fun needsAction(o: SpinOutcome) = o.mini.type == ReaperConfig.MINI_RAVEN ||
        o.mini.type == ReaperConfig.MINI_PUMPKIN || o.mini.type == ReaperConfig.MINI_SKULL

    private fun miniVoice(type: Int): Sfx? = when (type) {
        ReaperConfig.MINI_RAVEN -> Sfx.CAW; ReaperConfig.MINI_PUMPKIN -> Sfx.CACKLE
        ReaperConfig.MINI_SKULL -> Sfx.CHATTER; ReaperConfig.MINI_REAPER -> Sfx.LAUGH; else -> null
    }

    private fun miniSymbol(type: Int) = when (type) {
        ReaperConfig.MINI_RAVEN -> ReaperConfig.RAVEN; ReaperConfig.MINI_PUMPKIN -> ReaperConfig.PUMPKIN
        ReaperConfig.MINI_SKULL -> ReaperConfig.SKULL; else -> ReaperConfig.WILD
    }

    /** Cells the action touches, in order, and the time each lands (relative to actionStart). */
    private fun actionCells(o: SpinOutcome): IntArray =
        if (o.mini.type == ReaperConfig.MINI_PUMPKIN) (0 until REELS * ROWS).filter { o.mini.coins[it] > 0 }.toIntArray()
        else o.mini.wildCells

    private val actionGap = 0.38
    private fun actionEnd(o: SpinOutcome): Double =
        0.5 + actionCells(o).size * actionGap + if (o.mini.type == ReaperConfig.MINI_SKULL) 1.3 else 0.5

    private fun updateAction(t: Double) {
        val o = outcome ?: return
        val cells = actionCells(o)
        val e = t - actionStart
        while (actionEvents < cells.size && e >= 0.5 + actionEvents * actionGap + 0.3) {
            val cell = cells[actionEvents++]
            cellFlash[cell] = t
            if (o.mini.type == ReaperConfig.MINI_PUMPKIN) { coinShown[cell] = true; audio.play(Sfx.COINS, 0.7f) }
            else { grid[cell / ROWS][cell % ROWS] = WILD; audio.play(Sfx.SLASH, 0.6f); audio.play(Sfx.STOP, 0.6f) }
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (o.mini.type == ReaperConfig.MINI_SKULL && multStampAt < 0 && e >= 0.5 + cells.size * actionGap + 0.2) {
            multStampAt = t; shakeUntil = t + 0.3
            audio.play(Sfx.BELL, 0.8f); audio.play(Sfx.CHATTER)
        }
        if (e >= actionEnd(o)) {
            for (r in 0 until REELS) for (row in 0 until ROWS) grid[r][row] = o.grid[r][row]
            onStopped(o)
        }
    }

    private fun onStopped(o: SpinOutcome) {
        val t = now()
        presentStart = t
        featureStart = -1.0
        val p = play
        if (p != null && p.relicIndex >= 0) { relicFly = p.relicIndex; relicStart = t + 0.2 }
        val win = o.payTokens
        val x = win.toDouble() / o.stake
        tier = when { x >= 100 -> 4; x >= 50 -> 3; x >= 25 -> 2; x >= 10 -> 1; else -> 0 }
        showcaseSym = o.lineWins.filter { it.count == 5 && it.symbol <= ReaperConfig.RAVEN }.minByOrNull { it.symbol }?.symbol ?: -1
        if (win > 0) {
            rollTarget = win; rollStart = t
            // small wins show instantly; bigger ones count up (suspense, but never slow)
            rollDur = when {
                tier > 0 -> 2.2 + tier * 0.9
                x >= 2 -> (0.9 + 0.35 * ln(x)).coerceAtMost(2.0)
                else -> 0.0
            }
            countEndPlayed = rollDur == 0.0
            bannerHold = rollDur + if (x >= 2) 1.3 else 1.4
            winLockUntil = t + rollDur + 0.9
            // the best character in the win gets to laugh / chatter / caw
            val star = o.lineWins.map { it.symbol }.filter { it <= ReaperConfig.GHOST }.minOrNull()
            star?.let { sym -> charSound(sym)?.let { snd -> audio.play(snd, 0.85f) } }
            when {
                tier > 0 -> { audio.play(Sfx.FANFARE); audio.play(Sfx.BIG_WIN, 0.8f); audio.play(Sfx.LAUGH); soulRate = 30f + tier * 20f; shakeUntil = t + 0.6; sideLaughUntil = t + 3; enter(Phase.BIG_WIN); return }
                showcaseSym >= 0 -> { audio.play(Sfx.WIN_MED); enter(Phase.SHOWCASE); return }
                else -> { audio.play(if (x >= 3) Sfx.WIN_MED else Sfx.WIN, 0.9f); if (x < 2) audio.play(Sfx.COINS, 0.6f) }
            }
            if (x >= 4) soulRate = 18f
        } else {
            rollTarget = 0
            pendingWin = 0
        }
        enter(Phase.PRESENT)
        schedule()
    }

    private var bannerHold = 2.2

    private fun schedule() {
        val o = outcome ?: return
        // every win pauses long enough to read the amount, autoplay included
        val delay = if (o.payTokens > 0) bannerHold + 0.3 else if (holdMode) 0.12 else if (wallet.turbo) 0.25 else 0.45
        nextAt = now() + delay
    }

    private fun rollValue(t: Double): Long {
        if (rollTarget == 0L) return 0
        if (rollDur <= 0.0) return rollTarget
        val q = ((t - rollStart) / rollDur).coerceIn(0.0, 1.0)
        val eased = 1 - (1 - q) * (1 - q) * (1 - q)          // fast start, suspenseful slow finish
        return if (q >= 1) rollTarget else (rollTarget * eased).toLong()
    }

    private fun settleRoll() { rollStart = now() - rollDur - 1; pendingWin = 0; soulRate = 0f }

    private fun relicBusy(t: Double) = relicFly >= 0 && t < relicStart + relicDur

    private fun updateFlow(t: Double) {
        if (!countEndPlayed && rollTarget > 0 && t - rollStart >= rollDur) {
            countEndPlayed = true
            audio.play(Sfx.COUNT_END); audio.play(Sfx.COINS, 0.7f)
            soulRate = max(soulRate, 12f)
        }
        if (rollTarget > 0 && t - rollStart < rollDur) {
            val q = ((t - rollStart) / rollDur).toFloat()
            if (t - lastTick > 0.07 + 0.08 * q) { lastTick = t; audio.play(Sfx.TICK, 0.35f, 0.8f + 0.8f * q) }
            if (phase != Phase.IN_VAULT && vaultWinShown == 0L) pendingWin = rollTarget - rollValue(t)
            else if (vaultWinShown > 0) pendingVault = rollTarget - rollValue(t)
        } else if (phase != Phase.SPINNING && (pendingWin != 0L || (vaultWinShown > 0 && pendingVault != 0L))) {
            pendingWin = 0; if (vaultWinShown > 0) pendingVault = 0
            soulRate = 0f
        }
        if (relicFly >= 0 && t >= relicStart + relicDur) {
            relicFly = -1
        }
        if (relicFly >= 0 && !relicSoundPlayed && t >= relicStart) { relicSoundPlayed = true; audio.play(Sfx.RELIC) }
        if (relicFly < 0) relicSoundPlayed = false
        when (phase) {
            Phase.BIG_WIN -> if (t - rollStart > rollDur + 2.0) { soulRate = 0f; enter(Phase.PRESENT); presentStart = t - 10; nextAt = t + 0.8 }
            Phase.SHOWCASE -> if (t - phaseStart > 2.0) { enter(Phase.PRESENT); presentStart = t; rollStart = min(rollStart, t); schedule() }
            Phase.PRESENT -> if (t >= nextAt && t >= winLockUntil && !relicBusy(t)) {
                val v = play?.vault
                if (v != null && vaultWinShown == 0L) {
                    audio.play(Sfx.BELL); audio.play(Sfx.VAULT)
                    enter(Phase.VAULT_TRIGGER)
                } else if (holdMode) {
                    startSpin()
                } else if (autoLeft != 0) {
                    if (autoLeft > 0) autoLeft--
                    startSpin()
                }
            }
            Phase.IDLE -> if (holdMode) startSpin() else if (autoLeft != 0 && t >= nextAt) { if (autoLeft > 0) autoLeft--; startSpin() }
            Phase.VAULT_TRIGGER -> if (t - phaseStart > 3.2) {
                val v = play?.vault
                enter(Phase.IN_VAULT)
                audio.playTrack(Audio.Track.VAULT)
                if (v != null) host.openVault(v)
            }
            else -> {}
        }
    }

    private var relicSoundPlayed = false

    /** Called by the activity when the Soul Vault screen closes. */
    fun onVaultFinished(paid: Long) {
        audio.playTrack(Audio.Track.BASE)
        vaultWinShown = max(1L, paid)
        rollTarget = paid; rollStart = now(); rollDur = if (paid > 0) 2.0 else 0.1
        pendingVault = paid
        pendingWin = 0
        showMessage("THE SOUL VAULT PAID ${Money.fmt(paid)} TOKENS", 3.5)
        if (paid > 0) { audio.play(Sfx.WIN_MED); soulRate = 25f }
        enter(Phase.PRESENT)
        presentStart = now()
        nextAt = now() + 3.0
        play = null
    }

    /** App restarted with a paid-but-unseen Soul Vault: show it first. */
    fun resumeVault(v: VaultResult) {
        pendingVault = v.payTokens
        enter(Phase.IN_VAULT)
        audio.playTrack(Audio.Track.VAULT)
        host.openVault(v)
    }

    // ================================================================== input
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = buttons.firstOrNull { it.enabled && hit(it, e.x, e.y) }
                if (pressed?.id == "spin") { spinHeldSince = now(); audio.play(Sfx.PRESS) }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> pressed?.let { if (!hit(it, e.x, e.y)) { pressed = null; endHold(); invalidate() } }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = null
                val wasHolding = holdMode
                endHold()
                if (p != null && hit(p, e.x, e.y)) { if (!(p.id == "spin" && wasHolding)) onButton(p.id) } else onTap()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; endHold() }
        }
        return true
    }

    private fun endHold() {
        spinHeldSince = -1.0
        if (holdMode) { holdMode = false; showMessage("Rapid spins stopped", 1.2) }
    }

    /** Holding SPIN for half a second starts rapid spins until the finger lifts. */
    private fun checkHold(t: Double) {
        if (spinHeldSince > 0 && !holdMode && t - spinHeldSince > 0.5 && pressed?.id == "spin" && autoLeft == 0) {
            holdMode = true
            audio.play(Sfx.CLICK)
            if (!busy) startSpin()
        }
    }

    private fun hit(b: Btn, x: Float, y: Float): Boolean { tmp.set(b.rect); tmp.inset(-6 * u, -6 * u); return tmp.contains(x, y) }

    private fun onTap() {
        val t = now()
        when (phase) {
            Phase.SPINNING -> slamStop()
            Phase.BIG_WIN -> if (t - rollStart > rollDur + 1.0) rollStart = t - rollDur - 2.0
            Phase.SHOWCASE -> if (t - phaseStart > 1.2) phaseStart = t - 2.0
            else -> {}
        }
    }

    private fun onButton(id: String) {
        if (id != "spin") audio.play(Sfx.CLICK)
        when (id) {
            "spin" -> when {
                phase == Phase.SPINNING -> slamStop()
                autoLeft != 0 -> { autoLeft = 0; showMessage("Autoplay stopped") }
                busy -> onTap()
                else -> startSpin()
            }
            "auto" -> if (autoLeft != 0) { autoLeft = 0; showMessage("Autoplay stopped") } else if (!busy) host.openAutoPicker { n ->
                autoLeft = n
                if (n != 0 && !busy) { if (autoLeft > 0) autoLeft--; startSpin() }
            }
            "stake" -> if (!busy) {
                wallet.stakeIndex = (wallet.stakeIndex + 1) % ReaperConfig.STAKES.size
                wallet.save()
                if (phase == Phase.PRESENT) { play = null; enter(Phase.IDLE) }
            }
            "deposit" -> if (!busy && autoLeft == 0) host.openDeposit() else showMessage("Wait for the reels to stop")
            "withdraw" -> if (!busy && autoLeft == 0) host.openWithdraw() else showMessage("Wait for the reels to stop")
            "info" -> host.openInfo()
            "stats" -> host.openStats()
            "sound" -> {
                // cycle: all on -> music off -> silent
                when {
                    wallet.musicOn && wallet.soundOn -> wallet.musicOn = false
                    wallet.soundOn -> wallet.soundOn = false
                    else -> { wallet.soundOn = true; wallet.musicOn = true }
                }
                audio.sfxOn = wallet.soundOn; audio.musicOn = wallet.musicOn; wallet.save()
            }
            "turbo" -> { wallet.turbo = !wallet.turbo; wallet.save() }
        }
    }

    // ================================================================== drawing
    override fun onDraw(canvas: Canvas) {
        val ch = chars ?: return
        val t = now()
        val dt = if (lastFrame == 0.0) 0.016 else (t - lastFrame).coerceIn(0.0, 0.05)
        lastFrame = t
        if (phase == Phase.SPINNING) updateSpin(t)
        if (phase == Phase.MINI_ACTION) updateAction(t)
        checkHold(t)
        updateFlow(t)
        ambient(t)

        val g3 = lay3d != null
        canvas.save()
        if (t < shakeUntil) canvas.translate((fx.nextFloat() - 0.5f) * 10 * u, (fx.nextFloat() - 0.5f) * 8 * u)
        if (g3) hudChrome?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        else { canvas.drawBitmap(bg!!, 0f, 0f, null); drawSky(canvas, t) }
        drawLightning(canvas, t)
        if (!g3) {
            drawBats(canvas, t)
            drawFog(canvas, t, reelRect.bottom - (fog?.height ?: 0) * 0.5f, 18.0, 160)
            drawCharacters(canvas, t)
        }
        drawTitle(canvas, t)
        drawRelics(canvas, t)
        if (!g3) drawReels(canvas, ch, t)
        drawMiniEffects(canvas, t)
        drawWins(canvas, t)
        miniPoseValid = false
        drawMini(canvas, t)
        drawWinBanner(canvas, t)
        touchQ = -1f
        drawFeature(canvas, t)
        if (!g3) drawFog(canvas, t, reelRect.bottom - (fog?.height ?: 0) * 0.75f, -11.0, 90)
        drawPanel(canvas, t)
        drawButtons(canvas, t)
        canvas.restore()
        drawRelicFlight(canvas, t)
        updateParticles(dt.toFloat())
        if (!g3) drawParticles(canvas)
        when (phase) {
            Phase.BIG_WIN -> drawBigWin(canvas, t)
            Phase.SHOWCASE -> drawShowcase(canvas, t)
            Phase.VAULT_TRIGGER -> drawVaultTrigger(canvas, t)
            else -> {}
        }
        drawMessage(canvas, t)
        if (g3) publishScene(t)
        if (phase == Phase.IN_VAULT) postInvalidateDelayed(250) else postInvalidateOnAnimation()
    }

    private fun ambient(t: Double) {
        if (nextLightning == 0.0) { nextLightning = t + 8 + fx.nextDouble() * 10; nextBats = t + 5; nextBlink = t + 3 }
        if (t >= nextLightning && phase != Phase.IN_VAULT) {
            lightningAt = t; makeBolt(); thunderAt = t + 0.4 + fx.nextDouble() * 0.6
            nextLightning = t + 20 + fx.nextDouble() * 25
        }
        if (thunderAt > 0 && t >= thunderAt) { audio.play(Sfx.THUNDER, 0.45f); thunderAt = -1.0 }
        if (t >= nextBats) { batsStart = t; batsY = height * (0.15f + fx.nextFloat() * 0.2f); nextBats = t + 14 + fx.nextDouble() * 18 }
        if (t >= nextBlink) { blinkUntil = t + 0.14; nextBlink = t + 2.5 + fx.nextDouble() * 4 }
        // a random character on the reels comes alive now and then, with its voice
        if (nextEvent == 0.0) { nextEvent = t + 4; nextSideLaugh = t + 15 }
        val quiet = phase == Phase.IDLE || (phase == Phase.PRESENT && (outcome?.payTokens ?: 0L) == 0L)
        if (t >= nextEvent) {
            nextEvent = t + 6 + fx.nextDouble() * 7
            if (quiet) {
                val cands = ArrayList<Int>()
                for (r in 0 until REELS) for (row in 0 until ROWS) if (grid[r][row] <= ReaperConfig.GHOST) cands += r * ROWS + row
                if (cands.isNotEmpty()) {
                    val pick = cands[fx.nextInt(cands.size)]
                    eventReel = pick / ROWS; eventRow = pick % ROWS; eventUntil = t + 1.8
                    charSound(grid[eventReel][eventRow])?.let { audio.play(it, 0.55f) }
                }
            }
        }
        if (t >= nextSideLaugh) {
            nextSideLaugh = t + 18 + fx.nextDouble() * 20
            if (quiet) { sideLaughUntil = t + 2.0; audio.play(Sfx.LAUGH, 0.45f) }
        }
    }

    private fun makeBolt() {
        bolt.reset()
        var x = width * (0.15f + fx.nextFloat() * 0.7f); var y = 0f
        bolt.moveTo(x, y)
        while (y < height * 0.62f) {
            x += (fx.nextFloat() - 0.5f) * 60 * u; y += (20 + fx.nextFloat() * 40) * u
            bolt.lineTo(x, y)
            if (fx.nextFloat() < 0.18f) { bolt.lineTo(x + (fx.nextFloat() - 0.5f) * 120 * u, y + 50 * u); bolt.moveTo(x, y) }
        }
    }

    private fun lightningLevel(t: Double): Float {
        val e = t - lightningAt
        if (e < 0 || e > 0.6) return 0f
        val flicker = if (e < 0.08 || (e > 0.16 && e < 0.24)) 1f else 0.35f
        return ((1 - e / 0.6) * flicker).toFloat()
    }

    private fun drawLightning(c: Canvas, t: Double) {
        val e = t - lightningAt
        if (e < 0 || e > 0.6) return
        val a = lightningLevel(t)
        if (lay3d == null) {
            pFill.color = Color.argb((120 * a).toInt(), 200, 255, 220)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        }
        pStroke.color = Color.argb((255 * a).toInt(), 230, 255, 240)
        pStroke.strokeWidth = 3 * u
        c.drawPath(bolt, pStroke)
        pStroke.color = Color.argb((90 * a).toInt(), 118, 255, 3); pStroke.strokeWidth = 12 * u
        c.drawPath(bolt, pStroke)
    }

    private fun drawBats(c: Canvas, t: Double) {
        val e = t - batsStart
        if (e < 0 || e > 6) return
        for (i in 0 until 6) {
            val depth = 0.6f + (i % 3) * 0.3f                   // nearer bats are bigger, faster, lower
            val bx = (-80 * u + (e / 6 * (width + 160 * u)) * depth - i * 50 * u).toFloat()
            val by = batsY + (sin(e * 3 + i) * 20 * u).toFloat() + (i % 3) * 30 * u
            val flap = sin(e * 20 + i * 1.3).toFloat()
            val s = 16 * u * depth
            for (side in intArrayOf(-1, 1)) {
                path.reset()
                path.moveTo(bx, by)
                path.quadTo(bx + side * s * 0.6f, by - s * (0.2f + flap * 0.7f), bx + side * s * 1.6f, by - flap * s * 0.9f)
                path.lineTo(bx + side * s * 1.3f, by + s * 0.15f - flap * s * 0.3f)
                path.lineTo(bx + side * s * 0.95f, by + s * 0.05f)
                path.lineTo(bx + side * s * 0.6f, by + s * 0.3f)
                path.close()
                pFill.shader = LinearGradient(bx, by - s, bx, by + s, 0xFF2A2234.toInt(), 0xFF050308.toInt(), Shader.TileMode.CLAMP)
                c.drawPath(path, pFill)
            }
            pFill.shader = RadialGradient(bx - s * 0.1f, by - s * 0.1f, s * 0.4f, 0xFF3A3046.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            c.drawOval(bx - s * 0.28f, by - s * 0.3f, bx + s * 0.28f, by + s * 0.35f, pFill)
            pFill.shader = null
            pFill.color = Neon.RED
            c.drawCircle(bx - s * 0.1f, by - s * 0.1f, 1.6f * u * depth, pFill); c.drawCircle(bx + s * 0.1f, by - s * 0.1f, 1.6f * u * depth, pFill)
        }
    }

    private fun drawFog(c: Canvas, t: Double, y: Float, speed: Double, alpha: Int) {
        val f = fog ?: return
        val w = f.width.toFloat()
        var x = -(((t * speed * u) % w + w) % w).toFloat()
        pBmp.alpha = alpha
        while (x < width) { c.drawBitmap(f, x, y, pBmp); x += w }
        pBmp.alpha = 255
    }

    private fun drawCharacters(c: Canvas, t: Double) {
        val bc = bigChars ?: return
        val excited = phase == Phase.BIG_WIN || phase == Phase.SHOWCASE || phase == Phase.VAULT_TRIGGER ||
            (phase == Phase.PRESENT && (outcome?.payTokens ?: 0) > 0 && t - presentStart < 2.2)
        val breathe = (1 + 0.012 * sin(t * 1.6)).toFloat()
        c.save()
        c.translate(leftChar.left, leftChar.top)
        c.scale(1f, breathe, leftChar.width() / 2, leftChar.height())
        bc.draw(c, ReaperConfig.HOODED, t, 0.13, if (excited) Characters.EXCITED else Characters.IDLE)
        c.restore()
        val away = featureStart > 0 && t > featureStart && t < slashAt + 0.6
        if (!away) {
            c.save()
            c.translate(rightChar.left, rightChar.top)
            c.scale(1f, (1 + 0.012 * sin(t * 1.6 + 1)).toFloat(), rightChar.width() / 2, rightChar.height())
            bc.draw(c, ReaperConfig.WILD, t, 0.71, if (excited || t < sideLaughUntil) Characters.EXCITED else Characters.IDLE)
            c.restore()
        }
    }

    private fun drawTitle(c: Canvas, t: Double) {
        val r = titleRect
        pText.typeface = serif
        pText.textSize = r.height() * 0.62f
        val y = r.centerY() - (pText.fontMetrics.ascent + pText.fontMetrics.descent) / 2
        // neon flicker
        val flick = if ((t * 7).toInt() % 23 == 0) 0.4f else 1f
        val a = "NEON "; val b = "REAPER"
        val wa = pText.measureText(a); val wb = pText.measureText(b)
        val x0 = r.centerX() - (wa + wb) / 2
        pText.textAlign = Paint.Align.LEFT
        for ((text, x, col) in listOf(Triple(a, x0, Neon.GREEN), Triple(b, x0 + wa, Neon.RED))) {
            pText.color = col; pText.alpha = (255 * (if (col == Neon.GREEN) flick else 1f)).toInt()
            pText.setShadowLayer(16 * u, 0f, 0f, col)
            c.drawText(text, x, y, pText)
            pText.clearShadowLayer()
            pText.color = Color.WHITE; pText.alpha = (150 * (if (col == Neon.GREEN) flick else 1f)).toInt()
            pText.style = Paint.Style.STROKE; pText.strokeWidth = 1.2f * u
            c.drawText(text, x, y, pText)
            pText.style = Paint.Style.FILL; pText.alpha = 255
        }
        pText.textAlign = Paint.Align.CENTER
    }

    private fun drawRelics(c: Canvas, t: Double) {
        val lit = relicLit ?: return; val dark = relicDark ?: return
        val have = wallet.relics + if (relicFly >= 0 && t < relicStart + relicDur) -1 else 0
        val trigger = phase == Phase.VAULT_TRIGGER
        for (i in 0 until 5) {
            val r = relicSlots[i]
            val on = trigger || i < have.coerceAtLeast(0) || (play?.vault != null && phase != Phase.IN_VAULT && relicFly < 0 && vaultWinShown == 0L)
            if (on) {
                val pulse = (1 + 0.08 * sin(t * 4 + i)).toFloat()
                c.save(); c.scale(pulse, pulse, r.centerX(), r.centerY())
                c.drawBitmap(lit[i], r.left, r.top, null)
                c.restore()
            } else c.drawBitmap(dark[i], r.left, r.top, null)
        }
        pText.typeface = cond; pText.textSize = relicRect.height() * 0.24f; pText.color = 0xFF9CCC65.toInt()
        pText.textAlign = Paint.Align.LEFT
        val lx = relicSlots[4].right + 14 * u
        c.drawText("SOUL VAULT", lx, relicRect.centerY() - 2 * u, pText)
        pText.color = 0xFF8A9A80.toInt(); pText.textSize = relicRect.height() * 0.2f
        c.drawText("BONUS BET ${wallet.averageStake}", lx, relicRect.centerY() + relicRect.height() * 0.26f, pText)
        pText.textAlign = Paint.Align.RIGHT
        pText.color = 0xFF9CCC65.toInt(); pText.textSize = relicRect.height() * 0.24f
        c.drawText("RELICS", relicSlots[0].left - 14 * u, relicRect.centerY() - 2 * u, pText)
        pText.color = 0xFF8A9A80.toInt(); pText.textSize = relicRect.height() * 0.2f
        c.drawText("${wallet.relics.coerceAtMost(5)} / 5", relicSlots[0].left - 14 * u, relicRect.centerY() + relicRect.height() * 0.26f, pText)
        pText.textAlign = Paint.Align.CENTER
    }

    private fun drawRelicFlight(c: Canvas, t: Double) {
        val i = relicFly
        if (i < 0 || t < relicStart) return
        val lit = relicLit ?: return
        val q = ((t - relicStart) / relicDur).toFloat().coerceIn(0f, 1f)
        val target = relicSlots[i]
        // phase 1: appear big over the reels; phase 2: arc into its slot
        val appear = (q / 0.4f).coerceAtMost(1f)
        val fly = ((q - 0.4f) / 0.6f).coerceIn(0f, 1f)
        val ease = fly * fly * (3 - 2 * fly)
        val sx = reelRect.centerX(); val sy = reelRect.centerY()
        val x = sx + (target.centerX() - sx) * ease
        val y = sy + (target.centerY() - sy) * ease - sin(ease * PI).toFloat() * 80 * u
        val scale = (2.6f * appear) * (1 - ease) + 1f * ease
        val s = target.width() * scale
        if (s < 2f) return
        pFill.shader = RadialGradient(x, y, s, intArrayOf(0xAA76FF03.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(x, y, s, pFill); pFill.shader = null
        tmp.set(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
        c.drawBitmap(lit[i], null, tmp, pBmp)
        if (appear < 1f || fly < 0.2f) {
            pText.typeface = serif; pText.textSize = 34 * u; pText.color = Neon.GREEN
            pText.setShadowLayer(12 * u, 0f, 0f, Neon.GREEN)
            c.drawText("RELIC FOUND: ${ReaperConfig.RELIC_NAMES[i].uppercase()}", sx, reelRect.top + 46 * u, pText)
            pText.clearShadowLayer()
        }
    }

    private fun highlight(t: Double): Array<BooleanArray>? {
        val o = outcome ?: return null
        if (phase != Phase.PRESENT && phase != Phase.BIG_WIN && phase != Phase.SHOWCASE) return null
        if (o.lineWins.isEmpty()) return null
        val hl = Array(REELS) { BooleanArray(ROWS) }
        val idx = lineIndex(t)
        for (w in if (idx < 0) o.lineWins else listOf(o.lineWins[idx])) {
            val line = ReaperConfig.PAYLINES[w.line]
            for (r in 0 until w.count) hl[r][line[r]] = true
        }
        return hl
    }

    private fun lineIndex(t: Double): Int {
        val o = outcome ?: return -1
        val n = o.lineWins.size
        if (n <= 1 || phase != Phase.PRESENT) return -1
        val e = t - presentStart
        if (e < 1.3) return -1
        return (((e - 1.3) / 1.3).toInt()) % n
    }

    /** Y of a row centre on the drum; row is fractional (0 = top row, 1 = middle, 2 = bottom). */
    private fun rowY(row: Double): Float = lay3d?.rowY(row.toFloat()) ?: (reelRect.centerY() + drumR * sin((row - 1) * step).toFloat())
    private fun rowScale(row: Double): Float = lay3d?.rowScale(row.toFloat()) ?: cos((row - 1) * step).toFloat()
    private fun cellX(r: Int): Float = lay3d?.reelScreenX(r) ?: (reelRect.left + (r + 0.5f) * cell)
    private fun cellW(): Float = lay3d?.cellPx() ?: cell
    private fun cellPhase(r: Int, row: Int) = ((r * 0.37 + row * 0.61) % 1.0)

    private fun charSound(sym: Int): Sfx? = when (sym) {
        ReaperConfig.WILD -> Sfx.LAUGH; ReaperConfig.HOODED -> Sfx.GROWL; ReaperConfig.SKULL -> Sfx.CHATTER
        ReaperConfig.RAVEN -> Sfx.CAW; ReaperConfig.PUMPKIN -> Sfx.CACKLE; ReaperConfig.GHOST -> Sfx.GIGGLE
        else -> null
    }

    private fun drawSymbolAt(c: Canvas, ch: Characters, sym: Int, left: Float, row: Double, t: Double, phaseV: Double, mood: Int, alpha: Int, pop: Float = 1f) {
        val sc = rowScale(row)
        if (sc <= 0.05f) return
        c.save()
        c.translate(left, rowY(row))
        c.scale(pop, sc * pop, cell / 2, 0f)
        c.translate(0f, -cell / 2)
        ch.draw(c, sym, t, phaseV, mood, alpha)
        c.restore()
    }

    private fun drawReels(c: Canvas, ch: Characters, t: Double) {
        val bl = blurred ?: return
        c.save(); c.clipRect(reelRect)
        val hl = highlight(t)
        val showcase = phase == Phase.SHOWCASE || phase == Phase.BIG_WIN
        val pulse = (1 + 0.06 * sin(t * 9)).toFloat()
        for (r in 0 until REELS) {
            val left = reelRect.left + r * cell
            if (phase == Phase.SPINNING && !done[r]) {
                val off = offset(r, t)
                val fast = t > liftEnd[r] && t < runEnd[r]
                val base = floor(off).toInt()
                for (k in base - 1..base + 4) {
                    val row = k - off
                    if (abs((row - 1) * step) > 1.5) continue
                    val s = tapeSym(r, k)
                    if (fast) {
                        val sc = rowScale(row)
                        tmp.set(left, rowY(row) - cell / 2 * sc, left + cell, rowY(row) + cell / 2 * sc)
                        c.drawBitmap(bl[s], null, tmp, pBmp)
                    } else drawSymbolAt(c, ch, s, left, row, t, cellPhase(r, k), Characters.IDLE, 255)
                }
            } else {
                for (row in 0 until ROWS) {
                    val s = grid[r][row]
                    val lit = hl?.get(r)?.get(row) == true
                    val event = r == eventReel && row == eventRow && t < eventUntil
                    val mood = if (lit || event || (showcase && hl == null)) Characters.EXCITED else Characters.IDLE
                    val alpha = if (hl != null && !lit) 75 else 255
                    val pop = when { lit -> pulse; event -> 1.12f; else -> 1f }
                    drawSymbolAt(c, ch, s, left, row.toDouble(), t, cellPhase(r, row), mood, alpha, pop)
                }
            }
            if (isWildReel[r] && (phase != Phase.IDLE) && (t >= slashAt || slashAt < 0) && play != null) drawHellfire(c, ch, left, t)
        }
        // drum lighting: dark at the curved edges, a soft glossy band near the top
        pFill.shader = shadeTop; c.drawRect(reelRect.left, reelRect.top, reelRect.right, reelRect.top + cell * 0.75f, pFill)
        pFill.shader = shadeBot; c.drawRect(reelRect.left, reelRect.bottom - cell * 0.75f, reelRect.right, reelRect.bottom, pFill)
        pFill.shader = gloss; c.drawRect(reelRect.left, reelRect.top, reelRect.right, reelRect.top + cell * 0.9f, pFill)
        pFill.shader = null
        c.restore()
        // brass reel separators (cylinders)
        for (r in 1 until REELS) {
            val x = reelRect.left + r * cell
            pFill.shader = sepShader
            c.save(); c.translate(x, 0f)
            c.drawRect(-3 * u, reelRect.top, 3 * u, reelRect.bottom, pFill)
            c.restore()
        }
        pFill.shader = null
    }

    private fun drawHellfire(c: Canvas, ch: Characters, left: Float, t: Double) {
        val a = (90 + 60 * sin(t * 8)).toInt().coerceIn(0, 255)
        pFill.shader = LinearGradient(0f, reelRect.bottom, 0f, reelRect.top, intArrayOf(Color.argb(a, 118, 255, 3), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(left, reelRect.top, left + cell, reelRect.bottom, pFill); pFill.shader = null
        for (i in 0 until 5) {
            val ph = ((t * 1.3 + i * 0.21) % 1.0).toFloat()
            ch.flame(c, left + cell * (0.12f + i * 0.19f), reelRect.bottom - ph * cell * 0.5f, cell * 0.09f * (1 - ph * 0.5f), Neon.GREEN, sin(t * 11 + i).toFloat())
        }
        pStroke.color = Neon.GREEN; pStroke.strokeWidth = 4 * u; pStroke.alpha = (a + 60).coerceAtMost(255)
        c.drawRect(left + 3 * u, reelRect.top + 3 * u, left + cell - 3 * u, reelRect.bottom - 3 * u, pStroke)
        pStroke.alpha = 255
    }

    private fun drawWins(c: Canvas, t: Double) {
        val o = outcome ?: return
        if (phase != Phase.PRESENT && phase != Phase.BIG_WIN && phase != Phase.SHOWCASE) return
        if (o.lineWins.isEmpty()) return
        val idx = lineIndex(t)
        for (w in if (idx < 0) o.lineWins else listOf(o.lineWins[idx])) {
            val line = ReaperConfig.PAYLINES[w.line]
            val col = lineColors[w.line % lineColors.size]
            path.reset()
            path.moveTo(reelRect.left, rowY(line[0].toDouble()))
            for (r in 0 until REELS) path.lineTo(cellX(r), rowY(line[r].toDouble()))
            path.lineTo(reelRect.right, rowY(line[4].toDouble()))
            pStroke.color = col; pStroke.alpha = 80; pStroke.strokeWidth = 14 * u; c.drawPath(path, pStroke)
            pStroke.alpha = 255; pStroke.strokeWidth = 4 * u; c.drawPath(path, pStroke)
            pStroke.color = Color.WHITE; pStroke.alpha = 200; pStroke.strokeWidth = 1.4f * u; c.drawPath(path, pStroke)
            pStroke.alpha = 255
            pStroke.color = col; pStroke.strokeWidth = 3 * u
            for (r in 0 until w.count) {
                val cw = cellW(); val x = cellX(r) - cw / 2; val yc = rowY(line[r].toDouble()); val hh = cw * rowScale(line[r].toDouble()) / 2
                tmp.set(x + 5 * u, yc - hh + 4 * u, x + cw - 5 * u, yc + hh - 4 * u)
                c.drawRoundRect(tmp, 10 * u, 10 * u, pStroke)
            }
        }
        if (phase != Phase.PRESENT) return
        val cap = if (idx >= 0) {
            val w = o.lineWins[idx]
            "LINE ${w.line + 1}  •  ${w.count} × ${ReaperConfig.SYMBOL_NAMES[w.symbol].uppercase()}  •  ${Money.fmt(w.mult.toLong() * o.stake * o.mini.multiplier)} TOKENS"
        } else when {
            o.capped -> "MAXIMUM WIN — 25 BOOKS"
            o.coinMult > 0 -> "SOUL COINS  •  ${Money.fmt(o.coinMult.toLong() * o.stake)} TOKENS"
            else -> null
        } ?: return
        pText.typeface = cond; pText.textSize = 24 * u
        val tw = pText.measureText(cap) + 40 * u
        val cy = reelRect.bottom - 24 * u
        tmp.set(reelRect.centerX() - tw / 2, cy - 20 * u, reelRect.centerX() + tw / 2, cy + 16 * u)
        pFill.color = 0xE0000000.toInt(); c.drawRoundRect(tmp, 18 * u, 18 * u, pFill)
        pStroke.color = Neon.GREEN_DIM; pStroke.strokeWidth = 1.5f * u; c.drawRoundRect(tmp, 18 * u, 18 * u, pStroke)
        pText.color = 0xFFCCFF90.toInt()
        c.drawText(cap, reelRect.centerX(), cy + 6 * u, pText)
    }

    private var touchQ = -1f
    private var touchSwing = 0f

    /** Reaper's Touch: the reaper flies over the reels and slashes reels wild. */
    private fun drawFeature(c: Canvas, t: Double) {
        if (featureStart < 0 || t < featureStart) return
        val bc = bigChars ?: return
        val inEnd = slashAt - 0.25; val outStart = slashAt + 0.2; val outEnd = slashAt + 0.6
        if (t > outEnd) return
        val q = when {
            t < inEnd -> ((t - featureStart) / (inEnd - featureStart)).toFloat().coerceIn(0f, 1f)
            t < outStart -> 1f
            else -> 1f - ((t - outStart) / (outEnd - outStart)).toFloat().coerceIn(0f, 1f)
        }
        val e = q * q * (3 - 2 * q)
        val scale = 1f + 0.9f * e
        val cx = rightChar.centerX() + (reelRect.centerX() - rightChar.centerX()) * e
        val cy = rightChar.centerY() + (reelRect.top - rightChar.height() * 0.1f - rightChar.centerY()) * e
        val w = rightChar.width() * scale; val h = rightChar.height() * scale
        // shadow aura
        pFill.shader = RadialGradient(cx, cy, w.coerceAtLeast(1f), intArrayOf(0xAA000000.toInt(), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, w, pFill); pFill.shader = null
        val swing = if (t in (slashAt - 0.12)..(slashAt + 0.15)) ((t - slashAt + 0.12) / 0.27 * 50 - 25).toFloat() else -10f * e
        touchQ = q; touchSwing = swing
        if (lay3d == null) {
        c.save()
        c.rotate(swing, cx, cy + h * 0.2f)
        c.translate(cx - w / 2, cy - h / 2); c.scale(scale, scale)
        bc.draw(c, ReaperConfig.WILD, t, 0.71, Characters.EXCITED)
        c.restore()
        }
        // slash streak across the wild reels
        val se = t - slashAt
        if (se in 0.0..0.45) {
            val a = (1 - se / 0.45).toFloat()
            for (r in outcome?.wildReels ?: IntArray(0)) {
                val l = reelRect.left + r * cell
                pStroke.color = Neon.GREEN; pStroke.alpha = (255 * a).toInt(); pStroke.strokeWidth = 10 * u
                c.drawLine(l - 10 * u, reelRect.bottom, l + cell + 10 * u, reelRect.top, pStroke)
                pStroke.color = Color.WHITE; pStroke.strokeWidth = 3 * u
                c.drawLine(l - 10 * u, reelRect.bottom, l + cell + 10 * u, reelRect.top, pStroke)
            }
            pStroke.alpha = 255
        }
        if (t < slashAt + 0.4) {
            pText.typeface = serif; pText.textSize = 44 * u
            pText.color = Neon.GREEN; pText.alpha = (255 * e).toInt()
            pText.setShadowLayer(18 * u, 0f, 0f, Neon.GREEN)
            c.drawText("REAPER'S TOUCH", reelRect.centerX(), reelRect.bottom - 30 * u, pText)
            pText.clearShadowLayer(); pText.alpha = 255
        }
    }

    private fun meterLabel(c: Canvas, r: RectF, label: String) {
        pText.typeface = cond; pText.textSize = r.height() * 0.17f; pText.color = 0xFF9CCC65.toInt()
        c.drawText(label, r.centerX(), r.top + r.height() * 0.22f, pText)
    }

    private fun fit(text: String, maxW: Float, size: Float) {
        pText.textSize = size; val w = pText.measureText(text); if (w > maxW) pText.textSize = size * maxW / w
    }

    private fun drawPanel(c: Canvas, t: Double) {
        val shown = (wallet.balance - pendingWin - pendingVault).coerceAtLeast(0)
        meterLabel(c, creditRect, "CREDIT")
        val flash = t < creditFlashUntil && ((t * 8).toInt() % 2 == 0)
        pText.typeface = serif; pText.color = if (flash) Neon.RED else Color.WHITE
        fit(Money.fmt(shown), creditRect.width() * 0.9f, creditRect.height() * 0.34f)
        c.drawText(Money.fmt(shown), creditRect.centerX(), creditRect.centerY() + creditRect.height() * 0.12f, pText)
        pText.typeface = cond; pText.color = 0xFF8A9A80.toInt()
        val sub = if (shown >= 10) "TOKENS • ${Money.short(shown)}" else "TOKENS"
        fit(sub, creditRect.width() * 0.9f, creditRect.height() * 0.14f)
        c.drawText(sub, creditRect.centerX(), creditRect.bottom - creditRect.height() * 0.1f, pText)

        val o = outcome
        meterLabel(c, winRect, if ((o?.mini?.multiplier ?: 1) > 1 && phase != Phase.SPINNING) "WIN  ×${o!!.mini.multiplier}" else "WIN")
        val wv = if (phase == Phase.SPINNING) 0L else rollValue(t)
        pText.typeface = serif; pText.color = if (wv > 0) Neon.GREEN else 0xFF3A4A3A.toInt()
        fit(Money.fmt(wv), winRect.width() * 0.9f, winRect.height() * 0.38f)
        if (wv > 0) pText.setShadowLayer(12 * u, 0f, 0f, Neon.GREEN)
        c.drawText(Money.fmt(wv), winRect.centerX(), winRect.centerY() + winRect.height() * 0.13f, pText)
        pText.clearShadowLayer()
        pText.typeface = cond; pText.color = 0xFF8A9A80.toInt()
        val wsub = when {
            o != null && o.capped && phase != Phase.SPINNING -> "TOKENS • 25-BOOK MAX WIN"
            wv >= 10 -> "TOKENS • ${Money.short(wv)}"
            else -> "TOKENS"
        }
        fit(wsub, winRect.width() * 0.9f, winRect.height() * 0.14f)
        c.drawText(wsub, winRect.centerX(), winRect.bottom - winRect.height() * 0.1f, pText)
    }

    private fun drawButtons(c: Canvas, t: Double) {
        for (b in buttons) {
            b.enabled = when (b.id) {
                "stake" -> !busy
                "deposit", "withdraw" -> !busy && autoLeft == 0
                else -> true
            }
            when {
                b.round -> drawSpin(c, b, t)
                b.id == "stake" -> drawStake(c, b)
                else -> drawRectBtn(c, b)
            }
        }
    }

    private fun drawStake(c: Canvas, b: Btn) {
        val r = b.rect
        val down = pressed === b
        tmp.set(r); if (down) tmp.offset(0f, 3 * u)
        pFill.shader = LinearGradient(0f, tmp.top, 0f, tmp.bottom, 0xFF2A0610.toInt(), 0xFF0A0003.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(tmp, 14 * u, 14 * u, pFill); pFill.shader = null
        path.reset(); path.addRoundRect(tmp, 14 * u, 14 * u, Path.Direction.CW)
        Art.neonStroke(c, path, if (b.enabled) Neon.RED else Neon.RED_DIM, 2f * u)
        pText.typeface = cond; pText.textSize = tmp.height() * 0.17f; pText.color = 0xFFFF8A80.toInt()
        c.drawText("STAKE  ▸", tmp.centerX(), tmp.top + tmp.height() * 0.22f, pText)
        pText.typeface = serif; pText.color = Color.WHITE; pText.textSize = tmp.height() * 0.4f
        pText.setShadowLayer(10 * u, 0f, 0f, Neon.RED)
        c.drawText("${wallet.stake}", tmp.centerX(), tmp.centerY() + tmp.height() * 0.16f, pText)
        pText.clearShadowLayer()
        pText.typeface = cond; pText.textSize = tmp.height() * 0.13f; pText.color = 0xFFB0A0A0.toInt()
        c.drawText(if (b.enabled) "TOKENS • TAP TO CHANGE" else "TOKENS", tmp.centerX(), tmp.bottom - tmp.height() * 0.09f, pText)
    }

    private fun drawRectBtn(c: Canvas, b: Btn) {
        val r = b.rect
        tmp.set(r); if (pressed === b) tmp.offset(0f, 2 * u)
        val col = when {
            !b.enabled -> 0xFF333333.toInt()
            b.id == "deposit" -> Neon.GREEN
            b.id == "withdraw" -> Neon.RED
            b.id == "auto" && autoLeft != 0 -> Neon.CYAN
            b.id == "turbo" && wallet.turbo -> Neon.ORANGE
            b.id == "sound" && !wallet.soundOn -> 0xFF555555.toInt()
            else -> Neon.PURPLE
        }
        val rad = tmp.height() * 0.3f
        pFill.color = 0xE0050505.toInt(); c.drawRoundRect(tmp, rad, rad, pFill)
        pStroke.color = col; pStroke.alpha = 90; pStroke.strokeWidth = 6 * u; c.drawRoundRect(tmp, rad, rad, pStroke)
        pStroke.alpha = 255; pStroke.strokeWidth = 2 * u; c.drawRoundRect(tmp, rad, rad, pStroke)
        val label = when (b.id) {
            "sound" -> when { wallet.soundOn && wallet.musicOn -> "SOUND"; wallet.soundOn -> "SFX ONLY"; else -> "MUTED" }
            "auto" -> if (autoLeft != 0) (if (autoLeft < 0) "AUTO ∞" else "AUTO $autoLeft") else "AUTO"
            else -> b.label
        }
        pText.typeface = cond; pText.color = if (b.enabled) Color.WHITE else 0xFF777777.toInt()
        fit(label, tmp.width() * 0.86f, tmp.height() * 0.42f)
        c.drawText(label, tmp.centerX(), tmp.centerY() - (pText.fontMetrics.ascent + pText.fontMetrics.descent) / 2, pText)
    }

    private fun drawSpin(c: Canvas, b: Btn, t: Double) {
        val r = b.rect
        val down = pressed === b
        val cx = r.centerX(); val cy = r.centerY() + if (down) 4 * u else 0f
        val rad = r.width() / 2
        val stop = phase == Phase.SPINNING || autoLeft != 0 || holdMode
        val locked = now() < winLockUntil
        val col = if (stop) Neon.RED else Neon.GREEN
        val ready = !busy && autoLeft == 0
        val glow = if (ready) (0.5 + 0.5 * sin(t * 3.5)).toFloat() else 0.35f
        // outer glow + chrome bezel
        pFill.shader = RadialGradient(cx, cy, rad * 1.3f, intArrayOf(col and 0x00FFFFFF or ((70 + 110 * glow).toInt() shl 24), 0x00000000), floatArrayOf(0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 1.3f, pFill)
        pFill.shader = LinearGradient(0f, cy - rad, 0f, cy + rad, intArrayOf(0xFFE0E0E0.toInt(), 0xFF5A5A5A.toInt(), 0xFF1A1A1A.toInt(), 0xFF8A8A8A.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, pFill)
        // dome: lit sphere cap
        val dome = if (stop) intArrayOf(0xFFFF8A80.toInt(), Neon.RED, 0xFF4A0010.toInt()) else intArrayOf(0xFFCCFF90.toInt(), 0xFF2E9E0A.toInt(), 0xFF0B2A04.toInt())
        pFill.shader = RadialGradient(cx - rad * 0.25f, cy - rad * 0.35f, rad * 1.15f, dome, floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * (if (down) 0.84f else 0.86f), pFill)
        // specular highlight
        pFill.shader = RadialGradient(cx - rad * 0.28f, cy - rad * 0.42f, rad * 0.45f, intArrayOf(0xBBFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawOval(cx - rad * 0.65f, cy - rad * 0.8f, cx + rad * 0.15f, cy - rad * 0.1f, pFill)
        pFill.shader = null
        pText.typeface = serif; pText.color = Color.WHITE; pText.textSize = rad * 0.36f
        pText.setShadowLayer(10 * u, 0f, 3 * u, 0xCC000000.toInt())
        val label = when { holdMode -> "RAPID"; stop -> "STOP"; locked -> "WIN"; else -> "SPIN" }
        c.drawText(label, cx, cy + rad * 0.02f - (pText.fontMetrics.ascent + pText.fontMetrics.descent) / 2, pText)
        pText.clearShadowLayer()
        pText.typeface = cond; pText.textSize = rad * 0.12f; pText.color = 0xDDFFFFFF.toInt()
        c.drawText(if (holdMode) "RELEASE TO STOP" else "HOLD FOR RAPID", cx, cy + rad * 0.5f, pText)
    }

    private fun drawMessage(c: Canvas, t: Double) {
        val m = message ?: return
        if (t > messageUntil) { message = null; return }
        pText.typeface = cond; pText.textSize = 30 * u
        val w = pText.measureText(m) + 60 * u
        val cy = reelRect.centerY()
        tmp.set(reelRect.centerX() - w / 2, cy - 32 * u, reelRect.centerX() + w / 2, cy + 32 * u)
        pFill.color = 0xE8000000.toInt(); c.drawRoundRect(tmp, 30 * u, 30 * u, pFill)
        path.reset(); path.addRoundRect(tmp, 30 * u, 30 * u, Path.Direction.CW)
        Art.neonStroke(c, path, Neon.GREEN, 1.5f * u)
        pText.color = Color.WHITE
        c.drawText(m, reelRect.centerX(), cy + 10 * u, pText)
    }

    private val tierNames = arrayOf("", "BIG WIN", "HUGE WIN", "MASSIVE WIN", "DEADLY WIN")

    private fun drawBigWin(c: Canvas, t: Double) {
        val e = t - phaseStart
        pFill.color = Color.argb((min(1.0, e * 3) * (if (lay3d != null) 90 else 170)).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        // the reaper rises, enlarged, behind the text
        val bc = bigChars
        if (bc != null && lay3d == null) {
            // the reaper rises, enlarged and laughing, behind the text
            val rise = min(1.0, e / 1.2).toFloat()
            val ease = rise * rise * (3 - 2 * rise)
            val h = height * 0.95f; val sc = h / bc.h
            val w = bc.w * sc
            val top = height - h * ease * 0.92f
            c.save(); c.translate(width / 2f - w / 2, top); c.scale(sc, sc)
            bc.draw(c, ReaperConfig.WILD, t, 0.71, Characters.EXCITED, (200 * ease).toInt())
            c.restore()
        }
        val q = ((t - rollStart) / rollDur).coerceIn(0.0, 1.0)
        val shownTier = ceil(tier * min(1.0, q * 1.15 + 0.01)).toInt().coerceIn(1, tier)
        val name = tierNames[shownTier]
        val bounce = (1 + 0.1 * sin(e * 7) * (1 - min(1.0, e / 6))).toFloat() * min(1.0, e * 2.5).toFloat()
        c.save(); c.scale(bounce, bounce, width / 2f, height * 0.45f)
        pText.typeface = serif; pText.textSize = height * 0.14f
        val col = if (shownTier >= 3) Neon.RED else Neon.GREEN
        pText.color = col; pText.setShadowLayer(30 * u, 0f, 0f, col)
        c.drawText(name, width / 2f, height * 0.48f, pText)
        pText.clearShadowLayer()
        pText.style = Paint.Style.STROKE; pText.strokeWidth = 2 * u; pText.color = Color.WHITE
        c.drawText(name, width / 2f, height * 0.48f, pText); pText.style = Paint.Style.FILL
        c.restore()
        val v = rollValue(t)
        pText.typeface = serif; pText.textSize = height * 0.1f; pText.color = Color.WHITE
        pText.setShadowLayer(20 * u, 0f, 0f, Neon.GREEN)
        c.drawText(Money.fmt(v), width / 2f, height * 0.63f, pText); pText.clearShadowLayer()
        pText.typeface = cond; pText.textSize = height * 0.04f; pText.color = 0xFFCCFF90.toInt()
        c.drawText("TOKENS  •  ${Money.describe(v)}", width / 2f, height * 0.7f, pText)
        if (outcome?.capped == true && q >= 1) {
            pText.color = Neon.RED; c.drawText("25-BOOK MAXIMUM WIN", width / 2f, height * 0.76f, pText)
        }
    }

    /** A 5-of-a-kind character win: the character looms out of the reels, laughing. */
    private fun drawShowcase(c: Canvas, t: Double) {
        val ch = chars ?: return
        val sym = showcaseSym; if (sym < 0) return
        val e = (t - phaseStart).toFloat()
        val grow = min(1f, e / 0.45f)
        val fade = if (e > 1.6f) 1 - (e - 1.6f) / 0.4f else 1f
        pFill.color = Color.argb((150 * grow * fade).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        val sc = 1 + 2.2f * grow
        val size = cell * sc
        if (lay3d == null) {
            c.save(); c.translate(width / 2f - size / 2, reelRect.centerY() - size / 2); c.scale(sc, sc)
            ch.draw(c, sym, t, 0.0, Characters.EXCITED, (255 * fade).toInt())
            c.restore()
        }
        pText.typeface = serif; pText.textSize = 40 * u; pText.color = Neon.RED; pText.alpha = (255 * fade).toInt()
        pText.setShadowLayer(16 * u, 0f, 0f, Neon.RED)
        c.drawText("5 × ${ReaperConfig.SYMBOL_NAMES[sym].uppercase()}", width / 2f, reelRect.centerY() + size / 2 + 10 * u, pText)
        pText.clearShadowLayer(); pText.alpha = 255
    }

    /** Big, clear win amount over the reels for every winning spin: "WIN  8  TOKENS". */
    private fun drawWinBanner(c: Canvas, t: Double) {
        val o = outcome ?: return
        if (phase != Phase.PRESENT || o.payTokens <= 0 || vaultWinShown != 0L) return
        val e = (t - presentStart).toFloat()
        val hold = bannerHold.toFloat()
        if (e > hold + 0.35f) return
        val inQ = min(1f, e / 0.25f)
        val pop = if (e < 0.25f) 0.5f + 0.7f * inQ else if (e < 0.4f) 1.2f - (e - 0.25f) / 0.15f * 0.2f else 1f
        val outQ = if (e > hold) 1f - (e - hold) / 0.35f else 1f
        val a = (255 * outQ).toInt().coerceIn(0, 255)
        val v = rollValue(t)
        val counting = t - rollStart < rollDur
        val beat = if (!counting && t - rollStart < rollDur + 0.4) 1f + 0.12f * sin(((t - rollStart - rollDur) / 0.4 * PI)).toFloat() else 1f
        c.save(); c.scale(pop, pop, reelRect.centerX(), reelRect.centerY())
        val bw = reelRect.width() * 0.6f; val bh = reelRect.height() * 0.6f
        tmp.set(reelRect.centerX() - bw / 2, reelRect.centerY() - bh / 2, reelRect.centerX() + bw / 2, reelRect.centerY() + bh / 2)
        pFill.color = Color.argb((a * 0.88f).toInt(), 0, 0, 0)
        c.drawRoundRect(tmp, 28 * u, 28 * u, pFill)
        path.reset(); path.addRoundRect(tmp, 28 * u, 28 * u, Path.Direction.CW)
        pStroke.color = Neon.GREEN; pStroke.alpha = a; pStroke.strokeWidth = 5 * u
        c.drawPath(path, pStroke)
        pText.typeface = serif; pText.textAlign = Paint.Align.CENTER
        pText.textSize = bh * 0.16f; pText.color = Neon.GREEN; pText.alpha = a
        c.drawText("YOU WIN", tmp.centerX(), tmp.top + bh * 0.2f, pText)
        c.save(); c.scale(beat, beat, tmp.centerX(), tmp.top + bh * 0.55f)
        pText.color = Color.WHITE; pText.alpha = a
        fit(Money.fmt(v), bw * 0.9f, bh * 0.5f)
        pText.setShadowLayer(22 * u, 0f, 0f, Neon.GREEN)
        c.drawText(Money.fmt(v), tmp.centerX(), tmp.top + bh * 0.72f, pText)
        pText.clearShadowLayer()
        c.restore()
        pText.typeface = serif; pText.textSize = bh * 0.16f; pText.color = 0xFFCCFF90.toInt(); pText.alpha = a
        c.drawText(if (v == 1L) "TOKEN" else "TOKENS", tmp.centerX(), tmp.top + bh * 0.93f, pText)
        pText.alpha = 255
        c.restore()
    }

    /** The mini-feature character floats out in front of the reels; coins/wilds fly from it to the cells. */
    private fun drawMini(c: Canvas, t: Double) {
        val bc = bigChars ?: return
        val ch = chars ?: return
        val o = outcome ?: return
        val type = miniType
        if (type == ReaperConfig.MINI_NONE || type == ReaperConfig.MINI_REAPER || miniStart < 0 || t < miniStart) return
        val leaving = if (phase == Phase.PRESENT) ((t - presentStart) / 0.6).coerceIn(0.0, 1.0).toFloat() else 0f
        if (leaving >= 1f || (phase != Phase.SPINNING && phase != Phase.MINI_ACTION && phase != Phase.PRESENT)) return
        val inQ = ((t - miniStart) / 0.9).coerceIn(0.0, 1.0).toFloat()
        val ease = inQ * inQ * (3 - 2 * inQ)
        // flies from the left side to hover above the middle of the reels, growing toward the viewer
        // front-and-centre while the reels spin, then it lifts up over the top row to do its work
        val lift = if (phase == Phase.SPINNING) 0f else ((t - (if (phase == Phase.MINI_ACTION) actionStart else presentStart - 10)) / 0.4).coerceIn(0.0, 1.0).toFloat()
        val hx = reelRect.centerX()
        val hy = reelRect.centerY() + (reelRect.top + bc.h * 0.12f - reelRect.centerY()) * lift
        val sx = leftChar.centerX(); val sy = leftChar.centerY()
        val cx = sx + (hx - sx) * ease + sin(t * 2.2).toFloat() * 12 * u
        val cy = sy + (hy - sy) * ease + sin(t * 3.1).toFloat() * 8 * u - leaving * height * 0.6f
        val scale = (0.6f + (1.25f - 0.45f * lift) * ease) * (1f - leaving * 0.4f)
        miniPose[0] = cx; miniPose[1] = cy; miniPose[2] = scale; miniPoseValid = true
        if (lay3d != null) { drawMiniTitle(c, type, ease); return }
        val w = bc.w * scale; val h = bc.h * scale
        // dark aura + shadow on the reels (depth)
        pFill.color = 0x66000000
        c.drawOval(cx - w * 0.4f, reelRect.top + 10 * u, cx + w * 0.4f, reelRect.top + 40 * u, pFill)
        c.save(); c.translate(cx - w / 2, cy - h / 2); c.scale(scale, scale)
        bc.draw(c, miniSymbol(type), t, 0.4, Characters.EXCITED)
        c.restore()
        // title
        if (phase == Phase.SPINNING || phase == Phase.MINI_ACTION) {
            pText.typeface = serif; pText.textSize = 46 * u; pText.textAlign = Paint.Align.CENTER
            val col = when (type) { ReaperConfig.MINI_RAVEN -> Neon.PURPLE; ReaperConfig.MINI_PUMPKIN -> Neon.ORANGE; else -> Neon.GREEN }
            pText.color = col; pText.alpha = (255 * ease).toInt()
            pText.setShadowLayer(20 * u, 0f, 0f, col)
            c.drawText(ReaperConfig.MINI_NAMES[type], reelRect.centerX(), reelRect.bottom - 26 * u, pText)
            pText.clearShadowLayer(); pText.alpha = 255
        }
        // projectiles in flight toward their cells
        if (phase == Phase.MINI_ACTION) {
            val cells = actionCells(o)
            val e = t - actionStart
            for ((k, cell) in cells.withIndex()) {
                val t0 = 0.5 + k * actionGap
                val q = ((e - t0) / 0.3).toFloat()
                if (q < 0f || q > 1f) continue
                val tx = cellX(cell / ROWS); val ty = rowY((cell % ROWS).toDouble())
                val px0 = cx + (tx - cx) * q; val py0 = cy + (ty - cy) * q - sin(q * PI).toFloat() * 60 * u
                if (type == ReaperConfig.MINI_PUMPKIN) ch.coin(c, px0, py0, this.cell * 0.18f, "", sin(t * 20).toFloat())
                else {
                    pFill.color = Neon.GREEN; pFill.alpha = 90; c.drawCircle(px0, py0, this.cell * 0.2f, pFill)
                    pFill.alpha = 255; pFill.color = Color.WHITE; c.drawCircle(px0, py0, this.cell * 0.06f, pFill)
                }
            }
        }
    }

    /** Writes this frame's game state into the 3D world's shared [SceneState]. */
    private fun publishScene(t: Double) {
        val g = gl3d ?: return
        val s = g.state
        val o = outcome
        val hl = highlight(t)
        val showcase = phase == Phase.SHOWCASE || phase == Phase.BIG_WIN
        val pulse = (1 + 0.06 * sin(t * 9)).toFloat()
        synchronized(g.lock) {
            for (r in 0 until REELS) {
                val strip = ReaperConfig.STRIPS[r]
                if (phase == Phase.SPINNING && !done[r]) {
                    val off = offset(r, t); val base = floor(off).toInt()
                    var n = 0
                    for (k in base - 1..base + 4) { if (n >= com.tjshea.neonreaper.gl.SceneState.FACES) break; s.faceSym[r][n] = tapeSym(r, k); s.facePos[r][n] = (k - off).toFloat(); n++ }
                    s.faceCount[r] = n
                    s.fast[r] = t > liftEnd[r] && t < runEnd[r]
                } else {
                    val stop = o?.stops?.get(r) ?: idleStops[r]
                    s.faceSym[r][0] = strip[Math.floorMod(stop - 1, strip.size)]; s.facePos[r][0] = -1f
                    for (row in 0 until ROWS) { s.faceSym[r][row + 1] = grid[r][row]; s.facePos[r][row + 1] = row.toFloat() }
                    s.faceSym[r][4] = strip[(stop + 3) % strip.size]; s.facePos[r][4] = 3f
                    s.faceCount[r] = 5; s.fast[r] = false
                }
                s.wildReel[r] = isWildReel[r] && phase != Phase.IDLE && (t >= slashAt || slashAt < 0) && play != null
                for (row in 0 until ROWS) {
                    val cell = r * ROWS + row
                    val lit = hl?.get(r)?.get(row) == true
                    val event = r == eventReel && row == eventRow && t < eventUntil
                    s.cellExcited[cell] = lit || event || (showcase && hl == null)
                    s.cellDim[cell] = if (hl != null && !lit) 0.35f else 1f
                    s.cellPop[cell] = when { lit -> pulse; event -> 1.12f; else -> 1f }
                    s.coin[cell] = if (coinShown[cell] && o != null) o.mini.coins[cell] else 0
                    val fl = (t - cellFlash[cell]).toFloat()
                    s.cellFlash[cell] = if (fl in 0f..0.5f) (0.5f - fl) / 0.5f else 0f
                }
            }
            val excited = phase == Phase.BIG_WIN || phase == Phase.SHOWCASE || phase == Phase.VAULT_TRIGGER ||
                (phase == Phase.PRESENT && (o?.payTokens ?: 0) > 0 && t - presentStart < 2.2)
            s.hoodedExcited = excited
            s.reaperExcited = excited || t < sideLaughUntil
            s.reaperAway = featureStart > 0 && t > featureStart && t < slashAt + 0.6
            // mini-feature character + projectiles
            if (miniPoseValid && o != null) {
                s.miniSymbol = miniSymbol(miniType); s.miniX = miniPose[0]; s.miniY = miniPose[1]; s.miniScale = miniPose[2]
                java.util.Arrays.fill(s.projCell, -1)
                if (phase == Phase.MINI_ACTION) {
                    val cells = actionCells(o); val e = t - actionStart
                    var n = 0
                    for ((k, cell) in cells.withIndex()) {
                        val q = ((e - (0.5 + k * actionGap)) / 0.3).toFloat()
                        if (q < 0f || q > 1f || n >= 6) continue
                        s.projCell[n] = cell; s.projQ[n] = q; n++
                    }
                }
                s.projCoin = miniType == ReaperConfig.MINI_PUMPKIN
            } else { s.miniSymbol = -1; java.util.Arrays.fill(s.projCell, -1) }
            s.reaperTouch = touchQ; s.reaperSwing = touchSwing
            s.bigWin = if (phase == Phase.BIG_WIN) min(1.0, (t - phaseStart) / 1.2).toFloat() else -1f
            if (phase == Phase.SHOWCASE && showcaseSym >= 0) {
                val e = (t - phaseStart).toFloat()
                s.showcaseSym = showcaseSym; s.showcaseGrow = min(1f, e / 0.45f) * (if (e > 1.6f) 1 - (e - 1.6f) / 0.4f else 1f)
            } else s.showcaseSym = -1
            s.flash = lightningLevel(t)
            s.souls = (soulRate / 80f).coerceIn(0f, 1f)
        }
    }

    private val miniPose = FloatArray(3)
    private var miniPoseValid = false

    private fun drawMiniTitle(c: Canvas, type: Int, ease: Float) {
        if (phase != Phase.SPINNING && phase != Phase.MINI_ACTION) return
        pText.typeface = serif; pText.textSize = 46 * u; pText.textAlign = Paint.Align.CENTER
        val col = when (type) { ReaperConfig.MINI_RAVEN -> Neon.PURPLE; ReaperConfig.MINI_PUMPKIN -> Neon.ORANGE; else -> Neon.GREEN }
        pText.color = col; pText.alpha = (255 * ease).toInt()
        pText.setShadowLayer(20 * u, 0f, 0f, col)
        c.drawText(ReaperConfig.MINI_NAMES[type], reelRect.centerX(), reelRect.bottom - 26 * u, pText)
        pText.clearShadowLayer(); pText.alpha = 255
    }

    /** Soul coins sitting on the reels, flashes where wilds/coins landed, and the skull's multiplier stamp. */
    private fun drawMiniEffects(c: Canvas, t: Double) {
        val ch = chars ?: return
        val o = outcome ?: return
        if (play == null || phase == Phase.SPINNING || phase == Phase.IDLE) return
        for (cell in 0 until REELS * ROWS) {
            val x = cellX(cell / ROWS); val y = rowY((cell % ROWS).toDouble())
            val fl = (t - cellFlash[cell]).toFloat()
            if (fl in 0f..0.5f && lay3d == null) {
                pFill.color = Color.argb(((0.5f - fl) / 0.5f * 220).toInt(), 200, 255, 160)
                c.drawCircle(x, y, this.cell * (0.3f + fl), pFill)
            }
            if (coinShown[cell]) {
                val bounce = if (fl in 0f..0.35f) 1.3f - fl else 1f
                if (lay3d == null) ch.coin(c, x, y + this.cell * 0.12f, this.cell * 0.27f * bounce, Money.fmt(o.mini.coins[cell].toLong() * o.stake), 1f)
                else {
                    val label = Money.fmt(o.mini.coins[cell].toLong() * o.stake)
                    pText.typeface = serif; pText.textSize = cellW() * 0.2f * bounce; pText.color = Color.WHITE
                    pText.setShadowLayer(8 * u, 0f, 0f, Color.BLACK)
                    c.drawText(label, x, y + cellW() * 0.08f, pText); pText.clearShadowLayer()
                }
            }
        }
        if (multStampAt > 0) {
            val e = (t - multStampAt).toFloat()
            val s = if (e < 0.2f) 2.2f - e / 0.2f * 1.2f else 1f
            val x = reelRect.centerX(); val y = reelRect.top + reelRect.height() * 0.2f
            c.save(); c.scale(s, s, x, y)
            pText.typeface = serif; pText.textSize = 72 * u; pText.color = Neon.RED
            pText.setShadowLayer(24 * u, 0f, 0f, Neon.RED)
            if (phase == Phase.MINI_ACTION || e < 2.5f) c.drawText("×${o.mini.multiplier}", x, y + 24 * u, pText)
            pText.clearShadowLayer()
            c.restore()
        }
    }

    private fun drawSky(c: Canvas, t: Double) {
        val cl = cloud ?: return
        val span = width + cl.width
        for (k in 0 until 2) {
            val x = (((t * (9 + k * 5) * u) + k * span * 0.5) % span).toFloat() - cl.width
            pBmp.alpha = 200 - k * 60
            c.drawBitmap(cl, x, height * (0.08f + k * 0.12f), pBmp)
        }
        pBmp.alpha = 255
        // fireflies / wandering souls
        for (i in 0 until 14) {
            val ph = i * 1.7
            val fx0 = ((sin(t * 0.13 + ph) * 0.5 + 0.5) * width).toFloat()
            val fy0 = (height * (0.45 + 0.25 * sin(t * 0.21 + ph * 1.3))).toFloat()
            val tw = (0.5 + 0.5 * sin(t * 3 + ph * 2)).toFloat()
            pFill.color = Neon.GREEN; pFill.alpha = (50 * tw).toInt()
            c.drawCircle(fx0, fy0, 7 * u, pFill)
            pFill.alpha = (220 * tw).toInt()
            c.drawCircle(fx0, fy0, 1.8f * u, pFill)
        }
        pFill.alpha = 255
    }

    private fun drawVaultTrigger(c: Canvas, t: Double) {
        val e = (t - phaseStart).toFloat()
        val q = (e / 3.2f).coerceIn(0f, 1f)
        pFill.color = Color.argb((220 * q).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        val cx = width / 2f; val cy = height * 0.46f
        // relics spiral into a portal
        val lit = relicLit ?: return
        val gather = (e / 1.6f).coerceIn(0f, 1f)
        for (i in 0 until 5) {
            val sr = relicSlots[i]
            val ang = i * 2 * PI / 5 + e * 2.5
            val rad = (1 - gather) * 0f + gather * (1 - ((e - 1.6f) / 1.2f).coerceIn(0f, 1f)) * height * 0.22f
            val x = sr.centerX() + (cx + (rad * cos(ang)).toFloat() - sr.centerX()) * gather
            val y = sr.centerY() + (cy + (rad * sin(ang)).toFloat() - sr.centerY()) * gather
            val s = sr.width() * (1 + gather)
            tmp.set(x - s / 2, y - s / 2, x + s / 2, y + s / 2)
            c.drawBitmap(lit[i], null, tmp, pBmp)
        }
        val portal = ((e - 1.4f) / 1.6f).coerceIn(0f, 1f)
        if (portal > 0) {
            val pr = portal * max(width, height) * 0.6f
            pFill.shader = RadialGradient(cx, cy, pr.coerceAtLeast(1f), intArrayOf(0xFF000000.toInt(), 0xFF0A2A04.toInt(), Neon.GREEN, 0x00000000), floatArrayOf(0f, 0.6f, 0.85f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, pr, pFill); pFill.shader = null
        }
        pText.typeface = serif; pText.textSize = height * 0.09f
        pText.color = Neon.GREEN; pText.alpha = (255 * min(1f, e * 2)).toInt()
        pText.setShadowLayer(26 * u, 0f, 0f, Neon.GREEN)
        c.drawText("THE SOUL VAULT", cx, height * 0.18f, pText)
        pText.textSize = height * 0.045f; pText.color = Neon.RED; pText.setShadowLayer(14 * u, 0f, 0f, Neon.RED)
        c.drawText("ALL FIVE RELICS COLLECTED", cx, height * 0.25f, pText)
        pText.clearShadowLayer(); pText.alpha = 255
        if (e > 2.9f) { pFill.color = Color.argb(((e - 2.9f) / 0.3f * 255).toInt().coerceIn(0, 255), 255, 255, 255); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill) }
    }

    // ================================================================== particles
    private fun updateParticles(dt: Float) {
        if (soulRate > 0) { soulAcc += soulRate * dt; while (soulAcc >= 1) { soulAcc -= 1; spawn() } }
        var alive = 0
        for (i in 0 until maxP) {
            if (plife[i] <= 0) continue
            plife[i] -= dt
            pvx[i] += (fx.nextFloat() - 0.5f) * 60 * u * dt
            px[i] += pvx[i] * dt; py[i] += pvy[i] * dt
            if (plife[i] > 0) alive++
        }
        particleCount = alive
    }

    private fun spawn() {
        for (i in 0 until maxP) if (plife[i] <= 0) {
            pkind[i] = if (fx.nextFloat() < 0.3f) 1 else 0
            px[i] = width * (0.15f + fx.nextFloat() * 0.7f); py[i] = height * (0.85f + fx.nextFloat() * 0.1f)
            pvx[i] = (fx.nextFloat() - 0.5f) * 40 * u; pvy[i] = -(120 + fx.nextFloat() * 220) * u
            plife[i] = 2.5f + fx.nextFloat() * 1.5f; pmax[i] = plife[i]
            return
        }
    }

    private fun drawParticles(c: Canvas) {
        if (particleCount == 0) return
        for (i in 0 until maxP) {
            if (plife[i] <= 0) continue
            val a = (plife[i] / pmax[i]).coerceIn(0f, 1f)
            val col = if (pkind[i] == 1) Neon.RED else Neon.GREEN
            val r = (if (pkind[i] == 1) 3f else 7f) * u
            pFill.color = col; pFill.alpha = (60 * a).toInt()
            c.drawCircle(px[i], py[i], r * 2.6f, pFill)
            pFill.alpha = (230 * a).toInt()
            c.drawCircle(px[i], py[i], r, pFill)
        }
        pFill.alpha = 255
    }

}

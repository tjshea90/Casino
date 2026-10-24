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

    private enum class Phase { IDLE, SPINNING, PRESENT, SHOWCASE, BIG_WIN, VAULT_TRIGGER, IN_VAULT }

    private val wallet get() = host.wallet
    private val audio get() = host.audio
    private val fx = Random()

    // ------------------------------------------------------------------ game state
    private var phase = Phase.IDLE
    private var grid: Array<IntArray> = ReaperEngine.gridFor(IntArray(REELS) { fx.nextInt(ReaperConfig.STRIPS[it].size) })
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
    private var symbols: Array<Bitmap>? = null
    private var blurred: Array<Bitmap>? = null
    private var bg: Bitmap? = null
    private var fog: Bitmap? = null
    private var hoodBmp: Bitmap? = null
    private var reaperBmp: Bitmap? = null
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

    init { isClickable = true }

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
        cell = floor(min(availH / ROWS, availW / REELS))
        val rw = cell * REELS; val rh = cell * ROWS
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
        val spinR = ph * 0.42f
        val spinCx = panelRect.right - spinR - 14 * u
        buttons += Btn("spin", "SPIN", RectF(spinCx - spinR, panelRect.centerY() - spinR, spinCx + spinR, panelRect.centerY() + spinR), round = true)
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
        symbols = Array(ReaperConfig.SYMBOL_COUNT) { Art.symbol(it, cs) }
        blurred = Array(ReaperConfig.SYMBOL_COUNT) { motionBlur(symbols!![it]) }
        bg = Bitmap.createBitmap(w.toInt(), h.toInt(), Bitmap.Config.ARGB_8888).also { paintBackground(Canvas(it), w, h) }
        fog = makeFog((w * 0.6f).toInt().coerceAtLeast(64), (h * 0.18f).toInt().coerceAtLeast(16))
        hoodBmp = Art.bitmap(leftChar.width().toInt().coerceAtLeast(8), leftChar.height().toInt().coerceAtLeast(8)) { c, _ ->
            Art.hooded(c, 0f, 0f, leftChar.width(), leftChar.height())
        }
        reaperBmp = Art.bitmap(rightChar.width().toInt().coerceAtLeast(8), rightChar.height().toInt().coerceAtLeast(8)) { c, _ ->
            Art.reaper(c, 0f, 0f, rightChar.width(), rightChar.height())
        }
        val rs = relicSlots[0].width().toInt().coerceAtLeast(8)
        relicLit = Array(5) { Art.relic(it, rs, true) }
        relicDark = Array(5) { Art.relic(it, rs, false) }
        shadeTop = LinearGradient(0f, reelRect.top, 0f, reelRect.top + cell * 0.5f, 0xCC000000.toInt(), 0, Shader.TileMode.CLAMP)
        shadeBot = LinearGradient(0f, reelRect.bottom - cell * 0.5f, 0f, reelRect.bottom, 0, 0xCC000000.toInt(), Shader.TileMode.CLAMP)
    }

    private fun motionBlur(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out); val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (i in 0 until 6) { p.alpha = 80; c.drawBitmap(src, 0f, (i - 3) * src.height * 0.05f, p) }
        return out
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

    private fun paintBackground(c: Canvas, w: Float, h: Float) {
        Art.graveyard(c, w, h, Neon.RED)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
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

    private fun recycleBitmaps() {
        symbols?.forEach { it.recycle() }; blurred?.forEach { it.recycle() }
        relicLit?.forEach { it.recycle() }; relicDark?.forEach { it.recycle() }
        bg?.recycle(); fog?.recycle(); hoodBmp?.recycle(); reaperBmp?.recycle()
    }

    fun release() = recycleBitmaps()

    // ================================================================== flow
    fun onWalletChanged() { invalidate() }

    fun showMessage(text: String, sec: Double = 2.6) { message = text; messageUntil = now() + sec; invalidate() }

    fun onHostPause() { autoLeft = 0 }

    private fun enter(p: Phase) { phase = p; phaseStart = now(); invalidate() }

    private val busy get() = phase != Phase.IDLE && phase != Phase.PRESENT

    private fun startSpin() {
        if (busy) return
        val stake = wallet.stake
        if (!wallet.canAfford(stake)) {
            autoLeft = 0; audio.play(Sfx.ERROR); creditFlashUntil = now() + 1.5
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
        audio.play(Sfx.SPIN, 0.6f)
        val t = now()
        val turbo = wallet.turbo
        val base = if (turbo) 0.32 else 0.6
        val step = if (turbo) 0.1 else 0.2
        val feature = o.feature
        featureStart = if (feature) t + 0.3 else -1.0
        slashAt = if (feature) t + 1.05 else -1.0
        slashPlayed = false
        if (feature) audio.play(Sfx.GROWL, 0.9f)
        for (r in 0 until REELS) {
            isWildReel[r] = r in o.wildReels
            val stopAt = when {
                isWildReel[r] -> slashAt
                feature -> t + base + step * r + 1.25
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
                for (row in 0 until ROWS) grid[r][row] = o.grid[r][row]
                tape[r] = null
            }
        }
        if (done.all { it }) onStopped(o)
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
            rollDur = if (tier > 0) 2.4 + tier * 1.2 else (0.5 + 0.4 * ln(1 + x)).coerceAtMost(2.2)
            when {
                tier > 0 -> { audio.play(Sfx.BIG_WIN); if (tier >= 2) audio.play(Sfx.GROWL); soulRate = 30f + tier * 20f; shakeUntil = t + 0.6; enter(Phase.BIG_WIN); return }
                showcaseSym >= 0 -> { audio.play(Sfx.GROWL); audio.play(Sfx.WIN_MED); enter(Phase.SHOWCASE); return }
                else -> audio.play(if (x >= 3) Sfx.WIN_MED else Sfx.WIN, 0.8f)
            }
            if (x >= 4) soulRate = 18f
        } else {
            rollTarget = 0
            pendingWin = 0
        }
        enter(Phase.PRESENT)
        schedule()
    }

    private fun schedule() {
        val o = outcome ?: return
        val delay = if (o.payTokens > 0) max(1.0, rollDur + 0.5) else if (wallet.turbo) 0.25 else 0.45
        nextAt = now() + delay
    }

    private fun rollValue(t: Double): Long {
        if (rollTarget == 0L) return 0
        val q = ((t - rollStart) / rollDur).coerceIn(0.0, 1.0)
        return if (q >= 1) rollTarget else Money.floorToMack((rollTarget * q).toLong())
    }

    private fun settleRoll() { rollStart = now() - rollDur - 1; pendingWin = 0; soulRate = 0f }

    private fun relicBusy(t: Double) = relicFly >= 0 && t < relicStart + relicDur

    private fun updateFlow(t: Double) {
        if (rollTarget > 0 && t - rollStart < rollDur) {
            if (t - lastTick > 0.08) { lastTick = t; audio.play(Sfx.TICK, 0.3f, 0.9f + 0.4f * ((t - rollStart) / rollDur).toFloat()) }
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
            Phase.BIG_WIN -> if (t - rollStart > rollDur + 2.0) { soulRate = 0f; enter(Phase.PRESENT); presentStart = t; nextAt = t + 0.5 }
            Phase.SHOWCASE -> if (t - phaseStart > 2.0) { enter(Phase.PRESENT); presentStart = t; schedule() }
            Phase.PRESENT -> if (t >= nextAt && !relicBusy(t)) {
                val v = play?.vault
                if (v != null && vaultWinShown == 0L) {
                    audio.play(Sfx.BELL); audio.play(Sfx.VAULT)
                    enter(Phase.VAULT_TRIGGER)
                } else if (autoLeft != 0) {
                    if (autoLeft > 0) autoLeft--
                    startSpin()
                }
            }
            Phase.IDLE -> if (autoLeft != 0 && t >= nextAt) { if (autoLeft > 0) autoLeft--; startSpin() }
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
            MotionEvent.ACTION_DOWN -> { pressed = buttons.firstOrNull { it.enabled && hit(it, e.x, e.y) }; invalidate() }
            MotionEvent.ACTION_MOVE -> pressed?.let { if (!hit(it, e.x, e.y)) { pressed = null; invalidate() } }
            MotionEvent.ACTION_UP -> {
                val p = pressed; pressed = null
                if (p != null && hit(p, e.x, e.y)) onButton(p.id) else onTap()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> pressed = null
        }
        return true
    }

    private fun hit(b: Btn, x: Float, y: Float): Boolean { tmp.set(b.rect); tmp.inset(-6 * u, -6 * u); return tmp.contains(x, y) }

    private fun onTap() {
        val t = now()
        when (phase) {
            Phase.SPINNING -> slamStop()
            Phase.BIG_WIN -> rollStart = if (t - rollStart < rollDur) t - rollDur else t - rollDur - 2.0
            Phase.SHOWCASE -> phaseStart = t - 2.0
            Phase.PRESENT -> if (t - rollStart < rollDur) rollStart = t - rollDur
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
        val sy = symbols ?: return
        val t = now()
        val dt = if (lastFrame == 0.0) 0.016 else (t - lastFrame).coerceIn(0.0, 0.05)
        lastFrame = t
        if (phase == Phase.SPINNING) updateSpin(t)
        updateFlow(t)
        ambient(t)

        canvas.save()
        if (t < shakeUntil) canvas.translate((fx.nextFloat() - 0.5f) * 10 * u, (fx.nextFloat() - 0.5f) * 8 * u)
        canvas.drawBitmap(bg!!, 0f, 0f, null)
        drawLightning(canvas, t)
        drawBats(canvas, t)
        drawFog(canvas, t, reelRect.bottom - (fog?.height ?: 0) * 0.5f, 18.0, 160)
        drawCharacters(canvas, t)
        drawTitle(canvas, t)
        drawRelics(canvas, t)
        drawReels(canvas, sy, t)
        drawWins(canvas, t)
        drawFeature(canvas, t)
        drawFog(canvas, t, reelRect.bottom - (fog?.height ?: 0) * 0.75f, -11.0, 90)
        drawPanel(canvas, t)
        drawButtons(canvas, t)
        canvas.restore()
        drawRelicFlight(canvas, t)
        updateParticles(dt.toFloat())
        drawParticles(canvas)
        when (phase) {
            Phase.BIG_WIN -> drawBigWin(canvas, t)
            Phase.SHOWCASE -> drawShowcase(canvas, t)
            Phase.VAULT_TRIGGER -> drawVaultTrigger(canvas, t)
            else -> {}
        }
        drawMessage(canvas, t)
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

    private fun drawLightning(c: Canvas, t: Double) {
        val e = t - lightningAt
        if (e < 0 || e > 0.6) return
        val flicker = if (e < 0.08 || (e > 0.16 && e < 0.24)) 1f else 0.35f
        val a = ((1 - e / 0.6) * flicker).toFloat()
        pFill.color = Color.argb((120 * a).toInt(), 200, 255, 220)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        pStroke.color = Color.argb((255 * a).toInt(), 230, 255, 240)
        pStroke.strokeWidth = 3 * u
        c.drawPath(bolt, pStroke)
        pStroke.color = Color.argb((90 * a).toInt(), 118, 255, 3); pStroke.strokeWidth = 12 * u
        c.drawPath(bolt, pStroke)
    }

    private fun drawBats(c: Canvas, t: Double) {
        val e = t - batsStart
        if (e < 0 || e > 6) return
        pStroke.color = 0xFF000000.toInt(); pStroke.strokeWidth = 3 * u
        for (i in 0 until 5) {
            val bx = (-60 * u + (e / 6 * (width + 120 * u)) - i * 45 * u).toFloat()
            val by = batsY + (sin(e * 3 + i) * 18 * u).toFloat() + (i % 2) * 22 * u
            val flap = sin(e * 22 + i * 1.3).toFloat()
            val s = (12 + (i % 3) * 3) * u
            path.reset()
            path.moveTo(bx - s, by - flap * s * 0.8f)
            path.quadTo(bx - s * 0.5f, by + s * 0.2f, bx, by)
            path.quadTo(bx + s * 0.5f, by + s * 0.2f, bx + s, by - flap * s * 0.8f)
            c.drawPath(path, pStroke)
            pFill.color = Neon.RED
            c.drawCircle(bx, by, 1.4f * u, pFill)
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
        val hb = hoodBmp ?: return
        val rb = reaperBmp ?: return
        val breathe = (1 + 0.012 * sin(t * 1.6)).toFloat()
        val excited = phase == Phase.BIG_WIN || phase == Phase.SHOWCASE || phase == Phase.VAULT_TRIGGER ||
            (phase == Phase.PRESENT && (outcome?.payTokens ?: 0) > 0 && t - presentStart < 2)
        val blink = t < blinkUntil
        // hooded watcher (left)
        c.save()
        c.scale(1f, breathe, leftChar.centerX(), leftChar.bottom)
        c.drawBitmap(hb, leftChar.left, leftChar.top, null)
        if (!blink) Art.eyes(c, leftChar.left + leftChar.width() * Art.HOOD_EYES[0], leftChar.left + leftChar.width() * Art.HOOD_EYES[1],
            leftChar.top + leftChar.height() * Art.HOOD_EYES[2], leftChar.width() * 0.022f, Neon.RED, if (excited) 1.6f else (0.8f + 0.2f * sin(t * 2).toFloat()))
        else { pFill.color = Color.BLACK; c.drawRect(leftChar.left + leftChar.width() * 0.36f, leftChar.top + leftChar.height() * 0.31f, leftChar.left + leftChar.width() * 0.64f, leftChar.top + leftChar.height() * 0.36f, pFill) }
        // candle flame
        Art.flame(c, leftChar.left + leftChar.width() * 0.5f, leftChar.top + leftChar.height() * 0.565f, leftChar.width() * 0.05f, Neon.GREEN, sin(t * 9).toFloat())
        c.restore()
        // reaper (right) — hidden while he is out swinging at the reels
        val away = featureStart > 0 && t > featureStart && t < slashAt + 0.6
        if (!away) {
            c.save()
            c.scale(1f, (1 + 0.012 * sin(t * 1.6 + 1)).toFloat(), rightChar.centerX(), rightChar.bottom)
            c.drawBitmap(rb, rightChar.left, rightChar.top, null)
            Art.eyes(c, rightChar.left + rightChar.width() * Art.REAPER_EYES[0], rightChar.left + rightChar.width() * Art.REAPER_EYES[1],
                rightChar.top + rightChar.height() * Art.REAPER_EYES[2], rightChar.width() * 0.02f, Neon.RED, if (excited) 1.6f else 0.9f)
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

    private fun drawReels(c: Canvas, sy: Array<Bitmap>, t: Double) {
        val bl = blurred ?: return
        c.save(); c.clipRect(reelRect)
        val hl = highlight(t)
        val pulse = (1 + 0.08 * sin(t * 9)).toFloat()
        for (r in 0 until REELS) {
            val left = reelRect.left + r * cell
            if (phase == Phase.SPINNING && !done[r]) {
                val off = offset(r, t)
                val fast = t > liftEnd[r] && t < runEnd[r]
                val base = floor(off).toInt()
                for (j in 0..3) {
                    val k = base + j
                    val y = reelRect.top + ((k - off) * cell).toFloat()
                    val s = tapeSym(r, k)
                    c.drawBitmap(if (fast) bl[s] else sy[s], left, y, pBmp)
                }
            } else {
                for (row in 0 until ROWS) {
                    val s = grid[r][row]; val y = reelRect.top + row * cell
                    val lit = hl?.get(r)?.get(row) == true
                    when {
                        hl != null && !lit -> { pBmp.alpha = 70; c.drawBitmap(sy[s], left, y, pBmp); pBmp.alpha = 255 }
                        lit -> { c.save(); c.scale(pulse, pulse, left + cell / 2, y + cell / 2); c.drawBitmap(sy[s], left, y, pBmp); c.restore() }
                        else -> c.drawBitmap(sy[s], left, y, pBmp)
                    }
                }
            }
            // wild reel green hellfire
            if (isWildReel[r] && (phase != Phase.IDLE) && (t >= slashAt || slashAt < 0) && play != null) drawHellfire(c, left, t)
        }
        pFill.shader = shadeTop; c.drawRect(reelRect.left, reelRect.top, reelRect.right, reelRect.top + cell * 0.5f, pFill)
        pFill.shader = shadeBot; c.drawRect(reelRect.left, reelRect.bottom - cell * 0.5f, reelRect.right, reelRect.bottom, pFill)
        pFill.shader = null
        c.restore()
        pFill.color = 0xFF0A120A.toInt()
        for (r in 1 until REELS) { val x = reelRect.left + r * cell; c.drawRect(x - 1.5f * u, reelRect.top, x + 1.5f * u, reelRect.bottom, pFill) }
    }

    private fun drawHellfire(c: Canvas, left: Float, t: Double) {
        val a = (90 + 60 * sin(t * 8)).toInt().coerceIn(0, 255)
        pFill.shader = LinearGradient(0f, reelRect.bottom, 0f, reelRect.top, intArrayOf(Color.argb(a, 118, 255, 3), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(left, reelRect.top, left + cell, reelRect.bottom, pFill); pFill.shader = null
        for (i in 0 until 5) {
            val ph = ((t * 1.3 + i * 0.21) % 1.0).toFloat()
            Art.flame(c, left + cell * (0.12f + i * 0.19f), reelRect.bottom - ph * cell * 0.4f, cell * 0.09f * (1 - ph * 0.5f), Neon.GREEN, sin(t * 11 + i).toFloat())
        }
        pStroke.color = Neon.GREEN; pStroke.strokeWidth = 4 * u; pStroke.alpha = a + 60
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
            path.moveTo(reelRect.left, reelRect.top + (line[0] + 0.5f) * cell)
            for (r in 0 until REELS) path.lineTo(reelRect.left + (r + 0.5f) * cell, reelRect.top + (line[r] + 0.5f) * cell)
            path.lineTo(reelRect.right, reelRect.top + (line[4] + 0.5f) * cell)
            pStroke.color = col; pStroke.alpha = 80; pStroke.strokeWidth = 14 * u; c.drawPath(path, pStroke)
            pStroke.alpha = 255; pStroke.strokeWidth = 4 * u; c.drawPath(path, pStroke)
            pStroke.color = Color.WHITE; pStroke.alpha = 200; pStroke.strokeWidth = 1.4f * u; c.drawPath(path, pStroke)
            pStroke.alpha = 255
            pStroke.color = col; pStroke.strokeWidth = 3 * u
            for (r in 0 until w.count) {
                val x = reelRect.left + r * cell; val y = reelRect.top + line[r] * cell
                tmp.set(x + 5 * u, y + 5 * u, x + cell - 5 * u, y + cell - 5 * u)
                c.drawRoundRect(tmp, 10 * u, 10 * u, pStroke)
            }
        }
        if (phase != Phase.PRESENT) return
        val cap = if (idx >= 0) {
            val w = o.lineWins[idx]
            "LINE ${w.line + 1}  •  ${w.count} × ${ReaperConfig.SYMBOL_NAMES[w.symbol].uppercase()}  •  ${w.mult}× STAKE = ${w.mult * o.stake} TOKENS"
        } else when {
            o.capped -> "MAXIMUM WIN — 25 BOOKS"
            o.roundedAway > 0 && o.payTokens == 0L -> "WIN ${o.rawTokens} TOKENS — ROUNDED DOWN TO 0 (WHOLE MACKS ONLY)"
            o.roundedAway > 0 -> "WIN ${o.rawTokens} TOKENS — ROUNDED DOWN TO ${o.payTokens}"
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

    /** Reaper's Touch: the reaper flies over the reels and slashes reels wild. */
    private fun drawFeature(c: Canvas, t: Double) {
        if (featureStart < 0 || t < featureStart) return
        val rb = reaperBmp ?: return
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
        c.save()
        val swing = if (t in (slashAt - 0.12)..(slashAt + 0.15)) ((t - slashAt + 0.12) / 0.27 * 50 - 25).toFloat() else -10f * e
        c.rotate(swing, cx, cy + h * 0.2f)
        tmp.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
        c.drawBitmap(rb, null, tmp, pBmp)
        Art.eyes(c, tmp.left + w * Art.REAPER_EYES[0], tmp.left + w * Art.REAPER_EYES[1], tmp.top + h * Art.REAPER_EYES[2], w * 0.022f, Neon.RED, 2f)
        c.restore()
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
        meterLabel(c, winRect, "WIN")
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
        val cx = r.centerX(); val cy = r.centerY() + if (pressed === b) 3 * u else 0f
        val rad = r.width() / 2
        val stop = phase == Phase.SPINNING || autoLeft != 0
        val col = if (stop) Neon.RED else Neon.GREEN
        val ready = !busy && autoLeft == 0
        val glow = if (ready) (0.5 + 0.5 * sin(t * 3.5)).toFloat() else 0.3f
        pFill.shader = RadialGradient(cx, cy, rad * 1.35f, intArrayOf(col and 0x00FFFFFF or ((90 + 100 * glow).toInt() shl 24), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 1.35f, pFill)
        pFill.shader = RadialGradient(cx, cy - rad * 0.3f, rad * 1.2f, intArrayOf(0xFF1C1C1C.toInt(), 0xFF000000.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, pFill); pFill.shader = null
        path.reset(); path.addCircle(cx, cy, rad * 0.92f, Path.Direction.CW)
        Art.neonStroke(c, path, col, 3 * u)
        // skull emblem behind text
        pFill.color = col; pFill.alpha = 40
        c.drawCircle(cx, cy, rad * 0.6f, pFill); pFill.alpha = 255
        pText.typeface = serif; pText.color = Color.WHITE; pText.textSize = rad * 0.42f
        pText.setShadowLayer(10 * u, 0f, 0f, col)
        c.drawText(if (stop) "STOP" else "SPIN", cx, cy - (pText.fontMetrics.ascent + pText.fontMetrics.descent) / 2, pText)
        pText.clearShadowLayer()
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
        pFill.color = Color.argb((min(1.0, e * 3) * 170).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        // the reaper rises, enlarged, behind the text
        val rb = reaperBmp
        if (rb != null) {
            val rise = min(1.0, e / 1.2).toFloat()
            val ease = rise * rise * (3 - 2 * rise)
            val h = height * 0.95f; val w = h * rb.width / rb.height
            val top = height - h * ease * 0.92f
            tmp.set(width / 2f - w / 2, top, width / 2f + w / 2, top + h)
            pBmp.alpha = (200 * ease).toInt()
            c.drawBitmap(rb, null, tmp, pBmp); pBmp.alpha = 255
            Art.eyes(c, tmp.left + w * Art.REAPER_EYES[0], tmp.left + w * Art.REAPER_EYES[1], tmp.top + h * Art.REAPER_EYES[2], w * 0.024f,
                Neon.RED, (1.4 + 0.5 * sin(e * 6)).toFloat())
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

    /** A 5-of-a-kind character win: the character looms out of the reels. */
    private fun drawShowcase(c: Canvas, t: Double) {
        val sy = symbols ?: return
        val s = showcaseSym; if (s < 0) return
        val e = (t - phaseStart).toFloat()
        val grow = min(1f, e / 0.45f)
        val fade = if (e > 1.6f) 1 - (e - 1.6f) / 0.4f else 1f
        pFill.color = Color.argb((150 * grow * fade).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        val size = cell * (1 + 2.2f * grow * (1 - grow * 0.05f))
        tmp.set(width / 2f - size / 2, reelRect.centerY() - size / 2, width / 2f + size / 2, reelRect.centerY() + size / 2)
        pBmp.alpha = (255 * fade).toInt()
        c.drawBitmap(sy[s], null, tmp, pBmp); pBmp.alpha = 255
        pText.typeface = serif; pText.textSize = 40 * u; pText.color = Neon.RED; pText.alpha = (255 * fade).toInt()
        pText.setShadowLayer(16 * u, 0f, 0f, Neon.RED)
        c.drawText("5 × ${ReaperConfig.SYMBOL_NAMES[s].uppercase()}", width / 2f, tmp.bottom + 10 * u, pText)
        pText.clearShadowLayer(); pText.alpha = 255
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

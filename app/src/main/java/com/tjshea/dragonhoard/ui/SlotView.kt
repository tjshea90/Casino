package com.tjshea.dragonhoard.ui

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
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.tjshea.dragonhoard.audio.SoundFx
import com.tjshea.dragonhoard.audio.SoundFx.Sfx
import com.tjshea.dragonhoard.data.Wallet
import com.tjshea.dragonhoard.engine.GameConfig
import com.tjshea.dragonhoard.engine.GameConfig.REELS
import com.tjshea.dragonhoard.engine.GameConfig.ROWS
import com.tjshea.dragonhoard.engine.Money
import com.tjshea.dragonhoard.engine.SlotEngine
import com.tjshea.dragonhoard.engine.SpinResult
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The whole machine cabinet, drawn on one hardware-accelerated canvas:
 * marquee, reels, paylines, credit meters, buttons, celebrations.
 */
@SuppressLint("ViewConstructor")
class SlotView(context: Context, private val host: Host) : View(context) {

    interface Host {
        val wallet: Wallet
        val sfx: SoundFx
        fun openDeposit()
        fun openWithdraw()
        fun openPaytable()
        fun openStats()
        fun openAutoPicker(onPicked: (Int) -> Unit)
    }

    private enum class Phase { IDLE, SPINNING, PRESENT, BIG_WIN, FEATURE_INTRO, FEATURE_OUTRO }

    private val wallet get() = host.wallet
    private val sfx get() = host.sfx
    private val cosmeticRng = Random()

    // ------------------------------------------------------------------ state
    private var phase = Phase.IDLE
    private var grid: Array<IntArray> = SlotEngine.gridFor(
        IntArray(REELS) { cosmeticRng.nextInt(GameConfig.BASE_STRIPS[it].size) }, GameConfig.BASE_STRIPS
    )
    private var result: SpinResult? = null
    private var pendingWin = 0L          // tokens credited by the wallet but not yet shown
    private var rollFrom = 0L
    private var rollTarget = 0L
    private var rollStart = 0.0
    private var rollDur = 0.0
    private var lastTick = 0.0
    private var presentStart = 0.0
    private var phaseStart = 0.0
    private var autoLeft = 0             // -1 = until stopped
    private var nextAutoAt = 0.0
    private var bigTier = 0
    private var retriggerBanner = 0.0
    private var message: String? = null
    private var messageUntil = 0.0
    private var creditFlashUntil = 0.0
    private var featureBanner = "" // text for intro/outro

    // reel animation
    private val reelTape = arrayOfNulls<IntArray>(REELS)
    private val reelStop = IntArray(REELS)
    private val reelS = DoubleArray(REELS)
    private val reelV = DoubleArray(REELS)
    private val reelLiftEnd = DoubleArray(REELS)
    private val reelRunEnd = DoubleArray(REELS)
    private val reelTease = BooleanArray(REELS)
    private val reelDone = BooleanArray(REELS)
    private val reelLanded = BooleanArray(REELS)
    private var spinStrips = GameConfig.BASE_STRIPS
    private var teaseSoundPlayed = false
    private val landTime = 0.30
    private val landDist = 2.0

    // ------------------------------------------------------------------ layout
    private var art: SymbolArt? = null
    private var bgBase: Bitmap? = null
    private var bgFree: Bitmap? = null
    private var cell = 0f
    private val reelRect = RectF()
    private val frameRect = RectF()
    private val marqueeRect = RectF()
    private val panelRect = RectF()
    private var u = 1f
    private val bulbs = ArrayList<FloatArray>()
    private val buttons = ArrayList<Btn>()
    private var pressed: Btn? = null
    private val creditRect = RectF()
    private val betRect = RectF()
    private val winRect = RectF()
    private val linesRect = RectF()
    private val lineBetRect = RectF()

    private class Btn(val id: String, var label: String, val rect: RectF, val round: Boolean = false) {
        var enabled = true
        var lit = false
    }

    // ------------------------------------------------------------------ paints
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    }
    private var titleShader: LinearGradient? = null
    private val shaderMatrix = Matrix()
    private val path = Path()
    private val tmpRect = RectF()
    private val tmpR = Rect()
    private val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val sansBold = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private val lineColors = intArrayOf(
        0xFFFF5252.toInt(), 0xFF40C4FF.toInt(), 0xFF69F0AE.toInt(), 0xFFFFD740.toInt(), 0xFFE040FB.toInt(),
        0xFFFF6E40.toInt(), 0xFF18FFFF.toInt(), 0xFFB2FF59.toInt(), 0xFFFF4081.toInt(), 0xFF7C4DFF.toInt()
    )

    // ------------------------------------------------------------------ particles
    private val maxParticles = 160
    private val px = FloatArray(maxParticles); private val py = FloatArray(maxParticles)
    private val pvx = FloatArray(maxParticles); private val pvy = FloatArray(maxParticles)
    private val plife = FloatArray(maxParticles); private val pspin = FloatArray(maxParticles)
    private val pkind = IntArray(maxParticles)
    private var lastFrame = 0.0
    private var coinRate = 0f
    private var coinAcc = 0f

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    private fun now() = SystemClock.uptimeMillis() / 1000.0

    // ================================================================== layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        layoutCabinet(w.toFloat(), h.toFloat())
    }

    private fun layoutCabinet(w: Float, h: Float) {
        val landscape = w >= h
        u = min(w / 1280f, h / 800f)
        if (!landscape) u = min(w / 800f, h / 1280f)
        val marqueeH = if (landscape) h * 0.13f else h * 0.09f
        val panelH = if (landscape) h * 0.25f else h * 0.24f
        val margin = 10 * u
        marqueeRect.set(margin, margin * 0.6f, w - margin, marqueeH)
        panelRect.set(margin, h - panelH, w - margin, h - margin * 0.6f)

        val sideW = if (landscape) max(w * 0.15f, 150 * u) else 0f
        val markerW = 34 * u
        val sideMenuRow = if (landscape) 0f else 90 * u
        val availH = panelRect.top - marqueeRect.bottom - margin * 3 - sideMenuRow
        val availW = w - 2 * (sideW + markerW + margin * 2)
        cell = floor(min(availH / ROWS, availW / REELS))
        val rw = cell * REELS; val rh = cell * ROWS
        val left = (w - rw) / 2
        val top = marqueeRect.bottom + margin * 1.5f + (availH - rh) / 2
        reelRect.set(left, top, left + rw, top + rh)
        val fpad = 14 * u
        frameRect.set(reelRect.left - fpad, reelRect.top - fpad, reelRect.right + fpad, reelRect.bottom + fpad)

        // bulbs around the frame
        bulbs.clear()
        val per = frameRect.width() * 2 + frameRect.height() * 2
        val n = (per / (34 * u)).toInt()
        for (i in 0 until n) {
            var d = per * i / n
            val pt = when {
                d < frameRect.width() -> floatArrayOf(frameRect.left + d, frameRect.top)
                d < frameRect.width() + frameRect.height() -> { d -= frameRect.width(); floatArrayOf(frameRect.right, frameRect.top + d) }
                d < frameRect.width() * 2 + frameRect.height() -> { d -= frameRect.width() + frameRect.height(); floatArrayOf(frameRect.right - d, frameRect.bottom) }
                else -> { d -= frameRect.width() * 2 + frameRect.height(); floatArrayOf(frameRect.left, frameRect.bottom - d) }
            }
            bulbs += pt
        }

        // buttons
        buttons.clear()
        val p = panelRect
        val ph = p.height()
        val spinR = ph * 0.40f
        val spinCx = p.right - spinR - 16 * u
        val spinCy = p.centerY()
        buttons += Btn("spin", "SPIN", RectF(spinCx - spinR, spinCy - spinR, spinCx + spinR, spinCy + spinR), round = true)
        val autoW = ph * 0.52f
        val autoRect = RectF(spinCx - spinR - autoW - 14 * u, spinCy - ph * 0.17f, spinCx - spinR - 14 * u, spinCy + ph * 0.17f)
        buttons += Btn("auto", "AUTO", autoRect)

        // meters across the remaining width
        val meterLeft = p.left + 12 * u
        val meterRight = autoRect.left - 12 * u
        val mw = meterRight - meterLeft
        val gap = 10 * u
        val colW = (mw - gap * 4) / 5f
        val mTop = p.top + ph * 0.12f; val mBot = p.bottom - ph * 0.12f
        creditRect.set(meterLeft, mTop, meterLeft + colW * 1.35f, mBot)
        linesRect.set(creditRect.right + gap, mTop, creditRect.right + gap + colW * 0.9f, mBot)
        lineBetRect.set(linesRect.right + gap, mTop, linesRect.right + gap + colW * 0.9f, mBot)
        betRect.set(lineBetRect.right + gap, mTop, lineBetRect.right + gap + colW * 0.85f, mBot)
        winRect.set(betRect.right + gap, mTop, meterRight, mBot)
        fun plusMinus(r: RectF, idm: String, idp: String) {
            val bh = r.height() * 0.36f
            val bw = r.width() * 0.42f
            buttons += Btn(idm, "−", RectF(r.left, r.bottom - bh, r.left + bw, r.bottom))
            buttons += Btn(idp, "+", RectF(r.right - bw, r.bottom - bh, r.right, r.bottom))
        }
        plusMinus(linesRect, "lines-", "lines+")
        plusMinus(lineBetRect, "bet-", "bet+")

        // side menu
        val menu = listOf("deposit" to "DEPOSIT", "withdraw" to "CASH OUT", "paytable" to "PAYTABLE",
            "stats" to "STATS", "sound" to "SOUND", "turbo" to "TURBO")
        if (landscape) {
            val colLeft = margin * 1.5f
            val colRight = reelRect.left - markerW - fpad - margin * 1.5f
            val colL2 = reelRect.right + markerW + fpad + margin * 1.5f
            val colR2 = w - margin * 1.5f
            val bh = min((reelRect.height() - 20 * u) / 3f - 12 * u, 90 * u)
            for (i in 0 until 3) {
                val y = reelRect.centerY() + (i - 1) * (bh + 16 * u) - bh / 2
                buttons += Btn(menu[i].first, menu[i].second, RectF(colLeft, y, colRight, y + bh))
                buttons += Btn(menu[i + 3].first, menu[i + 3].second, RectF(colL2, y, colR2, y + bh))
            }
        } else {
            val y0 = frameRect.bottom + margin
            val bw = (w - margin * 2 - 5 * 8 * u) / 6f
            for (i in menu.indices) {
                val x = margin + i * (bw + 8 * u)
                buttons += Btn(menu[i].first, menu[i].second, RectF(x, y0, x + bw, y0 + sideMenuRow - margin))
            }
        }

        art?.recycle()
        art = SymbolArt(cell.toInt().coerceAtLeast(16))
        bgBase?.recycle(); bgFree?.recycle()
        bgBase = paintBackground(w.toInt(), h.toInt(), false)
        bgFree = paintBackground(w.toInt(), h.toInt(), true)
        titleShader = LinearGradient(0f, 0f, marqueeRect.width() * 0.35f, 0f,
            intArrayOf(0xFFB8860B.toInt(), 0xFFFFF8E1.toInt(), 0xFFFFC107.toInt(), 0xFFB8860B.toInt()),
            floatArrayOf(0f, 0.45f, 0.55f, 1f), Shader.TileMode.MIRROR)
    }

    private fun paintBackground(w: Int, h: Int, free: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val c1 = if (free) 0xFF3A0F6B.toInt() else 0xFF7A0E0E.toInt()
        val c2 = if (free) 0xFF0A0420.toInt() else 0xFF170203.toInt()
        p.shader = RadialGradient(w / 2f, h * 0.45f, max(w, h) * 0.75f, c1, c2, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        // faint lattice pattern (Chinese window style)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f * u
        p.color = if (free) 0x22B388FF else 0x22FFC107
        val step = 48 * u
        var y = 0f
        while (y < h) {
            var x = if (((y / step).toInt() % 2) == 0) 0f else step / 2
            while (x < w) {
                c.drawRect(x + step * 0.2f, y + step * 0.2f, x + step * 0.8f, y + step * 0.8f, p)
                x += step
            }
            y += step
        }
        p.style = Paint.Style.FILL
        // marquee plate
        p.shader = LinearGradient(0f, marqueeRect.top, 0f, marqueeRect.bottom,
            intArrayOf(0xFF2A0505.toInt(), 0xFF000000.toInt(), 0xFF2A0505.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(marqueeRect, 18 * u, 18 * u, p)
        goldStroke(c, p, marqueeRect, 4 * u, 18 * u)
        // cabinet frame around reels
        tmpRect.set(frameRect); tmpRect.inset(-8 * u, -8 * u)
        p.shader = LinearGradient(0f, tmpRect.top, 0f, tmpRect.bottom,
            intArrayOf(0xFFFFE082.toInt(), 0xFFB8860B.toInt(), 0xFF5D3A00.toInt(), 0xFFFFD54F.toInt(), 0xFF8D5A00.toInt()),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(tmpRect, 26 * u, 26 * u, p)
        p.shader = null; p.color = 0xFF120202.toInt()
        c.drawRoundRect(frameRect, 18 * u, 18 * u, p)
        // reel backing: each reel a curved cylinder look
        for (r in 0 until REELS) {
            val l = reelRect.left + r * cell
            p.shader = LinearGradient(0f, reelRect.top, 0f, reelRect.bottom,
                intArrayOf(0xFF2B1A12.toInt(), 0xFFFDF3DC.toInt(), 0xFFFFF8E8.toInt(), 0xFFFDF3DC.toInt(), 0xFF2B1A12.toInt()),
                floatArrayOf(0f, 0.18f, 0.5f, 0.82f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(l + 2 * u, reelRect.top, l + cell - 2 * u, reelRect.bottom, p)
        }
        p.shader = null
        // panel
        p.shader = LinearGradient(0f, panelRect.top, 0f, panelRect.bottom,
            intArrayOf(0xFF2A0606.toInt(), 0xFF0A0101.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(panelRect, 20 * u, 20 * u, p)
        goldStroke(c, p, panelRect, 3 * u, 20 * u)
        for (r in listOf(creditRect, linesRect, lineBetRect, betRect, winRect)) {
            p.shader = null; p.color = 0xFF050000.toInt()
            c.drawRoundRect(r, 12 * u, 12 * u, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * u; p.color = 0xFF8D5A00.toInt()
            c.drawRoundRect(r, 12 * u, 12 * u, p)
            p.style = Paint.Style.FILL
        }
        return bmp
    }

    private fun goldStroke(c: Canvas, p: Paint, r: RectF, width: Float, radius: Float) {
        p.style = Paint.Style.STROKE
        p.strokeWidth = width
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom,
            intArrayOf(0xFFFFF59D.toInt(), 0xFFB8860B.toInt(), 0xFFFFE082.toInt(), 0xFF8D5A00.toInt()),
            null, Shader.TileMode.MIRROR)
        c.drawRoundRect(r, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.FILL
    }

    // ================================================================== game flow
    private val lineBet get() = GameConfig.LINE_BET_OPTIONS[wallet.lineBetIndex.coerceIn(0, GameConfig.LINE_BET_OPTIONS.size - 1)]
    private val lines get() = GameConfig.LINE_OPTIONS[wallet.linesIndex.coerceIn(0, GameConfig.LINE_OPTIONS.size - 1)]
    private val totalBetMacks get() = lineBet * lines

    /** Called by the activity once the wallet is loaded / after dialogs close. */
    fun onWalletChanged() {
        if (wallet.inFreeSpins && phase == Phase.IDLE) {
            featureBanner = "resume"
            enterPhase(Phase.FEATURE_INTRO)
        }
        invalidate()
    }

    fun showMessage(text: String, seconds: Double = 2.6) {
        message = text; messageUntil = now() + seconds; invalidate()
    }

    private fun enterPhase(p: Phase) {
        phase = p; phaseStart = now(); invalidate()
    }

    private fun startSpin() {
        if (phase != Phase.IDLE && phase != Phase.PRESENT) return
        if (!wallet.inFreeSpins && !wallet.canAfford(lineBet, lines)) {
            autoLeft = 0
            sfx.play(Sfx.ERROR)
            creditFlashUntil = now() + 1.5
            showMessage(if (wallet.balance == 0L) "Deposit tokens to play" else "Not enough credit for this bet")
            return
        }
        settleRollup()
        val free = wallet.inFreeSpins
        val res = wallet.playSpin(lineBet, lines) ?: run { sfx.play(Sfx.ERROR); return }
        result = res
        pendingWin = res.totalWinTokens
        spinStrips = if (free) GameConfig.FREE_STRIPS else GameConfig.BASE_STRIPS
        sfx.play(Sfx.SPIN, 0.6f)
        val t = now()
        val turbo = wallet.turbo && !free
        val baseDur = if (turbo) 0.32 else 0.62
        val stepDur = if (turbo) 0.10 else 0.22
        var extra = 0.0
        var scatSoFar = 0
        teaseSoundPlayed = false
        for (r in 0 until REELS) {
            val tease = scatSoFar >= 2 && r >= 2
            if (tease) extra += if (turbo) 0.7 else 1.1
            reelTease[r] = tease
            val stopAt = t + baseDur + stepDur * r + extra
            val lift = 0.09
            reelLiftEnd[r] = t + lift
            reelRunEnd[r] = stopAt
            val speed = if (turbo) 30.0 else 24.0
            val runLen = stopAt - (t + lift)
            val sLen = max(4, ceil(landDist - 0.35 + speed * runLen).toInt())
            reelS[r] = sLen.toDouble()
            reelV[r] = (sLen + 0.35 - landDist) / runLen
            val strip = spinStrips[r]
            val tape = IntArray(sLen + 3)
            for (i in 0 until sLen) tape[i] = strip[(res.stops[r] + i) % strip.size]
            for (row in 0 until ROWS) tape[sLen + row] = grid[r][row]
            reelTape[r] = tape
            reelStop[r] = res.stops[r]
            reelDone[r] = false
            reelLanded[r] = false
            for (row in 0 until ROWS) if (res.grid[r][row] == GameConfig.SCATTER) scatSoFar++
        }
        enterPhase(Phase.SPINNING)
    }

    /** Slam-stop: tap during a spin brings the remaining reels in quickly. */
    private fun slamStop() {
        val t = now()
        for (r in 0 until REELS) if (!reelLanded[r]) {
            val target = t + 0.06 * r
            if (reelRunEnd[r] > target) reelRunEnd[r] = target
            reelTease[r] = false
        }
    }

    private fun reelOffset(r: Int, t: Double): Double {
        val s = reelS[r]
        if (t < reelLiftEnd[r]) {
            val q = 1 - (reelLiftEnd[r] - t) / 0.09
            return s + 0.35 * sin(PI / 2 * q.coerceIn(0.0, 1.0))
        }
        if (t < reelRunEnd[r]) return min(s + 0.35, landDist + reelV[r] * (reelRunEnd[r] - t))
        val q = (t - reelRunEnd[r]) / landTime
        if (q >= 1) return 0.0
        val c = 1.5
        val e = 1 + (c + 1) * (q - 1) * (q - 1) * (q - 1) + c * (q - 1) * (q - 1)
        return landDist * (1 - e)
    }

    private fun tapeSymbol(r: Int, k: Int): Int {
        val tape = reelTape[r] ?: return grid[r][k.coerceIn(0, 2)]
        if (k in tape.indices) return tape[k]
        val strip = spinStrips[r]
        return strip[Math.floorMod(reelStop[r] + k, strip.size)]
    }

    private fun updateSpin(t: Double) {
        val res = result ?: return
        var scat = 0
        for (r in 0 until REELS) {
            if (!reelLanded[r] && t >= reelRunEnd[r]) {
                reelLanded[r] = true
                sfx.play(Sfx.STOP, 0.8f)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val hasScatter = (0 until ROWS).any { res.grid[r][it] == GameConfig.SCATTER }
                if (hasScatter) {
                    var count = 0
                    for (rr in 0..r) for (row in 0 until ROWS) if (res.grid[rr][row] == GameConfig.SCATTER) count++
                    sfx.play(Sfx.SCATTER, 0.9f, 0.85f + 0.12f * count)
                }
            }
            if (!reelDone[r] && t >= reelRunEnd[r] + landTime) {
                reelDone[r] = true
                for (row in 0 until ROWS) grid[r][row] = res.grid[r][row]
                reelTape[r] = null
            }
            if (reelLanded[r]) for (row in 0 until ROWS) if (res.grid[r][row] == GameConfig.SCATTER) scat++
        }
        // anticipation sound when the next reel is in tease mode and all before it landed
        for (r in 0 until REELS) if (reelTease[r] && !reelLanded[r] && (r == 0 || reelLanded[r - 1]) && !teaseSoundPlayed) {
            teaseSoundPlayed = true
            sfx.play(Sfx.TEASE, 0.7f)
        }
        if (reelDone.all { it }) onReelsStopped(res)
    }

    private fun onReelsStopped(res: SpinResult) {
        presentStart = now()
        val win = res.totalWinTokens
        val betTokens = res.totalBetMacks * Money.TOKENS_PER_MACK
        val x = if (betTokens > 0) win.toDouble() / betTokens else 0.0
        bigTier = when {
            x >= 100 -> 4
            x >= 50 -> 3
            x >= 25 -> 2
            x >= 12 -> 1
            else -> 0
        }
        if (res.freeSpin && res.freeSpinsAwarded > 0) {
            retriggerBanner = now() + 2.2
            sfx.play(Sfx.FEATURE, 0.8f)
        }
        if (win > 0) {
            startRollup(win, if (bigTier > 0) 2.5 + bigTier * 1.4 else (0.5 + 0.45 * ln(1 + x)).coerceAtMost(2.5))
            if (bigTier > 0) {
                sfx.play(Sfx.BIG_WIN)
                coinRate = 40f + bigTier * 25f
                enterPhase(Phase.BIG_WIN)
                return
            }
            sfx.play(Sfx.WIN, 0.8f, if (x >= 3) 1.0f else 1.15f)
            if (x >= 5) coinRate = 25f
        } else {
            pendingWin = 0
        }
        enterPhase(Phase.PRESENT)
        afterPresentScheduled()
    }

    private fun afterPresentScheduled() {
        val res = result ?: return
        val t = now()
        if (res.freeSpinsAwarded > 0 && !res.freeSpin) {
            nextAutoAt = t + 1.8   // scatter celebration, then intro
            return
        }
        val delay = if (res.totalWinTokens > 0) max(1.1, rollDur + 0.6) else if (wallet.turbo) 0.25 else 0.45
        nextAutoAt = t + delay
    }

    private fun startRollup(win: Long, dur: Double) {
        rollFrom = 0; rollTarget = win; rollStart = now(); rollDur = dur; lastTick = 0.0
    }

    private fun rollValue(t: Double): Long {
        if (rollTarget == 0L) return 0
        val q = ((t - rollStart) / rollDur).coerceIn(0.0, 1.0)
        val v = rollFrom + ((rollTarget - rollFrom) * q).toLong()
        return Money.floorToMack(v).coerceAtMost(rollTarget).let { if (q >= 1.0) rollTarget else it }
    }

    private fun settleRollup() {
        rollStart = now() - rollDur - 1
        pendingWin = 0
        coinRate = 0f
    }

    private fun updateFlow(t: Double) {
        // rollup ticks & pending win release
        if (rollTarget > 0 && t - rollStart < rollDur) {
            if (t - lastTick > 0.075) { lastTick = t; sfx.play(Sfx.TICK, 0.35f, 0.9f + 0.4f * ((t - rollStart) / rollDur).toFloat()) }
            pendingWin = rollTarget - rollValue(t)
        } else if (pendingWin != 0L && phase != Phase.SPINNING) {
            pendingWin = 0
            if (rollTarget > 0) sfx.play(Sfx.COIN, 0.6f)
            coinRate = 0f
        }
        when (phase) {
            Phase.BIG_WIN -> if (t - rollStart > rollDur + 2.2) {
                coinRate = 0f
                enterPhase(Phase.PRESENT); presentStart = t
                afterPresentScheduled()
                nextAutoAt = t + 0.6
            }
            Phase.PRESENT -> {
                val res = result
                if (res != null && t >= nextAutoAt) {
                    if (res.freeSpinsAwarded > 0 && !res.freeSpin) {
                        featureBanner = "intro"
                        sfx.play(Sfx.FEATURE)
                        enterPhase(Phase.FEATURE_INTRO)
                    } else if (res.freeSpin && !wallet.inFreeSpins) {
                        featureBanner = "outro"
                        sfx.play(Sfx.BIG_WIN, 0.8f)
                        coinRate = 30f
                        enterPhase(Phase.FEATURE_OUTRO)
                    } else if (wallet.inFreeSpins) {
                        startSpin()
                    } else if (autoLeft != 0) {
                        if (autoLeft > 0) autoLeft--
                        startSpin()
                    }
                }
            }
            Phase.IDLE -> if (autoLeft != 0 && t >= nextAutoAt) { if (autoLeft > 0) autoLeft--; startSpin() }
            Phase.FEATURE_INTRO -> if (t - phaseStart > 4.0) beginFreeSpins()
            Phase.FEATURE_OUTRO -> if (t - phaseStart > 5.0) endFeature()
            Phase.SPINNING -> {}
        }
    }

    private fun beginFreeSpins() {
        coinRate = 0f
        enterPhase(Phase.PRESENT)
        result = null
        nextAutoAt = now() + 0.4
        if (wallet.inFreeSpins) startSpin()
    }

    private fun endFeature() {
        coinRate = 0f
        result = null
        enterPhase(Phase.IDLE)
        nextAutoAt = now() + 0.8
    }

    // ================================================================== input
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = buttons.firstOrNull { it.enabled && hit(it, e.x, e.y) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val p = pressed
                if (p != null && !hit(p, e.x, e.y)) { pressed = null; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = null
                if (p != null && hit(p, e.x, e.y)) onButton(p.id) else onTapElsewhere()
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; invalidate() }
        }
        return true
    }

    private fun hit(b: Btn, x: Float, y: Float): Boolean {
        tmpRect.set(b.rect); tmpRect.inset(-6 * u, -6 * u)
        return tmpRect.contains(x, y)
    }

    private fun onTapElsewhere() {
        when (phase) {
            Phase.SPINNING -> slamStop()
            Phase.BIG_WIN -> if (now() - rollStart < rollDur) rollStart = now() - rollDur else rollStart = now() - rollDur - 2.2
            Phase.FEATURE_INTRO -> beginFreeSpins()
            Phase.FEATURE_OUTRO -> endFeature()
            else -> {}
        }
    }

    private val busy get() = phase == Phase.SPINNING || phase == Phase.BIG_WIN ||
        phase == Phase.FEATURE_INTRO || phase == Phase.FEATURE_OUTRO || wallet.inFreeSpins

    private fun onButton(id: String) {
        if (id != "spin") sfx.play(Sfx.CLICK)
        when (id) {
            "spin" -> when {
                phase == Phase.SPINNING -> slamStop()
                autoLeft != 0 -> { autoLeft = 0; showMessage("Autoplay stopped") }
                phase == Phase.BIG_WIN || phase == Phase.FEATURE_INTRO || phase == Phase.FEATURE_OUTRO -> onTapElsewhere()
                wallet.inFreeSpins -> {}
                else -> startSpin()
            }
            "auto" -> if (autoLeft != 0) { autoLeft = 0; showMessage("Autoplay stopped") } else if (!busy) {
                host.openAutoPicker { n -> autoLeft = n; if (n != 0) { nextAutoAt = now(); if (phase == Phase.PRESENT || phase == Phase.IDLE) { if (autoLeft > 0) autoLeft--; startSpin() } } }
            }
            "lines-" -> if (!busy) changeIndex(lines = -1)
            "lines+" -> if (!busy) changeIndex(lines = 1)
            "bet-" -> if (!busy) changeIndex(bet = -1)
            "bet+" -> if (!busy) changeIndex(bet = 1)
            "deposit" -> if (!busy && autoLeft == 0) host.openDeposit() else showMessage("Wait for the reels to finish")
            "withdraw" -> if (!busy && autoLeft == 0) host.openWithdraw() else showMessage("Wait for the reels to finish")
            "paytable" -> host.openPaytable()
            "stats" -> host.openStats()
            "sound" -> { wallet.soundOn = !wallet.soundOn; sfx.enabled = wallet.soundOn; wallet.save() }
            "turbo" -> { wallet.turbo = !wallet.turbo; wallet.save() }
        }
    }

    private fun changeIndex(lines: Int = 0, bet: Int = 0) {
        if (lines != 0) wallet.linesIndex = (wallet.linesIndex + lines).coerceIn(0, GameConfig.LINE_OPTIONS.size - 1)
        if (bet != 0) wallet.lineBetIndex = (wallet.lineBetIndex + bet).coerceIn(0, GameConfig.LINE_BET_OPTIONS.size - 1)
        wallet.save()
        if (phase == Phase.PRESENT) { enterPhase(Phase.IDLE); result = null }
        showLinesUntil = now() + 1.4
    }

    private var showLinesUntil = 0.0

    // ================================================================== drawing
    override fun onDraw(canvas: Canvas) {
        val a = art ?: return
        val t = now()
        val dt = if (lastFrame == 0.0) 0.016 else (t - lastFrame).coerceIn(0.0, 0.05)
        lastFrame = t
        if (phase == Phase.SPINNING) updateSpin(t)
        updateFlow(t)

        val free = wallet.inFreeSpins || (result?.freeSpin == true && phase != Phase.IDLE) || phase == Phase.FEATURE_INTRO
        canvas.drawBitmap((if (free) bgFree else bgBase)!!, 0f, 0f, null)
        drawMarquee(canvas, t, free)
        drawBulbs(canvas, t)
        drawReels(canvas, a, t)
        drawWins(canvas, t)
        drawLineMarkers(canvas, t)
        drawPanel(canvas, t)
        drawButtons(canvas, t)
        drawMessage(canvas, t)
        updateParticles(dt.toFloat())
        drawParticles(canvas)
        when (phase) {
            Phase.BIG_WIN -> drawBigWin(canvas, t)
            Phase.FEATURE_INTRO, Phase.FEATURE_OUTRO -> drawFeatureBanner(canvas, t)
            else -> {}
        }
        if (t < retriggerBanner) drawRetrigger(canvas, t)

        val animating = phase != Phase.IDLE || pendingWin != 0L || particleCount > 0 || autoLeft != 0 ||
            t < messageUntil || t < showLinesUntil
        if (animating) postInvalidateOnAnimation() else postInvalidateDelayed(50) // idle shimmer ~20fps
    }

    private fun drawMarquee(c: Canvas, t: Double, free: Boolean) {
        val r = marqueeRect
        pTitle.textSize = r.height() * 0.58f
        pTitle.shader = null
        val title = if (free && (wallet.inFreeSpins || phase == Phase.PRESENT || phase == Phase.SPINNING)) {
            val res = result
            val done = wallet.freeSpinsTotal - wallet.freeSpinsLeft
            "FREE SPINS  $done / ${wallet.freeSpinsTotal}   ×${GameConfig.FREE_SPIN_MULTIPLIER}".takeIf { res?.freeSpin == true || wallet.inFreeSpins }
                ?: "DRAGON'S HOARD"
        } else "DRAGON'S HOARD"
        val ty = r.centerY() - (pTitle.fontMetrics.ascent + pTitle.fontMetrics.descent) / 2
        pTitle.textSize = min(r.height() * 0.58f, r.height() * 0.58f * (r.width() * 0.8f) / max(1f, pTitle.measureText(title)))
        val ty2 = r.centerY() - (pTitle.fontMetrics.ascent + pTitle.fontMetrics.descent) / 2
        // glow
        pTitle.color = if (free) 0xFF7C4DFF.toInt() else 0xFFFF1744.toInt()
        pTitle.setShadowLayer(18 * u, 0f, 0f, pTitle.color)
        c.drawText(title, r.centerX(), ty2, pTitle)
        pTitle.clearShadowLayer()
        pTitle.style = Paint.Style.STROKE; pTitle.strokeWidth = 5 * u; pTitle.color = 0xFF2A0000.toInt()
        c.drawText(title, r.centerX(), ty2, pTitle)
        pTitle.style = Paint.Style.FILL
        titleShader?.let {
            shaderMatrix.setTranslate(((t * 260 * u) % (r.width() * 0.7f)).toFloat(), 0f)
            it.setLocalMatrix(shaderMatrix)
            pTitle.shader = it
        }
        c.drawText(title, r.centerX(), ty2, pTitle)
        pTitle.shader = null
        if (ty.isNaN()) return
        if (free && wallet.freeSpinWinTokens > 0 && phase != Phase.FEATURE_OUTRO) {
            pText.textSize = r.height() * 0.2f; pText.color = 0xFFFFF59D.toInt(); pText.typeface = sansBold
            c.drawText("FEATURE WIN  ${Money.macks(wallet.freeSpinWinTokens - if (result?.freeSpin == true) pendingWin else 0)} MACKS",
                r.centerX(), r.bottom - r.height() * 0.06f, pText)
        }
    }

    private fun drawBulbs(c: Canvas, t: Double) {
        val excited = phase == Phase.BIG_WIN || (phase == Phase.PRESENT && (result?.totalWinTokens ?: 0) > 0 && t - presentStart < 2.5) ||
            phase == Phase.FEATURE_INTRO || phase == Phase.FEATURE_OUTRO
        val step = ((t * (if (excited) 14 else 6)).toInt())
        val r = 6.5f * u
        val n = bulbs.size
        for (i in 0 until n) {
            val lit = if (excited) (i + step) % 2 == 0 else (i + step) % 4 == 0
            val b = bulbs[i]
            pFill.color = if (lit) 0xFFFFF59D.toInt() else 0xFF6D4C00.toInt()
            if (lit) {
                pFill.alpha = 90
                c.drawCircle(b[0], b[1], r * 2.1f, pFill)
                pFill.alpha = 255
            }
            c.drawCircle(b[0], b[1], r, pFill)
        }
    }

    private fun highlightCells(t: Double): Array<BooleanArray>? {
        val res = result ?: return null
        if (phase != Phase.PRESENT && phase != Phase.BIG_WIN) return null
        val hl = Array(REELS) { BooleanArray(ROWS) }
        var any = false
        val idx = currentLineIndex(t)
        val wins = res.lineWins
        val show = if (idx < 0) wins else listOf(wins[idx])
        for (w in show) {
            val line = GameConfig.PAYLINES[w.line]
            for (r in 0 until w.count) { hl[r][line[r]] = true; any = true }
        }
        if (res.scatterCount >= 3 && (idx < 0 || res.lineWins.isEmpty())) {
            for (r in 0 until REELS) for (row in 0 until ROWS) if (res.grid[r][row] == GameConfig.SCATTER) { hl[r][row] = true; any = true }
        }
        return if (any) hl else null
    }

    /** -1 = show all winning lines, otherwise index into lineWins for the cycle. */
    private fun currentLineIndex(t: Double): Int {
        val res = result ?: return -1
        val n = res.lineWins.size
        if (n <= 1) return -1
        val e = t - presentStart
        if (e < 1.3 || phase == Phase.BIG_WIN) return -1
        return (((e - 1.3) / 1.4).toInt()) % n
    }

    private fun drawReels(c: Canvas, a: SymbolArt, t: Double) {
        c.save()
        c.clipRect(reelRect)
        val hl = highlightCells(t)
        val pulse = (1 + 0.07 * sin(t * 9)).toFloat()
        for (r in 0 until REELS) {
            val left = reelRect.left + r * cell
            val spinning = phase == Phase.SPINNING && !reelDone[r]
            if (spinning) {
                val off = reelOffset(r, t)
                val fast = t > reelLiftEnd[r] && t < reelRunEnd[r] && reelV[r] > 6
                val base = floor(off).toInt()
                for (j in 0..3) {
                    val k = base + j
                    val y = reelRect.top + ((k - off) * cell).toFloat()
                    val sym = tapeSymbol(r, k)
                    c.drawBitmap(if (fast) a.blurred[sym] else a.sharp[sym], left, y, pBmp)
                }
                if (reelTease[r] && (r == 0 || reelLanded[r - 1])) drawTease(c, left, t)
            } else {
                for (row in 0 until ROWS) {
                    val sym = grid[r][row]
                    val y = reelRect.top + row * cell
                    val lit = hl?.get(r)?.get(row) == true
                    if (hl != null && !lit) {
                        pBmp.alpha = 95
                        c.drawBitmap(a.sharp[sym], left, y, pBmp)
                        pBmp.alpha = 255
                    } else if (lit) {
                        c.save()
                        c.scale(pulse, pulse, left + cell / 2, y + cell / 2)
                        c.drawBitmap(a.sharp[sym], left, y, pBmp)
                        c.restore()
                    } else {
                        c.drawBitmap(a.sharp[sym], left, y, pBmp)
                    }
                }
            }
        }
        // cylinder shading top/bottom
        pFill.shader = LinearGradient(0f, reelRect.top, 0f, reelRect.top + cell * 0.45f, 0xAA000000.toInt(), 0, Shader.TileMode.CLAMP)
        c.drawRect(reelRect.left, reelRect.top, reelRect.right, reelRect.top + cell * 0.45f, pFill)
        pFill.shader = LinearGradient(0f, reelRect.bottom - cell * 0.45f, 0f, reelRect.bottom, 0, 0xAA000000.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(reelRect.left, reelRect.bottom - cell * 0.45f, reelRect.right, reelRect.bottom, pFill)
        pFill.shader = null
        c.restore()
        // reel separators
        pFill.color = 0xFF3E2500.toInt()
        for (r in 1 until REELS) {
            val x = reelRect.left + r * cell
            c.drawRect(x - 1.5f * u, reelRect.top, x + 1.5f * u, reelRect.bottom, pFill)
        }
    }

    private fun drawTease(c: Canvas, left: Float, t: Double) {
        val a = (150 + 100 * sin(t * 16)).toInt().coerceIn(0, 255)
        pStroke.strokeWidth = 7 * u
        pStroke.color = Color.argb(a, 255, 215, 64)
        tmpRect.set(left + 4 * u, reelRect.top + 4 * u, left + cell - 4 * u, reelRect.bottom - 4 * u)
        c.drawRoundRect(tmpRect, 12 * u, 12 * u, pStroke)
        pFill.color = Color.argb(a / 4, 255, 193, 7)
        c.drawRect(tmpRect, pFill)
    }

    private fun drawWins(c: Canvas, t: Double) {
        val showAll = t < showLinesUntil && phase != Phase.SPINNING
        if (showAll) {
            for (l in 0 until lines) drawPayline(c, l, 5, lineColors[l], 0.85f)
            return
        }
        val res = result ?: return
        if (phase != Phase.PRESENT && phase != Phase.BIG_WIN) return
        val idx = currentLineIndex(t)
        val wins = if (idx < 0) res.lineWins else listOf(res.lineWins[idx])
        for (w in wins) {
            drawPayline(c, w.line, 5, lineColors[w.line], 1f)
            val line = GameConfig.PAYLINES[w.line]
            pStroke.strokeWidth = 4 * u
            pStroke.color = lineColors[w.line]
            for (r in 0 until w.count) {
                val x = reelRect.left + r * cell; val y = reelRect.top + line[r] * cell
                tmpRect.set(x + 5 * u, y + 5 * u, x + cell - 5 * u, y + cell - 5 * u)
                c.drawRoundRect(tmpRect, 14 * u, 14 * u, pStroke)
            }
        }
        // caption for current line / scatter
        val caption = when {
            idx >= 0 -> {
                val w = res.lineWins[idx]
                "LINE ${w.line + 1}  •  ${w.count} × ${GameConfig.SYMBOL_NAMES[w.symbol].uppercase()}  •  ${res.winMacksFor(w)} MACKS"
            }
            res.scatterCount >= 3 && res.lineWins.isEmpty() -> "${res.scatterCount} SCATTERS  •  ${res.scatterWinMacks} MACKS"
            res.lineWins.size == 1 -> {
                val w = res.lineWins[0]
                "LINE ${w.line + 1}  •  ${w.count} × ${GameConfig.SYMBOL_NAMES[w.symbol].uppercase()}  •  ${res.winMacksFor(w)} MACKS"
            }
            else -> null
        }
        if (caption != null && phase == Phase.PRESENT) {
            pText.typeface = sansBold
            pText.textSize = 26 * u
            val tw = pText.measureText(caption) + 40 * u
            val cy = reelRect.bottom - 26 * u
            tmpRect.set(reelRect.centerX() - tw / 2, cy - 22 * u, reelRect.centerX() + tw / 2, cy + 18 * u)
            pFill.color = 0xD0000000.toInt()
            c.drawRoundRect(tmpRect, 20 * u, 20 * u, pFill)
            pText.color = 0xFFFFE57F.toInt()
            c.drawText(caption, reelRect.centerX(), cy + 7 * u, pText)
        }
    }

    private fun drawPayline(c: Canvas, l: Int, count: Int, color: Int, alpha: Float) {
        val line = GameConfig.PAYLINES[l]
        path.reset()
        path.moveTo(reelRect.left - 18 * u, reelRect.top + (line[0] + 0.5f) * cell)
        for (r in 0 until count) path.lineTo(reelRect.left + (r + 0.5f) * cell, reelRect.top + (line[r] + 0.5f) * cell)
        path.lineTo(reelRect.right + 18 * u, reelRect.top + (line[4] + 0.5f) * cell)
        pStroke.strokeJoin = Paint.Join.ROUND
        pStroke.strokeCap = Paint.Cap.ROUND
        pStroke.color = color
        pStroke.alpha = (90 * alpha).toInt()
        pStroke.strokeWidth = 14 * u
        c.drawPath(path, pStroke)
        pStroke.alpha = (255 * alpha).toInt()
        pStroke.strokeWidth = 5 * u
        c.drawPath(path, pStroke)
        pStroke.color = Color.WHITE
        pStroke.alpha = (200 * alpha).toInt()
        pStroke.strokeWidth = 1.6f * u
        c.drawPath(path, pStroke)
        pStroke.alpha = 255
    }

    private fun drawLineMarkers(c: Canvas, t: Double) {
        val rr = 14 * u
        pText.typeface = sansBold
        pText.textSize = 15 * u
        val res = result
        for (l in 0 until GameConfig.PAYLINES.size) {
            val line = GameConfig.PAYLINES[l]
            val active = l < lines
            val winning = res != null && (phase == Phase.PRESENT || phase == Phase.BIG_WIN) && res.lineWins.any { it.line == l }
            for (side in 0..1) {
                val x = if (side == 0) frameRect.left - rr - 10 * u else frameRect.right + rr + 10 * u
                val row = if (side == 0) line[0] else line[4]
                // spread markers that share a row
                val sameRow = (0 until GameConfig.PAYLINES.size).filter { (if (side == 0) GameConfig.PAYLINES[it][0] else GameConfig.PAYLINES[it][4]) == row }
                val k = sameRow.indexOf(l)
                val y = reelRect.top + (row + 0.5f) * cell + (k - (sameRow.size - 1) / 2f) * (rr * 2.2f)
                pFill.color = if (active) lineColors[l] else 0xFF3A2A2A.toInt()
                if (winning && ((t * 6).toInt() % 2 == 0)) pFill.color = Color.WHITE
                c.drawCircle(x, y, rr, pFill)
                pStroke.strokeWidth = 2 * u; pStroke.color = 0xFF2A1500.toInt()
                c.drawCircle(x, y, rr, pStroke)
                pText.color = if (active) 0xFF1A0A00.toInt() else 0xFF8A7A7A.toInt()
                c.drawText("${l + 1}", x, y + 5.5f * u, pText)
            }
        }
    }

    private fun meterLabel(c: Canvas, r: RectF, label: String) {
        pText.typeface = sansBold
        pText.textSize = r.height() * 0.15f
        pText.color = 0xFFFFB300.toInt()
        c.drawText(label, r.centerX(), r.top + r.height() * 0.2f, pText)
    }

    private fun fitText(text: String, maxW: Float, size: Float): Float {
        pText.textSize = size
        val w = pText.measureText(text)
        if (w > maxW) pText.textSize = size * maxW / w
        return pText.textSize
    }

    private fun drawPanel(c: Canvas, t: Double) {
        // CREDIT
        val shown = wallet.balance - pendingWin
        meterLabel(c, creditRect, "CREDIT")
        val flash = t < creditFlashUntil && ((t * 8).toInt() % 2 == 0)
        pText.typeface = serifBold
        pText.color = if (flash) 0xFFFF5252.toInt() else 0xFFFFF8E1.toInt()
        fitText("%,d".format(shown), creditRect.width() * 0.9f, creditRect.height() * 0.32f)
        c.drawText("%,d".format(shown), creditRect.centerX(), creditRect.centerY() + creditRect.height() * 0.12f, pText)
        pText.typeface = sansBold
        pText.color = 0xFFFFCC80.toInt()
        fitText("TOKENS • " + Money.short(shown), creditRect.width() * 0.92f, creditRect.height() * 0.13f)
        c.drawText("TOKENS • " + Money.short(shown), creditRect.centerX(), creditRect.bottom - creditRect.height() * 0.1f, pText)

        // LINES
        val freeLocked = wallet.inFreeSpins
        meterLabel(c, linesRect, "LINES")
        pText.typeface = serifBold; pText.color = 0xFFFFF8E1.toInt()
        pText.textSize = linesRect.height() * 0.3f
        c.drawText("${if (freeLocked) wallet.fsLines else lines}", linesRect.centerX(), linesRect.top + linesRect.height() * 0.52f, pText)
        // BET / LINE
        meterLabel(c, lineBetRect, "BET / LINE")
        pText.typeface = serifBold; pText.color = 0xFFFFF8E1.toInt()
        pText.textSize = lineBetRect.height() * 0.3f
        c.drawText("${if (freeLocked) wallet.fsLineBet else lineBet}", lineBetRect.centerX(), lineBetRect.top + lineBetRect.height() * 0.52f, pText)
        pText.typeface = sansBold; pText.textSize = lineBetRect.height() * 0.11f; pText.color = 0xFFFFCC80.toInt()
        c.drawText("MACKS", lineBetRect.centerX(), lineBetRect.top + lineBetRect.height() * 0.64f, pText)
        // TOTAL BET
        meterLabel(c, betRect, "TOTAL BET")
        val tb = if (freeLocked) wallet.fsLines * wallet.fsLineBet else totalBetMacks
        pText.typeface = serifBold; pText.color = if (freeLocked) 0xFFB388FF.toInt() else 0xFFFFF8E1.toInt()
        fitText(if (freeLocked) "FREE" else "$tb", betRect.width() * 0.9f, betRect.height() * 0.32f)
        c.drawText(if (freeLocked) "FREE" else "$tb", betRect.centerX(), betRect.centerY() + betRect.height() * 0.12f, pText)
        pText.typeface = sansBold; pText.color = 0xFFFFCC80.toInt()
        fitText("MACKS • ${Money.short(tb * Money.TOKENS_PER_MACK)}", betRect.width() * 0.92f, betRect.height() * 0.13f)
        c.drawText("MACKS • ${Money.short(tb * Money.TOKENS_PER_MACK)}", betRect.centerX(), betRect.bottom - betRect.height() * 0.1f, pText)
        // WIN
        meterLabel(c, winRect, if (wallet.inFreeSpins || result?.freeSpin == true) "WIN ×3" else "WIN")
        val winShown = if (phase == Phase.SPINNING) 0L else rollValue(t)
        pText.typeface = serifBold
        pText.color = if (winShown > 0) 0xFF69F0AE.toInt() else 0xFF6D5A4A.toInt()
        val wtxt = Money.macks(winShown)
        fitText(wtxt, winRect.width() * 0.9f, winRect.height() * 0.36f)
        if (winShown > 0) pText.setShadowLayer(10 * u, 0f, 0f, 0xFF00C853.toInt())
        c.drawText(wtxt, winRect.centerX(), winRect.centerY() + winRect.height() * 0.13f, pText)
        pText.clearShadowLayer()
        pText.typeface = sansBold; pText.color = 0xFFFFCC80.toInt()
        val wsub = if (winShown > 0) "MACKS • ${Money.short(winShown)}" else "MACKS"
        fitText(wsub, winRect.width() * 0.92f, winRect.height() * 0.13f)
        c.drawText(wsub, winRect.centerX(), winRect.bottom - winRect.height() * 0.1f, pText)
    }

    private fun drawButtons(c: Canvas, t: Double) {
        for (b in buttons) {
            b.enabled = when (b.id) {
                "lines-", "lines+", "bet-", "bet+" -> !busy
                "deposit", "withdraw" -> !busy && autoLeft == 0
                else -> true
            }
            b.lit = when (b.id) {
                "sound" -> wallet.soundOn
                "turbo" -> wallet.turbo
                "auto" -> autoLeft != 0
                else -> false
            }
            if (b.round) drawSpinButton(c, b, t) else drawRectButton(c, b)
        }
    }

    private fun drawRectButton(c: Canvas, b: Btn) {
        val down = pressed === b
        val r = b.rect
        val rad = min(r.height(), r.width()) * 0.28f
        val top = when {
            !b.enabled -> 0xFF3A3030.toInt()
            b.lit -> 0xFF00C853.toInt()
            b.id == "deposit" -> 0xFF2E7D32.toInt()
            b.id == "withdraw" -> 0xFFC62828.toInt()
            b.id == "auto" -> 0xFF1565C0.toInt()
            else -> 0xFF8D1010.toInt()
        }
        val bot = if (!b.enabled) 0xFF1A1414.toInt() else darker(top)
        tmpRect.set(r)
        if (down) tmpRect.offset(0f, 3 * u)
        pFill.color = 0xFF000000.toInt(); pFill.alpha = 120
        c.drawRoundRect(r.left, r.top + 5 * u, r.right, r.bottom + 5 * u, rad, rad, pFill)
        pFill.alpha = 255
        pFill.shader = LinearGradient(0f, tmpRect.top, 0f, tmpRect.bottom, lighter(top), bot, Shader.TileMode.CLAMP)
        c.drawRoundRect(tmpRect, rad, rad, pFill)
        pFill.shader = null
        pStroke.strokeWidth = 2.5f * u
        pStroke.color = if (b.enabled) 0xFFFFD54F.toInt() else 0xFF5A4A3A.toInt()
        c.drawRoundRect(tmpRect, rad, rad, pStroke)
        pText.typeface = sansBold
        pText.color = if (b.enabled) Color.WHITE else 0xFF8A7A7A.toInt()
        val label = when (b.id) {
            "sound" -> if (wallet.soundOn) "SOUND ON" else "SOUND OFF"
            "turbo" -> if (wallet.turbo) "TURBO ON" else "TURBO"
            "auto" -> if (autoLeft != 0) (if (autoLeft < 0) "AUTO ∞" else "AUTO $autoLeft") else "AUTO"
            else -> b.label
        }
        val big = b.label.length <= 1
        fitText(label, tmpRect.width() * 0.86f, tmpRect.height() * (if (big) 0.7f else 0.34f))
        c.drawText(label, tmpRect.centerX(), tmpRect.centerY() - (pText.fontMetrics.ascent + pText.fontMetrics.descent) / 2, pText)
    }

    private fun drawSpinButton(c: Canvas, b: Btn, t: Double) {
        val r = b.rect
        val cx = r.centerX(); val cy = r.centerY() + if (pressed === b) 3 * u else 0f
        val rad = r.width() / 2
        val canSpin = phase == Phase.IDLE || phase == Phase.PRESENT
        val glow = if (canSpin && autoLeft == 0 && !wallet.inFreeSpins) (0.5 + 0.5 * sin(t * 4)).toFloat() else 0f
        pFill.color = 0xFFFFD740.toInt()
        pFill.alpha = (60 + 100 * glow).toInt()
        c.drawCircle(cx, cy, rad * (1.08f + 0.05f * glow), pFill)
        pFill.alpha = 255
        pFill.shader = LinearGradient(0f, cy - rad, 0f, cy + rad,
            intArrayOf(0xFFFFF59D.toInt(), 0xFFFFB300.toInt(), 0xFF8D5A00.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad, pFill)
        val stop = phase == Phase.SPINNING || autoLeft != 0
        val inner = if (stop) intArrayOf(0xFFFF8A80.toInt(), 0xFFB71C1C.toInt()) else intArrayOf(0xFF69F0AE.toInt(), 0xFF1B5E20.toInt())
        pFill.shader = RadialGradient(cx, cy - rad * 0.4f, rad * 1.1f, inner, null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, rad * 0.84f, pFill)
        pFill.shader = null
        pText.typeface = serifBold; pText.color = Color.WHITE
        val label = when {
            wallet.inFreeSpins -> "FREE"
            stop -> "STOP"
            else -> "SPIN"
        }
        pText.textSize = rad * 0.46f
        pText.setShadowLayer(6 * u, 0f, 2 * u, 0xFF000000.toInt())
        c.drawText(label, cx, cy - (pText.fontMetrics.ascent + pText.fontMetrics.descent) / 2, pText)
        pText.clearShadowLayer()
    }

    private fun drawMessage(c: Canvas, t: Double) {
        val m = message ?: return
        if (t > messageUntil) { message = null; return }
        pText.typeface = sansBold
        pText.textSize = 30 * u
        val w = pText.measureText(m) + 60 * u
        val cy = reelRect.centerY()
        tmpRect.set(reelRect.centerX() - w / 2, cy - 34 * u, reelRect.centerX() + w / 2, cy + 34 * u)
        pFill.color = 0xE0100000.toInt()
        c.drawRoundRect(tmpRect, 34 * u, 34 * u, pFill)
        pStroke.color = 0xFFFFD54F.toInt(); pStroke.strokeWidth = 3 * u
        c.drawRoundRect(tmpRect, 34 * u, 34 * u, pStroke)
        pText.color = Color.WHITE
        c.drawText(m, reelRect.centerX(), cy + 10 * u, pText)
    }

    private val tierNames = arrayOf("", "BIG WIN", "SUPER WIN", "MEGA WIN", "EPIC WIN")

    private fun drawBigWin(c: Canvas, t: Double) {
        val e = t - phaseStart
        pFill.color = Color.argb((min(1.0, e * 3) * 150).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        // rays
        c.save()
        c.rotate((e * 25).toFloat(), width / 2f, height * 0.45f)
        pFill.color = 0x33FFD54F
        val rays = 16
        val len = max(width, height).toFloat()
        for (i in 0 until rays) {
            path.reset()
            val a0 = (2 * PI * i / rays).toFloat(); val a1 = a0 + (PI / rays).toFloat()
            path.moveTo(width / 2f, height * 0.45f)
            path.lineTo(width / 2f + len * kotlin.math.cos(a0), height * 0.45f + len * sin(a0))
            path.lineTo(width / 2f + len * kotlin.math.cos(a1), height * 0.45f + len * sin(a1))
            path.close()
            c.drawPath(path, pFill)
        }
        c.restore()
        // tier text escalates during rollup like real machines
        val q = ((t - rollStart) / rollDur).coerceIn(0.0, 1.0)
        val shownTier = max(1, (bigTier * min(1.0, q * 1.15 + 0.01)).let { ceil(it).toInt() }).coerceAtMost(bigTier)
        val name = tierNames[shownTier]
        val bounce = (1 + 0.12 * sin(e * 7) * (1 - min(1.0, e / 6))).toFloat()
        val scaleIn = min(1.0, e * 2.5).toFloat()
        c.save()
        c.scale(scaleIn * bounce, scaleIn * bounce, width / 2f, height * 0.38f)
        pTitle.textSize = height * 0.15f
        pTitle.color = 0xFFFF6D00.toInt()
        pTitle.setShadowLayer(30 * u, 0f, 0f, 0xFFFF3D00.toInt())
        c.drawText(name, width / 2f, height * 0.42f, pTitle)
        pTitle.clearShadowLayer()
        pTitle.style = Paint.Style.STROKE; pTitle.strokeWidth = 8 * u; pTitle.color = 0xFF3E0000.toInt()
        c.drawText(name, width / 2f, height * 0.42f, pTitle)
        pTitle.style = Paint.Style.FILL
        pTitle.shader = LinearGradient(0f, height * 0.30f, 0f, height * 0.43f,
            intArrayOf(0xFFFFFDE7.toInt(), 0xFFFFD54F.toInt(), 0xFFFF8F00.toInt()), null, Shader.TileMode.CLAMP)
        c.drawText(name, width / 2f, height * 0.42f, pTitle)
        pTitle.shader = null
        c.restore()
        val v = rollValue(t)
        pTitle.textSize = height * 0.11f
        pTitle.color = 0xFF69F0AE.toInt()
        pTitle.setShadowLayer(20 * u, 0f, 0f, 0xFF00C853.toInt())
        c.drawText(Money.macks(v), width / 2f, height * 0.58f, pTitle)
        pTitle.clearShadowLayer()
        pText.typeface = sansBold; pText.textSize = height * 0.04f; pText.color = 0xFFFFE0B2.toInt()
        c.drawText("MACKS  •  ${Money.describe(v)}", width / 2f, height * 0.65f, pText)
        if (q >= 1) {
            pText.textSize = height * 0.03f; pText.color = 0xAAFFFFFF.toInt()
            c.drawText("TAP TO CONTINUE", width / 2f, height * 0.74f, pText)
        }
    }

    private fun drawFeatureBanner(c: Canvas, t: Double) {
        val e = t - phaseStart
        pFill.color = Color.argb((min(1.0, e * 3) * 170).toInt(), 10, 0, 30)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
        val s = (min(1.0, e * 2.2) * (1 + 0.05 * sin(e * 5))).toFloat()
        c.save()
        c.scale(s, s, width / 2f, height / 2f)
        val bw = width * 0.62f; val bh = height * 0.5f
        tmpRect.set(width / 2f - bw / 2, height / 2f - bh / 2, width / 2f + bw / 2, height / 2f + bh / 2)
        pFill.shader = LinearGradient(0f, tmpRect.top, 0f, tmpRect.bottom, 0xFF4A148C.toInt(), 0xFF12002E.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(tmpRect, 30 * u, 30 * u, pFill)
        pFill.shader = null
        pStroke.strokeWidth = 6 * u
        pStroke.shader = LinearGradient(tmpRect.left, tmpRect.top, tmpRect.right, tmpRect.bottom,
            intArrayOf(0xFFFFF59D.toInt(), 0xFFB8860B.toInt(), 0xFFFFE082.toInt()), null, Shader.TileMode.MIRROR)
        c.drawRoundRect(tmpRect, 30 * u, 30 * u, pStroke)
        pStroke.shader = null
        val outro = featureBanner == "outro"
        val title = when (featureBanner) { "outro" -> "FEATURE COMPLETE"; "resume" -> "FREE SPINS RESUME"; else -> "FREE SPINS!" }
        pTitle.textSize = bh * 0.2f
        pTitle.color = 0xFFFFD54F.toInt()
        pTitle.setShadowLayer(20 * u, 0f, 0f, 0xFFFF6F00.toInt())
        fitTitle(title, bw * 0.9f, bh * 0.2f)
        c.drawText(title, width / 2f, tmpRect.top + bh * 0.3f, pTitle)
        pTitle.clearShadowLayer()
        pText.typeface = sansBold
        if (outro) {
            pTitle.textSize = bh * 0.22f; pTitle.color = 0xFF69F0AE.toInt()
            c.drawText(Money.macks(wallet.freeSpinWinTokens), width / 2f, tmpRect.top + bh * 0.62f, pTitle)
            pText.textSize = bh * 0.07f; pText.color = Color.WHITE
            c.drawText("MACKS WON  •  ${Money.describe(wallet.freeSpinWinTokens)}", width / 2f, tmpRect.top + bh * 0.76f, pText)
        } else {
            pText.textSize = bh * 0.32f
            c.drawText("🐉", width / 2f, tmpRect.top + bh * 0.66f, pText)
            pText.textSize = bh * 0.085f; pText.color = Color.WHITE
            val n = if (featureBanner == "resume") wallet.freeSpinsLeft else GameConfig.FREE_SPINS_AWARD
            c.drawText("$n FREE SPINS  •  ALL WINS ×${GameConfig.FREE_SPIN_MULTIPLIER}", width / 2f, tmpRect.top + bh * 0.82f, pText)
        }
        pText.textSize = bh * 0.055f; pText.color = 0xAAFFFFFF.toInt()
        c.drawText("TAP TO CONTINUE", width / 2f, tmpRect.bottom - bh * 0.06f, pText)
        c.restore()
    }

    private fun fitTitle(text: String, maxW: Float, size: Float) {
        pTitle.textSize = size
        val w = pTitle.measureText(text)
        if (w > maxW) pTitle.textSize = size * maxW / w
    }

    private fun drawRetrigger(c: Canvas, t: Double) {
        val left = retriggerBanner - t
        val a = min(1.0, left).toFloat()
        pTitle.textSize = height * 0.1f
        pTitle.color = 0xFFFFD54F.toInt()
        pTitle.alpha = (255 * a).toInt()
        pTitle.setShadowLayer(25 * u, 0f, 0f, 0xFFAA00FF.toInt())
        c.drawText("+${GameConfig.FREE_SPINS_AWARD} FREE SPINS", width / 2f, reelRect.centerY(), pTitle)
        pTitle.clearShadowLayer()
        pTitle.alpha = 255
    }

    // ================================================================== particles
    private var particleCount = 0

    private fun updateParticles(dt: Float) {
        if (coinRate > 0) {
            coinAcc += coinRate * dt
            while (coinAcc >= 1f) { coinAcc -= 1f; spawnCoin() }
        }
        var alive = 0
        val g = 1500f * u
        for (i in 0 until maxParticles) {
            if (plife[i] <= 0f) continue
            plife[i] -= dt
            pvy[i] += g * dt
            px[i] += pvx[i] * dt
            py[i] += pvy[i] * dt
            pspin[i] += dt * 9f
            if (py[i] > height + 60 * u) plife[i] = 0f
            if (plife[i] > 0f) alive++
        }
        particleCount = alive
    }

    private fun spawnCoin() {
        for (i in 0 until maxParticles) if (plife[i] <= 0f) {
            px[i] = width * (0.3f + 0.4f * cosmeticRng.nextFloat())
            py[i] = height * 0.85f
            pvx[i] = (cosmeticRng.nextFloat() - 0.5f) * 1100f * u
            pvy[i] = -(1100f + cosmeticRng.nextFloat() * 900f) * u
            plife[i] = 3f
            pspin[i] = cosmeticRng.nextFloat() * 6f
            pkind[i] = cosmeticRng.nextInt(3)
            return
        }
    }

    private fun drawParticles(c: Canvas) {
        if (particleCount == 0) return
        val r = 15 * u
        for (i in 0 until maxParticles) {
            if (plife[i] <= 0f) continue
            val sx = abs(sin(pspin[i].toDouble())).toFloat().coerceAtLeast(0.15f)
            c.save()
            c.translate(px[i], py[i])
            c.scale(sx, 1f)
            pFill.color = 0xFF8D5A00.toInt()
            c.drawCircle(0f, 0f, r, pFill)
            pFill.color = if (pkind[i] == 0) 0xFFFFE082.toInt() else 0xFFFFC107.toInt()
            c.drawCircle(0f, 0f, r * 0.82f, pFill)
            pFill.color = 0xFFFFF8E1.toInt()
            c.drawCircle(-r * 0.25f, -r * 0.25f, r * 0.22f, pFill)
            c.restore()
        }
    }

    // ================================================================== utils
    private fun lighter(c: Int): Int = Color.rgb(
        min(255, (Color.red(c) * 1.35f + 30).roundToInt()), min(255, (Color.green(c) * 1.35f + 30).roundToInt()),
        min(255, (Color.blue(c) * 1.35f + 30).roundToInt())
    )

    private fun darker(c: Int): Int = Color.rgb((Color.red(c) * 0.45f).toInt(), (Color.green(c) * 0.45f).toInt(), (Color.blue(c) * 0.45f).toInt())

    fun release() {
        art?.recycle(); bgBase?.recycle(); bgFree?.recycle()
        art = null; bgBase = null; bgFree = null
    }
}

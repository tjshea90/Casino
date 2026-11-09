package com.tjshea.neonreaper.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
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
import android.graphics.SweepGradient
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

// Palette extras for the crypt (the shared hues live in Neon: blood RED, toxic GREEN, ash PURPLE, BONE, BRASS, IRON, INK).
private const val GOLD = 0xFFE9B64B.toInt()
private const val GOLD_HOT = 0xFFFFEFB0.toInt()
private const val GOLD_DEEP = 0xFF7A4A0A.toInt()
private const val BLOOD_HOT = 0xFFFF8A8F.toInt()
private const val BLOOD_DEEP = 0xFF4A0610.toInt()
private const val VIOLET_HOT = 0xFFD9C0FF.toInt()
private const val VIOLET_DEEP = 0xFF241046.toInt()
private const val BONE_HOT = 0xFFFFFAEA.toInt()
private const val BONE_DEEP = 0xFF4A4636.toInt()
private const val SOUL_HOT = 0xFFF2FFD6.toInt()
private const val SOUL_DEEP = 0xFF0F3A06.toInt()
private const val STEEL = 0xFF8E939C.toInt()
private const val STEEL_DARK = 0xFF1C1F25.toInt()
private const val EMBER = 0xFFFF9A3C.toInt()

/**
 * The Soul Vault bonus: a Hold & Spin replay of an already-paid [VaultResult], staged as a dark gothic CRYPT.
 * Everything is driven by a pre-built timeline so a tap can fast-forward safely. All artwork is baked into bitmaps in
 * [onSizeChanged]; [onDraw] only blits sprites and draws a few lines (no allocation, no blur filters per frame).
 */
@SuppressLint("ViewConstructor")
class VaultView(context: Context, private val audio: Audio, private val result: VaultResult,
                /** Price paid for a bought Vault (31x the stake), or 0 when it was won by relics. */
                private val boughtPrice: Long = 0L,
                private val onFinished: (Long) -> Unit) : View(context) {

    /**
     * A bought Vault that pays less than its price must not be celebrated ("no loss disguised as a win"): the summary is
     * quiet (no win stinger, no embers, no big-tier title) and just states the price and the net result.
     */
    private val belowPrice: Boolean = boughtPrice > 0L && result.payTokens < boughtPrice

    /** Optional bake profiler: (stage, milliseconds). Null in production. */
    internal var bakeTrace: ((String, Long) -> Unit)? = null

    /** Mirror of the player's "reduce flashing" option (Wallet.reduceFlash): softer flicker, no thud, slower idle redraw. */
    var calm = false

    private enum class Kind { RESPIN_START, LAND, COLSTOP, COUNT, TALLY, GRAND, SUMMARY }
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
    private var jackpotKind = 0              // 0 MINI .. 2 MAJOR (visual only)

    // visual-only state
    private var reaperAt = -10.0             // vt of the last Reaper landing: wakes the looming figure
    private var lastLandAt = -10.0
    private var shownMult = 0f               // eased running total (multiples of the bonus bet)

    // clocks
    private var started = false
    private var startReal = 0.0
    private var vt = 0.0             // virtual timeline time
    private var lastReal = 0.0
    private var lastFrame = 0.0
    private var speed = 1.0
    private var outroAt = -1.0
    private var finished = false
    private var introStart = 0.0

    // test hooks (read-only views of the timeline state)
    internal val clock: Double get() = vt
    internal val collecting: Boolean get() = collectTo >= 0
    internal val jackpotAge: Double get() = vt - jackpotFlash
    internal val grandAge: Double get() = if (grandAt < 0) -1.0 else vt - grandAt
    internal val summaryAge: Double get() = if (summaryAt < 0) -1.0 else vt - summaryAt
    internal val talliedCount: Int get() = tallied.count { it }
    internal val spinning: Boolean get() = spinStepStart >= 0
    internal val begun: Boolean get() = started
    internal val leaving: Boolean get() = outroAt > 0
    internal val reaperAge: Double get() = vt - reaperAt
    internal val quietSummary: Boolean get() = belowPrice
    internal val emberRainLevel: Float get() = rainLevel
    /** The win stinger chosen when the summary appeared (null when it was skipped). */
    internal var summaryStinger: Sfx? = null
        private set
    internal val tallyMult: Int get() = totalMult
    internal val liveCells: Array<Orb?> get() = cells
    internal fun liveValue(i: Int): Int = cellValue[i]

    // ------------------------------------------------------------------ layout (pixels)
    private var portrait = false
    private var vw = 0f
    private var vh = 0f
    private var u = 1f
    private var ox = 0f
    private var oy = 0f
    private val altarRect = RectF()
    private val gridRect = RectF()
    private var cellW = 0f
    private var cellH = 0f
    private val jpRect = Array(4) { RectF() }          // index = jackpot tier: 0 MINI .. 3 GRAND
    private val respinRect = RectF()
    private val totalRect = RectF()
    private val betRect = RectF()
    private val spinBtn = RectF()
    private var hoodX = 0f
    private var hoodY = 0f
    private var figK = 80f
    private var figCx = 0f
    private var figCy = 0f
    private var plateM = 0f
    private var altarX = 0f
    private var altarY = 0f
    private val pillarL = RectF()
    private val pillarR = RectF()

    // fire points (braziers / candles): x, y, size
    private val fireX = FloatArray(8)
    private val fireY = FloatArray(8)
    private val fireS = FloatArray(8)
    private var fireN = 0

    // ------------------------------------------------------------------ baked art
    private val owned = ArrayList<Bitmap>()
    private var ready = false
    private lateinit var bgBack: Bitmap
    private lateinit var noise: Bitmap
    private lateinit var altarBmp: Bitmap
    private lateinit var barsBmp: Bitmap
    private val plates = arrayOfNulls<Bitmap>(7)      // 0..3 jackpots, 4 respins, 5 total, 6 bet
    private lateinit var glowBmp: Bitmap
    private lateinit var soulBmp: Array<Bitmap>
    private lateinit var sealBmp: Array<Bitmap>
    private lateinit var reaperOrb: Bitmap
    private lateinit var reaperFig: Bitmap
    private lateinit var flameBmp: Array<Bitmap>
    private lateinit var fireBmp: Array<Bitmap>
    private lateinit var fogBmp: Bitmap
    private lateinit var pipOn: Bitmap
    private lateinit var pipOff: Bitmap
    private lateinit var spinOn: Bitmap
    private lateinit var spinOff: Bitmap
    private var raysCache: Bitmap? = null
    private val raysBmp: Bitmap get() = raysCache ?: raysSprite(512).also { raysCache = it }
    private lateinit var crescent: Bitmap
    private lateinit var mistBmp: Bitmap
    private lateinit var chainBmp: Bitmap
    private var introTitle: Bitmap? = null
    private var doorBmp: Bitmap? = null
    private var doorSlammed = false
    private var introPlate: Bitmap? = null
    private var grandTitle: Bitmap? = null
    private var summaryTitle: Bitmap? = null
    private var summaryPlate: Bitmap? = null
    private var summaryTier = -1

    // ------------------------------------------------------------------ paints & scratch (never allocated while drawing)
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; strokeJoin = Paint.Join.ROUND }
    private val pBmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pAdd = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD) }
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val serifIt = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    private val tmp = RectF()
    private val dst = RectF()
    private val srcR = android.graphics.Rect()
    private val chainDx = floatArrayOf(-3.1f, -2.05f, 2.05f, 3.1f)
    private val path = Path()
    private val mtx = Matrix()
    private val nb = CharArray(32)
    private var nbStart = 0
    private val fx = Random(4242)

    private val tintWarm = PorterDuffColorFilter(EMBER, PorterDuff.Mode.SRC_IN)
    private val tintGreen = PorterDuffColorFilter(Neon.GREEN, PorterDuff.Mode.SRC_IN)
    private val tintRed = PorterDuffColorFilter(Neon.RED, PorterDuff.Mode.SRC_IN)
    private val tintViolet = PorterDuffColorFilter(Neon.PURPLE, PorterDuff.Mode.SRC_IN)
    private val tintGold = PorterDuffColorFilter(GOLD, PorterDuff.Mode.SRC_IN)
    private val tintBone = PorterDuffColorFilter(Neon.BONE, PorterDuff.Mode.SRC_IN)
    private val tintFog = PorterDuffColorFilter(0xFF8CA396.toInt(), PorterDuff.Mode.SRC_IN)
    private val jpTint: Array<ColorFilter> = arrayOf(tintBone, tintViolet, tintRed, tintGold)

    private val jpNames = arrayOf("MINI", "MINOR", "MAJOR", "GRAND")
    private val jpBang = arrayOf("MINI!", "MINOR!", "MAJOR!", "GRAND!")
    private val jpMult = intArrayOf(ReaperConfig.MINI, ReaperConfig.MINOR, ReaperConfig.MAJOR, ReaperConfig.GRAND)
    private val jpColor = intArrayOf(Neon.BONE, Neon.PURPLE, Neon.RED, GOLD)
    private val jpHot = intArrayOf(BONE_HOT, VIOLET_HOT, BLOOD_HOT, GOLD_HOT)

    // ambient particles
    private val emN = 40
    private val emX = FloatArray(emN); private val emY = FloatArray(emN); private val emVx = FloatArray(emN)
    private val emVy = FloatArray(emN); private val emLife = FloatArray(emN); private val emSz = FloatArray(emN)
    private val emGreen = BooleanArray(emN)
    private val rainN = 110
    private val rnX = FloatArray(rainN); private val rnY = FloatArray(rainN); private val rnVy = FloatArray(rainN)
    private val rnSz = FloatArray(rainN); private val rnPh = FloatArray(rainN); private val rnOn = BooleanArray(rainN)
    private var rainLevel = 0f
    private val fogN = 9
    private val fogX = FloatArray(fogN); private val fogY = FloatArray(fogN); private val fogW = FloatArray(fogN)
    private val fogV = FloatArray(fogN); private val fogA = FloatArray(fogN)
    // tally sparks: souls streaming into the total plate
    private val spN = 24
    private val spX = FloatArray(spN); private val spY = FloatArray(spN); private val spT = DoubleArray(spN) { -1.0 }
    private val spGold = BooleanArray(spN)
    private var bumpAt = -10.0

    init {
        isClickable = true
        buildTimeline()
    }

    private fun now() = SystemClock.uptimeMillis() / 1000.0

    /** Initial orbs drop in automatically; every respin after that waits for the SPIN button. */
    private fun buildTimeline() {
        var t = 0.6
        for (l in result.initial) { events += Ev(t, Kind.LAND, l.cell, l.orb, null); t += 0.28 }
    }

    private var nextStep = 0
    internal val spinButton: RectF get() = spinBtn
    internal val awaitingSpin: Boolean get() = waitingForSpin
    private var spinPressedAt = -10.0

    /** True when the game is waiting for the player to press SPIN. */
    private val waitingForSpin get() = started && evIndex >= events.size && nextStep < result.steps.size && summaryAt < 0

    /** Queues the events for the next respin, starting now. */
    private fun startRespin() {
        val si = nextStep++
        val step = result.steps[si]
        val chunk = ArrayList<Ev>()
        var t = vt + 0.05
        chunk += Ev(t, Kind.RESPIN_START, step = si)
        val reveal = t + 0.9
        var extra = 0.0
        val byCol = step.landings.groupBy { it.cell % VAULT_COLS }
        for (col in 0 until VAULT_COLS) {
            val ct = reveal + col * 0.17 + extra
            chunk += Ev(ct - 0.001, Kind.COLSTOP, value = col)
            for (l in byCol[col].orEmpty().sortedBy { it.cell }) {
                chunk += Ev(ct, Kind.LAND, l.cell, l.orb, l.collected, step = si)
                if (l.orb.kind == Orb.REAPER) extra += 1.3
                else if (l.orb.kind != Orb.SOUL) extra += 0.7
            }
        }
        t = reveal + VAULT_COLS * 0.17 + extra + 0.25
        chunk += Ev(t, Kind.COUNT, value = step.respinsAfter, step = si)
        if (nextStep >= result.steps.size) {
            // last respin: tally the vault and show the summary
            t += 1.0
            for (c in 0 until SoulVault.CELLS) {
                val o = result.cells[c] ?: continue
                chunk += Ev(t, Kind.TALLY, c)
                t += if (o.kind == Orb.SOUL) 0.16 else 0.5
            }
            if (result.grand) { t += 0.4; chunk += Ev(t, Kind.GRAND); t += 3.0 }
            chunk += Ev(t + 0.6, Kind.SUMMARY)
        }
        chunk.sortBy { it.time }
        events += chunk
    }

    private var orbStep = 0; private var tallyStep = 0
    private fun jackpotSfx(i: Int) = when (i.coerceIn(0, 3)) { 0 -> Sfx.JACKPOT_MINI; 1 -> Sfx.JACKPOT_MINOR; 2 -> Sfx.JACKPOT_MAJOR; else -> Sfx.JACKPOT_GRAND }

    // ================================================================== timeline
    private fun process() {
        while (evIndex < events.size && events[evIndex].time <= vt) {
            val e = events[evIndex++]
            when (e.kind) {
                Kind.RESPIN_START -> {
                    if (e.step == result.steps.size - 1) prebakeFinale()
                    spinStepStart = e.time
                    for (c in 0 until SoulVault.CELLS) revealAt[c] = if (cells[c] == null) e.time + 0.9 + (c % VAULT_COLS) * 0.17 else -1.0
                    audio.play(Sfx.SPIN_START, 0.5f)
                    orbStep = 0
                }
                Kind.COLSTOP -> audio.play(Sfx.STOP, 0.55f, 1.1f)
                Kind.LAND -> {
                    val o = e.orb!!
                    cells[e.cell] = o
                    landedAt[e.cell] = vt
                    lastLandAt = vt
                    revealAt[e.cell] = -1.0
                    when (o.kind) {
                        Orb.SOUL -> { cellValue[e.cell] = o.value; audio.play(Sfx.ORB_LAND, 0.8f, Audio.scaleRate(orbStep.coerceAtMost(9))); orbStep++ }
                        Orb.REAPER -> {
                            cellValue[e.cell] = 0
                            collectFrom = e.collected; collectTo = e.cell; collectAt = vt; reaperAt = vt
                            audio.play(Sfx.GROWL); audio.play(Sfx.SLASH)
                        }
                        else -> {
                            cellValue[e.cell] = o.value
                            jackpotName = jpNames[o.kind - 1]; jackpotFlash = vt; jackpotKind = o.kind - 1
                            audio.play(jackpotSfx(o.kind - 1))
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
                    val k = result.cells[e.cell]!!.kind
                    spawnSpark(e.cell, k != Orb.SOUL)
                    if (k == Orb.SOUL) { audio.play(Sfx.WIN_TICK, 0.5f, Audio.scaleRate(tallyStep.coerceAtMost(9))); tallyStep++ } else audio.play(jackpotSfx(k - 1), 0.7f)
                }
                Kind.GRAND -> { grandAt = vt; totalMult += ReaperConfig.GRAND; audio.play(Sfx.JACKPOT_GRAND) }
                Kind.SUMMARY -> {
                    summaryAt = vt
                    val x = result.payTokens.toDouble() / result.bet
                    val stinger = if (belowPrice) null else when { x >= 100 -> Sfx.WIN_APOCALYPSE; x >= 50 -> Sfx.WIN_EPIC; x >= 25 -> Sfx.WIN_MEGA; x >= 10 -> Sfx.WIN_BIG; x >= 3 -> Sfx.WIN_MED; x >= 1 -> Sfx.WIN else -> Sfx.WIN_SMALL }
                    summaryStinger = stinger
                    if (stinger != null) audio.play(stinger)
                }
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
        if (!ready) return true
        if (e.actionMasked == MotionEvent.ACTION_DOWN && waitingForSpin && hitSpin(e.x, e.y)) {
            spinPressedAt = now(); audio.play(Sfx.PRESS); invalidate()
        }
        if (e.actionMasked != MotionEvent.ACTION_UP) return true
        when {
            !started -> { audio.play(Sfx.CLICK, 0.5f); started = true; startReal = now(); lastReal = startReal }
            summaryAt >= 0 && outroAt < 0 -> if (vt - summaryAt > 0.8) outroAt = now()
            waitingForSpin && hitSpin(e.x, e.y) -> { audio.play(Sfx.SPIN, 0.8f); startRespin() }
        }
        return true
    }

    private fun hitSpin(x: Float, y: Float): Boolean {
        tmp.set(spinBtn); tmp.inset(-16 * u, -16 * u); return tmp.contains(x, y)
    }

    // ================================================================== layout
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w == 0 || h == 0) return
        ready = false
        recycleAll()
        computeLayout(w.toFloat(), h.toFloat())
        planBake(w, h)
        // Attached to a window (the real app): bake one stage per frame behind the host's white flash so a slow tablet never
        // freezes for a second. Off-window (unit tests, previews): bake everything now, deterministically.
        if (isAttachedToWindow || forceSlicedBake) postInvalidateOnAnimation() else while (!ready) bakeNext()
    }

    /** Test hook: take the on-device (one stage per frame) bake path even though the view is not attached to a window. */
    internal var forceSlicedBake = false
    internal val isReady: Boolean get() = ready
    private var released = false
    private val bakeSteps = ArrayList<() -> Unit>()
    private var bakeIdx = 0

    private fun bakeNext() {
        if (bakeIdx < bakeSteps.size) bakeSteps[bakeIdx++]()
        if (bakeIdx >= bakeSteps.size && !ready) { initAmbient(vw, vh); ready = true }
    }

    private fun rx(v: Float) = ox + v * u
    private fun ry(v: Float) = oy + v * u

    private fun computeLayout(W: Float, H: Float) {
        vw = W; vh = H
        portrait = H > W
        if (!portrait) {
            u = min(W / 1280f, H / 800f)
            ox = (W - 1280f * u) / 2f; oy = (H - 800f * u) / 2f
            altarRect.set(rx(302f), ry(184f), rx(978f), ry(666f))
            gridRect.set(rx(320f), ry(226f), rx(960f), ry(650f))
            jpRect[3].set(rx(32f), ry(40f), rx(268f), ry(212f))
            jpRect[2].set(rx(32f), ry(224f), rx(268f), ry(350f))
            jpRect[1].set(rx(32f), ry(362f), rx(268f), ry(488f))
            jpRect[0].set(rx(32f), ry(500f), rx(268f), ry(626f))
            respinRect.set(rx(1012f), ry(40f), rx(1248f), ry(232f))
            val sr = 96f * u
            spinBtn.set(rx(1130f) - sr, ry(430f) - sr, rx(1130f) + sr, ry(430f) + sr)
            betRect.set(rx(1012f), ry(596f), rx(1248f), ry(676f))
            totalRect.set(rx(352f), ry(686f), rx(928f), ry(776f))
            hoodX = W / 2f; hoodY = ry(128f); figK = 78f * u
            pillarL.set(rx(262f), ry(236f), rx(298f), ry(722f)); pillarR.set(rx(982f), ry(236f), rx(1018f), ry(722f))
            fireN = 0
            addFire(rx(150f), ry(758f), 1.15f * u); addFire(rx(1130f), ry(762f), 1.15f * u)
            addFire(rx(280f), ry(262f), 0.8f * u); addFire(rx(1000f), ry(262f), 0.8f * u)
        } else {
            u = min(W / 800f, H / 1280f)
            ox = (W - 800f * u) / 2f; oy = H - 1280f * u
            for (i in 0 until 4) jpRect[i].set(rx(41f + i * 182f), ry(330f), rx(41f + i * 182f + 172f), ry(458f))
            altarRect.set(rx(40f), ry(472f), rx(760f), ry(996f))
            gridRect.set(rx(56f), ry(512f), rx(744f), ry(982f))
            totalRect.set(rx(40f), ry(1010f), rx(760f), ry(1100f))
            respinRect.set(rx(40f), ry(1120f), rx(286f), ry(1256f))
            betRect.set(rx(514f), ry(1120f), rx(760f), ry(1256f))
            val sr = 68f * u
            spinBtn.set(rx(400f) - sr, ry(1174f) - sr, rx(400f) + sr, ry(1174f) + sr)
            hoodX = W / 2f; hoodY = ry(205f); figK = 100f * u
            pillarL.set(rx(0f), ry(300f), rx(34f), ry(1280f)); pillarR.set(rx(766f), ry(300f), rx(800f), ry(1280f))
            fireN = 0
            addFire(rx(60f), ry(300f), 1.0f * u); addFire(rx(740f), ry(300f), 1.0f * u)
            addFire(rx(150f), ry(1012f), 0.7f * u); addFire(rx(650f), ry(1012f), 0.7f * u)
        }
        cellW = gridRect.width() / VAULT_COLS
        cellH = gridRect.height() / VAULT_ROWS
        plateM = 12f * u
    }

    private fun addFire(x: Float, y: Float, s: Float) { fireX[fireN] = x; fireY[fireN] = y; fireS[fireN] = s; fireN++ }

    private fun cellRect(i: Int, out: RectF) {
        val x = gridRect.left + (i % VAULT_COLS) * cellW; val y = gridRect.top + (i / VAULT_COLS) * cellH
        out.set(x, y, x + cellW, y + cellH)
    }

    private fun initAmbient(W: Float, H: Float) {
        for (i in 0 until emN) { emLife[i] = 0f }
        for (i in 0 until rainN) { rnOn[i] = false; rnY[i] = -50f }
        for (i in 0 until fogN) {
            fogW[i] = (0.35f + fx.nextFloat() * 0.35f) * max(W, H)
            fogX[i] = fx.nextFloat() * (W + fogW[i]) - fogW[i]
            fogY[i] = H * (0.18f + 0.80f * fx.nextFloat())
            fogV[i] = (if (fx.nextBoolean()) 1f else -1f) * (4f + fx.nextFloat() * 9f) * u
            fogA[i] = 0.07f + fx.nextFloat() * 0.09f
        }
        for (i in 0 until spN) spT[i] = -1.0
    }

    // ================================================================== baking
    private fun recycleAll() {
        for (b in owned) if (!b.isRecycled) b.recycle()
        owned.clear()
        for (i in plates.indices) plates[i] = null
        introTitle = null; introPlate = null; grandTitle = null; summaryTitle = null; summaryPlate = null; summaryTier = -1; doorBmp = null; raysCache = null; finaleStep = 0
        for (k in jpTitle.indices) jpTitle[k] = null
    }

    private fun mk(w: Int, h: Int, draw: (Canvas) -> Unit): Bitmap {
        val b = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        draw(Canvas(b)); owned += b; return b
    }

    private fun withA(c: Int, a: Int) = (a.coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)
    private fun shade(c: Int, f: Float) = Color.argb(Color.alpha(c), (Color.red(c) * f).toInt().coerceIn(0, 255), (Color.green(c) * f).toInt().coerceIn(0, 255), (Color.blue(c) * f).toInt().coerceIn(0, 255))
    private fun mix(a: Int, b: Int, t: Float): Int {
        val q = t.coerceIn(0f, 1f)
        return Color.argb((Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * q).toInt(), (Color.red(a) + (Color.red(b) - Color.red(a)) * q).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * q).toInt(), (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * q).toInt())
    }
    private fun smooth(x: Float): Float { val q = x.coerceIn(0f, 1f); return q * q * (3f - 2f * q) }

    private fun stage(name: String, f: () -> Unit) {
        bakeSteps += {
            val tr = bakeTrace
            if (tr == null) f() else { val t0 = System.nanoTime(); f(); tr(name, (System.nanoTime() - t0) / 1_000_000) }
        }
    }

    private var spriteSize = 16

    private fun planBake(w: Int, h: Int) {
        bakeSteps.clear(); bakeIdx = 0
        stage("noise+glow+fog") {
            noise = makeNoise()
            glowBmp = mk(128, 128) { c ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = RadialGradient(64f, 64f, 64f, intArrayOf(0xFFFFFFFF.toInt(), 0x6EFFFFFF, 0x1CFFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.3f, 0.65f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(64f, 64f, 64f, p)
            }
            fogBmp = mk(256, 128) { c ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                c.save(); c.scale(1f, 0.5f)
                p.shader = RadialGradient(128f, 128f, 128f, intArrayOf(0x88FFFFFF.toInt(), 0x40FFFFFF, 0x00FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                c.drawCircle(128f, 128f, 128f, p); c.restore()
            }
        }
        stage("flames+fire+chain") {
            flameBmp = Array(6) { flameFrame(it) }
            fireBmp = Array(6) { fireSheet(it) }
            chainBmp = chainSprite((14 * u).toInt().coerceAtLeast(8), (220 * u).toInt().coerceAtLeast(40))
        }
        stage("bgBack") { bgBack = bakeBack(w, h) }
        stage("altar") { altarBmp = bakeAltar() }
        stage("bars") { barsBmp = bakeBars() }
        stage("orbs+seals+reaper") {
            val os = (min(cellW, cellH) * 1.05f).toInt().coerceAtLeast(16)
            spriteSize = os
            soulBmp = Array(3) { soulSprite(os, it) }
            val ss = max(os, (jpRect[3].height() * 0.9f).toInt())
            sealBmp = arrayOf(
                sealSprite(ss, Neon.BONE, BONE_HOT, BONE_DEEP, STEEL, STEEL_DARK, false),
                sealSprite(ss, Neon.PURPLE, VIOLET_HOT, VIOLET_DEEP, STEEL, STEEL_DARK, false),
                sealSprite(ss, Neon.RED, BLOOD_HOT, BLOOD_DEEP, 0xFFC8A24C.toInt(), 0xFF3C2C0C.toInt(), false),
                sealSprite(ss, GOLD, GOLD_HOT, GOLD_DEEP, GOLD_HOT, 0xFF5A3A08.toInt(), true))
            reaperOrb = reaperOrbSprite(os)
        }
        stage("figure") { reaperFig = bakeFigure() }
        stage("pips+spin+misc") {
            val pip = (min(respinRect.width(), respinRect.height()) * 0.34f).toInt().coerceAtLeast(16)
            pipOn = skullPip(pip, true); pipOff = skullPip(pip, false)
            spinOn = spinSprite((spinBtn.width() * 1.25f).toInt(), true)
            spinOff = spinSprite((spinBtn.width() * 1.25f).toInt(), false)
            crescent = crescentSprite((70 * u).toInt().coerceAtLeast(24))
            mistBmp = mk(max(8, cellW.toInt()), max(8, cellH.toInt())) { c ->
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                p.shader = LinearGradient(0f, 0f, 0f, cellH, intArrayOf(0x00000000, 0x3076FF03, 0x00000000), null, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, cellW, cellH, p)
            }
        }
        stage("plates") { bakePlates() }
        stage("intro") { bakeIntro(); for (k in 0..2) jpTitleFor(k) }
    }

    private fun makeNoise(): Bitmap {
        val s = 96; val px = IntArray(s * s); val r = Random(3)
        for (i in px.indices) {
            val a = if (r.nextInt(3) == 0) r.nextInt(46) else 0
            px[i] = if (r.nextBoolean()) Color.argb(a, 255, 255, 245) else Color.argb(a, 0, 0, 0)
        }
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888); b.setPixels(px, 0, s, 0, 0, s, s); owned += b; return b
    }

    // ------------------------------------------------------------------ the crypt itself
    private fun masonry(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, seed: Long, base: Int) {
        val p = Paint()
        val r = Random(seed)
        val bh = 46f * u
        var y = y0 - r.nextFloat() * bh
        while (y < y1) {
            var x = x0 - r.nextFloat() * 90f * u
            while (x < x1) {
                val bw = (80f + r.nextFloat() * 90f) * u
                val s = base + r.nextInt(16)
                p.color = Color.rgb(s, s + 4, s + 2)
                c.drawRect(x + 1.5f * u, y + 1.5f * u, x + bw - 1.5f * u, y + bh - 1.5f * u, p)
                p.color = Color.argb(44, 255, 255, 235); c.drawRect(x + 1.5f * u, y + 1.5f * u, x + bw - 1.5f * u, y + 3.4f * u, p)
                p.color = Color.argb(120, 0, 0, 0); c.drawRect(x + 1.5f * u, y + bh - 4.5f * u, x + bw - 1.5f * u, y + bh - 1.5f * u, p)
                if (r.nextInt(5) == 0) { p.color = Color.argb(40, 40, 90, 50); c.drawRect(x + bw * 0.1f, y + bh * 0.2f, x + bw * (0.3f + r.nextFloat() * 0.5f), y + bh * 0.85f, p) }
                x += bw
            }
            y += bh
        }
    }

    private fun archPath(out: Path, x0: Float, x1: Float, yBottom: Float, ySpring: Float, yApex: Float) {
        val xm = (x0 + x1) / 2f; val w = x1 - x0; val h = ySpring - yApex
        out.reset()
        out.moveTo(x0, yBottom); out.lineTo(x0, ySpring)
        out.cubicTo(x0, ySpring - h * 0.62f, xm - w * 0.20f, yApex + h * 0.18f, xm, yApex)
        out.cubicTo(xm + w * 0.20f, yApex + h * 0.18f, x1, ySpring - h * 0.62f, x1, ySpring)
        out.lineTo(x1, yBottom); out.close()
    }

    private fun bakeBack(w: Int, h: Int): Bitmap = mk(w, h) { c ->
        val W = w.toFloat(); val H = h.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF040506.toInt(); c.drawRect(0f, 0f, W, H, p)
        masonry(c, 0f, 0f, W, H, 5, 20)
        p.alpha = 255; p.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); p.alpha = 110
        c.drawRect(0f, 0f, W, H, p); p.shader = null; p.alpha = 255
        // damp streaks running down the walls
        val r = Random(21)
        for (k in 0 until 14) {
            val x = r.nextFloat() * W; val sw = (10f + r.nextFloat() * 34f) * u; val sh = (140f + r.nextFloat() * 380f) * u
            p.alpha = 255; p.shader = LinearGradient(0f, 0f, 0f, sh, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
            c.save(); c.translate(x, 0f); c.drawRect(0f, 0f, sw, sh, p); c.restore()
        }
        p.shader = null
        // ---- the alcove: a pointed arch between two pillars, with the looming Reaper inside
        val ax0 = pillarL.right; val ax1 = pillarR.left
        val ySpring = if (portrait) ry(300f) else ry(262f)
        val yApex = if (portrait) ry(-10f) else ry(-6f)
        val yBottom = if (portrait) ry(1000f) else ry(700f)
        val arch = Path(); archPath(arch, ax0, ax1, yBottom, ySpring, yApex)
        p.alpha = 255; p.shader = LinearGradient(0f, yApex, 0f, yBottom, intArrayOf(0xFF0A1311.toInt(), 0xFF060A09.toInt(), 0xFF030404.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(arch, p); p.shader = null
        c.save(); c.clipPath(arch)
        // sickly haze behind the figure + violet wash high up
        p.alpha = 255; p.shader = RadialGradient(hoodX, hoodY - figK * 0.2f, figK * 4.4f, intArrayOf(0x5A2F7A62, 0x28234F4A, 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(ax0, yApex, ax1, yBottom, p)
        p.alpha = 255; p.shader = RadialGradient(hoodX, hoodY - figK * 1.2f, figK * 4.0f, intArrayOf(0x44503A7E, 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(ax0, yApex, ax1, yBottom, p); p.shader = null
        // faint masonry inside the recess
        p.color = 0x22000000; c.drawRect(ax0, yApex, ax1, yBottom, p)
        c.restore()
        // voussoir ring: thick stone stroke with dashed joints and highlight edges
        val ring = 30f * u
        p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.MITER; p.strokeWidth = ring; p.color = 0xFF2B302D.toInt()
        arch.reset(); archPath(arch, ax0, ax1, yBottom + 40f, ySpring, yApex)
        c.drawPath(arch, p)
        p.pathEffect = DashPathEffect(floatArrayOf(2.4f * u, 31f * u), 0f); p.color = 0xFF070908.toInt(); p.strokeWidth = ring + 2f
        c.drawPath(arch, p); p.pathEffect = null
        p.strokeWidth = 2.2f * u; p.color = 0x40FFFFF0
        c.save(); c.translate(0f, -ring * 0.5f); c.drawPath(arch, p); c.restore()
        p.color = 0xAA000000.toInt()
        c.save(); c.translate(0f, ring * 0.5f); c.drawPath(arch, p); c.restore()
        p.style = Paint.Style.FILL
        // keystone with a skull
        val kx = (ax0 + ax1) / 2f
        p.color = 0xFF3A403B.toInt()
        c.drawRoundRect(kx - 26f * u, yApex - 20f * u, kx + 26f * u, yApex + 44f * u, 4f * u, 4f * u, p)
        skullGlyph(c, kx, yApex + 14f * u, 38f * u, 0xFF0A0C0B.toInt(), 0x55D9D2BE, 0x00000000)
        // ---- pillars
        pillar(c, pillarL); pillar(c, pillarR)
        // ---- iron-bound door leaves at the screen edges
        doorLeaf(c, 0f, true); doorLeaf(c, W, false)
        // ---- floor: dark flagstones fading in under the altar
        val fy = if (portrait) ry(996f) else ry(690f)
        p.alpha = 255; p.shader = LinearGradient(0f, fy - 30f * u, 0f, H, intArrayOf(0x00000000, 0xFF0B0D0C.toInt(), 0xFF030404.toInt()), floatArrayOf(0f, 0.25f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, fy - 30f * u, W, H, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * u; p.color = 0x55000000
        var yy = fy
        var step = 16f * u
        while (yy < H) { c.drawLine(0f, yy, W, yy, p); yy += step; step *= 1.25f }
        val vx = W / 2f
        for (k in -8..8) c.drawLine(vx + k * 60f * u, fy, vx + k * 190f * u, H, p)
        p.style = Paint.Style.FILL
        // braziers (static iron) and candelabra on the pillars
        for (i in 0 until fireN) if (fireS[i] > 0.9f * u) brazier(c, fireX[i], fireY[i], fireS[i]) else candelabra(c, fireX[i], fireY[i], fireS[i])
        // static warm light pools around the fires
        for (i in 0 until fireN) {
            p.alpha = 255; p.shader = RadialGradient(fireX[i], fireY[i] - 30f * u, 300f * u * (fireS[i] / u).coerceAtLeast(0.7f), intArrayOf(0x40FF8A30, 0x18C05A1A, 0x00000000), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, p)
        }
        p.shader = null
        // vignette (everything dynamic is drawn on top of this, so the edges stay dark and the centre readable)
        p.alpha = 255; p.shader = RadialGradient(W / 2f, H * 0.46f, max(W, H) * 0.72f, intArrayOf(0x00000000, 0x30000000, 0xE0000000.toInt()), floatArrayOf(0.35f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, p); p.shader = null
    }

    private fun pillar(c: Canvas, r: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.alpha = 255; p.shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(0xFF0A0C0B.toInt(), 0xFF3B413C.toInt(), 0xFF202421.toInt(), 0xFF080A09.toInt()), floatArrayOf(0f, 0.3f, 0.62f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(r, p); p.shader = null
        p.color = 0x33000000
        for (k in 1..2) c.drawRect(r.left + r.width() * k / 3f - 1f * u, r.top, r.left + r.width() * k / 3f + 1f * u, r.bottom, p)
        // capital: brass band + stone block
        val cap = 16f * u
        p.alpha = 255; p.shader = LinearGradient(0f, r.top, 0f, r.top + cap, intArrayOf(Neon.BRASS, Neon.BRASS_DIM, 0xFF2A1F08.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(r.left - 5f * u, r.top, r.right + 5f * u, r.top + cap, p); p.shader = null
        p.color = 0xFF1B1E1C.toInt(); c.drawRect(r.left - 3f * u, r.top - 12f * u, r.right + 3f * u, r.top, p)
        // iron straps with rivets
        for (k in 0 until 3) {
            val y = r.top + (r.height() * (0.18f + 0.30f * k))
            p.color = 0xFF111317.toInt(); c.drawRect(r.left - 1f * u, y, r.right + 1f * u, y + 10f * u, p)
            p.color = 0x55FFFFFF; c.drawRect(r.left - 1f * u, y, r.right + 1f * u, y + 1.4f * u, p)
            p.color = 0xFF6A6E75.toInt()
            c.drawCircle(r.left + 5f * u, y + 5f * u, 1.8f * u, p); c.drawCircle(r.right - 5f * u, y + 5f * u, 1.8f * u, p)
        }
        // base
        p.color = 0xFF191C1A.toInt(); c.drawRect(r.left - 6f * u, r.bottom - 22f * u, r.right + 6f * u, r.bottom, p)
    }

    private fun doorLeaf(c: Canvas, edge: Float, left: Boolean) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val w = (if (portrait) 22f else 40f) * u
        val x0 = if (left) edge else edge - w
        val rnd = Random(if (left) 5 else 6)
        // planks
        var x = x0
        while (x < x0 + w) {
            val pw = 12f * u
            val s = 20 + rnd.nextInt(14)
            p.color = Color.rgb(s + 6, s, s - 4)
            c.drawRect(x, 0f, min(x + pw, x0 + w), vh, p)
            p.color = 0x66000000; c.drawRect(x, 0f, x + 1.4f * u, vh, p)
            x += pw
        }
        // inner shadow toward the crypt
        p.alpha = 255; p.shader = LinearGradient(if (left) x0 + w else x0, 0f, if (left) x0 + w + 40f * u else x0 - 40f * u, 0f, 0xAA000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
        c.drawRect(if (left) x0 + w else x0 - 40f * u, 0f, if (left) x0 + w + 40f * u else x0, vh, p); p.shader = null
        // strap hinges
        val reach = (if (portrait) 70f else 120f) * u
        for (yy in floatArrayOf(0.14f, 0.52f, 0.86f)) {
            val y = vh * yy
            val sx0 = if (left) x0 else x0 - (reach - w)
            val sx1 = if (left) x0 + reach else x0 + w
            val tipX = if (left) sx1 else sx0
            path.reset()
            if (left) { path.moveTo(sx0, y - 11f * u); path.lineTo(sx1 - 16f * u, y - 7f * u); path.lineTo(tipX + 6f * u, y); path.lineTo(sx1 - 16f * u, y + 7f * u); path.lineTo(sx0, y + 11f * u) }
            else { path.moveTo(sx1, y - 11f * u); path.lineTo(sx0 + 16f * u, y - 7f * u); path.lineTo(tipX - 6f * u, y); path.lineTo(sx0 + 16f * u, y + 7f * u); path.lineTo(sx1, y + 11f * u) }
            path.close()
            p.alpha = 255; p.shader = LinearGradient(0f, y - 11f * u, 0f, y + 11f * u, intArrayOf(0xFF3A3E46.toInt(), 0xFF111318.toInt(), 0xFF07080A.toInt()), null, Shader.TileMode.CLAMP)
            c.drawPath(path, p); p.shader = null
            p.color = 0xFF8A8E96.toInt()
            for (k in 0 until 4) { val rxp = if (left) sx0 + (12f + k * 24f) * u else sx1 - (12f + k * 24f) * u; c.drawCircle(rxp, y, 2.4f * u, p) }
        }
    }

    private fun brazier(c: Canvas, cx: Float, y: Float, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // tripod legs
        p.style = Paint.Style.STROKE; p.strokeWidth = 7f * s; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFF14161B.toInt()
        c.drawLine(cx - 30f * s, y - 26f * s, cx - 52f * s, y + 36f * s, p); c.drawLine(cx + 30f * s, y - 26f * s, cx + 52f * s, y + 36f * s, p)
        c.drawLine(cx, y - 22f * s, cx, y + 40f * s, p)
        p.style = Paint.Style.FILL
        // bowl
        path.reset(); path.moveTo(cx - 60f * s, y - 36f * s); path.lineTo(cx + 60f * s, y - 36f * s)
        path.cubicTo(cx + 56f * s, y - 2f * s, cx + 24f * s, y + 12f * s, cx, y + 12f * s)
        path.cubicTo(cx - 24f * s, y + 12f * s, cx - 56f * s, y - 2f * s, cx - 60f * s, y - 36f * s); path.close()
        p.alpha = 255; p.shader = LinearGradient(cx - 60f * s, 0f, cx + 60f * s, 0f, intArrayOf(0xFF0A0B0E.toInt(), 0xFF4A4E57.toInt(), 0xFF101216.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.color = 0xFF9A7A34.toInt(); c.drawRoundRect(cx - 64f * s, y - 42f * s, cx + 64f * s, y - 33f * s, 4f * s, 4f * s, p)
        p.alpha = 255; p.shader = RadialGradient(cx, y - 38f * s, 56f * s, intArrayOf(0xFFFFB050.toInt(), 0xFFC0381A.toInt(), 0xFF3A0A06.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(cx - 52f * s, y - 46f * s, cx + 52f * s, y - 30f * s, p); p.shader = null
    }

    private fun candelabra(c: Canvas, cx: Float, y: Float, s: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xFF141519.toInt()
        c.drawRect(cx - 3f * s * 1.4f, y - 12f * s, cx + 3f * s * 1.4f, y + 70f * s, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f * s; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFF2A2C33.toInt()
        c.drawArc(cx - 38f * s, y + 8f * s, cx + 38f * s, y + 52f * s, 0f, 180f, false, p)
        p.style = Paint.Style.FILL
        for (dx in floatArrayOf(-38f, 0f, 38f)) {
            p.color = 0xFFD9D2BE.toInt()
            c.drawRect(cx + dx * s - 4f * s, y + (if (dx == 0f) -14f else 20f) * s, cx + dx * s + 4f * s, y + (if (dx == 0f) 12f else 40f) * s, p)
            p.color = 0xFF3A3E46.toInt()
            c.drawRect(cx + dx * s - 6f * s, y + (if (dx == 0f) 12f else 40f) * s, cx + dx * s + 6f * s, y + (if (dx == 0f) 15f else 43f) * s, p)
        }
    }

    // ------------------------------------------------------------------ altar
    private fun bakeAltar(): Bitmap {
        val m = (20f * u).toInt()
        val a = altarRect
        altarX = a.left - m; altarY = a.top - m
        return mk((a.width() + 2 * m).toInt(), (a.height() + 2 * m).toInt()) { c ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.translate(m.toFloat(), m.toFloat())
            val w = a.width(); val h = a.height()
            val cut = 16f * u
            val body = Path()
            body.moveTo(cut, 0f); body.lineTo(w - cut, 0f); body.lineTo(w, cut); body.lineTo(w, h - 6f * u); body.lineTo(w - 6f * u, h)
            body.lineTo(6f * u, h); body.lineTo(0f, h - 6f * u); body.lineTo(0f, cut); body.close()
            // shadow
            p.color = 0xAA000000.toInt(); p.maskFilter = BlurMaskFilter(14f * u, BlurMaskFilter.Blur.NORMAL)
            c.save(); c.translate(0f, 10f * u); c.drawPath(body, p); c.restore(); p.maskFilter = null
            // stone body
            p.alpha = 255; p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF454B45.toInt(), 0xFF2B302C.toInt(), 0xFF181B19.toInt()), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(body, p)
            p.alpha = 255; p.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); p.alpha = 150; c.drawPath(body, p); p.shader = null; p.alpha = 255
            // bevel
            p.style = Paint.Style.STROKE; p.strokeJoin = Paint.Join.MITER
            p.strokeWidth = 3f * u; p.color = 0x55FFFFF0; c.save(); c.translate(1.5f * u, 1.5f * u); c.clipPath(body); c.drawPath(body, p); c.restore()
            p.strokeWidth = 3.4f * u; p.color = 0xCC000000.toInt(); c.save(); c.translate(-1.6f * u, -1.6f * u); c.clipPath(body); c.drawPath(body, p); c.restore()
            p.strokeWidth = 1.6f * u; p.color = 0xFF000000.toInt(); c.drawPath(body, p)
            // engraved frame line
            p.strokeWidth = 1.4f * u; p.color = 0xAA000000.toInt(); tmp.set(9f * u, 9f * u, w - 9f * u, h - 9f * u); c.drawRoundRect(tmp, 5f * u, 5f * u, p)
            p.strokeWidth = 1.2f * u; p.color = 0x30FFFFF0; tmp.offset(0f, 1.6f * u); c.drawRoundRect(tmp, 5f * u, 5f * u, p)
            p.style = Paint.Style.FILL
            // title band: engraved lettering between skull glyphs
            val bandY = (gridRect.top - a.top - 8f * u) * 0.5f
            val gTop = gridRect.top - a.top - 8f * u
            engrave(c, "THE SOUL VAULT", w / 2f, gTop * 0.5f + 6.5f * u, 17f * u, 0xFFD2B060.toInt(), serif, 0.34f)
            val mp = Paint(Paint.ANTI_ALIAS_FLAG); mp.typeface = serif; mp.textSize = 17f * u; mp.letterSpacing = 0.34f
            val tw = mp.measureText("THE SOUL VAULT") / 2f
            skullGlyph(c, w / 2f - tw - 24f * u, bandY + 1f * u, 24f * u, 0xFF0A0B0A.toInt(), 0x66D9D2BE, 0x00000000)
            skullGlyph(c, w / 2f + tw + 24f * u, bandY + 1f * u, 24f * u, 0xFF0A0B0A.toInt(), 0x66D9D2BE, 0x00000000)
            p.color = Neon.BRASS_DIM
            c.drawRect(24f * u, bandY - 1f * u, w / 2f - tw - 46f * u, bandY + 1f * u, p); c.drawRect(w / 2f + tw + 46f * u, bandY - 1f * u, w - 24f * u, bandY + 1f * u, p)
            // recessed iron panel behind the niches
            val gx = gridRect.left - a.left; val gy = gridRect.top - a.top
            tmp.set(gx - 8f * u, gy - 8f * u, gx + gridRect.width() + 8f * u, gy + gridRect.height() + 8f * u)
            p.color = 0xFF020303.toInt(); c.drawRoundRect(tmp, 6f * u, 6f * u, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f * u; p.alpha = 255; p.shader = LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, intArrayOf(0xFFD2B060.toInt(), Neon.BRASS, Neon.BRASS_DIM, 0xFF2E230C.toInt()), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(tmp, 6f * u, 6f * u, p); p.shader = null; p.style = Paint.Style.FILL
            // niches
            val nr = Path()
            for (i in 0 until SoulVault.CELLS) {
                val cx0 = gx + (i % VAULT_COLS) * cellW; val cy0 = gy + (i / VAULT_COLS) * cellH
                val l = cx0 + 4f * u; val t = cy0 + 4f * u; val r = cx0 + cellW - 4f * u; val b = cy0 + cellH - 4f * u
                val arch = (r - l) * 0.30f
                nr.reset(); nr.addRoundRect(RectF(l, t, r, b), floatArrayOf(arch, arch, arch, arch, 4f * u, 4f * u, 4f * u, 4f * u), Path.Direction.CW)
                p.alpha = 255; p.shader = RadialGradient((l + r) / 2f, (t + b) / 2f + (b - t) * 0.1f, (r - l) * 0.85f, intArrayOf(0xFF18271E.toInt(), 0xFF080D0A.toInt(), 0xFF020303.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
                c.drawPath(nr, p)
                p.alpha = 255; p.shader = LinearGradient(0f, t, 0f, t + (b - t) * 0.45f, 0xCC000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
                c.save(); c.clipPath(nr); c.drawRect(l, t, r, t + (b - t) * 0.45f, p); c.restore(); p.shader = null
                // chiselled rim: dark outside edge, light lip along the sill
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * u; p.color = 0xFF000000.toInt(); c.drawPath(nr, p)
                p.strokeWidth = 1.2f * u; p.color = 0x26FFFFF0; c.save(); c.translate(0f, 1.8f * u); c.clipRect(l, b - 8f * u, r, b + 4f * u); c.drawPath(nr, p); c.restore()
                p.style = Paint.Style.FILL
                // faint engraved skull at the bottom of the empty niche
                skullGlyph(c, (l + r) / 2f, b - (b - t) * 0.22f, (r - l) * 0.30f, 0x18D9D2BE, 0x00000000, 0x00000000)
            }
            // brass corner fittings with rivets
            for (corner in 0 until 4) {
                val cxp = if (corner % 2 == 0) 0f else w; val cyp = if (corner < 2) 0f else h
                val sx = if (corner % 2 == 0) 1f else -1f; val sy = if (corner < 2) 1f else -1f
                path.reset()
                path.moveTo(cxp + sx * 2f * u, cyp + sy * 2f * u); path.lineTo(cxp + sx * 46f * u, cyp + sy * 2f * u); path.lineTo(cxp + sx * 40f * u, cyp + sy * 11f * u)
                path.lineTo(cxp + sx * 11f * u, cyp + sy * 11f * u); path.lineTo(cxp + sx * 11f * u, cyp + sy * 40f * u); path.lineTo(cxp + sx * 2f * u, cyp + sy * 46f * u); path.close()
                p.alpha = 255; p.shader = LinearGradient(cxp, cyp, cxp + sx * 46f * u, cyp + sy * 46f * u, intArrayOf(0xFFD7B866.toInt(), Neon.BRASS, Neon.BRASS_DIM), null, Shader.TileMode.CLAMP)
                c.drawPath(path, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * u; p.color = 0xCC000000.toInt(); c.drawPath(path, p); p.style = Paint.Style.FILL
                rivet(c, cxp + sx * 26f * u, cyp + sy * 6.6f * u, 2.3f * u); rivet(c, cxp + sx * 6.6f * u, cyp + sy * 26f * u, 2.3f * u)
            }
        }
    }

    private fun bakeBars(): Bitmap = mk(gridRect.width().toInt(), gridRect.height().toInt()) { c ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bw = 3.6f * u
        for (i in 0 until SoulVault.CELLS) {
            val cx0 = (i % VAULT_COLS) * cellW; val cy0 = (i / VAULT_COLS) * cellH
            for (f in floatArrayOf(0.30f, 0.70f)) {
                val x = cx0 + cellW * f - bw / 2
                val t = cy0 + 5f * u; val b = cy0 + cellH - 5f * u
                p.alpha = 255; p.shader = LinearGradient(x, 0f, x + bw, 0f, intArrayOf(0xFF07080A.toInt(), 0xFF6A6F79.toInt(), 0xFF111318.toInt()), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
                p.alpha = 190
                c.drawRect(x, t, x + bw, b, p)
                p.shader = null; p.alpha = 255
                p.color = 0xFF2A2D34.toInt(); c.drawRect(x - 1.6f * u, t - 1f * u, x + bw + 1.6f * u, t + 5f * u, p); c.drawRect(x - 1.6f * u, b - 5f * u, x + bw + 1.6f * u, b + 1f * u, p)
                p.color = 0xFF9A7A34.toInt(); c.drawCircle(x + bw / 2, t + 2f * u, 1.2f * u, p); c.drawCircle(x + bw / 2, b - 2f * u, 1.2f * u, p)
            }
        }
    }

    private fun rivet(c: Canvas, x: Float, y: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xCC000000.toInt(); c.drawCircle(x + r * 0.2f, y + r * 0.3f, r * 1.1f, p)
        p.alpha = 255; p.shader = RadialGradient(x - r * 0.35f, y - r * 0.4f, r * 1.4f, intArrayOf(0xFFFFE9A8.toInt(), Neon.BRASS, Neon.BRASS_DIM), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, p)
    }

    /** Engraved lettering: dark cut, light lip, then fill. Used only while baking. */
    private fun engrave(c: Canvas, text: String, cx: Float, base: Float, size: Float, fill: Int, tf: Typeface, tracking: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.typeface = tf; p.textAlign = Paint.Align.CENTER; p.textSize = size; p.letterSpacing = tracking
        p.color = 0xAA000000.toInt(); c.drawText(text, cx, base - size * 0.045f, p)
        p.color = 0x44FFFFF0; c.drawText(text, cx, base + size * 0.05f, p)
        p.color = fill; c.drawText(text, cx, base, p)
    }

    // ------------------------------------------------------------------ glyphs and sprites
    /** Skull relief. [rim] draws a lit outline, [eye] lights the sockets; pass 0 alpha colours to skip them. */
    private fun skullGlyph(c: Canvas, cx: Float, cy: Float, s: Float, fill: Int, rim: Int, eye: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val head = Path()
        head.addOval(RectF(cx - s * 0.36f, cy - s * 0.42f, cx + s * 0.36f, cy + s * 0.16f), Path.Direction.CW)
        head.moveTo(cx - s * 0.23f, cy + s * 0.02f); head.lineTo(cx - s * 0.19f, cy + s * 0.34f); head.lineTo(cx + s * 0.19f, cy + s * 0.34f); head.lineTo(cx + s * 0.23f, cy + s * 0.02f); head.close()
        p.color = fill; c.drawPath(head, p)
        if (Color.alpha(rim) > 0) { p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, s * 0.025f); p.color = rim; c.drawPath(head, p); p.style = Paint.Style.FILL }
        // sockets, nose, teeth are cut out of the fill: draw them in black / glow
        val sock = if (Color.alpha(fill) >= 0xC0) 0xFF000000.toInt() else 0x66000000
        p.color = sock
        for (sx in floatArrayOf(-1f, 1f)) c.drawOval(cx + sx * s * 0.15f - s * 0.095f, cy - s * 0.12f, cx + sx * s * 0.15f + s * 0.095f, cy + s * 0.06f, p)
        path.reset(); path.moveTo(cx, cy + s * 0.04f); path.lineTo(cx - s * 0.045f, cy + s * 0.15f); path.lineTo(cx + s * 0.045f, cy + s * 0.15f); path.close(); c.drawPath(path, p)
        if (Color.alpha(eye) > 0) {
            for (sx in floatArrayOf(-1f, 1f)) {
                p.alpha = 255; p.shader = RadialGradient(cx + sx * s * 0.15f, cy - s * 0.03f, s * 0.09f, intArrayOf(eye, withA(eye, 0)), null, Shader.TileMode.CLAMP)
                c.drawCircle(cx + sx * s * 0.15f, cy - s * 0.03f, s * 0.09f, p); p.shader = null
            }
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, s * 0.012f); p.color = sock
        for (k in -2..2) c.drawLine(cx + k * s * 0.07f, cy + s * 0.21f, cx + k * s * 0.07f, cy + s * 0.33f, p)
        c.drawLine(cx - s * 0.17f, cy + s * 0.25f, cx + s * 0.17f, cy + s * 0.25f, p)
        p.style = Paint.Style.FILL
    }

    private fun flameFrame(i: Int): Bitmap = mk(48, 96) { c ->
        val w = 48f; val h = 96f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun tongue(x: Float, wid: Float, ht: Float, lean: Float, shader: Shader) {
            val t = Path()
            t.moveTo(x, h * 0.98f)
            t.cubicTo(x - wid, h * 0.80f, x - wid * 0.55f + lean * 0.3f, h * (0.98f - ht) + ht * h * 0.42f, x + lean, h * (0.98f - ht))
            t.cubicTo(x + wid * 0.55f + lean * 0.3f, h * (0.98f - ht) + ht * h * 0.42f, x + wid, h * 0.80f, x, h * 0.98f); t.close()
            p.alpha = 255; p.shader = shader; c.drawPath(t, p)
        }
        val ph = i * 1.9f
        val outer = LinearGradient(0f, h, 0f, 0f, intArrayOf(0xFFFFB347.toInt(), 0xFFFF5A12.toInt(), 0x00A01005), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        p.maskFilter = BlurMaskFilter(2.2f, BlurMaskFilter.Blur.NORMAL)
        tongue(w * 0.30f, w * 0.22f, 0.55f + 0.08f * sin(ph + 1f), sin(ph + 2f) * 4f, outer)
        tongue(w * 0.70f, w * 0.22f, 0.5f + 0.08f * sin(ph + 3f), sin(ph + 4f) * 4f, outer)
        tongue(w * 0.50f, w * 0.30f, 0.94f, sin(ph) * 5f, outer)
        p.maskFilter = null
        val core = LinearGradient(0f, h, 0f, h * 0.3f, intArrayOf(0xFFFFF0B0.toInt(), 0xFFFFC060.toInt(), 0x00FF9A3C), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        tongue(w * 0.50f + sin(ph) * 1.5f, w * 0.15f, 0.6f, sin(ph) * 2f, core)
    }

    /** A wide, turbulent sheet of flame (many tongues, overlapped with ADD) for burning lettering and tablets. */
    private fun fireSheet(i: Int): Bitmap = mk(176, 128) { c ->
        val w = 176f; val h = 128f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val r = Random(100L + i * 7)
        fun tongue(x: Float, wid: Float, ht: Float, lean: Float, shader: Shader) {
            val t = Path()
            t.moveTo(x - wid * 0.95f, h * 0.99f)
            t.cubicTo(x - wid * 1.05f, h * (0.99f - ht * 0.45f), x - wid * 0.4f + lean * 0.3f, h * (0.99f - ht * 0.8f), x + lean, h * (0.99f - ht))
            t.cubicTo(x + wid * 0.4f + lean * 0.3f, h * (0.99f - ht * 0.8f), x + wid * 1.05f, h * (0.99f - ht * 0.45f), x + wid * 0.95f, h * 0.99f); t.close()
            p.alpha = 255; p.shader = shader; c.drawPath(t, p)
        }
        val outer = LinearGradient(0f, h, 0f, 0f, intArrayOf(0xFFFFB347.toInt(), 0xFFFF5A12.toInt(), 0x00A01005), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        p.maskFilter = BlurMaskFilter(3f, BlurMaskFilter.Blur.NORMAL)
        for (k in 0 until 6) tongue(w * (0.14f + 0.72f * k / 5f), w * (0.10f + 0.07f * r.nextFloat()), 0.40f + 0.58f * r.nextFloat(), (r.nextFloat() - 0.5f) * 22f, outer)
        p.maskFilter = null
        val core = LinearGradient(0f, h, 0f, h * 0.35f, intArrayOf(0xFFFFF4C0.toInt(), 0xFFFFC060.toInt(), 0x00FF9A3C), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        for (k in 0 until 4) tongue(w * (0.18f + 0.64f * k / 3f), w * 0.06f, 0.25f + 0.3f * r.nextFloat(), (r.nextFloat() - 0.5f) * 10f, core)
        p.alpha = 255; p.shader = LinearGradient(0f, h, 0f, h * 0.6f, 0x00FFFFFF, 0xFFFFFFFF.toInt(), Shader.TileMode.CLAMP)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        c.drawRect(0f, 0f, w, h, p)
        p.xfermode = null; p.shader = null
    }

    private fun chainSprite(w: Int, len: Int): Bitmap = mk(w, len) { c ->
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.STROKE
        val lw = w.toFloat(); val ll = lw * 1.9f
        var y = 0f; var k = 0
        while (y < len) {
            p.alpha = 255; p.shader = LinearGradient(0f, y, lw, y, intArrayOf(0xFF1A1C21.toInt(), 0xFF7A7F88.toInt(), 0xFF14161A.toInt()), null, Shader.TileMode.CLAMP)
            p.strokeWidth = lw * 0.17f
            if (k % 2 == 0) c.drawRoundRect(lw * 0.18f, y + lw * 0.1f, lw * 0.82f, y + ll, lw * 0.3f, lw * 0.3f, p)
            else { p.strokeWidth = lw * 0.22f; c.drawLine(lw * 0.5f, y + lw * 0.2f, lw * 0.5f, y + ll * 0.95f, p) }
            y += ll * 0.72f; k++
        }
        p.shader = null
    }

    private fun soulSprite(S: Int, tier: Int): Bitmap = mk(S, S) { c ->
        val s = S.toFloat(); val cx = s / 2f; val cy = s * 0.56f; val r = s * 0.29f
        val tint = when (tier) { 0 -> 0xFF8FCB5A.toInt(); 1 -> Neon.GREEN; else -> 0xFFB8FF70.toInt() }
        val deep = when (tier) { 0 -> 0xFF1F3A12.toInt(); 1 -> SOUL_DEEP; else -> 0xFF1C5A0A.toInt() }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // aura
        p.alpha = 255; p.shader = RadialGradient(cx, cy, r * 2.1f, intArrayOf(withA(tint, 120), withA(tint, 40), 0x00000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 2.1f, p); p.shader = null
        // wisp tail
        val tail = Path()
        tail.moveTo(cx - r * 0.62f, cy - r * 0.45f)
        tail.cubicTo(cx - r * 0.55f, cy - r * 1.05f, cx + r * 0.05f, cy - r * 1.05f, cx + r * 0.30f, cy - r * 1.78f)
        tail.cubicTo(cx + r * 0.55f, cy - r * 1.05f, cx + r * 0.72f, cy - r * 0.8f, cx + r * 0.62f, cy - r * 0.45f); tail.close()
        p.alpha = 255; p.shader = LinearGradient(0f, cy - r * 0.4f, 0f, cy - r * 1.8f, intArrayOf(withA(tint, 230), withA(tint, 100), withA(tint, 0)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        p.maskFilter = BlurMaskFilter(r * 0.06f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(tail, p); p.maskFilter = null; p.shader = null
        // body
        p.alpha = 255; p.shader = RadialGradient(cx - r * 0.32f, cy - r * 0.36f, r * 1.35f, intArrayOf(SOUL_HOT, tint, deep), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.07f; p.color = withA(deep, 200); c.drawCircle(cx, cy, r * 0.97f, p)
        if (tier == 2) { p.strokeWidth = r * 0.09f; p.color = withA(GOLD, 230); c.drawCircle(cx, cy, r * 1.02f, p) }
        else if (tier == 1) { p.strokeWidth = r * 0.05f; p.color = withA(Neon.BONE, 140); c.drawCircle(cx, cy, r * 1.02f, p) }
        // swirling wisps inside
        p.strokeWidth = r * 0.05f; p.color = 0x66FFFFFF
        for (k in 0..2) c.drawArc(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f, 20f + k * 120f, 62f, false, p)
        p.style = Paint.Style.FILL
        p.alpha = 255; p.shader = RadialGradient(cx - r * 0.36f, cy - r * 0.46f, r * 0.42f, intArrayOf(0xB0FFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawOval(cx - r * 0.7f, cy - r * 0.84f, cx + r * 0.0f, cy - r * 0.16f, p); p.shader = null
    }

    private fun sealSprite(S: Int, gem: Int, hot: Int, deep: Int, metalL: Int, metalD: Int, spikes: Boolean): Bitmap = mk(S, S) { c ->
        val s = S.toFloat(); val cx = s / 2f; val cy = s / 2f
        val R = s * (if (spikes) 0.40f else 0.45f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xAA000000.toInt(); p.maskFilter = BlurMaskFilter(s * 0.03f, BlurMaskFilter.Blur.NORMAL); c.drawCircle(cx, cy + s * 0.02f, R, p); p.maskFilter = null
        if (spikes) {
            for (k in 0 until 18) {
                val a = k * (2 * PI / 18).toFloat()
                val a0 = a - 0.13f; val a1 = a + 0.13f
                path.reset(); path.moveTo(cx + cos(a0) * R * 0.9f, cy + sin(a0) * R * 0.9f)
                path.lineTo(cx + cos(a) * R * 1.22f, cy + sin(a) * R * 1.22f); path.lineTo(cx + cos(a1) * R * 0.9f, cy + sin(a1) * R * 0.9f); path.close()
                p.color = if (k % 2 == 0) GOLD else 0xFFB8862A.toInt(); c.drawPath(path, p)
            }
        }
        p.alpha = 255; p.shader = SweepGradient(cx, cy, intArrayOf(metalL, metalD, metalL, metalD, metalL), floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f))
        c.drawCircle(cx, cy, R, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.018f; p.color = 0xDD000000.toInt(); c.drawCircle(cx, cy, R * 0.86f, p); c.drawCircle(cx, cy, R, p)
        p.style = Paint.Style.FILL
        for (k in 0 until 12) {
            val a = k * (2 * PI / 12).toFloat()
            val sx = cx + cos(a) * R * 0.93f; val sy = cy + sin(a) * R * 0.93f
            p.color = 0xAA000000.toInt(); c.drawCircle(sx + 0.8f, sy + 1f, s * 0.016f, p)
            p.color = metalL; c.drawCircle(sx, sy, s * 0.014f, p)
        }
        val gr = R * 0.78f
        p.alpha = 255; p.shader = RadialGradient(cx - gr * 0.3f, cy - gr * 0.38f, gr * 1.6f, intArrayOf(hot, gem, deep), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, gr, p); p.shader = null
        c.save()
        val clip = Path(); clip.addCircle(cx, cy, gr, Path.Direction.CW); c.clipPath(clip)
        for (k in 0 until 8) {
            val a0 = k * (PI / 4).toFloat() + 0.2f; val a1 = a0 + (PI / 4).toFloat()
            path.reset(); path.moveTo(cx, cy); path.lineTo(cx + cos(a0) * gr, cy + sin(a0) * gr); path.lineTo(cx + cos(a1) * gr, cy + sin(a1) * gr); path.close()
            p.color = if (k % 2 == 0) 0x2EFFFFFF else 0x38000000; c.drawPath(path, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.008f; p.color = 0x55FFFFFF
        c.drawCircle(cx, cy, gr * 0.62f, p)
        p.style = Paint.Style.FILL
        c.restore()
        skullGlyph(c, cx, cy + gr * 0.02f, gr * 1.28f, withA(shade(deep, 0.45f), 235), withA(hot, 150), withA(hot, 255))
        p.alpha = 255; p.shader = RadialGradient(cx - gr * 0.38f, cy - gr * 0.5f, gr * 0.55f, intArrayOf(0x77FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawOval(cx - gr * 0.8f, cy - gr * 0.95f, cx + gr * 0.1f, cy - gr * 0.1f, p); p.shader = null
    }

    private fun reaperOrbSprite(S: Int): Bitmap = mk(S, S) { c ->
        val s = S.toFloat(); val cx = s / 2f; val cy = s / 2f; val r = s * 0.40f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.alpha = 255; p.shader = RadialGradient(cx, cy, r * 1.7f, intArrayOf(0xAAE0182F.toInt(), 0x33E0182F, 0x00000000), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 1.7f, p)
        p.alpha = 255; p.shader = RadialGradient(cx, cy - r * 0.2f, r * 1.2f, intArrayOf(0xFF2A0A12.toInt(), 0xFF0A0306.toInt(), 0xFF000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.07f; p.color = Neon.RED; c.drawCircle(cx, cy, r, p)
        p.strokeWidth = r * 0.03f; p.color = withA(BLOOD_HOT, 160); c.drawCircle(cx, cy, r * 0.9f, p)
        p.style = Paint.Style.FILL
        // hooded bust
        val hood = Path()
        hood.moveTo(cx, cy - r * 0.78f)
        hood.cubicTo(cx + r * 0.46f, cy - r * 0.74f, cx + r * 0.58f, cy - r * 0.30f, cx + r * 0.62f, cy + r * 0.10f)
        hood.cubicTo(cx + r * 0.78f, cy + r * 0.24f, cx + r * 0.84f, cy + r * 0.50f, cx + r * 0.86f, cy + r * 0.86f)
        hood.lineTo(cx - r * 0.86f, cy + r * 0.86f)
        hood.cubicTo(cx - r * 0.84f, cy + r * 0.50f, cx - r * 0.78f, cy + r * 0.24f, cx - r * 0.62f, cy + r * 0.10f)
        hood.cubicTo(cx - r * 0.58f, cy - r * 0.30f, cx - r * 0.46f, cy - r * 0.74f, cx, cy - r * 0.78f); hood.close()
        c.save(); val clip = Path(); clip.addCircle(cx, cy, r * 0.97f, Path.Direction.CW); c.clipPath(clip)
        p.color = 0xFF050507.toInt(); c.drawPath(hood, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.035f; p.color = 0x66B4D8C8; c.drawPath(hood, p); p.style = Paint.Style.FILL
        // face void and cold skull
        p.color = 0xFF000000.toInt(); c.drawOval(cx - r * 0.36f, cy - r * 0.56f, cx + r * 0.36f, cy + r * 0.34f, p)
        p.color = 0x22D9D2BE; c.drawOval(cx - r * 0.26f, cy - r * 0.42f, cx + r * 0.26f, cy + r * 0.20f, p)
        c.restore()
    }

    private fun skullPip(S: Int, lit: Boolean): Bitmap = mk(S, S) { c ->
        val s = S.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (lit) {
            p.alpha = 255; p.shader = RadialGradient(s / 2, s / 2, s * 0.5f, intArrayOf(0x66E0182F, 0x00000000), null, Shader.TileMode.CLAMP)
            c.drawCircle(s / 2, s / 2, s * 0.5f, p); p.shader = null
        }
        skullGlyph(c, s / 2f, s * 0.52f, s * 0.82f, if (lit) 0xFFD9D2BE.toInt() else 0xFF2B2B2E.toInt(), if (lit) 0x88FFFFF0.toInt() else 0x22FFFFFF, if (lit) Neon.RED else 0x00000000)
    }

    private fun spinSprite(D: Int, ready: Boolean): Bitmap = mk(D, D) { c ->
        val s = D.toFloat(); val cx = s / 2; val cy = s / 2; val R = s * 0.44f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0xCC000000.toInt(); p.maskFilter = BlurMaskFilter(s * 0.03f, BlurMaskFilter.Blur.NORMAL); c.drawCircle(cx, cy + s * 0.025f, R, p); p.maskFilter = null
        p.alpha = 255; p.shader = SweepGradient(cx, cy, intArrayOf(0xFF9A9FA8.toInt(), 0xFF1C1F25.toInt(), 0xFF7A7F88.toInt(), 0xFF14161B.toInt(), 0xFF9A9FA8.toInt()), floatArrayOf(0f, 0.28f, 0.5f, 0.78f, 1f))
        c.drawCircle(cx, cy, R, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = R * 0.04f; p.color = 0xEE000000.toInt(); c.drawCircle(cx, cy, R, p); c.drawCircle(cx, cy, R * 0.86f, p)
        p.style = Paint.Style.FILL
        for (k in 0 until 14) {
            val a = k * (2 * PI / 14).toFloat()
            p.color = 0xAA000000.toInt(); c.drawCircle(cx + cos(a) * R * 0.93f + 1f, cy + sin(a) * R * 0.93f + 1.4f, R * 0.034f, p)
            p.color = if (ready) 0xFFC8A24C.toInt() else 0xFF5A5D66.toInt(); c.drawCircle(cx + cos(a) * R * 0.93f, cy + sin(a) * R * 0.93f, R * 0.03f, p)
        }
        val dr = R * 0.78f
        val dome = if (ready) intArrayOf(0xFFFF8F73.toInt(), Neon.RED, 0xFF5A0A14.toInt(), 0xFF22040A.toInt()) else intArrayOf(0xFF565A63.toInt(), 0xFF2A2D34.toInt(), 0xFF15171C.toInt(), 0xFF0A0B0D.toInt())
        p.alpha = 255; p.shader = RadialGradient(cx - dr * 0.3f, cy - dr * 0.4f, dr * 1.55f, dome, floatArrayOf(0f, 0.38f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, dr, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = dr * 0.05f; p.color = 0xAA000000.toInt(); c.drawCircle(cx, cy, dr, p); p.style = Paint.Style.FILL
        skullGlyph(c, cx, cy - dr * 0.18f, dr * 0.9f, if (ready) 0x30000000 else 0x20000000, 0x00000000, 0x00000000)
        engrave(c, "SPIN", cx, cy + dr * 0.30f, dr * 0.52f, if (ready) 0xFFF8EDD4.toInt() else 0xFF6E727A.toInt(), serif, 0.12f)
        p.alpha = 255; p.shader = RadialGradient(cx - dr * 0.36f, cy - dr * 0.52f, dr * 0.5f, intArrayOf(if (ready) 0x66FFFFFF else 0x33FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        c.drawOval(cx - dr * 0.8f, cy - dr * 0.95f, cx + dr * 0.1f, cy - dr * 0.05f, p); p.shader = null
    }

    private fun raysSprite(S: Int): Bitmap = mk(S, S) { c ->
        val s = S.toFloat(); val cx = s / 2; val cy = s / 2
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.maskFilter = BlurMaskFilter(s * 0.012f, BlurMaskFilter.Blur.NORMAL)
        val n = 26
        for (k in 0 until n) {
            val a = k * (2 * PI / n).toFloat()
            val w = (PI / n).toFloat() * (if (k % 2 == 0) 0.34f else 0.16f)
            path.reset(); path.moveTo(cx, cy)
            path.lineTo(cx + cos(a - w) * s * 0.5f, cy + sin(a - w) * s * 0.5f); path.lineTo(cx + cos(a + w) * s * 0.5f, cy + sin(a + w) * s * 0.5f); path.close()
            p.alpha = 255; p.shader = RadialGradient(cx, cy, s * 0.5f, intArrayOf(0x99FFFFFF.toInt(), 0x44FFFFFF, 0x00FFFFFF), floatArrayOf(0.05f, 0.5f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(path, p)
        }
    }

    private fun crescentSprite(S: Int): Bitmap = mk(S, S) { c ->
        val s = S.toFloat(); val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val cr = Path()
        cr.moveTo(s * 0.08f, s * 0.5f)
        cr.cubicTo(s * 0.30f, s * 0.04f, s * 0.78f, s * 0.04f, s * 0.97f, s * 0.5f)
        cr.cubicTo(s * 0.70f, s * 0.26f, s * 0.32f, s * 0.30f, s * 0.08f, s * 0.5f); cr.close()
        p.maskFilter = BlurMaskFilter(s * 0.05f, BlurMaskFilter.Blur.NORMAL); p.color = withA(Neon.RED, 200); c.drawPath(cr, p); p.maskFilter = null
        p.alpha = 255; p.shader = LinearGradient(s * 0.08f, 0f, s * 0.97f, 0f, intArrayOf(withA(BLOOD_HOT, 120), 0xFFFFF4E0.toInt(), 0xFFFFFFFF.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(cr, p)
    }

    // ------------------------------------------------------------------ the looming Reaper
    private fun bakeFigure(): Bitmap {
        val k = figK
        figCx = 3.3f * k; figCy = 2.45f * k
        return mk((6.6f * k).toInt(), (5.4f * k).toInt()) { c ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.translate(figCx, figCy)
            val cloak = Path()
            cloak.moveTo(0f, -2.3f * k)
            cloak.cubicTo(0.55f * k, -2.28f * k, 1.00f * k, -1.80f * k, 1.12f * k, -1.10f * k)
            cloak.cubicTo(1.22f * k, -0.55f * k, 1.20f * k, -0.10f * k, 1.34f * k, 0.30f * k)
            cloak.cubicTo(1.80f * k, 0.55f * k, 2.55f * k, 0.95f * k, 3.00f * k, 1.80f * k)
            cloak.lineTo(3.20f * k, 3.0f * k); cloak.lineTo(-3.20f * k, 3.0f * k); cloak.lineTo(-3.00f * k, 1.80f * k)
            cloak.cubicTo(-2.55f * k, 0.95f * k, -1.80f * k, 0.55f * k, -1.34f * k, 0.30f * k)
            cloak.cubicTo(-1.20f * k, -0.10f * k, -1.22f * k, -0.55f * k, -1.12f * k, -1.10f * k)
            cloak.cubicTo(-1.00f * k, -1.80f * k, -0.55f * k, -2.28f * k, 0f, -2.3f * k); cloak.close()
            p.alpha = 255; p.shader = LinearGradient(0f, -2.3f * k, 0f, 3.0f * k, intArrayOf(0xFF101216.toInt(), 0xFF08090B.toInt(), 0xFF040405.toInt()), null, Shader.TileMode.CLAMP)
            c.drawPath(cloak, p); p.shader = null
            // folds
            p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
            val fr = Random(77)
            for (f in 0 until 9) {
                val x0 = (-0.9f + f * 0.22f + fr.nextFloat() * 0.1f) * k
                path.reset(); path.moveTo(x0, 0.4f * k)
                path.cubicTo(x0 * 1.6f, 1.0f * k, x0 * 2.3f, 1.8f * k, x0 * 2.7f + (fr.nextFloat() - 0.5f) * 0.4f * k, 3.0f * k)
                p.strokeWidth = 5f * u; p.color = 0x55000000; c.drawPath(path, p)
                p.strokeWidth = 1.6f * u; p.color = 0x16FFFFFF; c.save(); c.translate(1.5f * u, 0f); c.drawPath(path, p); c.restore()
            }
            // rim light: sickly teal on the left, violet on the right
            p.strokeWidth = 2.6f * u
            p.alpha = 255; p.shader = LinearGradient(-1.4f * k, 0f, 1.4f * k, 0f, intArrayOf(0x8890D8B8.toInt(), 0x2290D8B8, 0x00000000, 0x558A6AC8), floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(cloak, p); p.shader = null
            p.style = Paint.Style.FILL
            // hood lining + face void
            p.color = 0xFF060709.toInt()
            c.drawOval(-0.86f * k, -1.62f * k, 0.86f * k, 1.0f * k, p)
            val face = Path()
            face.moveTo(0f, -1.50f * k)
            face.cubicTo(0.42f * k, -1.45f * k, 0.66f * k, -0.95f * k, 0.66f * k, -0.40f * k)
            face.cubicTo(0.66f * k, 0.15f * k, 0.42f * k, 0.62f * k, 0f, 0.82f * k)
            face.cubicTo(-0.42f * k, 0.62f * k, -0.66f * k, 0.15f * k, -0.66f * k, -0.40f * k)
            face.cubicTo(-0.66f * k, -0.95f * k, -0.42f * k, -1.45f * k, 0f, -1.50f * k); face.close()
            p.alpha = 255; p.shader = RadialGradient(0f, -0.2f * k, 1.0f * k, intArrayOf(0xFF0B0F10.toInt(), 0xFF020303.toInt(), 0xFF000000.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(face, p); p.shader = null
            // the skull inside the dark: cheekbones, nasal cavity, teeth (dim bone, so the eyes do the work)
            p.color = 0x14D9D2BE; c.drawOval(-0.46f * k, -1.05f * k, 0.46f * k, 0.42f * k, p)
            p.color = 0x26D9D2BE; c.drawOval(-0.50f * k, -0.18f * k, -0.14f * k, 0.30f * k, p); c.drawOval(0.14f * k, -0.18f * k, 0.50f * k, 0.30f * k, p)
            p.color = 0xFF000000.toInt()
            c.drawOval(-0.40f * k, -0.62f * k, -0.14f * k, -0.26f * k, p); c.drawOval(0.14f * k, -0.62f * k, 0.40f * k, -0.26f * k, p)
            path.reset(); path.moveTo(0f, -0.20f * k); path.lineTo(-0.07f * k, 0.04f * k); path.lineTo(0.07f * k, 0.04f * k); path.close(); c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * u; p.color = 0x44D9D2BE
            for (t in -3..3) c.drawLine(t * 0.075f * k, 0.30f * k, t * 0.075f * k, 0.46f * k, p)
            c.drawLine(-0.27f * k, 0.38f * k, 0.27f * k, 0.38f * k, p)
            // inner edge of the cowl catches the light
            p.strokeWidth = 2.2f * u; p.color = 0x4090C8B0; c.drawPath(face, p)
            p.style = Paint.Style.FILL
        }
    }

    // ------------------------------------------------------------------ HUD plates
    private fun octagon(out: Path, w: Float, h: Float, cut: Float) {
        out.reset(); out.moveTo(cut, 0f); out.lineTo(w - cut, 0f); out.lineTo(w, cut); out.lineTo(w, h - cut); out.lineTo(w - cut, h)
        out.lineTo(cut, h); out.lineTo(0f, h - cut); out.lineTo(0f, cut); out.close()
    }

    /** Iron plate with a brass border; [content] draws in plate-local coordinates (0..w, 0..h). */
    private fun plateBitmap(r: RectF, accent: Int, content: (Canvas, Float, Float) -> Unit): Bitmap {
        val m = plateM
        return mk((r.width() + 2 * m).toInt(), (r.height() + 2 * m).toInt()) { c ->
            c.translate(m, m)
            val w = r.width(); val h = r.height()
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val body = Path(); octagon(body, w, h, min(w, h) * 0.12f)
            p.color = 0xB0000000.toInt(); p.maskFilter = BlurMaskFilter(8f * u, BlurMaskFilter.Blur.NORMAL)
            c.save(); c.translate(0f, 5f * u); c.drawPath(body, p); c.restore(); p.maskFilter = null
            p.alpha = 255; p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF323640.toInt(), 0xFF1A1C22.toInt(), 0xFF0F1115.toInt()), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(body, p)
            p.alpha = 255; p.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); p.alpha = 120; c.drawPath(body, p); p.shader = null; p.alpha = 255
            // inner dark field
            val ins = 6f * u
            val inner = Path(); octagon(inner, w - 2 * ins, h - 2 * ins, min(w, h) * 0.10f); inner.offset(ins, ins)
            p.alpha = 255; p.shader = LinearGradient(0f, ins, 0f, h - ins, intArrayOf(0xFF07080A.toInt(), 0xFF101216.toInt()), null, Shader.TileMode.CLAMP)
            c.drawPath(inner, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * u; p.color = withA(accent, 150); c.drawPath(inner, p)
            // brass border
            p.strokeWidth = 2.8f * u; p.strokeJoin = Paint.Join.MITER
            p.alpha = 255; p.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFFD7B866.toInt(), Neon.BRASS, Neon.BRASS_DIM, 0xFF2E230C.toInt()), floatArrayOf(0f, 0.3f, 0.7f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(body, p); p.shader = null
            p.strokeWidth = 1.2f * u; p.color = 0xDD000000.toInt(); c.save(); c.scale((w - 6f * u) / w, (h - 6f * u) / h, w / 2, h / 2); c.drawPath(body, p); c.restore()
            p.style = Paint.Style.FILL
            val cut = min(w, h) * 0.12f
            rivet(c, cut * 0.55f, cut * 0.55f, 2.3f * u); rivet(c, w - cut * 0.55f, cut * 0.55f, 2.3f * u)
            rivet(c, cut * 0.55f, h - cut * 0.55f, 2.3f * u); rivet(c, w - cut * 0.55f, h - cut * 0.55f, 2.3f * u)
            content(c, w, h)
        }
    }

    private fun bakePlates() {
        // jackpots
        for (i in 0 until 4) {
            val r = jpRect[i]
            val accent = jpColor[i]
            plates[i] = plateBitmap(r, accent) { c, w, h ->
                val big = i == 3
                val vertical = portrait || big
                val sealS = if (vertical) h * (if (portrait) 0.62f else 0.58f) else h * 0.84f
                val sx = if (vertical) w / 2f else h * 0.56f
                val sy = if (vertical) h * 0.34f else h / 2f
                dst.set(sx - sealS / 2, sy - sealS / 2, sx + sealS / 2, sy + sealS / 2)
                c.drawBitmap(sealBmp[i], null, dst, pBmp)
                val tx = if (vertical) w / 2f else (h * 1.08f + w - 8f * u) / 2f
                val maxW = if (vertical) w - 14f * u else w - h * 1.08f - 14f * u
                val nameSize = if (vertical) h * 0.15f else h * 0.22f
                val valSize = if (vertical) h * 0.19f else h * 0.28f
                val tp = Paint(Paint.ANTI_ALIAS_FLAG); tp.textAlign = Paint.Align.CENTER; tp.typeface = serif; tp.letterSpacing = 0.16f
                tp.textSize = nameSize
                val ny = if (vertical) h * 0.70f else h * 0.40f
                engrave(c, jpNames[i], tx, ny, fit(tp, jpNames[i], maxW, nameSize), jpHot[i], serif, 0.16f)
                val v = tokens(jpMult[i])
                val txt = if (v > ReaperConfig.MAX_WIN_TOKENS) "${Money.fmt(ReaperConfig.MAX_WIN_TOKENS)} MAX" else Money.fmt(v)
                tp.letterSpacing = 0f; tp.typeface = serif
                val vs = fit(tp, txt, maxW, valSize)
                tp.textSize = vs
                tp.color = 0xCC000000.toInt(); tp.style = Paint.Style.STROKE; tp.strokeWidth = vs * 0.14f; tp.strokeJoin = Paint.Join.ROUND
                val vy = if (vertical) h * 0.93f else h * 0.80f
                c.drawText(txt, tx, vy, tp)
                tp.style = Paint.Style.FILL; tp.color = 0xFFF2EBD8.toInt(); c.drawText(txt, tx, vy, tp)
            }
        }
        // respins
        plates[4] = plateBitmap(respinRect, Neon.RED) { c, w, h ->
            val tall = h > w * 0.62f
            val lbl = if (tall) h * 0.12f else h * 0.17f
            engrave(c, "RESPINS", w / 2f, if (tall) h * 0.19f else h * 0.24f, lbl, 0xFFE9DEC2.toInt(), serif, 0.2f)
            // brass socket behind the numeral
            val nx = if (tall) w / 2f else w * 0.24f; val ny = if (tall) h * 0.50f else h * 0.62f
            val nr = if (tall) h * 0.19f else h * 0.26f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.color = 0xFF020203.toInt(); c.drawCircle(nx, ny, nr, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = nr * 0.12f
            p.alpha = 255; p.shader = LinearGradient(nx - nr, ny - nr, nx + nr, ny + nr, intArrayOf(0xFFD7B866.toInt(), Neon.BRASS, Neon.BRASS_DIM), null, Shader.TileMode.CLAMP)
            c.drawCircle(nx, ny, nr, p); p.shader = null
        }
        // total
        plates[5] = plateBitmap(totalRect, Neon.GREEN) { c, w, h ->
            engrave(c, "SOULS HARVESTED", w / 2f, h * 0.27f, h * 0.17f, 0xFFD2C9AE.toInt(), serif, 0.22f)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            tmp.set(w * 0.08f, h * 0.36f, w * 0.92f, h * 0.88f)
            p.color = 0xFF020303.toInt(); c.drawRoundRect(tmp, 6f * u, 6f * u, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * u; p.color = withA(Neon.BRASS, 150); c.drawRoundRect(tmp, 6f * u, 6f * u, p)
        }
        // bonus bet
        plates[6] = plateBitmap(betRect, Neon.PURPLE) { c, w, h ->
            engrave(c, "BONUS BET", w / 2f, h * 0.31f, h * 0.2f, 0xFFD9C0FF.toInt(), serif, 0.2f)
            val tp = Paint(Paint.ANTI_ALIAS_FLAG); tp.textAlign = Paint.Align.CENTER; tp.typeface = serif; tp.textSize = h * 0.38f
            val v = "${result.bet}"
            tp.color = 0xCC000000.toInt(); tp.style = Paint.Style.STROKE; tp.strokeWidth = h * 0.05f; c.drawText(v, w / 2f, h * 0.80f, tp)
            tp.style = Paint.Style.FILL; tp.color = 0xFFF2EBD8.toInt(); c.drawText(v, w / 2f, h * 0.80f, tp)
        }
    }

    private fun fit(p: Paint, s: String, maxW: Float, size: Float): Float { p.textSize = size; val w = p.measureText(s); return if (w > maxW && w > 0f) size * maxW / w else size }

    private fun tokens(mult: Int): Long = mult.toLong() * result.bet

    // ================================================================== number formatting (allocation free)
    private fun fmt(v0: Long): Int {
        var v = if (v0 < 0) 0L else v0
        var p = nb.size; var digits = 0
        if (v == 0L) { nb[--p] = '0'; nbStart = p; return 1 }
        while (v > 0) {
            if (digits == 3) { nb[--p] = ','; digits = 0 }
            nb[--p] = '0' + (v % 10).toInt(); v /= 10; digits++
        }
        nbStart = p; return nb.size - p
    }

    /** Outlined number centred at (x, y), shrunk to fit [maxW]. */
    private fun num(c: Canvas, v: Long, x: Float, y: Float, size: Float, fill: Int, maxW: Float, outline: Int = 0xE6000000.toInt()) {
        val n = fmt(v)
        pText.typeface = serif; pText.letterSpacing = 0f
        pText.textSize = size
        val wd = pText.measureText(nb, nbStart, n)
        val sz = if (wd > maxW && wd > 0f) size * maxW / wd else size
        pText.textSize = sz
        pText.style = Paint.Style.STROKE; pText.strokeWidth = sz * 0.17f; pText.color = outline
        c.drawText(nb, nbStart, n, x, y, pText)
        pText.style = Paint.Style.FILL; pText.color = fill
        c.drawText(nb, nbStart, n, x, y, pText)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size0: Float, fill: Int, tf: Typeface = serif, spacing: Float = 0.12f, outline: Boolean = true, maxW: Float = 0f) {
        pText.typeface = tf; pText.letterSpacing = spacing; pText.textSize = size0
        if (maxW > 0f) { val w = pText.measureText(s); if (w > maxW) pText.textSize = size0 * maxW / w }
        val size = pText.textSize
        if (outline) { pText.style = Paint.Style.STROKE; pText.strokeWidth = size * 0.16f; pText.color = 0xE6000000.toInt(); c.drawText(s, x, y, pText) }
        pText.style = Paint.Style.FILL; pText.color = fill
        c.drawText(s, x, y, pText)
        pText.letterSpacing = 0f
    }

    // ================================================================== draw
    override fun onDraw(c: Canvas) {
        if (released) return
        if (!ready) {
            // still baking (one stage per frame): hold the host's end-of-trigger white so the hand-over is seamless
            if (bakeSteps.isNotEmpty()) bakeNext()
            c.drawColor(if (introStart == 0.0) 0xFFECE6D6.toInt() else 0xFF050607.toInt())   // white only for the very first bake
            postInvalidateOnAnimation()
            return
        }
        val real = now()
        if (introStart == 0.0) { introStart = real; lastFrame = real }
        if (!started && real - introStart > 4.0) { started = true; startReal = real; lastReal = real }
        if (started) { vt += (real - lastReal) * speed; lastReal = real; process() }
        // while the player waits to press the LAST respin, bake the finale a piece per frame (no hitch when it appears)
        if (finaleStep < 3 && waitingForSpin && nextStep >= result.steps.size - 1) prebakeStep()
        val dt = (real - lastFrame).toFloat().coerceIn(0f, 0.1f); lastFrame = real
        val t = (real - introStart).toFloat()
        updateAmbient(dt, t)

        c.drawBitmap(bgBack, 0f, 0f, null)
        drawLights(c, t)
        drawFog(c, t, false)
        drawReaper(c, t)
        c.drawBitmap(altarBmp, altarX, altarY, null)
        drawCollectDim(c)
        drawGrid(c, t)
        c.drawBitmap(barsBmp, gridRect.left, gridRect.top, null)
        drawValues(c, t)
        drawBeams(c, t)
        drawSparks(c)
        drawFog(c, t, true)
        drawPlates(c, t)
        drawSpinButton(c, t)
        drawEmbers(c)
        if (vt - jackpotFlash < 1.4) drawJackpotFlash(c, t)
        if (grandAt >= 0 && vt - grandAt < 3.0) drawGrand(c, t)
        if (!started) drawIntro(c, t, 1f) else if (real - startReal < 0.45) drawIntro(c, t, (1.0 - (real - startReal) / 0.45).toFloat())
        if (summaryAt >= 0) drawSummary(c, t)
        // fade in from the end of the trigger flash, out to black on leaving
        val fin = (real - introStart).toFloat()
        if (fin < 0.8f) { pFill.color = Color.argb(((1f - fin / 0.8f) * (if (calm) 120 else 235)).toInt(), 236, 230, 214); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill) }
        if (outroAt > 0) {
            val q = ((real - outroAt) / 0.9).coerceIn(0.0, 1.0).toFloat()
            drawDoors(c, q)
            if (q >= 0.8f && !doorSlammed) { doorSlammed = true; audio.play(Sfx.DOOR_SLAM, 0.9f) }
            pFill.color = Color.argb((smooth((q - 0.72f) / 0.28f) * 255).toInt(), 0, 0, 0); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
            if (q >= 1f && !finished) { finished = true; post { onFinished(result.payTokens) }; return }
        }
        scheduleNext(real)
    }

    /** Frame economy: vsync while anything is moving, 30 fps for falling embers, a slow ambient tick while the vault just waits. */
    private fun scheduleNext(real: Double) {
        val fast = real - introStart < 1.3 || evIndex < events.size || vt - lastLandAt < 0.6 || vt - jackpotFlash < 1.5 ||
            (grandAt >= 0 && vt - grandAt < 3.2) || collectTo >= 0 || vt - resetFlash < 1.0 || (summaryAt >= 0 && vt - summaryAt < 2.2) ||
            outroAt > 0 || real - spinPressedAt < 0.3 || sparksLive()
        when {
            fast -> postInvalidateOnAnimation()
            summaryAt >= 0 && rainLevel > 0.05f -> postInvalidateDelayed(33L)
            else -> postInvalidateDelayed(if (calm) 250L else 90L)
        }
    }

    private fun sparksLive(): Boolean { for (i in 0 until spN) if (spT[i] >= 0) return true; return false }

    /** Flame sprite frames per second ("reduce flashing" slows the fire right down). */
    private val flameRate: Float get() = if (calm) 3.5f else 9f

    /** One heavy iron-bound door leaf (hinged on the outside edge), baked on first use; mirrored for the other side. */
    private fun ensureDoor() {
        if (doorBmp != null) return
        val w = (vw / 2f).toInt() + 3; val h = vh.toInt()
        doorBmp = mk(w, h) { c ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val rnd = Random(31)
            val pw = 52f * u
            var x = 0f
            while (x < w) {
                val sh = 24 + rnd.nextInt(12)
                p.alpha = 255; p.shader = LinearGradient(x, 0f, x + pw, 0f, Color.rgb(sh + 10, sh + 2, sh - 4), Color.rgb(sh - 6, sh - 12, sh - 16), Shader.TileMode.CLAMP)
                c.drawRect(x, 0f, x + pw, h.toFloat(), p); p.shader = null
                p.color = 0x26000000
                for (k in 0 until 7) { val gx = x + rnd.nextFloat() * pw; c.drawLine(gx, 0f, gx + (rnd.nextFloat() - 0.5f) * 8f * u, h.toFloat(), p) }
                p.color = 0xDD000000.toInt(); c.drawRect(x + pw - 2.4f * u, 0f, x + pw, h.toFloat(), p)
                x += pw
            }
            p.alpha = 255; p.shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); p.alpha = 120; c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null; p.alpha = 255
            // horizontal iron straps with rivets and a pointed tip toward the seam
            for (yy in floatArrayOf(0.07f, 0.29f, 0.50f, 0.71f, 0.93f)) {
                val y = h * yy; val th = 30f * u
                path.reset(); path.moveTo(0f, y - th / 2); path.lineTo(w - 34f * u, y - th / 2); path.lineTo(w - 6f * u, y); path.lineTo(w - 34f * u, y + th / 2); path.lineTo(0f, y + th / 2); path.close()
                p.alpha = 255; p.shader = LinearGradient(0f, y - th / 2, 0f, y + th / 2, intArrayOf(0xFF3E424B.toInt(), 0xFF14161B.toInt(), 0xFF08090B.toInt()), null, Shader.TileMode.CLAMP)
                c.drawPath(path, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * u; p.color = 0xCC000000.toInt(); c.drawPath(path, p); p.style = Paint.Style.FILL
                var rxp = 26f * u
                while (rxp < w - 50f * u) { rivet(c, rxp, y, 3.2f * u); rxp += 52f * u }
            }
            // the seam: a vertical iron band with a heavy brass ring and a skull boss
            val sx = w - 30f * u
            p.alpha = 255; p.shader = LinearGradient(sx, 0f, sx + 30f * u, 0f, intArrayOf(0xFF2E3138.toInt(), 0xFF0E1014.toInt(), 0xFF050608.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRect(sx, 0f, w.toFloat(), h.toFloat(), p); p.shader = null
            p.color = 0x33FFFFFF; c.drawRect(sx, 0f, sx + 1.6f * u, h.toFloat(), p)
            skullGlyph(c, w - 40f * u, h * 0.5f - 66f * u, 52f * u, 0xFF0A0B0D.toInt(), 0x88D9D2BE.toInt(), withA(Neon.RED, 255))
            p.style = Paint.Style.STROKE; p.strokeWidth = 9f * u
            p.alpha = 255; p.shader = SweepGradient(w - 40f * u, h * 0.5f + 20f * u, intArrayOf(0xFFE3C676.toInt(), Neon.BRASS_DIM, 0xFFC8A24C.toInt(), 0xFF2E230C.toInt(), 0xFFE3C676.toInt()), null)
            c.drawCircle(w - 40f * u, h * 0.5f + 20f * u, 34f * u, p); p.shader = null; p.style = Paint.Style.FILL
            // darkness gathers toward the hinges
            p.alpha = 255; p.shader = LinearGradient(0f, 0f, w * 0.5f, 0f, 0x99000000.toInt(), 0x00000000, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w * 0.5f, h.toFloat(), p); p.shader = null
        }
    }

    private fun drawDoors(c: Canvas, q: Float) {
        ensureDoor()
        val d = doorBmp ?: return
        val e = (q / 0.8f).coerceIn(0f, 1f)
        val slide = e * e * (3f - 2f * e) * (1f - 0.04f * (1f - e))      // eased, finishing with a little weight
        val w = d.width.toFloat()
        val lx = -w + slide * (vw / 2f + 1f)
        c.drawBitmap(d, lx, 0f, pBmp)
        c.save(); c.scale(-1f, 1f, vw / 2f, 0f)
        c.drawBitmap(d, lx, 0f, pBmp)
        c.restore()
        // the closing gap glows ember-red and a shadow falls across the room
        if (e < 1f) { val gap = (1f - slide) * vw * 0.5f; glow(c, vw / 2f, vh * 0.5f, gap * 0.8f + 30f * u, tintRed, 0.5f * (1f - e)) }
    }


    private fun flick(t: Float, seed: Float): Float {
        val a = sin(t * 7.1f + seed) * 0.5f + sin(t * 11.3f + seed * 2.3f) * 0.3f + sin(t * 3.7f + seed * 0.7f) * 0.2f
        return 1f + a * (if (calm) 0.05f else 0.16f)
    }

    // ------------------------------------------------------------------ ambient particles
    private fun updateAmbient(dt: Float, t: Float) {
        val W = width.toFloat(); val H = height.toFloat()
        // rising embers from the fires
        for (i in 0 until emN) {
            if (emLife[i] <= 0f) {
                if (fx.nextInt(100) < (if (calm) 3 else 8)) {
                    var pick = -1
                    if (fx.nextInt(100) < 45) for (a in 0 until 5) { val k = fx.nextInt(SoulVault.CELLS); if (cells[k]?.kind == Orb.SOUL) { pick = k; break } }
                    if (pick >= 0) {
                        cellRect(pick, tmp)
                        emX[i] = tmp.centerX() + (fx.nextFloat() - 0.5f) * cellW * 0.4f; emY[i] = tmp.centerY() - cellH * 0.12f
                        emVx[i] = (fx.nextFloat() - 0.5f) * 10f * u; emVy[i] = -(14f + fx.nextFloat() * 22f) * u
                        emLife[i] = 1f; emSz[i] = (2f + fx.nextFloat() * 3f) * u; emGreen[i] = true
                    } else if (fireN > 0) {
                        val f = fx.nextInt(fireN)
                        emX[i] = fireX[f] + (fx.nextFloat() - 0.5f) * 40f * fireS[f]; emY[i] = fireY[f] - 30f * fireS[f]
                        emVx[i] = (fx.nextFloat() - 0.5f) * 18f * u; emVy[i] = -(24f + fx.nextFloat() * 46f) * u
                        emLife[i] = 1f; emSz[i] = (3f + fx.nextFloat() * 5f) * u; emGreen[i] = false
                    }
                }
            } else {
                emLife[i] -= dt * 0.28f
                emX[i] += (emVx[i] + sin(t * 2f + i) * 8f * u) * dt; emY[i] += emVy[i] * dt
            }
        }
        // falling embers on big wins
        rainLevel = when {
            grandAt >= 0 && vt - grandAt < 3.4 -> 0.9f
            summaryAt >= 0 -> when (summaryTierOf()) { 4, 3 -> 1f; 2 -> 0.45f; 1 -> 0.12f; else -> 0f }
            else -> 0f
        }
        for (i in 0 until rainN) {
            if (!rnOn[i]) {
                if (rainLevel > 0f && fx.nextFloat() < rainLevel * 0.10f) {
                    rnOn[i] = true; rnX[i] = fx.nextFloat() * W; rnY[i] = -10f * u; rnVy[i] = (70f + fx.nextFloat() * 130f) * u
                    rnSz[i] = (3f + fx.nextFloat() * 6f) * u; rnPh[i] = fx.nextFloat() * 6.28f
                }
            } else {
                rnY[i] += rnVy[i] * dt; rnX[i] += sin(t * 1.7f + rnPh[i]) * 14f * u * dt
                if (rnY[i] > H + 10f) rnOn[i] = false
            }
        }
        for (i in 0 until fogN) {
            fogX[i] += fogV[i] * dt
            if (fogV[i] > 0 && fogX[i] > W) fogX[i] = -fogW[i]
            if (fogV[i] < 0 && fogX[i] < -fogW[i]) fogX[i] = W
        }
    }

    private fun glow(c: Canvas, x: Float, y: Float, r: Float, f: ColorFilter, alpha: Float, add: Boolean = true) {
        val p = if (add) pAdd else pBmp
        p.colorFilter = f; p.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        dst.set(x - r, y - r, x + r, y + r); c.drawBitmap(glowBmp, null, dst, p)
        p.colorFilter = null; p.alpha = 255
    }

    private fun drawLights(c: Canvas, t: Float) {
        for (i in 0 until fireN) {
            val f = flick(t, i * 1.7f)
            val s = fireS[i]
            glow(c, fireX[i], fireY[i] - 40f * s, 170f * s, tintWarm, 0.20f * f)
            glow(c, fireX[i], fireY[i] - 40f * s, 70f * s, tintWarm, 0.34f * f)
            val big = s > 0.9f * u
            val fh = (if (big) 78f else 46f) * s
            val n = if (big) 4 else 1
            for (k in 0 until n) {
                val frame = ((t * flameRate + i * 2 + k * 3).toInt() and 0x7FFFFFFF) % 6
                val dx = if (n == 1) 0f else (k - 1.5f) * 26f * s
                val hh = fh * (if (k == 1 || k == 2) 1f else 0.7f) * (0.92f + 0.12f * flick(t, k + i * 3f))
                val bx = fireX[i] + dx; val by = fireY[i] - 38f * s
                dst.set(bx - hh * 0.3f, by - hh, bx + hh * 0.3f, by)
                pBmp.alpha = 215; c.drawBitmap(flameBmp[frame], null, dst, pBmp); pBmp.alpha = 255
            }
        }
        // chains swaying from the ceiling beside the hood
        val sw = sin(t * 0.7f) * 1.4f
        for (k in 0 until 4) {
            val dx = chainDx[k] * figK
            val len = if (k == 0 || k == 3) 1f else 0.62f
            c.save(); c.rotate(sw * (if (k % 2 == 0) 1f else -0.8f), hoodX + dx, -4f)
            dst.set(hoodX + dx - chainBmp.width / 2f, -4f, hoodX + dx + chainBmp.width / 2f, -4f + chainBmp.height * len)
            srcR.set(0, 0, chainBmp.width, (chainBmp.height * len).toInt())
            c.drawBitmap(chainBmp, srcR, dst, pBmp)
            c.restore()
        }
    }

    private fun drawFog(c: Canvas, t: Float, front: Boolean) {
        val H = height.toFloat()
        pBmp.colorFilter = tintFog
        for (i in 0 until fogN) {
            val isFront = fogY[i] > H * 0.62f
            if (isFront != front) continue
            val a = fogA[i] * (if (front) 1.15f else 1f) * (0.85f + 0.15f * sin(t * 0.3f + i))
            pBmp.alpha = (a * 255f).toInt().coerceIn(0, 255)
            dst.set(fogX[i], fogY[i] - fogW[i] * 0.28f, fogX[i] + fogW[i], fogY[i] + fogW[i] * 0.28f)
            c.drawBitmap(fogBmp, null, dst, pBmp)
        }
        pBmp.colorFilter = null; pBmp.alpha = 255
    }

    private fun drawEmbers(c: Canvas) {
        for (i in 0 until emN) {
            if (emLife[i] <= 0f) continue
            val a = (emLife[i] * 0.95f).coerceIn(0f, 1f)
            glow(c, emX[i], emY[i], emSz[i] * 2.4f, if (emGreen[i]) tintGreen else tintWarm, a * 0.8f)
        }
        for (i in 0 until rainN) {
            if (!rnOn[i]) continue
            pAdd.colorFilter = tintWarm; pAdd.alpha = 220
            dst.set(rnX[i] - rnSz[i] * 1.7f, rnY[i] - rnSz[i] * 7f, rnX[i] + rnSz[i] * 1.7f, rnY[i] + rnSz[i] * 1.4f); c.drawBitmap(glowBmp, null, dst, pAdd)
            pAdd.alpha = 255; pAdd.colorFilter = null
            glow(c, rnX[i], rnY[i], rnSz[i] * 1.6f, tintBone, 0.7f)
        }
    }

    // ------------------------------------------------------------------ the figure
    private fun wakeLevel(): Float {
        if (grandAt >= 0) return 1f
        val q = (vt - reaperAt).toFloat()
        if (q < 0f || q > 3.2f) return 0f
        return min(1f, q / 0.3f) * (1f - smooth((q - 1.4f) / 1.8f))
    }

    private fun drawReaper(c: Canvas, t: Float) {
        val wake = wakeLevel()
        val k = figK
        val bob = sin(t * 0.6f) * 2.4f * u
        val sc = 1f + 0.05f * wake
        c.save(); c.translate(hoodX, hoodY + bob); c.scale(sc, sc)
        c.drawBitmap(reaperFig, -figCx, -figCy, pBmp)
        c.restore()
        val lvl = 0.38f + 0.62f * wake
        val br = (0.9f + 0.1f * sin(t * 1.3f)) * (if (calm) 0.9f else 1f)
        if (wake > 0f) glow(c, hoodX, hoodY + bob - k * 0.2f, k * 3.4f, tintRed, 0.30f * wake)
        for (sx in 0..1) {
            val ex = hoodX + (if (sx == 0) -0.27f else 0.27f) * k * sc; val ey = hoodY + bob - 0.44f * k * sc
            glow(c, ex, ey, k * (0.34f + 0.5f * lvl), tintRed, 0.55f * lvl * br)
            pFill.color = mix(0xFF9A1020.toInt(), 0xFFFFE6D8.toInt(), lvl * lvl)
            dst.set(ex - 0.12f * k, ey - 0.040f * k, ex + 0.12f * k, ey + 0.040f * k)
            c.drawOval(dst, pFill)
            pFill.color = 0xFFFFF4EC.toInt(); pFill.alpha = (255 * lvl).toInt()
            dst.set(ex - 0.05f * k, ey - 0.016f * k, ex + 0.05f * k, ey + 0.016f * k); c.drawOval(dst, pFill); pFill.alpha = 255
        }
    }

    private fun drawCollectDim(c: Canvas) {
        if (collectTo < 0) return
        val q = ((vt - collectAt)).toFloat().coerceIn(0f, 1f)
        val a = sin(q * PI.toFloat()) * (if (calm) 0.28f else 0.5f)
        pFill.color = Color.argb((a * 255).toInt(), 0, 0, 6); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)
    }

    // ------------------------------------------------------------------ the grid
    private fun drawGrid(c: Canvas, t: Float) {
        val cm = min(cellW, cellH)
        for (i in 0 until SoulVault.CELLS) {
            cellRect(i, tmp)
            val cx = tmp.centerX(); val cy = tmp.centerY()
            val o = cells[i]
            if (o == null) {
                if (revealAt[i] > 0 && vt < revealAt[i]) drawSpinning(c, i, tmp, t)
                continue
            }
            val age = (vt - landedAt[i]).toFloat()
            val pop = if (age < 0.25f) 0.4f + age / 0.25f * 0.75f else if (age < 0.4f) 1.15f - (age - 0.25f) / 0.15f * 0.15f else 1f
            val done = if (tallied[i]) 0.93f else 1f
            when (o.kind) {
                Orb.SOUL -> {
                    val tier = if (o.value <= 3) 0 else if (o.value <= 10) 1 else 2
                    glow(c, cx, cy, cm * 0.95f * pop, tintGreen, (0.30f + 0.08f * sin(t * 2f + i)) * min(1f, pop))
                    val sz = cm * 1.0f * pop * done
                    if (age < 0.35f) {
                        val q = 1f - age / 0.35f
                        for (k in 1..3) {
                            pBmp.alpha = (80 * q * (4 - k) / 3f).toInt()
                            val off = k * cm * 0.26f * q
                            dst.set(cx - sz / 2, cy - sz * 0.56f - off, cx + sz / 2, cy + sz * 0.44f - off); c.drawBitmap(soulBmp[tier], null, dst, pBmp)
                        }
                        pBmp.alpha = 255
                    }
                    c.save(); c.rotate(sin(t * 1.3f + i * 1.7f) * 2.5f, cx, cy)
                    dst.set(cx - sz / 2, cy - sz * 0.56f, cx + sz / 2, cy + sz * 0.44f); c.drawBitmap(soulBmp[tier], null, dst, pBmp)
                    c.restore()
                }
                Orb.REAPER -> {
                    val sz = cm * 1.02f * pop * done
                    val col = collectTo == i
                    glow(c, cx, cy, cm * (if (col) 1.5f else 1.0f) * pop, tintRed, if (col) 0.55f else 0.28f)
                    sprite(c, reaperOrb, cx, cy, sz, sz)
                    val lvl = 0.5f + (if (col) 0.5f else 0f)
                    pFill.color = mix(0xFF9A1020.toInt(), 0xFFFFE6D8.toInt(), lvl * lvl)
                    val k = cm * 0.4f
                    dst.set(cx - k * 0.46f, cy - k * 0.30f, cx - k * 0.12f, cy - k * 0.18f); c.drawOval(dst, pFill)
                    dst.set(cx + k * 0.12f, cy - k * 0.30f, cx + k * 0.46f, cy - k * 0.18f); c.drawOval(dst, pFill)
                    glow(c, cx - k * 0.29f, cy - k * 0.24f, k * 0.5f, tintRed, 0.5f * lvl); glow(c, cx + k * 0.29f, cy - k * 0.24f, k * 0.5f, tintRed, 0.5f * lvl)
                }
                else -> {
                    val idx = o.kind - 1
                    val sz = cm * 0.84f * pop * done
                    glow(c, cx, cy - cm * 0.06f, cm * 1.05f * pop, jpTint[idx], 0.38f + 0.1f * sin(t * 2.2f + i))
                    sprite(c, sealBmp[idx], cx, cy - cm * 0.06f, sz, sz)
                }
            }
            if (age < 0.5f) {
                val q = age / 0.5f
                pStroke.color = when (o.kind) { Orb.SOUL -> Neon.GREEN; Orb.REAPER -> Neon.RED; else -> jpColor[o.kind - 1] }
                pStroke.alpha = ((1f - q) * 200).toInt(); pStroke.strokeWidth = (3.5f - 2.5f * q) * u
                c.drawCircle(cx, cy, cm * (0.30f + 0.85f * q), pStroke); pStroke.alpha = 255
            }
            if (tallied[i]) {
                pStroke.color = GOLD; pStroke.alpha = 120; pStroke.strokeWidth = 1.6f * u
                c.drawCircle(cx, cy, cm * 0.44f, pStroke); pStroke.alpha = 255
            }
        }
    }

    /** Burning edge: [n] overlapped fire sheets along y = [base], from x0 to x1, [hgt] tall, animated by [t]. */
    private fun fireLine(c: Canvas, x0: Float, x1: Float, base: Float, hgt: Float, n: Int, t: Float, strength: Float) {
        val gapX = (x1 - x0) / max(1, n - 1)
        pAdd.alpha = (200 * strength).toInt().coerceIn(0, 255)
        for (k in 0 until n) {
            val frame = ((t * flameRate + hash(k) * 6f).toInt() and 0x7FFFFFFF) % 6
            val fxp = x0 + gapX * k + (hash(k + 5) - 0.5f) * gapX * 0.7f
            val sw = gapX * (1.9f + 0.7f * hash(k + 17))
            val hh = hgt * (0.55f + 0.7f * hash(k + 33)) * (0.75f + 0.25f * flick(t, k * 1.9f)) * (if (k == 0 || k == n - 1) 0.7f else 1f)
            dst.set(fxp - sw / 2f, base - hh * 0.9f, fxp + sw / 2f, base + hh * 0.1f)
            c.drawBitmap(fireBmp[frame], null, dst, pAdd)
        }
        pAdd.alpha = 255
    }

    /** Cheap deterministic hash to 0..1 (stable per index, no allocation). */
    private fun hash(i: Int): Float {
        var x = i * 374761393 + 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 65535f
    }

    private fun sprite(c: Canvas, b: Bitmap, cx: Float, cy: Float, w: Float, h: Float) {
        dst.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2); c.drawBitmap(b, null, dst, pBmp)
    }

    private fun drawSpinning(c: Canvas, i: Int, r: RectF, t: Float) {
        c.save(); c.clipRect(r.left + 5f * u, r.top + 5f * u, r.right - 5f * u, r.bottom - 5f * u)
        val ph = ((vt * 5 + i * 0.37) % 1.0).toFloat()
        val s = min(cellW, cellH) * 0.7f
        pBmp.alpha = 50
        for (k in -1..1) {
            val cy = r.top + (ph + k) * cellH + cellH / 2
            dst.set(r.centerX() - s / 2, cy - s * 0.9f, r.centerX() + s / 2, cy + s * 0.9f); c.drawBitmap(soulBmp[0], null, dst, pBmp)
        }
        pBmp.alpha = 255
        dst.set(r.left, r.top, r.right, r.bottom); c.drawBitmap(mistBmp, null, dst, pBmp)
        c.restore()
    }

    private fun drawValues(c: Canvas, t: Float) {
        val cm = min(cellW, cellH)
        for (i in 0 until SoulVault.CELLS) {
            val o = cells[i] ?: continue
            cellRect(i, tmp)
            val cx = tmp.centerX(); val cy = tmp.centerY()
            val age = (vt - landedAt[i]).toFloat()
            val pop = if (age < 0.25f) 0.4f + age / 0.25f * 0.75f else if (age < 0.4f) 1.15f - (age - 0.25f) / 0.15f * 0.15f else 1f
            val maxW = cellW * 0.80f
            when (o.kind) {
                Orb.SOUL -> {
                    val big = o.value >= 15
                    num(c, tokens(cellValue[i]), cx, cy + cm * 0.10f, cm * (if (big) 0.30f else 0.26f) * pop, if (big) GOLD_HOT else 0xFFF6F0DC.toInt(), maxW * 0.9f)
                }
                Orb.REAPER -> if (cellValue[i] > 0) ribbon(c, cx, tmp.bottom - cm * 0.06f, cellW * 0.86f, cm * 0.21f, Neon.RED, null, tokens(cellValue[i]), 0xFFFFD6D2.toInt())
                else -> if (age > 0.12f) {
                    val idx = o.kind - 1
                    ribbon(c, cx, tmp.bottom - cm * 0.06f, cellW * 0.92f, cm * 0.21f, jpColor[idx], jpNames[idx], tokens(cellValue[i]), 0xFFF6F0DC.toInt())
                }
            }
        }
    }

    /** Dark engraved ribbon across the foot of a niche: optional tier name on the left, token value on the right. */
    private fun ribbon(c: Canvas, cx: Float, bottom: Float, rw: Float, rh: Float, accent: Int, name: String?, value: Long, valueCol: Int) {
        dst.set(cx - rw / 2, bottom - rh, cx + rw / 2, bottom)
        pFill.color = 0xEB040406.toInt(); c.drawRoundRect(dst, rh * 0.32f, rh * 0.32f, pFill)
        pStroke.color = accent; pStroke.alpha = 215; pStroke.strokeWidth = 1.5f * u; c.drawRoundRect(dst, rh * 0.32f, rh * 0.32f, pStroke); pStroke.alpha = 255
        val pad = rh * 0.34f
        var avail = rw - pad * 2
        if (name != null) {
            pText.typeface = serif; pText.letterSpacing = 0.06f; pText.textSize = rh * 0.54f; pText.textAlign = Paint.Align.LEFT
            pText.style = Paint.Style.FILL; pText.color = accent
            c.drawText(name, dst.left + pad, dst.centerY() + rh * 0.20f, pText)
            avail -= pText.measureText(name) + pad * 0.6f
            pText.textAlign = Paint.Align.CENTER; pText.letterSpacing = 0f
        }
        val n = fmt(value)
        pText.typeface = serif; pText.textSize = rh * 0.72f
        val wd = pText.measureText(nb, nbStart, n)
        val sz = if (wd > avail && wd > 0f) rh * 0.72f * avail / wd else rh * 0.72f
        pText.textSize = sz; pText.style = Paint.Style.FILL; pText.color = valueCol
        if (name != null) { pText.textAlign = Paint.Align.RIGHT; c.drawText(nb, nbStart, n, dst.right - pad, dst.centerY() + sz * 0.34f, pText); pText.textAlign = Paint.Align.CENTER }
        else c.drawText(nb, nbStart, n, cx, dst.centerY() + sz * 0.34f, pText)
    }

    // ------------------------------------------------------------------ collector beams: scythe slashes into the Reaper
    private fun bez(a: Float, b: Float, c: Float, q: Float): Float { val m = 1f - q; return m * m * a + 2f * m * q * b + q * q * c }

    private fun drawBeams(c: Canvas, t: Float) {
        val from = collectFrom ?: return
        val to = collectTo; if (to < 0) return
        val q = ((vt - collectAt)).toFloat().coerceIn(0f, 1f)
        cellRect(to, tmp); val tx = tmp.centerX(); val ty = tmp.centerY()
        val fade = 1f - smooth((q - 0.85f) / 0.15f)
        var idx = 0
        for (f in from) {
            cellRect(f, tmp)
            val sx = tmp.centerX(); val sy = tmp.centerY()
            val dx = tx - sx; val dy = ty - sy; val len = sqrt(dx * dx + dy * dy)
            if (len < 2f) { idx++; continue }
            val nx = -dy / len; val ny = dx / len
            val bend = len * 0.30f * (if (idx and 1 == 0) 1f else -1f)
            val cx = (sx + tx) / 2f + nx * bend; val cy = (sy + ty) / 2f + ny * bend
            val h = smooth((q * 1.35f - idx * 0.012f).coerceIn(0f, 1f))
            val h0 = max(0f, h - 0.42f)
            // the source flares as the blade leaves it
            glow(c, sx, sy, cellW * 0.65f, tintBone, 0.38f * (1f - h) * fade)
            var px = bez(sx, cx, tx, h0); var py = bez(sy, cy, ty, h0)
            for (k in 1..6) {
                val hh = h0 + (h - h0) * k / 6f
                val x = bez(sx, cx, tx, hh); val y = bez(sy, cy, ty, hh)
                val wgt = k / 6f
                pStroke.color = Neon.RED; pStroke.alpha = (165 * wgt * fade).toInt(); pStroke.strokeWidth = (3f + 14f * wgt) * u
                c.drawLine(px, py, x, y, pStroke)
                pStroke.color = 0xFFFFF0DC.toInt(); pStroke.alpha = (235 * wgt * fade).toInt(); pStroke.strokeWidth = (1.4f + 4.0f * wgt) * u
                c.drawLine(px, py, x, y, pStroke)
                px = x; py = y
            }
            pStroke.alpha = 255
            // blade head
            val hx = bez(sx, cx, tx, h); val hy = bez(sy, cy, ty, h)
            val tdx = 2f * (1f - h) * (cx - sx) + 2f * h * (tx - cx); val tdy = 2f * (1f - h) * (cy - sy) + 2f * h * (ty - cy)
            c.save(); c.translate(hx, hy); c.rotate(Math.toDegrees(atan2(tdy, tdx).toDouble()).toFloat())
            pBmp.alpha = (255 * fade).toInt(); val cs = 70f * u
            dst.set(-cs * 0.55f, -cs * 0.5f, cs * 0.55f, cs * 0.5f); c.drawBitmap(crescent, null, dst, pBmp); pBmp.alpha = 255
            c.restore()
            glow(c, hx, hy, 22f * u, tintGreen, 0.8f * fade)
            idx++
        }
        // the Reaper cell drinks: a growing blood-red vortex ring
        cellRect(to, tmp)
        pStroke.color = Neon.RED; pStroke.alpha = (170 * fade).toInt(); pStroke.strokeWidth = 3f * u
        c.drawCircle(tmp.centerX(), tmp.centerY(), min(cellW, cellH) * (0.6f + 0.5f * q), pStroke); pStroke.alpha = 255
    }

    // ------------------------------------------------------------------ souls streaming into the total during the tally
    private fun spawnSpark(cell: Int, gold: Boolean) {
        for (i in 0 until spN) if (spT[i] < 0) {
            cellRect(cell, tmp); spX[i] = tmp.centerX(); spY[i] = tmp.centerY(); spT[i] = vt; spGold[i] = gold; return
        }
    }

    private fun drawSparks(c: Canvas) {
        val tx = totalRect.centerX(); val ty = totalRect.centerY()
        for (i in 0 until spN) {
            if (spT[i] < 0) continue
            val q = ((vt - spT[i]) / 0.5).toFloat()
            if (q >= 1f) { spT[i] = -1.0; bumpAt = vt; continue }
            val e = smooth(q)
            val mx = (spX[i] + tx) / 2f + (ty - spY[i]) * 0.18f; val my = (spY[i] + ty) / 2f - (tx - spX[i]) * 0.18f
            val x = bez(spX[i], mx, tx, e); val y = bez(spY[i], my, ty, e)
            val f = if (spGold[i]) tintGold else tintGreen
            glow(c, x, y, 20f * u, f, 0.9f * (1f - q * 0.5f))
            val e2 = smooth((q - 0.12f).coerceAtLeast(0f)); glow(c, bez(spX[i], mx, tx, e2), bez(spY[i], my, ty, e2), 12f * u, f, 0.5f)
        }
    }

    // ------------------------------------------------------------------ plates, counters
    private fun drawPlates(c: Canvas, t: Float) {
        for (i in 0 until 4) {
            val r = jpRect[i]
            c.drawBitmap(plates[i]!!, r.left - plateM, r.top - plateM, null)
            val flash = (i < 3 && jackpotName == jpNames[i] && vt - jackpotFlash < 1.4) || (i == 3 && grandAt >= 0)
            if (flash) {
                val e = if (i == 3) 1f else (1f - ((vt - jackpotFlash) / 1.4).toFloat())
                val pulse = 0.65f + 0.35f * sin(t * 8f)
                glow(c, r.centerX(), r.centerY(), max(r.width(), r.height()) * 0.8f, jpTint[i], 0.55f * e * pulse * (if (calm) 0.6f else 1f))
            }
            if (i == 3) drawGrandFlames(c, r, t)
        }
        drawRespins(c, t)
        // total
        val r = totalRect
        c.drawBitmap(plates[5]!!, r.left - plateM, r.top - plateM, null)
        val live = cellValue.sum() + if (grandAt >= 0) ReaperConfig.GRAND else 0
        val tgt = live.toFloat()
        shownMult += (tgt - shownMult) * 0.22f
        if (abs(tgt - shownMult) < 0.6f) shownMult = tgt
        val v = tokens(shownMult.toInt().coerceAtLeast(0))
        val nh = r.height() * 0.52f
        val big = abs(tgt - shownMult) > 0.5f
        val bump = (1f - ((vt - bumpAt) / 0.3).toFloat()).coerceIn(0f, 1f)
        glow(c, r.centerX(), r.top + r.height() * 0.62f, r.width() * 0.35f, tintGreen, (if (big) 0.30f else 0.12f) + 0.35f * bump)
        c.save(); c.scale(1f + 0.05f * bump, 1f + 0.05f * bump, r.centerX(), r.top + r.height() * 0.64f)
        num(c, v, r.centerX() - r.width() * 0.07f, r.top + r.height() * 0.80f, nh, 0xFFF4EDD6.toInt(), r.width() * 0.56f)
        c.restore()
        label(c, "TOKENS", r.right - r.width() * 0.17f, r.top + r.height() * 0.80f, r.height() * 0.17f, 0xFFB8AF94.toInt(), serif, 0.18f)
        // bet
        c.drawBitmap(plates[6]!!, betRect.left - plateM, betRect.top - plateM, null)
    }

    private fun drawGrandFlames(c: Canvas, r: RectF, t: Float) {
        val active = grandAt >= 0
        fireLine(c, r.left + r.width() * 0.10f, r.right - r.width() * 0.10f, r.top + 8f * u, (if (active) 64f else 38f) * u, if (portrait) 4 else 6, t, if (active) 1f else 0.75f)
        glow(c, r.centerX(), r.top + r.height() * 0.2f, r.width() * 0.6f, tintGold, (if (active) 0.40f else 0.14f) * flick(t, 9f))
    }

    private fun drawRespins(c: Canvas, t: Float) {
        val r = respinRect
        c.drawBitmap(plates[4]!!, r.left - plateM, r.top - plateM, null)
        val tall = r.height() > r.width() * 0.62f
        val nx = if (tall) r.centerX() else r.left + r.width() * 0.24f
        val ny = if (tall) r.top + r.height() * 0.50f else r.top + r.height() * 0.62f
        val nr = if (tall) r.height() * 0.19f else r.height() * 0.26f
        val flashAge = (vt - resetFlash).toFloat()
        val flash = flashAge in 0f..0.9f
        val s = if (flash) 1f + 0.22f * (1f - flashAge / 0.9f) else 1f
        val low = respins <= 1
        glow(c, nx, ny, nr * 2.4f, tintRed, (if (low) 0.45f else 0.22f) + (if (flash) 0.3f * (1f - flashAge / 0.9f) else 0f))
        c.save(); c.scale(s, s, nx, ny)
        nb[nb.size - 1] = '0' + respins.coerceIn(0, 9); pText.typeface = serif; pText.letterSpacing = 0f; pText.textSize = nr * 1.45f
        pText.style = Paint.Style.STROKE; pText.strokeWidth = nr * 0.22f; pText.color = 0xFF000000.toInt(); c.drawText(nb, nb.size - 1, 1, nx, ny + nr * 0.5f, pText)
        pText.style = Paint.Style.FILL; pText.color = if (respins == 0) 0xFF77716A.toInt() else if (low) 0xFFFF8A80.toInt() else 0xFFF6EBD0.toInt()
        c.drawText(nb, nb.size - 1, 1, nx, ny + nr * 0.5f, pText)
        c.restore()
        // skull pips
        val ps = pipOn.width * (if (tall) 1.0f else 0.95f)
        val n = ReaperConfig.VAULT_RESPINS
        for (i in 0 until n) {
            val px: Float; val py: Float
            if (tall) { px = r.centerX() + (i - (n - 1) / 2f) * ps * 1.12f; py = r.top + r.height() * 0.84f }
            else { px = r.left + r.width() * 0.58f + i * ps * 1.08f; py = r.top + r.height() * 0.62f }
            val on = i < respins
            val pulse = if (on && flash) 1f + 0.28f * (1f - flashAge / 0.9f) else 1f
            sprite(c, if (on) pipOn else pipOff, px, py, ps * pulse, ps * pulse)
        }
    }

    private fun drawSpinButton(c: Canvas, t: Float) {
        if (!started || summaryAt >= 0) return
        val ready = waitingForSpin
        val down = t + introStart - spinPressedAt < 0.15
        val cx = spinBtn.centerX(); val cy = spinBtn.centerY() + if (down) 4f * u else 0f
        val rad = spinBtn.width() / 2
        val glowK = if (ready) 0.5f + 0.5f * sin(t * 3.2f) else 0f
        if (ready) {
            glow(c, cx, cy, rad * 2.1f, tintRed, 0.22f + 0.22f * glowK)
            pStroke.color = Neon.RED; pStroke.alpha = (40 + 90 * glowK).toInt(); pStroke.strokeWidth = 2.4f * u
            c.drawCircle(cx, cy, rad * (1.12f + 0.07f * glowK), pStroke); pStroke.alpha = 255
        }
        val s = if (down) 0.96f else 1f
        val d = (if (ready) spinOn else spinOff)
        sprite(c, d, cx, cy, spinBtn.width() * 1.25f * s, spinBtn.width() * 1.25f * s)
        if (ready) {
            val a = 0.65f + 0.35f * sin(t * 3.2f)
            label(c, "TAP TO RESPIN", cx, spinBtn.bottom + (if (portrait) 24f else 34f) * u, (if (portrait) 17f else 21f) * u, mix(0xFF9A8E78.toInt(), 0xFFF2EBD8.toInt(), a), serif, 0.14f)
        }
    }

    // ------------------------------------------------------------------ gothic typography (baked)
    /** Title lettering baked to a bitmap: coloured glow, black outline, vertical metal gradient, bevel highlight. */
    private fun titleBitmap(text: String, size0: Float, maxW: Float, top: Int, mid: Int, bot: Int, glowCol: Int, tracking: Float = 0.1f): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.typeface = serif; p.letterSpacing = tracking; p.textSize = size0
        var size = size0
        val w0 = p.measureText(text)
        if (w0 > maxW) { size = size0 * maxW / w0; p.textSize = size }
        val tw = p.measureText(text)
        val m = size * 0.5f
        val bw = (tw + 2 * m).toInt(); val bh = (size * 1.3f + 2 * m).toInt()
        val base = m + size * 0.98f
        return mk(bw, bh) { c ->
            p.textAlign = Paint.Align.CENTER
            val cx = bw / 2f
            p.style = Paint.Style.FILL_AND_STROKE; p.strokeWidth = size * 0.10f; p.color = withA(glowCol, 210); p.maskFilter = BlurMaskFilter(size * 0.2f, BlurMaskFilter.Blur.NORMAL)
            c.drawText(text, cx, base, p); p.maskFilter = null
            p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.095f; p.strokeJoin = Paint.Join.ROUND; p.color = 0xFF0A0604.toInt()
            c.drawText(text, cx, base, p)
            p.style = Paint.Style.FILL; p.strokeWidth = 0f
            p.alpha = 255; p.shader = LinearGradient(0f, base - size * 0.74f, 0f, base + size * 0.08f, intArrayOf(top, mid, bot), floatArrayOf(0f, 0.48f, 1f), Shader.TileMode.CLAMP)
            c.drawText(text, cx, base, p)
            p.alpha = 255; p.shader = LinearGradient(0f, base - size * 0.74f, 0f, base - size * 0.40f, 0x77FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            c.drawText(text, cx, base, p); p.shader = null
        }
    }

    /** Thorned divider: a blade of brass to each side of a red-eyed skull. */
    private fun ornament(c: Canvas, cx: Float, y: Float, half: Float, alpha: Float) {
        val gap = 30f * u; val th = 3.2f * u
        pFill.color = Color.argb((255 * alpha).toInt(), 168, 135, 58)
        for (side in 0..1) {
            val sg = if (side == 0) -1f else 1f
            path.reset(); path.moveTo(cx + sg * gap, y - th); path.lineTo(cx + sg * half, y); path.lineTo(cx + sg * gap, y + th); path.close()
            c.drawPath(path, pFill)
            path.reset(); path.moveTo(cx + sg * (gap + 6f * u), y); path.lineTo(cx + sg * (gap + 16f * u), y - 9f * u); path.lineTo(cx + sg * (gap + 22f * u), y); path.lineTo(cx + sg * (gap + 16f * u), y + 9f * u); path.close()
            c.drawPath(path, pFill)
        }
        pBmp.alpha = (255 * alpha).toInt(); sprite(c, pipOn, cx, y, 40f * u, 40f * u); pBmp.alpha = 255
    }

    private val introRect = RectF()
    private var introTitleY = 0f
    private var introOrnY = 0f
    private var introPromptY = 0f
    private val sumRect = RectF()
    private val jpTitle = arrayOfNulls<Bitmap>(3)
    private val boneShader = LinearGradient(0f, -0.78f, 0f, 0.02f, intArrayOf(0xFFFFFAEA.toInt(), 0xFFE9E1CB.toInt(), 0xFFA39B84.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val goldShader = LinearGradient(0f, -0.78f, 0f, 0.02f, intArrayOf(0xFFFFF6C8.toInt(), GOLD, 0xFFB5541A.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val ashShader = LinearGradient(0f, -0.78f, 0f, 0.02f, intArrayOf(0xFFC9C5B6.toInt(), 0xFF8E8A7C.toInt(), 0xFF5A5850.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

    private fun layoutOverlays() {
        val W = vw; val H = vh
        val tw = if (portrait) W * 0.92f else min(W * 0.80f, 940f * u)
        val rowH = (if (portrait) 84f else 64f) * u
        val th = rowH * 4 + (if (portrait) 78f else 60f) * u
        val top = H * (if (portrait) 0.37f else 0.40f)
        introRect.set((W - tw) / 2, top, (W + tw) / 2, top + th)
        introTitleY = H * (if (portrait) 0.18f else 0.17f)
        introOrnY = introTitleY + H * (if (portrait) 0.07f else 0.095f)
        introPromptY = min(H - 30f * u, introRect.bottom + 56f * u)
        val sw = if (portrait) W * 0.92f else min(W * 0.76f, 900f * u)
        val sh = if (portrait) H * 0.52f else min(H * 0.68f, 500f * u)
        val cy = H * 0.49f
        sumRect.set((W - sw) / 2, cy - sh / 2, (W + sw) / 2, cy + sh / 2)
    }

    private fun bakeIntro() {
        layoutOverlays()
        val H = vh; val W = vw
        introTitle = titleBitmap("THE SOUL VAULT", H * (if (portrait) 0.066f else 0.098f), W * 0.9f, BONE_HOT, 0xFFD9D2BE.toInt(), 0xFFC02A3E.toInt(), Neon.RED)
        val rowH = (if (portrait) 84f else 64f) * u
        introPlate = plateBitmap(introRect, Neon.RED) { c, w, h ->
            val tp = Paint(Paint.ANTI_ALIAS_FLAG); tp.textAlign = Paint.Align.LEFT
            val iconW = (if (portrait) 96f else 112f) * u
            val tx = iconW + 24f * u
            val maxT = w - tx - 22f * u
            val rows = arrayOf(
                "RESPIN" to "Souls stay bound. Every new orb resets your respins to ${ReaperConfig.VAULT_RESPINS}.",
                "JACKPOT ORBS" to "MINI ${ReaperConfig.MINI}×     MINOR ${ReaperConfig.MINOR}×     MAJOR ${ReaperConfig.MAJOR}×",
                "THE REAPER ORB" to "Harvests every soul on the board.",
                "FILL ALL ${SoulVault.CELLS}" to "Claim the GRAND  —  ${ReaperConfig.GRAND}× your bonus bet.")
            for (i in rows.indices) {
                val y0 = 14f * u + i * rowH; val cy = y0 + rowH / 2f
                if (i > 0) { tp.color = 0x33D2B060; c.drawRect(26f * u, y0 - 0.6f * u, w - 26f * u, y0 + 0.6f * u, tp) }
                val ix = iconW / 2f + (if (portrait) 24f else 12f) * u
                when (i) {
                    0 -> for (k in 0..2) { dst.set(ix + (k - 1) * 31f * u - 15f * u, cy - 15f * u, ix + (k - 1) * 31f * u + 15f * u, cy + 15f * u); c.drawBitmap(pipOn, null, dst, pBmp) }
                    1 -> for (k in 0..2) { val xx = ix + (k - 1) * 33f * u; dst.set(xx - 19f * u, cy - 19f * u, xx + 19f * u, cy + 19f * u); c.drawBitmap(sealBmp[k], null, dst, pBmp) }
                    2 -> { dst.set(ix - 33f * u, cy - 33f * u, ix + 33f * u, cy + 33f * u); c.drawBitmap(reaperOrb, null, dst, pBmp) }
                    else -> { dst.set(ix - 36f * u, cy - 36f * u, ix + 36f * u, cy + 36f * u); c.drawBitmap(sealBmp[3], null, dst, pBmp) }
                }
                tp.typeface = serif; tp.letterSpacing = 0.14f
                val ts = fit(tp, rows[i].first, maxT, rowH * 0.30f)
                tp.textSize = ts; tp.color = 0xCC000000.toInt(); c.drawText(rows[i].first, tx, cy - rowH * 0.06f + 1.5f * u, tp)
                tp.color = 0xFFD2B060.toInt(); c.drawText(rows[i].first, tx, cy - rowH * 0.06f, tp)
                tp.typeface = cond; tp.letterSpacing = 0f
                tp.textSize = fit(tp, rows[i].second, maxT, rowH * 0.27f)
                tp.color = 0xFFD9D2BE.toInt(); c.drawText(rows[i].second, tx, cy + rowH * 0.27f, tp)
            }
            tp.textAlign = Paint.Align.CENTER; tp.typeface = serifIt; tp.letterSpacing = 0f
            val foot = "Bonus bet: ${result.bet} tokens, your average stake while collecting relics"
            tp.textSize = fit(tp, foot, w - 40f * u, rowH * 0.25f); tp.color = 0xFF9A9486.toInt()
            c.drawText(foot, w / 2f, h - 20f * u, tp)
        }
    }

    // ------------------------------------------------------------------ jackpot banner / grand
    private fun drawJackpotFlash(c: Canvas, t: Float) {
        val e = (vt - jackpotFlash).toFloat()
        val a = (1f - e / 1.4f).coerceIn(0f, 1f)
        val idx = jackpotKind.coerceIn(0, 2)
        val cx = gridRect.centerX(); val cy = gridRect.centerY()
        c.save(); c.rotate(e * (if (calm) 5f else 11f), cx, cy)
        pAdd.colorFilter = jpTint[idx]; pAdd.alpha = ((if (calm) 70 else 120) * a * min(1f, e * 6f)).toInt()
        val rs = gridRect.height() * 1.25f; dst.set(cx - rs, cy - rs, cx + rs, cy + rs); c.drawBitmap(raysBmp, null, dst, pAdd)
        pAdd.colorFilter = null; pAdd.alpha = 255
        c.restore()
        glow(c, cx, cy, gridRect.height() * 0.8f, jpTint[idx], 0.32f * a)
        val tb = jpTitleFor(idx)
        val s = 1f + 0.16f * (1f - a)
        pBmp.alpha = (255 * min(1f, a * 2.4f)).toInt()
        sprite(c, tb, cx, cy + gridRect.height() * 0.02f, tb.width * s, tb.height * s); pBmp.alpha = 255
    }

    private fun jpTitleFor(idx: Int): Bitmap {
        jpTitle[idx]?.let { return it }
        val b = titleBitmap(jpBang[idx], gridRect.height() * 0.2f, gridRect.width() * 0.8f, jpHot[idx], jpColor[idx], shade(jpColor[idx], 0.45f), jpColor[idx])
        jpTitle[idx] = b; return b
    }

    private fun grandTitleBmp(): Bitmap {
        grandTitle?.let { return it }
        val b = titleBitmap("GRAND", vh * 0.24f, vw * 0.8f, 0xFFFFFFFF.toInt(), GOLD, 0xFFB5541A.toInt(), EMBER, 0.12f)
        grandTitle = b; return b
    }

    private var finaleStep = 0

    /** One piece of the finale's artwork per call (summary plaque + title, GRAND title, outro door). */
    private fun prebakeStep() {
        when (finaleStep) {
            0 -> ensureSummary(summaryTierOf())
            1 -> if (result.grand) grandTitleBmp()
            else -> ensureDoor()
        }
        finaleStep++
    }

    /** Guarantees the finale's artwork exists: normally baked a piece per idle frame before the last SPIN, this finishes any leftovers. */
    private fun prebakeFinale() { while (finaleStep < 3) prebakeStep() }

    private fun drawGrand(c: Canvas, t: Float) {
        val e = (vt - grandAt).toFloat()
        val W = width.toFloat(); val H = height.toFloat()
        pFill.color = Color.argb((200 * smooth(e * 2.5f)).toInt(), 6, 3, 2); c.drawRect(0f, 0f, W, H, pFill)
        val cx = W / 2f; val cy = H * 0.46f
        c.save(); c.rotate(e * (if (calm) 3f else 7f), cx, cy)
        pAdd.colorFilter = tintGold; pAdd.alpha = ((if (calm) 70 else 130) * smooth(e * 2f)).toInt()
        val rs = max(W, H) * 0.78f; dst.set(cx - rs, cy - rs, cx + rs, cy + rs); c.drawBitmap(raysBmp, null, dst, pAdd)
        pAdd.colorFilter = null; pAdd.alpha = 255
        c.restore()
        glow(c, cx, cy, H * 0.55f, tintGold, 0.32f * smooth(e * 2f))
        val gt = grandTitleBmp()
        val s = (1f + 0.035f * sin(e * 4.2f)) * (0.7f + 0.3f * smooth(e * 2.2f))
        pBmp.alpha = (255 * smooth(e * 3f)).toInt()
        sprite(c, gt, cx, cy, gt.width * s, gt.height * s); pBmp.alpha = 255
        // fire along the lettering
        val capTop = cy - gt.height * s * 0.17f
        val fl = gt.width * s * 0.70f
        fireLine(c, cx - fl / 2f, cx + fl / 2f, capTop + H * 0.02f, H * 0.2f * min(1f, e * 2f), 9, t, 1f)
        label(c, "THE VAULT IS FULL", cx, H * 0.69f, H * (if (portrait) 0.034f else 0.05f), 0xFFF2EBD8.toInt(), serif, 0.24f, true, W * 0.9f)
    }

    // ------------------------------------------------------------------ intro and summary
    private fun drawIntro(c: Canvas, t: Float, ia: Float) {
        val W = width.toFloat(); val H = height.toFloat()
        pFill.color = Color.argb((236 * ia).toInt(), 2, 3, 4); c.drawRect(0f, 0f, W, H, pFill)
        val e = t
        val a = smooth(e * 2.2f) * ia
        val tb = introTitle ?: return
        val s = 0.94f + 0.06f * smooth(e * 1.6f)
        glow(c, W / 2f, introTitleY, W * 0.42f, tintRed, 0.16f * a)
        pBmp.alpha = (255 * a).toInt(); sprite(c, tb, W / 2f, introTitleY, tb.width * s, tb.height * s); pBmp.alpha = 255
        ornament(c, W / 2f, introOrnY, min(W * 0.30f, 330f * u), a)
        pBmp.alpha = (255 * smooth((e - 0.25f) * 2f) * ia).toInt()
        c.drawBitmap(introPlate!!, introRect.left - plateM, introRect.top - plateM, pBmp); pBmp.alpha = 255
        val pa = 0.55f + 0.45f * sin(t * 2.6f)
        if (ia >= 1f) label(c, "TAP TO BEGIN", W / 2f, introPromptY, H * (if (portrait) 0.034f else 0.045f), mix(0xFF7A0F18.toInt(), 0xFFFF3B4E.toInt(), pa), serif, 0.22f, true, W * 0.9f)
    }

    private fun summaryTierOf(): Int {
        if (belowPrice) return 0
        if (result.grand) return 4
        val x = result.payTokens.toDouble() / result.bet
        return if (x >= 100) 3 else if (x >= 40) 2 else if (x >= 18) 1 else 0
    }

    private val wonLine by lazy { "TOKENS WON  •  ${Money.describe(result.payTokens)}" }
    /** "BOUGHT FOR 310 TOKENS  •  NET -230": plain, no celebration. */
    private val boughtLine by lazy {
        val net = result.payTokens - boughtPrice
        "BOUGHT FOR ${Money.fmt(boughtPrice)} TOKENS  •  NET " + (if (net > 0) "+${Money.fmt(net)}" else if (net < 0) "-${Money.fmt(-net)}" else "0")
    }
    private val noteLine by lazy {
        when {
            result.capped -> "Harvest of ${Money.fmt(result.rawTokens)} tokens capped at the 25-book maximum win"
            result.rawTokens != result.payTokens -> "${Money.fmt(result.rawTokens)} tokens rounded down to whole macks"
            else -> "Bonus bet: ${result.bet} tokens"
        }
    }

    private fun web(c: Canvas, x0: Float, y0: Float, sx: Float, sy: Float, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG); p.style = Paint.Style.STROKE; p.strokeWidth = 1.1f * u; p.color = 0x55D9D2BE
        val n = 6
        for (k in 0..n) { val a = (PI / 2 * k / n).toFloat(); c.drawLine(x0, y0, x0 + sx * r * cos(a), y0 + sy * r * sin(a), p) }
        for (ring in 1..5) {
            val rr = r * ring / 5f
            for (k in 0 until n) {
                val a0 = (PI / 2 * k / n).toFloat(); val a1 = (PI / 2 * (k + 1) / n).toFloat(); val am = (a0 + a1) / 2f
                path.reset(); path.moveTo(x0 + sx * rr * cos(a0), y0 + sy * rr * sin(a0))
                path.quadTo(x0 + sx * rr * 0.86f * cos(am), y0 + sy * rr * 0.86f * sin(am), x0 + sx * rr * cos(a1), y0 + sy * rr * sin(a1))
                c.drawPath(path, p)
            }
        }
    }

    private fun ensureSummary(tier: Int) {
        if (summaryTier == tier && summaryPlate != null) return
        summaryTier = tier
        val accent = when (tier) { 0 -> 0xFF566450.toInt(); 1 -> Neon.GREEN; 2 -> Neon.RED; else -> GOLD }
        summaryPlate = plateBitmap(sumRect, accent) { c, w, h ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            if (tier == 0) { web(c, 6f * u, 6f * u, 1f, 1f, h * 0.30f); web(c, w - 6f * u, h - 6f * u, -1f, -1f, h * 0.22f) }
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * u; p.color = withA(accent, if (tier == 0) 70 else 150)
            tmp.set(20f * u, 20f * u, w - 20f * u, h - 20f * u); c.drawRoundRect(tmp, 8f * u, 8f * u, p)
            if (tier >= 3) { p.strokeWidth = 2.4f * u; p.color = withA(GOLD, 120); tmp.inset(7f * u, 7f * u); c.drawRoundRect(tmp, 6f * u, 6f * u, p) }
        }
        val title = if (belowPrice) "THE VAULT FALLS SHORT" else when (tier) { 0 -> "THE VAULT IS EMPTY"; 1 -> "SOULS HARVESTED"; 2 -> "THE VAULT YIELDS"; 3 -> "THE VAULT RUNS RED"; else -> "THE GRAND VAULT FALLS" }
        val sz = sumRect.height() * (if (portrait) 0.105f else 0.125f)
        val mw = sumRect.width() * 0.9f
        summaryTitle = when (tier) {
            0 -> titleBitmap(title, sz, mw, 0xFFB5B1A2.toInt(), 0xFF8E8A7C.toInt(), 0xFF4A4A44.toInt(), 0xFF1E2A1E.toInt())
            1 -> titleBitmap(title, sz, mw, BONE_HOT, 0xFFD9D2BE.toInt(), 0xFF8A8472.toInt(), 0xFF3F8F1A.toInt())
            2 -> titleBitmap(title, sz, mw, BLOOD_HOT, Neon.RED, BLOOD_DEEP, Neon.RED)
            3 -> titleBitmap(title, sz, mw, GOLD_HOT, GOLD, 0xFFB23A12.toInt(), EMBER)
            else -> titleBitmap(title, sz, mw, 0xFFFFFFFF.toInt(), GOLD, 0xFFB5541A.toInt(), EMBER)
        }
    }

    private fun drawSummary(c: Canvas, t: Float) {
        val e = (vt - summaryAt).toFloat()
        val tier = summaryTierOf()
        ensureSummary(tier)
        val W = width.toFloat(); val H = height.toFloat()
        val sa = smooth(e * 2.2f)
        pFill.color = Color.argb(((if (tier == 0) 232 else 206) * sa).toInt(), 0, 0, 0); c.drawRect(0f, 0f, W, H, pFill)
        val r = sumRect
        if (tier >= 2) glow(c, r.centerX(), r.centerY(), max(r.width(), r.height()) * 0.8f, if (tier >= 3) tintGold else tintRed, 0.22f * sa)
        // the tablet drops in with weight: heavy fall, one small rebound
        val fall = (e / 0.42f).coerceIn(0f, 1f)
        val y = -(1f - fall * fall) * H * 0.55f + (if (e > 0.42f && !calm) -abs(sin((e - 0.42f) * 13f)) * 9f * u * kotlin.math.exp(-(e - 0.42f) * 7f) else 0f)
        c.save(); c.translate(0f, y)
        pBmp.alpha = (255 * min(1f, e * 5f)).toInt()
        c.drawBitmap(summaryPlate!!, r.left - plateM, r.top - plateM, pBmp); pBmp.alpha = 255
        val tb = summaryTitle!!
        sprite(c, tb, r.centerX(), r.top + r.height() * 0.17f, tb.width.toFloat(), tb.height.toFloat())
        if (tier >= 3) fireLine(c, r.left + r.width() * 0.06f, r.right - r.width() * 0.06f, r.top + 6f * u, r.height() * 0.2f, 8, t, 1f)
        // amount (counts up while the stone settles)
        val shown = result.payTokens * smooth(e / 0.8f)
        val sh = when (tier) { 0 -> ashShader; 4, 3 -> goldShader; else -> boneShader }
        val ay = r.top + r.height() * 0.545f
        val asz = r.height() * 0.24f
        if (tier >= 1) glow(c, r.centerX(), ay - asz * 0.3f, r.width() * 0.34f, if (tier >= 3) tintGold else if (tier == 2) tintRed else tintGreen, if (tier >= 3) 0.42f else 0.22f)
        mtx.setScale(1f, asz); mtx.postTranslate(0f, ay); sh.setLocalMatrix(mtx)
        val n = fmt(shown.toLong())
        pText.typeface = serif; pText.letterSpacing = 0f; pText.textSize = asz
        val wd = pText.measureText(nb, nbStart, n)
        if (wd > r.width() * 0.86f) pText.textSize = asz * r.width() * 0.86f / wd
        pText.style = Paint.Style.STROKE; pText.strokeWidth = pText.textSize * 0.13f; pText.color = 0xFF000000.toInt(); c.drawText(nb, nbStart, n, r.centerX(), ay, pText)
        pText.style = Paint.Style.FILL; pText.shader = sh; c.drawText(nb, nbStart, n, r.centerX(), ay, pText); pText.shader = null
        val bought = boughtPrice > 0L
        label(c, wonLine, r.centerX(), r.top + r.height() * (if (bought) 0.655f else 0.675f), r.height() * (if (portrait) 0.052f else 0.062f), 0xFFE9E1CB.toInt(), cond, 0f, true, r.width() * 0.9f)
        if (bought) label(c, boughtLine, r.centerX(), r.top + r.height() * 0.735f, r.height() * (if (portrait) 0.04f else 0.048f), 0xFFD2CBB6.toInt(), cond, 0f, true, r.width() * 0.9f)
        label(c, noteLine, r.centerX(), r.top + r.height() * (if (bought) 0.805f else 0.765f), r.height() * (if (portrait) 0.036f else 0.044f), 0xFFA9A596.toInt(), cond, 0f, true, r.width() * 0.9f)
        label(c, "Added to your credit", r.centerX(), r.top + r.height() * (if (bought) 0.868f else 0.83f), r.height() * (if (portrait) 0.036f else 0.044f), 0xFFA9A596.toInt(), cond, 0f)
        c.restore()
        if (e > 0.8f) {
            val pa = 0.55f + 0.45f * sin(t * 2.6f)
            label(c, "TAP TO RETURN TO THE GRAVEYARD", r.centerX(), r.bottom + H * 0.075f, H * (if (portrait) 0.030f else 0.04f), mix(0xFF7A6A44.toInt(), GOLD_HOT, pa), serif, 0.14f, true, W * 0.94f)
        }
    }

    fun release() { released = true; ready = false; bakeSteps.clear(); recycleAll() }
}

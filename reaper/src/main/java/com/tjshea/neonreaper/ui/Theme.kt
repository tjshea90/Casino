package com.tjshea.neonreaper.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputFilter
import android.text.InputType
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * DREAD dialog theme: tarnished brass on iron, bone-white text, blood-red actions, a sickly green for "good" states.
 * The [Neon] palette constants are the single source of truth and are never modified; the extra tints below are
 * derived from them (muted text, highlights, a lighter blood for the top of a button bevel).
 */
internal object Dread {
    const val INK = Neon.INK
    const val IRON = Neon.IRON
    const val IRON_LIGHT = Neon.IRON_LIGHT
    const val BONE = Neon.BONE
    const val BONE_HI = 0xFFF1EAD2.toInt()       // emphasised text
    const val BONE_DIM = 0xFFA39D8B.toInt()      // secondary text (>= 6:1 on INK)
    const val BONE_FAINT = 0xFF6F6B60.toInt()    // hints, disabled
    const val BRASS = Neon.BRASS
    const val BRASS_DIM = Neon.BRASS_DIM
    const val BRASS_HI = 0xFFD4B35E.toInt()      // brass used for small text on dark
    const val RED = Neon.RED
    const val RED_DIM = Neon.RED_DIM
    const val BLOOD = 0xFFA6172C.toInt()         // lit top edge of a blood-red button
    const val ERROR = 0xFFFF7683.toInt()         // error text
    const val GREEN = Neon.GREEN
    const val GREEN_DIM = Neon.GREEN_DIM
    const val GOOD = 0xFF8FE04A.toInt()          // positive numbers (readable green)

    val SERIF: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    val CAPS: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or ((a and 0xFF) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * t).roundToInt().coerceIn(0, 255)
        return Color.argb(ch(a ushr 24, b ushr 24), ch((a shr 16) and 255, (b shr 16) and 255),
            ch((a shr 8) and 255, (b shr 8) and 255), ch(a and 255, b and 255))
    }

    enum class Kind { PRIMARY, SECONDARY, DANGER, CHIP, LINK }

    private fun g(top: Int, bottom: Int, stroke: Int, strokeDp: Float, d: Float, radiusDp: Float = 10f) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = radiusDp * d
            setStroke(max(1, (strokeDp * d).roundToInt()), stroke)
        }

    /** One drawable set per button; never share a Drawable between views. */
    fun buttonBg(kind: Kind, d: Float): StateListDrawable {
        val off = g(0xFF1A1C21.toInt(), 0xFF131519.toInt(), 0xFF2A2D34.toInt(), 1f, d)
        val set = StateListDrawable()
        set.addState(intArrayOf(-android.R.attr.state_enabled), off)
        when (kind) {
            Kind.PRIMARY -> {
                set.addState(intArrayOf(android.R.attr.state_pressed), g(0xFF7E1020.toInt(), 0xFF4D0A13.toInt(), BRASS_HI, 1.5f, d))
                set.addState(intArrayOf(android.R.attr.state_focused), g(BLOOD, RED_DIM, BONE_HI, 2f, d))
                set.addState(intArrayOf(), g(BLOOD, RED_DIM, BRASS, 1.5f, d))
            }
            Kind.DANGER -> {
                set.addState(intArrayOf(android.R.attr.state_pressed), g(0xFF5A101B.toInt(), 0xFF38080F.toInt(), RED, 1.5f, d))
                set.addState(intArrayOf(android.R.attr.state_focused), g(0xFF3E0A12.toInt(), 0xFF230509.toInt(), BONE_HI, 2f, d))
                set.addState(intArrayOf(), g(0xFF3E0A12.toInt(), 0xFF230509.toInt(), RED_DIM, 1.5f, d))
            }
            Kind.SECONDARY -> {
                set.addState(intArrayOf(android.R.attr.state_pressed), g(0xFF3A3F49.toInt(), 0xFF262A31.toInt(), BRASS, 1.5f, d))
                set.addState(intArrayOf(android.R.attr.state_focused), g(0xFF2B2F37.toInt(), 0xFF1B1E24.toInt(), BONE_HI, 2f, d))
                set.addState(intArrayOf(), g(0xFF2B2F37.toInt(), 0xFF1B1E24.toInt(), BRASS_DIM, 1.5f, d))
            }
            Kind.CHIP -> {
                set.addState(intArrayOf(android.R.attr.state_pressed), g(0xFF2B2F37.toInt(), 0xFF1B1E24.toInt(), BRASS, 1.5f, d, 9f))
                set.addState(intArrayOf(android.R.attr.state_selected), g(0xFF9A1529.toInt(), 0xFF5E0D18.toInt(), BRASS, 1.5f, d, 9f))
                set.addState(intArrayOf(android.R.attr.state_focused), g(0xFF1A1D22.toInt(), 0xFF111317.toInt(), BONE_HI, 2f, d, 9f))
                set.addState(intArrayOf(), g(0xFF1A1D22.toInt(), 0xFF111317.toInt(), IRON_LIGHT, 1f, d, 9f))
            }
            Kind.LINK -> {
                set.addState(intArrayOf(android.R.attr.state_pressed), g(0xFF2B2F37.toInt(), 0xFF1D2025.toInt(), BRASS, 1.5f, d))
                set.addState(intArrayOf(android.R.attr.state_focused), g(0xFF22252B.toInt(), 0xFF181A1F.toInt(), BONE_HI, 2f, d))
                set.addState(intArrayOf(), g(0xFF22252B.toInt(), 0xFF181A1F.toInt(), BRASS_DIM, 1f, d))
            }
        }
        return set
    }

    fun buttonText(kind: Kind): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_selected), intArrayOf()),
        intArrayOf(BONE_FAINT, BONE_HI, if (kind == Kind.CHIP) BONE else BONE_HI)
    )

    fun fieldBg(d: Float): StateListDrawable {
        fun box(stroke: Int, w: Float) = GradientDrawable().apply {
            setColor(0xFF07080A.toInt()); cornerRadius = 9 * d; setStroke(max(1, (w * d).roundToInt()), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), box(BRASS_HI, 1.5f))
            addState(intArrayOf(), box(IRON_LIGHT, 1f))
        }
    }

    fun cardBg(d: Float, highlight: Boolean = false, danger: Boolean = false): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF1D2026.toInt(), 0xFF121418.toInt())).apply {
            cornerRadius = 12 * d
            setStroke(max(1, ((if (highlight) 1.75f else 1f) * d).roundToInt()),
                when { danger -> RED_DIM; highlight -> BRASS; else -> withAlpha(IRON_LIGHT, 0xB0) })
        }
}

// ====================================================================== drawables

/** The ironwork sheet: dark plate, tarnished-brass double rule, four rivets and a faint blood glow behind the title. */
internal class IronPanel(context: Context) : Drawable() {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var plate: Shader? = null
    private var glow: Shader? = null

    override fun onBoundsChange(b: Rect) {
        plate = LinearGradient(0f, b.top.toFloat(), 0f, b.bottom.toFloat(),
            intArrayOf(0xFF20232A.toInt(), Neon.IRON, Neon.INK), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        glow = RadialGradient(b.exactCenterX(), b.top + 34 * d, max(1f, b.width() * 0.62f),
            intArrayOf(0x3AE0182F, 0x00E0182F), null, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val rad = 16 * d
        r.set(b)
        p.style = Paint.Style.FILL
        p.shader = plate; c.drawRoundRect(r, rad, rad, p)
        p.shader = glow; c.drawRoundRect(r, rad, rad, p); p.shader = null
        p.style = Paint.Style.STROKE
        r.set(b); r.inset(1.5f * d, 1.5f * d)
        p.strokeWidth = 3f * d; p.color = Neon.BRASS_DIM
        c.drawRoundRect(r, rad - 1.5f * d, rad - 1.5f * d, p)
        r.set(b); r.inset(7f * d, 7f * d)
        val ri = rad - 7f * d
        p.strokeWidth = d; p.color = Neon.BRASS
        c.drawRoundRect(r, ri, ri, p)
        val k = ri * (1f - 0.7071f)
        p.style = Paint.Style.FILL
        for (sx in 0..1) for (sy in 0..1) {
            val cx = if (sx == 0) r.left + k else r.right - k
            val cy = if (sy == 0) r.top + k else r.bottom - k
            p.color = Neon.BRASS_DIM; c.drawCircle(cx, cy, 3.6f * d, p)
            p.color = Neon.BRASS; c.drawCircle(cx, cy, 2.6f * d, p)
            p.color = Neon.INK; c.drawCircle(cx, cy, 1.1f * d, p)
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** Brass knob of the volume sliders: studded ring with a blood-red gem. */
internal class Knob(context: Context) : Drawable() {
    private val d = context.resources.displayMetrics.density
    private val size = (30 * d).roundToInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun draw(c: Canvas) {
        val b = bounds
        val cx = b.exactCenterX(); val cy = b.exactCenterY(); val r = min(b.width(), b.height()) / 2f - d
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx - r * 0.3f, cy - r * 0.35f, r * 1.3f,
            intArrayOf(Dread.BRASS_HI, Neon.BRASS, Neon.BRASS_DIM), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.color = Neon.INK; c.drawCircle(cx, cy, r * 0.58f, p)
        p.shader = RadialGradient(cx - r * 0.12f, cy - r * 0.15f, r * 0.55f,
            intArrayOf(0xFFFF5A6A.toInt(), Neon.RED, Neon.RED_DIM), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 0.46f, p)
        p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Neon.BRASS_DIM
        c.drawCircle(cx, cy, r, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

// ====================================================================== small custom views

/** A brass rule: a hairline that fades out at the ends, optionally with a diamond in the middle. */
internal class RuleView(context: Context, private val ornament: Boolean = true, private val fade: Int = FADE_BOTH) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, wSpec), ((if (ornament) 14 else 5) * d).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val cy = height / 2f
        val col = Neon.BRASS
        val clear = Dread.withAlpha(col, 0)
        p.shader = when (fade) {
            FADE_RIGHT -> LinearGradient(0f, 0f, w, 0f, intArrayOf(col, col, clear), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
            else -> LinearGradient(0f, 0f, w, 0f, intArrayOf(clear, col, col, clear), floatArrayOf(0f, 0.14f, 0.86f, 1f), Shader.TileMode.CLAMP)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, d)
        c.drawLine(0f, cy, w, cy, p)
        p.shader = null
        if (ornament) {
            val cx = w / 2f; val s = 4.5f * d
            path.reset(); path.moveTo(cx, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx, cy + s); path.lineTo(cx - s, cy); path.close()
            p.style = Paint.Style.FILL; p.color = Neon.INK; c.drawPath(path, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, d); p.color = Dread.BRASS_HI; c.drawPath(path, p)
            p.style = Paint.Style.FILL; p.color = Neon.RED; c.drawCircle(cx, cy, 1.4f * d, p)
            p.color = Neon.BRASS
            for (sx in intArrayOf(-1, 1)) c.drawCircle(cx + sx * 12f * d, cy, 1.5f * d, p)
        }
    }

    companion object { const val FADE_BOTH = 0; const val FADE_RIGHT = 1 }
}

/** Progress meter: iron trough with a brass rim and a blood-red fill. */
internal class Meter(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    var progress = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, wSpec), (22 * d).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val rad = h / 2f
        r.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        p.style = Paint.Style.FILL; p.color = 0xFF07080A.toInt(); c.drawRoundRect(r, rad, rad, p)
        val inset = 3f * d
        val fw = (w - 2 * inset) * progress
        if (fw > 0f) {
            r.set(inset, inset, inset + max(fw, h - 2 * inset), h - inset)
            val fr = (h - 2 * inset) / 2f
            p.shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(Neon.RED_DIM, Dread.BLOOD, Neon.RED), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, fr, fr, p); p.shader = null
            p.color = 0x28FFFFFF
            val gloss = RectF(r.left + fr * 0.5f, r.top + d, r.right - fr * 0.5f, r.centerY())
            c.drawRoundRect(gloss, fr / 2f, fr / 2f, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Neon.BRASS
        r.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        c.drawRoundRect(r, rad, rad, p)
    }
}

/** Round badge for the Ledger: a green tick on brass when unlocked, a padlock on iron when still locked. */
internal class Medallion(context: Context, private val unlocked: Boolean) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val s = (38 * d).roundToInt(); setMeasuredDimension(s, s)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = min(width, height) / 2f - d
        p.style = Paint.Style.FILL
        p.shader = RadialGradient(cx, cy, r, intArrayOf(if (unlocked) 0xFF1E3A12.toInt() else 0xFF16181D.toInt(), Neon.INK), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * d; p.color = if (unlocked) Neon.BRASS else Neon.IRON_LIGHT
        c.drawCircle(cx, cy, r, p)
        p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        if (unlocked) {
            p.strokeWidth = 2.8f * d; p.color = Dread.GOOD
            path.reset(); path.moveTo(cx - r * 0.38f, cy + r * 0.02f); path.lineTo(cx - r * 0.1f, cy + r * 0.32f); path.lineTo(cx + r * 0.42f, cy - r * 0.3f)
            c.drawPath(path, p)
        } else {
            p.strokeWidth = 2f * d; p.color = Dread.BONE_FAINT
            rect.set(cx - r * 0.3f, cy - r * 0.02f, cx + r * 0.3f, cy + r * 0.42f)
            p.style = Paint.Style.FILL; c.drawRoundRect(rect, 2f * d, 2f * d, p)
            p.style = Paint.Style.STROKE
            rect.set(cx - r * 0.2f, cy - r * 0.46f, cx + r * 0.2f, cy + r * 0.1f)
            c.drawArc(rect, 180f, 180f, false, p)
            c.drawLine(cx - r * 0.2f, cy - r * 0.18f, cx - r * 0.2f, cy, p)
            c.drawLine(cx + r * 0.2f, cy - r * 0.18f, cx + r * 0.2f, cy, p)
        }
    }
}

/** A red warning triangle (tilt / destructive actions). */
internal class WarnMark(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension((58 * d).roundToInt(), (52 * d).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = 3f * d
        path.reset(); path.moveTo(w / 2, s); path.lineTo(w - s, h - s); path.lineTo(s, h - s); path.close()
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xFF4A0C16.toInt(), 0xFF1E0509.toInt()), null, Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.6f * d; p.strokeJoin = Paint.Join.ROUND; p.color = Neon.RED
        c.drawPath(path, p)
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = 3.4f * d; p.color = Dread.BONE_HI
        c.drawLine(w / 2, h * 0.38f, w / 2, h * 0.64f, p)
        p.style = Paint.Style.FILL; c.drawCircle(w / 2, h * 0.79f, 2.2f * d, p)
    }
}

/** n small brass diamonds: the header of a pay column ("5 in a row" = five diamonds). */
internal class Pips(context: Context, private val n: Int) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Dread.BRASS_HI }
    private val path = Path()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(getDefaultSize((n * 9 * d).roundToInt(), wSpec), (12 * d).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val s = 3.2f * d; val step = 8.5f * d
        val x0 = width / 2f - (n - 1) * step / 2f; val cy = height / 2f
        for (i in 0 until n) {
            val cx = x0 + i * step
            path.reset(); path.moveTo(cx, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx, cy + s); path.lineTo(cx - s, cy); path.close()
            c.drawPath(path, p)
        }
    }
}

// ====================================================================== layouts

/**
 * Lays its children out as a tidy grid of equal-width cells (chips): as many columns as fit, every cell stretched so each
 * row ends flush. Rows have equal height. Wraps instead of clipping on narrow screens.
 */
internal class ChipGrid(context: Context, private val gap: Int) : ViewGroup(context) {
    private var cols = 1
    private var cellW = 0
    private var rowH = IntArray(0)
    val chips = ArrayList<Button>()
    private var selected = -1
    var onSelect: (Int) -> Unit = {}

    fun select(i: Int) {
        selected = i
        chips.forEachIndexed { k, b ->
            b.isSelected = k == i
            if (Build.VERSION.SDK_INT >= 30) b.stateDescription = if (k == i) "selected" else "not selected"
        }
    }

    val selectedIndex: Int get() = selected

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = (MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        var cell = 0
        var n = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), free)
            cell = max(cell, c.measuredWidth); n++
        }
        cols = if (n == 0) 1 else max(1, min(n, (maxW + gap) / max(1, cell + gap)))
        if (n > 0) cols = (n + ((n + cols - 1) / cols) - 1) / ((n + cols - 1) / cols)   // balance the rows (3+3 rather than 5+1)
        cellW = (maxW - (cols - 1) * gap) / cols
        val rows = if (n == 0) 0 else (n + cols - 1) / cols
        rowH = IntArray(rows)
        var k = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY), free)
            rowH[k / cols] = max(rowH[k / cols], c.measuredHeight); k++
        }
        var h = paddingTop + paddingBottom + max(0, rows - 1) * gap
        for (r in rowH) h += r
        setMeasuredDimension(maxW + paddingLeft + paddingRight, resolveSize(h, hSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var k = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            val row = k / cols; val col = k % cols
            var y = paddingTop
            for (j in 0 until row) y += rowH[j] + gap
            val x = paddingLeft + col * (cellW + gap)
            c.measure(MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowH[row], MeasureSpec.EXACTLY))
            c.layout(x, y, x + cellW, y + rowH[row]); k++
        }
    }
}

/** Side by side when wide enough ([thresholdPx]), stacked otherwise. Children are given equal width in the wide layout. */
internal class Columns(context: Context, private val thresholdPx: Int, private val gap: Int, private val equalHeight: Boolean = false) : LinearLayout(context) {
    private var wide: Boolean? = null

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val isWide = MeasureSpec.getSize(wSpec) >= thresholdPx
        if (wide != isWide) {
            wide = isWide
            orientation = if (isWide) HORIZONTAL else VERTICAL
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val lp = LayoutParams(if (isWide) 0 else ViewGroup.LayoutParams.MATCH_PARENT,
                    if (isWide && equalHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT, if (isWide) 1f else 0f)
                if (isWide) lp.setMargins(if (i > 0) gap / 2 else 0, 0, if (i < childCount - 1) gap / 2 else 0, 0)
                else lp.setMargins(0, if (i > 0) gap else 0, 0, 0)
                c.layoutParams = lp
            }
        }
        super.onMeasure(wSpec, hSpec)
    }
}

/**
 * Horizontal row of equal buttons when they all fit, a vertical stack when they do not (phones, big fonts).
 */
internal class ButtonBar(context: Context, private val gap: Int) : LinearLayout(context) {
    private var horizontal: Boolean? = null

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val avail = MeasureSpec.getSize(wSpec)
        var widest = 0; var n = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i); if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            widest = max(widest, c.measuredWidth); n++
        }
        val fits = n <= 1 || widest * n + gap * (n - 1) <= avail
        if (horizontal != fits) {
            horizontal = fits
            orientation = if (fits) HORIZONTAL else VERTICAL
            var k = 0
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                val lp = LayoutParams(if (fits) 0 else ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, if (fits) 1f else 0f)
                if (c.visibility != GONE) {
                    if (fits) lp.setMargins(if (k > 0) gap / 2 else 0, 0, if (k < n - 1) gap / 2 else 0, 0) else lp.setMargins(0, if (k > 0) gap else 0, 0, 0)
                    k++
                }
                c.layoutParams = lp
            }
        }
        super.onMeasure(wSpec, hSpec)
    }
}

/**
 * The dialog sheet: a fixed width (never wider than the screen minus a margin) and a height that wraps its content up to the
 * screen height minus a margin. Together with a weighted ScrollView body this keeps the title and the footer buttons on
 * screen while the middle scrolls. It re-measures on its own after a rotation or an IME resize.
 */
internal class SheetLayout(context: Context, private val maxWidth: Int, private val margin: Int) : LinearLayout(context) {
    init { orientation = VERTICAL }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val availW = if (MeasureSpec.getMode(wSpec) == MeasureSpec.UNSPECIFIED) maxWidth + 2 * margin else MeasureSpec.getSize(wSpec)
        val availH = if (MeasureSpec.getMode(hSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE / 2 else MeasureSpec.getSize(hSpec)
        val w = min(maxWidth, availW - 2 * margin).coerceAtLeast(0)
        val h = (availH - 2 * margin).coerceAtLeast(0)
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.AT_MOST))
    }
}

// ====================================================================== the builder

/** Small view factory shared by every dialog, so spacing, type and colour stay consistent. */
internal class Ui(val ctx: Context) {
    val d: Float = ctx.resources.displayMetrics.density
    fun dp(v: Number): Int = (v.toFloat() * d).roundToInt()

    val screenWdp: Int get() = ctx.resources.configuration.screenWidthDp
    val screenHdp: Int get() = ctx.resources.configuration.screenHeightDp
    /** Landscape phones and portrait phones: tighter paddings. */
    val tight: Boolean get() = screenHdp < 480 || screenWdp < 480

    fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT, weight: Float = 0f,
           l: Number = 0, t: Number = 0, r: Number = 0, b: Number = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(dp(l), dp(t), dp(r), dp(b)) }

    fun vbox(vararg views: View, gap: Number = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        views.forEachIndexed { i, v -> addView(v, lp(t = if (i > 0) gap else 0)) }
    }

    fun hbox(gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; this.gravity = gravity
    }

    fun space(h: Number): View = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, dp(h)) }

    // ---- text
    fun text(s: CharSequence, sp: Float = 15f, color: Int = Dread.BONE, bold: Boolean = false, center: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            if (center) gravity = Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.14f)
        }

    /** Small brass capitals (labels, section captions). */
    fun caps(s: CharSequence, sp: Float = 12f, color: Int = Dread.BRASS_HI, center: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            typeface = Dread.CAPS; isAllCaps = true; letterSpacing = 0.12f
            if (center) gravity = Gravity.CENTER_HORIZONTAL
        }

    /** Serif-bold heading with a faint red glow. */
    fun heading(s: CharSequence, sp: Float = 24f, center: Boolean = true): TextView =
        TextView(ctx).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(Dread.BONE_HI)
            typeface = Dread.SERIF
            if (center) gravity = Gravity.CENTER_HORIZONTAL
            setShadowLayer(dp(10).toFloat(), 0f, 0f, 0xB0E0182F.toInt())
            setLineSpacing(0f, 1.05f)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }

    /** Large figure that shrinks instead of clipping. */
    fun figure(s: CharSequence, maxSp: Float = 28f, color: Int = Dread.BONE_HI, center: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s; setTextColor(color); typeface = Dread.SERIF; maxLines = 1
            if (center) gravity = Gravity.CENTER_HORIZONTAL
            setAutoSizeTextTypeUniformWithConfiguration(11, maxSp.toInt(), 1, TypedValue.COMPLEX_UNIT_SP)
        }

    /** "**bold**" segments become bright, bold text. */
    fun rich(s: String, hi: Int = Dread.BONE_HI): CharSequence {
        val out = SpannableStringBuilder()
        val parts = s.split("**")
        parts.forEachIndexed { i, part ->
            val start = out.length
            out.append(part)
            if (i % 2 == 1) {
                out.setSpan(ForegroundColorSpan(hi), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.setSpan(StyleSpan(Typeface.BOLD), start, out.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    /** A bullet line: brass diamond marker + rich text. */
    fun bullet(s: String, sp: Float = 14.5f, color: Int = Dread.BONE): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(Diamond(ctx), LinearLayout.LayoutParams(dp(14), dp(20)).apply { topMargin = dp(1) })
        addView(text(rich(s), sp, color), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 4))
    }

    /** Section caption with a rule that fades to the right. */
    fun section(s: CharSequence): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(caps(s, 12.5f).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(RuleView(ctx, ornament = false, fade = RuleView.FADE_RIGHT), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 10))
    }

    fun rule(): View = RuleView(ctx, ornament = true)

    fun icon(bmp: android.graphics.Bitmap, sizeDp: Int, desc: String? = null): ImageView = ImageView(ctx).apply {
        setImageBitmap(bmp); scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = desc
        if (desc == null && Build.VERSION.SDK_INT >= 16) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    // ---- containers
    fun card(highlight: Boolean = false, danger: Boolean = false, pad: Number = if (tight) 12 else 14): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = Dread.cardBg(d, highlight, danger)
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
    }

    /** Label (left) and value (right) on one line. */
    fun kv(label: CharSequence, value: CharSequence, valueColor: Int = Dread.BONE_HI): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(7), 0, dp(7))
        addView(text(label, 14f, Dread.BONE_DIM), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(text(value, 15f, valueColor, bold = true).apply { gravity = Gravity.END }, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, l = 10))
    }

    /** A label over a big figure, in a card. */
    fun stat(label: CharSequence, value: CharSequence, sub: CharSequence? = null, valueColor: Int = Dread.BONE_HI, maxSp: Float = 26f): LinearLayout =
        card(pad = 12).apply {
            addView(caps(label, 11f, Dread.BRASS_HI), lp())
            addView(figure(value, maxSp, valueColor), lp(t = 4))
            if (sub != null) addView(text(sub, 12f, Dread.BONE_DIM), lp(t = 2))
        }

    // ---- controls
    fun button(label: CharSequence, kind: Dread.Kind = Dread.Kind.PRIMARY, small: Boolean = false, onClick: (View) -> Unit): Button =
        Button(ctx).apply {
            text = label; isAllCaps = true; gravity = Gravity.CENTER
            typeface = Dread.CAPS; letterSpacing = 0.06f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 13.5f else 15f)
            setTextColor(Dread.buttonText(kind))
            background = Dread.buttonBg(kind, d)
            stateListAnimator = null
            minWidth = 0; minimumWidth = 0
            val mh = dp(if (small) 44 else 52)
            minHeight = mh; minimumHeight = mh
            setPadding(dp(14), dp(6), dp(14), dp(6))
            setOnClickListener(onClick)
        }

    /** Segmented choice: one chip per label, the selected one lit blood-red. */
    fun choice(labels: List<String>, selected: Int, desc: String? = null, onSelect: (Int) -> Unit): ChipGrid =
        ChipGrid(ctx, dp(8)).apply {
            labels.forEachIndexed { i, label ->
                val b = Button(ctx).apply {
                    text = label; isAllCaps = false; gravity = Gravity.CENTER
                    typeface = Dread.MEDIUM
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                    setTextColor(Dread.buttonText(Dread.Kind.CHIP))
                    background = Dread.buttonBg(Dread.Kind.CHIP, d)
                    stateListAnimator = null
                    minWidth = 0; minimumWidth = 0; minHeight = dp(48); minimumHeight = dp(48)
                    setPadding(dp(10), dp(6), dp(10), dp(6))
                    maxLines = 2
                    if (desc != null) contentDescription = "$desc: $label"
                    setOnClickListener { select(i); onSelect(i) }
                }
                chips += b
                addView(b)
            }
            select(selected)
        }

    fun field(hint: String, password: Boolean = false, number: Boolean = false): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(Dread.BONE_FAINT)
        setTextColor(Dread.BONE_HI); setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        isSingleLine = true
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        typeface = Typeface.SANS_SERIF   // (password inputs would otherwise switch to monospace)
        if (number) filters = arrayOf(InputFilter.LengthFilter(7))
        if (password) filters = arrayOf(InputFilter.LengthFilter(64))
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        background = Dread.fieldBg(d)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        minimumHeight = dp(52)
        highlightColor = 0x66E0182F
        try {
            textCursorDrawable = GradientDrawable().apply { setColor(Dread.BONE_HI); setSize(dp(2), dp(22)) }
        } catch (_: Throwable) { /* cosmetic only */ }
        if (number) gravity = Gravity.CENTER_VERTICAL or Gravity.END
    }

    /** Caption over a view (e.g. a text field). */
    fun labeled(label: CharSequence, view: View): LinearLayout = vbox(caps(label, 11f).apply { setPadding(dp(2), 0, 0, dp(4)) }, view)

    /** Volume-style slider on a brass-rimmed blood-red track. */
    fun slider(max: Int, value: Int, desc: String, onChange: (Int, Boolean) -> Unit, onStop: () -> Unit): SeekBar = SeekBar(ctx).apply {
        val bg = GradientDrawable().apply { setColor(0xFF07080A.toInt()); cornerRadius = dp(5).toFloat(); setStroke(max(1, dp(1)), Neon.BRASS_DIM) }
        val fill = ClipDrawable(GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Neon.RED_DIM, Neon.RED)).apply { cornerRadius = dp(5).toFloat() },
            Gravity.START, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf<Drawable>(bg, fill)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
            for (i in 0..1) { setLayerHeight(i, dp(10)); setLayerGravity(i, Gravity.CENTER_VERTICAL) }
        }
        val knob = Knob(ctx)
        thumb = knob
        thumbOffset = 0
        splitTrack = false
        val half = knob.intrinsicWidth / 2
        setPadding(half, dp(9), half, dp(9))
        this.max = max; progress = value
        contentDescription = desc
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = onChange(p, fromUser)
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) = onStop()
        })
    }

    fun scroll(): ScrollView = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength(dp(22))
        scrollBarStyle = View.SCROLLBARS_OUTSIDE_INSET
        scrollBarSize = dp(3)
        try { verticalScrollbarThumbDrawable = GradientDrawable().apply { setColor(Dread.withAlpha(Neon.BRASS, 0xCC)); cornerRadius = dp(2).toFloat() } } catch (_: Throwable) {}
    }
}

/** Small brass diamond used as a list marker. */
internal class Diamond(context: Context) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Neon.BRASS }
    private val path = Path()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension((14 * d).roundToInt(), (20 * d).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height * 0.55f; val s = 3.2f * d
        path.reset(); path.moveTo(cx, cy - s); path.lineTo(cx + s, cy); path.lineTo(cx, cy + s); path.lineTo(cx - s, cy); path.close()
        c.drawPath(path, p)
    }
}

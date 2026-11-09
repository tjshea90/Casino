package com.tjshea.neonreaper.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.Money
import com.tjshea.neonreaper.engine.ReaperConfig
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How-to-play content for the Info dialog: the pay table at the current stake, the features and the rules.
 * Every figure comes from [ReaperConfig] / [Wallet]; nothing here is typed in by hand except wording.
 */
internal class Guide(private val ui: Ui, private val wallet: Wallet) {
    private val ctx: Context = ui.ctx
    private fun fmt(n: Long) = Money.fmt(n)
    private val maxBooks = ReaperConfig.MAX_WIN_TOKENS / Money.TOKENS_PER_BOOK
    private val rtpPct = ReaperConfig.TARGET_RTP * 100
    private val edgePct = 100 - rtpPct
    /** Bought features return slightly less than the 94 %: tools/reaper_model.py prints Séance 92.8 %, Vault 91.5 % at stakes 1-5. */
    private val buyRtpText = "about 92 %"

    private fun hair(): View = View(ctx).apply {
        setBackgroundColor(Dread.withAlpha(Dread.IRON_LIGHT, 0x90))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, max(1, ui.dp(1)))
    }

    private fun joinOr(items: List<String>): String =
        if (items.size <= 1) items.joinToString("") else items.dropLast(1).joinToString(", ") + " or " + items.last()

    // ================================================================== PAYS
    fun pays(stake: Int): View {
        val page = ui.vbox()
        page.addView(ui.text(ui.rich(
            "**${ReaperConfig.PAYLINES.size} paylines**, paid left to right from reel 1; wins on different lines add up. " +
                "Tokens at your stake of **$stake** (grey: \u00D7 your stake). Maximum win **$maxBooks books (${fmt(ReaperConfig.MAX_WIN_TOKENS)} tokens)**."),
            14f, Dread.BONE_DIM), ui.lp(b = 12))

        val cols = Columns(ctx, ui.dp(640), ui.dp(20))
        val left = ui.vbox(); val right = ui.vbox()
        left.addView(payHeader(), ui.lp()); right.addView(payHeader(), ui.lp())
        for (s in 0..5) { left.addView(symbolRow(s, stake), ui.lp()); left.addView(hair(), ui.lp()) }
        for (s in 6 until ReaperConfig.SYMBOL_COUNT) { right.addView(symbolRow(s, stake), ui.lp()); right.addView(hair(), ui.lp()) }
        cols.addView(left); cols.addView(right)
        page.addView(cols, ui.lp())

        page.addView(ui.section("The ${ReaperConfig.PAYLINES.size} paylines"), ui.lp(t = 18, b = 8))
        page.addView(PaylineAtlas(ctx, ReaperConfig.PAYLINES), ui.lp())
        page.addView(ui.text("A line pays when 3 or more matching symbols (or wilds) stand side by side from the first reel. Only the best win on a line counts.",
            13f, Dread.BONE_DIM), ui.lp(t = 8))
        return page
    }

    private fun payHeader(): View = ui.hbox().apply {
        setPadding(0, 0, 0, ui.dp(6))
        addView(ui.caps("Symbol", 10.5f, Dread.BONE_DIM), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for (n in 5 downTo 3) addView(Pips(ctx, n), LinearLayout.LayoutParams(ui.dp(58), ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun symbolRow(sym: Int, stake: Int): View {
        val raw = ReaperConfig.SYMBOL_NAMES[sym]
        val title = raw.removeSuffix(" Wild").removeSuffix(" Scatter")
        val tag = when (sym) {
            ReaperConfig.WILD -> "Wild · substitutes"
            ReaperConfig.SCATTER -> "Scatter · pays anywhere"
            else -> null
        }
        val mult = if (sym == ReaperConfig.SCATTER) intArrayOf(ReaperConfig.SCATTER_PAY[3], ReaperConfig.SCATTER_PAY[4], ReaperConfig.SCATTER_PAY[5])
        else ReaperConfig.PAYS[sym]
        return ui.hbox().apply {
            setPadding(0, ui.dp(6), 0, ui.dp(6))
            addView(ui.icon(Art.symbol(sym, ui.dp(46)), 46, title))
            val names = ui.vbox(ui.text(title, 14.5f, Dread.BONE_HI, bold = true))
            if (tag != null) names.addView(ui.caps(tag, 10f, Dread.BRASS_HI), ui.lp(t = 1))
            addView(names, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 10))
            for (i in 2 downTo 0) addView(payCell(mult[i], stake))
        }
    }

    private fun payCell(m: Int, stake: Int): View {
        val tokens = m.toLong() * stake
        val capped = tokens > ReaperConfig.MAX_WIN_TOKENS
        return ui.vbox(
            ui.text(fmt(min(tokens, ReaperConfig.MAX_WIN_TOKENS)), 14.5f, if (capped) Dread.BRASS_HI else Dread.BONE_HI, bold = true, center = true),
            ui.text(if (capped) "MAX" else "×$m", 11f, Dread.BONE_DIM, center = true)
        ).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(58), ViewGroup.LayoutParams.WRAP_CONTENT) }
    }

    // ================================================================== FEATURES
    fun features(stake: Int): View {
        // two balanced columns on a wide sheet, this exact order in a single column
        val page = Masonry(ctx, ui.dp(640), ui.dp(14))
        page.addView(wildCard()); page.addView(seanceCard()); page.addView(miniCard()); page.addView(vaultCard()); page.addView(buyCard(stake))
        return page
    }

    private fun cardHeader(title: String, tag: String?, icon: android.graphics.Bitmap?, sizeDp: Int = 44): View = ui.hbox().apply {
        if (icon != null) addView(ui.icon(icon, sizeDp, title))
        val t = ui.vbox(ui.heading(title, 17f, center = false).apply { setShadowLayer(0f, 0f, 0f, 0) })
        if (tag != null) t.addView(ui.caps(tag, 11f), ui.lp(t = 2))
        addView(t, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = if (icon != null) 10 else 0))
    }

    private fun wildCard(): View = ui.card().apply {
        addView(cardHeader("The Grim Reaper", "Wild symbol", Art.symbol(ReaperConfig.WILD, ui.dp(48)), 48), ui.lp(b = 8))
        addView(ui.bullet("Substitutes for every symbol except the **Coffin**."), ui.lp(t = 4))
        addView(ui.bullet("${ReaperConfig.REELS} Reapers on a payline pay **${ReaperConfig.PAYS[ReaperConfig.WILD][ReaperConfig.REELS - 3]}× your stake**."), ui.lp(t = 6))
        addView(ui.bullet("His touch can turn whole reels wild, see the mini features below."), ui.lp(t = 6))
    }

    private fun miniCard(): View {
        val card = ui.card()
        val every = (1.0 / ReaperConfig.FEATURE_RATE).roundToInt()
        card.addView(cardHeader("Mini features", "About 1 spin in $every", null), ui.lp(b = 4))
        card.addView(ui.text(ui.rich("A creature crawls out of the dark and changes **one spin**. The chance is the same at every stake."), 14f, Dread.BONE_DIM), ui.lp(b = 6))
        val patterns = ReaperConfig.FEATURE_PATTERNS
        val firstReel = patterns.minOf { it.min() } + 1; val lastReel = patterns.maxOf { it.max() } + 1
        val coinTxt = "**${ReaperConfig.COIN_COUNTS.min()} to ${ReaperConfig.COIN_COUNTS.max()}** soul coins land on the reels; each pays **${ReaperConfig.COIN_VALUES.min()}× to ${ReaperConfig.COIN_VALUES.max()}×** your stake on top of the line wins."
        val rows = listOf(
            Triple(ReaperConfig.WILD, ReaperConfig.MINI_REAPER,
                "**${patterns.minOf { it.size }} to ${patterns.maxOf { it.size }}** of the middle reels (reels $firstReel–$lastReel) turn completely **WILD**."),
            Triple(ReaperConfig.RAVEN, ReaperConfig.MINI_RAVEN,
                "**${ReaperConfig.RAVEN_WILDS.min()} to ${ReaperConfig.RAVEN_WILDS.max()}** random symbols are torn away and become **WILD**."),
            Triple(ReaperConfig.DEMON, ReaperConfig.MINI_DEMON, coinTxt),
            Triple(ReaperConfig.SKULL, ReaperConfig.MINI_SKULL,
                "**${ReaperConfig.SKULL_WILDS} WILDS** appear and every line win of the spin is multiplied by **${joinOr(ReaperConfig.SKULL_MULTS.map { "×$it" })}**.")
        )
        for ((sym, type, txt) in rows) {
            card.addView(hair(), ui.lp(t = 6))
            card.addView(ui.hbox(Gravity.TOP).apply {
                setPadding(0, ui.dp(8), 0, ui.dp(2))
                addView(ui.icon(Art.symbol(sym, ui.dp(44)), 44, ReaperConfig.MINI_NAMES[type]))
                addView(ui.vbox(ui.text(ReaperConfig.MINI_NAMES[type], 14f, Dread.BRASS_HI, bold = true).apply { typeface = Dread.CAPS; letterSpacing = 0.06f },
                    ui.text(ui.rich(txt), 14f, Dread.BONE)), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 10))
            }, ui.lp())
        }
        return card
    }

    private fun seanceCard(): View = ui.card(highlight = true).apply {
        val sp = ReaperConfig.SCATTER_PAY; val fs = ReaperConfig.FS_SPINS; val ex = ReaperConfig.FS_EXTRA
        addView(cardHeader("Coffin scatter · The Séance", "3, 4 or 5 coffins anywhere", Art.symbol(ReaperConfig.SCATTER, ui.dp(52)), 52), ui.lp(b = 8))
        addView(ui.bullet("They pay **${sp[3]}×, ${sp[4]}× or ${sp[5]}×** your stake, wherever they land."), ui.lp(t = 4))
        addView(ui.bullet("They also awaken **THE SÉANCE**: **${fs[3]}, ${fs[4]} or ${fs[5]} free spins** on richer reels with more wilds and characters."), ui.lp(t = 6))
        addView(ui.bullet("The multiplier climbs with every free spin, up to **×${ReaperConfig.FS_RAMP_CAP}**:"), ui.lp(t = 6))
        val ramp = ChipGrid(ctx, ui.dp(5))
        for (m in 1..ReaperConfig.FS_RAMP_CAP) ramp.addView(rampChip("×$m", m == ReaperConfig.FS_RAMP_CAP))
        addView(ramp, ui.lp(t = 6, l = 18))
        addView(ui.bullet("More coffins inside the Séance add **+${ex[3]}, +${ex[4]} or +${ex[5]} spins** (3, 4 or 5 coffins) and the climb carries on."), ui.lp(t = 8))
        addView(ui.bullet("It pays in proportion to the stake that started it. Expect one about every **${ReaperConfig.SEANCE_EVERY_TEXT}** spins."), ui.lp(t = 6))
    }

    private fun rampChip(label: String, top: Boolean): TextView = ui.text(label, 12.5f, if (top) Dread.BONE_HI else Dread.BONE, bold = true, center = true).apply {
        background = Dread.cardBg(ui.d, highlight = top)
        setPadding(0, ui.dp(5), 0, ui.dp(5))
    }

    private fun vaultCard(): View = ui.card().apply {
        val relics = wallet.relics
        addView(cardHeader("The Soul Vault", "Collect ${ReaperConfig.RELIC_COUNT} relics", null), ui.lp(b = 8))
        addView(ui.hbox().apply {
            for (i in 0 until ReaperConfig.RELIC_COUNT) addView(ui.icon(Art.relic(i, ui.dp(46), i < relics), 46, ReaperConfig.RELIC_NAMES[i]),
                LinearLayout.LayoutParams(0, ui.dp(46), 1f))
        }, ui.lp())
        addView(ui.text("You hold $relics of ${ReaperConfig.RELIC_COUNT} relics", 12.5f, Dread.BRASS_HI, center = true).apply { typeface = Dread.CAPS; letterSpacing = 0.08f }, ui.lp(t = 4, b = 8))
        addView(ui.bullet("About **1 spin in ${(1.0 / ReaperConfig.RELIC_CHANCE).roundToInt()}** reveals the next relic: ${ReaperConfig.RELIC_NAMES.joinToString(", ")}."), ui.lp(t = 4))
        addView(ui.bullet("All ${ReaperConfig.RELIC_COUNT} open the **SOUL VAULT**, about once in **${ReaperConfig.VAULT_EVERY_TEXT}** spins. Relics are kept between sessions."), ui.lp(t = 6))
        addView(ui.bullet("**Hold & Spin:** ${ReaperConfig.VAULT_START_ORBS} soul orbs start on a ${ReaperConfig.VAULT_COLS}×${ReaperConfig.VAULT_ROWS} board and you get **${ReaperConfig.VAULT_RESPINS} respins**, one SPIN each. Every orb that lands sticks and resets the respins."), ui.lp(t = 6))
        addView(ui.bullet("Soul orbs pay **${ReaperConfig.ORB_VALUES.min()}× to ${ReaperConfig.ORB_VALUES.max()}×** the bonus bet. Jackpot orbs: **MINI ${ReaperConfig.MINI}×, MINOR ${ReaperConfig.MINOR}×, MAJOR ${ReaperConfig.MAJOR}×**. The **Reaper orb** harvests every soul on the board."), ui.lp(t = 6))
        addView(ui.bullet("Fill all **${ReaperConfig.VAULT_COLS * ReaperConfig.VAULT_ROWS} niches** for the **GRAND ${ReaperConfig.GRAND}×**."), ui.lp(t = 6))
        addView(ui.bullet("**Bonus bet** = the average stake of the ${ReaperConfig.RELIC_COUNT} spins that revealed your relics, rounded down. Now: **${wallet.projectedBonusBet} tokens** if the missing relics arrive at your current stake."), ui.lp(t = 6))
    }

    private fun buyCard(stake: Int): View = ui.card().apply {
        val s = wallet.buyPrice(com.tjshea.neonreaper.data.Feature.SEANCE, stake)
        val v = wallet.buyPrice(com.tjshea.neonreaper.data.Feature.VAULT, stake)
        addView(cardHeader("Feature buy", "Skip the wait, for a price", null), ui.lp(b = 6))
        addView(ui.bullet("**THE SÉANCE** costs **${ReaperConfig.BUY_SEANCE_X}× your stake** (${fmt(s)} tokens at stake $stake) and starts with ${ReaperConfig.FS_SPINS[3]} free spins."), ui.lp(t = 4))
        addView(ui.bullet("**THE SOUL VAULT** costs **${ReaperConfig.BUY_VAULT_X}× your stake** (${fmt(v)} tokens) and uses your stake as the bonus bet."), ui.lp(t = 6))
        addView(ui.bullet("Both are priced above their average win: a bought feature returns **$buyRtpText** on average, slightly less than the ${"%.0f".format(rtpPct)} % of normal play."), ui.lp(t = 6))
        addView(ui.bullet("Every buy needs a second tap to confirm, and the price counts as tokens wagered."), ui.lp(t = 6))
    }

    // ================================================================== RULES
    fun rules(stake: Int): View {
        val page = ui.vbox()
        val stakeRtp = ReaperConfig.RTP_BY_STAKE[ReaperConfig.stakeIndex(stake)] * 100
        val hero = Columns(ctx, ui.dp(520), ui.dp(16)).apply {
            addView(ui.vbox(ui.figure("${"%.0f".format(rtpPct)} %", 46f, Dread.BONE_HI, center = true).apply {
                setShadowLayer(ui.dp(10).toFloat(), 0f, 0f, 0xB0E0182F.toInt())
            }, ui.caps("Return to player", 12f, Dread.BRASS_HI, center = true)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(8), ui.dp(4), ui.dp(8), ui.dp(4))
            })
            addView(ui.vbox(
                ui.text(ui.rich("**House edge ${"%.0f".format(edgePct)} %.** Over a very long run the machine pays back ${"%.0f".format(rtpPct)} tokens of every 100 wagered and keeps ${"%.0f".format(edgePct)}."), 15f),
                ui.text("In a single session anything can happen: you can win a lot, or lose everything you deposit.", 14f, Dread.BONE_DIM).apply { setPadding(0, ui.dp(6), 0, 0) }
            ))
        }
        page.addView(ui.card(highlight = true).apply { addView(hero, ui.lp()) }, ui.lp())

        page.addView(ui.section("Fair play"), ui.lp(t = 16, b = 8))
        page.addView(ui.bullet("At your stake of **$stake** the theoretical return is **${"%.1f".format(stakeRtp)} %**; the ${maxBooks}-book win cap trims it a little at the highest stakes."), ui.lp(t = 4))
        page.addView(ui.bullet("Plain spins win **${ReaperConfig.HIT_FREQUENCY_TEXT}** spins and every win pays at least your stake back."), ui.lp(t = 6))
        page.addView(ui.bullet("Every spin is **independent**. A secure random number is drawn the instant you press SPIN and the animation only shows it. Nothing is \"due\", and no machine is hot or cold."), ui.lp(t = 6))
        page.addView(ui.bullet("However you vary your stakes, the machine keeps at least **${"%.0f".format(edgePct)} %** of every token wagered over time."), ui.lp(t = 6))
        page.addView(ui.bullet("Bought features return slightly less (**$buyRtpText**)."), ui.lp(t = 6))

        page.addView(ui.section("Money"), ui.lp(t = 16, b = 8))
        page.addView(ui.bullet("**${Money.TOKENS_PER_MACK} tokens = 1 mack, ${Money.MACKS_PER_BOOK} macks = 1 book** (${Money.TOKENS_PER_BOOK} tokens). Stakes run from **${ReaperConfig.STAKES.first()} to ${ReaperConfig.STAKES.last()} tokens** a spin."), ui.lp(t = 4))
        page.addView(ui.bullet("Wins are paid in exact tokens. Cash-outs are whole macks; odd tokens stay on your credit."), ui.lp(t = 6))
        page.addView(ui.bullet("Maximum win: **$maxBooks books (${fmt(ReaperConfig.MAX_WIN_TOKENS)} tokens)** per spin, per Séance and per Soul Vault."), ui.lp(t = 6))

        page.addView(ui.section("Controls"), ui.lp(t = 16, b = 8))
        page.addView(ui.bullet("Tap **SPIN**. Hold it for rapid spins. Tap the reels to stop them early."), ui.lp(t = 4))
        page.addView(ui.bullet("**AUTO** plays a number of spins and can stop on a big win or when your credit drops by a set share. It always pauses for the Séance and the Soul Vault."), ui.lp(t = 6))
        page.addView(ui.bullet("**BUY** opens the feature shop; **MENU** holds settings, rank and statistics; **INFO** brings you back here."), ui.lp(t = 6))

        page.addView(ui.section("Play responsibly"), ui.lp(t = 16, b = 8))
        val mins = wallet.realityMinutes
        page.addView(ui.bullet("Tokens are for play. Treat what you deposit as the price of an evening's entertainment, never as income. Set a budget before you start and stop when it is spent."), ui.lp(t = 4))
        page.addView(ui.bullet("Take regular breaks and never chase losses. A **reality check** shows your time played and net result ${if (mins > 0) "every **$mins minutes**" else "(currently **off**)"}; change it in MENU."), ui.lp(t = 6))
        page.addView(ui.bullet("If gambling stops being fun, stop. Talk to someone you trust or contact a local problem-gambling support service."), ui.lp(t = 6))
        return page
    }
}

/** The paylines as small 5x3 diagrams: the cells the line crosses are lit and joined. One view draws all of them. */
internal class PaylineAtlas(context: Context, private val lines: Array<IntArray>) : View(context) {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val path = Path()
    private var cols = 4
    private var tileW = 0f
    private var tileH = 0f
    private val labelW = 17f * d
    private val pad = 5f * d

    init { p.typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD); contentDescription = "The ${lines.size} paylines" }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val avail = MeasureSpec.getSize(wSpec)
        cols = (avail / (96f * d)).toInt().coerceIn(3, 7)
        tileW = avail.toFloat() / cols
        val cell = (tileW - 2 * pad - labelW - 2 * d) / 5f
        tileH = cell * 3 + 2 * pad + 2 * d
        val rows = ceil(lines.size / cols.toFloat()).toInt()
        setMeasuredDimension(avail, ceil(rows * tileH).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cell = (tileW - 2 * pad - labelW - 2 * d) / 5f
        for (i in lines.indices) {
            val ox = (i % cols) * tileW; val oy = (i / cols) * tileH
            r.set(ox + d, oy + d, ox + tileW - d, oy + tileH - d)
            p.style = Paint.Style.FILL; p.color = 0xFF121418.toInt(); c.drawRoundRect(r, 6 * d, 6 * d, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = d; p.color = Dread.withAlpha(Dread.IRON_LIGHT, 0xA0); c.drawRoundRect(r, 6 * d, 6 * d, p)
            p.style = Paint.Style.FILL; p.color = Dread.BRASS_HI; p.textSize = 11f * d; p.textAlign = Paint.Align.CENTER
            c.drawText("${i + 1}", ox + pad + labelW / 2f, oy + tileH / 2f + 4f * d, p)
            val gx = ox + pad + labelW + d; val gy = oy + pad + d
            for (col in 0 until 5) for (row in 0 until 3) {
                val on = lines[i][col] == row
                r.set(gx + col * cell + 1.2f * d, gy + row * cell + 1.2f * d, gx + (col + 1) * cell - 1.2f * d, gy + (row + 1) * cell - 1.2f * d)
                p.style = Paint.Style.FILL; p.color = if (on) 0xFF8F1426.toInt() else 0xFF22252B.toInt()
                c.drawRoundRect(r, 2f * d, 2f * d, p)
            }
            path.reset()
            for (col in 0 until 5) {
                val x = gx + (col + 0.5f) * cell; val y = gy + (lines[i][col] + 0.5f) * cell
                if (col == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.7f * d; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
            p.color = Dread.BONE_HI
            c.drawPath(path, p)
        }
    }
}

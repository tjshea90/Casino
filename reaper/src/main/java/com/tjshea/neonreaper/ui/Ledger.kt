package com.tjshea.neonreaper.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tjshea.neonreaper.data.Achievements
import com.tjshea.neonreaper.data.Ranks
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.Money
import com.tjshea.neonreaper.engine.ReaperConfig
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs

/** Statistics, rank and ledger content (the Stats and Rank dialogs). Reads only what [Wallet] exposes. */
internal class Ledger(private val ui: Ui, private val wallet: Wallet) {
    private val ctx: Context = ui.ctx
    private fun fmt(n: Long) = Money.fmt(n)
    private fun signed(n: Long) = (if (n > 0) "+" else if (n < 0) "-" else "") + fmt(abs(n))
    private fun netColor(n: Long) = when { n > 0 -> Dread.GOOD; n < 0 -> Dread.ERROR; else -> Dread.BONE_HI }

    private fun minutes(ms: Long): String {
        val m = ms / 60000
        return when {
            m < 1 -> "< 1 min"
            m < 60 -> "$m min"
            else -> "${m / 60} h ${"%02d".format(m % 60)} min"
        }
    }

    private fun hair(): View = View(ctx).apply {
        setBackgroundColor(Dread.withAlpha(Dread.IRON_LIGHT, 0x80))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxOf(1, ui.dp(1)))
    }

    // ================================================================== STATS
    /** [actions] (change password / reset) are placed before the history so they are never buried under a long list. */
    fun stats(actions: View? = null): View {
        val page = ui.vbox()
        val net = wallet.sessionNet
        val cols = Masonry(ctx, ui.dp(640), ui.dp(16))

        // ---- this session
        val session = ui.vbox()
        session.addView(ui.section("This session"), ui.lp(b = 8))
        session.addView(ui.card(highlight = true).apply {
            addView(ui.caps("Net result", 11f), ui.lp())
            addView(ui.figure("${signed(net)} tokens", 34f, netColor(net)), ui.lp(t = 4))
            addView(ui.text("Won minus wagered since you started playing.", 12.5f, Dread.BONE_DIM), ui.lp(t = 2))
        }, ui.lp())
        session.addView(ui.tiles(
            ui.stat("Time played", minutes(wallet.sessionMillis), maxSp = 20f),
            ui.stat("Wagered", fmt(wallet.sessionWagered), maxSp = 20f),
            ui.stat("Won", fmt(wallet.sessionWon), maxSp = 20f)), ui.lp(t = 8))
        val back = if (wallet.sessionWagered > 0) "%.1f %%".format(wallet.sessionWon * 100.0 / wallet.sessionWagered) else "-"
        session.addView(ui.kv("Returned this session", back), ui.lp(t = 6))
        cols.addView(session)

        // ---- lifetime
        val life = ui.vbox()
        life.addView(ui.section("Lifetime"), ui.lp(b = 4))
        val rtp = if (wallet.totalWagered > 0) "%.1f %%".format(wallet.totalWon * 100.0 / wallet.totalWagered) else "-"
        life.addView(ui.card(pad = 12).apply {
            fun row(k: String, v: String, last: Boolean = false, color: Int = Dread.BONE_HI) {
                addView(ui.kv(k, v, color), ui.lp()); if (!last) addView(hair(), ui.lp())
            }
            row("Spins played", fmt(wallet.spins))
            row("Total wagered", "${fmt(wallet.totalWagered)} tokens")
            row("Total won", "${fmt(wallet.totalWon)} tokens")
            row("Your actual return", rtp)
            row("Biggest win", "${fmt(wallet.biggestWin)} tokens")
            row("Soul Vaults", "${fmt(wallet.bonuses)}  (paid ${fmt(wallet.bonusWon)})")
            row("Séances", "${fmt(wallet.seances)}  (paid ${fmt(wallet.seanceWon)})")
            row("Best Séance multiplier", if (wallet.maxSeanceMult > 0) "×${wallet.maxSeanceMult}" else "-")
            row("Mini features", fmt(wallet.features))
            row("Feature buys", fmt(wallet.buys))
            row("Relics held", "${wallet.relics} / ${ReaperConfig.RELIC_COUNT}", last = true)
        }, ui.lp())
        life.addView(ui.text("Theory: ${"%.0f".format(ReaperConfig.TARGET_RTP * 100)} % return to player, plain spins win ${ReaperConfig.HIT_FREQUENCY_TEXT} spins. " +
            "Real results swing around this, most of all in short sessions.", 12.5f, Dread.BONE_DIM), ui.lp(t = 8))
        cols.addView(life)

        // ---- credit
        val credit = ui.vbox()
        credit.addView(ui.section("Machine credit"), ui.lp(b = 8))
        credit.addView(ui.tiles(
            ui.stat("Balance", fmt(wallet.balance), Money.describe(wallet.balance), maxSp = 22f),
            ui.stat("Deposited", fmt(wallet.totalDeposited), maxSp = 22f),
            ui.stat("Withdrawn", fmt(wallet.totalWithdrawn), maxSp = 22f), weights = floatArrayOf(1.3f, 1f, 1f)), ui.lp())
        cols.addView(credit)
        page.addView(cols, ui.lp())

        if (actions != null) page.addView(actions, ui.lp(t = 16))

        // ---- history
        page.addView(ui.section("History"), ui.lp(t = 16, b = 6))
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        if (wallet.ledger.isEmpty()) {
            page.addView(ui.text("No deposits or withdrawals yet.", 14f, Dread.BONE_DIM), ui.lp(t = 4))
        } else {
            val list = ui.card(pad = 10)
            val items = wallet.ledger.asReversed().take(30)
            items.forEachIndexed { i, e ->
                val money = e.tokens != 0L
                val deposit = e.type == "DEPOSIT"
                val kind = e.type.lowercase().replaceFirstChar { it.uppercase() }
                list.addView(ui.hbox().apply {
                    setPadding(ui.dp(2), ui.dp(7), ui.dp(2), ui.dp(7))
                    addView(ui.vbox(ui.text(kind, 14.5f, Dread.BONE_HI, bold = true), ui.text(df.format(Date(e.time)), 12f, Dread.BONE_DIM)),
                        ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    if (money) addView(ui.vbox(
                        ui.text((if (deposit) "+" else "-") + fmt(e.tokens), 15f, if (deposit) Dread.GOOD else Dread.ERROR, bold = true).apply { gravity = Gravity.END },
                        ui.text("balance ${fmt(e.balanceAfter)}", 12f, Dread.BONE_DIM).apply { gravity = Gravity.END }),
                        ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, l = 10))
                }, ui.lp())
                if (i < items.size - 1) list.addView(hair(), ui.lp())
            }
            page.addView(list, ui.lp())
        }
        return page
    }

    // ================================================================== RANK
    fun rank(): View {
        val page = ui.vbox()
        val rk = wallet.rank
        val wag = wallet.totalWagered
        val hero = ui.card(highlight = true, pad = 16)
        hero.addView(ui.caps("Soul rank", 11.5f, center = true), ui.lp())
        hero.addView(ui.heading(rk.title.uppercase(), 32f), ui.lp(t = 4))
        hero.addView(ui.caps("Rank ${rk.index + 1} of ${Ranks.ALL.size}", 11.5f, Dread.BONE_DIM, center = true), ui.lp(t = 2, b = 12))
        val meter = Meter(ctx).apply { progress = rk.progress(wag) }
        hero.addView(meter, ui.lp())
        if (rk.isTop) {
            hero.addView(ui.text("You have reached the highest rank. The reaper knows your name.", 14f, Dread.BONE_HI, center = true), ui.lp(t = 8))
        } else {
            val nx = Ranks.ALL[rk.index + 1]
            hero.addView(ui.hbox().apply {
                addView(ui.text(fmt(rk.from), 12f, Dread.BONE_DIM), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text(fmt(rk.next), 12f, Dread.BONE_DIM).apply { gravity = Gravity.END }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, ui.lp(t = 4))
            hero.addView(ui.text(ui.rich("**${fmt(nx.from - wag)}** more tokens wagered to reach **${nx.title}**."), 14.5f, Dread.BONE, center = true), ui.lp(t = 6))
        }
        hero.addView(ui.text("Rank is cosmetic. It grows with the tokens you wager and never pays anything: no bonuses, no free credit.", 12.5f, Dread.BONE_DIM, center = true), ui.lp(t = 8))
        page.addView(hero, ui.lp())

        page.addView(ui.section("The ranks"), ui.lp(t = 16, b = 8))
        val ladder = ChipGrid(ctx, ui.dp(6))
        for (r in Ranks.ALL) {
            val state = when { r.index == rk.index -> 2; r.index < rk.index -> 1; else -> 0 }
            ladder.addView(rankChip(r.title, state))
        }
        page.addView(ladder, ui.lp())

        val unlocked = Achievements.ALL.count { Achievements.isUnlocked(wallet, it.id) }
        page.addView(ui.hbox().apply {
            addView(ui.section("Reaper's Ledger"), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.caps("$unlocked / ${Achievements.ALL.size}", 12.5f, Dread.BONE_HI).apply { gravity = Gravity.END }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, l = 10))
        }, ui.lp(t = 18, b = 8))
        val cols = Columns(ctx, ui.dp(640), ui.dp(10))
        val half = (Achievements.ALL.size + 1) / 2
        val left = ui.vbox(gap = 8); val right = ui.vbox(gap = 8)
        Achievements.ALL.forEachIndexed { i, a -> (if (i < half) left else right).addView(achievement(a)) }
        cols.addView(left); cols.addView(right)
        page.addView(cols, ui.lp())
        return page
    }

    private fun rankChip(title: String, state: Int): TextView = ui.text(title, 13f,
        when (state) { 2 -> Dread.BONE_HI; 1 -> Dread.BONE; else -> Dread.BONE_FAINT }, bold = state == 2, center = true).apply {
        gravity = Gravity.CENTER
        minimumHeight = ui.dp(40)
        setPadding(ui.dp(8), ui.dp(6), ui.dp(8), ui.dp(6))
        maxLines = 2
        background = when (state) {
            2 -> android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF9A1529.toInt(), 0xFF5E0D18.toInt())).apply {
                cornerRadius = ui.d * 9; setStroke(maxOf(1, ui.dp(1.5f)), Dread.BRASS)
            }
            1 -> Dread.cardBg(ui.d, highlight = false).apply { setStroke(maxOf(1, ui.dp(1)), Dread.BRASS_DIM) }
            else -> Dread.cardBg(ui.d).apply { alpha = 150 }
        }
        contentDescription = "$title, ${when (state) { 2 -> "current rank"; 1 -> "earned"; else -> "locked" }}"
    }

    private fun achievement(a: Achievements.Def): View {
        val on = Achievements.isUnlocked(wallet, a.id)
        return ui.hbox().apply {
            background = Dread.cardBg(ui.d, highlight = on)
            setPadding(ui.dp(10), ui.dp(9), ui.dp(12), ui.dp(9))
            addView(Medallion(ctx, on))
            addView(ui.vbox(
                ui.text(a.title, 14.5f, if (on) Dread.BONE_HI else Dread.BONE_DIM, bold = true),
                ui.text(a.text.replace("SEANCE", "SÉANCE"), 12.5f, if (on) Dread.BONE else Dread.BONE_FAINT)
            ), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 10))
            contentDescription = "${a.title}: ${if (on) "unlocked" else "locked"}. ${a.text}"
        }
    }
}

package com.tjshea.neonreaper.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tjshea.neonreaper.data.AuthResult
import com.tjshea.neonreaper.data.Feature
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.Money
import com.tjshea.neonreaper.engine.ReaperConfig
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * What the autoplay picker returns. [spins] < 0 = until stopped. [stopOnWinX]: stop after a round that pays >= this many times
 * the stake (0 = off). [lossLimit]: stop once the credit has fallen by this many tokens since autoplay started (0 = off).
 * (Regulated brands add exactly these limits to autoplay.)
 */
class AutoPlan(val spins: Int, val stopOnWinX: Int = 0, val lossLimit: Long = 0L)

/**
 * The game's dialogs, built in code (no layout inflation, no AndroidX) in the DREAD look: an iron sheet with brass rules and
 * rivets, bone text, blood-red actions. Every dialog is a [sheet]: title on top, footer buttons at the bottom, and a body that
 * scrolls in between, so nothing is clipped on a phone, a 10" tablet, landscape or portrait. The theme lives in Theme.kt,
 * the how-to-play content in Guide.kt, statistics and rank in Ledger.kt.
 */
class Dialogs(private val act: Activity, private val wallet: Wallet, private val onChanged: () -> Unit) {

    private val io = Executors.newSingleThreadExecutor()
    private val ui = Ui(act)
    private fun dp(v: Number) = ui.dp(v)
    private fun fmt(n: Long) = Money.fmt(n)

    /** Open sheets, oldest first (a link inside Settings stacks a new sheet on top). */
    private val stack = ArrayList<Dialog>()

    private class Sheet {
        lateinit var dlg: Dialog
        lateinit var scroll: ScrollView
        fun close() { if (this::dlg.isInitialized) dlg.dismiss() }
    }

    // ------------------------------------------------------------------ the sheet shell
    /**
     * Builds and shows one dialog. [body] scrolls; the title, the optional [tabs] strip and the [footer] stay on screen.
     * [widthDp] is the widest the sheet gets (it never exceeds the screen minus a margin).
     */
    private fun sheet(
        sh: Sheet, title: CharSequence, body: View, footer: View? = null, subtitle: CharSequence? = null, tabs: View? = null,
        widthDp: Int = 560, cancelable: Boolean = true, outsideCancels: Boolean = cancelable, onDismiss: () -> Unit = {},
    ): Sheet {
        if (act.isFinishing || act.isDestroyed) return sh
        val dlg = Dialog(act, android.R.style.Theme_Material_Dialog_NoActionBar)
        sh.dlg = dlg
        val tight = ui.tight
        val padH = dp(if (tight) 16 else 26)
        val panel = SheetLayout(act, dp(widthDp), dp(if (tight) 8 else 20)).apply {
            background = IronPanel(act)
            isClickable = true   // taps on the sheet never count as "outside"
            setPadding(padH, dp(if (tight) 16 else 22), padH, dp(if (tight) 14 else 20))
        }
        panel.addView(ui.heading(title, if (tight) 21f else 26f), ui.lp())
        if (subtitle != null) panel.addView(ui.text(subtitle, 13.5f, Dread.BONE_DIM, center = true), ui.lp(t = 4))
        panel.addView(ui.rule(), ui.lp(t = 6, b = 6))
        if (tabs != null) panel.addView(tabs, ui.lp(b = 8))
        val scroll = ui.scroll()
        scroll.addView(FrameLayout(act).apply { setPadding(0, dp(4), 0, dp(8)); addView(body) })
        sh.scroll = scroll
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (footer != null) {
            panel.addView(RuleView(act, ornament = false), ui.lp(t = 2, b = 10))
            panel.addView(footer, ui.lp())
        }
        val root = FrameLayout(act).apply {
            setBackgroundColor(0xD0050607.toInt())
            if (outsideCancels) setOnClickListener { dlg.cancel() }
        }
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        dlg.setContentView(root)
        dlg.setTitle(title)
        dlg.setCancelable(cancelable)
        val legacy = Build.VERSION.SDK_INT < 30
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // no screenshots of password dialogs
            w.setWindowAnimations(android.R.style.Animation_Toast)
            if (legacy) w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        }
        dlg.setOnDismissListener { stack.remove(dlg); onDismiss(); onChanged() }
        stack += dlg
        dlg.show()
        dlg.window?.let { w -> immerse(w); if (legacy) w.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) }
        return sh
    }

    /** Keeps the system bars hidden while a dialog has the focus (the activity runs immersive). */
    private fun immerse(w: Window) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                w.insetsController?.let {
                    it.hide(WindowInsets.Type.systemBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = act.window.decorView.systemUiVisibility
            }
        } catch (_: Throwable) { /* cosmetic */ }
    }

    private fun dismissAll() { for (d in ArrayList(stack)) d.dismiss() }

    // ------------------------------------------------------------------ small helpers
    private fun bar(vararg buttons: View): View = ButtonBar(act, dp(10)).apply { for (b in buttons) addView(b) }

    private fun statusView(): TextView = ui.text("", 14f, Dread.ERROR, center = true)
    private fun TextView.fail(msg: String) { setTextColor(Dread.ERROR); text = msg }
    private fun TextView.busy(msg: String) { setTextColor(Dread.BRASS_HI); text = msg }

    /** Pressing "Done" on the keyboard acts like the primary button. */
    private fun EditText.submitWith(primary: View) {
        imeOptions = (imeOptions and EditorInfo.IME_MASK_ACTION.inv()) or EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && primary.isEnabled) { primary.performClick(); true } else false
        }
    }

    private fun readPw(e: EditText): CharArray {
        val ed = e.text
        val out = CharArray(ed.length)
        ed.getChars(0, ed.length, out, 0)
        return out
    }

    private fun authMessage(r: AuthResult): String = when (r) {
        is AuthResult.Ok -> ""
        is AuthResult.Wrong -> "Wrong password. ${r.attemptsBeforeLock} attempt(s) left before lockout."
        is AuthResult.Locked -> "Too many wrong attempts. Locked for ${((r.untilMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)} s."
        is AuthResult.Failed -> r.message
    }

    /** Runs a slow wallet call (PBKDF2) off the UI thread. */
    private fun <T> background(work: () -> T, done: (T) -> Unit) {
        io.execute {
            val r = work()
            act.runOnUiThread { if (!act.isFinishing) done(r) }
        }
    }

    // ------------------------------------------------------------------ amount entry
    private inner class AmountEntry {
        val books = ui.field("0", number = true)
        val macks = ui.field("0", number = true)
        val tokens = ui.field("0", number = true)
        val total = ui.figure("0 tokens", 28f, Dread.BONE_HI)
        val totalText = ui.text("", 13f, Dread.BONE_DIM)
        var onChange: (() -> Unit)? = null

        fun value(): Long {
            fun v(e: EditText) = e.text.toString().toLongOrNull() ?: 0L
            return Money.toTokens(v(books), v(macks), v(tokens))
        }

        fun set(t: Long) {
            books.setText((t / Money.TOKENS_PER_BOOK).takeIf { it > 0 }?.toString() ?: "")
            macks.setText(((t % Money.TOKENS_PER_BOOK) / Money.TOKENS_PER_MACK).takeIf { it > 0 }?.toString() ?: "")
            tokens.setText((t % Money.TOKENS_PER_MACK).takeIf { it > 0 }?.toString() ?: "")
        }

        fun view(quick: List<Pair<String, Long>>, extra: View? = null): View {
            val w = object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = value()
                    total.text = "${fmt(v)} tokens"
                    totalText.text = if (v > 0) Money.describe(v) else "Enter books, macks and tokens"
                    onChange?.invoke()
                }
            }
            books.addTextChangedListener(w); macks.addTextChangedListener(w); tokens.addTextChangedListener(w)
            totalText.text = "Enter books, macks and tokens"
            val fields = ui.hbox(Gravity.BOTTOM).apply {
                addView(ui.labeled("Books", books), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, r = 5))
                addView(ui.labeled("Macks", macks), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 5, r = 5))
                addView(ui.labeled("Tokens", tokens), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 5))
            }
            val chips = ChipGrid(act, dp(8))
            for ((label, amt) in quick) chips.addView(ui.button(label, Dread.Kind.SECONDARY, small = true) { set(value() + amt) })
            extra?.let { chips.addView(it) }
            chips.addView(ui.button("Clear", Dread.Kind.SECONDARY, small = true) { set(0) })
            val totalCard = ui.card(pad = 12).apply {
                addView(ui.caps("Total", 11f), ui.lp())
                addView(total, ui.lp(t = 2))
                addView(totalText, ui.lp(t = 2))
            }
            return ui.vbox(fields, chips, totalCard, gap = 10)
        }
    }

    // ------------------------------------------------------------------ dialogs
    fun setupPassword() {
        val sh = Sheet()
        val pw1 = ui.field("At least 4 characters", password = true)
        val pw2 = ui.field("Repeat the password", password = true)
        val status = statusView()
        val ok = ui.button("Set password") { v ->
            val a = pw1.text.toString(); val b = pw2.text.toString()
            when {
                a.length < 4 -> status.fail("Password must be at least 4 characters.")
                a != b -> status.fail("Passwords don't match.")
                else -> {
                    v.isEnabled = false; status.busy("Securing machine…")
                    val chars = readPw(pw1)
                    background({ wallet.setInitialPassword(chars).also { chars.fill('\u0000') } }) {
                        sh.close()
                        message("Machine ready", "Your password now protects every deposit and cash-out.\n\nTap DEPOSIT to add tokens, then SPIN. Collect all five relics to open the Soul Vault, and land three coffins to awaken THE SÉANCE.\n\nThere is no password recovery — keep it safe.")
                    }
                }
            }
        }
        pw2.submitWith(ok)
        val emblem = ui.icon(Art.symbol(ReaperConfig.WILD, dp(104)), 104, "The Grim Reaper").apply {
            layoutParams = LinearLayout.LayoutParams(dp(104), dp(104)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        sheet(sh, "WELCOME TO NEON REAPER", subtitle = "Set the machine password", widthDp = 540, cancelable = false,
            body = ui.vbox(
                emblem,
                ui.text("The password is required to deposit or withdraw tokens, so nobody can change your balance without it.", 15f, Dread.BONE, center = true),
                ui.labeled("Password", pw1), ui.labeled("Confirm password", pw2), status, gap = 12),
            footer = ok)
    }

    fun deposit() {
        val sh = Sheet()
        val amt = AmountEntry()
        val pw = ui.field("Password", password = true)
        val status = statusView()
        val ok = ui.button("Deposit") { v ->
            val tokens = amt.value()
            if (tokens <= 0) { status.fail("Enter an amount."); return@button }
            v.isEnabled = false; status.busy("Verifying…")
            val chars = readPw(pw)
            background({ wallet.deposit(chars, tokens).also { chars.fill('\u0000') } }) { r ->
                if (r is AuthResult.Ok) {
                    sh.close()
                    toastBanner("Deposited ${Money.describe(tokens)}")
                } else {
                    v.isEnabled = true; status.fail(authMessage(r)); pw.setText("")
                }
            }
        }
        pw.submitWith(ok)
        sheet(sh, "DEPOSIT TOKENS", subtitle = "${Money.TOKENS_PER_MACK} tokens = 1 mack  ·  ${Money.MACKS_PER_BOOK} macks = 1 book (${Money.TOKENS_PER_BOOK} tokens)",
            widthDp = 580, outsideCancels = false,
            body = ui.vbox(
                amt.view(listOf("+1 mack" to 10L, "+5 macks" to 50L, "+1 book" to 80L, "+5 books" to 400L, "+25 books" to 2000L)),
                ui.labeled("Password", pw), status, gap = 12),
            footer = bar(ui.button("Cancel", Dread.Kind.SECONDARY) { sh.close() }, ok))
    }

    fun withdraw() {
        val sh = Sheet()
        val amt = AmountEntry()
        val pw = ui.field("Password", password = true)
        val status = statusView()
        val all = ui.button("All", Dread.Kind.SECONDARY, small = true) { amt.set(wallet.cashableTokens) }
        val ok = ui.button("Cash out") { v ->
            val tokens = amt.value()
            if (tokens <= 0) { status.fail("Enter an amount."); return@button }
            if (tokens > wallet.balance) { status.fail("You only have ${Money.describe(wallet.balance)}."); return@button }
            if (tokens < Money.TOKENS_PER_MACK) { status.fail("Cash-outs are whole macks — enter at least 10 tokens."); return@button }
            v.isEnabled = false; status.busy("Verifying…")
            val chars = readPw(pw)
            background({ wallet.withdraw(chars, tokens).also { chars.fill('\u0000') } }) { r ->
                if (r is AuthResult.Ok) {
                    sh.close()
                    ticket(wallet.lastCashOut)
                } else {
                    v.isEnabled = true; status.fail(authMessage(r)); pw.setText("")
                }
            }
        }
        pw.submitWith(ok)
        amt.set(wallet.cashableTokens)
        sheet(sh, "CASH OUT", widthDp = 580, outsideCancels = false,
            subtitle = "Balance ${fmt(wallet.balance)} tokens (${Money.describe(wallet.balance)})",
            body = ui.vbox(
                ui.text("Cash-outs are paid in whole macks: the amount is rounded down to the nearest ${Money.TOKENS_PER_MACK} tokens and any odd tokens stay on your credit.", 13.5f, Dread.BONE_DIM, center = true),
                amt.view(listOf("+1 mack" to 10L, "+1 book" to 80L, "+10 books" to 800L), extra = all),
                ui.labeled("Password", pw), status, gap = 12),
            footer = bar(ui.button("Cancel", Dread.Kind.SECONDARY) { sh.close() }, ok))
    }

    private fun ticket(tokens: Long) {
        val sh = Sheet()
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val ink = 0xFF1B1812.toInt()
        val paper = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = TicketPaper(act)
            setPadding(dp(22), dp(28), dp(22), dp(28))
            fun line(s: String, sp: Float = 15f, bold: Boolean = false) = addView(TextView(act).apply {
                text = s; textSize = sp; setTextColor(ink); gravity = Gravity.CENTER
                typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            }, ui.lp(t = 2))
            line("NEON REAPER", 19f, true)
            line("CASH-OUT TICKET #${wallet.ticketNo}")
            line(df.format(Date()))
            line("- - - - - - - - - - - - - - -")
            line("${fmt(tokens)} TOKENS", 26f, true)
            line(Money.describe(tokens))
            line("- - - - - - - - - - - - - - -")
            line("Remaining credit: ${fmt(wallet.balance)} tokens", 13f)
        }
        sheet(sh, "TICKET PRINTED", widthDp = 460, body = paper, footer = ui.button("Done") { sh.close() })
    }

    /** How to play: pay table at [stake], features, rules. */
    fun info(stake: Int) {
        val sh = Sheet()
        val guide = Guide(ui, wallet)
        val host = FrameLayout(act)
        val pages = arrayOfNulls<View>(3)
        fun page(i: Int): View = pages[i] ?: (when (i) { 0 -> guide.pays(stake); 1 -> guide.features(stake); else -> guide.rules(stake) }).also { pages[i] = it }
        fun showPage(i: Int) { host.removeAllViews(); host.addView(page(i)); sh.scroll.scrollTo(0, 0) }   // (only called by a tab tap, after the sheet exists)
        host.addView(page(0))
        val tabs = ui.choice(listOf("Pays", "Features", "Rules"), 0, "Section") { showPage(it) }
        sheet(sh, "PAYTABLE & RULES", subtitle = "Pays shown for your stake of $stake token${if (stake == 1) "" else "s"}", widthDp = 900,
            body = host, tabs = tabs, footer = ui.button("Close") { sh.close() })
    }

    fun stats() {
        val sh = Sheet()
        val actions = ui.vbox(
            ui.section("Machine"),
            ui.hbox().apply {
                addView(ui.button("Change password", Dread.Kind.SECONDARY) { sh.close(); changePassword() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, r = 5))
                addView(ui.button("Reset machine", Dread.Kind.DANGER) { sh.close(); reset() }, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 5))
            }, gap = 8)
        sheet(sh, "MACHINE STATS", subtitle = "Session figures count from when the game was opened", widthDp = 880,
            body = Ledger(ui, wallet).stats(actions), footer = ui.button("Close") { sh.close() })
    }

    fun changePassword() {
        val sh = Sheet()
        val old = ui.field("Current password", password = true)
        val n1 = ui.field("At least 4 characters", password = true)
        val n2 = ui.field("Repeat the new password", password = true)
        val status = statusView()
        val ok = ui.button("Change") { v ->
            if (n1.text.length < 4) { status.fail("New password must be at least 4 characters."); return@button }
            if (n1.text.toString() != n2.text.toString()) { status.fail("New passwords don't match."); return@button }
            v.isEnabled = false; status.busy("Verifying…")
            val o = readPw(old); val n = readPw(n1)
            background({ wallet.changePassword(o, n).also { o.fill('\u0000'); n.fill('\u0000') } }) { r ->
                if (r is AuthResult.Ok) { sh.close(); toastBanner("Password changed") }
                else { v.isEnabled = true; status.fail(authMessage(r)); old.setText("") }
            }
        }
        n2.submitWith(ok)
        sheet(sh, "CHANGE PASSWORD", widthDp = 540, outsideCancels = false,
            body = ui.vbox(ui.labeled("Current password", old), ui.labeled("New password", n1), ui.labeled("Confirm new password", n2), status, gap = 12),
            footer = bar(ui.button("Cancel", Dread.Kind.SECONDARY) { sh.close() }, ok))
    }

    fun reset() {
        val sh = Sheet()
        val pw = ui.field("Password", password = true)
        val status = statusView()
        val ok = ui.button("Erase everything", Dread.Kind.DANGER) { v ->
            v.isEnabled = false; status.busy("Verifying…")
            val chars = readPw(pw)
            background({ wallet.factoryReset(chars).also { chars.fill('\u0000') } }) { r ->
                if (r is AuthResult.Ok) { dismissAll(); setupPassword() }
                else { v.isEnabled = true; status.fail(authMessage(r)); pw.setText("") }
            }
        }
        pw.submitWith(ok)
        sheet(sh, "RESET MACHINE", widthDp = 540, outsideCancels = false,
            body = ui.vbox(
                warnMark(),
                ui.text(ui.rich("This permanently erases the balance (**${Money.describe(wallet.balance)}**), relics, statistics and password. Cash out first if you want to keep your tokens."), 15f, Dread.BONE, center = true),
                ui.labeled("Password", pw), status, gap = 12),
            footer = bar(ui.button("Cancel", Dread.Kind.SECONDARY) { sh.close() }, ok))
    }

    private fun warnMark(): View = WarnMark(act).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL }
    }

    fun tampered() {
        val sh = Sheet()
        sheet(sh, "TILT — MACHINE LOCKED", widthDp = 540, cancelable = false,
            body = ui.vbox(
                warnMark(),
                ui.text("The machine's credit records failed their security check (they were modified outside the game or are corrupted). For your protection the balance can't be trusted and play is disabled.", 15f, Dread.BONE, center = true),
                ui.text("Resetting erases the records and lets you set a new password.", 15f, Dread.BONE_HI, center = true), gap = 12),
            footer = ui.button("Reset machine", Dread.Kind.DANGER) {
                wallet.factoryReset(null); dismissAll(); setupPassword()
            })
    }

    // ------------------------------------------------------------------ autoplay
    fun autoPicker(onPicked: (AutoPlan) -> Unit) {
        val sh = Sheet()
        val counts = intArrayOf(10, 25, 50, 100, -1)
        val winOpts = intArrayOf(0, 10, 25, 50, 100)
        val lossOpts = intArrayOf(0, 10, 25, 50)
        var ci = 0; var wi = 0; var li = 0
        val summary = ui.text("", 14f, Dread.BONE)
        fun refresh() {
            val st = wallet.stake
            val n = counts[ci]
            val lines = ArrayList<String>()
            lines += if (n > 0) "**$n spins** at your stake of **$st** wager up to **${fmt(n.toLong() * st)} tokens**."
            else "Plays **until you stop it**, your credit runs out or a limit below is reached."
            if (winOpts[wi] > 0) lines += "Stops after a single win of **${winOpts[wi]}×** your stake (${fmt(winOpts[wi].toLong() * st)} tokens or more)."
            if (lossOpts[li] > 0) lines += "Stops if your credit falls by **${lossOpts[li]} %** (**${fmt(wallet.balance * lossOpts[li] / 100)} tokens**) from now."
            summary.text = ui.rich(lines.joinToString("\n"))
        }
        val spinsChips = ui.choice(listOf("10", "25", "50", "100", "Until stopped"), 0, "Number of spins") { ci = it; refresh() }
        val winChips = ui.choice(listOf("Off", "10×", "25×", "50×", "100×"), 0, "Stop on a single win of") { wi = it; refresh() }
        val lossChips = ui.choice(listOf("Off", "10 %", "25 %", "50 %"), 0, "Stop if credit drops by") { li = it; refresh() }
        refresh()
        val start = ui.button("Start autoplay") {
            sh.close()
            onPicked(AutoPlan(counts[ci], winOpts[wi], if (lossOpts[li] == 0) 0L else wallet.balance * lossOpts[li] / 100))
        }
        sheet(sh, "AUTOPLAY", subtitle = "Credit ${fmt(wallet.balance)} tokens  ·  stake ${wallet.stake}", widthDp = 660,
            body = ui.vbox(
                ui.vbox(ui.section("Number of spins"), spinsChips, gap = 8),
                ui.vbox(ui.section("Stop on a single win of (times your stake)"), winChips, gap = 8),
                ui.vbox(ui.section("Stop if my credit drops by"), lossChips, gap = 8),
                ui.card(pad = 12).apply {
                    addView(summary, ui.lp())
                    addView(ui.text("Autoplay also stops when you tap STOP, and always pauses for the Soul Vault and THE SÉANCE.", 12.5f, Dread.BONE_DIM), ui.lp(t = 6))
                }, gap = 14),
            footer = bar(ui.button("Cancel", Dread.Kind.SECONDARY) { sh.close() }, start))
    }

    // ------------------------------------------------------------------ feature buy
    /** Feature Buy: skip the wait for THE SÉANCE or the SOUL VAULT at a fixed price (priced above its exact expected payout). */
    fun buyFeature(stake: Int, onChoice: (Feature) -> Unit) {
        val sh = Sheet()
        var picked: Feature? = null
        val cardsHost = FrameLayout(act)
        val footerHost = FrameLayout(act)
        fun price(f: Feature) = wallet.buyPrice(f, stake)
        fun title(f: Feature) = if (f == Feature.SEANCE) "THE SÉANCE" else "THE SOUL VAULT"
        lateinit var render: () -> Unit

        fun card(f: Feature): View {
            val can = wallet.canBuy(f, stake)
            val seance = f == Feature.SEANCE
            val c = ui.card(highlight = picked == f, pad = 16)
            val icon = if (seance) Art.symbol(ReaperConfig.SCATTER, dp(64)) else Art.orbBitmap(dp(64), Neon.GREEN)
            c.addView(ui.hbox().apply {
                addView(ui.icon(icon, 64, title(f)))
                addView(ui.vbox(
                    ui.heading(title(f), 21f, center = false).apply { setShadowLayer(0f, 0f, 0f, 0) },
                    ui.caps(if (seance) "Free spins · rising multiplier" else "Hold & Spin · jackpot orbs", 11f)),
                    ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 12))
            }, ui.lp())
            c.addView(ui.figure("${fmt(price(f))} tokens", 30f, if (can) Dread.BONE_HI else Dread.BONE_DIM), ui.lp(t = 12))
            c.addView(ui.text("${if (seance) ReaperConfig.BUY_SEANCE_X else ReaperConfig.BUY_VAULT_X}× your stake of $stake", 13f, Dread.BONE_DIM), ui.lp())
            if (seance) {
                c.addView(ui.bullet("**${ReaperConfig.FS_SPINS[3]} free spins** on richer reels with extra wilds."), ui.lp(t = 10))
                c.addView(ui.bullet("The multiplier climbs from ×1 to **×${ReaperConfig.FS_RAMP_CAP}**; more coffins add spins."), ui.lp(t = 6))
            } else {
                c.addView(ui.bullet("**${ReaperConfig.VAULT_START_ORBS} soul orbs** on a ${ReaperConfig.VAULT_COLS}×${ReaperConfig.VAULT_ROWS} board, **${ReaperConfig.VAULT_RESPINS} respins**."), ui.lp(t = 10))
                c.addView(ui.bullet("Orbs pay up to **${ReaperConfig.ORB_VALUES.max()}×**; jackpots **MINI ${ReaperConfig.MINI}×, MINOR ${ReaperConfig.MINOR}×, MAJOR ${ReaperConfig.MAJOR}×**, GRAND ${ReaperConfig.GRAND}× for a full board."), ui.lp(t = 6))
                c.addView(ui.bullet("Your stake (**$stake**) is the bonus bet."), ui.lp(t = 6))
            }
            c.addView(ui.text(ui.rich("Returns **about 92 %** on average, a little less than the ${"%.0f".format(ReaperConfig.TARGET_RTP * 100)} % of normal play."), 13f, Dread.BONE_DIM), ui.lp(t = 10))
            if (!can) {
                val need = price(f) - wallet.balance
                c.addView(ui.text(if (need > 0) "Not enough credit: ${fmt(need)} more tokens needed." else "Not available right now.", 13.5f, Dread.ERROR), ui.lp(t = 8))
            }
            c.addView(ui.button(if (picked == f) "Selected" else "Buy for ${fmt(price(f))}", if (seance) Dread.Kind.PRIMARY else Dread.Kind.SECONDARY) {
                picked = f; render()
            }.also { it.isEnabled = can && picked != f }, ui.lp(t = 12))
            return c
        }

        render = {
            cardsHost.removeAllViews()
            cardsHost.addView(Columns(act, dp(640), dp(14), equalHeight = true).apply {
                addView(card(Feature.SEANCE)); addView(card(Feature.VAULT))
            })
            footerHost.removeAllViews()
            val f = picked
            if (f == null) {
                footerHost.addView(ui.vbox(
                    ui.text("Credit: ${fmt(wallet.balance)} tokens. Choose a feature, then confirm.", 13.5f, Dread.BONE_DIM, center = true),
                    bar(ui.button("Cancel", Dread.Kind.SECONDARY) { sh.close() }), gap = 8))
            } else {
                footerHost.addView(ui.vbox(
                    ui.text(ui.rich("Pay **${fmt(price(f))} tokens** for **${title(f)}**? Credit afterwards: **${fmt(wallet.balance - price(f))}**."), 14.5f, Dread.BONE, center = true),
                    bar(ui.button("Back", Dread.Kind.SECONDARY) { picked = null; render() },
                        ui.button("Confirm · pay ${fmt(price(f))}") { sh.close(); onChoice(f) }), gap = 10))
            }
        }
        render()
        sheet(sh, "FEATURE BUY", subtitle = "Skip the wait, for a price", widthDp = 800, body = cardsHost, footer = footerHost)
    }

    // ------------------------------------------------------------------ settings
    /** Quality, reduce-flashing, reality-check interval and volume. Every change is applied and saved at once. */
    fun settings(onChanged: () -> Unit) {
        val sh = Sheet()
        val handler = Handler(Looper.getMainLooper())
        var dirty = false
        val flush = Runnable { if (dirty) { dirty = false; wallet.saveSettings() } }
        fun applyNow() { wallet.saveSettings(); onChanged() }

        val quality = ui.choice(listOf("Auto", "Low", "Medium", "High"), wallet.quality.coerceIn(0, 3), "Graphics quality") { wallet.quality = it; applyNow() }
        val flashing = ui.choice(listOf("Off", "On"), if (wallet.reduceFlash) 1 else 0, "Reduce flashing") { wallet.reduceFlash = it == 1; applyNow() }
        val realityValues = intArrayOf(0, 15, 30, 60)
        val reality = ui.choice(listOf("Off", "15 min", "30 min", "60 min"), realityValues.indexOf(wallet.realityMinutes), "Reality check") {
            wallet.realityMinutes = realityValues[it]; applyNow()
        }

        fun volume(label: String, get: () -> Int, set: (Int) -> Unit): View {
            val value = ui.text("${get()} %", 14f, Dread.BONE_HI, bold = true)
            val bar = ui.slider(20, get() / 5, label, { p, _ ->
                set(p * 5); value.text = "${p * 5} %"; dirty = true; onChanged()
                handler.removeCallbacks(flush); handler.postDelayed(flush, 600)   // keyboard / accessibility changes have no "stop" event
            }, { handler.removeCallbacks(flush); flush.run() })
            return ui.vbox(ui.hbox().apply {
                addView(ui.caps(label, 11.5f), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
            }, bar, gap = 2)
        }

        fun group(label: String, control: View, hint: String? = null): View {
            val g = ui.vbox(ui.caps(label, 11.5f), control, gap = 7)
            if (hint != null) g.addView(ui.text(hint, 12.5f, Dread.BONE_DIM), ui.lp(t = 6))
            return g
        }

        fun link(title: String, sub: String, onClick: () -> Unit): View = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Dread.buttonBg(Dread.Kind.LINK, ui.d)
            minimumHeight = dp(58)
            isClickable = true; isFocusable = true
            setPadding(dp(14), dp(8), dp(12), dp(8))
            addView(ui.vbox(ui.text(title, 15f, Dread.BONE_HI, bold = true), ui.text(sub, 12.5f, Dread.BONE_DIM)), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text("›", 26f, Dread.BRASS_HI), ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f, l = 8))
            setOnClickListener { onClick() }
            contentDescription = "$title. $sub"
        }

        val left = ui.vbox(gap = 16)
        left.addView(ui.vbox(ui.section("Graphics"), group("Quality", quality), group("Reduce flashing", flashing, "Softens lightning, bloom and screen shake."), gap = 10))
        left.addView(ui.vbox(ui.section("Sound"), volume("Music volume", { wallet.musicVolume }, { wallet.musicVolume = it }),
            volume("Effects volume", { wallet.sfxVolume }, { wallet.sfxVolume = it }), gap = 10))
        val right = ui.vbox(gap = 16)
        right.addView(ui.vbox(ui.section("Player protection"),
            group("Reality check", reality, "A pause that shows your time played and net result."), gap = 10))
        right.addView(ui.vbox(ui.section("The machine"),
            link("Soul Rank & Reaper's Ledger", "Your rank and ${com.tjshea.neonreaper.data.Achievements.ALL.size} achievements") { rank() },
            link("Statistics & history", "Session and lifetime figures") { if (!gate()) stats() },
            link("How to play", "Pay table, features, rules") { info(wallet.stake) }, gap = 8))
        val cols = Columns(act, dp(620), dp(22)).apply { addView(left); addView(right) }
        sheet(sh, "SETTINGS", widthDp = 860, body = cols, footer = ui.button("Done") { sh.close() },
            onDismiss = { handler.removeCallbacks(flush); flush.run() })
    }

    /** Statistics need a ready machine (like the HUD's own gate): a missing password or a tampered wallet is handled first. */
    private fun gate(): Boolean = when {
        wallet.status == Wallet.LoadStatus.TAMPERED -> { tampered(); true }
        !wallet.hasPassword -> { setupPassword(); true }
        else -> false
    }

    // ------------------------------------------------------------------ rank, reality, message
    /** Soul Rank (cosmetic, from lifetime tokens wagered) and Reaper's Ledger (achievements). */
    fun rank() {
        val sh = Sheet()
        sheet(sh, "SOUL RANK & LEDGER", widthDp = 800, body = Ledger(ui, wallet).rank(), footer = ui.button("Close") { sh.close() })
    }

    /** Reality check: time played and net result so far this session. */
    fun reality(onContinue: () -> Unit, onStop: () -> Unit) {
        val sh = Sheet()
        val mins = wallet.sessionMillis / 60000
        val net = wallet.sessionNet
        val netText = (if (net > 0) "+" else if (net < 0) "-" else "") + fmt(abs(net))
        val netColor = when { net > 0 -> Dread.GOOD; net < 0 -> Dread.ERROR; else -> Dread.BONE_HI }
        val hourglass = ui.icon(Art.relic(1, dp(52), true), 52, "Hourglass").apply {
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(52)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        sheet(sh, "REALITY CHECK", subtitle = "A moment to look at your play", widthDp = 560, cancelable = false,
            body = ui.vbox(
                hourglass,
                ui.text(ui.rich(if (mins < 1) "You have been playing for **less than a minute**." else "You have been playing for **$mins minute${if (mins == 1L) "" else "s"}**."), 16f, Dread.BONE, center = true),
                ui.hbox().apply {
                    addView(ui.stat("Wagered", fmt(wallet.sessionWagered), maxSp = 22f), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, r = 5))
                    addView(ui.stat("Won", fmt(wallet.sessionWon), maxSp = 22f), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 5, r = 5))
                    addView(ui.stat("Net result", netText, valueColor = netColor, maxSp = 22f), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f, l = 5))
                },
                ui.text("Net result = won minus wagered, in tokens, for this session. Play should be fun: take a break whenever you like.", 13f, Dread.BONE_DIM, center = true),
                gap = 12),
            footer = bar(ui.button("Take a break") { sh.close(); onStop() }, ui.button("Keep playing", Dread.Kind.SECONDARY) { sh.close(); onContinue() }))
    }

    fun message(t: String, body: String) {
        val sh = Sheet()
        sheet(sh, t.uppercase(), widthDp = 540, body = ui.text(body, 15f, Dread.BONE, center = body.length < 90), footer = ui.button("OK") { sh.close() })
    }

    var toastBanner: (String) -> Unit = {}
}

/** The cash-out ticket: aged paper with torn (zig-zag) top and bottom edges. */
private class TicketPaper(context: android.content.Context) : Drawable() {
    private val d = context.resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onBoundsChange(b: Rect) {
        path.reset()
        val tooth = 9f * d; val depth = 4.5f * d
        val l = b.left.toFloat(); val r = b.right.toFloat(); val t = b.top.toFloat(); val bt = b.bottom.toFloat()
        path.moveTo(l, t + depth)
        var x = l; var up = false
        while (x < r) { x = minOf(x + tooth / 2, r); path.lineTo(x, if (up) t + depth else t); up = !up }
        path.lineTo(r, bt - depth)
        x = r; up = false
        while (x > l) { x = maxOf(x - tooth / 2, l); path.lineTo(x, if (up) bt - depth else bt); up = !up }
        path.close()
        p.shader = LinearGradient(0f, t, 0f, bt, intArrayOf(0xFFE9E1C9.toInt(), 0xFFD6CCAD.toInt()), null, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) { if (!bounds.isEmpty) c.drawPath(path, p) }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

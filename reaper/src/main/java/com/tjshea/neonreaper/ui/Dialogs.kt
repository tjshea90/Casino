package com.tjshea.neonreaper.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tjshea.neonreaper.data.AuthResult
import com.tjshea.neonreaper.data.Wallet
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.Money
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.max

/** Casino-styled dialogs built in code (no layout inflation, no AndroidX). */
class Dialogs(private val act: Activity, private val wallet: Wallet, private val onChanged: () -> Unit) {

    private val io = Executors.newSingleThreadExecutor()
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Number) = (v.toFloat() * d).toInt()

    private val gold = 0xFF76FF03.toInt()
    private val cream = 0xFFE0E8DC.toInt()
    private val serif = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    private val cond = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    // ------------------------------------------------------------------ building blocks
    private fun panelBg() = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0E140C.toInt(), 0xFF020302.toInt())
    ).apply { cornerRadius = dp(18).toFloat(); setStroke(dp(3), gold) }

    private fun buttonBg(color: Int) = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(lighten(color), color)
    ).apply { cornerRadius = dp(12).toFloat(); setStroke(dp(2), lighten(lighten(color))) }

    private fun lighten(c: Int) = Color.rgb(
        minOf(255, Color.red(c) + 50), minOf(255, Color.green(c) + 50), minOf(255, Color.blue(c) + 50)
    )

    private fun text(s: CharSequence, size: Float = 16f, color: Int = cream, bold: Boolean = false, center: Boolean = false) =
        TextView(act).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = cond
            if (center) gravity = Gravity.CENTER
        }

    private fun title(s: String) = TextView(act).apply {
        text = s; textSize = 26f; setTextColor(gold); typeface = serif; gravity = Gravity.CENTER
        setShadowLayer(14f, 0f, 0f, 0xFF76FF03.toInt())
        setPadding(0, 0, 0, dp(8))
    }

    private fun button(label: String, color: Int = 0xFF8D1010.toInt(), onClick: (View) -> Unit) = Button(act).apply {
        text = label; textSize = 16f; setTextColor(Color.WHITE); typeface = cond
        isAllCaps = true
        background = buttonBg(color)
        stateListAnimator = null
        setPadding(dp(14), dp(6), dp(14), dp(6))
        minHeight = dp(46); minimumHeight = dp(46)
        setOnClickListener(onClick)
    }

    private fun field(hint: String, password: Boolean = false, number: Boolean = false) = EditText(act).apply {
        this.hint = hint
        setHintTextColor(0xFF6A7A62.toInt())
        setTextColor(Color.WHITE); textSize = 18f
        isSingleLine = true
        inputType = when {
            password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT
        }
        if (number) filters = arrayOf(InputFilter.LengthFilter(7))
        if (password) filters = arrayOf(InputFilter.LengthFilter(64))
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        background = GradientDrawable().apply {
            setColor(0xFF000000.toInt()); cornerRadius = dp(10).toFloat(); setStroke(dp(1), 0xFF2E7D0A.toInt())
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    private fun row(vararg views: View, weights: Boolean = true) = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for (v in views) addView(v, LinearLayout.LayoutParams(if (weights) 0 else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, if (weights) 1f else 0f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
    }

    private fun show(content: View, cancelable: Boolean = true, widthFrac: Float = 0.62f): Dialog {
        val dlg = Dialog(act, android.R.style.Theme_Material_Dialog_NoActionBar)
        val scroll = ScrollView(act).apply { isFillViewport = true }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg()
            setPadding(dp(20), dp(16), dp(20), dp(16))
            addView(content)
        }
        scroll.addView(box)
        dlg.setContentView(scroll)
        dlg.setCancelable(cancelable)
        dlg.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val dm = act.resources.displayMetrics
            w.setLayout(max(dp(340), (dm.widthPixels * widthFrac).toInt()).coerceAtMost(dm.widthPixels - dp(16)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            w.addFlags(WindowManager.LayoutParams.FLAG_SECURE) // no screenshots of password dialogs
        }
        dlg.setOnDismissListener { onChanged() }
        dlg.show()
        return dlg
    }

    private fun vbox(vararg views: View) = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        for (v in views) addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(4), 0, dp(4)) })
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
        val books = field("Books", number = true)
        val macks = field("Macks", number = true)
        val tokens = field("Tokens", number = true)
        val total = text("Total: 0 tokens", 15f, gold, bold = true, center = true)
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
                    total.text = "Total: %,d tokens  (%s)".format(v, Money.describe(v))
                    onChange?.invoke()
                }
            }
            books.addTextChangedListener(w); macks.addTextChangedListener(w); tokens.addTextChangedListener(w)
            val chips = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, amt) in quick) {
                chips.addView(button(label, 0xFF4E342E.toInt()) { set(value() + amt) }.apply { textSize = 14f },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { setMargins(dp(3), 0, dp(3), 0) })
            }
            extra?.let { chips.addView(it) }
            chips.addView(button("Clear", 0xFF424242.toInt()) { set(0) }.apply { textSize = 14f })
            val hs = HorizontalScrollView(act).apply { addView(chips); isHorizontalScrollBarEnabled = false }
            return vbox(row(books, macks, tokens), hs, total)
        }
    }

    // ------------------------------------------------------------------ dialogs
    fun setupPassword() {
        val pw1 = field("New password (min 4 characters)", password = true)
        val pw2 = field("Confirm password", password = true)
        val status = text("", 14f, 0xFFFF8A80.toInt(), center = true)
        lateinit var dlg: Dialog
        val ok = button("Set password", 0xFF2E7D32.toInt()) { v ->
            val a = pw1.text.toString(); val b = pw2.text.toString()
            when {
                a.length < 4 -> status.text = "Password must be at least 4 characters."
                a != b -> status.text = "Passwords don't match."
                else -> {
                    v.isEnabled = false; status.setTextColor(gold); status.text = "Securing machine…"
                    val chars = readPw(pw1)
                    background({ wallet.setInitialPassword(chars).also { chars.fill('\u0000') } }) {
                        dlg.dismiss()
                        message("Machine ready", "Your password now protects every deposit and cash-out.\n\nTap DEPOSIT to add tokens, then SPIN. Collect all five relics to open the Soul Vault.\n\nThere is no password recovery — keep it safe.")
                    }
                }
            }
        }
        dlg = show(vbox(
            title("WELCOME TO NEON REAPER"),
            text("Set the machine password. It is required to deposit or withdraw tokens, so nobody can change your balance without it.", 15f),
            pw1, pw2, status, ok
        ), cancelable = false)
    }

    fun deposit() {
        val amt = AmountEntry()
        val pw = field("Password", password = true)
        val status = text("", 14f, 0xFFFF8A80.toInt(), center = true)
        lateinit var dlg: Dialog
        val ok = button("Deposit", 0xFF2E7D32.toInt()) { v ->
            val tokens = amt.value()
            if (tokens <= 0) { status.text = "Enter an amount."; return@button }
            v.isEnabled = false; status.setTextColor(gold); status.text = "Verifying…"
            val chars = readPw(pw)
            background({ wallet.deposit(chars, tokens).also { chars.fill('\u0000') } }) { r ->
                if (r is AuthResult.Ok) {
                    dlg.dismiss()
                    toastBanner("Deposited ${Money.describe(tokens)}")
                } else {
                    v.isEnabled = true; status.setTextColor(0xFFFF8A80.toInt()); status.text = authMessage(r); pw.setText("")
                }
            }
        }
        dlg = show(vbox(
            title("DEPOSIT TOKENS"),
            text("10 tokens = 1 mack  •  8 macks = 1 book (80 tokens)", 14f, 0xFFFFCC80.toInt(), center = true),
            amt.view(listOf("+1 M" to 10L, "+5 M" to 50L, "+1 B" to 80L, "+5 B" to 400L, "+25 B" to 2000L)),
            pw, status,
            row(button("Cancel", 0xFF424242.toInt()) { dlg.dismiss() }, ok)
        ))
    }

    fun withdraw() {
        val amt = AmountEntry()
        val pw = field("Password", password = true)
        val status = text("", 14f, 0xFFFF8A80.toInt(), center = true)
        lateinit var dlg: Dialog
        val all = button("All", 0xFF6A1B9A.toInt()) { amt.set(wallet.cashableTokens) }.apply { textSize = 14f }
        val ok = button("Cash out", 0xFFC62828.toInt()) { v ->
            val tokens = amt.value()
            if (tokens <= 0) { status.text = "Enter an amount."; return@button }
            if (tokens > wallet.balance) { status.text = "You only have ${Money.describe(wallet.balance)}."; return@button }
            if (tokens < Money.TOKENS_PER_MACK) { status.text = "Cash-outs are whole macks — enter at least 10 tokens."; return@button }
            v.isEnabled = false; status.setTextColor(gold); status.text = "Verifying…"
            val chars = readPw(pw)
            background({ wallet.withdraw(chars, tokens).also { chars.fill('\u0000') } }) { r ->
                if (r is AuthResult.Ok) {
                    dlg.dismiss()
                    ticket(wallet.lastCashOut)
                } else {
                    v.isEnabled = true; status.setTextColor(0xFFFF8A80.toInt()); status.text = authMessage(r); pw.setText("")
                }
            }
        }
        amt.set(wallet.cashableTokens)
        dlg = show(vbox(
            title("CASH OUT"),
            text("Balance: %,d tokens  (%s)".format(wallet.balance, Money.describe(wallet.balance)), 15f, gold, bold = true, center = true),
            text("Cash-outs are paid in whole macks: the amount is rounded down to the nearest 10 tokens and any odd tokens stay on your credit.", 13f, cream, center = true),
            amt.view(listOf("+1 M" to 10L, "+1 B" to 80L, "+10 B" to 800L), extra = all),
            pw, status,
            row(button("Cancel", 0xFF424242.toInt()) { dlg.dismiss() }, ok)
        ))
    }

    private fun ticket(tokens: Long) {
        val df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        val paper = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { setColor(0xFFFFFDF0.toInt()); cornerRadius = dp(6).toFloat() }
            setPadding(dp(16), dp(12), dp(16), dp(12))
            fun line(s: String, size: Float = 15f, bold: Boolean = false) = addView(TextView(act).apply {
                text = s; textSize = size; setTextColor(0xFF1A1A1A.toInt()); gravity = Gravity.CENTER
                typeface = if (bold) Typeface.MONOSPACE.let { Typeface.create(it, Typeface.BOLD) } else Typeface.MONOSPACE
            })
            line("NEON REAPER", 18f, true)
            line("CASH-OUT TICKET #${wallet.ticketNo}")
            line(df.format(Date()))
            line("------------------------------")
            line("%,d TOKENS".format(tokens), 24f, true)
            line(Money.describe(tokens))
            line("------------------------------")
            line("Remaining credit: %,d tokens".format(wallet.balance), 13f)
        }
        lateinit var dlg: Dialog
        dlg = show(vbox(title("TICKET PRINTED"), paper, button("Done", 0xFF2E7D32.toInt()) { dlg.dismiss() }))
    }

    fun info(stake: Int) {
        val chars = Characters(dp(64), dp(64))
        val art = Array(ReaperConfig.SYMBOL_COUNT) { chars.body(it) }
        val list = vbox(
            title("PAYTABLE & RULES"),
            text("Wins pay left to right on any of the 20 lines. Amounts below are in tokens for your current stake of $stake token(s).", 14f)
        )
        for (s in 0 until ReaperConfig.SYMBOL_COUNT) {
            val p = ReaperConfig.PAYS[s]
            val name = ReaperConfig.SYMBOL_NAMES[s].uppercase() + if (s == ReaperConfig.WILD) " — substitutes for every symbol" else ""
            fun v(m: Int): String { val x = m.toLong() * stake; return if (x > ReaperConfig.MAX_WIN_TOKENS) "${ReaperConfig.MAX_WIN_TOKENS} (max)" else "$x" }
            val desc = "$name\n5 in a row: ${v(p[2])}    4: ${v(p[1])}    3: ${v(p[0])} tokens"
            val nl = desc.indexOf('\n')
            val styled = android.text.SpannableString(desc).apply {
                setSpan(android.text.style.ForegroundColorSpan(gold), 0, nl, 0)
                setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, nl, 0)
            }
            val r = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(act).apply { setImageBitmap(art[s]) }, LinearLayout.LayoutParams(dp(56), dp(56)))
                addView(text(styled, 14f, cream).apply { setPadding(dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            list.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, dp(4), 0, dp(4)) })
        }
        list.addView(text(
            "MINI FEATURES (about 1 spin in 80)\n" +
            "A character floats out in front of the reels and changes that spin:\n" +
            "• Reaper's Touch — the Grim Reaper slashes 1–3 middle reels completely WILD.\n" +
            "• Raven's Flock — the raven drops 3–6 WILDS onto the reels.\n" +
            "• Pumpkin's Treats — the jack-o'-lantern spits 3–6 soul coins onto the reels; every coin pays its token value.\n" +
            "• Skull's Curse — the skull adds 2 WILDS and multiplies the spin's line wins ×2, ×3 or ×5.\n\n" +
            "SPINNING\n" +
            "Tap SPIN for one spin. HOLD the SPIN button for rapid spins until you let go. Every win pauses to show the amount.\n\n" +
            "RELICS & THE SOUL VAULT\n" +
            "About one spin in 28 reveals a relic (Scythe, Hourglass, Skull Candle, Raven Feather, Cursed Tome). Collect all five to open the Soul Vault: " +
            "5 soul orbs start in a 5×4 vault and you get 3 respins — press SPIN for each one. Every orb that lands sticks and resets the respins to 3. " +
            "Orbs pay 1×–25× the bonus bet; MINI ${ReaperConfig.MINI}×, MINOR ${ReaperConfig.MINOR}×, MAJOR ${ReaperConfig.MAJOR}× jackpot orbs; " +
            "the Reaper orb harvests the value of every soul on the board; fill all 20 niches for the GRAND (${ReaperConfig.GRAND}×). " +
            "The bonus bet is your average stake while collecting the relics (now: ${wallet.averageStake}). Relics are kept between sessions.\n\n" +
            "MONEY RULES\n" +
            "• Stake 1–40 tokens (10 tokens = 1 mack, 80 tokens = 1 book).\n" +
            "• Every win pays exact tokens. Rounding down to a whole mack only happens when you cash out (odd tokens stay on the machine).\n" +
            "• Maximum win: 25 books (2,000 tokens) per spin and per Soul Vault.\n" +
            "• Return to player: 87% at every stake — over time the machine keeps 13% of every token wagered. About 1 spin in 3.4 is a winner.\n" +
            "• Tap the reels to slam-stop; tap during the Soul Vault to fast-forward.", 14f))
        lateinit var dlg: Dialog
        list.addView(button("Close") { dlg.dismiss() })
        dlg = show(list, widthFrac = 0.7f)
        dlg.setOnDismissListener { chars.recycle(); onChanged() }
    }

    fun stats() {
        val df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val rtp = if (wallet.totalWagered > 0) "%.1f%%".format(wallet.totalWon * 100.0 / wallet.totalWagered) else "—"
        val body = StringBuilder()
            .append("Balance:            %,d tokens (%s)\n".format(wallet.balance, Money.describe(wallet.balance)))
            .append("Total deposited:    %,d tokens\n".format(wallet.totalDeposited))
            .append("Total withdrawn:    %,d tokens\n".format(wallet.totalWithdrawn))
            .append("Total wagered:      %,d tokens\n".format(wallet.totalWagered))
            .append("Total won:          %,d tokens\n".format(wallet.totalWon))
            .append("Your actual RTP:    $rtp (theory 87.0%)\n")
            .append("Spins played:       %,d\n".format(wallet.spins))
            .append("Reaper's Touch:     %,d\n".format(wallet.features))
            .append("Soul Vaults:        %,d (paid %,d tokens)\n".format(wallet.bonuses, wallet.bonusWon))
            .append("Relics collected:   ${wallet.relics} / 5\n")
            .append("Biggest win:        %,d tokens".format(wallet.biggestWin))
        val ledger = StringBuilder()
        if (wallet.ledger.isEmpty()) ledger.append("No deposits or withdrawals yet.")
        for (e in wallet.ledger.asReversed().take(30)) {
            ledger.append("${df.format(Date(e.time))}  ${e.type}")
            if (e.tokens != 0L) ledger.append("  %,d T  → bal %,d T".format(e.tokens, e.balanceAfter))
            ledger.append('\n')
        }
        lateinit var dlg: Dialog
        dlg = show(vbox(
            title("MACHINE STATS"),
            text(body, 14f).apply { typeface = Typeface.MONOSPACE },
            text("HISTORY", 16f, gold, bold = true),
            text(ledger.toString().trimEnd(), 13f).apply { typeface = Typeface.MONOSPACE },
            row(
                button("Change password", 0xFF4527A0.toInt()) { dlg.dismiss(); changePassword() },
                button("Reset machine", 0xFF5D1010.toInt()) { dlg.dismiss(); reset() }
            ),
            button("Close") { dlg.dismiss() }
        ), widthFrac = 0.7f)
    }

    fun changePassword() {
        val old = field("Current password", password = true)
        val n1 = field("New password (min 4)", password = true)
        val n2 = field("Confirm new password", password = true)
        val status = text("", 14f, 0xFFFF8A80.toInt(), center = true)
        lateinit var dlg: Dialog
        val ok = button("Change", 0xFF2E7D32.toInt()) { v ->
            if (n1.text.length < 4) { status.text = "New password must be at least 4 characters."; return@button }
            if (n1.text.toString() != n2.text.toString()) { status.text = "New passwords don't match."; return@button }
            v.isEnabled = false; status.setTextColor(gold); status.text = "Verifying…"
            val o = readPw(old); val n = readPw(n1)
            background({ wallet.changePassword(o, n).also { o.fill('\u0000'); n.fill('\u0000') } }) { r ->
                if (r is AuthResult.Ok) { dlg.dismiss(); toastBanner("Password changed") }
                else { v.isEnabled = true; status.setTextColor(0xFFFF8A80.toInt()); status.text = authMessage(r); old.setText("") }
            }
        }
        dlg = show(vbox(title("CHANGE PASSWORD"), old, n1, n2, status,
            row(button("Cancel", 0xFF424242.toInt()) { dlg.dismiss() }, ok)))
    }

    fun reset() {
        val pw = field("Password", password = true)
        val status = text("", 14f, 0xFFFF8A80.toInt(), center = true)
        lateinit var dlg: Dialog
        val ok = button("Erase everything", 0xFFB71C1C.toInt()) { v ->
            v.isEnabled = false; status.setTextColor(gold); status.text = "Verifying…"
            val chars = readPw(pw)
            background({ wallet.factoryReset(chars).also { chars.fill('\u0000') } }) { r ->
                if (r is AuthResult.Ok) { dlg.dismiss(); setupPassword() }
                else { v.isEnabled = true; status.setTextColor(0xFFFF8A80.toInt()); status.text = authMessage(r); pw.setText("") }
            }
        }
        dlg = show(vbox(title("RESET MACHINE"),
            text("This permanently erases the balance (%s), relics, statistics and password. Cash out first if you want to keep your tokens.".format(Money.describe(wallet.balance)), 15f),
            pw, status, row(button("Cancel", 0xFF424242.toInt()) { dlg.dismiss() }, ok)))
    }

    fun tampered() {
        lateinit var dlg: Dialog
        dlg = show(vbox(
            title("TILT — MACHINE LOCKED"),
            text("The machine's credit records failed their security check (they were modified outside the game or are corrupted). For your protection the balance can't be trusted and play is disabled.\n\nResetting erases the records and lets you set a new password.", 15f),
            button("Reset machine", 0xFFB71C1C.toInt()) {
                wallet.factoryReset(null); dlg.dismiss(); setupPassword()
            }
        ), cancelable = false)
    }

    fun autoPicker(onPicked: (Int) -> Unit) {
        lateinit var dlg: Dialog
        fun pick(n: Int) = button(if (n < 0) "Until stopped" else "$n spins", 0xFF1565C0.toInt()) { dlg.dismiss(); onPicked(n) }
        dlg = show(vbox(
            title("AUTOPLAY"),
            text("Autoplay stops when you tap SPIN/STOP or credit runs out. It pauses for the Soul Vault.", 14f, center = true),
            row(pick(10), pick(25), pick(50)), row(pick(100), pick(-1)),
            button("Cancel", 0xFF424242.toInt()) { dlg.dismiss() }
        ), widthFrac = 0.5f)
    }

    fun message(t: String, body: String) {
        lateinit var dlg: Dialog
        dlg = show(vbox(title(t), text(body, 15f), button("OK", 0xFF2E7D32.toInt()) { dlg.dismiss() }), widthFrac = 0.5f)
    }

    var toastBanner: (String) -> Unit = {}
}

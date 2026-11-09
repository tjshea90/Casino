package com.tjshea.neonreaper.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import com.tjshea.neonreaper.data.Achievements
import com.tjshea.neonreaper.data.AuthResult
import com.tjshea.neonreaper.data.Feature
import com.tjshea.neonreaper.data.LedgerEntry
import com.tjshea.neonreaper.data.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Launches the real activity (catches startup crashes), renders EVERY dialog to reaper/build/screens/d_<dialog>_<screen>.png
 * on a 10" tablet in landscape (land) and portrait (port) and on a small phone in landscape (phone_land) and portrait
 * (phone_port), each composited over the real game frame, plus d_<dialog>_<screen>_full.png with the whole scrolled content
 * when it does not fit. It fails when anything overflows its parent, and it drives the dialogs' behaviour (autoplay plan,
 * buy confirmation, settings, reality check, password flows).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1280dp-h800dp-land-mdpi")
class ActivityTest {
    private val pw = "abcd".toCharArray()
    private val out = File("build/screens").apply { mkdirs() }

    @Test fun launchAndDialogs() = tour("land")
    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi") fun portrait() = tour("port")
    @Test @Config(qualifiers = "w640dp-h360dp-land-xhdpi") fun phoneLandscape() = tour("phone_land")
    @Test @Config(qualifiers = "w360dp-h740dp-port-xhdpi") fun phonePortrait() = tour("phone_port")

    // ------------------------------------------------------------------ every dialog, one screen size
    private fun tour(tag: String) {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val shots = Shots(tag, act)
        shots.snap("setup")                                   // first launch: the password dialog is already open
        val w = act.wallet
        assertTrue(w.setInitialPassword(pw))
        val dialogs = Dialogs(act, w) {}
        dialogs.stats(); shots.snap("stats_empty")            // a brand-new machine

        seedPlay(w)
        act.openDeposit(); shots.snap("deposit")
        act.openWithdraw(); shots.snap("withdraw")
        dialogs.changePassword(); shots.snap("changepw")
        dialogs.reset(); shots.snap("reset")
        dialogs.tampered(); shots.snap("tampered")
        dialogs.message("Machine ready", "Your password now protects every deposit and cash-out.\n\nTap DEPOSIT to add tokens, then SPIN. " +
            "Collect all five relics to open the Soul Vault, and land three coffins to awaken THE SÉANCE.\n\nThere is no password recovery — keep it safe.")
        shots.snap("message")

        act.openInfo(); shots.snap("info_pays", dismiss = false)
        shots.click("Features"); shots.snap("info_features", dismiss = false)
        shots.click("Rules"); shots.snap("info_rules")
        dialogs.info(40); shots.snap("info_pays_stake40")       // capped pays show MAX

        act.openStats(); shots.snap("stats")
        poke(w, "achievements", 0b0010_0000_0110_1101); poke(w, "totalWagered", 18_400L)
        act.openRank(); shots.snap("rank")
        poke(w, "achievements", (1 shl Achievements.ALL.size) - 1); poke(w, "totalWagered", 3_000_000L)
        act.openRank(); shots.snap("rank_top")
        poke(w, "achievements", 0); poke(w, "totalWagered", 120L)
        act.openRank(); shots.snap("rank_new")

        act.openSettings(); shots.snap("settings")
        act.openAutoPicker {}; shots.snap("autoplay", dismiss = false)
        shots.click("Until stopped"); shots.click("25×"); shots.click("25 %"); shots.snap("autoplay_limits")

        poke(w, "balance", 5_000L)
        act.openBuy(w.stake) {}; shots.snap("buy", dismiss = false)
        shots.click("Buy for"); shots.snap("buy_confirm")
        poke(w, "balance", 200L)
        act.openBuy(w.stake) {}; shots.snap("buy_low")

        poke(w, "sessionWagered0", 0L); poke(w, "sessionWon0", 0L); poke(w, "totalWagered", 5_200L); poke(w, "totalWon", 3_950L)
        act.openReality({}, {}); shots.snap("reality_loss")
        poke(w, "totalWon", 6_100L)
        act.openReality({}, {}); shots.snap("reality_win")

        act.openWithdraw(); val wd = latest()                  // the whole cash-out flow ends in a printed ticket
        field(wd, "Password").setText(String(pw)); click(wd, "Cash out"); waitUntil { !wd.isShowing }
        shots.snap("ticket")

        shots.finish()
    }

    // ------------------------------------------------------------------ behaviour
    @Test fun autoplayPlan() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val w = act.wallet
        w.setInitialPassword(pw); w.deposit(pw, 2_000)
        var plan: AutoPlan? = null
        Dialogs(act, w) {}.autoPicker { plan = it }
        val dlg = latest()
        assertTrue("Start is offered", button(dlg, "Start autoplay").isEnabled)
        click(dlg, "Until stopped"); click(dlg, "25×"); click(dlg, "25 %")
        click(dlg, "Start autoplay")
        val p = plan ?: error("no plan")
        assertEquals(-1, p.spins); assertEquals(25, p.stopOnWinX); assertEquals(w.balance * 25 / 100, p.lossLimit)
        assertFalse("the picker closes", dlg.isShowing)

        Dialogs(act, w) {}.autoPicker { plan = it }
        val d2 = latest(); click(d2, "50", exact = true)
        click(d2, "Start autoplay")
        assertEquals(50, plan!!.spins); assertEquals(0, plan!!.stopOnWinX); assertEquals(0L, plan!!.lossLimit)

        plan = null
        Dialogs(act, w) {}.autoPicker { plan = it }
        val d3 = latest(); click(d3, "Cancel")
        assertFalse(d3.isShowing); assertNull("Cancel starts nothing", plan)
    }

    @Test fun buyNeedsConfirmation() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val w = act.wallet
        w.setInitialPassword(pw); w.deposit(pw, 2_000)
        val chosen = ArrayList<Feature>()
        val d = Dialogs(act, w) {}
        d.buyFeature(10) { chosen += it }
        var dlg = latest()
        click(dlg, "Buy for 310")           // first tap only selects the Séance
        assertTrue("one tap never buys", chosen.isEmpty()); assertTrue(dlg.isShowing)
        click(dlg, "Back")                  // and can be backed out of
        assertTrue(chosen.isEmpty()); assertTrue(dlg.isShowing)
        click(dlg, "Buy for 310")
        click(dlg, "Confirm")
        assertEquals(listOf(Feature.SEANCE), chosen); assertFalse(dlg.isShowing)

        chosen.clear()
        d.buyFeature(10) { chosen += it }; dlg = latest()
        val buys = views(dlg) { it is Button && it.text.toString().startsWith("Buy for") }.map { it as Button }
        assertEquals("one buy button per card", 2, buys.size)
        buys[1].performClick(); ShadowLooper.idleMainLooper()                    // the second card is the Soul Vault
        click(dlg, "Confirm"); assertEquals(listOf(Feature.VAULT), chosen)

        poke(w, "balance", 200L)            // not enough credit: nothing can be selected
        chosen.clear()
        d.buyFeature(10) { chosen += it }; dlg = latest()
        for (b in views(dlg) { it is Button && it.text.toString().startsWith("Buy for") }) assertFalse("disabled when credit is too low", b.isEnabled)
        assertTrue(allText(dlg).contains("more tokens needed"))
        click(dlg, "Cancel"); assertTrue(chosen.isEmpty())
    }

    @Test fun settingsApplyAndLinks() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val w = act.wallet
        w.setInitialPassword(pw)
        var changes = 0
        Dialogs(act, w) {}.settings { changes++ }
        val dlg = latest()
        click(dlg, "Low"); assertEquals(1, w.quality)
        click(dlg, "High"); assertEquals(3, w.quality)
        click(dlg, "Auto"); assertEquals(0, w.quality)
        click(dlg, "On", exact = true); assertTrue(w.reduceFlash)
        click(dlg, "Off", exact = true); assertFalse("the first Off is the reduce-flashing one", w.reduceFlash)
        click(dlg, "15 min"); assertEquals(15, w.realityMinutes)
        click(dlg, "60 min"); assertEquals(60, w.realityMinutes)
        assertTrue("every change is reported", changes >= 6)
        val bars = views(dlg) { it is SeekBar }.map { it as SeekBar }
        assertEquals(2, bars.size)
        assertEquals(20, bars[0].max)
        bars[0].progress = 11; assertEquals(55, w.musicVolume)
        bars[1].progress = 0; assertEquals(0, w.sfxVolume)
        bars[1].progress = 20; assertEquals(100, w.sfxVolume)

        // the three links open on top of the settings sheet and return to it
        click(dlg, "Soul Rank"); val rank = latest()
        assertTrue(allText(rank).contains("SOUL RANK")); assertTrue(allText(rank).contains("Reaper's Ledger"))
        click(rank, "Close"); assertFalse(rank.isShowing); assertTrue("settings stays underneath", dlg.isShowing)
        click(dlg, "Statistics"); val st = latest(); assertTrue(allText(st).contains("MACHINE STATS")); click(st, "Close")
        click(dlg, "How to play"); val info = latest(); assertTrue(allText(info).contains("PAYTABLE")); click(info, "Close")
        click(dlg, "Done"); assertFalse(dlg.isShowing)
    }

    @Test fun realityCheckIsModal() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val w = act.wallet
        w.setInitialPassword(pw)
        var cont = 0; var stop = 0
        val d = Dialogs(act, w) {}
        d.reality({ cont++ }, { stop++ })
        var dlg = latest()
        dlg.onBackPressed(); assertTrue("cannot be dismissed with back", dlg.isShowing)
        assertTrue(allText(dlg).contains("Net result"))
        click(dlg, "Take a break"); assertEquals(1, stop); assertEquals(0, cont); assertFalse(dlg.isShowing)
        d.reality({ cont++ }, { stop++ }); dlg = latest()
        click(dlg, "Keep playing"); assertEquals(1, cont); assertEquals(1, stop); assertFalse(dlg.isShowing)
    }

    @Test fun passwordFlowsStillWork() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val w = act.wallet
        val setup = latest()
        assertTrue(allText(setup).contains("WELCOME TO NEON REAPER"))
        setup.onBackPressed(); assertTrue("the first-run dialog cannot be skipped", setup.isShowing)
        field(setup, "At least 4").setText("abc"); field(setup, "Repeat").setText("abc")
        click(setup, "Set password"); assertTrue(allText(setup).contains("at least 4 characters"))
        field(setup, "At least 4").setText("abcd"); field(setup, "Repeat").setText("abce")
        click(setup, "Set password"); assertTrue(allText(setup).contains("don't match"))
        field(setup, "Repeat").setText("abcd")
        click(setup, "Set password")
        waitUntil { !setup.isShowing }
        assertTrue(w.hasPassword)
        val ready = latest(); assertTrue(allText(ready).contains("MACHINE READY")); click(ready, "OK")

        // deposit: the amount fields add up, a wrong password is refused, the right one pays in
        var banner = ""
        val d = Dialogs(act, w) {}; d.toastBanner = { banner = it }
        d.deposit(); val dep = latest()
        val amounts = views(dep) { it is EditText && it.hint?.toString() == "0" }.map { it as EditText }
        assertEquals(3, amounts.size)
        amounts[0].setText("1"); amounts[1].setText("2"); amounts[2].setText("3")
        assertTrue(allText(dep).contains("103 tokens"))
        field(dep, "Password").setText("nope"); click(dep, "Deposit")
        waitUntil { allText(dep).contains("Wrong password") }
        assertEquals(0L, w.balance); assertTrue(dep.isShowing)
        field(dep, "Password").setText("abcd"); click(dep, "Deposit")
        waitUntil { !dep.isShowing }
        assertEquals(103L, w.balance); assertTrue(banner.startsWith("Deposited"))

        // cash out: rounds down to whole macks and prints a ticket
        d.withdraw(); val wd = latest()
        field(wd, "Password").setText("abcd")
        click(wd, "Cash out")
        waitUntil { !wd.isShowing }
        assertEquals(100L, w.lastCashOut); assertEquals(3L, w.balance)
        val ticket = latest(); assertTrue(allText(ticket).contains("TICKET PRINTED")); assertTrue(allText(ticket).contains("100 TOKENS")); click(ticket, "Done")

        // change password
        d.changePassword(); val cp = latest()
        field(cp, "Current password").setText("abcd"); field(cp, "At least 4").setText("wxyz"); field(cp, "Repeat the new").setText("wxyz")
        click(cp, "Change"); waitUntil { !cp.isShowing }
        assertTrue(w.authenticate("wxyz".toCharArray()) is AuthResult.Ok)

        // reset: a wrong password is refused, the right one wipes the machine and asks for a new password
        d.reset(); val rs = latest()
        field(rs, "Password").setText("bad"); click(rs, "Erase everything"); waitUntil { allText(rs).contains("Wrong password") }
        assertTrue(w.hasPassword)
        field(rs, "Password").setText("wxyz"); click(rs, "Erase everything"); waitUntil { !rs.isShowing }
        assertFalse(w.hasPassword)
        assertTrue(allText(latest()).contains("WELCOME TO NEON REAPER"))
    }

    @Test fun tiltResetsMachine() {
        val act = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val w = act.wallet
        w.setInitialPassword(pw)
        poke(w, "status", Wallet.LoadStatus.TAMPERED)           // the dialog is only ever shown for a tampered wallet
        Dialogs(act, w) {}.tampered()
        val dlg = latest()
        dlg.onBackPressed(); assertTrue("cannot be dismissed", dlg.isShowing)
        click(dlg, "Reset machine")
        assertFalse(w.hasPassword)
        assertTrue(allText(latest()).contains("WELCOME TO NEON REAPER"))
    }

    // ------------------------------------------------------------------ helpers
    private fun latest(): Dialog { ShadowLooper.idleMainLooper(); return ShadowDialog.getLatestDialog() }

    /** Plays real spins so every statistic, the ledger and the achievements have believable values. */
    private fun seedPlay(w: Wallet) {
        w.deposit(pw, 6_000)
        var n = 0
        while (n < 500) {
            if (w.bonusPending) { w.markVaultSeen(); w.markSeanceSeen() }
            if (w.playSpin(if (n % 5 == 0) 5 else 10) == null) break
            n++
        }
        w.withdraw(pw, 300)
        w.deposit(pw, 80)
        w.ledger += LedgerEntry(System.currentTimeMillis() - 86_400_000L * 3, "PASSWORD CHANGED", 0, w.balance)
    }

    /** Sets a private-set field of the wallet (test-only state such as a low balance or a lifetime wager). */
    private fun poke(w: Wallet, name: String, value: Any) {
        try {
            val f = Wallet::class.java.getDeclaredField(name); f.isAccessible = true
            when (value) { is Long -> f.setLong(w, value); is Int -> f.setInt(w, value); else -> f.set(w, value) }
        } catch (e: NoSuchFieldException) { println("poke: no field $name (wallet changed?)") }
    }

    private fun views(root: View, pred: (View) -> Boolean = { true }): List<View> {
        val res = ArrayList<View>(); val st = ArrayDeque<View>(); st.addLast(root)
        while (st.isNotEmpty()) {
            val v = st.removeLast(); if (pred(v)) res += v
            if (v is ViewGroup) for (i in v.childCount - 1 downTo 0) st.addLast(v.getChildAt(i))
        }
        return res
    }
    private fun views(dlg: Dialog, pred: (View) -> Boolean = { true }) = views(dlg.window!!.decorView, pred)
    private fun allText(dlg: Dialog): String = views(dlg) { it is TextView }.joinToString(" | ") { (it as TextView).text.toString() }

    private fun matches(t: String, label: String, exact: Boolean) = if (exact) t == label else t.startsWith(label, ignoreCase = true)
    private fun labelled(dlg: Dialog, label: String, exact: Boolean): List<TextView> =
        views(dlg) { it is TextView && it.visibility == View.VISIBLE && matches(it.text.toString(), label, exact) }.map { it as TextView }
    private fun button(dlg: Dialog, label: String, exact: Boolean = false): Button =
        labelled(dlg, label, exact).firstOrNull { it is Button } as? Button ?: error("no button '$label' in: ${allText(dlg)}")

    /** The control a tap on [v] reaches: the view itself or the nearest clickable parent, but never the sheet (it only swallows taps). */
    private fun control(v: View): View? {
        var x: View? = v
        while (x != null && !x.isClickable) x = x.parent as? View
        return x?.takeIf { it !is SheetLayout }
    }

    /** Taps the control showing [label] (a button, a chip, or a link row that contains the text). */
    private fun click(dlg: Dialog, label: String, exact: Boolean = false) {
        val target = labelled(dlg, label, exact).firstNotNullOfOrNull { control(it) } ?: error("nothing clickable shows '$label' in: ${allText(dlg)}")
        assertTrue("'$label' is enabled", target.isEnabled)
        target.performClick(); ShadowLooper.idleMainLooper()
    }

    private fun field(dlg: Dialog, hintStart: String): EditText =
        views(dlg) { it is EditText && (it.hint?.toString() ?: "").startsWith(hintStart) }.firstOrNull() as? EditText ?: error("no field '$hintStart'")

    private fun waitUntil(ms: Long = 10_000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { ShadowLooper.idleMainLooper(); if (cond()) return; Thread.sleep(25) }
        fail("timed out waiting")
    }

    /** Renders the latest dialog over the game frame and checks the layout. */
    private inner class Shots(private val tag: String, private val act: Activity) {
        private val problems = ArrayList<String>()
        private val dm = RuntimeEnvironment.getApplication().resources.displayMetrics
        private val w = dm.widthPixels
        private val h = dm.heightPixels

        private fun layout(root: View) {
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, w, h)
        }

        fun click(label: String) { this@ActivityTest.click(latest(), label) }

        fun snap(name: String, dismiss: Boolean = true) {
            val dlg = latest()
            val root = dlg.window!!.decorView
            layout(root)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(0xFF000000.toInt())
            try { layout(act.window.decorView); act.window.decorView.draw(c) } catch (_: Throwable) { /* the game frame is decoration only */ }
            root.draw(c)
            File(out, "d_${name}_$tag.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            check(name, dlg)
            full(name, dlg)
            if (dismiss) dlg.dismiss()
        }

        /** The whole scrolled content in one tall picture, when it does not fit on screen. */
        private fun full(name: String, dlg: Dialog) {
            val sv = views(dlg) { it is ScrollView }.firstOrNull() as? ScrollView ?: return
            val body = sv.getChildAt(0) ?: return
            if (sv.width <= 0) return
            body.measure(View.MeasureSpec.makeMeasureSpec(sv.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            val bh = body.measuredHeight
            if (bh > sv.height + 2) {
                body.layout(0, 0, sv.width, bh)
                val bmp = Bitmap.createBitmap(sv.width, bh, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp); c.drawColor(0xFF101216.toInt()); body.draw(c)
                File(out, "d_${name}_${tag}_full.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                overflow(body, "$name/full")
            }
            layout(dlg.window!!.decorView)
        }

        private fun check(name: String, dlg: Dialog) {
            val panel = views(dlg) { it is SheetLayout }.firstOrNull() ?: run { problems += "$name: no sheet"; return }
            val r = Rect(); panel.getHitRect(r)
            if (r.left < 0 || r.top < 0 || r.right > w || r.bottom > h) problems += "$name: sheet $r leaves the ${w}x$h screen"
            overflow(dlg.window!!.decorView, name)
            val minTouch = (44 * dm.density).toInt() - 2
            for (b in views(dlg) { it is Button && it.visibility == View.VISIBLE })
                if (b.measuredHeight < minTouch) problems += "$name: touch target '${(b as Button).text}' is only ${b.measuredHeight}px tall"
        }

        private fun overflow(v: View, name: String) {
            if (v is TextView && v.maxLines == 1 && v.layout != null && v.width > 0) {
                val avail = v.width - v.compoundPaddingLeft - v.compoundPaddingRight
                if (v.layout.getLineWidth(0) > avail + 1) problems += "$name: single-line text '${v.text}' needs ${v.layout.getLineWidth(0)}px of $avail"
            }
            if (v !is ViewGroup) return
            for (i in 0 until v.childCount) {
                val c = v.getChildAt(i); if (c.visibility == View.GONE) continue
                val horiz = c.left < -1 || c.right > v.width + 1
                val vert = v !is ScrollView && (c.top < -1 || c.bottom > v.height + 1)
                if (horiz || vert) problems += "$name: ${c.javaClass.simpleName} at ${c.left},${c.top},${c.right},${c.bottom} overflows ${v.javaClass.simpleName} ${v.width}x${v.height}"
                overflow(c, name)
            }
        }

        fun finish() {
            File(out, "d_problems_$tag.txt").writeText(problems.joinToString("\n"))
            if (problems.isNotEmpty()) fail("layout problems on $tag:\n" + problems.joinToString("\n"))
        }
    }
}

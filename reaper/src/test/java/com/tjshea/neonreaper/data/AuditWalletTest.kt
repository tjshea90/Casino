package com.tjshea.neonreaper.data

import com.tjshea.neonreaper.engine.Money
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.RelicMeter
import com.tjshea.neonreaper.engine.Round
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Random

/**
 * Independent audit of the wallet: crash safety (kill at every stage), exactly-once payment of paid-but-unseen bonuses,
 * feature-buy guards, invalid stakes, cash-out rounding, and the integrity ("TILT") path against file tampering.
 * See docs/AUDIT_v3.md section 3.
 *
 * To make a spin trigger a Seance / Soul Vault on demand, the wallet's private SecureRandom is replaced (reflection) by a
 * deterministic generator, and a seed is searched with the very same Round.play the wallet runs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuditWalletTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val file get() = File(ctx.filesDir, "wallet.dat")
    private fun pw(s: String) = s.toCharArray()

    private fun fresh(balance: Long = 1_000_000): Wallet {
        file.delete(); File(ctx.filesDir, "wallet.tmp").deleteRecursively()
        val w = Wallet(ctx)
        assertEquals(Wallet.LoadStatus.NEW, w.load())
        assertTrue(w.setInitialPassword(pw("grim")))
        if (balance > 0) assertTrue(w.deposit(pw("grim"), balance) is AuthResult.Ok)
        return w
    }

    /** The app is killed and restarted: a brand-new Wallet object reads whatever is on disk. */
    private fun restart(): Wallet = Wallet(ctx).also { assertEquals(Wallet.LoadStatus.OK, it.load()) }

    /** java.util.Random behind a SecureRandom so the wallet can be driven deterministically (all overrides the engine uses). */
    private class DetRandom(seed: Long) : SecureRandom() {
        private val r = Random(seed)
        override fun nextDouble() = r.nextDouble()
        override fun nextInt(bound: Int) = r.nextInt(bound)
        override fun nextInt() = r.nextInt()
        override fun nextLong() = r.nextLong()
        override fun nextBoolean() = r.nextBoolean()
        override fun nextBytes(bytes: ByteArray) = r.nextBytes(bytes)
    }

    private fun inject(w: Wallet, seed: Long) {
        val f = Wallet::class.java.getDeclaredField("rng"); f.isAccessible = true; f.set(w, DetRandom(seed))
    }

    /** Edits the saved wallet and signs the result with the real key (what a legitimate older version would have written). */
    private fun rewrite(edit: (JSONObject) -> Unit) {
        val json = JSONObject(file.readText().substringBeforeLast('\n'))
        edit(json)
        val text = json.toString()
        file.writeText(text + "\n" + Security.b64(Security.sign(ctx, text.toByteArray())))
    }

    /** First seed whose first paid spin at [stake] (starting from relics/stakeSum) satisfies [want]. */
    private fun findSeed(stake: Int, relics: Int = 0, stakeSum: Long = 0, want: (com.tjshea.neonreaper.engine.RoundResult) -> Boolean): Long {
        for (seed in 1L..2_000_000L) if (want(Round.play(Random(seed), stake, RelicMeter(relics, stakeSum)))) return seed
        error("no seed found")
    }

    private fun sameSeance(a: com.tjshea.neonreaper.engine.SeanceResult, b: com.tjshea.neonreaper.engine.SeanceResult) {
        assertEquals(a.seed, b.seed); assertEquals(a.bet, b.bet); assertEquals(a.coffins, b.coffins)
        assertEquals(a.totalMult, b.totalMult); assertEquals(a.payTokens, b.payTokens); assertEquals(a.spins.size, b.spins.size)
        for (i in a.spins.indices) {
            assertTrue(a.spins[i].outcome.stops.contentEquals(b.spins[i].outcome.stops))
            assertEquals(a.spins[i].multiplier, b.spins[i].multiplier); assertEquals(a.spins[i].extraSpins, b.spins[i].extraSpins)
        }
    }

    private fun sameVault(a: com.tjshea.neonreaper.engine.VaultResult, b: com.tjshea.neonreaper.engine.VaultResult) {
        assertEquals(a.seed, b.seed); assertEquals(a.bet, b.bet); assertEquals(a.totalMult, b.totalMult); assertEquals(a.payTokens, b.payTokens)
        assertEquals(a.grand, b.grand); assertEquals(a.steps.size, b.steps.size)
        assertEquals(a.cells.map { it?.value }, b.cells.map { it?.value }); assertEquals(a.cells.map { it?.kind }, b.cells.map { it?.kind })
    }

    // ------------------------------------------------------------------------------------------------ kill at every stage

    @Test fun killAfterASeanceSpinReplaysIdenticallyAndPaysExactlyOnce() {
        val stake = 10
        val seed = findSeed(stake) { it.seance != null && it.vault == null }
        val w = fresh(); val before = w.balance
        inject(w, seed)
        val p = w.playSpin(stake) ?: error("refused")
        val s = p.seance ?: error("seed did not trigger")
        val credited = before - stake + p.outcome.payTokens + s.payTokens
        assertEquals(credited, w.balance)
        assertTrue(w.seanceUnseen && w.bonusPending)

        // kill #1 right after the spin was saved (the bonus has not been shown yet)
        val a = restart()
        assertEquals("credit was saved atomically with the debit and the seed", credited, a.balance)
        sameSeance(s, a.pendingSeance() ?: error("pending Seance lost"))
        assertNull("no new spin while a paid bonus is waiting", a.playSpin(stake))
        assertTrue(a.withdraw(pw("grim"), 100) is AuthResult.Failed)
        assertNull(a.buy(com.tjshea.neonreaper.data.Feature.SEANCE, stake))
        // looking at it any number of times never pays again
        repeat(50) { a.pendingSeance() }
        assertEquals(credited, a.balance)

        // kill #2 halfway through the replay (nothing was marked seen): identical again
        val b = restart()
        sameSeance(s, b.pendingSeance() ?: error("lost"))
        assertEquals(credited, b.balance)
        b.markSeanceSeen(); b.markSeanceSeen()        // idempotent
        assertFalse(b.bonusPending)
        assertEquals(credited, b.balance)

        // kill #3 after it was marked seen: nothing left to replay, credited once
        val c = restart()
        assertNull(c.pendingSeance()); assertEquals(credited, c.balance); assertEquals(1L, c.seances)
        assertEquals(s.payTokens, c.seanceWon)
        assertEquals(p.outcome.payTokens + s.payTokens, c.totalWon)
        assertNotNull(c.playSpin(stake))
    }

    @Test fun killAfterASoulVaultSpinReplaysIdenticallyAndPaysExactlyOnce() {
        val stake = 10
        val seed = findSeed(stake, relics = 4, stakeSum = 40) { it.vault != null && it.seance == null }
        fresh()
        rewrite { it.put("relics", 4); it.put("rSum", 40L) }
        val w0 = restart(); assertEquals(4, w0.relics)
        inject(w0, seed)
        val before = w0.balance
        val p = w0.playSpin(stake) ?: error("refused")
        val v = p.vault ?: error("seed did not trigger the vault")
        assertEquals(10, v.bet)                                       // floor((4*10 + 10) / 5)
        val credited = before - stake + p.outcome.payTokens + v.payTokens
        assertEquals(credited, w0.balance)
        assertEquals(0, w0.relics)

        val a = restart()
        assertEquals(credited, a.balance); assertEquals(0, a.relics)
        sameVault(v, a.pendingVault() ?: error("pending Vault lost"))
        assertNull(a.playSpin(stake)); assertTrue(a.withdraw(pw("grim"), 100) is AuthResult.Failed)
        a.markVaultSeen(); a.markVaultSeen()
        val c = restart()
        assertNull(c.pendingVault()); assertEquals(credited, c.balance); assertEquals(1L, c.bonuses)
        assertEquals(v.payTokens, c.bonusWon)
    }

    @Test fun aSpinThatUnlocksBothBonusesSurvivesAKillBetweenThem() {
        val stake = 5
        val seed = findSeed(stake, relics = 4, stakeSum = 20) { it.vault != null && it.seance != null }
        fresh(); rewrite { it.put("relics", 4); it.put("rSum", 20L) }
        val w = restart(); inject(w, seed)
        val before = w.balance
        val p = w.playSpin(stake) ?: error("refused")
        val v = p.vault ?: error("no vault"); val s = p.seance ?: error("no seance")
        val credited = before - stake + p.outcome.payTokens + v.payTokens + s.payTokens
        assertEquals(credited, w.balance)

        val a = restart()                                              // killed before either bonus was shown
        sameSeance(s, a.pendingSeance() ?: error("seance lost")); sameVault(v, a.pendingVault() ?: error("vault lost"))
        a.markSeanceSeen()                                             // the Seance was watched, the app dies during the Vault
        val b = restart()
        assertNull(b.pendingSeance()); sameVault(v, b.pendingVault() ?: error("vault lost"))
        assertTrue(b.bonusPending); assertNull(b.playSpin(stake))
        b.markVaultSeen()
        val c = restart()
        assertFalse(c.bonusPending); assertEquals(credited, c.balance)
    }

    @Test fun theBonusIsPaidForTheStakeThatTriggeredItWhateverStakeIsSelectedLater() {
        val stake = 25
        val seed = findSeed(stake) { it.seance != null }
        val w = fresh(); inject(w, seed)
        val p = w.playSpin(stake) ?: error("refused"); val s = p.seance!!
        assertEquals(stake, s.bet)
        w.stakeIndex = 0                                               // the player drops the stake to 1 before the replay
        val a = restart(); a.stakeIndex = 0
        val r = a.pendingSeance()!!
        assertEquals(stake, r.bet); assertEquals(r.totalMult * stake, r.rawTokens); assertEquals(s.payTokens, r.payTokens)
    }

    @Test fun aKillBetweenTmpWriteAndRenameNeverShowsAHalfSpin() {
        val w = fresh(); val saved = file.readText()
        // the temp file cannot be written (a directory is in the way): the spin throws before anything reaches wallet.dat
        File(ctx.filesDir, "wallet.tmp").mkdir()
        val seed = findSeed(10) { it.outcome.payTokens > 0 }
        inject(w, seed)
        var threw = false
        try { w.playSpin(10) } catch (e: IOException) { threw = true }
        assertTrue("the failure must surface, not be swallowed", threw)
        assertEquals("disk untouched: the spin never happened", saved, file.readText())
        val a = restart()
        assertEquals(1_000_000L, a.balance); assertEquals(0L, a.spins)
        File(ctx.filesDir, "wallet.tmp").deleteRecursively()
    }

    @Test fun aLeftoverTmpFileFromACrashedSaveIsIgnoredAndThenReplaced() {
        // the app died after writing wallet.tmp (even a "newer" one) but before the rename: only wallet.dat counts
        val w = fresh(); val before = w.balance
        val tmp = File(ctx.filesDir, "wallet.tmp")
        tmp.writeText(file.readText().replace("\"balance\":$before", "\"balance\":${before * 9}"))
        val a = restart()
        assertEquals("a stale tmp file must never be loaded", before, a.balance)
        assertTrue(a.deposit(pw("grim"), 10) is AuthResult.Ok)         // the next save replaces it
        assertFalse(tmp.exists())
        assertEquals(before + 10, restart().balance)
        tmp.writeText("garbage"); assertEquals(before + 10, restart().balance)
    }

    // ------------------------------------------------------------------------------------------------ feature buys

    @Test fun buysSurviveAKillAndCannotStack() {
        val w = fresh(); val price = w.buyPrice(Feature.SEANCE, 10)
        assertEquals(310L, price)
        val b1 = w.buy(Feature.SEANCE, 10) ?: error("refused")
        val after1 = 1_000_000L - price + b1.seance!!.payTokens
        assertEquals(after1, w.balance); assertEquals(price, w.totalWagered)
        assertNull("second buy while the first is unseen", w.buy(Feature.SEANCE, 10))
        assertNull(w.buy(Feature.VAULT, 10)); assertNull(w.playSpin(10))
        assertEquals(after1, w.balance)

        val a = restart()                                              // killed before the bought Seance was watched
        sameSeance(b1.seance!!, a.pendingSeance()!!)
        assertEquals(after1, a.balance); assertEquals(1L, a.buys)
        assertNull(a.buy(Feature.SEANCE, 10))
        a.markSeanceSeen()
        val b2 = a.buy(Feature.VAULT, 10) ?: error("refused after the first was seen")      // two buys in a row, one watched in between
        assertEquals(10, b2.vault!!.bet)
        val after2 = after1 - 310 + b2.vault!!.payTokens
        assertEquals(after2, a.balance); assertEquals(620L, a.totalWagered); assertEquals(2L, a.buys)
        val c = restart()
        sameVault(b2.vault!!, c.pendingVault()!!); assertEquals(after2, c.balance)
    }

    @Test fun aBuyNeverTouchesTheRelicMeter() {
        fresh(); rewrite { it.put("relics", 3); it.put("rSum", 90L) }
        val w = restart()
        assertEquals(3, w.relics)
        w.stakeIndex = ReaperConfig.STAKES.indexOf(10)
        val bet = w.projectedBonusBet
        assertEquals((90 + 2 * 10) / 5, bet)
        w.buy(Feature.VAULT, 10) ?: error("refused"); w.markVaultSeen()
        w.buy(Feature.SEANCE, 40) ?: error("refused"); w.markSeanceSeen()
        assertEquals("buys give no relic and spend none", 3, w.relics)
        assertEquals(bet, w.projectedBonusBet)
        val a = restart(); assertEquals(3, a.relics); assertEquals(bet, a.projectedBonusBet)
        assertTrue(file.readText().contains("\"rSum\":90"))
    }

    /**
     * A long random session with kills at random moments (before / between / after watching each bonus, buys included) against an
     * independent running ledger: balance, wagered, won and the pending flags must match after EVERY operation and every restart.
     */
    @Test fun randomSessionsWithKillsKeepTheLedgerExactAndPayEveryBonusOnce() {
        var w = fresh(balance = 3_000_000)
        val rng = Random(424242)
        var expected = w.balance; var wagered = 0L; var won = 0L
        var pendS: com.tjshea.neonreaper.engine.SeanceResult? = null; var pendV: com.tjshea.neonreaper.engine.VaultResult? = null
        var kills = 0; var buys = 0; var spins = 0; var bothPending = 0
        fun verify() {
            assertEquals(expected, w.balance); assertEquals(wagered, w.totalWagered); assertEquals(won, w.totalWon)
            assertEquals(pendS != null || pendV != null, w.bonusPending)
        }
        repeat(3_500) {
            if (pendS != null || pendV != null) {
                if (pendS != null && pendV != null) bothPending++
                when (rng.nextInt(5)) {
                    0 -> {      // the app is killed before the bonus was shown
                        w = restart(); kills++
                        val s = pendS; val v = pendV
                        if (s != null) sameSeance(s, w.pendingSeance() ?: error("seance lost")) else assertNull(w.pendingSeance())
                        if (v != null) sameVault(v, w.pendingVault() ?: error("vault lost")) else assertNull(w.pendingVault())
                    }
                    1 -> if (pendS != null) { w.markSeanceSeen(); pendS = null }
                    2 -> if (pendV != null) { w.markVaultSeen(); pendV = null }
                    3 -> { assertNull(w.playSpin(10)); assertNull(w.buy(Feature.SEANCE, 10)); assertNull(w.buy(Feature.VAULT, 10)) }
                    else -> { w.markSeanceSeen(); w.markVaultSeen(); pendS = null; pendV = null }      // watched everything
                }
            } else if (rng.nextInt(12) == 0) {
                val stake = ReaperConfig.STAKES[rng.nextInt(ReaperConfig.STAKES.size)]
                val feature = if (rng.nextBoolean()) Feature.SEANCE else Feature.VAULT
                val price = w.buyPrice(feature, stake)
                val b = w.buy(feature, stake) ?: error("refused")
                buys++
                expected += -price + (b.vault?.payTokens ?: 0L) + (b.seance?.payTokens ?: 0L)
                wagered += price; won += (b.vault?.payTokens ?: 0L) + (b.seance?.payTokens ?: 0L)
                pendS = b.seance; pendV = b.vault
            } else {
                val stake = ReaperConfig.STAKES[rng.nextInt(ReaperConfig.STAKES.size)]
                val p = w.playSpin(stake) ?: error("refused")
                spins++
                val pay = p.outcome.payTokens + (p.vault?.payTokens ?: 0L) + (p.seance?.payTokens ?: 0L)
                expected += pay - stake; wagered += stake; won += pay
                pendS = p.seance; pendV = p.vault
            }
            verify()
            if (rng.nextInt(400) == 0) { w = restart(); kills++; verify() }          // a kill while nothing is pending
        }
        println("random session: $spins spins, $buys buys, $kills kills, both bonuses pending at once $bothPending times, ledger exact")
        assertTrue(spins > 2_000 && buys > 100 && kills > 50)
        val last = restart(); assertEquals(expected, last.balance); assertEquals(wagered, last.totalWagered); assertEquals(won, last.totalWon)
    }

    /**
     * AUD-5 (code review; the JVM test clock cannot be moved): Wallet.authenticate() locks with `lockUntil = System.currentTimeMillis() + ...`
     * and tests `lockUntil > now`, so setting the device date forward ends a lockout and every wrong guess can be followed by another.
     * What CAN be checked here is that the lockout state itself is persisted and enforced for the right password too.
     */
    @Test fun lockoutAfterFiveWrongPasswordsIsPersistedAndRefusesEvenTheRightPassword() {
        val w = fresh(balance = 0)
        repeat(4) { assertTrue(w.deposit(pw("bad$it"), 1) is AuthResult.Wrong) }
        assertTrue(w.deposit(pw("bad4"), 1) is AuthResult.Locked)
        assertTrue("the right password is refused while locked", w.deposit(pw("grim"), 1) is AuthResult.Locked)
        val a = restart()                                              // killing the app does not reset the counter
        assertTrue(a.deposit(pw("grim"), 1) is AuthResult.Locked); assertEquals(0L, a.balance)
        assertTrue(a.withdraw(pw("grim"), 10) is AuthResult.Failed)    // nothing to withdraw, and never reaches authentication
    }

    @Test fun buyWithInsufficientCreditChangesNothing() {
        val w = fresh(balance = 309)                                   // one token short of 31 x 10
        val saved = file.readText()
        assertFalse(w.canBuy(Feature.SEANCE, 10)); assertNull(w.buy(Feature.SEANCE, 10)); assertNull(w.buy(Feature.VAULT, 10))
        assertEquals(309L, w.balance); assertEquals(0L, w.buys); assertEquals(0L, w.totalWagered); assertFalse(w.bonusPending)
        assertEquals("nothing was written", saved, file.readText())
        assertTrue(w.canBuy(Feature.SEANCE, 8))                        // 248 is affordable
    }

    @Test fun invalidStakesAreRejectedEverywhereAndLeaveNoTrace() {
        val w = fresh(); val saved = file.readText()
        for (bad in intArrayOf(0, -1, -10, 6, 7, 9, 11, 41, 100, Int.MAX_VALUE, Int.MIN_VALUE)) {
            assertNull("playSpin($bad)", w.playSpin(bad))
            assertFalse("canBuy seance $bad", w.canBuy(Feature.SEANCE, bad)); assertFalse(w.canBuy(Feature.VAULT, bad))
            assertNull("buy seance $bad", w.buy(Feature.SEANCE, bad)); assertNull(w.buy(Feature.VAULT, bad))
        }
        assertEquals(1_000_000L, w.balance); assertEquals(0L, w.spins); assertEquals(0L, w.totalWagered); assertEquals(0L, w.buys)
        assertEquals(saved, file.readText())
    }

    @Test fun everyListedStakeCanBePlayedAndNothingElse() {
        val w = fresh(balance = 5_000_000)
        for (s in ReaperConfig.STAKES) {
            val before = w.balance
            val p = w.playSpin(s) ?: error("stake $s refused")
            assertEquals(s, p.outcome.stake)
            val bonus = (p.vault?.payTokens ?: 0L) + (p.seance?.payTokens ?: 0L)
            assertEquals(before - s + p.outcome.payTokens + bonus, w.balance)
            if (w.bonusPending) { w.markVaultSeen(); w.markSeanceSeen() }
        }
    }

    // ------------------------------------------------------------------------------------------------ money in and out

    @Test fun cashOutsRoundDownToWholeMacksAndNeverExceedTheRequestOrTheBalance() {
        val w = fresh(balance = 0)
        assertTrue(w.deposit(pw("grim"), 1234) is AuthResult.Ok)
        for (req in longArrayOf(0, -5, 9, Long.MIN_VALUE)) assertTrue("withdraw($req)", w.withdraw(pw("grim"), req) is AuthResult.Failed)
        assertTrue(w.withdraw(pw("grim"), 1240) is AuthResult.Failed)                       // more than the balance (1235 would round down to 1230)
        assertTrue(w.withdraw(pw("grim"), Long.MAX_VALUE) is AuthResult.Failed)
        assertEquals(1234L, w.balance)
        assertTrue(w.withdraw(pw("grim"), 19) is AuthResult.Ok); assertEquals(10L, w.lastCashOut); assertEquals(1224L, w.balance)
        assertEquals(1220L, w.cashableTokens)
        assertTrue(w.withdraw(pw("grim"), 1224) is AuthResult.Ok); assertEquals(1220L, w.lastCashOut); assertEquals(4L, w.balance)
        assertTrue("odd tokens stay on the meter", w.withdraw(pw("grim"), 4) is AuthResult.Failed)
        assertEquals(1230L, w.totalWithdrawn)
        assertEquals(1234L - 1230L, w.balance)
    }

    @Test fun depositsAreBoundedAndPasswordProtected() {
        val w = fresh(balance = 0)
        for (bad in longArrayOf(0, -1, 10_000_001, Long.MAX_VALUE, Long.MIN_VALUE)) assertTrue("deposit($bad)", w.deposit(pw("grim"), bad) is AuthResult.Failed)
        assertTrue(w.deposit(pw("wrong"), 100) is AuthResult.Wrong)
        assertEquals(0L, w.balance); assertEquals(0L, w.totalDeposited)
        assertTrue(w.deposit(pw("grim"), 10_000_000) is AuthResult.Ok); assertEquals(10_000_000L, w.balance)
    }

    @Test fun veryLargeBalancesStillSettleExactly() {
        fresh(balance = 1); rewrite { it.put("balance", 4_000_000_000_000_000_000L) }          // 4e18 tokens: far beyond any deposit
        val w = restart(); assertEquals(4_000_000_000_000_000_000L, w.balance)
        var expected = w.balance
        for (i in 0 until 300) {
            val p = w.playSpin(40) ?: error("refused")
            expected += -40 + p.outcome.payTokens + (p.vault?.payTokens ?: 0L) + (p.seance?.payTokens ?: 0L)
            assertEquals(expected, w.balance)
            if (w.bonusPending) { w.markVaultSeen(); w.markSeanceSeen() }
        }
        assertTrue(w.balance > 0)
        assertEquals("4,000,000,000,000,000,000", Money.fmt(4_000_000_000_000_000_000L))
        assertTrue(Money.describe(w.balance).contains("books"))
    }

    @Ignore("AUD-8 (info): no overflow guard at Long.MAX_VALUE; unreachable (deposits are <= 10,000,000 each and need the password)")
    @Test fun balanceNearLongMaxDoesNotWrapNegative() {
        fresh(balance = 1); rewrite { it.put("balance", Long.MAX_VALUE - 10) }
        val w = restart()
        var wrapped = false
        for (i in 0 until 400) {
            if (w.playSpin(40) == null) break
            if (w.balance < 0) { wrapped = true; break }
            if (w.bonusPending) { w.markVaultSeen(); w.markSeanceSeen() }
        }
        assertFalse("balance wrapped to a negative number", wrapped)
    }

    // ------------------------------------------------------------------------------------------------ integrity (TILT)

    private fun assertTilt(label: String) {
        val l = Wallet(ctx)
        assertEquals(label, Wallet.LoadStatus.TAMPERED, l.load())
        assertNull(label, l.playSpin(1)); assertNull(label, l.buy(Feature.SEANCE, 1))
        assertTrue(label, l.deposit(pw("grim"), 100) is AuthResult.Failed)
        assertTrue(label, l.withdraw(pw("grim"), 100) is AuthResult.Failed)
        val bytes = file.exists().let { if (it) file.readBytes() else ByteArray(0) }
        l.saveSettings()
        assertTrue("$label: a tilted wallet must not overwrite the evidence", bytes.contentEquals(if (file.exists()) file.readBytes() else ByteArray(0)))
    }

    @Test fun everyEditThatKeepsTheOldSignatureTilts() {
        val w = fresh(); inject(w, findSeed(10) { it.seance != null })
        w.playSpin(10)
        val good = file.readText()
        val edits: List<Pair<String, (String) -> String>> = listOf(
            "balance up" to { t -> t.replace("\"balance\":", "\"balance\":9") },
            "unseen flag cleared" to { t -> t.replace("\"sUnseen\":true", "\"sUnseen\":false") },
            "relic stake sum raised" to { t -> t.replace("\"rSum\":0", "\"rSum\":200") },
            "pending bet raised" to { t -> t.replace("\"sBet\":10", "\"sBet\":40") },
            "stake index" to { t -> t.replace("\"stakeIdx\":6", "\"stakeIdx\":11") },
            "trailing space in json" to { t -> t.replace("}\n", " }\n") },
            "signature removed" to { t -> t.substringBeforeLast('\n') + "\n" },
            "no newline at all" to { t -> t.replace("\n", "") },
            "truncated" to { t -> t.substring(0, t.length / 2) },
            "empty" to { _ -> "" },
            "garbage" to { _ -> "not a wallet" },
            "signature of another payload" to { t -> t.substringBeforeLast('\n') + "\n" + Security.b64(Security.sign(ctx, "other".toByteArray())) },
            "signature bit flipped" to { t -> val sig = Security.unb64(t.substringAfterLast('\n').trim()); sig[0] = (sig[0].toInt() xor 1).toByte(); t.substringBeforeLast('\n') + "\n" + Security.b64(sig) },
        )
        for ((label, edit) in edits) {
            val edited = edit(good)
            assertTrue("$label: the edit changed nothing", edited != good)
            file.writeText(edited)
            assertTilt(label)
        }
    }

    @Test fun tiltThenResetWipesTheBalanceAndNeverLeavesAPendingBonus() {
        val w = fresh(); inject(w, findSeed(10) { it.seance != null }); w.playSpin(10)
        assertTrue(w.bonusPending)
        file.writeText(file.readText().replace("\"balance\":", "\"balance\":9"))
        val t = Wallet(ctx); assertEquals(Wallet.LoadStatus.TAMPERED, t.load())
        assertEquals(AuthResult.Ok, t.factoryReset(null))
        assertEquals(0L, t.balance); assertFalse(t.bonusPending); assertEquals(Wallet.LoadStatus.NEW, t.status)
        assertFalse(file.exists())
        assertTrue(t.setInitialPassword(pw("new")))
        val l = restart(); assertEquals(0L, l.balance); assertNull(l.pendingSeance())
    }

    @Test fun aWalletInTheOkStateNeedsThePasswordToBeReset() {
        val w = fresh()
        assertTrue(w.factoryReset(null) is AuthResult.Failed)
        assertTrue(w.factoryReset(pw("nope")) is AuthResult.Wrong)
        assertEquals(1_000_000L, restart().balance)
    }

    /** A state saved earlier carries a perfectly valid signature: copying wallet.dat back is NOT detected (no rollback counter). */
    @Ignore("known gap AUD-6: the signed wallet has no monotonic counter, so restoring an older wallet.dat (root / device snapshot) un-does losses")
    @Test fun restoringAnOlderSignedWalletIsDetected() {
        val w = fresh(); val old = file.readBytes()
        for (i in 0 until 20) { w.playSpin(10) ?: break; if (w.bonusPending) { w.markVaultSeen(); w.markSeanceSeen() } }
        file.writeBytes(old)
        assertEquals("rollback must tilt", Wallet.LoadStatus.TAMPERED, Wallet(ctx).load())
    }

    /** Documents the CURRENT behaviour of the same gap, so that the report can be reproduced (passes while the gap exists). */
    @Test fun currentlyARestoredOlderWalletIsAccepted() {
        val w = fresh(); val old = file.readBytes(); val start = w.balance
        var played = 0
        while (played < 30) { w.playSpin(40) ?: break; played++; if (w.bonusPending) { w.markVaultSeen(); w.markSeanceSeen() } }
        assertTrue(w.balance != start)
        file.writeBytes(old)
        val r = Wallet(ctx)
        assertEquals(Wallet.LoadStatus.OK, r.load()); assertEquals(start, r.balance); assertEquals(0L, r.spins)
    }

    @Test fun fileIsAppPrivateAndExcludedFromBackups() {
        // the escape hatches a player would use for rollbacks without root are closed in the manifest
        val manifest = File("src/main/AndroidManifest.xml").takeIf { it.exists() } ?: File("reaper/src/main/AndroidManifest.xml")
        val text = manifest.readText()
        assertTrue(text.contains("android:allowBackup=\"false\""))
        assertFalse(text.contains("android:debuggable=\"true\""))
        val rules = File(manifest.parentFile, "res/xml/data_extraction_rules.xml").readText()
        for (section in listOf("cloud-backup", "device-transfer")) {
            val body = Regex("<$section>(.*?)</$section>", RegexOption.DOT_MATCHES_ALL).find(rules)?.groupValues?.get(1) ?: ""
            assertTrue("$section must exclude the app files (wallet.dat)", body.contains("<exclude domain=\"file\""))
        }
    }
}

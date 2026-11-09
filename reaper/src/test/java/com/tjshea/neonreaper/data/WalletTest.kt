package com.tjshea.neonreaper.data

import com.tjshea.neonreaper.engine.ReaperConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalletTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private fun pw(s: String) = s.toCharArray()
    private fun fresh() = Wallet(ctx).also {
        File(ctx.filesDir, "wallet.dat").delete(); it.load(); assertTrue(it.setInitialPassword(pw("grim")))
    }

    @Test fun passwordGuardsMoney() {
        val w = fresh()
        assertTrue(w.deposit(pw("x"), 100) is AuthResult.Wrong)
        assertEquals(0L, w.balance)
        assertTrue(w.deposit(pw("grim"), 100) is AuthResult.Ok)
        assertTrue(w.withdraw(pw("y"), 50) is AuthResult.Wrong)
        assertTrue(w.withdraw(pw("grim"), 35) is AuthResult.Ok)   // cash-out rounds down to 30
        assertEquals(30L, w.lastCashOut)
        assertEquals(70L, w.balance)
        assertTrue(w.withdraw(pw("grim"), 7) is AuthResult.Failed)
        repeat(3) { w.deposit(pw("bad$it"), 1) }
        assertTrue(w.deposit(pw("bad9"), 1) is AuthResult.Wrong)
        assertTrue(w.deposit(pw("bad10"), 1) is AuthResult.Locked)
        assertTrue(w.deposit(pw("grim"), 1) is AuthResult.Locked)
    }

    @Test fun tamperDetected() {
        val w = fresh(); w.deposit(pw("grim"), 80)
        val f = File(ctx.filesDir, "wallet.dat")
        f.writeText(f.readText().replace("\"balance\":80", "\"balance\":8000"))
        val l = Wallet(ctx)
        assertEquals(Wallet.LoadStatus.TAMPERED, l.load())
        assertNull(l.playSpin(10))
    }

    @Test fun accountingWithRelicsVaultAndSeance() {
        val w = fresh(); w.deposit(pw("grim"), 5_000_000)
        var expected = 5_000_000L
        var vaults = 0; var seances = 0
        for (i in 0 until 6000) {
            val stake = ReaperConfig.STAKES[i % ReaperConfig.STAKES.size]
            val before = w.relics
            val p = w.playSpin(stake) ?: error("refused")
            expected += -stake + p.outcome.payTokens + (p.vault?.payTokens ?: 0) + (p.seance?.payTokens ?: 0)
            assertEquals(minOf(p.outcome.rawTokens, ReaperConfig.MAX_WIN_TOKENS), p.outcome.payTokens)
            assertTrue(p.outcome.payTokens <= ReaperConfig.MAX_WIN_TOKENS)
            assertEquals(expected, w.balance)
            if (p.relicIndex >= 0) assertEquals(before, p.relicIndex)
            val v = p.vault
            if (v != null) {
                vaults++
                assertEquals(0, w.relics)
                assertTrue(v.payTokens <= ReaperConfig.MAX_WIN_TOKENS)
                assertNull("spins blocked until the vault has been shown", w.playSpin(stake))
                assertEquals(v.payTokens, w.pendingVault()!!.payTokens)
                w.markVaultSeen()
            }
            val s = p.seance
            if (s != null) {
                seances++
                assertTrue(p.outcome.scatterCount >= 3)
                assertTrue(s.payTokens <= ReaperConfig.MAX_WIN_TOKENS)
                assertTrue(w.bonusPending)
                assertNull("spins blocked until the Seance has been shown", w.playSpin(stake))
                assertTrue(w.withdraw(pw("grim"), 10) is AuthResult.Failed)         // cash-out waits too
                val replay = w.pendingSeance()!!
                assertEquals(s.payTokens, replay.payTokens); assertEquals(s.totalSpins, replay.totalSpins)
                w.markSeanceSeen()
            } else assertEquals(0, p.outcome.seanceSpins)
            assertTrue(!w.bonusPending)
        }
        assertTrue("expected some vaults in 6000 spins", vaults > 5)
        assertTrue("expected some Seances in 6000 spins", seances > 5)
        assertEquals(seances.toLong(), w.seances)
    }

    @Test fun buysAreSettledAtomicallyAndReplayAfterARestart() {
        val w = fresh(); w.deposit(pw("grim"), 10_000)
        assertEquals(310L, w.buyPrice(Feature.SEANCE, 10))
        assertEquals(155L, w.buyPrice(Feature.VAULT, 5))
        // not enough credit -> nothing happens
        val poor = fresh(); poor.deposit(pw("grim"), 100)
        assertTrue(!poor.canBuy(Feature.SEANCE, 10)); assertNull(poor.buy(Feature.SEANCE, 10)); assertEquals(100L, poor.balance)

        val before = w.balance; val wag = w.totalWagered
        val b = w.buy(Feature.SEANCE, 10) ?: error("refused")
        assertEquals(310L, b.price)
        assertEquals(before - 310 + b.seance!!.payTokens, w.balance)
        assertEquals(wag + 310, w.totalWagered)                       // a buy counts as wagered, so "actual RTP" stays honest
        assertTrue(w.seanceUnseen && w.bonusPending)
        assertNull(w.buy(Feature.VAULT, 5)); assertNull(w.playSpin(10))   // nothing else until the bonus has been shown
        // the app is killed before the bonus was shown: it replays identically from the saved seed
        val again = Wallet(ctx); again.load()
        val replay = again.pendingSeance() ?: error("lost")
        assertEquals(b.seance!!.payTokens, replay.payTokens); assertEquals(b.seance!!.totalSpins, replay.totalSpins)
        assertEquals(b.seance!!.totalMult, replay.totalMult)
        again.markSeanceSeen()

        val bv = again.buy(Feature.VAULT, 5) ?: error("refused")
        assertEquals(155L, bv.price); assertEquals(5, bv.vault!!.bet)       // the bonus bet of a bought vault is the stake
        assertEquals(bv.vault!!.payTokens, again.pendingVault()!!.payTokens)
        assertTrue(again.achievements and (1 shl Achievements.DEVILS_BARGAIN) != 0)
    }

    @Test fun ranksAchievementsAndSettings() {
        assertEquals("Gravedigger", Ranks.of(0).title)
        assertEquals(1, Ranks.of(500).index); assertEquals(0, Ranks.of(499).index)
        assertTrue(Ranks.of(10_000_000).isTop); assertEquals(1f, Ranks.of(10_000_000).progress(10_000_000), 0f)
        assertEquals(0.5f, Ranks.of(500).progress(1250), 1e-4f)
        assertTrue(Achievements.ALL.map { it.id }.toSet().size == Achievements.ALL.size)

        val w = fresh(); w.deposit(pw("grim"), 100_000)
        var won = false
        for (i in 0 until 200) { val p = w.playSpin(1) ?: error("refused"); if (p.outcome.payTokens > 0) { won = true; break } }
        assertTrue(won)
        assertTrue(Achievements.isUnlocked(w, Achievements.FIRST_BLOOD))
        assertTrue(w.newAchievements.isNotEmpty())

        w.quality = 2; w.reduceFlash = true; w.realityMinutes = 15; w.musicVolume = 40; w.sfxVolume = 70; w.saveSettings()
        val l = Wallet(ctx); l.load()
        assertEquals(2, l.quality); assertTrue(l.reduceFlash); assertEquals(15, l.realityMinutes)
        assertEquals(40, l.musicVolume); assertEquals(70, l.sfxVolume)
        assertTrue(Achievements.isUnlocked(l, Achievements.FIRST_BLOOD))
        assertEquals(w.totalWagered, l.totalWagered)
    }

    /** A wallet saved by 2.1.0 (none of the v3 fields) loads with sensible defaults. */
    @Test fun anOlderWalletWithoutV3FieldsLoadsWithDefaults() {
        val w = fresh(); w.deposit(pw("grim"), 100)
        val f = File(ctx.filesDir, "wallet.dat")
        val json = org.json.JSONObject(f.readText().substringBeforeLast('\n'))
        for (k in listOf("sSeed", "sBet", "sCoffins", "sUnseen", "seances", "seanceWon", "ach", "miniSeen", "maxSM", "buys", "quality", "reduceFlash", "reality", "musicVol", "sfxVol")) json.remove(k)
        val text = json.toString()
        f.writeText(text + "\n" + Security.b64(Security.sign(ctx, text.toByteArray())))
        val l = Wallet(ctx)
        assertEquals(Wallet.LoadStatus.OK, l.load())
        assertEquals(100L, l.balance); assertTrue(!l.seanceUnseen); assertEquals(0, l.achievements)
        assertEquals(0, l.quality); assertEquals(30, l.realityMinutes); assertEquals(100, l.musicVolume)
    }

    /** A wallet saved by the previous version (relics + whole-window average, no relic-stake sum) loads and keeps working. */
    @Test fun migratesAnOldCollectionWindowWallet() {
        val w = fresh(); w.deposit(pw("grim"), 100)
        val f = File(ctx.filesDir, "wallet.dat")
        val json = org.json.JSONObject(f.readText().substringBeforeLast('\n'))
        json.put("relics", 3); json.remove("rSum"); json.put("cSpins", 70); json.put("cWag", 700)   // old average stake: 10
        val text = json.toString()
        f.writeText(text + "\n" + Security.b64(Security.sign(ctx, text.toByteArray())))
        val l = Wallet(ctx)
        assertEquals(Wallet.LoadStatus.OK, l.load())
        assertEquals(3, l.relics)
        // 3 banked relics at the old average of 10, 2 missing ones at the current stake (10) -> bonus bet 10
        assertEquals(10, l.projectedBonusBet)
        l.deposit(pw("grim"), 10)                       // saves in the new format ...
        assertTrue(f.readText().contains("\"rSum\":30"))
        val again = Wallet(ctx); again.load()           // ... and reloads identically
        assertEquals(3, again.relics)
        assertEquals(10, again.projectedBonusBet)
    }
}

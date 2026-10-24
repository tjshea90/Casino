package com.tjshea.neonreaper.data

import com.tjshea.neonreaper.engine.Money
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
        assertTrue(w.withdraw(pw("grim"), 35) is AuthResult.Ok)
        assertEquals(65L, w.balance)
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

    @Test fun accountingWithRelicsAndVault() {
        val w = fresh(); w.deposit(pw("grim"), 5_000_000)
        var expected = 5_000_000L
        var vaults = 0
        for (i in 0 until 6000) {
            val stake = ReaperConfig.STAKES[i % ReaperConfig.STAKES.size]
            val before = w.relics
            val p = w.playSpin(stake) ?: error("refused")
            expected += -stake + p.outcome.payTokens + (p.vault?.payTokens ?: 0)
            assertEquals(0L, p.outcome.payTokens % Money.TOKENS_PER_MACK)
            assertTrue(p.outcome.payTokens <= ReaperConfig.MAX_WIN_TOKENS)
            assertEquals(expected, w.balance)
            if (p.relicIndex >= 0) assertEquals(before, p.relicIndex)
            if (p.vault != null) {
                vaults++
                assertEquals(0, w.relics)
                assertTrue(p.vault!!.payTokens <= ReaperConfig.MAX_WIN_TOKENS)
                assertNull("spins blocked until the vault has been shown", w.playSpin(stake))
                val replay = w.pendingVault()!!
                assertEquals(p.vault!!.payTokens, replay.payTokens)
                w.markVaultSeen()
            }
        }
        assertTrue("expected some vaults in 6000 spins", vaults > 5)
    }
}

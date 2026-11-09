package com.tjshea.dragonhoard.data

import com.tjshea.dragonhoard.engine.Money
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

    private fun fresh(): Wallet = Wallet(ctx).also {
        File(ctx.filesDir, "wallet.dat").delete()
        it.load()
        assertTrue(it.setInitialPassword(pw("dragon")))
    }

    @Test fun depositAndWithdrawNeedPassword() {
        val w = fresh()
        assertTrue(w.deposit(pw("nope"), 500) is AuthResult.Wrong)
        assertEquals(0L, w.balance)
        assertTrue(w.deposit(pw("dragon"), 500) is AuthResult.Ok)
        assertEquals(500L, w.balance)
        assertTrue(w.withdraw(pw("bad"), 100) is AuthResult.Wrong)
        assertTrue(w.withdraw(pw("dragon"), 600) is AuthResult.Failed)
        assertTrue(w.withdraw(pw("dragon"), 125) is AuthResult.Ok)
        assertEquals(375L, w.balance)
        // persisted and reloadable
        val again = Wallet(ctx)
        assertEquals(Wallet.LoadStatus.OK, again.load())
        assertEquals(375L, again.balance)
    }

    @Test fun lockoutAfterRepeatedFailures() {
        val w = fresh()
        repeat(4) { assertTrue(w.deposit(pw("x$it"), 10) is AuthResult.Wrong) }
        assertTrue(w.deposit(pw("x5"), 10) is AuthResult.Locked)
        // even the right password is refused while locked
        assertTrue(w.deposit(pw("dragon"), 10) is AuthResult.Locked)
        assertEquals(0L, w.balance)
    }

    @Test fun tamperedFileIsDetected() {
        val w = fresh()
        w.deposit(pw("dragon"), 80)
        val f = File(ctx.filesDir, "wallet.dat")
        f.writeText(f.readText().replace("\"balance\":80", "\"balance\":999999"))
        val loaded = Wallet(ctx)
        assertEquals(Wallet.LoadStatus.TAMPERED, loaded.load())
        assertNull(loaded.playSpin(1, 10))
    }

    @Test fun spinsDebitBetAndCreditWholeMacks() {
        val w = fresh()
        w.deposit(pw("dragon"), 1_000_000)
        var expected = 1_000_000L
        repeat(500) {
            val free = w.inFreeSpins
            val r = w.playSpin(2, 10) ?: error("spin refused")
            if (!free) expected -= 2 * 10 * Money.TOKENS_PER_MACK
            expected += r.totalWinTokens
            assertEquals(0L, r.totalWinTokens % Money.TOKENS_PER_MACK)
            assertEquals(expected, w.balance)
        }
    }

    @Test fun cannotBetMoreThanBalance() {
        val w = fresh()
        w.deposit(pw("dragon"), 95)
        assertNull(w.playSpin(1, 10))  // needs 100 tokens
        assertEquals(95L, w.balance)
    }
}

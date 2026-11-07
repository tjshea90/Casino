package com.tjshea.neonreaper.data

import android.content.Context
import com.tjshea.neonreaper.engine.Money
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.ReaperEngine
import com.tjshea.neonreaper.engine.SoulVault
import com.tjshea.neonreaper.engine.SpinOutcome
import com.tjshea.neonreaper.engine.VaultResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import kotlin.math.roundToInt

class LedgerEntry(val time: Long, val type: String, val tokens: Long, val balanceAfter: Long)

sealed class AuthResult {
    object Ok : AuthResult()
    class Wrong(val attemptsBeforeLock: Int) : AuthResult()
    class Locked(val untilMillis: Long) : AuthResult()
    class Failed(val message: String) : AuthResult()
}

/** Result of one paid spin, already settled and saved. */
class SpinPlay(val outcome: SpinOutcome, val relicIndex: Int, val vault: VaultResult?)

/**
 * Credit meter, relic progress and stats. Same security model as Dragon's Hoard:
 * password (PBKDF2) for deposits/withdrawals, Keystore-HMAC-signed file, lockout.
 * A spin (and any Soul Vault bonus it unlocks) is settled and saved before any
 * animation, so killing the app can never lose or duplicate a win.
 */
class Wallet(private val context: Context) {

    enum class LoadStatus { NEW, OK, TAMPERED }

    private val file = File(context.filesDir, "wallet.dat")
    private val rng = SecureRandom()

    var status = LoadStatus.NEW; private set

    var balance = 0L; private set
    var stakeIndex = 6   // 10 tokens
    var soundOn = true
    var musicOn = true
    var turbo = false
    var relics = 0; private set
    var collectSpins = 0L; private set
    var collectWagered = 0L; private set
    var pendingVaultSeed = 0L; private set
    var pendingVaultBet = 0; private set
    var vaultUnseen = false; private set
    var totalDeposited = 0L; private set
    var totalWithdrawn = 0L; private set
    var totalWagered = 0L; private set
    var totalWon = 0L; private set
    var spins = 0L; private set
    var features = 0L; private set
    var bonuses = 0L; private set
    var bonusWon = 0L; private set
    var biggestWin = 0L; private set
    var ticketNo = 1000; private set
    val ledger = ArrayList<LedgerEntry>()
    private var pwSalt: ByteArray? = null
    private var pwHash: ByteArray? = null
    private var pwIter = Security.PBKDF2_ITERATIONS
    private var failedAttempts = 0
    private var lockUntil = 0L

    val hasPassword: Boolean get() = pwHash != null
    val stake: Int get() = ReaperConfig.STAKES[stakeIndex.coerceIn(0, ReaperConfig.STAKES.size - 1)]

    /** Bonus bet the Soul Vault would use right now: average stake while collecting relics. */
    val averageStake: Int
        get() = if (collectSpins == 0L) stake
        else (collectWagered.toDouble() / collectSpins).roundToInt().coerceIn(1, ReaperConfig.STAKES.last())

    @Synchronized
    fun load(): LoadStatus {
        if (!file.exists()) { status = LoadStatus.NEW; return status }
        status = try {
            val text = file.readText()
            val nl = text.lastIndexOf('\n')
            val json = text.substring(0, nl)
            val mac = Security.unb64(text.substring(nl + 1).trim())
            if (!Security.constantTimeEquals(mac, Security.sign(context, json.toByteArray()))) LoadStatus.TAMPERED
            else { fromJson(JSONObject(json)); LoadStatus.OK }
        } catch (e: Exception) {
            LoadStatus.TAMPERED
        }
        return status
    }

    private fun fromJson(o: JSONObject) {
        balance = o.getLong("balance")
        stakeIndex = o.optInt("stakeIdx", 6)
        soundOn = o.optBoolean("sound", true); musicOn = o.optBoolean("music", true); turbo = o.optBoolean("turbo")
        relics = o.optInt("relics"); collectSpins = o.optLong("cSpins"); collectWagered = o.optLong("cWag")
        pendingVaultSeed = o.optLong("vSeed"); pendingVaultBet = o.optInt("vBet"); vaultUnseen = o.optBoolean("vUnseen")
        totalDeposited = o.optLong("dep"); totalWithdrawn = o.optLong("wd")
        totalWagered = o.optLong("wag"); totalWon = o.optLong("won")
        spins = o.optLong("spins"); features = o.optLong("features"); bonuses = o.optLong("bonuses")
        bonusWon = o.optLong("bonusWon"); biggestWin = o.optLong("big"); ticketNo = o.optInt("ticket", 1000)
        o.optString("salt").takeIf { it.isNotEmpty() }?.let { pwSalt = Security.unb64(it) }
        o.optString("hash").takeIf { it.isNotEmpty() }?.let { pwHash = Security.unb64(it) }
        pwIter = o.optInt("iter", Security.PBKDF2_ITERATIONS)
        failedAttempts = o.optInt("fails"); lockUntil = o.optLong("lockUntil")
        ledger.clear()
        o.optJSONArray("ledger")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.getJSONObject(i)
                ledger += LedgerEntry(e.getLong("t"), e.getString("k"), e.getLong("n"), e.getLong("b"))
            }
        }
    }

    private fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1); put("balance", balance); put("stakeIdx", stakeIndex)
        put("sound", soundOn); put("music", musicOn); put("turbo", turbo)
        put("relics", relics); put("cSpins", collectSpins); put("cWag", collectWagered)
        put("vSeed", pendingVaultSeed); put("vBet", pendingVaultBet); put("vUnseen", vaultUnseen)
        put("dep", totalDeposited); put("wd", totalWithdrawn); put("wag", totalWagered); put("won", totalWon)
        put("spins", spins); put("features", features); put("bonuses", bonuses); put("bonusWon", bonusWon)
        put("big", biggestWin); put("ticket", ticketNo)
        pwSalt?.let { put("salt", Security.b64(it)) }
        pwHash?.let { put("hash", Security.b64(it)) }
        put("iter", pwIter); put("fails", failedAttempts); put("lockUntil", lockUntil)
        put("ledger", JSONArray().apply {
            ledger.takeLast(100).forEach {
                put(JSONObject().put("t", it.time).put("k", it.type).put("n", it.tokens).put("b", it.balanceAfter))
            }
        })
    }

    @Synchronized
    fun save() {
        if (status == LoadStatus.TAMPERED) return
        val json = toJson().toString()
        val mac = Security.b64(Security.sign(context, json.toByteArray()))
        val tmp = File(file.parentFile, "wallet.tmp")
        tmp.outputStream().use { out -> out.write("$json\n$mac".toByteArray()); out.fd.sync() }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    // ---- password (slow: call off the UI thread) ----
    @Synchronized
    fun setInitialPassword(pw: CharArray): Boolean {
        if (hasPassword) return false
        val salt = Security.newSalt()
        pwSalt = salt; pwIter = Security.PBKDF2_ITERATIONS
        pwHash = Security.hashPassword(pw, salt, pwIter)
        status = LoadStatus.OK
        save()
        return true
    }

    @Synchronized
    fun authenticate(pw: CharArray): AuthResult {
        val now = System.currentTimeMillis()
        if (lockUntil > now) return AuthResult.Locked(lockUntil)
        val salt = pwSalt ?: return AuthResult.Failed("No password set")
        val expected = pwHash ?: return AuthResult.Failed("No password set")
        return if (Security.constantTimeEquals(Security.hashPassword(pw, salt, pwIter), expected)) {
            failedAttempts = 0; lockUntil = 0; save()
            AuthResult.Ok
        } else {
            failedAttempts++
            val free = 5
            if (failedAttempts >= free) {
                val secs = (30L shl minOf(failedAttempts - free, 7)).coerceAtMost(3600)
                lockUntil = now + secs * 1000
                save()
                AuthResult.Locked(lockUntil)
            } else {
                save()
                AuthResult.Wrong(free - failedAttempts)
            }
        }
    }

    @Synchronized
    fun changePassword(old: CharArray, new: CharArray): AuthResult {
        val r = authenticate(old)
        if (r !is AuthResult.Ok) return r
        val salt = Security.newSalt()
        pwSalt = salt; pwIter = Security.PBKDF2_ITERATIONS
        pwHash = Security.hashPassword(new, salt, pwIter)
        log("PASSWORD CHANGED", 0)
        save()
        return r
    }

    @Synchronized
    fun deposit(pw: CharArray, tokens: Long): AuthResult {
        if (tokens <= 0 || tokens > 10_000_000) return AuthResult.Failed("Invalid amount")
        val r = authenticate(pw)
        if (r !is AuthResult.Ok) return r
        balance += tokens; totalDeposited += tokens
        log("DEPOSIT", tokens); save()
        return r
    }

    /** Largest amount that can be cashed out: cash-outs are whole macks (the only place rounding happens). */
    val cashableTokens: Long get() = Money.floorToMack(balance)

    /** Cash-outs are rounded DOWN to whole macks; any odd tokens stay on the credit meter. Returns the amount actually paid via [lastCashOut]. */
    var lastCashOut = 0L; private set

    @Synchronized
    fun withdraw(pw: CharArray, requested: Long): AuthResult {
        val tokens = Money.floorToMack(requested)
        if (tokens <= 0) return AuthResult.Failed("Cash-outs are whole macks: enter at least 10 tokens")
        if (tokens > balance) return AuthResult.Failed("Amount exceeds balance")
        if (vaultUnseen) return AuthResult.Failed("Finish the Soul Vault bonus first")
        val r = authenticate(pw)
        if (r !is AuthResult.Ok) return r
        balance -= tokens; totalWithdrawn += tokens; ticketNo++; lastCashOut = tokens
        log("WITHDRAW", tokens); save()
        return r
    }

    @Synchronized
    fun factoryReset(pw: CharArray?): AuthResult {
        if (status != LoadStatus.TAMPERED) {
            val r = authenticate(pw ?: return AuthResult.Failed("Password required"))
            if (r !is AuthResult.Ok) return r
        }
        file.delete()
        balance = 0; relics = 0; collectSpins = 0; collectWagered = 0; vaultUnseen = false
        totalDeposited = 0; totalWithdrawn = 0; totalWagered = 0; totalWon = 0
        spins = 0; features = 0; bonuses = 0; bonusWon = 0; biggestWin = 0; ledger.clear()
        pwSalt = null; pwHash = null; failedAttempts = 0; lockUntil = 0
        status = LoadStatus.NEW
        return AuthResult.Ok
    }

    private fun log(type: String, tokens: Long) {
        ledger += LedgerEntry(System.currentTimeMillis(), type, tokens, balance)
        while (ledger.size > 100) ledger.removeAt(0)
    }

    // ---- play ----
    fun canAfford(stake: Int) = balance >= stake

    /** Bonus awaiting display (already paid), or null. */
    fun pendingVault(): VaultResult? = if (vaultUnseen) SoulVault.play(pendingVaultSeed, pendingVaultBet) else null

    @Synchronized
    fun markVaultSeen() { if (vaultUnseen) { vaultUnseen = false; save() } }

    @Synchronized
    fun playSpin(stake: Int): SpinPlay? {
        if (status != LoadStatus.OK || vaultUnseen) return null
        if (stake !in ReaperConfig.STAKES || balance < stake) return null
        balance -= stake
        totalWagered += stake
        collectSpins++
        collectWagered += stake
        val outcome = ReaperEngine.spin(rng, stake)
        val win = outcome.payTokens
        balance += win
        totalWon += win
        spins++
        if (outcome.feature) features++
        var relicIndex = -1
        var vault: VaultResult? = null
        if (rng.nextDouble() < ReaperConfig.RELIC_CHANCE) {
            relicIndex = relics
            relics++
            if (relics >= ReaperConfig.RELIC_COUNT) {
                val bet = averageStake
                val seed = rng.nextLong()
                vault = SoulVault.play(seed, bet)
                balance += vault.payTokens
                totalWon += vault.payTokens
                bonusWon += vault.payTokens
                bonuses++
                if (vault.payTokens > biggestWin) biggestWin = vault.payTokens
                pendingVaultSeed = seed; pendingVaultBet = bet; vaultUnseen = true
                relics = 0; collectSpins = 0; collectWagered = 0
            }
        }
        if (win > biggestWin) biggestWin = win
        save()
        return SpinPlay(outcome, relicIndex, vault)
    }
}

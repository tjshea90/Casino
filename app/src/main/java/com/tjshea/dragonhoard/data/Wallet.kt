package com.tjshea.dragonhoard.data

import android.content.Context
import com.tjshea.dragonhoard.engine.Money
import com.tjshea.dragonhoard.engine.SlotEngine
import com.tjshea.dragonhoard.engine.SpinResult
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

class LedgerEntry(val time: Long, val type: String, val tokens: Long, val balanceAfter: Long)

sealed class AuthResult {
    object Ok : AuthResult()
    class Wrong(val attemptsBeforeLock: Int) : AuthResult()
    class Locked(val untilMillis: Long) : AuthResult()
    class Failed(val message: String) : AuthResult()
}

/**
 * The machine's credit meter. Every change is persisted atomically to a
 * Keystore-HMAC-signed file. Deposits and withdrawals require the password;
 * the only other way the balance moves is by playing (bet debited, win credited
 * in the same atomic save, before any animation runs).
 */
class Wallet(private val context: Context) {

    enum class LoadStatus { NEW, OK, TAMPERED }

    private val file = File(context.filesDir, "wallet.dat")
    private val rng = SecureRandom()

    var status = LoadStatus.NEW; private set

    // ---- persisted state ----
    var balance = 0L; private set
    var freeSpinsLeft = 0; private set
    var freeSpinsTotal = 0; private set
    var freeSpinWinTokens = 0L; private set
    var fsLineBet = 1; private set
    var fsLines = 10; private set
    var lineBetIndex = 0
    var linesIndex = 5
    var soundOn = true
    var turbo = false
    var totalDeposited = 0L; private set
    var totalWithdrawn = 0L; private set
    var totalWagered = 0L; private set
    var totalWon = 0L; private set
    var spins = 0L; private set
    var features = 0L; private set
    var biggestWin = 0L; private set
    var ticketNo = 1000; private set
    val ledger = ArrayList<LedgerEntry>()
    private var pwSalt: ByteArray? = null
    private var pwHash: ByteArray? = null
    private var pwIter = Security.PBKDF2_ITERATIONS
    private var failedAttempts = 0
    private var lockUntil = 0L

    val hasPassword: Boolean get() = pwHash != null
    val inFreeSpins: Boolean get() = freeSpinsLeft > 0

    @Synchronized
    fun load(): LoadStatus {
        if (!file.exists()) { status = LoadStatus.NEW; return status }
        status = try {
            val text = file.readText()
            val nl = text.lastIndexOf('\n')
            val json = text.substring(0, nl)
            val mac = Security.unb64(text.substring(nl + 1).trim())
            if (!Security.constantTimeEquals(mac, Security.sign(context, json.toByteArray()))) {
                LoadStatus.TAMPERED
            } else {
                fromJson(JSONObject(json)); LoadStatus.OK
            }
        } catch (e: Exception) {
            LoadStatus.TAMPERED
        }
        return status
    }

    private fun fromJson(o: JSONObject) {
        balance = o.getLong("balance")
        freeSpinsLeft = o.optInt("fsLeft")
        freeSpinsTotal = o.optInt("fsTotal")
        freeSpinWinTokens = o.optLong("fsWin")
        fsLineBet = o.optInt("fsLineBet", 1)
        fsLines = o.optInt("fsLines", 10)
        lineBetIndex = o.optInt("lineBetIdx")
        linesIndex = o.optInt("linesIdx", 5)
        soundOn = o.optBoolean("sound", true)
        turbo = o.optBoolean("turbo", false)
        totalDeposited = o.optLong("dep"); totalWithdrawn = o.optLong("wd")
        totalWagered = o.optLong("wag"); totalWon = o.optLong("won")
        spins = o.optLong("spins"); features = o.optLong("features"); biggestWin = o.optLong("big")
        ticketNo = o.optInt("ticket", 1000)
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
        put("v", 1)
        put("balance", balance)
        put("fsLeft", freeSpinsLeft); put("fsTotal", freeSpinsTotal); put("fsWin", freeSpinWinTokens)
        put("fsLineBet", fsLineBet); put("fsLines", fsLines)
        put("lineBetIdx", lineBetIndex); put("linesIdx", linesIndex)
        put("sound", soundOn); put("turbo", turbo)
        put("dep", totalDeposited); put("wd", totalWithdrawn)
        put("wag", totalWagered); put("won", totalWon)
        put("spins", spins); put("features", features); put("big", biggestWin)
        put("ticket", ticketNo)
        pwSalt?.let { put("salt", Security.b64(it)) }
        pwHash?.let { put("hash", Security.b64(it)) }
        put("iter", pwIter); put("fails", failedAttempts); put("lockUntil", lockUntil)
        put("ledger", JSONArray().apply {
            ledger.takeLast(100).forEach {
                put(JSONObject().put("t", it.time).put("k", it.type).put("n", it.tokens).put("b", it.balanceAfter))
            }
        })
    }

    /** Atomic signed write: temp file + rename. */
    @Synchronized
    fun save() {
        if (status == LoadStatus.TAMPERED) return
        val json = toJson().toString()
        val mac = Security.b64(Security.sign(context, json.toByteArray()))
        val tmp = File(file.parentFile, "wallet.tmp")
        tmp.outputStream().use { out ->
            out.write("$json\n$mac".toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    // ---- password ----

    /** Slow (PBKDF2): call off the UI thread. */
    @Synchronized
    fun setInitialPassword(pw: CharArray): Boolean {
        if (hasPassword) return false
        val salt = Security.newSalt()
        pwSalt = salt
        pwIter = Security.PBKDF2_ITERATIONS
        pwHash = Security.hashPassword(pw, salt, pwIter)
        status = LoadStatus.OK
        save()
        return true
    }

    /** Slow (PBKDF2): call off the UI thread. */
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
        balance += tokens
        totalDeposited += tokens
        log("DEPOSIT", tokens)
        save()
        return r
    }

    @Synchronized
    fun withdraw(pw: CharArray, tokens: Long): AuthResult {
        if (tokens <= 0) return AuthResult.Failed("Invalid amount")
        if (tokens > balance) return AuthResult.Failed("Amount exceeds balance")
        if (inFreeSpins) return AuthResult.Failed("Finish your free spins first")
        val r = authenticate(pw)
        if (r !is AuthResult.Ok) return r
        balance -= tokens
        totalWithdrawn += tokens
        ticketNo++
        log("WITHDRAW", tokens)
        save()
        return r
    }

    /** Erase everything. Allowed with the password, or without one if the file was tampered. */
    @Synchronized
    fun factoryReset(pw: CharArray?): AuthResult {
        if (status != LoadStatus.TAMPERED) {
            val r = authenticate(pw ?: return AuthResult.Failed("Password required"))
            if (r !is AuthResult.Ok) return r
        }
        file.delete()
        balance = 0; freeSpinsLeft = 0; freeSpinsTotal = 0; freeSpinWinTokens = 0
        totalDeposited = 0; totalWithdrawn = 0; totalWagered = 0; totalWon = 0
        spins = 0; features = 0; biggestWin = 0; ledger.clear()
        pwSalt = null; pwHash = null; failedAttempts = 0; lockUntil = 0
        status = LoadStatus.NEW
        return AuthResult.Ok
    }

    private fun log(type: String, tokens: Long) {
        ledger += LedgerEntry(System.currentTimeMillis(), type, tokens, balance)
        while (ledger.size > 100) ledger.removeAt(0)
    }

    // ---- play ----

    fun canAfford(lineBet: Int, lines: Int): Boolean =
        inFreeSpins || balance >= lineBet.toLong() * lines * Money.TOKENS_PER_MACK

    /**
     * Debits the bet (unless a free spin), resolves the outcome and credits the
     * win in a single persisted step. Returns null if the bet can't be covered.
     */
    @Synchronized
    fun playSpin(lineBet: Int, lines: Int): SpinResult? {
        if (status != LoadStatus.OK) return null
        val free = inFreeSpins
        val lb = if (free) fsLineBet else lineBet
        val ln = if (free) fsLines else lines
        val cost = lb.toLong() * ln * Money.TOKENS_PER_MACK
        if (!free) {
            if (balance < cost) return null
            balance -= cost
            totalWagered += cost
        }
        val result = SlotEngine.spin(rng, lb, ln, free)
        val win = result.totalWinTokens
        balance += win
        totalWon += win
        spins++
        if (free) {
            freeSpinsLeft--
            freeSpinWinTokens += win
        }
        if (result.freeSpinsAwarded > 0) {
            if (!free) {
                features++
                fsLineBet = lb; fsLines = ln
                freeSpinWinTokens = 0
                freeSpinsTotal = 0
            }
            freeSpinsLeft += result.freeSpinsAwarded
            freeSpinsTotal += result.freeSpinsAwarded
        }
        if (win > biggestWin) biggestWin = win
        save()
        return result
    }

    @Synchronized
    fun lockRemainingMillis(): Long = (lockUntil - System.currentTimeMillis()).coerceAtLeast(0)
}

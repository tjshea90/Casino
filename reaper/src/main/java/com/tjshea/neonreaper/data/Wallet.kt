package com.tjshea.neonreaper.data

import android.content.Context
import com.tjshea.neonreaper.engine.Money
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.RelicMeter
import com.tjshea.neonreaper.engine.Round
import com.tjshea.neonreaper.engine.SeanceResult
import com.tjshea.neonreaper.engine.Seance
import com.tjshea.neonreaper.engine.SoulVault
import com.tjshea.neonreaper.engine.SpinOutcome
import com.tjshea.neonreaper.engine.VaultResult
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

/** Result of one paid spin, already settled and saved (a Soul Vault and/or a Séance it started are already paid too). */
class SpinPlay(val outcome: SpinOutcome, val relicIndex: Int, val vault: VaultResult?, val seance: SeanceResult? = null)

/** What a feature buy produced (already paid and saved). */
class BuyPlay(val vault: VaultResult?, val seance: SeanceResult?, val price: Long)

enum class Feature { SEANCE, VAULT }

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
    /** Relic progress and the stakes of the spins that revealed them (decides the Soul Vault bonus bet). */
    private val meter = RelicMeter()
    val relics: Int get() = meter.relics
    var pendingVaultSeed = 0L; private set
    var pendingVaultBet = 0; private set
    var vaultUnseen = false; private set
    // THE SÉANCE (free spins): generated from a seed, paid at the trigger, replayed by the UI (also after a restart)
    var pendingSeanceSeed = 0L; private set
    var pendingSeanceBet = 0; private set
    var pendingSeanceCoffins = 0; private set
    var seanceUnseen = false; private set
    var seances = 0L; private set
    var seanceWon = 0L; private set
    /** Bit i set = achievement i unlocked (see [Achievements]); miniSeen bit = mini feature type seen. */
    var achievements = 0; private set
    var miniSeen = 0; private set
    var maxSeanceMult = 0; private set
    var buys = 0L; private set
    // settings
    var quality = 0                  // 0 auto, 1 low, 2 medium, 3 high
    var reduceFlash = false
    var realityMinutes = 30          // 0 = off
    var musicVolume = 100
    var sfxVolume = 100
    /** Achievements unlocked since the UI last looked (not saved). */
    val newAchievements = ArrayDeque<Int>()
    // session (not saved): for the stats screen and the reality check
    private val sessionStartMs = System.currentTimeMillis()
    private var sessionWagered0 = 0L
    private var sessionWon0 = 0L
    var totalDeposited = 0L; private set
    var totalWithdrawn = 0L; private set
    var totalWagered = 0L; private set
    var totalWon = 0L; private set
    var spins = 0L; private set
    var features = 0L; private set
    var bonuses = 0L; private set
    var bonusWon = 0L; private set
    var biggestWin = 0L; private set
    var winSpins = 0L; private set                 // spins whose lines paid (lifetime; the hit rate shown in Stats)
    private var sessionSpins0 = 0L
    private var sessionWinSpins0 = 0L
    var sessionBiggestWin = 0L; private set
    var ticketNo = 1000; private set
    val ledger = ArrayList<LedgerEntry>()
    private var pwSalt: ByteArray? = null
    private var pwHash: ByteArray? = null
    private var pwIter = Security.PBKDF2_ITERATIONS
    private var failedAttempts = 0
    private var lockUntil = 0L

    val hasPassword: Boolean get() = pwHash != null
    val sessionMillis: Long get() = System.currentTimeMillis() - sessionStartMs
    val sessionWagered: Long get() = totalWagered - sessionWagered0
    val sessionWon: Long get() = totalWon - sessionWon0
    val sessionNet: Long get() = sessionWon - sessionWagered
    val sessionSpins: Long get() = spins - sessionSpins0
    val sessionWinSpins: Long get() = winSpins - sessionWinSpins0

    /** Records a round's win for the "biggest win" statistics (lifetime and this session). */
    private fun noteWin(x: Long) { if (x > biggestWin) biggestWin = x; if (x > sessionBiggestWin) sessionBiggestWin = x }
    val rank: Ranks.Rank get() = Ranks.of(totalWagered)
    val stake: Int get() = ReaperConfig.STAKES[stakeIndex.coerceIn(0, ReaperConfig.STAKES.size - 1)]

    /**
     * Bonus bet the Soul Vault would get if the relics still missing were revealed at the current stake: the average stake
     * of the five relic-revealing spins, rounded down (see RelicMeter for why it is not the average of every spin).
     */
    val projectedBonusBet: Int
        get() = meter.projectedBet(stake)

    @Synchronized
    fun load(): LoadStatus {
        if (!file.exists()) { status = LoadStatus.NEW; return status }
        // (session baselines are re-taken below once the saved totals are known)
        status = try {
            val text = file.readText()
            val nl = text.lastIndexOf('\n')
            val json = text.substring(0, nl)
            val mac = Security.unb64(text.substring(nl + 1).trim())
            if (!Security.constantTimeEquals(mac, Security.sign(context, json.toByteArray()))) LoadStatus.TAMPERED
            else { fromJson(JSONObject(json)); sessionWagered0 = totalWagered; sessionWon0 = totalWon; sessionSpins0 = spins; sessionWinSpins0 = winSpins; sessionBiggestWin = 0; LoadStatus.OK }
        } catch (e: Exception) {
            LoadStatus.TAMPERED
        }
        return status
    }

    private fun fromJson(o: JSONObject) {
        balance = o.getLong("balance")
        stakeIndex = o.optInt("stakeIdx", 6)
        soundOn = o.optBoolean("sound", true); musicOn = o.optBoolean("music", true); turbo = o.optBoolean("turbo")
        meter.relics = o.optInt("relics")
        meter.stakeSum = if (o.has("rSum")) o.getLong("rSum") else {
            // wallet saved by an older version: it tracked the average stake of the whole collection window
            val oldSpins = o.optLong("cSpins"); val oldWagered = o.optLong("cWag")
            meter.relics * (if (oldSpins > 0) oldWagered / oldSpins else stake.toLong())
        }
        pendingVaultSeed = o.optLong("vSeed"); pendingVaultBet = o.optInt("vBet"); vaultUnseen = o.optBoolean("vUnseen")
        pendingSeanceSeed = o.optLong("sSeed"); pendingSeanceBet = o.optInt("sBet"); pendingSeanceCoffins = o.optInt("sCoffins", 3)
        seanceUnseen = o.optBoolean("sUnseen"); seances = o.optLong("seances"); seanceWon = o.optLong("seanceWon")
        achievements = o.optInt("ach"); miniSeen = o.optInt("miniSeen"); maxSeanceMult = o.optInt("maxSM"); buys = o.optLong("buys")
        quality = o.optInt("quality", 0); reduceFlash = o.optBoolean("reduceFlash"); realityMinutes = o.optInt("reality", 30)
        musicVolume = o.optInt("musicVol", 100); sfxVolume = o.optInt("sfxVol", 100)
        totalDeposited = o.optLong("dep"); totalWithdrawn = o.optLong("wd")
        totalWagered = o.optLong("wag"); totalWon = o.optLong("won")
        spins = o.optLong("spins"); features = o.optLong("features"); bonuses = o.optLong("bonuses")
        bonusWon = o.optLong("bonusWon"); biggestWin = o.optLong("big"); winSpins = o.optLong("winSpins"); ticketNo = o.optInt("ticket", 1000)
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
        put("relics", meter.relics); put("rSum", meter.stakeSum)
        put("vSeed", pendingVaultSeed); put("vBet", pendingVaultBet); put("vUnseen", vaultUnseen)
        put("sSeed", pendingSeanceSeed); put("sBet", pendingSeanceBet); put("sCoffins", pendingSeanceCoffins); put("sUnseen", seanceUnseen)
        put("seances", seances); put("seanceWon", seanceWon); put("ach", achievements); put("miniSeen", miniSeen)
        put("maxSM", maxSeanceMult); put("buys", buys)
        put("quality", quality); put("reduceFlash", reduceFlash); put("reality", realityMinutes)
        put("musicVol", musicVolume); put("sfxVol", sfxVolume)
        put("dep", totalDeposited); put("wd", totalWithdrawn); put("wag", totalWagered); put("won", totalWon)
        put("spins", spins); put("features", features); put("bonuses", bonuses); put("bonusWon", bonusWon)
        put("big", biggestWin); put("winSpins", winSpins); put("ticket", ticketNo)
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
        if (vaultUnseen || seanceUnseen) return AuthResult.Failed("Finish the bonus round first")
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
        balance = 0; meter.relics = 0; meter.stakeSum = 0; vaultUnseen = false; seanceUnseen = false
        seances = 0; seanceWon = 0; achievements = 0; miniSeen = 0; maxSeanceMult = 0; buys = 0
        sessionWagered0 = 0; sessionWon0 = 0; newAchievements.clear()
        totalDeposited = 0; totalWithdrawn = 0; totalWagered = 0; totalWon = 0
        spins = 0; winSpins = 0; sessionBiggestWin = 0; sessionSpins0 = 0; sessionWinSpins0 = 0; features = 0; bonuses = 0; bonusWon = 0; biggestWin = 0; ledger.clear()
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

    /** Soul Vault awaiting display (already paid), or null. */
    fun pendingVault(): VaultResult? = if (vaultUnseen) SoulVault.play(pendingVaultSeed, pendingVaultBet) else null

    /** Séance awaiting display (already paid), or null. A bought Séance is the same seed with the 3-coffin award. */
    fun pendingSeance(): SeanceResult? =
        if (seanceUnseen) Seance.play(pendingSeanceSeed, pendingSeanceBet, pendingSeanceCoffins.coerceIn(3, 5)) else null

    @Synchronized
    fun markVaultSeen() { if (vaultUnseen) { vaultUnseen = false; save() } }

    @Synchronized
    fun markSeanceSeen() { if (seanceUnseen) { seanceUnseen = false; save() } }

    /** True while a paid bonus still has to be shown (spins, buys and cash-outs wait for it). */
    val bonusPending: Boolean get() = vaultUnseen || seanceUnseen

    @Synchronized
    fun playSpin(stake: Int): SpinPlay? {
        if (status != LoadStatus.OK || bonusPending) return null
        if (stake !in ReaperConfig.STAKES || balance < stake) return null
        balance -= stake
        totalWagered += stake
        val r = Round.play(rng, stake, meter)
        val outcome = r.outcome
        val win = outcome.payTokens
        balance += win
        totalWon += win
        spins++
        if (win > 0) winSpins++
        if (outcome.feature) { features++; miniSeen = miniSeen or (1 shl (outcome.mini.type - 1)) }
        val vault = r.vault
        if (vault != null) {
            balance += vault.payTokens; totalWon += vault.payTokens; bonusWon += vault.payTokens; bonuses++
            noteWin(vault.payTokens)
            pendingVaultSeed = vault.seed; pendingVaultBet = vault.bet; vaultUnseen = true
        }
        val seance = r.seance
        if (seance != null) recordSeance(seance)
        noteWin(win)
        Achievements.afterSpin(this, r)
        save()
        return SpinPlay(outcome, r.relicIndex, vault, seance)
    }

    private fun recordSeance(s: SeanceResult) {
        balance += s.payTokens; totalWon += s.payTokens; seanceWon += s.payTokens; seances++
        noteWin(s.payTokens)
        pendingSeanceSeed = s.seed; pendingSeanceBet = s.bet; pendingSeanceCoffins = s.coffins; seanceUnseen = true
        maxSeanceMult = maxOf(maxSeanceMult, s.spins.maxOfOrNull { it.multiplier } ?: 0)
    }

    /** Price of a feature buy at [stake], in tokens. */
    fun buyPrice(feature: Feature, stake: Int): Long =
        (if (feature == Feature.SEANCE) ReaperConfig.BUY_SEANCE_X else ReaperConfig.BUY_VAULT_X).toLong() * stake

    @Synchronized
    fun canBuy(feature: Feature, stake: Int): Boolean =
        status == LoadStatus.OK && !bonusPending && stake in ReaperConfig.STAKES && balance >= buyPrice(feature, stake)

    /**
     * Buys a bonus round outright: the price is debited and counted as wagered, the whole round is generated from a seed,
     * paid immediately and saved before the UI shows it (so a crash can never lose or duplicate it).
     */
    @Synchronized
    fun buy(feature: Feature, stake: Int): BuyPlay? {
        if (!canBuy(feature, stake)) return null
        val price = buyPrice(feature, stake)
        balance -= price
        totalWagered += price
        buys++
        var vault: VaultResult? = null
        var seance: SeanceResult? = null
        if (feature == Feature.VAULT) {
            vault = Round.boughtVault(rng, stake)
            balance += vault.payTokens; totalWon += vault.payTokens; bonusWon += vault.payTokens; bonuses++
            noteWin(vault.payTokens)
            pendingVaultSeed = vault.seed; pendingVaultBet = vault.bet; vaultUnseen = true
        } else {
            seance = Round.boughtSeance(rng, stake)
            recordSeance(seance)
        }
        Achievements.afterBuy(this, vault, seance)
        save()
        return BuyPlay(vault, seance, price)
    }

    /** Unlocks achievement [id] (once). Called by [Achievements]. */
    internal fun unlock(id: Int) {
        if (achievements and (1 shl id) != 0) return
        achievements = achievements or (1 shl id)
        newAchievements.addLast(id)
    }

    @Synchronized
    fun saveSettings() = save()
}

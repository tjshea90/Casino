package com.tjshea.neonreaper.data

import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.engine.RoundResult
import com.tjshea.neonreaper.engine.SeanceResult
import com.tjshea.neonreaper.engine.VaultResult

/** Reaper's Ledger: cosmetic achievements (a bit each in [Wallet.achievements]). They never pay anything. */
object Achievements {
    class Def(val id: Int, val title: String, val text: String)

    const val FIRST_BLOOD = 0
    const val SOUL_REAPED = 1
    const val DEATHS_TOLL = 2
    const val HELLFIRE = 3
    const val APOCALYPSE = 4
    const val GRAVE_ROBBER = 5
    const val NAILED_SHUT = 6
    const val UNHOLY_FIVE = 7
    const val SOUL_HARVEST = 8
    const val FOUR_HORSEMEN = 9
    const val DEVILS_BARGAIN = 10
    const val RAPTURE_DENIED = 11
    const val DEATH_IS_GENEROUS = 12
    const val IRON_NERVES = 13

    val ALL = listOf(
        Def(FIRST_BLOOD, "First Blood", "Win a spin."),
        Def(SOUL_REAPED, "Soul Reaped", "Win 10x your stake in one round."),
        Def(DEATHS_TOLL, "Death's Toll", "Win 25x your stake in one round."),
        Def(HELLFIRE, "Hellfire", "Win 50x your stake in one round."),
        Def(APOCALYPSE, "Apocalypse", "Win 100x your stake in one round."),
        Def(GRAVE_ROBBER, "Grave Robber", "Open the Soul Vault."),
        Def(NAILED_SHUT, "Nailed Shut", "Land 3 coffins and start THE SEANCE."),
        Def(UNHOLY_FIVE, "The Unholy Five", "Land 5 coffins at once."),
        Def(SOUL_HARVEST, "Soul Harvest", "Reach the x10 multiplier inside THE SEANCE."),
        Def(FOUR_HORSEMEN, "Four Horsemen", "See all four mini features."),
        Def(DEVILS_BARGAIN, "Devil's Bargain", "Use a Feature Buy."),
        Def(RAPTURE_DENIED, "Rapture Denied", "Fill the whole Soul Vault board."),
        Def(DEATH_IS_GENEROUS, "Death Is Generous", "Hit the 25-book win cap."),
        Def(IRON_NERVES, "Iron Nerves", "Play 1,000 spins."),
    )

    fun isUnlocked(w: Wallet, id: Int) = w.achievements and (1 shl id) != 0

    internal fun afterSpin(w: Wallet, r: RoundResult) {
        val o = r.outcome
        if (o.payTokens > 0) w.unlock(FIRST_BLOOD)
        val x = r.totalTokens.toDouble() / o.stake
        if (x >= 10) w.unlock(SOUL_REAPED)
        if (x >= 25) w.unlock(DEATHS_TOLL)
        if (x >= 50) w.unlock(HELLFIRE)
        if (x >= 100) w.unlock(APOCALYPSE)
        if (o.scatterCount >= 5) w.unlock(UNHOLY_FIVE)
        if (w.miniSeen == 0b1111) w.unlock(FOUR_HORSEMEN)
        if (o.capped) w.unlock(DEATH_IS_GENEROUS)
        if (w.spins >= 1000) w.unlock(IRON_NERVES)
        bonuses(w, r.vault, r.seance)
    }

    internal fun afterBuy(w: Wallet, vault: VaultResult?, seance: SeanceResult?) {
        w.unlock(DEVILS_BARGAIN)
        bonuses(w, vault, seance)
    }

    private fun bonuses(w: Wallet, vault: VaultResult?, seance: SeanceResult?) {
        if (vault != null) {
            w.unlock(GRAVE_ROBBER)
            if (vault.grand) w.unlock(RAPTURE_DENIED)
            if (vault.capped) w.unlock(DEATH_IS_GENEROUS)
        }
        if (seance != null) {
            w.unlock(NAILED_SHUT)
            if (seance.coffins >= 5) w.unlock(UNHOLY_FIVE)
            if (seance.spins.any { it.multiplier >= ReaperConfig.FS_RAMP_CAP }) w.unlock(SOUL_HARVEST)
            if (seance.capped) w.unlock(DEATH_IS_GENEROUS)
        }
    }
}

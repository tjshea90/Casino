package com.tjshea.neonreaper.engine

/**
 * Relic collection and the Soul Vault bonus bet -- pure logic shared by Wallet and the tests, so the house-edge
 * tests exercise exactly the code that runs in the game.
 *
 * The bonus bet is the average stake of the FIVE SPINS THAT REVEALED THE RELICS, rounded down.
 *
 * Why not the average stake of every spin while collecting (the first version)? The collection window has a random length
 * N, and the payout is proportional to W/N. A player who bets big only while relics are arriving quickly gets a large
 * average for a cheap wager (E[1/N] > 1/E[N]); an independent audit solved the optimal policy and found 101-104 % RTP at the
 * 94 % target using only what the HUD shows (relic count + a spin count): "bet 1; with 4 relics and <= 60 spins since the
 * last bonus bet 40" alone gives 101.3 %.
 *
 * A spin reveals a relic with the same probability whatever the stake, so the stake on a relic spin is a random sample of
 * the player's stakes: by Wald's identity E[stake on a relic spin] = p * E[tokens wagered]. The expected bonus per token
 * wagered is therefore the SAME for every adaptive staking policy; rounding down and the (concave) 25-book cap can only
 * lower it. Nothing a player does with stakes can beat the advertised RTP (NoExploitTest runs the exploit policies).
 */
class RelicMeter(var relics: Int = 0, var stakeSum: Long = 0L) {

    /** A spin staked at [stake] revealed a relic. Returns the Soul Vault bonus bet if it was the last one, otherwise 0. */
    fun collect(stake: Int): Int {
        relics++
        stakeSum += stake
        if (relics < ReaperConfig.RELIC_COUNT) return 0
        val bet = betFor(stakeSum, relics)
        relics = 0
        stakeSum = 0
        return bet
    }

    /** The bonus bet if the relics still missing were revealed at [stake] -- what the HUD shows as BONUS BET. */
    fun projectedBet(stake: Int): Int =
        betFor(stakeSum + (ReaperConfig.RELIC_COUNT - relics).toLong() * stake, ReaperConfig.RELIC_COUNT)

    companion object {
        /** floor(mean stake) clamped to the stake range. Never rounds up (rounding up is exploitable). */
        fun betFor(stakeSum: Long, count: Int): Int =
            if (count <= 0) 1 else (stakeSum / count).toInt().coerceIn(1, ReaperConfig.STAKES.last())
    }
}

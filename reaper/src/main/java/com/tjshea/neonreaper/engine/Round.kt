package com.tjshea.neonreaper.engine

import java.util.Random

/** Everything one paid spin produced (all of it is decided before any animation and paid atomically by the Wallet). */
class RoundResult(
    val outcome: SpinOutcome,
    /** index (0..4) of the relic this spin revealed, or -1 */
    val relicIndex: Int,
    /** the Soul Vault the fifth relic unlocked, or null */
    val vault: VaultResult?,
    /** THE SÉANCE started by 3+ coffins, or null */
    val seance: SeanceResult?,
) {
    val bonusTokens: Long get() = (vault?.payTokens ?: 0L) + (seance?.payTokens ?: 0L)
    /** Spin win + every bonus it started. */
    val totalTokens: Long get() = outcome.payTokens + bonusTokens
}

/**
 * The game round, shared by the Wallet and the simulation tests so the tests exercise exactly the shipped logic and
 * RNG order: spin (stops, mini feature) -> relic roll -> Soul Vault seed -> Séance seed.
 */
object Round {
    fun play(rng: Random, stake: Int, meter: RelicMeter): RoundResult {
        val outcome = ReaperEngine.spin(rng, stake)
        var relicIndex = -1
        var vault: VaultResult? = null
        if (rng.nextDouble() < ReaperConfig.RELIC_CHANCE) {
            relicIndex = meter.relics
            val bet = meter.collect(stake)           // > 0 when this was the fifth relic
            if (bet > 0) vault = SoulVault.play(rng.nextLong(), bet)
        }
        val seance = if (outcome.seanceSpins > 0) Seance.play(rng.nextLong(), stake, outcome.scatterCount) else null
        return RoundResult(outcome, relicIndex, vault, seance)
    }

    /** A bought Séance (price [ReaperConfig.BUY_SEANCE_X] x stake). */
    fun boughtSeance(rng: Random, stake: Int): SeanceResult = Seance.playBought(rng.nextLong(), stake)

    /** A bought Soul Vault (price [ReaperConfig.BUY_VAULT_X] x stake); the bonus bet is the stake it was bought at. */
    fun boughtVault(rng: Random, stake: Int): VaultResult = SoulVault.play(rng.nextLong(), stake)
}

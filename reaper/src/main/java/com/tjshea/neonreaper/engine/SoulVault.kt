package com.tjshea.neonreaper.engine

import com.tjshea.neonreaper.engine.ReaperConfig.VAULT_COLS
import com.tjshea.neonreaper.engine.ReaperConfig.VAULT_ROWS
import java.util.Random

/** One orb in the Soul Vault. value is in multiples of the bonus bet. */
class Orb(val kind: Int, var value: Int) {
    companion object {
        const val SOUL = 0
        const val MINI = 1
        const val MINOR = 2
        const val MAJOR = 3
        const val REAPER = 4   // collector: takes the sum of all orbs already on screen
    }
}

class Landing(val cell: Int, val orb: Orb, /** for REAPER orbs: cells it collected from */ val collected: IntArray)

class VaultStep(val landings: List<Landing>, val respinsAfter: Int)

/**
 * Hold & Spin bonus. The full result is generated up front (so it is credited
 * atomically with the triggering spin) and then replayed by the UI. Deterministic
 * for a given seed so an interrupted bonus can be replayed after a restart.
 */
class VaultResult(
    val seed: Long,
    val bet: Int,
    val initial: List<Landing>,
    val steps: List<VaultStep>,
    val cells: Array<Orb?>,
    val grand: Boolean,
) {
    val totalMult: Int = cells.sumOf { it?.value ?: 0 } + if (grand) ReaperConfig.GRAND else 0
    val rawTokens: Long get() = totalMult.toLong() * bet
    val payTokens: Long get() = ReaperEngine.settle(rawTokens)
    val capped: Boolean get() = rawTokens > ReaperConfig.MAX_WIN_TOKENS
}

object SoulVault {
    const val CELLS = VAULT_COLS * VAULT_ROWS

    private fun orb(rng: Random, allowSpecial: Boolean): Orb {
        if (allowSpecial) {
            var x = rng.nextDouble()
            if (x < ReaperConfig.P_MAJOR) return Orb(Orb.MAJOR, ReaperConfig.MAJOR)
            x -= ReaperConfig.P_MAJOR
            if (x < ReaperConfig.P_MINOR) return Orb(Orb.MINOR, ReaperConfig.MINOR)
            x -= ReaperConfig.P_MINOR
            if (x < ReaperConfig.P_MINI) return Orb(Orb.MINI, ReaperConfig.MINI)
            x -= ReaperConfig.P_MINI
            if (x < ReaperConfig.P_REAPER) return Orb(Orb.REAPER, 0)
        }
        var w = rng.nextInt(ReaperConfig.ORB_WEIGHTS.sum())
        for (i in ReaperConfig.ORB_VALUES.indices) {
            w -= ReaperConfig.ORB_WEIGHTS[i]
            if (w < 0) return Orb(Orb.SOUL, ReaperConfig.ORB_VALUES[i])
        }
        return Orb(Orb.SOUL, 1)
    }

    fun play(seed: Long, bet: Int): VaultResult {
        val rng = Random(seed)
        val cells = arrayOfNulls<Orb>(CELLS)
        val free = (0 until CELLS).toMutableList()
        val initial = ArrayList<Landing>()
        repeat(ReaperConfig.VAULT_START_ORBS) {
            val c = free.removeAt(rng.nextInt(free.size))
            val o = orb(rng, false)
            cells[c] = o
            initial += Landing(c, o, IntArray(0))
        }
        initial.sortBy { it.cell }
        val steps = ArrayList<VaultStep>()
        var respins = ReaperConfig.VAULT_RESPINS
        while (respins > 0 && cells.any { it == null }) {
            respins--
            val landed = ArrayList<Landing>()
            // resolve column by column, top to bottom (matches the on-screen stop order)
            for (col in 0 until VAULT_COLS) for (row in 0 until VAULT_ROWS) {
                val c = row * VAULT_COLS + col
                if (cells[c] != null || rng.nextDouble() >= ReaperConfig.VAULT_ORB_CHANCE) continue
                val o = orb(rng, true)
                var collected = IntArray(0)
                if (o.kind == Orb.REAPER) {
                    val src = (0 until CELLS).filter { cells[it] != null && (cells[it]!!.kind == Orb.SOUL || cells[it]!!.kind == Orb.REAPER) }
                    collected = src.toIntArray()
                    o.value = src.sumOf { cells[it]!!.value }
                }
                cells[c] = o
                landed += Landing(c, o, collected)
            }
            if (landed.isNotEmpty()) respins = ReaperConfig.VAULT_RESPINS
            steps += VaultStep(landed, respins)
        }
        return VaultResult(seed, bet, initial, steps, cells, cells.all { it != null })
    }
}

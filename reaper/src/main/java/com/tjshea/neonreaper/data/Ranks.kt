package com.tjshea.neonreaper.data

/**
 * Soul Rank: a purely cosmetic title earned from lifetime tokens wagered (no money, no free credits -- see docs/DREAD_DESIGN.md).
 * It exists because loyalty tiers keep players coming back; here it can never cost the house anything.
 */
object Ranks {
    class Rank(val index: Int, val title: String, val from: Long, val next: Long) {
        /** 0..1 progress towards the next rank (1 at the top rank). */
        fun progress(wagered: Long): Float =
            if (next == Long.MAX_VALUE) 1f else ((wagered - from).toFloat() / (next - from)).coerceIn(0f, 1f)
        val isTop: Boolean get() = next == Long.MAX_VALUE
    }

    private val TITLES = arrayOf("Gravedigger", "Cultist", "Acolyte", "Medium", "Grave Warden", "Necromancer",
        "Soul Collector", "Death Knight", "Revenant", "Archlich", "Reaper's Chosen", "Death Incarnate")
    private val FROM = longArrayOf(0, 500, 2_000, 5_000, 12_000, 30_000, 70_000, 150_000, 300_000, 600_000, 1_200_000, 2_500_000)

    val ALL: List<Rank> = TITLES.indices.map { Rank(it, TITLES[it], FROM[it], if (it + 1 < FROM.size) FROM[it + 1] else Long.MAX_VALUE) }

    fun of(wagered: Long): Rank = ALL.last { wagered >= it.from }
}

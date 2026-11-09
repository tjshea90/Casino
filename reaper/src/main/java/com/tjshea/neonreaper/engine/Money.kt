package com.tjshea.neonreaper.engine

/** Token / mack / book conversions (same values as Dragon's Hoard). All balances are in tokens. */
object Money {
    const val TOKENS_PER_MACK = 10L
    const val MACKS_PER_BOOK = 8L
    const val TOKENS_PER_BOOK = TOKENS_PER_MACK * MACKS_PER_BOOK // 80

    /** Rounds a payout down to a whole mack (house rule for uneven winnings). */
    fun floorToMack(tokens: Long): Long = if (tokens <= 0) 0 else tokens - tokens % TOKENS_PER_MACK

    fun toTokens(books: Long, macks: Long, tokens: Long): Long =
        books * TOKENS_PER_BOOK + macks * TOKENS_PER_MACK + tokens

    fun describe(tokens: Long): String {
        if (tokens == 0L) return "0 tokens"
        val b = tokens / TOKENS_PER_BOOK
        val m = (tokens % TOKENS_PER_BOOK) / TOKENS_PER_MACK
        val t = tokens % TOKENS_PER_MACK
        val parts = ArrayList<String>(3)
        if (b > 0) parts += "$b book" + if (b == 1L) "" else "s"
        if (m > 0) parts += "$m mack" + if (m == 1L) "" else "s"
        if (t > 0) parts += "$t token" + if (t == 1L) "" else "s"
        return parts.joinToString(" ")
    }

    fun short(tokens: Long): String {
        val b = tokens / TOKENS_PER_BOOK
        val m = (tokens % TOKENS_PER_BOOK) / TOKENS_PER_MACK
        val t = tokens % TOKENS_PER_MACK
        return when {
            b > 0 -> "${b}B ${m}M" + if (t > 0) " ${t}T" else ""
            m > 0 -> "${m}M" + if (t > 0) " ${t}T" else ""
            else -> "${t}T"
        }
    }

    fun fmt(tokens: Long): String = "%,d".format(tokens)
}

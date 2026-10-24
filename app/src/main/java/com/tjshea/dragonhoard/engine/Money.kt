package com.tjshea.dragonhoard.engine

/** Token / mack / book conversions. All balances are stored in tokens. */
object Money {
    const val TOKENS_PER_MACK = 10L
    const val MACKS_PER_BOOK = 8L
    const val TOKENS_PER_BOOK = TOKENS_PER_MACK * MACKS_PER_BOOK // 80

    /** Rounds a payout down to a whole mack (the house rule for uneven winnings). */
    fun floorToMack(tokens: Long): Long = if (tokens <= 0) 0 else tokens - tokens % TOKENS_PER_MACK

    fun toTokens(books: Long, macks: Long, tokens: Long): Long =
        books * TOKENS_PER_BOOK + macks * TOKENS_PER_MACK + tokens

    /** "3 books 2 macks 5 tokens" — omits zero parts. */
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

    /** Compact "3B 2M 5T" form for tight UI. */
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

    fun macks(tokens: Long): String = "%,d".format(tokens / TOKENS_PER_MACK) +
        if (tokens % TOKENS_PER_MACK != 0L) ".${tokens % TOKENS_PER_MACK}" else ""
}

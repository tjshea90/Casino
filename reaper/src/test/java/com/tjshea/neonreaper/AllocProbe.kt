package com.tjshea.neonreaper

/**
 * Test-only: bytes allocated so far by the CURRENT thread (HotSpot's per-thread allocation counter, read by reflection because the
 * Android bootclasspath of the unit-test compile has no java.lang.management). Used to put numbers on per-frame garbage, which
 * is what causes GC jank on a low-end tablet. HotSpot may remove short-lived allocations by escape analysis, so the figures are a
 * lower bound of what ART (which does very little of that) allocates.
 */
object AllocProbe {
    private val bean: Any? = try {
        Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
    } catch (_: Throwable) { null }
    private val method = try {
        Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", java.lang.Long.TYPE)
    } catch (_: Throwable) { null }

    val available: Boolean get() = bean != null && method != null

    fun bytes(): Long = try { method!!.invoke(bean, Thread.currentThread().id) as Long } catch (_: Throwable) { -1L }
}

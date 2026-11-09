package com.tjshea.neonreaper

import com.tjshea.neonreaper.ui.runInBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** Independent review (3.0.0): the dialogs' worker thread must survive a failing wallet call (full disk, out of memory). */
class ReviewDialogsTest {
    private val inline = Executor { it.run() }

    @Test fun aFailingBackgroundCallReachesTheFailureHandlerInsteadOfKillingTheWorkerThread() {
        var failure: Throwable? = null; var result: Int? = null
        runInBackground(inline, { it.run() }, { throw java.io.IOException("No space left on device") }, { r: Int -> result = r }, { failure = it })
        assertTrue(failure is java.io.IOException)
        assertEquals(null, result)
        runInBackground(inline, { it.run() }, { 42 }, { r: Int -> result = r }, { failure = null })
        assertEquals(42, result)
    }

    @Test fun anOutOfMemoryErrorInTheBackgroundCallIsAlsoCaught() {
        var failure: Throwable? = null
        runInBackground(inline, { it.run() }, { throw OutOfMemoryError("pbkdf2") }, { _: Int -> }, { failure = it })
        assertTrue(failure is OutOfMemoryError)
    }

    @Test fun theUiCallbackIsPostedNotRunOnTheWorkerThread() {
        val posted = ArrayList<Runnable>(); var done = false
        runInBackground(inline, { posted += it }, { 1 }, { _: Int -> done = true }, { })
        assertTrue("the result must wait for the UI thread", !done && posted.size == 1)
        posted[0].run()
        assertTrue(done)
    }
}

package com.tjshea.neonreaper

import com.tjshea.neonreaper.audio.Audio
import com.tjshea.neonreaper.audio.AudioRender
import com.tjshea.neonreaper.audio.Job
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Independent review (3.0.0): rendering the 64 s BASE layers needs ~45 MB of live heap (measured with -Xmx: 48 MB passes, 40 MB fails
 * with an OutOfMemoryError), while a 1 GB Android Go phone often gives an app only 64-128 MB. The first-launch renderer therefore asks
 * whether the heap has room before it starts an asset.
 */
class ReviewAudioGuardTest {
    @Test fun theRendererAsksForRoomProportionalToTheAssetLength() {
        val base = Job.Layer(Audio.Track.BASE, 0)           // 64 s loop: ~45 MB measured
        val vault = Job.Layer(Audio.Track.VAULT, 0)         // 35.6 s loop
        val click = Job.OneShot(Audio.Sfx.CLICK)
        val need = AudioRender.heapNeed(base)
        assertTrue("BASE layer needs about 45-60 MB, said $need", need in 45_000_000L..65_000_000L)
        assertTrue(AudioRender.heapNeed(vault) < need)
        assertTrue(AudioRender.heapNeed(click) < 2_000_000L)
        assertFalse("a 64 MB app heap with 30 MB in use must not start the BASE render", AudioRender.fits(base, 34_000_000L))
        assertTrue(AudioRender.fits(base, 200_000_000L))
        assertTrue(AudioRender.fits(click, 5_000_000L))
    }

    @Test fun everyAssetOfTheFirstLaunchHasAFiniteHeapNeed() {
        for (job in AudioRender.jobs()) {
            val n = AudioRender.heapNeed(job)
            assertTrue("${job.file}: $n", n in 1L..80_000_000L)
        }
    }
}

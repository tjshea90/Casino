package com.tjshea.neonreaper.audio

import com.tjshea.neonreaper.audio.Audio.Sfx
import com.tjshea.neonreaper.audio.Audio.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The Android facade under Robolectric: robust against any call order, silent on failure, and the lazy pipeline really starts the music. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioWrapperTest {
    private val ctx get() = RuntimeEnvironment.getApplication()

    @Test fun apiNeverThrowsInAnyOrder() {
        val a = Audio(ctx)
        for (s in Sfx.values()) { a.play(s); a.play(s, 0.3f, 0.5f); a.play(s, 5f, 9f); a.play(s, -1f, Float.NaN); a.stop(s) }
        a.setIntensity(Float.NaN); a.setIntensity(-3f); a.setIntensity(7f)
        a.duck(0f); a.duck(2f, 0.1f); a.duck(-1f, 9f)
        a.playTrack(Track.SEANCE); a.playTrack(Track.VAULT); a.playTrack(Track.BASE)
        a.musicOn = false; a.musicOn = true; a.pauseMusic(); a.sfxOn = false; a.play(Sfx.WIN); a.sfxOn = true
        a.musicVolume = 0.4f; a.sfxVolume = 0.5f; a.autoDuck = false
        a.release(); a.release()
        a.play(Sfx.CLICK); a.playTrack(Track.BASE); a.setIntensity(1f); a.duck(1f); a.pauseMusic()
    }

    @Test fun lazyRenderingStartsTheBaseScoreAndSurvivesRelease() {
        val a = Audio(ctx, true)
        a.playTrack(Track.BASE)
        val end = System.currentTimeMillis() + 60_000
        while (a.playingTrack != Track.BASE && System.currentTimeMillis() < end) Thread.sleep(50)
        assertEquals("BASE music should start as soon as its first layer is rendered", Track.BASE, a.playingTrack)
        a.setIntensity(0.8f); a.duck(0.5f)
        a.playTrack(Track.VAULT)
        Thread.sleep(300)
        a.release()
        assertTrue(true)
    }

    @Test fun scaleHelpersExposeTimings() {
        assertTrue(Audio.hitSeconds(Sfx.WIN_BIG) in 0.5f..2f)
        assertTrue(Audio.durationSeconds(Sfx.WIN_APOCALYPSE) > 8f)
    }
}

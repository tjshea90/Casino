package com.tjshea.neonreaper.audio

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Renders all synthesized audio to reaper/build/audio for inspection (timing + levels). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioRenderTest {
    @Test fun render() {
        val t0 = System.currentTimeMillis()
        Audio(RuntimeEnvironment.getApplication()).renderAllTo(File("build/audio"))
        println("audio render ms: " + (System.currentTimeMillis() - t0))
    }
}

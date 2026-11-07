package com.tjshea.neonreaper.ui

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.DataOutputStream
import java.io.File

/** Writes the reel atlas to build/atlas.bin (w, h, RGBA) for the headless GL scene tests. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AtlasExportTest {
    @Test fun export() {
        val a = Atlas.build(256)
        val bytes = ByteArray(a.rgba.remaining()); a.rgba.get(bytes)
        DataOutputStream(File("build/atlas.bin").outputStream().buffered()).use { it.writeInt(a.width); it.writeInt(a.height); it.write(bytes) }
    }
}

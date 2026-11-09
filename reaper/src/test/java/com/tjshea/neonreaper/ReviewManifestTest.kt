package com.tjshea.neonreaper

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Independent review (3.0.0): the manifest asks for nothing the game does not use. */
class ReviewManifestTest {
    @Test fun noPermissionIsRequestedThatTheGameDoesNotUse() {
        val m = File("src/main/AndroidManifest.xml").readText()
        // haptics go through View.performHapticFeedback (no permission needed); there is no Vibrator call anywhere in the code
        assertFalse("VIBRATE is declared but never used", m.contains("android.permission.VIBRATE"))
        assertFalse("the game is offline", m.contains("INTERNET"))
    }
}

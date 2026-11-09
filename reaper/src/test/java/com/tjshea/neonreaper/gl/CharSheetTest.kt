package com.tjshea.neonreaper.gl

import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.gl.chars.Cast
import org.junit.Test
import java.io.File

/**
 * Renders every cast member that has a 3D model as a 2x2 contact sheet -> build/screens/cast_<symbol>.png:
 * front idle | three-quarter idle / excited | head close-up. This is how the models are judged without a device.
 * Set -Dcast=<symbolId> (e.g. via CAST env var) to render only one.
 */
class CharSheetTest {
    private val W = 640; private val H = 560

    @Test fun sheets() {
        val only = System.getenv("CAST")?.toIntOrNull()
        val hl = Headless(W, H)
        val gl = LwjglGl()
        val scene = Scene3D(gl)
        scene.init()
        for (sym in 0 until ReaperConfig.SYMBOL_COUNT) {
            if (only != null && only != sym) continue
            val model = Cast.get(sym) ?: continue
            val views = listOf(
                Triple(0f, 0f, 3.9f to 0.72f),          // front, idle (whole figure)
                Triple(-38f, 0f, 3.9f to 0.72f),        // three-quarter, idle
                Triple(0f, 1f, 3.9f to 0.72f),          // front, excited
                Triple(8f, 0f, 1.25f to 0.92f),         // head close-up
            )
            val sheet = IntArray(W * 2 * H * 2)
            for ((i, v) in views.withIndex()) {
                val (yaw, ex, cam) = v
                scene.renderPreview(model, 2.3f + i * 0.31f, ex, W, H, yawDeg = yaw, camDist = cam.first, lookY = cam.second)
                val px = hl.readArgb()
                val ox = (i % 2) * W; val oy = (i / 2) * H
                for (y in 0 until H) System.arraycopy(px, y * W, sheet, (oy + y) * W * 2 + ox, W)
            }
            Headless.writePng(File("build/screens/cast_${ReaperConfig.SYMBOL_NAMES[sym].replace(' ', '_')}.png"), W * 2, H * 2, sheet)
        }
        hl.close()
    }
}

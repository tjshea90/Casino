package com.tjshea.neonreaper.gl

import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.gl.chars.Cast
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Performance budget of the 3D cast: every model must stay light enough for a low-end tablet (MT8168) because up to 15 grid
 * cells can show live characters at once. Writes build/screens/cast_budget.txt and fails when a model blows the budget.
 */
class CastBudgetTest {
    private val MAX_TRIS = 5600
    private val MAX_DRAWS = 26

    @Test fun budget() {
        val hl = Headless(64, 64)
        val scene = Scene3D(LwjglGl())
        scene.init()
        val sb = StringBuilder()
        val failures = ArrayList<String>()
        for (sym in 0 until ReaperConfig.SYMBOL_COUNT) {
            val model = Cast.get(sym) ?: continue
            scene.renderPreview(model, 1f, 0f, 64, 64)       // warm-up: uploads the model's buffers
            Mesh.drawCalls = 0; Mesh.triangles = 0L
            scene.renderPreview(model, 1.3f, 0f, 64, 64)
            val calls = Mesh.drawCalls - 1; val tris = Mesh.triangles - 12     // minus the stone slab it stands on (a box)
            val name = ReaperConfig.SYMBOL_NAMES[sym]
            sb.append("$name: $tris tris, $calls draws\n")
            if (tris > MAX_TRIS) failures += "$name has $tris triangles (max $MAX_TRIS)"
            if (calls > MAX_DRAWS) failures += "$name needs $calls draw calls (max $MAX_DRAWS)"
        }
        File("build/screens").mkdirs()
        File("build/screens/cast_budget.txt").writeText(sb.toString())
        println(sb)
        hl.close()
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }
}

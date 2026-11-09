package com.tjshea.neonreaper.gl

import org.junit.Test
import java.io.File

/** Prints the triangle count of every world mesh (budget check; the numbers land in build/screens/world_tris.txt). */
class GothicWorldStatsTest {
    @Test fun counts() {
        val sb = StringBuilder()
        fun line(name: String, d: MeshData) { sb.append("$name: ${d.inN / 3} tris, ${d.vertexCount} verts\n") }
        line("pillar", GothicWorld.pillar(-1f))
        line("lintel(teeth)", GothicWorld.lintel(Layout3D.WORLD_W + 1.5f, true))
        line("sill", GothicWorld.lintel(Layout3D.WORLD_W + 1.5f, false))
        line("brazier", GothicWorld.brazier())
        line("archRuin", GothicWorld.archRuin())
        line("lamp", GothicWorld.lamp())
        line("fence", GothicWorld.fence())
        line("skullCandle", GothicWorld.skullCandle())
        line("gravecross", GothicWorld.gravecross())
        line("hangingCage", GothicWorld.hangingCage())
        line("deadTree(11)", Shapes.deadTree(11L, 1f))
        line("tomb", Shapes.extrude(Shapes.tombOutline(), 0.28f))
        line("ground terrain", Shapes.terrain(70f, 60f, 48, 40) { _, _ -> 0f })
        line("drum", Shapes.drum(Layout3D.R, Layout3D.REEL_W, 48))
        line("sphere(20,32)", Shapes.sphere(20, 32))
        File("build/screens").mkdirs()
        File("build/screens/world_tris.txt").writeText(sb.toString())
        println(sb)
    }
}

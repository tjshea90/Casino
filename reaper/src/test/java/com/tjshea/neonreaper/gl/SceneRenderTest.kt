package com.tjshea.neonreaper.gl

import com.tjshea.neonreaper.engine.ReaperConfig
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Renders the full 3D scene headless in several game states → build/screens/gl_*.png. */
class SceneRenderTest {
    private val W = 1280; private val H = 800

    private fun atlas(): Triple<Int, Int, ByteBuffer> {
        val f = File("build/atlas.bin")
        if (!f.exists()) { val b = ByteBuffer.allocateDirect(64 * 64 * 4).order(ByteOrder.nativeOrder()); for (i in 0 until 64 * 64) b.putInt(-1); b.position(0); return Triple(64, 64, b) }
        DataInputStream(f.inputStream().buffered()).use { d ->
            val w = d.readInt(); val h = d.readInt(); val bytes = ByteArray(w * h * 4); d.readFully(bytes)
            val b = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()); b.put(bytes).position(0)
            return Triple(w, h, b)
        }
    }

    private fun stopped(s: SceneState, grid: Array<IntArray>) {
        for (r in 0 until 5) {
            s.faceCount[r] = 5
            for (k in 0 until 5) { s.facePos[r][k] = (k - 1).toFloat(); s.faceSym[r][k] = if (k in 1..3) grid[r][k - 1] else ReaperConfig.STRIPS[r][(k * 5 + r) % 40] }
            s.fast[r] = false
        }
    }

    @Test fun scenes() {
        val hl = Headless(W, H)
        val gl = LwjglGl()
        val scene = Scene3D(gl)
        scene.init()
        // reel window where the HUD places it (matches ReaperView's landscape layout)
        scene.setLayout(Layout3D(W.toFloat(), H.toFloat(), 230f, 168f, 1050f, 628f))
        val (aw, ah, ab) = atlas(); scene.setAtlas(aw, ah, ab, 8, 4)
        val grid = arrayOf(intArrayOf(9, 2, 5), intArrayOf(0, 4, 7), intArrayOf(1, 6, 3), intArrayOf(10, 0, 8), intArrayOf(4, 5, 2))
        val s = SceneState()
        stopped(s, grid)
        scene.render(s, 3.0f, W, H); hl.save(File("build/screens/gl_idle.png"))
        // winning cells excited, others dimmed
        for (c in 0 until 15) { s.cellExcited[c] = c % 3 == 1; s.cellDim[c] = if (c % 3 == 1) 1f else 0.45f; s.cellPop[c] = if (c % 3 == 1) 1.06f else 1f }
        s.hoodedExcited = true; s.reaperExcited = true; s.souls = 0.8f
        scene.render(s, 3.3f, W, H); hl.save(File("build/screens/gl_win.png"))
        // spinning
        for (c in 0 until 15) { s.cellExcited[c] = false; s.cellDim[c] = 1f; s.cellPop[c] = 1f }
        for (r in 0 until 5) { s.fast[r] = true; for (k in 0 until 5) s.facePos[r][k] = k - 1.4f + r * 0.13f }
        s.souls = 0f; s.hoodedExcited = false; s.reaperExcited = false
        scene.render(s, 4f, W, H); hl.save(File("build/screens/gl_spin.png"))
        // mini feature: pumpkin flying in, coins on reels
        stopped(s, grid)
        s.miniSymbol = ReaperConfig.PUMPKIN; s.miniX = 640f; s.miniY = 300f; s.miniScale = 1f
        s.coin[4] = 20; s.coin[10] = 5; s.projCell[0] = 13; s.projQ[0] = 0.5f; s.projCoin = true
        scene.render(s, 5f, W, H); hl.save(File("build/screens/gl_mini.png"))
        s.miniSymbol = -1; s.projCell[0] = -1; java.util.Arrays.fill(s.coin, 0)
        s.bigWin = 0.8f; s.flash = 0.6f
        scene.render(s, 6f, W, H); hl.save(File("build/screens/gl_bigwin.png"))
        hl.close()
    }
}

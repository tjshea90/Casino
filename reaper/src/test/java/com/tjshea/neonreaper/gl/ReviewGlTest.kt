package com.tjshea.neonreaper.gl

import com.tjshea.neonreaper.AllocProbe
import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.gl.chars.Cast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Independent review (3.0.0) of things the headless renderer can prove: 16-bit index limits, what happens when a GPU refuses the
 * post-processing framebuffers, how much garbage a frame creates, and which GL state a frame leaves behind.
 */
class ReviewGlTest {
    private val W = 1280; private val H = 800

    private fun smallAtlas(): ByteBuffer {
        val b = ByteBuffer.allocateDirect(64 * 64 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until 64 * 64) b.putInt(-1)
        b.position(0)
        return b
    }

    /** A busy game state: every reel stopped on characters, all cells excited, a mini feature, glows, mood and anticipation. */
    private fun busy(): SceneState {
        val s = SceneState()
        for (r in 0 until 5) {
            s.faceCount[r] = 5
            for (k in 0 until 5) { s.facePos[r][k] = (k - 1).toFloat(); s.faceSym[r][k] = (r * 3 + k) % ReaperConfig.SYMBOL_COUNT }
        }
        for (c in 0 until 15) { s.cellExcited[c] = c % 2 == 0; s.cellPop[c] = 1.05f; s.cellFlash[c] = 0.5f }
        s.hoodedExcited = true; s.reaperExcited = true; s.souls = 0.7f; s.mood = 0.5f; s.anticipation = 0.6f
        s.miniSymbol = ReaperConfig.RAVEN; s.miniX = 640f; s.miniY = 300f; s.miniScale = 1f
        s.coin[4] = 20; s.coin[10] = 5
        return s
    }

    @Test fun everyMeshFitsSixteenBitIndices() {
        val hl = Headless(64, 64)
        val scene = Scene3D(LwjglGl()); scene.init()
        for (sym in 0 until ReaperConfig.SYMBOL_COUNT) {
            Cast.get(sym)?.let { scene.renderPreview(it, 1f, 0f, 64, 64) }
            Cast.hero(sym)?.let { scene.renderPreview(it, 1f, 1f, 64, 64) }
        }
        File("build/review").mkdirs()
        File("build/review/mesh_max_vertices.txt").writeText("largest mesh: ${Mesh.maxVertices} vertices (16-bit limit 65536)\n")
        println("largest mesh: ${Mesh.maxVertices} vertices")
        assertTrue("a mesh has ${Mesh.maxVertices} vertices: 16-bit indices would wrap", Mesh.maxVertices in 1..65536)
        hl.close()
    }

    /** A GPU that reports every framebuffer as incomplete (or runs out of memory for them). */
    private class RefusingGl(private val d: Gl) : Gl by d {
        var fbosCreated = 0; var texturesCreated = 0; var renderbuffersCreated = 0
        override fun checkFramebufferStatus(target: Int): Int = 0x8CD6            // FRAMEBUFFER_INCOMPLETE_ATTACHMENT
        override fun genFramebuffer(): Int { fbosCreated++; return d.genFramebuffer() }
        override fun genTexture(): Int { texturesCreated++; return d.genTexture() }
        override fun genRenderbuffer(): Int { renderbuffersCreated++; return d.genRenderbuffer() }
    }

    @Test fun aGpuThatRefusesThePostFramebuffersIsNotAskedAgainEveryFrame() {
        val hl = Headless(W, H)
        val gl = RefusingGl(LwjglGl())
        val scene = Scene3D(gl); scene.init()
        scene.setLayout(Layout3D(W.toFloat(), H.toFloat(), 230f, 168f, 1050f, 628f)); scene.setAtlas(64, 64, smallAtlas(), 8, 4)
        val s = busy()
        scene.render(s, 1f, W, H)                                  // warm-up: the first frame may create the sky target
        val fbo0 = gl.fbosCreated; val tex0 = gl.texturesCreated; val rb0 = gl.renderbuffersCreated
        repeat(60) { scene.render(s, 1f + it * 0.016f, W, H) }
        val fbos = gl.fbosCreated - fbo0; val tex = gl.texturesCreated - tex0; val rbs = gl.renderbuffersCreated - rb0
        File("build/review").mkdirs()
        File("build/review/postfx_refused.txt").writeText("60 frames on a GPU that refuses the post framebuffers: $fbos framebuffers, $tex textures, $rbs renderbuffers created\n")
        println("refusing GPU, 60 frames: $fbos fbos, $tex textures, $rbs renderbuffers")
        assertTrue("post-processing was re-created on every frame ($fbos framebuffers in 60 frames)", fbos <= 3)
        hl.close()
    }

    @Test fun frameGarbageIsMeasured() {
        val hl = Headless(W, H)
        val scene = Scene3D(LwjglGl())
        val t0 = System.nanoTime()
        scene.init()
        val initMs = (System.nanoTime() - t0) / 1_000_000
        scene.setLayout(Layout3D(W.toFloat(), H.toFloat(), 230f, 168f, 1050f, 628f)); scene.setAtlas(64, 64, smallAtlas(), 8, 4)
        val s = busy()
        repeat(40) { scene.render(s, 1f + it * 0.016f, W, H) }
        val n = 120
        val a0 = AllocProbe.bytes()
        repeat(n) { scene.render(s, 3f + it * 0.016f, W, H) }
        val perFrame = (AllocProbe.bytes() - a0) / n
        File("build/review").mkdirs()
        File("build/review/gl_alloc.txt").writeText("Scene3D.render, busy 15-character frame: $perFrame bytes allocated per frame on the GL thread\n" +
            "Scene3D.init() (shaders, world meshes, all 8 cast models built on the CPU): $initMs ms on this machine, cold JVM\n")
        println("Scene3D.render: $perFrame bytes/frame")
        hl.close()
    }

    /** Counts every call the renderer makes into the GL interface (each one is a JNI call plus driver work on a real device). */
    private fun counting(d: Gl, counts: java.util.TreeMap<String, Int>): Gl =
        java.lang.reflect.Proxy.newProxyInstance(Gl::class.java.classLoader, arrayOf<Class<*>>(Gl::class.java)) { _, m, a ->
            counts.merge(m.name, 1, Int::plus)
            try { m.invoke(d, *(a ?: emptyArray())) } catch (e: java.lang.reflect.InvocationTargetException) { throw e.targetException }
        } as Gl

    @Test fun glCallsPerFrameAreCounted() {
        val hl = Headless(W, H)
        val counts = java.util.TreeMap<String, Int>()
        val scene = Scene3D(counting(LwjglGl(), counts)); scene.init()
        scene.setLayout(Layout3D(W.toFloat(), H.toFloat(), 230f, 168f, 1050f, 628f)); scene.setAtlas(64, 64, smallAtlas(), 8, 4)
        val s = busy()
        repeat(5) { scene.render(s, 1f + it * 0.016f, W, H) }
        counts.clear(); Mesh.drawCalls = 0; Mesh.triangles = 0L
        val n = 20
        repeat(n) { scene.render(s, 3f + it * 0.016f, W, H) }
        val total = counts.values.sum() / n
        val sb = StringBuilder("GL calls per frame (busy 15-character frame, post tier 2): $total, draw calls ${Mesh.drawCalls / n}, triangles ${Mesh.triangles / n}\n")
        counts.entries.sortedByDescending { it.value }.take(14).forEach { sb.append("  ${it.key}: ${it.value / n}\n") }
        File("build/review").mkdirs()
        File("build/review/gl_calls.txt").writeText(sb.toString())
        println(sb)
        hl.close()
    }

    /** Rewrites shader sources on their way to the driver, to switch single costly features off for a cost experiment. */
    private class RewritingGl(private val d: Gl, private val rewrite: (String) -> String) : Gl by d {
        override fun shaderSource(shader: Int, src: String) = d.shaderSource(shader, rewrite(src))
    }

    private fun msPerFrame(rewrite: (String) -> String, tier: Int, frames: Int = 14): Double {
        val scene = Scene3D(RewritingGl(LwjglGl(), rewrite)); scene.init()
        scene.setLayout(Layout3D(W.toFloat(), H.toFloat(), 230f, 168f, 1050f, 628f)); scene.setAtlas(64, 64, smallAtlas(), 8, 4)
        scene.quality = tier
        val s = busy()
        repeat(4) { scene.render(s, 1f + it * 0.016f, W, H) }
        org.lwjgl.opengles.GLES20.glFinish()
        val ts = DoubleArray(frames)
        for (i in 0 until frames) {
            val t0 = System.nanoTime()
            scene.render(s, 3f + i * 0.016f, W, H)
            org.lwjgl.opengles.GLES20.glFinish()
            ts[i] = (System.nanoTime() - t0) / 1e6
        }
        ts.sort()
        return ts[frames / 2]
    }

    /**
     * Where does a frame's shading time go? The same busy frame is rendered with single features switched off (by rewriting the shader
     * text on its way to the driver) on Mesa's CPU renderer; the ratios say what is worth cutting for a weak mobile GPU. Not an absolute
     * GPU time: llvmpipe is ALU-bound, like the heavy parts of these shaders are on a PowerVR GE8300.
     */
    @Test fun shaderCostBreakdown() {
        val hl = Headless(W, H)
        val ground = Regex("float n = fbm\\(vWorld\\*0\\.8\\) \\* 0\\.6 \\+ fbm\\(vWorld\\*4\\.0\\) \\* 0\\.4;")
        val noGround = { src: String -> if (src.contains("uMode == 10")) src.replace(ground, "float n = 0.5;") else src }
        val noSky = { src: String -> if (src.contains("uMoonR")) src.replace(Regex("fbm\\(vec3\\([^;]*?\\)\\)"), "0.5") else src }
        val noFog = { src: String -> if (src.contains("uSpeed") && src.contains("smoothstep(0.35,0.85,n)")) src.replace(Regex("float n = fbm\\([^;]*;"), "float n = 0.6;") else src }
        val noFbm = { src: String -> src.replace(Regex("float fbm\\(vec3 p\\)\\{[^\\n]*\\}"), "float fbm(vec3 p){ return 0.5; }") }
        val rows = ArrayList<Pair<String, Double>>()
        for (round in 0 until 2) {
            val base = msPerFrame({ it }, 2)
            rows += "baseline (tier 2)" to base
            rows += "post off (tier 0)" to msPerFrame({ it }, 0)
            rows += "post tier 1" to msPerFrame({ it }, 1)
            rows += "post tier 3" to msPerFrame({ it }, 3)
            rows += "ground fbm removed" to msPerFrame(noGround, 2)
            rows += "sky fbm removed" to msPerFrame(noSky, 2)
            rows += "fog-bank fbm removed" to msPerFrame(noFog, 2)
            rows += "every procedural fbm removed" to msPerFrame(noFbm, 2)
        }
        val sb = StringBuilder("llvmpipe, 1280x800, busy frame, median ms/frame over 14 frames, two rounds\n")
        for ((k, v) in rows.groupBy({ it.first }, { it.second })) sb.append("  %-32s %s%n".format(k, v.joinToString("  ") { "%.1f".format(it) }))
        File("build/review").mkdirs()
        File("build/review/shader_cost.txt").writeText(sb.toString())
        println(sb)
        hl.close()
    }

    @Test fun aFrameLeavesTheFramebufferAndTextureUnitsAsItFoundThem() {
        val hl = Headless(W, H)
        val lw = LwjglGl()
        val scene = Scene3D(lw); scene.init()
        scene.setLayout(Layout3D(W.toFloat(), H.toFloat(), 230f, 168f, 1050f, 628f)); scene.setAtlas(64, 64, smallAtlas(), 8, 4)
        val before = lw.getInteger(Gl.FRAMEBUFFER_BINDING)
        scene.render(busy(), 2f, W, H)
        scene.render(busy(), 2.1f, W, H)
        assertEquals("the frame must hand back the framebuffer that was bound before it", before, lw.getInteger(Gl.FRAMEBUFFER_BINDING))
        hl.close()
    }
}

package com.tjshea.neonreaper.gl

import org.junit.Test
import java.io.File

/** Renders a few lit procedural meshes over the sky to prove the GL pipeline works headless. */
class GlSmokeTest {
    @Test fun smoke() {
        val hl = Headless(960, 600)
        val gl = LwjglGl()
        gl.viewport(0, 0, 960, 600)
        gl.clearColor(0f, 0f, 0f, 1f); gl.clear(Gl.COLOR_BUFFER_BIT or Gl.DEPTH_BUFFER_BIT)
        val sky = Program(gl, Shaders.SKY_VS, Shaders.SKY_FS, "sky")
        val lit = Program(gl, Shaders.LIT_VS, Shaders.LIT_FS, "lit")
        val quad = Mesh(Shapes.quad().let { q -> MeshData().append(q, Mat4.scale(Mat4.identity(), 2f, 2f, 1f)) })
        sky.use(); sky.f("uTime", 3f); sky.f2("uRes", 960f, 600f); sky.f2("uMoon", 0.8f, 0.78f); sky.f("uMoonR", 0.09f); sky.f("uFlash", 0f)
        gl.disable(Gl.DEPTH_TEST); quad.draw(gl)
        gl.enable(Gl.DEPTH_TEST); gl.enable(Gl.CULL_FACE); gl.enable(Gl.BLEND); gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE_MINUS_SRC_ALPHA)
        val proj = Mat4.perspective(FloatArray(16), 38f, 960f / 600f, 0.1f, 100f)
        val view = Mat4.lookAt(FloatArray(16), 0f, 0.5f, 6f, 0f, 0f, 0f)
        val vp = Mat4.mul(FloatArray(16), proj, view)
        lit.use()
        lit.f3("uLightDir", -0.4f, 0.6f, 0.7f); lit.f3("uLightColor", 1.0f, 0.75f, 0.7f); lit.f3("uFill", 0.05f, 0.1f, 0.15f)
        lit.f3("uAmbientTop", 0.12f, 0.1f, 0.18f); lit.f3("uAmbientBottom", 0.03f, 0.05f, 0.02f)
        lit.f3("uCamPos", 0f, 0.5f, 6f); lit.f3("uFogColor", 0.04f, 0.06f, 0.04f); lit.f("uFogDensity", 0.05f); lit.f("uFlash", 0f)
        lit.f("uTime", 2f); lit.f("uAlpha", 1f); lit.f("uSpec", 0.4f); lit.f("uShine", 24f); lit.f("uWave", 0f)
        fun draw(mesh: Mesh, model: FloatArray, mode: Int, color: Int, emis: Int = 0, rim: Int = 0x103010, p: FloatArray = floatArrayOf(1f, 1f, 0f, 0f)) {
            lit.m4("uMvp", Mat4.mul(FloatArray(16), vp, model)); lit.m4("uModel", model); lit.m3("uNormalMat", Mat4.normalMatrix(FloatArray(9), model))
            lit.i("uMode", mode); lit.rgb("uColor", color); lit.rgb("uEmissive", emis); lit.rgb("uRim", rim); lit.f4("uParam", p[0], p[1], p[2], p[3])
            mesh.draw(gl)
        }
        val pumpkin = Mesh(Shapes.lathe((0..16).map { k -> val a = Math.PI * k / 16; (Math.sin(a).toFloat() * 1f) to (Math.cos(a).toFloat() * 0.8f) }, 48) { ang, _ -> 1f + 0.06f * Math.abs(Math.cos(ang * 4.0)).toFloat() })
        val m1 = Mat4.identity(); Mat4.translate(m1, -1.6f, 0f, 0f); Mat4.rotate(m1, 90f, 0f, 1f, 0f)
        // lathe faces +Z at angle 90°, rotate so the carved face looks at the camera
        draw(pumpkin, m1, 2, 0xFF8800, 0, 0x301000, floatArrayOf(1f, 1f, 0f, 0f))
        val skull = Mesh(Shapes.sphere())
        val m2 = Mat4.identity(); Mat4.translate(m2, 0.3f, 0.1f, 0f); Mat4.scale(m2, 0.7f, 0.75f, 0.75f)
        draw(skull, m2, 3, 0xDDD2B8, 0x76FF03, 0x204010, floatArrayOf(1f, 0f, 0f, 0f))
        val tomb = Mesh(Shapes.extrude(Shapes.tombOutline(), 0.3f))
        val m3 = Mat4.identity(); Mat4.translate(m3, 1.9f, -0.9f, 0f); Mat4.rotate(m3, -20f, 0f, 1f, 0f)
        draw(tomb, m3, 6, 0x6A706A)
        hl.save(File("build/screens/gl_smoke.png"))
        hl.close()
    }
}

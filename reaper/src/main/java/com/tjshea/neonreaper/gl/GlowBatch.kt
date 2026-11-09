package com.tjshea.neonreaper.gl

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * All additive glow sprites of a frame (candle halos, eyes, blade edge, lamps ...) in ONE draw call: the scene collects them,
 * the batch writes 4 vertices per sprite into a streaming vertex buffer (layout = [MeshData] stride, packed as
 * pos = centre, normal = (corner x, corner y, size), uv = (alpha, core), colour = rgb) and a shader expands the camera-facing quads.
 * On a weak GPU driver this replaces ~150 draw calls + ~1200 uniform uploads per frame.
 */
internal class GlowBatch(private val gl: Gl, private val maxGlows: Int = 384) {
    private val prog = Program(gl, VS, FS, "glowbatch")
    private val vbo = gl.genBuffer()
    private val ibo = gl.genBuffer()
    private val vao = gl.genVertexArray()
    private val data = FloatArray(maxGlows * 4 * MeshData.STRIDE)
    private val buf: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var n = 0

    init {
        val idx = ShortArray(maxGlows * 6)
        for (i in 0 until maxGlows) {
            val b = i * 4
            idx[i * 6] = b.toShort(); idx[i * 6 + 1] = (b + 1).toShort(); idx[i * 6 + 2] = (b + 2).toShort()
            idx[i * 6 + 3] = b.toShort(); idx[i * 6 + 4] = (b + 2).toShort(); idx[i * 6 + 5] = (b + 3).toShort()
        }
        val sb: ShortBuffer = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        sb.put(idx).position(0)
        gl.bindVertexArray(vao)                                    // the batch owns a VAO: index buffer + attribute layout are recorded once
        gl.bindBuffer(Gl.ELEMENT_ARRAY_BUFFER, ibo)
        gl.bufferData(Gl.ELEMENT_ARRAY_BUFFER, sb, Gl.STATIC_DRAW)
        gl.bindBuffer(Gl.ARRAY_BUFFER, vbo)
        gl.bufferData(Gl.ARRAY_BUFFER, buf, Gl.DYNAMIC_DRAW)       // allocates the store (re-filled every frame)
        gl.vertexAttribPointer(0, 3, MeshData.BYTES, 0); gl.enableVertexAttribArray(0)
        gl.vertexAttribPointer(1, 3, MeshData.BYTES, 12); gl.enableVertexAttribArray(1)
        gl.vertexAttribPointer(2, 2, MeshData.BYTES, 24); gl.enableVertexAttribArray(2)
        gl.vertexAttribPointer(3, 4, MeshData.BYTES, 32); gl.enableVertexAttribArray(3)
    }

    fun begin() { n = 0 }

    fun add(x: Float, y: Float, z: Float, size: Float, color: Int, alpha: Float, core: Float) {
        if (n >= maxGlows) return
        val r = ((color shr 16) and 255) / 255f; val g = ((color shr 8) and 255) / 255f; val b = (color and 255) / 255f
        var o = n * 4 * MeshData.STRIDE
        for (c in 0 until 4) {
            val cx = if (c == 0 || c == 3) -0.5f else 0.5f
            val cy = if (c < 2) -0.5f else 0.5f
            data[o++] = x; data[o++] = y; data[o++] = z
            data[o++] = cx; data[o++] = cy; data[o++] = size
            data[o++] = alpha; data[o++] = core
            data[o++] = r; data[o++] = g; data[o++] = b; data[o++] = 1f
        }
        n++
    }

    fun draw(viewProj: FloatArray, camRight: FloatArray, camUp: FloatArray) {
        if (n == 0) return
        prog.use()
        prog.m4("uViewProj", viewProj)
        prog.f3("uCamRight", camRight[0], camRight[1], camRight[2]); prog.f3("uCamUp", camUp[0], camUp[1], camUp[2])
        buf.clear(); buf.put(data, 0, n * 4 * MeshData.STRIDE); buf.flip()      // upload only the sprites of this frame
        gl.bindVertexArray(vao)
        gl.bindBuffer(Gl.ARRAY_BUFFER, vbo)
        gl.bufferData(Gl.ARRAY_BUFFER, buf, Gl.DYNAMIC_DRAW)
        gl.drawElements(Gl.TRIANGLES, n * 6)
        Mesh.drawCalls++; Mesh.triangles += n * 2L
    }

    companion object {
        const val VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNormal; layout(location=2) in vec2 aUv; layout(location=3) in vec4 aColor;
uniform mat4 uViewProj; uniform vec3 uCamRight; uniform vec3 uCamUp;
out vec2 vUv; out vec3 vCol; out vec2 vParam;
void main(){
  vec3 p = aPos + (uCamRight * aNormal.x + uCamUp * aNormal.y) * aNormal.z;
  gl_Position = uViewProj * vec4(p, 1.0);
  vUv = aNormal.xy + 0.5; vCol = aColor.rgb; vParam = aUv;
}"""
        const val FS = """#version 300 es
precision mediump float;
in vec2 vUv; in vec3 vCol; in vec2 vParam; out vec4 frag;
void main(){
  float r = length(vUv - 0.5) * 2.0;
  float g = exp(-r * r * 4.0) * (1.0 - smoothstep(0.85, 1.0, r));
  float core = exp(-r * r * 40.0) * vParam.y;
  frag = vec4(vCol * g + vec3(core), (g + core) * vParam.x);
}"""
    }
}

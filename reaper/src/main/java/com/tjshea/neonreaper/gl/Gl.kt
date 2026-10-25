package com.tjshea.neonreaper.gl

import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * The subset of OpenGL ES 3.0 the 3D engine uses. On the device this is backed
 * by android.opengl.GLES30 ([AndroidGl]); in JVM tests by LWJGL on a software
 * GPU, so scenes can be rendered and inspected without a phone.
 */
interface Gl {
    fun createShader(type: Int): Int
    fun shaderSource(shader: Int, src: String)
    fun compileShader(shader: Int)
    fun getShaderi(shader: Int, pname: Int): Int
    fun getShaderInfoLog(shader: Int): String
    fun createProgram(): Int
    fun attachShader(program: Int, shader: Int)
    fun bindAttribLocation(program: Int, index: Int, name: String)
    fun linkProgram(program: Int)
    fun getProgrami(program: Int, pname: Int): Int
    fun getProgramInfoLog(program: Int): String
    fun useProgram(program: Int)
    fun getUniformLocation(program: Int, name: String): Int
    fun uniform1f(loc: Int, x: Float)
    fun uniform2f(loc: Int, x: Float, y: Float)
    fun uniform3f(loc: Int, x: Float, y: Float, z: Float)
    fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float)
    fun uniform1i(loc: Int, x: Int)
    fun uniformMatrix4fv(loc: Int, m: FloatArray)
    fun uniformMatrix3fv(loc: Int, m: FloatArray)
    fun genBuffer(): Int
    fun bindBuffer(target: Int, buffer: Int)
    fun bufferData(target: Int, data: FloatBuffer, usage: Int)
    fun bufferData(target: Int, data: ShortBuffer, usage: Int)
    fun vertexAttribPointer(index: Int, size: Int, stride: Int, offset: Int)
    fun enableVertexAttribArray(index: Int)
    fun disableVertexAttribArray(index: Int)
    fun drawElements(mode: Int, count: Int)
    fun drawArrays(mode: Int, first: Int, count: Int)
    fun genTexture(): Int
    fun bindTexture(target: Int, tex: Int)
    fun activeTexture(unit: Int)
    fun texParameteri(target: Int, pname: Int, value: Int)
    /** Uploads tightly packed RGBA8 pixels. */
    fun texImage2D(width: Int, height: Int, rgba: ByteBuffer)
    fun generateMipmap(target: Int)
    fun enable(cap: Int)
    fun disable(cap: Int)
    fun blendFunc(src: Int, dst: Int)
    fun depthMask(flag: Boolean)
    fun depthFunc(func: Int)
    fun cullFace(mode: Int)
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(mask: Int)
    fun viewport(x: Int, y: Int, w: Int, h: Int)
    fun deleteBuffer(buffer: Int)
    fun deleteTexture(tex: Int)
    fun deleteProgram(program: Int)

    companion object {
        const val VERTEX_SHADER = 0x8B31
        const val FRAGMENT_SHADER = 0x8B30
        const val COMPILE_STATUS = 0x8B81
        const val LINK_STATUS = 0x8B82
        const val ARRAY_BUFFER = 0x8892
        const val ELEMENT_ARRAY_BUFFER = 0x8893
        const val STATIC_DRAW = 0x88E4
        const val DYNAMIC_DRAW = 0x88E8
        const val TRIANGLES = 0x0004
        const val POINTS = 0x0000
        const val TRIANGLE_STRIP = 0x0005
        const val TEXTURE_2D = 0x0DE1
        const val TEXTURE0 = 0x84C0
        const val TEXTURE_MIN_FILTER = 0x2801
        const val TEXTURE_MAG_FILTER = 0x2800
        const val TEXTURE_WRAP_S = 0x2802
        const val TEXTURE_WRAP_T = 0x2803
        const val LINEAR = 0x2601
        const val LINEAR_MIPMAP_LINEAR = 0x2703
        const val CLAMP_TO_EDGE = 0x812F
        const val BLEND = 0x0BE2
        const val DEPTH_TEST = 0x0B71
        const val CULL_FACE = 0x0B44
        const val BACK = 0x0405
        const val SRC_ALPHA = 0x0302
        const val ONE = 1
        const val ONE_MINUS_SRC_ALPHA = 0x0303
        const val LEQUAL = 0x0203
        const val LESS = 0x0201
        const val COLOR_BUFFER_BIT = 0x4000
        const val DEPTH_BUFFER_BIT = 0x0100
    }
}

/** Unused helper to keep the java.nio.Buffer import meaningful for implementers. */
internal fun Buffer.bytes(): Int = capacity()

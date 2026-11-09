package com.tjshea.neonreaper.gl

import org.lwjgl.opengles.GLES30
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/** [Gl] backed by LWJGL's OpenGL ES bindings (used with Mesa's software renderer in tests). */
class LwjglGl : Gl {
    override fun createShader(type: Int) = GLES30.glCreateShader(type)
    override fun shaderSource(shader: Int, src: String) = GLES30.glShaderSource(shader, src)
    override fun compileShader(shader: Int) = GLES30.glCompileShader(shader)
    override fun getShaderi(shader: Int, pname: Int) = GLES30.glGetShaderi(shader, pname)
    override fun getShaderInfoLog(shader: Int): String = GLES30.glGetShaderInfoLog(shader)
    override fun createProgram() = GLES30.glCreateProgram()
    override fun attachShader(program: Int, shader: Int) = GLES30.glAttachShader(program, shader)
    override fun bindAttribLocation(program: Int, index: Int, name: String) = GLES30.glBindAttribLocation(program, index, name)
    override fun linkProgram(program: Int) = GLES30.glLinkProgram(program)
    override fun getProgrami(program: Int, pname: Int) = GLES30.glGetProgrami(program, pname)
    override fun getProgramInfoLog(program: Int): String = GLES30.glGetProgramInfoLog(program)
    override fun useProgram(program: Int) = GLES30.glUseProgram(program)
    override fun getUniformLocation(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)
    override fun uniform1f(loc: Int, x: Float) = GLES30.glUniform1f(loc, x)
    override fun uniform2f(loc: Int, x: Float, y: Float) = GLES30.glUniform2f(loc, x, y)
    override fun uniform3f(loc: Int, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc, x, y, z)
    override fun uniform4f(loc: Int, x: Float, y: Float, z: Float, w: Float) = GLES30.glUniform4f(loc, x, y, z, w)
    override fun uniform1i(loc: Int, x: Int) = GLES30.glUniform1i(loc, x)
    override fun uniformMatrix4fv(loc: Int, m: FloatArray) = GLES30.glUniformMatrix4fv(loc, false, m)
    override fun uniformMatrix3fv(loc: Int, m: FloatArray) = GLES30.glUniformMatrix3fv(loc, false, m)
    override fun genBuffer() = GLES30.glGenBuffers()
    override fun bindBuffer(target: Int, buffer: Int) = GLES30.glBindBuffer(target, buffer)
    override fun bufferData(target: Int, data: FloatBuffer, usage: Int) = GLES30.glBufferData(target, data, usage)
    override fun bufferData(target: Int, data: ShortBuffer, usage: Int) = GLES30.glBufferData(target, data, usage)
    override fun vertexAttribPointer(index: Int, size: Int, stride: Int, offset: Int) =
        GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, stride, offset.toLong())
    override fun enableVertexAttribArray(index: Int) = GLES30.glEnableVertexAttribArray(index)
    override fun disableVertexAttribArray(index: Int) = GLES30.glDisableVertexAttribArray(index)
    override fun drawElements(mode: Int, count: Int) = GLES30.glDrawElements(mode, count, GLES30.GL_UNSIGNED_SHORT, 0L)
    override fun drawArrays(mode: Int, first: Int, count: Int) = GLES30.glDrawArrays(mode, first, count)
    override fun genTexture() = GLES30.glGenTextures()
    override fun bindTexture(target: Int, tex: Int) = GLES30.glBindTexture(target, tex)
    override fun activeTexture(unit: Int) = GLES30.glActiveTexture(unit)
    override fun texParameteri(target: Int, pname: Int, value: Int) = GLES30.glTexParameteri(target, pname, value)
    override fun texImage2D(width: Int, height: Int, rgba: ByteBuffer) =
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba)
    override fun generateMipmap(target: Int) = GLES30.glGenerateMipmap(target)
    override fun enable(cap: Int) = GLES30.glEnable(cap)
    override fun disable(cap: Int) = GLES30.glDisable(cap)
    override fun blendFunc(src: Int, dst: Int) = GLES30.glBlendFunc(src, dst)
    override fun depthMask(flag: Boolean) = GLES30.glDepthMask(flag)
    override fun depthFunc(func: Int) = GLES30.glDepthFunc(func)
    override fun cullFace(mode: Int) = GLES30.glCullFace(mode)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES30.glClearColor(r, g, b, a)
    override fun clear(mask: Int) = GLES30.glClear(mask)
    override fun viewport(x: Int, y: Int, w: Int, h: Int) = GLES30.glViewport(x, y, w, h)
    override fun deleteBuffer(buffer: Int) = GLES30.glDeleteBuffers(buffer)
    override fun deleteTexture(tex: Int) = GLES30.glDeleteTextures(tex)
    override fun deleteProgram(program: Int) = GLES30.glDeleteProgram(program)
}

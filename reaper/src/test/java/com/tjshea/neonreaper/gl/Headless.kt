package com.tjshea.neonreaper.gl

import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10.EGL_NONE
import org.lwjgl.egl.EGL14
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES30
import org.lwjgl.system.MemoryStack
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Headless OpenGL ES 3 context (Mesa, surfaceless) with an offscreen framebuffer for tests. */
class Headless(val w: Int, val h: Int) {
    private val display: Long
    private val context: Long
    private val fbo: Int

    init {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        MemoryStack.stackPush().use { st ->
            val maj = st.mallocInt(1); val min = st.mallocInt(1)
            check(EGL14.eglInitialize(display, maj, min)) { "eglInitialize failed" }
            EGL.createDisplayCapabilities(display, maj[0], min[0])
            EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API)
            val attribs = st.ints(EGL14.EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL_NONE)
            val cfgs = st.mallocPointer(1); val n = st.mallocInt(1)
            check(EGL14.eglChooseConfig(display, attribs, cfgs, n) && n[0] > 0) { "no EGL config" }
            context = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, st.ints(0x3098 /* CONTEXT_CLIENT_VERSION */, 3, EGL_NONE))
            check(context != 0L) { "no context" }
            check(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)) { "makeCurrent failed" }
        }
        GLES.createCapabilities()
        fbo = GLES30.glGenFramebuffers()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        val color = GLES30.glGenRenderbuffers()
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, color)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_RGBA8, w, h)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_RENDERBUFFER, color)
        val depth = GLES30.glGenRenderbuffers()
        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, depth)
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, w, h)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, depth)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete" }
    }

    fun save(file: File) {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        // minimal PNG encoder (RGB, filter 0, flipped vertically)
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until h) {
            raw.write(0)
            for (x in 0 until w) { val i = ((h - 1 - y) * w + x) * 4; raw.write(buf[i].toInt()); raw.write(buf[i + 1].toInt()); raw.write(buf[i + 2].toInt()) }
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            val d = java.io.DataOutputStream(out)
            d.writeInt(data.size)
            val crc = java.util.zip.CRC32(); crc.update(type.toByteArray()); crc.update(data)
            d.write(type.toByteArray()); d.write(data); d.writeInt(crc.value.toInt())
        }
        val ihdr = java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array()
        chunk("IHDR", ihdr)
        val zbuf = java.io.ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(zbuf).use { it.write(raw.toByteArray()) }
        chunk("IDAT", zbuf.toByteArray())
        chunk("IEND", ByteArray(0))
        file.parentFile.mkdirs(); file.writeBytes(out.toByteArray())
    }

    fun close() { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT); EGL14.eglDestroyContext(display, context); EGL14.eglTerminate(display) }
}

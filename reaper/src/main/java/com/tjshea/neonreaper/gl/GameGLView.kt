package com.tjshea.neonreaper.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import com.tjshea.neonreaper.ui.Atlas
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Hosts the OpenGL 3D world behind the 2D HUD. The HUD writes [state] each frame
 * (under [lock]); this view's renderer copies it and draws the scene.
 */
@SuppressLint("ViewConstructor")
class GameGLView(context: Context) : GLSurfaceView(context), SceneSink {
    override val state = SceneState()
    override val lock = Any()
    @Volatile private var window: FloatArray? = null
    @Volatile var failed = false; private set
    var onFailed: (() -> Unit)? = null

    private val renderer = object : Renderer {
        private var scene: Scene3D? = null
        private val snapshot = SceneState()
        private var w = 1; private var h = 1
        private var applied: FloatArray? = null
        private val start = SystemClock.uptimeMillis()
        private var lastFrame = 0L; private var slowFrames = 0; private var frames = 0

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            try {
                val s = Scene3D(AndroidGl())
                s.init()
                val atlas = Atlas.build(256)
                s.setAtlas(atlas.width, atlas.height, atlas.rgba, Atlas.COLS, Atlas.ROWS)
                scene = s
                applied = null
            } catch (e: Throwable) {
                fail()
            }
        }

        override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) { w = width; h = height; applied = null }

        override fun onDrawFrame(unused: GL10?) {
            val s = scene ?: return
            val win = window
            if (win != null && win !== applied) { s.setLayout(Layout3D(w.toFloat(), h.toFloat(), win[0], win[1], win[2], win[3])); applied = win }
            synchronized(lock) { snapshot.copyFrom(state) }
            snapshot.miniX *= scale; snapshot.miniY *= scale   // HUD pixels → surface pixels
            try {
                s.render(snapshot, (SystemClock.uptimeMillis() - start) / 1000f, w, h)
            } catch (e: Throwable) {
                fail()
            }
            watchFrameRate()
        }

        /** Low-end GPUs: if frames stay slow, render the world at a reduced resolution (scaled up by the compositor). */
        private fun watchFrameRate() {
            val now = SystemClock.uptimeMillis()
            if (lastFrame != 0L && !reduced) {
                frames++
                if (now - lastFrame > 38) slowFrames++
                if (frames >= 90) {
                    if (slowFrames > 45) { reduced = true; post { reduceResolution() } }
                    frames = 0; slowFrames = 0
                }
            }
            lastFrame = now
        }

        private fun fail() {
            scene = null
            if (!failed) { failed = true; post { onFailed?.invoke() } }
        }
    }

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    @Volatile private var reduced = false

    private fun reduceResolution() {
        if (width <= 0 || height <= 0) return
        val k = 0.7f
        holder.setFixedSize((width * k).toInt(), (height * k).toInt())
        // the reel window is in view pixels; rescale it to the smaller surface
        window?.let { win -> window = floatArrayOf(win[0] * k, win[1] * k, win[2] * k, win[3] * k) }
        scale = k
    }

    @Volatile private var scale = 1f

    /** The HUD's reel window in pixels; the camera is fitted so the reels fill it exactly. */
    override fun setReelWindow(l: Float, t: Float, r: Float, b: Float) { val k = scale; window = floatArrayOf(l * k, t * k, r * k, b * k) }

    /** Pause rendering while a full-screen overlay (the Soul Vault) covers the world. */
    fun setActive(active: Boolean) { renderMode = if (active) RENDERMODE_CONTINUOUSLY else RENDERMODE_WHEN_DIRTY }

    companion object {
        fun supported(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        }
    }
}

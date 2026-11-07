package com.tjshea.neonreaper.gl

/** Where the HUD publishes the 3D world state each frame (the GL view on device, a recorder in tests). */
interface SceneSink {
    val state: SceneState
    val lock: Any
    fun setReelWindow(l: Float, t: Float, r: Float, b: Float)
}

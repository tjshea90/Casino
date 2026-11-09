package com.tjshea.neonreaper.gl.chars

import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.gl.Scene3D

/**
 * A 3D cast member. Meshes are built once (CPU) and drawn every frame with a pose.
 *
 * Character space: origin at the FEET, +Y up, facing +Z, roughly 1 unit tall (the scene scales it). Parts that animate
 * independently (head, jaw, arms, weapon) are separate meshes; everything else is merged into as few meshes as possible with
 * baked vertex colours / materials (see [com.tjshea.neonreaper.gl.PartBuilder]) so a whole character costs ~6-10 draw calls.
 *
 * Draw with [Scene3D.drawV] (vertex-material meshes) and [Scene3D.glowAt] (additive glow sprites, picked up by the bloom pass).
 */
interface CharacterModel {
    /**
     * @param mm   model matrix that places the character (feet at its origin)
     * @param t    animation clock in seconds
     * @param ex   excitement 0..1 (win / feature / being the star of a big win)
     * @param dim  brightness multiplier 0..1 (non-winning symbols dim while a win is shown)
     */
    fun draw(sc: Scene3D, mm: FloatArray, t: Float, ex: Float, dim: Float)
}

/** Registry of the new cast, keyed by reel symbol id. A symbol without a model falls back to the legacy renderer. */
object Cast {
    private val cache = HashMap<Int, CharacterModel?>()

    fun get(sym: Int): CharacterModel? = cache.getOrPut(sym) { create(sym) }

    private fun create(sym: Int): CharacterModel? = when (sym) {
        ReaperConfig.WILD -> ReaperModel()
        ReaperConfig.DOCTOR -> DoctorModel()
        ReaperConfig.DEMON -> DemonModel()
        else -> null
    }
}

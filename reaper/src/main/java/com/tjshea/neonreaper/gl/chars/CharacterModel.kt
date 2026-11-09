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

    /** The model used in reel cells (many at once: kept light). */
    fun get(sym: Int): CharacterModel? = cache.getOrPut(sym) { create(sym) }

    private val heroCache = HashMap<Int, CharacterModel?>()

    /**
     * Forgets every model. The models own GPU buffers (uploaded lazily on the GL thread); after an EGL context loss a new Scene3D
     * is created and those buffer ids are meaningless, so the registry must be rebuilt for each scene (see Scene3D.init).
     */
    @Synchronized fun reset() { cache.clear(); heroCache.clear() }

    /** Builds every model once (CPU only) so none of them causes a hitch the first time it appears on the reels. */
    @Synchronized fun warm() {
        for (sym in 0 until ReaperConfig.SYMBOL_COUNT) get(sym)
        hero(ReaperConfig.WILD)
    }

    /** The model used when ONE character is shown large (side stage, big win, mini feature, showcase): full detail where it exists. */
    fun hero(sym: Int): CharacterModel? = heroCache.getOrPut(sym) { if (sym == ReaperConfig.WILD) ReaperModel(low = false) else get(sym) }

    private fun create(sym: Int): CharacterModel? = when (sym) {
        ReaperConfig.WILD -> ReaperModel(low = true)
        ReaperConfig.DOCTOR -> DoctorModel()
        ReaperConfig.SKULL -> SkullModel()
        ReaperConfig.RAVEN -> RavenModel()
        ReaperConfig.DEMON -> DemonModel()
        ReaperConfig.WRAITH -> WraithModel()
        ReaperConfig.SCATTER -> CoffinModel()
        else -> null
    }
}

package com.tjshea.neonreaper.gl

/**
 * Everything the 3D renderer needs for one frame. The HUD (game logic) fills it on
 * the UI thread; the GL thread copies it under a lock before drawing.
 */
class SceneState {
    companion object { const val FACES = 8 }
    // reels: visible faces per reel (symbol + fractional row position, row 1 = front)
    val faceSym = Array(5) { IntArray(FACES) }
    val facePos = Array(5) { FloatArray(FACES) }
    val faceCount = IntArray(5)
    val fast = BooleanArray(5)
    val wildReel = BooleanArray(5)
    // per stopped cell (reel * 3 + row)
    val cellExcited = BooleanArray(15)
    val cellDim = FloatArray(15) { 1f }
    val cellPop = FloatArray(15) { 1f }
    val coin = IntArray(15)               // > 0: a soul coin sits on the cell
    val cellFlash = FloatArray(15)        // 0..1 flash where a wild/coin just landed
    // characters
    var hoodedExcited = false
    var reaperExcited = false
    var reaperAway = false
    var miniSymbol = -1                   // symbol id of the mini-feature character, -1 = none
    var miniX = 0f; var miniY = 0f; var miniScale = 1f      // screen px + scale (HUD decides the path)
    val projCell = IntArray(6) { -1 }; val projQ = FloatArray(6); var projCoin = false
    var reaperTouch = -1f                 // Reaper's Touch fly-in 0..1 (or -1)
    var reaperSwing = 0f
    var bigWin = -1f                      // big-win reaper rise 0..1 (or -1)
    var showcaseSym = -1; var showcaseGrow = 0f
    // effects
    var flash = 0f                        // lightning 0..1
    var souls = 0f                        // particle burst intensity 0..1
    var dim = 0f                          // darken scene (overlays)
    var mood = 0f                         // 0 = graveyard night, 1 = the Séance (blood-red, candles gutter, low fog)
    var anticipation = 0f                 // 0..1 while the last reels slow down with two coffins landed
    var seanceOpen = 0f                   // 0..1 across the coffin-burst cinematic
    var shake = 0f                        // camera shake 0..1
    var seanceCoffins = 0                 // 3..5 coffins burst open in front of the reels during the Séance cinematic (0 = none)
    var calm = false                      // "reduce flashing": no pulses, shake or strobing, softer flicker

    fun copyFrom(o: SceneState) {
        for (r in 0 until 5) { o.faceSym[r].copyInto(faceSym[r]); o.facePos[r].copyInto(facePos[r]) }
        o.faceCount.copyInto(faceCount); o.fast.copyInto(fast); o.wildReel.copyInto(wildReel)
        o.cellExcited.copyInto(cellExcited); o.cellDim.copyInto(cellDim); o.cellPop.copyInto(cellPop)
        o.coin.copyInto(coin); o.cellFlash.copyInto(cellFlash)
        hoodedExcited = o.hoodedExcited; reaperExcited = o.reaperExcited; reaperAway = o.reaperAway
        miniSymbol = o.miniSymbol; miniX = o.miniX; miniY = o.miniY; miniScale = o.miniScale
        o.projCell.copyInto(projCell); o.projQ.copyInto(projQ); projCoin = o.projCoin
        reaperTouch = o.reaperTouch; reaperSwing = o.reaperSwing; bigWin = o.bigWin
        showcaseSym = o.showcaseSym; showcaseGrow = o.showcaseGrow
        flash = o.flash; souls = o.souls; dim = o.dim
        mood = o.mood; anticipation = o.anticipation; seanceOpen = o.seanceOpen; shake = o.shake; calm = o.calm; seanceCoffins = o.seanceCoffins
    }
}

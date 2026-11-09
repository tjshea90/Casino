package com.tjshea.neonreaper.gl

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/** Test-only: records what the HUD publishes so the GL renderer can replay it (HUD + 3D composite screens). */
class RecordingSink : SceneSink {
    override val state = SceneState()
    override val lock = Any()
    val window = FloatArray(4)
    override fun setReelWindow(l: Float, t: Float, r: Float, b: Float) { window[0] = l; window[1] = t; window[2] = r; window[3] = b }
}

object SceneStateIO {
    fun write(f: File, w: Int, h: Int, window: FloatArray, s: SceneState, hud: IntArray) {
        f.parentFile.mkdirs()
        DataOutputStream(f.outputStream().buffered()).use { d ->
            d.writeInt(w); d.writeInt(h); window.forEach { d.writeFloat(it) }
            for (r in 0 until 5) { s.faceSym[r].forEach { d.writeInt(it) }; s.facePos[r].forEach { d.writeFloat(it) } }
            s.faceCount.forEach { d.writeInt(it) }; s.fast.forEach { d.writeBoolean(it) }; s.wildReel.forEach { d.writeBoolean(it) }
            s.cellExcited.forEach { d.writeBoolean(it) }; s.cellDim.forEach { d.writeFloat(it) }; s.cellPop.forEach { d.writeFloat(it) }
            s.coin.forEach { d.writeInt(it) }; s.cellFlash.forEach { d.writeFloat(it) }
            d.writeBoolean(s.hoodedExcited); d.writeBoolean(s.reaperExcited); d.writeBoolean(s.reaperAway)
            d.writeInt(s.miniSymbol); d.writeFloat(s.miniX); d.writeFloat(s.miniY); d.writeFloat(s.miniScale)
            s.projCell.forEach { d.writeInt(it) }; s.projQ.forEach { d.writeFloat(it) }; d.writeBoolean(s.projCoin)
            d.writeFloat(s.reaperTouch); d.writeFloat(s.reaperSwing); d.writeFloat(s.bigWin)
            d.writeInt(s.showcaseSym); d.writeFloat(s.showcaseGrow)
            d.writeFloat(s.flash); d.writeFloat(s.souls); d.writeFloat(s.dim)
            hud.forEach { d.writeInt(it) }
        }
    }

    class Frame(val w: Int, val h: Int, val window: FloatArray, val state: SceneState, val hud: IntArray)

    fun read(f: File): Frame = DataInputStream(f.inputStream().buffered()).use { d ->
        val w = d.readInt(); val h = d.readInt(); val win = FloatArray(4) { d.readFloat() }
        val s = SceneState()
        for (r in 0 until 5) { for (k in 0 until SceneState.FACES) s.faceSym[r][k] = d.readInt(); for (k in 0 until SceneState.FACES) s.facePos[r][k] = d.readFloat() }
        for (i in 0 until 5) s.faceCount[i] = d.readInt(); for (i in 0 until 5) s.fast[i] = d.readBoolean(); for (i in 0 until 5) s.wildReel[i] = d.readBoolean()
        for (i in 0 until 15) s.cellExcited[i] = d.readBoolean(); for (i in 0 until 15) s.cellDim[i] = d.readFloat(); for (i in 0 until 15) s.cellPop[i] = d.readFloat()
        for (i in 0 until 15) s.coin[i] = d.readInt(); for (i in 0 until 15) s.cellFlash[i] = d.readFloat()
        s.hoodedExcited = d.readBoolean(); s.reaperExcited = d.readBoolean(); s.reaperAway = d.readBoolean()
        s.miniSymbol = d.readInt(); s.miniX = d.readFloat(); s.miniY = d.readFloat(); s.miniScale = d.readFloat()
        for (i in 0 until 6) s.projCell[i] = d.readInt(); for (i in 0 until 6) s.projQ[i] = d.readFloat(); s.projCoin = d.readBoolean()
        s.reaperTouch = d.readFloat(); s.reaperSwing = d.readFloat(); s.bigWin = d.readFloat()
        s.showcaseSym = d.readInt(); s.showcaseGrow = d.readFloat()
        s.flash = d.readFloat(); s.souls = d.readFloat(); s.dim = d.readFloat()
        val hud = IntArray(w * h) { d.readInt() }
        Frame(w, h, win, s, hud)
    }
}

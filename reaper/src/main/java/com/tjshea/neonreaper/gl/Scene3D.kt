package com.tjshea.neonreaper.gl

import com.tjshea.neonreaper.engine.ReaperConfig
import com.tjshea.neonreaper.gl.chars.Cast
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The 3D world: graveyard environment, cabinet, five reel drums and the cast of
 * animated Halloween characters. Pure Kotlin over [Gl] so it runs both on the
 * device and headless in tests.
 */
class Scene3D(private val gl: Gl) {
    private lateinit var lit: Program
    private lateinit var sky: Program
    private lateinit var fog: Program
    private lateinit var glowP: Program
    private lateinit var part: Program
    private lateinit var blit: Program
    private lateinit var post: PostFx
    /** Post-processing tier: 0 off (direct to screen), 1 low, 2 medium, 3 high. Set by the GL view (auto quality). */
    var quality = 2
    val postParams = PostFx.Params()
    private var noiseTex = 0
    private var skyFbo = 0; private var skyTex = 0; private var skyW = 0; private var skyH = 0
    private var layout: Layout3D? = null
    private var atlasTex = 0
    private var atlasCols = 8
    private var atlasRows = 4
    private var ready = false

    // meshes
    private lateinit var mQuad: Mesh
    private lateinit var mScreen: Mesh
    private lateinit var mSphere: Mesh
    private lateinit var mLowSphere: Mesh
    private lateinit var mFace: Mesh
    private lateinit var mDrum: Mesh
    private lateinit var mBox: Mesh
    private lateinit var mCyl: Mesh
    private lateinit var mCone: Mesh
    private lateinit var mGround: Mesh
    private lateinit var mTomb: Mesh
    private lateinit var mCross: Mesh
    private val mTrees = ArrayList<Mesh>()
    private lateinit var mPumpkin: Mesh
    private lateinit var mStem: Mesh
    private lateinit var mRobe: Mesh
    private lateinit var mHood: Mesh
    private lateinit var mHoodIn: Mesh
    private lateinit var mGhost: Mesh
    private lateinit var mBlade: Mesh
    private lateinit var mWing: Mesh
    private lateinit var mBatWing: Mesh
    private lateinit var mCoin: Mesh
    private lateinit var mParticles: Mesh
    // gothic world (vertex-material meshes, see GothicWorld)
    private lateinit var wPillarL: Mesh; private lateinit var wPillarR: Mesh
    private lateinit var wLintel: Mesh; private lateinit var wSill: Mesh
    private lateinit var wBrazier: Mesh; private lateinit var wArch: Mesh; private lateinit var wLamp: Mesh
    private lateinit var wFence: Mesh; private lateinit var wSkullCandle: Mesh; private lateinit var wCross: Mesh; private lateinit var wCage: Mesh
    private var particleCount = 0

    // matrices / scratch
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val nrm = FloatArray(9)
    private val tmp4 = FloatArray(4)
    private val camRight = floatArrayOf(1f, 0f, 0f)
    private val camUp = floatArrayOf(0f, 1f, 0f)
    private var camX = 0f; private var camY = 0f; private var camZ = 10f

    private class Glow(var x: Float, var y: Float, var z: Float, var size: Float, var color: Int, var alpha: Float, var core: Float)
    private val glows = ArrayList<Glow>()
    private var glowUsed = 0

    // environment instances
    private class Prop(val kind: Int, val x: Float, val y: Float, val z: Float, val s: Float, val rot: Float, val tilt: Float)
    private val props = ArrayList<Prop>()

    // ================================================================== setup
    fun init() {
        lit = Program(gl, Shaders.LIT_VS, Shaders.LIT_FS, "lit")
        sky = Program(gl, Shaders.SKY_VS, Shaders.SKY_FS, "sky")
        fog = Program(gl, Shaders.FOG_VS, Shaders.FOG_FS, "fog")
        glowP = Program(gl, Shaders.GLOW_VS, Shaders.GLOW_FS, "glow")
        part = Program(gl, Shaders.PART_VS, Shaders.PART_FS, "particles")
        blit = Program(gl, PostShaders.QUAD_VS, PostShaders.BLIT_FS, "blit")
        buildMeshes()
        buildProps()
        post = PostFx(gl, mScreen)
        noiseTex = gl.genTexture()
        gl.bindTexture(Gl.TEXTURE_2D, noiseTex)
        gl.texImage2D(NoiseTex.SIZE, NoiseTex.SIZE, NoiseTex.build())
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MIN_FILTER, Gl.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MAG_FILTER, Gl.LINEAR)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_S, Gl.REPEAT)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_T, Gl.REPEAT)
        gl.generateMipmap(Gl.TEXTURE_2D)
        ready = true
    }

    fun setLayout(l: Layout3D) { layout = l }

    /** Atlas of reel symbol art (RGBA, [cols]×[rows] equal cells). */
    fun setAtlas(w: Int, h: Int, rgba: ByteBuffer, cols: Int, rows: Int) {
        if (atlasTex != 0) gl.deleteTexture(atlasTex)
        atlasTex = gl.genTexture()
        gl.bindTexture(Gl.TEXTURE_2D, atlasTex)
        gl.texImage2D(w, h, rgba)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MIN_FILTER, Gl.LINEAR_MIPMAP_LINEAR)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MAG_FILTER, Gl.LINEAR)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_S, Gl.CLAMP_TO_EDGE)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_T, Gl.CLAMP_TO_EDGE)
        gl.generateMipmap(Gl.TEXTURE_2D)
        atlasCols = cols; atlasRows = rows
    }

    private fun mesh(d: MeshData) = Mesh(d)

    private fun buildMeshes() {
        mQuad = mesh(Shapes.quad())
        mScreen = mesh(MeshData().append(Shapes.quad(), Mat4.scale(Mat4.identity(), 2f, 2f, 1f)))
        mSphere = mesh(Shapes.sphere(20, 32))
        mLowSphere = mesh(Shapes.sphere(8, 12))
        mFace = mesh(Shapes.drumFace(Layout3D.R + 0.004f, Layout3D.REEL_W * 0.96f, Layout3D.STEP * 0.96f))
        mDrum = mesh(Shapes.drum(Layout3D.R, Layout3D.REEL_W, 48))
        mBox = mesh(Shapes.extrude(listOf(0.5f to -0.5f, 0.5f to 0.5f, -0.5f to 0.5f, -0.5f to -0.5f), 1f))
        mCyl = mesh(Shapes.cylinder(1f, 1f, 14))
        mCone = mesh(Shapes.lathe(listOf(0f to 1f, 0.02f to 0.97f, 1f to 0f, 0f to 0f), 14))
        mGround = mesh(Shapes.terrain(70f, 60f, 48, 40) { x, z ->
            val far = (-z / 60f)
            (sin(x * 0.35f) * 0.25f + cos(z * 0.27f + x * 0.1f) * 0.35f) * (0.3f + far) + far * far * 3f
        })
        mTomb = mesh(Shapes.extrude(Shapes.tombOutline(), 0.28f))
        mCross = mesh(MeshData()
            .append(Shapes.extrude(listOf(0.09f to 0f, 0.09f to 1.4f, -0.09f to 1.4f, -0.09f to 0f), 0.16f), Mat4.identity())
            .append(Shapes.extrude(listOf(0.45f to 0.9f, 0.45f to 1.08f, -0.45f to 1.08f, -0.45f to 0.9f), 0.16f), Mat4.identity()))
        for (k in 0 until 3) mTrees += mesh(Shapes.deadTree(11L + k * 7, 1f))
        // jack-o'-lantern body: ribbed, slightly squashed sphere (unit radius, face toward +Z)
        mPumpkin = mesh(Shapes.lathe((0..22).map { k -> val a = PI * k / 22; (sin(a).toFloat()) to (cos(a).toFloat() * 0.82f) }, 56) { ang, k ->
            val rib = 1f + 0.075f * abs(cos(ang * 4f)) - 0.03f
            if (k == 0 || k == 22) 0.15f else rib
        })
        mStem = mesh(Shapes.lathe(listOf(0f to 1f, 0.11f to 0.98f, 0.13f to 0.8f, 0.16f to 0.4f, 0.2f to 0f, 0f to 0f), 10))
        // robe: shoulders to a ragged hem (height 1, origin at the feet)
        val hemRnd = Random(4)
        val hem = FloatArray(41) { 1f + (hemRnd.nextFloat() - 0.5f) * 0.25f }
        mRobe = mesh(Shapes.lathe(listOf(0.0f to 0.78f, 0.1f to 0.77f, 0.2f to 0.73f, 0.27f to 0.66f, 0.3f to 0.55f, 0.32f to 0.4f,
            0.35f to 0.25f, 0.4f to 0.1f, 0.44f to 0.0f, 0.38f to 0.0f), 40) { ang, k ->
            if (k >= 8) hem[((ang / (2 * PI.toFloat())) * 40).toInt().coerceIn(0, 40)] else 1f
        })
        // hood: a cowl shell with the face opening cut out toward +Z, plus a dark inner lining
        val hoodProfile = listOf(0f to 0.32f, 0.08f to 0.31f, 0.15f to 0.27f, 0.19f to 0.2f, 0.2f to 0.1f, 0.19f to 0.0f, 0.15f to -0.06f)
        mHood = mesh(cowl(hoodProfile, 40, 1f, inward = false))
        mHoodIn = mesh(cowl(hoodProfile, 40, 0.96f, inward = true))
        // ghost: dome head then a flared sheet with a wavy hem (head centre at origin, radius 1)
        mGhost = mesh(Shapes.lathe((0..10).map { k -> val a = PI / 2 * k / 10; sin(a).toFloat() to cos(a).toFloat() } +
            listOf(1.02f to -0.4f, 1.06f to -0.9f, 1.15f to -1.4f, 1.28f to -1.85f), 40) { ang, k ->
            if (k >= 13) 1f + 0.12f * sin(ang * 7f) else 1f
        })
        // scythe blade: crescent
        val blade = ArrayList<Pair<Float, Float>>()
        for (k in 0..14) { val a = PI * 0.92 * k / 14; blade += (-(1 - cos(a)) * 0.5f).toFloat() to (sin(a) * 0.32f).toFloat() }
        for (k in 14 downTo 0) { val a = PI * 0.92 * k / 14; blade += (-(1 - cos(a)) * 0.46f).toFloat() to (sin(a) * 0.2f + 0.05f).toFloat() }
        mBlade = mesh(extrudeAny(blade, 0.025f))
        mWing = mesh(Shapes.wing(1f, 0.55f, 4))
        mBatWing = mesh(Shapes.wing(1f, 0.7f, 3))
        mCoin = mesh(Shapes.coin())
        // fireflies / soul particles: random unit positions + per-particle params in the normal slot
        val pd = MeshData(); val r = Random(9)
        particleCount = 140
        for (k in 0 until particleCount) pd.vertex(r.nextFloat() - 0.5f, r.nextFloat(), r.nextFloat() - 0.5f, r.nextFloat(), 0.5f + r.nextFloat(), if (k % 5 == 0) 1f else 0f, 0f, 0f)
        mParticles = mesh(pd)
        wPillarL = mesh(GothicWorld.pillar(-1f)); wPillarR = mesh(GothicWorld.pillar(1f))
        wLintel = mesh(GothicWorld.lintel(Layout3D.WORLD_W + 1.5f, true)); wSill = mesh(GothicWorld.lintel(Layout3D.WORLD_W + 1.5f, false))
        wBrazier = mesh(GothicWorld.brazier()); wArch = mesh(GothicWorld.archRuin()); wLamp = mesh(GothicWorld.lamp())
        wFence = mesh(GothicWorld.fence()); wSkullCandle = mesh(GothicWorld.skullCandle()); wCross = mesh(GothicWorld.gravecross()); wCage = mesh(GothicWorld.hangingCage())
    }

    /** Lathed hood shell ([inward] flips it into a lining); the face opening is cut per-pixel (uParam.z). */
    private fun cowl(profile: List<Pair<Float, Float>>, seg: Int, scale: Float, inward: Boolean): MeshData {
        val m = MeshData()
        for ((k, pr) in profile.withIndex()) for (s in 0..seg) {
            val ang = 2f * PI.toFloat() * s / seg
            val r = pr.first * scale
            m.vertex(r * cos(ang), pr.second * scale, r * sin(ang), 0f, 1f, 0f, s.toFloat() / seg, k.toFloat() / (profile.size - 1))
        }
        for (k in 0 until profile.size - 1) for (s in 0 until seg) {
            val p = k * (seg + 1) + s; val q = p + seg + 1
            if (inward) m.quad(p, q, q + 1, p + 1) else m.quad(p, p + 1, q + 1, q)
        }
        return m.computeNormals()
    }

    /** Extrudes any simple (possibly concave) outline by fanning from each vertex pair — fine for thin blades. */
    private fun extrudeAny(o: List<Pair<Float, Float>>, depth: Float): MeshData {
        val m = MeshData(); val n = o.size / 2
        for (side in intArrayOf(1, -1)) {
            val base = m.vertexCount
            for ((x, y) in o) m.vertex(x, y, side * depth / 2, 0f, 0f, side.toFloat(), x, y)
            for (k in 0 until n - 1) {
                val a = base + k; val b = base + k + 1; val c = base + o.size - 2 - k; val d = base + o.size - 1 - k
                if (side > 0) { m.tri(a, b, c); m.tri(a, c, d) } else { m.tri(a, c, b); m.tri(a, d, c) }
            }
        }
        return m
    }

    private fun buildProps() {
        val r = Random(21)
        for (k in 0 until 18) {
            val side = if (k % 2 == 0) -1 else 1
            val x = side * (2.5f + r.nextFloat() * 11f)
            val z = -3f - r.nextFloat() * 20f
            props += Prop(if (r.nextFloat() < 0.25f) 1 else 0, x, 0f, z, 0.7f + r.nextFloat() * 0.6f, (r.nextFloat() - 0.5f) * 40f, (r.nextFloat() - 0.5f) * 14f)
        }
        props += Prop(2, -9f, 0f, -12f, 7f, 0f, 0f); props += Prop(3, 10.5f, 0f, -15f, 8f, 60f, 0f)
        props += Prop(4, -15f, 0f, -22f, 9f, 130f, 0f); props += Prop(2, 16f, 0f, -26f, 9f, 200f, 0f)
    }

    // ================================================================== frame
    private var t = 0f
    private val S = SceneState()

    private fun groundY(x: Float, z: Float): Float {
        val far = (-z / 60f)
        return -2.5f + (sin(x * 0.35f) * 0.25f + cos(z * 0.27f + x * 0.1f) * 0.35f) * (0.3f + far) + far * far * 3f
    }

    fun render(state: SceneState, time: Float, w: Int, h: Int) {
        if (!ready) return
        val l = layout ?: return
        S.copyFrom(state)
        t = time
        val target = gl.getInteger(Gl.FRAMEBUFFER_BINDING)
        val usePost = quality > 0 && post.begin(w, h, quality)
        if (!usePost) gl.viewport(0, 0, w, h)
        gl.clearColor(0f, 0f, 0f, 1f)
        gl.clear(Gl.COLOR_BUFFER_BIT or Gl.DEPTH_BUFFER_BIT)
        camX = l.camX; camY = l.camY; camZ = l.camZ
        l.viewProj.copyInto(vp)
        glowUsed = 0
        plN = 0
        addWorldLights()
        plWorld = plN

        // shared noise texture on unit 1 (unit 0 is the reel atlas)
        gl.activeTexture(Gl.TEXTURE1); gl.bindTexture(Gl.TEXTURE_2D, noiseTex); gl.activeTexture(Gl.TEXTURE0)

        drawSky(w, h)

        gl.enable(Gl.DEPTH_TEST); gl.depthFunc(Gl.LEQUAL); gl.depthMask(true)
        gl.enable(Gl.CULL_FACE); gl.cullFace(Gl.BACK)
        gl.enable(Gl.BLEND); gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE_MINUS_SRC_ALPHA)
        setupLit()

        drawEnvironment()
        drawCabinet()
        drawReels()
        drawSideCharacters()
        drawCoins()
        drawMini()
        drawReaperTouch()
        drawBigWin()
        drawShowcase()
        drawBats()

        // translucent: fog banks, particles, glows
        gl.depthMask(false)
        gl.disable(Gl.CULL_FACE)
        drawFogBanks()
        gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE)
        drawParticles()
        drawGlows()
        gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE_MINUS_SRC_ALPHA)
        gl.depthMask(true)

        if (usePost) {
            val p = postParams; val k = S.mood
            val beat = if (S.anticipation > 0f) S.anticipation * (if (S.calm) 0.6f else 0.5f + 0.5f * sin(time * 8.5f)) else 0f
            p.flash = S.flash * 0.35f
            p.tint[0] = 1f + 0.16f * k; p.tint[1] = 1f - 0.26f * k; p.tint[2] = 1f - 0.28f * k
            p.saturation = 0.86f + 0.06f * k
            p.vignette = 0.62f + 0.10f * k
            p.pulse = beat
            p.bloom = 0.85f + 0.30f * S.anticipation + 0.25f * S.seanceOpen + 0.35f * S.bigWin.coerceAtLeast(0f)
            p.aberration = 0.0045f + 0.006f * S.shake + 0.004f * S.anticipation
            val amp = 0.012f * S.shake
            p.shakeX = amp * sin(time * 91f) * cos(time * 37f); p.shakeY = amp * cos(time * 83f) * sin(time * 53f)
            post.end(target, w, h, quality, time, p)
        }
    }

    private fun lerp(a: Float, b: Float, k: Float) = a + (b - a) * k

    /** Lights shared by the whole frame (braziers, candles): slots 0..plWorld-1; characters add local lights after them. */
    private fun addWorldLights() {
        val fk = if (S.calm) 0.25f else 1f
        val fl = 0.85f + fk * (0.15f * sin(t * 11f) * sin(t * 4.1f) + 0.08f * sin(t * 23f))
        val red = lerp(1f, 1.45f, S.mood)
        val px = Layout3D.WORLD_W / 2 + 0.35f
        pointLight(-px - 0.3f, 0.5f, 2.3f, 7.0f, 0xFF8A3A, 0.95f * fl * red)          // candles on the left pillar
        pointLight(px + 0.3f, 0.5f, 2.3f, 7.0f, 0xFF8A3A, 0.95f * (1.7f - fl) * red)   // right pillar (out of phase)
    }

    /** The sky is expensive per pixel and soft by nature: render it at a fraction of the resolution and stretch it. */
    private fun drawSky(w: Int, h: Int) {
        val sw = 320; val sh = (320f * h / w).toInt().coerceIn(120, 640)
        val cur = gl.getInteger(Gl.FRAMEBUFFER_BINDING)
        if (skyFbo == 0 || skyW != sw || skyH != sh) {
            if (skyFbo != 0) { gl.deleteFramebuffer(skyFbo); gl.deleteTexture(skyTex) }
            skyW = sw; skyH = sh
            skyTex = gl.genTexture(); gl.bindTexture(Gl.TEXTURE_2D, skyTex); gl.texImage2DEmpty(sw, sh)
            gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MIN_FILTER, Gl.LINEAR); gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MAG_FILTER, Gl.LINEAR)
            gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_S, Gl.CLAMP_TO_EDGE); gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_T, Gl.CLAMP_TO_EDGE)
            skyFbo = gl.genFramebuffer(); gl.bindFramebuffer(Gl.FRAMEBUFFER, skyFbo)
            gl.framebufferTexture2D(Gl.FRAMEBUFFER, Gl.COLOR_ATTACHMENT0, Gl.TEXTURE_2D, skyTex, 0)
        }
        gl.bindFramebuffer(Gl.FRAMEBUFFER, skyFbo); gl.viewport(0, 0, sw, sh)
        gl.disable(Gl.DEPTH_TEST); gl.disable(Gl.CULL_FACE); gl.disable(Gl.BLEND)
        sky.use(); sky.f("uTime", t); sky.f2("uRes", sw.toFloat(), sh.toFloat()); sky.f2("uMoon", 0.83f, 0.8f); sky.f("uMoonR", 0.075f + 0.02f * S.mood); sky.f("uFlash", S.flash); sky.f("uMood", S.mood)
        mScreen.draw(gl)
        gl.bindFramebuffer(Gl.FRAMEBUFFER, cur); gl.viewport(0, 0, w, h)
        blit.use(); blit.i("uTex", 0)
        gl.bindTexture(Gl.TEXTURE_2D, skyTex)
        mScreen.draw(gl)
    }

    private fun setupLit() {
        lit.use()
        val k = S.mood
        lit.f3("uLightDir", -0.42f, 0.66f, 0.62f)
        val fl = S.flash
        lit.f3("uLightColor", lerp(0.66f, 0.95f, k) + fl, lerp(0.70f, 0.24f, k) + fl, lerp(0.92f, 0.20f, k) + fl)
        lit.f3("uFill", lerp(0.06f, 0.10f, k), lerp(0.10f, 0.02f, k), lerp(0.18f, 0.03f, k))
        lit.f3("uAmbientTop", lerp(0.13f, 0.22f, k), lerp(0.10f, 0.03f, k), lerp(0.20f, 0.05f, k))
        lit.f3("uAmbientBottom", lerp(0.04f, 0.10f, k), lerp(0.05f, 0.01f, k), lerp(0.04f, 0.01f, k))
        lit.f3("uCamPos", camX, camY, camZ)
        lit.f3("uFogColor", lerp(0.035f, 0.14f, k), lerp(0.045f, 0.012f, k), lerp(0.075f, 0.02f, k)); lit.f("uFogDensity", 0.034f)
        lit.f3("uMood", 1f + 0.12f * k, 1f - 0.34f * k, 1f - 0.38f * k)
        lit.f("uFlash", fl); lit.f("uTime", t)
        lit.i("uTex", 0); lit.i("uNoise", 1)
        uploadPointLights()
    }

    // ---- point lights (candles, eyes, flashes): up to 4 per frame, uploaded once in setupLit()
    private val plPos = FloatArray(16); private val plCol = FloatArray(12); private var plN = 0
    private var plWorld = 0; private var plDirty = true

    /** Re-uploads the point lights if they changed since the last draw (characters add local lights while they are drawn). */
    private fun flushLights() { if (plDirty) { uploadPointLights(); plDirty = false } }

    /** Call before drawing a character: drops the previous character's lights, keeps the frame's world lights. */
    internal fun beginLocalLights() { plN = plWorld; plDirty = true }

    /** Adds a point light for this frame (world position, radius, colour 0xRRGGBB scaled by [k]). Extra lights beyond 4 are ignored. */
    internal fun pointLight(x: Float, y: Float, z: Float, radius: Float, color: Int, k: Float = 1f) {
        if (plN >= 4) return
        plDirty = true
        plPos[plN * 4] = x; plPos[plN * 4 + 1] = y; plPos[plN * 4 + 2] = z; plPos[plN * 4 + 3] = radius
        plCol[plN * 3] = ((color shr 16) and 255) / 255f * k; plCol[plN * 3 + 1] = ((color shr 8) and 255) / 255f * k; plCol[plN * 3 + 2] = (color and 255) / 255f * k
        plN++
    }

    /** Point light placed at a point in a model's local space. */
    internal fun pointLightAt(m: FloatArray, lx: Float, ly: Float, lz: Float, radius: Float, color: Int, k: Float = 1f) {
        Mat4.transform(tmp4, m, lx, ly, lz)
        pointLight(tmp4[0], tmp4[1], tmp4[2], radius, color, k)
    }

    private fun uploadPointLights() {
        plDirty = false
        for (i in 0 until 4) {
            lit.f4("uPL$i", plPos[i * 4], plPos[i * 4 + 1], plPos[i * 4 + 2], plPos[i * 4 + 3])
            if (i < plN) lit.f3("uPC$i", plCol[i * 3], plCol[i * 3 + 1], plCol[i * 3 + 2]) else lit.f3("uPC$i", 0f, 0f, 0f)
        }
    }

    // ---- material draw helper
    private fun draw(mesh: Mesh, model: FloatArray, mode: Int, color: Int, emis: Int = 0, rim: Int = 0x000000,
                     spec: Float = 0.3f, shine: Float = 24f, wave: Float = 0f, alpha: Float = 1f,
                     p0: Float = 1f, p1: Float = 1f, emisK: Float = 1f, dim: Float = 1f, cut: Boolean = false) {
        Mat4.mul(mvp, vp, model)
        lit.m4("uMvp", mvp); lit.m4("uModel", model); lit.m3("uNormalMat", Mat4.normalMatrix(nrm, model))
        flushLights()
        lit.i("uMode", mode)
        lit.rgb("uColor", color, dim); lit.rgb("uEmissive", emis, emisK * dim); lit.rgb("uRim", rim, dim)
        lit.f("uSpec", spec); lit.f("uShine", shine); lit.f("uWave", wave); lit.f("uAlpha", alpha)
        lit.f4("uParam", p0, p1, if (cut) 1f else 0f, 0f)
        mesh.draw(gl)
    }

    /**
     * Draws a vertex-material mesh (the new cast / props): albedo + material id come from the mesh, [dim] darkens it,
     * [emis] is the brightness of its EMISSIVE parts, [wave] sways cloth below y = 0.4 (character space).
     */
    internal fun drawV(mesh: Mesh, model: FloatArray, emis: Float = 1f, dim: Float = 1f, alpha: Float = 1f, rim: Int = 0x000000,
                       spec: Float = 0.3f, shine: Float = 24f, wave: Float = 0f) {
        Mat4.mul(mvp, vp, model)
        lit.m4("uMvp", mvp); lit.m4("uModel", model); lit.m3("uNormalMat", Mat4.normalMatrix(nrm, model))
        flushLights()
        lit.i("uMode", 20)
        lit.f3("uColor", dim, dim, dim); lit.f3("uEmissive", 0f, 0f, 0f); lit.rgb("uRim", rim, dim)
        lit.f("uSpec", spec); lit.f("uShine", shine); lit.f("uWave", wave); lit.f("uAlpha", alpha)
        lit.f4("uParam", emis, 1f, 0f, 0f)
        mesh.draw(gl)
    }

    internal fun glow(x: Float, y: Float, z: Float, size: Float, color: Int, alpha: Float = 1f, core: Float = 0.6f) {
        if (glowUsed >= glows.size) glows += Glow(0f, 0f, 0f, 0f, 0, 0f, 0f)
        val g = glows[glowUsed++]
        g.x = x; g.y = y; g.z = z; g.size = size; g.color = color; g.alpha = alpha; g.core = core
    }

    /** Glow at a point in a model's local space. */
    internal fun glowAt(m: FloatArray, lx: Float, ly: Float, lz: Float, size: Float, color: Int, alpha: Float = 1f, core: Float = 0.6f) {
        Mat4.transform(tmp4, m, lx, ly, lz)
        glow(tmp4[0], tmp4[1], tmp4[2], size, color, alpha, core)
    }

    private fun m(): FloatArray = Mat4.identity()
    private fun copy(a: FloatArray) = a.copyOf()

    // ================================================================== environment
    private class Place(val x: Float, val z: Float, val s: Float, val ry: Float)
    private val archPlaces = arrayOf(Place(-6.4f, -8f, 1.0f, 14f), Place(6.9f, -10f, 1.1f, -16f), Place(-12.5f, -18f, 1.7f, 8f), Place(13.5f, -22f, 1.9f, -10f))
    private val lampPlaces = arrayOf(Place(-5.6f, -2.2f, 1f, 0f), Place(5.7f, -2.6f, 1f, 180f), Place(-8.2f, -6f, 1.1f, 0f), Place(8.6f, -7.5f, 1.1f, 180f))
    private val fencePlaces = arrayOf(Place(-4.6f, -5.5f, 1f, 4f), Place(-7.0f, -5.7f, 1f, -3f), Place(-9.4f, -6.0f, 1f, 5f),
        Place(4.7f, -5.6f, 1f, -5f), Place(7.1f, -5.8f, 1f, 3f), Place(9.5f, -6.1f, 1f, -4f))
    private val candlePlaces = arrayOf(Place(-3.9f, -3.2f, 1f, 30f), Place(4.1f, -3.6f, 1f, -25f), Place(-1.2f, -4.4f, 1f, 10f))
    private val braziers = arrayOf(Place(-5.7f, -4.2f, 1f, 0f), Place(5.8f, -4.6f, 1f, 0f))

    private fun placed(mesh: Mesh, pl: Place, dy: Float = 0f, scale: Float = pl.s, rim: Int = 0x1B2C48, spec: Float = 0.25f, emis: Float = 1f, dim: Float = 1f) {
        val mm = m(); Mat4.translate(mm, pl.x, groundY(pl.x, pl.z) + dy, pl.z); Mat4.rotate(mm, pl.ry, 0f, 1f, 0f); Mat4.scale(mm, scale, scale, scale)
        drawV(mesh, mm, emis = emis, dim = dim, rim = rim, spec = spec)
    }

    private fun drawEnvironment() {
        val g = m(); Mat4.translate(g, 0f, -2.5f, 2f)
        draw(mGround, g, 10, 0x0A120A, spec = 0.05f)
        for (p in props) {
            val gy = groundY(p.x, p.z)
            val mm = m(); Mat4.translate(mm, p.x, gy - 0.05f, p.z); Mat4.rotate(mm, p.rot, 0f, 1f, 0f); Mat4.rotate(mm, p.tilt, 0f, 0f, 1f)
            when (p.kind) {
                0 -> { Mat4.scale(mm, p.s, p.s, p.s); draw(mTomb, mm, 6, 0x5F665E, spec = 0.15f) }
                1 -> { Mat4.scale(mm, p.s * 0.9f, p.s * 0.9f, p.s * 0.9f); drawV(wCross, mm, rim = 0x1B2C48, spec = 0.2f) }
                else -> { Mat4.scale(mm, p.s, p.s, p.s); draw(mTrees[(p.kind - 2) % mTrees.size], mm, 7, 0x2A1E14, rim = 0x200810, spec = 0.05f) }
            }
        }
        for (a in archPlaces) placed(wArch, a, dy = -0.2f, spec = 0.15f)
        for (f in fencePlaces) placed(wFence, f, spec = 0.4f)
        for (l in lampPlaces) {
            placed(wLamp, l, spec = 0.4f)
            val fl = 0.8f + 0.2f * sin(t * 9f + l.x)
            val gx = l.x + 0.30f * l.s * cos(Math.toRadians(l.ry.toDouble())).toFloat(); val gz = l.z - 0.30f * l.s * sin(Math.toRadians(l.ry.toDouble())).toFloat()
            glow(gx, groundY(l.x, l.z) + 2.05f * l.s, gz, 1.5f * l.s, 0x9CFF57, 0.55f * fl, 0.5f)
        }
        for ((k, c) in candlePlaces.withIndex()) {
            placed(wSkullCandle, c, spec = 0.35f)
            val fl = 0.8f + 0.2f * sin(t * 12f + k * 2.1f) * sin(t * 5.3f + k)
            val gy = groundY(c.x, c.z)
            glow(c.x, gy + 0.82f, c.z, 0.35f * fl, 0xFFFFFF, 0.8f, 1f)
            glow(c.x, gy + 0.82f, c.z, 1.3f * fl, 0xFF8A3A, 0.55f, 0.2f)
            glow(c.x, gy + 0.55f, c.z + 0.2f, 0.5f, 0xFF7A20, 0.35f, 0.1f)    // the skull's burning eyes
        }
        for ((k, b) in braziers.withIndex()) {
            val gy = groundY(b.x, b.z)
            val ped = m(); Mat4.translate(ped, b.x, gy + 0.55f, b.z); Mat4.scale(ped, 0.8f, 1.1f, 0.8f)
            draw(mBox, ped, 6, 0x4A4D52, spec = 0.12f)
            val mm = m(); Mat4.translate(mm, b.x, gy + 1.1f, b.z); Mat4.scale(mm, 1.25f, 1.25f, 1.25f)
            drawV(wBrazier, mm, emis = 1.1f + 0.25f * sin(t * 13f + k * 3f), rim = 0x301008, spec = 0.3f)
            val fl = 0.8f + 0.2f * sin(t * 10f + k * 1.7f) * sin(t * 4.3f)
            glow(b.x, gy + 1.95f, b.z, 1.0f * fl, 0xFFB050, 0.85f, 0.7f)
            glow(b.x, gy + 2.0f, b.z, 3.2f * fl, 0xFF5A1A, 0.45f, 0f)
        }
        // hanging iron cages with burning skulls, swaying on chains from beyond the top of the frame
        for ((k, x) in floatArrayOf(-5.1f, 5.3f).withIndex()) {
            val sw = sin(t * 0.7f + k * 2f) * 5f
            val mm = m(); Mat4.translate(mm, x, 3.4f, -1.5f); Mat4.rotate(mm, sw, 0f, 0f, 1f); Mat4.scale(mm, 1.7f, 1.7f, 1.7f)
            drawV(wCage, mm, rim = 0x1B2C48, spec = 0.35f)
            glowAt(mm, 0f, -0.85f, 0.1f, 0.9f, 0x9CFF57, 0.55f, 0.3f)
        }
    }

    private fun drawFogBanks() {
        fog.use(); fog.f("uTime", t)
        val banks = arrayOf(floatArrayOf(-1.8f, 0f, 60f, 3f, 0.55f, 0.06f), floatArrayOf(-2.0f, -6f, 70f, 4f, 0.5f, -0.04f),
            floatArrayOf(-1.6f, -14f, 90f, 6f, 0.45f, 0.05f), floatArrayOf(-2.3f, 2.4f, 40f, 1.6f, 0.35f, -0.08f))
        for (b in banks) {
            val mm = m(); Mat4.translate(mm, camX * 0.3f, b[0], b[1]); Mat4.scale(mm, b[2], b[3], 1f)
            Mat4.mul(mvp, vp, mm)
            fog.m4("uMvp", mvp); fog.f3("uColor", 0.45f, 0.62f, 0.42f); fog.f("uAlpha", b[4]); fog.f("uSpeed", b[5])
            mQuad.draw(gl)
        }
        lit.use()
    }

    private fun drawParticles() {
        part.use()
        part.m4("uViewProj", vp); part.f("uTime", t)
        part.f3("uOrigin", 0f, -2.2f, -4f); part.f3("uBox", 22f, 6f, 14f); part.f("uRise", 0.12f)
        part.f("uScale", 60f + S.souls * 80f)
        part.f3("uColor", 0.45f, 1f, 0.1f); part.f3("uColor2", 1f, 0.25f, 0.2f)
        mParticles.upload(gl)
        drawPoints(mParticles)
        if (S.souls > 0.01f) {
            part.f3("uOrigin", 0f, -1.6f, 2.2f); part.f3("uBox", 7f, 4f, 1.5f); part.f("uRise", 1.2f); part.f("uScale", 140f * S.souls)
            drawPoints(mParticles)
        }
        lit.use()
    }

    private fun drawPoints(mesh: Mesh) {
        // the particle mesh's vertices are drawn as points; indices are unused
        mesh.bindForPoints(gl)
        gl.drawArrays(Gl.POINTS, 0, particleCount)
    }

    private fun drawGlows() {
        glowP.use(); glowP.m4("uViewProj", vp)
        glowP.f3("uCamRight", camRight[0], camRight[1], camRight[2]); glowP.f3("uCamUp", camUp[0], camUp[1], camUp[2])
        for (k in 0 until glowUsed) {
            val g = glows[k]
            glowP.f3("uCenter", g.x, g.y, g.z); glowP.f2("uSize", g.size, g.size)
            glowP.rgb("uColor", g.color); glowP.f("uAlpha", g.alpha); glowP.f("uCore", g.core)
            mQuad.draw(gl)
        }
        lit.use()
    }

    // ================================================================== cabinet
    private fun drawCabinet() {
        val R = Layout3D.R; val ww = Layout3D.WORLD_W
        val edgeY = R * sin(1.5f * Layout3D.STEP)
        // dark back panel so gaps between drums show the cabinet, not the graveyard
        val back = m(); Mat4.translate(back, 0f, 0f, -0.6f); Mat4.scale(back, ww + 0.4f, edgeY * 2 + 0.6f, 0.2f)
        draw(mBox, back, 0, 0x050806, spec = 0f)
        val rimC = 0x203A5E
        val top = m(); Mat4.translate(top, 0f, edgeY + 0.17f, 1.15f); drawV(wLintel, top, rim = rimC, spec = 0.4f)
        val bot = m(); Mat4.translate(bot, 0f, -(edgeY + 0.17f), 1.15f); drawV(wSill, bot, rim = rimC, spec = 0.4f)
        // inner edge light strips: blood red above, sickly green below
        for (sgn in intArrayOf(1, -1)) {
            val tube = m(); Mat4.translate(tube, 0f, sgn * (edgeY + 0.0f), 1.58f); Mat4.rotate(tube, 90f, 0f, 0f, 1f); Mat4.scale(tube, 0.028f, ww + 0.2f, 0.028f)
            draw(mCyl, tube, 9, 0, emis = if (sgn > 0) 0xFF2236 else 0x76FF03, emisK = 0.8f)
            var x = -ww / 2 + 0.3f
            while (x <= ww / 2) { glow(x, sgn * edgeY, 1.62f, 0.42f, if (sgn > 0) 0xFF2236 else 0x76FF03, 0.22f, 0.15f); x += 0.6f }
        }
        // pillars with their candelabras
        val px = ww / 2 + 0.35f
        val pl = m(); Mat4.translate(pl, -px, -2.1f, 1.15f); drawV(wPillarL, pl, rim = rimC, spec = 0.35f, emis = 1.1f + 0.3f * S.mood)
        val pr = m(); Mat4.translate(pr, px, -2.1f, 1.15f); drawV(wPillarR, pr, rim = rimC, spec = 0.35f, emis = 1.1f + 0.3f * S.mood)
        for (sgn in intArrayOf(-1, 1)) {
            for ((k, off) in floatArrayOf(-0.07f, 0f, 0.07f).withIndex()) {
                val fl = 0.78f + 0.22f * sin(t * (10f + k * 3.1f) + sgn * 2f) * sin(t * 4.7f + k)
                val hgt = floatArrayOf(0.20f, 0.30f, 0.16f)[k]
                val fx = sgn * px + off; val fy = -2.1f + 2.22f + hgt + 0.06f; val fz = 1.15f + 0.74f
                glow(fx, fy, fz, 0.16f * fl, 0xFFFFFF, 0.9f, 1f)
                glow(fx, fy, fz, 0.55f * fl, 0xFFA040, 0.6f, 0.15f)
            }
            glow(sgn * px, 0.55f, 1.95f, 2.6f, 0xFF7A20, 0.18f + 0.07f * sin(t * 7f + sgn), 0f)
            // the skull crown's burning eyes
            for (e in floatArrayOf(-1f, 1f)) glow(sgn * px + e * 0.065f - sgn * 0.02f, -2.1f + 3.88f, 1.15f + 0.3f, 0.2f, 0xFF2236, 0.8f, 0.4f)
        }
        // reel separators: iron rods
        for (k in 1 until 5) {
            val x = Layout3D.reelX(k) - (Layout3D.REEL_W + Layout3D.GAP) / 2
            val sep = m(); Mat4.translate(sep, x, 0f, R + 0.05f); Mat4.scale(sep, 0.03f, edgeY * 2, 0.03f)
            draw(mCyl, sep, 8, 0x3A453C, spec = 1f)
        }
    }

    // ================================================================== reels
    private fun drawReels() {
        for (r in 0 until 5) {
            val x = Layout3D.reelX(r)
            val drum = m(); Mat4.translate(drum, x, 0f, 0f)
            draw(mDrum, drum, 0, 0x0B130B, rim = 0x0A2008, spec = 0.2f)
            val n = S.faceCount[r]
            for (k in 0 until n) {
                val pos = S.facePos[r][k]
                val a = Layout3D.rowAngle(pos)
                if (abs(a) > 1.3f) continue
                val sym = S.faceSym[r][k]
                val face = m(); Mat4.translate(face, x, 0f, 0f); Mat4.rotate(face, -Math.toDegrees(a.toDouble()).toFloat(), 1f, 0f, 0f)
                val stoppedRow = if (!S.fast[r] && abs(pos - Math.round(pos)) < 0.02f && Math.round(pos) in 0..2) Math.round(pos) else -1
                val cell = if (stoppedRow >= 0) r * 3 + stoppedRow else -1
                val dim = if (cell >= 0) S.cellDim[cell] else 1f
                val excited = cell >= 0 && S.cellExcited[cell]
                val pop = if (cell >= 0) S.cellPop[cell] else 1f
                val flash = if (cell >= 0) S.cellFlash[cell] else 0f
                val character = sym <= ReaperConfig.WRAITH || (sym == ReaperConfig.SCATTER && Cast.get(sym) != null)
                if (S.fast[r] || !character) {
                    // painted face from the atlas (blurred when spinning fast)
                    val idx = if (S.fast[r]) sym + ReaperConfig.SYMBOL_COUNT else sym
                    lit.f4("uUvRect", (idx % atlasCols).toFloat() / atlasCols, (idx / atlasCols).toFloat() / atlasRows, 1f / atlasCols, 1f / atlasRows)
                    gl.activeTexture(Gl.TEXTURE0); gl.bindTexture(Gl.TEXTURE_2D, atlasTex)
                    val f2 = face.copyOf()
                    if (pop != 1f) { Mat4.translate(f2, 0f, 0f, Layout3D.R); Mat4.scale(f2, pop, pop, pop); Mat4.translate(f2, 0f, 0f, -Layout3D.R) }
                    draw(mFace, f2, 1, 0xFFFFFF, rim = 0x000000, spec = 0.35f, shine = 30f, p0 = if (excited) 0.35f + 0.25f * sin(t * 9f) else 0.08f, dim = dim)
                } else {
                    // a live 3D character at the face, kept upright toward the viewer
                    val ch = m()
                    Mat4.translate(ch, x, Layout3D.R * sin(a), Layout3D.R * cos(a) + 0.05f)
                    Mat4.rotate(ch, -Math.toDegrees(a.toDouble()).toFloat() * 0.35f, 1f, 0f, 0f)
                    val s = 0.86f * pop * (0.55f + 0.45f * cos(a))
                    Mat4.translate(ch, 0f, -0.43f * s, 0f)
                    Mat4.scale(ch, s, s, s)
                    drawCharacter(sym, ch, t + r * 0.37f + k * 0.61f, excited, dim * (0.45f + 0.55f * cos(a)))
                }
                if (flash > 0f) glowAt(face, 0f, 0f, Layout3D.R + 0.2f, 1.4f, 0xCCFF90, flash, 0.8f)
            }
            if (S.wildReel[r]) drawHellfire(x)
        }
    }

    private fun drawHellfire(x: Float) {
        val edge = Layout3D.R * sin(1.5f * Layout3D.STEP)
        for (k in 0 until 7) {
            val ph = ((t * 0.9f + k * 0.143f) % 1f)
            val y = -edge + ph * edge * 2
            glow(x + sin(t * 3 + k) * 0.25f, y, Layout3D.R + 0.3f, 0.7f * (1 - ph * 0.5f), 0x76FF03, 0.45f * (1 - ph), 0.3f)
        }
        glow(x, 0f, Layout3D.R + 0.25f, 2.6f, 0x4CAF50, 0.25f + 0.1f * sin(t * 8), 0f)
    }

    private fun drawCharacter(sym: Int, mm: FloatArray, tt: Float, ex: Boolean, dim: Float = 1f) {
        val model = Cast.get(sym)
        if (model != null) { beginLocalLights(); model.draw(this, mm, tt, if (ex) 1f else 0f, dim); beginLocalLights(); return }
        when (sym) {
            ReaperConfig.WILD -> drawReaper(mm, tt, ex, dim, scythe = true, crimson = false)
            ReaperConfig.HOODED -> drawReaper(mm, tt, ex, dim, scythe = false, crimson = true)
            ReaperConfig.SKULL -> { val s = copy(mm); Mat4.translate(s, 0f, 0.48f, 0f); Mat4.scale(s, 0.42f, 0.42f, 0.42f); drawSkull(s, tt, ex, dim) }
            ReaperConfig.RAVEN -> drawRaven(mm, tt, ex, dim, perched = true)
            ReaperConfig.PUMPKIN -> { val s = copy(mm); Mat4.translate(s, 0f, 0.4f, 0f); Mat4.scale(s, 0.42f, 0.42f, 0.42f); drawPumpkin(s, tt, ex, dim) }
            ReaperConfig.GHOST -> { val s = copy(mm); Mat4.translate(s, 0f, 0.62f + sin(tt * 2f) * 0.04f, 0f); Mat4.scale(s, 0.26f, 0.26f, 0.26f); drawGhost(s, tt, ex, dim) }
        }
    }

    // ================================================================== characters
    /** Jack-o'-lantern centred on the origin, radius 1, face toward +Z. */
    private fun drawPumpkin(mm: FloatArray, tt: Float, ex: Boolean, dim: Float = 1f) {
        val bounce = if (ex) abs(sin(tt * 9f)) * 0.25f else 0f
        val b = copy(mm); Mat4.translate(b, 0f, bounce, 0f)
        if (ex) Mat4.scale(b, 1f + 0.06f * cos(tt * 18f), 1f - 0.06f * cos(tt * 18f), 1f)
        val flicker = 0.75f + 0.25f * sin(tt * 13f) * sin(tt * 7.3f)
        draw(mPumpkin, b, 2, 0xFF8800, rim = 0x401800, spec = 0.35f, shine = 18f, p0 = if (ex) 1.3f else flicker, p1 = if (ex) 1.8f else 1f, dim = dim)
        val st = copy(b); Mat4.translate(st, 0.05f, 0.7f, 0f); Mat4.rotate(st, 12f, 0f, 0f, 1f); Mat4.scale(st, 0.35f, 0.4f, 0.35f)
        draw(mStem, st, 0, 0x3E5A1A, spec = 0.2f, dim = dim)
        glowAt(b, 0f, -0.05f, 1.05f, 2.2f, 0xFF9800, (if (ex) 0.5f else 0.3f * flicker) * dim, 0.2f)
    }

    /** Skull: cranium centred on the origin (radius ~1), jaw below; jaw chatters when excited. */
    private fun drawSkull(mm: FloatArray, tt: Float, ex: Boolean, dim: Float = 1f) {
        val tilt = if (ex) sin(tt * 12f) * 8f else sin(tt * 0.9f) * 3f
        val sk = copy(mm); Mat4.rotate(sk, tilt, 0f, 0f, 1f)
        val head = copy(sk); Mat4.scale(head, 0.95f, 1f, 1f)
        val eyes = if (ex) 1.8f else 0.8f + 0.25f * sin(tt * 3f)
        draw(mSphere, head, 3, 0xDDD2B8, emis = 0x76FF03, rim = 0x204010, spec = 0.4f, shine = 30f, p0 = eyes, dim = dim)
        val open = if (ex) abs(sin(tt * 22f)) else if (((tt * 0.31f) % 1f) < 0.06f) abs(sin(tt * 30f)) * 0.6f else 0f
        val jaw = copy(sk); Mat4.translate(jaw, 0f, -0.7f - open * 0.2f, 0.32f); Mat4.rotate(jaw, open * 12f, 1f, 0f, 0f); Mat4.scale(jaw, 0.48f, 0.2f, 0.42f)
        draw(mSphere, jaw, 0, 0xBFB49A, rim = 0x204010, spec = 0.3f, dim = dim)
        // teeth rows
        for (k in -3..3) {
            val tooth = copy(sk); Mat4.translate(tooth, k * 0.12f, -0.62f, 0.72f - abs(k) * 0.05f); Mat4.scale(tooth, 0.05f, 0.09f, 0.05f)
            draw(mLowSphere, tooth, 0, 0xF2EEE0, spec = 0.6f, dim = dim)
            val low = copy(sk); Mat4.translate(low, k * 0.11f, -0.72f - open * 0.18f, 0.66f - abs(k) * 0.05f); Mat4.scale(low, 0.045f, 0.07f, 0.045f)
            draw(mLowSphere, low, 0, 0xE6E0CE, spec = 0.6f, dim = dim)
        }
        glowAt(sk, -0.36f, 0.05f, 1f, 0.9f, 0x76FF03, 0.5f * eyes * dim, 0.5f)
        glowAt(sk, 0.36f, 0.05f, 1f, 0.9f, 0x76FF03, 0.5f * eyes * dim, 0.5f)
    }

    /**
     * Grim Reaper (or the crimson Hooded Watcher): tattered robe, deep cowl, a
     * shadowed skull with ember-red eyes, skeletal fingers; the reaper carries a
     * scythe and laughs (jaw) when excited. Origin at the feet, height ~1.
     */
    private fun drawReaper(mm: FloatArray, tt: Float, ex: Boolean, dim: Float, scythe: Boolean, crimson: Boolean) {
        val sway = if (ex) sin(tt * 9f) * 4f else sin(tt * 1.3f) * 1.2f
        val b = copy(mm); Mat4.rotate(b, sway, 0f, 0f, 1f)
        val cloth = if (crimson) 0x3A0712 else 0x15151C
        val rim = if (crimson) 0x9F0020 else 0x3A9E14
        draw(mRobe, b, 5, cloth, rim = rim, spec = 0.12f, wave = 1f, dim = dim)
        val hood = copy(b); Mat4.translate(hood, 0f, 0.8f, 0.0f); Mat4.rotate(hood, 14f, 1f, 0f, 0f); Mat4.scale(hood, 1.25f, 1.12f, 1.3f)
        draw(mHood, hood, 5, cloth, rim = rim, spec = 0.12f, dim = dim, cut = true)
        // the deep, dark lining of the cowl and the shadow behind the face
        draw(mHoodIn, hood, 9, 0, emis = 0x030104, dim = dim, cut = true)
        val voidM = copy(b); Mat4.translate(voidM, 0f, 0.87f, 0.02f); Mat4.scale(voidM, 0.16f, 0.19f, 0.12f)
        draw(mSphere, voidM, 9, 0, emis = 0x020103, dim = dim)
        val eyeK = if (ex) 1.8f + 0.35f * sin(tt * 20f) else 0.95f + 0.15f * sin(tt * 2f)
        val blink = !ex && ((tt * 0.23f) % 1f) < 0.03f
        if (!crimson) {
            // a skull lurking in the shadow: dim bone, burning sockets
            val face = copy(b); Mat4.translate(face, 0f, 0.865f, 0.17f); Mat4.scale(face, 0.088f, 0.1f, 0.085f)
            draw(mSphere, face, 3, 0x8A8070, emis = 0xFF1744, spec = 0.3f, p0 = if (blink) 0f else eyeK, dim = dim)
            val open = if (ex) abs(sin(tt * 15f)) else 0f
            val jaw = copy(b); Mat4.translate(jaw, 0f, 0.79f - open * 0.03f, 0.205f); Mat4.rotate(jaw, open * 15f, 1f, 0f, 0f); Mat4.scale(jaw, 0.04f, 0.02f, 0.042f)
            draw(mSphere, jaw, 0, 0x5A5246, dim = dim)
            for (k in -2..2) {
                val tooth = copy(b); Mat4.translate(tooth, k * 0.013f, 0.8f, 0.245f); Mat4.scale(tooth, 0.0055f, 0.011f, 0.005f)
                draw(mLowSphere, tooth, 0, 0xB8B09C, dim = dim)
            }
        }
        if (!blink) {
            val ey = 0.875f
            for (sx in floatArrayOf(-0.035f, 0.035f)) {
                glowAt(b, sx, ey, 0.26f, 0.09f * eyeK, 0xFFFFFF, 0.9f * dim, 1f)
                glowAt(b, sx, ey, 0.26f, 0.22f * eyeK, 0xFF1744, 0.9f * dim, 0.3f)
            }
        }
        // sleeves + skeletal hands
        for (sx in floatArrayOf(-1f, 1f)) {
            val sl = copy(b); Mat4.translate(sl, sx * 0.25f, 0.5f, 0.12f); Mat4.rotate(sl, sx * 25f, 0f, 0f, 1f); Mat4.rotate(sl, -40f, 1f, 0f, 0f)
            Mat4.scale(sl, 0.075f, 0.26f, 0.075f)
            draw(mCyl, sl, 5, cloth, rim = rim, spec = 0.1f, dim = dim)
            val hand = copy(b); Mat4.translate(hand, sx * 0.31f, 0.37f, 0.27f)
            val palm = copy(hand); Mat4.scale(palm, 0.03f, 0.035f, 0.022f)
            draw(mSphere, palm, 0, 0xC8BFA8, spec = 0.4f, dim = dim)
            for (f in 0 until 4) {
                val curl = if (ex) sin(tt * 8f + f) * 15f else 0f
                val fg = copy(hand); Mat4.translate(fg, (f - 1.5f) * 0.014f, -0.03f, 0.012f); Mat4.rotate(fg, 160f + curl, 1f, 0f, 0f); Mat4.scale(fg, 0.006f, 0.065f, 0.006f)
                Mat4.translate(fg, 0f, 0.5f, 0f)
                draw(mCyl, fg, 0, 0xC8BFA8, spec = 0.4f, dim = dim)
            }
        }
        if (scythe) {
            val staff = copy(b); Mat4.translate(staff, 0.32f, 0.6f, 0.28f); Mat4.rotate(staff, -8f, 0f, 0f, 1f); Mat4.scale(staff, 0.02f, 1.25f, 0.02f)
            draw(mCyl, staff, 7, 0x3A2616, spec = 0.2f, dim = dim)
            val blade = copy(b); Mat4.translate(blade, 0.41f, 1.2f, 0.28f); Mat4.rotate(blade, -10f, 0f, 0f, 1f); Mat4.scale(blade, 1.25f, 1.25f, 1f)
            draw(mBlade, blade, 8, 0xB8C6CC, emis = 0x0C2406, rim = 0x76FF03, spec = 1.5f, shine = 90f, dim = dim)
            for (k in 0..5) glowAt(blade, -0.06f - k * 0.17f, 0.3f - k * k * 0.012f, 0f, 0.22f, 0x76FF03, 0.3f * dim, 0.1f)
        } else {
            // candle held in both hands
            val candle = copy(b); Mat4.translate(candle, 0f, 0.4f, 0.31f); Mat4.scale(candle, 0.025f, 0.09f, 0.025f)
            draw(mCyl, candle, 0, 0xF0E8D0, spec = 0.3f, dim = dim)
            val flick = 0.85f + 0.15f * sin(tt * 11f) * sin(tt * 4.3f)
            glowAt(b, 0f, 0.47f, 0.32f, 0.07f * flick, 0xFFFFFF, 0.9f * dim, 1f)
            glowAt(b, 0f, 0.48f, 0.32f, 0.2f * flick, if (ex) 0xFF1744 else 0x9CFF57, 0.8f * dim, 0.6f)
            glowAt(b, 0f, 0.48f, 0.32f, 0.8f * flick, if (ex) 0xFF1744 else 0x76FF03, 0.22f * dim, 0f)
        }
    }

    /** Ghost: head centre at origin (radius 1), sheet hanging to y = -1.85. Translucent. */
    private fun drawGhost(mm: FloatArray, tt: Float, ex: Boolean, dim: Float = 1f) {
        val b = copy(mm)
        Mat4.rotate(b, sin(tt * 1.2f) * 6f + if (ex) sin(tt * 10f) * 8f else 0f, 0f, 0f, 1f)
        val blink = if (!ex && ((tt * 0.23f + 0.1f) % 1f) < 0.035f) 0.05f else 1f
        val open = if (ex) 0.7f + 0.3f * abs(sin(tt * 14f)) else 0f
        draw(mGhost, b, 4, 0xDDEBFF, rim = 0x80A0D0, spec = 0.4f, wave = 1.6f, alpha = 0.92f * dim, p0 = blink, p1 = open, dim = dim)
        for (sx in floatArrayOf(-1f, 1f)) {
            val arm = copy(b); Mat4.translate(arm, sx * 1.15f, -0.55f + if (ex) sin(tt * 12f + sx) * 0.15f else 0f, 0.2f); Mat4.scale(arm, 0.28f, 0.2f, 0.22f)
            draw(mSphere, arm, 4, 0xDDEBFF, rim = 0x80A0D0, alpha = 0.85f * dim, dim = dim)
        }
        glowAt(b, 0f, -0.3f, 0f, 4.5f, 0x90B8FF, 0.18f * dim, 0f)
    }

    /** Raven perched (or flying when [perched] = false). Origin at the feet, ~1 tall, facing +Z. */
    private fun drawRaven(mm: FloatArray, tt: Float, ex: Boolean, dim: Float, perched: Boolean) {
        val b = copy(mm)
        val feather = 0x121018; val sheen = 0x6A4AE0
        if (perched) {
            val branch = copy(b); Mat4.translate(branch, 0f, 0.03f, 0.05f); Mat4.rotate(branch, 90f, 0f, 0f, 1f); Mat4.scale(branch, 0.045f, 0.9f, 0.045f)
            draw(mCyl, branch, 7, 0x3A2818, dim = dim)
            for (sx in floatArrayOf(-0.06f, 0.06f)) {
                val leg = copy(b); Mat4.translate(leg, sx, 0.12f, 0.05f); Mat4.scale(leg, 0.012f, 0.1f, 0.012f)
                draw(mCyl, leg, 0, 0x2A2A2E, dim = dim)
            }
        }
        val body = copy(b); Mat4.translate(body, 0f, 0.4f, 0f); Mat4.rotate(body, -28f, 1f, 0f, 0f); Mat4.scale(body, 0.19f, 0.2f, 0.36f)
        draw(mSphere, body, 0, feather, rim = sheen, spec = 1.1f, shine = 50f, dim = dim)
        val chest = copy(b); Mat4.translate(chest, 0f, 0.47f, 0.17f); Mat4.scale(chest, 0.15f, 0.17f, 0.15f)
        draw(mSphere, chest, 0, feather, rim = sheen, spec = 1.1f, shine = 50f, dim = dim)
        // tail feathers fanning down and back
        val tail = copy(b); Mat4.translate(tail, 0f, 0.27f, -0.37f); Mat4.rotate(tail, 35f, 1f, 0f, 0f); Mat4.scale(tail, 0.13f, 0.03f, 0.3f)
        draw(mSphere, tail, 0, feather, rim = sheen, spec = 0.9f, dim = dim)
        // head with a slow, eerie look-around
        val hb = copy(b); Mat4.translate(hb, 0f, 0.66f, 0.27f)
        val look = if (ex) sin(tt * 6f) * 12f else sin(tt * 0.7f) * 25f
        Mat4.rotate(hb, look, 0f, 1f, 0f); Mat4.rotate(hb, if (ex) -15f else 0f, 1f, 0f, 0f)
        val head = copy(hb); Mat4.scale(head, 0.12f, 0.12f, 0.14f)
        draw(mSphere, head, 0, feather, rim = sheen, spec = 1.1f, shine = 50f, dim = dim)
        val caw = if (ex || ((tt * 0.17f) % 1f) < 0.05f) abs(sin(tt * 10f)) else 0f
        val up = copy(hb); Mat4.translate(up, 0f, 0.005f, 0.11f); Mat4.rotate(up, 82f - caw * 8f, 1f, 0f, 0f); Mat4.scale(up, 0.035f, 0.17f, 0.03f)
        draw(mCone, up, 8, 0x3A3A44, spec = 1f, dim = dim)
        val low = copy(hb); Mat4.translate(low, 0f, -0.025f, 0.1f); Mat4.rotate(low, 96f + caw * 28f, 1f, 0f, 0f); Mat4.scale(low, 0.028f, 0.14f, 0.024f)
        draw(mCone, low, 8, 0x30303A, spec = 1f, dim = dim)
        glowAt(hb, 0.08f, 0.03f, 0.07f, 0.06f, 0xFFFFFF, 0.9f * dim, 1f)
        glowAt(hb, -0.08f, 0.03f, 0.07f, 0.06f, 0xFFFFFF, 0.9f * dim, 1f)
        glowAt(hb, 0.08f, 0.03f, 0.07f, 0.14f, 0xFF1744, 0.9f * dim, 0.3f)
        glowAt(hb, -0.08f, 0.03f, 0.07f, 0.14f, 0xFF1744, 0.9f * dim, 0.3f)
        // wings: folded along the body, or spread and flapping
        val flying = ex || !perched
        val flap = sin(tt * 12f) * 50f
        gl.disable(Gl.CULL_FACE)
        for (sx in floatArrayOf(-1f, 1f)) {
            val w = copy(b)
            if (flying) {
                Mat4.translate(w, sx * 0.12f, 0.5f, 0.02f)
                if (sx < 0) Mat4.scale(w, -1f, 1f, 1f)
                Mat4.rotate(w, flap, 0f, 0f, 1f)
                Mat4.scale(w, 0.75f, 1f, 0.55f)
            } else {
                Mat4.translate(w, sx * 0.17f, 0.47f, 0.12f)
                if (sx < 0) Mat4.scale(w, -1f, 1f, 1f)
                Mat4.rotate(w, 95f, 0f, 1f, 0f); Mat4.rotate(w, -25f, 1f, 0f, 0f); Mat4.rotate(w, -15f, 0f, 0f, 1f)
                Mat4.scale(w, 0.5f, 1f, 0.32f)
            }
            draw(mWing, w, 0, 0x0E0C16, rim = sheen, spec = 0.9f, shine = 30f, dim = dim)
        }
        gl.enable(Gl.CULL_FACE)
    }

    private fun drawBat(mm: FloatArray, tt: Float) {
        val body = copy(mm); Mat4.scale(body, 0.12f, 0.16f, 0.12f)
        draw(mSphere, body, 0, 0x1A1222, rim = 0x7F0019, spec = 0.4f)
        for (sx in floatArrayOf(-1f, 1f)) {
            val ear = copy(mm); Mat4.translate(ear, sx * 0.05f, 0.16f, 0f); Mat4.scale(ear, 0.04f, 0.09f, 0.04f)
            draw(mCone, ear, 0, 0x1A1222)
            val w = copy(mm); Mat4.translate(w, sx * 0.06f, 0.02f, 0f)
            if (sx < 0) Mat4.scale(w, -1f, 1f, 1f)
            Mat4.rotate(w, sin(tt * 20f) * 50f, 0f, 0f, 1f)
            Mat4.rotate(w, 90f, 1f, 0f, 0f)
            Mat4.scale(w, 0.5f, 1f, 0.35f)
            gl.disable(Gl.CULL_FACE)
            draw(mBatWing, w, 0, 0x22182C, rim = 0x7F0019, spec = 0.2f)
            gl.enable(Gl.CULL_FACE)
        }
        glowAt(mm, -0.04f, 0.05f, 0.11f, 0.08f, 0xFF1744, 0.9f, 0.9f)
        glowAt(mm, 0.04f, 0.05f, 0.11f, 0.08f, 0xFF1744, 0.9f, 0.9f)
    }

    private fun drawBats() {
        val cycle = 26f
        val e = t % cycle
        if (e > 9f) return
        for (k in 0 until 7) {
            val q = e / 9f
            val x = -14f + q * 28f - k * 0.9f
            val y = 2.6f + sin(t * 2f + k) * 0.4f + (k % 3) * 0.5f
            val z = -6f - (k % 4) * 2.5f
            val mm = m(); Mat4.translate(mm, x, y, z); Mat4.rotate(mm, 90f, 0f, 1f, 0f); Mat4.scale(mm, 1.6f, 1.6f, 1.6f)
            drawBat(mm, t + k * 0.3f)
        }
    }

    // ================================================================== side characters & effects
    private val pt = FloatArray(3)

    /** Draws a cast member through the registry; falls back to the legacy procedural drawer while a model does not exist yet. */
    private fun castOr(sym: Int, mm: FloatArray, tt: Float, ex: Boolean, dim: Float, legacy: () -> Unit) {
        if (Cast.get(sym) != null) drawCharacter(sym, mm, tt, ex, dim) else legacy()
    }

    private fun drawSideCharacters() {
        val gy = -2.5f
        // Plague Doctor (or the crimson watcher while that model is missing) on the left, Grim Reaper on the right
        val hover = sin(t * 0.9f) * 0.06f
        val left = m(); Mat4.translate(left, -4.75f, gy + 0.75f + hover, -0.7f); Mat4.rotate(left, 24f, 0f, 1f, 0f); Mat4.scale(left, 1.9f, 1.9f, 1.9f)
        castOr(ReaperConfig.DOCTOR, left, t + 0.13f, S.hoodedExcited, 1f) { drawReaper(left, t + 0.13f, S.hoodedExcited, 1f, scythe = false, crimson = true) }
        if (!S.reaperAway) {
            val right = m(); Mat4.translate(right, 4.75f, gy + 0.75f - hover, -0.7f); Mat4.rotate(right, -24f, 0f, 1f, 0f); Mat4.scale(right, 1.95f, 1.95f, 1.95f)
            castOr(ReaperConfig.WILD, right, t + 0.71f, S.reaperExcited, 1f) { drawReaper(right, t + 0.71f, S.reaperExcited, 1f, scythe = true, crimson = false) }
        }
        // a raven perched on the top beam; a wraith drifting far behind the cabinet once that model exists
        val rv = m(); Mat4.translate(rv, 2.45f, Layout3D.R * sin(1.5f * Layout3D.STEP) + 0.37f, 1.2f); Mat4.rotate(rv, -15f, 0f, 1f, 0f); Mat4.scale(rv, 0.55f, 0.55f, 0.55f)
        castOr(ReaperConfig.RAVEN, rv, t, false, 1f) { drawRaven(rv, t, false, 1f, perched = true) }
        if (Cast.get(ReaperConfig.WRAITH) != null) {
            val gx = sin(t * 0.13f) * 8f
            val gh = m(); Mat4.translate(gh, gx, -0.4f + sin(t * 0.7f) * 0.4f, -7f); Mat4.scale(gh, 2.4f, 2.4f, 2.4f)
            drawCharacter(ReaperConfig.WRAITH, gh, t, false, 0.7f)
        }
    }

    private fun drawCoins() {
        for (cell in 0 until 15) {
            if (S.coin[cell] <= 0) continue
            val r = cell / 3; val row = cell % 3
            val a = Layout3D.rowAngle(row.toFloat())
            val mm = m(); Mat4.translate(mm, Layout3D.reelX(r), 0f, 0f); Mat4.rotate(mm, -Math.toDegrees(a.toDouble()).toFloat(), 1f, 0f, 0f)
            Mat4.translate(mm, 0f, -0.08f, Layout3D.R + 0.18f)
            Mat4.rotate(mm, sin(t * 2f + cell) * 25f, 0f, 1f, 0f)
            Mat4.scale(mm, 0.3f, 0.3f, 0.3f)
            draw(mCoin, mm, 11, 0xFFB300, rim = 0x403000, spec = 1.2f, shine = 60f, p0 = 0.5f + 0.5f * sin(t * 4f + cell))
            glowAt(mm, 0f, 0f, 0.2f, 1.6f, 0x76FF03, 0.3f, 0.2f)
        }
    }

    /** Converts a HUD screen position to a world point a little in front of the reels. */
    private fun screenToFront(sx: Float, sy: Float, z: Float): FloatArray {
        layout!!.unproject(sx, sy, z, pt); return pt
    }

    private fun drawMini() {
        val sym = S.miniSymbol
        if (sym < 0) return
        val z = Layout3D.R + 1.6f
        val p = screenToFront(S.miniX, S.miniY, z)
        val mm = m(); Mat4.translate(mm, p[0], p[1], p[2])
        val s = S.miniScale
        when (sym) {
            ReaperConfig.RAVEN -> {
                if (Cast.get(sym) != null) { Mat4.translate(mm, 0f, -0.7f * s, 0f); Mat4.scale(mm, 1.7f * s, 1.7f * s, 1.7f * s); drawCharacter(sym, mm, t, true) }
                else { Mat4.translate(mm, 0f, -0.6f * s, 0f); Mat4.scale(mm, 1.5f * s, 1.5f * s, 1.5f * s); drawRaven(mm, t, true, 1f, perched = false) }
            }
            ReaperConfig.DEMON -> {
                if (Cast.get(sym) != null) { Mat4.translate(mm, 0f, -0.9f * s, 0f); Mat4.scale(mm, 1.8f * s, 1.8f * s, 1.8f * s); drawCharacter(sym, mm, t, true) }
                else { Mat4.rotate(mm, sin(t * 3f) * 15f, 0f, 1f, 0f); Mat4.scale(mm, 0.75f * s, 0.75f * s, 0.75f * s); drawPumpkin(mm, t, true) }
            }
            ReaperConfig.SKULL -> {
                if (Cast.get(sym) != null) { Mat4.translate(mm, 0f, -0.7f * s, 0f); Mat4.scale(mm, 1.6f * s, 1.6f * s, 1.6f * s); drawCharacter(sym, mm, t, true) }
                else { Mat4.rotate(mm, sin(t * 2.5f) * 18f, 0f, 1f, 0f); Mat4.scale(mm, 0.7f * s, 0.7f * s, 0.7f * s); drawSkull(mm, t, true) }
            }
            else -> { Mat4.translate(mm, 0f, -1.2f * s, 0f); Mat4.scale(mm, 1.9f * s, 1.9f * s, 1.9f * s); castOr(ReaperConfig.WILD, mm, t, true, 1f) { drawReaper(mm, t, true, 1f, scythe = true, crimson = false) } }
        }
        // projectiles: wild orbs or spinning coins arcing to their cells
        for (k in 0 until 6) {
            val cell = S.projCell[k]; if (cell < 0) continue
            val q = S.projQ[k]
            val r = cell / 3; val row = cell % 3
            val a = Layout3D.rowAngle(row.toFloat())
            val tx = Layout3D.reelX(r); val ty = Layout3D.R * sin(a); val tz = Layout3D.R * cos(a) + 0.2f
            val x = p[0] + (tx - p[0]) * q; val y = p[1] + (ty - p[1]) * q + sin(q * PI.toFloat()) * 0.8f; val zz = p[2] + (tz - p[2]) * q
            if (S.projCoin) {
                val c = m(); Mat4.translate(c, x, y, zz); Mat4.rotate(c, t * 720f, 0f, 1f, 0f); Mat4.scale(c, 0.25f, 0.25f, 0.25f)
                draw(mCoin, c, 11, 0xFFB300, spec = 1.2f, shine = 60f, p0 = 1f)
                glow(x, y, zz, 0.9f, 0xFFC107, 0.5f, 0.4f)
            } else {
                glow(x, y, zz, 1.0f, 0x76FF03, 0.8f, 0.9f)
                glow(x, y, zz, 2.2f, 0x76FF03, 0.25f, 0f)
            }
        }
    }

    private fun drawReaperTouch() {
        val q = S.reaperTouch
        if (q < 0f) return
        val e = q * q * (3 - 2 * q)
        val x = 4.3f + (0f - 4.3f) * e
        val y = -1.75f + (0.0f + 1.75f) * e
        val z = -0.2f + (Layout3D.R + 1.4f + 0.2f) * e
        val s = 2.0f + 1.4f * e
        val mm = m(); Mat4.translate(mm, x, y - 1.6f * e, z); Mat4.rotate(mm, -22f * (1 - e) + S.reaperSwing, 0f, 0f, 1f); Mat4.scale(mm, s, s, s)
        castOr(ReaperConfig.WILD, mm, t, true, 1f) { drawReaper(mm, t, true, 1f, scythe = true, crimson = false) }
    }

    private fun drawBigWin() {
        val q = S.bigWin
        if (q < 0f) return
        val e = q * q * (3 - 2 * q)
        val mm = m(); Mat4.translate(mm, 0f, -6.2f + 4.6f * e, Layout3D.R + 0.4f); Mat4.scale(mm, 3.6f, 3.6f, 3.6f)
        castOr(ReaperConfig.WILD, mm, t, true, 1f) { drawReaper(mm, t, true, 1f, scythe = true, crimson = false) }
    }

    private fun drawShowcase() {
        val sym = S.showcaseSym
        if (sym < 0) return
        val g = S.showcaseGrow
        val mm = m(); Mat4.translate(mm, 0f, -0.9f * g, Layout3D.R + 1.5f * g); val s = 0.6f + 1.6f * g; Mat4.scale(mm, s, s, s)
        drawCharacter(sym, mm, t, true)
    }

    /**
     * Developer / test harness: renders one cast member close up on a dark stage with a 3-point light set
     * (through the same post-processing as the game). [yawDeg] turns the character, [ex] is its excitement 0..1.
     */
    internal fun renderPreview(model: com.tjshea.neonreaper.gl.chars.CharacterModel, time: Float, ex: Float, w: Int, h: Int,
                               yawDeg: Float = 0f, camDist: Float = 2.7f, lookY: Float = 0.55f, scale: Float = 1f) {
        if (!ready) return
        t = time
        S.copyFrom(SceneState())
        val target = gl.getInteger(Gl.FRAMEBUFFER_BINDING)
        val usePost = quality > 0 && post.begin(w, h, quality)
        if (!usePost) gl.viewport(0, 0, w, h)
        gl.clearColor(0.015f, 0.018f, 0.024f, 1f); gl.clear(Gl.COLOR_BUFFER_BIT or Gl.DEPTH_BUFFER_BIT)
        val proj = Mat4.perspective(FloatArray(16), 30f, w.toFloat() / h, 0.1f, 60f)
        val view = Mat4.lookAt(FloatArray(16), 0f, lookY + 0.12f, camDist, 0f, lookY, 0f)
        Mat4.mul(vp, proj, view); camX = 0f; camY = lookY + 0.12f; camZ = camDist; glowUsed = 0
        gl.activeTexture(Gl.TEXTURE1); gl.bindTexture(Gl.TEXTURE_2D, noiseTex); gl.activeTexture(Gl.TEXTURE0)
        gl.enable(Gl.DEPTH_TEST); gl.depthFunc(Gl.LEQUAL); gl.depthMask(true)
        gl.enable(Gl.CULL_FACE); gl.cullFace(Gl.BACK)
        gl.enable(Gl.BLEND); gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE_MINUS_SRC_ALPHA)
        plN = 0
        pointLight(-1.2f, 1.1f, 1.6f, 4.0f, 0xFF5A40, 0.5f)       // warm red kicker (candle / hellfire)
        pointLight(1.6f, 0.9f, 1.2f, 4.0f, 0x6FA8FF, 0.35f)       // cold moon fill
        plWorld = plN
        setupLit()
        val mm = Mat4.identity(); Mat4.translate(mm, 0f, 0f, 0f); Mat4.rotate(mm, yawDeg, 0f, 1f, 0f); Mat4.scale(mm, scale, scale, scale)
        // a dark stone slab to stand on
        val slab = Mat4.identity(); Mat4.translate(slab, 0f, -0.06f, 0f); Mat4.scale(slab, 1.6f, 0.06f, 1.6f)
        draw(mBox, slab, 6, 0x34363A, spec = 0.1f)
        model.draw(this, mm, time, ex, 1f)
        gl.depthMask(false); gl.disable(Gl.CULL_FACE); gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE)
        drawGlows()
        gl.blendFunc(Gl.SRC_ALPHA, Gl.ONE_MINUS_SRC_ALPHA); gl.depthMask(true)
        plN = 0
        if (usePost) post.end(target, w, h, quality, time, postParams)
    }
}

/** Binds a mesh's vertex buffer for point drawing (no indices). */
internal fun Mesh.bindForPoints(gl: Gl) = drawPointsSetup(gl)

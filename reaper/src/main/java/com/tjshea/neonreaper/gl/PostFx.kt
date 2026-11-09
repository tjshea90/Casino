package com.tjshea.neonreaper.gl

/**
 * Post-processing for the 3D world: the scene is rendered into an offscreen target, a bright-pass + separable blur at a
 * fraction of the resolution produces BLOOM (neon, candle flames, eyes, lightning glow), and a final composite adds
 * vignette, film grain, a touch of chromatic aberration and the "dread" colour grade.
 *
 * Quality tiers keep weak GPUs alive: tier 1 = bloom at 1/8 resolution + vignette, tier 2 = bloom at 1/4 + grain,
 * tier 3 = bloom at 1/4 with a wider blur + aberration. Tier 0 bypasses everything (the scene renders straight to the screen).
 */
class PostFx(private val gl: Gl, private val quad: Mesh) {

    /** Per-frame look parameters (the scene fills these from the game state). */
    class Params {
        var bloom = 0.85f
        var threshold = 0.58f
        var vignette = 0.62f
        var grain = 0.045f
        var aberration = 0.0045f
        var tint = floatArrayOf(1f, 1f, 1f)     // multiplies the final colour (blood-red mood in the Séance, etc.)
        var flash = 0f                          // white-blue lightning / win flash 0..1
        var saturation = 0.86f
        var pulse = 0f                          // heartbeat / anticipation vignette pulse 0..1
        var shakeX = 0f; var shakeY = 0f        // screen-space jitter in UV units (camera shake)
    }

    private val bright = Program(gl, PostShaders.QUAD_VS, PostShaders.BRIGHT_FS, "bright")
    private val blur = Program(gl, PostShaders.QUAD_VS, PostShaders.BLUR_FS, "blur")
    private val comp = Program(gl, PostShaders.QUAD_VS, PostShaders.COMPOSITE_FS, "composite")

    private var sceneFbo = 0; private var sceneTex = 0; private var depthRb = 0
    private var sw = 0; private var sh = 0
    private var bloomFbo = IntArray(2); private var bloomTex = IntArray(2)
    private var bw = 0; private var bh = 0

    private fun makeTex(w: Int, h: Int): Int {
        val t = gl.genTexture()
        gl.bindTexture(Gl.TEXTURE_2D, t)
        gl.texImage2DEmpty(w, h)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MIN_FILTER, Gl.LINEAR)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_MAG_FILTER, Gl.LINEAR)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_S, Gl.CLAMP_TO_EDGE)
        gl.texParameteri(Gl.TEXTURE_2D, Gl.TEXTURE_WRAP_T, Gl.CLAMP_TO_EDGE)
        return t
    }

    private fun release() {
        if (sceneFbo != 0) { gl.deleteFramebuffer(sceneFbo); gl.deleteTexture(sceneTex); gl.deleteRenderbuffer(depthRb) }
        for (i in 0..1) if (bloomFbo[i] != 0) { gl.deleteFramebuffer(bloomFbo[i]); gl.deleteTexture(bloomTex[i]) }
        sceneFbo = 0; bloomFbo = IntArray(2); bloomTex = IntArray(2)
    }

    /** (Re)creates the targets for a [w] x [h] frame with the bloom buffer at 1/[div]. Returns false if the GPU refuses (then post is skipped). */
    private fun ensure(w: Int, h: Int, div: Int): Boolean {
        val nbw = (w / div).coerceAtLeast(16); val nbh = (h / div).coerceAtLeast(16)
        if (sceneFbo != 0 && sw == w && sh == h && bw == nbw && bh == nbh) return true
        release()
        sw = w; sh = h; bw = nbw; bh = nbh
        val prev = gl.getInteger(Gl.FRAMEBUFFER_BINDING)
        sceneTex = makeTex(w, h)
        sceneFbo = gl.genFramebuffer(); gl.bindFramebuffer(Gl.FRAMEBUFFER, sceneFbo)
        gl.framebufferTexture2D(Gl.FRAMEBUFFER, Gl.COLOR_ATTACHMENT0, Gl.TEXTURE_2D, sceneTex, 0)
        depthRb = gl.genRenderbuffer(); gl.bindRenderbuffer(Gl.RENDERBUFFER, depthRb)
        gl.renderbufferStorage(Gl.RENDERBUFFER, Gl.DEPTH_COMPONENT16, w, h)
        gl.framebufferRenderbuffer(Gl.FRAMEBUFFER, Gl.DEPTH_ATTACHMENT, Gl.RENDERBUFFER, depthRb)
        var ok = gl.checkFramebufferStatus(Gl.FRAMEBUFFER) == Gl.FRAMEBUFFER_COMPLETE
        for (i in 0..1) {
            bloomTex[i] = makeTex(bw, bh)
            bloomFbo[i] = gl.genFramebuffer(); gl.bindFramebuffer(Gl.FRAMEBUFFER, bloomFbo[i])
            gl.framebufferTexture2D(Gl.FRAMEBUFFER, Gl.COLOR_ATTACHMENT0, Gl.TEXTURE_2D, bloomTex[i], 0)
            ok = ok && gl.checkFramebufferStatus(Gl.FRAMEBUFFER) == Gl.FRAMEBUFFER_COMPLETE
        }
        gl.bindFramebuffer(Gl.FRAMEBUFFER, prev)
        if (!ok) { release(); return false }
        return true
    }

    /** Binds the offscreen scene target; returns false when post-processing is unavailable (render directly). */
    fun begin(w: Int, h: Int, tier: Int): Boolean {
        val div = if (tier <= 1) 8 else 4
        if (!ensure(w, h, div)) return false
        gl.bindFramebuffer(Gl.FRAMEBUFFER, sceneFbo)
        gl.viewport(0, 0, w, h)
        return true
    }

    /** Runs bloom + composite into [target] (the framebuffer that was bound before [begin]). */
    fun end(target: Int, w: Int, h: Int, tier: Int, time: Float, p: Params) {
        gl.disable(Gl.DEPTH_TEST); gl.disable(Gl.CULL_FACE); gl.disable(Gl.BLEND); gl.depthMask(false)
        gl.activeTexture(Gl.TEXTURE0)
        // 1. bright pass: scene -> bloom[0]
        gl.bindFramebuffer(Gl.FRAMEBUFFER, bloomFbo[0]); gl.viewport(0, 0, bw, bh)
        bright.use(); bright.i("uTex", 0); bright.f2("uTexel", 1f / sw, 1f / sh); bright.f("uThreshold", p.threshold)
        gl.bindTexture(Gl.TEXTURE_2D, sceneTex); quad.draw(gl)
        // 2. separable gaussian: bloom[0] -> bloom[1] -> bloom[0] (twice at tier 3 for a wider glow)
        val passes = if (tier >= 3) 2 else 1
        blur.use(); blur.i("uTex", 0)
        for (k in 0 until passes) {
            val spread = 1f + k * 1.6f
            gl.bindFramebuffer(Gl.FRAMEBUFFER, bloomFbo[1]); blur.f2("uDir", spread / bw, 0f)
            gl.bindTexture(Gl.TEXTURE_2D, bloomTex[0]); quad.draw(gl)
            gl.bindFramebuffer(Gl.FRAMEBUFFER, bloomFbo[0]); blur.f2("uDir", 0f, spread / bh)
            gl.bindTexture(Gl.TEXTURE_2D, bloomTex[1]); quad.draw(gl)
        }
        // 3. composite to the screen
        gl.bindFramebuffer(Gl.FRAMEBUFFER, target); gl.viewport(0, 0, w, h)
        comp.use(); comp.i("uScene", 0); comp.i("uBloom", 1)
        comp.f2("uRes", w.toFloat(), h.toFloat()); comp.f("uTime", time)
        comp.f("uBloomK", p.bloom); comp.f("uVig", p.vignette + 0.18f * p.pulse); comp.f("uGrain", if (tier >= 2) p.grain else 0f)
        comp.f("uCA", if (tier >= 3) p.aberration else 0f); comp.f("uFlash", p.flash); comp.f("uSat", p.saturation)
        comp.f3("uTint", p.tint[0], p.tint[1], p.tint[2]); comp.f2("uShake", p.shakeX, p.shakeY)
        gl.bindTexture(Gl.TEXTURE_2D, sceneTex)
        gl.activeTexture(Gl.TEXTURE1); gl.bindTexture(Gl.TEXTURE_2D, bloomTex[0])
        quad.draw(gl)
        gl.activeTexture(Gl.TEXTURE0)
        gl.depthMask(true)
    }
}

object PostShaders {
    const val QUAD_VS = """#version 300 es
layout(location=0) in vec3 aPos;
out vec2 vUv;
void main(){ vUv = aPos.xy*0.5+0.5; gl_Position = vec4(aPos.xy, 0.0, 1.0); }"""

    const val BLIT_FS = """#version 300 es
precision mediump float;
in vec2 vUv; out vec4 frag;
uniform sampler2D uTex;
void main(){ frag = vec4(texture(uTex, vUv).rgb, 1.0); }"""

    /** Downsampling bright pass: 4 taps, keeps only what is brighter than the threshold (soft knee). */
    const val BRIGHT_FS = """#version 300 es
precision mediump float;
in vec2 vUv; out vec4 frag;
uniform sampler2D uTex; uniform vec2 uTexel; uniform float uThreshold;
void main(){
  vec2 o = uTexel * 1.5;
  vec3 c = texture(uTex, vUv + vec2(-o.x,-o.y)).rgb + texture(uTex, vUv + vec2(o.x,-o.y)).rgb
         + texture(uTex, vUv + vec2(-o.x, o.y)).rgb + texture(uTex, vUv + vec2(o.x, o.y)).rgb;
  c *= 0.25;
  float l = max(c.r, max(c.g, c.b));
  float k = smoothstep(uThreshold, uThreshold + 0.30, l);
  frag = vec4(c * k, 1.0);
}"""

    const val BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vUv; out vec4 frag;
uniform sampler2D uTex; uniform vec2 uDir;
void main(){
  vec3 c = texture(uTex, vUv).rgb * 0.2270270270;
  c += (texture(uTex, vUv + uDir*1.3846153846).rgb + texture(uTex, vUv - uDir*1.3846153846).rgb) * 0.3162162162;
  c += (texture(uTex, vUv + uDir*3.2307692308).rgb + texture(uTex, vUv - uDir*3.2307692308).rgb) * 0.0702702703;
  frag = vec4(c, 1.0);
}"""

    const val COMPOSITE_FS = """#version 300 es
precision mediump float;
in vec2 vUv; out vec4 frag;
uniform sampler2D uScene; uniform sampler2D uBloom;
uniform vec2 uRes; uniform float uTime;
uniform float uBloomK; uniform float uVig; uniform float uGrain; uniform float uCA; uniform float uFlash; uniform float uSat;
uniform vec3 uTint; uniform vec2 uShake;
float hash12(vec2 p){ vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
void main(){
  vec2 c = vUv - 0.5;
  vec2 off = c * dot(c, c) * uCA;
  vec2 su = vUv + uShake;
  vec3 col;
  col.r = texture(uScene, su + off).r;
  col.g = texture(uScene, su).g;
  col.b = texture(uScene, su - off).b;
  col += texture(uBloom, su).rgb * uBloomK;
  // lightning / win flash washes the frame with cold light
  col += vec3(0.55, 0.65, 0.9) * uFlash * (0.35 + 0.65 * smoothstep(1.0, 0.0, length(c) * 1.6));
  // dread grade: gently desaturate, deepen the blacks, add contrast, cool shadows
  float l = dot(col, vec3(0.299, 0.587, 0.114));
  col = mix(vec3(l), col, uSat);
  col = col * col * (3.0 - 2.0 * min(col, vec3(1.0))) * 0.35 + col * 0.65;      // soft S-curve
  col += vec3(0.004, 0.006, 0.012) * (1.0 - l);                                   // cold lift in the shadows
  col *= uTint;
  // vignette
  float v = smoothstep(0.92, 0.18, length(c * vec2(1.15, 1.0)));
  col *= mix(1.0 - uVig, 1.0, v);
  // film grain
  col += (hash12(vUv * uRes + fract(uTime) * 91.7) - 0.5) * uGrain;
  frag = vec4(col, 1.0);
}"""
}

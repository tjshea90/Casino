package com.tjshea.neonreaper.gl

/** A compiled program with a uniform-location cache. Attribute locations: 0 pos, 1 normal, 2 uv. */
class Program(private val gl: Gl, vs: String, fs: String, val name: String) {
    val id: Int
    private val locs = HashMap<String, Int>()

    init {
        fun compile(type: Int, src: String): Int {
            val s = gl.createShader(type)
            gl.shaderSource(s, src); gl.compileShader(s)
            if (gl.getShaderi(s, Gl.COMPILE_STATUS) == 0) throw IllegalStateException("$name shader: " + gl.getShaderInfoLog(s))
            return s
        }
        id = gl.createProgram()
        gl.attachShader(id, compile(Gl.VERTEX_SHADER, vs))
        gl.attachShader(id, compile(Gl.FRAGMENT_SHADER, fs))
        gl.bindAttribLocation(id, 0, "aPos"); gl.bindAttribLocation(id, 1, "aNormal"); gl.bindAttribLocation(id, 2, "aUv"); gl.bindAttribLocation(id, 3, "aColor")
        gl.linkProgram(id)
        if (gl.getProgrami(id, Gl.LINK_STATUS) == 0) throw IllegalStateException("$name link: " + gl.getProgramInfoLog(id))
    }

    fun use() = gl.useProgram(id)
    fun loc(n: String): Int = locs.getOrPut(n) { gl.getUniformLocation(id, n) }

    // Uniform values persist inside a program, so setting the value it already holds is wasted driver work (a busy frame sets
    // ~3,000 uniforms, most of them unchanged from the previous draw): remember the last value per location and skip repeats.
    private val last = FloatArray(MAX_LOC * 4)
    private val lastN = ByteArray(MAX_LOC)           // number of floats cached for the location; 0 = nothing known yet

    private fun same1(l: Int, a: Float): Boolean {
        if (l !in 0 until MAX_LOC) return false
        val o = l * 4
        if (lastN[l].toInt() == 1 && last[o] == a) return true
        last[o] = a; lastN[l] = 1; return false
    }
    private fun same2(l: Int, a: Float, b: Float): Boolean {
        if (l !in 0 until MAX_LOC) return false
        val o = l * 4
        if (lastN[l].toInt() == 2 && last[o] == a && last[o + 1] == b) return true
        last[o] = a; last[o + 1] = b; lastN[l] = 2; return false
    }
    private fun same3(l: Int, a: Float, b: Float, c: Float): Boolean {
        if (l !in 0 until MAX_LOC) return false
        val o = l * 4
        if (lastN[l].toInt() == 3 && last[o] == a && last[o + 1] == b && last[o + 2] == c) return true
        last[o] = a; last[o + 1] = b; last[o + 2] = c; lastN[l] = 3; return false
    }
    private fun same4(l: Int, a: Float, b: Float, c: Float, d: Float): Boolean {
        if (l !in 0 until MAX_LOC) return false
        val o = l * 4
        if (lastN[l].toInt() == 4 && last[o] == a && last[o + 1] == b && last[o + 2] == c && last[o + 3] == d) return true
        last[o] = a; last[o + 1] = b; last[o + 2] = c; last[o + 3] = d; lastN[l] = 4; return false
    }

    fun f(n: String, x: Float) { val l = loc(n); if (l >= 0 && !same1(l, x)) gl.uniform1f(l, x) }
    fun f2(n: String, x: Float, y: Float) { val l = loc(n); if (l >= 0 && !same2(l, x, y)) gl.uniform2f(l, x, y) }
    fun f3(n: String, x: Float, y: Float, z: Float) { val l = loc(n); if (l >= 0 && !same3(l, x, y, z)) gl.uniform3f(l, x, y, z) }
    fun f4(n: String, x: Float, y: Float, z: Float, w: Float) { val l = loc(n); if (l >= 0 && !same4(l, x, y, z, w)) gl.uniform4f(l, x, y, z, w) }
    fun i(n: String, x: Int) { val l = loc(n); if (l >= 0 && !same1(l, x.toFloat())) gl.uniform1i(l, x) }
    fun m4(n: String, m: FloatArray) = gl.uniformMatrix4fv(loc(n), m)
    fun m3(n: String, m: FloatArray) = gl.uniformMatrix3fv(loc(n), m)
    private companion object { const val MAX_LOC = 512 }

    /** Sets a vec3 uniform from 0xRRGGBB scaled by [k]. */
    fun rgb(n: String, c: Int, k: Float = 1f) = f3(n, ((c shr 16) and 255) / 255f * k, ((c shr 8) and 255) / 255f * k, (c and 255) / 255f * k)
}

object Shaders {
    private const val NOISE = """
float hash(vec3 p){ p = fract(p*0.3183099 + 0.1); p *= 17.0; return fract(p.x*p.y*p.z*(p.x+p.y+p.z)); }
float noise(vec3 x){ vec3 i = floor(x); vec3 f = fract(x); f = f*f*(3.0-2.0*f);
  return mix(mix(mix(hash(i+vec3(0,0,0)),hash(i+vec3(1,0,0)),f.x), mix(hash(i+vec3(0,1,0)),hash(i+vec3(1,1,0)),f.x),f.y),
             mix(mix(hash(i+vec3(0,0,1)),hash(i+vec3(1,0,1)),f.x), mix(hash(i+vec3(0,1,1)),hash(i+vec3(1,1,1)),f.x),f.y), f.z); }
float fbm(vec3 p){ float a = 0.5, s = 0.0; for (int k = 0; k < 4; k++){ s += a*noise(p); p *= 2.03; a *= 0.5; } return s; }
"""

    // ------------------------------------------------------------------ lit materials
    const val LIT_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNormal; layout(location=2) in vec2 aUv; layout(location=3) in vec4 aColor;
uniform mat4 uMvp; uniform mat4 uModel; uniform mat3 uNormalMat;
uniform float uTime; uniform float uWave;
out vec3 vWorld; out vec3 vNormal; out vec2 vUv; out vec3 vLocal; out vec4 vColor;
void main(){
  vColor = aColor;
  vec3 p = aPos;
  if (uWave > 0.0) {                       // cloth / ghost hem sway, stronger toward the bottom
    float h = clamp(0.4 - p.y, 0.0, 2.0);
    p.x += sin(uTime*3.0 + p.y*5.0 + p.z*4.0) * 0.07*h*uWave;
    p.z += cos(uTime*2.4 + p.y*4.0 + p.x*3.0) * 0.06*h*uWave;
  }
  vLocal = p; vUv = aUv;
  vWorld = (uModel*vec4(p,1.0)).xyz;
  vNormal = normalize(uNormalMat*aNormal);
  gl_Position = uMvp*vec4(p,1.0);
}"""

    val LIT_FS = """#version 300 es
precision highp float;
in vec3 vWorld; in vec3 vNormal; in vec2 vUv; in vec3 vLocal; in vec4 vColor;
uniform vec3 uColor; uniform vec3 uEmissive; uniform vec3 uRim; uniform float uSpec; uniform float uShine;
uniform sampler2D uNoise;                                   // R soft fbm, G fine fbm, B ridged, A cellular (see NoiseTex)
uniform vec4 uPL0; uniform vec4 uPL1; uniform vec4 uPL2; uniform vec4 uPL3;   // point lights: xyz world position, w radius
uniform vec3 uPC0; uniform vec3 uPC1; uniform vec3 uPC2; uniform vec3 uPC3;   // point light colours (0 = unused)
uniform vec3 uMood;                                         // final colour multiplier (blood-red Seance, etc.), default 0 -> treated as 1
uniform vec3 uLightDir; uniform vec3 uLightColor; uniform vec3 uFill; uniform vec3 uAmbientTop; uniform vec3 uAmbientBottom;
uniform vec3 uCamPos; uniform vec3 uFogColor; uniform float uFogDensity; uniform float uFlash;
uniform float uTime; uniform int uMode; uniform vec4 uParam; uniform float uAlpha;
uniform sampler2D uTex; uniform vec4 uUvRect;
out vec4 frag;
$NOISE
// signed area test for a triangle in 2D
float inTri(vec2 p, vec2 a, vec2 b, vec2 c){
  float d1 = (p.x-b.x)*(a.y-b.y)-(a.x-b.x)*(p.y-b.y);
  float d2 = (p.x-c.x)*(b.y-c.y)-(b.x-c.x)*(p.y-c.y);
  float d3 = (p.x-a.x)*(c.y-a.y)-(c.x-a.x)*(p.y-a.y);
  bool neg = (d1<0.0)||(d2<0.0)||(d3<0.0); bool pos = (d1>0.0)||(d2>0.0)||(d3>0.0);
  return (neg && pos) ? 0.0 : 1.0; }
// bump mapping from the screen-space derivatives of a height value (one texture fetch, no extra noise evaluations)
vec3 perturb(vec3 N, vec3 P, float h, float k){
  vec3 dpx = dFdx(P), dpy = dFdy(P);
  float dhx = dFdx(h), dhy = dFdy(h);
  vec3 r1 = cross(dpy, N), r2 = cross(N, dpx);
  float det = dot(dpx, r1);
  vec3 g = sign(det) * (dhx * r1 + dhy * r2);
  return normalize(abs(det) * N - k * g);
}
vec4 nz(vec3 p, float s){ return texture(uNoise, p.xy * s + p.zy * (s * 0.71) + vec2(p.z, p.x) * (s * 0.37)); }
vec3 plight(vec4 pl, vec3 pc, vec3 N, vec3 P, vec3 V, float spec, float shine){
  if (pc.x + pc.y + pc.z <= 0.0) return vec3(0.0);
  vec3 d = pl.xyz - P; float dist = length(d);
  float att = clamp(1.0 - dist / pl.w, 0.0, 1.0); att *= att;
  vec3 L = d / max(dist, 0.001);
  float ndl = max(dot(N, L) * 0.85 + 0.15, 0.0);
  vec3 H = normalize(L + V);
  return pc * att * (ndl + pow(max(dot(N, H), 0.0), shine) * spec);
}
void main(){
  vec3 N = normalize(vNormal); if (!gl_FrontFacing) N = -N;
  if (uParam.z > 0.5) {                               // cowl: smooth elliptical face opening toward +Z
    vec2 e = vec2(atan(vLocal.x, vLocal.z) / 0.78, (vLocal.y - 0.1) / 0.17);
    if (vLocal.z > 0.0 && dot(e, e) < 1.0) discard;
  }
  vec3 V = normalize(uCamPos - vWorld);
  vec3 albedo = uColor; vec3 emis = uEmissive; float alpha = uAlpha; float spec = uSpec; float shine = uShine;
  if (uMode == 1) {                                   // atlas texture (reel faces)
    vec4 t = texture(uTex, uUvRect.xy + vUv*uUvRect.zw);
    albedo = t.rgb; alpha *= t.a; emis += t.rgb * uParam.x;
  } else if (uMode == 2) {                            // jack-o'-lantern: ribs + carved glowing face
    float ang = atan(vLocal.z, vLocal.x);
    float rib = 0.75 + 0.25*abs(cos(ang*4.0));
    albedo = mix(vec3(0.55,0.18,0.0), vec3(1.0,0.45,0.02), rib) * (0.85 + 0.3*fbm(vLocal*6.0));
    vec2 f = vec2(vLocal.x, vLocal.y);
    float face = 0.0;
    if (vLocal.z > 0.25) {
      face += inTri(f, vec2(-0.45,0.05), vec2(-0.15,0.05), vec2(-0.3,0.4));
      face += inTri(f, vec2(0.15,0.05), vec2(0.45,0.05), vec2(0.3,0.4));
      face += inTri(f, vec2(-0.08,-0.12), vec2(0.08,-0.12), vec2(0.0,0.02));
      float grin = uParam.y;                          // 1 = normal, >1 = laughing
      float mouthTop = -0.25 + 0.05*sin(f.x*14.0) ;
      float mouthBot = -0.25 - (0.18 + 0.12*grin) * (1.0 - pow(abs(f.x)/0.6, 2.0));
      if (abs(f.x) < 0.6 && f.y < mouthTop && f.y > mouthBot) face += 1.0;
      if (abs(f.x) < 0.6 && f.y < mouthTop + 0.08 && f.y > mouthTop - 0.02 && fract(f.x*6.0) > 0.55) face = 0.0;  // teeth
    }
    face = clamp(face, 0.0, 1.0);
    float flicker = uParam.x;
    albedo = mix(albedo, vec3(0.08,0.02,0.0), face);
    emis += face * vec3(1.0,0.65,0.1) * (1.6*flicker);
  } else if (uMode == 3) {                            // skull bone with deep glowing sockets
    albedo = uColor * (0.8 + 0.25*fbm(vLocal*9.0));
    vec2 f = vLocal.xy;
    if (vLocal.z > 0.3) {
      float e1 = length((f - vec2(-0.36,0.05))*vec2(1.0,1.25));
      float e2 = length((f - vec2(0.36,0.05))*vec2(1.0,1.25));
      float sock = 1.0 - smoothstep(0.2, 0.26, min(e1,e2));
      float nose = inTri(f, vec2(-0.1,-0.35), vec2(0.1,-0.35), vec2(0.0,-0.15));
      albedo = mix(albedo, vec3(0.02), max(sock, nose));
      float glow = 1.0 - smoothstep(0.0, 0.16, min(e1,e2));
      emis += glow * uEmissive * uParam.x;
      emis -= uEmissive * (1.0 - glow) * step(0.0, 1.0);    // only sockets glow
    }
    emis = max(emis, vec3(0.0));
  } else if (uMode == 4) {                            // ghost: translucent sheet, fresnel glow, wailing face
    float fres = pow(1.0 - max(dot(N, V), 0.0), 2.0);
    albedo = vec3(0.78,0.9,0.86);
    alpha = uAlpha * (0.55 + 0.45*fres);
    emis += vec3(0.35,0.5,0.7) * (0.25 + fres);
    vec2 f = vLocal.xy;
    if (vLocal.z > 0.2) {
      // wailing spectre: hollow drooping eye pits with a faint red ember, a long screaming mouth
      float blink = uParam.x;
      vec2 a = f - vec2(-0.3,0.22); a = vec2(a.x*0.94 + a.y*0.34, a.y*0.94 - a.x*0.34);
      vec2 b = f - vec2(0.3,0.22);  b = vec2(b.x*0.94 - b.y*0.34, b.y*0.94 + b.x*0.34);
      float sy = 1.0/max(0.1, blink*0.62);
      float e1 = length(a*vec2(1.0, sy)); float e2 = length(b*vec2(1.0, sy));
      float eyes = 1.0 - smoothstep(0.15,0.19,min(e1,e2));
      float open = max(uParam.y, 0.55);
      float mw = length((f - vec2(0.0,-0.28))*vec2(1.0, 0.75/open));
      float m = 1.0 - smoothstep(0.13,0.17, mw);
      float k = max(eyes, m);
      float ember = (1.0 - smoothstep(0.0,0.07,min(e1,e2))) * blink;
      albedo = mix(albedo, vec3(0.01,0.0,0.02), k); emis *= (1.0 - k); alpha = mix(alpha, 1.0, k);
      emis += vec3(1.0,0.08,0.15) * ember * (1.2 + uParam.y);
    }
  } else if (uMode == 5) {                            // cloth: folds + noise
    float folds = 0.75 + 0.25*sin(atan(vLocal.z, vLocal.x)*9.0 + vLocal.y*2.0);
    albedo *= folds * (0.8 + 0.4*fbm(vLocal*5.0));
    spec *= 0.3;
  } else if (uMode == 6) {                            // stone with moss near the ground
    float n = fbm(vWorld*3.5);
    albedo = uColor * (0.6 + 0.6*n);
    float moss = smoothstep(0.35, 0.0, vLocal.y) * smoothstep(0.45, 0.7, fbm(vWorld*6.0));
    albedo = mix(albedo, vec3(0.08,0.25,0.03), moss);
    emis += vec3(0.1,0.5,0.0) * moss * 0.25;
  } else if (uMode == 7) {                            // bark
    albedo = uColor * (0.5 + 0.7*fbm(vec3(vLocal.x*20.0, vLocal.y*3.0, vLocal.z*20.0)));
  } else if (uMode == 8) {                            // metal: fake environment reflection
    vec3 R = reflect(-V, N);
    vec3 env = mix(vec3(0.02,0.03,0.02), vec3(0.35,0.08,0.1), clamp(R.y*0.5+0.5, 0.0, 1.0)) + vec3(0.2,0.5,0.1)*pow(max(R.y,0.0),8.0);
    albedo = uColor * 0.35; emis += env * uColor * 1.2;
  } else if (uMode == 10) {                           // ground: dark grass and dirt
    float n = fbm(vWorld*0.8) * 0.6 + fbm(vWorld*4.0) * 0.4;
    albedo = mix(vec3(0.03,0.05,0.02), vec3(0.07,0.12,0.04), n);
  } else if (uMode == 11) {                           // soul coin: gold rim, glowing green face
    float r = length(vLocal.xy);
    vec3 gold = vec3(1.0,0.72,0.2);
    albedo = gold; spec = 1.2; shine = 60.0;
    if (r < 0.78 && abs(N.z) > 0.6) { albedo = vec3(0.1,0.45,0.1); emis += vec3(0.25,1.0,0.2) * (0.5 + 0.5*uParam.x) * (1.0 - r); }
  } else if (uMode == 20) {                           // VERTEX MATERIAL: merged character / prop meshes
    albedo = vColor.rgb * uColor;                      // uColor = dim tint (white = full brightness)
    if (!gl_FrontFacing) albedo *= 0.32;               // the inside of a hood / cloak is dark
    int mat = int(vColor.a * 15.0 + 0.5);
    vec4 q = nz(vLocal, 2.6);
    if (mat == 1) {                                    // cloth: mottled weave, soft sheen at grazing angles
      float fold = nz(vLocal * vec3(1.0, 0.45, 1.0), 1.7).g;
      albedo *= 0.78 + 0.45 * (0.6 * q.r + 0.4 * fold); spec *= 0.15;
      N = perturb(N, vWorld, fold, 0.10);
    } else if (mat == 2) {                             // bone: faintly mottled, pitted in the crevices, hairline cracks (no bump: it scatters the lights)
      vec4 b = nz(vLocal, 3.4);
      float pit = smoothstep(0.50, 0.12, b.a);
      float crack = smoothstep(0.86, 0.985, nz(vLocal, 1.35).b);
      albedo *= 0.92 + 0.14 * b.g;
      albedo = mix(albedo, albedo * vec3(0.62, 0.52, 0.42), pit * 0.25);
      albedo = mix(albedo, vec3(0.05, 0.04, 0.035), crack * 0.7);
      spec *= 0.6;
    } else if (mat == 3) {                             // metal: dark steel, fake environment, scratches
      vec3 R = reflect(-V, N);
      vec3 env = mix(vec3(0.02, 0.03, 0.03), vec3(0.34, 0.12, 0.12), clamp(R.y * 0.5 + 0.5, 0.0, 1.0)) + vec3(0.1, 0.45, 0.2) * pow(max(R.y, 0.0), 6.0);
      float scr = nz(vLocal * vec3(3.0, 1.0, 3.0), 1.2).b;
      albedo *= 0.32 + 0.2 * q.g; emis += env * vColor.rgb * (1.5 + 0.25 * scr); spec = 1.4; shine = 60.0;

    } else if (mat == 4) {                             // leather: pebbled grain, dark creases
      vec4 l = nz(vLocal, 6.0);
      albedo *= 0.70 + 0.5 * l.a; spec *= 0.45; N = perturb(N, vWorld, l.a, 0.10);
    } else if (mat == 5) {                             // emissive (eyes, flames, runes): self-lit, flickers with uParam.x
      frag = vec4(vColor.rgb * uColor * uParam.x * (0.85 + 0.3 * q.r), uAlpha); return;
    } else if (mat == 6) {                             // stone: mottled, veined, mossy at the bottom
      vec4 b = nz(vWorld * 0.8, 1.6);
      albedo *= 0.72 + 0.5 * b.r; albedo = mix(albedo, albedo * 0.35, smoothstep(0.88, 0.99, b.b) * 0.6);
      N = perturb(N, vWorld, b.g * 0.6, 0.03); spec *= 0.35;
    } else if (mat == 7) {                             // demon flesh: glossy, with glowing ember veins in the crevices
      vec4 v = nz(vLocal, 3.0);
      float vein = smoothstep(0.80, 0.97, v.b);
      albedo *= 0.65 + 0.5 * v.g; emis += vec3(1.0, 0.16, 0.05) * vein * (0.9 + 0.7 * uParam.x);
      spec = 1.1; shine = 40.0; N = perturb(N, vWorld, v.g, 0.08);
    } else if (mat == 8) {                             // glass lens / polished gem: mirror-like with a coloured core glow
      vec3 R = reflect(-V, N);
      emis += (vec3(0.05, 0.08, 0.1) + vec3(0.5, 0.55, 0.65) * pow(max(R.y, 0.0), 3.0)) * 0.6 + vColor.rgb * uParam.x * 0.6;
      spec = 1.8; shine = 90.0;
    } else if (mat == 9) {                             // wood: ridged grain along the local Y axis
      float gr = nz(vLocal * vec3(2.2, 0.6, 2.2), 0.9).b;
      albedo *= 0.78 + 0.32 * gr; N = perturb(N, vWorld, gr, 0.03); spec *= 0.25;
    } else if (mat == 10) {                            // ectoplasm: translucent with a strong fresnel glow, drifting wisps
      float fres = pow(1.0 - max(dot(N, V), 0.0), 2.0);
      float w = nz(vLocal + vec3(0.0, uTime * 0.25, 0.0), 1.4).r;
      alpha = uAlpha * (0.28 + 0.72 * fres) * (0.6 + 0.8 * w);
      emis += vColor.rgb * (0.45 + 1.1 * fres) * (0.7 + 0.5 * w);
      albedo *= 0.5;
    } else if (mat == 11) {                            // feathers / hair: dark with a sharp sheen
      float f = nz(vLocal * vec3(10.0, 2.0, 10.0), 1.0).g;
      albedo *= 0.6 + 0.6 * f; spec = 1.3; shine = 55.0;
    } else if (mat == 12) {                            // wax / candle: soft, warm, translucent-looking
      albedo *= 0.85 + 0.25 * q.r; spec *= 0.5; emis += albedo * 0.18;
    } else {                                           // matte with a little mottling
      albedo *= 0.82 + 0.3 * q.g;
    }
    emis *= uColor.r;                                  // material emission (metal/glass reflections, ember veins) follows the dim tint
  } else if (uMode == 9) {
    frag = vec4(uEmissive, uAlpha); return;
  }
  // lighting
  float ndl = max(dot(N, uLightDir), 0.0);
  float fill = max(dot(N, normalize(vec3(0.6,0.2,0.8))), 0.0);
  vec3 amb = mix(uAmbientBottom, uAmbientTop, N.y*0.5+0.5);
  vec3 H = normalize(uLightDir + V);
  float sp = pow(max(dot(N, H), 0.0), shine) * spec;
  float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);
  vec3 pls = plight(uPL0, uPC0, N, vWorld, V, spec, shine) + plight(uPL1, uPC1, N, vWorld, V, spec, shine)
           + plight(uPL2, uPC2, N, vWorld, V, spec, shine) + plight(uPL3, uPC3, N, vWorld, V, spec, shine);
  vec3 col = albedo * (amb + uLightColor*ndl*(1.0+uFlash*2.0) + uFill*fill + pls) + uLightColor*sp + uRim*rim + emis;
  if (uMood.x + uMood.y + uMood.z > 0.0) col *= uMood;
  // fog
  float d = length(vWorld - uCamPos);
  float fog = 1.0 - exp(-pow(d*uFogDensity, 1.6));
  col = mix(col, uFogColor, clamp(fog, 0.0, 1.0));
  frag = vec4(col, alpha);
}"""

    // ------------------------------------------------------------------ sky (fullscreen)
    const val SKY_VS = """#version 300 es
layout(location=0) in vec3 aPos;
out vec2 vUv;
void main(){ vUv = aPos.xy*0.5+0.5; gl_Position = vec4(aPos.xy, 0.9999, 1.0); }"""

    val SKY_FS = """#version 300 es
precision highp float;
in vec2 vUv; out vec4 frag;
uniform float uTime; uniform vec2 uRes; uniform vec2 uMoon; uniform float uFlash; uniform float uMoonR; uniform float uMood;
$NOISE
void main(){
  vec2 uv = vUv; float aspect = uRes.x/uRes.y;
  vec3 top = vec3(0.02,0.0,0.04), mid = vec3(0.09,0.02,0.07), low = vec3(0.05,0.09,0.04);
  vec3 col = mix(low, mid, smoothstep(0.0,0.45,uv.y)); col = mix(col, top, smoothstep(0.45,1.0,uv.y));
  // stars
  vec2 g = floor(uv*vec2(aspect,1.0)*180.0);
  float s = hash(vec3(g, 1.0));
  if (s > 0.992) col += vec3(0.8,0.85,1.0) * (s-0.992)*120.0 * (0.6+0.4*sin(uTime*2.0+s*50.0)) * smoothstep(0.35,0.7,uv.y);
  // blood moon with glow and craters
  vec2 d = (uv - uMoon)*vec2(aspect,1.0); float r = length(d);
  float disk = 1.0 - smoothstep(uMoonR-0.003, uMoonR, r);
  vec3 moon = mix(vec3(0.95,0.25,0.18), vec3(0.6,0.05,0.05), smoothstep(0.0,uMoonR,length(d+vec2(uMoonR*0.3,-uMoonR*0.3))));
  moon *= 0.8 + 0.3*fbm(vec3(d*28.0, 3.0));
  col = mix(col, moon, disk);
  col += vec3(0.6,0.05,0.05) * exp(-max(r-uMoonR,0.0)*9.0) * 0.55;
  // drifting clouds lit by the moon
  float c = fbm(vec3(uv.x*aspect*2.2 + uTime*0.015, uv.y*3.5, uTime*0.02));
  float cloud = smoothstep(0.45, 0.8, c) * smoothstep(0.25, 0.75, uv.y);
  vec3 ccol = mix(vec3(0.03,0.02,0.05), vec3(0.35,0.08,0.1), exp(-r*3.0));
  col = mix(col, ccol, cloud*0.9);
  col += vec3(0.7,0.8,1.0) * uFlash * (0.5 + 0.5*cloud);
  // the Seance: the whole sky bleeds red and the moon swells
  col = mix(col, col * vec3(1.9, 0.45, 0.4) + vec3(0.07, 0.0, 0.0) + vec3(0.55, 0.03, 0.03) * exp(-r * 4.0) * 0.5, uMood);
  frag = vec4(col, 1.0);
}"""

    // ------------------------------------------------------------------ fog bank (world quad, alpha)
    const val FOG_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=2) in vec2 aUv;
uniform mat4 uMvp; out vec2 vUv; out vec3 vP;
void main(){ vUv = aUv; vP = aPos; gl_Position = uMvp*vec4(aPos,1.0); }"""

    val FOG_FS = """#version 300 es
precision highp float;
in vec2 vUv; in vec3 vP; out vec4 frag;
uniform float uTime; uniform vec3 uColor; uniform float uAlpha; uniform float uSpeed;
$NOISE
void main(){
  float n = fbm(vec3(vUv.x*4.0 + uTime*uSpeed, vUv.y*2.0, uTime*0.05));
  float edge = smoothstep(0.0,0.25,vUv.y) * smoothstep(1.0,0.5,vUv.y) * smoothstep(0.0,0.1,vUv.x) * smoothstep(1.0,0.9,vUv.x);
  frag = vec4(uColor, smoothstep(0.35,0.85,n) * edge * uAlpha);
}"""

    // ------------------------------------------------------------------ additive glow sprite (camera-facing quad)
    const val GLOW_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=2) in vec2 aUv;
uniform mat4 uViewProj; uniform vec3 uCenter; uniform vec2 uSize; uniform vec3 uCamRight; uniform vec3 uCamUp;
out vec2 vUv;
void main(){ vUv = aUv; vec3 p = uCenter + uCamRight*aPos.x*uSize.x + uCamUp*aPos.y*uSize.y; gl_Position = uViewProj*vec4(p,1.0); }"""

    const val GLOW_FS = """#version 300 es
precision mediump float;
in vec2 vUv; out vec4 frag; uniform vec3 uColor; uniform float uAlpha; uniform float uCore;
void main(){ float r = length(vUv-0.5)*2.0; float g = exp(-r*r*4.0)*(1.0-smoothstep(0.85,1.0,r)); float core = exp(-r*r*40.0)*uCore;
  frag = vec4(uColor*g + vec3(core), (g+core)*uAlpha); }"""

    // ------------------------------------------------------------------ particles (points, additive)
    const val PART_VS = """#version 300 es
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNormal;
uniform mat4 uViewProj; uniform float uTime; uniform float uScale; uniform vec3 uBox; uniform vec3 uOrigin; uniform float uRise;
out float vTw; out float vKind;
void main(){
  float s = aNormal.x, sp = aNormal.y; vKind = aNormal.z;
  vec3 p = uOrigin + aPos*uBox;
  p.x += sin(uTime*0.3*sp + s*6.28)*0.6; p.z += cos(uTime*0.27*sp + s*3.1)*0.4;
  p.y = uOrigin.y + mod(aPos.y*uBox.y + uTime*uRise*sp, uBox.y) + sin(uTime*0.5+s*9.0)*0.2;
  vTw = 0.5+0.5*sin(uTime*3.0*sp + s*40.0);
  vec4 c = uViewProj*vec4(p,1.0);
  gl_Position = c; gl_PointSize = uScale*(0.6+vTw)/c.w;
}"""

    const val PART_FS = """#version 300 es
precision mediump float;
in float vTw; in float vKind; out vec4 frag; uniform vec3 uColor; uniform vec3 uColor2;
void main(){ vec2 d = gl_PointCoord-0.5; float r = length(d)*2.0; float g = exp(-r*r*5.0);
  vec3 c = vKind > 0.5 ? uColor2 : uColor; frag = vec4(c*g*(0.4+vTw), g*(0.4+vTw)); }"""
}

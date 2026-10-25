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
        gl.bindAttribLocation(id, 0, "aPos"); gl.bindAttribLocation(id, 1, "aNormal"); gl.bindAttribLocation(id, 2, "aUv")
        gl.linkProgram(id)
        if (gl.getProgrami(id, Gl.LINK_STATUS) == 0) throw IllegalStateException("$name link: " + gl.getProgramInfoLog(id))
    }

    fun use() = gl.useProgram(id)
    fun loc(n: String): Int = locs.getOrPut(n) { gl.getUniformLocation(id, n) }
    fun f(n: String, x: Float) = gl.uniform1f(loc(n), x)
    fun f2(n: String, x: Float, y: Float) = gl.uniform2f(loc(n), x, y)
    fun f3(n: String, x: Float, y: Float, z: Float) = gl.uniform3f(loc(n), x, y, z)
    fun f4(n: String, x: Float, y: Float, z: Float, w: Float) = gl.uniform4f(loc(n), x, y, z, w)
    fun i(n: String, x: Int) = gl.uniform1i(loc(n), x)
    fun m4(n: String, m: FloatArray) = gl.uniformMatrix4fv(loc(n), m)
    fun m3(n: String, m: FloatArray) = gl.uniformMatrix3fv(loc(n), m)
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
layout(location=0) in vec3 aPos; layout(location=1) in vec3 aNormal; layout(location=2) in vec2 aUv;
uniform mat4 uMvp; uniform mat4 uModel; uniform mat3 uNormalMat;
uniform float uTime; uniform float uWave;
out vec3 vWorld; out vec3 vNormal; out vec2 vUv; out vec3 vLocal;
void main(){
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
in vec3 vWorld; in vec3 vNormal; in vec2 vUv; in vec3 vLocal;
uniform vec3 uColor; uniform vec3 uEmissive; uniform vec3 uRim; uniform float uSpec; uniform float uShine;
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
  vec3 col = albedo * (amb + uLightColor*ndl*(1.0+uFlash*2.0) + uFill*fill) + uLightColor*sp + uRim*rim + emis;
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
uniform float uTime; uniform vec2 uRes; uniform vec2 uMoon; uniform float uFlash; uniform float uMoonR;
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

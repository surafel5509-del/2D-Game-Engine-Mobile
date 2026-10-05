package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.project.Project

/** Sprite program (built-in or user `effect()` shader). */
class SpriteProgram(val id: Int) {
    val aPos = GLES20.glGetAttribLocation(id, "aPos")
    val uMVP = GLES20.glGetUniformLocation(id, "uMVP")
    val uColor = GLES20.glGetUniformLocation(id, "uColor")
    val uTex = GLES20.glGetUniformLocation(id, "uTex")
    val uUseTex = GLES20.glGetUniformLocation(id, "uUseTex")
    val uShape = GLES20.glGetUniformLocation(id, "uShape")
    val uAA = GLES20.glGetUniformLocation(id, "uAA")
    val uUV = GLES20.glGetUniformLocation(id, "uUV")
    val uTime = GLES20.glGetUniformLocation(id, "uTime")
    val uParam = GLES20.glGetUniformLocation(id, "uParam")
    val uResolution = GLES20.glGetUniformLocation(id, "uResolution")
}

/** Lit 3D mesh program. */
class MeshProgram(val id: Int) {
    val aPos = GLES20.glGetAttribLocation(id, "aPos")
    val aNormal = GLES20.glGetAttribLocation(id, "aNormal")
    val aUV = GLES20.glGetAttribLocation(id, "aUV")
    val uMVP = GLES20.glGetUniformLocation(id, "uMVP")
    val uModel = GLES20.glGetUniformLocation(id, "uModel")
    val uNormalMat = GLES20.glGetUniformLocation(id, "uNormalMat")
    val uColor = GLES20.glGetUniformLocation(id, "uColor")
    val uTex = GLES20.glGetUniformLocation(id, "uTex")
    val uUseTex = GLES20.glGetUniformLocation(id, "uUseTex")
    val uTiling = GLES20.glGetUniformLocation(id, "uTiling")
    val uCamPos = GLES20.glGetUniformLocation(id, "uCamPos")
    val uAmbient = GLES20.glGetUniformLocation(id, "uAmbient")
    val uDirDir = GLES20.glGetUniformLocation(id, "uDirDir")
    val uDirColor = GLES20.glGetUniformLocation(id, "uDirColor")
    val uPointPos = GLES20.glGetUniformLocation(id, "uPointPos")
    val uPointColor = GLES20.glGetUniformLocation(id, "uPointColor")
    val uSpec = GLES20.glGetUniformLocation(id, "uSpec")
    val uShine = GLES20.glGetUniformLocation(id, "uShine")
    val uEmission = GLES20.glGetUniformLocation(id, "uEmission")
    val uUnlit = GLES20.glGetUniformLocation(id, "uUnlit")
    val uFogColor = GLES20.glGetUniformLocation(id, "uFogColor")
    val uFog = GLES20.glGetUniformLocation(id, "uFog")
    val uTime = GLES20.glGetUniformLocation(id, "uTime")
    val uParam = GLES20.glGetUniformLocation(id, "uParam")
    val uResolution = GLES20.glGetUniformLocation(id, "uResolution")
}

/** Full-screen post-processing program. */
class PostProgram(val id: Int) {
    val aPos = GLES20.glGetAttribLocation(id, "aPos")
    val uTex = GLES20.glGetUniformLocation(id, "uTex")
    val uTime = GLES20.glGetUniformLocation(id, "uTime")
    val uParam = GLES20.glGetUniformLocation(id, "uParam")
    val uResolution = GLES20.glGetUniformLocation(id, "uResolution")
}

/**
 * Compiles and caches GPU programs. User shaders (`.glsl` assets) define
 *   vec4 effect(vec4 color, vec2 uv)
 * with uTime, uParam, uTex and uResolution available.
 */
class ShaderLibrary(private val project: Project, private val log: (String) -> Unit) {
    private val sprites = HashMap<String, Pair<Long, SpriteProgram?>>()
    private val meshes = HashMap<String, Pair<Long, MeshProgram?>>()
    private val posts = HashMap<String, Pair<Long, PostProgram?>>()
    lateinit var defaultSprite: SpriteProgram
    lateinit var defaultMesh: MeshProgram

    fun init() {
        sprites.clear(); meshes.clear(); posts.clear()
        defaultSprite = SpriteProgram(GL.tryCompile(SPRITE_VS, spriteFs(DEFAULT_EFFECT)).first)
        defaultMesh = MeshProgram(GL.tryCompile(MESH_VS, meshFs(DEFAULT_EFFECT)).first)
    }

    private fun source(name: String): Pair<Long, String>? {
        val f = project.assetFile(name)
        if (!f.exists()) return null
        return f.lastModified() to f.readText()
    }

    fun sprite(name: String): SpriteProgram? {
        if (name.isBlank()) return null
        val (stamp, src) = source(name) ?: return null
        sprites[name]?.let { if (it.first == stamp) return it.second }
        val (id, err) = GL.tryCompile(SPRITE_VS, spriteFs(src))
        if (err != null) log("Shader $name: ${err.trim()}")
        val p = if (id != 0) SpriteProgram(id) else null
        sprites[name] = stamp to p
        return p
    }

    fun mesh(name: String): MeshProgram? {
        if (name.isBlank()) return null
        val (stamp, src) = source(name) ?: return null
        meshes[name]?.let { if (it.first == stamp) return it.second }
        val (id, err) = GL.tryCompile(MESH_VS, meshFs(src))
        if (err != null) log("Shader $name: ${err.trim()}")
        val p = if (id != 0) MeshProgram(id) else null
        meshes[name] = stamp to p
        return p
    }

    /** Built-in effect index (1..8) or custom asset. */
    fun post(builtin: Int, custom: String): PostProgram? {
        val key: String
        val src: String
        val stamp: Long
        if (builtin in 1 until POST_EFFECTS.size) {
            key = "#$builtin"; src = POST_EFFECTS[builtin]; stamp = 0L
        } else {
            val s = source(custom) ?: return null
            key = custom; src = s.second; stamp = s.first
        }
        posts[key]?.let { if (it.first == stamp) return it.second }
        val (id, err) = GL.tryCompile(POST_VS, postFs(src))
        if (err != null) log("Post shader $key: ${err.trim()}")
        val p = if (id != 0) PostProgram(id) else null
        posts[key] = stamp to p
        return p
    }

    companion object {
        const val DEFAULT_EFFECT = "vec4 effect(vec4 color, vec2 uv) { return color; }"

        const val SPRITE_VS = """
uniform mat4 uMVP;
uniform vec4 uUV;
attribute vec2 aPos;
varying vec2 vP;
varying vec2 vUV;
void main() {
  vP = aPos;
  vec2 t = aPos + 0.5;
  vUV = vec2(mix(uUV.x, uUV.z, t.x), mix(uUV.y, uUV.w, t.y));
  gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
}
"""

        fun spriteFs(effect: String) = """
precision mediump float;
varying vec2 vP;
varying vec2 vUV;
uniform vec4 uColor;
uniform sampler2D uTex;
uniform float uUseTex;
uniform float uShape;
uniform float uAA;
uniform float uTime;
uniform float uParam;
uniform vec2 uResolution;
$effect
void main() {
  float a = 1.0;
  if (uShape > 0.5 && uShape < 1.5) {
    a = clamp((0.5 - length(vP)) * uAA, 0.0, 1.0);
  } else if (uShape > 1.5 && uShape < 2.5) {
    float w = (0.5 - vP.y) * 0.5;
    float e = min(w - abs(vP.x), vP.y + 0.5);
    a = clamp(e * uAA, 0.0, 1.0);
  } else if (uShape > 2.5) {
    float d = length(vP);
    a = clamp((0.5 - d) * uAA, 0.0, 1.0) * clamp((d - 0.40) * uAA, 0.0, 1.0);
  }
  vec4 c = uColor;
  if (uUseTex > 0.5) c *= texture2D(uTex, vUV);
  c.a *= a;
  gl_FragColor = effect(c, vUV);
}
"""

        const val MESH_VS = """
uniform mat4 uMVP;
uniform mat4 uModel;
uniform mat4 uNormalMat;
attribute vec3 aPos;
attribute vec3 aNormal;
attribute vec2 aUV;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vUV;
void main() {
  vWorld = (uModel * vec4(aPos, 1.0)).xyz;
  vNormal = (uNormalMat * vec4(aNormal, 0.0)).xyz;
  vUV = aUV;
  gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

        fun meshFs(effect: String) = """
precision mediump float;
varying vec3 vWorld;
varying vec3 vNormal;
varying vec2 vUV;
uniform vec4 uColor;
uniform sampler2D uTex;
uniform float uUseTex;
uniform float uTiling;
uniform vec3 uCamPos;
uniform vec3 uAmbient;
uniform vec3 uDirDir;
uniform vec3 uDirColor;
uniform vec4 uPointPos[4];
uniform vec3 uPointColor[4];
uniform float uSpec;
uniform float uShine;
uniform float uEmission;
uniform float uUnlit;
uniform vec3 uFogColor;
uniform vec3 uFog;
uniform float uTime;
uniform float uParam;
uniform vec2 uResolution;
$effect
void main() {
  vec4 base = uColor;
  vec2 uv = vUV * uTiling;
  if (uUseTex > 0.5) base *= texture2D(uTex, uv);
  vec3 col = base.rgb;
  if (uUnlit < 0.5) {
    vec3 n = normalize(vNormal);
    vec3 v = normalize(uCamPos - vWorld);
    vec3 l = normalize(-uDirDir);
    float diff = max(dot(n, l), 0.0);
    vec3 h = normalize(l + v);
    float spec = pow(max(dot(n, h), 0.0), uShine) * uSpec;
    vec3 light = uAmbient + uDirColor * diff;
    vec3 specular = uDirColor * spec * step(0.0001, diff);
    for (int i = 0; i < 4; i++) {
      vec3 d = uPointPos[i].xyz - vWorld;
      float dist = length(d);
      float att = clamp(1.0 - dist / max(uPointPos[i].w, 0.001), 0.0, 1.0);
      att *= att;
      vec3 pl = d / max(dist, 0.0001);
      float pd = max(dot(n, pl), 0.0);
      light += uPointColor[i] * pd * att;
      specular += uPointColor[i] * pow(max(dot(n, normalize(pl + v)), 0.0), uShine) * uSpec * att * step(0.0001, pd);
    }
    col = col * light + specular;
  }
  col += base.rgb * uEmission;
  if (uFog.z > 0.5) {
    float f = clamp((length(uCamPos - vWorld) - uFog.x) / max(uFog.y - uFog.x, 0.001), 0.0, 1.0);
    col = mix(col, uFogColor, f);
  }
  gl_FragColor = effect(vec4(col, base.a), uv);
}
"""

        const val POST_VS = """
attribute vec2 aPos;
varying vec2 vUV;
void main() { vUV = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

        fun postFs(effect: String) = """
precision mediump float;
varying vec2 vUV;
uniform sampler2D uTex;
uniform float uTime;
uniform float uParam;
uniform vec2 uResolution;
$effect
void main() { gl_FragColor = effect(texture2D(uTex, vUV), vUV); }
"""

        /** Index matches ComponentRegistry.POST_FX. uParam = intensity. */
        val POST_EFFECTS = listOf(
            DEFAULT_EFFECT,
            // Grayscale
            "vec4 effect(vec4 c, vec2 uv) { float g = dot(c.rgb, vec3(0.299, 0.587, 0.114)); return vec4(mix(c.rgb, vec3(g), clamp(uParam, 0.0, 1.0)), 1.0); }",
            // Sepia
            "vec4 effect(vec4 c, vec2 uv) { vec3 s = vec3(dot(c.rgb, vec3(0.393, 0.769, 0.189)), dot(c.rgb, vec3(0.349, 0.686, 0.168)), dot(c.rgb, vec3(0.272, 0.534, 0.131))); return vec4(mix(c.rgb, s, clamp(uParam, 0.0, 1.0)), 1.0); }",
            // Vignette
            "vec4 effect(vec4 c, vec2 uv) { float d = distance(uv, vec2(0.5)); float v = smoothstep(0.8, 0.25, d * (0.6 + uParam * 0.6)); return vec4(c.rgb * v, 1.0); }",
            // CRT
            """vec4 effect(vec4 c, vec2 uv) {
  vec2 q = uv - 0.5; q *= 1.0 + dot(q, q) * 0.25 * uParam; vec2 w = q + 0.5;
  if (w.x < 0.0 || w.x > 1.0 || w.y < 0.0 || w.y > 1.0) return vec4(0.0, 0.0, 0.0, 1.0);
  vec3 col = texture2D(uTex, w).rgb;
  float scan = 0.85 + 0.15 * sin(w.y * uResolution.y * 3.14159);
  float vig = smoothstep(0.75, 0.3, length(q));
  return vec4(col * scan * vig * 1.15, 1.0);
}""",
            // Pixelate
            "vec4 effect(vec4 c, vec2 uv) { float px = max(2.0, 4.0 * uParam) ; vec2 g = uResolution / px; vec2 p = (floor(uv * g) + 0.5) / g; return vec4(texture2D(uTex, p).rgb, 1.0); }",
            // Bloom
            """vec4 effect(vec4 c, vec2 uv) {
  vec2 px = 2.5 / uResolution; vec3 acc = vec3(0.0);
  for (int x = -2; x <= 2; x++) for (int y = -2; y <= 2; y++) {
    vec3 s = texture2D(uTex, uv + vec2(float(x), float(y)) * px).rgb;
    acc += max(s - 0.6, 0.0);
  }
  return vec4(c.rgb + acc / 25.0 * 3.0 * uParam, 1.0);
}""",
            // Invert
            "vec4 effect(vec4 c, vec2 uv) { return vec4(mix(c.rgb, 1.0 - c.rgb, clamp(uParam, 0.0, 1.0)), 1.0); }",
            // Chromatic aberration
            "vec4 effect(vec4 c, vec2 uv) { vec2 o = (uv - 0.5) * 0.012 * uParam; return vec4(texture2D(uTex, uv + o).r, c.g, texture2D(uTex, uv - o).b, 1.0); }",
        )
    }
}

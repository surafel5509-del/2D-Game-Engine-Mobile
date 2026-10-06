package com.sengine.engine.render

import android.opengl.GLES20

/**
 * All GLSL ES 1.0 programs used by the 2D renderer.
 *
 * The engine is strictly 2D: every shader here works on screen-aligned quads (sprites, shapes,
 * glyphs, particles, light gradients, blur and post effects). There is no 3D lighting, no normal
 * mapping in 3D space and no mesh pipeline.
 */
object ShaderSources {

    /** Shared vertex shader for every batched 2D quad: world position, uv and packed colour. */
    const val QUAD_VS = """
attribute vec2 aPos;
attribute vec2 aUV;
attribute vec4 aColor;
uniform mat4 uMVP;
varying vec2 vUV;
varying vec4 vColor;
void main() {
    vUV = aUV;
    vColor = aColor;
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
}
"""

    /**
     * Sprite fragment shader with the full 2D material feature set (dissolve, outline, glow,
     * grayscale, colour replacement, hit flash, water wobble, heat distortion).
     */
    const val SPRITE_FS = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
uniform sampler2D uTex;
uniform vec2 uTexel;
uniform float uTime;
uniform float uDissolve;
uniform float uDissolveEdge;
uniform float uOutline;
uniform vec4 uOutlineColor;
uniform float uGlow;
uniform float uGray;
uniform float uDistort;
uniform float uWater;
uniform float uHeat;
uniform float uFlash;
uniform vec4 uFlashColor;
uniform vec4 uReplaceFrom;
uniform vec4 uReplaceTo;
uniform float uReplaceAmt;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
}

void main() {
    vec2 uv = vUV;
    if (uDistort > 0.001) {
        float w = sin(uv.y * 24.0 + uTime * 4.0) * 0.006 * uDistort;
        uv.x += w;
    }
    if (uWater > 0.001) {
        uv.x += sin(uv.y * 40.0 + uTime * 3.0) * 0.012 * uWater;
        uv.y += cos(uv.x * 34.0 - uTime * 2.2) * 0.010 * uWater;
        uv += vec2(sin(uTime * 1.7) * 0.002, 0.0) * uWater;
    }
    if (uHeat > 0.001) {
        uv.y += sin(uv.x * 30.0 + uTime * 6.0) * 0.010 * uHeat;
        uv.x += cos(uv.y * 26.0 + uTime * 5.0) * 0.008 * uHeat;
    }
    vec4 tex = texture2D(uTex, uv);
    vec4 col = tex * vColor;
    if (uReplaceAmt > 0.001) {
        float d = distance(tex.rgb, uReplaceFrom.rgb);
        float m = 1.0 - clamp(d / max(uReplaceFrom.a, 0.001), 0.0, 1.0);
        col.rgb = mix(col.rgb, uReplaceTo.rgb, m * uReplaceAmt);
    }
    if (uGray > 0.001) {
        float l = dot(col.rgb, vec3(0.299, 0.587, 0.114));
        col.rgb = mix(col.rgb, vec3(l), clamp(uGray, 0.0, 1.0));
    }
    if (uGlow > 0.001) {
        float a = 0.0;
        a += texture2D(uTex, uv + vec2(uTexel.x * 3.0, 0.0)).a;
        a += texture2D(uTex, uv - vec2(uTexel.x * 3.0, 0.0)).a;
        a += texture2D(uTex, uv + vec2(0.0, uTexel.y * 3.0)).a;
        a += texture2D(uTex, uv - vec2(0.0, uTexel.y * 3.0)).a;
        col.rgb += uOutlineColor.rgb * a * 0.25 * uGlow;
    }
    if (uOutline > 0.001) {
        float around = 0.0;
        around = max(around, texture2D(uTex, uv + vec2(uTexel.x * 2.0, 0.0)).a);
        around = max(around, texture2D(uTex, uv - vec2(uTexel.x * 2.0, 0.0)).a);
        around = max(around, texture2D(uTex, uv + vec2(0.0, uTexel.y * 2.0)).a);
        around = max(around, texture2D(uTex, uv - vec2(0.0, uTexel.y * 2.0)).a);
        around = max(around, texture2D(uTex, uv + vec2(uTexel.x * 2.0, uTexel.y * 2.0)).a);
        around = max(around, texture2D(uTex, uv - vec2(uTexel.x * 2.0, uTexel.y * 2.0)).a);
        float edge = clamp(around - tex.a, 0.0, 1.0) * uOutline * 8.0;
        col = mix(col, vec4(uOutlineColor.rgb, max(col.a, around)), clamp(edge, 0.0, 1.0) * uOutlineColor.a);
    }
    if (uDissolve > 0.001) {
        float n = hash(floor(vUV * 512.0));
        float threshold = uDissolve;
        if (n < threshold) discard;
        float edge = smoothstep(threshold, threshold + max(uDissolveEdge, 0.001), n);
        col.rgb += vec3(0.0, 0.15, 0.35) * (1.0 - edge) * uDissolveEdge * 4.0;
    }
    if (uFlash > 0.001) {
        col.rgb = mix(col.rgb, uFlashColor.rgb, clamp(uFlash, 0.0, 1.0));
    }
    gl_FragColor = col;
}
"""

    /**
     * Shape vertex shader. Shape parameters travel per-vertex (aParam: sizeX, sizeY, radius,
     * border) so hundreds of rounded panels/buttons batch into a single draw call.
     */
    const val SHAPE_VS = """
attribute vec2 aPos;
attribute vec2 aUV;
attribute vec4 aColor;
attribute vec4 aParam;
uniform mat4 uMVP;
varying vec2 vUV;
varying vec4 vColor;
varying vec4 vParam;
void main() {
    vUV = aUV;
    vColor = aColor;
    vParam = aParam;
    gl_Position = uMVP * vec4(aPos, 0.0, 1.0);
}
"""

    /** Rounded-rect / circle / capsule SDF shape shader used for UI, tiles, gizmos and particles. */
    const val SHAPE_FS = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
varying vec4 vParam;
uniform vec4 uBorderColor;
uniform float uInnerGlow;
void main() {
    vec2 size = max(vParam.xy, vec2(0.001));
    vec2 half = size * 0.5;
    vec2 p = (vUV - 0.5) * size;
    float r = min(vParam.z, min(half.x, half.y));
    vec2 q = abs(p) - (half - vec2(r));
    float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
    float aa = 1.0 - smoothstep(-1.0, 1.0, d);
    vec4 col = vColor;
    float borderWidth = vParam.w;
    if (borderWidth > 0.01) {
        float border = smoothstep(-borderWidth - 1.0, -borderWidth + 1.0, d);
        col = mix(uBorderColor, col, border);
    }
    if (uInnerGlow > 0.0) {
        col.rgb += col.rgb * clamp(1.0 - abs(d + 2.0) * 0.25, 0.0, 1.0) * uInnerGlow;
    }
    col.a *= aa * vColor.a;
    if (col.a <= 0.003) discard;
    gl_FragColor = col;
}
"""

    /** Glyph / text shader: alpha from the font atlas, optional outline and shadow tint. */
    const val TEXT_FS = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
uniform sampler2D uTex;
uniform vec4 uOutlineColor;
uniform float uOutlineWidth;
void main() {
    vec4 t = texture2D(uTex, vUV);
    float a = t.a;
    vec4 col = vec4(vColor.rgb, vColor.a * a);
    if (uOutlineWidth > 0.001 && uOutlineColor.a > 0.001) {
        vec2 texel = vec2(1.0 / 512.0) * uOutlineWidth;
        float around = 0.0;
        around = max(around, texture2D(uTex, vUV + vec2(texel.x, 0.0)).a);
        around = max(around, texture2D(uTex, vUV - vec2(texel.x, 0.0)).a);
        around = max(around, texture2D(uTex, vUV + vec2(0.0, texel.y)).a);
        around = max(around, texture2D(uTex, vUV - vec2(0.0, texel.y)).a);
        float outline = clamp(around - a, 0.0, 1.0);
        col = mix(col, vec4(uOutlineColor.rgb, uOutlineColor.a * around), outline);
    }
    if (col.a <= 0.002) discard;
    gl_FragColor = col;
}
"""

    /** Particles: optional texture, radial soft particle, additive or alpha blending. */
    const val PARTICLE_FS = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
uniform sampler2D uTex;
uniform float uSoftness;
uniform float uUseTexture;
void main() {
    float mask = 1.0;
    if (uUseTexture > 0.5) {
        mask = texture2D(uTex, vUV).a;
    } else {
        vec2 d = vUV - 0.5;
        float r = length(d) * 2.0;
        mask = clamp(1.0 - smoothstep(1.0 - uSoftness, 1.0, r), 0.0, 1.0);
    }
    vec4 col = vec4(vColor.rgb, vColor.a * mask);
    if (col.a <= 0.004) discard;
    gl_FragColor = col;
}
"""

    /** Radial 2D light with falloff, inner core and optional cone. */
    const val LIGHT_FS = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
uniform vec2 uLightPos;
uniform float uRadius;
uniform float uInner;
uniform float uFalloff;
uniform float uConeCos;
uniform vec2 uDirection;
void main() {
    vec2 p = (vUV - 0.5) * 2.0;
    float dist = length(p);
    if (dist > 1.0) discard;
    float atten = pow(clamp(1.0 - dist, 0.0, 1.0), uFalloff);
    float core = uInner > 0.0 ? smoothstep(1.0 - uInner, 1.0, 1.0 - dist) : 0.0;
    float a = clamp(atten + core * 0.35, 0.0, 1.0);
    if (uConeCos > -1.5) {
        vec2 dir = normalize(p + vec2(0.0001));
        float cd = dot(dir, normalize(uDirection));
        a *= smoothstep(uConeCos, mix(uConeCos, 1.0, 0.35), cd);
    }
    if (a <= 0.002) discard;
    gl_FragColor = vec4(vColor.rgb, vColor.a * a);
}
"""

    /** 9-tap separable gaussian blur (bloom, light softening, UI backdrop blur). */
    const val BLUR_FS = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
uniform sampler2D uTex;
uniform vec2 uStep;
void main() {
    vec4 sum = texture2D(uTex, vUV) * 0.2270270270;
    sum += texture2D(uTex, vUV + uStep * 1.3846153846) * 0.3162162162;
    sum += texture2D(uTex, vUV - uStep * 1.3846153846) * 0.3162162162;
    sum += texture2D(uTex, vUV + uStep * 3.2307692308) * 0.0702702703;
    sum += texture2D(uTex, vUV - uStep * 3.2307692308) * 0.0702702703;
    gl_FragColor = sum;
}
"""

    /**
     * Final 2D post-processing composite: bloom, vignette, chromatic aberration, scanlines (CRT),
     * grayscale, colour grading, shockwave and screen distortion.
     */
    const val POST_FS = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
uniform sampler2D uTex;
uniform sampler2D uBloom;
uniform float uBloomAmount;
uniform float uVignette;
uniform float uChromatic;
uniform float uScanlines;
uniform float uGray;
uniform float uInvert;
uniform float uShockwave;
uniform vec2 uShockCenter;
uniform float uShockTime;
uniform float uDistortion;
uniform float uTime;
uniform vec3 uTint;
uniform float uExposure;
uniform float uPixelate;
uniform vec2 uResolution;

void main() {
    vec2 uv = vUV;
    if (uPixelate > 1.0) {
        uv = floor(uv * uPixelate) / uPixelate;
    }
    if (uDistortion > 0.001) {
        uv.x += sin(uv.y * 28.0 + uTime * 2.0) * 0.004 * uDistortion;
        uv.y += cos(uv.x * 22.0 - uTime * 1.6) * 0.004 * uDistortion;
    }
    if (uShockwave > 0.001) {
        vec2 d = uv - uShockCenter;
        float dist = length(d);
        float wave = (uShockTime * 1.6 - dist) * 6.0;
        float amp = exp(-abs(wave)) * 0.05 * uShockwave;
        uv += normalize(d + vec2(0.0001)) * amp;
    }
    vec4 col;
    if (uChromatic > 0.001) {
        float o = uChromatic * 0.004;
        col.r = texture2D(uTex, uv + vec2(o, 0.0)).r;
        col.g = texture2D(uTex, uv).g;
        col.b = texture2D(uTex, uv - vec2(o, 0.0)).b;
        col.a = texture2D(uTex, uv).a;
    } else {
        col = texture2D(uTex, uv);
    }
    if (uBloomAmount > 0.001) {
        vec4 bloom = texture2D(uBloom, uv);
        col.rgb += bloom.rgb * uBloomAmount;
    }
    if (uScanlines > 0.001) {
        float s = sin(uv.y * uResolution.y * 0.8) * 0.5 + 0.5;
        col.rgb *= mix(1.0, 0.82 + 0.18 * s, uScanlines);
    }
    if (uGray > 0.001) {
        float l = dot(col.rgb, vec3(0.299, 0.587, 0.114));
        col.rgb = mix(col.rgb, vec3(l), uGray);
    }
    if (uInvert > 0.001) {
        col.rgb = mix(col.rgb, vec3(1.0) - col.rgb, uInvert);
    }
    if (uVignette > 0.001) {
        vec2 p = (uv - 0.5) * vec2(uResolution.x / max(uResolution.y, 1.0), 1.0);
        float v = smoothstep(0.85, 0.25, length(p));
        col.rgb *= mix(1.0, v, clamp(uVignette, 0.0, 2.0));
    }
    col.rgb *= uTint * uExposure;
    col.a = 1.0;
    gl_FragColor = col;
}
"""

    /** Bright-pass filter used to build the bloom source. */
    const val BLOOM_PREFILTER_FS = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
uniform sampler2D uTex;
uniform float uThreshold;
uniform float uSoftKnee;
void main() {
    vec4 c = texture2D(uTex, vUV);
    float brightness = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
    float knee = max(uSoftKnee, 0.0001);
    float soft = clamp(brightness - uThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee);
    float contribution = max(soft, brightness - uThreshold) / max(brightness, 0.0001);
    gl_FragColor = vec4(c.rgb * contribution, 1.0);
}
"""

    /** Simple full-screen blit (also used by the light map composite). */
    const val BLIT_FS = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
uniform sampler2D uTex;
uniform vec4 uColor;
void main() {
    gl_FragColor = texture2D(uTex, vUV) * uColor;
}
"""
}

/** A compiled program plus the uniform locations the batched renderer uses. */
class Program(val id: Int, val name: String) {
    val uMVP = GL.loc(id, "uMVP")
    val uTex = GL.loc(id, "uTex")
    val uTexel = GL.loc(id, "uTexel")
    val uTime = GL.loc(id, "uTime")
    val uColor = GL.loc(id, "uColor")
    val uSize = GL.loc(id, "uSize")
    val aParam = GL.attrib(id, "aParam")
    val uRadius = GL.loc(id, "uRadius")
    val uBorderWidth = GL.loc(id, "uBorderWidth")
    val uBorderColor = GL.loc(id, "uBorderColor")
    val uInnerGlow = GL.loc(id, "uInnerGlow")
    val uDissolve = GL.loc(id, "uDissolve")
    val uDissolveEdge = GL.loc(id, "uDissolveEdge")
    val uOutline = GL.loc(id, "uOutline")
    val uOutlineColor = GL.loc(id, "uOutlineColor")
    val uOutlineWidth = GL.loc(id, "uOutlineWidth")
    val uGlow = GL.loc(id, "uGlow")
    val uGray = GL.loc(id, "uGray")
    val uDistort = GL.loc(id, "uDistort")
    val uWater = GL.loc(id, "uWater")
    val uHeat = GL.loc(id, "uHeat")
    val uFlash = GL.loc(id, "uFlash")
    val uFlashColor = GL.loc(id, "uFlashColor")
    val uReplaceFrom = GL.loc(id, "uReplaceFrom")
    val uReplaceTo = GL.loc(id, "uReplaceTo")
    val uReplaceAmt = GL.loc(id, "uReplaceAmt")
    val uSoftness = GL.loc(id, "uSoftness")
    val uUseTexture = GL.loc(id, "uUseTexture")
    val uLightPos = GL.loc(id, "uLightPos")
    val uInner = GL.loc(id, "uInner")
    val uFalloff = GL.loc(id, "uFalloff")
    val uConeCos = GL.loc(id, "uConeCos")
    val uDirection = GL.loc(id, "uDirection")
    val uStep = GL.loc(id, "uStep")
    val uBloom = GL.loc(id, "uBloom")
    val uBloomAmount = GL.loc(id, "uBloomAmount")
    val uVignette = GL.loc(id, "uVignette")
    val uChromatic = GL.loc(id, "uChromatic")
    val uScanlines = GL.loc(id, "uScanlines")
    val uInvert = GL.loc(id, "uInvert")
    val uShockwave = GL.loc(id, "uShockwave")
    val uShockCenter = GL.loc(id, "uShockCenter")
    val uShockTime = GL.loc(id, "uShockTime")
    val uDistortion = GL.loc(id, "uDistortion")
    val uTint = GL.loc(id, "uTint")
    val uExposure = GL.loc(id, "uExposure")
    val uPixelate = GL.loc(id, "uPixelate")
    val uResolution = GL.loc(id, "uResolution")
    val uThreshold = GL.loc(id, "uThreshold")
    val uSoftKnee = GL.loc(id, "uSoftKnee")

    val aPos = GL.attrib(id, "aPos")
    val aUV = GL.attrib(id, "aUV")
    val aColor = GL.attrib(id, "aColor")

    /** Names of every uniform the editor's shader panel can expose for a custom material. */
    val materialUniforms: List<String> get() = listOf(
        "uDissolve", "uDissolveEdge", "uOutline", "uOutlineColor", "uGlow", "uGray",
        "uDistort", "uWater", "uHeat", "uFlash", "uFlashColor", "uReplaceFrom", "uReplaceTo", "uReplaceAmt"
    )

    fun use() {
        GLES20.glUseProgram(id)
    }
}

/**
 * Compiles and owns every program the renderer needs. Compilation is lazy but all core programs
 * are created in [init] so a broken shader surfaces immediately in the editor console.
 */
class ShaderLibrary {

    var sprite: Program? = null; private set
    var shape: Program? = null; private set
    var text: Program? = null; private set
    var particle: Program? = null; private set
    var light: Program? = null; private set
    var blur: Program? = null; private set
    var post: Program? = null; private set
    var bloomPrefilter: Program? = null; private set
    var blit: Program? = null; private set

    /** Custom materials loaded from `.shader` assets (their fragment source is compiled on load). */
    private val custom = HashMap<String, Program?>()

    var errors = ArrayList<String>(); private set

    fun init() {
        errors.clear()
        sprite = create("sprite", ShaderSources.QUAD_VS, ShaderSources.SPRITE_FS)
        shape = create("shape", ShaderSources.SHAPE_VS, ShaderSources.SHAPE_FS)
        text = create("text", ShaderSources.QUAD_VS, ShaderSources.TEXT_FS)
        particle = create("particle", ShaderSources.QUAD_VS, ShaderSources.PARTICLE_FS)
        light = create("light", ShaderSources.QUAD_VS, ShaderSources.LIGHT_FS)
        blur = create("blur", ShaderSources.QUAD_VS, ShaderSources.BLUR_FS)
        post = create("post", ShaderSources.QUAD_VS, ShaderSources.POST_FS)
        bloomPrefilter = create("bloomPrefilter", ShaderSources.QUAD_VS, ShaderSources.BLOOM_PREFILTER_FS)
        blit = create("blit", ShaderSources.QUAD_VS, ShaderSources.BLIT_FS)
    }

    private fun create(name: String, vs: String, fs: String): Program? {
        val id = GL.compile(vs, fs, name)
        if (id == 0) {
            errors.add("Shader '$name' failed to compile")
            return null
        }
        return Program(id, name)
    }

    /**
     * Compiles a user shader (the 2D shader editor) or returns a cached program.
     * Fragment source must declare `varying vec2 vUV; varying vec4 vColor;` and write gl_FragColor.
     */
    fun custom(name: String, fragmentSource: String): Program? {
        custom[name]?.let { return it }
        val id = GL.compile(ShaderSources.QUAD_VS, fragmentSource, "custom:$name")
        val program = if (id == 0) null else Program(id, "custom:$name")
        custom[name] = program
        return program
    }

    fun invalidateCustom(name: String) {
        custom.remove(name)
    }

    fun hasCustom(name: String) = custom.containsKey(name)

    /** Releases every compiled program (called when the GL context is lost). */
    fun release() {
        for (p in listOf(sprite, shape, text, particle, light, blur, post, bloomPrefilter, blit)) {
            p?.let { GLES20.glDeleteProgram(it.id) }
        }
        for (p in custom.values) p?.let { GLES20.glDeleteProgram(it.id) }
        custom.clear()
        sprite = null; shape = null; text = null; particle = null; light = null
        blur = null; post = null; bloomPrefilter = null; blit = null
    }
}

package com.sengine.engine.render

import android.opengl.GLES20
import com.sengine.engine.math.Colors
import com.sengine.project.Project
import org.json.JSONObject

/**
 * A 2D material: a sprite shader preset plus its parameters.
 *
 * Materials are strictly 2D screen-space effects applied to sprites, tilemaps and UI: dissolve,
 * outline, glow, grayscale, colour replacement, hit flash, water wobble, heat distortion and
 * custom fragment shaders authored in the shader editor. The engine ships the presets below so a
 * game can look great without writing GLSL.
 */
class Material2D(var name: String = DEFAULT) {

    /** Fragment shader: a built-in preset name or a `custom:<name>` program. */
    var shader = "sprite"
    /** Custom fragment source (only used by custom shaders). */
    var fragmentSource = ""

    val floats = HashMap<String, Float>()
    val colors = HashMap<String, Int>()

    /** Textures referenced by the material (dissolve noise, distortion maps...). */
    val textures = HashMap<String, String>()

    fun get(name: String, def: Float = 0f): Float = floats[name] ?: def
    fun set(name: String, value: Float): Material2D { floats[name] = value; return this }
    fun color(name: String, def: Int): Int = colors[name] ?: def
    fun setColor(name: String, value: Int): Material2D { colors[name] = value; return this }

    fun copy(name: String = this.name): Material2D {
        val m = Material2D(name)
        m.shader = shader
        m.fragmentSource = fragmentSource
        m.floats.putAll(floats)
        m.colors.putAll(colors)
        m.textures.putAll(textures)
        return m
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("name", name)
        o.put("shader", shader)
        if (fragmentSource.isNotBlank()) o.put("fragment", fragmentSource)
        val f = JSONObject()
        for ((k, v) in floats) f.put(k, v.toDouble())
        o.put("floats", f)
        val c = JSONObject()
        for ((k, v) in colors) c.put(k, Colors.toHex(v))
        o.put("colors", c)
        return o
    }

    companion object {
        const val DEFAULT = "Default"
        const val DISSOLVE = "Dissolve"
        const val OUTLINE = "Outline"
        const val GLOW = "Glow"
        const val GRAYSCALE = "Grayscale"
        const val COLOR_REPLACE = "Color Replace"
        const val HIT_FLASH = "Hit Flash"
        const val WATER = "Water"
        const val HEAT = "Heat Distortion"
        const val DISTORTION = "Distortion"
        const val GHOST = "Ghost"

        val PRESETS = listOf(
            DEFAULT, DISSOLVE, OUTLINE, GLOW, GRAYSCALE, COLOR_REPLACE, HIT_FLASH, WATER, HEAT, DISTORTION, GHOST
        )

        fun ofJson(o: JSONObject): Material2D {
            val m = Material2D(o.optString("name", DEFAULT))
            m.shader = o.optString("shader", "sprite")
            m.fragmentSource = o.optString("fragment", "")
            o.optJSONObject("floats")?.let { f ->
                for (k in f.keys()) m.floats[k] = f.optDouble(k, 0.0).toFloat()
            }
            o.optJSONObject("colors")?.let { c ->
                for (k in c.keys()) m.colors[k] = Colors.parse(c.optString(k, "#FFFFFF"))
            }
            return m
        }

        /** Builds one of the built-in presets. */
        fun preset(name: String): Material2D {
            val m = Material2D(name)
            when (name) {
                DISSOLVE -> {
                    m.shader = "sprite"
                    m.set("uDissolve", 0.4f)
                    m.set("uDissolveEdge", 0.08f)
                    m.setColor("uOutlineColor", 0xFFFF7A18.toInt())
                }
                OUTLINE -> {
                    m.shader = "sprite"
                    m.set("uOutline", 1f)
                    m.setColor("uOutlineColor", 0xFF101418.toInt())
                }
                GLOW -> {
                    m.shader = "sprite"
                    m.set("uGlow", 0.8f)
                    m.setColor("uOutlineColor", 0xFFFFD25E.toInt())
                }
                GRAYSCALE -> {
                    m.shader = "sprite"
                    m.set("uGray", 1f)
                }
                COLOR_REPLACE -> {
                    m.shader = "sprite"
                    m.set("uReplaceAmt", 1f)
                    m.setColor("uReplaceFrom", 0xFF2E7BD6.toInt())
                    m.setColor("uReplaceTo", 0xFFD64B2E.toInt())
                }
                HIT_FLASH -> {
                    m.shader = "sprite"
                    m.set("uFlash", 0.75f)
                    m.setColor("uFlashColor", 0xFFFFFFFF.toInt())
                }
                WATER -> {
                    m.shader = "sprite"
                    m.set("uWater", 0.6f)
                }
                HEAT -> {
                    m.shader = "sprite"
                    m.set("uHeat", 0.7f)
                }
                DISTORTION -> {
                    m.shader = "sprite"
                    m.set("uDistort", 1f)
                }
                GHOST -> {
                    m.shader = "sprite"
                    m.set("uGray", 0.4f)
                    m.set("uGlow", 0.4f)
                    m.setColor("uOutlineColor", 0xFF39D2FF.toInt())
                }
            }
            return m
        }
    }
}

/**
 * Loads `.mat` assets from the project, keeps the built-in presets and resolves programs.
 * Custom GLSL materials are compiled through [ShaderLibrary] and cached.
 */
class MaterialLibrary(private val project: Project) {

    private val cache = HashMap<String, Material2D>()
    /** Per-frame uniform overrides (damage flash from gameplay code). */
    private val runtime = HashMap<String, Material2D>()

    var compileErrors = ArrayList<String>(); private set

    /** Float uniforms scaled by `SpriteRenderer.shaderParam` at draw time. */
    val RUNTIME_PARAMS = listOf(
        "uDissolve", "uFlash", "uOutline", "uGlow", "uGray", "uDistort", "uWater", "uHeat", "uReplaceAmt"
    )

    /** Default material used when an object has no material assigned. */
    val default: Material2D by lazy {
        Material2D(Material2D.DEFAULT)
    }

    /** Drops cached material instances and compile errors (GL context loss). */
    fun clear() {
        cache.clear()
        runtime.clear()
        compileErrors.clear()
    }

    fun material(name: String): Material2D {
        if (name.isBlank() || name == Material2D.DEFAULT) return default
        cache[name]?.let { return it }
        val loaded = load(name) ?: Material2D.preset(name)
        cache[name] = loaded
        return loaded
    }

    /** A material whose parameters can be tweaked at runtime without touching the asset. */
    fun instance(name: String, ownerKey: String): Material2D {
        val key = "$name@$ownerKey"
        runtime[key]?.let { return it }
        val inst = material(name).copy(name)
        runtime[key] = inst
        return inst
    }

    fun invalidate(name: String? = null) {
        if (name == null) { cache.clear(); runtime.clear() } else { cache.remove(name); cache.remove(name.removePrefix("custom:")) }
    }

    private fun load(name: String): Material2D? {
        val file = project.assetFile(if (name.endsWith(".mat")) name else "$name.mat")
        if (!file.exists()) return null
        return try {
            Material2D.ofJson(JSONObject(file.readText()))
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Binds a material's program and uniforms. Returns the program to draw with, or the default
     * sprite program when the material is missing.
     */
    fun bind(shaders: ShaderLibrary, material: Material2D?, time: Float, texelX: Float, texelY: Float): Program? {
        val sprite = shaders.sprite ?: return null
        val m = material ?: return sprite
        val program = when {
            m.shader.startsWith("custom:") -> shaders.custom(m.shader.removePrefix("custom:"), m.fragmentSource) ?: sprite
            m.shader == "sprite" || m.shader.isBlank() -> sprite
            else -> shaders.custom(m.shader, "") ?: sprite
        }
        if (program !== sprite) return program
        // reset the sprite material uniforms to their defaults, then apply this material
        set1f(sprite.uDissolve, m.get("uDissolve", 0f))
        set1f(sprite.uDissolveEdge, m.get("uDissolveEdge", 0.08f))
        set1f(sprite.uOutline, m.get("uOutline", 0f))
        set1f(sprite.uGlow, m.get("uGlow", 0f))
        set1f(sprite.uGray, m.get("uGray", 0f))
        set1f(sprite.uDistort, m.get("uDistort", 0f))
        set1f(sprite.uWater, m.get("uWater", 0f))
        set1f(sprite.uHeat, m.get("uHeat", 0f))
        set1f(sprite.uFlash, m.get("uFlash", 0f))
        set1f(sprite.uReplaceAmt, m.get("uReplaceAmt", 0f))
        if (sprite.uTexel >= 0) GLES20.glUniform2f(sprite.uTexel, texelX, texelY)
        set1f(sprite.uTime, time)
        set4f(sprite.uOutlineColor, m.color("uOutlineColor", 0xFFFFFFFF.toInt()))
        set4f(sprite.uFlashColor, m.color("uFlashColor", 0xFFFFFFFF.toInt()))
        set4f(sprite.uReplaceFrom, m.color("uReplaceFrom", 0xFFFF00FF.toInt()))
        set4f(sprite.uReplaceTo, m.color("uReplaceTo", 0xFFFF00FF.toInt()))
        return sprite
    }

    private fun set1f(loc: Int, v: Float) {
        if (loc >= 0) GLES20.glUniform1f(loc, v)
    }

    private fun set4f(loc: Int, color: Int) {
        if (loc >= 0) GLES20.glUniform4f(loc, GL.r(color), GL.g(color), GL.b(color), GL.a(color))
    }

    /** Writes a small GLSL snippet into a new material (used by the shader editor "New material"). */
    fun createCustom(name: String, fragmentSource: String): Material2D {
        val m = Material2D(name)
        m.shader = "custom:$name"
        m.fragmentSource = fragmentSource
        return m
    }

    /** The template shown when creating a new 2D shader in the editor. */
    fun newShaderTemplate(): String = """
precision mediump float;
varying vec2 vUV;
varying vec4 vColor;
uniform sampler2D uTex;
uniform float uTime;
uniform float uFlash;

void main() {
    vec4 c = texture2D(uTex, vUV) * vColor;
    // example: subtle 2D tint pulse
    c.rgb *= 0.85 + 0.15 * sin(uTime * 2.0);
    gl_FragColor = c;
}
""".trimIndent()
}

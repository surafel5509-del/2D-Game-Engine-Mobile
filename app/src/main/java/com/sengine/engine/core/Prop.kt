package com.sengine.engine.core

import com.sengine.engine.math.Colors

/**
 * Asset categories understood by the engine. S Engine is a 2D engine, so the kinds are
 * strictly 2D: textures (including SVG source art), 2D sprite-sheet animations, 2D tilemaps,
 * 2D shaders/materials, audio and scripts.
 */
enum class AssetKind(val extensions: List<String>, val label: String) {
    TEXTURE(listOf("png", "jpg", "jpeg", "webp", "bmp", "svg"), "Texture"),
    SCRIPT(listOf("js"), "Script"),
    SOUND(listOf("wav", "ogg", "mp3", "m4a", "aac", "flac"), "Audio"),
    SHADER(listOf("glsl", "frag"), "Shader"),
    ANIMATION(listOf("anim"), "Animation"),
    TILESET(listOf("tileset"), "Tileset"),
    TILEMAP(listOf("tilemap"), "Tilemap"),
    MATERIAL(listOf("mat"), "Material"),
    FONT(listOf("fnt"), "Bitmap Font"),
    PREFAB(listOf("prefab"), "Prefab"),
    CURVE(listOf("curve"), "Curve");

    companion object {
        fun of(fileName: String): AssetKind? {
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return values().firstOrNull { ext in it.extensions }
        }
        fun extensionsAll(): List<String> = values().flatMap { it.extensions }.distinct().sorted()
    }
}

/**
 * Editable / serializable property descriptor. The Inspector renders these, the scene and
 * prefab serializers persist them, and the scripting layer exposes them where useful.
 */
sealed class Prop(val name: String, val tooltip: String = "") {
    class F(
        name: String, val get: () -> Float, val set: (Float) -> Unit,
        val step: Float = 0.1f, val min: Float = Float.NEGATIVE_INFINITY, val max: Float = Float.POSITIVE_INFINITY,
        tooltip: String = ""
    ) : Prop(name, tooltip)

    class I(
        name: String, val get: () -> Int, val set: (Int) -> Unit,
        val step: Int = 1,
        val min: Int = Int.MIN_VALUE, val max: Int = Int.MAX_VALUE, tooltip: String = ""
    ) : Prop(name, tooltip)

    class B(name: String, val get: () -> Boolean, val set: (Boolean) -> Unit, tooltip: String = "") : Prop(name, tooltip)

    class S(
        name: String, val get: () -> String, val set: (String) -> Unit,
        val multiline: Boolean = false, tooltip: String = ""
    ) : Prop(name, tooltip)

    class Color(name: String, val get: () -> Int, val set: (Int) -> Unit, tooltip: String = "") : Prop(name, tooltip)

    class Choice(
        name: String, val options: List<String>, val get: () -> Int, val set: (Int) -> Unit,
        tooltip: String = ""
    ) : Prop(name, tooltip) {
        fun valueLabel(): String = options.getOrElse(get()) { options.firstOrNull() ?: "" }
    }

    class Asset(
        name: String, val kind: AssetKind, val get: () -> String, val set: (String) -> Unit,
        tooltip: String = ""
    ) : Prop(name, tooltip)

    /** Two floats edited as X / Y (vectors, offsets, anchor points). */
    class V2(
        name: String, val getX: () -> Float, val getY: () -> Float,
        val setX: (Float) -> Unit, val setY: (Float) -> Unit, tooltip: String = ""
    ) : Prop(name, tooltip)

    /** A single asset reference that may be a texture or a sprite sheet frame. */
    class Flags(name: String, val bits: Int, val get: () -> Int, val set: (Int) -> Unit, val labels: List<String>, tooltip: String = "") :
        Prop(name, tooltip)

    /** Read-only informational row (e.g. runtime stats in the inspector). */
    class Info(name: String, val get: () -> String, tooltip: String = "") : Prop(name, tooltip)
}

/** Converters used by the serializers. */
object PropCodec {
    fun colorToJson(c: Int): String = Colors.toHex(c)
    fun jsonToColor(s: String): Int = Colors.parse(s)
}

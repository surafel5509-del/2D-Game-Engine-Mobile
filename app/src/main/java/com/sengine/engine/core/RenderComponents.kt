package com.sengine.engine.core

import com.sengine.engine.math.Colors
import com.sengine.engine.math.M

/**
 * SpriteRenderer - draws a 2D sprite (optionally from a sprite sheet) with tint, flip,
 * tiling, pivot, material and sorting options.
 */
class SpriteRenderer : Component() {
    override val type = "SpriteRenderer"

    /** 0 = Square, 1 = Circle, 2 = Triangle, 3 = Texture, 4 = Nine Slice, 5 = Capsule. */
    var shape = 0
    var texture = ""
    var color = Colors.WHITE
    var width = 1f
    var height = 1f
    var flipX = false
    var flipY = false
    /** Sprite sheet frame (column, row) inside a uniform grid. */
    var frameX = 0
    var frameY = 0
    var sheetColumns = 1
    var sheetRows = 1
    /** UVs used by sprite-sheet animations (set at runtime by the Animator). */
    var uvU0 = 0f
    var uvV0 = 1f
    var uvU1 = 1f
    var uvV1 = 0f
    var useFrameUv = false

    /** Tiling: >1 repeats the texture (parallax backgrounds, terrain strips). */
    var tileX = 1f
    var tileY = 1f

    /** Pivot inside the sprite: (0.5, 0.5) = centred. */
    var pivotX = 0.5f
    var pivotY = 0.5f

    var material = ""
    var shaderParam = 1f
    var additive = false

    /** Draw in screen space (HUD) instead of world space. */
    var screenSpace = false
    var visible = true

    /** 2D pixel snapping for crisp pixel art. */
    var pixelSnap = false

    /** Optional rounded corner radius for shape rendering. */
    var roundness = 0f

    override fun props() = listOf(
        Prop.B("Visible", { visible }, { visible = it }),
        Prop.Choice("Shape", SHAPES, { shape }, { shape = it }, "Square, Circle and Triangle are drawn procedurally; Texture draws an imported image."),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
        Prop.Color("Color / Tint", { color }, { color = it }),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.001f) }, 0.05f, 0.001f, 500f),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.001f) }, 0.05f, 0.001f, 500f),
        Prop.B("Flip X", { flipX }, { flipX = it }),
        Prop.B("Flip Y", { flipY }, { flipY = it }),
        Prop.I("Frame X", { frameX }, { frameX = it }, 0, 0, 64),
        Prop.I("Frame Y", { frameY }, { frameY = it }, 0, 0, 64),
        Prop.I("Sheet Columns", { sheetColumns }, { sheetColumns = it.coerceIn(1, 64) }, 1, 1, 64),
        Prop.I("Sheet Rows", { sheetRows }, { sheetRows = it.coerceIn(1, 64) }, 1, 1, 64),
        Prop.F("Tile X", { tileX }, { tileX = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 200f),
        Prop.F("Tile Y", { tileY }, { tileY = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 200f),
        Prop.V2("Pivot", { pivotX }, { pivotY }, { pivotX = it }, { pivotY = it }),
        Prop.F("Roundness", { roundness }, { roundness = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.Asset("Material", AssetKind.MATERIAL, { material }, { material = it }, "2D shader material (dissolve, outline, hit flash...)."),
        Prop.F("Shader Param", { shaderParam }, { shaderParam = it }),
        Prop.B("Additive Blend", { additive }, { additive = it }),
        Prop.B("Screen Space (HUD)", { screenSpace }, { screenSpace = it }),
        Prop.B("Pixel Snap", { pixelSnap }, { pixelSnap = it }, "Aligns the sprite to whole screen pixels for crisp pixel art.")
    )

    override fun resetRuntime() {
        useFrameUv = false
        uvU0 = 0f; uvV0 = 1f; uvU1 = 1f; uvV1 = 0f
    }

    fun resetUv() {
        useFrameUv = false
    }

    companion object {
        val SHAPES = listOf("Square", "Circle", "Triangle", "Texture", "Nine Slice", "Capsule")
        const val SHAPE_SQUARE = 0
        const val SHAPE_CIRCLE = 1
        const val SHAPE_TRIANGLE = 2
        const val SHAPE_TEXTURE = 3
        const val SHAPE_NINE_SLICE = 4
        const val SHAPE_CAPSULE = 5
    }
}

/** TextRenderer - bitmap/atlas font text with wrapping, alignment and outline. */
class TextRenderer : Component() {
    override val type = "TextRenderer"

    var text = "Hello S Engine"
    var font = ""
    var size = 0.5f
    var color = Colors.WHITE
    /** 0 = Left, 1 = Center, 2 = Right. */
    var align = 1
    var verticalAlign = 1
    var bold = false
    var wrapWidth = 0f
    var lineSpacing = 1.15f
    var letterSpacing = 0f
    var outline = false
    var outlineColor = Colors.BLACK
    var outlineWidth = 0.04f
    var shadow = false
    var shadowColor = 0x80000000.toInt()
    var shadowOffsetX = 0.03f
    var shadowOffsetY = -0.03f
    var screenSpace = false
    var background = Colors.CLEAR
    var padding = 0.05f

    /** Localised text lookup key (set by the localisation system, optional). */
    var localizationKey = ""
    var visible = true

    override fun props() = listOf(
        Prop.B("Visible", { visible }, { visible = it }),
        Prop.S("Text", { text }, { text = it }, multiline = true),
        Prop.Asset("Font", AssetKind.FONT, { font }, { font = it }),
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 20f),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Choice("Align", listOf("Left", "Center", "Right"), { align }, { align = it }),
        Prop.Choice("Vertical", listOf("Top", "Middle", "Bottom"), { verticalAlign }, { verticalAlign = it }),
        Prop.B("Bold", { bold }, { bold = it }),
        Prop.F("Wrap Width", { wrapWidth }, { wrapWidth = it.coerceAtLeast(0f) }, 0.1f, 0f, 500f),
        Prop.F("Line Spacing", { lineSpacing }, { lineSpacing = it.coerceIn(0.5f, 4f) }, 0.05f, 0.5f, 4f),
        Prop.F("Letter Spacing", { letterSpacing }, { letterSpacing = it }, 0.005f),
        Prop.B("Outline", { outline }, { outline = it }),
        Prop.Color("Outline Color", { outlineColor }, { outlineColor = it }),
        Prop.F("Outline Width", { outlineWidth }, { outlineWidth = it.coerceAtLeast(0f) }, 0.005f, 0f, 0.5f),
        Prop.B("Shadow", { shadow }, { shadow = it }),
        Prop.Color("Shadow Color", { shadowColor }, { shadowColor = it }),
        Prop.V2("Shadow Offset", { shadowOffsetX }, { shadowOffsetY }, { shadowOffsetX = it }, { shadowOffsetY = it }),
        Prop.Color("Background", { background }, { background = it }),
        Prop.F("Padding", { padding }, { padding = it.coerceAtLeast(0f) }, 0.01f, 0f, 2f),
        Prop.B("Screen Space (HUD)", { screenSpace }, { screenSpace = it })
    )
}

/**
 * Camera2D - the 2D camera. The engine has exactly one kind of camera and it is strictly 2D:
 * an orthographic view with zoom, follow, dead-zone, look-ahead, bounds, shake and 2D post FX.
 */
class Camera2D : Component() {
    override val type = "Camera"

    /** Half-height of the view in world units (zoom is derived from this). */
    var size = 5f
    var background = 0xFF0E1116.toInt()
    var follow = ""
    /** 0 = snap instantly, higher values follow faster. */
    var smoothing = 6f
    /** Follow offset (e.g. look slightly ahead). */
    var offsetX = 0f
    var offsetY = 0f
    /** Extra offset driven by follow-target velocity (look-ahead). */
    var lookAheadX = 0f
    var lookAheadY = 0.1f
    /** Dead zone half size; the camera does not move while the target is inside it. */
    var deadZoneX = 0f
    var deadZoneY = 0f
    /** Restrict the view to a rectangle (world units), 0 size disables clamping. */
    var limitX = 0f
    var limitY = 0f
    var limitWidth = 0f
    var limitHeight = 0f
    /** Rotation is allowed but rarely used in 2D (screen shake uses it). */
    var rotation = 0f
    /** Avoid jitter on pixel art: rounds the camera to whole pixels. */
    var pixelPerfect = false
    /** 0 = None, see ComponentRegistry.POST_FX. */
    var postFx = 0
    var postIntensity = 1f
    var postShader = ""
    /** Screen shake settings. */
    var shakeDecay = 2.5f

    // ---- runtime state
    var shake = 0f
    var viewWidth = 0f
    var viewHeight = 0f
    var targetX = 0f
    var targetY = 0f

    override fun props() = listOf(
        Prop.F("Size (half height)", { size }, { size = it.coerceAtLeast(0.05f) }, 0.1f, 0.05f, 200f),
        Prop.Color("Background", { background }, { background = it }),
        Prop.S("Follow Target", { follow }, { follow = it }, tooltip = "Object name; leave empty for a free camera."),
        Prop.F("Follow Smoothing", { smoothing }, { smoothing = it.coerceAtLeast(0f) }, 0.1f, 0f, 60f),
        Prop.F("Offset X", { offsetX }, { offsetX = it }, 0.05f),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }, 0.05f),
        Prop.F("Look Ahead X", { lookAheadX }, { lookAheadX = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.F("Look Ahead Y", { lookAheadY }, { lookAheadY = it.coerceIn(0f, 2f) }, 0.05f, 0f, 2f),
        Prop.F("Dead Zone X", { deadZoneX }, { deadZoneX = it.coerceAtLeast(0f) }, 0.05f, 0f, 50f),
        Prop.F("Dead Zone Y", { deadZoneY }, { deadZoneY = it.coerceAtLeast(0f) }, 0.05f, 0f, 50f),
        Prop.F("Limit X", { limitX }, { limitX = it }, 0.1f),
        Prop.F("Limit Y", { limitY }, { limitY = it }, 0.1f),
        Prop.F("Limit Width", { limitWidth }, { limitWidth = it.coerceAtLeast(0f) }, 0.5f, 0f, 10000f),
        Prop.F("Limit Height", { limitHeight }, { limitHeight = it.coerceAtLeast(0f) }, 0.5f, 0f, 10000f),
        Prop.F("Rotation", { rotation }, { rotation = M.wrapAngle(it) }, 1f, -180f, 180f),
        Prop.B("Pixel Perfect", { pixelPerfect }, { pixelPerfect = it }),
        Prop.Choice("Post FX", ComponentRegistry.POST_FX, { postFx }, { postFx = it }),
        Prop.F("FX Intensity", { postIntensity }, { postIntensity = it.coerceIn(0f, 4f) }, 0.05f, 0f, 4f),
        Prop.Asset("FX Shader", AssetKind.SHADER, { postShader }, { postShader = it })
    )

    override fun resetRuntime() {
        shake = 0f
    }

    fun addShake(amount: Float) {
        shake = maxOf(shake, amount)
    }

    /** Half-width of the view for the given aspect ratio. */
    fun halfWidth(aspect: Float) = size * aspect

    fun bounds(): com.sengine.engine.math.Rect2 =
        com.sengine.engine.math.Rect2(limitX, limitY, limitWidth, limitHeight)
}

/**
 * Parallax background layer: offsets the object based on the camera position.
 * Optionally repeats the sprite horizontally (endless runners, scrolling skies).
 */
class Parallax : Component() {
    override val type = "Parallax"

    /** 0 = pinned to the camera, 1 = moves with the world. */
    var factorX = 0.5f
    var factorY = 0.5f
    /** Repeats the layer horizontally every [repeatWidth] world units. */
    var repeatX = false
    var repeatY = false
    var repeatWidth = 20f
    var repeatHeight = 20f
    /** Scrolls on its own (clouds, star fields). */
    var autoScrollX = 0f
    var autoScrollY = 0f

    var scroll = 0f

    override fun props() = listOf(
        Prop.F("Factor X", { factorX }, { factorX = it }, 0.05f, 0f, 2f),
        Prop.F("Factor Y", { factorY }, { factorY = it }, 0.05f, 0f, 2f),
        Prop.B("Repeat X", { repeatX }, { repeatX = it }),
        Prop.B("Repeat Y", { repeatY }, { repeatY = it }),
        Prop.F("Repeat Width", { repeatWidth }, { repeatWidth = it.coerceAtLeast(0.1f) }, 0.5f, 0.1f, 5000f),
        Prop.F("Repeat Height", { repeatHeight }, { repeatHeight = it.coerceAtLeast(0.1f) }, 0.5f, 0.1f, 5000f),
        Prop.F("Auto Scroll X", { autoScrollX }, { autoScrollX = it }, 0.05f),
        Prop.F("Auto Scroll Y", { autoScrollY }, { autoScrollY = it }, 0.05f)
    )

    override fun resetRuntime() { scroll = 0f }
}

/** Keeps an object attached to the active camera (HUD/overlay objects). */
class CameraAttach : Component() {
    override val type = "CameraAttach"
    var offsetX = 0f
    var offsetY = 0f

    override fun props() = listOf(
        Prop.F("Offset X", { offsetX }, { offsetX = it }, 0.05f),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }, 0.05f)
    )
}

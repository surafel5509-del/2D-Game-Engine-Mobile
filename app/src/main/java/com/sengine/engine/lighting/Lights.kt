package com.sengine.engine.lighting

import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M
import com.sengine.engine.math.Noise2D

/**
 * Light2D - a 2D light source. Point, spot and "2D directional" (parallel light, e.g. a sun)
 * lights are supported; all of them work on the 2D light map with per-light shadows, flicker and
 * mobile-friendly culling. There is no 3D light anywhere in S Engine.
 */
class Light2D : Component() {
    override val type = "Light2D"

    /** 0 = Point, 1 = Spot, 2 = Directional (2D sun). */
    var kind = 0
    var color = 0xFFFFF2D0.toInt()
    var intensity = 1f
    /** Radius in world units (ignored for directional lights). */
    var radius = 6f
    /** Inner radius where the light is at full intensity. */
    var innerRadius = 0.5f
    /** Falloff curve exponent: 1 = linear, 2 = quadratic-ish. */
    var falloff = 1.6f
    /** Spot cone width in degrees. */
    var coneAngle = 60f
    /** Direction for spot/directional lights in degrees. */
    var direction = 270f
    var castShadows = true
    var shadowSoftness = 0.35f
    var shadowsLength = 1f

    /** Flicker: 0 = steady, 1 = candle. */
    var flicker = 0f
    var flickerSpeed = 8f
    var pulse = 0f
    var pulseSpeed = 1f
    /** Only affects sprites whose layer mask intersects this. */
    var layerMask = -1
    /** Lights sorted by priority when the mobile light budget is exceeded. */
    var priority = 0
    var visible = true

    /** Runtime: current intensity after flicker/pulse. */
    var runtimeIntensity = 1f
        private set

    override fun props() = listOf(
        Prop.B("Visible", { visible }, { visible = it }),
        Prop.Choice("Type", TYPES, { kind }, { kind = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.F("Intensity", { intensity }, { intensity = it.coerceIn(0f, 20f) }, 0.05f, 0f, 20f),
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.05f) }, 0.25f, 0.05f, 500f),
        Prop.F("Inner Radius", { innerRadius }, { innerRadius = it.coerceAtLeast(0f) }, 0.1f, 0f, 500f),
        Prop.F("Falloff", { falloff }, { falloff = it.coerceIn(0.2f, 6f) }, 0.05f, 0.2f, 6f),
        Prop.F("Cone Angle", { coneAngle }, { coneAngle = it.coerceIn(1f, 360f) }, 1f, 1f, 360f),
        Prop.F("Direction", { direction }, { direction = M.wrapAngle(it) }, 1f, -180f, 180f),
        Prop.B("Cast Shadows", { castShadows }, { castShadows = it }),
        Prop.F("Shadow Softness", { shadowSoftness }, { shadowSoftness = it.coerceIn(0f, 4f) }, 0.05f, 0f, 4f),
        Prop.F("Shadows Length", { shadowsLength }, { shadowsLength = it.coerceIn(0.1f, 4f) }, 0.05f, 0.1f, 4f),
        Prop.F("Flicker", { flicker }, { flicker = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.F("Flicker Speed", { flickerSpeed }, { flickerSpeed = it.coerceAtLeast(0.1f) }, 0.5f, 0.1f, 60f),
        Prop.F("Pulse", { pulse }, { pulse = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.F("Pulse Speed", { pulseSpeed }, { pulseSpeed = it.coerceAtLeast(0.01f) }, 0.05f, 0.01f, 20f),
        Prop.I("Priority", { priority }, { priority = it }, 1, 0, 100),
        Prop.Info("Active Intensity", { "%.2f".format(runtimeIntensity) })
    )

    override fun resetRuntime() { runtimeIntensity = intensity }

    /** Updates flicker/pulse; returns the effective intensity. */
    fun updateIntensity(time: Float): Float {
        var v = intensity
        if (flicker > 0f) {
            val n = Noise2D.value(time * flickerSpeed, 12.34f, hashSeed())
            v *= 1f - flicker * 0.5f * (1f - (n * 0.5f + 0.5f))
        }
        if (pulse > 0f) {
            v *= 1f - pulse + pulse * (0.5f + 0.5f * kotlin.math.sin(time * pulseSpeed * M.TAU))
        }
        runtimeIntensity = v.coerceAtLeast(0f)
        return runtimeIntensity
    }

    private fun hashSeed() = (go.id.toInt() * 2654435761u.toInt())

    companion object {
        val TYPES = listOf("Point", "Spot", "Directional")
    }
}

/**
 * LightOccluder2D - geometry that blocks 2D light. Either uses the object's collider shape or a
 * custom polygon, and can be a soft (blurred) occluder for stylised looks.
 */
class LightOccluder2D : Component() {
    override val type = "LightOccluder"

    /** 0 = Use collider, 1 = Custom polygon. */
    var source = 0
    var points = "-0.5,-0.5 0.5,-0.5 0.5,0.5 -0.5,0.5"
    var occludes = true
    /** Soft occluders let a little light bleed through (foliage, glass). */
    var softness = 0f
    var selfLit = false

    override fun props() = listOf(
        Prop.Choice("Source", listOf("Collider Shape", "Custom Polygon"), { source }, { source = it }),
        Prop.S("Points", { points }, { points = it }, multiline = true),
        Prop.B("Occludes Light", { occludes }, { occludes = it }),
        Prop.F("Softness", { softness }, { softness = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.B("Self Lit", { selfLit }, { selfLit = it }, "The occluder itself is lit instead of blacked out (walls with a lit face).")
    )

    /** World-space polygon points (x, y pairs). */
    fun worldPolygon(): FloatArray {
        val w = go.computeWorld()
        if (source == 0) {
            val col = go.get<Collider2D>()
            if (col != null) {
                val cx = col.offsetX; val cy = col.offsetY
                val hw: Float; val hh: Float
                if (col.shape == Collider2D.SHAPE_CIRCLE) {
                    hw = col.radius; hh = col.radius
                } else {
                    hw = col.width * 0.5f; hh = col.height * 0.5f
                }
                return floatArrayOf(
                    w.mapX(cx - hw, cy - hh), w.mapY(cx - hw, cy - hh),
                    w.mapX(cx + hw, cy - hh), w.mapY(cx + hw, cy - hh),
                    w.mapX(cx + hw, cy + hh), w.mapY(cx + hw, cy + hh),
                    w.mapX(cx - hw, cy + hh), w.mapY(cx - hw, cy + hh)
                )
            }
        }
        val parts = points.trim().split(Regex("[\\s;]+")).filter { it.isNotBlank() }
        val out = FloatArray(parts.size * 2)
        var n = 0
        for (p in parts) {
            val xy = p.split(',')
            if (xy.size != 2) continue
            val lx = xy[0].trim().toFloatOrNull() ?: continue
            val ly = xy[1].trim().toFloatOrNull() ?: continue
            out[n * 2] = w.mapX(lx, ly)
            out[n * 2 + 1] = w.mapY(lx, ly)
            n++
        }
        return out.copyOf(n * 2)
    }
}

/** Data handed to the renderer for one light. */
class LightRenderData(
    val light: Light2D,
    val x: Float,
    val y: Float,
    val color: Int,
    val intensity: Float,
    val radius: Float,
    val innerRadius: Float,
    val falloff: Float,
    val kind: Int,
    val direction: Float,
    val coneAngle: Float
)

/** A shadow quad (two triangles) extruded from an occluder edge. */
class ShadowQuad(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float)

/** Ambient light settings for a scene (also used by the editor's 2D lighting preview). */
class AmbientLight {
    var color = 0xFF2A3040.toInt()
    var intensity = 1f
    var showLights = true
    var maxLightsOnScreen = 16
    var lightmapScale = 0.5f

    fun effectiveColor(): Int = Colors.scale(color, intensity, 1f)
}

/**
 * Scene ambient light. Add one to a scene to control the baseline brightness, the light count cap
 * and the light map resolution (mobile knob). Without it the scene falls back to `Scene.ambient`.
 */
class AmbientLight2D : Component() {
    override val type = "AmbientLight2D"
    val ambient = AmbientLight()

    override fun props() = listOf(
        Prop.Color("Color", { ambient.color }, { ambient.color = it }, "Base light colour applied to the whole scene."),
        Prop.F("Intensity", { ambient.intensity }, { ambient.intensity = it.coerceIn(0f, 4f) }, 0.05f, 0f, 4f),
        Prop.B("Enable Lights", { ambient.showLights }, { ambient.showLights = it }),
        Prop.I("Max Lights", { ambient.maxLightsOnScreen }, { ambient.maxLightsOnScreen = it.coerceIn(0, 64) }, 1, 0, 64),
        Prop.F("Lightmap Scale", { ambient.lightmapScale }, { ambient.lightmapScale = it.coerceIn(0.25f, 1f) }, 0.05f, 0.25f, 1f)
    )

    override fun resetRuntime() {}
}

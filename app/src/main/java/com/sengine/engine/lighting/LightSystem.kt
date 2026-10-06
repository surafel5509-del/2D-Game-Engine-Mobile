package com.sengine.engine.lighting

import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Colors
import com.sengine.engine.math.Rect2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Collects the 2D lights and occluders that are relevant for a frame and computes the shadow
 * geometry. Only lights inside (or near) the camera view are submitted, and the number of active
 * lights is capped - this is what keeps lit scenes fast on low-end Android devices.
 */
class LightSystem {
    var ambient = AmbientLight()
    /** Statistics for the profiler. */
    var activeLights = 0
        private set
    var culledLights = 0
        private set
    var shadowQuads = 0
        private set
    var shadowBuildMs = 0f

    val lights = ArrayList<LightRenderData>()
    val shadows = ArrayList<ShadowQuad>()
    private val occluders = ArrayList<FloatArray>()
    private val scratch = FloatArray(2)

    /** Rebuilds the visible light list for the given camera rectangle. */
    fun gather(scene: Scene, view: Rect2, time: Float) {
        val t0 = System.nanoTime()
        lights.clear()
        shadows.clear()
        occluders.clear()
        culledLights = 0
        val pad = 4f
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val light = go.get<Light2D>() ?: continue
            if (!light.visible) continue
            light.updateIntensity(time)
            if (light.runtimeIntensity <= 0.001f) continue
            val w = go.world
            val x = w.tx
            val y = w.ty
            val r = if (light.kind == 2) 0f else light.radius
            if (light.kind != 2 && !Rect2(x - r, y - r, r * 2, r * 2).intersects(view)) {
                culledLights++
                continue
            }
            if (lights.size >= ambient.maxLightsOnScreen) {
                // keep the brightest/most important ones
                val minIdx = lights.indices.minByOrNull { lights[it].intensity * lights[it].radius } ?: 0
                val existing = lights[minIdx]
                if (existing.intensity * existing.radius >= light.runtimeIntensity * r) {
                    culledLights++
                    continue
                }
                lights.removeAt(minIdx)
            }
            lights.add(
                LightRenderData(
                    light, x, y,
                    Colors.withAlpha(light.color, 1f),
                    light.runtimeIntensity,
                    r,
                    light.innerRadius,
                    light.falloff,
                    light.kind,
                    light.direction,
                    light.coneAngle
                )
            )
        }
        activeLights = lights.size
        if (lights.isNotEmpty()) gatherOccluders(scene, view)
        buildShadows()
        shadowBuildMs = shadowBuildMs * 0.9f + (System.nanoTime() - t0) / 1e6f * 0.1f
    }

    private fun gatherOccluders(scene: Scene, view: Rect2) {
        val padded = view.grow(6f)
        for (go in scene.objects) {
            if (!go.isActiveInHierarchy()) continue
            val occ = go.get<LightOccluder2D>() ?: continue
            if (!occ.occludes) continue
            val poly = occ.worldPolygon()
            if (poly.size < 6) continue
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var i = 0
            while (i < poly.size) {
                minX = minOf(minX, poly[i]); maxX = maxOf(maxX, poly[i])
                minY = minOf(minY, poly[i + 1]); maxY = maxOf(maxY, poly[i + 1])
                i += 2
            }
            if (!padded.intersects(Rect2(minX, minY, maxX - minX, maxY - minY))) continue
            occluders.add(poly)
            if (occluders.size >= MAX_OCCLUDERS) break
        }
    }

    /**
     * Extrudes the silhouette edges of every occluder away from each light.
     * The resulting quads are drawn multiplicatively into the light map, which darkens exactly
     * the regions the light cannot reach.
     */
    private fun buildShadows() {
        for (light in lights) {
            if (!light.light.castShadows || light.kind == 2) continue
            val lx = light.x
            val ly = light.y
            val far = light.radius * light.light.shadowsLength.coerceAtLeast(0.1f) + 1f
            for (poly in occluders) {
                val n = poly.size / 2
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val ax = poly[i * 2]; val ay = poly[i * 2 + 1]
                    val bx = poly[j * 2]; val by = poly[j * 2 + 1]
                    // edge normal
                    var nx = by - ay
                    var ny = -(bx - ax)
                    val len = sqrt(nx * nx + ny * ny)
                    if (len < 1e-6f) continue
                    nx /= len; ny /= len
                    // is this edge facing away from the light?
                    if (nx * (lx - ax) + ny * (ly - ay) <= 0f) continue
                    // project both vertices away from the light
                    val a2x = ax + (ax - lx) / maxOf(0.0001f, dist(lx, ly, ax, ay)) * far
                    val a2y = ay + (ay - ly) / maxOf(0.0001f, dist(lx, ly, ax, ay)) * far
                    val b2x = bx + (bx - lx) / maxOf(0.0001f, dist(lx, ly, bx, by)) * far
                    val b2y = by + (by - ly) / maxOf(0.0001f, dist(lx, ly, bx, by)) * far
                    shadows.add(ShadowQuad(ax, ay, bx, by, b2x, b2y, a2x, a2y))
                }
            }
        }
        shadowQuads = shadows.size
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    fun clear() {
        lights.clear()
        shadows.clear()
        occluders.clear()
        activeLights = 0
        shadowQuads = 0
    }

    /** Ambient colour used as the lightmap clear colour. */
    fun ambientColor(): Int = ambient.effectiveColor()

    /**
     * Samples the light level at a world point (CPU side). Used by gameplay (stealth detection,
     * "is the torch lit?") and by the editor's lighting preview.
     */
    fun sampleLight(x: Float, y: Float): Float {
        var total = ambient.intensity
        for (l in lights) {
            val d = dist(l.x, l.y, x, y)
            if (l.kind == 2) {
                total += l.intensity
                continue
            }
            if (d > l.radius) continue
            if (l.kind == 1) {
                val ang = Math.toDegrees(kotlin.math.atan2((y - l.y).toDouble(), (x - l.x).toDouble())).toFloat()
                val delta = abs(com.sengine.engine.math.M.wrapAngle(ang - l.direction))
                if (delta > l.coneAngle * 0.5f) continue
            }
            val t = if (l.radius <= l.innerRadius) 0f else ((d - l.innerRadius) / (l.radius - l.innerRadius)).coerceIn(0f, 1f)
            total += l.intensity * pow(1f - t, l.falloff)
        }
        return total
    }

    private fun pow(v: Float, e: Float): Float {
        if (v <= 0f) return 0f
        var result = 1f
        var exp = e
        var base = v
        // simple float pow without Math.pow allocation overhead concerns
        return Math.pow(base.toDouble(), exp.toDouble()).toFloat()
    }

    companion object {
        const val MAX_OCCLUDERS = 128
    }
}

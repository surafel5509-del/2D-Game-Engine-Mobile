package com.sengine.engine.render

import com.sengine.engine.math.M
import com.sengine.engine.math.Rect2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The 2D view: an orthographic window into the world with zoom, rotation, limits and
 * pixel-perfect snapping. This replaces every 3D camera concept - the engine only ever has one
 * kind of view and it is 2D.
 */
class View2D {
    /** Centre of the view in world units. */
    @Volatile var cx = 0f
    @Volatile var cy = 0f
    /** Half-height of the view in world units (zoom is derived from this). */
    @Volatile var size = 5f
    /** View rotation in degrees (kept for screen shake, tilting vehicles and cinematic camera work). */
    @Volatile var rotation = 0f
    @Volatile var widthPx = 1
    @Volatile var heightPx = 1
    /** Snap the camera to whole texels (crisp pixel art). */
    @Volatile var pixelPerfect = false
    /** Reference pixels per world unit used by pixel-perfect snapping. */
    @Volatile var referencePixelsPerUnit = 64f

    val aspect: Float get() = widthPx.toFloat() / heightPx.coerceAtLeast(1)
    val halfW: Float get() = size * aspect
    val halfH: Float get() = size
    val pixelsPerUnit: Float get() = heightPx / (2f * size).coerceAtLeast(0.0001f)

    /** World-space rectangle currently visible (ignores rotation, used for culling). */
    fun bounds(): Rect2 = Rect2(cx - halfW, cy - halfH, halfW * 2f, halfH * 2f)

    fun screenToWorldX(sx: Float) = cx + (sx / widthPx * 2f - 1f) * halfW
    fun screenToWorldY(sy: Float) = cy + (1f - sy / heightPx * 2f) * halfH
    fun worldToScreenX(wx: Float) = (wx - cx) / halfW * 0.5f * widthPx + widthPx * 0.5f
    fun worldToScreenY(wy: Float) = heightPx * 0.5f - (wy - cy) / halfH * 0.5f * heightPx

    fun screenToWorld(sx: Float, sy: Float) = floatArrayOf(screenToWorldX(sx), screenToWorldY(sy))

    /** Snaps the centre to the reference pixel grid when pixel-perfect rendering is on. */
    fun applyPixelSnap() {
        if (!pixelPerfect) return
        val textureels = referencePixelsPerUnit
        val step = 1f / textureels
        // snap in view space so the whole scene moves on whole texels (Unity-style)
        val sx = cx * textureels
        val sy = cy * textureels
        cx = sx.roundToInt() / textureels
        cy = sy.roundToInt() / textureels
    }

    /**
     * Rotation-aware projection matrix. When the view is not rotated this reduces to a plain
     * orthographic matrix, which is the fast path the engine uses by default.
     */
    fun matrix(out: FloatArray) {
        if (abs(rotation) < 0.001f) {
            setOrtho(out, cx - halfW, cx + halfW, cy - halfH, cy + halfH)
            return
        }
        val rad = Math.toRadians(rotation.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val sx = 1f / halfW
        val sy = 1f / halfH
        // rotate then scale then translate: R * S, column-major
        out[0] = c * sx; out[1] = s * sy; out[2] = 0f; out[3] = 0f
        out[4] = -s * sx; out[5] = c * sy; out[6] = 0f; out[7] = 0f
        out[8] = 0f; out[9] = 0f; out[10] = -1f; out[11] = 0f
        out[12] = -(c * cx - s * cy) * sx
        out[13] = -(s * cx + c * cy) * sy
        out[14] = 0f; out[15] = 1f
    }

    private fun setOrtho(out: FloatArray, left: Float, right: Float, bottom: Float, top: Float) {
        out[0] = 2f / (right - left); out[1] = 0f; out[2] = 0f; out[3] = 0f
        out[4] = 0f; out[5] = 2f / (top - bottom); out[6] = 0f; out[7] = 0f
        out[8] = 0f; out[9] = 0f; out[10] = -1f; out[11] = 0f
        out[12] = -(right + left) / (right - left)
        out[13] = -(top + bottom) / (top - bottom)
        out[14] = 0f; out[15] = 1f
    }

    fun copyFrom(o: View2D) {
        cx = o.cx; cy = o.cy; size = o.size; rotation = o.rotation
        widthPx = o.widthPx; heightPx = o.heightPx
        pixelPerfect = o.pixelPerfect; referencePixelsPerUnit = o.referencePixelsPerUnit
    }

    /** Visible world rect grown by [margin] world units (culling helper). */
    fun cullBounds(margin: Float): Rect2 = Rect2(
        cx - halfW - margin, cy - halfH - margin,
        halfW * 2f + margin * 2f, halfH * 2f + margin * 2f
    )

    /**
     * Computes the zoom level needed to fit a world rectangle with padding
     * (used by "focus selection" and cinematic framing in the editor).
     */
    fun fitTo(rect: Rect2, padding: Float = 0.15f) {
        val pad = 1f + padding
        val neededH = (rect.h * 0.5f) * pad
        val neededW = (rect.w * 0.5f) * pad * aspect
        size = M.clamp(maxOf(neededH, neededW), 0.05f, 10000f)
        cx = rect.centerX
        cy = rect.centerY
    }

    override fun toString() = "View2D(center=(%.2f, %.2f), size=%.2f)".format(cx, cy, size)
}

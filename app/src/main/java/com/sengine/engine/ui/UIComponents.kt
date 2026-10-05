package com.sengine.engine.ui

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Signals
import com.sengine.engine.math.Affine
import com.sengine.engine.render.Renderer2D
import com.sengine.engine.render.View2D
import kotlin.math.*

/**
 * In-game UI system for HUD elements, menus, buttons, and overlays.
 * UI elements are rendered in screen space (independent of camera).
 */

/** Anchor points for UI positioning. */
enum class UIAnchor {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

/**
 * Canvas component that holds UI elements.
 * Rendered in screen space, separate from the game world.
 */
class UICanvas : Component() {
    override val type = "UICanvas"
    var sortMode = 0 // 0 = order, 1 = depth
    val elements = ArrayList<UIElement>()
    var referenceWidth = 1920f
    var referenceHeight = 1080f

    override fun props() = listOf(
        Prop.F("Reference Width", { referenceWidth }, { referenceWidth = it.coerceAtLeast(100f) }),
        Prop.F("Reference Height", { referenceHeight }, { referenceHeight = it.coerceAtLeast(100f) }),
        Prop.Choice("Sort Mode", listOf("Order", "Depth"), { sortMode }, { sortMode = it }),
    )

    fun addElement(element: UIElement): UIElement {
        elements.add(element)
        return element
    }

    fun removeElement(element: UIElement) {
        elements.remove(element)
    }

    fun findElement(name: String): UIElement? = elements.firstOrNull { it.name == name }

    /** Update all UI elements (handle input, animations). */
    fun updateUI(dt: Float, touchX: Float, touchY: Float, touched: Boolean, viewWidth: Float, viewHeight: Float) {
        for (e in elements) {
            if (!e.visible) continue
            e.update(dt, touchX, touchY, touched, viewWidth, viewHeight)
        }
    }

    /** Render all UI elements. */
    fun renderUI(r: Renderer2D, view: View2D) {
        val sorted = elements.sortedBy { it.depth }
        for (e in sorted) {
            if (!e.visible) continue
            e.render(r, view)
        }
    }
}

/**
 * Base class for UI elements.
 */
abstract class UIElement {
    var name = ""
    var x = 0f
    var y = 0f
    var width = 200f
    var height = 50f
    var anchor = UIAnchor.CENTER
    var depth = 0
    var visible = true
    var interactive = true
    var alpha = 1f
    var color = 0xFFFFFFFF.toInt()

    val signals = Signals()

    // Computed screen position
    var screenX = 0f; protected set
    var screenY = 0f; protected set

    fun computePosition(viewWidth: Float, viewHeight: Float): Pair<Float, Float> {
        val ax = when (anchor) {
            UIAnchor.TOP_LEFT, UIAnchor.CENTER_LEFT, UIAnchor.BOTTOM_LEFT -> 0f
            UIAnchor.TOP_CENTER, UIAnchor.CENTER, UIAnchor.BOTTOM_CENTER -> viewWidth * 0.5f
            UIAnchor.TOP_RIGHT, UIAnchor.CENTER_RIGHT, UIAnchor.BOTTOM_RIGHT -> viewWidth
        }
        val ay = when (anchor) {
            UIAnchor.TOP_LEFT, UIAnchor.TOP_CENTER, UIAnchor.TOP_RIGHT -> 0f
            UIAnchor.CENTER_LEFT, UIAnchor.CENTER, UIAnchor.CENTER_RIGHT -> viewHeight * 0.5f
            UIAnchor.BOTTOM_LEFT, UIAnchor.BOTTOM_CENTER, UIAnchor.BOTTOM_RIGHT -> viewHeight
        }
        screenX = ax + x
        screenY = ay + y
        return screenX to screenY
    }

    abstract fun update(dt: Float, touchX: Float, touchY: Float, touched: Boolean, viewWidth: Float, viewHeight: Float)
    abstract fun render(r: Renderer2D, view: View2D)

    open fun containsPoint(px: Float, py: Float): Boolean {
        return px in (screenX - width * 0.5f)..(screenX + width * 0.5f) &&
               py in (screenY - height * 0.5f)..(screenY + height * 0.5f)
    }
}

/**
 * Button UI element.
 */
class UIButton : UIElement() {
    var text = "Button"
    var textSize = 24f
    var textColor = 0xFFFFFFFF.toInt()
    var normalColor = 0xFF4A90D9.toInt()
    var hoverColor = 0xFF5AA0E9.toInt()
    var pressedColor = 0xFF3A80C9.toInt()
    var disabledColor = 0xFF666666.toInt()
    var cornerRadius = 8f
    var borderColor = 0x00000000
    var borderWidth = 0f
    var disabled = false

    private var pressed = false
    private var hovering = false

    override fun update(dt: Float, touchX: Float, touchY: Float, touched: Boolean, viewWidth: Float, viewHeight: Float) {
        computePosition(viewWidth, viewHeight)
        hovering = containsPoint(touchX, touchY)
        val wasPressed = pressed
        pressed = hovering && touched
        if (wasPressed && !pressed && hovering && !disabled) {
            signals.emit("clicked")
        }
    }

    override fun render(r: Renderer2D, view: View2D) {
        val (sx, sy) = screenX to screenY
        // Convert screen coords to world coords for rendering
        val wx = view.screenToWorldX(sx)
        val wy = view.screenToWorldY(sy)
        val ppu = view.pixelsPerUnit
        val w = width / ppu
        val h = height / ppu

        val c = when {
            disabled -> disabledColor
            pressed -> pressedColor
            hovering -> hoverColor
            else -> normalColor
        }

        val m = Affine()
        m.a = w; m.d = h; m.tx = wx; m.ty = wy
        r.quad(m, c, 0, null, 1f)
    }
}

/**
 * Text display UI element.
 */
class UILabel : UIElement() {
    var text = "Text"
    var textSize = 24f
    var textColor = 0xFFFFFFFF.toInt()
    var align = 1 // 0 left, 1 center, 2 right
    var bold = false

    override fun update(dt: Float, touchX: Float, touchY: Float, touched: Boolean, viewWidth: Float, viewHeight: Float) {
        computePosition(viewWidth, viewHeight)
    }

    override fun render(r: Renderer2D, view: View2D) {
        // Text rendering handled by SceneRenderer using texture cache
    }
}

/**
 * Progress bar UI element.
 */
class UIProgressBar : UIElement() {
    var value = 0.5f // 0..1
    var backgroundColor = 0xFF333333.toInt()
    var fillColor = 0xFF4CAF50.toInt()
    var borderColor = 0xFF555555.toInt()
    var borderWidth = 2f
    var fillDirection = 0 // 0 = left-to-right, 1 = bottom-to-top

    override fun update(dt: Float, touchX: Float, touchY: Float, touched: Boolean, viewWidth: Float, viewHeight: Float) {
        computePosition(viewWidth, viewHeight)
    }

    override fun render(r: Renderer2D, view: View2D) {
        val (sx, sy) = screenX to screenY
        val wx = view.screenToWorldX(sx)
        val wy = view.screenToWorldY(sy)
        val ppu = view.pixelsPerUnit
        val w = width / ppu
        val h = height / ppu

        // Background
        val mbg = Affine()
        mbg.a = w; mbg.d = h; mbg.tx = wx; mbg.ty = wy
        r.quad(mbg, backgroundColor, 0, null, 1f)

        // Fill
        val fillW = w * value.coerceIn(0f, 1f)
        val mfill = Affine()
        mfill.a = fillW; mfill.d = h
        mfill.tx = wx - w * 0.5f + fillW * 0.5f
        mfill.ty = wy
        r.quad(mfill, fillColor, 0, null, 1f)
    }

    fun setValue(v: Float) {
        value = v.coerceIn(0f, 1f)
        signals.emit("value_changed", value)
    }
}

/**
 * Image display UI element.
 */
class UIImage : UIElement() {
    var textureName = ""
    var preserveAspect = true
    var tint = 0xFFFFFFFF.toInt()

    override fun update(dt: Float, touchX: Float, touchY: Float, touched: Boolean, viewWidth: Float, viewHeight: Float) {
        computePosition(viewWidth, viewHeight)
    }

    override fun render(r: Renderer2D, view: View2D) {
        // Rendering handled by SceneRenderer using texture cache
    }
}

/**
 * Slider/joystick input UI element.
 */
class UISlider : UIElement() {
    var minValue = 0f
    var maxValue = 1f
    var currentValue = 0.5f
    var trackColor = 0xFF333333.toInt()
    var thumbColor = 0xFFFFFFFF.toInt()
    var fillTint = 0xFF4A90D9.toInt()
    var isDragging = false

    override fun update(dt: Float, touchX: Float, touchY: Float, touched: Boolean, viewWidth: Float, viewHeight: Float) {
        computePosition(viewWidth, viewHeight)
        if (touched && containsPoint(touchX, touchY)) {
            isDragging = true
            val relX = (touchX - (screenX - width * 0.5f)) / width
            val newValue = minValue + relX * (maxValue - minValue)
            currentValue = newValue.coerceIn(minValue, maxValue)
            signals.emit("value_changed", currentValue)
        } else if (!touched) {
            isDragging = false
        }
    }

    override fun render(r: Renderer2D, view: View2D) {
        val wx = view.screenToWorldX(screenX)
        val wy = view.screenToWorldY(screenY)
        val ppu = view.pixelsPerUnit
        val w = width / ppu
        val h = height / ppu

        // Track
        val mTrack = Affine()
        mTrack.a = w; mTrack.d = h * 0.2f; mTrack.tx = wx; mTrack.ty = wy
        r.quad(mTrack, trackColor, 0, null, 1f)

        // Fill
        val progress = (currentValue - minValue) / (maxValue - minValue)
        val fillW = w * progress
        val mFill = Affine()
        mFill.a = fillW; mFill.d = h * 0.2f
        mFill.tx = wx - w * 0.5f + fillW * 0.5f
        mFill.ty = wy
        r.quad(mFill, fillTint, 0, null, 1f)

        // Thumb
        val thumbX = wx - w * 0.5f + w * progress
        val mThumb = Affine()
        mThumb.a = h * 0.4f; mThumb.d = h * 0.4f
        mThumb.tx = thumbX; mThumb.ty = wy
        r.quad(mThumb, thumbColor, 1, null, 1f) // Circle shape
    }
}

/**
 * Virtual joystick for mobile controls.
 */
class UIVirtualJoystick : UIElement() {
    var radius = 80f
    var deadZone = 0.15f
    var baseColor = 0x44FFFFFF.toInt()
    var stickColor = 0xAAFFFFFF.toInt()
    var axisX = 0f; private set
    var axisY = 0f; private set
    var isActive = false; private set
    private var touchStartX = 0f
    private var touchStartY = 0f
    var floating = false // Follows touch position

    override fun update(dt: Float, touchX: Float, touchY: Float, touched: Boolean, viewWidth: Float, viewHeight: Float) {
        computePosition(viewWidth, viewHeight)
        if (touched) {
            if (!isActive && containsPoint(touchX, touchY)) {
                isActive = true
                touchStartX = if (floating) touchX else screenX
                touchStartY = if (floating) touchY else screenY
            }
            if (isActive) {
                val dx = touchX - touchStartX
                val dy = touchY - touchStartY
                val dist = sqrt(dx * dx + dy * dy)
                if (dist > radius) {
                    val nx = dx / dist
                    val ny = dy / dist
                    axisX = nx
                    axisY = -ny // flip Y for world coords
                } else if (dist > deadZone * radius) {
                    axisX = dx / radius
                    axisY = -dy / radius
                } else {
                    axisX = 0f; axisY = 0f
                }
                if (floating) {
                    screenX = touchStartX
                    screenY = touchStartY
                }
            }
        } else {
            isActive = false
            axisX = 0f; axisY = 0f
        }
    }

    override fun render(r: Renderer2D, view: View2D) {
        val wx = view.screenToWorldX(screenX)
        val wy = view.screenToWorldY(screenY)
        val ppu = view.pixelsPerUnit
        val rPx = radius / ppu

        // Base circle
        val mBase = Affine()
        mBase.a = rPx * 2f; mBase.d = rPx * 2f; mBase.tx = wx; mBase.ty = wy
        r.quad(mBase, baseColor, 1, null, ppu * rPx)

        // Stick
        val stickX = wx + axisX * rPx * 0.6f
        val stickY = wy - axisY * rPx * 0.6f
        val mStick = Affine()
        mStick.a = rPx; mStick.d = rPx; mStick.tx = stickX; mStick.ty = stickY
        r.quad(mStick, stickColor, 1, null, ppu * rPx * 0.5f)
    }
}

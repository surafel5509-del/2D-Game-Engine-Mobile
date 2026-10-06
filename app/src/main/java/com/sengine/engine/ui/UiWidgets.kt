package com.sengine.engine.ui

import com.sengine.engine.core.AssetKind
import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M
import kotlin.math.abs

/**
 * Base class of every game UI widget.
 *
 * Widgets live in the scene tree (so the editor can select, inspect and animate them) and are
 * laid out in "UI units" relative to a [UiCanvas]. The layout is anchor based with margins,
 * padding, auto-sizing and child layouts, which makes the UI responsive on any screen.
 */
abstract class UiWidget : Component() {
    /** Anchor preset inside the parent. */
    var anchor = UiAnchor.MIDDLE_CENTER
    var offsetX = 0f
    var offsetY = 0f
    var width = 200f
    var height = 60f
    /** When true the size is derived from the content (labels, buttons). */
    var autoWidth = false
    var autoHeight = false
    /** Extra margin applied when stretching. */
    var marginLeft = 0f
    var marginRight = 0f
    var marginTop = 0f
    var marginBottom = 0f

    var layout = UiLayout.FREE
    var spacing = 8f
    var padding = 8f
    var gridColumns = 2

    var visible = true
    var interactable = true
    var opacity = 1f
    /** Draw order inside the canvas (higher on top). */
    var order = 0
    /** Widgets with tabIndex >= 0 are only visible when the matching UiTabs page is selected. */
    var tabIndex = -1
    /** Accessibility label read by screen readers / shown in the accessibility preview. */
    var accessibilityLabel = ""
    /** Animations (seconds): fade/slide/scale transitions between states. */
    var animateHover = 0.08f
    var anchorOffsetAnim = true

    // ---- runtime state (filled by UiSystem)
    val rect = UiRect()
    var state = UiState.NORMAL
        internal set
    var hovered = false
        internal set
    var pressed = false
        internal set
    var focused = false
        internal set
    var scrollOffset = 0f
    var parentWidget: UiWidget? = null
        internal set
    /** Current animation value used by draw (0..1 for hover/press smoothness). */
    var animT = 0f
        internal set
    /** Text to display for widgets that can render a label. */
    open var text: String = ""

    val isInteractable: Boolean get() = interactable && enabled
    val isVisibleInHierarchy: Boolean get() = visible && enabled && (parentWidget?.isVisibleInHierarchy ?: true)

    /** Called every frame: computes the widget rect from the parent rect. */
    open fun layoutSelf(parentRect: UiRect, theme: UiTheme) {
        val ax = UiAnchors.anchorX(anchor)
        val ay = UiAnchors.anchorY(anchor)
        var w = if (autoWidth) contentWidth(theme) else width
        var h = if (autoHeight) contentHeight(theme) else height
        if (UiAnchors.stretchesWide(anchor)) w = parentRect.w - marginLeft - marginRight
        if (UiAnchors.stretchesTall(anchor)) h = parentRect.h - marginTop - marginBottom
        val pivotX = if (UiAnchors.stretchesWide(anchor)) 0f else ax
        val pivotY = if (UiAnchors.stretchesTall(anchor)) 0f else ay
        val x = parentRect.x + ax * parentRect.w - pivotX * w + offsetX + (if (pivotX == 0f) marginLeft else 0f)
        val y = parentRect.y + ay * parentRect.h - pivotY * h + offsetY + (if (pivotY == 0f) marginTop else 0f)
        rect.setRect(x, y, w, h)
    }

    protected open fun contentWidth(theme: UiTheme): Float {
        val size = theme.fontSize * theme.textScale
        return UiText.measure(text, size) + padding * 2f
    }

    protected open fun contentHeight(theme: UiTheme): Float {
        val size = theme.fontSize * theme.textScale
        return UiText.measureHeight(text, width - padding * 2f, size) + padding * 2f
    }

    /** Computes the rects of this widget's child widgets according to [layout]. */
    open fun layoutChildren(children: List<UiWidget>, theme: UiTheme) {
        if (children.isEmpty()) return
        val contentX = rect.x + padding
        val contentY = rect.y + padding
        val contentW = (rect.w - padding * 2f).coerceAtLeast(0f)
        val contentH = (rect.h - padding * 2f).coerceAtLeast(0f)
        val content = UiRect().setRect(contentX, contentY - scrollOffset, contentW, contentH)
        when (layout) {
            UiLayout.FREE -> children.forEach { it.layoutSelf(rect, theme) }
            UiLayout.HORIZONTAL -> {
                var x = content.x
                for (c in children) {
                    c.layoutSelf(UiRect().setRect(x, content.y, content.w, content.h), theme)
                    x += c.rect.w + spacing
                }
            }
            UiLayout.VERTICAL -> {
                var y = content.y
                for (c in children) {
                    c.layoutSelf(UiRect().setRect(content.x, y, content.w, content.h), theme)
                    y += c.rect.h + spacing
                }
            }
            UiLayout.GRID -> {
                val cols = gridColumns.coerceAtLeast(1)
                val cellW = (content.w - spacing * (cols - 1)) / cols
                var row = 0
                var col = 0
                var cellH = 0f
                for (c in children) {
                    if (cellH == 0f) cellH = c.height
                    c.layoutSelf(UiRect().setRect(content.x + col * (cellW + spacing), content.y + row * (cellH + spacing), cellW, content.h), theme)
                    col++
                    if (col >= cols) { col = 0; row++ }
                }
            }
        }
    }

    open fun draw(list: UiDrawList, theme: UiTheme) {
        val effective = if (!isInteractable) UiState.DISABLED else state
        val color = Colors.withAlpha(theme.colorFor(effective), opacity * Colors.alpha(theme.colorFor(effective)) / 255f)
        list.rect(rect.x, rect.y, rect.w, rect.h, color, theme.cornerRadius * theme.fontSize)
        if (effective == UiState.FOCUSED) {
            list.rect(rect.x, rect.y, rect.w, rect.h, 0, theme.cornerRadius * theme.fontSize, theme.accentColor, 2f)
        }
        if (text.isNotEmpty()) {
            val size = theme.fontSize * theme.textScale
            list.text(
                rect.x + padding, rect.y + padding, rect.w - padding * 2f, rect.h - padding * 2f,
                text, theme.textColorFor(effective), size, UiTextAlign.CENTER, 1,
                theme.font, true, order
            )
        }
    }

    /** Handles a pointer event; returns true when the widget consumed it. */
    open fun onPointer(event: UiPointerEvent, system: UiSystem): Boolean = false

    /** Handles a keyboard event when the widget has focus. */
    open fun onKey(event: UiKeyEvent, system: UiSystem): Boolean = false

    /** Accessibility description (screen reader text). */
    open fun accessibilityText(): String =
        accessibilityLabel.ifBlank { if (text.isNotBlank()) text else type }

    override fun props(): List<Prop> = listOf(
        Prop.B("Visible", { visible }, { visible = it }),
        Prop.B("Interactable", { interactable }, { interactable = it }),
        Prop.Choice("Anchor", UiAnchor.labels, { UiAnchor.entries.indexOf(anchor) }, { anchor = UiAnchor.of(it) }),
        Prop.F("Offset X", { offsetX }, { offsetX = it }, 1f),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }, 1f),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(1f) }, 5f, 1f, 10000f),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(1f) }, 5f, 1f, 10000f),
        Prop.B("Auto Width", { autoWidth }, { autoWidth = it }),
        Prop.B("Auto Height", { autoHeight }, { autoHeight = it }),
        Prop.F("Margin L", { marginLeft }, { marginLeft = it }, 1f),
        Prop.F("Margin R", { marginRight }, { marginRight = it }, 1f),
        Prop.F("Margin T", { marginTop }, { marginTop = it }, 1f),
        Prop.F("Margin B", { marginBottom }, { marginBottom = it }, 1f),
        Prop.Choice("Child Layout", UiLayout.labels, { UiLayout.entries.indexOf(layout) }, { layout = UiLayout.of(it) }),
        Prop.F("Spacing", { spacing }, { spacing = it }, 1f),
        Prop.F("Padding", { padding }, { padding = it.coerceAtLeast(0f) }, 1f, 0f, 200f),
        Prop.I("Grid Columns", { gridColumns }, { gridColumns = it.coerceIn(1, 20) }, 1, 1, 20),
        Prop.F("Opacity", { opacity }, { opacity = it.coerceIn(0f, 1f) }, 0.05f, 0f, 1f),
        Prop.I("Order", { order }, { order = it }),
        Prop.I("Tab Index", { tabIndex }, { tabIndex = it }, 1),
        Prop.S("Accessibility Label", { accessibilityLabel }, { accessibilityLabel = it }),
        Prop.Info("Bounds", { "${rect.x.toInt()},${rect.y.toInt()} ${rect.w.toInt()}x${rect.h.toInt()}" })
    )
}

/** UiCanvas - the root of a screen-space UI hierarchy. */
class UiCanvas : UiWidget() {
    override val type = "UiCanvas"
    /** Design resolution; the canvas is scaled to the real screen. */
    var referenceWidth = 1280f
    var referenceHeight = 720f
    /** 0 = Fit Height, 1 = Fit Width, 2 = Fill, 3 = Stretch. */
    var scaleMode = 0
    var themeName = "Dark"
    var customTheme: UiTheme? = null
    /** Blocks touches from reaching the game world. */
    var blocksGameInput = false
    /** Draws a full-screen dim backdrop (menus/pause screens). */
    var backdrop = false
    var backdropColor = 0x99000000.toInt()

    var scale = 1f
        private set

    override var text: String = ""

    val theme: UiTheme get() = customTheme ?: UiTheme.presets[themeName] ?: UiTheme.presets.values.first()

    override fun layoutSelf(parentRect: UiRect, theme: UiTheme) {
        // the canvas always covers the screen
        rect.setRect(0f, 0f, referenceWidth, referenceHeight)
    }

    fun computeScale(screenW: Float, screenH: Float): Float {
        val sx = screenW / referenceWidth
        val sy = screenH / referenceHeight
        scale = when (scaleMode) {
            0 -> sy
            1 -> sx
            2 -> maxOf(sx, sy)
            else -> 1f
        }
        return scale
    }

    override fun props(): List<Prop> = super.props() + listOf(
        Prop.F("Reference Width", { referenceWidth }, { referenceWidth = it.coerceAtLeast(64f) }, 16f, 64f, 8192f),
        Prop.F("Reference Height", { referenceHeight }, { referenceHeight = it.coerceAtLeast(64f) }, 16f, 64f, 8192f),
        Prop.Choice("Scale Mode", listOf("Fit Height", "Fit Width", "Fill", "Stretch"), { scaleMode }, { scaleMode = it }),
        Prop.Choice("Theme", UiTheme.presets.keys.toList(), { UiTheme.presets.keys.indexOf(themeName).coerceAtLeast(0) }, {
            themeName = UiTheme.presets.keys.toList()[it]
        }),
        Prop.F("Text Scale", { theme.textScale }, { theme.textScale = it.coerceIn(0.6f, 2.5f) }, 0.05f, 0.6f, 2.5f),
        Prop.B("Backdrop", { backdrop }, { backdrop = it }),
        Prop.Color("Backdrop Color", { backdropColor }, { backdropColor = it }),
        Prop.B("Blocks Game Input", { blocksGameInput }, { blocksGameInput = it })
    )

    override fun draw(list: UiDrawList, theme: UiTheme) {
        if (backdrop) list.rect(0f, 0f, referenceWidth, referenceHeight, Colors.withAlpha(backdropColor, opacity * (Colors.alpha(backdropColor) / 255f)))
    }
}

/** A styled panel/container (cards, windows, HUD frames). */
class UiPanel : UiWidget() {
    override val type = "UiPanel"
    var background = 0xE61A1F27.toInt()
    var borderColor = 0xFF2E3644.toInt()
    var borderWidth = 1f
    var cornerRadius = 0.12f
    var shadow = true
    /** When true the panel follows the canvas theme colours. */
    var useThemeColor = true

    override fun props() = super.props() + listOf(
        Prop.B("Use Theme Color", { useThemeColor }, { useThemeColor = it }),
        Prop.Color("Background", { background }, { background = it }),
        Prop.Color("Border", { borderColor }, { borderColor = it }),
        Prop.F("Border Width", { borderWidth }, { borderWidth = it.coerceAtLeast(0f) }, 0.5f, 0f, 20f),
        Prop.F("Corner Radius", { cornerRadius }, { cornerRadius = it.coerceIn(0f, 1f) }, 0.02f, 0f, 1f),
        Prop.B("Shadow", { shadow }, { shadow = it })
    )

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val bg = if (useThemeColor) theme.panelColor else background
        val bc = if (useThemeColor) theme.panelBorder else borderColor
        val radius = cornerRadius * theme.fontSize
        if (shadow) {
            list.rect(rect.x + 3f, rect.y + 4f, rect.w, rect.h, theme.shadowColor, radius, 0, 0f, order - 1)
        }
        list.rect(rect.x, rect.y, rect.w, rect.h, Colors.withAlpha(bg, opacity * (Colors.alpha(bg) / 255f)), radius, bc, borderWidth, order)
    }
}

/** Text label with wrapping, alignment and optional localisation key. */
class UiLabel : UiWidget() {
    override val type = "UiLabel"
    override var text: String = "Label"
    var fontSize = 0f
    var align = UiTextAlign.LEFT
    /** 0 = top, 1 = middle, 2 = bottom. */
    var verticalAlign = 1
    var color = Colors.WHITE
    var useThemeColor = true
    var bold = false
    var wrap = true
    var localizationKey = ""

    override fun contentWidth(theme: UiTheme): Float =
        UiText.measure(text, (if (fontSize > 0f) fontSize else theme.fontSize) * theme.textScale) + padding * 2f

    override fun contentHeight(theme: UiTheme): Float =
        UiText.measureHeight(text, width - padding * 2f, (if (fontSize > 0f) fontSize else theme.fontSize) * theme.textScale) + padding * 2f

    override fun props() = super.props() + listOf(
        Prop.S("Text", { text }, { text = it }, multiline = true),
        Prop.S("Localization Key", { localizationKey }, { localizationKey = it }),
        Prop.F("Font Size", { fontSize }, { fontSize = it.coerceAtLeast(0f) }, 1f, 0f, 200f),
        Prop.Choice("Align", UiTextAlign.labels, { UiTextAlign.entries.indexOf(align) }, { align = UiTextAlign.of(it) }),
        Prop.Choice("Vertical", listOf("Top", "Middle", "Bottom"), { verticalAlign }, { verticalAlign = it.coerceIn(0, 2) }),
        Prop.B("Use Theme Color", { useThemeColor }, { useThemeColor = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.B("Bold", { bold }, { bold = it }),
        Prop.B("Wrap", { wrap }, { wrap = it })
    )

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val size = (if (fontSize > 0f) fontSize else theme.fontSize) * theme.textScale
        val c = if (useThemeColor) theme.textColor else color
        list.text(rect.x, rect.y, rect.w, rect.h, text, Colors.withAlpha(c, opacity), size, align, verticalAlign, theme.font, wrap, order, bold)
        if (focused) list.rect(rect.x, rect.y, rect.w, rect.h, 0, theme.cornerRadius * theme.fontSize, theme.accentColor, 1.5f, order)
    }
}

/** Image / sprite widget with optional nine-slice and tint. */
class UiImage : UiWidget() {
    override val type = "UiImage"
    var texture = ""
    var tint = Colors.WHITE
    var nineSlice = false
    var sliceBorder = 12f
    var preserveAspect = false
    var fillColor = Colors.CLEAR

    override fun props() = super.props() + listOf(
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
        Prop.Color("Tint", { tint }, { tint = it }),
        Prop.B("Nine Slice", { nineSlice }, { nineSlice = it }),
        Prop.F("Slice Border", { sliceBorder }, { sliceBorder = it.coerceAtLeast(0f) }, 1f, 0f, 512f),
        Prop.B("Preserve Aspect", { preserveAspect }, { preserveAspect = it }),
        Prop.Color("Fill", { fillColor }, { fillColor = it })
    )

    override fun draw(list: UiDrawList, theme: UiTheme) {
        if (Colors.alpha(fillColor) > 0) {
            list.rect(rect.x, rect.y, rect.w, rect.h, Colors.withAlpha(fillColor, opacity), 0f, 0, 0f, order)
        }
        if (texture.isNotBlank()) {
            list.sprite(rect.x, rect.y, rect.w, rect.h, texture, Colors.withAlpha(tint, opacity), order)
        } else {
            list.rect(rect.x, rect.y, rect.w, rect.h, Colors.withAlpha(theme.widgetColor, opacity * 0.5f), 0f, theme.panelBorder, 1f, order)
        }
    }

    override fun accessibilityText(): String = accessibilityLabel.ifBlank { "Image ${texture.substringAfterLast('/')}" }
}

/** Button with pressed/hover/selected/disabled states, signals and keyboard activation. */
class UiButton : UiWidget() {
    override val type = "UiButton"
    override var text: String = "Button"
    var icon = ""
    var toggle = false
    var selected = false
    var onClickSignal = "clicked"
    var onClickSound = ""
    var fontSize = 0f
    var colorNormal = 0
    var colorHover = 0
    var colorPressed = 0
    var cornerRadius = -1f
    /** Animation: scale bounce when pressed. */
    var bounce = 0.06f
    var tooltip = ""

    var clickCount = 0; private set

    override fun contentWidth(theme: UiTheme): Float =
        UiText.measure(text, (if (fontSize > 0f) fontSize else theme.fontSize) * theme.textScale) + padding * 2f + 24f

    override fun props() = super.props() + listOf(
        Prop.S("Text", { text }, { text = it }),
        Prop.Asset("Icon", AssetKind.TEXTURE, { icon }, { icon = it }),
        Prop.B("Toggle", { toggle }, { toggle = it }, "Toggle buttons stay selected when clicked."),
        Prop.B("Selected", { selected }, { selected = it }),
        Prop.S("Click Signal", { onClickSignal }, { onClickSignal = it }),
        Prop.Asset("Click Sound", AssetKind.SOUND, { onClickSound }, { onClickSound = it }),
        Prop.F("Font Size", { fontSize }, { fontSize = it.coerceAtLeast(0f) }, 1f, 0f, 200f),
        Prop.Color("Color", { colorNormal }, { colorNormal = it }),
        Prop.Color("Hover Color", { colorHover }, { colorHover = it }),
        Prop.Color("Pressed Color", { colorPressed }, { colorPressed = it }),
        Prop.F("Corner Radius", { cornerRadius }, { cornerRadius = it }, 0.02f, -1f, 1f),
        Prop.F("Bounce", { bounce }, { bounce = it.coerceIn(0f, 0.4f) }, 0.01f, 0f, 0.4f),
        Prop.S("Tooltip", { tooltip }, { tooltip = it }),
        Prop.Info("Clicks", { clickCount.toString() })
    )

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val effective = if (!isInteractable) UiState.DISABLED else if (selected && toggle) UiState.SELECTED else state
        var color = when {
            colorNormal != 0 && effective == UiState.NORMAL -> colorNormal
            colorHover != 0 && effective == UiState.HOVER -> colorHover
            colorPressed != 0 && effective == UiState.PRESSED -> colorPressed
            else -> theme.colorFor(effective)
        }
        color = Colors.withAlpha(color, opacity * (Colors.alpha(color) / 255f))
        val radius = if (cornerRadius >= 0f) cornerRadius * theme.fontSize else theme.cornerRadius * theme.fontSize
        val scale = 1f - bounce * (if (pressed) 1f else 0f)
        val w = rect.w * scale
        val h = rect.h * scale
        val x = rect.centerX - w * 0.5f
        val y = rect.centerY - h * 0.5f
        list.rect(x, y, w, h, color, radius, theme.panelBorder, 1f, order)
        if (focused) list.rect(x - 2f, y - 2f, w + 4f, h + 4f, 0, radius + 2f, theme.accentColor, 2f, order)
        val size = (if (fontSize > 0f) fontSize else theme.fontSize) * theme.textScale
        var textX = x
        var textW = w
        if (icon.isNotBlank()) {
            val iconSize = h * 0.55f
            list.sprite(x + padding * 0.6f, y + (h - iconSize) * 0.5f, iconSize, iconSize, icon, Colors.withAlpha(theme.textColorFor(effective), opacity), order)
            textX = x + padding * 0.6f + iconSize
            textW = w - (textX - x) - padding * 0.6f
        }
        list.text(textX, y, textW, h, text, theme.textColorFor(effective), size, UiTextAlign.CENTER, 1, theme.font, false, order)
    }

    /** Programmatic click (also used by the accessibility layer and keyboard activation). */
    fun click(system: UiSystem? = null) {
        if (!isInteractable) return
        clickCount++
        if (toggle) selected = !selected
        go.emit(onClickSignal, selected)
        go.emit("uiClick", go.name)
        system?.playClick(go, onClickSound)
    }

    override fun onKey(event: UiKeyEvent, system: UiSystem): Boolean {
        if (event.action == UiKeyEvent.ACTION_DOWN && (event.code == "ENTER" || event.code == "SPACE")) {
            pressed = true
            return true
        }
        if (event.action == UiKeyEvent.ACTION_UP && (event.code == "ENTER" || event.code == "SPACE")) {
            pressed = false
            click(system)
            return true
        }
        return false
    }

    override fun accessibilityText(): String = accessibilityLabel.ifBlank { "Button $text" }
}

/** Slider with drag support, optional snapping, value display and change events. */
class UiSlider : UiWidget() {
    override val type = "UiSlider"
    var min = 0f
    var max = 1f
    var value = 0.5f
    var step = 0f
    var showValue = false
    var onChangeSignal = "changed"
    var fillColor = 0
    var trackHeight = 10f
    var knobSize = 22f

    var dragging = false; private set

    fun applyValue(v: Float, notify: Boolean = true) {
        val nv = M.clamp(if (step > 0f) M.roundTo(v, step) else v, min, max)
        if (nv == value) return
        value = nv
        if (notify) {
            go.emit(onChangeSignal, value)
            go.emit("uiValueChanged", value)
        }
    }

    val normalized: Float get() = if (max - min <= 1e-6f) 0f else (value - min) / (max - min)

    override fun props() = super.props() + listOf(
        Prop.F("Min", { min }, { min = it }, 0.1f),
        Prop.F("Max", { max }, { max = it }, 0.1f),
        Prop.F("Value", { value }, { applyValue(it, false) }, 0.05f),
        Prop.F("Step", { step }, { step = it.coerceAtLeast(0f) }, 0.05f, 0f, 100f),
        Prop.B("Show Value", { showValue }, { showValue = it }),
        Prop.S("Change Signal", { onChangeSignal }, { onChangeSignal = it }),
        Prop.Color("Fill Color", { fillColor }, { fillColor = it }),
        Prop.F("Track Height", { trackHeight }, { trackHeight = it.coerceAtLeast(1f) }, 1f, 1f, 200f),
        Prop.F("Knob Size", { knobSize }, { knobSize = it.coerceAtLeast(4f) }, 1f, 4f, 200f)
    )

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val cy = rect.centerY
        val trackY = cy - trackHeight * 0.5f
        val fill = if (fillColor != 0) fillColor else theme.sliderFill
        list.rect(rect.x, trackY, rect.w, trackHeight, Colors.withAlpha(theme.sliderTrack, opacity), trackHeight * 0.5f, 0, 0f, order)
        val fillW = rect.w * normalized
        list.rect(rect.x, trackY, fillW, trackHeight, Colors.withAlpha(fill, opacity), trackHeight * 0.5f, 0, 0f, order + 1)
        val knobX = rect.x + fillW - knobSize * 0.5f
        val knobColor = if (!isInteractable) theme.widgetColorDisabled else if (pressed || dragging) theme.accentColor else theme.textColor
        list.rect(knobX, cy - knobSize * 0.5f, knobSize, knobSize, Colors.withAlpha(knobColor, opacity), knobSize * 0.5f, theme.panelBorder, 1f, order + 2)
        if (showValue) {
            list.text(rect.x, rect.y - 18f, rect.w, 18f, "%.2f".format(value), theme.textColorDim, theme.fontSize * 0.8f, UiTextAlign.RIGHT, 1, theme.font, false, order + 3)
        }
    }

    override fun onPointer(event: UiPointerEvent, system: UiSystem): Boolean {
        when (event.action) {
            UiPointerEvent.ACTION_DOWN -> { dragging = true; updateFromPointer(event); return true }
            UiPointerEvent.ACTION_MOVE -> if (dragging) { updateFromPointer(event); return true }
            UiPointerEvent.ACTION_UP -> if (dragging) { dragging = false; return true }
        }
        return false
    }

    private fun updateFromPointer(event: UiPointerEvent) {
        val t = if (rect.w <= 0f) 0f else ((event.x - rect.x) / rect.w).coerceIn(0f, 1f)
        applyValue(min + t * (max - min))
    }

    override fun onKey(event: UiKeyEvent, system: UiSystem): Boolean {
        if (event.action != UiKeyEvent.ACTION_DOWN) return false
        val delta = if (step > 0f) step else (max - min) / 20f
        return when (event.code) {
            "LEFT" -> { applyValue(value - delta); true }
            "RIGHT" -> { applyValue(value + delta); true }
            else -> false
        }
    }
}

/** Progress bar (health, loading, fuel). */
class UiProgressBar : UiWidget() {
    override val type = "UiProgressBar"
    var min = 0f
    var max = 1f
    var value = 1f
    var fillColor = 0
    var backgroundFill = 0
    var showText = false
    var textFormat = "%.0f%%"
    var smooth = 0.25f
    var animate = true
    private var display = 1f

    val normalized: Float get() = if (max - min <= 1e-6f) 0f else ((value - min) / (max - min)).coerceIn(0f, 1f)

    fun applyValue(v: Float) {
        value = M.clamp(v, min, max)
        if (!animate) display = normalized
    }

    override fun resetRuntime() { display = normalized }

    override fun props() = super.props() + listOf(
        Prop.F("Min", { min }, { min = it }, 0.1f),
        Prop.F("Max", { max }, { max = it }, 0.1f),
        Prop.F("Value", { value }, { value = it }, 0.05f),
        Prop.Color("Fill Color", { fillColor }, { fillColor = it }),
        Prop.Color("Background", { backgroundFill }, { backgroundFill = it }),
        Prop.B("Show Text", { showText }, { showText = it }),
        Prop.S("Text Format", { textFormat }, { textFormat = it }),
        Prop.B("Animate", { animate }, { animate = it }),
        Prop.F("Smooth Time", { smooth }, { smooth = it.coerceIn(0.01f, 3f) }, 0.05f, 0.01f, 3f)
    )

    fun updateDisplay(dt: Float) {
        if (!animate) { display = normalized; return }
        display = M.damp(display, normalized, 1f / smooth.coerceAtLeast(0.01f), dt)
    }

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val bg = if (backgroundFill != 0) backgroundFill else theme.sliderTrack
        val fg = if (fillColor != 0) fillColor else theme.sliderFill
        list.rect(rect.x, rect.y, rect.w, rect.h, Colors.withAlpha(bg, opacity), theme.cornerRadius * theme.fontSize, theme.panelBorder, 1f, order)
        val w = rect.w * display.coerceIn(0f, 1f)
        list.rect(rect.x, rect.y, w, rect.h, Colors.withAlpha(fg, opacity), theme.cornerRadius * theme.fontSize, 0, 0f, order + 1)
        if (showText) {
            val label = try { String.format(textFormat, normalized * 100f) } catch (_: Exception) { "${(normalized * 100).toInt()}%" }
            list.text(rect.x, rect.y, rect.w, rect.h, label, theme.textColor, theme.fontSize * theme.textScale, UiTextAlign.CENTER, 1, theme.font, false, order + 2)
        }
    }
}

/** Checkbox with a label and change signal. */
class UiCheckbox : UiWidget() {
    override val type = "UiCheckbox"
    override var text: String = "Option"
    var checked = false
    var onChangeSignal = "changed"
    var boxSize = 28f

    fun toggleValue() {
        checked = !checked
        go.emit(onChangeSignal, checked)
        go.emit("uiValueChanged", checked)
    }

    override fun props() = super.props() + listOf(
        Prop.S("Label", { text }, { text = it }),
        Prop.B("Checked", { checked }, { checked = it }),
        Prop.S("Change Signal", { onChangeSignal }, { onChangeSignal = it }),
        Prop.F("Box Size", { boxSize }, { boxSize = it.coerceAtLeast(8f) }, 2f, 8f, 120f)
    )

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val size = boxSize
        val y = rect.centerY - size * 0.5f
        val fill = if (checked) theme.accentColor else theme.widgetColor
        list.rect(rect.x, y, size, size, Colors.withAlpha(fill, opacity), theme.cornerRadius * theme.fontSize * 0.6f, theme.checkboxBorder, 1.5f, order)
        if (checked) {
            // simple check mark built from two rotated bars
            val cx = rect.x + size * 0.5f
            val cy = rect.centerY
            val t = size * 0.14f
            list.rect(cx - size * 0.22f, cy - t * 0.5f, size * 0.32f, t, theme.accentTextColor, t * 0.5f, 0, 0f, order + 1)
            list.rect(cx - size * 0.02f, cy - t * 0.5f, size * 0.34f, t, theme.accentTextColor, t * 0.5f, 0, 0f, order + 1)
        }
        if (focused) list.rect(rect.x - 2f, rect.y - 2f, rect.w + 4f, rect.h + 4f, 0, theme.cornerRadius * theme.fontSize, theme.accentColor, 1.5f, order + 2)
        list.text(rect.x + size + 10f, rect.y, rect.w - size - 10f, rect.h, text, theme.textColor, theme.fontSize * theme.textScale, UiTextAlign.LEFT, 1, theme.font, false, order + 1)
    }

    override fun onKey(event: UiKeyEvent, system: UiSystem): Boolean {
        if (event.action == UiKeyEvent.ACTION_DOWN && (event.code == "ENTER" || event.code == "SPACE")) {
            toggleValue(); return true
        }
        return false
    }

    override fun accessibilityText() = accessibilityLabel.ifBlank { "Checkbox $text ${if (checked) "checked" else "unchecked"}" }
}

/** Single-line text field. Text is typed with the Android soft keyboard or a physical keyboard. */
class UiTextField : UiWidget() {
    override val type = "UiTextField"
    override var text: String = ""
    var placeholder = "Enter text..."
    var maxLength = 64
    var numeric = false
    var password = false
    var onSubmitSignal = "submitted"
    var onChangeSignal = "changed"
    var caretBlink = true
    var caretIndex = 0
    private var blinkTimer = 0f

    fun appendChar(c: Char) {
        if (text.length >= maxLength) return
        if (numeric && !c.isDigit() && c != '.' && c != '-') return
        val i = caretIndex.coerceIn(0, text.length)
        text = text.substring(0, i) + c + text.substring(i)
        caretIndex = i + 1
        go.emit(onChangeSignal, text)
    }

    fun backspace() {
        if (caretIndex <= 0 || text.isEmpty()) return
        val i = caretIndex.coerceIn(0, text.length)
        text = text.substring(0, i - 1) + text.substring(i)
        caretIndex = i - 1
        go.emit(onChangeSignal, text)
    }

    fun submit() {
        go.emit(onSubmitSignal, text)
        focused = false
    }

    override fun props() = super.props() + listOf(
        Prop.S("Text", { text }, { text = it }),
        Prop.S("Placeholder", { placeholder }, { placeholder = it }),
        Prop.I("Max Length", { maxLength }, { maxLength = it.coerceIn(1, 4096) }, 1, 1, 4096),
        Prop.B("Numeric", { numeric }, { numeric = it }),
        Prop.B("Password", { password }, { password = it }),
        Prop.S("Submit Signal", { onSubmitSignal }, { onSubmitSignal = it }),
        Prop.S("Change Signal", { onChangeSignal }, { onChangeSignal = it })
    )

    fun update(dt: Float) {
        blinkTimer += dt
    }

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val bg = if (!isInteractable) theme.widgetColorDisabled else if (focused) theme.widgetColorHover else theme.widgetColor
        list.rect(rect.x, rect.y, rect.w, rect.h, Colors.withAlpha(bg, opacity), theme.cornerRadius * theme.fontSize, if (focused) theme.accentColor else theme.panelBorder, if (focused) 2f else 1f, order)
        val size = theme.fontSize * theme.textScale
        val display = if (password) "*".repeat(text.length) else text
        val showPlaceholder = display.isEmpty()
        list.text(
            rect.x + padding, rect.y, rect.w - padding * 2f - 4f, rect.h,
            if (showPlaceholder) placeholder else display,
            if (showPlaceholder) theme.textColorDisabled else theme.textColor,
            size, UiTextAlign.LEFT, 1, theme.font, false, order + 1
        )
        if (focused && caretBlink && (blinkTimer % 1.0f) < 0.55f) {
            val caretX = rect.x + padding + UiText.measure(display.substring(0, caretIndex.coerceIn(0, display.length)), size)
            list.rect(caretX, rect.y + rect.h * 0.2f, 2f, rect.h * 0.6f, theme.accentColor, 0f, 0, 0f, order + 2)
        }
    }

    override fun onKey(event: UiKeyEvent, system: UiSystem): Boolean {
        if (event.action != UiKeyEvent.ACTION_DOWN) return false
        return when (event.code) {
            "BACKSPACE" -> { backspace(); true }
            "ENTER" -> { submit(); true }
            "LEFT" -> { caretIndex = (caretIndex - 1).coerceAtLeast(0); true }
            "RIGHT" -> { caretIndex = (caretIndex + 1).coerceAtMost(text.length); true }
            "HOME" -> { caretIndex = 0; true }
            "END" -> { caretIndex = text.length; true }
            else -> {
                val c = event.char
                if (c != null) { appendChar(c); true } else false
            }
        }
    }
}

/** Scroll view: clips its children, supports dragging, inertia, scroll wheel and a scrollbar. */
class UiScrollView : UiWidget() {
    override val type = "UiScrollView"
    /** 0 = Vertical, 1 = Horizontal, 2 = Both. */
    var direction = 0
    var inertia = 0.92f
    var scrollSpeed = 1f
    var showScrollbar = true
    var scrollbarWidth = 6f
    var contentHeight = 0f
    var contentWidth = 0f
    var velocity = 0f
    var startDragY = 0f
    var dragging = false

    val maxScroll: Float
        get() = maxOf(0f, (if (direction == 0) contentHeight else contentWidth) - rect.h + padding * 2f)

    fun scrollBy(delta: Float) {
        scrollOffset = (scrollOffset + delta).coerceIn(0f, maxScroll)
    }

    override fun resetRuntime() {
        scrollOffset = 0f
        velocity = 0f
    }

    override fun props() = super.props() + listOf(
        Prop.Choice("Direction", listOf("Vertical", "Horizontal", "Both"), { direction }, { direction = it }),
        Prop.F("Inertia", { inertia }, { inertia = it.coerceIn(0f, 1f) }, 0.02f, 0f, 1f),
        Prop.F("Scroll Speed", { scrollSpeed }, { scrollSpeed = it.coerceAtLeast(0.05f) }, 0.05f, 0.05f, 10f),
        Prop.B("Show Scrollbar", { showScrollbar }, { showScrollbar = it }),
        Prop.F("Content Height", { contentHeight }, { contentHeight = it.coerceAtLeast(0f) }, 5f, 0f, 100000f),
        Prop.F("Content Width", { contentWidth }, { contentWidth = it.coerceAtLeast(0f) }, 5f, 0f, 100000f)
    )

    fun update(dt: Float) {
        if (!dragging && abs(velocity) > 0.01f) {
            scrollOffset = (scrollOffset + velocity * dt).coerceIn(0f, maxScroll)
            velocity *= inertia
            if (scrollOffset <= 0f || scrollOffset >= maxScroll) velocity = 0f
        }
    }

    override fun draw(list: UiDrawList, theme: UiTheme) {
        list.rect(rect.x, rect.y, rect.w, rect.h, Colors.withAlpha(theme.widgetColor, opacity * 0.35f), theme.cornerRadius * theme.fontSize, theme.panelBorder, 1f, order)
        list.clip(rect.x, rect.y, rect.w, rect.h)
    }

    /** Called after the children are drawn to end the clip and draw the scrollbar. */
    fun drawEnd(list: UiDrawList, theme: UiTheme) {
        list.clipEnd()
        if (!showScrollbar) return
        val max = maxScroll
        if (max <= 0.5f) return
        val trackH = rect.h - padding
        val thumbH = maxOf(24f, trackH * (rect.h / (contentHeight + padding * 2f).coerceAtLeast(1f)))
        val t = scrollOffset / max
        val y = rect.y + padding * 0.5f + t * (trackH - thumbH)
        list.rect(rect.right - scrollbarWidth - 2f, y, scrollbarWidth, thumbH, theme.scrollbar, scrollbarWidth * 0.5f, 0, 0f, order + 999)
    }

    override fun onPointer(event: UiPointerEvent, system: UiSystem): Boolean {
        when (event.action) {
            UiPointerEvent.ACTION_DOWN -> {
                dragging = true
                startDragY = if (direction == 0) event.y else event.x
                velocity = 0f
                return true
            }
            UiPointerEvent.ACTION_MOVE -> {
                if (dragging) {
                    val cur = if (direction == 0) event.y else event.x
                    val delta = startDragY - cur
                    scrollBy(delta * scrollSpeed)
                    velocity = delta / maxOf(0.016f, system.dt) * scrollSpeed * 0.35f
                    startDragY = cur
                    return true
                }
            }
            UiPointerEvent.ACTION_UP -> { dragging = false; return true }
        }
        return false
    }

    override fun onKey(event: UiKeyEvent, system: UiSystem): Boolean {
        if (event.action != UiKeyEvent.ACTION_DOWN) return false
        return when (event.code) {
            "UP" -> { scrollBy(-40f * scrollSpeed); true }
            "DOWN" -> { scrollBy(40f * scrollSpeed); true }
            "PAGE_UP" -> { scrollBy(-rect.h * 0.9f); true }
            "PAGE_DOWN" -> { scrollBy(rect.h * 0.9f); true }
            "HOME" -> { scrollBy(-100000f); true }
            "END" -> { scrollBy(100000f); true }
            else -> false
        }
    }
}

/** Tab bar: children with a matching [UiWidget.tabIndex] become visible per tab. */
class UiTabs : UiWidget() {
    override val type = "UiTabs"
    var tabs = "Tab 1,Tab 2"
    var selected = 0
    var tabHeight = 44f
    var onChangeSignal = "tabChanged"

    fun tabList(): List<String> = tabs.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun select(index: Int, system: UiSystem? = null) {
        val n = tabList().size
        if (n == 0) return
        val i = index.coerceIn(0, n - 1)
        if (i == selected) return
        selected = i
        go.emit(onChangeSignal, i)
        system?.onTabChanged(this)
    }

    override fun props() = super.props() + listOf(
        Prop.S("Tabs", { tabs }, { tabs = it }, tooltip = "Comma separated tab names"),
        Prop.I("Selected", { selected }, { selected = it.coerceAtLeast(0) }, 1, 0, 64),
        Prop.F("Tab Height", { tabHeight }, { tabHeight = it.coerceAtLeast(16f) }, 2f, 16f, 200f),
        Prop.S("Change Signal", { onChangeSignal }, { onChangeSignal = it })
    )

    override fun layoutChildren(children: List<UiWidget>, theme: UiTheme) {
        val content = UiRect().setRect(rect.x + padding, rect.y + tabHeight + spacing, (rect.w - padding * 2f).coerceAtLeast(0f), (rect.h - tabHeight - spacing - padding).coerceAtLeast(0f))
        children.forEach { it.layoutSelf(content, theme) }
    }

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val names = tabList()
        if (names.isEmpty()) return
        val tabW = rect.w / names.size
        for ((i, name) in names.withIndex()) {
            val x = rect.x + i * tabW
            val active = i == selected
            val bg = if (active) theme.accentColor else theme.widgetColor
            list.rect(x + 1f, rect.y, tabW - 2f, tabHeight, Colors.withAlpha(bg, opacity), theme.cornerRadius * theme.fontSize, theme.panelBorder, 1f, order)
            list.text(x, rect.y, tabW, tabHeight, name, if (active) theme.accentTextColor else theme.textColor, theme.fontSize * theme.textScale * 0.92f, UiTextAlign.CENTER, 1, theme.font, false, order + 1)
        }
    }

    override fun onPointer(event: UiPointerEvent, system: UiSystem): Boolean {
        if (event.action != UiPointerEvent.ACTION_UP) return false
        val names = tabList()
        if (names.isEmpty()) return false
        val tabW = rect.w / names.size
        val i = ((event.x - rect.x) / tabW).toInt()
        if (i in names.indices) { select(i, system); return true }
        return false
    }
}

/**
 * List widget: data driven (newline separated items) with selection, scrolling and signals.
 * It can also host child widgets as custom row templates.
 */
class UiList : UiWidget() {
    override val type = "UiList"
    var items = "Item 1\nItem 2\nItem 3"
    var itemHeight = 36f
    var selectedIndex = -1
    var onSelectSignal = "selected"
    var showIndices = false
    var scrollOffsetInternal = 0f
    var dragStart = 0f
    var dragging = false

    fun itemList(): List<String> = items.split('\n').filter { it.isNotEmpty() }

    fun select(index: Int) {
        if (index < 0 || index >= itemList().size) return
        selectedIndex = index
        go.emit(onSelectSignal, index)
        go.emit("uiValueChanged", index)
    }

    override fun resetRuntime() { scrollOffsetInternal = 0f }

    override fun props() = super.props() + listOf(
        Prop.S("Items", { items }, { items = it }, multiline = true),
        Prop.F("Item Height", { itemHeight }, { itemHeight = it.coerceAtLeast(8f) }, 2f, 8f, 400f),
        Prop.I("Selected", { selectedIndex }, { selectedIndex = it }, 1),
        Prop.S("Select Signal", { onSelectSignal }, { onSelectSignal = it }),
        Prop.B("Show Indices", { showIndices }, { showIndices = it })
    )

    private val visibleHeight get() = ((rect.h / itemHeight).toInt() + 1).coerceAtLeast(1)
    private val maxScrollI get() = maxOf(0f, itemList().size * itemHeight - rect.h)

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val all = itemList()
        list.rect(rect.x, rect.y, rect.w, rect.h, Colors.withAlpha(theme.widgetColor, opacity * 0.5f), theme.cornerRadius * theme.fontSize, theme.panelBorder, 1f, order)
        list.clip(rect.x, rect.y, rect.w, rect.h)
        val first = (scrollOffsetInternal / itemHeight).toInt().coerceAtLeast(0)
        val last = (first + visibleHeight).coerceAtMost(all.size)
        for (i in first until last) {
            val y = rect.y + i * itemHeight - scrollOffsetInternal
            val selected = i == selectedIndex
            if (selected) {
                list.rect(rect.x + 2f, y + 1f, rect.w - 4f, itemHeight - 2f, Colors.withAlpha(theme.widgetColorSelected, opacity * 0.55f), theme.cornerRadius * theme.fontSize * 0.6f, 0, 0f, order + 1)
            }
            val label = if (showIndices) "$i. ${all[i]}" else all[i]
            list.text(rect.x + padding, y, rect.w - padding * 2f, itemHeight, label, theme.textColorFor(if (selected) UiState.SELECTED else UiState.NORMAL), theme.fontSize * theme.textScale, UiTextAlign.LEFT, 1, theme.font, false, order + 2)
        }
    }

    fun drawEnd(list: UiDrawList, theme: UiTheme) {
        list.clipEnd()
        val max = maxScrollI
        if (max <= 0.5f) return
        val thumbH = maxOf(20f, rect.h * (rect.h / (itemList().size * itemHeight).coerceAtLeast(1f)))
        val t = scrollOffsetInternal / max
        list.rect(rect.right - 6f, rect.y + t * (rect.h - thumbH), 4f, thumbH, theme.scrollbar, 2f, 0, 0f, order + 999)
    }

    override fun onPointer(event: UiPointerEvent, system: UiSystem): Boolean {
        when (event.action) {
            UiPointerEvent.ACTION_DOWN -> { dragging = true; dragStart = event.y; return true }
            UiPointerEvent.ACTION_MOVE -> if (dragging) {
                val delta = dragStart - event.y
                scrollOffsetInternal = (scrollOffsetInternal + delta).coerceIn(0f, maxScrollI)
                dragStart = event.y
                return true
            }
            UiPointerEvent.ACTION_UP -> {
                dragging = false
                val index = ((event.y - rect.y + scrollOffsetInternal) / itemHeight).toInt()
                if (index in itemList().indices) select(index)
                return true
            }
        }
        return false
    }

    override fun onKey(event: UiKeyEvent, system: UiSystem): Boolean {
        if (event.action != UiKeyEvent.ACTION_DOWN) return false
        val count = itemList().size
        return when (event.code) {
            "UP" -> { select((selectedIndex - 1).coerceAtLeast(0)); ensureVisible(); true }
            "DOWN" -> { select((selectedIndex + 1).coerceAtMost(count - 1)); ensureVisible(); true }
            "ENTER" -> { if (selectedIndex >= 0) go.emit(onSelectSignal, selectedIndex); true }
            else -> false
        }
    }

    private fun ensureVisible() {
        val top = selectedIndex * itemHeight
        if (top < scrollOffsetInternal) scrollOffsetInternal = top
        if (top + itemHeight > scrollOffsetInternal + rect.h) scrollOffsetInternal = top + itemHeight - rect.h
    }

    override fun accessibilityText() = accessibilityLabel.ifBlank { "List with ${itemList().size} items" }
}

/** Tooltip: attached to a widget, it shows floating text after hovering for [delay] seconds. */
class UiTooltip : Component() {
    override val type = "UiTooltip"
    var text: String = ""
    var delay = 0.45f
    var fontSize = 14f
    /** Hide the tooltip after this many seconds (0 = keep visible while hovering). */
    var hideAfter = 0f

    var timer = 0f
    var visible = false

    override fun props() = listOf(
        Prop.S("Text", { text }, { text = it }, multiline = true),
        Prop.F("Delay", { delay }, { delay = it.coerceIn(0f, 5f) }, 0.05f, 0f, 5f),
        Prop.F("Font Size", { fontSize }, { fontSize = it.coerceAtLeast(6f) }, 1f, 6f, 100f),
        Prop.F("Hide After", { hideAfter }, { hideAfter = it.coerceAtLeast(0f) }, 0.1f, 0f, 30f)
    )

    override fun resetRuntime() { timer = 0f; visible = false }
}

/** Icon-only button used by toolbars and HUDs. */
class UiIconButton : UiWidget() {
    override val type = "UiIconButton"
    var icon = ""
    var onClickSignal = "clicked"
    var tint = Colors.WHITE
    var iconScale = 0.72f

    override fun props() = super.props() + listOf(
        Prop.Asset("Icon", AssetKind.TEXTURE, { icon }, { icon = it }),
        Prop.S("Click Signal", { onClickSignal }, { onClickSignal = it }),
        Prop.Color("Tint", { tint }, { tint = it }),
        Prop.F("Icon Scale", { iconScale }, { iconScale = it.coerceIn(0.1f, 1f) }, 0.05f, 0.1f, 1f)
    )

    override fun draw(list: UiDrawList, theme: UiTheme) {
        val effective = if (!isInteractable) UiState.DISABLED else state
        list.rect(rect.x, rect.y, rect.w, rect.h, Colors.withAlpha(theme.colorFor(effective), opacity), theme.cornerRadius * theme.fontSize, theme.panelBorder, 1f, order)
        val size = minOf(rect.w, rect.h) * iconScale
        if (icon.isNotBlank()) {
            list.sprite(rect.centerX - size * 0.5f, rect.centerY - size * 0.5f, size, size, icon, Colors.withAlpha(tint, opacity), order + 1)
        } else if (text.isNotBlank()) {
            list.text(rect.x, rect.y, rect.w, rect.h, text, theme.textColorFor(effective), theme.fontSize * theme.textScale, UiTextAlign.CENTER, 1, theme.font, false, order + 1)
        }
    }

    fun click(system: UiSystem? = null) {
        if (!isInteractable) return
        go.emit(onClickSignal)
        system?.playClick(go, "")
    }
}

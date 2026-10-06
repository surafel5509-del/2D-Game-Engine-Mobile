package com.sengine.engine.ui

import com.sengine.engine.math.Colors
import com.sengine.engine.math.M
import com.sengine.engine.math.Rect2

/** Anchor presets for responsive UI layout (Unity/Godot style). */
enum class UiAnchor(val label: String) {
    TOP_LEFT("Top Left"), TOP_CENTER("Top"), TOP_RIGHT("Top Right"),
    MIDDLE_LEFT("Left"), MIDDLE_CENTER("Center"), MIDDLE_RIGHT("Right"),
    BOTTOM_LEFT("Bottom Left"), BOTTOM_CENTER("Bottom"), BOTTOM_RIGHT("Bottom Right"),
    STRETCH_H("Stretch H"), STRETCH_V("Stretch V"), STRETCH("Stretch Both");

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { MIDDLE_CENTER }
        val labels = entries.map { it.label }
    }
}

/** Child layout modes used by panels, scroll views and lists. */
enum class UiLayout(val label: String) {
    FREE("Free"), HORIZONTAL("Horizontal"), VERTICAL("Vertical"), GRID("Grid");

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { FREE }
        val labels = entries.map { it.label }
    }
}

/** Interaction state of a widget (drives the visual look). */
enum class UiState { NORMAL, HOVER, PRESSED, SELECTED, DISABLED, FOCUSED }

/** Text alignment inside a widget. */
enum class UiTextAlign(val label: String) {
    LEFT("Left"), CENTER("Center"), RIGHT("Right");

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { CENTER }
        val labels = entries.map { it.label }
    }
}

/** Theme: one place to style the whole game UI (and the editor's runtime UI preview). */
class UiTheme {
    var name = "Dark"
    var font = ""
    var fontSize = 18f
    var titleFontSize = 26f

    var panelColor = 0xE61A1F27.toInt()
    var panelBorder = 0xFF2E3644.toInt()
    var widgetColor = 0xFF232A34.toInt()
    var widgetColorHover = 0xFF2C3644.toInt()
    var widgetColorPressed = 0xFF1B2129.toInt()
    var widgetColorSelected = 0xFF2F6FEB.toInt()
    var widgetColorDisabled = 0xFF1A1E25.toInt()
    var textColor = 0xFFE7ECF3.toInt()
    var textColorDim = 0xFF97A3B4.toInt()
    var textColorDisabled = 0xFF5A6472.toInt()
    var accentColor = 0xFF4C8DFF.toInt()
    var accentTextColor = 0xFFFFFFFF.toInt()
    var dangerColor = 0xFFFF5A5F.toInt()
    var successColor = 0xFF3DD68C.toInt()
    var sliderTrack = 0xFF161A21.toInt()
    var sliderFill = 0xFF4C8DFF.toInt()
    var checkboxBorder = 0xFF3A4352.toInt()
    var scrollbar = 0x66FFFFFF
    var tooltipColor = 0xF01D2330.toInt()
    var shadowColor = 0x66000000
    var cornerRadius = 0.12f
    var padding = 0.18f
    var spacing = 0.12f
    /** Accessibility: text scale multiplier (players can enlarge text). */
    var textScale = 1f

    fun colorFor(state: UiState): Int = when (state) {
        UiState.NORMAL -> widgetColor
        UiState.HOVER -> widgetColorHover
        UiState.PRESSED -> widgetColorPressed
        UiState.SELECTED -> widgetColorSelected
        UiState.DISABLED -> widgetColorDisabled
        UiState.FOCUSED -> widgetColorHover
    }

    fun textColorFor(state: UiState): Int = when (state) {
        UiState.DISABLED -> textColorDisabled
        UiState.SELECTED -> accentTextColor
        else -> textColor
    }

    /** Scales every size for accessibility (big-text mode). */
    fun withTextScale(scale: Float): UiTheme {
        textScale = scale.coerceIn(0.6f, 2.5f)
        return this
    }

    companion object {
        val presets: LinkedHashMap<String, UiTheme> = linkedMapOf(
            "Dark" to UiTheme(),
            "Light" to UiTheme().also {
                it.name = "Light"
                it.panelColor = 0xF0F2F4F8.toInt()
                it.panelBorder = 0xFFC9D2DE.toInt()
                it.widgetColor = 0xFFE4E9F0.toInt()
                it.widgetColorHover = 0xFFD8DFE9.toInt()
                it.widgetColorPressed = 0xFFC7D0DC.toInt()
                it.widgetColorDisabled = 0xFFEDF0F4.toInt()
                it.textColor = 0xFF1C222B.toInt()
                it.textColorDim = 0xFF5A6472.toInt()
                it.textColorDisabled = 0xFF98A2B0.toInt()
                it.sliderTrack = 0xFFCBD3DE.toInt()
            },
            "Neon" to UiTheme().also {
                it.name = "Neon"
                it.panelColor = 0xE6110B20.toInt()
                it.panelBorder = 0xFF5B2CFF.toInt()
                it.widgetColor = 0xFF1B1030.toInt()
                it.widgetColorHover = 0xFF2A1850.toInt()
                it.widgetColorPressed = 0xFF120A22.toInt()
                it.accentColor = 0xFF00E5FF.toInt()
                it.textColor = 0xFFEAE6FF.toInt()
                it.sliderFill = 0xFFFF2ED0.toInt()
            },
            "Pixel" to UiTheme().also {
                it.name = "Pixel"
                it.cornerRadius = 0f
                it.panelColor = 0xFF101820.toInt()
                it.widgetColor = 0xFF203040.toInt()
                it.widgetColorHover = 0xFF2C4358.toInt()
                it.accentColor = 0xFFFFC400.toInt()
                it.textColor = 0xFFFFF3C4.toInt()
                it.fontSize = 20f
            }
        )
    }
}

/** A single UI draw command produced by the layout pass and consumed by the renderer. */
sealed class UiCommand {
    class Rect(
        var x: Float, var y: Float, var w: Float, var h: Float,
        var color: Int, var radius: Float = 0f,
        var borderColor: Int = 0, var borderWidth: Float = 0f,
        var order: Int = 0
    ) : UiCommand()

    class Text(
        var x: Float, var y: Float, var w: Float, var h: Float,
        var text: String, var color: Int, var size: Float,
        var align: UiTextAlign = UiTextAlign.CENTER,
        /** 0 = top, 1 = middle, 2 = bottom. */
        var verticalAlign: Int = 1,
        var font: String = "", var wrap: Boolean = true, var order: Int = 0, var bold: Boolean = false
    ) : UiCommand()

    class Sprite(
        var x: Float, var y: Float, var w: Float, var h: Float,
        var texture: String, var u0: Float, var v0: Float, var u1: Float, var v1: Float,
        var tint: Int = Colors.WHITE, var order: Int = 0
    ) : UiCommand()

    class Clip(var x: Float, var y: Float, var w: Float, var h: Float) : UiCommand()
    object ClipEnd : UiCommand()

    /** Rounded/polygon free drawing for custom controls. */
    class Polygon(var points: FloatArray, var color: Int, var order: Int = 0) : UiCommand()
}

/** Draw list: widgets push commands, the renderer draws them in order. */
class UiDrawList {
    val commands = ArrayList<UiCommand>(128)
    private val pool = ArrayList<UiCommand>(128)
    private var used = 0

    fun begin() {
        used = 0
        commands.clear()
    }

    private fun <T : UiCommand> obtain(factory: () -> T): T {
        val c = factory()
        pool.add(c)
        return c
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 0f, borderColor: Int = 0, borderWidth: Float = 0f, order: Int = 0) {
        if (w <= 0f || h <= 0f) return
        commands.add(obtain { UiCommand.Rect(x, y, w, h, color, radius, borderColor, borderWidth, order) }.also {
            it.x = x; it.y = y; it.w = w; it.h = h; it.color = color
            it.radius = radius; it.borderColor = borderColor; it.borderWidth = borderWidth; it.order = order
        })
    }

    fun text(
        x: Float, y: Float, w: Float, h: Float, text: String, color: Int, size: Float,
        align: UiTextAlign = UiTextAlign.CENTER, valign: Int = 1,
        font: String = "", wrap: Boolean = true, order: Int = 0, bold: Boolean = false
    ) {
        if (text.isEmpty()) return
        commands.add(obtain { UiCommand.Text(x, y, w, h, text, color, size, align, valign, font, wrap, order, bold) }.also {
            it.x = x; it.y = y; it.w = w; it.h = h; it.text = text; it.color = color; it.size = size
            it.align = align; it.verticalAlign = valign; it.font = font; it.wrap = wrap; it.order = order; it.bold = bold
        })
    }

    fun sprite(x: Float, y: Float, w: Float, h: Float, texture: String, tint: Int = Colors.WHITE, order: Int = 0) {
        if (texture.isEmpty()) return
        commands.add(obtain { UiCommand.Sprite(x, y, w, h, texture, 0f, 1f, 1f, 0f, tint, order) }.also {
            it.x = x; it.y = y; it.w = w; it.h = h; it.texture = texture; it.tint = tint
            it.u0 = 0f; it.v0 = 1f; it.u1 = 1f; it.v1 = 0f; it.order = order
        })
    }

    fun clip(x: Float, y: Float, w: Float, h: Float) {
        commands.add(obtain { UiCommand.Clip(x, y, w, h) }.also { it.x = x; it.y = y; it.w = w; it.h = h })
    }

    fun clipEnd() {
        commands.add(UiCommand.ClipEnd)
    }

    fun polygon(points: FloatArray, color: Int, order: Int = 0) {
        commands.add(obtain { UiCommand.Polygon(points, color, order) }.also { it.points = points; it.color = color; it.order = order })
    }

    val size get() = commands.size
}

/** Computed rectangle of a widget in UI units. */
class UiRect(var x: Float = 0f, var y: Float = 0f, var w: Float = 0f, var h: Float = 0f) {
    fun setRect(nx: Float, ny: Float, nw: Float, nh: Float): UiRect { x = nx; y = ny; w = nw; h = nh; return this }
    fun set(o: UiRect): UiRect = setRect(o.x, o.y, o.w, o.h)
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
    val right get() = x + w
    val bottom get() = y + h
    val centerX get() = x + w * 0.5f
    val centerY get() = y + h * 0.5f
    fun toRect2() = Rect2(x, y, w, h)
    fun inflate(pad: Float) { x -= pad; y -= pad; w += pad * 2; h += pad * 2 }
}

/** Maps a UiAnchor to normalised (0..1) anchor/pivot values. */
object UiAnchors {
    fun anchorX(a: UiAnchor): Float = when (a) {
        UiAnchor.TOP_LEFT, UiAnchor.MIDDLE_LEFT, UiAnchor.BOTTOM_LEFT -> 0f
        UiAnchor.TOP_CENTER, UiAnchor.MIDDLE_CENTER, UiAnchor.BOTTOM_CENTER, UiAnchor.STRETCH_H, UiAnchor.STRETCH_V, UiAnchor.STRETCH -> 0.5f
        else -> 1f
    }

    fun anchorY(a: UiAnchor): Float = when (a) {
        UiAnchor.TOP_LEFT, UiAnchor.TOP_CENTER, UiAnchor.TOP_RIGHT -> 0f
        UiAnchor.MIDDLE_LEFT, UiAnchor.MIDDLE_CENTER, UiAnchor.MIDDLE_RIGHT, UiAnchor.STRETCH_H -> 0.5f
        else -> 1f
    }

    fun stretchesWide(a: UiAnchor) = a == UiAnchor.STRETCH_H || a == UiAnchor.STRETCH
    fun stretchesTall(a: UiAnchor) = a == UiAnchor.STRETCH_V || a == UiAnchor.STRETCH
}

/** Text measuring helper used by the layout (approximate but consistent with the bitmap font). */
object UiText {
    /** Rough advance width of one character at [size] (0.62 is the engine font's average). */
    const val AVG_ADVANCE = 0.58f

    fun measure(text: String, size: Float): Float {
        var maxLine = 0f
        var current = 0
        for (c in text) {
            if (c == '\n') {
                maxLine = maxOf(maxLine, current.toFloat())
                current = 0
            } else current++
        }
        maxLine = maxOf(maxLine, current.toFloat())
        return maxLine * size * AVG_ADVANCE
    }

    fun lineCount(text: String, wrapWidth: Float, size: Float): Int {
        if (wrapWidth <= 0f) return text.count { it == '\n' } + 1
        var lines = 1
        var lineWidth = 0f
        for (word in text.split(' ', '\n')) {
            val w = measure(word, size)
            if (lineWidth + w > wrapWidth && lineWidth > 0f) {
                lines++
                lineWidth = w + size * AVG_ADVANCE
            } else {
                lineWidth += w + size * AVG_ADVANCE
            }
        }
        return lines
    }

    fun measureHeight(text: String, wrapWidth: Float, size: Float, lineSpacing: Float = 1.15f): Float =
        lineCount(text, wrapWidth, size) * size * lineSpacing
}

package com.sengine.engine.ui

import com.sengine.engine.core.Component
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prop
import com.sengine.engine.core.Scene
import com.sengine.engine.math.Colors
import com.sengine.engine.math.M
import kotlin.math.abs

/** Pointer event delivered to widgets. */
class UiPointerEvent(var action: Int, var x: Float, var y: Float, var pointerId: Int = 0) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_MOVE = 1
        const val ACTION_UP = 2
        const val ACTION_SCROLL = 3
    }
}

/** Keyboard event delivered to the focused widget. */
class UiKeyEvent(var code: String, var action: Int, var char: Char? = null) {
    companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
    }
}

/** Per-frame input snapshot handed to the UI system by the engine. */
class UiInputState {
    var pointerX = -1f
    var pointerY = -1f
    var pointerDown = false
    var pointerJustDown = false
    var pointerJustUp = false
    var scrollDelta = 0f
    var screenWidth = 1280f
    var screenHeight = 720f
    val keysDown = HashSet<String>()
    val keysJustPressed = HashSet<String>()
    val textInput = StringBuilder()
    /** True when a widget consumed the pointer (stops game input). */
    var consumedPointer = false

    fun clearFrame() {
        keysJustPressed.clear()
        textInput.setLength(0)
        pointerJustDown = false
        pointerJustUp = false
        scrollDelta = 0f
        consumedPointer = false
    }
}

/**
 * The UI runtime: lays out every visible [UiCanvas], routes input to the widgets under the
 * pointer, manages focus/keyboard navigation, tooltips, state animations and produces the draw
 * lists consumed by the renderer. Everything here is testable without OpenGL.
 */
class UiSystem {

    /** Collected widget rects per canvas (used by tests and the UI editor overlay). */
    val drawLists = LinkedHashMap<GameObject, UiDrawList>()
    val input = UiInputState()

    var dt = 0f
        private set
    var activeCanvas: GameObject? = null
        private set
    var hoveredWidget: UiWidget? = null
        private set
    var focusedWidget: UiWidget? = null
        private set
    var lastClickWidget: String = ""
        private set

    /** Statistics for the profiler. */
    var widgetCount = 0
        private set
    var interactableCount = 0
        private set

    /** Callback used to play UI sounds (wired to the audio system by the engine). */
    var playSound: ((String) -> Unit)? = null

    private var pressedWidget: UiWidget? = null
    private var tooltipWidget: UiWidget? = null
    private var tooltipTimer = 0f
    private val pointerEvent = UiPointerEvent(UiPointerEvent.ACTION_MOVE, 0f, 0f)
    private var lastPointerX = -1f
    private var lastPointerY = -1f
    private var lastPointerDown = false
    private val visibleWidgets = ArrayList<UiWidget>()

    fun playClick(go: GameObject, sound: String) {
        if (sound.isNotBlank()) playSound?.invoke(sound)
        go.emit("clicked")
    }

    /** Tab change hook: hides/shows widgets that belong to a tab page. */
    fun onTabChanged(tabs: UiTabs) {
        tabs.go.emit("tabChanged", tabs.selected)
    }

    /** Runs the layout + interaction pass for the scene. */
    fun update(scene: Scene, dt: Float, paused: Boolean = false) {
        this.dt = dt
        widgetCount = 0
        interactableCount = 0
        drawLists.clear()
        hoveredWidget = null
        activeCanvas = null

        val canvases = scene.objects.filter { it.isActiveInHierarchy() && it.get<UiCanvas>() != null }
        if (canvases.isEmpty()) {
            if (input.pointerJustUp) pressedWidget = null
            return
        }

        // scale each canvas to the screen
        for (canvasGo in canvases) {
            val canvas = canvasGo.get<UiCanvas>()!!
            canvas.computeScale(input.screenWidth, input.screenHeight)
        }
        val main = canvases.first()
        activeCanvas = main

        // scale the pointer into UI units of the main canvas
        val canvasComp = main.get<UiCanvas>()!!
        val scale = canvasComp.scale.coerceAtLeast(0.0001f)
        val uiX = (input.pointerX - (input.screenWidth - canvasComp.referenceWidth * scale) * 0.5f) / scale
        val uiY = (input.pointerY - (input.screenHeight - canvasComp.referenceHeight * scale) * 0.5f) / scale

        // ---------------- gather + layout
        val lists = LinkedHashMap<GameObject, UiDrawList>()
        for (canvasGo in canvases.sortedBy { it.order }) {
            val list = UiDrawList()
            list.begin()
            lists[canvasGo] = list
            val canvas = canvasGo.get<UiCanvas>()!!
            layoutCanvas(scene, canvasGo, canvas, list, paused)
        }
        drawLists.putAll(lists)

        // ---------------- input routing
        val top = visibleWidgets.lastOrNull { it.isInteractable }
        if (!paused) {
            if (input.pointerJustDown) {
                val hit = hitTest(uiX, uiY)
                if (hit != null) {
                    pressedWidget = hit
                    hit.pressed = true
                    if (hit is UiTextField) setFocus(hit)
                    input.consumedPointer = true
                } else {
                    // clicking outside a text field removes focus
                    if (focusedWidget is UiTextField) setFocus(null)
                }
            }
            val moveEvent = UiPointerEvent(UiPointerEvent.ACTION_MOVE, uiX, uiY)
            hoveredWidget = hitTest(uiX, uiY)
            if (hoveredWidget?.isInteractable == true) input.consumedPointer = true
            if (pressedWidget != null) {
                pressedWidget?.onPointer(moveEvent, this)
                input.consumedPointer = true
            } else if (hoveredWidget != null) {
                hoveredWidget?.onPointer(moveEvent, this)
            }
            if (input.pointerJustUp) {
                val pw = pressedWidget
                if (pw != null) {
                    val up = UiPointerEvent(UiPointerEvent.ACTION_UP, uiX, uiY)
                    pw.onPointer(up, this)
                    pw.pressed = false
                    if (pw.rect.contains(uiX, uiY)) activate(pw)
                    pressedWidget = null
                    input.consumedPointer = true
                }
            }
            if (abs(input.scrollDelta) > 0.001f) {
                val scrollTarget = hitTest(uiX, uiY) ?: hoveredWidget
                var node: UiWidget? = scrollTarget
                while (node != null) {
                    if (node is UiScrollView || node is UiList) {
                        if (node is UiScrollView) node.scrollBy(-input.scrollDelta * node.scrollSpeed)
                        input.consumedPointer = true
                        break
                    }
                    node = node.parentWidget
                }
            }
            // keyboard navigation
            if ("TAB" in input.keysJustPressed) focusNext(1)
            if ("TAB_BACK" in input.keysJustPressed) focusNext(-1)
            val target = focusedWidget
            if (target != null) {
                for (code in input.keysJustPressed) {
                    target.onKey(UiKeyEvent(code, UiKeyEvent.ACTION_DOWN), this)
                }
                if (input.textInput.isNotEmpty()) {
                    for (c in input.textInput) target.onKey(UiKeyEvent("CHAR", UiKeyEvent.ACTION_DOWN, c), this)
                }
            }
        }

        // ---------------- tooltips
        val tipOwner = hoveredWidget
        if (tipOwner != null) {
            val tooltip = tipOwner.go.getAny<UiTooltip>()
            if (tooltip != null && tooltip.text.isNotBlank()) {
                if (tooltipWidget !== tipOwner) {
                    tooltipWidget = tipOwner
                    tooltipTimer = 0f
                    tooltip.visible = false
                }
                tooltipTimer += dt
                tooltip.timer += dt
                if (tooltipTimer >= tooltip.delay) {
                    tooltip.visible = true
                    val list = lists[main]
                    if (list != null) drawTooltip(list, tipOwner, tooltip, canvasComp.theme)
                }
            } else {
                tooltipWidget = null
            }
        } else {
            tooltipWidget = null
        }
        input.consumedPointer = input.consumedPointer || (pressedWidget != null)
    }

    private fun layoutCanvas(scene: Scene, canvasGo: GameObject, canvas: UiCanvas, list: UiDrawList, paused: Boolean) {
        visibleWidgets.clear()
        val theme = canvas.theme
        canvas.layoutSelf(UiRect().setRect(0f, 0f, canvas.referenceWidth, canvas.referenceHeight), theme)
        collectAndLayout(scene, canvasGo, canvas, list, theme, null)
        canvas.draw(list, theme)
        // stable sort by draw order
        val indexMap = java.util.IdentityHashMap<UiCommand, Int>()
        list.commands.forEachIndexed { i, c -> indexMap[c] = i }
        list.commands.sortWith(compareBy({ orderOf(it) }, { indexMap[it] ?: 0 }))
        // scroll views/list close their clipping region after their content
        for (w in visibleWidgets) {
            if (w is UiScrollView && w.isVisibleInHierarchy) w.drawEnd(list, theme)
            if (w is UiList && w.isVisibleInHierarchy) w.drawEnd(list, theme)
        }
    }

    private fun orderOf(c: UiCommand): Int = when (c) {
        is UiCommand.Rect -> c.order
        is UiCommand.Text -> c.order
        is UiCommand.Sprite -> c.order
        is UiCommand.Polygon -> c.order
        is UiCommand.Clip -> -1
        is UiCommand.ClipEnd -> Int.MAX_VALUE
    }

    private fun collectAndLayout(scene: Scene, go: GameObject, widget: UiWidget, list: UiDrawList, theme: UiTheme, parentWidget: UiWidget?) {
        widget.parentWidget = parentWidget
        widgetCount++
        if (widget.isInteractable) interactableCount++
        val tabsParent = findTabsAncestor(go)
        val tabVisible = widget.tabIndex < 0 || tabsParent == null || tabsParent.selected == widget.tabIndex
        if (!widget.isVisibleInHierarchy || !tabVisible) return
        visibleWidgets.add(widget)
        val childWidgets = ArrayList<Pair<GameObject, UiWidget>>()
        for (child in scene.childrenOf(go)) {
            val w = child.getAny<UiWidget>() ?: continue
            if (!child.isActiveInHierarchy()) continue
            childWidgets.add(child to w)
        }
        val children = childWidgets.map { it.second }
        widget.layoutChildren(children, theme)
        widget.draw(list, theme)
        if (widget is UiScrollView) list.clip(widget.rect.x, widget.rect.y, widget.rect.w, widget.rect.h)
        for ((childGo, child) in childWidgets) {
            collectAndLayout(scene, childGo, child, list, theme, widget)
        }
        if (widget is UiScrollView) list.clipEnd()
    }

    private fun parentWidgetOf(go: GameObject): UiWidget? = null

    private fun findTabsAncestor(go: GameObject): UiTabs? {
        var p = go.parent
        while (p != null) {
            p.getAny<UiTabs>()?.let { return it }
            p = p.parent
        }
        return null
    }

    /** Draws a floating tooltip above (or below) its owner widget. */
    private fun drawTooltip(list: UiDrawList, owner: UiWidget, tooltip: UiTooltip, theme: UiTheme) {
        val size = tooltip.fontSize
        val textW = UiText.measure(tooltip.text, size) + 18f
        val textH = size * 1.7f
        var x = owner.rect.centerX - textW * 0.5f
        var y = owner.rect.y - textH - 6f
        if (y < 4f) y = owner.rect.bottom + 6f
        val maxX = maxOf(4f, (activeCanvas?.get<UiCanvas>()?.referenceWidth ?: 1280f) - textW - 4f)
        x = x.coerceIn(4f, maxX)
        list.rect(x, y, textW, textH, theme.tooltipColor, 6f, theme.panelBorder, 1f, 10000)
        list.text(x, y, textW, textH, tooltip.text, theme.textColor, size, UiTextAlign.CENTER, 1, theme.font, false, 10001)
    }

    /** Topmost interactable widget under the point (last drawn wins). */
    fun hitTest(x: Float, y: Float): UiWidget? {
        for (i in visibleWidgets.indices.reversed()) {
            val w = visibleWidgets[i]
            if (!w.isInteractable) continue
            if (w is UiCanvas) continue
            if (w.rect.contains(x, y)) return w
        }
        return null
    }

    private fun activate(widget: UiWidget) {
        lastClickWidget = widget.go.name
        when (widget) {
            is UiButton -> widget.click(this)
            is UiIconButton -> widget.click(this)
            is UiCheckbox -> widget.toggleValue()
            else -> {
                widget.go.emit("clicked")
                widget.go.emit("uiClick", widget.go.name)
            }
        }
    }

    fun setFocus(widget: UiWidget?) {
        if (focusedWidget === widget) return
        focusedWidget?.focused = false
        focusedWidget = widget
        widget?.focused = true
    }

    /** Moves the keyboard focus to the next (or previous) interactable widget in draw order. */
    fun focusNext(direction: Int) {
        val candidates = visibleWidgets.filter { it.isInteractable && it !is UiCanvas }
        if (candidates.isEmpty()) return
        val current = candidates.indexOf(focusedWidget)
        var next = if (current < 0) (if (direction > 0) 0 else candidates.size - 1) else (current + direction + candidates.size) % candidates.size
        setFocus(candidates[next])
    }

    /** Screen (canvas) space -> UI units. */
    fun screenToUi(canvas: UiCanvas, x: Float, y: Float): FloatArray {
        val scale = canvas.scale.coerceAtLeast(0.0001f)
        return floatArrayOf(
            (x - (input.screenWidth - canvas.referenceWidth * scale) * 0.5f) / scale,
            (y - (input.screenHeight - canvas.referenceHeight * scale) * 0.5f) / scale
        )
    }

    /** UI units -> canvas screen space (for drawing the UI with the engine's pixel renderer). */
    fun uiToScreen(canvas: UiCanvas, x: Float, y: Float): FloatArray {
        val scale = canvas.scale
        return floatArrayOf(
            x * scale + (input.screenWidth - canvas.referenceWidth * scale) * 0.5f,
            y * scale + (input.screenHeight - canvas.referenceHeight * scale) * 0.5f
        )
    }

    fun clear() {
        drawLists.clear()
        visibleWidgets.clear()
        pressedWidget = null
        focusedWidget = null
        hoveredWidget = null
    }

    /** All currently laid-out widgets (used by tests and the UI editor overlay). */
    fun currentWidgets(): List<UiWidget> = visibleWidgets.toList()
}

/**
 * Runtime dialog helper: shows a popup with a title, message and buttons; the result is
 * delivered through signals, so scripts and gameplay code can wait for an answer.
 */
object UiDialogs {

    /** Creates a modal dialog under [canvas] and returns its root object. */
    fun show(
        scene: Scene,
        canvasGo: GameObject,
        title: String,
        message: String,
        buttons: List<String>,
        width: Float = 520f,
        height: Float = 260f,
        onResult: (index: Int, label: String) -> Unit = { _, _ -> }
    ): GameObject {
        val canvas = canvasGo.getAny<UiCanvas>()
        val dialog = scene.create("Dialog_$title", canvasGo)
        val panel = UiPanel()
        panel.anchor = UiAnchor.MIDDLE_CENTER
        panel.width = width
        panel.height = height
        panel.layout = UiLayout.VERTICAL
        panel.spacing = 12f
        panel.padding = 18f
        dialog.add(panel)
        if (canvas != null) panel.order = 500

        val titleLabel = scene.create("Title", dialog)
        titleLabel.add(UiLabel().also {
            it.text = title
            it.fontSize = 26f
            it.align = UiTextAlign.CENTER
            it.anchor = UiAnchor.TOP_CENTER
            it.offsetY = 24f
            it.width = width - 36f
            it.height = 34f
            it.order = 501
        })
        val body = scene.create("Message", dialog)
        body.add(UiLabel().also {
            it.text = message
            it.align = UiTextAlign.CENTER
            it.anchor = UiAnchor.MIDDLE_CENTER
            it.offsetY = -10f
            it.width = width - 48f
            it.height = 90f
            it.wrap = true
            it.order = 501
        })
        for ((i, label) in buttons.withIndex()) {
            val row = buttons.size
            val btnObj = scene.create("Button_$label", dialog)
            val btn = UiButton()
            btn.text = label
            btn.anchor = UiAnchor.BOTTOM_CENTER
            btn.width = (width - 40f) / maxOf(1, row) - 10f
            btn.height = 46f
            btn.offsetX = (i - (row - 1) * 0.5f) * (btn.width + 12f)
            btn.offsetY = -26f - btn.height
            btn.order = 502
            btnObj.add(btn)
            btnObj.signal<Boolean>("clicked").connect {
                onResult(i, label)
                close(scene, dialog)
            }
        }
        dialog.emit("opened")
        return dialog
    }

    /**
     * Convenience wrapper used by gameplay code and the scripting API: finds the active canvas and
     * shows a dialog on it. Returns the dialog object (null when there is no canvas in the scene).
     */
    fun showDialog(
        scene: Scene,
        title: String,
        message: String,
        buttons: List<String> = emptyList(),
        onResult: ((Int, String) -> Unit)? = null
    ): GameObject? {
        val canvas = UiRuntime.canvasOf(scene)
            ?: scene.objects.firstOrNull { it.getAny<UiCanvas>() != null }
            ?: return null
        return show(scene, canvas, title, message, buttons, onResult = { i, l -> onResult?.invoke(i, l) })
    }

    fun close(scene: Scene, dialog: GameObject) {
        dialog.emit("closed")
        scene.remove(dialog)
    }

    /** Convenience: a yes/no popup. */
    fun confirm(scene: Scene, canvasGo: GameObject, title: String, message: String, onYes: () -> Unit) {
        show(scene, canvasGo, title, message, listOf("Cancel", "OK")) { index, _ -> if (index == 1) onYes() }
    }
}

/**
 * Runtime UI helpers exposed to gameplay code and scripts: show/hide a canvas, update a HUD
 * value, push notifications.
 */
object UiRuntime {
    fun canvasOf(scene: Scene): GameObject? = scene.objects.firstOrNull { it.isActiveInHierarchy() && it.get<UiCanvas>() != null }

    fun setText(scene: Scene, objectName: String, text: String) {
        scene.find(objectName)?.getAny<UiLabel>()?.text = text
        scene.find(objectName)?.getAny<UiButton>()?.text = text
    }

    fun setProgress(scene: Scene, objectName: String, value: Float) {
        scene.find(objectName)?.getAny<UiProgressBar>()?.applyValue(value)
    }

    fun setSlider(scene: Scene, objectName: String, value: Float) {
        scene.find(objectName)?.getAny<UiSlider>()?.applyValue(value, false)
    }

    fun setVisible(scene: Scene, objectName: String, visible: Boolean) {
        scene.find(objectName)?.getAny<UiWidget>()?.visible = visible
    }

    fun addListItem(scene: Scene, objectName: String, item: String) {
        val list = scene.find(objectName)?.getAny<UiList>() ?: return
        list.items = if (list.items.isBlank()) item else list.items + "\n" + item
    }

    fun toast(scene: Scene, canvasGo: GameObject, message: String, seconds: Float = 2f) {
        val obj = scene.create("Toast", canvasGo)
        val label = UiLabel()
        label.text = message
        label.anchor = UiAnchor.BOTTOM_CENTER
        label.offsetY = -120f
        label.autoWidth = true
        label.autoHeight = true
        label.padding = 16f
        label.order = 900
        obj.add(label)
        val timer = com.sengine.engine.core.Lifetime()
        timer.lifetime = seconds
        obj.add(timer)
    }
}


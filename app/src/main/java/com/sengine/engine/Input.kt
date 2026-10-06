package com.sengine.engine

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.sengine.engine.math.M
import com.sengine.engine.render.View2D
import com.sengine.engine.ui.UiInputState
import java.util.concurrent.ConcurrentHashMap

/**
 * One touch / mouse pointer.
 *
 * Pointers are pooled per touch id so a game can read multi-touch input (two thumbs, pinch, etc.)
 * without allocating during a frame. [frameDx]/[frameDy] are the movement since the previous frame,
 * [dx]/[dy] the movement since the pointer went down.
 */
class Pointer(val id: Int) {
    var x = 0f; var y = 0f
    var frameDx = 0f; var frameDy = 0f
    private var accDx = 0f; private var accDy = 0f
    var startX = 0f; var startY = 0f
    var dx = 0f; var dy = 0f
    var down = false
    var justDown = false
    var justUp = false
    var downTime = 0f
    var duration = 0f
    var pressure = 1f
    var worldX = 0f; var worldY = 0f
    var consumed = false
    /** Set when the pointer moved more than the tap threshold. */
    var moved = false

    internal fun reset(x0: Float, y0: Float, time: Float) {
        x = x0; y = y0; startX = x0; startY = y0
        accDx = 0f; accDy = 0f; frameDx = 0f; frameDy = 0f
        dx = 0f; dy = 0f
        down = true; justDown = true; justUp = false
        downTime = time; duration = 0f
        moved = false; consumed = false
    }

    internal fun move(x0: Float, y0: Float, time: Float, threshold: Float) {
        if (!down) return
        accDx += x0 - x; accDy += y0 - y
        x = x0; y = y0
        dx = x - startX; dy = y - startY
        duration = time - downTime
        if (!moved && (dx * dx + dy * dy) > threshold * threshold) moved = true
    }

    internal fun release(time: Float) {
        down = false; justUp = true
        duration = time - downTime
        dx = x - startX; dy = y - startY
    }

    internal fun beginFrame() {
        justDown = false
        justUp = false
        frameDx = accDx; frameDy = accDy
        accDx = 0f; accDy = 0f
    }
}

/**
 * Unified 2D input: keyboard, mouse, multi-touch, on-screen virtual controls, Android gamepads and
 * gesture recognition (tap, double tap, long press, swipe, pinch, rotation).
 *
 * The Android layer pushes raw events in ([handleTouchEvent], [handleKeyEvent],
 * [handleGenericMotion]); the engine calls [beginFrame] once per frame to publish edges, resolve
 * world positions and run gesture detection; games read the resulting state from scripts, UI or
 * Kotlin gameplay code.
 */
class Input {

    // ---------------------------------------------------------------- keyboard
    /** Held keys (Android [KeyEvent] key codes). */
    val keys: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val pendingDown = HashSet<Int>()
    private val pendingUp = HashSet<Int>()
    val justPressed: MutableSet<Int> = HashSet()
    val justReleased: MutableSet<Int> = HashSet()
    /** UTF-8 text typed since the last frame (consumed by text fields). */
    val typed = StringBuilder()

    fun isKeyDown(code: Int) = code in keys || code in pendingDown
    fun wasPressed(code: Int) = code in justPressed
    fun wasReleased(code: Int) = code in justReleased
    fun anyKeyDown() = keys.isNotEmpty()

    // ---------------------------------------------------------------- mouse (editor + desktop-ish devices)
    var mouseX = 0f; var mouseY = 0f
    var mouseWorldX = 0f; var mouseWorldY = 0f
    var mouseDown = false
    var mouseJustDown = false
    var mouseJustUp = false
    var mouseButton = 0
    var scrollDelta = 0f
    var hoverX = 0f; var hoverY = 0f

    // ---------------------------------------------------------------- gamepad
    var padAxisX = 0f; var padAxisY = 0f
    var padLeftTrigger = 0f; var padRightTrigger = 0f
    val padButtons: MutableSet<Int> = HashSet()
    private val padButtonsPrev: MutableSet<Int> = HashSet()
    var gamepadConnected = false
    var gamepadName = ""

    fun padDown(keyCode: Int) = keyCode in padButtons
    fun padPressed(keyCode: Int) = keyCode in padButtons && keyCode !in padButtonsPrev
    fun padReleased(keyCode: Int) = keyCode !in padButtons && keyCode in padButtonsPrev

    // ---------------------------------------------------------------- virtual on-screen controls
    /** Analog joystick axis written by the on-screen joystick view (-1..1). */
    var axisX = 0f; private set
    var axisY = 0f; private set
    var joystickActive = false; private set
    /** Action button A (jump / accelerate). */
    var a = false; private set
    var b = false; private set
    var aDown = false; private set
    var bDown = false; private set
    private var aPendingDown = false; private var aPendingUp = false
    private var bPendingDown = false; private var bPendingUp = false
    /** Optional on-screen d-pad contribution. */
    var dpadX = 0f; private set
    var dpadY = 0f; private set

    // ---------------------------------------------------------------- touch
    val pointers = ArrayList<Pointer>(8)
    private val pointerPool = ArrayList<Pointer>(8)
    /** Number of pointers currently down. */
    val touchCount: Int get() = pointers.count { it.down }
    var touching = false; private set
    var tapped = false; private set
    var doubleTapped = false; private set
    var longPressed = false; private set
    /** Last tap position in world units (set when [tapped]). */
    var touchX = 0f; private set
    var touchY = 0f; private set
    /** Last tap position in screen pixels. */
    var screenX = 0f; private set
    var screenY = 0f; private set
    /** Swipe direction of the last completed swipe (null until one happens). */
    var swipeDirX = 0f; private set
    var swipeDirY = 0f; private set
    var swipeDistance = 0f; private set
    var flickSpeed = 0f; private set
    /** Pinch scale delta this frame (1 = unchanged) and rotation delta in degrees. */
    var pinchDelta = 1f; private set
    var rotationDelta = 0f; private set
    var pinchActive = false; private set

    private var lastTapTime = -10f
    private var lastTapX = 0f; private var lastTapY = 0f
    private var pinchStartDistance = 0f
    private var pinchStartAngle = 0f
    private var swipeCandidate: Pointer? = null
    private var downSince = -1f
    private var longPressArmed = false

    /** When false all gameplay input reads as idle (used by dialogs / cutscenes). */
    var enabled = true
    /** Tap movement threshold in screen pixels. */
    var tapSlop = 24f
    /** Long-press duration in seconds. */
    var longPressTime = 0.5f
    /** Minimum swipe length in screen pixels to register as a swipe. */
    var swipeMinDistance = 60f
    /** Minimum swipe speed (pixels/second) to register as a flick. */
    var flickMinSpeed = 700f

    // ---------------------------------------------------------------- frame plumbing
    private var time = 0f
    private var frameDt = 1f / 60f
    private var screenW = 1f
    private var screenH = 1f
    private var view: View2D? = null
    private var pendingTapX = 0f
    private var pendingTapY = 0f
    private var pendingTap = false
    private var pendingLongPress = false
    private var pendingDoubleTap = false
    private var pendingSwipe = false
    private var swipeStartX = 0f; private var swipeStartY = 0f
    private var swipeEndX = 0f; private var swipeEndY = 0f
    private var swipeDuration = 0f

    /**
     * Publishes this frame's state: key/button edges, per-pointer frame deltas, world coordinates
     * and gesture results. Called by the engine before scripts and physics run.
     */
    fun beginFrame(view: View2D, dt: Float = frameDt, screenWidth: Float = screenW, screenHeight: Float = screenH) {
        time += dt
        frameDt = dt
        screenW = screenWidth
        screenH = screenHeight
        this.view = view

        // --- keyboard / virtual button edges
        justPressed.clear(); justReleased.clear()
        if (pendingDown.isNotEmpty()) {
            justPressed.addAll(pendingDown)
            keys.addAll(pendingDown)
            pendingDown.clear()
        }
        if (pendingUp.isNotEmpty()) {
            justReleased.addAll(pendingUp)
            keys.removeAll(pendingUp)
            pendingUp.clear()
        }
        aDown = false; bDown = false
        if (aPendingDown) { if (!a) aDown = true; a = true; aPendingDown = false }
        if (bPendingDown) { if (!b) bDown = true; b = true; bPendingDown = false }
        if (aPendingUp) { a = false; aPendingUp = false }
        if (bPendingUp) { b = false; bPendingUp = false }

        // --- pointers: publish deltas, expire released ones after one frame
        touching = false
        val it = pointers.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.beginFrame()
            p.duration = time - p.downTime
            if (p.down) touching = true else if (p.justUp) it.remove()
            p.worldX = screenToWorldX(p.x); p.worldY = screenToWorldY(p.y)
        }

        // --- gestures
        tapped = pendingTap; doubleTapped = pendingDoubleTap; longPressed = pendingLongPress
        if (pendingTap) {
            screenX = pendingTapX; screenY = pendingTapY
            touchX = screenToWorldX(screenX); touchY = screenToWorldY(screenY)
        }
        if (pendingSwipe) {
            val dx = swipeEndX - swipeStartX
            val dy = swipeEndY - swipeStartY
            swipeDistance = kotlin.math.sqrt(dx * dx + dy * dy)
            swipeDirX = if (swipeDistance > 0.001f) dx / swipeDistance else 0f
            swipeDirY = if (swipeDistance > 0.001f) dy / swipeDistance else 0f
            flickSpeed = if (swipeDuration > 0.001f) swipeDistance / swipeDuration else 0f
        }
        pendingTap = false; pendingDoubleTap = false; pendingLongPress = false; pendingSwipe = false

        // pinch + rotation from the first two live pointers
        pinchDelta = 1f; rotationDelta = 0f
        val live = pointers.filter { it.down }
        if (live.size >= 2) {
            val d = dist(live[0].x, live[0].y, live[1].x, live[1].y)
            val ang = angleDeg(live[0].x, live[0].y, live[1].x, live[1].y)
            if (!pinchActive) {
                pinchActive = true
                pinchStartDistance = d
                pinchStartAngle = ang
                // keep using the first two pointers for the whole gesture
                primaryA = live[0].id; primaryB = live[1].id
            } else {
                if (pinchStartDistance > 1f) pinchDelta = d / pinchStartDistance
                rotationDelta = M.wrapAngle(ang - pinchStartAngle)
            }
        } else if (pinchActive) {
            pinchActive = false
            pinchStartDistance = 0f
            primaryA = -1; primaryB = -1
        }

        // long press arming for the primary pointer
        if (longPressArmed && !pendingLongPress) {
            val p = pointer(0)
            if (p != null && p.down && !p.moved && p.duration >= longPressTime) {
                pendingLongPress = true
                longPressArmed = false
                screenX = p.x; screenY = p.y
                touchX = screenToWorldX(p.x); touchY = screenToWorldY(p.y)
            }
            longPressed = longPressed || pendingLongPress
        }

        mouseJustDown = pendingMouseDown; mouseJustUp = pendingMouseUp
        pendingMouseDown = false; pendingMouseUp = false
        mouseWorldX = screenToWorldX(mouseX); mouseWorldY = screenToWorldY(mouseY)

        padButtonsPrev.clear(); padButtonsPrev.addAll(padButtons)
    }

    private var pendingMouseDown = false; private var pendingMouseUp = false
    private var primaryA = -1; private var primaryB = -1

    /** Clears the per-frame gesture flags early (dialogs, scene changes). */
    fun consumeGestures() {
        tapped = false; doubleTapped = false; longPressed = false
        swipeDistance = 0f; flickSpeed = 0f; pinchDelta = 1f; rotationDelta = 0f
    }

    fun clear() {
        keys.clear(); pendingDown.clear(); pendingUp.clear()
        justPressed.clear(); justReleased.clear()
        pointers.clear()
        typed.setLength(0)
        axisX = 0f; axisY = 0f; dpadX = 0f; dpadY = 0f
        a = false; b = false; aDown = false; bDown = false
        touching = false; tapped = false; doubleTapped = false; longPressed = false
        joystickActive = false
        padButtons.clear(); padAxisX = 0f; padAxisY = 0f
        mouseDown = false
        scrollDelta = 0f
    }

    // ---------------------------------------------------------------- screen -> world
    fun setScreenSize(width: Float, height: Float) {
        screenW = width.coerceAtLeast(1f)
        screenH = height.coerceAtLeast(1f)
    }

    /** Screen (pixels) to world units, honouring the current camera view. */
    fun screenToWorldX(px: Float): Float {
        val v = view ?: return px
        return v.cx + (px / screenW - 0.5f) * v.size * v.aspect
    }

    fun screenToWorldY(py: Float): Float {
        val v = view ?: return py
        return v.cy - (py / screenH - 0.5f) * v.size
    }

    /**
     * Converts a touch stored in an editor viewport of size [viewW]x[viewH] pixels into world
     * coordinates using the given view (used by the editor viewport + tools).
     */
    fun screenToWorldIn(v: View2D, px: Float, py: Float, viewWidth: Float, viewHeight: Float): FloatArray {
        val wx = v.cx + (px / viewWidth - 0.5f) * v.size * v.aspect
        val wy = v.cy - (py / viewHeight - 0.5f) * v.size
        return floatArrayOf(wx, wy)
    }

    // ---------------------------------------------------------------- android event bridge
    fun handleTouchEvent(e: MotionEvent): Boolean {
        if (!enabled) return false
        val action = e.actionMasked
        val index = e.actionIndex
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val p = obtainPointer(e.getPointerId(index))
                p.reset(e.getX(index), e.getY(index), time)
                p.pressure = e.getPressure(index)
                if (action == MotionEvent.ACTION_DOWN) {
                    mouseDown = true; mouseJustDown = false; pendingMouseDown = true
                    mouseX = p.x; mouseY = p.y
                    downSince = time
                    longPressArmed = true
                    swipeStartX = p.x; swipeStartY = p.y
                    swipeCandidate = p
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val n = e.pointerCount
                for (i in 0 until n) {
                    val p = findPointer(e.getPointerId(i)) ?: continue
                    p.move(e.getX(i), e.getY(i), time, tapSlop)
                }
                val first = pointers.firstOrNull { it.down }
                if (first != null) { mouseX = first.x; mouseY = first.y }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val p = findPointer(e.getPointerId(index))
                if (p != null) {
                    p.move(e.getX(index), e.getY(index), time, tapSlop)
                    p.release(time)
                    onPointerReleased(p)
                }
                if (action == MotionEvent.ACTION_UP) {
                    mouseDown = false; pendingMouseUp = true
                    swipeCandidate = null
                    longPressArmed = false
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                for (q in pointers) if (q.down) q.release(time)
                mouseDown = false
                longPressArmed = false
                swipeCandidate = null
            }
            MotionEvent.ACTION_SCROLL -> {
                scrollDelta += e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            }
        }
        return true
    }

    private fun onPointerReleased(p: Pointer) {
        val duration = p.duration
        val distance = kotlin.math.sqrt(p.dx * p.dx + p.dy * p.dy)
        if (!p.moved && distance < tapSlop && duration < 0.4f) {
            // tap
            if (time - lastTapTime < 0.32f &&
                dist(p.x, p.y, lastTapX, lastTapY) < tapSlop * 2f
            ) {
                pendingDoubleTap = true
                lastTapTime = -10f
            } else {
                lastTapTime = time
                lastTapX = p.x; lastTapY = p.y
            }
            pendingTapX = p.x; pendingTapY = p.y; pendingTap = true
            p.consumed = false
        } else if (duration > 0.02f) {
            val speed = distance / duration
            if (distance >= swipeMinDistance || speed >= flickMinSpeed) {
                swipeStartX = p.startX; swipeStartY = p.startY
                swipeEndX = p.x; swipeEndY = p.y
                swipeDuration = duration
                pendingSwipe = true
            }
        }
    }

    /** Returns false when the key is not one the engine consumes (so Android can handle it). */
    fun handleKeyEvent(e: KeyEvent, down: Boolean): Boolean {
        if (!enabled) return false
        val code = e.keyCode
        // Gamepad buttons arrive as key events too.
        if (isGamepadKey(code)) {
            if (down) padButtons.add(code) else padButtons.remove(code)
            gamepadConnected = true
            if (e.device != null) gamepadName = e.device.name ?: ""
            return true
        }
        return when (code) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU -> false
            else -> {
                if (down) {
                    pendingDown.add(code)
                    val c = e.unicodeChar
                    if (c != 0) typed.append(c.toChar())
                } else {
                    pendingUp.add(code)
                }
                true
            }
        }
    }

    /** Analog sticks / triggers from a gamepad. */
    fun handleGenericMotion(e: MotionEvent): Boolean {
        if (!enabled) return false
        if (e.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK &&
            e.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD
        ) return false
        gamepadConnected = true
        if (e.device != null) gamepadName = e.device.name ?: ""
        padAxisX = deadZone(e.getAxisValue(MotionEvent.AXIS_X), 0.18f)
        padAxisY = deadZone(e.getAxisValue(MotionEvent.AXIS_Y), 0.18f)
        val rx = e.getAxisValue(MotionEvent.AXIS_RX)
        if (kotlin.math.abs(rx) > 0.001f) padAxisX = deadZone(rx, 0.18f)
        val ry = e.getAxisValue(MotionEvent.AXIS_RY)
        if (kotlin.math.abs(ry) > 0.001f) padAxisY = deadZone(ry, 0.18f)
        padLeftTrigger = e.getAxisValue(MotionEvent.AXIS_LTRIGGER).coerceIn(0f, 1f)
        padRightTrigger = e.getAxisValue(MotionEvent.AXIS_RTRIGGER).coerceIn(0f, 1f)
        if (padButtons.isEmpty()) {
            val hatX = e.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (kotlin.math.abs(hatX) > 0.5f) padAxisX = hatX
            if (kotlin.math.abs(hatY) > 0.5f) padAxisY = -hatY
        }
        return true
    }

    private fun deadZone(v: Float, dz: Float): Float {
        val m = kotlin.math.abs(v)
        if (m < dz) return 0f
        return (m - dz) / (1f - dz) * (if (v < 0) -1f else 1f)
    }

    private fun isGamepadKey(code: Int) = when (code) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
        KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2, KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER -> true
        else -> false
    }

    // ---------------------------------------------------------------- virtual controls
    /** Called by the on-screen joystick; [x]/[y] are in -1..1 with y up. */
    fun setJoystick(x: Float, y: Float, active: Boolean) {
        axisX = x.coerceIn(-1f, 1f)
        axisY = y.coerceIn(-1f, 1f)
        joystickActive = active
    }

    fun pressButtonA() { aPendingDown = true }
    fun releaseButtonA() { aPendingUp = true }
    fun pressButtonB() { bPendingDown = true }
    fun releaseButtonB() { bPendingUp = true }
    fun setDpad(x: Float, y: Float) { dpadX = x.coerceIn(-1f, 1f); dpadY = y.coerceIn(-1f, 1f) }

    /**
     * Injects a tap at screen pixel coordinates. Used by input replay/tests and by the editor's
     * game-view preview so a desktop run can feed the same events a device would.
     */
    fun injectTap(screenPx: Float, screenPy: Float) {
        pendingTap = true
        pendingTapX = screenPx
        pendingTapY = screenPy
    }

    // ---------------------------------------------------------------- gameplay reads
    /** Horizontal axis combining joystick, d-pad, gamepad and keyboard. */
    fun axisHorizontal(): Float {
        var v = axisX
        if (kotlin.math.abs(v) < 0.01f) v = dpadX
        if (kotlin.math.abs(v) < 0.01f) v = padAxisX
        if (kotlin.math.abs(v) < 0.01f) {
            val l = if (KeyEvent.KEYCODE_A in keys || KeyEvent.KEYCODE_DPAD_LEFT in keys) -1f else 0f
            val r = if (KeyEvent.KEYCODE_D in keys || KeyEvent.KEYCODE_DPAD_RIGHT in keys) 1f else 0f
            v = l + r
        }
        return v.coerceIn(-1f, 1f)
    }

    /** Vertical axis (up = +1). */
    fun axisVertical(): Float {
        var v = axisY
        if (kotlin.math.abs(v) < 0.01f) v = dpadY
        if (kotlin.math.abs(v) < 0.01f) v = -padAxisY
        if (kotlin.math.abs(v) < 0.01f) {
            val u = if (KeyEvent.KEYCODE_W in keys || KeyEvent.KEYCODE_DPAD_UP in keys) 1f else 0f
            val d = if (KeyEvent.KEYCODE_S in keys || KeyEvent.KEYCODE_DPAD_DOWN in keys) -1f else 0f
            v = u + d
        }
        return v.coerceIn(-1f, 1f)
    }

    /** Action button by name: "a", "b", "jump", "fire", "action", "brake", "boost". */
    fun button(name: String): Boolean = when (name.lowercase()) {
        "a", "jump", "fire", "action", "accelerate", "gas", "up" ->
            a || KeyEvent.KEYCODE_SPACE in keys || KeyEvent.KEYCODE_DPAD_UP in keys ||
                KeyEvent.KEYCODE_BUTTON_A in padButtons
        "b", "brake", "reverse", "down" ->
            b || KeyEvent.KEYCODE_SHIFT_LEFT in keys || KeyEvent.KEYCODE_DPAD_DOWN in keys ||
                KeyEvent.KEYCODE_BUTTON_B in padButtons
        "left" -> axisHorizontal() < -0.3f
        "right" -> axisHorizontal() > 0.3f
        "boost", "run" -> KeyEvent.KEYCODE_DPAD_LEFT in padButtons || KeyEvent.KEYCODE_BUTTON_X in padButtons
        else -> false
    }

    /** True on the frame the button was pressed (edge). */
    fun buttonDown(name: String): Boolean = when (name.lowercase()) {
        "a", "jump", "fire", "action", "accelerate", "gas", "up" ->
            aDown || KeyEvent.KEYCODE_SPACE in justPressed || KeyEvent.KEYCODE_BUTTON_A in justPressed
        "b", "brake", "reverse", "down" -> bDown || KeyEvent.KEYCODE_BUTTON_B in justPressed
        else -> false
    }

    // ---------------------------------------------------------------- UI bridge
    /** Copies the frame's state into the UI input state so widgets react to real input. */
    fun feedUi(state: UiInputState) {
        val p = pointers.firstOrNull { it.down } ?: pointers.firstOrNull()
        if (p != null) {
            state.pointerX = p.x
            state.pointerY = p.y
            state.pointerJustDown = state.pointerJustDown || p.justDown
            state.pointerJustUp = state.pointerJustUp || p.justUp
        }
        state.pointerDown = touchCount > 0 || mouseDown
        state.scrollDelta += scrollDelta
        if (aDown || a) state.pointerDown = true
        for (k in justPressed) state.keysJustPressed.add(keyName(k))
        for (k in keys) state.keysDown.add(keyName(k))
        if (typed.isNotEmpty()) {
            state.textInput.append(typed)
        }
    }

    /** Android key code -> readable name used by scripts and the UI system. */
    fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
        KeyEvent.KEYCODE_SPACE -> "space"
        KeyEvent.KEYCODE_ENTER -> "enter"
        KeyEvent.KEYCODE_ESCAPE -> "escape"
        KeyEvent.KEYCODE_TAB -> "tab"
        KeyEvent.KEYCODE_DEL -> "backspace"
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> "shift"
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> "ctrl"
        KeyEvent.KEYCODE_BUTTON_A -> "a"
        KeyEvent.KEYCODE_BUTTON_B -> "b"
        else -> if (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            ('a' + (code - KeyEvent.KEYCODE_A)).toString()
        } else if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
            ('0' + (code - KeyEvent.KEYCODE_0)).toString()
        } else "key$code"
    }

    // ---------------------------------------------------------------- internals
    private fun obtainPointer(id: Int): Pointer {
        var p = pointerPool.firstOrNull { it.id == id && !it.down }
        if (p == null) {
            p = Pointer(id)
            pointerPool.add(p)
        }
        if (pointers.size >= 10) pointers.removeAt(0)
        pointers.add(p)
        return p
    }

    /** Pointer by index in the order they were pressed. */
    fun pointer(index: Int): Pointer? = pointers.firstOrNull { it.down }?.let { first ->
        if (index == 0) first else pointers.filter { it.down }.getOrNull(index)
    }

    fun findPointer(id: Int): Pointer? = pointers.firstOrNull { it.id == id }

    fun pointerById(id: Int): Pointer? = findPointer(id)

    /** Primary (first) pointer or null. */
    fun primary(): Pointer? = pointers.firstOrNull { it.down }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun angleDeg(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        Math.toDegrees(kotlin.math.atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())).toFloat()

    /** Human-readable state for the profiler / debug overlay. */
    fun debugText(): String {
        val p = primary()
        return "touch=${touchCount} axis=${"%.2f".format(axisHorizontal())},${"%.2f".format(axisVertical())}" +
            (p?.let { " ptr=${it.x.toInt()},${it.y.toInt()}" } ?: "") +
            (if (gamepadConnected) " pad=$gamepadName" else "")
    }
}

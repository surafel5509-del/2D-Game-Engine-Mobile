package com.sengine.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.sengine.engine.blueprint.Blueprint
import com.sengine.engine.blueprint.BpNode
import kotlin.math.hypot

/** Node-graph canvas for blueprints: pan/zoom, drag nodes, drag wires from output pins to nodes. */
@SuppressLint("ViewConstructor")
class BlueprintView(ctx: Context, var bp: Blueprint) : View(ctx) {
    var onEditParam: (BpNode, String) -> Unit = { _, _ -> }
    var onNodeMenu: (BpNode) -> Unit = {}
    var onChanged: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private val nodeW = 210f * d
    private val headerH = 30f * d
    private val rowH = 24f * d
    private val pinR = 7f * d

    var offX = 20f * d
    var offY = 20f * d
    var scale = 1f

    var selected: BpNode? = null
    private var dragNode: BpNode? = null
    private var wireFrom: BpNode? = null
    private var wirePin = ""
    private var wireX = 0f
    private var wireY = 0f
    private var panning = false
    private var lastX = 0f
    private var lastY = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * d }
    private val grid = Paint().apply { color = 0x14FFFFFF; strokeWidth = 1f }
    private val path = Path()
    private val rect = RectF()

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean {
            val ns = (scale * det.scaleFactor).coerceIn(0.35f, 2.5f)
            val fx = det.focusX; val fy = det.focusY
            offX = fx - (fx - offX) * ns / scale
            offY = fy - (fy - offY) * ns / scale
            scale = ns
            invalidate(); return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean { tap(e.x, e.y); return true }
        override fun onLongPress(e: MotionEvent) {
            val n = nodeAt(wx(e.x), wy(e.y)) ?: return
            if (dragNode != null || wireFrom != null) return
            selected = n; invalidate(); onNodeMenu(n)
        }
    })

    fun wx(sx: Float) = (sx - offX) / scale
    fun wy(sy: Float) = (sy - offY) / scale

    fun centerWorld(): Pair<Float, Float> = wx(width / 2f) - nodeW / 2 to wy(height / 3f)

    fun nodeHeight(n: BpNode): Float {
        val def = n.def ?: return headerH
        return headerH + (def.outs.size.coerceAtLeast(if (def.hasIn) 1 else 0)) * rowH + def.params.size * rowH + 8f * d
    }

    private fun inPin(n: BpNode) = floatArrayOf(n.x * d, n.y * d + headerH / 2)
    private fun outPin(n: BpNode, i: Int) = floatArrayOf(n.x * d + nodeW, n.y * d + headerH + rowH * i + rowH / 2)

    private fun nodeAt(x: Float, y: Float): BpNode? = bp.nodes.lastOrNull {
        x >= it.x * d && x <= it.x * d + nodeW && y >= it.y * d && y <= it.y * d + nodeHeight(it)
    }

    private fun outPinAt(x: Float, y: Float): Pair<BpNode, String>? {
        for (n in bp.nodes.asReversed()) {
            val def = n.def ?: continue
            def.outs.forEachIndexed { i, pin ->
                val p = outPin(n, i)
                if (hypot(x - p[0], y - p[1]) < pinR * 2.6f) return n to pin
            }
        }
        return null
    }

    private fun tap(sx: Float, sy: Float) {
        val x = wx(sx); val y = wy(sy)
        outPinAt(x, y)?.let { (n, pin) ->
            // tap on a connected output pin disconnects it
            if (bp.links.removeAll { it.from == n.id && it.pin == pin }) { onChanged(); invalidate(); return }
        }
        val n = nodeAt(x, y)
        selected = n
        if (n != null) {
            val def = n.def
            if (def != null) {
                val paramTop = n.y * d + headerH + def.outs.size.coerceAtLeast(if (def.hasIn) 1 else 0) * rowH
                val idx = ((y - paramTop) / rowH).toInt()
                if (y >= paramTop && idx in def.params.indices) onEditParam(n, def.params[idx].name)
            }
        }
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        val x = wx(e.x); val y = wy(e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                val pin = outPinAt(x, y)
                if (pin != null) { wireFrom = pin.first; wirePin = pin.second; wireX = x; wireY = y }
                else {
                    val n = nodeAt(x, y)
                    if (n != null) { dragNode = n; selected = n; bp.nodes.remove(n); bp.nodes.add(n) } else panning = true
                }
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> { dragNode = null; wireFrom = null; panning = false }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount > 1 || scaleDetector.isInProgress) { lastX = e.x; lastY = e.y; return true }
                val dx = e.x - lastX; val dy = e.y - lastY
                when {
                    wireFrom != null -> { wireX = x; wireY = y }
                    dragNode != null -> { dragNode!!.x += dx / scale / d; dragNode!!.y += dy / scale / d }
                    panning -> { offX += dx; offY += dy }
                }
                lastX = e.x; lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val from = wireFrom
                if (from != null) {
                    val target = nodeAt(x, y)
                    if (target != null && target !== from && target.def?.hasIn == true) { bp.connect(from.id, wirePin, target.id); onChanged() }
                }
                if (dragNode != null) {
                    // snap to a 10dp grid
                    dragNode!!.x = Math.round(dragNode!!.x / 10f) * 10f; dragNode!!.y = Math.round(dragNode!!.y / 10f) * 10f
                    onChanged()
                }
                wireFrom = null; dragNode = null; panning = false
                invalidate()
            }
        }
        return true
    }

    private fun catColor(cat: String): Int = when (cat) {
        "Events" -> 0xFFC62828.toInt()
        "Flow" -> 0xFF6A1B9A.toInt()
        "Movement" -> 0xFF1565C0.toInt()
        "Physics" -> 0xFF00838F.toInt()
        "Objects" -> 0xFF2E7D32.toInt()
        "Variables" -> 0xFFEF6C00.toInt()
        else -> 0xFF455A64.toInt()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1B1D21.toInt())
        // grid
        val step = 24f * d * scale
        var gx = offX % step
        while (gx < width) { c.drawLine(gx, 0f, gx, height.toFloat(), grid); gx += step }
        var gy = offY % step
        while (gy < height) { c.drawLine(0f, gy, width.toFloat(), gy, grid); gy += step }

        c.save()
        c.translate(offX, offY)
        c.scale(scale, scale)

        // links
        stroke.strokeWidth = 3f * d
        for (l in bp.links) {
            val a = bp.node(l.from) ?: continue
            val b = bp.node(l.to) ?: continue
            val i = a.def?.outs?.indexOf(l.pin) ?: -1
            if (i < 0) continue
            val p = outPin(a, i); val q = inPin(b)
            stroke.color = 0xFFE0E0E0.toInt()
            wire(c, p[0], p[1], q[0], q[1])
        }
        wireFrom?.let { n ->
            val i = n.def?.outs?.indexOf(wirePin) ?: 0
            val p = outPin(n, i)
            stroke.color = 0xFFFFD54F.toInt()
            stroke.pathEffect = DashPathEffect(floatArrayOf(10f * d, 6f * d), 0f)
            wire(c, p[0], p[1], wireX, wireY)
            stroke.pathEffect = null
        }

        // nodes
        for (n in bp.nodes) drawNode(c, n)
        c.restore()

        if (bp.nodes.isEmpty()) {
            text.textAlign = Paint.Align.CENTER; text.color = 0xFF9AA0A6.toInt()
            c.drawText("Add nodes from the palette. Drag from a ● output to a node to connect.", width / 2f, height / 2f, text)
            text.textAlign = Paint.Align.LEFT; text.color = Color.WHITE
        }
    }

    private fun wire(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
        val k = maxOf(40f * d, kotlin.math.abs(x2 - x1) * 0.5f)
        path.reset(); path.moveTo(x1, y1); path.cubicTo(x1 + k, y1, x2 - k, y2, x2, y2)
        c.drawPath(path, stroke)
    }

    private fun drawNode(c: Canvas, n: BpNode) {
        val def = n.def
        val x = n.x * d; val y = n.y * d
        val h = nodeHeight(n)
        val r = 8f * d
        // shadow + body
        fill.color = 0x66000000; rect.set(x + 3 * d, y + 4 * d, x + nodeW + 3 * d, y + h + 4 * d); c.drawRoundRect(rect, r, r, fill)
        fill.color = 0xF02B2E34.toInt(); rect.set(x, y, x + nodeW, y + h); c.drawRoundRect(rect, r, r, fill)
        // header
        fill.color = catColor(def?.category ?: "")
        rect.set(x, y, x + nodeW, y + headerH); c.drawRoundRect(rect, r, r, fill)
        rect.set(x, y + headerH - r, x + nodeW, y + headerH); c.drawRect(rect, fill)
        text.isFakeBoldText = true; text.color = Color.WHITE; text.textSize = 13f * d
        c.drawText(def?.title ?: "? ${n.type}", x + (if (def?.hasIn == true) 16f else 10f) * d, y + headerH * 0.66f, text)
        text.isFakeBoldText = false; text.textSize = 11.5f * d
        // selection outline
        if (n === selected) {
            stroke.color = 0xFFFFD54F.toInt(); stroke.strokeWidth = 2f * d
            rect.set(x, y, x + nodeW, y + h); c.drawRoundRect(rect, r, r, stroke)
        }
        if (def == null) return
        // input pin
        if (def.hasIn) {
            val p = inPin(n)
            fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(p[0], p[1], pinR * 0.85f, fill)
            fill.color = catColor(def.category); c.drawCircle(p[0], p[1], pinR * 0.45f, fill)
        }
        // outputs
        def.outs.forEachIndexed { i, pin ->
            val p = outPin(n, i)
            val connected = bp.links.any { it.from == n.id && it.pin == pin }
            fill.color = if (connected) 0xFFFFFFFF.toInt() else 0xFF8A8F98.toInt()
            c.drawCircle(p[0], p[1], pinR, fill)
            if (!connected) { fill.color = 0xFF2B2E34.toInt(); c.drawCircle(p[0], p[1], pinR * 0.5f, fill) }
            text.textAlign = Paint.Align.RIGHT; text.color = 0xFFCFD2D6.toInt()
            c.drawText(if (pin == "out") "▶" else pin, p[0] - pinR * 1.8f, p[1] + 4f * d, text)
            text.textAlign = Paint.Align.LEFT
        }
        // params
        val top = y + headerH + def.outs.size.coerceAtLeast(if (def.hasIn) 1 else 0) * rowH
        def.params.forEachIndexed { i, pd ->
            val ry = top + i * rowH
            fill.color = 0xFF1E2024.toInt()
            rect.set(x + 8 * d, ry + 2 * d, x + nodeW - 8 * d, ry + rowH - 2 * d); c.drawRoundRect(rect, 4 * d, 4 * d, fill)
            text.color = 0xFF9AA0A6.toInt()
            c.drawText(pd.name, x + 13 * d, ry + rowH * 0.68f, text)
            val nameW = text.measureText(pd.name) + 8 * d
            text.color = if (pd.str) 0xFFA5D6A7.toInt() else 0xFFFFE082.toInt()
            var v = n.param(pd.name)
            val maxW = nodeW - 26 * d - nameW
            while (v.isNotEmpty() && text.measureText(v) > maxW) v = v.dropLast(2) + "…".takeIf { v.length > 2 }.orEmpty()
            c.drawText(v, x + 13 * d + nameW, ry + rowH * 0.68f, text)
        }
    }
}

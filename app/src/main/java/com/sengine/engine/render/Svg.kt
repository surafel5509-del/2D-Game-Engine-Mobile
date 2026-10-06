package com.sengine.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal, dependency-free SVG rasteriser for the 2D asset pipeline.
 *
 * Supports the subset that 2D game art actually uses: svg/viewBox sizing, `g`/`path`/`rect`/
 * `circle`/`ellipse`/`line`/`polyline`/`polygon`, path commands M L H V C S Q T A Z (absolute and
 * relative), `transform` (translate/scale/rotate/matrix), flat fills and strokes with opacity,
 * linear/radial gradient paint servers and `fill-rule`. Anything unsupported is ignored instead of
 * failing, so a hand-authored icon never breaks the import.
 */
object Svg {

    /** Rasterises SVG markup to a bitmap. Returns null when the document has no drawable content. */
    fun rasterize(input: InputStream, overrideWidth: Int = 0, overrideHeight: Int = 0): Bitmap? {
        return try {
            val doc = parse(input)
            val w = if (overrideWidth > 0) overrideWidth else doc.width
            val h = if (overrideHeight > 0) overrideHeight else doc.height
            if (w <= 0 || h <= 0) null else draw(doc, w, h)
        } catch (t: Throwable) {
            android.util.Log.w(GL.TAG, "SVG import failed: ${t.message}")
            null
        }
    }

    fun rasterize(text: String, overrideWidth: Int = 0, overrideHeight: Int = 0): Bitmap? =
        rasterize(text.byteInputStream(), overrideWidth, overrideHeight)

    // ------------------------------------------------------------------ document model

    private class Shape(
        val path: Path,
        val fill: Paint?,
        val stroke: Paint?,
        val gradient: GradientPaint?
    )

    private class GradientPaint(
        val radial: Boolean,
        val x1: Float, val y1: Float, val x2: Float, val y2: Float,
        val radius: Float,
        val stops: List<Pair<Float, Int>>
    )

    private class Doc(val width: Int, val height: Int, val viewW: Float, val viewH: Float, val shapes: MutableList<Shape>)

    private class PaintState {
        var fillColor: Int = Color.BLACK
        var strokeColor: Int = 0
        var fillAlpha: Float = 1f
        var strokeAlpha: Float = 1f
        var strokeWidth: Float = 1f
        var fillGradient: String? = null
        var strokeGradient: String? = null
        var fillRuleEvenOdd = false

        fun copy(): PaintState {
            val s = PaintState()
            s.fillColor = fillColor; s.strokeColor = strokeColor
            s.fillAlpha = fillAlpha; s.strokeAlpha = strokeAlpha
            s.strokeWidth = strokeWidth
            s.fillGradient = fillGradient; s.strokeGradient = strokeGradient
            s.fillRuleEvenOdd = fillRuleEvenOdd
            return s
        }
    }

    // ------------------------------------------------------------------ parser

    private fun parse(input: InputStream): Doc {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        parser.setInput(input, null)
        val shapes = ArrayList<Shape>()
        val gradients = HashMap<String, GradientPaint>()
        var width = 0f
        var height = 0f
        var viewW = 0f
        var viewH = 0f
        val stack = ArrayList<PaintState>().apply { add(PaintState()) }
        val transformStack = ArrayList<FloatArray>()
        transformStack.add(floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f))

        fun current() = stack.last()
        fun ctm() = transformStack.last()

        var event = parser.eventType
        var pendingGradient: GradientPaint? = null
        var pendingGradientId: String? = null
        var pendingStopList: MutableList<Pair<Float, Int>>? = null

        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val name = parser.name ?: ""
                when (name.lowercase()) {
                    "svg" -> {
                        val vw = parser.getAttributeValue(null, "viewBox")
                        width = length(parser.getAttributeValue(null, "width"), 0f)
                        height = length(parser.getAttributeValue(null, "height"), 0f)
                        if (vw != null) {
                            val p = vw.trim().split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                            if (p.size == 4) {
                                viewW = p[2]; viewH = p[3]
                                if (width <= 0f) width = viewW
                                if (height <= 0f) height = viewH
                            }
                        }
                        if (width <= 0f) width = 64f
                        if (height <= 0f) height = 64f
                        if (viewW <= 0f) viewW = width
                        if (viewH <= 0f) viewH = height
                    }
                    "g" -> {
                        val st = current().copy()
                        readStyle(parser, st)
                        stack.add(st)
                        transformStack.add(compose(ctm(), parser.getAttributeValue(null, "transform")))
                    }
                    "linearGradient", "radialGradient" -> {
                        pendingStopList = ArrayList()
                        pendingGradientId = parser.getAttributeValue(null, "id")
                        val radial = name.lowercase() == "radialGradient"
                        val x1 = length(parser.getAttributeValue(null, "x1"), 0f)
                        val y1 = length(parser.getAttributeValue(null, "y1"), 0f)
                        val x2 = length(parser.getAttributeValue(null, "x2"), 1f)
                        val y2 = length(parser.getAttributeValue(null, "y2"), 0f)
                        val r = length(parser.getAttributeValue(null, "r"), 0.5f)
                        pendingGradient = GradientPaint(radial, x1, y1, x2, y2, r, emptyList())
                    }
                    "stop" -> {
                        val offset = parser.getAttributeValue(null, "offset")?.removeSuffix("%")?.toFloatOrNull() ?: 0f
                        val t = if (offset > 1f) offset / 100f else offset
                        val col = parseColor(parser.getAttributeValue(null, "stop-color"), Color.BLACK)
                        val op = parser.getAttributeValue(null, "stop-opacity")?.toFloatOrNull() ?: 1f
                        pendingStopList?.add(t.coerceIn(0f, 1f) to applyAlpha(col, op))
                    }
                    "path", "rect", "circle", "ellipse", "line", "polyline", "polygon" -> {
                        val st = current().copy()
                        readStyle(parser, st)
                        val t = compose(ctm(), parser.getAttributeValue(null, "transform"))
                        val path = Path()
                        pathFor(parser, name.lowercase(), path)
                        path.transform(matrixOf(t))
                        shapes.add(build(path, st, gradients, parser))
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                when ((parser.name ?: "").lowercase()) {
                    "g" -> {
                        if (stack.size > 1) stack.removeAt(stack.size - 1)
                        if (transformStack.size > 1) transformStack.removeAt(transformStack.size - 1)
                    }
                    "lineargradient", "radialgradient" -> {
                        val g = pendingGradient
                        val id = pendingGradientId
                        if (g != null && id != null) {
                            gradients[id] = GradientPaint(g.radial, g.x1, g.y1, g.x2, g.y2, g.radius, pendingStopList ?: emptyList())
                        }
                        pendingGradient = null; pendingGradientId = null; pendingStopList = null
                    }
                }
            }
            event = parser.next()
        }
        val outW = max(1, width.toInt())
        val outH = max(1, height.toInt())
        return Doc(outW, outH, viewW, viewH, shapes)
    }

    private fun readStyle(parser: XmlPullParser, st: PaintState) {
        val style = parser.getAttributeValue(null, "style")
        if (style != null) {
            for (part in style.split(';')) {
                val kv = part.split(':')
                if (kv.size == 2) applyProperty(kv[0].trim(), kv[1].trim(), st)
            }
        }
        for (attr in listOf("fill", "stroke", "stroke-width", "opacity", "fill-opacity", "stroke-opacity", "fill-rule")) {
            val v = parser.getAttributeValue(null, attr) ?: continue
            applyProperty(attr, v, st)
        }
    }

    private fun applyProperty(key: String, value: String, st: PaintState) {
        when (key) {
            "fill" -> {
                if (value.startsWith("url(")) st.fillGradient = value.substringAfter('#').substringBefore(')').trim()
                else if (value == "none") st.fillColor = 0
                else st.fillColor = parseColor(value, st.fillColor)
            }
            "stroke" -> {
                if (value.startsWith("url(")) st.strokeGradient = value.substringAfter('#').substringBefore(')').trim()
                else if (value == "none") st.strokeColor = 0
                else st.strokeColor = parseColor(value, 0xFF000000.toInt())
            }
            "stroke-width" -> st.strokeWidth = length(value, 1f)
            "opacity" -> {
                val o = value.toFloatOrNull() ?: 1f
                st.fillAlpha *= o; st.strokeAlpha *= o
            }
            "fill-opacity" -> st.fillAlpha *= value.toFloatOrNull() ?: 1f
            "stroke-opacity" -> st.strokeAlpha *= value.toFloatOrNull() ?: 1f
            "fill-rule" -> st.fillRuleEvenOdd = value == "evenodd"
        }
    }

    private fun build(path: Path, st: PaintState, gradients: Map<String, GradientPaint>, parser: XmlPullParser): Shape {
        var gradient: GradientPaint? = null
        st.fillGradient?.let { gradient = gradients[it] }
        if (gradient == null) st.strokeGradient?.let { gradient = gradients[it] }
        val fillPaint = if (st.fillColor != 0 || gradient != null) Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = applyAlpha(st.fillColor, st.fillAlpha)
            isAntiAlias = true
        } else null
        val strokePaint = if (st.strokeColor != 0 && st.strokeWidth > 0f) Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = applyAlpha(st.strokeColor, st.strokeAlpha)
            strokeWidth = st.strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        } else null
        return Shape(path, fillPaint, strokePaint, gradient)
    }

    private fun draw(doc: Doc, outW: Int, outH: Int): Bitmap {
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT)
        val sx = outW / doc.viewW
        val sy = outH / doc.viewH
        canvas.scale(sx, sy)
        for (s in doc.shapes) {
            val g = s.gradient
            if (g != null && s.fill != null) {
                val shader = gradientShader(g, doc.viewW, doc.viewH)
                if (shader != null) s.fill.shader = shader
            }
            s.fill?.let { canvas.drawPath(s.path, it) }
            s.stroke?.let { canvas.drawPath(s.path, it) }
        }
        return bmp
    }

    private fun gradientShader(g: GradientPaint, w: Float, h: Float): Shader? {
        if (g.stops.isEmpty()) return null
        val colors = IntArray(g.stops.size)
        val pos = FloatArray(g.stops.size)
        for ((i, s) in g.stops.withIndex()) { pos[i] = s.first; colors[i] = s.second }
        return try {
            if (g.radial) {
                val r = if (g.radius > 0f) g.radius * maxOf(w, h) else maxOf(w, h) * 0.5f
                android.graphics.RadialGradient(g.x1 * w, g.y1 * h, max(1f, r), colors, pos, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(
                    g.x1 * w, g.y1 * h, (g.x2) * w, (g.y2) * h, colors, pos, Shader.TileMode.CLAMP
                )
            }
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------ path building

    private fun pathFor(parser: XmlPullParser, tag: String, path: Path) {
        when (tag) {
            "path" -> parsePathData(parser.getAttributeValue(null, "d") ?: return, path)
            "rect" -> {
                val x = length(parser.getAttributeValue(null, "x"), 0f)
                val y = length(parser.getAttributeValue(null, "y"), 0f)
                val w = length(parser.getAttributeValue(null, "width"), 0f)
                val h = length(parser.getAttributeValue(null, "height"), 0f)
                val rx = length(parser.getAttributeValue(null, "rx"), 0f)
                val ry = length(parser.getAttributeValue(null, "ry"), rx)
                if (rx > 0f || ry > 0f) path.addRoundRect(RectF(x, y, x + w, y + h), rx, ry, Path.Direction.CW)
                else path.addRect(x, y, x + w, y + h, Path.Direction.CW)
            }
            "circle" -> {
                val cx = length(parser.getAttributeValue(null, "cx"), 0f)
                val cy = length(parser.getAttributeValue(null, "cy"), 0f)
                val r = length(parser.getAttributeValue(null, "r"), 0f)
                path.addCircle(cx, cy, r, Path.Direction.CW)
            }
            "ellipse" -> {
                val cx = length(parser.getAttributeValue(null, "cx"), 0f)
                val cy = length(parser.getAttributeValue(null, "cy"), 0f)
                val rx = length(parser.getAttributeValue(null, "rx"), 0f)
                val ry = length(parser.getAttributeValue(null, "ry"), 0f)
                val r = RectF(cx - rx, cy - ry, cx + rx, cy + ry)
                path.addOval(r, Path.Direction.CW)
            }
            "line" -> {
                path.moveTo(length(parser.getAttributeValue(null, "x1"), 0f), length(parser.getAttributeValue(null, "y1"), 0f))
                path.lineTo(length(parser.getAttributeValue(null, "x2"), 0f), length(parser.getAttributeValue(null, "y2"), 0f))
            }
            "polyline", "polygon" -> {
                val pts = (parser.getAttributeValue(null, "points") ?: return).trim()
                    .split(Regex("[\\s,]+")).mapNotNull { it.toFloatOrNull() }
                if (pts.size >= 4) {
                    path.moveTo(pts[0], pts[1])
                    var i = 2
                    while (i + 1 < pts.size) { path.lineTo(pts[i], pts[i + 1]); i += 2 }
                    if (tag == "polygon") path.close()
                }
            }
        }
    }

    /** Parses an SVG path `d` attribute. Format is forgiving: numbers may be separated by spaces or commas. */
    fun parsePathData(d: String, path: Path) {
        val tokens = tokenize(d)
        var i = 0
        var cx = 0f; var cy = 0f
        var startX = 0f; var startY = 0f
        var lastCtrlX = 0f; var lastCtrlY = 0f
        var cmd = ' '
        var prevCmd = ' '
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]; i++
                if (cmd == 'Z' || cmd == 'z') { path.close(); cx = startX; cy = startY; prevCmd = cmd; continue }
            } else if (cmd == 'M') {
                cmd = 'L'
            } else if (cmd == 'm') {
                cmd = 'l'
            }
            fun num(): Float = tokens.getOrNull(i++)?.toFloatOrNull() ?: 0f
            when (cmd) {
                'M' -> { cx = num(); cy = num(); path.moveTo(cx, cy); startX = cx; startY = cy }
                'm' -> { cx += num(); cy += num(); path.moveTo(cx, cy); startX = cx; startY = cy }
                'L' -> { cx = num(); cy = num(); path.lineTo(cx, cy) }
                'l' -> { cx += num(); cy += num(); path.lineTo(cx, cy) }
                'H' -> { cx = num(); path.lineTo(cx, cy) }
                'h' -> { cx += num(); path.lineTo(cx, cy) }
                'V' -> { cy = num(); path.lineTo(cx, cy) }
                'v' -> { cy += num(); path.lineTo(cx, cy) }
                'C' -> {
                    val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num()
                    val x = num(); val y = num()
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y
                }
                'c' -> {
                    val x1 = cx + num(); val y1 = cy + num(); val x2 = cx + num(); val y2 = cy + num()
                    val x = cx + num(); val y = cy + num()
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y
                }
                'S' -> {
                    val x2 = num(); val y2 = num(); val x = num(); val y = num()
                    val x1 = if (prevCmd == 'C' || prevCmd == 'c' || prevCmd == 'S' || prevCmd == 's') 2 * cx - lastCtrlX else cx
                    val y1 = if (prevCmd == 'C' || prevCmd == 'c' || prevCmd == 'S' || prevCmd == 's') 2 * cy - lastCtrlY else cy
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y
                }
                's' -> {
                    val x2 = cx + num(); val y2 = cy + num(); val x = cx + num(); val y = cy + num()
                    val x1 = if (prevCmd == 'C' || prevCmd == 'c' || prevCmd == 'S' || prevCmd == 's') 2 * cx - lastCtrlX else cx
                    val y1 = if (prevCmd == 'C' || prevCmd == 'c' || prevCmd == 'S' || prevCmd == 's') 2 * cy - lastCtrlY else cy
                    path.cubicTo(x1, y1, x2, y2, x, y)
                    lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y
                }
                'Q' -> {
                    val qx = num(); val qy = num(); val x = num(); val y = num()
                    path.quadTo(qx, qy, x, y)
                    lastCtrlX = qx; lastCtrlY = qy; cx = x; cy = y
                }
                'q' -> {
                    val qx = cx + num(); val qy = cy + num(); val x = cx + num(); val y = cy + num()
                    path.quadTo(qx, qy, x, y)
                    lastCtrlX = qx; lastCtrlY = qy; cx = x; cy = y
                }
                'T' -> {
                    val x = num(); val y = num()
                    val qx = if (prevCmd == 'Q' || prevCmd == 'q' || prevCmd == 'T' || prevCmd == 't') 2 * cx - lastCtrlX else cx
                    val qy = if (prevCmd == 'Q' || prevCmd == 'q' || prevCmd == 'T' || prevCmd == 't') 2 * cy - lastCtrlY else cy
                    path.quadTo(qx, qy, x, y)
                    lastCtrlX = qx; lastCtrlY = qy; cx = x; cy = y
                }
                't' -> {
                    val x = cx + num(); val y = cy + num()
                    val qx = if (prevCmd == 'Q' || prevCmd == 'q' || prevCmd == 'T' || prevCmd == 't') 2 * cx - lastCtrlX else cx
                    val qy = if (prevCmd == 'Q' || prevCmd == 'q' || prevCmd == 'T' || prevCmd == 't') 2 * cy - lastCtrlY else cy
                    path.quadTo(qx, qy, x, y)
                    lastCtrlX = qx; lastCtrlY = qy; cx = x; cy = y
                }
                'A', 'a' -> {
                    val rx = num(); val ry = num(); val rot = num()
                    val large = num() != 0f; val sweep = num() != 0f
                    var x = num(); var y = num()
                    if (cmd == 'a') { x += cx; y += cy }
                    arcTo(path, cx, cy, rx, ry, rot, large, sweep, x, y)
                    cx = x; cy = y
                }
                else -> { i++ }
            }
            prevCmd = cmd
        }
    }

    /** Elliptical arc -> cubic segments (endpoint parameterisation per SVG spec, section F.6). */
    private fun arcTo(path: Path, x1: Float, y1: Float, rx0: Float, ry0: Float, rotDeg: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float) {
        var rx = abs(rx0); var ry = abs(ry0)
        if (rx < 1e-6f || ry < 1e-6f) { path.lineTo(x2, y2); return }
        val phi = Math.toRadians(rotDeg.toDouble())
        val cosPhi = cos(phi).toFloat(); val sinPhi = sin(phi).toFloat()
        val dx2 = (x1 - x2) / 2f; val dy2 = (y1 - y2) / 2f
        val x1p = cosPhi * dx2 + sinPhi * dy2
        val y1p = -sinPhi * dx2 + cosPhi * dy2
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1f) { val s = sqrt(lambda); rx *= s; ry *= s }
        val sign = if (large != sweep) 1f else -1f
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val co = if (den <= 0f) 0f else sign * sqrt(max(0f, num / den))
        val cxp = co * rx * y1p / ry
        val cyp = -co * ry * x1p / rx
        val cx = cosPhi * cxp - sinPhi * cyp + (x1 + x2) / 2f
        val cy = sinPhi * cxp + cosPhi * cyp + (y1 + y2) / 2f
        fun angle(ux: Float, uy: Float, vx: Float, vy: Float): Float {
            val dot = ux * vx + uy * vy
            val len = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
            if (len <= 0f) return 0f
            var a = Math.toDegrees(kotlin.math.acos((dot / len).coerceIn(-1f, 1f).toDouble())).toFloat()
            if (ux * vy - uy * vx < 0f) a = -a
            return a
        }
        val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
        val startAngle = angle(1f, 0f, ux, uy)
        var delta = angle(ux, uy, vx, vy)
        if (!sweep && delta > 0f) delta -= 360f
        if (sweep && delta < 0f) delta += 360f
        val segments = max(1, (abs(delta) / 90f).toInt() + 1)
        val step = delta / segments
        var a = Math.toRadians(startAngle.toDouble()).toFloat()
        val stepRad = Math.toRadians(step.toDouble()).toFloat()
        val k = 4f / 3f * kotlin.math.tan(stepRad / 4.0).toFloat()
        var px = x1; var py = y1
        for (s in 0 until segments) {
            val a2 = a + stepRad
            val cosA = cos(a); val sinA = sin(a)
            val cosA2 = cos(a2); val sinA2 = sin(a2)
            val ex = cx + cosPhi * rx * cosA2 - sinPhi * ry * sinA2
            val ey = cy + sinPhi * rx * cosA2 + cosPhi * ry * sinA2
            val c1x = px + k * (-cosPhi * rx * sinA - sinPhi * ry * cosA)
            val c1y = py + k * (-sinPhi * rx * sinA + cosPhi * ry * cosA)
            val c2x = ex - k * (-cosPhi * rx * sinA2 - sinPhi * ry * cosA2)
            val c2y = ey - k * (-sinPhi * rx * sinA2 + cosPhi * ry * cosA2)
            path.cubicTo(c1x, c1y, c2x, c2y, ex, ey)
            px = ex; py = ey; a = a2
        }
    }

    private fun tokenize(d: String): List<String> {
        val out = ArrayList<String>(d.length / 2)
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) } }
        var i = 0
        while (i < d.length) {
            val c = d[i]
            when {
                c.isLetter() -> { flush(); out.add(c.toString()) }
                c == ' ' || c == ',' || c == '\n' || c == '\r' || c == '\t' -> flush()
                (c == '-' || c == '+') && sb.isNotEmpty() && sb.last() != 'e' && sb.last() != 'E' -> { flush(); sb.append(c) }
                else -> sb.append(c)
            }
            i++
        }
        flush()
        return out
    }

    // ------------------------------------------------------------------ transforms & helpers

    private fun parseMatrix(transform: String?): FloatArray? {
        if (transform.isNullOrBlank()) return null
        var m = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        val regex = Regex("(matrix|translate|scale|rotate|skewX|skewY)\\s*\\(([^)]*)\\)")
        for (match in regex.findAll(transform)) {
            val args = match.groupValues[2].split(Regex("[\\s,]+")).filter { it.isNotBlank() }.mapNotNull { it.toFloatOrNull() }
            val local = when (match.groupValues[1]) {
                "matrix" -> if (args.size >= 6) floatArrayOf(args[0], args[1], args[2], args[3], args[4], args[5]) else null
                "translate" -> floatArrayOf(1f, 0f, 0f, 1f, args.getOrElse(0) { 0f }, args.getOrElse(1) { 0f })
                "scale" -> {
                    val sx = args.getOrElse(0) { 1f }
                    floatArrayOf(sx, 0f, 0f, args.getOrElse(1) { sx }, 0f, 0f)
                }
                "rotate" -> {
                    val a = Math.toRadians(args.getOrElse(0) { 0f }.toDouble()).toFloat()
                    val c = cos(a); val s = sin(a)
                    floatArrayOf(c, s, -s, c, 0f, 0f)
                }
                "skewX" -> floatArrayOf(1f, 0f, kotlin.math.tan(Math.toRadians(args.getOrElse(0) { 0f }.toDouble())).toFloat(), 1f, 0f, 0f)
                "skewY" -> floatArrayOf(1f, kotlin.math.tan(Math.toRadians(args.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f, 1f, 0f, 0f)
                else -> null
            }
            if (local != null) m = mul(m, local)
        }
        return m
    }

    private fun compose(parent: FloatArray, transform: String?): FloatArray {
        val local = parseMatrix(transform) ?: return parent
        return mul(parent, local)
    }

    /** [a,b,c,d,e,f] with (x,y) -> (a*x + c*y + e, b*x + d*y + f). */
    private fun mul(m1: FloatArray, m2: FloatArray) = floatArrayOf(
        m1[0] * m2[0] + m1[2] * m2[1],
        m1[1] * m2[0] + m1[3] * m2[1],
        m1[0] * m2[2] + m1[2] * m2[3],
        m1[1] * m2[2] + m1[3] * m2[3],
        m1[0] * m2[4] + m1[2] * m2[5] + m1[4],
        m1[1] * m2[4] + m1[3] * m2[5] + m1[5]
    )

    private fun matrixOf(m: FloatArray) = android.graphics.Matrix().apply {
        setValues(floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f))
    }

    private fun length(value: String?, def: Float): Float {
        if (value.isNullOrBlank()) return def
        val v = value.trim().lowercase()
        return when {
            v.endsWith("px") -> v.dropLast(2).toFloatOrNull() ?: def
            v.endsWith("%") -> def
            v.endsWith("pt") -> (v.dropLast(2).toFloatOrNull() ?: def) * 1.333f
            else -> v.toFloatOrNull() ?: def
        }
    }

    private fun applyAlpha(color: Int, alpha: Float): Int {
        val a = ((color ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)
        return (color and 0x00FFFFFF) or (a.toInt().coerceIn(0, 255) shl 24)
    }

    private val namedColors = mapOf(
        "black" to 0xFF000000.toInt(), "white" to 0xFFFFFFFF.toInt(), "red" to 0xFFFF0000.toInt(),
        "green" to 0xFF00FF00.toInt(), "blue" to 0xFF0000FF.toInt(), "yellow" to 0xFFFFFF00.toInt(),
        "cyan" to 0xFF00FFFF.toInt(), "magenta" to 0xFFFF00FF.toInt(), "gray" to 0xFF808080.toInt(),
        "grey" to 0xFF808080.toInt(), "orange" to 0xFFFFA500.toInt(), "purple" to 0xFF800080.toInt(),
        "pink" to 0xFFFFC0CB.toInt(), "brown" to 0xFFA52A2A.toInt(), "lime" to 0xFF00FF00.toInt(),
        "navy" to 0xFF000080.toInt(), "teal" to 0xFF008080.toInt(), "gold" to 0xFFFFD700.toInt(),
        "silver" to 0xFFC0C0C0.toInt(), "transparent" to 0
    )

    fun parseColor(value: String?, def: Int): Int {
        if (value.isNullOrBlank()) return def
        val v = value.trim()
        if (v.startsWith("#")) {
            val hex = v.substring(1)
            return when (hex.length) {
                3 -> {
                    val r = hex[0].digitToIntOrNull(16) ?: return def
                    val g = hex[1].digitToIntOrNull(16) ?: return def
                    val b = hex[2].digitToIntOrNull(16) ?: return def
                    0xFF000000.toInt() or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
                }
                6 -> (0xFF000000.toInt() or hex.toIntOrNull(16)!!)
                8 -> hex.toLongOrNull(16)?.toInt() ?: def
                else -> def
            }
        }
        if (v.startsWith("rgb")) {
            val nums = v.substringAfter('(').substringBefore(')').split(',').mapNotNull { it.trim().toFloatOrNull() }
            if (nums.size >= 3) {
                val a = if (nums.size >= 4) (nums[3] * 255).toInt() else 255
                return (a shl 24) or (nums[0].toInt() shl 16) or (nums[1].toInt() shl 8) or nums[2].toInt()
            }
            return def
        }
        return namedColors[v.lowercase()] ?: def
    }
}

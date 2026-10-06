package com.sengine.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

/**
 * Editor design system: colors, spacing, and factory helpers that build the whole IDE UI out of
 * plain Android widgets (no support library). Every helper returns a real, interactive view.
 */
object Dp {
    fun Context.dp(v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}

object C {
    const val BG = 0xFF1E1F22.toInt()
    const val PANEL = 0xFF2B2D31.toInt()
    const val PANEL2 = 0xFF383A40.toInt()
    const val HEADER = 0xFF232428.toInt()
    const val FIELD = 0xFF1A1B1E.toInt()
    const val ACCENT = 0xFF4C8DFF.toInt()
    const val TEXT = 0xFFE6E6E6.toInt()
    const val DIM = 0xFF9AA0A6.toInt()
    const val SEL = 0xFF34507F.toInt()
    const val RED = 0xFFE5534B.toInt()
    const val GREEN = 0xFF57AB5A.toInt()
    const val YELLOW = 0xFFE0B341.toInt()
    const val BORDER = 0xFF3A3C42.toInt()
    const val PAD = 8
}

fun Context.dp(v: Number): Int = Dp.run { dp(v) }

fun round(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke > 0) setStroke(stroke, strokeColor)
    }

fun ripple(color: Int, radius: Float): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), round(color, radius), null)

// ---------------------------------------------------------------- text & buttons

fun Context.label(text: String, size: Float = 13f, color: Int = C.TEXT, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

fun Context.title(text: String): TextView = label(text, 15f, C.TEXT, bold = true).apply {
    setPadding(dp(10), dp(8), dp(10), dp(8))
}

/** Flat rounded button. */
fun Context.button(text: String, color: Int = C.PANEL2, textColor: Int = C.TEXT, onClick: (View) -> Unit): TextView =
    TextView(this).apply {
        this.text = text
        setTextColor(textColor)
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(7), dp(12), dp(7))
        minWidth = dp(40)
        background = ripple(color, dp(6).toFloat())
        isClickable = true
        isFocusable = true
        setOnClickListener(onClick)
    }

/** Square toolbar button with a text glyph (vector-free icon set). */
fun Context.toolButton(glyph: String, tooltip: String, active: Boolean = false, onClick: (View) -> Unit): TextView =
    TextView(this).apply {
        text = glyph
        setTextColor(if (active) 0xFFFFFFFF.toInt() else C.TEXT)
        textSize = 16f
        gravity = Gravity.CENTER
        background = ripple(if (active) C.ACCENT else 0x00000000, dp(6).toFloat())
        isClickable = true
        contentDescription = tooltip
        setOnClickListener(onClick)
        layoutParams = LinearLayout.LayoutParams(dp(38), dp(36))
    }

fun TextView.setButtonColor(color: Int) {
    background = ripple(color, context.dp(6).toFloat())
}

fun TextView.setActive(active: Boolean) {
    setTextColor(if (active) 0xFFFFFFFF.toInt() else C.TEXT)
    background = ripple(if (active) C.ACCENT else 0x00000000, context.dp(6).toFloat())
}

fun Context.field(value: String, numeric: Boolean = false, multiline: Boolean = false): EditText =
    EditText(this).apply {
        setText(value)
        setTextColor(C.TEXT)
        textSize = 13f
        setPadding(dp(6), dp(4), dp(6), dp(4))
        background = round(C.FIELD, dp(4).toFloat(), 1, C.BORDER)
        inputType = when {
            numeric -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        if (!multiline) {
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        setSelectAllOnFocus(numeric)
    }

// ---------------------------------------------------------------- layout helpers

fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun LinearLayout.LayoutParams.margins(l: Int, t: Int, r: Int, b: Int) = apply { setMargins(l, t, r, b) }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.card(padding: Int = C.PAD): LinearLayout = vbox().apply {
    background = round(C.PANEL, dp(8).toFloat(), 1, 0xFF33363C.toInt())
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.scroller(child: View, horizontal: Boolean = false): ViewGroup =
    if (horizontal) HorizontalScrollView(this).apply {
        isFillViewport = true
        addView(child, FrameLayout.LayoutParams(WRAP, MATCH))
    } else ScrollView(this).apply {
        isFillViewport = true
        addView(child, FrameLayout.LayoutParams(MATCH, WRAP))
    }

fun Context.divider(): View = View(this).apply {
    setBackgroundColor(C.BORDER)
    layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).margins(0, dp(6), 0, dp(6))
}

fun Context.spacer(weight: Float = 1f): Space = Space(this).apply {
    layoutParams = LinearLayout.LayoutParams(0, 1, weight)
}

/** Section header used inside panels. */
fun Context.section(text: String, action: String? = null, onAction: ((View) -> Unit)? = null): LinearLayout =
    hbox().apply {
        background = round(C.HEADER, dp(6).toFloat())
        setPadding(dp(8), dp(6), dp(8), dp(6))
        addView(label(text.uppercase(), 11f, C.DIM, bold = true), lp(0, WRAP, 1f))
        if (action != null && onAction != null) {
            addView(button(action, 0x00000000, C.ACCENT, onAction).apply { setPadding(dp(6), dp(2), dp(6), dp(2)) })
        }
    }

/** Labelled row: label on the left, control on the right. */
fun Context.row(name: String, control: View, tooltip: String = ""): LinearLayout =
    hbox().apply {
        setPadding(0, dp(3), 0, dp(3))
        val l = label(name, 12.5f, C.DIM)
        l.layoutParams = lp(0, WRAP, 0.42f)
        addView(l)
        if (control.layoutParams == null) control.layoutParams = lp(0, WRAP, 0.58f) else addView(control)
        if (control.parent == null) addView(control)
        if (tooltip.isNotBlank()) {
            val info = label("?", 11f, C.DIM)
            info.setPadding(dp(6), 0, 0, 0)
            info.contentDescription = tooltip
            info.setOnClickListener { Toast.makeText(this@row, tooltip, Toast.LENGTH_LONG).show() }
            addView(info)
        }
    }

// ---------------------------------------------------------------- controls

fun Context.numberField(value: Float, step: Float = 0.1f, min: Float = -1e6f, max: Float = 1e6f, onChange: (Float) -> Unit): LinearLayout =
    hbox().apply {
        val edit = field(fmt(value), numeric = true).apply {
            layoutParams = lp(0, WRAP, 1f)
            setOnFocusChangeListener { _, has ->
                if (!has) text.toString().toFloatOrNull()?.let { onChange(it.coerceIn(min, max)) }
            }
            setOnEditorActionListener { _, _, _ ->
                text.toString().toFloatOrNull()?.let { onChange(it.coerceIn(min, max)) }
                false
            }
        }
        addView(edit)
        addView(button("−", 0x00000000) {
            val v = (edit.text.toString().toFloatOrNull() ?: 0f) - step
            edit.setText(fmt(v))
            onChange(v.coerceIn(min, max))
        }.apply { layoutParams = lp(dp(30), WRAP) })
        addView(button("+", 0x00000000) {
            val v = (edit.text.toString().toFloatOrNull() ?: 0f) + step
            edit.setText(fmt(v))
            onChange(v.coerceIn(min, max))
        }.apply { layoutParams = lp(dp(30), WRAP) })
    }

fun Context.intField(value: Int, step: Int = 1, min: Int = -1_000_000, max: Int = 1_000_000, onChange: (Int) -> Unit): LinearLayout =
    hbox().apply {
        val edit = field(value.toString(), numeric = true).apply {
            layoutParams = lp(0, WRAP, 1f)
            setOnFocusChangeListener { _, has ->
                if (!has) text.toString().toIntOrNull()?.let { onChange(it.coerceIn(min, max)) }
            }
        }
        addView(edit)
        addView(button("−", 0x00000000) {
            val v = (edit.text.toString().toIntOrNull() ?: 0) - step
            edit.setText(v.toString())
            onChange(v.coerceIn(min, max))
        }.apply { layoutParams = lp(dp(30), WRAP) })
        addView(button("+", 0x00000000) {
            val v = (edit.text.toString().toIntOrNull() ?: 0) + step
            edit.setText(v.toString())
            onChange(v.coerceIn(min, max))
        }.apply { layoutParams = lp(dp(30), WRAP) })
    }

fun Context.checkField(value: Boolean, onChange: (Boolean) -> Unit): android.widget.CheckBox =
    android.widget.CheckBox(this).apply {
        isChecked = value
        buttonTintList = ColorStateList.valueOf(C.ACCENT)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

fun Context.slider(value: Float, min: Float, max: Float, onChange: (Float) -> Unit): LinearLayout =
    hbox().apply {
        val span = (max - min).coerceAtLeast(0.0001f)
        val bar = SeekBar(this@slider).apply {
            this.max = 1000
            progress = (((value - min) / span) * 1000f).toInt().coerceIn(0, 1000)
            progressTintList = ColorStateList.valueOf(C.ACCENT)
            thumbTintList = ColorStateList.valueOf(C.ACCENT)
            layoutParams = lp(0, WRAP, 1f)
        }
        val valueText = label(fmt(value), 12f, C.TEXT)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                val v = min + span * (p / 1000f)
                valueText.text = fmt(v)
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        addView(bar)
        addView(valueText.apply { layoutParams = lp(dp(46), WRAP()) })
    }

private fun WRAP() = ViewGroup.LayoutParams.WRAP_CONTENT

fun Context.choice(options: List<String>, selected: Int, onChange: (Int) -> Unit): Spinner =
    Spinner(this).apply {
        adapter = ArrayAdapter(this@choice, android.R.layout.simple_spinner_dropdown_item, options)
        setSelection(selected.coerceIn(0, (options.size - 1).coerceAtLeast(0)))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onChange(position)
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        background = round(C.FIELD, dp(4).toFloat(), 1, C.BORDER)
    }

fun Context.colorSwatch(color: Int, onChange: (Int) -> Unit): View =
    View(this).apply {
        background = round(color, dp(5).toFloat(), 1, 0xFF57595E.toInt())
        layoutParams = lp(dp(30), dp(22))
        isClickable = true
        setOnClickListener { ColorPickerDialog(this@colorSwatch, color, onChange).show() }
    }

fun Context.iconButton(glyph: String, onClick: (View) -> Unit): TextView =
    toolButton(glyph, glyph, false, onClick)

/** Small circular badge used by the asset browser and the hierarchy (drawn, no images). */
class BadgeView(context: Context, var text: String = "", var color: Int = C.ACCENT) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = 0xFFFFFFFF.toInt()
        typeface = Typeface.DEFAULT_BOLD
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(width, height) * 0.5f
        paint.color = color
        canvas.drawCircle(width * 0.5f, height * 0.5f, r, paint)
        textPaint.textSize = r * 1.1f
        val fm = textPaint.fontMetrics
        canvas.drawText(text, width * 0.5f, height * 0.5f - (fm.ascent + fm.descent) * 0.5f, textPaint)
    }
}

/** A labelled drop-down that shows the current value and a chevron (used for tools/filters). */
fun Context.dropdown(label: String, options: List<String>, selected: Int, onChange: (Int) -> Unit): TextView =
    this.button("$label ▾", C.PANEL2, C.TEXT) { v ->
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(options.toTypedArray()) { _, which -> onChange(which) }
            .show()
    }.apply { textSize = 12.5f; setPadding(dp(10), dp(6), dp(10), dp(6)) }

// ---------------------------------------------------------------- dialogs

fun Context.inputDialog(title: String, value: String, hint: String = "", multiline: Boolean = false, onOk: (String) -> Unit) {
    val edit = field(value, multiline = multiline)
    val box = vbox().apply {
        setPadding(dp(14), dp(10), dp(14), dp(0))
        if (hint.isNotBlank()) addView(label(hint, 11.5f, C.DIM).apply { setPadding(0, 0, 0, dp(6)) })
        addView(edit)
    }
    AlertDialog.Builder(this)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("OK") { _, _ -> onOk(edit.text.toString()) }
        .setNegativeButton("Cancel", null)
        .show()
}

fun Context.confirmDialog(title: String, message: String, onYes: () -> Unit) {
    AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("OK") { _, _ -> onYes() }
        .setNegativeButton("Cancel", null)
        .show()
}

fun Context.chooseDialog(title: String, options: List<String>, onPick: (Int) -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setItems(options.toTypedArray()) { _, which -> onPick(which) }.show()
}

fun Context.toast(message: String, long: Boolean = false) {
    Toast.makeText(this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
}

fun Activity.hideBars() {
    window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
}

fun fmt(v: Float): String {
    if (v == Math.round(v).toFloat() && kotlin.math.abs(v) < 1e7) return Math.round(v).toString()
    return String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}

/** Simple click-ripple list row used by list-based panels (hierarchy, assets, scenes). */
class RowView(context: Context, val titleText: String, val subtitle: String, val glyph: String, val glyphColor: Int = C.ACCENT) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    val title = TextView(context).apply {
        text = titleText
        setTextColor(C.TEXT)
        textSize = 13f
        includeFontPadding = false
    }
    val subtitleView = TextView(context).apply {
        text = subtitle
        setTextColor(C.DIM)
        textSize = 11f
        includeFontPadding = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            p.color = 0x33FFFFFF
            invalidate()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            p.color = 0
            invalidate()
            performClick()
        }
        return true
    }
}

/** Creates an ImageView tinted with the accent color for a generated bitmap. */
fun Context.imageView(bitmap: android.graphics.Bitmap): ImageView = ImageView(this).apply {
    setImageBitmap(bitmap)
    adjustViewBounds = true
}

fun Context.pixelCard(size: Int = 56): LinearLayout = vbox().apply {
    background = round(C.PANEL2, dp(8).toFloat(), 1, C.BORDER)
    setPadding(dp(6), dp(6), dp(6), dp(6))
    layoutParams = lp(size + dp(16), MATCH)
    gravity = Gravity.CENTER
}

/** Escapes a value for embedding in JSON / script source. */
fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

/** Blends two ARGB colors (used for hover/selection shading). */
fun blend(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).toInt().coerceIn(0, 255)
    return Color.argb(ch(24), ch(16), ch(8), ch(0))
}

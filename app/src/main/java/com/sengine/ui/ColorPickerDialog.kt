package com.sengine.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import com.sengine.engine.math.Colors

/**
 * HSV color picker with alpha, a live preview swatch and a hex field.
 * Bound to `Prop.Color` in the inspector and to theme editing in project settings.
 */
class ColorPickerDialog(
    private val context: Context,
    initial: Int,
    private val onPick: (Int) -> Unit
) {

    private var alpha = Color.alpha(initial) / 255f
    private var hue = 0f
    private var sat = 0f
    private var value = 0f
    private var hex: EditText? = null
    private var preview: View? = null

    init {
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
    }

    private fun current(): Int = Color.HSVToColor((alpha * 255f).toInt().coerceIn(0, 255), floatArrayOf(hue, sat, value))

    fun show() {
        val box = context.vbox().apply {
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(4))
        }
        preview = View(context).apply {
            background = round(current(), context.dp(8).toFloat(), 1, C.BORDER)
            layoutParams = lp(MATCH, context.dp(44))
        }
        box.addView(preview)

        var hexField: EditText? = null
        fun refresh() {
            val c = current()
            (preview?.background as? android.graphics.drawable.GradientDrawable)?.setColor(c)
            hexField?.setText(Colors.toHex(c))
        }

        fun sliderRow(name: String, initialProgress: Int, onChange: (Int) -> Unit): LinearLayout {
            val bar = SeekBar(context).apply {
                max = 1000
                progress = initialProgress
                progressTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
                thumbTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
            }
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    onChange(p)
                    refresh()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
            return context.hbox().apply {
                setPadding(0, context.dp(4), 0, 0)
                addView(context.label(name, 12f, C.DIM), lp(context.dp(56), WRAP))
                addView(bar, lp(0, WRAP, 1f))
            }
        }

        box.addView(sliderRow("Hue", (hue / 360f * 1000f).toInt()) { hue = it / 1000f * 360f })
        box.addView(sliderRow("Saturation", (sat * 1000f).toInt()) { sat = it / 1000f })
        box.addView(sliderRow("Brightness", (value * 1000f).toInt()) { value = it / 1000f })
        box.addView(sliderRow("Alpha", (alpha * 1000f).toInt()) { alpha = it / 1000f })

        hexField = context.field(Colors.toHex(current())).apply {
            layoutParams = lp(0, WRAP, 1f)
            setOnFocusChangeListener { _, has ->
                if (!has) parseHex(text.toString())
            }
            setOnEditorActionListener { _, _, _ -> parseHex(text.toString()); false }
        }
        box.addView(context.hbox().apply {
            setPadding(0, context.dp(6), 0, 0)
            addView(context.label("Hex", 12f, C.DIM), lp(context.dp(56), WRAP))
            addView(hexField!!)
        })

        // preset swatches for fast access to the engine palette
        val presets = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF4C8DFF.toInt(), 0xFF57AB5A.toInt(),
            0xFFE5534B.toInt(), 0xFFE0B341.toInt(), 0xFFFF8A3D.toInt(), 0xFFB36BE0.toInt(),
            0xFF25C2C2.toInt(), 0xFF9AA0A6.toInt(), 0xFF2B2D31.toInt(), 0x00000000
        )
        val row = context.hbox().apply {
            setPadding(0, context.dp(8), 0, 0)
            gravity = Gravity.START
        }
        for (p in presets) {
            row.addView(View(context).apply {
                background = round(p, context.dp(4).toFloat(), 1, C.BORDER)
                layoutParams = lp(context.dp(24), context.dp(24)).margins(0, 0, context.dp(6), 0)
                isClickable = true
                setOnClickListener {
                    val hsv = FloatArray(3)
                    Color.colorToHSV(p, hsv)
                    hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                    alpha = Color.alpha(p) / 255f
                    refresh()
                }
            })
        }
        box.addView(row)

        AlertDialog.Builder(context)
            .setTitle("Color")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> onPick(current()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parseHex(raw: String) {
        val c = Colors.parse(raw.trim(), current())
        val hsv = FloatArray(3)
        Color.colorToHSV(c, hsv)
        hue = hsv[0]; sat = hsv[1]; value = hsv[2]
        alpha = Color.alpha(c) / 255f
        preview?.background = round(c, context.dp(8).toFloat(), 1, C.BORDER)
    }

    companion object {
        fun pick(context: Context, initial: Int, onPick: (Int) -> Unit) = ColorPickerDialog(context, initial, onPick).show()
    }
}

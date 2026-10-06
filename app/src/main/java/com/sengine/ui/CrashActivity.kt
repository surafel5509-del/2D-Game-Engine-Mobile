package com.sengine.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class CrashActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorText = intent.getStringExtra("error") ?: "Unknown error"

        val root = vbox().apply {
            setBackgroundColor(C.BG)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val header = hbox().apply {
            setBackgroundColor(C.HEADER)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        header.addView(label("⚠ S Engine Crash Report", 17f, C.RED, bold = true))
        root.addView(header, lp(MATCH, WRAP))

        root.addView(label("The application encountered an unexpected error. You can copy the diagnostic details below or restart the app.", 12.5f, C.DIM).apply {
            setPadding(dp(4), dp(12), dp(4), dp(10))
        }, lp(MATCH, WRAP))

        val scroll = ScrollView(this).apply {
            background = round(C.PANEL, dp(6).toFloat())
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val content = TextView(this).apply {
            text = errorText
            setTextColor(C.TEXT)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scroll.addView(content)
        root.addView(scroll, lp(MATCH, 0, 1f))

        val actions = hbox().apply {
            setPadding(0, dp(12), 0, 0)
        }
        actions.addView(button("📋 Copy Details", C.PANEL2, C.TEXT) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("S Engine Error", errorText))
            Toast.makeText(this, "Copied error to clipboard", Toast.LENGTH_SHORT).show()
        }, lp(0, WRAP, 1f))

        actions.addView(button("🔄 Restart S Engine", C.ACCENT, 0xFFFFFFFF.toInt()) {
            val intent = Intent(this, ProjectsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
            finish()
        }.apply {
            layoutParams = lp(0, WRAP, 1f).margins(dp(8), 0, 0, 0)
        })

        actions.addView(button("✕ Close", C.PANEL2, C.DIM) {
            finish()
        }.apply {
            layoutParams = lp(WRAP, WRAP).margins(dp(8), 0, 0, 0)
        })

        root.addView(actions, lp(MATCH, WRAP))
        setContentView(root)
    }
}

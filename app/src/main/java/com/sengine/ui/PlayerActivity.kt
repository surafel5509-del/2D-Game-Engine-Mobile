package com.sengine.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.TextView
import com.sengine.engine.Engine
import com.sengine.engine.render.SceneRenderer
import com.sengine.export.GameRuntime
import com.sengine.project.Project
import com.sengine.project.ProjectManager
import java.io.File

/**
 * Runs a project full screen, exactly like an exported game: engine loop, virtual controls,
 * gamepad/keyboard input, pause menu, FPS overlay and lifecycle handling.
 *
 * Used by the editor's Play button (with an FPS badge and a close button) and by exported games
 * (standalone mode, no overlay).
 */
class PlayerActivity : Activity() {

    private lateinit var engine: Engine
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: SceneRenderer
    private lateinit var controls: GameControlsView
    private var fpsText: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    private var standalone = false
    private var paused = false

    private val fpsTick = object : Runnable {
        override fun run() {
            fpsText?.text = "${engine.fps.toInt()} FPS · ${engine.rigidBodyCount} bodies · ${engine.activeParticles} particles"
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        standalone = intent.getBooleanExtra("standalone", false)
        val project = resolveProject() ?: run { finish(); return }
        try {
            requestedOrientation = if (project.orientation == 1) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } catch (_: Throwable) {}
        val sceneName = intent.getStringExtra("scene") ?: project.startScene
        val scene = if (project.sceneExists(sceneName)) project.loadScene(sceneName) else project.loadScene(project.listScenes().firstOrNull() ?: "Main")

        engine = Engine(project, scene)
        engine.clipLoader = { com.sengine.project.ClipLibrary.clip(project, it) }
        engine.listeners.add(object : Engine.Listener {
            override fun onLog(level: Int, message: String) {
                if (level >= 2) handler.post { toast(message) }
            }
        })

        val root = FrameLayout(this)
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            renderer = SceneRenderer.forEngine(engine, null)
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl: javax.microedition.khronos.opengles.GL10?, config: javax.microedition.khronos.egl.EGLConfig?) {
                    renderer.initGl()
                    engine.audio.attachContext(this@PlayerActivity)
                }

                override fun onSurfaceChanged(gl: javax.microedition.khronos.opengles.GL10?, width: Int, height: Int) {
                    GLES20.glViewport(0, 0, width, height)
                    renderer.resize(width, height)
                    engine.input.setScreenSize(width.toFloat(), height.toFloat())
                }

                override fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
                    val dt = engine.frameDelta()
                    synchronized(engine.lock) {
                        engine.updateGameView()
                        engine.tick(dt)
                        renderer.render(engine.scene, dt, engine.backgroundColor(), drawUi = true)
                        engine.drawCalls = renderer.renderer.stats.drawCalls
                        engine.renderMs = renderer.renderer.frameMilliseconds
                    }
                }
            })
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glView.setOnTouchListener { _, e -> engine.input.handleTouchEvent(e) }
        root.addView(glView, FrameLayout.LayoutParams(MATCH, MATCH))
        controls = GameControlsView(this) { engine.input }
        root.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))

        if (!standalone) {
            fpsText = label("", 11f, 0x99FFFFFF.toInt()).apply { setPadding(dp(10), dp(6), 0, 0) }
            root.addView(fpsText, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
            root.addView(button("✕", 0x55000000) { finish() },
                FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(8), 0) })
            root.addView(button("❚❚", 0x55000000) { togglePause() },
                FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(8), dp(56), 0) })
        }
        setContentView(root)
        hideSystemUi()
        engine.play()
        engine.audio.attachContext(this)
    }

    private fun resolveProject(): Project? {
        intent.getStringExtra("projectDir")?.let { return Project(File(it)) }
        if (standalone || intent.getBooleanExtra("embedded", false)) {
            GameRuntime.standaloneProject(this)?.let { return it }
        }
        val name = intent.getStringExtra("project") ?: return null
        return ProjectManager.open(this, name)
    }

    private fun togglePause() {
        paused = !paused
        if (paused) {
            engine.pause()
            engine.audio.pauseAll()
            controls.reset()
        } else {
            engine.resume()
            engine.audio.resumeAll()
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ---------------------------------------------------------------- input plumbing
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        engine.input.handleKeyEvent(event ?: KeyEvent(KeyEvent.ACTION_DOWN, keyCode), true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyUp(keyCode, event)
        engine.input.handleKeyEvent(event ?: KeyEvent(KeyEvent.ACTION_UP, keyCode), false)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            return engine.input.handleGenericMotion(event)
        }
        return super.onGenericMotionEvent(event)
    }

    // ---------------------------------------------------------------- lifecycle
    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (!paused) engine.audio.resumeAll()
        handler.post(fpsTick)
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        glView.onPause()
        engine.audio.pauseAll()
        controls.reset()
        handler.removeCallbacks(fpsTick)
    }

    override fun onStop() {
        super.onStop()
        if (!standalone) engine.audio.pauseAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::engine.isInitialized) {
            synchronized(engine.lock) { engine.release() }
        }
        if (::renderer.isInitialized) renderer.releaseGl()
    }

    private fun toast(message: String) = android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
}

package com.sengine.engine.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.RhinoException

/**
 * Compiles a script with the same Rhino runtime the game uses, without executing it, so the
 * editor can report real syntax errors with line numbers before the game runs.
 */
object ScriptValidator {

    class Result(val ok: Boolean, val line: Int = -1, val message: String = "")

    fun validate(source: String, name: String = "script.js"): Result {
        if (source.isBlank()) return Result(false, 1, "script is empty")
        var cx: Context? = null
        return try {
            cx = Context.enter()
            cx.optimizationLevel = -1
            cx.languageVersion = Context.VERSION_ES6
            val scope = cx.initStandardObjects()
            cx.evaluateString(scope, ScriptSystem.PRELUDE, "prelude", 1, null)
            cx.compileString(source, name, 1, null)
            Result(true)
        } catch (e: RhinoException) {
            Result(false, e.lineNumber(), e.details())
        } catch (e: Exception) {
            Result(false, -1, e.message ?: "compile failed")
        } finally {
            if (cx != null) try { Context.exit() } catch (_: Exception) {}
        }
    }
}

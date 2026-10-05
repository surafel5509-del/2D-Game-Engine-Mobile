package com.sengine.engine.debug

/**
 * Performance profiler for monitoring engine systems.
 * Tracks frame times, object counts, draw calls, physics, and scripting performance.
 */
class Profiler {
    private val frameTimes = ArrayDeque<Float>()
    private val maxFrameSamples = 120

    // Per-system profiling
    private val markers = HashMap<String, MarkerData>()
    private val stack = ArrayDeque<String>()

    // Stats
    var fps = 0f; private set
    var frameTime = 0f; private set
    var avgFrameTime = 0f; private set
    var minFrameTime = 0f; private set
    var maxFrameTime = 0f; private set
    var objectCount = 0; set
    var drawCalls = 0; set
    var physicsBodies = 0; set
    var physicsContacts = 0; set
    var scriptInstances = 0; set
    var particleCount = 0; set
    var audioSources = 0; set
    var memoryUsageMB = 0f; set

    private var lastFrameNs = 0L

    data class MarkerData(
        val name: String,
        var totalTimeMs: Float = 0f,
        var lastTimeMs: Float = 0f,
        var avgTimeMs: Float = 0f,
        var callCount: Int = 0,
        var samples: ArrayDeque<Float> = ArrayDeque()
    ) {
        val maxSamples = 60
    }

    /** Start a new frame. */
    fun beginFrame() {
        val now = System.nanoTime()
        if (lastFrameNs > 0) {
            frameTime = (now - lastFrameNs) / 1_000_000f
            frameTimes.addLast(frameTime)
            while (frameTimes.size > maxFrameSamples) frameTimes.removeFirst()

            // Calculate FPS and averages
            if (frameTimes.isNotEmpty()) {
                val total = frameTimes.sum()
                avgFrameTime = total / frameTimes.size
                fps = if (avgFrameTime > 0f) 1000f / avgFrameTime else 0f
                minFrameTime = frameTimes.min()
                maxFrameTime = frameTimes.max()
            }

            // Average markers
            for ((_, m) in markers) {
                if (m.callCount > 0) {
                    m.avgTimeMs = m.totalTimeMs / m.callCount
                }
                m.samples.addLast(m.lastTimeMs)
                while (m.samples.size > m.maxSamples) m.samples.removeFirst()
                m.totalTimeMs = 0f
                m.callCount = 0
            }
        }
        lastFrameNs = now

        // Reset per-frame stats
        drawCalls = 0
        physicsContacts = 0
    }

    /** Begin profiling a section. */
    fun begin(name: String) {
        stack.addLast(name)
        val marker = markers.getOrPut(name) { MarkerData(name) }
        marker.lastTimeMs = -System.nanoTime() / 1_000_000f // start
    }

    /** End profiling a section. */
    fun end(name: String) {
        val marker = markers[name] ?: return
        val elapsed = System.nanoTime() / 1_000_000f + marker.lastTimeMs
        marker.lastTimeMs = elapsed
        marker.totalTimeMs += elapsed
        marker.callCount++
        if (stack.isNotEmpty() && stack.last() == name) stack.removeLast()
    }

    /** Get all markers sorted by time. */
    fun getMarkers(): List<MarkerData> = markers.values.sortedByDescending { it.avgTimeMs }

    /** Get a summary report. */
    fun report(): String {
        val sb = StringBuilder()
        sb.appendLine("═══ Performance Report ═══")
        sb.appendLine("FPS: ${"%.1f".format(fps)}  Frame: ${"%.2f".format(frameTime)}ms  Avg: ${"%.2f".format(avgFrameTime)}ms")
        sb.appendLine("Min: ${"%.2f".format(minFrameTime)}ms  Max: ${"%.2f".format(maxFrameTime)}ms")
        sb.appendLine("Objects: $objectCount  Draw Calls: $drawCalls")
        sb.appendLine("Physics Bodies: $physicsBodies  Contacts: $physicsContacts")
        sb.appendLine("Scripts: $scriptInstances  Particles: $particleCount")
        sb.appendLine("Audio Sources: $audioSources  Memory: ${"%.1f".format(memoryUsageMB)}MB")
        sb.appendLine("─── Markers ───")
        for (m in getMarkers()) {
            sb.appendLine("  ${m.name}: avg ${"%.2f".format(m.avgTimeMs)}ms  last ${"%.2f".format(m.lastTimeMs)}ms")
        }
        return sb.toString()
    }

    /** Get a single-line status string for overlay. */
    fun statusLine(): String {
        return "FPS:${"%.0f".format(fps)} | ${"%.1f".format(frameTime)}ms | Obj:$objectCount | Draw:$drawCalls | Phys:$physicsBodies"
    }

    /** Reset all profiling data. */
    fun reset() {
        frameTimes.clear()
        markers.clear()
        stack.clear()
        fps = 0f; frameTime = 0f; avgFrameTime = 0f
        minFrameTime = 0f; maxFrameTime = 0f
        objectCount = 0; drawCalls = 0
        physicsBodies = 0; physicsContacts = 0
        scriptInstances = 0; particleCount = 0
        audioSources = 0; memoryUsageMB = 0f
        lastFrameNs = 0
    }

    /** Estimate memory usage (best-effort). */
    fun estimateMemory(): Float {
        val runtime = Runtime.getRuntime()
        return ((runtime.totalMemory() - runtime.freeMemory()) / 1_048_576f)
    }
}

/**
 * Debug console for in-game debugging.
 * Accepts commands and displays output.
 */
class DebugConsole {
    private val lines = ArrayDeque<ConsoleLine>()
    private val commands = HashMap<String, CommandEntry>()
    val maxLines = 500

    data class ConsoleLine(val text: String, val level: Int = 0, val timestamp: Long = System.currentTimeMillis())
    data class CommandEntry(val name: String, val description: String, val handler: (Array<String>) -> String)

    /** Register a debug command. */
    fun registerCommand(name: String, description: String, handler: (Array<String>) -> String) {
        commands[name] = CommandEntry(name, description, handler)
    }

    /** Execute a command string. */
    fun execute(input: String): String {
        val parts = input.trim().split(" ").filter { it.isNotEmpty() }.toTypedArray()
        if (parts.isEmpty()) return ""
        val cmd = parts[0].lowercase()
        val args = if (parts.size > 1) parts.copyOfRange(1, parts.size) else emptyArray()

        // Built-in commands
        when (cmd) {
            "help" -> {
                val sb = StringBuilder("Available commands:\n")
                for ((name, entry) in commands.entries.sortedBy { it.key }) {
                    sb.appendLine("  $name - ${entry.description}")
                }
                return sb.toString()
            }
            "clear" -> {
                lines.clear()
                return ""
            }
            "list" -> return "Objects: see profiler"
            "fps" -> return "FPS tracking active"
        }

        val entry = commands[cmd] ?: return "Unknown command: $cmd. Type 'help' for list."
        return try {
            entry.handler(args)
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    /** Add a line to the console. */
    fun addLine(text: String, level: Int = 0) {
        lines.addLast(ConsoleLine(text, level))
        while (lines.size > maxLines) lines.removeFirst()
    }

    /** Get all console lines. */
    fun getLines(): List<ConsoleLine> = lines.toList()

    /** Get the last N lines. */
    fun getRecentLines(count: Int = 50): List<ConsoleLine> {
        return lines.toList().takeLast(count)
    }

    /** Clear the console. */
    fun clear() = lines.clear()

    val commandNames: Set<String> get() = commands.keys
}

/**
 * In-game debug overlay.
 * Renders performance stats, object counts, and custom debug info.
 */
class DebugOverlay {
    var enabled = false
    var showFps = true
    var showFrameTime = true
    var showObjectCount = true
    var showPhysicsInfo = true
    var showMemory = false
    var showProfiler = false
    var customText = ""

    fun formatStats(profiler: Profiler): String {
        if (!enabled) return ""
        val sb = StringBuilder()
        if (showFps) sb.append("FPS: ${"%.0f".format(profiler.fps)}\n")
        if (showFrameTime) sb.append("Frame: ${"%.2f".format(profiler.frameTime)}ms\n")
        if (showObjectCount) sb.append("Objects: ${profiler.objectCount}\n")
        if (showPhysicsInfo) {
            sb.append("Physics: ${profiler.physicsBodies} bodies, ${profiler.physicsContacts} contacts\n")
        }
        if (showMemory) sb.append("Memory: ${"%.1f".format(profiler.memoryUsageMB)}MB\n")
        if (customText.isNotEmpty()) sb.append(customText).append("\n")
        if (showProfiler) {
            sb.append("\n--- Profiler ---\n")
            for (m in profiler.getMarkers().take(10)) {
                sb.append("${m.name}: ${"%.2f".format(m.avgTimeMs)}ms\n")
            }
        }
        return sb.toString()
    }
}

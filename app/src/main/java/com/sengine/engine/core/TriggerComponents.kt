package com.sengine.engine.core

/**
 * Trigger zone component for detecting when objects enter/exit areas.
 * Used for checkpoints, cutscenes, area transitions, events, etc.
 */
class TriggerZone : Component() {
    override val type = "TriggerZone"

    var width = 1f
    var height = 1f
    var triggerMode = TriggerMode.ON_ENTER
    var targetTag = ""
    var targetName = ""
    var once = false
    var enabled = true

    // Callbacks
    var onEnterCallback: ((GameObject) -> Unit)? = null
    var onExitCallback: ((GameObject) -> Unit)? = null
    var onStayCallback: ((GameObject) -> Unit)? = null

    // Runtime
    private val triggeredObjects = mutableSetOf<Long>()
    private var hasTriggered = false

    enum class TriggerMode { ON_ENTER, ON_EXIT, ON_STAY, ON_ENTER_AND_EXIT }

    fun checkTrigger(obj: GameObject, isInside: Boolean) {
        if (!enabled || hasTriggered && once) return

        // Check if matches target
        if (targetTag.isNotEmpty() && !obj.tags.contains(targetTag)) return
        if (targetName.isNotEmpty() && obj.name != targetName) return

        val objId = obj.id

        when (triggerMode) {
            TriggerMode.ON_ENTER -> {
                if (isInside && !triggeredObjects.contains(objId)) {
                    triggeredObjects.add(objId)
                    onEnterCallback?.invoke(obj)
                    if (once) hasTriggered = true
                }
            }
            TriggerMode.ON_EXIT -> {
                if (!isInside && triggeredObjects.contains(objId)) {
                    triggeredObjects.remove(objId)
                    onExitCallback?.invoke(obj)
                }
            }
            TriggerMode.ON_STAY -> {
                if (isInside) {
                    onStayCallback?.invoke(obj)
                }
            }
            TriggerMode.ON_ENTER_AND_EXIT -> {
                if (isInside && !triggeredObjects.contains(objId)) {
                    triggeredObjects.add(objId)
                    onEnterCallback?.invoke(obj)
                } else if (!isInside && triggeredObjects.contains(objId)) {
                    triggeredObjects.remove(objId)
                    onExitCallback?.invoke(obj)
                }
            }
        }
    }

    fun isPointInside(px: Float, py: Float, obj: GameObject): Boolean {
        val cx = obj.x + width / 2f
        val cy = obj.y + height / 2f
        return px >= cx - width / 2f && px <= cx + width / 2f &&
               py >= cy - height / 2f && py <= cy + height / 2f
    }

    fun reset() {
        triggeredObjects.clear()
        hasTriggered = false
    }

    override fun props() = listOf(
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.01f) }, 0.1f),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.01f) }, 0.1f),
        Prop.Choice("Mode", listOf("On Enter", "On Exit", "On Stay", "Enter & Exit"), { triggerMode.ordinal }, { triggerMode = TriggerMode.values()[it] }),
        Prop.S("Target Tag", { targetTag }, { targetTag = it }),
        Prop.S("Target Name", { targetName }, { targetName = it }),
        Prop.B("Once", { once }, { once = it }),
        Prop.B("Enabled", { enabled }, { enabled = it }),
    )

    override fun resetRuntime() {
        reset()
    }
}

/**
 * Checkpoint component for save points
 */
class Checkpoint : Component() {
    override val type = "Checkpoint"

    var checkpointId = ""
    var autoSave = true
    var respawnX = 0f
    var respawnY = 0f
    var activated = false

    override fun props() = listOf(
        Prop.S("Checkpoint ID", { checkpointId }, { checkpointId = it }),
        Prop.B("Auto Save", { autoSave }, { autoSave = it }),
        Prop.F("Respawn X", { respawnX }, { respawnX = it }),
        Prop.F("Respawn Y", { respawnY }, { respawnY = it }),
    )

    fun activate() {
        activated = true
    }

    override fun resetRuntime() {
        activated = false
    }
}

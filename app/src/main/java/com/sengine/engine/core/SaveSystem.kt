package com.sengine.engine.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Save/Load system for persisting game state, progress, and settings.
 * Supports multiple save slots and automatic checkpoints.
 */
class SaveSystem(private val saveDirectory: File) {
    private var currentSlot = 0
    private val maxSlots = 10

    init {
        if (!saveDirectory.exists()) {
            saveDirectory.mkdirs()
        }
    }

    /**
     * Save game state to a slot
     */
    fun saveGame(slot: Int = currentSlot, scene: Scene, variables: Map<String, Any> = emptyMap()) {
        val saveData = SaveData()
        saveData.slot = slot
        saveData.timestamp = System.currentTimeMillis()
        saveData.playTime = System.currentTimeMillis() // Would track actual play time

        // Serialize scene
        saveData.sceneData = serializeScene(scene)

        // Serialize variables
        saveData.variables = serializeVariables(variables)

        // Write to file
        val file = getSaveFile(slot)
        file.writeText(saveData.toJson())
    }

    /**
     * Load game state from a slot
     */
    fun loadGame(slot: Int = currentSlot): SaveData? {
        val file = getSaveFile(slot)
        if (!file.exists()) return null

        try {
            val json = file.readText()
            return SaveData.fromJson(json)
        } catch (e: Exception) {
            println("Failed to load save file: ${e.message}")
            return null
        }
    }

    /**
     * Delete a save slot
     */
    fun deleteSave(slot: Int) {
        val file = getSaveFile(slot)
        if (file.exists()) {
            file.delete()
        }
    }

    /**
     * Check if a save slot exists
     */
    fun hasSave(slot: Int): Boolean {
        return getSaveFile(slot).exists()
    }

    /**
     * Get info about a save slot without loading full data
     */
    fun getSaveInfo(slot: Int): SaveInfo? {
        val file = getSaveFile(slot)
        if (!file.exists()) return null

        try {
            val json = JSONObject(file.readText())
            return SaveInfo(
                slot = slot,
                timestamp = json.getLong("timestamp"),
                playTime = json.optLong("playTime", 0),
                sceneName = json.optString("sceneName", "")
            )
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * Get all save slot info
     */
    fun getAllSaveInfo(): List<SaveInfo?> {
        return (0 until maxSlots).map { getSaveInfo(it) }
    }

    /**
     * Set current save slot
     */
    fun setCurrentSlot(slot: Int) {
        if (slot in 0 until maxSlots) {
            currentSlot = slot
        }
    }

    /**
     * Get current save slot
     */
    fun getCurrentSlot(): Int = currentSlot

    /**
     * Auto-save checkpoint
     */
    fun checkpoint(scene: Scene, variables: Map<String, Any> = emptyMap()) {
        saveGame(maxSlots, scene, variables) // Use slot maxSlots for checkpoint
    }

    /**
     * Load from checkpoint
     */
    fun loadCheckpoint(): SaveData? {
        return loadGame(maxSlots)
    }

    private fun getSaveFile(slot: Int): File {
        return File(saveDirectory, "save_$slot.json")
    }

    private fun serializeScene(scene: Scene): String {
        val json = JSONObject()
        json.put("name", scene.name)

        val gameObjects = JSONArray()
        scene.root.forEachChild { go ->
            gameObjects.put(serializeGameObject(go))
        }
        json.put("gameObjects", gameObjects)

        return json.toString()
    }

    private fun serializeGameObject(go: GameObject): JSONObject {
        val json = JSONObject()
        json.put("name", go.name)
        json.put("id", go.id)
        json.put("x", go.x.toDouble())
        json.put("y", go.y.toDouble())
        json.put("rotation", go.rotation.toDouble())
        json.put("scaleX", go.scaleX.toDouble())
        json.put("scaleY", go.scaleY.toDouble())
        json.put("active", go.active)
        json.put("tags", JSONArray(go.tags))

        val components = JSONArray()
        go.components.forEach { comp ->
            components.put(serializeComponent(comp))
        }
        json.put("components", components)

        val children = JSONArray()
        go.children.forEach { child ->
            children.put(serializeGameObject(child))
        }
        json.put("children", children)

        return json
    }

    private fun serializeComponent(comp: Component): JSONObject {
        val json = JSONObject()
        json.put("type", comp.type)

        // Serialize component properties
        val props = JSONObject()
        comp.props().forEach { prop ->
            when (prop) {
                is Prop.F -> props.put(prop.label, prop.getter())
                is Prop.I -> props.put(prop.label, prop.getter())
                is Prop.B -> props.put(prop.label, prop.getter())
                is Prop.S -> props.put(prop.label, prop.getter())
                is Prop.Choice -> props.put(prop.label, prop.getter())
                is Prop.Color -> props.put(prop.label, prop.getter())
                else -> {}
            }
        }
        json.put("properties", props)

        return json
    }

    private fun serializeVariables(variables: Map<String, Any>): String {
        val json = JSONObject()
        variables.forEach { (key, value) ->
            when (value) {
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is Double -> json.put(key, value)
                is Boolean -> json.put(key, value)
                is String -> json.put(key, value)
                else -> json.put(key, value.toString())
            }
        }
        return json.toString()
    }

    companion object {
        /**
         * Deserialize scene from JSON
         */
        fun deserializeScene(jsonString: String): Scene {
            val json = JSONObject(jsonString)
            val scene = Scene(json.getString("name"))

            val gameObjects = json.getJSONArray("gameObjects")
            for (i in 0 until gameObjects.length()) {
                val goJson = gameObjects.getJSONObject(i)
                val go = deserializeGameObject(goJson)
                scene.root.addChild(go)
            }

            return scene
        }

        private fun deserializeGameObject(json: JSONObject): GameObject {
            val go = GameObject(json.getString("name"))
            go.x = json.getDouble("x").toFloat()
            go.y = json.getDouble("y").toFloat()
            go.rotation = json.getDouble("rotation").toFloat()
            go.scaleX = json.getDouble("scaleX").toFloat()
            go.scaleY = json.getDouble("scaleY").toFloat()
            go.active = json.getBoolean("active")

            val tags = json.getJSONArray("tags")
            for (i in 0 until tags.length()) {
                go.tags.add(tags.getString(i))
            }

            val components = json.getJSONArray("components")
            for (i in 0 until components.length()) {
                val compJson = components.getJSONObject(i)
                val comp = deserializeComponent(compJson)
                if (comp != null) {
                    go.addComponent(comp)
                }
            }

            val children = json.getJSONArray("children")
            for (i in 0 until children.length()) {
                val childJson = children.getJSONObject(i)
                val child = deserializeGameObject(childJson)
                go.addChild(child)
            }

            return go
        }

        private fun deserializeComponent(json: JSONObject): Component? {
            val type = json.getString("type")
            val props = json.getJSONObject("properties")

            val comp = ComponentRegistry.create(type) ?: return null

            // Deserialize properties
            comp.props().forEach { prop ->
                if (props.has(prop.label)) {
                    when (prop) {
                        is Prop.F -> prop.setter(props.getDouble(prop.label).toFloat())
                        is Prop.I -> prop.setter(props.getInt(prop.label))
                        is Prop.B -> prop.setter(props.getBoolean(prop.label))
                        is Prop.S -> prop.setter(props.getString(prop.label))
                        is Prop.Choice -> prop.setter(props.getInt(prop.label))
                        is Prop.Color -> prop.setter(props.getInt(prop.label))
                        else -> {}
                    }
                }
            }

            return comp
        }

        /**
         * Deserialize variables from JSON
         */
        fun deserializeVariables(jsonString: String): Map<String, Any> {
            val json = JSONObject(jsonString)
            val vars = mutableMapOf<String, Any>()

            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = json.get(key)
                vars[key] = value
            }

            return vars
        }
    }
}

/**
 * Save data container
 */
class SaveData {
    var slot = 0
    var timestamp = 0L
    var playTime = 0L
    var sceneData = ""
    var variables = ""

    fun toJson(): String {
        val json = JSONObject()
        json.put("slot", slot)
        json.put("timestamp", timestamp)
        json.put("playTime", playTime)
        json.put("sceneData", sceneData)
        json.put("variables", variables)
        return json.toString()
    }

    companion object {
        fun fromJson(jsonString: String): SaveData {
            val json = JSONObject(jsonString)
            val data = SaveData()
            data.slot = json.getInt("slot")
            data.timestamp = json.getLong("timestamp")
            data.playTime = json.getLong("playTime")
            data.sceneData = json.getString("sceneData")
            data.variables = json.getString("variables")
            return data
        }
    }
}

/**
 * Save slot info
 */
data class SaveInfo(
    val slot: Int,
    val timestamp: Long,
    val playTime: Long,
    val sceneName: String
)

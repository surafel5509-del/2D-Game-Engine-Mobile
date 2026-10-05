package com.sengine.engine.save

import com.sengine.engine.Engine
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Scene
import com.sengine.engine.core.SceneSerializer
import com.sengine.engine.core.Component
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Save/Load system for persisting game state.
 * Supports full scene saves, individual object saves, and key-value game data.
 */
class SaveSystem(private val engine: Engine) {
    val saveDir: File get() = File(engine.project.dir, "saves").also { it.mkdirs() }

    /** Save the current scene state. */
    fun saveScene(slot: Int = 0) {
        val scene = engine.scene
        val data = JSONObject()
        data.put("version", SAVE_VERSION)
        data.put("timestamp", System.currentTimeMillis())
        data.put("engineTime", engine.time)
        data.put("scene", SceneSerializer.toJson(scene))
        data.put("gameData", saveGameData())
        val file = File(saveDir, "save_$slot.json")
        file.writeText(data.toString(1))
    }

    /** Load a saved scene state. */
    fun loadScene(slot: Int = 0): Boolean {
        val file = File(saveDir, "save_$slot.json")
        if (!file.exists()) return false
        return try {
            val data = JSONObject(file.readText())
            val sceneJson = data.optJSONObject("scene") ?: return false
            val scene = SceneSerializer.fromJson(sceneJson)
            synchronized(engine.lock) {
                engine.replaceScene(scene)
            }
            // Restore game data
            val gd = data.optJSONObject("gameData")
            if (gd != null) restoreGameData(gd)
            true
        } catch (e: Exception) {
            engine.log(2, "Load failed: ${e.message}")
            false
        }
    }

    /** Delete a save slot. */
    fun deleteSave(slot: Int = 0) {
        File(saveDir, "save_$slot.json").delete()
    }

    /** Check if a save slot exists. */
    fun saveExists(slot: Int = 0): Boolean {
        return File(saveDir, "save_$slot.json").exists()
    }

    /** Get metadata for a save slot. */
    fun saveInfo(slot: Int = 0): SaveInfo? {
        val file = File(saveDir, "save_$slot.json")
        if (!file.exists()) return null
        return try {
            val data = JSONObject(file.readText())
            SaveInfo(
                slot = slot,
                timestamp = data.optLong("timestamp", 0),
                engineTime = data.optDouble("engineTime", 0.0),
                sceneName = data.optJSONObject("scene")?.optString("name", "Unknown") ?: "Unknown"
            )
        } catch (_: Exception) { null }
    }

    /** List all save slots. */
    fun listSaves(): List<SaveInfo> {
        val saves = ArrayList<SaveInfo>()
        for (slot in 0 until MAX_SLOTS) {
            val info = saveInfo(slot)
            if (info != null) saves.add(info)
        }
        return saves
    }

    // --- Key-Value Game Data ---
    private val gameData = JSONObject()

    /** Store a value in game data. */
    fun setData(key: String, value: Any?) {
        when (value) {
            is Boolean -> gameData.put(key, value)
            is Int -> gameData.put(key, value)
            is Long -> gameData.put(key, value)
            is Float -> gameData.put(key, value.toDouble())
            is Double -> gameData.put(key, value)
            is String -> gameData.put(key, value)
            null -> gameData.put(key, JSONObject.NULL)
            else -> gameData.put(key, value.toString())
        }
    }

    /** Get a value from game data. */
    fun getData(key: String, default: Any? = null): Any? {
        if (!gameData.has(key)) return default
        return gameData.get(key)
    }

    fun getInt(key: String, default: Int = 0): Int = gameData.optInt(key, default)
    fun getFloat(key: String, default: Float = 0f): Float = gameData.optDouble(key, default.toDouble()).toFloat()
    fun getDouble(key: String, default: Double = 0.0): Double = gameData.optDouble(key, default)
    fun getString(key: String, default: String = ""): String = gameData.optString(key, default)
    fun getBoolean(key: String, default: Boolean = false): Boolean = gameData.optBoolean(key, default)
    fun getLong(key: String, default: Long = 0L): Long = gameData.optLong(key, default)

    /** Save game data to its own file. */
    fun saveGameData(): JSONObject = JSONObject(gameData.toString())

    /** Restore game data from JSON. */
    fun restoreGameData(json: JSONObject) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            gameData.put(k, json.get(k))
        }
    }

    /** Save just the game data (separate from scene save). */
    fun saveGameProfile() {
        val file = File(saveDir, "game_profile.json")
        file.writeText(gameData.toString(1))
    }

    /** Load just the game data. */
    fun loadGameProfile(): Boolean {
        val file = File(saveDir, "game_profile.json")
        if (!file.exists()) return false
        return try {
            restoreGameData(JSONObject(file.readText()))
            true
        } catch (_: Exception) { false }
    }

    /** Clear all game data. */
    fun clearGameData() {
        gameData.clear()
    }

    /** Export all data as a single JSON string. */
    fun exportAll(): String {
        val data = JSONObject()
        data.put("saves", JSONArray().apply {
            for (slot in 0 until MAX_SLOTS) {
                val info = saveInfo(slot)
                if (info != null) put(JSONObject().apply {
                    put("slot", slot)
                    put("timestamp", info.timestamp)
                })
            }
        })
        data.put("gameData", JSONObject(gameData.toString()))
        return data.toString(2)
    }

    data class SaveInfo(val slot: Int, val timestamp: Long, val engineTime: Double, val sceneName: String)

    companion object {
        const val SAVE_VERSION = 2
        const val MAX_SLOTS = 20
    }
}

/**
 * Checkpoint system for games.
 * Automatically saves state at designated checkpoints.
 */
class CheckpointSystem(private val saveSystem: SaveSystem) {
    private val checkpoints = ArrayList<Checkpoint>()
    var lastCheckpoint: Checkpoint? = null
        private set

    data class Checkpoint(
        val id: String,
        val slot: Int,
        val x: Float = 0f,
        val y: Float = 0f,
        val timestamp: Long = System.currentTimeMillis()
    )

    /** Register a checkpoint position in the scene. */
    fun addCheckpoint(id: String, slot: Int, x: Float, y: Float) {
        checkpoints.add(Checkpoint(id, slot, x, y))
    }

    /** Trigger a checkpoint save. */
    fun reachCheckpoint(id: String) {
        val cp = checkpoints.firstOrNull { it.id == id } ?: return
        saveSystem.saveScene(cp.slot)
        lastCheckpoint = cp
    }

    /** Load the last checkpoint. */
    fun loadLastCheckpoint(): Boolean {
        val cp = lastCheckpoint ?: return false
        return saveSystem.loadScene(cp.slot)
    }

    /** Get the respawn position. */
    fun respawnPosition(): Pair<Float, Float>? {
        val cp = lastCheckpoint ?: return null
        return cp.x to cp.y
    }
}

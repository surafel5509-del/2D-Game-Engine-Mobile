package com.sengine.engine.gameplay

import org.json.JSONArray
import org.json.JSONObject

/**
 * Dialogue system for cutscenes, NPC conversations, and story sequences.
 */
class DialogueSystem {
    private var currentDialogue: Dialogue? = null
    private var currentNodeIndex = 0
    private var currentChoiceIndex = 0
    private var variables = mutableMapOf<String, Any>()

    var isPlaying = false
    var onDialogueStart: (() -> Unit)? = null
    var onDialogueEnd: (() -> Unit)? = null
    var onNodeAdvance: ((DialogueNode) -> Unit)? = null
    var onChoice: ((Int) -> Unit)? = null

    /**
     * Load dialogue from JSON
     */
    fun loadFromJson(jsonString: String): Dialogue {
        val json = JSONObject(jsonString)
        val title = json.optString("title", "")
        val nodesJson = json.getJSONArray("nodes")

        val nodes = mutableListOf<DialogueNode>()
        for (i in 0 until nodesJson.length()) {
            val nodeJson = nodesJson.getJSONObject(i)
            nodes.add(parseNode(nodeJson))
        }

        return Dialogue(title, nodes)
    }

    private fun parseNode(json: JSONObject): DialogueNode {
        val id = json.getString("id")
        val speaker = json.optString("speaker", "")
        val text = json.getString("text")
        val portrait = json.optString("portrait", "")
        val nextId = json.optString("next", "")

        val choices = mutableListOf<DialogueChoice>()
        if (json.has("choices")) {
            val choicesJson = json.getJSONArray("choices")
            for (i in 0 until choicesJson.length()) {
                val choiceJson = choicesJson.getJSONObject(i)
                choices.add(
                    DialogueChoice(
                        text = choiceJson.getString("text"),
                        nextId = choiceJson.getString("next"),
                        condition = choiceJson.optString("condition", ""),
                        action = choiceJson.optString("action", "")
                    )
                )
            }
        }

        val conditions = mutableListOf<String>()
        if (json.has("conditions")) {
            val conditionsJson = json.getJSONArray("conditions")
            for (i in 0 until conditionsJson.length()) {
                conditions.add(conditionsJson.getString(i))
            }
        }

        val actions = mutableListOf<String>()
        if (json.has("actions")) {
            val actionsJson = json.getJSONArray("actions")
            for (i in 0 until actionsJson.length()) {
                actions.add(actionsJson.getString(i))
            }
        }

        return DialogueNode(id, speaker, text, portrait, nextId, choices, conditions, actions)
    }

    /**
     * Start a dialogue
     */
    fun startDialogue(dialogue: Dialogue) {
        currentDialogue = dialogue
        currentNodeIndex = 0
        isPlaying = true
        onDialogueStart?.invoke()
        onNodeAdvance?.invoke(getCurrentNode()!!)
    }

    /**
     * Advance to next node or show choices
     */
    fun advance() {
        val currentNode = getCurrentNode() ?: return

        // If current node has choices, handle choice selection
        if (currentNode.choices.isNotEmpty()) {
            return // Wait for choice selection
        }

        // Move to next node
        moveToNode(currentNode.nextId)
    }

    /**
     * Select a choice
     */
    fun selectChoice(choiceIndex: Int) {
        val currentNode = getCurrentNode() ?: return
        if (choiceIndex < 0 || choiceIndex >= currentNode.choices.size) return

        val choice = currentNode.choices[choiceIndex]
        currentChoiceIndex = choiceIndex
        onChoice?.invoke(choiceIndex)

        // Execute choice action
        if (choice.action.isNotEmpty()) {
            executeAction(choice.action)
        }

        // Move to choice's next node
        moveToNode(choice.nextId)
    }

    /**
     * Move to a specific node by ID
     */
    private fun moveToNode(nodeId: String) {
        if (nodeId.isEmpty() || currentDialogue == null) {
            endDialogue()
            return
        }

        val nodeIndex = currentDialogue!!.nodes.indexOfFirst { it.id == nodeId }
        if (nodeIndex == -1) {
            endDialogue()
            return
        }

        currentNodeIndex = nodeIndex
        val node = getCurrentNode()!!

        // Check conditions
        if (!checkConditions(node.conditions)) {
            // Skip to next if conditions not met
            moveToNode(node.nextId)
            return
        }

        // Execute node actions
        node.actions.forEach { executeAction(it) }

        onNodeAdvance?.invoke(node)
    }

    /**
     * End current dialogue
     */
    fun endDialogue() {
        currentDialogue = null
        isPlaying = false
        onDialogueEnd?.invoke()
    }

    /**
     * Get current dialogue node
     */
    fun getCurrentNode(): DialogueNode? {
        return currentDialogue?.nodes?.getOrNull(currentNodeIndex)
    }

    /**
     * Get available choices for current node
     */
    fun getAvailableChoices(): List<DialogueChoice> {
        val node = getCurrentNode() ?: return emptyList()
        return node.choices.filter { choice ->
            choice.condition.isEmpty() || checkCondition(choice.condition)
        }
    }

    /**
     * Set a variable
     */
    fun setVariable(name: String, value: Any) {
        variables[name] = value
    }

    /**
     * Get a variable
     */
    fun getVariable(name: String): Any? = variables[name]

    /**
     * Check multiple conditions (AND logic)
     */
    private fun checkConditions(conditions: List<String>): Boolean {
        if (conditions.isEmpty()) return true
        return conditions.all { checkCondition(it) }
    }

    /**
     * Check a single condition
     */
    private fun checkCondition(condition: String): Boolean {
        // Parse condition like "varname > 5" or "varname == true"
        val parts = condition.split(" ")
        if (parts.size != 3) return true

        val varName = parts[0]
        val operator = parts[1]
        val value = parts[2]

        val actualValue = variables[varName] ?: return false

        return when (operator) {
            "==" -> actualValue.toString() == value
            "!=" -> actualValue.toString() != value
            ">" -> (actualValue as? Number)?.toDouble()?.let { it > value.toDouble() } ?: false
            "<" -> (actualValue as? Number)?.toDouble()?.let { it < value.toDouble() } ?: false
            ">=" -> (actualValue as? Number)?.toDouble()?.let { it >= value.toDouble() } ?: false
            "<=" -> (actualValue as? Number)?.toDouble()?.let { it <= value.toDouble() } ?: false
            else -> true
        }
    }

    /**
     * Execute an action
     */
    private fun executeAction(action: String) {
        // Parse action like "set varname value" or "add varname 5"
        val parts = action.split(" ")
        if (parts.isEmpty()) return

        when (parts[0]) {
            "set" -> {
                if (parts.size >= 3) {
                    variables[parts[1]] = parts[2]
                }
            }
            "add" -> {
                if (parts.size >= 3) {
                    val current = (variables[parts[1]] as? Number)?.toDouble() ?: 0.0
                    val add = parts[2].toDoubleOrNull() ?: 0.0
                    variables[parts[1]] = current + add
                }
            }
            "remove" -> {
                if (parts.size >= 3) {
                    val current = (variables[parts[1]] as? Number)?.toDouble() ?: 0.0
                    val sub = parts[2].toDoubleOrNull() ?: 0.0
                    variables[parts[1]] = current - sub
                }
            }
        }
    }

    fun isAtChoices(): Boolean = getCurrentNode()?.choices?.isNotEmpty() == true
    fun getDialogue(): Dialogue? = currentDialogue
}

/**
 * Dialogue data class
 */
data class Dialogue(
    val title: String,
    val nodes: List<DialogueNode>
)

/**
 * Single dialogue node
 */
data class DialogueNode(
    val id: String,
    val speaker: String,
    val text: String,
    val portrait: String,
    val nextId: String,
    val choices: List<DialogueChoice> = emptyList(),
    val conditions: List<String> = emptyList(),
    val actions: List<String> = emptyList()
)

/**
 * Choice option in dialogue
 */
data class DialogueChoice(
    val text: String,
    val nextId: String,
    val condition: String = "",
    val action: String = ""
)

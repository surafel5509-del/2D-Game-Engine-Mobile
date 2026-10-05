package com.sengine.engine.blueprint

import org.json.JSONArray
import org.json.JSONObject

/** Parameter of a node. [str] params are emitted as string literals, others as raw JS expressions. */
class ParamDef(val name: String, val default: String, val str: Boolean = false)

class NodeDef(
    val type: String,
    val title: String,
    val category: String,
    val hasIn: Boolean,
    val outs: List<String>,
    val params: List<ParamDef> = emptyList(),
    val help: String = "",
    /** JS code template for action nodes; {param} placeholders. */
    val code: String = "",
) {
    val isEvent get() = category == "Events"
}

class BpNode(val id: Int, val type: String, var x: Float, var y: Float, val params: MutableMap<String, String> = LinkedHashMap()) {
    val def: NodeDef? get() = BlueprintNodes.byType[type]
    fun param(name: String): String = params[name] ?: def?.params?.firstOrNull { it.name == name }?.default ?: ""
}

class BpLink(val from: Int, val pin: String, val to: Int)

/** Visual script graph stored as JSON in a `.bp` asset. */
class Blueprint {
    val nodes = ArrayList<BpNode>()
    val links = ArrayList<BpLink>()
    var nextId = 1

    fun node(id: Int) = nodes.firstOrNull { it.id == id }

    fun add(type: String, x: Float, y: Float): BpNode {
        val n = BpNode(nextId++, type, x, y)
        BlueprintNodes.byType[type]?.params?.forEach { n.params[it.name] = it.default }
        nodes.add(n)
        return n
    }

    fun remove(n: BpNode) {
        nodes.remove(n)
        links.removeAll { it.from == n.id || it.to == n.id }
    }

    /** Connect an output pin to a node input. An output pin has at most one link. */
    fun connect(from: Int, pin: String, to: Int) {
        if (from == to) return
        links.removeAll { it.from == from && it.pin == pin }
        links.add(BpLink(from, pin, to))
    }

    fun next(from: Int, pin: String): BpNode? = links.firstOrNull { it.from == from && it.pin == pin }?.let { node(it.to) }

    fun toJson(): JSONObject {
        val ns = JSONArray()
        for (n in nodes) {
            val p = JSONObject()
            n.params.forEach { (k, v) -> p.put(k, v) }
            ns.put(JSONObject().put("id", n.id).put("type", n.type).put("x", n.x.toDouble()).put("y", n.y.toDouble()).put("params", p))
        }
        val ls = JSONArray()
        for (l in links) ls.put(JSONObject().put("from", l.from).put("pin", l.pin).put("to", l.to))
        return JSONObject().put("format", "sengine-blueprint-1").put("nodes", ns).put("links", ls)
    }

    companion object {
        fun parse(text: String): Blueprint {
            val o = JSONObject(text)
            val bp = Blueprint()
            val ns = o.optJSONArray("nodes") ?: JSONArray()
            for (i in 0 until ns.length()) {
                val n = ns.getJSONObject(i)
                val node = BpNode(n.getInt("id"), n.getString("type"), n.optDouble("x", 0.0).toFloat(), n.optDouble("y", 0.0).toFloat())
                val p = n.optJSONObject("params")
                if (p != null) for (k in p.keys()) node.params[k] = p.optString(k)
                bp.nodes.add(node)
                bp.nextId = maxOf(bp.nextId, node.id + 1)
            }
            val ls = o.optJSONArray("links") ?: JSONArray()
            for (i in 0 until ls.length()) {
                val l = ls.getJSONObject(i)
                bp.links.add(BpLink(l.getInt("from"), l.optString("pin", "out"), l.getInt("to")))
            }
            return bp
        }

        fun defaultGraph(): Blueprint {
            val bp = Blueprint()
            val s = bp.add("OnStart", 40f, 40f)
            val log = bp.add("Log", 300f, 40f)
            log.params["message"] = "\"Blueprint started!\""
            bp.connect(s.id, "out", log.id)
            val u = bp.add("OnUpdate", 40f, 220f)
            val r = bp.add("Rotate", 300f, 220f)
            bp.connect(u.id, "out", r.id)
            return bp
        }
    }
}

/** Library of available nodes. */
object BlueprintNodes {
    private val O = listOf("out")

    val all: List<NodeDef> = listOf(
        // ---------------- events
        NodeDef("OnStart", "On Start", "Events", false, O, help = "Runs once when play begins"),
        NodeDef("OnUpdate", "On Update", "Events", false, O, help = "Runs every frame"),
        NodeDef("OnTap", "On Tap", "Events", false, O, help = "Object's collider was tapped"),
        NodeDef("OnTouch", "On Screen Touch", "Events", false, O, help = "Screen was tapped anywhere"),
        NodeDef("OnButtonA", "On Button A", "Events", false, O, help = "A button pressed"),
        NodeDef("OnButtonB", "On Button B", "Events", false, O, help = "B button pressed"),
        NodeDef("OnCollision", "On Collision", "Events", false, O, help = "Solid collision; 'other' is the object hit"),
        NodeDef("OnTrigger", "On Trigger Enter", "Events", false, O, help = "Entered a trigger; 'other' available"),
        NodeDef("OnTriggerExit", "On Trigger Exit", "Events", false, O),
        NodeDef("OnTimer", "On Timer", "Events", false, O, listOf(ParamDef("seconds", "1")), "Repeats every N seconds"),
        NodeDef("OnMessage", "On Message", "Events", false, O, listOf(ParamDef("name", "onHit")), "Called by send(\"name\"); 'arg' available"),

        // ---------------- flow
        NodeDef("If", "Branch (If)", "Flow", true, listOf("true", "false"), listOf(ParamDef("condition", "self.x > 0"))),
        NodeDef("Sequence", "Sequence", "Flow", true, listOf("then 1", "then 2", "then 3")),
        NodeDef("Repeat", "Repeat", "Flow", true, listOf("loop", "done"), listOf(ParamDef("count", "3")), "'i' is the loop index"),
        NodeDef("Wait", "Wait / Delay", "Flow", true, O, listOf(ParamDef("seconds", "1"))),
        NodeDef("Chance", "Random Chance", "Flow", true, listOf("yes", "no"), listOf(ParamDef("probability", "0.5"))),
        NodeDef("Once", "Do Once", "Flow", true, O),
        NodeDef("Cooldown", "Cooldown", "Flow", true, O, listOf(ParamDef("seconds", "0.5")), "Passes at most once per N seconds"),

        // ---------------- movement
        NodeDef("Move", "Move (per second)", "Movement", true, O, listOf(ParamDef("dx", "1"), ParamDef("dy", "0")), code = "self.move(({dx}) * __dt, ({dy}) * __dt);"),
        NodeDef("Move3D", "Move 3D (per second)", "Movement", true, O, listOf(ParamDef("dx", "0"), ParamDef("dy", "0"), ParamDef("dz", "-1")), code = "self.move(({dx}) * __dt, ({dy}) * __dt, ({dz}) * __dt);"),
        NodeDef("Rotate", "Rotate (deg/sec)", "Movement", true, O, listOf(ParamDef("degrees", "90")), code = "self.rotate(({degrees}) * __dt);"),
        NodeDef("Rotate3D", "Rotate 3D (deg/sec)", "Movement", true, O, listOf(ParamDef("x", "0"), ParamDef("y", "90"), ParamDef("z", "0")), code = "self.rotate(({x}) * __dt, ({y}) * __dt, ({z}) * __dt);"),
        NodeDef("SetPosition", "Set Position", "Movement", true, O, listOf(ParamDef("x", "0"), ParamDef("y", "0")), code = "self.setPosition({x}, {y});"),
        NodeDef("JoystickMove", "Move With Joystick", "Movement", true, O, listOf(ParamDef("speed", "5")), code = "self.move(input.axisX * ({speed}) * __dt, input.axisY * ({speed}) * __dt);"),
        NodeDef("Platformer", "Platformer Controller", "Movement", true, O, listOf(ParamDef("speed", "6"), ParamDef("jump", "11")),
            "Use in On Update with a Rigidbody2D", "self.vx = input.axisX * ({speed}); if (input.aDown && self.grounded) self.vy = ({jump}); if (input.axisX != 0) self.flipX = input.axisX < 0;"),
        NodeDef("Controller3D", "3D Character Controller", "Movement", true, O, listOf(ParamDef("speed", "6"), ParamDef("jump", "7")),
            "Use in On Update with a Rigidbody3D", "self.vx = input.axisX * ({speed}); self.vz = -input.axisY * ({speed}); if (input.aDown && self.grounded) self.vy = ({jump});"),

        // ---------------- physics
        NodeDef("SetVelocity", "Set Velocity", "Physics", true, O, listOf(ParamDef("vx", "0"), ParamDef("vy", "5")), code = "self.setVelocity({vx}, {vy});"),
        NodeDef("AddForce", "Add Impulse", "Physics", true, O, listOf(ParamDef("fx", "0"), ParamDef("fy", "5")), code = "self.addForce({fx}, {fy});"),
        NodeDef("Jump", "Jump (if grounded)", "Physics", true, O, listOf(ParamDef("force", "10")), code = "if (self.grounded) self.vy = ({force});"),

        // ---------------- objects
        NodeDef("Spawn", "Spawn Object", "Objects", true, O, listOf(ParamDef("template", "Bullet", true), ParamDef("x", "self.worldX"), ParamDef("y", "self.worldY")), code = "scene.spawn({template}, {x}, {y});"),
        NodeDef("DestroySelf", "Destroy Self", "Objects", true, O, code = "self.destroy();"),
        NodeDef("DestroyOther", "Destroy Other", "Objects", true, O, code = "if (other) other.destroy();"),
        NodeDef("SetActive", "Set Active", "Objects", true, O, listOf(ParamDef("object", "Door", true), ParamDef("active", "false")), code = "{ var __o = scene.find({object}); if (__o) __o.active = ({active}); }"),
        NodeDef("SendMessage", "Send Message", "Objects", true, O, listOf(ParamDef("object", "Player", true), ParamDef("message", "onHit", true)), code = "{ var __o = scene.find({object}); if (__o) __o.send({message}); }"),
        NodeDef("SetText", "Set Text", "Objects", true, O, listOf(ParamDef("object", "ScoreText", true), ParamDef("text", "\"Score: \" + vars.score")), code = "{ var __o = scene.find({object}); if (__o) __o.text = {text}; }"),
        NodeDef("SetColor", "Set Color", "Objects", true, O, listOf(ParamDef("color", "#FFFF5252", true)), code = "self.color = {color};"),
        NodeDef("FlipX", "Flip X", "Objects", true, O, listOf(ParamDef("flip", "true")), code = "self.flipX = ({flip});"),
        NodeDef("PlayAnimation", "Play Animation", "Objects", true, O, listOf(ParamDef("clip", "Run.anim", true)), code = "self.play({clip});"),
        NodeDef("Burst", "Particle Burst", "Objects", true, O, listOf(ParamDef("count", "20")), code = "self.burst({count});"),

        // ---------------- variables
        NodeDef("SetVar", "Set Variable", "Variables", true, O, listOf(ParamDef("name", "score", true), ParamDef("value", "0")), code = "vars[{name}] = ({value});"),
        NodeDef("AddVar", "Add To Variable", "Variables", true, O, listOf(ParamDef("name", "score", true), ParamDef("amount", "1")), code = "vars[{name}] = (vars[{name}] || 0) + ({amount});"),

        // ---------------- game
        NodeDef("PlaySound", "Play Sound", "Game", true, O, listOf(ParamDef("file", "coin.wav", true)), code = "audio.play({file});"),
        NodeDef("Beep", "Beep", "Game", true, O, code = "audio.beep();"),
        NodeDef("Shake", "Camera Shake", "Game", true, O, listOf(ParamDef("amount", "0.5")), code = "scene.shake({amount});"),
        NodeDef("LoadScene", "Load Scene", "Game", true, O, listOf(ParamDef("scene", "Main", true)), code = "scene.load({scene});"),
        NodeDef("Reload", "Restart Scene", "Game", true, O, code = "scene.reload();"),
        NodeDef("Log", "Print Log", "Game", true, O, listOf(ParamDef("message", "\"Hello\"")), code = "log({message});"),
        NodeDef("Code", "Run JavaScript", "Game", true, O, listOf(ParamDef("code", "self.scaleX = 1 + Math.sin(time.time)")), code = "{code};"),
    )

    val byType: Map<String, NodeDef> = all.associateBy { it.type }
    val categories: List<String> = all.map { it.category }.distinct()
}

/** Compiles a blueprint graph into an S Engine JavaScript behaviour. */
object BlueprintCompiler {
    private const val MAX_DEPTH = 64

    fun compile(bp: Blueprint): String {
        val sb = StringBuilder()
        sb.append("// Generated from a blueprint by S Engine\n")
        sb.append("var vars = {};\nvar other = null;\nvar arg = null;\nvar __dt = 1 / 60;\n")
        val globals = StringBuilder()
        val start = StringBuilder()
        val update = StringBuilder()
        val handlers = LinkedHashMap<String, StringBuilder>()
        fun handler(sig: String) = handlers.getOrPut(sig) { StringBuilder() }

        for (n in bp.nodes.sortedBy { it.y }) {
            val def = n.def ?: continue
            if (!def.isEvent) continue
            val body = StringBuilder()
            chain(bp, bp.next(n.id, "out"), body, "    ", 0, globals, HashSet())
            if (body.isEmpty()) continue
            when (n.type) {
                "OnStart" -> start.append(body)
                "OnUpdate" -> update.append(body)
                "OnButtonA" -> update.append("  if (input.aDown) {\n").append(body).append("  }\n")
                "OnButtonB" -> update.append("  if (input.bDown) {\n").append(body).append("  }\n")
                "OnTouch" -> update.append("  if (input.tapped) {\n").append(body).append("  }\n")
                "OnTimer" -> start.append("  every(${expr(n.param("seconds"))}, function () {\n").append(body).append("  });\n")
                "OnTap" -> handler("onTap()").append(body)
                "OnCollision" -> handler("onCollision(other)").append(body)
                "OnTrigger" -> handler("onTrigger(other)").append(body)
                "OnTriggerExit" -> handler("onTriggerExit(other)").append(body)
                "OnMessage" -> {
                    val name = n.param("name").replace(Regex("[^A-Za-z0-9_$]"), "").ifBlank { "onMessage" }
                    handler("$name(arg)").append(body)
                }
            }
        }
        sb.append(globals)
        sb.append("\nfunction start() {\n").append(start).append("}\n")
        sb.append("\nfunction update(dt) {\n  __dt = dt;\n").append(update).append("}\n")
        for ((sig, body) in handlers) sb.append("\nfunction ").append(sig).append(" {\n").append(body).append("}\n")
        return sb.toString()
    }

    private fun expr(s: String) = s.trim().ifEmpty { "0" }
    private fun lit(s: String) = JSONObject.quote(s)

    private fun chain(bp: Blueprint, node: BpNode?, out: StringBuilder, ind: String, depth: Int, globals: StringBuilder, path: HashSet<Int>) {
        var n = node
        var d = depth
        while (n != null) {
            if (d > MAX_DEPTH || n.id in path) { out.append(ind).append("// (loop in graph stopped)\n"); return }
            path.add(n.id)
            val def = n.def
            if (def == null) { out.append(ind).append("// unknown node ${n.type}\n"); return }
            val id = n.id
            when (n.type) {
                "If" -> {
                    out.append(ind).append("if (").append(expr(n.param("condition"))).append(") {\n")
                    chain(bp, bp.next(id, "true"), out, "$ind  ", d + 1, globals, HashSet(path))
                    out.append(ind).append("} else {\n")
                    chain(bp, bp.next(id, "false"), out, "$ind  ", d + 1, globals, HashSet(path))
                    out.append(ind).append("}\n")
                    return
                }
                "Chance" -> {
                    out.append(ind).append("if (Math.random() < (").append(expr(n.param("probability"))).append(")) {\n")
                    chain(bp, bp.next(id, "yes"), out, "$ind  ", d + 1, globals, HashSet(path))
                    out.append(ind).append("} else {\n")
                    chain(bp, bp.next(id, "no"), out, "$ind  ", d + 1, globals, HashSet(path))
                    out.append(ind).append("}\n")
                    return
                }
                "Sequence" -> {
                    for (pin in def.outs) chain(bp, bp.next(id, pin), out, ind, d + 1, globals, HashSet(path))
                    return
                }
                "Repeat" -> {
                    out.append(ind).append("for (var i = 0; i < (").append(expr(n.param("count"))).append("); i++) {\n")
                    chain(bp, bp.next(id, "loop"), out, "$ind  ", d + 1, globals, HashSet(path))
                    out.append(ind).append("}\n")
                    n = bp.next(id, "done"); d++
                    continue
                }
                "Wait" -> {
                    out.append(ind).append("after(").append(expr(n.param("seconds"))).append(", function () {\n")
                    chain(bp, bp.next(id, "out"), out, "$ind  ", d + 1, globals, HashSet(path))
                    out.append(ind).append("});\n")
                    return
                }
                "Once" -> {
                    globals.append("var __once$id = false;\n")
                    out.append(ind).append("if (!__once$id) {\n").append(ind).append("  __once$id = true;\n")
                    chain(bp, bp.next(id, "out"), out, "$ind  ", d + 1, globals, HashSet(path))
                    out.append(ind).append("}\n")
                    return
                }
                "Cooldown" -> {
                    globals.append("var __cd$id = -1e9;\n")
                    out.append(ind).append("if (time.time - __cd$id >= (").append(expr(n.param("seconds"))).append(")) {\n")
                    out.append(ind).append("  __cd$id = time.time;\n")
                    chain(bp, bp.next(id, "out"), out, "$ind  ", d + 1, globals, HashSet(path))
                    out.append(ind).append("}\n")
                    return
                }
                else -> {
                    var code = def.code
                    for (p in def.params) {
                        val v = n.param(p.name)
                        code = code.replace("{${p.name}}", if (p.str) lit(v) else expr(v))
                    }
                    if (code.isNotBlank()) out.append(ind).append(code).append('\n')
                    n = bp.next(id, "out"); d++
                }
            }
        }
    }
}

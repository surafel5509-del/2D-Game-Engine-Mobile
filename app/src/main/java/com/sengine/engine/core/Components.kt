package com.sengine.engine.core

object ComponentRegistry {
    val types: LinkedHashMap<String, () -> Component> = linkedMapOf(
        "SpriteRenderer" to { SpriteRenderer() },
        "TextRenderer" to { TextRenderer() },
        "Camera" to { Camera2D() },
        "Rigidbody2D" to { Rigidbody2D() },
        "Collider2D" to { Collider2D() },
        "Script" to { ScriptComponent() },
        "ParticleEmitter" to { ParticleEmitter() },
        "AudioSource" to { AudioSource() },
        "Animator" to { Animator() },
        "MeshRenderer" to { MeshRenderer() },
        "Camera3D" to { Camera3D() },
        "Light" to { Light() },
        "Rigidbody3D" to { Rigidbody3D() },
        "Collider3D" to { Collider3D() },
    )

    val categories: LinkedHashMap<String, List<String>> = linkedMapOf(
        "Rendering 2D" to listOf("SpriteRenderer", "TextRenderer", "Animator", "ParticleEmitter", "Camera"),
        "Rendering 3D" to listOf("MeshRenderer", "Camera3D", "Light"),
        "Physics 2D" to listOf("Rigidbody2D", "Collider2D"),
        "Physics 3D" to listOf("Rigidbody3D", "Collider3D"),
        "Scripting & Audio" to listOf("Script", "AudioSource"),
    )

    val POST_FX = listOf("None", "Grayscale", "Sepia", "Vignette", "CRT", "Pixelate", "Bloom", "Invert", "Chromatic", "Custom Shader")

    fun create(type: String): Component? = types[type]?.invoke()
}

class SpriteRenderer : Component() {
    override val type = "SpriteRenderer"
    var shape = 0 // 0 Square, 1 Circle, 2 Triangle
    var color = 0xFFFFFFFF.toInt()
    var texture = ""
    var flipX = false
    var flipY = false
    var shader = ""
    var shaderParam = 1f
    var screenSpace = false

    // runtime (set by Animator)
    var animTexture: String? = null
    val uv = floatArrayOf(0f, 1f, 1f, 0f) // u0, vBottom, u1, vTop

    override fun props() = listOf(
        Prop.Choice("Shape", SHAPES, { shape }, { shape = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
        Prop.B("Flip X", { flipX }, { flipX = it }),
        Prop.B("Flip Y", { flipY }, { flipY = it }),
        Prop.Asset("Shader", AssetKind.SHADER, { shader }, { shader = it }),
        Prop.F("Shader Param", { shaderParam }, { shaderParam = it }),
        Prop.B("Screen Space UI", { screenSpace }, { screenSpace = it }),
    )

    fun resetUv() { uv[0] = 0f; uv[1] = 1f; uv[2] = 1f; uv[3] = 0f; animTexture = null }

    companion object {
        val SHAPES = listOf("Square", "Circle", "Triangle")
    }
}

class TextRenderer : Component() {
    override val type = "TextRenderer"
    var text = "Hello S Engine"
    var size = 0.5f
    var color = 0xFFFFFFFF.toInt()
    var align = 1 // 0 left, 1 center, 2 right
    var bold = false
    var screenSpace = false

    override fun props() = listOf(
        Prop.S("Text", { text }, { text = it }, multiline = true),
        Prop.B("Screen Space UI", { screenSpace }, { screenSpace = it }),
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.01f) }, 0.05f),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Choice("Align", listOf("Left", "Center", "Right"), { align }, { align = it }),
        Prop.B("Bold", { bold }, { bold = it }),
    )
}

class Camera2D : Component() {
    override val type = "Camera"
    var size = 5f
    var background = 0xFF1B2533.toInt()
    var follow = ""
    var smoothing = 5f
    var postFx = 0
    var postIntensity = 1f
    var postShader = ""
    var shake = 0f // runtime

    override fun props() = listOf(
        Prop.F("Size", { size }, { size = it.coerceAtLeast(0.1f) }),
        Prop.Color("Background", { background }, { background = it }),
        Prop.S("Follow Target", { follow }, { follow = it }),
        Prop.F("Follow Smoothing", { smoothing }, { smoothing = it.coerceAtLeast(0f) }),
        Prop.Choice("Post FX", ComponentRegistry.POST_FX, { postFx }, { postFx = it }),
        Prop.F("FX Intensity", { postIntensity }, { postIntensity = it.coerceIn(0f, 4f) }, 0.05f),
        Prop.Asset("FX Shader", AssetKind.SHADER, { postShader }, { postShader = it }),
    )

    override fun resetRuntime() { shake = 0f }
}

class Rigidbody2D : Component() {
    override val type = "Rigidbody2D"
    var bodyType = 0 // 0 Dynamic, 1 Kinematic, 2 Static
    var mass = 1f
    var gravityScale = 1f
    var drag = 0f
    var bounciness = 0f
    var friction = 0.4f
    var startVx = 0f
    var startVy = 0f

    // runtime
    var vx = 0f
    var vy = 0f
    var grounded = false

    override fun props() = listOf(
        Prop.Choice("Body Type", listOf("Dynamic", "Kinematic", "Static"), { bodyType }, { bodyType = it }),
        Prop.F("Mass", { mass }, { mass = it.coerceAtLeast(0.001f) }),
        Prop.F("Gravity Scale", { gravityScale }, { gravityScale = it }),
        Prop.F("Linear Drag", { drag }, { drag = it.coerceAtLeast(0f) }),
        Prop.F("Bounciness", { bounciness }, { bounciness = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Friction", { friction }, { friction = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Velocity X", { startVx }, { startVx = it }),
        Prop.F("Velocity Y", { startVy }, { startVy = it }),
    )

    override fun resetRuntime() {
        vx = startVx; vy = startVy; grounded = false
    }
}

class Collider2D : Component() {
    override val type = "Collider2D"
    var shape = 0 // 0 Box, 1 Circle
    var width = 1f
    var height = 1f
    var radius = 0.5f
    var offsetX = 0f
    var offsetY = 0f
    var isTrigger = false

    override fun props() = listOf(
        Prop.Choice("Shape", listOf("Box", "Circle"), { shape }, { shape = it }),
        Prop.F("Width", { width }, { width = it.coerceAtLeast(0.01f) }),
        Prop.F("Height", { height }, { height = it.coerceAtLeast(0.01f) }),
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.01f) }),
        Prop.F("Offset X", { offsetX }, { offsetX = it }),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }),
        Prop.B("Is Trigger", { isTrigger }, { isTrigger = it }),
    )
}

class ScriptComponent : Component() {
    override val type = "Script"
    var script = ""
    var params = ""

    override fun props() = listOf(
        Prop.Asset("Script", AssetKind.SCRIPT, { script }, { script = it }),
        Prop.S("Params", { params }, { params = it }),
    )
}

class ParticleEmitter : Component() {
    override val type = "ParticleEmitter"
    var emitting = true
    var rate = 30f
    var lifetime = 1.2f
    var speed = 3f
    var direction = 90f
    var spread = 30f
    var startSize = 0.25f
    var endSize = 0.02f
    var startColor = 0xFFFFC940.toInt()
    var endColor = 0x00FF3D00
    var gravity = 0f
    var maxParticles = 300
    var additive = false
    var texture = ""

    // runtime
    val particles = ArrayList<Particle>()
    var accumulator = 0f
    var pendingBurst = 0

    class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float, var age: Float, var life: Float)

    override fun props() = listOf(
        Prop.B("Emitting", { emitting }, { emitting = it }),
        Prop.F("Rate", { rate }, { rate = it.coerceAtLeast(0f) }, 1f),
        Prop.F("Lifetime", { lifetime }, { lifetime = it.coerceAtLeast(0.01f) }),
        Prop.F("Speed", { speed }, { speed = it }),
        Prop.F("Direction", { direction }, { direction = it }, 1f),
        Prop.F("Spread", { spread }, { spread = it.coerceIn(0f, 360f) }, 1f),
        Prop.F("Start Size", { startSize }, { startSize = it.coerceAtLeast(0f) }, 0.01f),
        Prop.F("End Size", { endSize }, { endSize = it.coerceAtLeast(0f) }, 0.01f),
        Prop.Color("Start Color", { startColor }, { startColor = it }),
        Prop.Color("End Color", { endColor }, { endColor = it }),
        Prop.F("Gravity", { gravity }, { gravity = it }),
        Prop.I("Max Particles", { maxParticles }, { maxParticles = it.coerceIn(1, 5000) }),
        Prop.B("Additive Blend", { additive }, { additive = it }),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
    )

    override fun resetRuntime() {
        particles.clear(); accumulator = 0f; pendingBurst = 0
    }
}

class AudioSource : Component() {
    override val type = "AudioSource"
    var clip = ""
    var playOnStart = true
    var loop = false
    var volume = 1f

    override fun props() = listOf(
        Prop.Asset("Clip", AssetKind.SOUND, { clip }, { clip = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.B("Loop", { loop }, { loop = it }),
        Prop.F("Volume", { volume }, { volume = it.coerceIn(0f, 1f) }, 0.05f),
    )
}

/** Plays sprite-sheet animation clips (.anim assets) on the SpriteRenderer of the same object. */
class Animator : Component() {
    override val type = "Animator"
    var clip = ""
    var playOnStart = true
    var speed = 1f

    // runtime
    var current = ""
    var time = 0f
    var playing = false
    var frame = 0
    var finished = false

    override fun props() = listOf(
        Prop.Asset("Clip", AssetKind.ANIMATION, { clip }, { clip = it }),
        Prop.B("Play On Start", { playOnStart }, { playOnStart = it }),
        Prop.F("Speed", { speed }, { speed = it }),
    )

    override fun resetRuntime() {
        current = clip; time = 0f; playing = playOnStart; frame = 0; finished = false
    }
}

class MeshRenderer : Component() {
    override val type = "MeshRenderer"
    var mesh = 0
    var model = ""
    var color = 0xFFD0D4DC.toInt()
    var texture = ""
    var tiling = 1f
    var specular = 0.35f
    var shininess = 32f
    var emission = 0f
    var unlit = false
    var shader = ""
    var shaderParam = 1f

    override fun props() = listOf(
        Prop.Choice("Mesh", MESHES, { mesh }, { mesh = it }),
        Prop.Asset("Model (.obj)", AssetKind.MODEL, { model }, { model = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.Asset("Texture", AssetKind.TEXTURE, { texture }, { texture = it }),
        Prop.F("Tiling", { tiling }, { tiling = it.coerceAtLeast(0.01f) }),
        Prop.F("Specular", { specular }, { specular = it.coerceIn(0f, 2f) }, 0.05f),
        Prop.F("Shininess", { shininess }, { shininess = it.coerceIn(1f, 256f) }, 1f),
        Prop.F("Emission", { emission }, { emission = it.coerceIn(0f, 4f) }, 0.05f),
        Prop.B("Unlit", { unlit }, { unlit = it }),
        Prop.Asset("Shader", AssetKind.SHADER, { shader }, { shader = it }),
        Prop.F("Shader Param", { shaderParam }, { shaderParam = it }),
    )

    companion object {
        val MESHES = listOf("Cube", "Sphere", "Plane", "Cylinder", "Cone", "Torus", "Capsule", "Pyramid", "Custom Model")
    }
}

class Camera3D : Component() {
    override val type = "Camera3D"
    var fov = 60f
    var near = 0.1f
    var far = 500f
    var skyTop = 0xFF3B7BD4.toInt()
    var skyHorizon = 0xFFBFD8F0.toInt()
    var follow = ""
    var offsetX = 0f
    var offsetY = 4f
    var offsetZ = 8f
    var lookAtTarget = true
    var smoothing = 6f
    var postFx = 0
    var postIntensity = 1f
    var postShader = ""
    var shake = 0f

    override fun props() = listOf(
        Prop.F("Field of View", { fov }, { fov = it.coerceIn(10f, 150f) }, 1f),
        Prop.F("Near", { near }, { near = it.coerceAtLeast(0.01f) }, 0.01f),
        Prop.F("Far", { far }, { far = it.coerceAtLeast(1f) }, 1f),
        Prop.Color("Sky Top", { skyTop }, { skyTop = it }),
        Prop.Color("Sky Horizon", { skyHorizon }, { skyHorizon = it }),
        Prop.S("Follow Target", { follow }, { follow = it }),
        Prop.F("Offset X", { offsetX }, { offsetX = it }),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }),
        Prop.F("Offset Z", { offsetZ }, { offsetZ = it }),
        Prop.B("Look At Target", { lookAtTarget }, { lookAtTarget = it }),
        Prop.F("Follow Smoothing", { smoothing }, { smoothing = it.coerceAtLeast(0f) }),
        Prop.Choice("Post FX", ComponentRegistry.POST_FX, { postFx }, { postFx = it }),
        Prop.F("FX Intensity", { postIntensity }, { postIntensity = it.coerceIn(0f, 4f) }, 0.05f),
        Prop.Asset("FX Shader", AssetKind.SHADER, { postShader }, { postShader = it }),
    )

    override fun resetRuntime() { shake = 0f }
}

class Light : Component() {
    override val type = "Light"
    var kind = 0 // 0 Directional, 1 Point
    var color = 0xFFFFF4E0.toInt()
    var intensity = 1f
    var range = 10f

    override fun props() = listOf(
        Prop.Choice("Type", listOf("Directional", "Point"), { kind }, { kind = it }),
        Prop.Color("Color", { color }, { color = it }),
        Prop.F("Intensity", { intensity }, { intensity = it.coerceIn(0f, 10f) }, 0.05f),
        Prop.F("Range", { range }, { range = it.coerceAtLeast(0.1f) }),
    )
}

class Rigidbody3D : Component() {
    override val type = "Rigidbody3D"
    var bodyType = 0 // 0 Dynamic, 1 Kinematic, 2 Static
    var mass = 1f
    var gravityScale = 1f
    var drag = 0.05f
    var bounciness = 0f
    var friction = 0.5f
    var startVx = 0f
    var startVy = 0f
    var startVz = 0f

    var vx = 0f
    var vy = 0f
    var vz = 0f
    var grounded = false

    override fun props() = listOf(
        Prop.Choice("Body Type", listOf("Dynamic", "Kinematic", "Static"), { bodyType }, { bodyType = it }),
        Prop.F("Mass", { mass }, { mass = it.coerceAtLeast(0.001f) }),
        Prop.F("Gravity Scale", { gravityScale }, { gravityScale = it }),
        Prop.F("Linear Drag", { drag }, { drag = it.coerceAtLeast(0f) }),
        Prop.F("Bounciness", { bounciness }, { bounciness = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Friction", { friction }, { friction = it.coerceIn(0f, 1f) }, 0.05f),
        Prop.F("Velocity X", { startVx }, { startVx = it }),
        Prop.F("Velocity Y", { startVy }, { startVy = it }),
        Prop.F("Velocity Z", { startVz }, { startVz = it }),
    )

    override fun resetRuntime() { vx = startVx; vy = startVy; vz = startVz; grounded = false }
}

class Collider3D : Component() {
    override val type = "Collider3D"
    var shape = 0 // 0 Box, 1 Sphere
    var sizeX = 1f
    var sizeY = 1f
    var sizeZ = 1f
    var radius = 0.5f
    var centerX = 0f
    var centerY = 0f
    var centerZ = 0f
    var isTrigger = false

    override fun props() = listOf(
        Prop.Choice("Shape", listOf("Box", "Sphere"), { shape }, { shape = it }),
        Prop.F("Size X", { sizeX }, { sizeX = it.coerceAtLeast(0.01f) }),
        Prop.F("Size Y", { sizeY }, { sizeY = it.coerceAtLeast(0.01f) }),
        Prop.F("Size Z", { sizeZ }, { sizeZ = it.coerceAtLeast(0.01f) }),
        Prop.F("Radius", { radius }, { radius = it.coerceAtLeast(0.01f) }),
        Prop.F("Center X", { centerX }, { centerX = it }),
        Prop.F("Center Y", { centerY }, { centerY = it }),
        Prop.F("Center Z", { centerZ }, { centerZ = it }),
        Prop.B("Is Trigger", { isTrigger }, { isTrigger = it }),
    )
}

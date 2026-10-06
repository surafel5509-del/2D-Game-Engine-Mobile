package com.sengine.engine.core

import com.sengine.engine.anim.Animator
import com.sengine.engine.character.CharacterController2D
import com.sengine.engine.character.Ragdoll2D
import com.sengine.engine.fx.ParticleEmitter
import com.sengine.engine.fx.Trail2D
import com.sengine.engine.lighting.Light2D
import com.sengine.engine.lighting.LightOccluder2D
import com.sengine.engine.tilemap.TilemapRenderer
import com.sengine.engine.ui.UiButton
import com.sengine.engine.ui.UiCanvas
import com.sengine.engine.ui.UiCheckbox
import com.sengine.engine.ui.UiImage
import com.sengine.engine.ui.UiLabel
import com.sengine.engine.ui.UiList
import com.sengine.engine.ui.UiPanel
import com.sengine.engine.ui.UiProgressBar
import com.sengine.engine.ui.UiScrollView
import com.sengine.engine.ui.UiSlider
import com.sengine.engine.ui.UiTabs
import com.sengine.engine.ui.UiTextField
import com.sengine.engine.ui.UiTooltip
import com.sengine.engine.vehicle.Vehicle2D

/**
 * Every component the engine knows about, grouped by category for the "Add Component" menu.
 * The registry is strictly 2D - adding a component here makes it available to the inspector,
 * the serializers and the scripting layer at the same time.
 */
object ComponentRegistry {
    val types: LinkedHashMap<String, () -> Component> = linkedMapOf(
        // rendering
        "SpriteRenderer" to { SpriteRenderer() },
        "TextRenderer" to { TextRenderer() },
        "Camera" to { Camera2D() },
        "Parallax" to { Parallax() },
        "CameraAttach" to { CameraAttach() },
        "Animator" to { Animator() },
        "ParticleEmitter" to { ParticleEmitter() },
        "Trail2D" to { Trail2D() },
        "Tilemap" to { TilemapRenderer() },
        "Light2D" to { Light2D() },
        "LightOccluder" to { LightOccluder2D() },
        // physics 2D
        "Rigidbody2D" to { Rigidbody2D() },
        "Collider2D" to { Collider2D() },
        "HingeJoint" to { HingeJoint() },
        "DistanceJoint" to { DistanceJoint() },
        "SpringJoint" to { SpringJoint() },
        "RopeJoint" to { RopeJoint() },
        "WheelJoint" to { WheelJoint() },
        "WeldJoint" to { WeldJoint() },
        "PrismaticJoint" to { PrismaticJoint() },
        "MotorJoint" to { MotorJoint() },
        // character & vehicle
        "CharacterController2D" to { CharacterController2D() },
        "Health" to { Health() },
        "Checkpoint" to { Checkpoint() },
        "Ragdoll2D" to { Ragdoll2D() },
        "Vehicle2D" to { Vehicle2D() },
        // gameplay
        "Script" to { ScriptComponent() },
        "AudioSource" to { AudioSource() },
        "Spawner" to { Spawner() },
        "DestroyAfter" to { Lifetime() },
        // ui
        "UiCanvas" to { UiCanvas() },
        "UiPanel" to { UiPanel() },
        "UiLabel" to { UiLabel() },
        "UiImage" to { UiImage() },
        "UiButton" to { UiButton() },
        "UiSlider" to { UiSlider() },
        "UiProgressBar" to { UiProgressBar() },
        "UiCheckbox" to { UiCheckbox() },
        "UiTextField" to { UiTextField() },
        "UiScrollView" to { UiScrollView() },
        "UiTabs" to { UiTabs() },
        "UiList" to { UiList() },
        "UiTooltip" to { UiTooltip() }
    )

    val categories: LinkedHashMap<String, List<String>> = linkedMapOf(
        "Rendering 2D" to listOf("SpriteRenderer", "TextRenderer", "Camera", "Parallax", "CameraAttach", "Tilemap", "Light2D", "LightOccluder"),
        "Effects 2D" to listOf("Animator", "ParticleEmitter", "Trail2D"),
        "Physics 2D" to listOf("Rigidbody2D", "Collider2D"),
        "Joints 2D" to listOf("HingeJoint", "DistanceJoint", "SpringJoint", "RopeJoint", "WheelJoint", "WeldJoint", "PrismaticJoint", "MotorJoint"),
        "Character" to listOf("CharacterController2D", "Health", "Checkpoint", "Ragdoll2D"),
        "Vehicle" to listOf("Vehicle2D"),
        "Gameplay" to listOf("Script", "AudioSource", "Spawner", "DestroyAfter"),
        "UI 2D" to listOf("UiCanvas", "UiPanel", "UiLabel", "UiImage", "UiButton", "UiSlider", "UiProgressBar", "UiCheckbox", "UiTextField", "UiScrollView", "UiTabs", "UiList", "UiTooltip")
    )

    /** Screen-space 2D post effects (no 3D effects are available). */
    val POST_FX = listOf(
        "None", "Grayscale", "Sepia", "Vignette", "CRT", "Pixelate", "Bloom", "Invert", "Chromatic",
        "Scanlines", "Heat Haze", "Water", "Custom Shader"
    )

    fun create(type: String): Component? = types[type]?.invoke()

    fun exists(type: String) = types.containsKey(type)

    fun allTypes(): List<String> = types.keys.toList()
}

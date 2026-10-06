package com.sengine.engine.script

/**
 * The 2D scripting reference shown inside the script editor. Every entry here is a real, callable
 * part of the runtime API - there is no documentation for features that do not exist, and no 3D
 * API is listed because the engine has none.
 */
object ScriptReference {

    val GROUPS: List<Pair<String, List<String>>> = listOf(
        "Transform (2D)" to listOf(
            "self.x self.y            position (local)",
            "self.worldX self.worldY  computed world position",
            "self.rotation            degrees around Z",
            "self.scaleX self.scaleY  scale",
            "self.setPosition(x,y)  self.setWorldPosition(x,y)",
            "self.move(dx,dy)  self.rotate(deg)  self.lookAt(x,y)",
            "self.distanceTo(other)  self.angleTo(other)"
        ),
        "Physics 2D" to listOf(
            "self.vx self.vy          linear velocity",
            "self.angularVelocity     spin (degrees/second)",
            "self.grounded  self.sleeping  self.speed",
            "self.addForce(fx,fy)  self.addImpulse(ix,iy)",
            "self.addTorque(t)  self.setVelocity(vx,vy)  self.teleport(x,y)",
            "self.mass  self.friction  self.restitution",
            "self.setBodyType(\"Dynamic\"|\"Kinematic\"|\"Static\"|\"Character\")",
            "physics.raycast(x1,y1,x2,y2)       -> object or null",
            "physics.raycastPoint(x1,y1,x2,y2)  -> {x,y,normalX,normalY,fraction,object}",
            "physics.overlapCircle(x,y,r)  physics.overlapBox(x,y,hw,hh)",
            "physics.circleCast(x1,y1,x2,y2,r)",
            "physics.setGravityY(v)"
        ),
        "Animation" to listOf(
            "self.play(\"HeroRun.anim\")  self.stopAnimation()",
            "self.playState(\"jump\")  self.setAnimFloat(\"speed\", v)",
            "self.setAnimBool(\"grounded\", b)  self.setAnimTrigger(\"attack\")",
            "self.getAnimation()  self.getState()  self.getFrame()",
            "self.setAnimSpeed(2)  self.isAnimationFinished()"
        ),
        "Gameplay" to listOf(
            "self.health  self.damage(10)  self.heal(5)  self.isDead()",
            "self.knockback(dx,dy,strength)  self.goLimp()  self.explode(force)",
            "self.setThrottle(1)  self.getVehicleSpeedKmh()  self.refuel()",
            "self.getTeam()  self.setTeam(1)",
            "self.destroy()  self.isAlive()  self.emit(\"hit\", 3)"
        ),
        "Rendering 2D" to listOf(
            "self.visible  self.color  self.alpha  self.width  self.height",
            "self.setTexture(\"hero.png\")  self.setFrame(x,y)",
            "self.setFlipX(true)  self.setShader(\"Outline.mat\")  self.setShaderParam(0.5)",
            "self.setText(\"Score: 0\")  self.setFontSize(1)  self.setTextColor(\"#FFF\")"
        ),
        "Effects 2D" to listOf(
            "self.burst(30)  self.setEmitterPreset(\"explosion\")",
            "self.startEmitting()  self.stopEmitting()  self.particleCount()",
            "particles.play(\"fire\", x, y, 2)  particles.burst(\"sparks\", x, y, 12)",
            "particles.trail(self, 0.3, \"#FFFF00\")  self.clearTrail()",
            "camera.shake(0.5)"
        ),
        "Camera 2D" to listOf(
            "camera.follow(\"Player\")  camera.stopFollow()",
            "camera.setSize(4)  camera.zoom(1.5)  camera.shake(0.4)",
            "camera.setPixelPerfect(true)  camera.postFx(\"Bloom\", 0.8)",
            "camera.worldToScreenX(y)  camera.setPosition(x,y)"
        ),
        "Input" to listOf(
            "input.axisX input.axisY (-1..1, joystick/dpad/gamepad/keys)",
            "input.a input.b input.aDown input.bDown",
            "input.touching input.tapped input.doubleTapped input.longPressed",
            "input.tapX input.tapY  input.touchCount  input.pointerX(i) input.pointerY(i)",
            "input.worldX(i) input.worldY(i)  input.gamepadX input.gamepadY",
            "input.swipeX input.swipeY input.pinch input.hasGamepad()",
            "input.key(\"space\")  input.keyDown(\"escape\")  input.button(\"jump\")"
        ),
        "Audio" to listOf(
            "audio.play(\"jump.wav\")  audio.playAt(\"hit.wav\", x, y, 1)",
            "audio.music(\"theme.ogg\", 0.8, true)  audio.stopMusic()",
            "audio.stop(handle, 0.3)  audio.setBusVolume(\"Music\", 0.5)",
            "audio.beep()  audio.pause()  audio.resume()"
        ),
        "Tasks (coroutines)" to listOf(
            "tasks.after(2, fn)      run once after 2s",
            "tasks.every(0.5, fn)    repeat forever",
            "tasks.afterFrames(30, fn)",
            "tasks.tween(0, 1, 0.5, fn)",
            "tasks.waitUntil(cond, fn)",
            "after(2, fn)  every(1, fn)   // prelude shortcuts"
        ),
        "Scene" to listOf(
            "scene.find(\"Player\")  scene.findAllTag(\"Enemy\")",
            "scene.instantiate(\"Coin.prefab\", x, y)  scene.create(\"Empty\")",
            "scene.emit(\"levelDone\")  scene.on(\"levelDone\", fn)",
            "scene.save()  scene.loadSlot()  scene.load(\"Level2\")",
            "scene.shake(0.5)  scene.setGravity(0, -20)"
        ),
        "UI 2D" to listOf(
            "ui.setText(\"ScoreLabel\", \"Score: 120\")",
            "ui.setProgress(\"HealthBar\", 0.6)  ui.setSlider(\"Volume\", 0.5)",
            "ui.setVisible(\"Panel\", false)  ui.addListItem(\"Log\", \"picked up\")",
            "ui.toast(\"Level complete\")  ui.show(\"Paused\", \"Resume?\", [\"Yes\"], fn)"
        ),
        "Helpers (prelude)" to listOf(
            "math: clamp lerp clamp01 smoothstep moveTowards damp sign dist angleTo",
            "frames: random(a,b) randomInt(a,b) chance(p) pick(array)",
            "noise: noise.value(x,y) noise.fbm(x,y,3)",
            "random.seed(42) random.range(0,10) random.insideCircle(5)",
            "save.set(\"coins\", 12)  save.getNumber(\"coins\")  save.save(\"slot1\")  save.load(\"slot1\")",
            "log(...)  warn(...)  error(...)  engine.getFps()  time.time"
        )
    )
}

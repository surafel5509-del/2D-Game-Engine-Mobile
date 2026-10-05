package com.sengine.project

import com.sengine.engine.core.Animator
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Camera3D
import com.sengine.engine.core.Collider3D
import com.sengine.engine.core.Light
import com.sengine.engine.core.MeshRenderer
import com.sengine.engine.core.Rigidbody3D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer

object Templates {
    class Template(val name: String, val description: String, val build: (Project) -> Unit)

    val all: List<Template> by lazy { listOf(empty, platformer, shooter, physics, demo3d, animated, blueprintDemo) }

    const val NEW_SHADER = """// S Engine effect shader (GLSL ES)
// Available: uTime, uParam, uTex, uUseTex, uColor, uResolution
// Assign to a SpriteRenderer / MeshRenderer "Shader", or a camera Post FX.

vec4 effect(vec4 color, vec2 uv) {
    float pulse = 0.75 + 0.25 * sin(uTime * 3.0);
    return vec4(color.rgb * pulse, color.a);
}
"""

    const val NEW_SCRIPT = """// S Engine behaviour script (JavaScript)
// Globals: self/transform, input, time, scene, audio, log(), after(), every()

function start() {
    log("Hello from " + self.name);
}

function update(dt) {
    // transform.rotation += 90 * dt;
}

// function onCollision(other) {}
// function onTrigger(other) {}
// function onTap() {}
"""

    // ---------------------------------------------------------------- helpers
    private fun obj(s: Scene, name: String, x: Float, y: Float, sx: Float = 1f, sy: Float = 1f, parent: GameObject? = null): GameObject {
        val go = s.create(name, parent)
        go.x = x; go.y = y; go.scaleX = sx; go.scaleY = sy
        return go
    }

    private fun GameObject.sprite(color: Long, shape: Int = 0): GameObject {
        add(SpriteRenderer().also { it.color = color.toInt(); it.shape = shape }); return this
    }

    private fun GameObject.box(trigger: Boolean = false): GameObject {
        add(Collider2D().also { it.isTrigger = trigger }); return this
    }

    private fun GameObject.circleCol(trigger: Boolean = false): GameObject {
        add(Collider2D().also { it.shape = 1; it.isTrigger = trigger }); return this
    }

    private fun GameObject.body(type: Int = 0, friction: Float = 0.4f, bounce: Float = 0f, gravity: Float = 1f): GameObject {
        add(Rigidbody2D().also { it.bodyType = type; it.friction = friction; it.bounciness = bounce; it.gravityScale = gravity }); return this
    }

    private fun GameObject.script(name: String, params: String = ""): GameObject {
        add(ScriptComponent().also { it.script = name; it.params = params }); return this
    }

    private fun GameObject.text(t: String, size: Float, color: Long = 0xFFFFFFFF): GameObject {
        add(TextRenderer().also { it.text = t; it.size = size; it.color = color.toInt(); it.bold = true }); return this
    }

    private fun camera(s: Scene, size: Float, bg: Long, follow: String = ""): GameObject {
        val c = obj(s, "Main Camera", 0f, 0f)
        c.add(Camera2D().also { it.size = size; it.background = bg.toInt(); it.follow = follow })
        return c
    }

    // ---------------------------------------------------------------- empty
    private val empty = Template("Empty 2D", "A camera and a square. Start from scratch.") { p ->
        val s = Scene("Main")
        camera(s, 5f, 0xFF1B2533)
        obj(s, "Square", 0f, 0f).sprite(0xFF4FC3F7)
        p.writeAsset("NewBehaviour.js", NEW_SCRIPT)
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- platformer
    private val platformer = Template("Platformer Demo", "Run, jump and collect coins. Joystick + A button.") { p ->
        p.writeAsset("Player.js", """// Player controller
// Move: joystick / A-D keys.  Jump: button A / Space.
// Params (set in inspector): speed, jump
var coins = 0;

function start() {
    log("Collect all the coins!");
}

function update(dt) {
    self.vx = input.axisX * speed;
    if (input.aDown && self.grounded) {
        self.vy = jump;
        audio.beep();
    }
    if (input.axisX < -0.1) self.flipX = true;
    else if (input.axisX > 0.1) self.flipX = false;

    // fell off the world
    if (self.y < -12) scene.reload();
}

function onTrigger(other) {
    if (other.tag == "Coin") {
        coins++;
        var fx = scene.spawn("CoinFX", other.worldX, other.worldY);
        if (fx) {
            fx.burst(24);
            after(1.5, function () { fx.destroy(); });
        }
        other.destroy();
        var label = scene.find("ScoreText");
        if (scene.count("Coin") == 0) label.text = "You win!";
        else label.text = "Coins: " + coins;
    }
}
""")
        p.writeAsset("Coin.js", """// Makes a coin bob and spin
var baseY;
function start() { baseY = transform.y; }
function update(dt) {
    transform.y = baseY + Math.sin(time.time * 3 + transform.x) * 0.15;
    transform.scaleX = 0.1 + 0.4 * Math.abs(Math.cos(time.time * 2.5 + transform.x));
}
""")
        p.writeAsset("MovingPlatform.js", """// Moves back and forth. Params: range, speed
var startX;
function start() { startX = transform.x; }
function update(dt) {
    self.vx = Math.cos(time.time * speed) * range * speed;
}
""")
        val s = Scene("Main")
        val cam = camera(s, 6f, 0xFF6EC6FF, follow = "Player")
        obj(s, "ScoreText", 0f, 5f, parent = cam).text("Coins: 0", 0.7f).also { it.order = 100 }
        obj(s, "Ground", 0f, -3f, 30f, 1f).sprite(0xFF4E7D3A).box()
        obj(s, "Platform A", 4f, 0f, 4f, 0.5f).sprite(0xFF8D6E63).box()
        obj(s, "Platform B", -5f, 1.2f, 3f, 0.5f).sprite(0xFF8D6E63).box()
        obj(s, "Platform C", 10f, 2.5f, 3f, 0.5f).sprite(0xFF8D6E63).box()
        obj(s, "Moving Platform", 16f, 1f, 3f, 0.5f).sprite(0xFFB0BEC5).box().body(type = 1)
            .script("MovingPlatform.js", "range=2, speed=1")
        obj(s, "Wall L", -15.5f, 0f, 1f, 8f).sprite(0xFF4E7D3A).box()
        val player = obj(s, "Player", 0f, -1.5f, 0.8f, 0.8f).sprite(0xFFFF7043).box()
            .body(friction = 0f).script("Player.js", "speed=6, jump=11")
        player.tag = "Player"; player.order = 10
        val coinPos = listOf(4f to 1.2f, -5f to 2.4f, 10f to 3.7f, 7f to -1.8f, -9f to -1.8f, 16f to 2.3f, 20f to -1.8f)
        for ((x, y) in coinPos) {
            val c = obj(s, "Coin", x, y, 0.5f, 0.5f).sprite(0xFFFFD54F, 1).circleCol(true).script("Coin.js")
            c.tag = "Coin"; c.order = 5
        }
        val fx = obj(s, "CoinFX", 0f, 0f)
        fx.active = false
        fx.add(ParticleEmitter().also {
            it.emitting = false; it.rate = 0f; it.spread = 360f; it.speed = 4f; it.gravity = -6f
            it.lifetime = 0.7f; it.startColor = 0xFFFFF176.toInt(); it.endColor = 0x00FFA000
        })
        fx.order = 20
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- shooter
    private val shooter = Template("Space Shooter", "Top-down shooter with spawning, triggers and score.") { p ->
        p.writeAsset("Ship.js", """// Player ship. Params: speed
var cooldown = 0;
function update(dt) {
    self.vx = input.axisX * speed;
    self.vy = input.axisY * speed;
    transform.x = clamp(transform.x, -12, 12);
    transform.y = clamp(transform.y, -7, 7);
    cooldown -= dt;
    if ((input.a || input.touching) && cooldown <= 0) {
        cooldown = 0.18;
        scene.spawn("Bullet", self.worldX, self.worldY + 0.6);
    }
}
function onTrigger(other) {
    if (other.tag == "Enemy") {
        var fx = scene.spawn("Explosion", self.worldX, self.worldY);
        if (fx) fx.burst(60);
        self.active = false;
        scene.find("Game").send("gameOver");
    }
}
""")
        p.writeAsset("Bullet.js", """function update(dt) {
    if (transform.y > 10) self.destroy();
}
""")
        p.writeAsset("Enemy.js", """function start() {
    self.vy = -random(2, 4.5);
}
function update(dt) {
    transform.rotation += 90 * dt;
    if (transform.y < -10) self.destroy();
}
function onTrigger(other) {
    if (other.tag == "Bullet") {
        var fx = scene.spawn("Explosion", self.worldX, self.worldY);
        if (fx) { fx.burst(30); after(1, function () { fx.destroy(); }); }
        other.destroy();
        self.destroy();
        audio.beep();
        scene.find("Game").send("addScore", 10);
    }
}
""")
        p.writeAsset("Star.js", """function start() { self.vy = 0; speedY = random(0.5, 3); }
var speedY = 1;
function update(dt) {
    transform.y -= speedY * dt;
    if (transform.y < -9) { transform.y = 9; transform.x = random(-15, 15); }
}
""")
        p.writeAsset("Game.js", """// Game manager: spawns enemies and tracks the score
var score = 0;
var over = false;
function start() {
    for (var i = 0; i < 40; i++) scene.spawn("Star", random(-15, 15), random(-9, 9));
    every(0.8, function () {
        if (!over) scene.spawn("Enemy", random(-11, 11), 10);
    });
}
function addScore(n) {
    score += n;
    scene.find("ScoreText").text = "Score: " + score;
}
function gameOver() {
    over = true;
    scene.find("ScoreText").text = "Game Over  -  Score: " + score;
    after(2.5, function () { scene.reload(); });
}
""")
        val s = Scene("Main")
        s.gravityY = 0f
        val cam = camera(s, 8f, 0xFF0B1026)
        obj(s, "ScoreText", 0f, 7f, parent = cam).text("Score: 0", 0.8f).also { it.order = 100 }
        obj(s, "Game", 0f, 0f).script("Game.js")
        val ship = obj(s, "Ship", 0f, -5f, 1f, 1.2f).sprite(0xFF4FC3F7, 2).box(true).body(type = 1)
            .script("Ship.js", "speed=9")
        ship.tag = "Player"; ship.order = 10
        val flame = obj(s, "Engine Flame", 0f, -0.5f, 1f, 1f, parent = ship)
        flame.add(ParticleEmitter().also {
            it.direction = -90f; it.spread = 20f; it.speed = 4f; it.rate = 60f; it.lifetime = 0.35f
            it.startSize = 0.3f; it.startColor = 0xFF80DEEA.toInt(); it.endColor = 0x000277BD
        })
        val bullet = obj(s, "Bullet", 0f, 0f, 0.15f, 0.5f).sprite(0xFFFFF176).box(true).body(type = 1)
            .script("Bullet.js")
        bullet.tag = "Bullet"; bullet.active = false
        bullet.getAny<Rigidbody2D>()!!.startVy = 16f
        val enemy = obj(s, "Enemy", 0f, 12f, 1f, 1f).sprite(0xFFEF5350, 0).box(true).body(type = 1)
            .script("Enemy.js")
        enemy.tag = "Enemy"; enemy.active = false; enemy.order = 5
        val star = obj(s, "Star", 0f, 0f, 0.08f, 0.08f).sprite(0xAAFFFFFF, 1).script("Star.js")
        star.active = false; star.order = -10
        val ex = obj(s, "Explosion", 0f, 0f)
        ex.active = false; ex.order = 20
        ex.add(ParticleEmitter().also {
            it.emitting = false; it.rate = 0f; it.spread = 360f; it.speed = 5f; it.lifetime = 0.6f
            it.startSize = 0.35f; it.startColor = 0xFFFFAB40.toInt(); it.endColor = 0x00D50000
        })
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- physics
    private val physics = Template("Physics Sandbox", "Tap anywhere to drop bouncy balls and boxes.") { p ->
        p.writeAsset("Spawner.js", """// Tap the screen to spawn objects
var n = 0;
function update(dt) {
    if (input.tapped) {
        var name = (n++ % 2 == 0) ? "Ball" : "Crate";
        var o = scene.spawn(name, input.touchX, input.touchY);
        if (o) {
            var colors = ["#FFEF5350", "#FF42A5F5", "#FF66BB6A", "#FFFFCA28", "#FFAB47BC"];
            o.color = colors[randomInt(0, colors.length - 1)];
        }
        scene.find("Counter").text = "Objects: " + n;
    }
}
""")
        val s = Scene("Main")
        camera(s, 7f, 0xFF263238)
        obj(s, "Counter", 0f, 6f).text("Tap to spawn!", 0.6f).also { it.order = 100 }
        obj(s, "Spawner", 0f, 0f).script("Spawner.js")
        obj(s, "Floor", 0f, -6.5f, 24f, 1f).sprite(0xFF546E7A).box()
        obj(s, "Wall Left", -12f, 0f, 1f, 14f).sprite(0xFF546E7A).box()
        obj(s, "Wall Right", 12f, 0f, 1f, 14f).sprite(0xFF546E7A).box()
        obj(s, "Ramp", -5f, -2f, 5f, 0.4f).sprite(0xFF78909C).box()
        for (row in 0 until 4) for (i in 0 until 4 - row) {
            obj(s, "Crate", 3f + i * 1.05f + row * 0.52f, -5.5f + row * 1.02f).sprite(0xFFA1887F).box().body()
        }
        val ball = obj(s, "Ball", 0f, 20f, 0.8f, 0.8f).sprite(0xFFFFFFFF, 1).circleCol().body(bounce = 0.7f)
        ball.active = false
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ================================================================ v2 templates

    private fun installAssets(p: Project, vararg titles: String) {
        for (t in titles) {
            val item = AssetLibrary.items.firstOrNull { it.title == t } ?: continue
            // Texture generation needs android.graphics; ignore failures (e.g. JVM unit tests).
            try { item.install(p) } catch (_: Throwable) {}
        }
    }

    private fun obj3(s: Scene, name: String, x: Float, y: Float, z: Float, sx: Float = 1f, sy: Float = 1f, sz: Float = 1f, parent: GameObject? = null): GameObject {
        val go = s.create(name, parent)
        go.x = x; go.y = y; go.z = z; go.scaleX = sx; go.scaleY = sy; go.scaleZ = sz
        return go
    }

    private fun GameObject.mesh(kind: Int, color: Long, texture: String = "", tiling: Float = 1f, model: String = ""): GameObject {
        add(MeshRenderer().also { it.mesh = kind; it.color = color.toInt(); it.texture = texture; it.tiling = tiling; it.model = model }); return this
    }

    private fun GameObject.col3(sphere: Boolean = false, trigger: Boolean = false): GameObject {
        add(Collider3D().also { it.shape = if (sphere) 1 else 0; it.isTrigger = trigger }); return this
    }

    private fun GameObject.body3(type: Int = 0, bounce: Float = 0f, friction: Float = 0.5f): GameObject {
        add(Rigidbody3D().also { it.bodyType = type; it.bounciness = bounce; it.friction = friction }); return this
    }

    // ---------------------------------------------------------------- 3D demo
    private val demo3d = Template("3D Demo", "3D world with lights, physics, models, shaders and a character.") { p ->
        installAssets(p, "Checker", "Wooden Crate", "Low-Poly Tree", "Rock", "Crystal", "Toon", "Pulse", "Player3D", "Coin Pickup", "Jump")
        p.writeAsset("Coin3D.js", """// Spinning pickup
var baseY;
function start() { baseY = self.y; }
function update(dt) {
    self.rotY += 120 * dt;
    self.y = baseY + Math.sin(time.time * 3 + self.x) * 0.2;
}
function onTrigger(other) {
    if (other.tag != "Player") return;
    audio.play("coin.wav");
    var label = scene.find("ScoreText");
    var left = scene.count("Coin") - 1;
    label.text = left > 0 ? "Coins left: " + left : "You collected everything!";
    self.destroy();
}
""")
        p.writeAsset("Player3D.js", AssetLibrary.Scripts.all.first { it.first == "Player3D.js" }.third.replace("self.vy = jump;", "{ self.vy = jump; audio.play(\"jump.wav\"); }"))
        val s = Scene("Main")
        s.fog = true; s.fogStart = 25f; s.fogEnd = 70f
        val cam = obj3(s, "Main Camera", 0f, 5f, 10f)
        cam.rotX = -20f
        cam.add(Camera3D().also { it.follow = "Player"; it.offsetY = 5f; it.offsetZ = 9f; it.postFx = 3; it.postIntensity = 0.6f })
        obj3(s, "Sun", 0f, 10f, 0f).also { it.rotX = -50f; it.rotY = 30f }.add(Light().also { it.kind = 0; it.intensity = 1f })
        obj3(s, "Lamp", -4f, 2.5f, -3f).add(Light().also { it.kind = 1; it.color = 0xFFFF9F40.toInt(); it.intensity = 1.6f; it.range = 9f })
        obj3(s, "Ground", 0f, -0.5f, 0f, 40f, 1f, 40f).mesh(0, 0xFFB8C4B0, "Checker.png", 10f).col3().body3(type = 2)
        val player = obj3(s, "Player", 0f, 1f, 0f, 0.8f, 1.6f, 0.8f).mesh(6, 0xFFFF7043).col3().body3(friction = 0.2f)
            .script("Player3D.js", "speed=6, jump=7")
        player.tag = "Player"
        for (i in 0 until 6) obj3(s, "Crate", -3f + (i % 3) * 1.05f, 0.5f + (i / 3) * 1.05f, -6f).mesh(0, 0xFFFFFFFF, "Crate.png").col3().body3(bounce = 0.1f)
        for (i in 0 until 4) obj3(s, "Ball", 4f + i * 0.5f, 3f + i * 1.5f, -4f, 0.8f, 0.8f, 0.8f).mesh(1, listOf(0xFF42A5F5, 0xFFAB47BC, 0xFFFFCA28, 0xFF66BB6A)[i]).col3(sphere = true).body3(bounce = 0.7f)
        val treePos = listOf(-10f to -10f, 8f to -12f, -14f to 4f, 12f to 6f, 0f to -16f, -6f to 12f)
        for ((x, z) in treePos) obj3(s, "Tree", x, 0f, z, 2f, 2f, 2f).mesh(8, 0xFF5FA85B, model = "Tree.obj").col3()
        for ((x, z) in listOf(6f to 2f, -8f to -3f)) obj3(s, "Rock", x, 0.3f, z, 1.2f, 1.2f, 1.2f).mesh(8, 0xFF8D8D8D, model = "Rock.obj").col3().body3(type = 2)
        val crystal = obj3(s, "Crystal", 0f, 1f, -10f, 1.2f, 1.2f, 1.2f).mesh(8, 0xFF7C4DFF, model = "Crystal.obj").script("Rotator.js", "x=0, y=45, z=0")
        crystal.get<MeshRenderer>()!!.also { it.shader = "Pulse.glsl"; it.emission = 0.3f }
        p.writeAsset("Rotator.js", AssetLibrary.Scripts.all.first { it.first == "Rotator.js" }.third)
        val coins = listOf(3f to 3f, -5f to 5f, 7f to -7f, -9f to -8f, 10f to 0f)
        for ((x, z) in coins) {
            val c = obj3(s, "Coin", x, 1f, z, 0.7f, 0.7f, 0.7f).mesh(5, 0xFFFFC107).col3(sphere = true, trigger = true).script("Coin3D.js")
            c.tag = "Coin"; c.get<MeshRenderer>()!!.emission = 0.25f
        }
        val torusShader = obj3(s, "Toon Torus", -4f, 1.5f, 4f).mesh(5, 0xFF26C6DA).script("Rotator.js", "x=40, y=60, z=0")
        torusShader.get<MeshRenderer>()!!.shader = "Toon.glsl"
        obj(s, "ScoreText", 0f, 4.2f).text("Coins left: ${coins.size}", 0.45f).also { it.get<TextRenderer>()!!.screenSpace = true; it.order = 100 }
        p.saveScene(s)
        p.startScene = "Main"
        p.orientation = 0
    }

    // ---------------------------------------------------------------- animated platformer
    private val animated = Template("Animated Platformer", "Sprite-sheet animation, textures, sounds and a camera shake.") { p ->
        installAssets(p, "Hero Run (4 frames)", "Hero", "Coin Spin (6 frames)", "Slime Bounce (4 frames)", "Grass Tile", "Brick Wall",
            "Sky Gradient", "Mountains", "Coin Pickup", "Jump", "Hit", "EnemyPatrol", "Health", "Hit Flash")
        p.writeAsset("Hero.js", """// Animated hero. Params: speed, jump
var coins = 0;
function update(dt) {
    self.vx = input.axisX * speed;
    if (input.axisX != 0) self.flipX = input.axisX < 0;
    if (input.aDown && self.grounded) { self.vy = jump; audio.play("jump.wav"); }
    if (Math.abs(self.vx) > 0.1 && self.grounded) self.play("HeroRun.anim");
    else self.stopAnimation();
    if (self.y < -12) scene.reload();
}
function onTrigger(other) {
    if (other.tag != "Coin") return;
    coins++; other.destroy(); audio.play("coin.wav");
    scene.find("ScoreText").text = scene.count("Coin") <= 1 ? "You win!" : "Coins: " + coins;
}
""")
        val s = Scene("Main")
        val cam = camera(s, 5f, 0xFF6EC6FF, follow = "Player")
        obj(s, "Sky", 0f, 0f, 20f, 12f, parent = cam).also { it.order = -100 }.add(SpriteRenderer().also { it.texture = "Sky.png" })
        obj(s, "Mountains", 0f, -2f, 24f, 8f).also { it.order = -90 }.add(SpriteRenderer().also { it.texture = "Mountains.png" })
        obj(s, "ScoreText", 0f, 4.2f).text("Coins: 0", 0.5f).also { it.get<TextRenderer>()!!.screenSpace = true; it.order = 100 }
        for (i in -8..12) obj(s, "Ground", i * 1f, -3f, 1f, 1f).also { it.order = -1 }.add(SpriteRenderer().also { it.texture = "Grass.png" })
        obj(s, "Ground Collider", 2f, -3f, 21f, 1f).box()
        for ((x, y, w) in listOf(Triple(4f, 0f, 3), Triple(-4f, 1f, 2), Triple(9f, 2f, 3))) {
            for (k in 0 until w) obj(s, "Brick", x + k - (w - 1) / 2f, y, 1f, 1f).add(SpriteRenderer().also { it.texture = "Brick.png" })
            obj(s, "Platform Collider", x, y, w.toFloat(), 1f).box()
        }
        val hero = obj(s, "Player", 0f, -1.5f, 1f, 1f).also { it.order = 10; it.tag = "Player" }
        hero.add(SpriteRenderer().also { it.texture = "Hero.png"; it.shader = "HitFlash.glsl"; it.shaderParam = 0f })
        hero.add(Animator().also { it.clip = "HeroRun.anim"; it.playOnStart = false })
        hero.add(com.sengine.engine.core.Collider2D().also { it.width = 0.6f; it.height = 0.9f })
        hero.body(friction = 0f).script("Hero.js", "speed=6, jump=11")
        hero.add(ScriptComponent().also { it.script = "Health.js"; it.params = "hp=3" })
        for ((x, y) in listOf(4f to 1.2f, -4f to 2.2f, 9f to 3.2f, 7f to -2f, -6f to -2f)) {
            val c = obj(s, "Coin", x, y, 0.6f, 0.6f).also { it.tag = "Coin"; it.order = 5 }
            c.add(SpriteRenderer().also { it.texture = "CoinSpin.png" })
            c.add(Animator().also { it.clip = "CoinSpin.anim" })
            c.circleCol(true)
        }
        val slime = obj(s, "Slime", 8f, -2.1f, 0.9f, 0.9f).also { it.order = 8; it.tag = "Enemy" }
        slime.add(SpriteRenderer().also { it.texture = "Slime.png" })
        slime.add(Animator().also { it.clip = "SlimeBounce.anim" })
        slime.box().body(type = 1).script("EnemyPatrol.js", "distance=2.5, speed=1.5")
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- blueprint demo
    private val blueprintDemo = Template("Blueprint Demo", "Gameplay built with visual scripting nodes — no code.") { p ->
        installAssets(p, "Coin Pickup", "Jump", "Rotator (Blueprint)", "Collectible (Blueprint)", "Soft Particle")
        val bp = com.sengine.engine.blueprint.Blueprint()
        val u = bp.add("OnUpdate", 40f, 40f)
        val mv = bp.add("Platformer", 300f, 40f)
        bp.connect(u.id, "out", mv.id)
        val a = bp.add("OnButtonA", 40f, 220f)
        val snd = bp.add("PlaySound", 300f, 220f).also { it.params["file"] = "jump.wav" }
        bp.connect(a.id, "out", snd.id)
        val st = bp.add("OnStart", 40f, 380f)
        val lg = bp.add("Log", 300f, 380f).also { it.params["message"] = "\"Blueprint player ready!\"" }
        bp.connect(st.id, "out", lg.id)
        p.writeAsset("PlayerBP.bp", bp.toJson().toString(2))
        val s = Scene("Main")
        camera(s, 6f, 0xFF263238, follow = "Player")
        obj(s, "Info", 0f, 4.2f).text("Open PlayerBP.bp in the editor to see the nodes", 0.3f).also { it.get<TextRenderer>()!!.screenSpace = true; it.order = 100 }
        obj(s, "Ground", 0f, -3f, 30f, 1f).sprite(0xFF546E7A).box()
        obj(s, "Step", 5f, -1f, 4f, 0.5f).sprite(0xFF78909C).box()
        val pl = obj(s, "Player", 0f, -1.5f, 0.8f, 0.8f).sprite(0xFF29B6F6).box().body(friction = 0f).script("PlayerBP.bp", "speed=6, jump=11")
        pl.tag = "Player"
        for (i in 0 until 5) obj(s, "Gem", -6f + i * 3f, 0.5f, 0.5f, 0.5f).sprite(0xFFFFD54F, 2).circleCol(true).script("CollectibleBP.bp").also { it.order = 5 }
        obj(s, "Spinner", -8f, 1f, 1f, 1f).sprite(0xFFEC407A).script("RotatorBP.bp")
        p.saveScene(s)
        p.startScene = "Main"
    }
}

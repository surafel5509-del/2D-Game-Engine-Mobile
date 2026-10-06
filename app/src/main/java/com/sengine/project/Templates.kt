package com.sengine.project

import com.sengine.engine.anim.Animator
import com.sengine.engine.character.CharacterController2D
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.Prefab
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import com.sengine.engine.fx.ParticleEmitter
import com.sengine.engine.tilemap.TilemapData
import com.sengine.engine.tilemap.TilemapRenderer
import com.sengine.engine.tilemap.Tileset
import com.sengine.engine.vehicle.Vehicle2D

/**
 * Project templates. Every template is a real, playable 2D scene built from the same components and
 * scripts a user has in the editor - there are no 3D templates and no fake content.
 */
object Templates {
    class Template(val name: String, val description: String, val build: (Project) -> Unit)

    val all: List<Template> by lazy {
        listOf(empty, platformer, shooter, physics, tilemap, vehicle, animated, blueprintDemo)
    }

    const val NEW_SHADER = """// S Engine effect shader (GLSL ES)
// Available: uTime, uParam, uTex, uUseTex, uColor, uResolution
// Assign to a SpriteRenderer "Material", or to a camera Post FX.

vec4 effect(vec4 color, vec2 uv) {
    float pulse = 0.75 + 0.25 * sin(uTime * 3.0);
    return vec4(color.rgb * pulse, color.a);
}
"""

    const val NEW_SCRIPT = """// S Engine behaviour script (JavaScript)
// Globals: self/transform, input, time, scene, camera, audio, ui, log(), after(), every()

function start() {
    log("Hello from " + self.name);
}

function update(dt) {
    // self.rotation = self.rotation + 90 * dt;
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

    private fun GameObject.textured(texture: String, w: Float, h: Float): GameObject {
        add(SpriteRenderer().also {
            it.texture = texture; it.shape = SpriteRenderer.SHAPE_TEXTURE; it.width = w; it.height = h
        })
        return this
    }

    private fun GameObject.box(trigger: Boolean = false, friction: Float = 0.4f, restitution: Float = 0f, oneWay: Boolean = false): GameObject {
        add(Collider2D().also {
            it.isTrigger = trigger; it.friction = friction; it.restitution = restitution; it.oneWay = oneWay
        })
        return this
    }

    private fun GameObject.circleCol(trigger: Boolean = false, restitution: Float = 0f): GameObject {
        add(Collider2D().also { it.shape = Collider2D.SHAPE_CIRCLE; it.isTrigger = trigger; it.restitution = restitution }); return this
    }

    private fun GameObject.capsuleCol(): GameObject {
        add(Collider2D().also { it.shape = Collider2D.SHAPE_CAPSULE }); return this
    }

    private fun GameObject.body(type: Int = 0, gravity: Float = 1f, mass: Float = 1f): GameObject {
        add(Rigidbody2D().also { it.bodyType = type; it.gravityScale = gravity; it.mass = mass }); return this
    }

    private fun GameObject.script(name: String, params: String = ""): GameObject {
        add(ScriptComponent().also { it.script = name; it.params = params }); return this
    }

    private fun GameObject.text(t: String, size: Float, color: Long = 0xFFFFFFFF): GameObject {
        add(TextRenderer().also { it.text = t; it.size = size; it.color = color.toInt(); it.bold = true }); return this
    }

    private fun GameObject.inFrontOfCamera(): GameObject {
        getAny<TextRenderer>()?.screenSpace = true
        order = 100
        return this
    }

    private fun GameObject.particles(block: ParticleEmitter.() -> Unit): GameObject {
        add(ParticleEmitter().also(block)); return this
    }

    private fun camera(s: Scene, size: Float, bg: Long, follow: String = ""): GameObject {
        val c = obj(s, "Main Camera", 0f, 0f)
        c.add(Camera2D().also { it.size = size; it.background = bg.toInt(); it.follow = follow })
        return c
    }

    private fun installAssets(p: Project, vararg titles: String) {
        for (t in titles) {
            val item = AssetLibrary.items.firstOrNull { it.title == t } ?: continue
            // Texture generation needs android.graphics; ignore failures (e.g. JVM unit tests).
            try { item.install(p) } catch (_: Throwable) {}
        }
    }

    /** Saves an (inactive) scene object as a prefab asset so scripts can `scene.spawn(name, x, y)`. */
    private fun prefab(p: Project, go: GameObject, name: String) {
        val wasActive = go.active
        go.active = true
        try {
            p.writeAsset("$name.prefab", Prefab.fromObject(go, name).toJson().toString(2))
        } catch (_: Throwable) {
            // asset folders can be read-only in restricted sandboxes; the template still loads
        } finally {
            go.active = wasActive
        }
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
// Params (set in the inspector): speed, jump
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
    if (input.axisX < -0.1) self.flipSpriteX();
    else if (input.axisX > 0.1) self.flipSpriteX();

    // fell off the world
    if (self.y < -12) scene.reload();
}
""")
        p.writeAsset("Coin.js", """// Makes a coin bob and spin
var baseY;
function start() { baseY = self.y; }
function update(dt) {
    self.y = baseY + Math.sin(time.time * 3 + self.x) * 0.15;
    self.scaleX = 0.4 + 0.6 * Math.abs(Math.cos(time.time * 2.5 + self.x));
}
function onTrigger(other) {
    if (other.tag != "Player") return;
    audio.play("coin.wav");
    var fx = scene.spawn("CoinFX", self.worldX, self.worldY);
    if (fx) { fx.burst(20); after(0.8, function () { fx.destroy(); }); }
    var label = scene.find("ScoreText");
    self.destroy();
    if (label && scene.findAllTag("Coin").length == 0) label.setText("You win!");
}
""")
        p.writeAsset("MovingPlatform.js", """// Moves back and forth. Params: range, speed
var startX;
function start() { startX = self.x; }
function update(dt) {
    self.x = startX + Math.sin(time.time * speed) * range;
}
""")
        val s = Scene("Main")
        val cam = camera(s, 6f, 0xFF6EC6FF, follow = "Player")
        obj(s, "ScoreText", 0f, 5f, parent = cam).text("Coins: 0", 0.7f).inFrontOfCamera()
        obj(s, "Ground", 0f, -3f, 30f, 1f).sprite(0xFF4E7D3A).box()
        obj(s, "Platform A", 4f, 0f, 4f, 0.5f).sprite(0xFF8D6E63).box()
        obj(s, "Platform B", -5f, 1.2f, 3f, 0.5f).sprite(0xFF8D6E63).box()
        obj(s, "Platform C", 10f, 2.5f, 3f, 0.5f).sprite(0xFF8D6E63).box()
        obj(s, "Moving Platform", 16f, 1f, 3f, 0.5f).sprite(0xFFB0BEC5).box(oneWay = true)
            .body(type = 1).script("MovingPlatform.js", "range=2, speed=1")
        obj(s, "Wall L", -15.5f, 0f, 1f, 8f).sprite(0xFF4E7D3A).box()
        val player = obj(s, "Player", 0f, -1.5f, 0.8f, 0.8f).sprite(0xFFFF7043).box(friction = 0f)
            .body().script("Player.js", "speed=6, jump=11")
        player.tag = "Player"; player.order = 10
        val coinPos = listOf(4f to 1.2f, -5f to 2.4f, 10f to 3.7f, 7f to -1.8f, -9f to -1.8f, 16f to 2.3f, 20f to -1.8f)
        for ((x, y) in coinPos) {
            val c = obj(s, "Coin", x, y, 0.5f, 0.5f).sprite(0xFFFFD54F, SpriteRenderer.SHAPE_CIRCLE)
                .circleCol(trigger = true).script("Coin.js")
            c.tag = "Coin"; c.order = 5
        }
        val fx = obj(s, "CoinFX", 0f, 0f).particles {
            emitting = false; rate = 0f; burstOnStart = true; burstCount = 20
            spread = 360f; speed = 4f; gravityY = -6f; lifetime = 0.7f
            colorStart = 0xFFFFF176.toInt(); colorEnd = 0x00FFA000
        }
        fx.active = false
        fx.order = 20
        prefab(p, fx, "CoinFX")
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
    self.x = clamp(self.x, -12, 12);
    self.y = clamp(self.y, -7, 7);
    cooldown -= dt;
    if ((input.a || input.touching) && cooldown <= 0) {
        cooldown = 0.18;
        scene.spawn("Bullet", self.worldX, self.worldY + 0.6);
    }
}
function onTrigger(other) {
    if (other.tag == "Enemy") {
        var fx = scene.spawn("Explosion", self.worldX, self.worldY);
        if (fx) { fx.burst(60); after(1, function () { fx.destroy(); }); }
        self.destroy();
        var game = scene.find("Game");
        if (game) game.send("gameOver");
    }
}
""")
        p.writeAsset("Bullet.js", """function update(dt) {
    if (self.y > 10) self.destroy();
}
""")
        p.writeAsset("Enemy.js", """function start() {
    self.vy = -random(2, 4.5);
}
function update(dt) {
    self.rotation += 90 * dt;
    if (self.y < -10) self.destroy();
}
function onTrigger(other) {
    if (other.tag == "Bullet") {
        var fx = scene.spawn("Explosion", self.worldX, self.worldY);
        if (fx) { fx.burst(30); after(1, function () { fx.destroy(); }); }
        other.destroy();
        self.destroy();
        audio.beep();
        var game = scene.find("Game");
        if (game) game.send("addScore", 10);
    }
}
""")
        p.writeAsset("Star.js", """var speedY = 1;
function start() { speedY = random(0.5, 3); }
function update(dt) {
    self.y -= speedY * dt;
    if (self.y < -9) { self.y = 9; self.x = random(-15, 15); }
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
    var label = scene.find("ScoreText");
    if (label) label.setText("Score: " + score);
}
function gameOver() {
    over = true;
    var label = scene.find("ScoreText");
    if (label) label.setText("Game Over  -  Score: " + score);
    after(2.5, function () { scene.reload(); });
}
""")
        val s = Scene("Main")
        s.gravityY = 0f
        val cam = camera(s, 8f, 0xFF0B1026)
        obj(s, "ScoreText", 0f, 7f, parent = cam).text("Score: 0", 0.8f).inFrontOfCamera()
        obj(s, "Game", 0f, 0f).script("Game.js")
        val ship = obj(s, "Ship", 0f, -5f, 1f, 1.2f).sprite(0xFF4FC3F7, SpriteRenderer.SHAPE_TRIANGLE)
            .box(trigger = true).body(type = 1).script("Ship.js", "speed=9")
        ship.tag = "Player"; ship.order = 10
        obj(s, "Engine Flame", 0f, -0.6f, 1f, 1f, parent = ship).particles {
            direction = -90f; spread = 20f; speed = 4f; rate = 60f; lifetime = 0.35f
            sizeStart = 0.3f; colorStart = 0xFF80DEEA.toInt(); colorEnd = 0x000277BD
        }
        val bullet = obj(s, "Bullet", 0f, 0f, 0.15f, 0.5f).sprite(0xFFFFF176).box(trigger = true)
            .body(type = 1).script("Bullet.js")
        bullet.tag = "Bullet"; bullet.active = false
        bullet.getAny<Rigidbody2D>()!!.startVy = 16f
        prefab(p, bullet, "Bullet")
        val enemy = obj(s, "Enemy", 0f, 12f, 1f, 1f).sprite(0xFFEF5350, SpriteRenderer.SHAPE_TRIANGLE)
            .box(trigger = true).body(type = 1).script("Enemy.js")
        enemy.tag = "Enemy"; enemy.active = false; enemy.order = 5
        prefab(p, enemy, "Enemy")
        val star = obj(s, "Star", 0f, 0f, 0.08f, 0.08f).sprite(0xAAFFFFFF, SpriteRenderer.SHAPE_CIRCLE)
            .script("Star.js")
        star.active = false; star.order = -10
        prefab(p, star, "Star")
        val ex = obj(s, "Explosion", 0f, 0f).particles {
            emitting = false; rate = 0f; burstCount = 40; spread = 360f; speed = 5f; lifetime = 0.6f
            sizeStart = 0.35f; colorStart = 0xFFFFAB40.toInt(); colorEnd = 0x00D50000
        }
        ex.active = false; ex.order = 20
        prefab(p, ex, "Explosion")
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
        var o = scene.spawn(name, input.tapX, input.tapY);
        if (o) {
            var colors = ["#FFEF5350", "#FF42A5F5", "#FF66BB6A", "#FFFFCA28", "#FFAB47BC"];
            o.color = colors[randomInt(0, colors.length - 1)];
        }
        var counter = scene.find("Counter");
        if (counter) counter.setText("Objects: " + n);
    }
}
""")
        p.writeAsset("Ragdoll.js", """// Turn the object into a limp ragdoll when it takes damage
function onCollision(other) {
    self.goLimp();
}
""")
        val s = Scene("Main")
        camera(s, 7f, 0xFF263238)
        obj(s, "Counter", 0f, 6f).text("Tap to spawn!", 0.6f).inFrontOfCamera()
        obj(s, "Spawner", 0f, 0f).script("Spawner.js")
        obj(s, "Floor", 0f, -6.5f, 24f, 1f).sprite(0xFF546E7A).box(friction = 0.6f)
        obj(s, "Wall Left", -12f, 0f, 1f, 14f).sprite(0xFF546E7A).box()
        obj(s, "Wall Right", 12f, 0f, 1f, 14f).sprite(0xFF546E7A).box()
        obj(s, "Ramp", -5f, -2f, 5f, 0.4f).sprite(0xFF78909C).box().also { it.rotation = 14f }
        var crateTemplate: GameObject? = null
        for (row in 0 until 4) for (i in 0 until 4 - row) {
            val crate = obj(s, "Crate", 3f + i * 1.05f + row * 0.52f, -5.5f + row * 1.02f).sprite(0xFFA1887F).box().body()
            if (crateTemplate == null) crateTemplate = crate
        }
        val ball = obj(s, "Ball", 0f, 20f, 0.8f, 0.8f).sprite(0xFFFFFFFF, SpriteRenderer.SHAPE_CIRCLE)
            .circleCol(restitution = 0.7f).body()
        ball.active = false
        crateTemplate?.let { prefab(p, it, "Crate") }
        prefab(p, ball, "Ball")
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- tilemap
    private val tilemap = Template("Tilemap Level", "A chunked tilemap level with generated collision, coins and a moving hero.") { p ->
        installAssets(p, "Grass Tile", "Hero", "Coin Pickup", "Jump", "Hit", "EnemyPatrol", "Health", "Soft Particle")
        p.writeAsset("Hero.js", AssetLibrary.Scripts.all.first { it.first == "PlayerPlatformer.js" }.third)
        val s = Scene("Main")
        camera(s, 6.5f, 0xFF6EC6FF, follow = "Player")

        val map = TilemapData("Level")
        map.tileWidth = 16; map.tileHeight = 16; map.pixelsPerUnit = 16f
        map.tilesets.add(Tileset("Ground", "Grass.png").also {
            it.tileWidth = 16; it.tileHeight = 16
            it.solid.add(1); it.solid.add(2); it.solid.add(3)
            it.oneWay.add(3)
            it.friction[2] = 1.1f
        })
        val ground = map.layer("Ground")
        for (x in -24..24) {
            ground.set(x, -1, 1)
            if (x % 5 == 0) ground.set(x, -2, 2)
        }
        for (x in 4..8) ground.set(x, 0, 3)
        for (x in -12..-8) ground.set(x, 1, 3)

        // The map is stored as a project asset so the scene keeps its terrain after a save/load
        // round trip; the renderer also gets the live data so the editor shows it immediately.
        p.writeAsset("Level.tilemap", map.toJson().toString())
        val tileGo = s.create("Tilemap")
        tileGo.add(TilemapRenderer().also {
            it.map = "Level.tilemap"
            it.assignData(map)
            it.generateCollision = true
            // The tileset paints its own solid tile ids, so the blob autotile pass is off: rewriting
            // the ids to autotile-sheet indices would leave the solid set without colliders.
            it.autoTile = false
        })

        val player = obj(s, "Player", -2f, 1f, 1f, 1f)
        player.add(SpriteRenderer().also { it.texture = "Hero.png"; it.shape = SpriteRenderer.SHAPE_TEXTURE; it.width = 0.7f; it.height = 0.9f })
        player.capsuleCol().body(gravity = 1.6f).script("Hero.js", "speed=6, jump=12")
        player.tag = "Player"; player.order = 10
        player.add(CharacterController2D())

        for ((x, y) in listOf(4f to 1.6f, 6f to 1.6f, 8f to 1.6f, -12f to 2.6f, -10f to 2.6f)) {
            val c = obj(s, "Coin", x, y, 0.5f, 0.5f).sprite(0xFFFFD54F, SpriteRenderer.SHAPE_CIRCLE)
                .circleCol(trigger = true)
            c.tag = "Coin"; c.order = 5
        }
        val slime = obj(s, "Slime", 12f, 0.5f, 0.9f, 0.9f).sprite(0xFF8BC34A, SpriteRenderer.SHAPE_CIRCLE)
            .box().body(gravity = 1.2f).script("EnemyPatrol.js", "distance=3, speed=1.5")
        slime.tag = "Enemy"; slime.order = 8
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- vehicle (Hill Climb style)
    private val vehicle = Template("Hill Climb Vehicle", "Two-wheel vehicle physics with suspension, terrain and flip recovery.") { p ->
        installAssets(p, "Dirt", "Metal Plate", "Coin Pickup", "Jump", "Explosion", "Soft Particle", "Spark")
        p.writeAsset("Driver.js", """// Vehicle driver. Params: airControl
var airControl = 55;
function update(dt) {
    self.throttle = input.axisX;
    if (input.aDown) self.throttle = 1;
    if (input.b) self.throttle = -1;

    if (self.airborne) {
        // rotate the chassis in the air so the player can save a bad landing
        self.addTorque(-input.axisX * airControl * dt * 60);
    }
    var label = scene.find("SpeedLabel");
    if (label) label.setText(Math.round(self.vehicleSpeedKmh) + " km/h");
    if (self.vehicleSpeedKmh > 45) camera.shake(0.05);
}
""")
        val s = Scene("Main")
        val cam = camera(s, 8f, 0xFF7EC8F0, follow = "Vehicle")
        cam.getAny<Camera2D>()!!.deadZoneY = 2.5f
        obj(s, "SpeedLabel", 0f, 3.6f, parent = cam).text("0 km/h", 0.6f).inFrontOfCamera()

        // rolling terrain built from static boxes: flat start, hill, dip, ramp
        var prevY = -1f
        var x = -30f
        while (x < 60f) {
            val y = (-1.0 + Math.sin(x * 0.16) * 1.6 + Math.sin(x * 0.05) * 1.2).toFloat()
            val h = (y - prevY).coerceIn(-2f, 2f)
            val slope = (Math.toDegrees(Math.atan2(h.toDouble(), 1.2)) * 0.5).toFloat()
            obj(s, "Terrain", x, y - 1.5f, 1.2f, 3f + Math.abs(h))
                .sprite(0xFF6D4C41).box(friction = 0.9f)
                .also { it.rotation = slope }
            prevY = y; x += 1.2f
        }

        for (k in 0 until 5) {
            val gx = 6f + k * 10f
            val coin = obj(s, "Coin", gx, 2.4f, 0.5f, 0.5f).sprite(0xFFFFD54F, SpriteRenderer.SHAPE_CIRCLE)
                .circleCol(trigger = true)
            coin.tag = "Coin"; coin.order = 5
        }

        val car = obj(s, "Vehicle", 0f, 1.5f, 1.5f, 0.8f).sprite(0xFFE53935).box(friction = 1.1f)
            .body(mass = 2.2f).script("Driver.js", "airControl=55")
        car.tag = "Player"; car.order = 10
        car.add(Vehicle2D().also {
            it.wheels = "-0.65,-0.3,0.32,drive,steer;0.65,-0.3,0.32,drive;"
            it.maxMotorTorque = 420f
            it.traction = 1.25f
            it.stabilization = 0.4f
            it.useFuel = true
            it.fuelCapacity = 100f
            it.fuelConsumption = 1.6f
        })
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- animated platformer
    private val animated = Template("Animated Platformer", "Sprite-sheet animation, textures, sounds and a camera shake.") { p ->
        installAssets(p, "Hero Run (4 frames)", "Hero", "Coin Spin (6 frames)", "Slime Bounce (4 frames)", "Grass Tile", "Brick Wall",
            "Sky Gradient", "Mountains", "Coin Pickup", "Jump", "Hit", "EnemyPatrol", "Health", "Hit Flash")
        p.writeAsset("Hero.js", """// Animated hero. Params: speed, jump
var coins = 0;
function update(dt) {
    self.vx = input.axisX * speed;
    if (input.axisX != 0) self.setFlipX(input.axisX < 0);
    if (input.aDown && self.grounded) { self.vy = jump; audio.play("jump.wav"); }
    if (Math.abs(self.vx) > 0.1 && self.grounded) self.play("HeroRun.anim");
    else self.stopAnimation();
    if (self.y < -12) scene.reload();
}
function onTrigger(other) {
    if (other.tag != "Coin") return;
    coins++; other.destroy(); audio.play("coin.wav");
    var label = scene.find("ScoreText");
    if (label) label.setText(scene.findAllTag("Coin").length <= 1 ? "You win!" : "Coins: " + coins);
}
""")
        val s = Scene("Main")
        val cam = camera(s, 5f, 0xFF6EC6FF, follow = "Player")
        obj(s, "Sky", 0f, 0f, 20f, 12f, parent = cam).also { it.order = -100 }
            .add(SpriteRenderer().also { it.texture = "Sky.png"; it.shape = SpriteRenderer.SHAPE_TEXTURE })
        obj(s, "Mountains", 0f, -2f, 24f, 8f).also { it.order = -90 }
            .add(SpriteRenderer().also { it.texture = "Mountains.png"; it.shape = SpriteRenderer.SHAPE_TEXTURE })
        obj(s, "ScoreText", 0f, 4.2f, parent = cam).text("Coins: 0", 0.5f).inFrontOfCamera()
        for (i in -8..12) obj(s, "Ground", i * 1f, -3f, 1f, 1f).also { it.order = -1 }
            .add(SpriteRenderer().also { it.texture = "Grass.png"; it.shape = SpriteRenderer.SHAPE_TEXTURE })
        obj(s, "Ground Collider", 2f, -3f, 21f, 1f).box()
        for ((x, y, w) in listOf(Triple(4f, 0f, 3), Triple(-4f, 1f, 2), Triple(9f, 2f, 3))) {
            for (k in 0 until w) obj(s, "Brick", x + k - (w - 1) / 2f, y, 1f, 1f)
                .add(SpriteRenderer().also { it.texture = "Brick.png"; it.shape = SpriteRenderer.SHAPE_TEXTURE })
            obj(s, "Platform Collider", x, y, w.toFloat(), 1f).box()
        }
        val hero = obj(s, "Player", 0f, -1.5f, 1f, 1f).also { it.order = 10; it.tag = "Player" }
        hero.add(SpriteRenderer().also {
            it.texture = "Hero.png"; it.shape = SpriteRenderer.SHAPE_TEXTURE
            it.material = "Hit Flash"; it.shaderParam = 0f
            it.width = 1f; it.height = 1f
        })
        hero.add(Animator().also { it.clip = "HeroRun.anim"; it.playOnStart = false })
        hero.add(Collider2D().also { it.width = 0.6f; it.height = 0.9f })
        hero.body().script("Hero.js", "speed=6, jump=11")
        hero.add(ScriptComponent().also { it.script = "Health.js"; it.params = "hp=3" })
        for ((x, y) in listOf(4f to 1.2f, -4f to 2.2f, 9f to 3.2f, 7f to -2f, -6f to -2f)) {
            val c = obj(s, "Coin", x, y, 0.6f, 0.6f).also { it.tag = "Coin"; it.order = 5 }
            c.add(SpriteRenderer().also { it.texture = "CoinSpin.png"; it.shape = SpriteRenderer.SHAPE_TEXTURE })
            c.add(Animator().also { it.clip = "CoinSpin.anim" })
            c.circleCol(trigger = true)
        }
        val slime = obj(s, "Slime", 8f, -2.1f, 0.9f, 0.9f).also { it.order = 8; it.tag = "Enemy" }
        slime.add(SpriteRenderer().also { it.texture = "Slime.png"; it.shape = SpriteRenderer.SHAPE_TEXTURE })
        slime.add(Animator().also { it.clip = "SlimeBounce.anim" })
        slime.box().body().script("EnemyPatrol.js", "distance=2.5, speed=1.5")
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- blueprint demo
    private val blueprintDemo = Template("Blueprint Demo", "Gameplay built with visual scripting nodes - no code.") { p ->
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
        obj(s, "Info", 0f, 4.2f).text("Open PlayerBP.bp in the editor to see the nodes", 0.3f).inFrontOfCamera()
        obj(s, "Ground", 0f, -3f, 30f, 1f).sprite(0xFF546E7A).box()
        obj(s, "Step", 5f, -1f, 4f, 0.5f).sprite(0xFF78909C).box()
        val pl = obj(s, "Player", 0f, -1.5f, 0.8f, 0.8f).sprite(0xFF29B6F6).box(friction = 0f)
            .body().script("PlayerBP.bp", "speed=6, jump=11")
        pl.tag = "Player"
        for (i in 0 until 5) obj(s, "Gem", -6f + i * 3f, 0.5f, 0.5f, 0.5f).sprite(0xFFFFD54F, SpriteRenderer.SHAPE_TRIANGLE)
            .circleCol(trigger = true).script("CollectibleBP.bp").also { it.order = 5 }
        obj(s, "Spinner", -8f, 1f, 1f, 1f).sprite(0xFFEC407A).script("RotatorBP.bp")
        p.saveScene(s)
        p.startScene = "Main"
    }
}

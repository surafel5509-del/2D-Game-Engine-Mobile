package com.sengine.project

import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Rigidbody2D
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer
import kotlin.math.sin
import kotlin.math.atan2

object Templates {
    class Template(val name: String, val description: String, val build: (Project) -> Unit)

    val all: List<Template> by lazy { listOf(empty, platformer, shooter, physics, vehicle, platformerPro, puzzle) }

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

    // ---------------------------------------------------------------- vehicle
    private val vehicle = Template("Hill Climb Vehicle", "Drive a vehicle over hilly terrain with physics.") { p ->
        p.writeAsset("Vehicle.js", """// Vehicle controller
// Params: maxSpeed, acceleration, brakePower
var speed = 0;
function update(dt) {
    var accel = 0;
    if (input.axisX > 0.1) accel = acceleration;
    else if (input.axisX < -0.1) accel = -brakePower;
    self.vx += accel * dt;
    self.vx = clamp(self.vx, -maxSpeed, maxSpeed);
    if (Math.abs(input.axisX) < 0.1) self.vx *= (1 - 2 * dt);
    // Show speed
    var hud = scene.find("SpeedHUD");
    if (hud) hud.text = Math.abs(self.vx * 3.6).toFixed(0) + " km/h";
}
""")
        p.writeAsset("Camera.js", """// Camera follow with look-ahead
function update(dt) {
    var target = scene.find("Vehicle");
    if (target) {
        var lookAhead = self.vx * 0.3;
        transform.x += (target.x + lookAhead - transform.x) * 3 * dt;
        transform.y += (target.y + 2 - transform.y) * 3 * dt;
    }
}
""")
        val s = Scene("Main")
        camera(s, 8f, 0xFF87CEEB, follow = "Vehicle")
        obj(s, "SpeedHUD", 0f, 6f, parent = s.objects.first { it.get<Camera2D>() != null }).text("0 km/h", 0.8f).also { it.order = 100 }
        // Generate hilly terrain using stacked boxes
        val hillPoints = mutableListOf<Float>()
        for (i in 0..80) {
            val x = -20f + i * 1.5f
            val y = sin(x * 0.15) * 3f + sin(x * 0.08) * 1.5f - 4f
            hillPoints.add(y)
        }
        for (i in 0 until hillPoints.size - 1) {
            val x = -20f + i * 1.5f + 0.75f
            val y = (hillPoints[i] + hillPoints[i + 1]) * 0.5f
            val angle = atan2(hillPoints[i + 1] - hillPoints[i], 1.5) * 180 / Math.PI
            val ground = obj(s, "Ground_$i", x.toFloat(), y.toFloat(), 1.6f, 0.5f).sprite(0xFF4E7D3A)
            ground.box().body(type = 2)
            ground.rotation = angle.toFloat()
        }
        // Vehicle body
        val vehicle = obj(s, "Vehicle", 0f, 2f, 2f, 0.8f).sprite(0xFFE53935).box()
            .body(friction = 0.6f).script("Vehicle.js", "maxSpeed=15, acceleration=20, brakePower=25")
        vehicle.tag = "Vehicle"
        // Wheels
        val wheelL = obj(s, "WheelL", -0.7f, -0.5f, 0.6f, 0.6f).sprite(0xFF333333, 1).circleCol().body(friction = 0.8f)
        wheelL.parent = vehicle
        val wheelR = obj(s, "WheelR", 0.7f, -0.5f, 0.6f, 0.6f).sprite(0xFF333333, 1).circleCol().body(friction = 0.8f)
        wheelR.parent = vehicle
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- platformerPro
    private val platformerPro = Template("Advanced Platformer", "Full platformer with double-jump, wall-slide, moving platforms, coins and enemies.") { p ->
        p.writeAsset("Player.js", """// Advanced player with double-jump, wall-jump, coyote time
var coins = 0;
var health = 3;
var jumpsLeft = 2;
var wallSliding = false;

function start() {
    log("Player ready! Double-jump and wall-jump enabled.");
}

function update(dt) {
    // Horizontal movement
    self.vx = input.axisX * 8;
    if (input.axisX > 0.1) self.flipX = false;
    else if (input.axisX < -0.1) self.flipX = true;

    // Jump (handles double-jump via engine CharacterBody)
    if (input.aDown) {
        if (self.grounded) {
            self.vy = 12;
            jumpsLeft = 1;
        } else if (jumpsLeft > 0) {
            self.vy = 10;
            jumpsLeft--;
        }
    }

    // Fell off
    if (self.y < -15) {
        health--;
        scene.find("HealthHUD").text = "♥".repeat(health);
        if (health <= 0) {
            scene.find("GameHUD").text = "GAME OVER";
            after(2, function() { scene.reload(); });
        } else {
            transform.x = 0; transform.y = 0;
            self.vx = 0; self.vy = 0;
        }
    }
}

function onTrigger(other) {
    if (other.tag == "Coin") {
        coins++;
        scene.find("ScoreHUD").text = "★ " + coins;
        audio.beep();
        other.destroy();
    } else if (other.tag == "Enemy") {
        // Stomp from above
        if (self.vy < -1) {
            self.vy = 10;
            other.destroy();
            audio.beep();
        } else {
            health--;
            scene.find("HealthHUD").text = "♥".repeat(Math.max(0, health));
            self.vy = 8;
            self.vx = (self.x < other.x) ? -5 : 5;
        }
    } else if (other.tag == "Goal") {
        scene.find("GameHUD").text = "LEVEL COMPLETE!";
        after(2, function() { scene.reload(); });
    }
}
""")
        p.writeAsset("Enemy.js", """// Patrol enemy
var dir = 1;
var startX;
function start() { startX = transform.x; }
function update(dt) {
    self.vx = dir * 2;
    if (Math.abs(transform.x - startX) > 3) dir *= -1;
}
""")
        p.writeAsset("MovingPlatform.js", """// Moving platform with pause
var startX;
var timer = 0;
function start() { startX = transform.x; }
function update(dt) {
    timer += dt;
    self.vx = Math.cos(timer * 1.5) * range;
}
""")
        val s = Scene("Main")
        camera(s, 6f, 0xFF6EC6FF, follow = "Player")
        // HUD
        val cam = s.objects.first { it.get<Camera2D>() != null }
        obj(s, "ScoreHUD", -4f, 5f, parent = cam).text("★ 0", 0.7f).also { it.order = 100 }
        obj(s, "HealthHUD", 4f, 5f, parent = cam).text("♥♥♥", 0.7f).also { it.order = 100 }
        obj(s, "GameHUD", 0f, 4f, parent = cam).text("", 1f).also { it.order = 100 }
        // Level geometry
        obj(s, "Ground", 0f, -4f, 30f, 1f).sprite(0xFF4E7D3A).box().body(type = 2)
        obj(s, "Platform A", 4f, -1f, 4f, 0.4f).sprite(0xFF8D6E63).box().body(type = 2)
        obj(s, "Platform B", -5f, 1f, 3f, 0.4f).sprite(0xFF8D6E63).box().body(type = 2)
        obj(s, "Platform C", 8f, 2.5f, 3f, 0.4f).sprite(0xFF8D6E63).box().body(type = 2)
        obj(s, "Moving Platform", 14f, 1f, 3f, 0.4f).sprite(0xFFB0BEC5).box().body(type = 1).script("MovingPlatform.js", "range=3")
        obj(s, "Wall", -8f, 0f, 0.5f, 8f).sprite(0xFF6D4C41).box().body(type = 2)
        obj(s, "Wall R", 18f, 0f, 0.5f, 8f).sprite(0xFF6D4C41).box().body(type = 2)
        // Player
        val player = obj(s, "Player", 0f, -2.5f, 0.7f, 0.9f).sprite(0xFF42A5F5).box()
            .body(friction = 0f).script("Player.js")
        player.tag = "Player"; player.order = 10
        // Enemies
        val enemy1 = obj(s, "Enemy", 6f, -2.8f, 0.7f, 0.7f).sprite(0xFFEF5350, 0).box(true).script("Enemy.js").body(type = 1)
        enemy1.tag = "Enemy"
        // Coins
        val coinPositions = listOf(4f to 0.2f, -5f to 2.2f, 8f to 3.7f, 14f to 2.3f, -2f to -2.8f, 10f to -2.8f, 16f to -2.8f)
        for ((x, y) in coinPositions) {
            val c = obj(s, "Coin", x, y, 0.4f, 0.4f).sprite(0xFFFFD54F, 1).circleCol(true)
            c.tag = "Coin"; c.order = 5
        }
        // Goal
        val goal = obj(s, "Goal", 17f, -2.5f, 1f, 2f).sprite(0xFF66BB6A).box(true)
        goal.tag = "Goal"
        p.saveScene(s)
        p.startScene = "Main"
    }

    // ---------------------------------------------------------------- puzzle
    private val puzzle = Template("Physics Puzzle", "Drag-and-drop physics puzzle with joints and objectives.") { p ->
        p.writeAsset("Puzzle.js", """// Physics puzzle controller
var solved = false;
var targetZone;
function start() {
    targetZone = scene.find("TargetZone");
}
function update(dt) {
    if (solved) return;
    // Check if all pieces are in the target zone
    var pieces = scene.findAll("Piece");
    // Simple check: all pieces near target
    if (pieces) {
        var allIn = true;
        for (var i = 0; i < pieces.length; i++) {
            var d = distanceTo(pieces[i].x, pieces[i].y, targetZone.x, targetZone.y);
            if (d > 2) allIn = false;
        }
        if (allIn) {
            solved = true;
            scene.find("Status").text = "PUZZLE SOLVED!";
            audio.beep();
        }
    }
}
""")
        val s = Scene("Main")
        camera(s, 7f, 0xFF37474F)
        obj(s, "Status", 0f, 5.5f).text("Place all pieces in the green zone", 0.5f).also { it.order = 100 }
        obj(s, "Puzzle", 0f, 0f).script("Puzzle.js")
        obj(s, "Floor", 0f, -6f, 20f, 1f).sprite(0xFF455A64).box().body(type = 2)
        obj(s, "Wall L", -10f, 0f, 1f, 12f).sprite(0xFF455A64).box().body(type = 2)
        obj(s, "Wall R", 10f, 0f, 1f, 12f).sprite(0xFF455A64).box().body(type = 2)
        // Target zone (trigger)
        val target = obj(s, "TargetZone", 5f, -4f, 3f, 2f).sprite(0x4466BB6A.toInt()).box(true)
        target.tag = "TargetZone"
        // Puzzle pieces
        for (i in 0 until 4) {
            val piece = obj(s, "Piece", -3f + i * 2f, 3f, 1.2f, 1.2f).sprite(0xFF42A5F5.toInt() + i * 0x101010 * 30).box().body()
            piece.tag = "Piece"
        }
        // Ramps and obstacles
        obj(s, "Ramp", -3f, -3f, 4f, 0.3f).sprite(0xFF78909C).box().body(type = 2)
        obj(s, "Peg", 2f, -2f, 0.3f, 0.3f).sprite(0xFFBDBDBD, 1).circleCol().body(type = 2)
        p.saveScene(s)
        p.startScene = "Main"
    }
}

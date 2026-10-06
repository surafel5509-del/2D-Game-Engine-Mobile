package com.sengine.project

/**
 * Ready-to-use 2D gameplay scripts offered by the script editor's snippet menu and written into
 * new projects. Every script uses only real engine APIs and is valid JavaScript.
 */
object ScriptTemplates {

    private val templates: LinkedHashMap<String, String> = linkedMapOf(
        "Basic behaviour" to """
// Runs every frame. `self` is this object, `dt` is the delta time in seconds.
var speed = 3;

function start() {
    log(self.name + " ready at " + self.worldX + "," + self.worldY);
}

function update(dt) {
    self.x += input.axisX * speed * dt;
    self.y += input.axisY * speed * dt;
    if (input.tapped) {
        self.burst(12);
        audio.playAt("hit.wav", self.worldX, self.worldY, 0.8);
    }
}
""".trimIndent(),

        "Platformer controller" to """
// Side-scrolling character: run, jump, coyote time, one extra air jump.
var moveSpeed = 6;
var jumpForce = 13;
var airJumps = 1;
var jumpsLeft = airJumps;
var coyote = 0.0;

function update(dt) {
    var axis = input.axisX;
    self.vx = axis * moveSpeed;
    if (self.grounded) {
        coyote = 0.12;
        jumpsLeft = airJumps;
    } else {
        coyote = Math.max(0, coyote - dt);
    }
    var wantJump = input.aDown || input.keyDown("space");
    if (wantJump && (self.grounded || coyote > 0)) {
        self.vy = jumpForce;
        coyote = 0;
        audio.play("jump.wav");
        self.burst(8);
    } else if (wantJump && jumpsLeft > 0) {
        jumpsLeft = jumpsLeft - 1;
        self.vy = jumpForce * 0.85;
    }
    // variable jump height: release early = shorter jump
    if (!input.a && self.vy > 4) self.vy = self.vy * 0.9;
    if (Math.abs(axis) > 0.1) {
        self.setFlipX(axis < 0);
        self.playState("run");
    } else {
        self.playState("idle");
    }
}
""".trimIndent(),

        "Top-down controller" to """
// 8-way movement with acceleration and sprite flipping (RPGs, twin-stick shooters).
var speed = 4.5;
var accel = 30;

function update(dt) {
    var ax = input.axisX;
    var ay = input.axisY;
    var len = Math.sqrt(ax * ax + ay * ay);
    if (len > 1) { ax /= len; ay /= len; }
    self.vx = approach(self.vx, ax * speed, accel * dt);
    self.vy = approach(self.vy, ay * speed, accel * dt);
    if (len > 0.1) self.rotation = angleTo(0, 0, ax, ay);
}
""".trimIndent(),

        "Shooter / bullets" to """
// Tap or press A to fire a bullet prefab towards the aim direction.
var fireDelay = 0.18;
var bulletSpeed = 14;
var nextFire = 0;

function update(dt) {
    nextFire -= dt;
    var firing = input.a || input.touching;
    if (firing && nextFire <= 0) {
        nextFire = fireDelay;
        var dir = input.touchCount > 0
            ? angleTo(self.worldX, self.worldY, input.worldX(0), input.worldY(0))
            : self.rotation;
        var b = scene.instantiate("Bullet.prefab", self.worldX, self.worldY);
        if (b) {
            b.rotation = dir;
            var rad = dir * Math.PI / 180;
            b.setVelocity(Math.cos(rad) * bulletSpeed, Math.sin(rad) * bulletSpeed);
        }
        audio.play("shoot.wav");
        camera.shake(0.15);
    }
}
""".trimIndent(),

        "Enemy AI patrol" to """
// Patrols between two x positions, turns at edges/walls, damages the player on contact.
var left = -4;
var right = 4;
var speed = 2.5;
var dir = 1;
var damage = 10;

function update(dt) {
    self.vx = dir * speed;
    if (self.worldX < left) dir = 1;
    if (self.worldX > right) dir = -1;
    self.setFlipX(dir < 0);
    self.playState("walk");
}

function onCollision(other) {
    if (other.tag == "Player") {
        other.damage(damage, self.worldX, self.worldY);
        other.knockback(other.worldX - self.worldX, 0.4, 6);
    }
}
""".trimIndent(),

        "Camera follow" to """
// Smoothed follow with look-ahead and shake when the player gets hit.
var target = "Player";
var strength = 5;
var lookAhead = 0.35;

function start() { camera.follow(target); camera.setSize(5.5); }

function update(dt) {
    var p = scene.find(target);
    if (!p) return;
    // the camera component reads look-ahead from the object velocity
    camera.setPosition(
        damp(camera.x, p.worldX + p.vx * lookAhead, strength, dt),
        damp(camera.y, p.worldY + p.vy * lookAhead * 0.5, strength, dt)
    );
}
""".trimIndent(),

        "Pickups and score" to """
// Coin/pickup: adds score, plays a sound, spawns a sparkle and destroys itself.
var points = 1;

function start() { }

function onTrigger(other) {
    if (other.tag != "Player") return;
    var total = (save.getNumber("score", 0) + points);
    save.set("score", total);
    ui.setText("ScoreLabel", "Score: " + total);
    particles.burst("spark", self.worldX, self.worldY, 16);
    audio.play("coin.wav");
    self.destroy();
}
""".trimIndent(),

        "Checkpoint and respawn" to """
// Saves a respawn point, updates the HUD and flashes when activated.
var active = false;

function onTrigger(other) {
    if (active || other.tag != "Player") return;
    active = true;
    save.set("spawnX", self.worldX);
    save.set("spawnY", self.worldY);
    ui.setText("HintLabel", "Checkpoint saved");
    particles.burst("magic", self.worldX, self.worldY, 24);
    audio.play("checkpoint.wav");
}

function respawn(other) {
    var x = save.getNumber("spawnX", 0);
    var y = save.getNumber("spawnY", 0);
    other.teleport(x, y);
    other.setVelocity(0, 0);
}
""".trimIndent(),

        "Vehicle controls (Hill Climb)" to """
// Throttle/brake from the on-screen controls with a flip-recovery boost.
var maxThrottle = 1.0;
var airControl = 55;

function update(dt) {
    self.throttle = input.axisX;
    if (input.aDown) self.throttle = 1;
    if (input.b) self.throttle = -1;

    if (self.airborne) {
        // rotate the chassis in the air so the player can save a bad landing
        self.addTorque(-input.axisX * airControl * dt * 60);
    }
    if (self.vehicleSpeedKmh > 45) camera.shake(0.05);
    ui.setText("SpeedLabel", Math.round(self.vehicleSpeedKmh) + " km/h");
}
""".trimIndent(),

        "Health and damage" to """
// Health bar wiring, knockback, hit flash and death handling.
var maxHp = 100;
var flashTime = 0.15;
var timer = 0;

function start() {
    self.health = maxHp;
    ui.setProgress("HealthBar", 1);
}

function update(dt) {
    timer = Math.max(0, timer - dt);
    self.setAlpha(timer > 0 ? 0.5 : 1);
    ui.setProgress("HealthBar", self.health / maxHp);
}

function takeDamage(amount, fromX, fromY) {
    if (self.damage(amount, fromX, fromY)) {
        timer = flashTime;
        camera.shake(0.4);
        audio.playAt("hit.wav", self.worldX, self.worldY, 1);
        particles.burst("impact", self.worldX, self.worldY, 10);
    }
    if (self.isDead()) {
        self.goLimp();
        after(1.2, function () { scene.reload(); });
    }
}
""".trimIndent(),

        "Particle effects" to """
// Composite effect: muzzle flash, smoke trail, explosion on impact.
var effect = "explosion";

function start() {
    self.setEmitterPreset("smoke");
    self.startEmitting();
}

function update(dt) {
    if (input.bDown) {
        self.burst(40);
        camera.shake(0.7);
        audio.playAt("explosion.wav", self.worldX, self.worldY, 1);
    }
    if (input.aDown) {
        var fx = particles.play(effect, self.worldX, self.worldY, 2.5);
        if (fx) camera.shake(0.3);
    }
}
""".trimIndent(),

        "Coroutines / cutscene" to """
// Sequenced intro: fade the UI in, move the camera, then enable gameplay.
var running = false;

function start() {
    if (running) return;
    running = true;
    ui.setVisible("IntroPanel", true);
    ui.setText("IntroLabel", "Get ready...");

    tasks.after(1.5, function () {
        ui.setText("IntroLabel", "Go!");
        camera.shake(0.4);
        audio.play("start.wav");
    });
    tasks.after(2.5, function () {
        ui.setVisible("IntroPanel", false);
        self.emit("gameplayStarted");
    });
    tasks.tween(0, 1, 1.0, function (v) {
        ui.setProgress("LoadBar", v);
    });
}
""".trimIndent(),

        "Endless runner spawner" to """
// Spawns obstacles ahead of the player at a randomised rhythm and speeds up over time.
var spawnEveryMin = 0.7;
var spawnEveryMax = 1.4;
var timer = 1.0;
var speed = 6;
var distance = 0;

function update(dt) {
    timer -= dt;
    distance += speed * dt;
    speed = 6 + Math.min(6, distance / 200);
    if (timer <= 0) {
        timer = random(spawnEveryMin, spawnEveryMax);
        var lane = randomInt(0, 2);
        var obstacle = scene.instantiate("Obstacle.prefab", 14, -3 + lane * 2.2);
        if (obstacle) obstacle.setVelocity(-speed, 0);
        ui.setText("ScoreLabel", "Distance: " + Math.round(distance) + " m");
    }
}
""".trimIndent(),

        "Save / load progress" to """
// Persists level progress and coins; call saveAll() from a pause menu.
function collect(c) { save.set("coins", save.getNumber("coins", 0) + c); }

function saveAll() {
    save.save("slot1");
    ui.toast("Progress saved");
}

function loadAll() {
    if (save.load("slot1")) {
        ui.setText("ScoreLabel", "Coins: " + save.getNumber("coins", 0));
    }
}
""".trimIndent(),

        "Screen flow (menus)" to """
// Menu logic: play/pause, scene switching and dialog helpers.
function onTap() {
    if (self.name == "PlayButton") { scene.emit("startGame"); }
    if (self.name == "Level2Button") { scene.load("Level2"); }
    if (self.name == "QuitButton") { confirmQuit(); }
}

function confirmQuit() {
    ui.confirm("Quit", "Return to the level select?", function () {
        scene.load("MainMenu");
    });
}

function start() {
    scene.on("startGame", function () { scene.load("Level1"); });
}
""".trimIndent(),

        "Moving platform rider" to """
// Rides a MovingPlatform: the platform carries the player between waypoints.
var rideSpeed = 2.2;
var waypoints = [[0, 0], [6, 0], [6, 3], [0, 3]];
var index = 0;
var progress = 0;

function update(dt) {
    var a = waypoints[index];
    var b = waypoints[(index + 1) % waypoints.length];
    var dist = Math.max(0.001, dist(a[0], a[1], b[0], b[1]));
    progress += (rideSpeed / dist) * dt;
    if (progress >= 1) { progress = 0; index = (index + 1) % waypoints.length; }
    self.x = lerp(a[0], b[0], progress);
    self.y = lerp(a[1], b[1], progress);
}
""".trimIndent()
    )

    val names: List<String> = templates.keys.toList()

    fun get(name: String): String = templates[name] ?: basic()

    fun basic(): String = templates.values.first()

    /** Writes the standard helper scripts into a new project. */
    fun install(project: Project) {
        project.writeAsset("Player.js", templates["Platformer controller"]!!)
        project.writeAsset("Enemy.js", templates["Enemy AI patrol"]!!)
        project.writeAsset("Coin.js", templates["Pickups and score"]!!)
        project.writeAsset("Camera.js", templates["Camera follow"]!!)
        project.writeAsset("Vehicle.js", templates["Vehicle controls (Hill Climb)"]!!)
    }
}

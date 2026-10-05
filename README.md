# S Engine 2D — Professional Mobile Game Engine

A powerful, production-ready 2D game engine for Android built entirely in Kotlin. Create commercial-quality mobile games including platformers, racing games, physics puzzles, shooters, and more — all from your phone or tablet.

## 🎮 Engine Features

### Advanced 2D Physics
- **Rigid bodies** — Dynamic, kinematic, and static body types
- **Collision detection** — Box, circle, and capsule colliders with offset support
- **Impulse-based resolution** — Gravity, friction, restitution, drag
- **Collision layers & masks** — 32-bit layer system for selective collision
- **Joints** — Distance, hinge, spring, wheel, and rope joints
- **Raycasting** — Point queries, ray casts, overlap circle/area queries
- **Character body** — Grounded detection, coyote time, jump buffering, wall-slide, double-jump, step-up
- **Vehicle physics** — Wheel suspension, torque, steering, terrain interaction
- **Ragdoll** — Physics-based character death/fall simulation

### Rendering & Animation
- **OpenGL ES 2.0 renderer** — Batched sprite rendering with shape & texture support
- **Sprite-sheet animation** — Multi-animation state machine with frame events
- **Particle system** — Configurable emitter with gravity, color/size over lifetime, rotation
- **TileMap system** — Multi-layer grid maps with collision generation and auto-tiling
- **Terrain system** — Procedural heightmap terrain (Hill Climb Racing style)
- **Camera system** — Follow with smoothing, dead zone, look-ahead, shake, zoom, boundary limits
- **Sorting layers** — Per-object draw order

### Scripting
- **JavaScript (Rhino)** — Write game logic in JavaScript
- **Rich API** — Transform, physics, input, audio, scene, camera, signals, save/load
- **Event callbacks** — `start()`, `update(dt)`, `onCollision(other)`, `onTrigger(other)`, `onTap()`, `onDestroy()`
- **Signal/Event system** — Global and object-level named signals for decoupled communication
- **Timers** — `after(seconds, fn)`, `every(seconds, fn)`
- **Math utilities** — `random()`, `clamp()`, `lerp()`, `distanceTo()`, `normalize()`, `reflect()`, etc.
- **Physics queries** — `physics.raycast()`, `physics.overlapCircle()`, `physics.overlapArea()`

### Audio
- **SoundPool-based** — Low-latency SFX playback with 12+ simultaneous streams
- **Per-source control** — Volume, pitch, looping, spatial blend
- **Music support** — WAV, OGG, MP3, M4A formats

### Input System
- **Touch** — On-screen touch with world-coordinate conversion
- **Virtual joystick** — Configurable floating joystick for mobile controls
- **Keyboard** — WASD, arrow keys, space, enter
- **Game controller** — D-pad, analog stick, A/B buttons
- **Input mapping** — Unified axis/button abstraction

### In-Game UI
- **UI Canvas** — Screen-space overlay rendering
- **Button, Label, ProgressBar, Slider, Image, VirtualJoystick** — Built-in UI elements
- **Anchoring** — 9-point anchor system for responsive layouts
- **Signals** — Click events, value changes

### Scene & Architecture
- **Scene/Node/Component** — Entity-component architecture with hierarchy
- **Signals/Events** — Decoupled communication between systems
- **Object pooling** — Eliminate GC pressure with pre-allocated object pools
- **Save/Load system** — Full scene state persistence with key-value game data
- **Checkpoint system** — Automatic save at designated points
- **Resource manager** — Asset caching, async loading, reference counting

### Debugging & Profiling
- **Profiler** — Frame timing, draw calls, physics stats, memory usage
- **Debug console** — In-game command console with custom commands
- **Debug overlay** — Real-time FPS, object count, physics info
- **Physics debugger** — Visualize colliders, joints, raycasts
- **Engine signals** — Built-in events for all major engine occurrences

### Visual Editor
- **Scene Tree** — Hierarchical object browser
- **Inspector** — Component property editing with live preview
- **2D Viewport** — Pan, zoom, grid, snap-to-grid
- **Transform tools** — Move, rotate, scale with gizmos
- **Animation timeline** — Visual keyframe editing
- **Asset manager** — Import images, scripts, sounds
- **Project management** — Create, duplicate, export/import projects

## 🎯 Game Templates

| Template | Description |
|----------|-------------|
| **Empty 2D** | Camera + square. Start from scratch. |
| **Platformer Demo** | Run, jump, collect coins. Joystick + A button. |
| **Advanced Platformer** | Double-jump, wall-slide, enemies, moving platforms, HUD. |
| **Space Shooter** | Top-down shooter with spawning, triggers, score. |
| **Physics Sandbox** | Tap to drop bouncy balls and boxes. |
| **Hill Climb Vehicle** | Drive a vehicle over hilly terrain with physics. |
| **Physics Puzzle** | Drag-and-drop puzzle with joints and objectives. |

## 🏗 Architecture

```
com.sengine.engine/
├── core/          Scene, GameObject, Component, SignalBus, TileMap, Terrain, CameraSystem, ObjectPool
├── physics/       PhysicsWorld, Joints (Distance/Hinge/Spring/Wheel/Rope), Raycaster, CharacterBody, VehiclePhysics, Ragdoll
├── render/        Renderer2D, SceneRenderer, View2D, TextureCache, EditorState
├── animation/     SpriteAnimator, AnimationStateMachine
├── script/        ScriptSystem (Rhino JS), API bindings
├── ui/            UICanvas, UIButton, UILabel, UIProgressBar, UISlider, UIVirtualJoystick
├── resource/      ResourceManager, TextureAtlasManager, PrefabManager
├── save/          SaveSystem, CheckpointSystem
├── debug/         Profiler, DebugConsole, DebugOverlay
├── math/          Affine transform, Vector2D
├── AudioSystem    SoundPool-based audio
├── Input          Multi-input abstraction
└── Engine         Main game loop & system integration
```

## 📱 Building

```bash
./gradlew assembleDebug
```

The APK installs directly on any Android device. No computer needed to create or play games.

## 📜 Scripting Example

```javascript
// Player controller with double-jump and wall-jump
var speed = 6;
var jumpForce = 12;
var coins = 0;

function start() {
    log("Player ready!");
}

function update(dt) {
    // Movement
    self.vx = input.axisX * speed;
    if (input.axisX > 0.1) self.flipX = false;
    else if (input.axisX < -0.1) self.flipX = true;

    // Jump (CharacterBody handles double-jump, coyote time, etc.)
    if (input.aDown && self.grounded) {
        self.vy = jumpForce;
    }

    // Fell off the world
    if (self.y < -15) scene.reload();
}

function onTrigger(other) {
    if (other.tag == "Coin") {
        coins++;
        scene.find("ScoreText").text = "★ " + coins;
        audio.beep();
        other.destroy();
    }
}

function onCollision(other) {
    if (other.tag == "Enemy" && self.vy < 0) {
        // Stomp!
        self.vy = 10;
        other.destroy();
    }
}
```

## 🔧 Professional Features

### Collision Layers
```javascript
// Set layer via component properties
// Layer 0 = default, layers 1-31 for custom grouping
// Mask controls which layers this body collides with
```

### Raycasting
```javascript
function update(dt) {
    var hit = physics.raycast(self.x, self.y, 0, -1, 10);
    if (hit) {
        log("Hit " + hit.gameObject.name + " at distance " + hit.distance);
    }
}
```

### Signals
```javascript
// Global events
signals.emit1("score_changed", newScore);
signals.connect("score_changed", function(score) { ... });

// Object events
function start() {
    on("custom_event", myHandler);
}
```

### Save/Load
```javascript
function update(dt) {
    if (input.tapped) {
        saveGame("highScore", coins);
        scene.save(0); // Full scene snapshot
    }
}
```

### Camera Control
```javascript
function update(dt) {
    // Shake on explosion
    cameraShake(0.5, 0.3);
    // Zoom for dramatic effect
    cameraZoom(8);
}
```

### Object Pooling
```javascript
// Pre-register pool
// Then in scripts:
var bullet = spawnPooled("Bullet", self.x, self.y + 1);
// Later:
releasePooled(bullet);
```

## 📄 License

This project is open source. See LICENSE file for details.

---

**S Engine 2D v2.0** — Built for mobile, designed for professionals.

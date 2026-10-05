# S Engine 2D

S Engine is an Android-first, strictly 2D Kotlin game editor and runtime. It lets you create a project on-device, edit scenes and JavaScript behaviours, preview the game, and build the editor APK in CI. It uses OpenGL ES 2.0 for rendering and Rhino for interpreted JavaScript.

> **Scope:** S Engine is an actively developed lightweight engine, not a Godot-compatible engine yet. The feature list below describes implemented behaviour in this repository; unfinished systems are called out explicitly rather than presented as complete.

## What works today

### Scene editor and project workflow

- Project manager with Empty 2D, Platformer, Space Shooter, Physics Sandbox and Top-Down Racer starters.
- Scene hierarchy, object selection, transform editing, parent/reparent, add/remove components, duplicate/delete, scene switching and save/load.
- 2D viewport with grid, pan/zoom, transform gizmos, collider visualization, snapping, play/pause/step, undo/redo and a live console.
- Project asset browser for image, sound, JavaScript and GLSL source files; texture previews; source editing with undo/redo.
- Built-in offline library of 100 original CC0-1.0 starter assets: 20 characters, 20 objects, 20 backgrounds, 16 synthesized WAV effects, 12 JavaScript behaviors and 12 GLSL sources. Assets can be previewed and copied into a project.
- Full-screen in-app game preview with touch controls; Android orientation settings per project.

### Strictly 2D runtime

- GLES 2.0 orthographic renderer for tinted sprites, shapes, text and CPU particles.
- Stable-order texture batching (preserves alpha draw order), sprite-atlas UV regions and sprite-sheet animation.
- Camera follow with smoothing, offset, velocity look-ahead, bounds, shake and pixel-perfect camera mode.
- Scene serialization, parent transforms, tags, sorting order, script lifecycle callbacks, timers, signals/messages, scene reload/load and game-object duplication.
- JavaScript API for transforms, input, physics queries, audio, particles, animation, scene access and logs.
- SoundPool-based short sound effects and a built-in beep fallback; mobile virtual joystick/buttons plus keyboard/controller buttons.

### Physics implemented

- Dynamic, kinematic and static bodies; box and circle colliders; triggers; gravity; drag; friction and restitution.
- 32-bit collision layers and mutually checked masks.
- Accumulated forces, impulses, torque and angular velocity. Rotated boxes use a 2D SAT narrow phase.
- Pin, distance and damped spring joints support world anchors or named connected bodies; connected-body collision filtering and joint counts are exposed to the editor.
- Fixed-step simulation with a sweep-and-prune broad phase and optional adaptive substeps for continuous bodies.
- Raycasts, circle casts, point/circle/area overlap queries and collision/trigger enter/exit callbacks.
- A live physics summary in the editor viewport.

The solver is intentionally compact. It does **not** currently implement a complete Box2D/Godot-style contact manifold solver, joint warm-starting/limits/motors, wheel suspension, ragdolls, or terrain collision.

## Build and test

Requirements: JDK 17, Gradle 8.7, and Android SDK platform 34/build tools available to Gradle. The source snapshot does not include Gradle's wrapper JAR, so install Gradle 8.7 on `PATH` (the `gradlew` script falls back to it).

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

The installable editor APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions installs Gradle 8.7, runs the JVM/unit simulation tests, and uploads the debug APK and test reports. Tag builds can produce a privately signed editor APK when the signing secrets documented in `.github/workflows/release-apk.yml` are configured.

### Export and build a separate game APK

Open a project, choose **Main menu → Export Android Game Project**, set the display title/package id, and save the Gradle project ZIP. It contains the 2D player runtime without the editor, plus that project's scenes/assets, orientation, build configuration and a GitHub Actions workflow. Open it in Android Studio and run `gradle assembleDebug`, or push the ZIP's contents to GitHub: the included workflow builds and publishes a debug APK artifact. For a signed release, configure the documented `SENGINE_KEYSTORE_*` secrets and push a `v*` tag.

Download the APK artifact, then use **Main menu → Install Game APK** in S Engine. Android will ask for the system's “install unknown apps” permission and show its standard package-installer confirmation. APK compilation is done on a computer/CI with Android SDK tools; the Android editor itself does not pretend to run Gradle on-device.

A release APK is **not** signed with the public Android debug key. Configure a private keystore before running `gradle assembleRelease`:

```bash
export SENGINE_KEYSTORE_PATH=/secure/path/release.jks
export SENGINE_KEYSTORE_PASSWORD='…'
export SENGINE_KEY_ALIAS='release'
export SENGINE_KEY_PASSWORD='…'
gradle assembleRelease
```

The four values can also be supplied as Gradle properties. Do not commit keystores or passwords. The release artifact is unsigned when signing values are absent.

## Scripting

Each Script component has its own JavaScript scope. Supported callbacks include `start`, `update(dt)`, `onCollision`, `onCollisionExit`, `onTrigger`, `onTriggerExit`, `onTap`, `onDestroy` and `onStop`.

```javascript
function update(dt) {
    self.vx = input.axisX * 6;

    if (input.aDown && self.grounded) {
        self.addImpulse(0, 10);
    }

    var hit = physics.raycast(self.worldX, self.worldY, 0, -1, 5);
    if (hit) log("Hit " + hit.gameObject.name + " at " + hit.distance);

    if (input.bDown) cameraShake(0.25, 0.2);
}
```

Additional helpers include `after(seconds, fn)`, `every(seconds, fn)`, `clamp`, `lerp`, `random`, `randomInt`, `scene.find`, `scene.spawn`, `scene.load`, `audio.play`, `audio.beep`, `physics.circleCast`, `physics.overlapCircle` and `physics.overlapArea`. The in-app Script API Reference lists object properties and callbacks.

## Current limitations / roadmap

The following requested Godot-class features are **not implemented yet** and should not be inferred from project naming or future-looking APIs: a full animation timeline/state machine and event editor; a TileMap/terrain authoring tool; a general UI canvas/layout editor; arbitrary custom shader/material execution (the library's GLSL files are editable examples only); texture-atlas packing/import settings; advanced audio buses/effects; full Input Map and gesture editor; robust joint limits/motors, ropes, vehicles, ragdolls and terrain; a profiler beyond the live frame/draw/physics summary; and collaboration. Per-project Android export now creates a standalone player-only Gradle project and build workflow; actual APK compilation still requires Android SDK build tools on a computer/CI, not on the editor device.

These are meaningful larger systems, not hidden behind mock buttons. The existing scene/component architecture and project format are the extension points for implementing them incrementally.

## Repository map

```text
app/src/main/java/com/sengine/
├── engine/core/       Scene, GameObject, components and JSON serialization
├── engine/physics/    2D collision, fixed-step dynamics and query API
├── engine/render/     GLES2 renderer, batching, camera view and texture cache
├── engine/script/     Rhino runtime and JavaScript bindings
├── project/           Project storage, templates and import/export
└── ui/                Android project manager, editor, inspector and player
```

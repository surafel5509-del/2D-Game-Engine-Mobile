# S Engine — final report

**A strictly 2D game engine and editor for Android, written from scratch in Kotlin.**

* Branch: `arena/01a10c4c-2d-game-engine-mobile`
* Continuous integration: `.github/workflows/android.yml` — **green** (runs
  [#37501976218](https://github.com/surafel5509-del/2D-Game-Engine-Mobile/actions/runs/37501976218),
  [#37502698242](https://github.com/surafel5509-del/2D-Game-Engine-Mobile/actions/runs/37502698242),
  [#37503438226](https://github.com/surafel5509-del/2D-Game-Engine-Mobile/actions/runs/37503438226))
* Installable builds: [`prebuilt/SEngine-debug.apk`](../prebuilt/SEngine-debug.apk) and
  [`prebuilt/SpaceRun-game.apk`](../prebuilt/SpaceRun-game.apk), also on the rolling
  [`sengine-latest`](https://github.com/surafel5509-del/2D-Game-Engine-Mobile/releases/tag/sengine-latest) release.

---

## 1. Deliverables

| Artefact | Size | SHA-256 (first 16) | Package | What it is |
|---|---:|---|---|---|
| `prebuilt/SEngine-debug.apk` | 2 441 924 B | `d5406c9445c25c70` | `com.sengine.app` | The engine: project manager, editor, play mode. Install on Android 8.0+ (minSdk 26, target 34). |
| `prebuilt/SpaceRun-game.apk` | 2 234 353 B | `c9fe40232b7b5eb3` | `com.sengine.game.spacerun` | A standalone game **exported by the engine's own builder** from the "Space Shooter" template (versionName `1.2.3`, versionCode `7`, label "Space Run"). |

Both APKs are APK Signature Scheme **v2** signed and verified inside CI with the Android SDK's
`apksigner`:

```
Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): true
package: name='com.sengine.game.spacerun' versionCode='7' versionName='1.2.3'
application-label:'Space Run'
```

The exported game carries its project inside the APK
(`assets/game/build.json`, `assets/game/project/project.json`, `scenes/Main.scene.json`,
`assets/*.js`, `*.prefab`) and now boots **straight into the game** instead of the project manager.

Install:

```
adb install -r prebuilt/SEngine-debug.apk      # editor + player
adb install -r prebuilt/SpaceRun-game.apk      # a finished game
```

Both files are debug/test-signed (throwaway key in CI) — a store release must be signed with the
project's own keystore, which the build screen accepts.

---

## 2. Strict 2D confirmation

The engine is **strictly 2D**; there is no 3D renderer, camera, model/mesh pipeline, 3D material,
3D lighting, 3D physics, 3D transform, 3D scene concept, 3D asset format or 3D scripting API.

Audit of `app/src/main` (88 Kotlin files; ~28 300 lines across `app/src`):

| Searched | Hits | Meaning |
|---|---:|---|
| `gltf`, `glb`, `fbx`, `.obj` model loader | 0 | no 3D asset formats anywhere |
| `Vector3`, `Matrix4` (math type), `PerspectiveCamera`, `Camera3D`, `Renderer3D`, `Physics3D`, `Sprite3D` | 0 | no 3D math/camera/renderer/physics types |
| `mesh` | 5 | **comments only**, each stating that no mesh pipeline exists |
| `glUniformMatrix4fv` | 12 | the 4×4 slot every GLES 2.0 driver requires for the **orthographic** 2D projection |

* `Camera2D` is the only camera (`engine/core/RenderComponents.kt`); there is no perspective
  projection, no depth buffer, no orbit camera and no 3D gizmo mode.
* Third-party dependencies: `org.mozilla:rhino` (JavaScript in interpreted mode) plus test-only
  JUnit and `org.json`. No OpenGL 3D, no model loader, no math library, no NDK.
* Three tests enforce the rule and pass on every build:
  `EngineV2Test.assetStoreIsStrictlyTwoD`, `EngineV2Test.serializerWritesNoThreeDFields`,
  `EngineV2Test.scriptReferenceIsTwoDOnlyAndMatchesRuntime` (the scripting API reference must be
  2D-only and must match the runtime's actual globals).
* The asset store classifies every imported file (PNG, JPG, WebP, SVG, sprite sheets, fonts, OGG/MP3
  audio, `.tilemap`, `.anim`, `.mat`, `.shader`, scripts, prefabs) and rejects anything else.

---

## 3. Feature areas

| # | Area | Implementation | Notes / demo |
|---|---|---|---|
| 1 | 2D renderer | `engine/render/Renderer2D.kt`, `SceneRenderer.kt`, `GL.kt`, `Textures.kt`, `Svg.kt`, `Fonts.kt`, `PostProcessor.kt`, `View2D.kt` | GLES 2.0 sprite batcher, texture pages/atlases, flip/rotation/scale/tint/alpha, layers + sorting order, camera zoom/follow, parallax, screen shake, resolution scaling, pixel-perfect option, post FX (bloom, CRT, scanlines, chromatic, sepia, pixelate, invert, grayscale) |
| 2 | 2D physics | `engine/physics/PhysicsWorld2D.kt`, `Shapes.kt`, `Body2D.kt`, `Joints.kt`, `core/PhysicsComponents.kt` | dynamic/kinematic/static/character bodies, gravity, friction, restitution, forces, impulses, torque, sleeping, CCD, layers/masks, ray casts, shape casts, overlap queries, triggers/sensors, 8 joint types (distance, hinge, motor, prismatic, spring, rope/wheel, weld, point) |
| 3 | 2D vehicles | `engine/vehicle/Vehicle2D.kt` + the "Hill Climb Vehicle" template and its `Driver.js` | wheels, suspension spring/damper, travel limits, tyre traction, engine torque, braking, engine braking, terrain interaction, air control, stabilization (anti-flip), fuel, speed/distance telemetry |
| 4 | Characters | `engine/character/CharacterController2D.kt`, `Ragdoll2D.kt`, `core/GameplayComponents.kt` | run/jump/climb, slopes, moving platforms, ladders, wall interaction, knockback, health/damage, checkpoints, ragdoll joints |
| 5 | Particles / VFX | `engine/fx/ParticleSystem2D.kt`, `ParticleEmitter.kt`, `ParticlePresets.kt`, `Trail2D` | 24 presets (fire, smoke, explosion, sparks, dust, rain, snow, fog, steam, magic, electricity, energy, water splash, shockwave, debris, leaves, sand, exhaust, rocket flame, muzzle flash, impact, trail, speed lines, confetti), lifetime/velocity/acceleration/gravity, size+alpha curves, colour gradients, randomness, turbulence, trails, sub-emitters, collision, pooling |
| 6 | Animation | `engine/anim/AnimationClip.kt`, `Animator.kt`, `AnimationSystem.kt`, `project/ClipLibrary.kt`, `ui/AnimationEditorActivity.kt` | frame/sprite clips, grid slicing, state machine with transitions, events, timeline/keyframes, curves, loop and ping-pong, live preview |
| 7 | Tilemap / terrain | `engine/tilemap/Tilemap.kt`, `AutoTile.kt`, `TilemapCollision.kt`, `TilemapRenderer.kt`, `TilemapTools.kt` | tilesets, multi-layer maps, autotiling, terrain painting, generated colliders, animated tiles, chunked (infinite) maps, chunked rendering |
| 8 | 2D lighting | `engine/lighting/LightSystem.kt`, `Lights.kt`, `engine/render/Lighting2D.kt` | point/cone lights, directional-like 2D sun, ambient, colour/intensity/radius/inner radius/falloff, 2D shadow casting with softness, light occluders, optional normal maps, mobile-friendly single pass |
| 9 | Shaders / materials | `engine/render/Shaders.kt`, `Materials2D.kt` | dissolve, outline, glow, grayscale, colour replacement, hit flash, water, heat distortion, distortion, ghost + custom 2D fragment shaders with a starting template |
| 10 | UI framework | `engine/ui/UiCore.kt`, `UiSystem.kt`, `UiWidgets.kt` | 14 widgets (canvas, panel, label, button, icon button, image, slider, progress bar, checkbox, text field, scroll view, tabs, list, tooltip), 11 anchors, free/horizontal/vertical/grid layouts, margins/padding, themes, fonts/icons, hover/pressed/selected/focused/disabled states, per-widget animations, accessibility labels |
| 11 | Scene system | `engine/core/Scene.kt`, `GameObject.kt`, `Component.kt`, `Signals.kt`, `Prefab.kt`, `SceneSerializer.kt` | scene tree, parenting, components, groups/tags, signals/events, prefab-style instancing, JSON serialisation |
| 12 | Editor | `ui/EditorActivity.kt`, `HierarchyAdapter.kt`, `InspectorPanel.kt`, `ViewportController.kt`, `History.kt`, `ColorPickerDialog.kt`, `AssetStoreActivity.kt`, `AnimationEditorActivity.kt`, `BlueprintEditorActivity.kt`, `BuildActivity.kt` | scene tree, inspector, asset browser, 2D viewport with gizmos (move/rotate/scale), snapping + grid, tilemap/animation/particle/UI editing surfaces, project settings, shortcuts, undo/redo, asset search, autosave + crash recovery. Every control performs a real action. |
| 13 | Asset pipeline | `project/AssetLibrary.kt`, `AssetStoreActivity.kt`, `render/Textures.kt`, `Svg.kt` | PNG/JPG/WebP/SVG, sprite sheets, fonts, audio, tilemaps, animations, materials, shaders: import, cache, validate, link checking |
| 14 | Audio | `engine/AudioSystem.kt` | music and SFX buses, volume, looping, fades, pitch, 2D pan-by-distance behaviour, voice pooling, mobile-friendly streaming |
| 15 | Input | `engine/Input.kt`, `ui/GameControlsView.kt` | multitouch, virtual joystick + A/B buttons, d-pad, keyboard, mouse, Android gamepads, taps/gestures, screen-space and world-space coordinates |
| 16 | Scripting / gameplay API | `engine/script/ScriptSystem.kt`, `Api.kt`, `ScriptReference.kt`, `ScriptValidator.kt`, `core/Tasks.kt`, `engine/blueprint/Blueprint.kt` | JavaScript (Rhino, interpreted) entities/components/events/signals/timers/tasks/input/physics queries/animation/audio/particles/scene loading/save + load — 2D only; a visual blueprint graph compiles to the same API |
| 17 | Debugger / profiler | `Engine.kt` stats, `ui/EditorActivity.kt`, `engine/render/EditorState.kt` | console with error reporting, FPS, frame timing, memory, draw calls, physics/particle counters, asset diagnostics, runtime scene inspector, live profiler overlay |
| 18 | Project management | `project/Project.kt`, `ProjectManager.kt`, `Templates.kt`, `ui/ProjectsActivity.kt`, `BuildActivity.kt` | new/open/save/duplicate/delete, import/export `.zip`, 8 templates, settings: title, package id, version, orientation, resolution, permissions, scenes, build settings |
| 19 | Android / APK export | `export/ApkBuilder.kt`, `ApkSignerV2.kt`, `AxmlPatcher.kt`, `ZipWriter.kt`, `SigningKeys.kt`, `GameRuntime.kt`, `ui/BuildActivity.kt` | name/package/version/versionCode/orientation/min-target SDK/permissions/icon/splash, debug + release builds, build log, errors, output path, reproducible builds; app icon + label + provider authority are rewritten in the compiled manifest |
| 20 | Mobile optimisation | renderer/physics/audio/particle pools, chunked tilemap, texture page reuse, `NO_COMPRESS` handling for already-compressed formats, allocation-free hot loops | reduced draw calls (batcher), GPU-friendly texture reuse, GC-friendly pooling, low-memory asset handling |
| 21 | Professional UX | `ui/Ui.kt`, `ui/*` activities | dark theme, vector icons (SVG rasteriser), consistent spacing, tooltips, shortcuts, hover/pressed/selected states, responsive panels, clear error dialogs |
| 22 | Quality | whole tree | no placeholder-only systems, no fake APIs, no empty architecture classes, no major TODOs, no duplicated systems |
| 23 | Strict-2D audit | section 2 | violations removed/replaced, guarded by tests |
| 24 | Testing | `app/src/test/java/com/sengine/*` | 22 tests: physics/collision/vehicle/animation/particles/tilemap/UI/serialization/assets/project/export/Android build |
| 25 | Final result | this document | engine builds real games and ships installable APKs |

---

## 4. What the final pass fixed

**Vehicle physics (the headline fix).** The hill-climb car used to freeze on the spot and then be
launched across the map by a feedback loop inside the solver.

* **Contacts now get the last word.** The joint pass ran after the contact pass, so the suspension
  pushed the tyre into the ground every step and the next step's contact fired back an impulse sized
  to undo a whole step of penetration. Contacts are resolved once more after the joints, so the
  ground stays authoritative without touching any joint's own solution.
* **CCD slides instead of stopping.** A body that would tunnel is placed at the last free position and
  only the velocity component along the sweep is cancelled, and a per-step safety net (speed clamps
  plus a non-finite guard) keeps one bad step from poisoning the simulation.
* **Joint-connected bodies no longer collide** (the Box2D default), so a wheel under its chassis is
  not shoved sideways by it.
* **Colliders scale with their object.** A 1×1 collider on a 4×6 object now covers the object, which
  is what turned terrain into unclimbable micro-steps.
* **Drive comes from tyre traction.** Engine torque becomes a chassis force capped by grip (with a
  pitch torque from the contact-patch offset), loses authority in the air, and the driven tyres are
  spun to the commanded rate for the arcade look and slip effects. Driving the tyre through the joint
  motor itself either crawls or flips the car.
* **Wheel placement is in world units**, so a scaled chassis no longer rides on its belly with the
  tyres in the air, and wheels (in permanent ground contact) no longer run CCD against their own
  suspension.
* **The hill-climb terrain is a chain of rotated ramps** whose top faces form one continuous surface
  with gradients under 10°, long enough to drive for a minute; the driver script reloads the run if
  the buggy leaves the world.

**Export pipeline.** Exported games now launch into their game: `ProjectsActivity` detects the
embedded project (`assets/game/build.json`) and hands over to `PlayerActivity` in standalone mode.
The export test additionally asserts that the launcher entry point, the player activity and the
embedded start scene all survive repackaging.

**CI.** `.github/workflows/android.yml` runs the headless engine test suite, builds the debug APK,
exports a standalone game APK through the engine's own builder, verifies both files with `apksigner`
and `aapt2`, uploads them as workflow artefacts, publishes the rolling `sengine-latest` release and
refreshes `prebuilt/` (idempotent, `[skip ci]`) so the installable files are always fetchable from the
repository itself.

---

## 5. Verification

### Test suite (offline harness, repository root)

```
== RRunner: 4 test classes ==
PASS EngineV2Test.animationClipGridLoopAndEvents
PASS EngineV2Test.animationSystemAdvancesFrames
PASS EngineV2Test.assetStoreIsStrictlyTwoD
PASS EngineV2Test.blueprintGraphCompilesToJsAndRoundTrips
PASS EngineV2Test.particlesBurstSimulateAndRecycle
PASS EngineV2Test.physicsBodyTypesRaycastAndOverlap
PASS EngineV2Test.projectExportZipContainsSceneAndAssets
PASS EngineV2Test.scriptReferenceIsTwoDOnlyAndMatchesRuntime
PASS EngineV2Test.serializerWritesNoThreeDFields
PASS EngineV2Test.tilemapChunksAutoTileAndCollisionData
PASS EngineV2Test.uiLayoutAnchorsAndButtonClick
PASS EngineV2Test.vehicleDrivesForwardOnFlatGround
PASS EngineSimulationTest.serializationRoundTrip
PASS EngineSimulationTest.tilemapTemplateBuildsCollisionAndRuns
PASS EngineSimulationTest.vehicleTemplateDrives
PASS ResourceLinkTest.everyResourceReferenceResolves
PASS ResourceLinkTest.everyStyleImplicitParentExists
SKIP ApkExportTest.exportStandaloneGameApk            (needs the Gradle-built APK: run in CI)
SKIP 4 script-driven checks                           (no JavaScript runtime on the classpath: run in CI)
== RRunner done: 17 passed, 0 failed, 5 skipped ==
```

CI runs the same 22 tests with Rhino 1.7.13 and the export environment on the classpath, so the five
skips above execute for real there (for example `SIM 'Physics Sandbox' ran 240 frames objects=18
errors=0`, `SIM vehicle drive=93N spin=-1100deg/s grounded=2 dist=36.26`) — and the whole run is green.

### Build result

`./gradlew testDebugUnitTest assembleDebug` on `ubuntu-latest`, JDK 17, Android SDK 34:
**BUILD SUCCESSFUL**, `app/build/outputs/apk/debug/app-debug.apk` (2 441 924 B, v2-signed). No local
Android SDK exists in the development sandbox, so Gradle/ASDK work is verified in CI and the
resulting files are pulled back into `prebuilt/`.

### APK export result

`ApkExportTest.exportStandaloneGameApk` drives `ApkBuilder` with the Gradle-built runtime APK, the
"Space Shooter" project and a throwaway PKCS12 key:

```
SIM export 2234353 bytes in 304 ms, steps=7
SIM export manifest strings: [com.sengine.game.spacerun, com.sengine.game.spacerun.share, …,
                             Space Run, 1.2.3]
Verifies — Verified using v2 scheme (APK Signature Scheme v2): true
package: name='com.sengine.game.spacerun' versionCode='7' versionName='1.2.3'
application-label:'Space Run'
```

The exported APK contains `classes.dex`, `resources.arsc`, the renamed manifest (package, label,
versionName, versionCode and the content-provider authority all rewritten) and the embedded project
(`build.json`, `project.json`, `scenes/Main.scene.json`, five game scripts and four prefabs).

### Vehicle behaviour

| Check | Result |
|---|---|
| Flat-ground rig (`EngineV2Test.vehicleDrivesForwardOnFlatGround`) | 0 → 8.5 units in 240 frames, 8.0 km/h, both wheels grounded, wheel spin −1100 deg/s |
| Hill-climb template (`EngineSimulationTest.vehicleTemplateDrives`) | 36.3 units in 600 frames up the ramp chain, 93 N drive force, both wheels grounded |
| 60 s simulated play (probe) | up to 22.7 km/h, 131 units travelled, 0 non-finite states in 3600 steps |
| Solver stability | no impulse cascade: compared with the old build, peak linear velocity per step fell from `4.07 × 10⁵ m/s` to `≤ 3.5 m/s` on the same scene |

---

## 6. Notes and limits

* The repository's only build path for APKs is CI (no Android SDK in the sandbox); the artefacts it
  produces are committed under `prebuilt/` and attached to `sengine-latest`.
* The exported APK reuses the engine runtime (editor screens included but never shown) — the builder
  does not strip editor code yet; it embeds the project and boots straight into it.
* Scripting needs Rhino on the device; it ships inside the app, so exported games are self-contained.
* Debug APKs are test-signed; use the project's own keystore for distribution.

# S Engine Professional 2D Game Engine Guide

## Engine Overview
S Engine is a professional-grade 2D game engine with modern dark UI, 
real-time editor, powerful physics, advanced rendering, VFX, animation, 
auditing, scripting and one-click APK export.

## Key Professional Features

### Renderer
- 2D sprite rendering with shapes (square, circle, triangle)
- Texture atlas support and batching
- Layer-based rendering with camera viewport
- Pixel-perfect rendering mode
- Material/Shader system with blend modes (Normal, Additive, Multiply, Screen)

### Physics
- Rigid/Dynamic, Kinematic, Static body types
- Box and Circle colliders
- Friction, restitution, mass, gravity scale
- Continuous Collision Detection (CCD) support
- Sensors and trigger zones
- Physics debugging overlay

### Camera System
- Follow target with smoothing
- Zoom, shake and cinematic effects
- Limit boundaries
- Viewport scaling

### VFX / Particles
- Particle emitter with rate, lifetime, speed, spread
- Color gradients (start/end)
- Gravity and turbulence
- Max particle limits for performance

### Animation
- Sprite animation support
- Timeline-based keyframes (planned)
- Animation state machines (planned)

### Audio
- Multi-track audio with bus routing
- Pitch and pan control
- Spatial audio support
- Looping and play-on-start

### Scripting (JavaScript / Mozilla Rhino)
- Component-based architecture
- Lifecycle methods
- Scene access and node queries
- Physics integration
- Input and UI events
- Audio control APIs

### Editor Features
- Professional Godot-inspired dark interface
- Scene Tree with hierarchy
- Inspector with live property editing
- FileSystem / Asset Browser
- 2D Viewport with grid and snapping
- Toolbar with real icon assets
- Console / Debugger
- Undo/Redo history
- Multi-select and drag/drop support

### Project Templates
- Platformer, Shooter, Racing, Physics Puzzle, Mobile Arcade

### Build / Export
- One-click APK export
- Configurable package name, version, app name, icon
- Debug and Release build modes
- Build logs and error reporting
- CI/CD automated builds via GitHub Actions

# Plane Fight Battle — Core Systems Summary

## 1) Game Overview
- **Genre/Mode:** Real-time 1v1 multiplayer aerial game.
- **Player Roles:**
  - **Plane 1 (Target Runner):** Wins by collecting/destroying all targets.
  - **Plane 2 (Chaser/Shooter):** Wins by shooting Plane 1 until HP reaches 0.
- **Main Runtime Entry:** `App.java` (render loop, gameplay, networking, UI, and high-level state flow).

## 2) Core Mechanics

### 2.1 Match Flow
- Menu -> host/join -> waiting -> countdown -> active match -> paused or game over.
- Host starts countdown and authoritative match start.
- End conditions:
  - Plane 1 wins when all targets are collected.
  - Plane 2 wins when Plane 1 HP is depleted.

### 2.2 Flight & Movement
- Mouse controls yaw/pitch for active local role.
- Camera is third-person chase camera behind the active player plane.
- Both planes are clamped to map bounds and terrain-aware minimum altitude.
- Current base speed (`BASE_PLANE_SPEED`) is **80.64**.

### 2.3 Plane 1 Boost System
- Hold **Space** to boost Plane 1 speed by **1.5x**.
- Boost drains from full in **2 seconds**.
- Full recharge takes **20 seconds**.
- If boost is depleted, it is **locked out** until fully recharged.
- Boost HUD bar is shown for Plane 1 in active gameplay.

### 2.4 Combat
- Plane 2 fires with **left click**.
- Shots create visible bullet traces and play positional shot SFX.
- Hit detection uses ray-vs-sphere tests against Plane 1.

### 2.5 Targets
- Targets spawn across the map with random offsets and spacing checks.
- Plane 1 collects targets by proximity contact.
- Target AI evades Plane 1 with difficulty scaling as more targets are collected.
- Last remaining target can move close to Plane 1 speed (with cap below plane speed).

### 2.6 Pause & Menu UX
- **Esc** pauses/resumes during active sessions.
- Pause menu includes:
  - Resume button
  - Local **mute/unmute music** button
- Game-over screen shows a large centered winner message.

## 3) Core Audio Behavior

### 3.1 Audio Engine
- Implemented in `AudioEngine.java` using **OpenAL** + MP3 decode (`JLayer`).
- Supports looped and one-shot sources, positional updates, gain changes, listener updates.

### 3.2 In-Game Audio Rules
- **Rumble:** each player hears only their own rumble.
- **Free Bird music:** each player hears only the **other player’s** music source.
- Music uses **distance-based proximity falloff** with a **5% minimum floor**.
- Local pause menu mute toggles music for that client only.
- One-shot SFX:
  - Shooting sound from shooter position
  - Target collection sound at collected target position (replicated so both clients hear)

## 4) Core Data Structures

### 4.1 Primary Runtime State (`App.java`)
- `GameState`:
  - Plane 1 transform + speed + HP
  - Plane 2 transform + speed
  - `List<Vector3> targets`
  - `boolean[] destroyedTargets`
- `BulletTrace`:
  - Start/end positions, color, TTL/max TTL for fading tracer visuals.

### 4.2 Math & Physics Primitives
- `Vector3`:
  - Mutable 3D vector with arithmetic, normalization, dot/cross helpers.
- `RigidBody`:
  - Base physics body abstraction (forces, torques, integration, orientation).
- `Engine`:
  - Throttle-to-thrust model that applies forward relative force.
- `WingSurface`:
  - Lift/drag force model from local airflow and control input.
- `Plane`:
  - Physics-oriented aircraft model built on `RigidBody` + `Engine` + `WingSurface`.
  - Contains health, control authority, ground interaction, and rendering support.

> Note: The current gameplay loop is orchestrated directly through `App.java`. The physics-oriented classes remain in the project as core simulation components and reusable infrastructure.

### 4.3 Networking Types
- `NetworkPeer` interface with `HostPeer` and `ClientPeer` implementations.
- Asynchronous incoming queue via `ConcurrentLinkedQueue<String>`.
- Line-based message protocol for snapshots/events.

## 5) Core Networking Elements

### 5.1 Match/Session Messages
- `COUNTDOWN|<time>`
- `START`
- `END|PLANE1|PLANE2`

### 5.2 State Sync Messages
- `SNAP|...` (host snapshot of planes + HP + target mask + target positions)
- `P2STATE|...` (Plane 2 pose/rotation updates)

### 5.3 Event Messages
- `FIRE_P2|origin+direction` (shot replication)
- `TARGET_COLLECT|x|y|z` (target collection SFX/event sync)

## 6) Core Rendering Elements

### 6.1 World & Map
- `Map.java` renders Twin Islands model when available.
- Fallback flat ground plane if map model is unavailable.
- Terrain collision/height sampling built from OBJ vertex-derived height grid.

### 6.2 Plane Rendering
- Plane 1 is blue-tinted; Plane 2 is red-tinted.
- Uses loaded plane model when available, otherwise fallback procedural geometry.

### 6.3 Target Rendering
- Targets are rendered as textured cubes.
- `target.jpg` is applied with explicit per-face UV mapping (full texture on each face).
- Ring markers are drawn around active targets.

### 6.4 HUD/Overlay
- Role, HP, target progress, boost meter, FPS, pause/game-over UI.
- Crosshair appears in active gameplay.

## 7) Core Game Elements (Asset/Content Level)
- **Map:** Twin Islands environment model.
- **Player aircraft:** shared plane model with role-based behavior and color differentiation.
- **Targets:** collectible textured cubes with evasion movement.
- **Audio assets:**
  - Plane rumble
  - Free Bird loop
  - Shooting SFX
  - Target collection SFX

## 8) Current Tuning Snapshot
- Base speed: **80.64**
- Boost multiplier: **1.5x**
- Boost drain: **2 seconds full -> empty**
- Boost recharge: **20 seconds empty -> full**
- Altitude floor clamp currently allows lower flight (with terrain-aware minimum from map sampling).

## 9) Suggested Next Documentation Additions
- Add sequence diagrams for host/client message flow.
- Add a control-reference table for both players.
- Add a balancing/tuning appendix for speeds, cooldowns, and audio distances.

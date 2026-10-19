# Open masks implementation plan

> **For agentic workers:** Use superpowers:executing-plans for implementation;
> the independent Android and renderer tasks may use dispatching-parallel-agents.

**Goal:** Ship V8.9 with open-source tracking and freely licensed, mesh-attached masks.

**Architecture:** Keep Redmi analysis and native POCO composition. Both consume
the same generated canonical topology; PVR1 remains unchanged.

**Tech Stack:** Java 17, MediaPipe Tasks Android, OpenGL ES 2, Python asset builders.

**Spec:** `docs/superpowers/specs/2026-10-19-open-masks-design.md`

## Global constraints

- All new runtime components/assets must be free and openly licensed.
- Preserve every existing camera shortcut and all four startup modes.
- Preserve `--hwdec=disabled` and `--no-audio`.
- Keep source timestamps, protocol format, clean analysis images and bounded work.
- `com.poco.vision.FaceTopology`: `VERTEX_COUNT=468`, `float[] UV` indexed by
  landmark, `float[] CANONICAL` XYZ, `short[] TRIANGLES` canonical indices.

## Review focus

- No face, partial face, and tracking loss: no detached or lingering masks.
- Delayed inference after reconnect/stop: discard stale results and release inputs.
- Large or malformed JPEGs/results: preserve existing protocol bounds.
- All eight mask selections and context recreation: valid textures and no GL leaks.
- Bundled models/native libraries: APK installs and works without model downloads.

### Task 1: Canonical assets and Android tracking

Files: `build_face_topology.py`, `common/java/com/poco/vision/FaceTopology.java`,
`redmi-vision/app/build.gradle`, Android tracking classes and tests.

- [ ] Run existing JVM tests; record environment failures separately.
- [ ] Generate/check topology from Apache-2.0 source and bundle pinned models.
- [ ] Write/run failing landmark conversion and lifecycle/monotonic timestamp tests.
- [ ] Replace ML Kit with MediaPipe using single-owner inference threads.
- [ ] Run conversion tests and compile the complete APK.

### Task 2: Mesh masks

Files: renderer, mesh shaders, mask assets/builders, GPU tests, patch copier.

- [ ] Add failing coverage/UV/loss tests against the actual mesh shader.
- [ ] Implement eight freely licensed texture masks using canonical UVs.
- [ ] Remove old bounding-box ornaments; preserve keyboard slots.
- [ ] Validate transparency, facial alignment, shader compilation and resource cleanup.

### Task 3: Verification and delivery

Files: CI workflow, installer/menu labels, control documentation, license notices.

- [ ] Update to V8.9 and preserve all menu/camera controls.
- [ ] Verify model hashes, asset generation, JVM suite and GPU pixels.
- [ ] Build server/APK and run actual Android install/infer/stop/restart checks.
- [ ] Review branch, address material findings and package matching Windows/APK.
- [ ] Deliver files with explicit hardware/Android version validation limits.

# Scanning, Coverage, UI & Performance

## Scanning State

Implement a scanning state machine.

```text
IDLE → SCANNING → STABILIZING → COMPLETE
```

**IDLE** — No measurement is currently being collected.

**SCANNING** — Continuously: segment the object, acquire depth, generate world points, accumulate observations, track viewpoint coverage, estimate dimensions.

**STABILIZING** — Enough coverage exists, but dimensions are still converging.

**COMPLETE** — Coverage and geometry are sufficiently stable and the application can report the measurement.

---

## Viewpoint Coverage

Track how much of the object has been observed from different horizontal viewpoints. A simple 8–12 sector angular coverage system is sufficient.

```text
              0°
               │
        11     │     1
          \    │    /
      10 ───── 📦 ───── 2
          /    │    \
         9     │     3
               │
         8  7  6  5  4
```

Display coverage to the user: `Coverage: 72%`

The mentor's recommendation of at least three angles should be treated as an initial experimental requirement, not a permanent hard-coded rule.

The eventual scan completion decision should consider: coverage, valid point count, tracking quality, dimension stability, floor validity.

---

## Measurement Stability

Measurements should not jump between frames. Track Width/Height/Depth over time and use temporal smoothing. A measurement should only become `COMPLETE` after it remains sufficiently stable.

```text
Frame 1   62.7 cm
Frame 2   63.1 cm
Frame 3   62.8 cm
Frame 4   62.9 cm
Frame 5   62.9 cm
```

should converge toward `62.9 cm` rather than exposing every raw estimate to the user.

Reuse the existing measurement stabilizer where possible.

---

## UI

Keep the UI simple — it is primarily a debugging and validation interface.

During scanning:

```text
SCANNING

Coverage: 68%

Width: 62.8 cm
Height: 41.9 cm
Depth: 28.5 cm

Status: Stabilizing...
```

When complete:

```text
MEASUREMENT READY

62.7 × 28.4 × 42.0 cm

Confidence: High
Coverage: 87%
```

Also provide debugging overlays for: segmentation mask, depth, accumulated point cloud, camera trajectory, floor plane, coverage sectors, horizontal footprint, final oriented rectangle.

Do not spend significant development time on visual polish.

---

## Debugging Philosophy

Always determine **which stage is wrong** before changing the measurement algorithm.

Possible failure sources, in order to check:

```text
Segmentation
     ↓
Depth
     ↓
Depth ↔ RGB alignment
     ↓
Camera intrinsics
     ↓
Camera pose
     ↓
Camera → world transformation
     ↓
Multi-view accumulation
     ↓
Outlier rejection
     ↓
Floor estimation
     ↓
Footprint geometry
     ↓
Measurement stabilization
```

If points from different viewpoints do not align in world space, do not attempt to fix the final bounding-box algorithm. First debug ARCore tracking, camera pose, intrinsics, depth alignment, coordinate transforms, segmentation.

---

## Performance

This is an on-device Android application. Do not perform expensive work on every render frame.

Segmentation, point extraction, filtering, and geometry calculations may be throttled. Rendering must remain responsive. Reuse buffers where practical. Avoid unnecessary allocations in hot paths.

Do not prematurely optimize before the geometry is correct.

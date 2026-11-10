# Development Process

## Development Methodology

Build **milestone by milestone**. Every milestone must eventually be verified on the physical device. Do not rewrite the application in one large change.

### M1 — Existing ARCore foundation
Verify: camera, tracking, depth, intrinsics, pose, floor/plane.

### M2 — Existing segmentation
Verify: YOLO11-Seg, target selection, mask quality.

### M3 — Single-view world points
Verify:
```text
mask + depth → camera-space points → world-space points
```

### M4 — Multi-view accumulation
Verify that points from different camera viewpoints overlap correctly in the same world coordinate system. **This is the most important milestone.**

### M5 — Robust filtering
Implement: confidence filtering, outlier rejection, bounded accumulation.

### M6 — Height
Implement robust floor-relative vertical measurement.

### M7 — Horizontal footprint
Implement X/Z projection and minimum-area rectangle.

### M8 — Coverage
Implement angular viewpoint coverage.

### M9 — Stability
Implement measurement convergence and completion criteria.

### M10 — Accuracy evaluation
Compare measurements against physical ground truth.

---

## Critical Experiment

Before pursuing higher-level accuracy improvements, answer:

> Do points captured from three or more different phone viewpoints reconstruct the same physical object consistently in ARCore world coordinates?

**If NO:** Stop. Debug camera pose, coordinate transforms, depth alignment, segmentation, tracking. Do not modify the bounding-box algorithm yet.

**If YES:** Proceed to evaluate depth accuracy, filtering, footprint estimation, measurement stability.

---

## Accuracy Evaluation

Use physical objects with known dimensions. For every experiment record:

```text
Ground truth width / height / depth
Estimated width / height / depth
Width / Height / Depth error %
Number of viewpoints
Coverage %
Device
Lighting
Approximate camera distance
```

Do not judge the approach using a single successful scan. Previous experiments showed significant measurement errors — the purpose of this iteration is to determine whether multi-view accumulation materially reduces those errors.

---

## Definition of Done

This experiment is successful when:

1. One object can be selected.
2. The object is sitting on a detectable floor.
3. The user can slowly move around it.
4. Multiple viewpoints contribute observations.
5. Those observations align in ARCore world coordinates.
6. The accumulated geometry visibly represents the object.
7. Width, height, and depth converge.
8. The measurement becomes stable.
9. Measurement occurs entirely on-device.
10. The result can be compared against physical ground truth.

Perfect accuracy is NOT required for the first successful implementation. The first objective is proving that the multi-view reconstruction pipeline works.

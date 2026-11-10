# Architecture

## Existing Architecture

Before creating new components, inspect the existing code.

ARTest2 already contains working infrastructure for:

* ARCore camera tracking
* ARCore Depth API
* camera intrinsics
* camera pose
* YOLO11-Seg
* object segmentation
* masked depth extraction
* depth → 3D back-projection
* camera → world transformation
* point-cloud generation
* existing measurement
* rendering
* Compose UI
* MVVM architecture

**Reuse these components whenever possible.**

Do not rewrite working ARCore, ML, rendering, or coordinate-transformation code simply to fit the new architecture.

---

## Object Scope

For this iteration:

* Measure exactly **one object**.
* Do not implement multi-object measurement.
* Do not implement a general-purpose scene scanner.
* Do not optimize for arbitrary cluttered scenes yet.

Example target objects: box, suitcase, chair, microwave, table, household appliance.

The object should initially be placed on a detectable floor.

---

## Suggested Components

Reuse existing classes first. Only introduce new classes where necessary.

Potential responsibilities:

```text
ObjectSegmenter
DepthExtractor
PointCloudProjector
WorldPointTransformer
PointCloudAccumulator
OutlierFilter
CoverageTracker
FloorEstimator
FootprintEstimator
MeasurementEstimator
MeasurementStabilizer
ScanState
```

Do not create duplicate classes when an existing implementation already performs the required responsibility.

---

## Server Boundary

Do not add server-side measurement logic.

The eventual Android result should look conceptually like:

```json
{
  "widthCm": 62.7,
  "heightCm": 42.0,
  "depthCm": 28.4,
  "confidence": 0.91,
  "coverage": 0.87,
  "timestamp": "..."
}
```

The backend can later store this result and use it for survey records, quotes, packing calculations, and business logic.

The backend should not reconstruct the object's geometry.

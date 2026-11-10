# Measurement Pipeline

The target pipeline is:

```text
ARCore RGB
    ↓
YOLO11-Seg
    ↓
Single target object mask
    ↓
ARCore depth
    ↓
Masked depth pixels
    ↓
Camera-space 3D points
    ↓
ARCore camera pose
    ↓
World-space 3D points
    ↓
Multi-view accumulation
    ↓
Outlier rejection
    ↓
Gravity-aligned geometry
    ↓
Horizontal footprint estimation
    ↓
Width / Height / Depth
    ↓
Temporal stabilization
    ↓
Final measurement
```

---

## Object Segmentation

Reuse the existing YOLO11-Seg implementation.

```text
RGB → YOLO11-Seg → Target object → Segmentation mask
```

The segmentation mask should determine which depth pixels are eligible for reconstruction.

Do not rely primarily on the old "nearest point + sphere" object-isolation heuristic when a segmentation mask is available. Segmentation should be the primary object-isolation mechanism.

---

## Depth → 3D

For each valid pixel inside the object mask:

```text
pixel = (u, v)
depth = Z
```

Use the ARCore camera intrinsics (`fx, fy, cx, cy`) to back-project:

```text
X = (u - cx) * Z / fx
Y = (v - cy) * Z / fy
Z = Z
```

This produces a camera-space 3D point. Then transform it using the ARCore camera pose:

```text
P_world = CameraPose × P_camera
```

Every observation added to the accumulated object representation must be in the **same world coordinate system**.

---

## Multi-View Accumulation

This is the primary new feature. The user moves around the object across multiple views, and all observations must be transformed into ARCore world coordinates.

The accumulated representation should progressively contain information from different sides of the object. Do not simply average dimensions from separate views:

```text
View 1 → world points ┐
View 2 → world points ├──→ accumulated object geometry
View 3 → world points │
View 4 → world points ┘
```

---

## Point Cloud Accumulation

Do not allow the accumulated point cloud to grow without bounds. A bounded representation is preferred.

Possible approaches:

* voxel downsampling
* spatial hashing
* fixed-size buffers
* periodic pruning

Start with the simplest implementation that preserves enough geometric information for measurement. A dense photogrammetry-quality reconstruction is not required — the goal is **dimension estimation**, not visual reconstruction.

---

## Outlier Rejection

Depth measurements contain noise and occasional invalid points. Never calculate dimensions using raw absolute minimum/maximum coordinates without filtering.

Possible filtering strategies:

* depth confidence filtering
* percentile-based bounds
* neighborhood density
* statistical filtering
* voxel occupancy

Start with a simple deterministic approach. Percentile-based bounds are acceptable for the first experiment:

```text
lower bound = 5th percentile
upper bound = 95th percentile
```

Do not over-engineer filtering before measurements demonstrate that it is necessary.

---

## Height Estimation

ARCore's world coordinate system is gravity-aligned. Use world Y for vertical measurement.

The floor must be detected/validated before producing a final measurement. Do not assume the lowest noisy point represents the object's physical bottom.

```text
H = object_top - object_floor
```

using robust point filtering.

---

## Width and Depth Estimation

Do **not** use PCA as the primary measurement method for this iteration.

The existing project contains an oriented bounding box based on PCA. Preserve it for comparison/debugging if useful, but do not make it the authoritative measurement method.

Instead:

1. Remove the vertical component.
2. Project object points onto the horizontal X/Z plane.
3. Estimate the object's footprint.
4. Fit a **minimum-area oriented rectangle**.
5. Use the two rectangle side lengths as the horizontal dimensions.

```text
        ┌───────────────────┐
        │       OBJECT      │
        └───────────────────┘
        ←────── Width ─────→
                 Depth
```

The rectangle should be independent of the phone's orientation. This is intended to reduce footprint inflation when a rectangular object is rotated relative to the ARCore world X/Z axes.

### Why PCA is not the primary method

PCA finds the principal directions of the point distribution. Those directions are not guaranteed to correspond to the physical semantic axes of an object — an irregular object can have principal axes that don't cleanly map to Length/Width/Height.

Gravity provides a reliable vertical reference. For the horizontal dimensions, a minimum-area footprint rectangle is the preferred experiment. PCA may remain available as a comparison/debugging method.

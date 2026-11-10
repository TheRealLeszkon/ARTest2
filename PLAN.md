# AR Object Dimension Measurement — Proof of Concept Plan

**Project:** `ARTest2`
**Goal:** Point an Android phone at a single household object (chair, suitcase, microwave…) and estimate its **width / height / depth** in **cm and inches**, using ARCore for depth + a segmentation model for the object mask, then fitting an **Oriented Bounding Box (OBB)** to a 3D point cloud.

**Priority:** *Understanding the pipeline* over accuracy. This is a learning PoC, not a product.

---

## 0. TL;DR of the key decisions

| Decision | Choice | Why |
|---|---|---|
| Rendering | **SceneView** (`io.github.sceneview:arsceneview`) | Actively maintained, Compose-friendly ARCore wrapper. Raw ARCore+OpenGL is too much boilerplate for a PoC. |
| UI | **Jetpack Compose** | Requested; SceneView ships a Compose `ARScene` composable. |
| Detection + Segmentation | **One model: YOLO11-Seg** (instance segmentation) | Does detection **and** the mask in a single pass. Your own benchmark already produced `yoloe-11s-seg-pf.onnx`. Replaces the two-model (YOLO + SAM) path with no meaningful quality loss for whole-object masks. |
| Inference runtime | **ONNX Runtime Mobile** (with NNAPI/GPU EP) | You already have `.onnx` exports. Avoids the TFLite re-export/quantization detour. |
| Depth | **ARCore Depth API** only | Requested. Monocular depth is the fallback *only* if a device lacks Depth support. |
| Heavy geometry | Plain Kotlin math (no OpenCV needed for v1) | Point-cloud → PCA → OBB is ~150 lines of linear algebra. OpenCV is optional and only added if we later want robust plane/outlier ops. |
| Architecture | **MVVM**, one module (Gradle) with clean **package** boundaries per stage | Full multi-Gradle-module split is overkill for a PoC; we get the same separation-of-concerns with packages + interfaces, and can promote to modules later. |

> **Why NOT the two-model YOLO + EfficientSAM path?**
> EfficientSAM is excellent (your benchmark: ~10–20 ms/mask, ~30× faster than FastSAM) and would be the right call *if* we needed promptable, click-to-segment behavior or higher-quality masks than YOLO gives. But for "segment the one object I detected," YOLO11-Seg's built-in prototype masks are good enough, and running **one** model halves the on-device complexity, memory, and latency budget. This directly satisfies your "if one model can replace two, prefer it" rule. We keep EfficientSAM ONNX in our back pocket as a Milestone-4 upgrade if masks look ragged.

---

## 1. Object Detection model evaluation (the three candidates)

| Option | What it is | Pros | Cons | Verdict |
|---|---|---|---|---|
| **YOLOv8-Seg** | Mature instance segmentation | Tons of examples, stable exports | Older head; slightly behind 11 on accuracy/speed | Fine fallback |
| **YOLO11-Seg** | Current-gen instance segmentation | Best speed/accuracy of the YOLO seg family; single-model detect+mask; you already exported it | Mask edges coarser than SAM | ✅ **Chosen** |
| **YOLO + EfficientSAM** | Detector gives a box → SAM turns box/point into a crisp mask | Best mask quality; promptable | Two models = 2× memory, 2× latency, more glue code | Overkill for PoC; keep as upgrade |

**Decision: YOLO11-Seg (`yolo11n-seg` / `yolo11s-seg`).** Start with the **nano** (`n`) variant for frame rate; bump to **small** (`s`) if detection misses the object. Input size **640×640**.

---

## 2. High-level pipeline (what we're building)

```
CameraX/ARCore camera stream
        │
        ▼
  ARCore Session ──► per-frame: RGB image, Depth image (16-bit mm),
        │                        camera Pose (world), camera Intrinsics (fx,fy,cx,cy)
        ▼
  Object Detection (YOLO11-Seg) ──► boxes + class + prototype masks
        │
        ▼
  Segmentation mask (single target object, upsampled to depth resolution)
        │
        ▼
  Masked depth extraction ──► keep depth pixels where mask==1
        │
        ▼
  Back-projection to 3D ──► (X,Y,Z) per pixel via intrinsics, then to world via Pose
        │
        ▼
  Outlier removal ──► statistical (distance-to-centroid) + optional voxel downsample
        │
        ▼
  OBB fit via PCA ──► 3 orthogonal axes + extents
        │
        ▼
  Dimensions: width / height / depth  (cm + inches)
        │
        ▼
  AR rendering: box wireframe, mask overlay, point cloud (debug), text labels
```

---

## 3. Architecture (MVVM + per-stage packages)

Single Gradle module `app`, package `com.example.arsurvey`, sub-packages:

```
com.example.arsurvey
├── ar/            # ARSessionManager: owns SceneView session, exposes ARFrame(rgb, depth, pose, intrinsics)
├── detect/        # ObjectDetector interface + YoloSegDetector (ONNX Runtime)
├── segment/       # MaskProcessor: pick target instance, resize/threshold mask to depth space
├── cloud/         # PointCloudBuilder: masked depth → world-space Float3 points; OutlierFilter
├── measure/       # ObbFitter (PCA) → Obb(center, axes, halfExtents) → Dimensions
├── render/        # AR overlays: BoxRenderer, MaskOverlay, PointCloudRenderer, LabelRenderer
├── ui/            # Compose screens + MeasureViewModel (MVVM), UiState
└── util/          # Math (Mat3, eigen), units (m↔cm↔in), image conversion helpers
```

- **ViewModel** (`MeasureViewModel`) holds a `StateFlow<MeasureUiState>` and orchestrates: it receives frames from `ARSessionManager`, kicks detection on a background dispatcher, and publishes results.
- Each stage is an **interface** with one implementation → swappable, testable, no giant classes.
- Data classes as the contracts between stages: `ARFrame`, `Detection`, `Mask`, `PointCloud`, `Obb`, `Dimensions`.

---

## 4. Tech stack & versions (baseline)

Inherited from existing `ARTest` where sensible, upgraded for Compose:

- Kotlin `1.9.25`, AGP `8.6.1`, `compileSdk 34`, `minSdk 24` → **raise minSdk to 26** (ARCore Depth realistically needs newer devices anyway; simplifies image APIs).
- Jetpack Compose BOM (latest stable), `activity-compose`.
- SceneView: `io.github.sceneview:arsceneview` (latest).
- ONNX Runtime Mobile: `com.microsoft.onnxruntime:onnxruntime-android`.
- ARCore: `com.google.ar:core:1.44.0` (already used).
- Coroutines (already used).
- (Optional later) OpenCV Android SDK — only if we need it.

Assets: `app/src/main/assets/yolo11n-seg.onnx` (+ label list). We'll re-export from `Yolo-test` if the existing `.onnx` head format doesn't match a clean YOLO11-seg decode.

---

## 5. Milestones

Each milestone: **Theory → Implement → Why it works → Verify → Common mistakes.** We do **not** advance until the verification passes on a real device.

### Milestone 1 — ARCore app: planes + feature points
- **Theory:** ARCore SLAM tracks the device pose by fusing camera + IMU; it surfaces *planes* and *feature points* (the sparse map it's confident about). This proves the session, permissions, and rendering work.
- **Build:** Compose `ARScene`, camera permission flow, render detected planes + point cloud of feature points. `ARSessionManager` skeleton.
- **Verify:** Move phone around a table → planes grow, feature points appear, no crashes; tracking state = TRACKING.
- **Mistakes:** Forgetting `CAMERA` permission; not installing/updating ARCore ("Google Play Services for AR"); testing on emulator (needs a real ARCore-capable device).

### Milestone 2 — Display the depth map
- **Theory:** ARCore Depth API returns a per-frame 16-bit depth image (millimeters, from device ToF or motion-stereo). Lower res than RGB. This is the raw material for 3D.
- **Build:** Acquire `frame.acquireDepthImage16Bits()`, colorize (near=warm/far=cool), show as a toggleable overlay. Log depth resolution + intrinsics.
- **Verify:** Overlay tracks scene geometry; nearer objects distinctly colored; confirm `isDepthModeSupported`.
- **Mistakes:** Not closing the `Image` (leak → freeze); assuming depth == RGB resolution; ignoring the per-pixel confidence image.

### Milestone 3 — Object detection
- **Theory:** YOLO takes a 640×640 RGB tensor → boxes + class scores in one pass. We convert the ARCore YUV frame → RGB → normalized tensor.
- **Build:** `YoloSegDetector` (ONNX Runtime, NNAPI EP), YUV→RGB, letterbox resize, decode boxes, NMS. Draw boxes + labels. Pick the single **target** object (largest/most-central, or user tap).
- **Verify:** Boxes track a chair/suitcase across frames at a usable FPS; correct labels.
- **Mistakes:** Wrong color order (RGB vs BGR); forgetting letterbox → coords don't map back; running inference on the main thread; not applying NMS.

### Milestone 4 — Segmentation masks
- **Theory:** YOLO11-Seg outputs *k* mask prototypes + per-detection coefficients; the instance mask = sigmoid(coeffs · prototypes), cropped to the box, thresholded at 0.5. This is *the* step that isolates only the target object.
- **Build:** `MaskProcessor`: assemble the mask for the chosen detection, threshold, resize to **depth-image** resolution (nearest-neighbor to keep it binary). Render as semi-transparent overlay.
- **Verify:** Mask hugs the object, excludes background/floor. Toggle overlay on/off.
- **Mistakes:** Mixing up mask vs image coordinate spaces; bilinear-resizing a binary mask (blurs edges); not accounting for the letterbox padding when mapping mask→image.

### Milestone 5 — Segmented object → 3D point cloud
- **Theory & math (the core CV):**
  For each masked pixel `(u,v)` with depth `Z` (meters) and intrinsics `fx,fy,cx,cy`:
  ```
  X = (u - cx) * Z / fx
  Y = (v - cy) * Z / fy
  Z = Z
  ```
  This is the **pinhole camera model inverted** (back-projection): the camera projects 3D→2D by `u = fx·X/Z + cx`; we solve for X,Y given the measured Z. The result `(X,Y,Z)` is in the **camera** frame. Multiply by the camera **Pose** (world_T_camera) to get **world** coordinates, so the cloud stays stable as the phone moves.
  *Critical alignment:* the mask is in RGB space, depth is lower-res — we must sample depth at the mask pixel's corresponding depth-space location using the depth image's own intrinsics.
- **Build:** `PointCloudBuilder` → `List<Float3>` in world space. Debug renderer to draw the points.
- **Verify:** Rotate the phone → the cloud stays locked to the object in world space and visibly forms its shape.
- **Mistakes:** Using RGB intrinsics on the depth image; forgetting mm→m (÷1000); not transforming to world (cloud swims when you move); including Z==0 invalid-depth pixels.

### Milestone 6 — Oriented Bounding Box via PCA
- **Theory:** An **AABB** (axis-aligned) is fixed to world X/Y/Z, so a chair rotated 30° gets an inflated, wrong box. An **OBB** finds the object's *own* axes. **PCA:** compute the 3×3 covariance of the (centered) points; its **eigenvectors** are the principal axes (directions of greatest spread), **eigenvalues** rank them. Project points onto these axes; the min/max extent along each axis gives the box half-sizes. → Width/Height/Depth = the three extents.
  - **Why OBB > AABB:** tight fit regardless of object orientation → accurate dimensions; AABB overestimates whenever the object isn't aligned to world axes.
- **Build:** `ObbFitter` (covariance + symmetric-3×3 eigen-decomposition in `util/`). Optionally constrain the "up" axis to gravity (ARCore gives gravity) for more stable H. `OutlierFilter` before fitting (drop points > k·σ from centroid; optional voxel downsample).
- **Verify:** Box wireframe encloses the object snugly and rotates with it; extents are physically plausible.
- **Mistakes:** Not centering before covariance; eigenvector sign/order ambiguity; letting outliers (floor/wall bleed-through) stretch an axis; degenerate flat clouds.

### Milestone 7 — Live dimensions
- **Theory:** Convert extents (meters) → cm (×100) and inches (×39.37). Smooth across frames (moving average / low-pass) so numbers don't jitter.
- **Build:** `LabelRenderer` anchored to the OBB; Compose HUD showing W/H/D in cm + in; freeze/measure button; optional debug toggles (mask, cloud, box).
- **Verify:** Measure a known object (box/suitcase you can tape-measure) → within a sane error band (goal: right order of magnitude, ideally <~15%). Numbers stable, not flickering.
- **Mistakes:** Reporting raw per-frame values (jittery); confusing which extent is W vs D (define by axis, and label consistently); unit conversion errors.

---

## 6. Cross-cutting: debugging techniques (used throughout)
- **Visual overlays as assertions:** if the mask, cloud, or box looks wrong on screen, the math is wrong — see it, don't guess.
- **Log intrinsics & resolutions** every session; most bugs are coordinate-space mismatches.
- **Freeze-frame mode:** capture one frame's RGB+depth+pose and iterate on detection/segmentation/OBB offline against it.
- **Known-object calibration:** keep a taped box of known size to sanity-check every stage.
- **Stage isolation:** each package has a debug render mode so we can verify one stage with the others stubbed.

## 7. Risks / assumptions
- Requires a **physical, ARCore Depth-capable device** (no emulator). **Target device locked: OnePlus Nord 5** (Snapdragon 8s Gen 3, Android 15) — ARCore-certified **and** Depth-API-supported per Google's official list.
  - **Samsung Galaxy S20 FE is NOT usable for the depth stages** — ARCore-certified but Google lists it as *"Does not support Depth API."* It can still run Milestones 1, 3, 4 (tracking, detection, segmentation) but cannot do 2/5/6/7. Use the Nord 5 as the real target.
  - In-app truth check: `session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)` decides at runtime; we branch on it in Milestone 1/2.
- ARCore depth is noisy on thin/reflective/dark objects → outlier filtering matters.
- YOLO nano may miss uncommon objects → fall back to `s` variant or add tap-to-select.
- ONNX head decoding must match the exported model; we may re-export a clean `yolo11n-seg.onnx` from `Yolo-test`.

## 8. Deliverable cadence
Build **milestone by milestone**, not all at once. At each: I explain the theory, implement, explain why it works, and we **verify on-device before advancing**. Each milestone ends in a runnable app.

---

### Immediate next step (Milestone 1)
Scaffold the Compose + SceneView project in `ARTest2/` (Gradle, manifest, permissions, `ARSessionManager`, plane + feature-point rendering), then verify tracking on your device.

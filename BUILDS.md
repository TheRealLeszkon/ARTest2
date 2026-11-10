# BUILDS — approach3 runnable APK log

Each entry is a committed, installable debug APK snapshotting the app at a milestone, so the
build can be verified on the OnePlus Nord 5 later. APKs live under `builds/` and are stored via
Git LFS (`*.apk`). Newest first.

```bash
# One-time: point ADB at the platform-tools shipped with the Android SDK.
ADB=$HOME/Library/Android/sdk/platform-tools/adb

# Generic install + launch (swap in the APK path from the entry you want):
$ADB wait-for-device
$ADB install -r <apk-path>
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

The Nord 5 ADB link is flaky; if `install` drops, re-run `$ADB wait-for-device` and retry.
Package id: `com.example.arsurvey`.

---

## Build 09 — Tap-to-select target + world-space lock + drift fix
- **APK:** `builds/approach3-09-tap-select.apk`
  (find its commit: `git log --oneline -- builds/approach3-09-tap-select.apk`)
- **What's in it:** explicit **tap-to-select** — tap an object and the detection whose *segmentation
  mask* covers the tap (smallest wins) becomes the target (`MaskProcessor.pickDetectionAtPoint`,
  single-point proto eval; `TargetTracker.lockTo`). The target's persistent identity is now its
  **world-space centroid** (`ArUiState.targetWorldCentroid`), established from the locked mask's
  back-projected depth and EMA-tracked — screen coords are no longer the identity. Isolation gate +
  a new **world veto** (`WORLD_LOST_TOL`) anchor on that centroid, so a frame whose mask jumped to
  another object is rejected wholesale. **Drift fix:** a world-anchored `TargetMarkerOverlay`
  reticle (`LOCKED 🔒`) is reprojected from the live camera pose every frame, so it stays glued to
  the object (vs the inference-lagged 2D detection box). HUD adds a tap hint, `Target xyz`, and
  keeps the lock/match debug. Measurement geometry unchanged. 16 JVM unit tests (tap containment +
  tracking).
- **On-device check (the key experiment):** two objects in view. **Tap object A** → `Lock: LOCKED 🔒`,
  green reticle sits on A. Walk fully around A. PASS if: the reticle stays on A as the camera orbits
  (does not drift off-screen), object B never becomes the target (tap it deliberately to switch —
  that should work; walking past it should not), and B's geometry never joins the green accumulated
  cloud. Then A single-object vs cluttered W×H×D should agree if contamination was the culprit.
- **Note:** tap-to-select needs YOLO to detect the object under the tap. A plain cardboard box is
  not a clean COCO class, so the detector may report 0 obj or an odd label — tapping still locks
  onto whatever detection covers the point regardless of its label.

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device
$ADB install -r builds/approach3-09-tap-select.apk
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

## Build 08 — Target locking (object isolation)
- **APK:** `builds/approach3-08-target-lock.apk`
  (find its commit: `git log --oneline -- builds/approach3-08-target-lock.apk`)
- **What's in it:** a target-lock stage so a scan stays on ONE physical object and surrounding
  furniture can't contaminate the accumulator. New `com.example.arsurvey.track.TargetTracker`
  (class + bbox-IoU + screen-center scoring; prevents object switching, tolerates brief loss,
  reacquires) replaces the old "largest detection every frame" pick. New
  `com.example.arsurvey.track.TargetIsolation` gates each frame's masked points to a horizontal
  radius (`TARGET_RADIUS_M = 0.9 m`) around the locked target's established center before they
  enter the accumulator. **Measurement geometry is unchanged** — this only improves which points
  feed it. HUD adds a `Lock` line (`ACQUIRING…` / `LOCKED 🔒` / `LOST — searching`) plus match
  debug (`s/iou/cΔ`, `Candidates`, `World Δ`); the target box is amber while acquiring, green when
  locked. 11 JVM unit tests cover the tracking + isolation logic.
- **On-device check (the key experiment):** Put TWO objects in the scene. Aim at object A, tap
  **Scan** (Lock → `LOCKED 🔒`, box green). Walk around A until object B enters view. PASS if the
  lock STAYS on A, B's box stays thin yellow (non-target), and the green accumulated cloud never
  jumps onto B. Then compare A's W×H×D to ground truth vs a single-object scan — the point is
  whether removing contamination fixes the earlier blow-ups (e.g. a 37 cm box reading as metres).

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device
$ADB install -r builds/approach3-08-target-lock.apk
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

## Build 07 — Debug overlays: coverage-sector ring + camera trajectory
- **APK:** `builds/approach3-07-debug-overlays.apk`
  (find its commit: `git log --oneline -- builds/approach3-07-debug-overlays.apk`)
- **What's in it:** the last two overlays from `docs/scanning-ui.md`. A **coverage ring** of
  angular sectors on the floor around the object centroid (bright green = observed, faint =
  not), and the **camera trajectory** polyline (orange) of the phone's sampled world path while
  scanning. Completes the debugging surface; no measurement-logic change.
- **On-device check:** Scan and orbit an object. PASS if the green ring sectors light up as you
  reach each side, the faint sectors show what's left, and the orange trail traces your path
  around the object (a clean arc, not a jumpy line).

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device
$ADB install -r builds/approach3-07-debug-overlays.apk
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

## Build 06 — Increment 3 / M-FloorPlane: ARCore floor detection + validation
- **APK:** `builds/approach3-06-floor-plane.apk`
  (find its commit: `git log --oneline -- builds/approach3-06-floor-plane.apk`)
- **What's in it:** `FrameExtractor` now takes the ARCore `Session` and reports the floor plane's
  world Y (largest horizontal-up tracking plane). `FloorEstimator` accepts it only if it agrees
  with the object base within 20 cm, then uses it to refine the floor. COMPLETE now requires a
  validated floor plane (`REQUIRE_FLOOR_PLANE`). HUD floor line shows `ARCore plane ✓` vs
  `object base (no plane)`.
- **On-device check:** Scan a floor object. PASS if, once ARCore has mapped the floor, the HUD
  floor line flips to `ARCore plane ✓`, Height stays consistent, and the scan can reach COMPLETE.
  If it stays on `object base (no plane)`, the floor plane isn't being detected — pan the floor
  more, or (bring-up only) flip `REQUIRE_FLOOR_PLANE` to false.

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device
$ADB install -r builds/approach3-06-floor-plane.apk
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

## Build 05 — Increment 2 / M-Stability + Result: convergence, COMPLETE, ObjectMeasurement
- **APK:** `builds/approach3-05-stability-result.apk`
  (find its commit: `git log --oneline -- builds/approach3-05-stability-result.apk`)
- **What's in it:** `MeasurementStabilizer` (EMA smoothing + windowed convergence + 0..1
  stability) drives the `SCANNING → STABILIZING → COMPLETE` state machine; `ObjectMeasurement`
  (cm + confidence + coverage + ISO timestamp, `toJson()`) is emitted on COMPLETE. HUD gains
  `Confidence High/Medium/Low` and a `— MEASUREMENT READY —` banner. Completion criteria:
  ≥3 sectors covered, ≥400 accumulated voxels, valid floor, dimensions converged (<2% spread
  over 8 ticks). This is the end-to-end multi-view pipeline.
- **On-device check (full run):** place a known object on the floor → **Scan** → slowly walk
  around it. PASS if: Coverage climbs, W/H/D converge and stop drifting, state advances
  SCANNING→STABILIZING→COMPLETE on its own, and the READY banner + Confidence appear. Then
  compare W×H×D to ground truth (record per `docs/development-process.md`).

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device
$ADB install -r builds/approach3-05-stability-result.apk
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

## Build 04 — Increment 2 / M-Measurement: authoritative W×H×D
- **APK:** `builds/approach3-04-measurement-whd.apk`
  (find its commit: `git log --oneline -- builds/approach3-04-measurement-whd.apk`)
- **What's in it:** `MeasurementEstimator` stitches floor-relative height + footprint rectangle
  into the authoritative W×H×D and a gravity-vertical measured box (cyan overlay, gated on the
  Box toggle alongside the white PCA debug box). HUD now reports `Width/Height/Depth` from the
  measurement, with `PCA(dbg)` shown separately for comparison.
- **On-device check:** Scan a known box. PASS if the cyan measured box hugs the object and its
  W×H×D is closer to ground truth than `PCA(dbg)`; the cyan box should stay upright (gravity
  vertical) and not tilt like PCA can.

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device
$ADB install -r builds/approach3-04-measurement-whd.apk
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

## Build 03 — Increment 2 / M-Footprint: min-area rectangle
- **APK:** `builds/approach3-03-footprint-rect.apk`
  (find its commit: `git log --oneline -- builds/approach3-03-footprint-rect.apk`)
- **What's in it:** `FootprintEstimator` — projects the accumulated cloud to X/Z, robust radial
  trim → convex hull → rotating-calipers **minimum-area oriented rectangle**. Amber rectangle
  overlay drawn flat on the floor; HUD `Footprint W×D cm`. This is the non-PCA horizontal method.
- **On-device check:** Scan a rectangular object (box/suitcase) rotated off the world axes. PASS
  if the amber rectangle hugs the object's true footprint and its size stays stable regardless of
  phone orientation (it should NOT inflate when the object is diagonal to you).

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device
$ADB install -r builds/approach3-03-footprint-rect.apk
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

## Build 02 — Increment 2 / M-Floor: gravity-aligned floor + height
- **APK:** `builds/approach3-02-floor-height.apk`
  (find its commit: `git log --oneline -- builds/approach3-02-floor-height.apk`)
- **What's in it:** `FloorEstimator` — robust percentile floor (3rd pct of world Y) + object
  top (97th pct) computed on the **accumulated** cloud each scan tick. HUD gains `Height*` once
  the floor estimate is valid (≥40 accumulated voxels).
- **On-device check:** Scan a floor object from a few angles; `Height*` should settle near the
  object's true height and stay stable as you keep moving (it's measured off the fused cloud, not
  one frame). `*` = floor derived from the object base, not yet an ARCore plane.

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device
$ADB install -r builds/approach3-02-floor-height.apk
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

## Build 01 — Increment 1: multi-view accumulation
- **APK:** `builds/approach3-01-multiview-accum.apk`
  (find its commit: `git log --oneline -- builds/approach3-01-multiview-accum.apk`)
- **What's in it:** `ScanState`, `PointCloudAccumulator` (bounded voxel grid), `CoverageTracker`
  (12 sectors) wired into `MeasureViewModel`. New UI: Scan / Stop scan / Reset / Accum chips,
  green accumulated-cloud overlay, HUD `Scan` / `Coverage %` / `Accum` lines.
- **On-device check (go/no-go):** place one object on the floor, tap **Scan**, slowly walk
  around it. PASS if the green accumulated points stay locked to the object surface and fill it
  in from multiple sides while **Coverage** climbs past ~25 % (3/12 sectors). FAIL = points
  smear/duplicate → debug tracking/pose/depth, not the box math.

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
$ADB wait-for-device
$ADB install -r builds/approach3-01-multiview-accum.apk
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

# ARTest2 — Progress & Session Handoff (approach3)

> **Read this first each session.** Living status log for the multi-view on-device
> object-measurement PoC on branch **`approach3`**. Source of truth for *where we are*.
> `PLAN.md` + `docs/development-process.md` describe *where we're going*; the reference docs
> under `docs/` (architecture, measurement-pipeline, scanning-ui, verification, rules) hold the
> theory and hard rules. Runnable per-commit APK builds are logged in **`BUILDS.md`**.

_Last updated: 2026-08-08 — Increment 5 (tap-to-select + world-space lock + drift fix) built,
unit-tested (16 JVM), and verified on device (launch/HUD/tap-pipeline). Physical two-object
walk-around still needs a human._

---

## What approach3 is

Rework ARTest2 from a **single-view** measurement experiment into a **multi-view on-device
measurement system**. The old `main`-branch pipeline treated every frame independently:
segment → masked depth → world cloud → PCA oriented box → dimensions, all rebuilt per frame,
nothing persisted. approach3 instead **accumulates** masked world points from many viewpoints
into one ARCore world-coordinate reconstruction and measures *that*.

**Core go/no-go experiment (must pass before trusting any dimension):**
> Do points captured from 3+ different phone viewpoints reconstruct the same physical object
> consistently in ARCore world coordinates?

If NO — debug tracking / pose / depth alignment / segmentation, **not** the box algorithm.

---

## Documentation state legend

Per `docs/verification.md`, every milestone is tracked through these states:

```
Implemented → Built → Installed → Verified on device → Physically validated → Accuracy validated
```

- **Implemented** — code written, reviewed.
- **Built** — compiles + APK assembled.
- **Installed** — APK installed on the Nord 5.
- **Verified on device** — runs, HUD/overlays behave, no crash (subagent-checkable).
- **Physically validated** — requires a human to walk around a real object.
- **Accuracy validated** — measured against known ground truth.

---

## Increment / milestone status

### Increment 1 — multi-view accumulation (the core experiment)
**State: Built.** Not yet verified on device.

New package `com.example.arsurvey.scan`:
- `ScanState` — `IDLE → SCANNING → STABILIZING → COMPLETE`.
- `PointCloudAccumulator` — bounded voxel grid (1.5 cm cells, 80k cap); fuses masked world
  points across frames, averaging a running centroid per voxel.
- `CoverageTracker` — 12 angular sectors around the object centroid from the camera pose;
  `minSectorsForScan = 3` (configurable, not hard-coded).

Wiring: `MeasureViewModel.onFrame` folds each frame's outlier-filtered `PointCloudBuilder`
output into the accumulator while `SCANNING` (throttled ~12 Hz) and updates coverage from the
pose translation. `ArUiState` carries scan/accum/coverage fields. `ArScreen` adds a green
accumulated-cloud overlay (vs magenta per-frame), Scan/Stop/Reset/Accum chips, and HUD lines
(`Scan`, `Coverage %`, `Accum: N vox · M obs`). Existing per-frame cloud + PCA OBB path left
intact for comparison.

**Reused unchanged:** `PointCloudBuilder`, `OutlierFilter`, `FrameExtractor`, `YoloSegDetector`,
`MaskProcessor`, overlay/HUD plumbing.

**Needs on device:** aim at one floor object, tap **Scan**, walk around it; confirm green
points stay locked to the object surface and fill it in from multiple sides while Coverage
climbs past ~25 % (3/12). This is the go/no-go check.

### Increment 2 — measurement stack
**State: Built.** Not yet verified on device. New classes in `com.example.arsurvey.measure`:
- **M-Floor / Height** — `FloorEstimator`: robust percentile floor (3rd pct world Y) + top
  (97th pct) on the accumulated cloud; `H = top − floor`. (Build 02)
- **M-Footprint** — `FootprintEstimator`: X/Z projection → radial trim → convex hull (monotone
  chain) → rotating-calipers **min-area oriented rectangle** → width/depth. (Build 03)
- **M-Measurement** — `MeasurementEstimator`: stitches floor height + footprint into
  authoritative W×H×D and a gravity-vertical measured box (cyan overlay). PCA (`ObbFitter`) is
  now debug-only. (Build 04)
- **M-Stability / Result** — `MeasurementStabilizer` (EMA + windowed convergence + 0..1 stability)
  drives `SCANNING → STABILIZING → COMPLETE`; `ObjectMeasurement` (cm + confidence + coverage +
  ISO timestamp, `toJson()`) is emitted on COMPLETE. HUD shows Confidence + MEASUREMENT READY.
  Completion needs: ≥3 sectors, ≥400 voxels, valid floor, and converged dimensions. (Build 05)

### Increment 3 — ARCore floor-plane validation
**State: Built.** Not yet verified on device. (Build 06)
- `FrameExtractor` now receives the ARCore `Session` and returns `floorPlaneY` = world Y of the
  largest horizontal, upward-facing, tracking, non-subsumed plane.
- `FloorEstimator.estimate(cloud, floorPlaneY)` accepts the plane **only if** it agrees with the
  object's own base percentile within 20 cm (so it's really this object's floor, not another low
  surface); when accepted it also *refines* the floor (a plane fit is less noisy than a depth
  percentile). Returns `fromPlane`.
- COMPLETE now additionally requires a validated floor plane (`REQUIRE_FLOOR_PLANE = true`),
  honoring the doc rule that the floor must be detected/validated before a final measurement.
- HUD shows floor source: `ARCore plane ✓` vs `object base (no plane)`.

### Debug overlays — complete (Build 07)
All overlays listed in `docs/scanning-ui.md` are now implemented: segmentation mask, depth,
per-frame cloud (magenta), accumulated cloud (green), footprint rectangle (amber), measured box
(cyan), PCA debug box (white), dimensions label, **coverage-sector ring** (green observed /
faint unobserved, on the floor) and **camera trajectory** (orange polyline of the phone's path).

### Increment 4 — target locking / object isolation
**State: Verified on device (launch/HUD), not yet physically validated. Unit-tested (11 JVM tests).** (Build 08)

On-device 2026-08-08 (Nord 5, CPH2805): installed, launched, no crash; HUD renders the new
`Lock: —` line (NO_TARGET before Scan, as designed), Depth API supported, detector on NNAPI.
Scene was dark (`Tracking: PAUSED`, `INSUFFICIENT_LIGHT`) so no object was detected/locked —
the lock/scan flow still needs a human to aim at a lit floor object and orbit it.

**Why:** the first real multi-view scan (box on a cluttered floor) blew up — HUD showed
Height ≈ 626 cm, Footprint 1177×580 cm, target misclassified as `toilet 64%`. Root cause: the
old target pick chose the **largest detection every frame independently**, so it grabbed a
misdetection with an oversized mask (and could switch objects mid-walk), letting room-scale
depth into the accumulator. This is the surrounding-object contamination the mentor warned about.

**What (measurement algorithm deliberately unchanged — this only changes *which points* feed it):**
- `com.example.arsurvey.track.TargetTracker` — persistent target identity in image-pixel space.
  Scores each candidate against the locked target with class + bbox-IoU + screen-center distance
  (class alone is insufficient — two boxes both say `box`). Prevents switching to a different
  object even if it's bigger / higher-confidence; tolerates a few missed detections
  (`TEMPORARILY_LOST`, accumulation paused, not switched); reacquires the original on return.
  Replaces `pickTarget` (largest-detection). Armed on **Scan**, reset on Stop/Reset.
- `com.example.arsurvey.track.TargetIsolation` — horizontal (X/Z) radius gate
  (`TARGET_RADIUS_M = 0.9 m`) around the locked target's established center (the accumulator
  centroid), applied to each frame's masked points **before** they enter the accumulator. Second
  line of defence against mask bleed onto a neighbour. Vertical bleed is still handled by the
  existing `OutlierFilter` + percentile floor.
- HUD: `Lock` line (`ACQUIRING…`/`LOCKED 🔒`/`LOST — searching`) + `Match s/iou/cΔ`, `Candidates`,
  `World Δ`. Target box amber while acquiring, green when locked. `TargetTracker` logs
  `TARGET_ACQUIRED/MATCH/LOST/REACQUIRED/REJECTED`.

**Reused unchanged:** `PointCloudBuilder`, `OutlierFilter`, `PointCloudAccumulator`,
`FloorEstimator`, `FootprintEstimator`, `MeasurementEstimator`, `MeasurementStabilizer` — all
downstream geometry is untouched, so an A/B (with vs without contamination) is a clean comparison.

**Needs on device (the key experiment):** two objects in frame; Scan object A; walk until B
appears; the lock must stay on A and B's geometry must never enter the accumulator. Then compare
A's W×H×D single-object vs cluttered to see if isolation fixes the earlier blow-ups.

### Increment 5 — tap-to-select target + world-space lock + drift fix
**State: Verified on device (launch/HUD/tap-pipeline), not yet physically validated. Unit-tested
(16 JVM tests).** (Build 09)

**Why:** target selection should be explicit (tap the object you mean), and the on-device run showed
the target box visualization drifting as the camera moved — plus we still needed the target's
identity to live in world space, not screen space.

**What (measurement geometry still unchanged):**
- **Tap-to-select** — `MaskProcessor.pickDetectionAtPoint` finds the detection whose segmentation
  mask covers the tap (single-point sigmoid(coeffs·protos) eval, smallest-area wins); a tap on empty
  space selects nothing. `TargetTracker.lockTo` forces that detection as the identity. Tap works in
  IDLE (before Scan); Scan keeps a pre-selected target instead of re-arming. Tap plumbed via a
  full-screen `pointerInput` layer under the HUD.
- **World-space identity** — `ArUiState.targetWorldCentroid` is established from the locked mask's
  back-projected depth (render thread) and EMA-tracked (`WORLD_CENTROID_EMA`). This — not the tap
  pixel or initial screen box — is the persistent target. Isolation gate now anchors on it, and a
  **world veto** (`WORLD_LOST_TOL = 0.6 m`) rejects a whole frame whose masked-cloud centroid
  jumped away (mask switched objects).
- **Drift fix** — `TargetMarkerOverlay` reprojects `targetWorldCentroid` through the *current* frame
  VP matrix every frame (reticle + `LOCKED 🔒`), so it stays glued to the object as the camera
  orbits. This makes the world-space source of truth visible and distinguishes a *visualization*
  drift (stale 2D detection box) from a *world-transform* drift (the reticle itself would wander).
  The existing measured/OBB boxes already reproject world corners each frame — confirmed as the
  correct pattern; no per-pixel box-nudging anywhere.
- HUD: tap hint (`Tap an object to measure` / `No object there…`), `Target xyz`, lock/match debug.

**On-device 2026-08-08 (Nord 5, CPH2805):** installed, launched, no crash; lit scene TRACKING with
a box visible; HUD renders the new `Lock: —` + `Tap an object to measure` hint; the tap pipeline is
live — a stray launch tap logged `TARGET_SELECT_MISS tap=(328,209)` (correctly hit nothing).
**Limitation seen:** YOLO (COCO) did not detect the plain cardboard box (`0 obj`); tap-to-select
depends on a detection existing under the tap, so a clean COCO object (or accepting the odd label
YOLO assigns) is needed for the physical walk-around.

**Needs on device (the key experiment):** two objects; tap A → green reticle locks on A; walk fully
around A; reticle must stay glued to A (not drift off-screen), B must not become target or enter the
accumulator. Then compare A clean vs cluttered W×H×D.

### On-device (2026-08-08) — state: Verified on device (launch/tracking), not yet physically validated

**First multi-view scan attempt (pre-Increment-4):** ran end-to-end but produced grossly inflated
dimensions (Height ≈ 626 cm) from surrounding-geometry contamination — the finding that motivated
Increment 4 above.


**OnePlus Nord 5 (CPH2805, Android 16 — the documented device):** built from source via
`:app:installDebug` and launched. **Runs and TRACKS**: HUD shows `Tracking: TRACKING`,
`Depth API: supported`, `Depth res: 160x90`, `Detector: 0 obj · 1432ms · NNAPI` (first inference
includes the NNAPI compile), `Scan: IDLE`; new multi-view UI renders (Scan/Reset/Accum + Freeze +
Depth/Mask/Cloud/Box); no crash. Camera was aimed at a blank wall/floor so no object was detected
(0 obj) and `PCA(dbg)` is untracked-target noise.

**Still not physically validated:** the measurement pipeline (target selection → Scan →
accumulation → coverage → floor plane → W×H×D → STABILIZING → COMPLETE) needs a human to point at
a household object on the floor and slowly orbit it. Not doable from the tooling.

Earlier same-day launch on a Samsung **SM-G781B (Galaxy S20 FE 5G)** also ran fine but was in
`INSUFFICIENT_LIGHT` (tracking PAUSED) — kept only as a "launches on a second device" data point.

### Non-device work: complete
Everything buildable without hardware is done. The remaining work is all device-gated:
on-device verification of each build, the core multi-view-alignment go/no-go, M9 accuracy vs
known objects, and tuning the constants (voxel size, throttle, completion thresholds) from real
point-density/convergence data. See `BUILDS.md` for the per-build device checks.

### Pipeline state machine (as built)
```
IDLE ──Scan──▶ SCANNING ──(≥3 sectors + ≥400 vox + floor + converging)──▶ STABILIZING
                  ▲                                                            │
                  └───────────────── Stop scan ────────────────────┐          │(dims converge <2% over 8 ticks)
                                                                    │          ▼
IDLE ◀── Reset ── COMPLETE ◀───────────────────────────────────────┴──── COMPLETE (emits ObjectMeasurement)
```
Collection (accumulation + coverage + measurement) runs during SCANNING **and** STABILIZING;
it stops at COMPLETE so the result is held. Scan again from COMPLETE starts a fresh scan.

---

### Capture-first: predicted measurement (2026-08-18) — Built + unit-tested
The `capture-first-workflow` branch now closes the loop to the project's headline result:
**W × H × D in cm** for a captured object, on the 3D Reconstruction screen. New
`reconstruct/MeasurementCalculator` feeds the combined 3-photo world cloud through the existing
`measure/` stack (`OutlierFilter → FloorEstimator → FootprintEstimator → MeasurementEstimator →
ObjectMeasurement`) — no new geometry, no PCA as the authoritative value. A **Predicted Measurement**
card + an **amber oriented-box** overlay render the result; the AABB extents stay as debug.
`:app:assembleDebug` + `:app:testDebugUnitTest` green (incl. `MeasurementCalculatorTest`).
Floor uses the cloud-base percentile (`floorPlaneY = null` — captures save no plane); confidence is an
uncalibrated heuristic. **On-device Verified / Accuracy validated: pending a connected Nord 5** and a
human tape-measure check. Full write-up in `docs/CAPTURE_FIRST.md`.

## Known follow-ups / risks

- Voxel size (1.5 cm) and accumulate throttle (80 ms) are untuned guesses — revisit after the
  first device run shows point density.
- Coverage pivot is the accumulator centroid, which drifts early in a scan when few points
  exist; acceptable for the first experiment.
- Per-frame `OutlierFilter` before accumulation removes gross floor/wall bleed, but a second
  pass on the *accumulated* cloud will be needed for measurement (Increment 2, M-Floor).
- Depth/RGB aspect + rotate-90 overlay assumptions inherited from single-view code are
  unverified on the Nord 5 for the accumulated overlay.
- `REQUIRE_FLOOR_PLANE = true` blocks COMPLETE until ARCore reports a floor plane agreeing with
  the object base. Expected fine for an object on a lit floor, but if planes are flaky the scan
  will sit in STABILIZING — flip the constant to relax during bring-up. The floor-source HUD line
  makes this diagnosable.
- Floor plane picked as the *largest* horizontal-up plane; a very large table could in principle
  outrank the floor. The ±20 cm agreement check with the object base mitigates this.

---

## Device / verification

- Device: OnePlus Nord 5 (CPH2805, Android 16), ARCore Depth-capable.
- `ADB=$HOME/Library/Android/sdk/platform-tools/adb`
- Install + launch commands per build are in `BUILDS.md`.
- On-device verification runs in a parallel **sonnet** subagent (see `docs/verification.md`);
  physical walk-around checks are flagged for the human, never auto-failed.

# Capture-First Workflow (branch `capture-first-workflow`)

An **isolated, revertible** alternative to the live-measurement pipeline. Instead of running
segmentation/depth/PCA/measurement live on every frame, the user takes **3 photos per object** and
we persist each full-resolution image together with the exact ARCore frame metadata. No ML,
segmentation, depth processing, PCA, or dimension math runs during capture. Measurement (if any)
happens later, off this device, from the persisted data.

This branch changes only the **entry point** and adds a new `com.example.arsurvey.capture` package.
The live-measurement code (`MeasureViewModel`, `ArScreen`, and the whole `measure`/`scan`/`track`/
`cloud`/`segment`/`detect` stack) is left untouched. Revert the branch to restore the old workflow.

---

## User flow

```
IDLE ──► CAPTURE (live camera, silent ARCore session)
             │  tap shutter × 3  (no ML runs)
             ▼
         REVIEW (the 3 images + full ARCore metadata per image)
             │  "Capture another object"
             ▼
         new CAPTURE
```

- **Capture screen:** live camera, a shutter button, an "N of 3" counter, and a one-line tracking
  hint. Nothing else — no debug overlays.
- After the 3rd photo it auto-navigates to **Review**.
- **Review screen:** each saved image (rotated upright) with a **full metadata panel** beneath it
  (timing, tracking state, camera pose, intrinsics, image size, depth, and an expandable
  transform-matrix block). This is the on-device proof that image + metadata were captured and
  persisted.

---

## Architecture

New package `com.example.arsurvey.capture`:

```
capture/
├── model/
│   ├── CapturedObject.kt        // one object → 3 CapturedPhoto (PHOTOS_PER_OBJECT = 3)
│   ├── CapturedPhoto.kt         // index + JPEG path + ARCoreFrameMetadata
│   └── ARCoreFrameMetadata.kt   // SDK-free geometry snapshot; null = unavailable (never faked)
├── data/                        // Room persistence (see "Persistence" below)
│   ├── CaptureEntities.kt       // CapturedObjectEntity, CapturedPhotoEntity, ObjectWithPhotos
│   ├── CaptureDao.kt            // insert + observeAllObjects(): Flow + getObject(id)
│   ├── CaptureDatabase.kt       // @Database v1, singleton
│   ├── CaptureMetadataJson.kt   // ARCoreFrameMetadata ↔ JSON (shared serializer)
│   └── CaptureMappers.kt        // entity ↔ domain-model mapping
├── CaptureFrameExtractor.kt     // pulls image + raw depth + geometry off the Frame (NO ML)
├── CameraImageJpegEncoder.kt    // reuses ar/YuvImage; YUV_420_888 → NV21 → full-res JPEG
├── CaptureRepository.kt         // writes image/depth files + records rows in Room
├── CaptureViewModel.kt          // silent AR session, tracking-gated capture, 3-photo limit, nav
├── CaptureUiState.kt            // screen (CAPTURE/REVIEW/CATALOG/DETAIL) + counter + photos
└── ui/CaptureFlowScreen.kt      // capture + review + catalog + object-detail screens
```

The relationship, as required:

```
CapturedObject
 ├── CapturedPhoto 0 → ARCoreFrameMetadata
 ├── CapturedPhoto 1 → ARCoreFrameMetadata
 └── CapturedPhoto 2 → ARCoreFrameMetadata
```

### Reused existing components
- **SceneView `ARScene`** session wiring (same as `ArScreen`) — the ARCore session runs silently.
- **`ar/YuvImage`** — the existing stride-aware camera-image plane copy, reused for JPEG encoding.
- Same depth-support probe (`isDepthModeSupported`) and session config pattern as the live screen.

---

## Persistence (files on disk + Room)

The heavy binaries (JPEG + raw depth) stay as **files** on app-internal storage; the catalog index
and all ARCore metadata live in a **Room** database (`capture.db`). This keeps the DB small and the
catalog fast while making every capture queryable/retrievable for later analysis.

```
filesDir/captures/<objectId>/
├── photo_0.jpg     # full-resolution camera image (sensor-landscape), 3 per object
├── photo_1.jpg
├── photo_2.jpg
├── depth_0.raw     # raw uint16 mm depth, little-endian, row-major, when available
├── depth_1.raw
└── depth_2.raw

Room (capture.db):
  captured_objects(id, createdAtEpochMs)
  captured_photos(objectId FK, photoIndex, imagePath, depthRawPath,
                  + promoted queryable columns: capturedAtEpochMs, frameTimestampNs,
                    trackingState, imageWidth/Height, depthAvailable, depthWidth/Height,
                  + metadataJson  // full ARCoreFrameMetadata, lossless, via CaptureMetadataJson)
```

**Dependencies added:** KSP `1.9.25-1.0.20` (root plugins, `apply false`; applied in `app`) and
Room `2.6.1` (`room-runtime`, `room-ktx`, `ksp room-compiler`). No version catalog exists, so these
are string literals matching the project style.

The earlier `object.json` file manifest was removed — Room is now the index. A few frequently-useful
fields are promoted to their own columns for querying; the complete metadata round-trips losslessly
through the `metadataJson` column (unavailable values stay `null`).

## Screens & navigation

A lightweight `CaptureScreen` enum (`CAPTURE → REVIEW`, plus `CATALOG` and `DETAIL`) drives a `when`
in `CaptureFlowScreen` — no navigation library.

- **Capture** — live camera, shutter, "N of 3", tracking hint, and a **Catalog** button (top-right).
- **Review** — the just-captured 3 images + full metadata; "Capture another object" / "View catalog".
- **Catalog** — every persisted object from Room (newest first) as cards (first-photo thumbnail,
  date, photo count); survives app restarts. Tap → detail.
- **Detail** — one object's 3 photos + full metadata, reusing the review `ReviewPhoto`/`MetadataPanel`.

## Tracking gate (fixes "metadata always unavailable")

Capture is gated on ARCore `TrackingState.TRACKING`. A shutter press is held pending (with a
"Hold still — establishing tracking…" hint) until the frame is actually tracking, so pose, the
transform matrices, and depth are never read from a non-tracking frame. Previously a capture
committed as soon as the camera image was ready — before ARCore reached TRACKING — which recorded
those fields as `null` on slower-to-track devices (the Nord CE6 symptom). Depth acquisition now also
logs the real failure reason so a device that genuinely lacks Depth API can be told apart from a
transient miss.

---

## Persisted metadata (`ARCoreFrameMetadata`)

Everything reliably available off the exact captured `Frame`. **Unavailable values are stored as
`null`** (e.g. pose while not tracking, depth on a device without Depth support) — never invented.

| Field | Source |
|---|---|
| `frameTimestampNs` | `Frame.getTimestamp()` |
| `capturedAtEpochMs` | wall clock at shutter |
| `trackingState` | `Camera.getTrackingState()` |
| `poseTranslation` / `poseRotationQuaternion` | `Camera.getPose()` |
| `cameraToWorldMatrix` | `Pose.toMatrix()` (column-major) |
| `worldToCameraMatrix` | `Camera.getViewMatrix()` |
| `projectionMatrix` (+ near/far) | `Camera.getProjectionMatrix()` |
| `intrinsicsFocalLength` / `intrinsicsPrincipalPoint` / `intrinsicsImageDimensions` | `Camera.getImageIntrinsics()` |
| `imageWidth` / `imageHeight` | captured camera image |
| `displayRotationDegrees` | rotation to display sensor-landscape image upright (recorded, not baked into pixels) |
| `depthSupported` / `depthAvailable` | config probe / per-frame acquire |
| `depthWidth` / `depthHeight` / `depthRawPath` | `Frame.acquireDepthImage16Bits()` |

---

## Constraints honored

- No ML inference, segmentation, PCA, depth processing, or dimension math during capture.
- No complicated controls / calibration UI; no ARCore/debug values on the **capture** screen.
- Existing workflow outside this feature is preserved (measurement code untouched).
- Exactly 3 photos per object.

> Note: the **review** screen intentionally *does* show full metadata — this was an explicit
> follow-up request so the captured geometry can be inspected on-device. The capture screen stays
> clean.

---

## Known limitations / choices

- **"Full resolution" = the ARCore CPU camera image** (`acquireCameraImage()`), typically 640×480,
  not a separate high-res Camera2 still pipeline (which would redesign the app).
- **`displayRotationDegrees` defaults to 90°** (portrait-locked device, sensor-landscape). The JPEG
  is saved in native sensor orientation to stay consistent with the persisted intrinsics; the
  review screen rotates for display. If a device mounts its sensor differently, adjust the default.
- Past objects **are** browsable via the catalog (Room-backed, survives restarts). Existing
  file-based captures from before Room were **not** migrated (chosen "start fresh").

---

## On-device verification

### Capture + metadata (Samsung SM-G781B, 2026-08-10) — PASS
Installed + launched cleanly, no crash. Captured multiple 3-photo objects; each persisted
`photo_N.jpg` (~50–90 KB, 640×480) + `depth_N.raw` (28,800 B = 160×90×2). Metadata confirmed real:
`trackingState=TRACKING`, populated pose (distinct per photo → genuine multi-viewpoint), intrinsics
(`fx≈485`, `cx≈319`, `cy≈241`, `640×480`), depth `160×90`. Review rendered all 3 images upright with
per-photo metadata. (This predates the Room/catalog/tracking-gate/crash-fix changes below.)

### Room + catalog + fixes — built, install pending
Compiles and assembles (`:app:assembleDebug`, KSP/Room annotation processing OK). On-device
verification of the catalog (survives restart), the tracking gate, and the matrix-view crash fix
was blocked by an intermittent ADB disconnect and still needs a device run:
1. **Crash fix:** capture while not tracking (matrices null) → Review → "Show transform matrices" →
   no crash, shows "unavailable"; with populated matrices it renders the 4×4.
2. **Tracking gate:** shutter waits for TRACKING (hint shows); saved metadata has non-null
   pose/matrices; check the new logcat diagnostics.
3. **Room/catalog:** capture an object, `adb shell am force-stop`, relaunch, open Catalog → object
   still listed; `databases/capture.db` exists and `captures/<id>/` still holds JPEG/RAW files.
4. **Nord CE6:** install, capture, report the new depth/tracking logcat lines to confirm whether
   depth is genuinely unsupported vs. fixable; pose/matrices should now populate via the gate.

> Not yet verified on the documented target **OnePlus Nord 5**. Notable: depth **was** available on
> the SM-G781B (contra `PLAN.md`'s "S20 FE has no Depth API").

---

## Predicted measurement — on-device dimensions from the 3 photos (2026-08-18)

The capture-first path now produces the project's headline result: **Width × Height × Depth in
centimeters** for the captured object, shown as a **Predicted Measurement** section on the 3D
Reconstruction screen (reached from Review / object Detail via *Reconstruct 3D*).

This is **glue over the existing geometry stack**, not new algorithms. `ObjectReconstructor` already
concatenates the 3 photos' masked depth into one ARCore-world point cloud; the new
`reconstruct/MeasurementCalculator` runs that cloud through the **same authoritative `measure/`
pipeline the live path uses** — `OutlierFilter` → `FloorEstimator` (gravity height from robust Y
percentiles) → `FootprintEstimator` (min-area oriented rectangle on X/Z, the doc-mandated non-PCA
horizontal method) → `MeasurementEstimator.assemble` → `ObjectMeasurement` (cm). No PCA is used as the
authoritative value; raw min/max are never the measurement (rules 12–13).

- **New:** `reconstruct/MeasurementCalculator.kt` (+ `PredictedMeasurement` value: cm result, the
  oriented `Obb` for overlay, `viewsUsed/totalViews`, floor source, and a `note` when unmeasurable).
- **Wiring:** `ReconstructionResult` gains a `measurement` field, populated by `ObjectReconstructor`.
- **UI (`reconstruct/ui/ReconstructionScreen.kt`):** a prominent **Predicted Measurement** card
  (`62.7 × 42.0 × 28.4 cm`, W×H×D, confidence · N/3 views · floor source) at the top, plus an **amber
  oriented-box wireframe** drawn over the point cloud (reuses the viewer's existing VP projection).
  The old axis-aligned extents remain in `StatsPanel` as debug (raw vs. oriented comparison).
- **Honesty:** no ARCore floor *plane* is saved with a capture (only the live `FrameExtractor`
  computes one), so `FloorEstimator` runs with `floorPlaneY = null` and falls back to the object's own
  cloud-base percentile — the card labels this `floor: object base`. Confidence is an **uncalibrated
  heuristic** (`0.5·coverage + 0.5·point-density`), documented as such, not a validated probability.
- **Out of scope (follow-ups):** persisting the measurement to Room; running the ML pipeline on the
  Review screen for an auto-measurement before *Reconstruct 3D*.

**Documentation state: Implemented → Built → unit-tested.** `:app:assembleDebug` +
`:app:testDebugUnitTest` green, including `MeasurementCalculatorTest` (a synthetic known-size box —
0.60×0.40×0.30 m, axis-aligned and rotated 35° — recovers W/H/D within a few cm; a sparse cloud
yields no measurement + a reason). **On-device Verified / Accuracy validated: pending** — needs the
Nord 5 connected to reconstruct a real captured object and a human tape-measure comparison.

---

## Diagnostic 1 — depth → RGB reprojection (2026-08-10, OnePlus Nord 5) — PASS

First correspondence test for the saved data, **isolated from** YOLO/SAM/PCA/world-transform (per the
brief). New, self-contained code — no capture/reconstruction change:
- `diag/DepthReprojectionDiagnostic.kt` — for one photo: load JPEG + `depth_N.raw` + intrinsics,
  scale intrinsics to depth res, back-project every valid depth pixel to camera-space XYZ (the exact
  inverse model of `cloud/PointCloudBuilder`), then forward-project that XYZ back into RGB pixels
  with the full-res intrinsics. Reports RGB/ref/depth dims, valid/projected/inside counts, % inside.
- `diag/ui/DepthDiagnosticScreen.kt` — LEFT original · RIGHT reprojected overlay; photo selector,
  overlay vs points-only, adjustable point size; depth-colored dots (near red → far blue). Reached
  from Review and object Detail via "Depth reprojection test".

**Result (fresh capture, box on tiled floor):** `640×480` RGB, `160×90` depth, intrinsics ref
`640×480` (`fx≈454`, `cx≈327.5`, `cy≈240.9`). **14400 valid depth / 99.5 % reproject inside RGB.**
Overlay alignment is correct: the near→far color gradient tracks the real geometry (red ~2.0 m floor
at the bottom → blue ~3.3 m wall/cabinet at the top; box face a distinct nearer patch). Depth genuinely
corresponds to RGB and the resolution scaling + 90° display rotation are all consistent. First stage of
`scanning-ui.md`'s failure list (**depth ↔ RGB**) is verified for this device.

> The reprojected pixel *position* is independent of the depth value (Z cancels in the round-trip), so
> this test validates FOV/scaling/rotation/intrinsics correspondence, not depth *magnitude* accuracy.

**Secondary finding — DEPTH16 confidence bits (this diagnostic surfaced it; now fixed, see below).**
`acquireDepthImage16Bits()` returns Android **DEPTH16** (low 13 bits = mm, top 3 bits = confidence).
On some captures the top bits are **set**: an older on-device `depth_N.raw` read a uniform raw `≈11115`
(> ARCore's ~8 m ceiling → impossible as mm); masking to 13 bits gives a physical `≈2.92 m`.

---

## Depth fixes (2026-08-10) — DEPTH16 decode + first-frame depth gate

Two corrections to the **existing** pipeline (no UI redesign, no ML/PCA/algorithm change beyond the
decode). Both driven by the diagnostic above.

### 1. Correct DEPTH16 decoding
`acquireDepthImage16Bits()` samples are **13-bit mm + 3-bit confidence**. The pipeline previously read
`raw and 0xFFFF`, treating a set confidence bit as **+8192 mm per unit** — an ~8.2 m inflation on
affected frames (a plausible contributor to the earlier "Height ≈ 626 cm" blow-ups). Now decoded via a
single shared helper `depth/Depth16.kt` (`DEPTH_MASK = 0x1FFF`, `millimeters()`, `confidence()`,
`countValid()`), applied consistently in **every** production decode site:
`cloud/PointCloudBuilder`, `reconstruct/ObjectReconstructor`, `ar/FrameExtractor` (debug midMm),
`ar/DepthColorizer`. `depthMm == 0` remains "invalid"; confidence bits are never treated as depth.
The reprojection diagnostic already decoded 13-bit; its report now notes the pipeline is consistent.

### 2. Reject unusable depth at capture (first-frame all-zero)
The first captured frame's depth is often entirely zero. `CaptureFrameExtractor` now decodes + counts
valid samples (`Depth16.countValid`) and, below `MIN_VALID_DEPTH_SAMPLES = 100`, **marks depth
unavailable** (no `depth_N.raw` written, `depthAvailable = false`) instead of saving a dead frame.
`CaptureViewModel` adds a **bounded retry**: while depth is supported but this frame has no usable
depth, it holds the shutter pending up to `MAX_DEPTH_RETRIES = 30` frames (~1 s) so the saved RGB +
depth + pose all come from one good frame; after the budget it commits anyway with depth marked
unavailable (the photo is never blocked). Downstream (`ObjectReconstructor`, the diagnostic) already
skip photos with `depthAvailable = false`. Debug log per capture: raw DEPTH16 value, decoded mm,
confidence bits, and valid-depth count.

**Verification — on-device PASS (Nord 5, 2026-08-10).** Builds (`:app:assembleDebug`) + JVM unit tests
pass. All previous captures cleared (files + Room DB), then a fresh object captured:
- **Confidence bits are real and now handled.** Pulled the 3 saved `depth_N.raw`: `depth_1` had 67
  pixels and `depth_2` had 610 pixels with confidence bits set — raw values up to **16158 mm / 14872 mm**
  (impossible as depth, > ARCore's ~8 m ceiling). Decoded 13-bit they fall to **≤ 8024 / 8184 mm**.
- **Diagnostic (item 6) confirms it live:** Photo 3 report shows `Depth range (13-bit) 2 … 8184 mm`,
  `Confidence bits set 4% · raw max 14872 mm`, `DEPTH16 decode: 13-bit mm masked (pipeline uses Depth16)`,
  100 % of valid depth reprojecting inside RGB — and the box reprojects onto its real RGB location.
- **No unusable depth saved (item 5):** all three `depth_N.raw` (incl. the first photo) have 14400/14400
  valid samples — the capture depth-gate + bounded retry produced a usable first-frame depth rather than
  the old all-zero frame.
- Reconstruction (`PointCloudBuilder` via the 3D view) ran on the corrected metric depth (item 7).

Per-capture debug logging (raw DEPTH16, decoded mm, confidence, valid count) is emitted by
`CaptureFrameExtractor`; a live first-frame *rejection* log wasn't captured this run (the retry yielded a
valid first frame), but the reject path is covered by `MIN_VALID_DEPTH_SAMPLES`.

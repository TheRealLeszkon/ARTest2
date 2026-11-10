# ARTest2 — Overview

## What this project does

**ARTest2** is an Android proof-of-concept that measures the real-world size of a
single household object by pointing a phone's camera at it. Aim at a chair, suitcase,
bottle, microwave, etc., and the app estimates its **width / height / depth** in both
**centimeters and inches**, drawn live on screen as an oriented 3D box with a HUD readout.

It is a **learning PoC, not a product** — the explicit priority is *understanding the
computer-vision pipeline* over squeezing out measurement accuracy.

## How it works (the pipeline)

The app fuses three things every camera frame — the RGB image, an ARCore depth map, and
the device's tracked pose — and runs them through a fixed sequence of stages:

```
ARCore camera stream
   │  per-frame: RGB image, 16-bit depth (mm), camera pose (world), intrinsics
   ▼
Object detection (YOLO11-Seg, one model)      → boxes + class + prototype masks
   ▼
Segmentation mask for the single target object → isolates just that object
   ▼
Masked depth extraction                        → keep depth only where mask == 1
   ▼
Back-projection to 3D (pinhole model inverted) → (X,Y,Z) per pixel, then camera→world
   ▼
Outlier removal                                → drop floor/wall bleed-through
   ▼
Oriented Bounding Box via PCA                  → object's own 3 axes + extents
   ▼
Dimensions in cm + inches                      → smoothed across frames, freeze button
```

The key idea in the last two stages: rather than an axis-aligned box (which inflates when
the object is rotated), it fits an **Oriented Bounding Box** using **PCA** — the covariance
of the 3D point cloud gives the object's own principal axes, and the spread along each axis
becomes width / height / depth.

## Key technical decisions

- **One model, not two** — YOLO11-Seg does detection *and* the segmentation mask in a single
  pass, instead of a YOLO + SAM two-model pipeline (half the on-device memory/latency).
- **ONNX Runtime Mobile** (NNAPI execution provider) for inference — reuses existing `.onnx`
  exports rather than a TFLite re-export detour.
- **ARCore Depth API** for real depth — monocular depth is only a fallback for unsupported
  devices.
- **SceneView + Jetpack Compose** for AR rendering — avoids raw ARCore/OpenGL boilerplate.
- **MVVM, single Gradle module**, with package boundaries per pipeline stage
  (`ar/ detect/ segment/ cloud/ measure/ render/ ui/ util/`).
- **Debugging philosophy:** visual overlays as assertions — if the mask, cloud, or box looks
  wrong on screen, the math is wrong.

## Development approach

Built **milestone by milestone**, each ending in a runnable app that is verified on a
physical device before advancing:

| # | Milestone | What it adds |
|---|-----------|--------------|
| 1 | ARCore foundation | Camera permission, tracking, planes + feature points |
| 2 | Display the depth map | Colorized near-warm/far-cool depth overlay |
| 3 | Object detection | YOLO11-Seg boxes + labels, pick the target object |
| 4 | Segmentation masks | Per-object mask overlay, isolates the target |
| 5 | Masked depth → 3D point cloud | Back-project masked depth to world space |
| 6 | Oriented Bounding Box via PCA | Fit the object's own axes, draw wireframe |
| 7 | Live dimensions | W/H/D in cm + inches, smoothed, with a freeze button |

**Current status:** all 7 milestones are code-complete, build, and are committed. M1–M4 are
verified on-device; M5–M7 build and install but their on-device visuals still need a good
lit-scene capture (an environmental blocker, not a code one). See `docs/PROGRESS.md` for the
living status, `PLAN.md` for the roadmap, and `docs/MILESTONE_N.md` for per-milestone theory
and verification.

## Target device

**OnePlus Nord 5** (model `CPH2805`, Android 16) — ARCore-certified *and* Depth-API-supported.
A physical, ARCore Depth-capable device is required; the emulator will not work. The runtime
truth check is `session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)`.

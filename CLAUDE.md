# ARTest2 — Working Agreement

AR object-dimension measurement PoC.

The current experiment is **multi-view, on-device object measurement using ARCore depth, camera pose, and object segmentation**.

Project status lives in `docs/PROGRESS.md` — read it first each session.

Roadmap lives in `PLAN.md`.

Per-milestone theory and verification criteria live in `docs/MILESTONE_N.md`.

---

# Primary Goal

Transform ARTest2 from a primarily single-view measurement experiment into a **multi-view on-device measurement system**.

The application must measure **one physical household object** sitting on the floor. The user points the phone at the object and slowly moves around it. The application continuously segments the object, acquires depth, back-projects to 3D, transforms into world coordinates, accumulates observations from multiple viewpoints, estimates Width × Height × Depth, stabilizes the measurement, and produces the final result entirely on-device.

The backend is **not involved in measurement**. See `docs/architecture.md` for the server boundary.

```text
Android
    │
    │ W/H/D + confidence + metadata
    ▼
Backend
```

---

# Core Experiment

The central question for this iteration is:

> Can observations of the same object from 3+ different camera viewpoints be transformed into a common ARCore world coordinate system and combined to produce more accurate dimensions?

Do not treat each frame as an independent measurement. This is a **multi-view reconstruction problem**.

The critical go/no-go check (see `docs/development-process.md`):

> Do points captured from three or more different phone viewpoints reconstruct the same physical object consistently in ARCore world coordinates?

If no, stop and debug alignment/tracking/pose before touching the bounding-box algorithm.

---

# Reference Docs

@docs/architecture.md
@docs/measurement-pipeline.md
@docs/scanning-ui.md
@docs/development-process.md
@docs/verification.md
@docs/rules.md

- **architecture.md** — existing components to reuse, object scope, suggested new classes, server boundary
- **measurement-pipeline.md** — segmentation → depth → world points → accumulation → outlier rejection → height/footprint math
- **scanning-ui.md** — scan state machine, coverage tracking, stability, UI, debugging philosophy, performance
- **development-process.md** — milestones M1–M10, critical experiment, accuracy evaluation, definition of done
- **verification.md** — on-device subagent workflow, ADB commands, documentation states
- **rules.md** — the hard rules, read these before making changes

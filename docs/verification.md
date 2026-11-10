# On-Device Verification

On-device verification runs in a parallel Sonnet subagent.

We build **milestone by milestone**, and every milestone must be verified on the physical device (OnePlus Nord 5). To keep development moving, do not block the implementation on physical verification.

**Rule:** the moment a milestone's code is implemented and builds, hand the on-device install + verification to a subagent running the **`sonnet`** model (`Agent` tool, `model: "sonnet"`, `run_in_background: true`), and immediately continue with the next milestone while it runs.

When the subagent reports back, fold the result into `docs/PROGRESS.md` and the relevant `docs/MILESTONE_N.md`.

The subagent must report:

* screenshot path
* clear PASS/FAIL
* observed HUD values
* observed overlays
* relevant logcat output
* any limitation encountered

It cannot physically move the phone. Therefore, checks requiring physical motion — walking around the object, changing viewpoints, panning for feature points, physically positioning the device — must be flagged for the human rather than automatically marked as failures.

---

## Physical Device

```text
OnePlus Nord 5
Model: CPH2805
Android 16
```

The device must support ARCore Depth API. The emulator cannot validate the depth pipeline.

---

## ADB Verification

```bash
ADB=$HOME/Library/Android/sdk/platform-tools/adb
```

Install:
```bash
$ADB install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch:
```bash
$ADB shell monkey -p com.example.arsurvey -c android.intent.category.LAUNCHER 1
```

Screenshot:
```bash
$ADB exec-out screencap -p > out.png
```

Logs:
```bash
$ADB logcat
```

---

## Flaky ADB Connection

The Nord 5 ADB connection is flaky and can drop during transfers. The verification subagent must use a wait-for-device retry loop. Use the existing `scratchpad/verify_m3.sh` as a template.

The verification procedure should:

1. Poll `adb get-state`.
2. Wait for the device to become available.
3. Install the APK.
4. Launch the application.
5. Capture screenshots.
6. Inspect logcat.
7. Retry when the device connection drops.

Do not declare a build or milestone broken merely because the ADB connection temporarily disappeared.

---

## Verification Criteria

The exact pass criteria for each milestone live in `docs/MILESTONE_N.md`. Read that milestone's verification section before performing verification.

When verification reports a failure, distinguish between: code failure, runtime failure, device/environment failure, physical interaction requirement.

Do not incorrectly mark a milestone as failed because the subagent cannot physically move the phone.

---

## Documentation

After each milestone:

1. Update `docs/PROGRESS.md`.
2. Update the relevant `docs/MILESTONE_N.md`.
3. Record what was actually verified.
4. Record what remains unverified.
5. Do not claim hardware verification that did not occur.

The documentation must distinguish these states:

```text
Implemented
Built
Installed
Verified on device
Physically validated
Accuracy validated
```

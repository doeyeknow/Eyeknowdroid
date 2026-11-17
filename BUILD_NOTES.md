# eyeKnow native app — build notes

Native Android shell for eyeknow.app + a native Camera2 iris camera.
Built 2026-11-15 on Adam's order. Intended home: github.com/doeyeknow/Eyeknowdroid
(public, "Native app", branch main). The repo held only an initial commit
(README + LICENSE) when this tree was built; this project was pushed there
on Adam's say-so — source + scripts + docs only, never signing material.

## Identity

- applicationId: `app.eyeknow.android` — matched to Adam's
  Eyeknow-Android-Starter.zip in the Eyeknow repo (its build.gradle.kts).
  The starter was never compiled, so there is no installed base to
  conflict with, but the appId is his and stays.
- minSdk 26 / targetSdk 34 / compileSdk 34 (matches the starter).
- Version 1.1 / versionCode 2 (auto-capture; v1.0 was code 1).
- Signing: `eyeknow-release.keystore` (alias `eyeknow`, RSA 2048),
  credentials in `keystore-credentials.txt` (0600). NEVER commit the
  keystore, the credentials file, build outputs, or the APK — the
  repo is public. Every future update signs with this key.
- Certificate SHA-256 (v1.0 and v1.1, same key): f81636efe59d728bee505f323fd34e428bead2bdaac1f290b1ff6721a65f674b

## Build (no Gradle — impossible in the build VM)

The runtime garbles JVM IPv4 loopback traffic, which kills the Gradle
daemon handshake. The APK is assembled with the SDK tools directly,
the same shape as the Palm shell (~/workspace/palm-native) and the
Control Launcher: aapt2 -> javac -> d8 -> zipalign -> apksigner.

    ./build-apk.sh          # needs env.sh (JAVA_HOME/ANDROID_HOME)
    # output: ./eyeknow.apk (signed, verified by the script)

No third-party dependencies at all: WebView + Camera2 + framework
APIs only. Pure Java (the starter was Kotlin; the manual pipeline
is javac, so the shell was rewritten in Java).

## Shell

MainActivity loads https://www.eyeknow.app — the apex 308-redirects
there (verified live 2026-11-15), so the shell uses the final host
and trusts both hosts. Navigation is locked to eyeknow.app /
www.eyeknow.app; every other link opens in the browser. Site
updates need no reinstall.

The upload fallback and the web camera keep working inside the
shell: file inputs get a chooser (image/*), and getUserMedia
permission requests are granted once the runtime camera permission
holds. Against the CURRENT live site (which predates the bridge
code — see below) those two paths are how capture works.

## The bridge (Adam's contract, honored exactly)

Adam's lib/nativeCapture.ts (Eyeknow repo, Nov 14-15) defines the
contract. The site calls `window.EyeknowCapture.postMessage` with
`{"type":"captureEye","requestId","eye"}` and reads replies from
`bridge.onmessage` as JSON strings: captured (with
`capture.display` = "data:image/jpeg;base64,...", `stackedFrames`,
`source`), cancelled, or error. It can also send
`cancelEyeCapture` (its 180s timeout).

Why a JS shim: the site assigns `bridge.onmessage`, and a Java
object injected via addJavascriptInterface cannot hold a JS
function property. So the native side injects a plain JS object as
window.EyeknowCapture whose postMessage forwards into a
@JavascriptInterface object exposed as `EyeknowNative`, and
delivers replies via evaluateJavascript calling the shim's
onmessage with `{data: "<reply JSON string>"}`. The shim is
re-injected on every page finish. CaptureBridge.java holds the
shim + delivery; BridgeMessages.java holds the wire format (pure
Java, harness-proven).

Submission path decision (evidence, 2026-11-15): the bridge is
the ONLY submission path. Analysis is client-side (autoRead in
the browser); there is no server-side analysis endpoint — the
/api/scans/* routes are CRUD/claim/discount for readings that
already exist. A "direct upload" would have nothing to upload to
that produces a reading, so none was built. The native camera
hands the cropped JPEG data URL to the site's existing runRead
pipeline, exactly as the web capture does.

Single still: source "still", stackedFrames 0 (the capture.ts
convention: 0 = single frame), no "analysis" image — the reader
analyses the display image itself. Multi-frame stacking (the web
flow's aligned average) is future work; nothing here invents
frames that were not captured.

## What waits on Adam's pending web fix

The live site (deploy dpl_HS5W..., commit 9a4b9d9) PREDATES the
bridge code: main is broken by the duplicate nativeCapture.ts
(Lib/ + lib/, byte-identical, line-66 'bridge possibly undefined'
error failing deploys). Adam's call: delete the Lib/ duplicate,
guard line 66 in lib/, redeploy. Until that deploy lands, the
bridge in this APK is inert — the site uses its getUserMedia /
upload fallback inside the shell, which works. The native camera
activates automatically the moment the fixed site deploys; no
app update is needed.

## Camera (CameraActivity, Camera2)

- Selection (CameraChoice, pure, harness-proven): enumerate all
  cameras INCLUDING physical ids behind logical multi-cameras
  (a hidden macro lens lives there). Among back cameras prefer
  the closest-focus lens (largest LENS_INFO_MINIMUM_FOCUS_DISTANCE).
  The "Macro" label is earned only by a distinct lens focusing
  >= 2 diopters closer than the main lens; otherwise the label is
  "Main" with close focus, and the UI never claims macro the
  device did not provide. Front camera is a last resort, labelled
  as such. What the Galaxy A16 exposes is unprovable off-device —
  Adam's test.
- Torch: on by default for the back camera (even illumination,
  matching the web flow), toggleable, state shown. Off/hidden
  when the camera has no flash.
- Autofocus: continuous picture AF + tap-to-focus (AF/AE regions
  + trigger, focus ring shown, back to continuous after 1.2s).
  Focus state is shown honestly (Focusing… / Focused / Not
  focused). Focus/zoom math is FocusMath (pure, harness-proven).
- Zoom: pinch + slider, SCALER_CROP_REGION, level shown (1.0×…).
- Still: largest JPEG size, JPEG_ORIENTATION upright, quality 95
  at capture; the crop step re-encodes at quality 92 capped at
  2560 on the long side — the web capture's own caps.
- Guide overlay: dimmed surround + clear circle + ring + center
  tick; the preview is center-cropped without stretching so the
  guide and tap-to-focus stay true.
- Memory: stills decode subsampled to the 2560 cap before any
  bitmap work (CropMath.sampleSize) — a 50MP still never inflates.
- Handoff: photos travel as FILE PATHS between activities (a JPEG
  data URL would blow the Binder transaction limit). Raw + cropped
  files live in the private cache dir and are deleted after use.

## Auto-capture (v1.1, Adam's order 2026-11-15)

The camera fires the still itself when it sees an iris in focus.
The decision logic is a port of the web reader's own code
(lib/autoread.ts + lib/capture.ts in the Eyeknow repo) to pure
Java — the same thresholds and semantics where they transfer,
documented where they do not. Nothing here invents a different
detection standard.

- Pipeline: a third ImageReader (YUV_420_888, smallest size in
  the 320-640-wide band, else the smallest offered) feeds a
  dedicated analysis thread. Frames are throttled to ~5 fps
  (latest-only), converted by YuvFrame to a 320-wide gray +
  saturation frame (IrisFrame), and analyzed by IrisDetector.
  Analysis never blocks the preview and any exception in it
  stays local — the manual shutter always remains.
- Pupil (autoread §1-2): 180-wide working grid, thresholds
  [18, 26, 34, 44, 56], blob rules (area 0.0008-0.06 of frame,
  center 0.15-0.85, aspect <= 1.7, fill >= 0.42, radius
  0.018-0.16 of min grid dim), grow step (coreMean+34 capped at
  88, growth capped at 2x radius), 36-ray radial-gradient
  refinement (jump > 26, >= 30% rays).
- Iris edge (autoread §3-5): 72 rays, score = sat*150 +
  (255-lum)*0.45, baseline at 1.15-1.65x pupil radius, edge after
  3 consecutive samples below baseline*ratio (0.45, retry 0.62
  if validFrac < 0.45), gradient fallback if validFrac < 0.3.
- Hard gates (autoread §6): pupil found AND validFrac >= 0.30
  AND iris radius >= 0.07 * minDim — the floor Adam set
  2026-11-08. Well-framed means validFrac >= 0.55, the web's
  "fitted by estimate" line.
- Sharpness (autoread §7): mean |luminance step| between
  samples 1.17% of width apart inside the annulus
  (0.3 < r/R <= 1.0), floor 6.0 — the web's "soft or blurry"
  floor, in the same luminance units.
- Stability window (NEW — the web has no equivalent): the gate
  (AutoCaptureGate) requires 4 consecutive passing feeds
  (~0.8 s at 5 fps) with pupil-center drift <= 3% of frame
  (the capture.ts frame-stacking movement bound) and iris-radius
  drift <= 10% (new). Any failing feed or excess drift restarts
  the window. A passing feed also requires the camera AF state
  to be focused — UNLESS the device reports no AF state at all
  (fixed-focus lens), in which case sharpness alone decides.
- Adaptations for the ~320px frame (documented in
  IrisDetector.java): ray step 1px (web: 3px on a larger image),
  edge back-off 2 steps (web: 6px), gradient fallback 2px apart
  (web: 3px), no denoise pass, no pupil-shape classification.
- UI: the status line shows the gate state honestly — looking
  for the iris / iris found / focusing (with a too-dark hint
  when mean annulus luminance < 30) / hold steady with a 1-4
  count / captured. An "Auto: on/off" button sits beside the
  Capture button, default ON, persisted in SharedPreferences.
  The manual Capture button ALWAYS stays available. Firing goes
  through the exact manual path: still -> crop step -> bridge.
- Failure honesty: no iris found means the status says so and
  nothing fires; a rejected frame never captures; a failed
  capture resets the gate so auto-capture can fire again.
  If the camera offers no YUV size, or the session cannot run
  preview + YUV + JPEG together (the session config is retried
  once without the analysis target), auto-capture disables
  itself with an honest message and manual capture is unaffected.
- No new permissions. No network, no storage of analysis frames.

## Crop (CropActivity + CropOverlayView)

Square crop only: drag to move, drag a corner to resize, slider
resizes too. The site defines NO crop contract (capture.ts hands
full frames to autoRead), so the square crop is the native UX
Adam ordered; autoRead re-detects the iris in the cropped image.
Crop math is CropMath (pure, harness-proven), including the
view->image letterbox mapping and the minimum-size rule (15% of
the short side).

## Permissions (3)

INTERNET, CAMERA, FLASHLIGHT. Camera features are required=false
so a camera-less device installs and gets an honest error.
Runtime camera permission is requested on first capture (bridge
or getUserMedia); denial produces an honest error reply, never a
silent drop.

## Verification

- Harness: harness/Harness.java, 101/101 checks on the exact
  shipping pure-Java classes (bridge parse/build, camera
  selection incl. the macro honesty rule, crop math incl.
  view<->image round-trip, focus/zoom math, YUV conversion
  incl. strides + downsampling, detector on synthetic eyes
  incl. sharp/soft/dark/eyelid/no-eye/off-center/tiny cases,
  gate state machine incl. drift resets and AF rules).
  Run: javac the eight pure sources + Harness.java, java Harness.
  Calibration (printed by the harness): sharp synthetic eye
  sharpness 20.2, defocused 5.7 — clean separation around the
  web's floor of 6. The threshold was NOT tuned to the
  synthetic; the synthetic's fiber texture and defocus kernel
  were made realistic instead.
- APK v1.1: 53,747 bytes, sha256
  3e9c74c9302db89bcb95316f5195f7239ab7d764cc1bed830cf58d52c3856eca;
  apksigner verify passes (v2+v3, 1 signer, cert fingerprint
  matches v1.0); badging confirms appId, versionCode 2,
  versionName 1.1, the same 3 permissions, launchable activity.
- NOT verifiable here (Adam's Galaxy A16 is the first real test):
  camera open on the A16, which lens the selector picks and what
  it is labelled, torch behavior, tap-to-focus feel, zoom feel,
  JPEG orientation uprightness, the crop step in hand, the bridge
  handshake against the fixed site deploy, and the file chooser /
  getUserMedia fallback against the current live site. For v1.1
  additionally: detection on a real eye, focus accuracy, false
  triggers, and analysis performance on the A16.

## Files

    AndroidManifest.xml
    build-apk.sh, env.sh
    res/drawable/ic_launcher.png
    src/app/eyeknow/android/
      MainActivity.java       WebView shell + bridge host + chooser
      CaptureBridge.java      JS shim + @JavascriptInterface + delivery
      BridgeMessages.java     wire format (pure)
      CaptureSession.java     one-in-flight guard + cancel
      CameraActivity.java     Camera2 camera + auto-capture wiring
      CameraChoice.java       lens selection + honesty label (pure)
      FocusMath.java          zoom crop + tap-focus math (pure)
      IrisFrame.java          analysis frame: gray + saturation (pure)
      YuvFrame.java           YUV_420_888 -> IrisFrame (pure)
      IrisDetector.java       pupil/iris/sharpness, web port (pure)
      AutoCaptureGate.java    stability window + fire latch (pure)
      GuideOverlay.java       eye guide + focus ring
      CropActivity.java       crop step
      CropOverlayView.java    square crop overlay
      CropMath.java           crop + sizing math (pure)
      JpegUtil.java           decode/crop/encode/data URL
    harness/Harness.java

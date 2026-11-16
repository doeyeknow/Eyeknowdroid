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
- Version 1.0 / versionCode 1 (first build).
- Signing: `eyeknow-release.keystore` (alias `eyeknow`, RSA 2048),
  credentials in `keystore-credentials.txt` (0600). NEVER commit the
  keystore, the credentials file, build outputs, or the APK — the
  repo is public. Every future update signs with this key.
- Certificate SHA-256 (v1.0): f81636efe59d728bee505f323fd34e428bead2bdaac1f290b1ff6721a65f674b

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

- Harness: harness/Harness.java, 58/58 checks on the exact
  shipping pure-Java classes (bridge parse/build, camera
  selection incl. the macro honesty rule, crop math incl.
  view<->image round-trip, focus/zoom math).
  Run: javac the four pure sources + Harness.java, java Harness.
- APK: apksigner verify passes; badging confirms appId, versions,
  permissions, launchable activity; dex contains all 12 classes.
- NOT verifiable here (Adam's Galaxy A16 is the first real test):
  camera open on the A16, which lens the selector picks and what
  it is labelled, torch behavior, tap-to-focus feel, zoom feel,
  JPEG orientation uprightness, the crop step in hand, the bridge
  handshake against the fixed site deploy, and the file chooser /
  getUserMedia fallback against the current live site.

## Files

    AndroidManifest.xml
    build-apk.sh, env.sh
    res/drawable/ic_launcher.png
    src/app/eyeknow/android/
      MainActivity.java       WebView shell + bridge host + chooser
      CaptureBridge.java      JS shim + @JavascriptInterface + delivery
      BridgeMessages.java     wire format (pure)
      CaptureSession.java     one-in-flight guard + cancel
      CameraActivity.java     Camera2 camera
      CameraChoice.java       lens selection + honesty label (pure)
      FocusMath.java          zoom crop + tap-focus math (pure)
      GuideOverlay.java       eye guide + focus ring
      CropActivity.java       crop step
      CropOverlayView.java    square crop overlay
      CropMath.java           crop + sizing math (pure)
      JpegUtil.java           decode/crop/encode/data URL
    harness/Harness.java

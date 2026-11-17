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
- Version 1.5 / versionCode 6 (stamped APK download; v1.4 was
  code 5, v1.3 code 4, v1.2 code 3, v1.1 code 2, v1.0 code 1).
- Signing: `eyeknow-release.keystore` (alias `eyeknow`, RSA 2048),
  credentials in `keystore-credentials.txt` (0600). NEVER commit the
  keystore, the credentials file, build outputs, or the APK — the
  repo is public. Every future update signs with this key.
- Certificate SHA-256 (v1.0, v1.1, v1.2, same key): f81636efe59d728bee505f323fd34e428bead2bdaac1f290b1ff6721a65f674b

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
  The manual Capture button ALWAYS stays available. In v1.1
  firing went through the exact manual path: still -> crop step
  -> bridge. v1.2 changed the fired path (see below); the
  manual path is unchanged.
- Failure honesty: no iris found means the status says so and
  nothing fires; a rejected frame never captures; a failed
  capture resets the gate so auto-capture can fire again.
  If the camera offers no YUV size, or the session cannot run
  preview + YUV + JPEG together (the session config is retried
  once without the analysis target), auto-capture disables
  itself with an honest message and manual capture is unaffected.
- No new permissions. No network, no storage of analysis frames.

## Auto-crop + auto-submit (v1.2, Adam's order 2026-11-15)

Adam reported v1.1 "took an image but it doesnt autocrop or
submit it" and confirmed it reached the crop screen with the
photo. v1.2 closes that gap on the auto path only: a gate-fired
still is cropped to the detected iris bounds and submitted
through the existing bridge path, with no crop screen. The
manual shutter still goes to the manual crop step exactly as
before.

- BUG FIXED (v1.1, found while building v1.2): the analysis
  surface was added to the capture session's outputs but never
  to any request's targets — and in Camera2 a surface only
  receives frames when a request targets it. So analyzeFrame
  never ran and the gate could never fire in v1.1; every v1.1
  photo that reached the crop screen came from the manual
  shutter. v1.2 adds the analysis surface to the repeating
  preview request. The no-analysis retry path builds a fresh
  request builder with no analysis reader, so no stale target
  survives it.
- Fire state: when the gate fires, CameraActivity stashes the
  firing detection (pupil-center fractions, iris radius in
  analysis-frame pixels, analysis frame size) and the
  JPEG_ORIENTATION actually used for the still. captureStill
  owns the auto/manual flag and sets it only when the capture
  actually proceeds, so a gate fire racing a manual capture
  cannot misroute the manual photo.
- Crop rule (AutoCrop, pure Java): a SQUARE centered on the
  detected pupil center, half-side = iris radius x 1.3 — 30%
  of breathing room on every side, because the web reader
  re-detects the iris itself and the crop must frame it
  generously, never tight. Clamped inside the JPEG with
  CropMath's rules (minimum 15% of the short side).
- Coordinate mapping (the hard part): the detector works in
  SENSOR orientation (YUV frames are never rotated); the
  stored JPEG is the sensor frame rotated clockwise by
  JPEG_ORIENTATION. The center fractions rotate by that angle
  (all four rotations handled, direction pinned by harness
  tests); the radius scales by the JPEG-to-analysis size
  ratio along each sensor axis, taking the larger scale so
  the iris is covered even when the streams' aspect ratios
  differ. Zoom cancels out: the still and the analysis frames
  share the same SCALER_CROP_REGION, so both cover the same
  field of view and fractions map directly.
- Safety: the mapping refuses (null) on any insane input, on a
  crop below the minimum size, and when the stored JPEG's
  dimensions disagree with the rotation (e.g. an EXIF-only
  HAL that stored sensor-oriented pixels — then the rotation
  assumption is false and no mapping is trusted). Any refusal
  — or any crop-encode failure — falls back to the manual
  crop screen with the raw photo intact and an honest status
  line ("Auto-crop could not frame this photo"). Never a
  guessed crop, never a silent dead end.
- No re-fire loop: the gate latches on fire until reset
  (retake, toggle, capture failure, or returning to the
  camera), and a failed auto-submit requires a fresh
  stability window before firing again. A successful
  auto-submit finishes the activity with the cropped path,
  exactly like a manual confirm — MainActivity and the bridge
  reply are untouched.
- Feedback: "Captured." -> "Capturing… hold still." ->
  "Preparing your photo…" -> "Submitting…", then the web
  side takes over. No new permissions.

## Rotate + higher-resolution crops (v1.3, Adam's orders 2026-11-16)

Rotate after capture: the crop screen has a Rotate button
beside Use this photo / Retake / Cancel. Each tap rotates the
displayed photo 90 degrees clockwise; four taps return to the
original. The preview updates on each tap. The rotation state
is per-photo (a fresh activity instance per capture, starting
at 0) and never persists across photos.

The crop square is defined in DISPLAYED space. On confirm,
CropActivity maps the displayed square back through the
inverse rotation into the JPEG's pixel space
(CropMath.unrotateRect, pure, harness-pinned for all four
rotations including off-center and edge rects and non-square
sources), cuts the crop from the source, and rotates the
cropped square by the same amount inside cropToFile — so the
written JPEG matches exactly what the preview showed. The
pixel-level harness leg proves the direction: a marked
synthetic image rotated with independent reference rotators
holds the source rect's pixels inside the mapped rect, corners
pinned individually. Rotating re-frames the square to the
default centered square in the new orientation (rotate first,
then frame the iris); drag/resize/slider keep working in every
rotation because the overlay always works in view space and
the mapping goes through the same rotation math.

The auto path is unchanged: it never reaches the crop screen,
always passes rotation 0, and the rotation-0 mapping is the
identity (harness-pinned). The auto-crop FALLBACK (manual crop
screen with the honest "could not frame" status) gets the
Rotate button, since it is the same CropActivity.

Higher resolution: capture ALREADY requests the largest JPEG
size the camera reports (pickStillSize = max area) — the
limiter was the crop output, which subsample-decoded to a
2560 long-side cap. v1.3 region-decodes instead: cropToFile
uses BitmapRegionDecoder to decode ONLY the crop rect at full
source resolution (a 50MP still never inflates into a full
bitmap — grep-provable: the only decodeFile calls left are
dimensions() with inJustDecodeBounds and the subsampled
preview decode), then downscales the cropped square ONLY when
it exceeds the new 4096 long-side cap (LONG_SIDE_CAP), quality
still 92; anything smaller is written at native crop
resolution. The preview decode stays subsampled to the
separate 2560 preview cap (PREVIEW_LONG_SIDE_CAP) — display
never needs full res and must stay fast. The auto path shares
cropToFile, so it benefits automatically with identical rects
and fallback rules. The web reader caps its ANALYSIS canvas at
2000px, but the stored/displayed photo is the submitted image
at full size — so the bigger crop sharpens the photo the user
sees and keeps, and slightly improves the analysis input,
without changing the analysis resolution. The bridge still
carries the photo as a base64 data URL; output stays in the
low single-digit MB range at quality 92.

Caveats: BitmapRegionDecoder itself is Android-only and cannot
run in the JVM harness — the region path is proven by
construction (same clamped rect the subsample path used,
decoder returns the requested region, defensive trim if a
decoder ever returns more) and needs the on-device pass:
submitted photo dimensions larger/sharper than 1.2, no
OOM/slowness on a full-res capture. Rotate direction on real
hardware is validated by the confirm-matches-preview check.

## Auto-crop verification + sequencing + diagnostics + deep links (v1.4, Adam's reports 2026-11-16)

Adam (04:24): "The auto crop isnt working very well." His
submitted scan photo (right eye, rear macro camera) shows the
pupil cut off at the top and left, the bottom ~35% eyelid and
lashes — the crop center was displaced down-right of the true
pupil, mostly vertically. The photo is kept as the harness
fixture harness/fixtures/adam-autocrop-20261116.jpg; the
verification below MUST reject it.

Code review found the rotation mapping internally consistent
(working-frame fractions + pixels + dims, zoom applied to both
builders so it cancels, correct front/back JPEG_ORIENTATION
formulas, the macro lens a standalone camera id) — so the
mapping was NOT "fixed" on a guess. The rotation-direction
question stays open, and this version makes a wrong mapping
harmless while the diagnostics reveal the pattern. Two real
faults were found and fixed:

1. STATUS SEQUENCING. The FIRED branch set "Captured." when the
   GATE fired, before the still was requested — the user
   relaxed and moved, and at macro distance a small move
   shifts the frame a lot. The sequence is now factored into
   CaptureStatus (pure, harness-pinned): FIRING and CAPTURING
   both say "Hold still — capturing…", SAVED says "Photo saved
   — preparing…", VERIFYING says "Checking the framing…",
   SUBMITTING says "Submitting…". No status before SAVED may
   claim a capture. The manual shutter shares the CAPTURING
   text (it already said "hold still" — the flaw was
   auto-only) and lands on the crop screen's own status once
   the photo is saved.

2. NO VERIFICATION. tryAutoCrop cut the crop and submitted it
   without ever checking the framing. Now, after cutting and
   before submitting, the cut crop is decoded downscaled
   (JpegUtil.frameForVerify -> RgbFrame, the same gray/sat
   math as the YUV path) and the detector re-runs on it — on
   the camera background thread, never the UI thread. A
   verification failure or error deletes the rejected crop,
   keeps the raw photo intact, and falls back to the manual
   crop screen with an honest reason. The manual path is
   untouched, and the fallback screen keeps the Rotate button.

   The verification rule is the CENTER TRACE, not the pupil
   position — a deliberate deviation from the original brief,
   grounded in probes on Adam's real photo. The pupil blob
   cannot be trusted on this eye: on the fixture it lands on a
   dark iris patch (0.176, 0.399) instead of the pupil
   (~0.37, 0.23) — the flash reflection plus the dark-brown
   iris leave no clean luminance blob — and on re-centered
   versions of the SAME photo it alternates no-pupil /
   correct / wrong-blob across scales. IrisDetector.
   traceFromCenter() traces the iris edge from the exact
   frame center with the SAME ray machinery, scoring, ratios,
   and fallback chain as detect() (seed radius = the
   detector's own no-pupil estimate, minDim x 0.085 — no new
   constant, no second standard). CropVerify passes only
   when the trace is solid (validFrac >= 0.55, the gate's own
   well-framed line, reused) AND the iris radius sits in
   [0.18, 0.50] of the crop side (the crop rule aims at
   0.385). Measured: fixture validFrac 0.36 (rejected),
   re-centered versions 0.97 / 0.82 / 0.75 (accepted),
   centered synthetics 1.00, a 0.32-displaced synthetic 0.42
   (rejected), too-tight 0.31 (rejected), too-loose rejected
   by the radius band, flat frame 0.00 (rejected). The radius
   SPREAD is recorded in diagnostics but deliberately NOT
   gated: on real photos eyelid coverage and an iris already
   cut in the source inflate it past the mis-framed photo's
   own value (re-centered versions measure 1.05–1.25 vs the
   fixture's 0.60), so gating on it would false-reject good
   crops. Sharpness is not re-checked either — the gate
   already required it at fire time; verification polices
   FRAMING only. IrisDetector.Trace now also carries the
   spread ((p90-p10)/median of valid ray radii, 1.0 when
   under 12 valid rays); detect() behavior is unchanged
   (the 334 prior harness checks still pass).

   Diagnostics: each auto attempt records one CropDiagnostics
   record — camera id + label, analysis size, still size,
   JPEG_ORIENTATION, firing fractions + iris radius + frame
   dims, computed rect, JPEG dims, outcome, verification
   result (reason + validFrac + irisFrac + spread + wall
   time). Numbers only, no photo bytes, last attempt only,
   app-private SharedPreferences, never transmitted. The
   record's encoding and plain-words summaries are pure Java
   and harness-pinned. Surfaces: (a) the manual crop screen
   shows a compact "Why" line under the status when an auto
   attempt fell back (e.g. "The auto-crop check found the
   iris cut off or off-center in the crop (only 36% of the
   edge traced) — frame the iris yourself."); (b) the camera
   screen shows a small "Last auto-crop check: …" line under
   the status, hidden until the first auto attempt, refreshed
   on resume. Verification wall time on the JVM probe was
   ~110ms for a full detect on a 320x320 frame; the center
   trace is a fraction of that — the on-device time is
   recorded in the diagnostics and named in the checklist.

Deep links (Adam 04:43: "The eyeknow app isnt getting the
referral cookie"). The web app stores ?ref= in the cookie jar
of whatever loaded the link; the native app had no
VIEW/BROWSABLE intent filter and MainActivity always loaded
HOME_URL, so affiliate links opened in Chrome and the
referral never reached the app's separate WebView jar. v1.4
adds a second intent-filter on MainActivity (VIEW + DEFAULT
+ BROWSABLE, autoVerify, https, hosts eyeknow.app and
www.eyeknow.app; the LAUNCHER filter is untouched) and
MainActivity loads the intent's FULL URL — query intact,
never stripped, rebuilt, or re-encoded — when it passes the
trust rule, HOME_URL otherwise. The trust rule (https +
exactly those two hosts + port -1/443) is factored into
TrustedSite (pure Java, harness-pinned) and shared by the
deep-link entry point and the WebView's navigation guard, so
the two can never drift. launchMode singleTask keeps exactly
one MainActivity: a link tap while the app runs delivers
onNewIntent to the existing instance (a plain launcher
re-tap, with no data, leaves the current page alone). No new
permissions. The assetlinks verification file is a web-repo
matter, handled separately — with it live, taps open straight
in the app; without it, the system shows a chooser. The
app-side behavior is correct either way.

Caveats: frameForVerify's Bitmap decode is Android-only (the
conversion it feeds, RgbFrame, is harness-proven, and the
subsample pattern is decodeCapped's). The rotation-mapping
direction is still unproven on hardware — verification makes
it harmless, diagnostics make it visible. Deep-link
verification (autoVerify) completes at install time against
the live assetlinks file; if a tap still shows a chooser,
that is the platform verification state, not the app.

## Stamped APK download (v1.5, Adam's order 2026-11-16)

When someone downloads the app through a referral link, the
download endpoint stamps the referral code into the APK they
receive, and the app reads it on its first launch — so
checkout credits the affiliate even when the link never
opened in the app (the WebView's cookie jar is separate from
the browser's, and no app can read another app's cookies).

Mechanism (VasDolly-style channel tag, proven before it was
built): the stamp is one ID-value pair appended to the APK
Signing Block. The v2 spec protects sections 1, 3 and 4 — the
signing block is outside the signed digest — and unknown IDs
must be ignored, so the stamper only inserts the pair, updates
the block's two size fields and the EOCD central-directory
offset. apksigner still verifies v2 AND v3 on the stamped
file (proven on the real APK, including a 30-char code and a
double stamp), zipalign still passes, badging is unchanged.

The ONE shared format (both implementations, pinned by test
vectors run both ways — TS stamper output read by the Java
reader, Java-written blocks read by the TS reader):
pair ID 0x45594b4e, value ASCII "EYEKNOW-REF:" + <code>, code
validated by the web rule exactly (lib/affiliates-client.ts
isValidRefValue). First valid stamp wins; anything malformed
fails safe to no stamp.

App side (ApkStamp.java, pure Java): on the first PLAIN
launch only (no trusted deep link — a tapped link always
wins), MainActivity reads its own APK file via
getPackageCodePath and, when a valid stamp is present, loads
HOME_URL + "?ref=" + code ONCE, so the page's existing
ReferralCapture stores it (cookie + localStorage + one
honest click: a real install from the link). A persisted flag
(eyeknow_shell / ref_stamp_checked, set even when no stamp is
found) makes the check once-ever: reinstalls start fresh,
updates never re-stamp. No stamp, invalid stamp, or
already-checked behaves exactly as before. Reading runs
synchronously in onCreate — the file is ~66 KiB.

Web side (Eyeknow repo, commit prepared, NOT pushed):
GET /api/app/download?ref=CODE serves the bundled APK
(app-binary/eyeknow.apk) with the stamp injected when the
code is valid (same trim + lowercase + rule as
ReferralCapture), byte-identical unstamped bytes otherwise,
and pristine bytes on any stamping failure. No page UI — the
endpoint + URL pattern only. The stamper is lib/apkStamp.ts
(Buffer only, no dependencies); the bundle is wired via
outputFileTracingIncludes in next.config.mjs, following the
book-download precedent.

STANDING RULE: app-binary/eyeknow.apk in the web repo MUST be
refreshed on every native release — the endpoint serves
whatever file is bundled. Bundle the PRISTINE build output
only, never an already-stamped file.

Harness: 497/497 (452 prior + 45 stamp checks: rule vectors,
synthetic blocks incl. all failure shapes, real stamped APK
fixtures incl. the TypeScript stamper's output over the v1.5
bytes). The Android-only parts (reading the installed APK
file, the prefs flag) are proven on-device.

Caveats: the stamp only covers downloads through the
endpoint. Someone who already has the app, or installs from
an unstamped copy, is covered by the deep links (v1.4) and —
if built — account-synced referral recovery, not by this.

## Viewport fit (v1.6, Adam's report 2026-11-16)

Adam: the app is getting the referral code, "but the page is
zoomed in for some reason." Cause: MainActivity never set
useWideViewPort or loadWithOverviewMode, so the WebView
ignored the site's viewport meta (width=device-width,
initial-scale=1), laid the page out at the default wide
width, and showed it with no shrink-to-fit — the classic
zoomed-in WebView. Fix: setUseWideViewPort(true) +
setLoadWithOverviewMode(true), two lines, no other behavior
change. The site sets user-scalable=false itself, so pinch
zoom stays off, matching Chrome and Adam's 2026-11-05
no-zoom call. The settings are Android-only and cannot run
in the JVM harness; the harness still runs green as a
regression check, and the fit is proven on-device. STANDING
RULE reminder: the web endpoint still bundles the v1.5 APK
until app-binary/eyeknow.apk is refreshed and pushed.

Verified: APK v1.6, 66,035 bytes, sha256
485b1d4bf00f76a19d9d41111eea0f190867614f107783eea747b485d8767810;
apksigner v2+v3, 1 signer, cert fingerprint matches
v1.0–v1.5; badging confirms versionCode 7, versionName 1.6,
the same 3 permissions; both settings calls present in the
dex. Harness 497/497.

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

- Harness: harness/Harness.java, 452/452 checks on the exact
  shipping pure-Java classes (bridge parse/build, camera
  selection incl. the macro honesty rule, crop math incl.
  view<->image round-trip, focus/zoom math, YUV conversion
  incl. strides + downsampling, detector on synthetic eyes
  incl. sharp/soft/dark/eyelid/no-eye/off-center/tiny cases,
  gate state machine incl. drift resets and AF rules, 51
  auto-crop checks, 176 rotation checks, 6 sizing checks,
  plus 118 v1.4 checks: RgbFrame conversion (Rec.601 luma,
  saturation, center-sampled downsample, bad inputs), the
  CaptureStatus sequence (exact strings, no capture claim
  before SAVED, null fails safe), CropVerify decision
  boundaries + the fixture leg (Adam's real photo REJECTED
  at validFrac 0.36, re-centered versions ACCEPTED at
  0.97/0.82/0.75, centered/dark/eyelid synthetics accepted,
  displaced/too-tight/too-loose/flat rejected), the
  CropDiagnostics record (round-trip, separator safety,
  garbage handling, wording), and the TrustedSite rule +
  URL selection (verbatim referral passthrough, all
  rejection cases -> home).
  Run: javac -encoding UTF-8 the fourteen pure sources +
  Harness.java, java Harness, from harness/ (the fixture
  leg loads fixtures/adam-autocrop-20261116.jpg).
- APK v1.4: 61,939 bytes, sha256
  71d376bf2fafe93ffd7ba88e5b8efaf4fe5cbc8de47e5dac197d9ebfe616dd0e;
  apksigner verify passes (v2+v3, 1 signer, cert fingerprint
  matches v1.0/v1.1/v1.2/v1.3); badging confirms appId,
  versionCode 5, versionName 1.4, the same 3 permissions,
  launchable activity. The built manifest was dumped and
  confirms launchMode singleTask, the LAUNCHER filter
  intact, and the deep-link filter (autoVerify, VIEW /
  DEFAULT / BROWSABLE, https, both hosts); the dex contains
  all five new classes (RgbFrame, CropVerify,
  CaptureStatus, CropDiagnostics, TrustedSite). Grep
  confirms no user-facing "Captured." string remains (only
  comments explaining the fix) and all capture-phase
  statuses go through CaptureStatus.
- APK v1.3: 53,747 bytes, sha256
  40fac88a58606a1a1349a6be01f1e9c0fcc037b7a19ca9a3c5ce056a5233f5a1;
  apksigner verify passes (v2+v3, 1 signer, cert fingerprint
  matches v1.0/v1.1/v1.2); badging confirms appId,
  versionCode 4, versionName 1.3, the same 3 permissions,
  launchable activity. (Same byte count as v1.1/v1.2 by zip
  coincidence; the sha differs and the dex contains the new
  rotateRect/unrotateRect/BitmapRegionDecoder code.)
  Calibration (printed by the harness): sharp synthetic eye
  sharpness 20.2, defocused 5.7 — clean separation around the
  web's floor of 6. The threshold was NOT tuned to the
  synthetic; the synthetic's fiber texture and defocus kernel
  were made realistic instead.
- APK v1.2: 53,747 bytes, sha256
  49a73474269b04b19f8aab9f7724413b75742e6ab8ab583f00c7a274b4cdaa82;
  apksigner verify passes (v2+v3, 1 signer, cert fingerprint
  matches v1.0/v1.1); badging confirms appId, versionCode 3,
  versionName 1.2, the same 3 permissions, launchable activity.
  (Same byte count as v1.1 by zip coincidence; the sha differs
  and the dex contains the new AutoCrop/tryAutoCrop code.)
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
  triggers, and analysis performance on the A16. For v1.2
  additionally: the gate actually firing on-device (v1.1 could
  not fire — see the bug fix above), the auto-crop framing the
  iris correctly in the submitted photo (validates the rotation
  direction on real hardware), and the auto-submit landing in
  the reading flow with no crop screen. For v1.3 additionally:
  Rotate cycles 0/90/180/270/0 with the preview matching each
  tap, the crop square behaves in every rotation, the confirmed
  photo matches the preview orientation exactly, the submitted
  photo is visibly sharper / larger dimensions than 1.2,
  auto-capture still skips the crop screen, the auto-crop
  fallback shows the crop screen WITH rotation, and no
  OOM/slowness on a full-res capture (region decode). For v1.4
  additionally: the gate fires -> the status holds "Hold
  still — capturing…" until the still saves (no early
  "Captured."); a good auto-capture still submits with the
  iris centered; a bad-framing attempt lands on the manual
  crop screen with the honest reason + the diagnostics line
  readable, and the camera screen's "Last auto-crop check"
  line names the outcome; fixture-equivalent framing
  falls back instead of submitting; manual capture + Rotate
  + resolution unchanged; no new slowness (verification
  wall time shows in the diagnostics line). For deep links:
  tapping an affiliate link opens the app (straight in, now
  that the assetlinks file is live) and the affiliate's
  price shows in-app; a test checkout carries the referral;
  cold start via link; link tap while the app is already
  open; a plain launch still opens the home page; a
  non-eyeknow.app link still opens externally.

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
      RgbFrame.java           ARGB -> IrisFrame, verify path (pure)
      IrisDetector.java       pupil/iris/sharpness, web port (pure)
      AutoCaptureGate.java    stability window + fire latch (pure)
      AutoCrop.java           detection -> JPEG crop rect (pure)
      CropVerify.java         post-crop center-trace decision (pure)
      CaptureStatus.java      capture-phase status strings (pure)
      CropDiagnostics.java    attempt record + wording (pure)
      TrustedSite.java        URL trust rule + deep-link select (pure)
      GuideOverlay.java       eye guide + focus ring
      CropActivity.java       crop step
      CropOverlayView.java    square crop overlay
      CropMath.java           crop + sizing math (pure)
      JpegUtil.java           decode/crop/encode/data URL
    harness/Harness.java
    harness/fixtures/adam-autocrop-20261116.jpg   Adam's real mis-framed scan photo

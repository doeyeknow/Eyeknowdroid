# eyeKnow — native Android app

A native Android shell for [eyeknow.app](https://www.eyeknow.app) with a
native iris camera: macro lens selection, flash, autofocus, zoom into
the iris, auto-capture when an iris is detected in focus, a square crop
step, and submission straight into the eyeKnow
reading flow through the site's native-capture bridge.

- App ID: `app.eyeknow.android` (from Adam's Android starter)
- The shell loads the live site, so site updates need no reinstall.
- The camera implements Adam's `lib/nativeCapture.ts` bridge contract
  exactly: the site asks for a capture, the native camera returns the
  cropped JPEG, and the site's own reading pipeline takes it from there.
- See [BUILD_NOTES.md](BUILD_NOTES.md) for the build, the bridge
  decision, the camera design, and what is still unproven on-device.

## Build

Gradle cannot run in the build sandbox, so the APK is assembled with
the Android SDK tools directly (aapt2 → javac → d8 → zipalign →
apksigner). No third-party dependencies.

```sh
./build-apk.sh   # output: ./eyeknow.apk (signed)
```

`env.sh` must point at a JDK 17 + Android SDK (platform android-34,
build-tools 34.0.0). The release keystore is created on first build
and must never be committed — this repo is public.

## Status

v1.5 (2026-11-16): stamped APK download. An APK downloaded
through a referral link carries the code stamped into its
signing block; the app reads it on its first plain launch and
loads the home page with ?ref= once, so checkout credits the
affiliate. Once ever, deep links win, updates never re-stamp.

v1.4 (2026-11-16): auto-crop verification + capture sequencing
fix + diagnostics + deep links. The gate firing no longer says
"Captured." — the status holds "Hold still — capturing…"
until the still is saved. Every auto-crop is re-checked before
it submits (the iris edge is traced from the center of the cut
crop); a badly framed crop falls back to the manual crop
screen with the raw photo intact and a plain-words reason,
and the last attempt's numbers show on the camera screen.
Affiliate/referral links now open in the app, query intact,
so the referral lands in the app's own storage. On-device
proof (hold-still sequencing, good crops still submitting,
bad framing falling back with a readable reason, referral
price showing in-app) is still Adam's Galaxy A16.

v1.3 (2026-11-16): rotate after capture + higher-resolution
crops. The crop screen has a Rotate button (90° clockwise per
tap, four taps back to the original); the confirmed photo
matches the preview orientation exactly. Crop output is now
region-decoded at full source resolution and capped at 4096
on the long side instead of 2560 (capture already requested
the camera's largest JPEG size — the crop was the limiter).
The auto path is unchanged apart from the resolution. On-device
proof (rotation direction, sharper submitted photo, no OOM on
a full-res capture) is still Adam's Galaxy A16.

v1.2 (2026-11-15): auto-crop + auto-submit. A gate-fired still is
cropped to the detected iris bounds and submitted straight into
the reading flow — no crop screen on the auto path. Also fixes a
v1.1 bug: the analysis stream was never targeted by the repeating
request, so the gate could never fire. Manual capture and its crop
step are unchanged. On-device proof (gate firing, crop framing,
submit landing) is still Adam's Galaxy A16.

v1.1 (2026-11-15): auto-capture. The camera fires the still itself
when the web reader's own decision logic — ported to pure Java —
sees an iris in focus and steady for ~0.8 s. Manual capture always
remains; auto-capture toggles off. Real-eye detection, focus
accuracy, false triggers, and A16 performance are unverified —
the Galaxy A16 is the first test.

v1.0 (2026-11-15): first build. The site's bridge code is live
(the Eyeknow `main` fix deployed 2026-11-15), so the native
camera activates with no app update. Camera behavior on real
hardware is unverified — the Galaxy A16 is the first test.

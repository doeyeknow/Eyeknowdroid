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

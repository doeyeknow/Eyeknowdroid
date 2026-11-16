# eyeKnow — native Android app

A native Android shell for [eyeknow.app](https://www.eyeknow.app) with a
native iris camera: macro lens selection, flash, autofocus, zoom into
the iris, a square crop step, and submission straight into the eyeKnow
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

v1.0 (2026-11-15): first build. The native camera activates when the
site's bridge code deploys (the Eyeknow repo's `main` branch needs
the duplicate `nativeCapture.ts` removed and the line-66 guard
applied first — Adam's GitHub action). Until then the shell runs
the site's web camera / upload fallback. Camera behavior on real
hardware is unverified — the Galaxy A16 is the first test.

# AsteriskMEOW — meow-rs engine PoC

This repository is a fork of
[AsteriskMETA](https://github.com/Asterisk4Magisk/AsteriskMETA) at
`17d3974` (2026-10-30), plus `meow-poc/`: a self-contained Android app that
runs the [meow-rs](https://github.com/meow-rs/meow-rs) kernel over
`VpnService` through [libmeow](https://github.com/grill-glitch/libmeow).

The META app itself is untouched. `meow-poc/` is a separate Gradle project
(its own `settings.gradle.kts`) so it can be built and installed without
META's submodules or Compose toolchain.

## What the PoC proves

- `libmeow.so` loads and parses a Clash YAML profile with meow-config.
- meow's TUN listener adopts the `VpnService.Builder.establish()` fd
  (patched in libmeow; see its README).
- All outbound sockets go through `VpnService.protect`.
- DNS is answered in-process by meow's fake-IP resolver on the TUN.
- DIRECT traffic flows end to end.

It does **not** yet expose the Libclash-compatible API, mihomo's
`/proxies`/`/configs` control surface, per-app UID attribution, or the ROOT
modes. Those are the next milestones.

## Build

```bash
# 1. Build libmeow (arm64)
cd ../libmeow
./scripts/build-android.sh arm64-v8a release

# 2. Package it into the PoC
cd ../AsteriskMEOW/meow-poc
mkdir -p app/src/main/jniLibs/arm64-v8a
cp ../../libmeow/build/jniLibs/arm64-v8a/libmeow.so app/src/main/jniLibs/arm64-v8a/

# 3. APK
./gradlew :app:assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Install and run:

```bash
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.asterisk.meow.poc/.MainActivity
```

## Profile

`app/src/main/assets/config.yaml` is copied to the app's files dir on each
start. It is DIRECT-only for the first milestone; add `proxies:` and rules
as meow-rs supports them. `unified-delay`/handshake semantics are not yet
ported — they are kernel measurement changes, not config keys.

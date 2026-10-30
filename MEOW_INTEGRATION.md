# AsteriskMEOW — META UI with the meow-rs kernel

This fork of [AsteriskMETA](https://github.com/Asterisk4Magisk/AsteriskMETA)
keeps the META Compose UI and replaces the mihomo `libclash` AAR with
`:libclash-shim`, a Java bridge over [libmeow](../libmeow) (meow-rs).

- Package: `org.asterisk.zcc.ameow` (coexists with `org.asterisk.zcc.ameta`;
  no data is shared or migrated).
- First launch seeds `AsteriskMEOW Demo`, a DIRECT profile, so the VPN can
  start without importing a subscription.
- APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
  (`./gradlew :app:assembleDebug`).

## What works

- META home / proxy / config / settings UI, unchanged.
- `VpnService` fd handed to meow's TUN listener (`startTun` → libmeow); all
  data-plane routing is meow-rs.
- `TunInterface.markSocket` → meow's socket protector;
  `TunInterface.querySocketUid` → per-flow UID for `UID`/`PROCESS-NAME` rules.
- Traffic counters, engine start/stop/restart, profile YAML load.
- Proxy snapshot is built from the profile YAML, so configured proxies and
  groups appear on the proxy page.

## Known gaps (control plane)

- Delay tests return an explicit error; `queryGroupDelay` is empty.
- Proxy selection is stored in the shim but does **not** change meow's
  routing yet (meow would need a config reload).
- Providers, connections, logs and auto-switch are stubs; age-encrypted and
  HWID subscriptions are unsupported.
- ROOT modes still use the mihomo CLI; only VPN mode runs on meow.

## Build notes

- `buildSrc` uses JVM 21 (upstream pins 26; JDK 26 is not installed here).
- `libclash-shim/src/main/jniLibs/arm64-v8a/libmeow.so` is the arm64 build
  from `libmeow`; rebuild it there and copy it in.
- META closes the established `ParcelFileDescriptor` after `startTun`
  returns, so `AndroidMihomoRuntime` now passes `detachFd()`; meow owns the
  fd for the session.
- META's generated config uses mihomo's `system` nameserver and
  `geoip.metadb`/`GeoSite.dat` names. The shim rewrites a sibling
  `config.meow.yaml` (`system` → `223.5.5.5`) and copies the resources to
  meow's expected `Country.mmdb` / `geosite.dat` names.

See `meow-poc/VERIFICATION.md` for the on-device evidence (milestones 1–3).

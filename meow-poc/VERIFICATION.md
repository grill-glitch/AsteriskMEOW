# Milestones 1–2 verification — alioth

Device: Xiaomi alioth (`M2012K11AC`), Android 16 / SDK 36, `arm64-v8a`.

Artifacts (milestone 2):

- `libmeow.so` — 6,022,608 bytes (arm64), NEEDED: `liblog.so`,
  `libdl.so`, `libm.so`, `libc.so` only (no `libc++_shared.so`).
- `app-debug.apk` — 8,583,917 bytes.

## Milestone 1 — VPN TUN data path

1. Installed the APK, launched `org.asterisk.meow.poc/.MainActivity`.
   `Libmeow.nativeVersion()` returned `libmeow 0.1.0 (meow-rs 0.28.0)`.
2. Tapped **Start VPN**; `VpnService.Builder.establish()` fd was handed to
   `Libmeow.nativeStart(config, fd, 1500)`.
3. logcat:

```
I libmeow: VpnService.protect hook installed
I libmeow: Config loaded: mode=rule, proxies=7, rules=1
I libmeow: TUN device 'meow' created in 0ms
I libmeow: lwIP netstack built in 1ms
I libmeow: TUN listener 'tun' started on device 'meow' (172.19.0.1/30, mtu 1500,
           auto-route: false, dns-hijack: true, max-connections: 256, stack: lwip)
I libmeow: engine started: .../files/config.yaml (fd=132, mtu=1500)
I AsteriskMEOW: TUN up · libmeow 0.1.0 (meow-rs 0.28.0)
I libmeow: 172.19.0.1:47180 --> example.com:80 match MATCH(DIRECT) using DIRECT
```

4. `adb shell curl http://example.com/` returned 200 through the tunnel; the
   UI showed `upload 2,612 B / download 11,681 B`; the VpnService interface
   was the default route.
5. **Stop VPN** logged `engine stopped` and removed the interface routes.

## Milestone 2 — per-flow UID + real proxy path

### UID attribution

`nativeSetOwnerResolver(this)` installs the
`ConnectivityManager.getConnectionOwnerUid` hook; the TUN listener fills
`Metadata.uid` / `process` before rule matching.

Test profile: `UID,2000,REJECT` before `MATCH,DIRECT` (UID 2000 is `shell`).

```
I libmeow: getConnectionOwnerUid hook installed
I libmeow: 172.19.0.1:47268 --> example.com:80 match UID(2000) using REJECT
$ adb shell curl ... http://example.com/
curl=000            # rejected, as configured
```

`adb shell id -u` is 2000, so the rule matched the shell flow specifically.

### Proxy path

A minimal SOCKS5 CONNECT server (`meow-poc/tools/socks5_server.py`) runs on
the host, exposed to the phone with `adb reverse tcp:1080 tcp:1080`.

Profile:

```yaml
proxies:
  - {name: lan-socks, type: socks5, server: 127.0.0.1, port: 1080}
rules:
  - MATCH,lan-socks
```

(logcat)

```
I libmeow: 172.19.0.1:47618 --> example.com:80 match MATCH(lan-socks) using lan-socks
$ adb shell curl ... http://example.com/
curl=200
```

(host SOCKS server)

```
[socks] 127.0.0.1 -> example.com:80
[socks] 127.0.0.1 -> app.tuta.com:443
```

Killing the server made the next request fail (`curl=000`,
`dial error: IO error: early eof`) while the log still said
`using lan-socks` — no silent DIRECT fallback. Restarting it restored 200.

Note: the phone reaches the host over Wi-Fi, but the host's own VPN
(`throne-tun`) captured the LAN route, so the proxy was exposed over
`adb reverse` instead.

## Notes / next

- Default asset profile stays DIRECT-only; test profiles are pushed to
  `files/config.yaml` via `run-as` (see `meow-poc/README.md`).
- Proven: fd TUN, `VpnService.protect`, fake-IP DNS, UID attribution,
  SOCKS5 proxy egress, clean stop.
- Not yet: Libclash-compatible API (40 methods), a native SS/Trojan node
  instead of SOCKS5, per-app rule parity, ROOT modes,
  `unified-delay`/handshake measurement semantics.

## Milestone 3 — META UI APK with meow kernel

The real META UI (`:app`) now depends on `:libclash-shim` instead of the
mihomo AAR; package `org.asterisk.zcc.ameow` coexists with the user's META.

- APK: `app-arm64-v8a-debug.apk` (52,768,584 B), label AsteriskMEOW.
- First launch seeded `AsteriskMEOW Demo` (`data/DemoProfileSeeder`).
- Home: 服务已启用, 当前配置 = AsteriskMEOW Demo, 当前节点 = DIRECT,
  upload/download counters live.
- Proxy page: DIRECT / Direct / 未测试 (profile snapshot from YAML).
- logcat: `TunInterface.markSocket hook installed`,
  `TunInterface.querySocketUid hook installed`, `TUN listener 'tun' started`,
  `engine started: .../config.meow.yaml`.
- `adb shell curl http://example.com/` → 200 through the VPN; log
  `172.19.0.1:... --> example.com:80 match MATCH(DIRECT) using DIRECT`.
- Stop → `engine stopped`, tun routes removed, process alive; Start again →
  TUN restarted (fd 143) and curl 200.

Two integration fixes were required:

1. fd ownership: META's `tunFileDescriptor.use { ... }` closed the fd after
   `startTun` returned, aborting meow's netstack (SIGABRT). The runtime now
   passes `it.detachFd()`.
2. config normalization: `dns.default-nameserver: system` is invalid for
   meow; the shim rewrites `config.meow.yaml` and seeds
   `Country.mmdb` / `geosite.dat` from META's `geoip.metadb` / `GeoSite.dat`.

Not yet wired: delay tests, provider pages, logs/connections, selection
routing, age/HWID, ROOT modes.


### Config sync / HWID

Saving a URL subscription with HWID previously failed at the 下载配置 stage
with "The bundled core does not support subscription HWID" because the shim
returned `supportsSubscriptionHwid() = false`. The shim now returns true and
forwards `FetchOptions.hwid` as the `x-hwid` request header (mihomo's
header), so the profile save can complete.

### Subscription save, end to end

After the HWID gate fix, saving a URL subscription still failed with
`FileNotFoundException: .../cache/mihomo-profile-processing-*: open failed:
EISDIR`: META passes the processing **directory**, while the shim wrote to
`path` itself. The shim now writes `config.yaml` inside it.

It also called `Clash.decryptAge(content, "")` on every profile read; the
shim threw unconditionally, which would have failed even non-age profiles.
Empty keys now return the content unchanged.

Verified with a local HTTP subscription (`http://127.0.0.1:8000/sub.yaml`,
`adb reverse`): the profile saved as `127.0.0.1`, 同步时间 22:10, and
`files/mihomo-profiles/profile-*.yaml` was written. No error dialog.

### Delay tests and protocol coverage

"Test group" failed every node because the first `libmeow` build disabled
meow's default protocol features: the log showed
`proxy type 'vless' is not compiled into this build`, every VLESS node was
dropped, and every group was empty. Rebuilding `meow-config` / `meow-proxy`
with their default features restored VLESS/Vision/Vmess/etc.

`nativeProxyDelay` now runs meow's real URL probe (`probe_and_record`) and
`queryGroupDelay` fans it out over the group's leaf nodes. Verified on the
user's subscription: 334/344/352/516/613 ms on several nodes (two timeouts
for genuinely slow nodes), and VPN egress changed from the host IP
`18.138.127.43` to proxy `57.181.70.92`.

### Build configuration audit

Three mismatches were found and fixed:

- `MIN_SDK=29` was used for bindgen/CMake, but `cargo ndk` was not given a
  platform, so the ELF was tagged API 21. `cargo ndk -P 26` plus
  `MIN_SDK=26` now matches the app's minSdk 26 and the ELF note reads 26.
- `RUST_ANDROID_GRADLE_CC_LINK_ARG` is a rust-android-gradle variable and was
  ignored by cargo-ndk; 16 KB alignment happened to come from NDK r30. It is
  replaced by an explicit rustc link argument; LOAD alignment is 0x4000.
- The app advertised four ABIs, but libmeow could only be built for arm64:
  armv7 and x86_64 fail in `boring-sys` bindgen (`_bindgen_ty_1`). The fork
  now declares arm64-v8a only instead of shipping broken splits.

Remaining known risks: `panic=abort` means a Rust panic in a JNI entry point
kills the app; NDK/CMake paths in the build script are pinned to the local
SDK; `strip=true` makes AGP warn that libmeow.so cannot be stripped again.

### Home monitoring: memory, connections, traffic rate

`queryMemory()` now returns the process RSS (`/proc/self/status VmRSS`),
`queryConnectionCount()` / `queryConnections()` read meow's
`Statistics::active_connections`, and `queryTrafficNow()` derives per-second
rates from the cumulative counters; `queryTrafficTotal()` remains cumulative.

Verified on alioth with a 20 KiB/s limited download: home showed
`3 个活动连接`, `CPU 7.3% · 335.4 MiB`, and `↓ 35.9 KiB/s` instead of the
old `↓ 1.1 MiB/s` cumulative reading.

### JNI panic boundary

The release profile now uses `panic = "unwind"`; engine start/stop, the delay
probe and the statistics entry points catch panics at the JNI boundary, and
mutex poisoning is recovered instead of `unwrap()`-panicking.

### ROOT TUN startup

TUN（ROOT）mode failed with `child_exited exitCode=127` because META's
`asteriskd` executes `nativeLibraryDir/libmihomo.so`; replacing the
`libclash` AAR with the shim removed that launcher and its `libgojni.so`
payload. Restored both from `AndroidLibClashLite v1.19.33-android.3`:
`libmihomo.so` (CLI launcher) + `libgojni.so` (mihomo core). TUN now reaches
`phase=Running` and creates `asterisk0` with `172.19.0.1/30`.

This path still runs the mihomo core, not libmeow: meow's TUN listener
rejects Android's non-fd device creation (`tun listener on Android needs a
pre-opened fd`). VPN Service mode remains libmeow.


### ROOT TUN: meow core

Removed all mihomo binaries (`libmihomo.so`, `libgojni.so`). `libmeowcore.so`
is a PIE built from `libmeow/core`; `asteriskd` starts it with `-d/-f`, it
reshapes META's `listeners: type tun` into meow's `tun:` block, opens
`/dev/net/tun` (`asterisk0`), installs `uidrange 0-0` + table 2022 policy
rules, and hands the fd to meow's TUN listener. Device: TUN reaches
`meow-core: TUN ready on asterisk0`; `asterisk0` is UP with 172.19.0.1/30.

Android app traffic still returns no data through this path (DNS/TCP fail;
the root/shell test is not equivalent to a VpnService-protected app), so
ROOT TUN is not yet declared working. VPN Service remains fully verified.


### Single engine, launcher only

`libmeow.so` now exports both the JNI surface and `meow_cli_main`; the ROOT
entry point is a 5.6 KB PIE `libmeowcore.so` that dlopens the same
`libmeow.so` and calls `meow_cli_main(argc, argv)`. The duplicate 9.7 MB
engine binary was removed. TUN reaches ready on `asterisk0` through this
launcher; the Android data path remains under verification.

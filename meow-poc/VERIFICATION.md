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

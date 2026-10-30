# Milestone 1 verification — alioth

Device: Xiaomi alioth (`M2012K11AC`), Android 16 / SDK 36, `arm64-v8a`.

Artifacts:

- `libmeow.so` — 6,010,584 bytes (arm64), NEEDED: `liblog.so`,
  `libdl.so`, `libm.so`, `libc.so` only (no `libc++_shared.so`).
- `app-debug.apk` — 8,571,893 bytes.

## What ran

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

4. `adb shell` traffic through the tunnel:

```
$ adb shell curl -s -o /dev/null -w 'http=%{http_code}\n' --max-time 10 http://example.com/
http=200
```

5. Device routing showed the VpnService interface (`tun1`) as the default
   network, and the PoC UI reported live engine totals:

```
engine: running
upload: 2,612 B
download: 11,681 B
```

6. Tapped **Stop VPN**: `engine stopped`, and the `tun1` routes were removed.

## Notes / next

- The profile is DIRECT-only (`meow-poc/app/src/main/assets/config.yaml`).
- This proves the patched kernel TUN fd path, `VpnService.protect`, fake-IP
  DNS and DIRECT forwarding on-device.
- Not yet: Libclash-compatible API (40 methods), per-flow UID attribution,
  proxy protocols in the profile, ROOT modes, `unified-delay`/handshake
  measurement semantics.

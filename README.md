# KARETA.KZ — Android shell

Current shell: 1.4.5 (11)
Application ID: `kz.kareta.app`
Web origin: `https://kareta.kz/`
Native API: 6
Hosted diagnostics: `https://kareta.kz/#/diagnostics`

Architecture: the UI is owned by `kareta.kz`; Android is the native WebView/hardware bridge for Bluetooth/ELM327, camera, scanner, notifications, location, contacts and other device capabilities.

1.4.3 hardens Android system insets, async permissions, ELM327 Classic SPP transport/state handling, connect timeouts and durable offline synchronization. Native duplicate ELM327 UI has been removed from the offline screen.

Generated build output, `.gradle`, `build/`, APKs, local.properties, signing material and nested release ZIPs do not belong in Git.

See `CHANGELOG.md` and `docs/V1_4_2_ANDROID_BRIDGE_HARDENING.md`.


### 1.4.3 verification

GitHub Actions requires both `lintDebug` and `assembleDebug` before publishing the debug APK.
WebView file inputs, native camera capture, trusted-origin camera/microphone permissions,
geolocation prompts, predictive back navigation, renderer recovery, ELM327 and the
durable offline queue are included in the verified shell.

### 1.4.5 — production-domain Android shell

- Android WebView, native message bridge, geolocation and WebRTC trust only `https://kareta.kz/`.
- External first-party subdomains are not native-trusted; authentication cookies from the former `s.kareta.kz` origin do not migrate and users must sign in again.
- Route opening validates the exact trusted HTTPS host (rather than a string prefix).
- The APK loads the live website and therefore requires `https://kareta.kz/` to be reachable; the Android app cannot repair HTTP 530/network-tunnel failures.
- Physical Android/ELM327 testing and persistent release signing are still required before a production release.

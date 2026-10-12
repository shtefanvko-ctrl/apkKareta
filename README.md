# KARETA.KZ — Android shell

Staging shell candidate: 1.5.0-staging (10)
Staging Application ID: `kz.kareta.app.staging` (installs alongside production)
Previous shell: 1.4.3 (9)
Web origin: `https://s.kareta.kz/`
Native API: 6
Hosted diagnostics: `https://s.kareta.kz/#/diagnostics`

Architecture: the UI is owned by `s.kareta.kz`; Android is the native WebView/hardware bridge for Bluetooth/ELM327, camera, scanner, notifications, location, contacts and other device capabilities.

1.4.3 hardens Android system insets, async permissions, ELM327 Classic SPP transport/state handling, connect timeouts and durable offline synchronization. Native duplicate ELM327 UI has been removed from the offline screen.

Generated build output, `.gradle`, `build/`, APKs, local.properties, signing material and nested release ZIPs do not belong in Git.

See `CHANGELOG.md` and `docs/V1_4_2_ANDROID_BRIDGE_HARDENING.md`.


### 1.4.3 verification

GitHub Actions requires both `lintDebug` and `assembleDebug` before publishing the debug APK.
WebView file inputs, native camera capture, trusted-origin camera/microphone permissions,
geolocation prompts, predictive back navigation, renderer recovery, ELM327 and the
durable offline queue are included in the verified shell.

### 1.5.0 staging isolation

- Debug APK label: **KARETA STAGING**. Its package is isolated from the production app and uses independent app storage/cookies.
- Main WebView, native navigation and message listener trust **https://s.kareta.kz/** only.
- In-WebView requests to other KARETA first-party hosts are blocked rather than silently redirected to production; first-party navigation and native `openExternal` calls cannot open those hosts.
- External map handlers and Android/Google platform services remain external; the Android shell does not rewrite third-party traffic.
- The remote SPA must itself use relative staging API URLs; WebView interception alone does not certify all service-worker, WebSocket or backend-to-backend traffic. Validate those against the live staging deployment.
- CI gates: staging-source isolation, lintDebug and assembleDebug. Device-only acceptance (camera, QR, geolocation, Bluetooth ELM327 and offline replay) remains separate until exercised on a physical Android device.

Install the CI-generated debug APK on a test handset. It must open **s.kareta.kz** over HTTPS, with no production fallback. Do not publish a production release from this branch.

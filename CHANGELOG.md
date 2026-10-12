# Changelog

## 1.5.3-staging (13) — transport-agnostic ELM327

- Bluetooth Classic SPP: paired devices, secure RFCOMM with insecure fallback for compatible paired clone adapters.
- BLE: active scanner (with Android 12+ BLUETOOTH_SCAN permission), GATT UART with NUS, HM-10/FFE1, FFF0/FFF1/FFF2 and Vgate service profiles.
- Wi-Fi: direct TCP to a user-provided RFC1918 IPv4/port; public Internet targets are deliberately rejected.
- All transports reuse the existing read-only Native API 6, command allowlist, ELM initialization, DTC/VIN/PID sampling and offline queue.
- Reconnect remembers adapter type and last selected address/host/port.
- USB OTG serial controllers, proprietary ABS/SRS protocols and universal OBDLink compatibility are **not implemented**.
- CI lint/assemble is not a physical ELM/ECU acceptance test. The user-installable candidate must be signed with the existing locally stored staging key.

## 1.5.2-staging (12) — display name and durable signing

- Application name changed to **Kareta.kz - Автосервисы!**, preserving the existing KARETA icon and staging package ID.
- New staging-only, locally persisted signing key ensures that future staging builds can update in place once installed.
- CI remains lint/build only and its ephemeral debug-signed artifact must not be distributed to users.
- One-time uninstall of former GitHub debug-signed KARETA STAGING is unavoidable because Android forbids certificate changes across in-place upgrades.
- Signing identity is stored on KOMPUTER outside the repository with a DPAPI-encrypted password.

## 1.5.1-staging (11) — Android startup and location hotfix

- Removes the dark startup root, makes Android system splash and WebView paint white, and scales the splash mark consistently.
- Shows a branded native progress overlay until WebView reports a completed visual state, preventing blank black intermediary frames.
- Falls back to https://s.kareta.kz/ when activity state restoration lacks valid navigation history.
- Prefers network-based coarse location for nearby listings, with GPS fallback and an eight-second timeout.
- Preserves production-host blockade, API origin lock, ELM327/QR/camera permissions and 1.5.0 staging isolation.
- Leaves PHP staging SMS delivery/provider configuration unchanged. Service-side diagnosis is separate.

## 1.5.0-staging (10) — isolated staging APK

- New `kz.kareta.app.staging` package and `KARETA STAGING` label; can coexist with production.
- WebView home and Native API 6 remain bound to `https://s.kareta.kz/`.
- Blocks first-party KARETA production subresource requests, navigation and Native API requests to open production links.
- Rejects untrusted absolute routes using parsed URL origins, not string prefixes.
- Updates Android user agent and GitHub Actions build artifact to 1.5.0-staging.
- Adds build-time staging-host checks; preserves Bluetooth ELM327, GPS, QR, camera, contacts, notifications and offline diagnostics queue unchanged.
- CI build is not a physical-device test; live staging availability must pass before rollout.

## 1.4.3 — WebView media hardening

- Declares the FileProvider required by native and WebView camera capture.
- Restores HTML file inputs in WebView, including image capture when camera permission is granted.
- Restores trusted-origin WebRTC camera/microphone permission handling.
- Restores trusted-origin WebView geolocation prompts.
- Separates native-camera and WebView-camera result state to prevent callback collisions.
- Disables Android app-data backup for WebView/session privacy.
- CI now requires both lintDebug and assembleDebug before publishing the APK artifact.
- Replaces legacy back handling with AndroidX OnBackPressedDispatcher for gesture navigation.
- Adds explicit location permission proof at the LocationManager call site.
- Handles WebView renderer termination instead of allowing the process-loss path to crash the shell.
- Declares camera package visibility and disables cloud/device-transfer extraction of app session data.
- Bluetooth permission-sensitive ELM calls are lint-annotated only where a runtime guard already exists.

## 1.4.2 — Native API 6

- Web UI remains hosted at https://s.kareta.kz/.
- Android edge-to-edge corrected with systemBars, displayCutout and IME insets.
- Launcher icon uses a white adaptive background and vector orange KARETA mark.
- Native bridge is restricted to the https://s.kareta.kz origin.
- Bluetooth permission resolves only after the Android permission result.
- ELM327 uses paired Bluetooth Classic SPP devices with a 15 second connect timeout.
- ELM state distinguishes DISCONNECTED, CONNECTING, ADAPTER_CONNECTED, READY and ERROR.
- ECU handshake checks ATI, ATDP and PID 0100 before enabling diagnostics.
- Read-only OBD command allowlist enforced in Native API 6.
- Offline diagnostic queue is durable and requires server acknowledgement before deletion.
- Native duplicate ELM327 UI removed; diagnostics UI belongs to s.kareta.kz/#/diagnostics.
- GitHub Actions builds the debug APK against Android SDK 36.

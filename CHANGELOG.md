# Changelog

## 1.4.4 — Production origin + OBD installation identity

- Adds a stable per-installation UUID stored in app-private preferences.
- Exposes `installationId` through trusted-origin `appInfo()` without changing Native API 6 command compatibility.
- Provides the device identity needed by the active-app OBD remote diagnostic control plane.
- Switches the Android WebView start URL from `https://s.kareta.kz/` to `https://m.kareta.kz/`.
- Moves the Native API 6 trusted-origin allowlist to `m.kareta.kz`, so camera, geolocation, scanner and ELM327 remain available after the production host switch.
- Updates the Android user agent and application version to 1.4.4 (10).
- Keeps staging `s.kareta.kz` outside the trusted native bridge.

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

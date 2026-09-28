# Changelog

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

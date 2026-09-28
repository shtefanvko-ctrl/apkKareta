# KARETA.KZ — Android shell

Current shell: 1.4.2 (8)
Application ID: `kz.kareta.app`
Web origin: `https://s.kareta.kz/`
Native API: 6
Hosted diagnostics: `https://s.kareta.kz/#/diagnostics`

Architecture: the UI is owned by `s.kareta.kz`; Android is the native WebView/hardware bridge for Bluetooth/ELM327, camera, scanner, notifications, location, contacts and other device capabilities.

1.4.2 hardens Android system insets, async permissions, ELM327 Classic SPP transport/state handling, connect timeouts and durable offline synchronization. Native duplicate ELM327 UI has been removed from the offline screen.

Generated build output, `.gradle`, `build/`, APKs, local.properties, signing material and nested release ZIPs do not belong in Git.

See `CHANGELOG.md` and `docs/V1_4_2_ANDROID_BRIDGE_HARDENING.md`.

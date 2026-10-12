# KARETA.KZ — Android shell

Staging shell candidate: 1.5.2-staging (12)
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

### 1.5.1 staging UX hotfix

- Native and Android 12 system splash now use the same white background and a bounded KARETA mark.
- The WebView root no longer draws an empty dark frame before the first visual state is committed.
- A small branded loading overlay remains visible until WebView has painted.
- If Android fails to restore WebView history, the shell loads the staging start URL.
- Location now prioritizes fast network location over slow GPS for nearby discovery (GPS remains a fallback), accepts reasonably accurate recent positions and times out after eight seconds.
- Backend OTP provider configuration is owned by the staging PHP deployment, not the APK. The application must not bypass identity security if SMS is absent.
- On GitHub-hosted CI, the Android debug keystore may differ between builds: if the existing staging app cannot be upgraded, uninstall **only KARETA STAGING** before installing the new debug APK. This clears local staging cookies.

### 1.5.2 staging identity and stable signing

- Android launcher name is EXACTLY `Kareta.kz - Автосервисы!` (Android manifest label). The staging package ID remains `kz.kareta.app.staging` so it can coexist with production.
- GitHub Actions builds are for compilation/lint evidence **only**: their runner-specific debug certificate MUST NOT be used for end-user installation or in-place updates.
- The user-installable APK is re-signed with the **permanent local staging key** on KOMPUTER using `scripts/sign-staging-apk.ps1` and the existing Android SDK build tools. The signer validates versionCode/applicationId and verifies v2/v3 signatures after signing.
- The PKCS12 key and DPAPI-encrypted password are held outside the repository in `%LOCALAPPDATA%\Kareta\Signing`. NEVER commit either file, share the private key, or put it in downloadable ZIPs.
- To produce the next version, increment versionCode, update the script's expected version metadata, compile via CI, download the build input, and sign it on KOMPUTER with **the existing key**. Do not regenerate keys after the first run.
- IMPORTANT: prior GitHub-hosted CI APKs have a different debug certificate. They cannot be updated in place to this permanently signed version. Once, uninstall ONLY the previous `KARETA STAGING` package and install 1.5.2. Future permanently signed updates can install on top without uninstalling.
- Back up the signing identity securely. The DPAPI password file is tied to the original Windows profile; migrating machines requires a planned secure export/restore, not copying the key alone.
- Physical Android smoke tests are not replaced by lint/assemble CI checks.

### Universal ELM transports (staging 1.5.3)

- `classic_spp`: Android Bluetooth paired device selection. Attempts secure RFCOMM, then an insecure RFCOMM fallback if the paired adapter requires it.
- `ble_gatt`: Android BLE scan with runtime permissions. Supported profiles include Nordic UART (NUS), HM-10 FFE0/FFE1, FFF0 and Vgate UART. Unsupported or proprietary BLE services receive explicit errors rather than false READY states.
- `wifi_tcp`: Connect to a **private IPv4** address and port using the phone's Wi-Fi connection; configure the adapter network on the phone first.
- `elmStatus`, `elmInit`, `elmCommand`, `elmSnapshot`, `elmLiveSnapshot` and the existing read-only OBD command allowlist are the common upper layer. Native API version remains 6.
- USB OTG serial (CH340, CP210x, FTDI and CDC ACM) requires a separately tested Android USB Host implementation and is not part of this candidate.
- Compile/lint alone does not prove any Bluetooth or vehicle handshake. Validate each physical adapter and vehicle with logs and ignition safely switched on.
- Do not distribute the ephemeral CI-signed APK as the upgrade: re-sign on KOMPUTER with the same permanent staging certificate used for versionCode 12. This is needed for Android update-in-place.

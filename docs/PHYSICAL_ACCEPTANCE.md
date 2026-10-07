# KARETA Android — Physical Device Acceptance

This harness is the final verification layer after repository/source/build gates. It does not replace those gates and must not be reported as PASS unless an authorized Android device is connected through ADB and the required steps are actually performed.

## Candidate

Use the exact APK artifact produced by the Android Debug APK workflow for the candidate under test. Verify the artifact digest before installation.

Example:

```bash
python3 tools/device_acceptance.py \
  --apk ./app-debug.apk \
  --expected-sha256-file ./app-debug.apk.sha256
```

On Windows PowerShell, the same Python command works if `adb.exe` and `python` are on PATH.

## Automated preflight

The harness verifies:

- exactly one authorized ADB device, unless `--serial` is supplied;
- exact APK SHA-256 when `--expected-sha256` or `--expected-sha256-file` is supplied;
- install succeeds;
- package `kz.kareta.app` is version 1.4.4 (10);
- MainActivity launches and becomes foreground;
- no fatal AndroidRuntime/ANR signature appears immediately after launch;
- evidence files and screenshots are stored under `artifacts/device-smoke/`.

Use `--non-interactive` to run only this automated preflight.

## Manual hardware acceptance

Interactive mode records PASS/FAIL/SKIP and captures a screenshot/logcat after each step:

1. SPA second navigation circle.
2. Native QR.
3. Native VIN.
4. Camera/file capture.
5. Geolocation.
6. Bluetooth paired ELM327 connect.
7. ELM327 init / ECU readiness.
8. ELM327 diagnostic snapshot.
9. Offline diagnostic → network restore → API sync → history with vehicleId.

A skipped hardware step produces PARTIAL, not PASS. Any failed step or fatal Android runtime error produces FAIL.

## Evidence

The evidence directory includes install output, package dump, activity dump, launch screenshot, logcat, per-step screenshots/logs, and a machine-readable `result.json`.

Do not commit real vehicle identifiers, VINs, location data, Bluetooth addresses, screenshots, or generated evidence directories to Git. Evidence is for local/release review only.


## APK provenance

The Android Debug APK workflow publishes both `app-debug.apk` and `app-debug.apk.sha256` in the same exact-head artifact. Use the checksum file for device acceptance.

Do not use the GitHub artifact ZIP digest as the APK digest: the artifact digest covers the ZIP container, while the harness verifies the extracted APK bytes.

# KARETA Android physical acceptance

Run after downloading the exact-head debug APK and connecting one authorized Android device over ADB.

```bash
bash tools/device_smoke_adb.sh /path/to/app-debug.apk
```

The harness verifies only what ADB can prove automatically: device authorization, APK installation, exact app version, successful activity launch, foreground process, and absence of a fatal crash in the initial logcat window. It stores evidence in `device-evidence/<UTC timestamp>/`.

The generated `manual-acceptance.txt` is the physical acceptance checklist for WebView navigation, native QR/VIN, camera, geolocation, Bluetooth, ELM327, vehicle-bound diagnostics, offline persistence and server synchronization.

A successful shell exit does **not** mean those manual/device-hardware scenarios passed. Until the checklist is executed on a device and vehicle, they remain `NOT RUN`.

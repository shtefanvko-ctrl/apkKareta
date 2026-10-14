---
name: android-apk-release
description: Manage KARETA Android version, app name, icon/splash, signing and APK build identity.
version: 1.0.0
---
Keep applicationId stable. Update versionName/versionCode deliberately. Do not commit signing secrets. Verify launcher/adaptive icon and splash separately. Build exact candidate, record SHA/artifact identity, and distinguish debug from release signing.

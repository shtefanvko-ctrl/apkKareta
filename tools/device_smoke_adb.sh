#!/usr/bin/env bash
set -euo pipefail

PACKAGE="kz.kareta.app"
ACTIVITY="kz.kareta.app/.MainActivity"
EXPECTED_HOST="m.kareta.kz"
EXPECTED_VERSION="1.4.4"
APK_PATH="${1:-}"
EVIDENCE_DIR="${2:-device-evidence}"

fail(){ echo "DEVICE_SMOKE: FAIL — $*" >&2; exit 1; }
note(){ echo "DEVICE_SMOKE: $*"; }

command -v adb >/dev/null 2>&1 || fail "adb not found in PATH"
mkdir -p "$EVIDENCE_DIR"

mapfile -t DEVICES < <(adb devices | awk 'NR>1 && $2=="device"{print $1}')
if [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "DEVICE_SMOKE: NOT RUN — no authorized Android device connected"
  exit 2
fi
if [[ ${#DEVICES[@]} -gt 1 && -z "${ANDROID_SERIAL:-}" ]]; then
  fail "multiple devices connected; set ANDROID_SERIAL"
fi
SERIAL="${ANDROID_SERIAL:-${DEVICES[0]}}"
export ANDROID_SERIAL="$SERIAL"

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RUN_DIR="$EVIDENCE_DIR/$STAMP"
mkdir -p "$RUN_DIR"

note "device=$SERIAL"
adb get-state | tee "$RUN_DIR/get-state.txt"
adb shell getprop ro.product.manufacturer | tr -d '\r' | tee "$RUN_DIR/manufacturer.txt"
adb shell getprop ro.product.model | tr -d '\r' | tee "$RUN_DIR/model.txt"
adb shell getprop ro.build.version.release | tr -d '\r' | tee "$RUN_DIR/android-version.txt"
adb shell getprop ro.build.version.sdk | tr -d '\r' | tee "$RUN_DIR/android-sdk.txt"

if [[ -n "$APK_PATH" ]]; then
  [[ -f "$APK_PATH" ]] || fail "APK not found: $APK_PATH"
  sha256sum "$APK_PATH" | tee "$RUN_DIR/apk.sha256"
  note "installing exact APK"
  adb install -r "$APK_PATH" | tee "$RUN_DIR/install.txt"
fi

if ! adb shell pm path "$PACKAGE" | tee "$RUN_DIR/package-path.txt" | grep -q '^package:'; then
  fail "$PACKAGE is not installed"
fi

DUMPSYS="$(adb shell dumpsys package "$PACKAGE" | tr -d '\r')"
printf '%s\n' "$DUMPSYS" > "$RUN_DIR/package-dumpsys.txt"
VERSION_NAME="$(printf '%s\n' "$DUMPSYS" | sed -n 's/.*versionName=//p' | head -n1 | xargs)"
VERSION_CODE="$(printf '%s\n' "$DUMPSYS" | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n1)"
note "versionName=${VERSION_NAME:-unknown} versionCode=${VERSION_CODE:-unknown}"
[[ "$VERSION_NAME" == "$EXPECTED_VERSION" ]] || fail "expected versionName $EXPECTED_VERSION, got ${VERSION_NAME:-unknown}"

adb logcat -c
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$ACTIVITY" | tee "$RUN_DIR/am-start.txt"

sleep 3
FOCUS="$(adb shell dumpsys window | tr -d '\r' | grep -E 'mCurrentFocus|mFocusedApp' || true)"
printf '%s\n' "$FOCUS" | tee "$RUN_DIR/window-focus.txt"
printf '%s\n' "$FOCUS" | grep -q "$PACKAGE" || fail "app did not become foreground"

PID="$(adb shell pidof "$PACKAGE" | tr -d '\r' | xargs || true)"
[[ -n "$PID" ]] || fail "app process not running after launch"
note "pid=$PID"

adb logcat -d -v threadtime | grep -E "kz\.kareta\.app|chromium|AndroidRuntime|WebView|ELM327" > "$RUN_DIR/logcat.txt" || true
if grep -E "FATAL EXCEPTION|AndroidRuntime.*FATAL|Process: kz\.kareta\.app.*has died" "$RUN_DIR/logcat.txt" >/dev/null 2>&1; then
  fail "fatal Android runtime error detected"
fi

# Network/WebView evidence. These are diagnostics, not a substitute for UI assertions.
adb shell dumpsys connectivity > "$RUN_DIR/connectivity.txt" || true
adb shell dumpsys package com.google.android.webview > "$RUN_DIR/webview-package.txt" || true

cat > "$RUN_DIR/manual-acceptance.txt" <<'EOF'
PHYSICAL ACCEPTANCE — MANUAL STEPS

[ ] Login/open KARETA at m.kareta.kz
[ ] Navigate first circle: Главная → Услуги → Сообщество → Мастера → Ещё
[ ] Navigate second circle; no skeleton/loading regression
[ ] QR scanner opens native scanner and reads a valid KARETA QR
[ ] VIN scanner reads/normalizes a 17-char VIN
[ ] Camera/file input works
[ ] Geolocation permission works and returns actual device coordinates
[ ] Bluetooth permission granted
[ ] Paired ELM327 appears in device list
[ ] ELM327 connect succeeds
[ ] Init reaches READY / vehicleConnected=true
[ ] Snapshot returns RPM/speed/coolant/voltage
[ ] Full snapshot returns DTC list and VIN when ECU supports 0902
[ ] Diagnostic session is bound to vehicleId
[ ] Offline queue survives app restart
[ ] Network restore syncs valid session to /api/obd.php
[ ] Synced diagnostic appears in history

Do not mark any unchecked item PASS.
EOF

cat > "$RUN_DIR/result.env" <<EOF
DEVICE_SMOKE_AUTOMATIC=PASS
ANDROID_SERIAL=$SERIAL
PACKAGE=$PACKAGE
VERSION_NAME=$VERSION_NAME
VERSION_CODE=$VERSION_CODE
EXPECTED_HOST=$EXPECTED_HOST
PHYSICAL_UI_ACCEPTANCE=NOT_RUN
REAL_ELM327_ACCEPTANCE=NOT_RUN
EOF

note "AUTOMATIC PASS"
note "manual evidence checklist: $RUN_DIR/manual-acceptance.txt"
note "PHYSICAL UI / real ELM327 remain NOT RUN until checklist is executed"

#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = (ROOT / "app/src/main/java/kz/kareta/app/MainActivity.java").read_text(encoding="utf-8")
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
OFFLINE = (ROOT / "app/src/main/java/kz/kareta/app/OfflineQueue.java").read_text(encoding="utf-8")
README = (ROOT / "README.md").read_text(encoding="utf-8")

errors = []

def require(text: str, token: str, label: str) -> None:
    if token not in text:
        errors.append(f"{label}: missing {token}")

def require_regex(text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.MULTILINE | re.DOTALL):
        errors.append(f"{label}: pattern not found: {pattern}")

# Canonical shell/origin contract.
require(MAIN, 'private static final String BASE_URL = "https://m.kareta.kz/";', "origin")
require(MAIN, 'private static final String BASE_HOST = "m.kareta.kz";', "origin")
require(MAIN, 'private static final int NATIVE_API_VERSION = 6;', "native api")
require(README, 'Web origin: `https://m.kareta.kz/`', "readme")
require(README, 'Native API: 6', "readme")

# WebMessage listener and URI trust must both derive from the same BASE_HOST.
require(MAIN, 'Collections.singleton("https://" + BASE_HOST)', "web message origin")
require_regex(
    MAIN,
    r'private boolean isTrustedUri\(Uri uri\)\s*\{\s*return uri != null\s*&& "https"\.equalsIgnoreCase\(uri\.getScheme\(\)\)\s*&& BASE_HOST\.equalsIgnoreCase\(uri\.getHost\(\)\);',
    "trusted uri",
)
require(MAIN, 'if (!isMainFrame || !isTrustedUri(sourceOrigin))', "bridge origin guard")

# WebView hardening that must not regress.
for token, label in [
    ('settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);', 'mixed content'),
    ('settings.setAllowFileAccess(false);', 'file access'),
    ('cookies.setAcceptThirdPartyCookies(webView, false);', 'third-party cookies'),
    ('android:usesCleartextTraffic="false"', 'cleartext'),
]:
    require(MAIN if token.startswith(('settings.', 'cookies.')) else MANIFEST, token, label)

# Native API 6 commands consumed by the KARETA SPA bridge.
required_commands = [
    "appInfo","requestPermission","getLocation","openMap","ping","network","pushToken",
    "registerPush","unregisterPush","logout","pickImage","takePhoto","pickContact",
    "scanCode","actionSheet","elmStatus","elmDevices","elmConnect","elmReconnectLast",
    "elmDisconnect","elmInit","elmCommand","elmSnapshot","elmLiveSnapshot",
    "openBluetoothSettings","offlineState","offlineEnqueue","offlineDrain",
    "offlineAcknowledge","offlineRestore","offlineClear","share","copy","openPhone",
    "openExternal","openSettings","vibrate","reload","openRoute",
]
implemented = set(re.findall(r'case\s+"([^"]+)"\s*:', MAIN))
missing = [command for command in required_commands if command not in implemented]
if missing:
    errors.append("native api: missing required commands: " + ", ".join(missing))

# appInfo handshake consumed by js/mobile_native_bridge.js.
require(MAIN, 'out.put("nativeApiVersion", NATIVE_API_VERSION);', "appInfo")
require(MAIN, 'out.put("installationId", installationId());', "appInfo")
require(MAIN, 'out.put("baseUrl", BASE_URL);', "appInfo")
require(MAIN, 'out.put("nativeCapabilities", new JSONArray()', "appInfo")
required_capabilities = {
    "elm327","camera","images","contacts","location","scanner","actionSheet","offlineQueue"
}
capability_block = re.search(
    r'out\.put\("nativeCapabilities", new JSONArray\(\)(.*?)\);',
    MAIN,
    re.DOTALL,
)
if not capability_block:
    errors.append("appInfo: nativeCapabilities block missing")
else:
    capabilities = set(re.findall(r'\.put\("([^"]+)"\)', capability_block.group(1)))
    missing_caps = sorted(required_capabilities - capabilities)
    if missing_caps:
        errors.append("appInfo: missing capabilities: " + ", ".join(missing_caps))

# Native scanner must cover QR and VIN-oriented barcode formats.
require(MAIN, 'case "scanCode":', "scanner")
for token in [
    'Barcode.FORMAT_QR_CODE',
    'Barcode.FORMAT_CODE_39',
    'Barcode.FORMAT_CODE_128',
    'Barcode.FORMAT_DATA_MATRIX',
]:
    require(MAIN, token, "scanner")

# Device capabilities/permissions needed by currently exposed bridge functions.
for permission in [
    'android.permission.CAMERA',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.BLUETOOTH_CONNECT',
    'android.permission.POST_NOTIFICATIONS',
]:
    require(MANIFEST, permission, "manifest permission")

# Offline queue must remain durable and ack-based; drain/peek is non-destructive.
require(OFFLINE, 'private static final int MAX_ITEMS = 100;', "offline queue")
require(OFFLINE, 'out.put("durable", true);', "offline queue")
require(OFFLINE, 'out.put("acknowledgeRequired", true);', "offline queue")
require(OFFLINE, 'out.put("destructive", false);', "offline queue")
require(OFFLINE, '.commit();', "offline queue durable write")
require(MAIN, 'case "offlineDrain":', "offline bridge")
require(MAIN, 'replyOk(reply, id, offlineQueue.peek());', "offline drain")
require(MAIN, 'case "offlineAcknowledge":', "offline bridge")

if errors:
    print("NATIVE_API6_CONTRACT: FAIL")
    for error in errors:
        print("- " + error)
    sys.exit(1)

print("NATIVE_API6_CONTRACT: PASS")
print(f"required_commands={len(required_commands)}")
print(f"implemented_commands={len(implemented)}")
print("trusted_origin=https://m.kareta.kz/")
print("native_api=6")
print("offline_queue=durable_ack_based")

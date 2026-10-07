#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]

required_files = [
    "README.md",
    "ai/DEVIL.md",
    ".github/prompts/devil.prompt.md",
    ".cursor/commands/devil.md",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/kz/kareta/app/MainActivity.java",
    "app/src/main/java/kz/kareta/app/Elm327Manager.java",
    "app/src/main/java/kz/kareta/app/OfflineQueue.java",
    "tools/verify_native_api6_contract.py",
    "tools/verify_elm327_contract.py",
]

errors = []

for relative in required_files:
    if not (ROOT / relative).is_file():
        errors.append(f"missing required file: {relative}")

def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        return ""
    return path.read_text(encoding="utf-8")

devil = read("ai/DEVIL.md")
copilot = read(".github/prompts/devil.prompt.md")
cursor = read(".cursor/commands/devil.md")

devil_tokens = [
    "Android / WebView lane",
    "ELM327 / OBD lane",
    "https://m.kareta.kz/",
    "Native API version: 6",
    "read-only OBD",
    "durable and acknowledgement-based",
    "tools/verify_native_api6_contract.py",
    "tools/verify_elm327_contract.py",
    "NOT RUN",
    "DEVIL STATUS",
    "DESIRED",
    "IMPLEMENTED",
    "VERIFIED",
    "DEPLOYED",
    "BLOCKED",
]

for token in devil_tokens:
    if token not in devil:
        errors.append(f"ai/DEVIL.md missing contract token: {token}")

if not copilot.startswith("---\n") or "\n---\n" not in copilot[4:]:
    errors.append("Copilot DEVIL prompt is missing front matter")

for name, text in [("Copilot", copilot), ("Cursor", cursor)]:
    for token in [
        "ai/DEVIL.md",
        "README.md",
        "tools/verify_native_api6_contract.py",
        "tools/verify_elm327_contract.py",
        "DEVIL STATUS",
        "NOT RUN",
    ]:
        if token not in text:
            errors.append(f"{name} DEVIL adapter missing route/token: {token}")

main = read("app/src/main/java/kz/kareta/app/MainActivity.java")
elm = read("app/src/main/java/kz/kareta/app/Elm327Manager.java")
offline = read("app/src/main/java/kz/kareta/app/OfflineQueue.java")

for token in [
    'private static final String BASE_URL = "https://m.kareta.kz/";',
    "private static final int NATIVE_API_VERSION = 6;",
]:
    if token not in main:
        errors.append(f"Android source no longer matches DEVIL invariant: {token}")

if 'private static final int CONNECT_TIMEOUT_MS = 15000;' not in elm:
    errors.append("ELM327 bounded connect timeout invariant missing")

if 'out.put("acknowledgeRequired", true);' not in offline:
    errors.append("offline acknowledgement invariant missing")

if errors:
    print("DEVIL_ANDROID_MODE: FAIL")
    for error in errors:
        print("- " + error)
    sys.exit(1)

print("DEVIL_ANDROID_MODE: PASS")
print(f"checked_files={len(required_files)}")
print("trusted_origin=https://m.kareta.kz/")
print("native_api=6")
print("obd_policy=read_only")
print("evidence_policy=exact_head_no_fake_hardware_pass")

#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone

PACKAGE = "kz.kareta.app"
ACTIVITY = "kz.kareta.app/.MainActivity"
EXPECTED_VERSION_NAME = "1.4.4"
EXPECTED_VERSION_CODE = "10"

FATAL_PATTERNS = (
    "FATAL EXCEPTION",
    "AndroidRuntime",
    "Process: kz.kareta.app",
    "ANR in kz.kareta.app",
)

def run(cmd: list[str], *, text: bool = True, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=text,
        check=check,
    )

def adb_path() -> str:
    found = shutil.which("adb")
    if not found:
        raise RuntimeError("ADB_NOT_FOUND: install Android platform-tools and add adb to PATH")
    return found

def devices(adb: str) -> list[tuple[str, str]]:
    out = run([adb, "devices"]).stdout.splitlines()
    rows = []
    for line in out[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2:
            rows.append((parts[0], parts[1]))
    return rows

def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def parse_package_dump(text: str) -> dict:
    version_name = ""
    version_code = ""
    match = re.search(r"versionName=([^\s]+)", text)
    if match:
        version_name = match.group(1)
    match = re.search(r"versionCode=(\d+)", text)
    if match:
        version_code = match.group(1)
    granted = sorted(set(re.findall(r"android\.permission\.([A-Z0-9_]+): granted=true", text)))
    return {
        "versionName": version_name,
        "versionCode": version_code,
        "grantedPermissions": granted,
    }

def check_foreground(activity_dump: str) -> bool:
    patterns = (
        "mResumedActivity",
        "topResumedActivity",
        "ResumedActivity",
    )
    lines = [line for line in activity_dump.splitlines() if any(p in line for p in patterns)]
    return any(PACKAGE in line and "MainActivity" in line for line in lines)

def fatal_lines(logcat: str) -> list[str]:
    lines = []
    for line in logcat.splitlines():
        if any(pattern in line for pattern in FATAL_PATTERNS):
            lines.append(line)
    return lines[-50:]

def write_text(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8", errors="replace")

def screenshot(adb: str, serial: str, path: Path) -> bool:
    proc = subprocess.run(
        [adb, "-s", serial, "exec-out", "screencap", "-p"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if proc.returncode != 0 or not proc.stdout.startswith(b"\x89PNG"):
        return False
    path.write_bytes(proc.stdout)
    return True

def prompt_step(title: str, instruction: str) -> str:
    print()
    print(f"=== {title} ===")
    print(instruction)
    print("Enter PASS, FAIL <reason>, SKIP <reason>, or press Enter to mark PASS.")
    raw = input("> ").strip()
    if not raw:
        return "PASS"
    upper = raw.upper()
    if upper == "PASS":
        return "PASS"
    if upper.startswith("FAIL"):
        return raw
    if upper.startswith("SKIP"):
        return raw
    return "FAIL invalid operator response: " + raw

def self_test() -> int:
    sample = """
    versionCode=10 minSdk=24 targetSdk=36
    versionName=1.4.4
      android.permission.CAMERA: granted=true
      android.permission.ACCESS_FINE_LOCATION: granted=true
    """
    parsed = parse_package_dump(sample)
    assert parsed["versionName"] == "1.4.4"
    assert parsed["versionCode"] == "10"
    assert "CAMERA" in parsed["grantedPermissions"]
    assert check_foreground("mResumedActivity: ActivityRecord{ x kz.kareta.app/.MainActivity t1 }")
    assert not check_foreground("mResumedActivity: ActivityRecord{ x com.example/.MainActivity t1 }")
    assert fatal_lines("I/Tag: ok\nFATAL EXCEPTION: main\nProcess: kz.kareta.app")
    print("PHYSICAL_ACCEPTANCE_HARNESS_SELF_TEST: PASS")
    return 0

def main() -> int:
    parser = argparse.ArgumentParser(
        description="KARETA Android physical-device acceptance harness"
    )
    parser.add_argument("--apk", type=Path, help="Exact debug APK to install")
    parser.add_argument("--expected-sha256", default="", help="Optional exact APK sha256")
    parser.add_argument("--expected-sha256-file", type=Path, help="Optional sha256sum file emitted with the APK artifact")
    parser.add_argument("--serial", default="", help="ADB device serial when multiple devices exist")
    parser.add_argument("--evidence-dir", type=Path, default=Path("artifacts/device-smoke"))
    parser.add_argument("--no-install", action="store_true", help="Do not install APK; verify installed package only")
    parser.add_argument("--non-interactive", action="store_true", help="Run automated preflight/launch checks only")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    adb = adb_path()
    rows = devices(adb)
    unauthorized = [serial for serial, state in rows if state != "device"]
    ready = [serial for serial, state in rows if state == "device"]

    if unauthorized:
        print("ADB_UNAVAILABLE_DEVICES:", ", ".join(unauthorized), file=sys.stderr)
    if args.serial:
        if args.serial not in ready:
            raise RuntimeError(f"ADB_DEVICE_NOT_READY: {args.serial}")
        serial = args.serial
    else:
        if len(ready) != 1:
            raise RuntimeError(f"ADB_DEVICE_COUNT: expected 1 authorized device, found {len(ready)}")
        serial = ready[0]

    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    evidence = args.evidence_dir / f"{timestamp}_{serial.replace(':', '_')}"
    evidence.mkdir(parents=True, exist_ok=False)

    metadata = {
        "schema": 1,
        "package": PACKAGE,
        "activity": ACTIVITY,
        "serial": serial,
        "startedAtUtc": timestamp,
        "expectedVersionName": EXPECTED_VERSION_NAME,
        "expectedVersionCode": EXPECTED_VERSION_CODE,
        "automated": {},
        "manual": {},
    }

    if not args.no_install:
        if not args.apk or not args.apk.is_file():
            raise RuntimeError("--apk must point to the exact APK when --no-install is not used")
        apk_hash = sha256(args.apk)
        metadata["apk"] = {
            "path": str(args.apk.resolve()),
            "sha256": apk_hash,
            "bytes": args.apk.stat().st_size,
        }
        expected_hash = args.expected_sha256.strip().lower()
        if args.expected_sha256_file:
            if not args.expected_sha256_file.is_file():
                raise RuntimeError("EXPECTED_SHA256_FILE_NOT_FOUND")
            checksum_text = args.expected_sha256_file.read_text(encoding="utf-8").strip()
            file_hash = checksum_text.split()[0].lower() if checksum_text else ""
            if not re.fullmatch(r"[0-9a-f]{64}", file_hash):
                raise RuntimeError("EXPECTED_SHA256_FILE_INVALID")
            if expected_hash and expected_hash != file_hash:
                raise RuntimeError("EXPECTED_SHA256_ARGUMENT_CONFLICT")
            expected_hash = file_hash
        if expected_hash and apk_hash.lower() != expected_hash:
            raise RuntimeError(
                f"APK_SHA256_MISMATCH: expected {expected_hash}, got {apk_hash}"
            )
        metadata["apk"]["expectedSha256"] = expected_hash
        install = run([adb, "-s", serial, "install", "-r", str(args.apk)], check=False).stdout
        write_text(evidence / "01_install.txt", install)
        if "Success" not in install:
            metadata["automated"]["install"] = "FAIL"
            write_text(evidence / "result.json", json.dumps(metadata, indent=2, ensure_ascii=False))
            raise RuntimeError("APK_INSTALL_FAILED")
        metadata["automated"]["install"] = "PASS"
    else:
        metadata["automated"]["install"] = "SKIP --no-install"

    package_dump = run([adb, "-s", serial, "shell", "dumpsys", "package", PACKAGE], check=False).stdout
    write_text(evidence / "02_package.txt", package_dump)
    package_info = parse_package_dump(package_dump)
    metadata["installedPackage"] = package_info

    if package_info["versionName"] != EXPECTED_VERSION_NAME:
        raise RuntimeError(f"VERSION_NAME_MISMATCH: {package_info['versionName']}")
    if package_info["versionCode"] != EXPECTED_VERSION_CODE:
        raise RuntimeError(f"VERSION_CODE_MISMATCH: {package_info['versionCode']}")
    metadata["automated"]["version"] = "PASS"

    run([adb, "-s", serial, "logcat", "-c"], check=False)
    run([adb, "-s", serial, "shell", "am", "force-stop", PACKAGE], check=False)
    launch = run([adb, "-s", serial, "shell", "am", "start", "-W", "-n", ACTIVITY], check=False).stdout
    write_text(evidence / "03_launch.txt", launch)
    if "Error:" in launch or "Exception" in launch:
        raise RuntimeError("APP_LAUNCH_FAILED")
    time.sleep(4)

    activity_dump = run([adb, "-s", serial, "shell", "dumpsys", "activity", "activities"], check=False).stdout
    write_text(evidence / "04_activity.txt", activity_dump)
    foreground = check_foreground(activity_dump)
    metadata["automated"]["foregroundMainActivity"] = "PASS" if foreground else "FAIL"
    if not foreground:
        raise RuntimeError("MAIN_ACTIVITY_NOT_FOREGROUND")

    screenshot(adb, serial, evidence / "05_launch.png")

    logcat = run([adb, "-s", serial, "logcat", "-d", "-v", "threadtime"], check=False).stdout
    write_text(evidence / "06_logcat_launch.txt", logcat)
    fatals = fatal_lines(logcat)
    metadata["automated"]["launchFatalErrors"] = "FAIL" if fatals else "PASS"
    if fatals:
        write_text(evidence / "06_fatal_extract.txt", "\n".join(fatals))
        raise RuntimeError("FATAL_ERROR_AFTER_LAUNCH")

    if not args.non_interactive:
        steps = [
            (
                "SPA_SECOND_CIRCLE",
                "In KARETA, navigate Home → Services → Community → Masters → More → Back, then repeat the circle. Confirm no second-circle skeleton/loading regression."
            ),
            (
                "QR_NATIVE",
                "Open Scan QR in KARETA. Scan a valid KARETA QR and confirm it opens the expected internal route."
            ),
            (
                "VIN_NATIVE",
                "Open Garage vehicle form. Tap Scan VIN and scan a real/test 17-character VIN. Confirm the VIN field is populated correctly."
            ),
            (
                "CAMERA",
                "Exercise camera/file capture path and confirm permission/capture/return to SPA works."
            ),
            (
                "GEO",
                "Exercise location/nearby path. Confirm permission flow and a valid location result."
            ),
            (
                "BLUETOOTH_ELM_CONNECT",
                "Pair ELM327 in Android first, then in KARETA run device list → connect. Confirm adapter becomes connected."
            ),
            (
                "ELM_INIT",
                "Run ELM initialization. Confirm adapter identity/protocol and ECU ready state are shown."
            ),
            (
                "ELM_SNAPSHOT",
                "Run diagnostic snapshot. Confirm RPM/speed/coolant/voltage and, where supported, DTC/VIN are returned without app crash."
            ),
            (
                "OBD_OFFLINE_SYNC",
                "With a selected vehicle, capture a diagnostic session offline, restore network, sync, and confirm the session appears in history with vehicleId."
            ),
        ]
        for key, instruction in steps:
            result = prompt_step(key, instruction)
            metadata["manual"][key] = result
            screenshot(adb, serial, evidence / f"manual_{key.lower()}.png")
            step_log = run([adb, "-s", serial, "logcat", "-d", "-v", "threadtime"], check=False).stdout
            write_text(evidence / f"manual_{key.lower()}_logcat.txt", step_log)
            if result.upper().startswith("FAIL"):
                break

    final_log = run([adb, "-s", serial, "logcat", "-d", "-v", "threadtime"], check=False).stdout
    write_text(evidence / "99_logcat_final.txt", final_log)
    final_fatals = fatal_lines(final_log)
    metadata["automated"]["finalFatalErrors"] = "FAIL" if final_fatals else "PASS"
    metadata["completedAtUtc"] = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")

    manual_values = list(metadata["manual"].values())
    if args.non_interactive:
        verdict = "AUTOMATED_PREFLIGHT_PASS" if not final_fatals else "FAIL"
    elif final_fatals or any(str(v).upper().startswith("FAIL") for v in manual_values):
        verdict = "FAIL"
    elif any(str(v).upper().startswith("SKIP") for v in manual_values):
        verdict = "PARTIAL"
    else:
        verdict = "PASS"
    metadata["verdict"] = verdict
    write_text(evidence / "result.json", json.dumps(metadata, indent=2, ensure_ascii=False))

    print(f"PHYSICAL_ACCEPTANCE: {verdict}")
    print(f"EVIDENCE_DIR: {evidence}")
    return 0 if verdict in ("PASS", "AUTOMATED_PREFLIGHT_PASS", "PARTIAL") else 1

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"PHYSICAL_ACCEPTANCE: FAIL — {exc}", file=sys.stderr)
        raise

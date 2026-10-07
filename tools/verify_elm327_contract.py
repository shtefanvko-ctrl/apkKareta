#!/usr/bin/env python3
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ELM_PATH = ROOT / "app/src/main/java/kz/kareta/app/Elm327Manager.java"
MAIN_PATH = ROOT / "app/src/main/java/kz/kareta/app/MainActivity.java"
MANIFEST_PATH = ROOT / "app/src/main/AndroidManifest.xml"

ELM = ELM_PATH.read_text(encoding="utf-8")
MAIN = MAIN_PATH.read_text(encoding="utf-8")
MANIFEST = MANIFEST_PATH.read_text(encoding="utf-8")
errors = []

def require(text: str, token: str, label: str) -> None:
    if token not in text:
        errors.append(f"{label}: missing {token}")

def require_regex(text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.MULTILINE | re.DOTALL):
        errors.append(f"{label}: pattern not found: {pattern}")

def require_order(text: str, tokens: list[str], label: str) -> None:
    positions = []
    start = 0
    for token in tokens:
        pos = text.find(token, start)
        if pos < 0:
            errors.append(f"{label}: missing ordered token {token}")
            return
        positions.append(pos)
        start = pos + len(token)
    if positions != sorted(positions):
        errors.append(f"{label}: sequence out of order")

# Transport and connection invariants.
require(ELM, 'UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")', "SPP UUID")
require(ELM, 'private static final int CONNECT_TIMEOUT_MS = 15000;', "connect timeout")
require(ELM, 'adapter.getBondedDevices()', "paired device discovery")
require(ELM, 'out.put("pairedOnly", true);', "paired-only contract")
require(ELM, 'BluetoothAdapter.checkBluetoothAddress(address)', "MAC validation")
require(ELM, 'device.createRfcommSocketToServiceRecord(SPP_UUID)', "RFCOMM socket")
require(ELM, 'future.get(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)', "connect timeout enforcement")
require(ELM, 'future.cancel(true);', "connect timeout cancellation")
require(ELM, 'candidate.close()', "failed socket close")
require(MANIFEST, 'android.permission.BLUETOOTH_CONNECT', "Bluetooth permission")
require(ELM, 'Build.VERSION.SDK_INT < 31', "pre-Android-12 compatibility")
require(ELM, 'Manifest.permission.BLUETOOTH_CONNECT', "Android-12 connect guard")
require(ELM, 'throw new SecurityException("BLUETOOTH_CONNECT_REQUIRED")', "permission failure contract")

# State machine.
for state in ["DISCONNECTED", "CONNECTING", "ADAPTER_CONNECTED", "READY", "ERROR"]:
    require(ELM, state, "ELM state")
require(ELM, 'state = State.CONNECTING;', "connect transition")
require(ELM, 'state = State.ADAPTER_CONNECTED;', "adapter transition")
require(ELM, 'state = State.READY;', "ready transition")
require(ELM, 'state = State.ERROR;', "error transition")
require(ELM, 'state = State.DISCONNECTED;', "disconnect transition")
require(ELM, 'if (state != State.READY)', "ready guard")

# ELM initialization order and ECU readiness proof.
init_sequence = [
    'commandDirect("ATZ", 5000)',
    'commandDirect("ATE0", 2500)',
    'commandDirect("ATL0", 2500)',
    'commandDirect("ATS0", 2500)',
    'commandDirect("ATH0", 2500)',
    'commandDirect("ATSP0", 3500)',
    'commandDirect("ATI", 2500)',
    'commandDirect("ATDP", 3000)',
    'commandDirect("0100", 5000)',
]
require_order(ELM, init_sequence, "ELM init")
require(ELM, 'hasModeResponse(supported, "4100")', "ECU readiness response")
require(ELM, 'lastError = "ECU_NO_RESPONSE";', "ECU no-response state")

# Command channel must remain allowlisted.
required_allowlist = {
    "ATI","ATZ","ATE0","ATL0","ATS0","ATH0","ATSP0","ATDP","ATRV",
    "0100","0105","010C","010D","03","0902",
}
validate_match = re.search(
    r'private String validateCommand\(String command\).*?switch \(value\) \{(.*?)default:',
    ELM,
    re.DOTALL,
)
if not validate_match:
    errors.append("command allowlist: validateCommand switch missing")
else:
    cases = set(re.findall(r'case\s+"([^"]+)"\s*:', validate_match.group(1)))
    missing = sorted(required_allowlist - cases)
    extra = sorted(cases - required_allowlist)
    if missing:
        errors.append("command allowlist: missing " + ", ".join(missing))
    if extra:
        errors.append("command allowlist: unexpected commands " + ", ".join(extra))
require(ELM, 'throw new IllegalArgumentException("Команда запрещена Native API 6.")', "command rejection")
require(ELM, 'clamp(timeoutMs, 800, 8000)', "command timeout clamp")

# Snapshot semantics used by SPA/API.
snapshot_sequence = [
    'commandDirect("010C", 2600)',
    'commandDirect("010D", 2600)',
    'commandDirect("0105", 2600)',
    'commandDirect("ATRV", 2600)',
]
require_order(ELM, snapshot_sequence, "live snapshot")
for token in [
    'putNumber(out, "rpm",',
    'putNumber(out, "speedKph",',
    'putNumber(out, "coolantC",',
    'putNumber(out, "voltageV",',
    'out.put("adapterName", deviceName);',
    'out.put("adapterAddress", deviceAddress);',
]:
    require(ELM, token, "snapshot payload")

full_snapshot = [
    'commandDirect("03", 4500)',
    'commandDirect("0902", 5500)',
    'out.put("dtcCodes", parseDtc(dtcRaw));',
    'out.put("vin", parseVin(vinRaw));',
]
require_order(ELM, full_snapshot, "full snapshot")
require(ELM, 'vin.length() < 17', "VIN length")
for invalid in ["I", "O", "Q"]:
    require(ELM, f"c != '{invalid}'", "VIN alphabet")

# Error semantics from the transport.
for token in [
    'upper.contains("UNABLE TO CONNECT")',
    'upper.contains("BUS INIT")',
    'upper.contains("CAN ERROR")',
    'upper.contains("STOPPED")',
    'upper.trim().endsWith("?")',
]:
    require(ELM, token, "ELM response errors")
require(ELM, 'if (raw.isEmpty()) throw new Exception("Нет ответа ELM327: " + command);', "empty response")

# Native API wiring for ELM commands.
bridge_commands = [
    "elmStatus","elmDevices","elmConnect","elmReconnectLast","elmDisconnect",
    "elmInit","elmCommand","elmSnapshot","elmLiveSnapshot","openBluetoothSettings",
]
for command in bridge_commands:
    require(MAIN, f'case "{command}":', "MainActivity ELM bridge")
require(MAIN, 'payload.optInt("timeoutMs", 2500)', "ELM command timeout payload")
require(MAIN, 'Settings.ACTION_BLUETOOTH_SETTINGS', "Bluetooth settings bridge")

if errors:
    print("ELM327_SOURCE_CONTRACT: FAIL")
    for error in errors:
        print("- " + error)
    sys.exit(1)

print("ELM327_SOURCE_CONTRACT: PASS")
print("transport=classic_spp")
print("connect_timeout_ms=15000")
print("paired_only=true")
print(f"allowlisted_commands={len(required_allowlist)}")
print("init=ATZ,ATE0,ATL0,ATS0,ATH0,ATSP0,ATI,ATDP,0100")
print("snapshot=010C,010D,0105,ATRV,03,0902")
print("hardware_acceptance=NOT_RUN")

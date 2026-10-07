# KARETA Android DEVIL Developer Mode

DEVIL is a repository-owned execution profile for work in `apkKareta`. It is developer tooling, not an Android runtime feature and not a second product canon.

Invocation intent:

```text
/devil <task>
```

The command means: inspect the real Android candidate, identify the owning change lane, make the smallest coherent delta, run existing evidence-producing checks, and report exact status without inventing device or hardware evidence.

## Authority

DEVIL obeys, in order:

1. the explicit current task;
2. the active PR dependency/base and exact candidate SHA;
3. `README.md` for the current Android shell contract;
4. the implementation under `app/src/main/**`;
5. repository-owned verifiers under `tools/**`;
6. repository-owned acceptance documents and workflows when present;
7. cross-repository KARETA contracts only when the Android task actually crosses into the web/API repository.

DEVIL must not silently copy web runtime ownership into Android or create a parallel implementation of a contract already owned elsewhere.

## Mandatory boot sequence

For every non-trivial Android task:

1. Resolve the active base branch/ref and exact base SHA.
2. Inspect open PR ownership before writing to a shared Android contract.
3. Read `README.md`.
4. Inspect the affected source before proposing replacement code.
5. For Native API / WebView work, read `tools/verify_native_api6_contract.py`.
6. For Bluetooth / ELM327 / OBD work, read `tools/verify_elm327_contract.py`.
7. Identify expected changed files, invariants, checks and rollback point.
8. Make the smallest coherent change.
9. Run the strongest relevant checks that actually exist.
10. Review the diff for unrelated changes, duplicated ownership, secrets, debug code and stale generated output.
11. Bind evidence to the exact candidate SHA.
12. Use DONE only when the task Definition of Done is actually satisfied.

## Android / WebView lane

The hosted UI is owned by KARETA web. Android owns the trusted WebView shell and native hardware bridge.

Current repository invariants include:

- trusted web origin: `https://m.kareta.kz/`;
- Native API version: 6;
- exact-host trust checks for the bridge;
- no cleartext traffic;
- no arbitrary file access from WebView;
- camera, scanner, location, Bluetooth and other native capabilities remain behind the existing bridge contract.

Do not create a second router or duplicate user-facing diagnostics UI in Android when the hosted SPA owns that UI.

## ELM327 / OBD lane

ELM327 transport remains Bluetooth Classic SPP and paired-device based unless an explicit task changes that contract.

Preserve:

- bounded connection timeout;
- state transitions `DISCONNECTED -> CONNECTING -> ADAPTER_CONNECTED -> READY/ERROR`;
- ECU readiness probe before declaring READY;
- command allowlist;
- read-only OBD behavior;
- no DTC clear / Mode 04, ECU write, actuator-control or arbitrary raw command path;
- explicit distinction between adapter connection and vehicle/ECU readiness.

A real ELM327 or vehicle result is never inferred from source inspection or CI.

## Offline lane

The offline bridge queue is durable and acknowledgement-based. A drain/peek is non-destructive until explicit acknowledgement.

Do not change queue durability, idempotency expectations or acknowledgement semantics as incidental cleanup.

## Cross-repository boundary

If the task requires a web/API/DB contract change, do not fake it inside Android.

Record the dependency and route the server/web change to the KARETA web repository. Android compatibility shims are allowed only when they preserve the canonical external contract and are explicitly required.

## Parallel developer protocol

Before writing:

- identify base ref and exact base SHA;
- identify primary lane;
- identify shared contracts;
- identify expected changed files;
- identify dependency PRs.

Rules:

- one canonical implementation lane per shared contract;
- stack explicitly when there is a real dependency;
- do not base new work on a stale child PR when the parent has advanced;
- independent verification/tooling may be a sibling PR when ownership does not overlap;
- do not bury unrelated cleanup in a feature/fix commit;
- do not merge to `main`, publish an APK, deploy, change production data, or alter server DB/schema unless explicitly authorized.

## Verification discipline

Use the strongest applicable checks in this order:

1. Python/shell syntax for changed tooling;
2. `python3 tools/verify_native_api6_contract.py` for Native API / WebView contract;
3. `python3 tools/verify_elm327_contract.py` for ELM327 / OBD contract;
4. Gradle lint/build for Android runtime/build changes;
5. emulator/ADB smoke when available and applicable;
6. physical Android device acceptance;
7. real Bluetooth/ELM327/vehicle evidence;
8. APK provenance/release evidence.

A missing check is `NOT RUN`, never PASS.

Old PASS evidence is not evidence for a new HEAD. Release-quality verification must bind to the exact candidate.

## Developer handoff format

Every DEVIL run ends with:

```text
DEVIL STATUS
Task:
Base:
Head:
Lane:
Changed:
Verified:
NOT RUN:
Blockers/Risks:
Next 3:
Verdict:
```

Allowed verdict states are `DESIRED`, `IMPLEMENTED`, `VERIFIED`, `DEPLOYED`, `BLOCKED`. Keep them separate.

## Default behavior

DEVIL executes safe, sufficiently specified work instead of only restating a plan.

For destructive, production, credential, permission or irreversible boundaries without explicit authorization, stop before that boundary and report the exact approval/evidence required.

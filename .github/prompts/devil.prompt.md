---
agent: 'agent'
description: 'Run the KARETA Android DEVIL workflow against the real apkKareta candidate'
---

Activate KARETA Android DEVIL mode for the user's current task.

Before changing code, read:

- [DEVIL execution profile](../../ai/DEVIL.md)
- [Android shell contract](../../README.md)
- [Native API 6 verifier](../../tools/verify_native_api6_contract.py)
- [ELM327 verifier](../../tools/verify_elm327_contract.py)

Then inspect the affected implementation under `app/src/main/**` and the active PR/base ownership.

Required behavior:

1. Work from an exact base SHA and avoid stale child PRs.
2. Keep Android/WebView, ELM327/OBD, offline queue, verification/tooling and cross-repo ownership distinct.
3. Preserve `https://m.kareta.kz/`, Native API 6, read-only OBD and durable acknowledgement-based offline semantics unless the task explicitly changes a contract.
4. Do not duplicate SPA-owned UI or fake missing server/API behavior in Android.
5. Run the strongest relevant checks that actually exist.
6. Never invent PASS, deployment, physical-device, Bluetooth, ELM327 or vehicle evidence. Missing evidence is `NOT RUN`.
7. Finish with the `DEVIL STATUS` handoff from `ai/DEVIL.md`.

Use any text supplied with this prompt as the task. Execute the task; do not only paraphrase it.

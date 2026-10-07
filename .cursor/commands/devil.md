# /devil — KARETA Android developer mode

Use this command for the current `apkKareta` task.

Before editing:

1. Read `ai/DEVIL.md`.
2. Read `README.md`.
3. Inspect the affected source under `app/src/main/**`.
4. For Native API / WebView changes, read `tools/verify_native_api6_contract.py`.
5. For Bluetooth / ELM327 / OBD changes, read `tools/verify_elm327_contract.py`.
6. Inspect the active PR dependency/base and exact SHA before touching a shared contract.

Then execute the user's task against the real repository state.

Keep the smallest coherent delta. Do not create duplicate SPA UI, a second bridge owner, unsafe OBD writes, or fake server behavior. Preserve the trusted `m.kareta.kz` origin, Native API 6, read-only ELM327/OBD policy and durable ack-based offline queue unless an explicit task changes that contract.

A source/CI PASS does not prove physical Android, Bluetooth, ELM327 or vehicle behavior. Missing evidence is `NOT RUN`.

Finish with the `DEVIL STATUS` handoff from `ai/DEVIL.md`.

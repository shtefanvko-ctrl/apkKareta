# KARETA Android Agent Kernel

- Android is the WebView/device bridge; web UI remains owned by the configured KARETA web origin.
- Preserve Native API 6 unless an explicit versioned contract change is approved.
- Do not add duplicate native UI for functionality already owned by the web app.
- Treat permissions, Bluetooth/ELM327, signing, trusted origins and offline sync as high-risk.
- Never commit signing keys, local.properties, device credentials or tunnel secrets.
- Use `.agents/skills/`; run `android-devil-review` for Native API, permissions, ELM transport, signing/release identity or broad MainActivity changes.

# KARETA.KZ — Android shell

Current candidate: **1.4.4 (10)** (draft native OCR integration). Application ID: `kz.kareta.app`. Web origin: `https://s.kareta.kz/`. Native API **7** (compatible with Web minimum API 6).

The native shell owns hardware/device capabilities; all UI and vehicle saving remain in the web SPA. Android **7** adds optional `documentOcr` capability and command `recognizeDocumentImage` with `{imageBase64, catalog}`. It returns **only** `{fields, engine}`. It never uploads or persists registration document photos and does not send raw document text, owner name or IIN to JavaScript. The embedded Latin ML Kit model provides offline OCR for Latin VIN/plates/brands; Cyrillic/Kazakh recognition remains a known limitation. Users must review every proposed field before garage saving.

Camera, Bluetooth ELM327, route and offline queue contracts remain unchanged. No staging deployment, production release, signing change or Play Store upload occurs from this draft PR. Build with GitHub Actions lintDebug/assembleDebug and validate on a physical Android device before release.

# Autonomous engineering runs

## 2026-09-29 — P1 contact permission minimization

- Repository: `shtefanvko-ctrl/apkKareta`
- Base branch/SHA: `main` / `082e2c8ae3e138f680f61c494eb38c142d21b731`
- Working branch: `verification/contact-picker-no-read-contacts`
- Problem reproduced: manifest declared `READ_CONTACTS` even though `pickContact` already used a user-driven system `ACTION_PICK` flow for one phone row.
- Increment: remove broad contacts permission while preserving the existing Native API command and selected-row result contract.
- Acceptance: no `READ_CONTACTS` in manifest or Native permission mapping; `pickContact` remains system picker based; build verification fails if either condition regresses; lint/build CI must pass.
- Verification evidence expected: `:app:verifyContactPickerContract`, `:app:lintDebug`, `:app:assembleDebug`, PR CI final SHA, and device/emulator install plus selection/cancel flow.
- Rollback: revert the increment commit. Do not restore `READ_CONTACTS` unless a separately approved broad-address-book feature demonstrates a real need.
- Current limitation at implementation time: connected development device `KOMPUTER` is offline, so device/emulator verification must remain explicitly pending until an install target is available.

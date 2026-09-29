# Feature Map

## Native contact picker — permission-minimized single selection

- Entry point: trusted WebView origin `https://s.kareta.kz` → Native API 6 command `pickContact`.
- Android implementation: `MainActivity.pickContact()` launches `Intent.ACTION_PICK` with `ContactsContract.CommonDataKinds.Phone.CONTENT_URI`.
- States: idle → picker open → selected / cancelled / picker unavailable / selected-row read failed.
- Data contract on success: selected contact display name and selected phone number only.
- Data scope: no background address-book scan; no `READ_CONTACTS`; access is limited to the URI delegated by the system contact picker.
- UI contract: Android system contact picker. KARETA does not replace it with a custom full-address-book browser.
- Native API compatibility: `requestPermission({permission:"contacts"})` remains supported and returns granted without prompting because broad permission is not required.
- Dependencies: an installed Android contacts picker/provider capable of handling `ACTION_PICK`.
- Regression check: `gradle :app:verifyContactPickerContract`.
- Negative paths: user cancellation returns `CANCELLED`; missing picker returns `CONTACT_PICKER_UNAVAILABLE`; selected-row read failure returns `CONTACT_READ_FAILED`.
- Privacy boundary: selected contact data is returned only to the already origin-restricted Native Bridge call; this increment adds no background upload or marketing processing.

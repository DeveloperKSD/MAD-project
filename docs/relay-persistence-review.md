# Focused persistence review

Branch verified as `feature/ria` before edits. Reviewed the existing uncommitted work without resetting, staging, committing, or pushing it. This review changes no BLE/mesh behavior, identity storage, wire encryption, or navigation.

## Confirmed issues and fixes

1. **Concurrent first-open race (availability/integrity boundary).** Repository mutexes were instance-local. A second fresh repository could observe the first instance's newly generated Keystore key before its database/authenticated marker existed and incorrectly latch a storage error. Six concurrent repository opens reproduced `LocalHistoryException` before the fix. A process-wide coroutine mutex now serializes the complete open/marker-validation path across instances. Existing orphan-key/file checks still fail closed; no replacement/recovery is introduced. A regression opens six repositories together, then verifies a saved name through every instance.
2. **Sensitive drafts in Android saved state (privacy).** The online message draft, unsaved contact name, and search query used `rememberSaveable`. These strings could leave the encrypted-storage boundary in Android saved-instance-state bundles. They now use memory-only Compose state. Three restoration tests enter sensitive strings, recreate saved-state registries, and assert empty editable fields. Persisted names/history still come from the encrypted repository. Unfinished drafts/search/name entry reset on activity recreation instead of being serialized in plaintext.
3. **WAL test blind spot (verification).** The raw-file plaintext scan ran only after closing the last Room connection, which can checkpoint/delete WAL/SHM. It now asserts live WAL and SHM exist, scans every file while open, closes, and scans again. It checks message text, contact label, and sensitive relay token. This is a test correction, not a claim that plaintext was found.

4. **Cancellation during the initial outgoing commit (status accuracy).** The initial save was outside the send error handler. Lifecycle cancellation at its commit/return boundary could leave a stored QUEUED row and later show it as Sending without any active publication. The initial enqueue now completes under a non-cancellable context inside the handler, explicitly checks cancellation before calling transport, and records UNKNOWN on interruption. A regression pauses the outgoing save, stops the ViewModel, releases the save, and verifies durable UNKNOWN with no transport send and no storage error.

Review edits: `RoomInternetHistoryRepository.kt`, `RelayApp.kt`, `OnlineChatScreen.kt`, `RelayViewModel.kt`, `RelayViewModelTest.kt`, `InternetHistoryStorageTest.kt`, new `SensitiveUiStateTest.kt`, the corresponding old restoration expectation in `RelayUiTest.kt`, and this report/documentation.

## Findings without changes

- Contact/conversation composite keys and canonical-rumor unique indexes prevent duplicates. Transactions verify a sender-copy duplicate before merging; a repeat cannot overwrite recipient relay acceptance. Tests include repeats after repository/VM recreation.
- Room receives ciphertext BLOBs for bodies, contact names, and relay URLs. No plaintext file/log/temp write was found in the reviewed persistence, ViewModel, or coordinator paths. Strings necessarily exist in UI/process memory; this is not protection against a compromised OS, heap inspection, keyboard software, or user screenshots.
- AES-256-GCM uses provider-generated fresh 96-bit IVs, a versioned envelope, 128-bit tags, and authenticated row/domain metadata. Its key alias differs from Nostr identity storage. Real Android Keystore tests cover round-trip, randomized ciphertext, tampering, wrong bindings, swapped rows, missing keys, and replacement keys.
- The database and sidecars/marker are under `noBackupFilesDir`; both backup-rule generations additionally exclude the history directory and named database companions. The manifest references both rules. There is no private-key database column or export path.
- Missing keys/files, wrong keys, corrupt envelopes/metadata, and damaged SQLite fail visibly without destructive reset. The custom SQLite corruption callback prevents default deletion. The repository does not use destructive migration. Schema v1 is exported and exercised by actual Room creation/reopen. Future schema changes require explicit migrations and migration tests; no later version exists to migrate today.
- Database/Keystore work runs on IO. The coordinator's durability hook runs on its worker outside the user-callback lock, before network publication, and propagates failure. Initial loading precedes inbox startup. Rapid sends are gated; repository/VM mutexes serialize mutations; Room transactions protect duplicate merges and status updates.
- Outgoing content is encrypted/saved before sending, and the canonical rumor is durably bound before publication. Failures block subsequent Internet mutations and show storage errors rather than claiming success. Pre-publication storage failure is covered by a coordinator test that verifies no message EVENT was sent.
- Pending messages restore as outcome unknown. Sender copies, socket connectivity, publication acceptance, delivery, and reading are not conflated. No automatic resend or transport switching was added.
- Existing activity/navigation tests retain Nearby discovery/settings controls. Source comparison shows this review did not edit BLE, mesh, or either identity implementation.

## Verification scope and remaining risks

The manual production force-stop/relaunch check from the implementation stage exercised the default production repository path and preserved/reopened a named contact. It did **not** exchange private messages. `HistoryViewModelRestartTest` uses the actual Room repository, disk database, Android Keystore, and new ViewModel/repository instances, with a controlled transport supplying incoming and outgoing messages. It is a close/reopen test, not an OS process-kill test. Independent coordinator/NIP-17 tests cover transport/crypto behavior. No live relay availability or physical BLE/multi-hop result is claimed.

The per-attempt publication rows are saved with the completed send result. A kill or storage failure after relay acceptance but before that result is committed leaves an unknown outcome; intermediate acknowledgments are not a crash-durable event journal. This is conservative and never restores delivery confirmation. The body remains saved.

Field encryption leaves public keys, IDs, direction, timestamps, transport, and status as structured metadata. Authentication detects modified protected rows but provides no whole-database rollback/deletion detection. There is no safe key recovery or cloud restore. Losing a key requires recovery work outside this stage; files are preserved. Interruption during initial key/database creation may leave an orphan that intentionally blocks initialization. The initialization mutex is process-local; the manifest runs these components in one application process.

History is loaded into memory and snapshots are reloaded after mutations; large histories may need pagination later. Relay configuration remains session-only. BLE history is unchanged. Sensitive unfinished UI drafts now reset on recreation. Device-level backup/transfer was checked through placement/configuration/tests, not an actual OEM device-transfer operation. Emulator Keystore tests do not attest hardware-backed keys.

## Upcoming commit contents

Do not omit untracked source files merely because `git diff --stat` does not show them. Include the persistence package's four Kotlin files, all Relay UI source files, production factory integration in `MainActivity.kt`, coordinator durability changes and its unit tests, the earlier `Nip17.kt` rumor-ID change, `app/build.gradle.kts`, and the exported `app/schemas/in.yashd.bitchat.data.internet.InternetHistoryDatabase/1.json`.

Include `src/sharedTest/.../TestHistoryRepository.kt`, the unit tests under `src/test/.../ui/relay`, all instrumentation tests under `src/androidTest/.../data/internet` and `src/androidTest/.../ui/relay` (including the new privacy tests), backup XML, and the persistence/UI/review documents. The test fixture is test-only; production must continue injecting Room explicitly.

For the combined uncommitted UI milestone also include its Relay drawable vectors, packaged and documented Hugeicons licenses, design reference, branding strings, and existing service branding changes. Review these earlier changes as their own scope; this review did not modify them. The unrelated `.idea/markdown.xml` and `.idea/misc.xml` changes are not required for the feature. Generated APKs, build reports, `.kotlin` compiler-session files, emulator data, and temporary scripts are not commit inputs.

## Final verification

Command: `./gradlew test assembleDebug connectedDebugAndroidTest` — **BUILD SUCCESSFUL in 1m 28s**.

- **105 unit tests**, 0 failures, 0 errors, 0 skipped. Includes the new durable-enqueue interruption regression.
- **49 Android instrumented tests**, 0 failures, 0 errors, 0 skipped on `Medium_Phone(AVD)` Android 17. Includes 23 real Room/Keystore storage tests, the Room-backed ViewModel reopen test, three new saved-state privacy tests, and existing UI/navigation/identity tests.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- `git diff --check` passed. Branch remains `feature/ria`. No commits or pushes.
- Before the fix, the targeted concurrent-open test failed with `LocalHistoryException`, reproducing the race. Intermediate runs exposed an outdated search-restoration expectation and a test-helper compile error; both were corrected before the passing final run.
- No new physical BLE, public-relay exchange, OEM transfer, or OS process-kill message-exchange test was performed in this review. The earlier manual contact force-stop check and the actual-code automated encryption/reopen tests are distinguished above.

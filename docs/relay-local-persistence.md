# Relay secure local Internet history

Implemented on `feature/ria`, preserving the existing uncommitted UI and BLE work. No commit or push was made. The existing single Chats / Discover / Settings navigation and design remain intact.

## Storage design

Room schema v1 is exported in `app/schemas`. Contacts and conversations use `(owner public key, peer public key)` primary keys, so adding the same public ID reuses the existing conversation and preserves its name when no replacement name is supplied. Messages have stable local record IDs and a unique owner/canonical Nostr rumor ID index. The canonical rumor ID also deduplicates repeated gift wraps and independently wrapped sender copies. A sender copy arriving before local publication bookkeeping is merged transactionally after its body, peer, and direction are verified.

Tables are `contacts`, `conversations`, `messages`, `publications`, and `storage_metadata`. Foreign keys prevent orphan messages. Publication attempts retain recipient/sender targets, event IDs, and exact stages. Recipient relay acceptance remains **delivery unconfirmed**; receiving a sender copy never implies recipient delivery. Interrupted queued messages restore as **outcome unknown**, without automatic retransmission.

`InternetHistoryRepository` separates storage from the ViewModel. The Room implementation performs transactions, database operations, and Keystore work on IO, serialized with a mutex. Initial loading completes before inbox connections start. New contacts and outgoing messages are saved before the UI reports success; the canonical rumor is durably bound before network publication. Incoming authenticated messages are saved before display. The ViewModel reloads snapshots after mutations and closes storage on teardown. There is no production in-memory fallback on storage failure.

## Encryption and failure behavior

Message bodies, optional display names, and relay addresses are encrypted using AES-256-GCM with fresh random 96-bit IVs and 128-bit authentication tags. Versioned ciphertext envelopes use the distinct Android Keystore alias `relay.internet.history.aes.v1`; the existing identity key and identity storage are untouched. Additional authenticated data binds ciphertext to its domain and row identity. Message bindings include owner, peer, record ID, direction, timestamp, canonical rumor ID, transport, and publication summary status. Updating authenticated metadata reseals the body. Publication bindings include target, position, event ID, and stage. This detects ciphertext swaps and attempts to forge a valid-looking acceptance status.

An authenticated key-check record verifies that the database and Keystore key match. Missing keys, orphan keys/files, damaged ciphertext, invalid metadata, and SQLite corruption surface a fixed safe local-storage error. No database deletion, destructive migration, key replacement, or silent empty-history reset is performed. The custom SQLite corruption callback explicitly preserves files rather than using SQLite's default deletion recovery. Retry requires reopening the app; unresolved key loss has no recovery mechanism in this milestone.

The database, WAL, sidecars, and encryption metadata reside in `noBackupFilesDir/relay_history`. Backup XML additionally excludes the corresponding file directory and database/sidecar names from cloud backup and device transfer. Keys are nonexportable Keystore handles. No private key or plaintext body is written to database rows.

This is field encryption, not full-database encryption: public keys, message IDs, timestamps, direction, transport, and status remain structured metadata. Decrypted content exists in application memory while displayed. Uninstalling/clearing app data or permanently losing the key loses access to local history. No cloud recovery, export, synchronization, or storage migration from an already terminated RAM-only session is provided.

## UI and scope

Saved names/history reopen without re-entering a public ID. Loading, unavailable-storage, and genuinely empty lists have distinct states. Storage failures block Internet mutations and remain distinct from network errors; Nearby remains accessible. Text now explains encrypted device-local storage and exclusion from backups instead of claiming history disappears on restart.

BLE transport, MeshRuntime, BLE identity, BLE history behavior, Nostr wire encryption, and navigation are unchanged. Relay setup configuration remains session-only and must be entered again after process restart; historical relay acceptance does not restore live connection readiness. No automatic transport switching or private-message resend was added.

## Changed files

- `data/internet/HistoryModels.kt`, `HistoryCipher.kt`, `InternetHistoryDatabase.kt`, `RoomInternetHistoryRepository.kt`: modular repository, schema, encryption and conservative failure handling.
- `app/build.gradle.kts`, `app/schemas/.../1.json`: Room 2.8.5, KSP 2.3.12, exported schema and shared test-fixture source directories.
- `ui/relay/RelayViewModel.kt`, `MainActivity.kt`: explicit production repository injection, loading and persisted conversation/message lifecycle.
- `nostr/NostrMessagingCoordinator.kt`: optional pre-publication durability hook; default messaging behavior and wire format remain unchanged.
- `ui/relay/RelayApp.kt`, `OnlineChatScreen.kt`, `InternetSetupScreen.kt`: storage states, awaiting contact saves, corrected notices and action availability.
- `res/xml/backup_rules.xml`, `data_extraction_rules.xml`: history exclusions while preserving existing exclusions.
- `InternetHistoryStorageTest.kt`, `HistoryViewModelRestartTest.kt`, existing ViewModel/coordinator/Compose tests and shared `TestHistoryRepository.kt`: real Room/Keystore restart, duplication, corruption, authentication, status and UI regression coverage. The memory repository is test-only.
- This report and the updated earlier UI walkthrough. Preexisting IDE, BLE-host, icon and design changes remain uncommitted.

## Restart walkthrough

1. Install `app/build/outputs/apk/debug/app-debug.apk`. Open New Chat → Chat Online, paste a valid public npub and supply an optional name. Start Chat and return to Chats.
2. Add the same npub again: it should reopen/relabel the existing conversation, not create another row.
3. With working user-supplied discovery/inbox relays and an inbox announcement, exchange Internet messages. Recipient relay acceptance must say delivery is unconfirmed.
4. Force-stop and reopen (`adb shell am force-stop in.yashd.bitchat`, then launch the app). Complete onboarding again if shown. The name and saved messages must return; opening the row must not request its npub again. Do not uninstall or clear app data for this test.
5. Re-enter relay setup to receive/send new Internet messages. Historical statuses must not claim current connectivity. Verify Nearby controls remain available independently.

Use two physical BLE devices for radio/mesh validation and two compatible NIP-17 clients with working relay infrastructure for live end-to-end messaging. Automated persistence tests use controlled transports; they do not verify public relay availability or physical BLE exchange.

Official references: [Room releases](https://developer.android.com/jetpack/androidx/releases/room), [KSP 2.3.12](https://github.com/google/ksp/releases/tag/2.3.12), [Android Keystore](https://developer.android.com/privacy-and-security/keystore), [backup behavior](https://developer.android.com/identity/data/autobackup).

## Verification results

Final command: `./gradlew test assembleDebug connectedDebugAndroidTest` — **BUILD SUCCESSFUL in 1m 26s**.

- **104 unit tests**, 0 failures, 0 errors, 0 skipped. The preceding unit-only run executed successfully in 16 seconds; the final combined run reused those passing unit results.
- **45 Android instrumented tests**, 0 failures, 0 errors, 0 skipped on `Medium_Phone(AVD)` Android 17, including 22 storage/encryption tests and the Room-backed ViewModel restart test.
- Debug APK built at `app/build/outputs/apk/debug/app-debug.apk` and installed successfully on the emulator.
- Coverage includes save/load/update/reopen, owner isolation, contact/conversation/message uniqueness, pending/accepted status restoration, sender-copy races, repeated incoming rumors, raw stored-data checks, GCM round-trip/tampering/row swaps, missing/replaced keys, missing database, corrupt SQLite preservation, authenticated metadata, backup exclusions, storage-error UI, and existing navigation/BLE controls.
- Public-relay end-to-end exchanges and physical BLE/mesh transport were not verified. Controlled messaging backends and real Room/Keystore exercised persistence without publishing private test messages.
- Manual production-app emulator check passed: created a synthetic named Internet contact, force-stopped/relaunched, confirmed its name in Chats, and reopened it without another public-ID prompt. No message or public announcement was sent. The synthetic `Persistence Review` conversation remains in the installed debug app for review.
- `git diff --check` passed; branch remains `feature/ria`. No commits or pushes.

## Subsequent focused review

See [correctness/security review](relay-persistence-review.md) for the reproduced concurrent-initialization fix, durable-enqueue cancellation handling, live WAL/SHM verification, and removal of sensitive UI drafts from Android saved state. Unfinished message/name/search drafts now reset on activity recreation; saved encrypted contacts/history still reload normally. The earlier verification totals above describe the implementation stage, not this later review.

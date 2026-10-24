# Repository audit and implementation log

What this repo does: Relay is an Android chat app with independent Bluetooth mesh and encrypted Nostr messaging. Assumed load: one user per phone, one app process with UI/background/Bluetooth threads, a small nearby group, and history growing over months.

## Repository map

- Build: one Gradle Android module; Compose, Room, OkHttp, Bouncy Castle, secp256k1.
- Entry points: `MainActivity` → `RelayHome`; `MeshForegroundService` → `MeshRuntime`.
- Internet: `RelayViewModel` → `NostrMessagingCoordinator` → relay sockets; Room/Keystore persistence.
- Tests: protocol vectors, mock relays, Room/Keystore instrumentation, Compose instrumentation.
- No payment flow or checked-in CI/deployment workflow found.

Paths below are relative to `app/src/main/java/in/yashd/bitchat/` unless stated. Line numbers describe the original audit snapshot, before implementation.

## Must fix

### 1. Endless announcement replies — `mesh/MeshRuntime.kt:153–164,190–196`
- **What this is:** Announcements share names and public keys.
- **Problem:** Every received announcement creates a fresh reply; two phones can reply forever because each packet has a new ID.
- **Fix:** Remove the unconditional reply; retain link-ready and periodic announcements.
- **If we skip it:** Idle phones waste radio capacity and battery.

### 2. Long Nearby messages crash — `mesh/MeshRuntime.kt:107–138`; `mesh/PacketCodec.kt:15–23`
- **What this is:** Text is packaged as JSON, optionally encrypted, then signed.
- **Problem:** The UI accepts unlimited text but packet creation throws above 4096 payload bytes.
- **Fix:** Validate final payload bytes and report send failures without throwing into the UI.
- **If we skip it:** Pasting a long message can close the app and leave a pending record.

### 3. Disconnect stalls all BLE writes — `bluetooth/BleTransport.kt:85–94,145–153,307–320`
- **What this is:** Active-operation flags serialize asynchronous BLE operations.
- **Problem:** Disconnect before a completion callback leaves the flag set indefinitely.
- **Fix:** Track operation ownership, clear disconnected work, resume other links, and time out missing callbacks.
- **If we skip it:** One interrupted link can block all later sends.

### 4. Packet authentication bypass — `mesh/MeshRuntime.kt:166–187,198–205,228–231`
- **What this is:** Signatures identify message and acknowledgement senders.
- **Problem:** Unknown public senders and ACKs bypass verification; duplicate tracking and forwarding happen before authentication.
- **Fix:** Authenticate before accepting/tracking/forwarding; match ACKs to the original recipient.
- **If we skip it:** Forged messages and delivery status can be accepted.

### 5. Signing-key substitution — `mesh/AnnouncementCodec.kt:31–36`; `mesh/PeerManager.kt:12–24`
- **What this is:** Announcements contain encryption and signing keys.
- **Problem:** The ID binds only the encryption key; an attacker can reuse it with a new self-signed signing key.
- **Fix:** Bind IDs to both keys and explicitly migrate the Nearby protocol/identity.
- **If we skip it:** A discovered peer's signing identity can be replaced.

### 6. Nearby secrets enter plaintext backups — `identity/IdentityManager.kt:63–71`; `mesh/MessageStore.kt:79–96`
- **What this is:** Nearby keys and decrypted history use SharedPreferences.
- **Problem:** Base64 keys and readable messages are not excluded from backups.
- **Fix:** Keystore-backed encryption and exclusions for Nearby preference files.
- **If we skip it:** Backups can contain private keys and readable private chats.

### 7. Damaged history causes overwrite — `mesh/MessageStore.kt:99–120`
- **What this is:** Startup parses saved messages into the live map.
- **Problem:** A later invalid entry leaves a partial map; the next write overwrites the original history.
- **Fix:** Parse atomically and block writes on load failure, preserving the source.
- **If we skip it:** One damaged record can destroy other recoverable messages.

### 8. Shared BLE state races — `bluetooth/BleTransport.kt:44–54,241–257`; `mesh/MessageStore.kt:38–81`
- **What this is:** UI actions, radio callbacks, and jobs share mutable collections.
- **Problem:** Concurrent map iteration/mutation can crash or lose updates; operation flags can race.
- **Fix:** Serialize mesh and transport mutations on one execution context.
- **If we skip it:** Simultaneous sends/receives can break state or BLE operations.

### 9. Unlimited incomplete assemblies — `bluetooth/BleFrameCodec.kt:31–58`
- **What this is:** Reassembly retains fragments until a complete packet arrives.
- **Problem:** Fresh message IDs create unlimited pending assemblies and buffered bytes.
- **Fix:** Bound packet/assembly bytes and pending counts; clear link state on disconnect.
- **If we skip it:** A nearby device can exhaust memory.

### 10. Internet catch-up drops events — `nostr/NostrMessagingCoordinator.kt:34–35,150–161,203–228`
- **What this is:** A bounded worker handles blocking sends and incoming decoding.
- **Problem:** Rejected incoming tasks are not fetched again while the subscription stays open.
- **Fix:** Retry incomplete catch-up with bounded work and backpressure.
- **If we skip it:** Messages can remain missing until a later reconnect.

### 11. Pending messages have no send operation — `mesh/MeshRuntime.kt:136–143`; `RelayChatScreen.kt:179–185`
- **What this is:** No-link sends are saved as pending.
- **Problem:** No later job sends those records, despite the sending label.
- **Fix:** Mark no-link sends failed and allow manual retry.
- **If we skip it:** Users wait for messages queued nowhere.

## Should fix

### 12. Departed/indirect peers appear connected — `mesh/PeerManager.kt:12–24,42–51`; `bluetooth/BleTransport.kt:85–94,156–161`
- **What this is:** Peer state drives availability labels.
- **Problem:** Link loss never updates peers; forwarded announcements look like direct connections.
- **Fix:** Propagate disconnects, distinguish direct links, and expire stale announcements.
- **If we skip it:** Availability and send guards mislead users.

### 13. Nearby history grows forever in memory — `mesh/MessageStore.kt:12,69–81`
- **What this is:** UI/disk history are limited to 100/200 records.
- **Problem:** The underlying map is never trimmed and is repeatedly sorted/copied.
- **Fix:** Enforce retention in the actual map.
- **If we skip it:** Long sessions consume increasing memory and CPU.

### 14. Internet updates decrypt the full archive — `ui/relay/RelayViewModel.kt:120–128`; `data/internet/RoomInternetHistoryRepository.kt:51–65`
- **What this is:** Every storage mutation reloads a complete UI snapshot.
- **Problem:** Catch-up repeatedly scans/decrypts an expanding archive, roughly quadratic work.
- **Fix:** Bound the loaded history and update only changed UI records after writes.
- **If we skip it:** Large histories slow down startup, sending, and catch-up.

### 15. Tests target the old UI — `MainActivity.kt:150–151`; `app/src/androidTest/.../RelayActivityNavigationTest.kt:12–18`
- **What this is:** The activity opens `RelayHome`, while tests exercise `RelayApp`.
- **Problem:** Production navigation expects missing onboarding buttons; privacy tests miss shipped replacements.
- **Fix:** Exercise the current app shell and current screens.
- **If we skip it:** Tests fail or provide confidence about the wrong UI.

### 16. BLE security/failure paths lack tests — `app/src/test/.../BleFrameCodecTest.kt:9–17`
- **What this is:** One test covers ordered fragmentation only.
- **Problem:** Authentication, key binding, ACK ownership, disconnect recovery, and damaged history lack regressions.
- **Fix:** Focused tests for the fixes and frame ordering/duplicates/expiry/resource bounds.
- **If we skip it:** Serious Nearby regressions can pass the suite.

### 17. Unreachable old chat UI — `MainActivity.kt:186–445,744–888`
- **What this is:** The activity contains its previous private chat rendering helpers.
- **Problem:** Whole-tree searches found no caller of the old root screen.
- **Fix:** Delete the unused blocks and imports; retain live identity/radio controls.
- **If we skip it:** Fixes can be made in a screen users never see.

## Nice to have

### 18. New messages interrupt history reading — `RelayChatScreen.kt:169–170`
- **What this is:** Every row-count change scrolls to the bottom.
- **Problem:** Incoming messages pull readers away from older messages.
- **Fix:** Scroll on open, own sends, or when already near the bottom.
- **If we skip it:** Busy threads remain frustrating to read.

## Original verdict

Fix announcement loops, send crashes, authentication, and queue recovery first. Internet crypto/storage have stronger defensive code and tests than Nearby.

Lean: approximately 405 unreachable UI lines can be deleted; no dependency removal established.

Original audit verification: source inspection and `git diff --check` only. No builds, physical BLE, live relay exchange, OEM backup/restore, or native-library audit. Not every test body, documentation file, preview, or visual asset was read.

## Implementation checklist

Checked means implemented and checked with the available JVM/build verification. Android instrumentation and physical-radio results are tracked separately below; a compiled test APK is not a phone test pass.

- [x] 1. Announcement loop
- [x] 2. Payload limits and send errors
- [x] 3. BLE operation recovery
- [x] 4. Packet/ACK authentication
- [x] 5. Bound identity and protocol migration
- [x] 6. Nearby encryption and backup exclusion
- [x] 7. Atomic history load/preserve failures
- [x] 8. Serialized shared state
- [x] 9. Bounded reassembly
- [x] 10. Recoverable Internet catch-up
- [x] 11. Honest failed-send status
- [x] 12. Accurate peer availability
- [x] 13. Bounded Nearby retention
- [x] 14. Bounded Internet history updates
- [x] 15. Production UI tests
- [x] 16. BLE regression tests
- [x] 17. Delete unreachable UI
- [x] 18. Respect history scroll position

## Implementation log

### Started
- Saved the audit before editing implementation code.
- Existing `.idea` changes and untracked `synopsys.md` are user work and are preserved.
- Verification results are recorded as each group is completed, with phone-only checks identified explicitly.

### Findings 1–9, 11–13: implementation pass
- 1: Removed announcement-on-announcement replies; link-ready and periodic announcements remain.
- 2: Packet-creation/send failures become visible failed sends instead of UI exceptions.
- 3: BLE operations now track their owning link, time out after 15 seconds, discard disconnected work, and continue other links. Queue draining is iterative and bounded.
- 4: Announcements or known-peer signatures are checked before duplicate tracking and forwarding. ACK updates require an outbound record and the intended recipient (or a signed group participant).
- 5: Protocol 2 IDs hash both public keys. Legacy local IDs are recomputed without replacing key pairs; startup explains the ID change. Protocol 1 packets are rejected. Old history is retained; old remote-ID conversations do not silently retarget new identities.
- 6: Nearby private keys and aggregate history use separate Keystore encryption aliases. Legacy plaintext is removed only in the successful encrypted preference commit. Backup and transfer exclude both preference files.
- 7: Nearby history parses into a temporary map. Damaged history blocks subsequent writes and preserves the source.
- 8: Mesh jobs use the main dispatcher; BLE callbacks are posted to the same main looper. Late/stale client callbacks are ignored.
- 9: Reassembly is limited to 4352 bytes per packet, eight pending packets per link, and 64 overall; link loss clears assembly state. Joining is linear.
- 11: No-link sends are marked failed, with a manual-retry message. Interrupted pending records also restore as failed, without automatic replay, so a process kill cannot leave a phantom sending operation.
- 12: Link loss updates peer availability; routes expire after 90 seconds and direct/relayed labels differ.
- 13: The Nearby map itself retains at most 200 records; peer and transport queues are also bounded.
- Verification: baseline `./gradlew testDebugUnitTest` passed before edits; the same command passed after this implementation pass. Dedicated new regression tests are still pending, so checklist entries remain open.

### Finding 10: Internet receive backpressure
- Added a separate bounded receive worker so slow sends do not consume the receipt worker.
- A full receipt queue pauses its socket reader until capacity is available instead of dropping the event. Message callbacks run outside the relay client's lock, and queue admission happens outside the coordinator lifecycle lock, so shutdown can still cancel work.
- Existing issue reporting remains, with a catch-up message that does not tell the user to resend. The new 80-distinct-message regression verifies that every event arrives, in addition to the existing duplicate, cancellation, and authentication tests.
- Updated the existing thread assertions to accept the dedicated receive worker and made the overflow/issue test await both distinct issues rather than assuming there can be only two callbacks.

### Findings 12–14: availability, retention, and bounded history
- 12: Peer routes survive loss of an alternate link, become disconnected when the last link is lost, and expire after 90 seconds. Relayed announcements no longer get a direct-device address. Physical proximity remains informational: TTL is not a proof of physical distance.
- 13: Nearby storage keeps 200 records in memory/disk and publishes the most recent 100. A phone test verifies retention across reopen.
- 14: Room startup decrypts at most 200 recent messages, and mutations return only changed contact/message records. Old messages stay in the database. Conversations use 100-message keyset pages with **Older messages** and **Latest messages** controls, including a record-ID tie-breaker for equal timestamps.
- Publication validation is batched for each loaded page, rather than issuing a query per message. Contact-list times are loaded as grouped metadata, so old conversations outside the recent window still show that saved history exists.
- Added regressions for page boundaries, delta updates, preventing another contact's messages from showing while a different thread loads, and returning to the latest page after sending from an older page. Older encrypted rows are authenticated when their page is read; they are no longer decrypted on every unrelated write.

### Findings 15–18: current UI, regression tests, and deletion
- 15: Navigation, setup, contact, sending, filter/search restoration, and sensitive-draft tests now target `RelayHome`, `RelayChatScreen`, and `RelayChatsScreen`. Removed expectations for retired onboarding. Restored the Internet filter in the shipped filter list.
- 16: Added tests for signed packet tampering/version/size limits, signing-key substitution, fragment order/duplicates/expiry/bounds, operation ownership/disconnect/late timeout, peer route loss/expiry, and receive backpressure. Added phone tests for Nearby encryption, legacy migration, ACK ownership, corrupt-history preservation, missing keys, and retention.
- 17: Deleted the unreachable Nearby chat implementation from `MainActivity`, its unused imports, and the now-unreferenced `ui/relay/RelayApp.kt` shell after moving its tests. Removed obsolete connection-state helpers with no callers. Live identity/radio controls and Internet Setup remain.
- 18: New-message scrolling now respects whether the user was near the bottom, while still scrolling on open and after an own send. A production-screen instrumentation regression reads older messages while an incoming message arrives.
- Nearby send errors now appear in the current conversation. Both-key fingerprints use a hash of the full keys rather than a shared encoding prefix. Startup avoids permission-protected adapter reads until permission is granted.

### Verification and intermediate failures
- The first full build caught an unused-import cleanup mistake (`background` was still used by the identity avatar); restored that import.
- The new suspended-thread-loading JVM test initially inferred a Boolean return, which JUnit rejects. Changed it to return Unit. The subsequent complete JVM run passed.
- Final successful command: `./gradlew testDebugUnitTest assembleDebug assembleDebugAndroidTest && git diff --check` — **BUILD SUCCESSFUL**, with the whitespace check passing. This was rerun after the interrupted-send restoration and pagination fixes.
- **116 JVM tests passed**, zero failures/errors/skips, across 16 suites. This includes real TLS mock-relay tests; it is not a public-relay exchange.
- Debug APK and Android test APK both built. Instrumentation tests were compiled, **not executed**.
- Verified both APKs exist at the handoff paths below. The final combined build/test run completed in 25 seconds.
- `git diff --check` passed. No commit or push was made.
- Per user request, no emulator will be started or used. Phone/Keystore/UI/radio verification remains with the user.

## Phone verification handoff

App APK: `app/build/outputs/apk/debug/app-debug.apk`.

Test APK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

Install the app as an update to exercise migration; clearing app data does not test preservation of the existing keys/history.

1. Open the updated app. Verify saved Internet conversations remain. In Bluetooth & identity, verify the protocol-2/new-ID notice and preserved display name. Upgrade all nearby test phones and compare their new IDs.
2. Start Nearby on two phones. Leave them idle, then exchange public and private messages. Check that idle announcements do not cause continuous reply traffic.
3. Send a long UTF-8 message; verify a clear size error and no crash. With Nearby off, send to the public group; verify failed status and manual retry after reconnecting.
4. Disconnect one phone during a long send. A remaining connected phone must still receive later sends; failed/disconnected links must not block the queue indefinitely.
5. Move a peer out of range or stop Nearby. Verify its availability disappears on link loss or within the 90-second route expiry. With three phones, verify the relayed peer is labelled as a mesh route.
6. Configure both Internet users, exchange messages, reopen the app, and test **Older messages** / **Latest messages** on a long thread. Scroll upward before an incoming message and verify the view does not jump to the bottom.
7. Optional automated phone verification with USB debugging: `./gradlew connectedDebugAndroidTest`. This runs the compiled UI, Room, Keystore, and Nearby-storage regressions on the attached phone. It does not replace a two-/three-phone BLE test.

Not checked here: Android instrumentation execution, physical BLE interoperability/queue timing/multi-hop delivery, live public-relay exchange, actual OEM backup/restore, and native dependency internals. The identity migration is intentionally incompatible with protocol 1; old remote-ID history is retained separately.

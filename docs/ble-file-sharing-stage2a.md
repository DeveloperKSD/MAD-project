# Stage 2A: BLE file protocol (no attachment UI)

This is an additive, developer/service API integration. It has not established that wireless file sharing works on physical phones. Wi-Fi Direct, Nostr attachments, chat attachment buttons and automatic acceptance are absent.

## Security audit and boundaries

Existing private mesh chat uses static P-256 ECDH, SHA-256 and AES-GCM in `CryptoBox`; the signed mesh envelope authenticates its encrypted payload and endpoints. Public/broadcast chat is not private. Announcements bind both public keys to a self-certifying 64-bit routing ID and carry a signature. The existing `Authenticated` label means a valid announcement, not verified human identity or a persistent trusted-contact approval. Existing routing TTL is mutable.

The file extension leaves those identities and normal message encodings unchanged. It adds ECDH-derived, domain-separated HKDF-SHA256 directional keys, AES-256-GCM with a fresh random 96-bit IV per packet, signed mesh envelopes, and authenticated header/context. Full SHA-256 fingerprints of both endpoint public keys are pinned for the transfer and bound into the KDF/AAD. The manifest binding is an ECDH-keyed HMAC over both fingerprints, transfer UUID, profile and manifest, rather than a public file hash that observers could test against known files. No key is sent over BLE.

Offers and all controls are encrypted end to end. No file chunk is queued before a signed, decrypted receiver ACCEPT containing its fresh 128-bit challenge has been checked and persisted. A sender-generated 128-bit epoch changes on resume. Chunks, ACKs and completion bind both identities, UUID, profile, manifest, epoch and receiver challenge. Restarts restore active transfers as paused and clear acceptance; resuming requires a fresh exchange and explicit receiver acceptance. Key changes and corrupt metadata fail closed, preserving data. Unknown peers, unsupported types and malformed envelopes are ignored.

Assumptions: uncompromised endpoint identity keys, working Android cryptographic providers/randomness, and users checking the existing contact fingerprint outside the protocol when human identity matters. This adds no human-verification UI, forward secrecy or protection from a compromised recipient. Traffic metadata remains visible. Routing IDs are short; full-key pinning prevents silently substituting another identity within a transfer. A direct route is a GATT link plus the existing proximity hint, not cryptographic proof of physical distance; wormholes/malicious radios are outside this guarantee. Honest relays never forward direct-profile packets.

The additive protocol needs independent security review before production exposure. Standards used: [HKDF RFC 5869](https://www.rfc-editor.org/rfc/rfc5869.html), [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography).

## Packet format

New mesh types are FILE_CONTROL `0x40` and FILE_CHUNK `0x41`. All existing mesh type values and text encoding remain unchanged. Old PacketCodec implementations reject unknown types safely; old clients cannot relay these files. Every hop on a file route must support this extension.

The encrypted payload starts with this strictly bounded 120-byte, big-endian header:

| Offset | Bytes | Field |
| --- | --- | --- |
| 0 | 4 | Magic RBF1 |
| 4 | 1 | Version 1 |
| 5 | 1 | Code |
| 6 | 1 | Profile: direct 0, multi-hop 1 |
| 7 | 1 | Header length 120 |
| 8 | 16 | UUID: most significant then least significant 64 bits |
| 24 | 8 | Sender routing ID |
| 32 | 8 | Recipient routing ID |
| 40 | 16 | Sender epoch |
| 56 | 16 | Receiver acceptance challenge; zero for offer/sender resume |
| 72 | 32 | Keyed manifest binding |
| 104 | 12 | AES-GCM IV |
| 116 | 4 | Ciphertext length including 16-byte tag |
| 120 | variable | Ciphertext and tag; at most 1216 bytes |

Code values: offer 1, accept 2, reject 3, chunk 4, acknowledge 5, resume 6, cancel 7, verified completion 8, pause 9. Only chunk uses FILE_CHUNK. The header and directional full fingerprints are AEAD associated data. The mesh signature covers the payload as usual.

Offer and sender resume bodies: 44 bytes: unsigned-valid nonnegative file size encoded as 64-bit integer, 32-bit chunk size (1024), SHA-256 (32 bytes). Accept/receiver resume bodies: 32-bit count then up to 128 distinct 32-bit missing indices, requested in bounded pages. ACK: one 32-bit index. COMPLETE: 32-byte verified SHA-256. Other controls have empty bodies. CHUNK contains the unchanged Stage 1 binary chunk packet, never Base64. Exact lengths, indices, total count, profile limits, authenticated context and hashes are checked.

## Bounds, delivery and priority

The Stage 1 limits remain centralized in TransferLimits: direct BLE 1 MiB, multi-hop BLE 250 KiB (provisional policy, not measured throughput). Wi-Fi profile is refused. An oversized direct transfer cannot silently switch to multi-hop. One nonterminal endpoint transfer, including paused work, reserves the device. MeshRuntime also excludes simultaneous forwarding and endpoint transfers; a forwarder admits one route at a time. Route selection uses known peer links, avoiding indiscriminate chunk flooding.

Each write/notification queue reserves 4096 frames for existing text/control and 256 separately for file chunks. Each dequeue takes text/control first, including at forwarding nodes. Already submitted GATT operations cannot be preempted (one per queue). Incoming file work has a 32-packet mailbox, executes disk/crypto on a serialized IO dispatcher and never enters the chat parser. Existing text processing stays on its existing path. Sustained high priority traffic can starve files deliberately; deadlines pause them.

The sender has two unacknowledged chunks, retries at five-second intervals with four attempts per chunk/control, then pauses. These conservative starting timings need tuning on phones. A successful GATT write is only enqueue/transport progress. A receiver ACK follows Stage 1 durable chunk/checkpoint storage. Completion requires the receiver's authenticated SHA-256 confirmation. Sender completion probes recover lost final confirmations; missing-index responses recover receiver rollback. A malicious recipient can lie about its own receipt; this is not a proof of possession scheme.

Unaccepted offers expire after 30 seconds. A receiving session pauses after 60 seconds without a matching authenticated frame. New incoming offers are rate limited (one per ten seconds), authenticated input is capped at 64 frames/second, and persisted transfer slots are bounded at four. Forwarders allow one route, expire offers after 30 seconds/active routes after 60 seconds, and cap forwarded non-offer bytes at 2 MiB including retransmissions. Retired epochs are temporarily remembered. Unknown/replayed frames cannot allocate receiver payload storage before explicit acceptance. Authenticated peers can still cause denial of service; the bounds limit its impact.

## Persistence and cleanup

Stage 1 private temporary files and checkpoints remain authoritative for chunks, duplicate detection, corruption recovery and SHA-256 verification. No entire large file is read into RAM. Transfer bindings (pinned public keys, manifest, direction and consent state) live in a separate no-backup AtomicFile encrypted/authenticated with an Android Keystore HistoryCipher key. They do not alter chat Room history or Nostr configuration. File payload bytes remain plaintext inside app-private no-backup storage; this stage does not add encrypted file-at-rest storage. Do not treat this as suitable for a rooted/compromised device threat model.

Link loss pauses and purges queued chunks; restart never resumes automatically. Cancel cleans Stage 1 payloads and preserves a terminal record. Explicit discard removes terminal records/files; unaccepted rejected offers allocate no payload and release their metadata slot. Store/key loss, corruption, identity rotation and orphaned checkpoints preserve files and disable affected operations rather than replacing keys or wiping data. Source/output streams are caller-owned and must remain open until the returned API Job finishes. No existing UI calls these APIs yet.

## Verification and next device checks

JVM integration uses real identities, signing, encryption, PacketCodec, fragmentation/reassembly and private disk storage with a deterministic virtual two-endpoint or three-node route. It exercises loss/retry, refusal before acceptance, out-of-order BLE frames, cancellation, restart, damaged payload recovery, completion loss, backpressure, limits, changed/unknown keys and malformed packets. Queue tests verify text is selected ahead of a full chunk backlog; this is not a measured radio latency result.

Before exposing an attachment UI, use a debug/instrumentation harness calling MeshRuntime's offerFile/acceptFile/pauseFile/resumeFile/cancelFile APIs and observing fileTransfers:

1. Two phones: announce/verify fingerprints, offer a binary file, verify no DATA before manual accept, receive and compare SHA-256. Test empty, maximum-size and oversized files, refusal, cancellation and export.
2. Two phones: send text continuously while transferring; measure real latency, throughput, memory and battery. Exercise default MTU and negotiated MTU, both GATT roles, Bluetooth off/on, movement out of range and reconnection.
3. Kill/restart both apps mid-transfer; confirm paused state, fresh consent and exact resumed output. Test low storage and damaged metadata using disposable test data. Verify normal chat, history, notifications, Nostr and background sync still work.
4. Three phones: force a route through the middle phone with no direct endpoint link. Confirm 250 KiB policy, forwarding queue priority, single-route exclusion, cancellation and restart of the forwarding phone. Test one legacy intermediary fails safely.

Two or three physical Android devices are required to validate those radio behaviors. JVM success or an emulator does not verify wireless delivery. Further stages should add a small debug-only harness, security review, measured tuning, then explicit-consent production attachment UI; Wi-Fi Direct remains a separate adapter project.

## Initial implementation verification (2026-10-29)

`JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin` passed: 215 JVM tests, zero failures/errors/skips; debug APK built; release Kotlin compiled. Existing deprecated Bluetooth API warnings remain. No physical-device or Android instrumentation run was performed for this stage. In particular, real Keystore/AtomicFile recovery and Android process lifecycle still require device/instrumentation validation; JVM restart tests use an in-memory binding-store double with real file-engine checkpoints.

Changed implementation files:

- `app/src/main/java/in/yashd/bitchat/bluetooth/BleTransport.kt`
- `app/src/main/java/in/yashd/bitchat/bluetooth/PriorityFrameQueue.kt`
- `app/src/main/java/in/yashd/bitchat/mesh/MeshModels.kt`
- `app/src/main/java/in/yashd/bitchat/mesh/MeshRuntime.kt`
- `app/src/main/java/in/yashd/bitchat/mesh/PeerManager.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/ble/AndroidBleFileBindingStore.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/ble/BleFileBindings.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/ble/BleFileController.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/ble/BleFileForwardingGate.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/ble/BleFileProtocol.kt`

Tests: `app/src/test/java/in/yashd/bitchat/transfer/ble/` contains `BleFileControllerTest.kt`, `BleFileForwardingGateTest.kt`, `BleFileProtocolTest.kt`, `BleFileTestNetwork.kt` and `PriorityFrameQueueTest.kt`. This document is `docs/ble-file-sharing-stage2a.md`.

## Final pre-commit verification (2026-10-29)

Reviewed the unchanged normal mesh encoding, routing hints, GATT write/notification lifecycle, Stage 1 checkpoints and file limits, signed/encrypted file protocol, full endpoint key pinning, replay challenges, private metadata and all file queues. Text/routing/control frames are selected before file chunks at endpoints and forwarding nodes. Queues remain bounded: 4096 normal and 256 file frames per GATT queue, 32 incoming file packets, two outstanding chunks, 128 requested missing indices, four persistent records and one active route/endpoint reservation. A submitted GATT operation remains non-preemptible; throughput and text latency require physical measurement.

Minimal corrections during this verification:

- Reconcile binding state with Stage 1 recovered failures/cancellations, matching manifest and direction. Damaged completed data must not generate another verified completion. A new regression test reproduced the stale-completed status before the correction.
- Restore an outgoing transfer as completed when its Stage 1 authenticated remote-completion checkpoint committed before the binding update. A new crash-boundary regression test failed before this correction.
- Enforce offer/accept deadlines and receiving inactivity at API/packet admission as well as periodic tick time. A stalled ticker must not extend consent or permit late chunks. Restart still clears active acceptance and requires explicit reacceptance.
- Validate the combined missing-index request bound before mutating the request set.
- Allow isolated file/cipher injection into AndroidBleFileBindingStore for real Android tests; its production filename, alias and cryptography remain unchanged.

Final command:

```
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=in.yashd.bitchat.transfer.ble.BleFilePersistenceTest,in.yashd.bitchat.NearbyStorageTest,in.yashd.bitchat.data.internet.InternetHistoryStorageTest,in.yashd.bitchat.ui.relay.FavoriteChatsStoreTest,in.yashd.bitchat.notifications.TestNotificationTest,in.yashd.bitchat.background.BackgroundSyncStorageTest,in.yashd.bitchat.background.BackgroundSchedulingTest,in.yashd.bitchat.background.NostrBackgroundWorkerTest,in.yashd.bitchat.ui.relay.InternetRuntimeActivityTest,in.yashd.bitchat.notifications.NotificationNavigationTest,in.yashd.bitchat.background.BackgroundSyncSettingsTest
```

Results: **218 JVM tests and 56 focused Android instrumentation tests passed**, zero failures/errors/skips. Debug APK build, release Kotlin compilation and `git diff --check` passed. The emulator was `emulator-5554`, Medium Phone, Android API 37. The focused instrumentation includes five new BleFilePersistenceTest cases using isolated files/Keystore aliases: encrypted reopen, interrupted `.new`/legacy `.bak` recovery, corrupt ciphertext preservation, missing key preservation, missing metadata with an existing key, and controller recreation with paused consent/fresh resume. The other classes verify existing Nearby history/delivery, Nostr/Internet history and runtime recreation, favourites, notification actions/navigation, and background sync settings/persistence/scheduling/worker outcomes. No production chat UI, Nostr, favourites, notification or background sync implementation was changed.

Failures during this review were new Stage 2A regression tests identifying the two recovery defects above; both passed after fixes. No unrelated/pre-existing test failures were observed. ADB initially needed sandbox permission to access the connected emulator; the permitted rerun succeeded. Initial Gradle/Bluetooth deprecation warnings are unchanged.

Files changed specifically by final verification (in addition to the implementation manifest above):

- `app/src/main/java/in/yashd/bitchat/transfer/ble/AndroidBleFileBindingStore.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/ble/BleFileController.kt`
- `app/src/test/java/in/yashd/bitchat/transfer/ble/BleFileControllerTest.kt`
- New: `app/src/androidTest/java/in/yashd/bitchat/transfer/ble/BleFilePersistenceTest.kt`
- `docs/ble-file-sharing-stage2a.md`

The full Stage 2A change set is therefore 17 files (10 production, six test/fixture, this document), excluding pre-existing `.idea/misc.xml` and the five unrelated root files. All remain unstaged; no commit/push/merge/rebase was performed.

Ready for a separate Stage 2A development commit, with documented limits: emulator/JVM tests do not establish wireless delivery or measured latency. Two physical phones are still required for GATT roles/MTU, concurrent text, disconnect/reconnect, throughput and battery checks; three phones are required for forced multi-hop routes, forwarding contention and legacy intermediary checks. Controller recreation and on-disk recovery are tested, but actual OS process termination during each disk/write boundary and manufacturer-specific Bluetooth/Keystore behavior still need device validation. The self-certifying identity/forward-secrecy/file-at-rest limitations described above remain; independent security review is still recommended before production UI exposure.

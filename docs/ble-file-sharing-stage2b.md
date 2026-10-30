# Stage 2B: Nearby attachment interface

Uncommitted Nearby-only interface over Stage 2A. No Wi-Fi Direct, Internet/Nostr attachment handling, crypto changes, packet changes, broad storage permissions or FileProvider were added. Wireless delivery is not verified by the emulator tests.

## What the interface does

In a private Nearby chat, **+ / Add attachment** offers Android Photo Picker (with the platform document fallback on older devices) and OpenDocument. Only content URIs are accepted. Cancelling a picker sends nothing. The recipient and route are captured when opening the picker, so navigation while it is open cannot silently retarget the selection. If the process dies while picking, select again rather than reconstructing consent.

A bounded streaming import reads the actual bytes into app-private no-backup storage. Provider MIME, extension and reported size are untrusted. JPEG/PNG signatures must match Android's image decoder, dimensions must be positive and at most 16 million pixels, and decoding is sampled to at most 512 pixels per edge. PDFs require a supported `%PDF-1.x`/`%PDF-2.x` header and an end marker; this is a content-type gate, not a complete PDF validator or malware sanitizer. PDFs are never rendered in-process. Original bytes, including any embedded image EXIF metadata, are preserved rather than recompressed. GIF, HEIC, executable files, empty files, incomplete images and oversized selections fail with a message. A provider that does not advertise size is still limited by counting the stream. Imports have an interruptible 30-second deadline; a non-cooperative remote provider can delay cancellation until its blocking operation returns.

Filenames are reduced to a short safe basename and an extension based on detected content. Before sending, a confirmation shows filename, actual byte size, recipient display name plus Nearby ID and BLE method. It does not start a transfer automatically. It rechecks connection, route, current size/type and free storage at confirmation. Direct BLE permits 1 MiB, multi-hop BLE 250 KiB, using Stage 1 policy constants; these are provisional limits, not measured capabilities. A lost direct route requires selecting/confirming again for multi-hop; files are never silently downgraded. Free-space admission conservatively reserves three private copies plus 1 MiB overhead; Stage 1 also performs its own storage admission.

The confirmation queues an OFFER through the real controller. File chunks still cannot start until authenticated explicit receiver acceptance. Pending means an offer is pending, not delivered. Cards show confirmed bytes (receiver ACKs for outgoing files, durable stored bytes for incoming files). Even all bytes acknowledged are labelled **awaiting receiver verification** until the controller receives verified completion. The existing mesh file inbox, bounded GATT priority queues and two-chunk window remain unchanged. File controls never become text chat bubbles.

Incoming offers appear as separate attachment cards in the matching private Nearby chat. They have Accept and Reject; no data allocation/download or preview is automatic. Offers expire after 30 seconds. Stage 2A carries neither filename nor MIME: receivers see a generic transfer ID and size and an explicit explanation that type will be checked after receipt. An authenticated offer means the existing self-certifying peer identity, not verified human identity; compare contact fingerprints out of band when needed.

## Controls and recovery

- Active sending/receiving: Pause and Cancel transfer.
- Outgoing paused: Offer resume; it uses Stage 2A's fresh epoch and requires receiver acceptance again. An expired/rejected offer can require Cancel, removal and a new file selection/UUID instead. No silent retries/new sends occur.
- Incoming paused: explanation to ask the sender to offer resume, then accept again. No fabricated receiver-initiated resume API.
- Failed/cancelled/rejected: explain choosing a file again for a new offer; no reuse of a failed UUID or claim of an unsupported retry.
- Terminal records: Remove attachment explicitly discards the Stage 1 record/private payload and releases a retained slot. Paused transfers still reserve the single transfer slot; at most four records are retained.

The UI uses result-bearing MeshRuntime operations on the same serialized dispatcher as packet handling, ticks and teardown; the old Job-returning APIs remain compatible. Status now includes its existing persisted transfer profile. Opening a private chat explicitly reloads saved bindings/engine status on this dispatcher. Active transfers restore paused with cleared acceptance. Key changes/corrupt metadata fail closed and show an unavailable message; no key/identity reset or history wipe is attempted.

An activity-scoped AndroidViewModel retains an imported unsent selection, original outgoing display names, loading state and verified preview copies across normal activity recreation. No filenames, file content or URI grants go into saved-instance-state. Only picker visibility/request counters and a pending Save transfer UUID use saved state; private draft/preview content stays in the ViewModel and private files. The persisted Stage 2A ID, peer/key binding, size, method, state and progress survive process restart. Original filenames and unsent selections are not persisted: after process death cards use a generic ID label and a new selection requires confirmation. Only disposable copies in the dedicated `noBackupFilesDir/nearby-attachment-ui` directory are cleaned on ViewModel teardown/next process use; Stage 1 storage and unrelated files are untouched. Engine file contents remain plaintext in app-private storage, as in Stage 1/2A.

Two minimal controller defects were reproduced with JVM tests and fixed without changing the wire/crypto:

1. Rejecting an offer to resume a partially received file previously removed its binding but orphaned its engine record/payload. It now cancels the partial payload and keeps a visible terminal record for explicit discard. First-time unaccepted offers still allocate no file and release their slot when rejected.
2. Discovering corruption during verified export previously failed the engine but left the UI status completed. The binding now reflects FAILED if the engine fails; destination-only write failures preserve valid completed state.

## Preview and safe export

For a completed incoming transfer, **Check / preview received file** calls the engine's verified export API, which checks SHA-256 again, into a private bounded temporary copy. Content validation then gates preview/export. Images decode to bounded thumbnails. Unsupported or corrupt content yields an error and no Save action. There is no automatic preview or external launch.

**Save verified file…** uses CreateDocument with `image/jpeg`, `image/png` or `application/pdf`. The user chooses the destination; the engine re-verifies the completed data before copying. Only that explicit export may leave app-private storage. PDFs can be opened afterwards from the system Files app with a trusted viewer. No public directory is used automatically and no broad URI/FileProvider access is exposed. A failed destination may contain an empty/partial document chosen by the user; the original verified private payload remains available to retry Save. This stage provides Save, not an in-app PDF Open action or PDF sandbox.

## Exact changed files

Production:

- `app/src/main/java/in/yashd/bitchat/RelayChatScreen.kt`
- `app/src/main/java/in/yashd/bitchat/NearbyAttachments.kt` (new)
- `app/src/main/java/in/yashd/bitchat/attachments/AttachmentPolicy.kt` (new)
- `app/src/main/java/in/yashd/bitchat/attachments/PrivateAttachments.kt` (new)
- `app/src/main/java/in/yashd/bitchat/attachments/NearbyAttachmentModel.kt` (new)
- `app/src/main/java/in/yashd/bitchat/mesh/MeshRuntime.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/ble/BleFileBindings.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/ble/BleFileController.kt`

Tests:

- `app/src/test/java/in/yashd/bitchat/attachments/AttachmentPolicyTest.kt` (new)
- `app/src/test/java/in/yashd/bitchat/transfer/ble/BleFileControllerTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/attachments/NearbyAttachmentUiTest.kt` (new)
- `app/src/androidTest/java/in/yashd/bitchat/attachments/NearbyAttachmentHostTest.kt` (new)
- `app/src/androidTest/java/in/yashd/bitchat/attachments/PrivateAttachmentValidationTest.kt` (new)
- `app/src/androidTest/java/in/yashd/bitchat/attachments/AttachmentPickerImportTest.kt` (new)

Documentation: `docs/ble-file-sharing-stage2b.md` (new). No dependency/manifest/permission changes. `.idea/misc.xml` and the five unrelated empty root files are preserved and excluded. Gradle briefly generated a `.kotlin/` compiler artifact directory during testing; it is not a feature file and was absent from the final status.

## Two-phone manual test procedure (required, not yet performed)

1. Install the debug APK on phones A and B. Leave background sync opt-in unchanged. Grant only the existing Nearby/Bluetooth permissions; turn Bluetooth and Nearby on. In Discover, find each other, compare contact fingerprints and open the private chat. Send text both ways first.
2. On A tap +, Choose photo; cancel the picker and verify no offer appears on B. Repeat with a small JPEG/PNG (e.g. 30–100 KiB). Confirm the safe filename, exact bytes, B's name/ID and Direct BLE / 1 MiB. Cancel selection and verify nothing is offered. Select again and Send offer.
3. On B open A's private chat. Confirm a generic authenticated offer/size appears with Accept/Reject and no preview. Reject it; on A verify Rejected (or the documented timeout state if disconnected), no completed claim and no fake Retry. Remove the record on A.
4. Repeat and Accept within 30 seconds. Observe real acknowledged/stored progress. Send several text messages in both directions during transfer; record latency, MTU behavior and elapsed time. Both cards may say completed only after SHA-256 verification. On B tap Check / preview; verify the bounded image, then Save to a chosen folder. Compare the saved file's SHA-256 against A's original using a trusted tool.
5. Repeat with a small PDF via Choose file. B accepts, checks the completed file, uses Save verified file, and opens it from Files in a trusted viewer. Confirm no automatic PDF launch. Try a GIF/HEIC, a renamed executable `.jpg`, a truncated image/PDF, empty file and a >1 MiB file on A; verify rejection before any offer/data. The policy is byte size, not filename extension.
6. Start another image transfer; pause on A and check both cards. Use Offer resume on A, then Accept on B. Pause on B and confirm B has no fake resume control; resume must be offered by A. Cancel mid-transfer and verify no completion; remove the terminal record. Repeat with an unanswered offer: wait >30 seconds and follow Cancel/new selection if the old offer cannot resume.
7. Rotate/recreate each activity with a selected confirmation and during a transfer. Ensure no extra offer/data is sent just by recreation. Force-stop/relaunch both apps mid-transfer, rediscover/reopen the private chat: saved cards must be paused; a fresh sender resume and explicit receiver Accept are required. Original display names may become generic IDs after process death. Do not expect a terminated process to continue BLE work.
8. Repeat after Bluetooth off/on, moving out of range, denied picker access, cancelling Save, and low free storage on B (use disposable test data). Verify existing history/text, favourites, debug notification action, normal notifications and opt-in background sync remain intact.

A forced multi-hop radio route cannot be tested with two phones: use a third supporting phone between A and B, prevent a direct endpoint link, verify Multi-hop BLE / 250 KiB, reject a 250 KiB+1 file before offering, and repeat text priority, interruption and resume checks. Old clients cannot relay the new file types. Two/three real phones are still needed for measured delivery, route selection, radio timing, peer churn, battery/memory behavior and OEM picker/decoder/provider differences. There is no claim that wireless file transfer has been verified.

## Verification results (2026-10-29)

- All **225 JVM tests passed**, including the five new attachment policy cases, two newly reproduced/fixed controller cases and all existing mesh/Nostr/background tests.
- Final focused emulator run: **69 instrumentation tests passed**, zero failures/errors/skips, on emulator-5554 (Android API 37). This includes 13 new attachment UI/host/content/import cases plus the 56 Stage 2A regression cases. The new saved-state restoration test checks that the attachment action works on its first tap after recreation.
- Debug build, release Kotlin compilation and `git diff --check` passed. No new permissions or dependencies were needed.
- The earlier broader 38-test UI/regression run had **36 passes and two failures**: `RelayUiTest.incomingMessageDoesNotPullReaderAwayFromHistory` and `RelayUiTest.chatShowsAcceptanceWithoutDeliveryClaim`, both Compose-not-idle/recomposition timeouts. Both failures reproduced on a separate read-only archive of committed Stage 2A (`5dd92e749d38ca95275774ec83baec03bbae18a1`) installed on the same emulator: two tests, two failures. They are pre-existing on this environment, not claimed fixed; Internet chat behavior/test code was left unchanged. The final 69-test selection does not include RelayUiTest.
- Two focused controller regression tests initially failed and passed after their minimal fixes. An initial missing import in the new host test was corrected before the passing final run. No remaining Stage 2B test failures were observed.

Final command:

```
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' ANDROID_SERIAL=emulator-5554 ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=in.yashd.bitchat.attachments.NearbyAttachmentUiTest,in.yashd.bitchat.attachments.NearbyAttachmentHostTest,in.yashd.bitchat.attachments.PrivateAttachmentValidationTest,in.yashd.bitchat.attachments.AttachmentPickerImportTest,in.yashd.bitchat.transfer.ble.BleFilePersistenceTest,in.yashd.bitchat.NearbyStorageTest,in.yashd.bitchat.data.internet.InternetHistoryStorageTest,in.yashd.bitchat.ui.relay.FavoriteChatsStoreTest,in.yashd.bitchat.notifications.TestNotificationTest,in.yashd.bitchat.background.BackgroundSyncStorageTest,in.yashd.bitchat.background.BackgroundSchedulingTest,in.yashd.bitchat.background.NostrBackgroundWorkerTest,in.yashd.bitchat.ui.relay.InternetRuntimeActivityTest,in.yashd.bitchat.notifications.NotificationNavigationTest,in.yashd.bitchat.background.BackgroundSyncSettingsTest
```

The emulator tests use real Compose host/controls, a local test content provider with dishonest metadata, Android decoders and the existing Keystore/engine persistence tests. They do not automate the entire system Photo Picker/CreateDocument interaction with real phone providers and radios. Full picker-return/process-death, SAF destination errors, physical BLE delivery, interruption/reconnect, latency and multi-hop checks remain manual as described above. No commit, push, merge or rebase was performed.

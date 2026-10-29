# Periodic Nostr inbox catch-up (Part C)

Background Sync is off by default. Finish Internet Setup (including an accepted inbox announcement) before enabling it in Settings. Opt-in is saved atomically in `noBackupFilesDir`, bound to a digest of the existing identity and encrypted server configuration. Changing servers pauses scheduling and requires switching off/on after setup. Nothing in a background entry point creates an identity, announces an inbox, sends messages, or resets damaged storage.

[WorkManager 2.11.2](https://developer.android.com/jetpack/androidx/releases/work) schedules unique periodic work named `relay-nostr-inbox-catchup-v1`, with a [15-minute minimum period](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), a connected-network constraint, KEEP on repeated launches, and exponential retry backoff (at most two retries before yielding to the periodic cadence). WorkManager owns reboot/upgrade rescheduling; app launch and every worker revalidate eligibility. Android may defer work for Doze, battery restrictions, or connectivity. Force-stopped apps cannot resume checks until Android allows them to run again. No permanent service, foreground service, or wakeful socket is added.

## Ownership and storage

The worker borrows `RelayApplication.internetRuntime`. Admission checks the runtime lock: foreground reception, sends, configuration writes, or another background run make a worker yield. Foreground activation cancels catch-up and opens live subscriptions only after temporary transport cleanup. All background writes use the runtime history mutex and the existing Room repository. Background writes never call `interruptPending`, live-message notification callbacks, or any send/publication operation. The runtime's normal first initialization still restores previously interrupted queued sends as unknown.

`NostrMessagingCoordinator.catchUp` is a separate finite operation, not `startReceiving`. It authenticates the outer gift wrap, NIP-44 seal and inner NIP-17 rumor with the existing decoder. Only authenticated inner rumor IDs reach Room. Existing encrypted bodies, unique IDs, deletion tombstones, hidden conversations, and sender-copy publication status remain unchanged. Already stored catch-up messages also remain silent if later replayed through foreground reception.

## Retrieval bounds and limitations

* First check: the previous 72 hours of outer gift-wrap time.
* Later checks: last completed check's **start time minus 72 hours**, clamped to the previous seven days. This overlaps the protocol's 48-hour backwards timestamp randomization plus a day for delay/skew. A clock rollback starts a fresh bounded overlap.
* Each relay request includes kind 1059, local recipient, `since`, `until`, and `limit=128`. Up to 12 pages per configured inbox (maximum three inboxes) are queried sequentially. Paging moves backwards with an inclusive outer timestamp boundary to retain ties.
* Each page ends at EOSE, a five-second deadline, cancellation, subscription rejection, or overflow. Every temporary connection is closed in `finally`; persistent reconnect is disabled. A worker has a four-minute deadline and closes its owned OkHttp pool/executor.
* Last-success time advances only after every relay/page and durable storage succeeds. Partial writes survive and deduplicate on retry. Missing EOSE, timeout, page-budget exhaustion, excess responses, or saturated timestamp ties produce an incomplete check, not a new replay boundary.
* NIP-01 has no standard event-ID pagination cursor. A full page at a single timestamp cannot be paged safely, so the check stops incomplete rather than skipping a second. Heavy inboxes can repeatedly exhaust the budget. Relay omissions/retention policies cannot be detected. This is best-effort recent catch-up, not guaranteed delivery or full history restoration. Messages published more than the overlap late, wraps older than seven days, and sufficiently skewed timestamps may be missed by background checks.
* Status shows enabled/disabled/paused, incomplete checks, and last successful check time only. No keys, peers, relay URLs, or message contents enter WorkManager input/output, status, or logs.

## Automated checks

JVM tests cover finite TLS mock-relay catch-up, timestamp paging, duplicate authenticated rumors, sender copies, missing EOSE, cancellation, storage failures, existing-only identity reads, replay bounds, explicit consent, repeated launches, stale completions, disabling in flight, and foreground ownership. The same runner/ownership tests also run on Android.

Focused emulator tests cover the WorkManager driver, period/network constraints, KEEP/cancel, the actual CoroutineWorker entry point with an injected finite transport, Settings toggle/explanation, AtomicFile reopen/corruption, encrypted Room reopen/tombstone/sender-copy integration, and current lifecycle/notification/UI regressions. Tests use isolated state and fake/mock transport; they do not opt a user into production sync or connect to public inboxes. WorkManager's driver uses a no-op test worker to avoid uncontrolled network checks.

Run with JDK 21, the configured Android SDK, and an emulator:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=in.yashd.bitchat.background.BackgroundSyncTest,in.yashd.bitchat.background.BackgroundSyncStorageTest,in.yashd.bitchat.background.BackgroundSchedulingTest,in.yashd.bitchat.background.BackgroundSyncSettingsTest,in.yashd.bitchat.background.NostrBackgroundWorkerTest,in.yashd.bitchat.ui.relay.InternetRuntimeTest,in.yashd.bitchat.ui.relay.InternetRuntimeActivityTest,in.yashd.bitchat.data.internet.InternetHistoryStorageTest,in.yashd.bitchat.data.internet.InternetConfigurationStoreTest,in.yashd.bitchat.notifications.TestNotificationTest
```

## Manual device verification (still required)

1. Install an upgrade without clearing app data. Confirm identity, saved chats, deleted chats, favourites, BLE, floating New Chat, normal notifications, and debug Test Notification still work. Confirm Background Sync starts off for a previously unconfigured installation.
2. Complete Internet Setup against controlled inbox relays. Enable Background Sync and put Relay in the background with Home. Send a message from a second identity; allow at least 15 minutes and potentially longer under Android power management. Reopen Relay: verify last successful check and one stored message, with no background notification or sound.
3. While backgrounded, disconnect/reconnect networking and repeat. Reopen Relay during a check and send a message: foreground should recover promptly, with no duplicate session or interrupted send caused by a worker. Redeliver recipient/sender copies; verify no duplicate rows, changed publication acceptance, or revived deleted messages.
4. Turn Background Sync off, then close/reopen Relay and reboot the device. Confirm no new catch-up runs. Re-enable explicitly, repeat a reboot and an APK upgrade without clearing data, and verify the existing unique schedule resumes when Android permits it.
5. With controlled test relays, test absent EOSE, delayed gift wraps, timestamp ties, page saturation, unavailable relays, and a multi-day gap. Incomplete runs must not advance last-success time. Test corrupted stores only on disposable fixtures; existing files/keys must be preserved.

Physical-device delivery, reboot/upgrade execution, Doze timing, and public-relay interoperability have not been verified by these automated tests. Do not treat emulator or mock-relay success as proof of reliable background delivery on real devices.

## Changed files

Production:

- `app/build.gradle.kts` — WorkManager runtime and Android testing dependency.
- `app/src/main/java/in/yashd/bitchat/MainActivity.kt` — authorize foreground-only first-use identity creation.
- `app/src/main/java/in/yashd/bitchat/RelayApplication.kt` — shared controller, eligibility reconciliation, lifecycle wiring.
- `app/src/main/java/in/yashd/bitchat/RelaySettingsPage.kt` — opt-in actions and observable status.
- `app/src/main/java/in/yashd/bitchat/BackgroundSyncSettingsCard.kt` — isolated Settings presentation.
- `app/src/main/java/in/yashd/bitchat/background/BackgroundSyncController.kt` — consent, configuration binding, scheduling policy, success boundaries.
- `app/src/main/java/in/yashd/bitchat/background/BackgroundSyncStore.kt` — atomic no-backup consent/status persistence.
- `app/src/main/java/in/yashd/bitchat/background/NostrBackgroundWorker.kt` — WorkManager scheduler, bounded runner and worker.
- `app/src/main/java/in/yashd/bitchat/nostr/InboxReplayWindow.kt` — overlap and retention budgets.
- `app/src/main/java/in/yashd/bitchat/nostr/NostrIdentityManager.kt` — existing-only key access.
- `app/src/main/java/in/yashd/bitchat/nostr/NostrMessagingCoordinator.kt` — finite authenticated inbox retrieval.
- `app/src/main/java/in/yashd/bitchat/ui/relay/InternetRuntime.kt` — exclusive worker admission, cancellation, shared history gate and thread refresh.

Tests:

- `app/src/sharedTest/java/in/yashd/bitchat/background/BackgroundSyncTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/background/BackgroundSchedulingTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/background/BackgroundSyncSettingsTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/background/BackgroundSyncStorageTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/background/NostrBackgroundWorkerTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/data/internet/InternetHistoryStorageTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/ui/relay/FavoriteStarActivityTest.kt` — fixture now seeds via the application runtime, since writing through a separate Room instance cannot update its cached UI snapshot.
- `app/src/test/java/in/yashd/bitchat/nostr/NostrIdentityManagerTest.kt`
- `app/src/test/java/in/yashd/bitchat/nostr/NostrMessagingCoordinatorTest.kt`

Documentation: `docs/background-nostr-sync.md` (this file).

## Validation results for this change

- `:app:testDebugUnitTest`: **152 passed**, zero failures/skips.
- `:app:assembleDebug`, `:app:compileReleaseKotlin`: passed with JDK 21.
- Focused emulator suite on `emulator-5554` (Medium Phone, Android API 37): **68 passed**, zero failures/skips. Includes the final late-callback cancellation guard and already-open thread refresh.
- Broader UI check: `RelayUiTest.incomingMessageDoesNotPullReaderAwayFromHistory` and `RelayUiTest.chatShowsAcceptanceWithoutDeliveryClaim` time out with pending Compose recompositions. Both fail in isolation with this change and on untouched `ria` HEAD `babf97dd75700a34f5f38f62bd29724b5dbb19bc`, exported to `/private/tmp/ria-head-check-7i3yr4ht` and tested on `emulator-5556` (Small Phone, API 37). These existing chat rendering failures remain unresolved; their implementation/tests were not changed.
- `git diff --check`: clean. No staging, commits, pushes, or branch changes. Existing `.idea/misc.xml` and five unrelated empty root files remain untouched.

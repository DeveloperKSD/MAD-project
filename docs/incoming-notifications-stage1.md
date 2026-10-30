# Stage 1 incoming-message notifications

Implemented on `ria`; no commit or push. `.idea/misc.xml` was already modified and was left untouched.

Notifications run only at successful incoming storage boundaries, after the existing signature verification and decryption. Nearby inserts report whether they actually inserted a packet. Internet duplicate checks include Room deletion tombstones and run under the existing history mutex. Sender copies and outgoing sends never trigger notifications. Historical messages are stored normally but do not alert: their authenticated packet/rumor timestamp must be at or after the receive-session start. This deliberately conservative rule can suppress a newly delivered message sent before that boundary (or within Nostr's same timestamp second). Device clock skew can also suppress alerts.

Mutes are encrypted separately, keyed by transport, local identity, and conversation. Writes reload under a shared lock and commit before publishing UI state. Missing keys/corrupt preferences fail closed without replacing saved data. Deleting chat history retains its mute. Backup excludes mute ciphertext because its Android Keystore key cannot be transferred.

The message notification channel (`incoming_messages_v1`) is separate from the unchanged Nearby service channel (`mesh`). Notifications show a conversation name and “New message”; their public lock-screen version shows only Relay and “New message”. No message body is supplied to Android. Permission is requested once on Android 13+, independently of Bluetooth permissions; denial never blocks reception/storage. Users can enable it later in Android app notification settings.

Tap intents have distinct conversation identities, immutable PendingIntents, and validated routes. Cold launches and onNewIntent both pass the route to the existing shell. Opening a route clears overlays and selects the appropriate conversation. Active-conversation suppression only applies while the activity is foregrounded.

## Stage 1 limits

Internet/Nostr reception still starts and stops with the existing activity lifecycle. **There is no background Nostr reception and no new always-running service.** Internet notifications can appear while the app is active on another screen; closing/backgrounding the app stops Internet reception. On reopening, old relay history is stored without alerts. Nearby reception continues only while the existing Nearby system/service is active. The foreground-service notification remains separate.

Message encryption, identity logic, wire protocols, database schemas, favourites and pins were not changed.

## Manual checks

1. On Android 13+, allow notifications on first launch. Deny on a separate fresh installation, restart repeatedly, and confirm no repeated prompt. Messages must still save. Enable notifications later through Android settings.
2. With two devices and Nearby active, send both public-group and private messages. Confirm one generic alert outside that chat, none inside it, and no alert for sends or repeated packets. Background/lock the receiver while Nearby stays active and repeat.
3. With Nostr configured on both devices and the receiving app visible on another screen, send a new message. Confirm one alert across multiple inbox relays, no sender-copy alert, and no history alerts after reconnect/reopening.
4. Mute each transport's conversation, restart Relay, and confirm its mute remains and incoming messages save silently. Unmute and send a new message. Other conversations must remain unaffected.
5. Tap Nearby group, Nearby private and Internet notifications with the app open, backgrounded and process-stopped. Confirm correct conversation and Back behavior. Recreate/rotate the activity and confirm the target remains selected.
6. Lock the phone and confirm message bodies and conversation names are absent from the public lock-screen notification. Confirm the ongoing Nearby notification remains available and its Stop action still works.
7. Confirm existing favourite/pinned chats and pinned messages survive restart and still behave normally.

## Verification

- `./gradlew testDebugUnitTest`: 126 tests passed, zero failures/errors/skips.
- `./gradlew assembleDebug`: passed. APK: `app/build/outputs/apk/debug/app-debug.apk`.
- Focused instrumented run: 18 tests passed on Medium_Phone AVD / Android API 37 (reported as Android 17). Includes Nearby storage, mute persistence/corruption/key loss/concurrent stores, route parsing and actual activity cold/warm launch/recreation.
- Final full `connectedDebugAndroidTest`: 78 completed, 75 passed, 3 failed. All new tests passed. Failures in existing tests:
  - `GuidedInternetUiTest.invalidServersDoNotAdvanceAndProgressSurvivesTabNavigation`: ambiguous “Settings” selector matches two nodes.
  - `RelayUiTest.incomingMessageDoesNotPullReaderAwayFromHistory`: ComposeNotIdleException.
  - `RelayUiTest.chatShowsAcceptanceWithoutDeliveryClaim`: ComposeNotIdleException.
- All three failures were reproduced by running those exact tests against untouched `HEAD` (`46eafd5`) in `/tmp/relay-stage1-baseline-mj6yp5db`; they predate this feature. Baseline build passed.
- Earlier exploratory runs encountered emulator process/uninstall errors and a permission-dialog timeout in the new activity test. The test setup was corrected to exercise routing independently of the platform dialog, and its final focused and full runs passed.
- `git diff --check`: passed.

## Changed files

- `app/src/androidTest/java/in/yashd/bitchat/NearbyStorageTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/notifications/NotificationActivityTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/notifications/NotificationNavigationTest.kt`
- `app/src/androidTest/java/in/yashd/bitchat/ui/relay/MutedChatsStoreTest.kt`
- `app/src/main/java/in/yashd/bitchat/MainActivity.kt`
- `app/src/main/java/in/yashd/bitchat/RelayHome.kt`
- `app/src/main/java/in/yashd/bitchat/data/internet/HistoryModels.kt`
- `app/src/main/java/in/yashd/bitchat/data/internet/RoomInternetHistoryRepository.kt`
- `app/src/main/java/in/yashd/bitchat/mesh/MeshRuntime.kt`
- `app/src/main/java/in/yashd/bitchat/mesh/MessageStore.kt`
- `app/src/main/java/in/yashd/bitchat/notifications/MessageNotifications.kt`
- `app/src/main/java/in/yashd/bitchat/notifications/NotificationPolicy.kt`
- `app/src/main/java/in/yashd/bitchat/ui/relay/MutedChatsStore.kt`
- `app/src/main/java/in/yashd/bitchat/ui/relay/RelayViewModel.kt`
- `app/src/main/res/xml/backup_rules.xml`
- `app/src/main/res/xml/data_extraction_rules.xml`
- `app/src/test/java/in/yashd/bitchat/notifications/NotificationPolicyTest.kt`
- `app/src/test/java/in/yashd/bitchat/ui/relay/RelayViewModelTest.kt`
- `docs/incoming-notifications-stage1.md` (this report)

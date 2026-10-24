# Relay guided Internet setup and reusable conversations

The UI uses `design/relay-ui-reference.png` as its primary reference: near-black backgrounds, rounded panels and fields, blue accents, the existing typography, and licensed Hugeicons Android vectors. There is exactly one Scaffold/NavigationBar with **Chats, Discover, Settings**. The subsequent persistence milestone adds Room/KSP; see [local persistence](relay-local-persistence.md) for current storage behavior and verification.

## Screen flow

**New Chat** now presents two choices. Chat Nearby opens the existing Discover flow. Chat Online opens a dedicated screen for a friend's public Relay ID (`npub`) and an optional local conversation name. Names are explicitly labels, not usernames, unique identities, or a searchable directory. The public ID checksum is validated before creating a conversation.

Adding an Internet conversation immediately creates an encrypted local database record, even with no messages. Canonical Nostr public keys identify records: uppercase/lowercase representations of the same npub reuse one record. A nonempty new label can update that conversation's local name; omitting a label preserves an existing one. Different keys can use the same label. Incoming-message conversations and explicit records are merged by key in Chats, preserving the Nearby/Internet distinction. Existing chats automatically reopen with the stored public ID; a friend never needs to enter it for each message. The chat header shows the local name and offers the friend's public ID for inspection.

**Internet Setup** opens from Settings or Chat Online, with three steps:

1. **Choose your servers.** Plain-language explanations distinguish discovery and inbox servers. Users provide one to three distinct secure `wss://` URLs per list, one per line. Credentials and fragments are rejected by the existing URL policy. No public relay operator is selected or contacted implicitly. Next validates/applies the addresses and starts the existing inbox connections; merely configuring a URL does not verify it.
2. **Make yourself reachable.** Live inbox socket state is separate from the last inbox-announcement publication result. Publish my inbox uses the existing coordinator's kind-10050 operation. Failed, partial, and unknown publication results have actionable retry guidance and Advanced details. Retry connection creates a new connection generation; stale callbacks are ignored. Next is available only when all configured inbox sockets are currently connected and all configured discovery servers accepted the last announcement. Users can go Back and remove/change failed servers. These checks verify socket handshakes and publication acknowledgments, not end-to-end delivery or permanent subscription support.
3. **Share your Relay ID.** The public npub supports Copy and Android Sharesheet. The private key is never exposed or exported. Copy/share remain safe independently of the connection state; Finish requires current readiness. Explanations tell users that friends need this ID when first adding them, and that Internet conversations/names are saved locally with encrypted sensitive content.

Back, Next, Retry connection, Retry announcement, Finish, and Save for later are provided as appropriate. Server drafts, step, publication results, and completion state live in the retained ViewModel across tab navigation and activity recreation. Backgrounding closes Internet connections and clears their live state. A previously accepted announcement remains a historical result, not a claim of a current connection. Interrupted publishing becomes **outcome unknown**. Changed server configuration invalidates earlier announcement acceptance/completion. Process restart creates a new setup state and reloads saved Internet conversations; saved screen routes do not restore verified network success.

**Settings** includes the public ID, setup entry/status, and the unchanged Nearby identity, permission, Bluetooth, radio/mesh, rename, copy/share, rotation confirmation, and About controls. **Discover** retains the existing BLE discovery/start/stop and explicit Nearby group/private conversation routes. Unknown private BLE recipients remain blocked at the existing UI boundary; no public fallback or automatic transport switching is introduced.

## Changed files in this stage

- `ui/relay/RelayViewModel.kt`: in-memory named conversation records, key-based deduplication, server drafts/setup progress, structured announcement states/results, explicit readiness, retry/reconnect, interruption handling, and user-safe relay issue explanations. The existing backend interfaces and Nostr coordinator are reused.
- `ui/relay/RelayConversations.kt`: merges empty/named Internet records with message-backed conversations, preserving canonical keys and explicit transports.
- `ui/relay/RelayApp.kt`: New Chat choices, the dedicated online route, guided setup route, combined Settings entry, named chat headers, and the local-storage notice. Single bottom navigation and the existing BLE host slots are preserved.
- `ui/relay/InternetSetupScreen.kt`: the three setup screens, progress/status, advanced publication details, public-ID Copy/Android Sharesheet card, and public-only share intent.
- `ui/relay/OnlineChatScreen.kt`: public-ID/name entry, validation, explanation of local names, setup shortcut, and local-storage limitations.
- `RelayViewModelTest.kt`: setup validation/progress, failure/retry, partial/unknown results, changed configuration, interruption, live connection requirements, safe errors, duplicate prevention, named empty conversations, and session reset.
- `RelayUiTest.kt`, `GuidedInternetUiTest.kt`, `RelayActivityNavigationTest.kt`: New Chat choices, dedicated entry/validation, reopening/deduplication, setup/navigation/progress, publication failure/retry, readiness loss/reconnection, public-only share payload, and production BLE controls after activity recreation.
- This walkthrough/report.

This stage does not modify MainActivity's BLE host, MeshRuntime, BLE transport, the Nostr messaging coordinator, encryption, or identity storage. Earlier uncommitted work and unrelated IDE changes remain intact. The original Hugeicons MIT license remains in `docs/licenses/hugeicons-MIT.md` and packaged `assets/licenses/hugeicons-MIT.txt`.

## Emulator/device walkthrough

With an AVD running and the Android SDK/JDK configured:

```sh
./gradlew test assembleDebug connectedDebugAndroidTest
./gradlew installDebug
adb shell am start -n in.yashd.bitchat/.MainActivity
```

The tested machine uses Android Studio's bundled JDK (`/Applications/Android Studio.app/Contents/jbr/Contents/Home`) and SDK (`ANDROID_HOME=/Users/riakate/Library/Android/sdk`). The APK is `app/build/outputs/apk/debug/app-debug.apk`.

1. Continue through onboarding. Open New Chat and check both choices. Chat Nearby should open Discover with the original Find Nearby, permissions, Advanced, and public-group controls.
2. Choose Chat Online. Try an invalid ID, then paste a valid public npub and an optional name. Start Chat, return to Chats before sending anything, and reopen the named row. It should use the stored key without another ID prompt. Add the same npub again: the list must still contain one conversation. Different IDs may share a local name.
3. In Settings open Internet Setup. Try `ws://`, credentials, malformed hosts, or more than three addresses: validation must keep you on Step 1. Supply your own valid `wss://` server lists and choose Next.
4. In Step 2 inspect the actual connection state. No connection should be called connected until a WebSocket handshake is reported. Disabling networking, using an unreachable server, or a relay refusing the inbox should prevent readiness. Retry connection must clear stale socket state.
5. With working inbox connections, publish your inbox. Check failed/partial/unknown/accepted results, retry guidance, and Advanced kind-10050 details. Next must remain disabled until all configured inbox sockets are connected and discovery publications were accepted. Change failed servers with Back if necessary.
6. On Step 3 Copy or Share only the public npub. Finish, review setup, navigate away/back, rotate the activity, and background/return. Progress should remain in the session; live connection state must pause/reconnect. An interrupted announcement must not become accepted without a new result.
7. Configure two compatible NIP-17 clients with mutually queryable discovery servers and their own advertised inboxes. Add each other's public IDs once and exchange encrypted text in foreground chats. Relay acceptance must continue to say delivery is unconfirmed.
8. Verify Nearby on two BLE-capable physical devices. This requires real radios; emulator UI tests cannot establish discovery or multi-hop behavior. No automatic Internet/Nearby switching should occur.
9. Force-stop/relaunch: Internet names and message history reload from encrypted local storage. Applied server configuration and live setup state still reset. The separate Keystore-backed public identity remains stable. See the persistence walkthrough for details.

## Limits

- No contact synchronization, username search, cloud accounts, QR scanner, automatic retries of private messages, or automatic transport switching.
- Users must supply working server addresses. Failed/partial/unknown announcements cannot finish setup. Accepted is the last publication acknowledgment; connected is a live socket handshake. Neither proves message delivery/read state or ongoing subscription support.
- The existing coordinator retains its three-server-per-list limits, announcement freshness policy, and lack of NIP-42 login. Unsupported relay authentication is explained without exposing raw relay responses, exception contents, or URL query/path details in errors.
- Names are local convenience labels and are not verified identities. Public-key equality determines duplicate conversations.
- Internet history now survives process death through Room; BLE storage remains unchanged. Relay setup drafts survive activity recreation through the ViewModel, not process death. Onboarding completion remains saved UI state rather than a persistent preference.
- Physical BLE exchanges/multi-hop routing and live two-user public-relay exchanges require separate device/infrastructure testing. Automated UI tests exercise successful setup with a controlled injectable backend; they do not claim that a public relay is operational.

## Earlier guided-UI milestone verification

The results below document the earlier UI stage. Current persistence-stage results are in [local persistence](relay-local-persistence.md).

Command: `./gradlew test assembleDebug connectedDebugAndroidTest` — **BUILD SUCCESSFUL in 1m 16s**.

- **101 unit tests passed**, 0 failures, 0 errors, 0 skipped.
- **21 Android instrumented tests passed**, 0 failures, 0 errors, 0 skipped, on `Medium_Phone(AVD)` running Android 17.
- Debug APK built successfully at `app/build/outputs/apk/debug/app-debug.apk`.
- `git diff --check` passed. Source inspection confirms a single navigation bar with Chats, Discover, Settings.
- Manual emulator review covered the New Chat choices, dedicated online form, an empty named conversation in Chats, reopening it without ID entry, Discover, Settings/public ID, setup server entry, and actual connection failure/retry against the unreachable **local** address `wss://127.0.0.1:1`. Publishing stayed disabled and readiness stayed false. No private messages or public relay announcements were sent.
- The successful announcement/share/Finish path was exercised by Compose tests with a controlled injectable backend, not by a live public relay publication. The share-intent test verifies that its sole payload is the public npub. Physical BLE exchange/multi-hop behavior and live two-user public-relay delivery were not verified.
- The final suite removed the manual session's temporary conversation/configuration. The final debug APK was reinstalled for review. Production identity storage was not changed.
- Branch remains `feature/ria`. No commits or pushes; unrelated IDE changes and the supplied reference screenshot were preserved.

# Relay — Offline Bluetooth Mesh & Nostr Messaging (Android)

Relay is an experimental Android messaging application built with **Kotlin and Jetpack Compose** for a Mobile App Development mini-project. It supports two **independent** ways to communicate:

- **Nearby (offline):** Bluetooth Low Energy (BLE) for nearby public and private chats, with packet forwarding through intermediate devices.
- **Internet (online):** Nostr relays for encrypted, one-to-one messages between users outside Bluetooth range.

Users explicitly choose Nearby or Internet conversations. **Relay does not automatically switch between them.** Nostr requires internet access and working relay servers; Bluetooth mesh messaging does not.

> **Project status:** The implementation builds and automated tests have passed, but live end-to-end messaging through public Nostr relays and BLE mesh behavior on physical phones still require separate verification. Relay is a coursework prototype, not an emergency communications service.

## Features

### Nearby — BLE mesh

- BLE scanning, advertising, and GATT connections for peer discovery and packet exchange.
- Public broadcasts and AES-GCM encrypted one-to-one messages using the BLE identity and ECDH-derived keys.
- Signed announcements, unique packet IDs, duplicate suppression, and multi-hop flooding with TTL 5.
- Local message history, acknowledgement-based status updates, and an Android foreground service for BLE activity.
- Peer discovery and controls in **Discover**; Nearby conversations in **Chats**.

### Internet — Nostr

- An independent persistent Nostr identity (secp256k1) with a shareable **npub** public ID; private key storage uses Android Keystore-backed protection.
- NIP-01 event construction and verification, secure `wss://` relay connections and subscriptions.
- NIP-44 encryption and NIP-17 private-message envelopes (NIP-59 gift wraps).
- Configurable discovery and inbox relays (1–3 secure URLs per list); signed kind-10050 inbox announcements.
- Local Internet contacts and message history using Room; sensitive message bodies and local contact names are encrypted at rest.
- Separate Nearby and Internet conversation indicators. **Relay accepted** means a server accepted an event; it is **not** proof that the recipient received or read it.

### User interface

- A unified dark-themed interface with **Chats**, **Discover**, and **Settings**.
- Chats supports All / Nearby / Internet filters, search, and a New Chat flow.
- Internet Setup guides users through selecting relay servers, publishing their inbox announcement, and sharing their public ID.
- Internet chats can be reopened without entering an npub again; stored contacts and Internet message history survive app restarts.

## Project structure

```text
app/src/main/java/in/yashd/bitchat/
├── MainActivity.kt           Android entry point and BLE host integration
├── bluetooth/               BLE discovery, connections and framing
├── identity/                Nearby identity management
├── mesh/                    Mesh packets, forwarding, BLE chat and storage
├── nostr/                   Nostr identities, events, encryption and relay messaging
├── data/internet/           Room storage and local encryption
├── ui/relay/                Relay screens, navigation and ViewModel
├── ui/theme/                Compose theme
└── service/                 BLE foreground service
```

Additional documentation is in `docs/`. The UI reference is in `design/relay-ui-reference.png`.

## Requirements and run instructions

- Android Studio with a compatible JDK/Android SDK.
- Android 7.0 (API 24) or later for the project's baseline; test actual BLE hardware support on physical devices.
- Internet connectivity for Nostr messaging.
- Two separate Relay installations or compatible NIP-17 clients for a real send/receive test.

Open the repository in Android Studio, let Gradle sync, select an emulator/device and Run. The package name is currently `in.yashd.bitchat` even though the app is branded Relay.

From the project root, the basic build and test commands are:

```bash
./gradlew test assembleDebug
./gradlew connectedDebugAndroidTest  # requires a running emulator/device
```

The debug APK is produced at `app/build/outputs/apk/debug/app-debug.apk`.

## Test the app on an emulator

### A. Test navigation and public-ID validation (one emulator)

1. Launch Relay and finish the onboarding screens.
2. Check the **Chats**, **Discover**, and **Settings** tabs.
3. Select **Chats → New Chat → Chat Online**.
4. Try an invalid value such as `hello123`. It must not be accepted as a valid public ID.
5. Enter a valid `npub` belonging to a test contact (ideally copied from a second Relay installation), and an optional local name. Create the conversation.
6. Go back to Chats and reopen that conversation. You should not need to enter its ID again.
7. Add the same `npub` again. This should reuse the existing Internet conversation, not create a duplicate.
8. Force-stop and relaunch the app without clearing its data. The saved contact/name should remain.

A syntactically valid but inactive public key tests **ID validation and storage only**, not message delivery.

### B. Configure Nostr Internet messaging (both installations)

1. On each device, open **Settings → Internet Setup**.
2. In **Choose your servers**, supply 1–3 valid secure `wss://` addresses for **discovery** and 1–3 for the **inbox**, one per line. Choose compatible relays that you control or have permission to use; the app does not automatically select public relays.
3. Tap **Next**. Confirm the inbox connection state. A saved URL alone does not mean the server is connected.
4. In **Make yourself reachable**, tap **Publish my inbox**. An accepted kind-10050 announcement tells contacts which inbox relays to use. If a server rejects the announcement, fix the address or choose another server and retry.
5. Continue to **Share your Relay ID** and copy the device's **public npub**. Never share the private key.
6. Repeat on the second installation. For easiest discovery, use the same accessible discovery relay(s) on both, and ensure each device can reach its selected inbox relay(s).
7. On Device A, use **New Chat → Chat Online** and paste Device B's npub. On Device B, add Device A's npub.
8. Keep both apps open and online, send a short encrypted text from A to B, and check that B actually receives and decrypts it. Reply from B to A.
9. Record separately: connection, inbox announcement acceptance, send/publish result, and actual receipt. **Relay acceptance is not recipient delivery.**

If messaging fails, check that both apps have internet, each user has published an inbox announcement, the discovery relay can return both announcements, inbox subscriptions remain open, and the relays do not require unsupported authentication (NIP-42).

### C. BLE tests (physical devices recommended)

1. Install the same debug build on two BLE-capable Android phones.
2. Allow Bluetooth permissions and start Nearby discovery in **Discover** on both devices.
3. Check peer discovery, then test public and private messages.
4. For multi-hop forwarding, use three phones with a layout where two endpoints cannot communicate directly but can each reach the middle phone.

Emulator navigation tests do **not** prove that physical BLE radio discovery or multi-hop delivery works.

## Storage and privacy notes

- Nostr uses a **separate identity** from the BLE identity. Their keys are not interchangeable.
- Internet messages and local contact names are encrypted at rest. Some non-content metadata (for example public keys, timestamps and status) remains visible in structured storage.
- Internet history is device-local and excluded from normal Android backups. Uninstalling/clearing app data or losing the device's Keystore key may make that history unrecoverable.
- Internet relay configuration and live connection state must be set up again after a process restart. Saved message history does not imply a current server connection.
- The implementation does not provide automatic BLE/Nostr fallback, cloud synchronization, read receipts, or guaranteed recipient delivery.
- Public Nostr relays can observe public routing metadata and availability; encryption protects message content, not all metadata.

## Automated verification and current limitations

The most recent Codex review reported **105 JVM unit tests and 49 Android instrumented tests passed**, with a successful debug APK build. That review also noted that **live public-relay messaging and BLE on physical phones had not been verified**. These are reported results, not a claim of new tests run for this README update.

Known development limitations include: no automatic transport switching; unsupported NIP-42 relay login; session-only relay setup configuration; no cloud backup/recovery for encrypted Internet history; unknown publication outcomes when interrupted; and real-device BLE / public-relay interoperability tests still pending.

## References

- [Nostr NIP-01 — Basic protocol](https://github.com/nostr-protocol/nips/blob/master/01.md)
- [Nostr NIP-17 — Private direct messages](https://github.com/nostr-protocol/nips/blob/master/17.md)
- [Nostr NIP-19 — Public key encoding](https://github.com/nostr-protocol/nips/blob/master/19.md)
- [Nostr NIP-44 — Encryption](https://github.com/nostr-protocol/nips/blob/master/44.md)
- [Nostr NIP-59 — Gift wraps](https://github.com/nostr-protocol/nips/blob/master/59.md)
- See `docs/licenses/hugeicons-MIT.md` for icon attribution.

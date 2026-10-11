# bitchat – Bluetooth-only Mesh Chat (Android)

An Android chat app that works **without internet or cell service**. Nearby phones find each other over Bluetooth Low Energy (BLE), exchange public and encrypted private messages, and relay packets for each other to form a small mesh network.

Built with Kotlin and Jetpack Compose.

---

## Original features

- **Persistent local identity:** a peer ID and key pairs are created once and stored on the device. Display name can be changed, and the identity can be rotated.
- **Nearby peer discovery over BLE:** the app scans, advertises and runs a GATT server so phones can find and connect to each other.
- **Signed announcements:** each device announces itself every 30 seconds so peers learn its name and public keys.
- **Public messages:** broadcast to everyone nearby.
- **Encrypted direct messages:** select a peer and send a private message that only that peer can decrypt.
- **Multi-hop relay:** packets carry a TTL and are forwarded by intermediate devices.
- **Duplicate suppression:** a seen-packet cache stops the same packet being processed or forwarded twice.
- **Delivery status:** messages move through `pending`, `sent`, `delivered` using ACK packets.
- **Message persistence:** the latest messages are stored locally and survive app restarts.
- **Foreground service:** keeps scanning, advertising and connections alive while the app is in the background.
- **Readiness panel:** shows whether permissions, the Bluetooth adapter, BLE transport and hardware are ready, with start/stop controls.

---

## What's new as of 11/10/26

### 1. Per-chat message filtering
Previously, public and private messages were shown together in one list, no matter which peer was selected.

Now the message list depends on the current selection:

| Selection | Messages shown |
|-----------|----------------|
| No peer selected | **Public** messages only (`recipientId == null`) |
| A peer selected | **Encrypted** messages sent to or received from that peer only |

Tap the selected peer again to deselect it and return to the public feed. Messages are only hidden, never deleted.

### 2. Confirmation before rotating identity
Tapping **rotate** used to change the peer ID and keys immediately. It now opens a confirmation dialog explaining that:

- a new peer ID and new keys will be created,
- nearby peers will see you as a different person,
- the mesh stops until you start it again.

The identity is only rotated if you confirm.

Both changes are UI-only and live in `MainActivity.kt`. The Bluetooth, crypto, routing and storage code is untouched.

---

## Project structure

```
app/src/main/java/in/yashd/bitchat/
├── MainActivity.kt          Compose UI
├── bluetooth/               BLE transport, framing, readiness checks
├── identity/                Local identity and peer identity
├── mesh/                    Runtime, packet codec, peer manager, message store, crypto
├── service/                 Foreground service
└── ui/theme/                Compose theme
```

---

## Requirements

- Android 7.0 (API 24) or higher
- A device with Bluetooth Low Energy
- At least **two phones** to test chat and relay
- Android Studio with a recent Gradle/AGP setup

## Build and run

1. Open the project in Android Studio.
2. Let Gradle sync.
3. Install on two or more physical devices (emulators do not support BLE advertising).
4. On each device tap **allow bluetooth**, grant the permissions, then tap **start mesh**.
5. Wait for the other device to appear under **nearby**.

## How to test the new changes

1. Send a public message from phone A. It appears on phone B's public feed.
2. On phone A, select phone B and send a private message. It shows only while B is selected, and is not on the public feed.
3. Deselect the peer. The public feed returns.
4. Tap **rotate**. A dialog appears. **cancel** keeps your identity, **rotate** creates a new one.

---

<img width="338" height="387" alt="image" src="https://github.com/user-attachments/assets/8cf8eef6-d8de-4b6c-83ca-4bddf7d4a590" />
<img width="338" height="387" alt="image" src="https://github.com/user-attachments/assets/9008af98-dd7e-4ff5-8c06-7780090a6551" />



## Known limitations / ideas for next steps

- Show timestamps on messages and auto-scroll to the newest one.
- Resend messages that are stuck in `pending` once a link is ready.
- Rate-limit announcement replies so two devices do not keep answering each other.
- Notifications for incoming messages.
- Ping/latency button using the existing `PING`/`PONG` packet types.
- Move message storage from SharedPreferences to Room.

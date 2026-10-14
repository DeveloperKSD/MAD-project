# bitchat – Bluetooth-only Mesh Chat (Android)

An Android chat app that works **without internet or cell service**. Nearby phones find each other over Bluetooth Low Energy (BLE), exchange public and encrypted private messages, and relay packets for each other to form a small mesh network.

Built with Kotlin and Jetpack Compose.

---

## Screenshots

<img width="338" height="387" alt="image" src="https://github.com/user-attachments/assets/8cf8eef6-d8de-4b6c-83ca-4bddf7d4a590" />
<img width="338" height="387" alt="image" src="https://github.com/user-attachments/assets/9008af98-dd7e-4ff5-8c06-7780090a6551" />

---

## Features

### Core (original)

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

### Added since the original

| Feature | What it does |
|---------|--------------|
| **Per-chat message filtering** | The message list now depends on the selected peer. No peer selected shows only public messages. A selected peer shows only the encrypted messages with that peer. |
| **Rotate-identity confirmation** | Tapping **rotate** opens a confirmation dialog before the peer ID and keys are replaced. |
| **Bottom navigation with 3 tabs** | The single long scroll is split into **peers**, **chat** and **settings**, using a Material 3 `NavigationBar`. |
| **Signal-strength bars** | Each peer shows a 4-bar signal icon next to its RSSI value. |
| **"Last seen" time** | Each peer shows when it was last heard from, such as `just now`, `30s ago`, `2m ago`, `3h ago`. It refreshes every 15 seconds. |
| **Always-visible mesh control bar** | A bar at the top of every tab shows `mesh on / N nearby` or `mesh off`, with a **start mesh** or **stop** button, so the mesh can be started from any tab. |
| **Chat header for private chats** | When a peer is selected, the chat tab shows "encrypted chat with X" and a **public feed** button to switch back. |
| **Errors on every tab** | Any runtime error text appears at the top of whichever tab is open. |

All of these changes are UI-only and live in `MainActivity.kt`. The Bluetooth, crypto, routing and storage code is untouched.

---

## How the screens work

### peers tab
- Lists nearby devices with a connection dot, name, state, peer ID, signal bars, dBm value and last-seen time.
- Tap a peer to select it. The app switches to the chat tab for an encrypted chat with that peer.
- Tap the selected peer again to deselect it.

### chat tab
- Shows the message list for the current selection (public feed, or the selected peer).
- The composer at the bottom shows who the message will go to: `public nearby message` or `encrypted direct to <name>`.

### settings tab
- Identity panel with **rename** and **rotate** (with confirmation).
- Readiness panel showing permissions, adapter, transport and hardware status, with the start and stop buttons.

### signal bars

| RSSI | Bars |
|------|------|
| -60 dBm or stronger | 4 |
| -61 to -70 dBm | 3 |
| -71 to -80 dBm | 2 |
| -81 to -90 dBm | 1 |
| weaker than -90 dBm, or unknown | 0 |

---

## Project structure

```
app/src/main/java/in/yashd/bitchat/
├── MainActivity.kt          Compose UI (tabs, peers, chat, settings)
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
- At least **two devices** to test chat and relay. Physical phones are the most reliable. Emulators with virtual Bluetooth also worked in testing.
- Android Studio with a recent Gradle/AGP setup

## Build and run

1. Open the project in Android Studio.
2. Let Gradle sync.
3. Install the same build on two or more devices.
4. On each device, tap **allow bluetooth** in the top bar and grant the permissions, then tap **start mesh**.
5. Open the **peers** tab and wait for the other device to appear under **nearby**.

### If devices don't find each other

- Both devices must show **mesh on** in the top bar.
- Keep both screens on with the app open.
- Both devices need the same build installed.
- Both devices need the correct date and time. Packets from a device whose clock is more than 10 minutes off are dropped.
- On Android 11 and below, turn the phone's Location switch on as well.

## How to test the new features

1. Send a public message from device A. It appears on device B's chat tab under the public feed.
2. On device A, tap device B in the **peers** tab and send a private message. It shows only while B is selected and is not on the public feed.
3. Tap **public feed** on the chat tab. The public feed returns.
4. Move the devices apart and check that the signal bars and dBm value change, and that last-seen text updates.
5. Open the **settings** tab and tap **rotate**. A dialog appears. **cancel** keeps your identity, **rotate** creates a new one.

---

## Changelog

**14/10/26**
- Added bottom navigation with peers, chat and settings tabs.
- Added signal-strength bars and last-seen time on each peer.
- Added an always-visible mesh start/stop bar on every tab.
- Added a private-chat header with a button back to the public feed.

**11/10/26**
- Added per-chat message filtering (public feed vs. selected peer).
- Added a confirmation dialog before rotating identity.

---

## Known limitations / ideas for next steps

- Show timestamps on messages and auto-scroll to the newest one.
- Resend messages that are stuck in `pending` once a link is ready.
- Rate-limit announcement replies so two devices do not keep answering each other.
- Notifications for incoming messages.
- Ping/latency button using the existing `PING`/`PONG` packet types.
- Move message storage from SharedPreferences to Room.

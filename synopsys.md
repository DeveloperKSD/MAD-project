## 1. Technologies Used

| Technology / Tool | Purpose |
| --- | --- |
| Kotlin | Primary programming language used for the Android application logic and UI implementation. |
| Jetpack Compose | Builds the application's single-screen user interface for identity management, readiness status, peer listing, and messaging. |
| Material 3 | Provides Compose-based UI components and theming. |
| Android SDK | Supplies the Android application framework, service lifecycle, permissions, notifications, and Bluetooth APIs. |
| Android Bluetooth LE and GATT APIs | Implement nearby peer discovery, advertising, GATT server/client communication, and packet exchange. |
| Kotlin Coroutines and StateFlow | Manage asynchronous runtime tasks and observable UI state updates. |
| SharedPreferences | Stores persistent local identity data and recent message history on the device. |
| org.json | Encodes and decodes message payloads and stored message data in JSON form. |
| Java Cryptography Architecture | Provides ECDH key agreement, AES-GCM encryption, SHA-256 hashing, and ECDSA-based packet signing and verification. |
| UUID | Defines fixed BLE service and characteristic identifiers for the custom transport. |
| Gradle 9.5.0 | Handles project build automation and dependency management. |
| Android Gradle Plugin 9.3.2 | Builds the Android application module. |
| Kotlin Compose Plugin 2.2.10 | Enables Compose support in the Kotlin build configuration. |
| AndroidX Core KTX 1.19.0 | Adds Kotlin-friendly Android utility extensions. |
| AndroidX Lifecycle Runtime KTX 2.11.0 | Supports lifecycle-aware runtime behavior. |
| AndroidX Activity Compose 1.13.0 | Integrates Jetpack Compose with Android activities. |
| Jetpack Compose BOM 2026.02.01 | Aligns Compose library versions used by the project. |
| JUnit 4.13.2 | Supports unit testing. |
| AndroidX JUnit 1.3.0 and Espresso 3.7.0 | Support Android instrumentation and UI testing. |
| Android Studio / IntelliJ project configuration | Evidenced by the included `.idea` files for project setup and development. |

## 2. Objective

`bitchat` is an Android application designed to support nearby communication without relying on internet connectivity or centralized servers. It targets users who need local, device-to-device messaging in situations where network access is unavailable, limited, or undesirable. The application addresses this need by using Bluetooth Low Energy for peer discovery and GATT-based packet transport, while maintaining a persistent local identity for each user. It also adds signed announcements, packet verification, and optional encrypted direct messaging so that communication is not tied only to temporary Bluetooth device addresses. This approach is useful because it combines offline operation, identity continuity, and lightweight mesh-style relaying within a mobile application structure.

## 3. Key Features

1. **Persistent Local Identity**  
   The application creates or loads a local user identity that includes a display name, cryptographic key pairs, and a derived peer ID. Users can rename themselves or rotate their identity from the interface, which helps maintain continuity across app restarts while still allowing manual identity reset.

2. **Bluetooth LE Discovery and Custom GATT Transport**  
   The system advertises a custom BLE service, scans for nearby devices exposing the same service, and connects through GATT to exchange packets. It supports both characteristic writes and notifications, allowing communication between devices without internet infrastructure.

3. **Signed Peer Announcements and Peer Tracking**  
   Each device broadcasts announcement packets containing its display name and public keys, and incoming announcements are verified before a peer is added to the active list. The application tracks discovered peers, their recent visibility, connection state, and signal strength, which helps users identify nearby participants.

4. **Public and Encrypted Direct Messaging**  
   Users can send a public nearby message or select a peer to send an encrypted direct message. Public messages are sent as regular packets, while direct messages are encrypted with a shared key derived from the sender's private key and the recipient's public key before transmission.

5. **Packet Handling with Acknowledgement and Relay Logic**  
   The messaging layer uses a structured packet format with packet IDs, timestamps, TTL, signatures, and optional recipients. The runtime drops malformed or duplicate packets, sends acknowledgement packets for received messages, and relays eligible packets to support nearby forwarding behavior.

6. **Foreground Service and Runtime Readiness Monitoring**  
   The project includes a foreground service that keeps Bluetooth scanning, advertising, and connection handling active while the mesh is running. The main screen also checks permissions, adapter state, and BLE transport readiness so users can see whether the device is prepared to participate.

## 4. Target Audience

The application is intended for Android users who need short-range, local messaging with nearby devices, especially in environments where internet access is unreliable or not preferred. This includes students, small groups, event participants, or field users who benefit from direct peer discovery and ad hoc communication. The project is relevant to such users because it combines offline operation, recognizable peer identity, and a simple mobile interface in a single BLE-based application.

## 5. Benefits

* Enables nearby communication without depending on mobile data, Wi-Fi, or remote servers.
* Preserves user identity across restarts, making peer recognition more stable than raw Bluetooth addressing alone.
* Provides message delivery visibility through stored status values and acknowledgement handling.
* Supports more secure direct communication by encrypting selected peer-to-peer messages.
* Helps users understand device readiness through integrated permission and Bluetooth status checks.

## 6. Future Scope

1. **Secure Key Storage**: Moving identity keys from plain `SharedPreferences` to Android Keystore-backed storage would improve protection of persistent private keys.
2. **Database-Based Message and Peer Storage**: Replacing JSON-based preference storage with a local database such as Room would make message history and peer metadata more structured and scalable.
3. **Authenticated Session Handshake**: Adding a dedicated session-management and handshake layer would strengthen peer verification for encrypted conversations beyond announcement-based trust.
4. **More Complete Mesh Routing Controls**: Extending the current relay logic with richer routing, retry, and forwarding control would improve reliability in larger multi-device scenarios.
5. **Conversation Filtering by Peer**: Separating message history into peer-specific conversations would make the interface more practical as the number of users and messages grows.

## 7. Conclusion

`bitchat` is a Bluetooth-only Android messaging application built to support nearby communication through BLE discovery, GATT transport, and persistent peer identity. Its main value lies in enabling local message exchange with signed announcements, optional encrypted direct messaging, recent message persistence, and continuous operation through a foreground service. These implemented components directly support the project's objective of providing offline, device-to-device communication for nearby users. Overall, the repository demonstrates a functional foundation for localized mesh-style chat on Android using only platform-provided tools and libraries.

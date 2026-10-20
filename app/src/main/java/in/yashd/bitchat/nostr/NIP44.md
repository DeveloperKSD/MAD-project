# NIP-44 version 2

`Nip44.encrypt(plaintext, senderSecretKey, recipientXOnlyPublicKey)` returns the
standard padded Base64 payload. `decrypt(payload, recipientSecretKey,
senderXOnlyPublicKey)` returns UTF-8 text or throws for invalid input. Keys are
32-byte arrays. Calls are blocking and should run on a worker thread. No identity
storage or transport is connected in this stage.

Use the encrypted payload as content in a signed NIP-01 event. Before calling
decrypt, verify that event with `NostrEventSigner.verify`. Payload authentication
alone does not verify the outer event or implement NIP-17 sender authentication.

ACINQ/libsecp256k1 validates keys and multiplies the peer's even-y public point by
the private scalar. The raw x coordinate (not the hashed `ecdh` API result) goes
through HKDF-extract/SHA-256 with salt `nip44-v2`. A fresh SecureRandom 32-byte nonce
and HKDF-expand derive separate ChaCha20 and HMAC keys plus a 12-byte cipher nonce.
UTF-8 bytes get the standard length prefix and zero padding. Bouncy Castle's
RFC 8439 ChaCha20 starts at counter zero. HMAC-SHA256 authenticates nonce and
ciphertext; the payload is Base64(version || nonce || ciphertext || MAC).
Decryption verifies the MAC in constant time before decrypting and validates the
length/padding structure and UTF-8. Unknown versions have a distinct exception.

The platform limit is 1..1,048,576 UTF-8 bytes, inclusive, with a corresponding
early encoded-payload cap. The current specification permits up to 2^32-1 bytes;
that theoretical maximum cannot fit JVM arrays and is unsuitable for mobile
memory. The standard extended prefix is supported starting at 65,536 bytes.
Secret arrays owned by this component are cleared where practical. JVM strings,
crypto-provider internal copies, and garbage-collected memory cannot be reliably
zeroized; callers own and must manage their input private-key arrays. No keys,
shared secrets, or plaintext are logged or persisted by this component.

## Sources and vectors

- Specification: https://github.com/nostr-protocol/nips/blob/master/44.md
- Published vectors: https://github.com/paulmillr/nip44/blob/main/nip44.vectors.json
- Vendored test resource SHA-256:
  `269ed0f69e4c192512cc779e78c555090cebc7c785b609e338a62afc3ce25040`

The published JSON predates the extended prefix. Its invalid-length entries
65,536 and 100,000 are valid under the current specification and are tested as
valid lengths instead. Zero and 10,000,000 remain rejected (the latter exceeds
our platform limit). All published invalid decrypt and invalid-key cases are
exercised. Current specification inline checksums cover 65,535/65,536/65,537 bytes.
The suite also checks all published conversation/message keys, padding lengths,
exact ciphertexts, long-message hashes, independently random identities, Unicode,
nonce freshness, modifications, wrong peers, malformed payloads, and local limits.

Verification: `ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew test assembleDebug --console=plain`
passed after focused review: 43 tests, zero failures/errors/skips (9 NIP-44 test methods containing
vector loops), and debug APK build successful. No Android device/instrumentation
or live-relay interoperability test was run. No BLE, MeshRuntime, chat UI,
identity storage, subscriptions, or NIP-17 packaging was changed.

## Focused review (2026-10-21)

Confirmed the key derivation, counter-zero ChaCha20, fresh nonce generation,
nonce-plus-ciphertext HMAC, fixed-length constant-time MAC comparison, canonical
Base64, UTF-8 handling, and extended prefix against the current specification.
The vendored checksum is now asserted in the unit suite. Added explicit 100,000
byte coverage and valid-MAC malformed-prefix, inconsistent-padding-size, and
invalid-UTF-8 cases, plus malformed key/nonce lengths and unsupported versions.
Fixed an internal helper failure path: reject invalid key/nonce lengths before
allocating plaintext buffers, and clear padded plaintext even if key derivation
throws. Public encryption always supplies valid internal lengths.

Bouncy Castle remains pinned to `bcprov-jdk18on:1.80.2`, using its lightweight
ChaCha20 and HKDF APIs without globally registering a provider. It supplies these
primitives consistently on minSdk 24. Upstream identifies 1.80.2 as a security
patch release carrying fixes from 1.84:
https://downloads.bouncycastle.org/betas/
No confirmed issue in the used primitives was identified in this focused review;
this is not an exhaustive dependency audit or a formal cryptographic audit.
There are no logging, filesystem, identity-storage, BLE, or MeshRuntime calls in
the component. Published private keys in the test resource are public test
fixtures, not application identities. NIP-44 itself has no forward secrecy: a
compromised identity key can expose previous and future conversations.

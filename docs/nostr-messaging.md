# Nostr messaging transport

This stage is independent of Android UI, BLE and MeshRuntime. It uses the existing
persistent Nostr identity, signer, NIP-44, NIP-17 packaging and WebSocket client.
Official references reviewed: [NIP-17](https://github.com/nostr-protocol/nips/blob/master/17.md),
[NIP-01](https://github.com/nostr-protocol/nips/blob/master/01.md),
[NIP-59](https://github.com/nostr-protocol/nips/blob/master/59.md),
[NIP-42](https://github.com/nostr-protocol/nips/blob/master/42.md).

## Usage and flow

Construct `NostrMessagingCoordinator` with a Nostr identity manager, caller-owned
OkHttp client and 1–3 explicit discovery relay URLs. Public operations return
Futures; never wait on them from the Android main thread or a coordinator callback.
All callbacks run on the coordinator worker, outside relay transport locks. They
must be quick and must not log message contents. Issue notifications are coalesced
and can wait behind current network or identity work. Relay-list arguments are
snapshotted when an operation is submitted.

1. `announce(inboxUrls)` signs a kind-10050 announcement and publishes it to the
   discovery relays. To update it, call again with a strictly newer timestamp.
   This client treats an empty list as a withdrawn inbox. Per-relay results may differ; an update is
   not atomic across servers. The application must check each result.
2. `send(recipientHex, text)` asks discovery servers for both people's signed
   inbox lists. It checks signatures, authors, timestamps and URLs, then picks
   the newest valid list (lowest event ID breaks equal-timestamp ties).
3. If either list is missing or empty, sending fails before any message is
   published. Discovery servers are never used as guessed inbox destinations.
4. Existing NIP-17 code makes two encrypted envelopes containing the same
   message: one for the recipient and one for the sender. Each goes exclusively
   to its owner's advertised inbox servers.
5. `startReceiving(inboxUrls)` listens for kind-1059 envelopes with the local
   public key in their `p` tag. The caller explicitly configures these URLs;
   publishing an announcement does not automatically change subscriptions.
6. Existing cryptography verifies the envelope and sender's seal, decrypts the
   message and checks sender/recipient consistency before `onReceived` runs.
   That callback means **locally received**. It also receives valid sender copies.

`onPublication` reports QUEUED (admitted to a per-relay attempt), SUBMITTED
(WebSocket accepted the bytes), RELAY_ACCEPTED or RELAY_REJECTED (matching relay
OK). FAILED means submission failed; UNKNOWN means submission happened but no
conclusive acknowledgment arrived. Relay acceptance is neither delivery nor a
read receipt. There is no aggregate “delivered” state. Future queue rejection and
missing inbox errors are separate from per-relay publication outcomes.

AUTH challenges and auth-required OK/CLOSED responses raise
AUTHENTICATION_REQUIRED. No private-key authentication is sent. CLOSED inbox
subscriptions and connection errors are exposed, not treated as successful
listening. Reconnects restore active subscriptions, but never replay publications.

## Bounds and limitations

- TLS `wss://` only; credentials, fragments and invalid ports are rejected.
  HTTP redirects are disabled so a relay cannot move traffic to another endpoint.
- Application policy accepts announcements up to 30 days old and 5 minutes into
  the future. This expiry is not mandated by NIP-17. Discovery accepts lists
  containing more than three relays; a send uses the first three distinct validated
  URLs from the selected announcement. Local configuration remains capped at three.
  `NostrInbox` supports an injected clock and age policy for independent use.
- At most three receiving connections plus one temporary operation connection.
  A temporary connection may use the same URL as a receiving connection; this
  bounded design does not yet share a socket for every use of the same relay,
  as recommended by NIP-01. Closing either connection does not close the other.
- One subscription per connection, one worker, 32 pending work items. Receive
  queue overflow is reported; it does not mark the message as processed. The
  issue backlog holds at most 64 distinct relay/issue pairs. Discovery response
  queues hold 256 items; publication response queues hold 32.
- Discovery waits up to five seconds per server by default and requests 64
  candidates. If a relay returns more, selection still considers every event
  consumed before EOSE, retaining only the best valid candidate. Batches with
  queue overflow or without EOSE are discarded. Unreachable or
  withholding discovery servers can hide newer announcements; no global latest
  event guarantee is possible. There is no persistent cache or rollback history.
- Publishing waits up to five seconds to connect and five more for OK per relay.
  Timeout is configurable (100–60,000 ms). Operations run sequentially.
- Duplicate suppression remembers the last 4,096 authenticated rumor IDs in
  memory. Old entries or a restart can therefore cause repeat local callbacks.
- `startReceiving(emptyList())` stops listening. Reconfiguration drops stale
  queued callbacks. `close()` cancels all sockets and work; the HTTP client remains
  caller-owned. Running and queued Futures are cancelled on shutdown. A running
  Keystore call cannot be forcibly stopped; after it returns the cancelled work
  cannot publish or invoke a receive callback. close waits for an already-running
  user callback, so callbacks must return promptly. Active receiving subscriptions
  restore with the relay client's bounded reconnect budget; temporary operations
  do not reconnect. Exhausted inbox retries require application intervention.
- One-to-one text only. No contacts, switching, message persistence, UI wiring,
  authentication signing, delivery receipts or read receipts are implemented.

## Verification

`NostrMessagingCoordinatorTest` uses local TLS MockWebServer WebSockets only.
It covers list validation/newest selection, invalid and outdated discovery events,
recipient/sender routing and decryption, announcement publication, encrypted
incoming messages, malformed wire input, duplicate suppression after reconnect,
missing lists, relay rejection, connection failure, and AUTH with unknown outcome.
The existing relay tests additionally cover CLOSE cleanup and bounded reconnects.
Review regressions cover candidates beyond the requested discovery limit, longer
inbox lists, caller mutation of queued configuration, work-queue rejection,
shutdown cancellation, a non-interruptible identity read, worker-only callbacks,
redirect rejection, auth-required OK/CLOSED responses, subscription bounds,
receive-queue saturation, and disconnects with no publication replay. Identity
unit tests check scoped key buffers are wiped on both success and exception;
NIP-17 tests exercise incoming impersonation and malformed encrypted layers.

Device instrumentation and real Android Keystore timing were not tested in this
review. Public relay compatibility was not tested. All network tests use local
TLS mock relays.

Review verification: `ANDROID_HOME=/Users/riakate/Library/Android/sdk ./gradlew test assembleDebug`
completed successfully. The configured local unit suite ran 69 tests: 0 failures,
0 errors, 0 skipped (14 tests added during review). Debug APK assembly succeeded.
`git diff --check` also passed. No commits or pushes were made.

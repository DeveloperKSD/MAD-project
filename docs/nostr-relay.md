# Nostr relay transport

This stage supplies an isolated transport API; it does not connect the chat UI or BLE
runtime to Nostr and does not implement private messaging.

## Files

- `NostrRelayClient.kt`: one secure WebSocket connection, state callbacks, active
  subscription registry, bounded reconnect scheduler, cancellation and cleanup.
- `NostrRelayProtocol.kt`: EVENT/REQ/CLOSE encoders and five typed relay responses.
- `NostrEventCodec.kt`: adds strict event structure decoding; the relay decoder then
  checks both the calculated ID and Schnorr signature with the existing signer.
- `NostrRelayTest.kt`: protocol unit tests and local TLS MockWebServer integration tests.
- The manifest adds INTERNET; Gradle adds OkHttp and test-only MockWebServer/TLS helpers.

## Flow and ownership

Create a client with a `wss://` URL and an application-owned `OkHttpClient`.
Register callbacks and call `subscribe(id, filters)` before or after `connect()`.
Each REQ accepts one or more JSON filter objects; callers provide NIP-01 filters.
Using the same ID replaces the subscription. EOSE marks the end of stored events;
its subscription remains live. CLOSED removes the subscription. Cancellation sends
CLOSE when connected and prevents future restoration. Responses for inactive IDs
are ignored. Other valid responses are surfaced through `onResponse`.

`publish(signedEvent)` checks the signature and returns whether OkHttp queued the
EVENT. A later Acceptance response identifies whether the relay accepted or
rejected that event. Neither result proves delivery to another person. There is no
recipient-delivery status, offline publication queue, automatic publication replay,
or acknowledgement timeout in this layer; disconnecting may leave an outcome unknown.

Callbacks run under the client lock on the calling, transport, or retry thread
and must be short and nonblocking. Active subscriptions are restored before the
CONNECTED callback, so subscribing there sends one REQ. The pending retry is
registered before the ERROR callback, so calling connect() there cannot bypass it. Callback exceptions are isolated. Call `close()` when the owner is done:
it cancels the socket, subscriptions and retries and shuts down its scheduler.
Closing is terminal and idempotent. The caller retains ownership of the shared
OkHttp dispatcher and connection pool.

Unexpected closure or failure enters ERROR and retries at 1, 2, 4, 8 and 16 seconds
by default (configurable delay cap and attempt count). Restored connections resend
active REQs, so history may repeat; consumers may deduplicate using event IDs.
The retry budget is not reset on each successful handshake, preventing endless
flapping. After exhaustion, an explicit connect() starts a new budget. Active
subscriptions remain registered until cancelled, CLOSED, or client closure.

Incoming text is limited to 256 KiB UTF-8 by default, with a JSON nesting limit of
32. Event timestamps and kinds must use valid nonnegative JSON integer syntax;
leading zeros, plus signs, quoted numbers and fractional/exponent forms are rejected.
Malformed/unknown messages and unauthenticated events are discarded. Oversized
or binary messages terminate the connection and use the same limited retry budget.
**Limitation:** OkHttp buffers complete WebSocket messages before its callback;
these limits bound parsing and acceptance, not the network library's initial
allocation. A strict pre-buffer allocation cap would require a different transport
or support in OkHttp. No trust-all TLS or plaintext test escape hatch is added.

## Verification

Command (using the installed SDK):

```
ANDROID_HOME=/Users/riakate/Library/Android/sdk ./gradlew test assembleDebug
```

Final result: BUILD SUCCESSFUL; 34 tests, 0 failures, 0 errors, 0 skipped
(22 existing tests plus 12 relay tests). Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Tests cover encoding, verified decoding and tampering, all five relay responses,
malformed/oversized inputs, TLS connection, publishing, acceptance/rejection,
cancellation, server CLOSED, retry exhaustion, restoration and terminal cleanup.
Review regressions also cover callback-triggered duplicate connections/REQs,
closing with a pending retry, failed WebSocket handshakes, and malformed event
numbers. The duplicate connection, duplicate REQ, and malformed-number tests
failed against the original implementation and pass with the fixes.
All sockets used by tests connect only to local MockWebServer instances with a
locally trusted certificate. No public relays or device instrumentation were used.
Live relay interoperability and Android device lifecycle behavior were not tested.
Initial verification caught two test-only issues (JUnit return type and comparing
independently randomized signatures); both were corrected before the passing run.

Protocol reference: https://github.com/nostr-protocol/nips/blob/master/01.md

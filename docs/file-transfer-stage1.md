# Hybrid file transfer — Stage 1 engine

This stage is a **local, transport-independent binary engine**, not working wireless file sharing. No BLE, Wi-Fi Direct, Nostr, chat UI, picker, service, permissions, or background worker is connected to it. No existing scheduler or history schema was modified.

## Existing architecture inspected

- `bluetooth/BleFrameCodec.kt`: big-endian frames, bounded per-packet in-memory reassembly, 4,352-byte packet ceiling, fragment/assembly limits and expiry. It is NOT a whole-file reassembler.
- `mesh/PacketCodec.kt`: signed version-2 mesh packets, 4,096-byte payload ceiling, TTL bounds. `mesh/PeerManager.kt` maintains bounded routes; `MeshRuntime.kt` verifies/deduplicates/relays packets and handles text acknowledgements.
- `bluetooth/BleTransport.kt`: bounded FIFO write/notification queues and serialized GATT operations. Admission is not peer delivery. These queues and operations are untouched.
- `mesh/MessageStore.kt`: bounded encrypted Nearby text history. Internet history uses a separate encrypted Room repository and application-owned `InternetRuntime`; background catch-up shares its ownership/history gates. None of these stores or runtimes are used by this engine.

## Public API and ownership

Production code is isolated under `in.yashd.bitchat.transfer` and has no Android dependencies. Create `FileTransferEngine(privateDirectory, limits)` only on a worker thread. A future Android integration must supply an app-private **no-backup** parent such as `context.noBackupFilesDir`; never shared/external storage or a peer-supplied directory. Tests use private temporary directories.

The engine creates its own `relay-transfers-v1` subdirectory with owner-only permissions. A process/file lock prevents two engines from writing the same store. Fixed filenames and UUID directory names prevent peer filenames from becoming paths; symlink paths and unrecognized entries are rejected rather than followed or recursively deleted. The caller's source stream and export stream remain caller-owned.

Sending:

1. `prepareOutgoing(input, locallySelectedProfile, chunkBytes)` counts, hashes and copies the source into a private disk snapshot. It reads at most one byte beyond the policy limit and never writes that byte. The source is not changed or closed. Failed imports remove only their own staging record.
2. Send the returned immutable `TransferManifest` over a future authenticated control channel. It contains protocol version, 128-bit UUID, byte size, chunk size, computed total chunk count and expected SHA-256. It contains no filename or filesystem path.
3. `start(id)` verifies the snapshot. Pull one encoded frame at a time with `readChunk(id, index)` only when an adapter has capacity. There is no eager list of file bytes or all packets.
4. Persist authenticated receiver receipts with `acknowledge`. `missingChunks` returns at most 128 indices, with a caller-provided starting index for paging. The same API tracks missing incoming chunks.
5. Only `confirmRemoteCompletion(id, expectedSha256)` after **all** durable chunk ACKs can complete an outgoing transfer. Empty outgoing files still require a remote completion signal. Queue admission, GATT callbacks, or the last chunk ACK are not completion.

Receiving:

1. Authorize the peer and user consent in Stage 2, select the profile locally, and call `beginIncoming`. Repeated identical offers are idempotent. Changed manifests for an existing ID are rejected; failed/cancelled IDs cannot be resurrected by replay.
2. Call `start`, then `accept(packet)`. Header, transfer ID, count, index, exact final-chunk length and state are checked before writes. Invalid packets do not allocate new transfers.
3. Out-of-order chunks are written directly at `index * chunkBytes`. Equal duplicates are ignored; conflicting duplicates fail incomplete transfers. A conflicting late packet cannot destroy a verified completed file.
4. Persist payload bytes, per-chunk local receipt digest and receipt bitmap before reporting a stored receipt. Once all chunks exist, stream the file through SHA-256. A mismatch fails the transfer and removes its payload, without exposing it as completed.
5. Use `copyCompletedTo` to export verified incoming bytes without exposing temporary paths. The source is checked again before export. Export destination errors preserve the verified local file for retry. The caller must not expose partial exports as completed files.

All engine operations are synchronized and blocking. A future integration must not call them from the main thread, a Bluetooth callback, or inside an existing Room transaction.

## Version 1 DATA packet

All multi-byte integers use **network byte order (big-endian)**. Each call to the decoder receives exactly one packet; future stream adapters must establish packet boundaries before allocating/decoding. File content stays binary; no Base64 is used.

| Offset | Bytes | Field | Validation |
|---:|---:|---|---|
| 0 | 4 | Magic | ASCII `RLFT`, hex `52 4c 46 54` |
| 4 | 1 | Version | Exactly `1` |
| 5 | 1 | Reserved flags | Exactly `0` |
| 6 | 2 | Header length | Exactly `36` |
| 8 | 16 | Transfer UUID | Most-significant 64 bits, then least-significant 64 bits |
| 24 | 4 | Chunk index, unsigned | `0 <= index < totalChunks` |
| 28 | 4 | Total chunks, unsigned | `1..32768` |
| 32 | 4 | Payload length, unsigned | `1..16384` |
| 36 | variable | Binary payload | Exactly the declared byte count |

Minimum DATA frame: 37 bytes. Maximum: 16,420 bytes. Signed-negative representations of unsigned fields, integer overflow values, unknown versions/flags, truncated frames, and trailing bytes are rejected before payload allocation. The receiver additionally compares geometry against the accepted manifest. Unknown versions have no permissive fallback.

Default payload: 1,024 bytes. `ceil(fileBytes / chunkBytes)` is computed using bounded long arithmetic. The last chunk is the exact remainder. A zero-byte file has zero chunks and SHA-256 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`; it uses the offer/completion path and no zero-length DATA frame.

Golden vector: UUID `00112233-4455-6677-8899-aabbccddeeff`, index 1, total 3, payload `00 ff 7f`:

```text
524c4654 01 00 0024 00112233445566778899aabbccddeeff 00000001 00000003 00000003 00ff7f
```

Only DATA has a wire encoding in Stage 1. `TransferTransport` and typed accept/ACK/missing/pause/resume/complete/cancel controls are contracts, not implemented or serialized adapters. `TransferQueueAdmission.ACCEPTED` means queue admission only. Future adapters must check their own maximum packet budget, including mesh signatures, encryption and framing overhead; a default chunk fitting the raw mesh payload is not a measured radio capability.

## Starting limits and resource budgets

`TransferLimits` centralizes configurable defaults:

| Local profile | Maximum file size | Meaning |
|---|---:|---|
| Direct BLE | 1 MiB / 1,048,576 bytes | Provisional policy |
| Multi-hop BLE | 250 KiB / 256,000 bytes | Provisional policy |
| Wi-Fi Direct | 25 MiB / 26,214,400 bytes | Provisional policy |

These are **not tested throughput/range/reliability claims**. The engine does not select or connect a transport. Stage 2 must negotiate transport and chunk size and enforce the actual locally selected profile; a remote peer must never grant itself the larger Wi-Fi limit.

Hard parsing limits are 32,768 chunks and 16 KiB per chunk. Default store budget is four retained records and 64 MiB of logical file storage, including source snapshots, incoming payload reservations, receipts and checkpoint allowance. Incoming reservation includes 32 bytes per chunk for receipt digests. Each record reserves 16 KiB for its old and replacement checkpoints, including during later writes. Outgoing imports conservatively reserve the selected profile/chunk-count maximum before reading; they can be rejected even if the eventual file might be smaller. Completed, failed, cancelled and unreadable records occupy slots until explicitly discarded or separately recovered.

Memory use consists of bounded chunk buffers, an 8 KiB streaming buffer, at most an 8 KiB checkpoint, and a maximum 4 KiB receipt bitmap per transfer. Chunk hashes remain on disk. No entire large file or list of all payloads is retained in memory. Source and destination stores have separate budgets; future concurrent send/receive use within one store shares that store's budget. Logical quotas cannot guarantee free physical disk space; I/O failure is handled separately.

## State, cleanup and recovery

| State | Rule |
|---|---|
| `PENDING` | Prepared outgoing snapshot or accepted incoming offer; no chunk traffic admitted yet. |
| `TRANSFERRING` | Explicitly started; chunk read/receipt/ACK APIs available for the relevant direction. |
| `PAUSED` | Progress retained; no chunk admission. Explicit `start` resumes after rechecking stored data. |
| `COMPLETED` | Incoming whole-file hash verified, or authenticated remote confirmation asserted for outgoing. Retained until explicit discard. |
| `FAILED` | Hash mismatch, conflicting incomplete duplicate, missing data, or storage failure. Payload/receipts are removed when filesystem operations succeed; a small failure record remains. |
| `CANCELLED` | Persist cancellation before removing payload/receipts. Replays cannot restart this record. Explicit discard removes its record. |

- `close` pauses active records and releases the lock. It is idempotent. It does not erase pending/paused/completed files.
- Thread interruption pauses an established active transfer and preserves committed progress. An interrupted source import has no manifest yet and is cleaned up; the caller reopens its original source.
- Checkpoints have a schema version, bounds, bitmap and SHA-256 checksum. They are written to a fixed private staging file, fsynced and renamed in the same directory. Payload and receipt digest are fsynced **before** the corresponding receipt bit is committed. A chunk must not be ACKed before `accept` returns successfully.
- After restart, an in-flight record becomes paused. Every incoming recorded chunk is checked against its local receipt digest; torn/corrupt chunks become missing and can be retransmitted. Uncheckpointed bytes do not count as received. Outgoing snapshots and completed incoming files are fully rehashed with bounded memory.
- Missing payloads are failed, never replaced with empty files. Corrupt/unknown-version checkpoints are preserved and reported through `recoveryIssues`; the ID cannot be reused over them. There is no automatic purge of unknown recovery data.
- Cancelling or failing retains a bounded metadata record. `discard` removes only known files inside one terminal transfer's directory. No TTL, automatic eviction, recursive deletion, or access to unrelated caller files is introduced. A future retention UI/job must explicitly release old terminal records.
- Disk errors can prevent checkpointing or cleanup. The operation throws instead of claiming receipt/completion. Recovery uses the last committed checkpoint; corrupt state is not silently reset. Same-directory rename provides process-crash recovery on supported filesystems; abrupt device power-loss/directory-entry durability has not been device-tested.

## Security boundary and limitations

SHA-256 is integrity checking, **not sender authentication or encryption**. An attacker who can replace both an offer and its hash can substitute data. Stage 2 must authenticate the peer and immutable manifest, authorize controls, protect chunks against tampering, and bind transfer IDs to peer/session identity. Receipts stored locally only detect disk damage; they are not network MACs. A newly corrupted network payload may be detected only at final file verification, which fails and cleans the transfer rather than identifying a particular bad chunk.

Temporary file permissions are private, but file contents are not encrypted by this engine. It may operate over opaque already-encrypted bytes later. Exported bytes and filenames/content types remain the caller's responsibility. Deleting a file is not a promise of physical secure erasure on flash storage. Root access, a compromised app process, hostile filesystems, encrypted-at-rest integration, content preview safety and wireless performance are outside Stage 1.

## Stage 2: BLE priority scheduler and adapters (design only)

1. Keep existing text messaging behavior. Add explicit logical traffic classes: routing/control and text first, file DATA last. Transfer ACK/pause/cancel controls also need prompt service. Bound control traffic too; never let a peer turn ACKs into an unbounded high-priority flood.
2. Use separate bounded queues with reserved capacity for text/control. Begin conservatively with at most four queued and four in-flight file chunks per adapter, plus a global/per-peer budget. Pull new file bytes only after queue/window capacity becomes available; return `BACKPRESSURE` immediately when full. Do not enqueue a whole file through `BleTransport.send`.
3. A successful GATT operation is not a durable receiver ACK. Authenticate chunk-index ACKs, track outstanding indices, use bounded timeouts/retries with backoff, and pause on retry exhaustion. Retransmit only missing chunks using paged missing-index controls. Loss of a COMPLETE reply should permit an idempotent completion exchange without resending a whole verified file.
4. Pause file admission immediately for text/control demand or link loss; stop filling the lower-level FIFO. Already submitted radio operations cannot be preempted, so keep that window small. Resume fairly across peers only after text/control is serviced. Add starvation and flood protections.
5. Add direct BLE first, with explicit consent and authenticated control serialization/version negotiation. Preserve existing mesh signatures, MTU framing, packet-size limits, duplicate handling and routing TTL. Multi-hop files need separate routing/forwarding quotas and loop/replay controls; do not broadcast chunks to all links indiscriminately.
6. Add Wi-Fi Direct discovery/permissions, authenticated peer/session binding and a bounded length-prefixed socket adapter separately. Validate each announced length before allocation. A Wi-Fi connection/handshake or offer is not completed file delivery. Switching transport/profile must revalidate file policy and immutable chunk geometry; do not let a Wi-Fi-sized transfer silently fall back to BLE.
7. Test two physical devices, loss/duplication/disconnects, text latency under load, cancellation, process restart and disk pressure before claiming any wireless support. No such support is claimed here.

## JVM verification

`ChunkProtocolTest` fixes exact offsets/byte order with a golden vector and rejects malformed/overflow headers, bounds, versions, flags and control sizes. `FileTransferEngineTest` covers binary shuffled round trips, missing and duplicate chunks, hash failures, empty/oversized files, quotas, cancellation/cleanup, exact final chunks, sender ACK semantics, export failures, and a generated streamed 25 MiB source. `FileTransferRecoveryTest` covers interrupted/restarted transfers, receipt revalidation, torn/unknown checkpoints, missing files, immutable source snapshots, exclusive locks, private permissions and unsafe path rejection.

Run with the project's configured Android SDK and JDK 21:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:compileReleaseKotlin
```

No emulator or physical radio tests are needed to claim local Stage 1 engine behavior. They will be necessary for Android filesystem/power-loss validation and Stage 2 transport integration.

Validation in this workspace: **190 JVM tests passed** (38 new transfer tests and 152 existing regression tests), zero failures/skips; `:app:assembleDebug` and `:app:compileReleaseKotlin` passed with JDK 21. Private-file permissions were exercised on the local JVM filesystem. No Android/radio integration, emulator wireless test, or physical-device file transfer was performed.

New files only:

- `app/src/main/java/in/yashd/bitchat/transfer/ChunkProtocol.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/TransferModels.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/TransferTransport.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/TransferStore.kt`
- `app/src/main/java/in/yashd/bitchat/transfer/FileTransferEngine.kt`
- `app/src/test/java/in/yashd/bitchat/transfer/ChunkProtocolTest.kt`
- `app/src/test/java/in/yashd/bitchat/transfer/FileTransferEngineTest.kt`
- `app/src/test/java/in/yashd/bitchat/transfer/FileTransferRecoveryTest.kt`
- `docs/file-transfer-stage1.md`

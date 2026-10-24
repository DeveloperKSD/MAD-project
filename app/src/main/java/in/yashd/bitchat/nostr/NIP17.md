# NIP-17 text packaging

Specifications reviewed on 2026-10-21:
- https://github.com/nostr-protocol/nips/blob/master/17.md
- https://github.com/nostr-protocol/nips/blob/master/44.md
- https://github.com/nostr-protocol/nips/blob/master/59.md

`Nip17.createMessage(senderSecret, recipientPublicKey, content)` creates two
signed kind-1059 events: `recipientCopy` and `senderCopy`. Publish/store only
those encrypted events when transport is implemented. The same kind-14 rumor
is used for both copies: its sender, actual timestamp, plain text, recipient
p-tag, and calculated ID are enclosed without a signature. Each copy has its
own kind-13 seal, encrypted to that copy's owner and signed by the sender.
Each seal is encrypted again into a gift wrap with a fresh temporary signing
key. The temporary key is wiped in a finally block. Both seals and wraps get
independent random timestamps within the two days before the rumor timestamp.
The optional timestamp argument is the actual creation time, in Unix seconds.

`decodeMessage(giftWrap, localSecret, expectedSender, expectedRecipient)` verifies
the wrap before decrypting, then verifies the sender's seal before decrypting
the rumor. It checks the rumor ID, unsigned status, sender/seal agreement and
one-recipient conversation, including a valid recipient curve point. For sender copies, expectedRecipient remains the
original recipient; localSecret belongs to the sender. The outer p-tag must
match the local key. Supply expected identities from trusted conversation
context. Returned UnsignedNostrEvent contains plaintext; its ID can be obtained
with NostrEventCodec.calculateId. Do not log or persist plaintext unnecessarily.
Decrypted JSON is limited to 32 nesting levels before parsing to prevent stack
exhaustion. Brackets inside quoted message text do not count as nesting.
Parser failures are sanitized so their exceptions cannot disclose decrypted JSON.

This is a blocking API intended for a worker thread. Caller-owned key arrays
are never retained or cleared by this class. JVM strings cannot be reliably
wiped; plaintext and JSON strings exist transiently during packaging/decoding.
No storage or identity-key access API was added. Existing identity storage does
not expose a message-encryption hook; connecting it is a later integration step.

Scope: one-to-one kind-14 text only, including self-messages. Groups, files,
reactions, ephemeral wraps, discovery, relay publication, and UI are unsupported.
Optional non-recipient rumor tags are preserved on decode. Existing NIP-44
1 MiB plaintext limits apply to each serialized layer, so the effective message
limit is smaller and depends on JSON escaping and encryption overhead. Oversized
layers fail rather than being truncated. Historical messages are accepted: the
randomization window is enforced when creating events, not used as an expiry
rule when decoding. Randomness quality/key uniqueness is provided by SecureRandom;
a decoder cannot prove that another client used a fresh key or random timestamp.

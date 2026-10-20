The unmodified `bip340-test-vectors.csv` fixture comes from:
https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv

BIP-340 is licensed under the BSD-2-Clause license:
https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki

`SchnorrSignatureTest` exercises all 15 vectors with 32-byte messages,
including all invalid-signature/public-key cases and four exact signing cases.
Vectors 15–18 cover variable-length messages, outside NIP-01's 32-byte hash API.

The event hash fixture in `NostrEventCodecTest` is a project fixture independently
calculated with Python's hashlib, not a published Nostr test vector.

`nip59-example.json` contains the published seal and gift wrap from the official
NIP-59 example: https://github.com/nostr-protocol/nips/blob/master/59.md
Retrieved 2026-10-21. The example's trailing comma was removed to make valid JSON;
field values are unchanged. These are public example keys, not application keys.
Nip17Test verifies/decrypts both layers, checks the rumor hash and author, and
rejects the example's kind-1 rumor as an unsupported NIP-17 text message.
NIP-17 currently provides message shapes rather than encrypted test vectors.

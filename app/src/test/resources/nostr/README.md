The unmodified `bip340-test-vectors.csv` fixture comes from:
https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv

BIP-340 is licensed under the BSD-2-Clause license:
https://github.com/bitcoin/bips/blob/master/bip-0340.mediawiki

`SchnorrSignatureTest` exercises all 15 vectors with 32-byte messages,
including all invalid-signature/public-key cases and four exact signing cases.
Vectors 15–18 cover variable-length messages, outside NIP-01's 32-byte hash API.

The event hash fixture in `NostrEventCodecTest` is a project fixture independently
calculated with Python's hashlib, not a published Nostr test vector.

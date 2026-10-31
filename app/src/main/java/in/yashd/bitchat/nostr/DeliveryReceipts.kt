package `in`.yashd.bitchat.nostr

/** App-level delivery acknowledgements inside the existing NIP-17 kind-14 scope.
 * A receipt is a kind-14 rumor with an empty body and a relay-receipt tag that only
 * this app constructs; user-typed message text can never create rumor tags, so typed
 * messages can never be misclassified as receipts. Other NIP-17 clients see an empty
 * chat message at worst; receipts are best-effort and never required for correctness. */
object DeliveryReceipts {
    const val TAG = "relay-receipt"
    const val DELIVERED = "delivered"
    private val rumorIdPattern = Regex("[0-9a-f]{64}")

    /** Returns the original rumor id this rumor acknowledges, or null when it is not a
     * well-formed delivered receipt. Empty body and exact tag shape are both required. */
    fun receiptTarget(rumor: UnsignedNostrEvent): String? {
        if (rumor.kind != 14 || rumor.content.isNotEmpty()) return null
        return rumor.tags.firstOrNull { it.size == 3 && it[0] == TAG && it[1] == DELIVERED }
            ?.get(2)?.takeIf { rumorIdPattern.matches(it) }
    }
}

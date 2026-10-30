package `in`.yashd.bitchat.nostr

/** Outer gift-wrap times are randomized up to 48 hours backwards. Re-read 72 hours
 * before the last completed check, including a day for delayed publication/clock skew.
 * Seven days is a deliberate hard retention horizon, not a claim of full history sync.
 * Never advance from a relay timestamp or a partially completed check.
 */
data class InboxReplayWindow(val since: Long, val until: Long,
                           val pageSize: Int = 128, val maxPages: Int = 12) {
    init { require(since >= 0 && until >= since && pageSize in 2..128 && maxPages in 1..12) }
    companion object {
        const val OVERLAP_SECONDS = 3 * 24 * 60 * 60L
        const val HORIZON_SECONDS = 7 * 24 * 60 * 60L
        fun at(nowSeconds: Long, lastSuccessSeconds: Long): InboxReplayWindow {
            require(nowSeconds >= 0)
            val base = if (lastSuccessSeconds in 1..nowSeconds) lastSuccessSeconds else nowSeconds
            return InboxReplayWindow(maxOf(0, nowSeconds - HORIZON_SECONDS, base - OVERLAP_SECONDS), nowSeconds)
        }
    }
}

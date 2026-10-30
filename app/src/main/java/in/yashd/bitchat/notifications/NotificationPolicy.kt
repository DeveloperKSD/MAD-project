package `in`.yashd.bitchat.notifications

object NotificationPolicy {
    fun shouldAlert(permission: Boolean, muted: Boolean, viewing: Boolean) = permission && !muted && !viewing
    // Conservative session boundary: older verified messages are still stored, but never alert.
    fun isLive(createdAtMs: Long, sessionStartedAtMs: Long, now: Long) =
        createdAtMs >= sessionStartedAtMs && createdAtMs <= now + 60_000
}

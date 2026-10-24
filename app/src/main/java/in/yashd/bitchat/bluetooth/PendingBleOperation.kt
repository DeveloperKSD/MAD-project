package `in`.yashd.bitchat.bluetooth

/** Used only on the transport's owning looper, for both writes and notifications. */
internal class PendingBleOperation<T : Any>(
    private val schedule: (Runnable) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onTimeout: (T) -> Unit
) {
    var owner: T? = null
        private set
    private var timeout: Runnable? = null
    private var generation = 0

    fun start(value: T) {
        check(owner == null)
        owner = value
        val epoch = ++generation
        timeout = Runnable {
            if (epoch == generation) {
                clear()
                onTimeout(value)
            }
        }.also(schedule)
    }

    fun finish(value: T): Boolean {
        if (owner != value) return false
        clear()
        return true
    }

    fun clear() {
        generation++
        timeout?.let(cancel)
        timeout = null
        owner = null
    }
}

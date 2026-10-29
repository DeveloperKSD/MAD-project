package `in`.yashd.bitchat.bluetooth

import java.util.ArrayDeque
import java.util.UUID

/** Owning-looper queue. Existing text/control capacity cannot be consumed by file DATA.
 * At most one already-submitted GATT operation can precede newly arrived high priority work.
 */
internal class PriorityFrameQueue<T>(private val normalCapacity: Int = 4096, private val fileCapacity: Int = 256) {
    data class Frame<T>(val target: T, val bytes: ByteArray, val transferId: UUID?)
    private val normal = ArrayDeque<Frame<T>>()
    private val files = ArrayDeque<Frame<T>>()
    init { require(normalCapacity > 0 && fileCapacity > 0) }
    val size: Int get() = normal.size + files.size
    fun offer(target: T, frames: List<ByteArray>, transferId: UUID? = null): Boolean {
        val queue = if (transferId == null) normal else files
        val capacity = if (transferId == null) normalCapacity else fileCapacity
        if (frames.isEmpty() || frames.size > capacity - queue.size) return false
        frames.forEach { queue.addLast(Frame(target, it, transferId)) }
        return true
    }
    fun poll(): Frame<T>? = normal.pollFirst() ?: files.pollFirst()
    fun removeTarget(matches: (T) -> Boolean) { normal.removeAll { matches(it.target) }; files.removeAll { matches(it.target) } }
    fun removeTransfer(id: UUID) { files.removeAll { it.transferId == id } }
    fun clear() { normal.clear(); files.clear() }
}

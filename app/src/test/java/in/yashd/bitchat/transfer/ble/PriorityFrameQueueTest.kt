package `in`.yashd.bitchat.transfer.ble

import `in`.yashd.bitchat.bluetooth.PriorityFrameQueue
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PriorityFrameQueueTest {
    @Test fun fullFileQueueCannotConsumeTextOrControlCapacity() {
        val queue = PriorityFrameQueue<String>(normalCapacity = 2, fileCapacity = 4)
        val id = UUID.randomUUID()
        assertTrue(queue.offer("peer", List(4) { byteArrayOf(it.toByte()) }, id))
        assertFalse(queue.offer("peer", listOf(byteArrayOf(9)), id))
        assertTrue(queue.offer("peer", listOf("text".toByteArray(), "control".toByteArray())))
        assertEquals("text", queue.poll()!!.bytes.decodeToString())
        assertEquals("control", queue.poll()!!.bytes.decodeToString())
        assertEquals(4, queue.size)
    }
    @Test fun textLatencyIsAtMostOneAlreadySubmittedFrameNotTheFileBacklog() {
        val queue = PriorityFrameQueue<Int>(fileCapacity = 256)
        val id = UUID.randomUUID()
        queue.offer(1, List(256) { byteArrayOf(1) }, id)
        val inFlight = queue.poll()!! // Cannot be preempted once submitted to GATT.
        queue.offer(1, listOf(byteArrayOf(99)))
        assertEquals(1.toByte(), inFlight.bytes[0])
        assertEquals(99.toByte(), queue.poll()!!.bytes[0])
        assertEquals(255, queue.size) // No real-time/physical radio latency claim.
    }
    @Test fun admissionIsAtomicAndCancelOrDisconnectPurgesOnlyRelevantFrames() {
        val queue = PriorityFrameQueue<String>(normalCapacity = 2, fileCapacity = 3)
        val first = UUID.randomUUID(); val second = UUID.randomUUID()
        queue.offer("a", listOf(byteArrayOf(1)), first)
        assertFalse(queue.offer("a", List(3) { byteArrayOf(1) }, first))
        queue.offer("b", listOf(byteArrayOf(2)), second)
        queue.offer("a", listOf(byteArrayOf(3)))
        queue.removeTransfer(first)
        assertEquals(2, queue.size)
        queue.removeTarget { it == "a" }
        assertEquals("b", queue.poll()!!.target)
        assertNull(queue.poll())
    }
}

package `in`.yashd.bitchat

import `in`.yashd.bitchat.bluetooth.PendingBleOperation
import org.junit.Assert.*
import org.junit.Test

class PendingBleOperationTest {
    @Test fun disconnectAndLateTimeoutCannotBlockOrFinishTheNextLink() {
        val timers = mutableListOf<Runnable>()
        val cancelled = mutableListOf<Runnable>()
        val expired = mutableListOf<String>()
        val operation = PendingBleOperation<String>({ timers.add(it) }, { cancelled.add(it) }, { expired.add(it) })
        operation.start("phone-one")
        assertFalse(operation.finish("unrelated-phone"))
        assertTrue(operation.finish("phone-one"))
        assertEquals(1, cancelled.size)
        operation.start("phone-two")
        timers.first().run()
        assertEquals("phone-two", operation.owner)
        assertTrue(expired.isEmpty())
        timers.last().run()
        assertNull(operation.owner)
        assertEquals(listOf("phone-two"), expired)
        operation.start("phone-three")
        operation.clear()
        timers.last().run()
        assertNull(operation.owner)
        assertEquals(1, expired.size)
    }
}

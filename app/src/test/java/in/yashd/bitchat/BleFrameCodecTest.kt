package `in`.yashd.bitchat

import `in`.yashd.bitchat.bluetooth.BleFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BleFrameCodecTest {
    @Test
    fun reassemblesPacketAtMinimumBleMtu() {
        val packet = ByteArray(307) { it.toByte() }
        val frames = BleFrameCodec.encode(packet, maxValueBytes = 20, messageId = 7)
        val reassembler = BleFrameCodec.Reassembler()

        frames.dropLast(1).forEach { assertNull(reassembler.accept("peer", it)) }
        assertArrayEquals(packet, reassembler.accept("peer", frames.last()))
    }

    @Test fun outOfOrderFragmentsAndIdenticalDuplicatesAreSafe() {
        val packet = ByteArray(100) { it.toByte() }
        val frames = BleFrameCodec.encode(packet, 30, 42)
        val receiver = BleFrameCodec.Reassembler()
        assertNull(receiver.accept("a", frames.last()))
        assertNull(receiver.accept("a", frames.last()))
        frames.drop(1).dropLast(1).reversed().forEach { assertNull(receiver.accept("a", it)) }
        assertArrayEquals(packet, receiver.accept("a", frames.first()))
    }

    @Test fun rejectsConflictingDuplicatesMalformedHeadersAndOversizedPackets() {
        val frames = BleFrameCodec.encode(ByteArray(30), 20, 7)
        val receiver = BleFrameCodec.Reassembler()
        receiver.accept("a", frames.first())
        assertThrows(IllegalArgumentException::class.java) {
            receiver.accept("a", frames.first().copyOf().apply { this[lastIndex] = 1 })
        }
        assertThrows(IllegalArgumentException::class.java) { receiver.accept("a", ByteArray(11)) }
        assertThrows(IllegalArgumentException::class.java) { BleFrameCodec.encode(ByteArray(4353), 20, 1) }
    }

    @Test fun assembliesAreBoundedExpireAndClearPerLink() {
        val receiver = BleFrameCodec.Reassembler()
        repeat(8) { receiver.accept("a", BleFrameCodec.encode(ByteArray(20), 20, it).first(), 0) }
        val next = BleFrameCodec.encode(ByteArray(20), 20, 9)
        assertThrows(IllegalArgumentException::class.java) { receiver.accept("a", next.first(), 1) }
        assertNull(receiver.accept("b", next.first(), 1))
        receiver.clear("a")
        assertNull(receiver.accept("a", next.first(), 2))
        assertArrayEquals(ByteArray(20), receiver.accept("b", next.last(), 2))
        assertNull(receiver.accept("a", next.last(), 60_003))
    }
}

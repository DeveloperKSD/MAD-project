package `in`.yashd.bitchat

import `in`.yashd.bitchat.bluetooth.BleFrameCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
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
}

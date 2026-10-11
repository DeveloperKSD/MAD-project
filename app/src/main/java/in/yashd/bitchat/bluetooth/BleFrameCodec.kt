package `in`.yashd.bitchat.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object BleFrameCodec {
    private const val MAGIC = 0xBC01
    private const val HEADER_BYTES = 10
    private const val MAX_FRAGMENTS = 512
    private const val ASSEMBLY_TIMEOUT_MS = 60_000L

    fun encode(packet: ByteArray, maxValueBytes: Int, messageId: Int): List<ByteArray> {
        require(packet.isNotEmpty())
        val chunkSize = maxValueBytes - HEADER_BYTES
        require(chunkSize > 0)
        val count = (packet.size + chunkSize - 1) / chunkSize
        require(count <= MAX_FRAGMENTS)
        return (0 until count).map { index ->
            val start = index * chunkSize
            val end = minOf(start + chunkSize, packet.size)
            ByteBuffer.allocate(HEADER_BYTES + end - start).order(ByteOrder.BIG_ENDIAN).apply {
                putShort(MAGIC.toShort())
                putInt(messageId)
                putShort(index.toShort())
                putShort(count.toShort())
                put(packet, start, end - start)
            }.array()
        }
    }

    class Reassembler {
        private data class Assembly(
            val fragments: Array<ByteArray?>,
            val createdAtMs: Long
        )

        private val assemblies = mutableMapOf<String, Assembly>()

        fun accept(linkId: String, frame: ByteArray, nowMs: Long = System.currentTimeMillis()): ByteArray? {
            require(frame.size > HEADER_BYTES)
            val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            require(buffer.short.toInt() and 0xffff == MAGIC)
            val messageId = buffer.int
            val index = buffer.short.toInt() and 0xffff
            val count = buffer.short.toInt() and 0xffff
            require(count in 1..MAX_FRAGMENTS && index < count)

            assemblies.entries.removeAll { nowMs - it.value.createdAtMs > ASSEMBLY_TIMEOUT_MS }
            val key = "$linkId:$messageId"
            val assembly = assemblies.getOrPut(key) { Assembly(arrayOfNulls(count), nowMs) }
            require(assembly.fragments.size == count)
            val fragment = ByteArray(buffer.remaining()).also(buffer::get)
            val existing = assembly.fragments[index]
            require(existing == null || existing.contentEquals(fragment))
            assembly.fragments[index] = fragment
            if (assembly.fragments.any { it == null }) return null
            assemblies.remove(key)
            return assembly.fragments.filterNotNull().fold(ByteArray(0)) { packet, part -> packet + part }
        }

        fun clear() = assemblies.clear()
    }
}

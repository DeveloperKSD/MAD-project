package `in`.yashd.bitchat.nostr

import org.junit.Assert.*
import org.junit.Test

class NostrNpubTest {
    private val hex = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"
    private val npub = "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6"

    @Test fun publishedNip19Vector() {
        assertEquals(npub, NostrNpub.encode(hex.hexBytes()))
        assertArrayEquals(hex.hexBytes(), NostrNpub.decode(npub))
        assertArrayEquals(hex.hexBytes(), NostrNpub.decode(npub.uppercase()))
    }

    @Test fun roundTripsAllByteValues() {
        repeat(256) { n ->
            val bytes = ByteArray(32) { (n + it).toByte() }
            assertArrayEquals(bytes, NostrNpub.decode(NostrNpub.encode(bytes)))
        }
    }

    @Test fun rejectsMalformedInput() {
        val invalid = listOf(npub.dropLast(1) + "q", "N" + npub.drop(1),
            npub.replace("npub", "nsec"), npub.dropLast(1), npub + "q", npub.replaceRange(6, 7, "!"))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { NostrNpub.decode(it) } }
        assertThrows(IllegalArgumentException::class.java) { NostrNpub.encode(ByteArray(31)) }
    }
}

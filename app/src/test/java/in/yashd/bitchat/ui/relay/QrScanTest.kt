package `in`.yashd.bitchat.ui.relay

import `in`.yashd.bitchat.nostr.NostrNpub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrScanTest {
    private val key = ByteArray(32) { (it * 7 + 3).toByte() }
    private val npub = NostrNpub.encode(key)

    @Test fun `raw npub parses to itself`() = assertEquals(npub, parseScannedPublicId(npub))

    @Test fun `nostr uri parses to npub`() = assertEquals(npub, parseScannedPublicId("nostr:$npub"))

    @Test fun `uppercase npub normalizes`() = assertEquals(npub, parseScannedPublicId(npub.uppercase()))

    @Test fun `surrounding whitespace is ignored`() = assertEquals(npub, parseScannedPublicId("  $npub  "))

    @Test fun `garbage rejected`() = assertNull(parseScannedPublicId("hello world"))

    @Test fun `other nostr entities rejected`() {
        val nsec = "nsec1" + npub.drop(5)
        assertNull(parseScannedPublicId(nsec))
    }

    @Test fun `truncated npub rejected`() = assertNull(parseScannedPublicId(npub.dropLast(3)))
}

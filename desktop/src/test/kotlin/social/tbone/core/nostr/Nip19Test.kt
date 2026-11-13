package social.tbone.core.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NIP-19 bech32 entities and the URI/`npub` inputs the search box accepts.
 *
 * Upstream accepts `npub`, `note`, `nevent`, `nprofile` and `nostr:` URIs in one
 * field, so the round trip and the failure modes both matter: a malformed entity
 * must return null (which the UI turns into "no match") rather than throw.
 */
class Nip19Test {

    private val hex32 = "3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"

    @Test
    fun npubRoundTrips() {
        val npub = Nip19.hexToNpub(hex32)
        assertTrue(npub.startsWith("npub1"))
        assertEquals(hex32, Nip19.npubToHex(npub))
    }

    /**
     * Builds a NIP-19 TLV payload: a sequence of `[type][length][value]` entries.
     * nevent/nprofile are not raw keys — they wrap the 32-byte id/pubkey in a
     * type-0 entry (and may carry relay hints and an author afterwards).
     */
    private fun tlv(vararg entries: Pair<Int, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        entries.forEach { (type, value) ->
            out.write(type)
            out.write(value.size)
            out.write(value)
        }
        return out.toByteArray()
    }

    @Test
    fun noteAndNeventRoundTrip() {
        assertEquals(hex32, Nip19.noteToHex(Nip19.hexToNote(hex32)))

        val nevent = Nip19.bech32Encode("nevent", tlv(0 to hex32.hexToBytes()))
        assertNotNull(nevent)
        assertEquals(hex32, Nip19.neventToHex(nevent!!))
    }

    @Test
    fun neventWithRelayHintAndAuthorStillResolvesTheId() {
        // Type 0 = event id, 1 = relay hint, 2 = author pubkey, per NIP-19.
        val author = "a".repeat(64)
        val payload = tlv(
            0 to hex32.hexToBytes(),
            1 to "wss://relay.damus.io".toByteArray(),
            2 to author.hexToBytes(),
        )
        val nevent = Nip19.bech32Encode("nevent", payload)
        assertNotNull(nevent)
        assertEquals(hex32, Nip19.neventToHex(nevent!!))
    }

    @Test
    fun nprofileRoundTrip() {
        val nprofile = Nip19.bech32Encode("nprofile", tlv(0 to hex32.hexToBytes()))
        assertNotNull(nprofile)
        assertEquals(hex32, Nip19.nprofileToHex(nprofile!!))
    }

    @Test
    fun truncatedTlvDoesNotRunOffTheEnd() {
        // A length byte larger than the remaining payload must be refused, not
        // sliced out of bounds.
        val malformed = byteArrayOf(0, 62) + ByteArray(10)
        val encoded = Nip19.bech32Encode("nevent", malformed)
        assertNotNull(encoded)
        assertNull(Nip19.neventToHex(encoded!!))
    }

    @Test
    fun wrongHrpIsRejected() {
        // A note id must not be accepted where a pubkey is expected.
        val note = Nip19.hexToNote(hex32)
        assertNull("npubToHex must reject a note1 entity", Nip19.npubToHex(note))

        val npub = Nip19.hexToNpub(hex32)
        assertNull("noteToHex must reject an npub1 entity", Nip19.noteToHex(npub))
    }

    @Test
    fun checksumCorruptionIsRejected() {
        val npub = Nip19.hexToNpub(hex32)
        // Flip one character in the data part: bech32's checksum must catch it.
        val mutated = npub.dropLast(1) + if (npub.last() == 'q') 'p' else 'q'
        assertNull(Nip19.npubToHex(mutated))
    }

    @Test
    fun garbageInputReturnsNullInsteadOfThrowing() {
        listOf("", "npub", "npub1", "not-an-entity", "npub1!!!!", "https://example.com").forEach {
            assertNull("'$it' must not resolve to a pubkey", Nip19.npubToHex(it))
            assertNull("'$it' must not resolve to an event id", Nip19.noteToHex(it))
        }
    }

    @Test
    fun normalisePubkeyAcceptsNpubAndRawHex() {
        val npub = Nip19.hexToNpub(hex32)
        assertEquals(hex32, Nip19.normalisePubkey(npub))
        assertEquals(hex32, Nip19.normalisePubkey(hex32))
        assertNull(Nip19.normalisePubkey("nonsense"))
    }

    @Test
    fun nostrUriIsParsed() {
        val note = Nip19.hexToNote(hex32)
        assertEquals(hex32, Nip19.nostrUriToEventId("nostr:$note"))
        assertEquals(hex32, Nip19.nostrUriToEventId(note))
        assertNull(Nip19.nostrUriToEventId("nostr:npub-not-an-event"))
    }

    @Test
    fun isBech32DistinguishesEntitiesFromHex() {
        assertTrue(Nip19.isBech32(Nip19.hexToNpub(hex32)))
        assertFalse(Nip19.isBech32(hex32))
        assertFalse(Nip19.isBech32("hello"))
    }
}

package social.tbone.core.nostr.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import social.tbone.core.nostr.Crypto
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.Filter
import social.tbone.core.nostr.UnsignedEvent
import social.tbone.core.nostr.hexToBytes
import social.tbone.core.nostr.toHex

/**
 * Relay wire protocol: NIP-01 client messages the app sends, and the relay
 * messages it must parse (including the malformed ones a real relay will send).
 *
 * These run against the serialisers directly — no sockets — so they are fast and
 * deterministic, and they pin the exact JSON shapes relays depend on.
 */
class RelayProtocolTest {

    private fun signedEvent(content: String = "hi"): Event {
        val privkey = Crypto.generatePrivateKey()
        val unsigned = UnsignedEvent(
            pubkey = Crypto.deriveXOnlyPubkey(privkey).toHex(),
            createdAt = 1_700_000_000L,
            kind = 1,
            content = content,
        )
        val id = unsigned.computeId()
        return Event(
            id = id,
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = Crypto.schnorrSign(id.hexToBytes(), privkey).toHex(),
        )
    }

    // ── Client -> relay ──────────────────────────────────────────────────────

    @Test
    fun reqSerialisesAsNip01Array() {
        val json = ClientMessage.Req(
            subscriptionId = "sub-1",
            filters = listOf(Filter(authors = listOf("ab".repeat(32)), kinds = listOf(1), limit = 20)),
        ).toJson()

        assertTrue(json.startsWith("[\"REQ\",\"sub-1\","))
        assertTrue(json.contains("\"authors\":[\"${"ab".repeat(32)}\"]"))
        assertTrue(json.contains("\"kinds\":[1]"))
        assertTrue(json.contains("\"limit\":20"))
    }

    @Test
    fun filterOmitsNullFields() {
        // Relays interpret an absent key as "match anything"; a literal null is
        // either ignored or an error. Upstream omitted them, and so must the port.
        val json = Filter(authors = listOf("cd".repeat(32))).toJsonObject().toString()

        assertTrue(json.contains("authors"))
        assertFalse("null fields must be dropped", json.contains("null"))
        assertFalse(json.contains("kinds"))
        assertFalse(json.contains("since"))
    }

    @Test
    fun closeMessageIsTwoElements() {
        assertEquals("[\"CLOSE\",\"sub-1\"]", ClientMessage.Close("sub-1").toJson())
    }

    @Test
    fun publishAndAuthCarryTheWholeEvent() {
        val event = signedEvent("published")
        val publish = ClientMessage.Publish(event).toJson()

        assertTrue(publish.startsWith("[\"EVENT\","))
        assertTrue(publish.contains(event.id))
        assertTrue(publish.contains(event.sig))
        assertTrue("event must still verify after serialisation", event.verify())

        val auth = ClientMessage.Auth(event).toJson()
        assertTrue(auth.startsWith("[\"AUTH\","))
    }

    // ── Relay -> client ──────────────────────────────────────────────────────

    @Test
    fun eventMessageIsParsed() {
        val event = signedEvent("from a relay")
        val raw = """["EVENT","sub-1",${eventLetJson(event)}]"""

        val parsed = RelayMessage.parse(raw)
        assertTrue(parsed is RelayMessage.EventMessage)
        val message = parsed as RelayMessage.EventMessage
        assertEquals("sub-1", message.subscriptionId)
        assertEquals(event.id, message.event.id)
        assertTrue("a parsed event must still verify", message.event.verify())
    }

    @Test
    fun eoseNoticeAndClosedAreParsed() {
        assertEquals(
            RelayMessage.EndOfStoredEvents("s"),
            RelayMessage.parse("""["EOSE","s"]"""),
        )
        assertEquals(
            RelayMessage.Notice("rate limit"),
            RelayMessage.parse("""["NOTICE","rate limit"]"""),
        )
        assertEquals(
            RelayMessage.Closed("s", "auth-required"),
            RelayMessage.parse("""["CLOSED","s","auth-required"]"""),
        )
    }

    @Test
    fun okMessageDistinguishesAcceptanceFromRejection() {
        val accepted = RelayMessage.parse("""["OK","${"ab".repeat(32)}",true,""]""")
        assertTrue(accepted is RelayMessage.Ok)
        assertTrue((accepted as RelayMessage.Ok).accepted)

        val rejected = RelayMessage.parse("""["OK","${"ab".repeat(32)}",false,"blocked: not on list"]""")
        assertTrue(rejected is RelayMessage.Ok)
        assertFalse((rejected as RelayMessage.Ok).accepted)
        assertEquals("blocked: not on list", rejected.message)
    }

    @Test
    fun authChallengeIsSurfacedForNip42() {
        val parsed = RelayMessage.parse("""["AUTH","challenge-string"]""")
        assertTrue(parsed is RelayMessage.Auth)
        assertEquals("challenge-string", (parsed as RelayMessage.Auth).challenge)
    }

    @Test
    fun unknownAndMalformedInputIsRetainedNotThrown() {
        // A relay may send types this build does not know, or junk. Neither may
        // crash the connection loop; both must come back as Unknown.
        listOf(
            """["COUNT","s",{"count":3}]""",
            """not json at all""",
            """[]""",
            """["EVENT"]""",
        ).forEach { raw ->
            val parsed = RelayMessage.parse(raw)
            assertTrue("'$raw' must degrade to Unknown, not throw", parsed is RelayMessage.Unknown)
        }
    }

    @Test
    fun relayMessageRoundTripsThroughJsonEncoding() {
        val event = signedEvent("rt")
        val encoded = kotlinx.serialization.json.Json.encodeToJsonElement(
            social.tbone.core.nostr.Event.serializer(), event,
        )
        val decoded = social.tbone.core.nostr.Event.fromJson(encoded.toString())
        assertEquals(event, decoded)
        assertTrue(decoded.verify())
    }

    private fun eventLetJson(event: Event): String =
        kotlinx.serialization.json.Json.encodeToJsonElement(
            social.tbone.core.nostr.Event.serializer(), event,
        ).toString()
}

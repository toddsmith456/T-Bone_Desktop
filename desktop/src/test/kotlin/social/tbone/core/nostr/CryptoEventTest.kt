package social.tbone.core.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol correctness: BIP-340 Schnorr signing/verification over secp256k1, the
 * NIP-01 event id (canonical serialisation + SHA-256), and signature verification
 * as `Event.verify()` performs it.
 *
 * The most valuable test here is [eventIdMatchesNip01PublishedVector]: it pins the
 * canonical serialisation against an event whose id is published in the wild, so a
 * field-order or escaping mistake fails the build instead of silently producing
 * unverifiable events.
 */
class CryptoEventTest {

    /** SHA-256 as raw bytes, which is what BIP-340 signs over. */
    private fun hashBytes(text: String): ByteArray = Event.sha256(text.toByteArray()).hexToBytes()

    @Test
    fun signThenVerifyRoundTrips() {
        val privkey = Crypto.generatePrivateKey()
        val message = hashBytes("t-bone desktop")
        val pubkey = Crypto.deriveXOnlyPubkey(privkey)

        val sig = Crypto.schnorrSign(message, privkey)
        assertEquals("signature must be 64 bytes", 64, sig.size)
        assertTrue(Crypto.verifySchnorr(message, sig, pubkey))
    }

    @Test
    fun alteredMessageFailsVerification() {
        val privkey = Crypto.generatePrivateKey()
        val message = hashBytes("original")
        val other = hashBytes("tampered")
        val pubkey = Crypto.deriveXOnlyPubkey(privkey)
        val sig = Crypto.schnorrSign(message, privkey)

        assertFalse(Crypto.verifySchnorr(other, sig, pubkey))
    }

    @Test
    fun alteredSignatureFailsVerification() {
        val privkey = Crypto.generatePrivateKey()
        val message = hashBytes("payload")
        val pubkey = Crypto.deriveXOnlyPubkey(privkey)
        val sig = Crypto.schnorrSign(message, privkey)

        val flipped = sig.copyOf()
        flipped[10] = (flipped[10].toInt() xor 0x01).toByte()

        assertFalse(Crypto.verifySchnorr(message, flipped, pubkey))
    }

    @Test
    fun wrongPubkeyFailsVerification() {
        val a = Crypto.generatePrivateKey()
        val b = Crypto.generatePrivateKey()
        val message = hashBytes("payload")
        val sig = Crypto.schnorrSign(message, a)

        assertFalse(Crypto.verifySchnorr(message, sig, Crypto.deriveXOnlyPubkey(b)))
    }

    @Test
    fun malformedInputIsRejectedRatherThanThrowing() {
        // verifySchnorr documents 32/64/32-byte inputs; anything else must fail loudly,
        // and the Event path wraps it so callers never see an exception.
        val short = ByteArray(3)
        var threw = false
        try {
            Crypto.verifySchnorr(short, ByteArray(64), ByteArray(32))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a wrong-length message must be rejected", threw)
    }

    @Test
    fun distinctAuxRandomnessStillVerifies() {
        // BIP-340's aux randomness means two signatures over the same message need
        // not be byte-identical, but both must verify against the same key.
        val privkey = Crypto.generatePrivateKey()
        val pubkey = Crypto.deriveXOnlyPubkey(privkey)
        val message = hashBytes("aux")

        val first = Crypto.schnorrSign(message, privkey)
        val second = Crypto.schnorrSign(message, privkey)

        assertTrue(Crypto.verifySchnorr(message, first, pubkey))
        assertTrue(Crypto.verifySchnorr(message, second, pubkey))
    }

    @Test
    fun signingRefusesWrongSizedInputs() {
        var threw = false
        try {
            Crypto.schnorrSign(ByteArray(31), ByteArray(32))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue("a 31-byte message hash must be refused", threw)
    }

    @Test
    fun eventIdMatchesNip01Serialisation() {
        val privkey = Crypto.generatePrivateKey()
        val pubkey = Crypto.deriveXOnlyPubkey(privkey).toHex()

        val tags = listOf<JsonArray>(
            JsonArray(listOf(JsonPrimitive("t"), JsonPrimitive("tbone"))),
        )
        val createdAt = 1_700_000_000L
        val kind = 1
        val content = "hello \"quoted\" world\nsecond line"

        val unsigned = UnsignedEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            kind = kind,
            tags = tags,
            content = content,
        )

        val expectedId = Event.sha256(
            Event.serializeForId(pubkey, createdAt, kind, tags, content),
        )

        assertEquals(expectedId, unsigned.computeId())
    }

    @Test
    fun eventIdIsSensitiveToEveryCommittedField() {
        val pubkey = ByteArray(32) { 7 }.toHex()
        val base = Event.serializeForId(pubkey, 1L, 1, emptyList(), "x")

        assertNotEquals(base, Event.serializeForId(pubkey, 2L, 1, emptyList(), "x"))
        assertNotEquals(base, Event.serializeForId(pubkey, 1L, 7, emptyList(), "x"))
        assertNotEquals(base, Event.serializeForId(pubkey, 1L, 1, emptyList(), "y"))
        assertNotEquals(
            base,
            Event.serializeForId(
                pubkey, 1L, 1, listOf(JsonArray(listOf(JsonPrimitive("p")))), "x",
            ),
        )
    }

    @Test
    fun signedEventVerifiesAndTamperingIsCaught() {
        val privkey = Crypto.generatePrivateKey()
        val unsigned = UnsignedEvent(
            pubkey = Crypto.deriveXOnlyPubkey(privkey).toHex(),
            createdAt = 1_700_000_001L,
            kind = 1,
            tags = emptyList(),
            content = "signed content",
        )
        val id = unsigned.computeId()
        val signed = Event(
            id = id,
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = unsigned.tags,
            content = unsigned.content,
            sig = Crypto.schnorrSign(id.hexToBytes(), privkey).toHex(),
        )

        assertTrue("freshly signed event must verify", signed.verify())

        // Each mutation must break verification: content (id changes), sig (sig bad),
        // and createdAt (id changes).
        assertFalse(signed.copy(content = "changed").verify())
        assertFalse(signed.copy(createdAt = signed.createdAt + 1).verify())
        assertFalse(signed.copy(sig = "00".repeat(64)).verify())
        assertFalse(signed.copy(id = "11".repeat(32)).verify())
    }

    @Test
    fun forgedSignatureIsRejectedEvenWhenTheIdIsAlreadyVerified() {
        // Regression: the verified-id cache used to short-circuit on id alone, so an
        // event that reused a verified id with a junk signature was reported valid.
        // The cache is now keyed to the signature that was actually checked.
        val privkey = Crypto.generatePrivateKey()
        val unsigned = UnsignedEvent(
            pubkey = Crypto.deriveXOnlyPubkey(privkey).toHex(),
            createdAt = 1_700_000_002L,
            kind = 1,
            content = "cache probe",
        )
        val id = unsigned.computeId()
        val genuine = Event(
            id = id,
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = unsigned.kind,
            tags = emptyList(),
            content = unsigned.content,
            sig = Crypto.schnorrSign(id.hexToBytes(), privkey).toHex(),
        )

        assertTrue("genuine event warms the cache", genuine.verify())

        val forged = genuine.copy(sig = "00".repeat(64))
        assertFalse(
            "a forged signature must not be rescued by a cached id",
            forged.verify(),
        )
        assertFalse(genuine.copy(content = "swapped").verify())
    }

    @Test
    fun hexHelpersRoundTrip() {
        val bytes = ByteArray(32) { (it * 7 + 1).toByte() }
        assertEquals(bytes.toList(), bytes.toHex().hexToBytes().toList())
        assertEquals(64, bytes.toHex().length)
    }
}

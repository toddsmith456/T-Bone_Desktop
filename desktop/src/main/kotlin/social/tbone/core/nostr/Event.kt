package social.tbone.core.nostr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.encodeToJsonElement
import org.bouncycastle.jce.provider.BouncyCastleProvider
import social.tbone.core.Tunables
import java.security.MessageDigest
import java.security.Security

/**
 * Caches verified event IDs so Schnorr verification is only done once per unique event.
 *
 * Desktop port note: upstream used android.util.LruCache. This is the same
 * bounded, synchronized least-recently-used cache implemented on the JVM, so
 * behaviour and the [Tunables.VERIFIED_EVENT_CACHE_SIZE] bound are unchanged.
 */
object VerifiedEventCache {
    /**
     * Keyed by event id, storing the signature that was verified for it.
     *
     * Storing the signature (rather than a bare marker) is what makes the cache
     * safe: a cached id only short-circuits verification when the event presents
     * the same signature that was actually checked. An event carrying the right id
     * with a forged signature therefore still fails verification.
     */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > Tunables.VERIFIED_EVENT_CACHE_SIZE
    }

    fun contains(id: String, sig: String): Boolean =
        synchronized(cache) { cache[id] == sig }

    fun add(id: String, sig: String) { synchronized(cache) { cache[id] = sig } }

    fun clear() { synchronized(cache) { cache.clear() } }
}

/**
 * A Nostr event as defined in NIP-01.
 *
 * All fields are immutable. Events signed by an external signer (Amber, nsecBunker)
 * arrive fully formed. Local draft events (pre-signing) use [UnsignedEvent].
 */
@Serializable
data class Event(
    val id: String,
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long,
    val kind: Int,
    val tags: List<JsonArray>,
    val content: String,
    val sig: String,
) {
    /** Parsed tag wrappers for ergonomic access. */
    val parsedTags: List<Tag> by lazy { tags.map { Tag.fromJsonArray(it) } }

    /**
     * Verifies the event id and signature.
     * Returns false if either check fails — never throws.
     */
    fun verify(): Boolean {
        // The cache only short-circuits the expensive Schnorr check. The id must be
        // recomputed first, because it is derived from the event's own fields: if a
        // relay serves a forged payload reusing a cached id, the recomputed id will
        // differ and verification must fail here rather than return a stale `true`.
        val idMatches = runCatching { verifyId() }.getOrDefault(false)
        if (!idMatches) return false

        if (VerifiedEventCache.contains(id, sig)) return true

        val result = runCatching { verifySignature() }.getOrDefault(false)
        if (result) VerifiedEventCache.add(id, sig)
        return result
    }

    private fun verifyId(): Boolean {
        val serialized = serializeForId(pubkey, createdAt, kind, tags, content)
        val hash = sha256(serialized)
        return hash == id
    }

    private fun verifySignature(): Boolean {
        ensureBouncyCastle()
        return Crypto.verifySchnorr(
            message = id.hexToBytes(),
            signature = sig.hexToBytes(),
            pubkey = pubkey.hexToBytes(),
        )
    }

    companion object {
        fun fromJson(raw: String): Event = NostrJson.decodeFromString(raw)

        /**
         * Computes the canonical serialization used for the event id.
         * [0, pubkey, created_at, kind, tags, content]
         */
        fun serializeForId(
            pubkey: String,
            createdAt: Long,
            kind: Int,
            tags: List<JsonArray>,
            content: String,
        ): ByteArray {
            val array = buildJsonArray {
                add(NostrJson.encodeToJsonElement(0))
                add(NostrJson.encodeToJsonElement(pubkey))
                add(NostrJson.encodeToJsonElement(createdAt))
                add(NostrJson.encodeToJsonElement(kind))
                add(JsonArray(tags.map { it as JsonElement }))
                add(NostrJson.encodeToJsonElement(content))
            }
            return array.toString().toByteArray(Charsets.UTF_8)
        }

        fun sha256(input: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(input)
                .toHex()

        private fun ensureBouncyCastle() {
            if (Security.getProvider("BC") == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }
}

/**
 * A Nostr event that has not yet been signed.
 * Passed to a [social.tbone.signer.NostrSigner] to produce a signed [Event].
 */
@Serializable
data class UnsignedEvent(
    val pubkey: String,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis() / 1000,
    val kind: Int,
    val tags: List<JsonArray> = emptyList(),
    val content: String,
) {
    fun computeId(): String {
        val serialized = Event.serializeForId(pubkey, createdAt, kind, tags, content)
        return Event.sha256(serialized)
    }
}

// ── Hex utilities ─────────────────────────────────────────────────────────────

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length, got $length" }
    return ByteArray(length / 2) { i ->
        val hi = Character.digit(this[i * 2], 16)
        val lo = Character.digit(this[i * 2 + 1], 16)
        require(hi in 0..15 && lo in 0..15) {
            "Non-hex characters at index ${i * 2}: '${this[i * 2]}${this[i * 2 + 1]}'"
        }
        ((hi shl 4) + lo).toByte()
    }
}

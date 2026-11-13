package social.tbone.core.signer

import social.tbone.core.Log
import social.tbone.core.signer.NostrSignerType
import social.tbone.core.nostr.Crypto
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.Nip44
import social.tbone.core.nostr.UnsignedEvent
import social.tbone.core.nostr.hexToBytes
import social.tbone.core.nostr.toHex
import social.tbone.core.store.LocalCrypto
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-process signer holding a secp256k1 private key.
 *
 * Port note — upstream `LocalKeySigner` wrapped the private key with an AES-GCM
 * key held in the *Android Keystore*, then decrypted it on every signing call so
 * the raw scalar lived in memory only for the duration of that call. The desktop
 * port keeps that shape:
 *
 *   - the key is persisted only as an AES-256-GCM blob ([LocalCrypto]);
 *   - the plaintext scalar is decoded into a local array per operation and
 *     overwritten afterwards;
 *   - signing/ECDH is BIP-340 secp256k1 via Bouncy Castle ([Crypto]).
 *
 * The honest difference: Android Keystore can be hardware-backed; a desktop key
 * file (0600) cannot. See docs/PARITY.md.
 *
 * This class is also used as the NIP-46 session signer (it signs the kind-24133
 * wrapper events with the ephemeral session key, never the user's key).
 */
class LocalKeySigner(
    override val pubkey: String,
    /** AES-GCM blob of the 32-byte private scalar, or null for encrypt/decrypt-only use. */
    private val encryptedPrivkey: ByteArray?,
    private val crypto: LocalCrypto,
) : NostrSigner {

    override val kind: NostrSignerType = NostrSignerType.LOCAL_KEY

    private val wiped = AtomicBoolean(false)

    override suspend fun signEvent(event: UnsignedEvent): Result<Event> = runCatching {
        check(event.pubkey.equals(pubkey, ignoreCase = true)) {
            "Event pubkey does not match this signer"
        }
        val id = event.computeId()
        val sig = withPrivkey { pk ->
            Crypto.schnorrSign(id.hexToBytes(), pk).toHex()
        }
        Event(
            id = id,
            pubkey = pubkey,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags,
            content = event.content,
            sig = sig,
        ).also {
            check(it.verify()) { "Self-signed event failed verification" }
        }
    }

    override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String> =
        runCatching {
            withPrivkey { pk -> Nip44.encrypt(plaintext, pk, recipientPubkey.hexToBytes()) }
        }

    override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String> =
        runCatching {
            withPrivkey { pk -> Nip44.decrypt(ciphertext, pk, senderPubkey.hexToBytes()) }
        }

    /** Synchronous NIP-44 decrypt, used by the NIP-46 response parser. */
    fun nip44DecryptSync(ciphertext: String, senderPubkey: String): String? = runCatching {
        withPrivkey { pk -> Nip44.decrypt(ciphertext, pk, senderPubkey.hexToBytes()) }
    }.getOrNull()

    /** Synchronous sign, used where the caller is already on a blocking thread. */
    fun signEventBlocking(event: UnsignedEvent): Event {
        val id = event.computeId()
        val sig = withPrivkey { pk -> Crypto.schnorrSign(id.hexToBytes(), pk).toHex() }
        val signed = Event(
            id = id,
            pubkey = event.pubkey,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags,
            content = event.content,
            sig = sig,
        )
        check(signed.verify()) { "Self-signed event failed verification" }
        return signed
    }

    /** Drops the reference to the encrypted blob (account removed / app locked). */
    fun wipe() = wiped.set(true)

    internal inline fun <T> withPrivkey(block: (ByteArray) -> T): T {
        check(!wiped.get()) { "Signer has been wiped" }
        val blob = encryptedPrivkey ?: error("This signer has no private key material")
        val pk = crypto.decrypt(blob)
        try {
            return block(pk)
        } finally {
            pk.fill(0)
        }
    }

    companion object {
        /**
         * Generates a new secp256k1 keypair, encrypts the private key with
         * [crypto], and returns the signer plus the encrypted blob to persist.
         */
        fun generate(crypto: LocalCrypto): Pair<LocalKeySigner, ByteArray> {
            val privkey = Crypto.generatePrivateKey()
            return try {
                val pubkey = Crypto.deriveXOnlyPubkey(privkey).toHex()
                val encrypted = crypto.encrypt(privkey)
                Log.i("LocalKeySigner", "generated local key for ${pubkey.take(8)}…")
                LocalKeySigner(pubkey, encrypted, crypto) to encrypted
            } finally {
                privkey.fill(0)
            }
        }

        /** Builds a signer around an existing 32-byte private key (already decrypted). */
        fun fromPrivateKey(privkey: ByteArray, crypto: LocalCrypto): LocalKeySigner {
            require(privkey.size == 32) { "private key must be 32 bytes" }
            val pubkey = Crypto.deriveXOnlyPubkey(privkey).toHex()
            return LocalKeySigner(pubkey, crypto.encrypt(privkey), crypto)
        }

        /** Builds a public-only signer (cannot sign) — used to inspect an account. */
        fun publicOnly(pubkey: String, crypto: LocalCrypto): LocalKeySigner =
            LocalKeySigner(pubkey, null, crypto)
    }
}

package social.tbone.core.signer

import social.tbone.core.signer.NostrSignerType
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.UnsignedEvent

/**
 * Abstraction over all signing backends — port of upstream's NostrSigner.
 *
 * Upstream delegated to Amber (NIP-55, Android intents), nsecBunker (NIP-46),
 * or an Android-Keystore-wrapped local key. On desktop there is no NIP-55
 * intent surface, so the two available backends are:
 *
 *  - [LocalKeySigner]  — in-process secp256k1 key, stored AES-GCM encrypted at
 *                        rest by [social.tbone.core.store.LocalCrypto].
 *  - [NsecBunkerSigner] — NIP-46 remote signer over a relay (unchanged
 *                        protocol from upstream; portable as-is).
 */
interface NostrSigner {
    val pubkey: String
    val kind: NostrSignerType

    /** Signs [event] and returns the fully populated [Event] with id and sig set. */
    suspend fun signEvent(event: UnsignedEvent): Result<Event>

    /** Encrypts [plaintext] for [recipientPubkey] with NIP-44 v2. */
    suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String>

    /** Decrypts [ciphertext] from [senderPubkey] with NIP-44 v2. */
    suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String>
}

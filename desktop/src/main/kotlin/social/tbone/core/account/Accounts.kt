package social.tbone.core.account

import kotlinx.serialization.Serializable
import social.tbone.core.signer.NostrSignerType

/** Full NIP-02 contact entry: pubkey plus optional relay hint and petname. */
@Serializable
data class FollowEntry(
    val pubkey: String,
    val relay: String = "",
    val petname: String = "",
)

/**
 * One Nostr identity stored by the app. Ported verbatim from upstream's Account:
 * the private key is never kept here, only the public identity and the signer
 * metadata needed to reconstruct a signer at unlock time.
 */
@Serializable
data class Account(
    val pubkey: String,
    val displayName: String? = null,
    val pictureUrl: String? = null,
    val signerType: NostrSignerType,
    val nsecBunkerConfig: NsecBunkerConfig? = null,
    val relays: List<String> = emptyList(),      // NIP-65 outbox relays
    val follows: List<String> = emptyList(),
    val followEntries: List<FollowEntry> = emptyList(),
) {
    fun isFollowing(pubkey: String): Boolean =
        follows.contains(pubkey) || followEntries.any { it.pubkey == pubkey }
}

@Serializable
data class NsecBunkerConfig(
    val bunkerPubkey: String,
    val relayUrl: String,
    val secret: String,
    val sessionPubkey: String,
)

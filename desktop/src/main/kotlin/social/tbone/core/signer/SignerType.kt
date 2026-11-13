package social.tbone.core.signer

import kotlinx.serialization.Serializable

/**
 * Which backend signs for a stored account.
 *
 * [AMBER] and [NSEC_BUNKER] keep upstream's names so existing account records
 * deserialize unchanged; [LOCAL_KEY] is upstream's Android-Keystore signer,
 * implemented on desktop as an AES-GCM-wrapped local key.
 */
@Serializable
enum class NostrSignerType {
    /** Local secp256k1 key held by this app (upstream: Android Keystore). */
    LOCAL_KEY,

    /** NIP-46 remote signer. */
    NSEC_BUNKER,

    /**
     * NIP-55 external signer app (Amber). Declared so stored account records and
     * UI can round-trip it, but it is not selectable on desktop: there is no
     * Android intent bus. Such accounts show an explicit unsupported notice.
     */
    AMBER,
}

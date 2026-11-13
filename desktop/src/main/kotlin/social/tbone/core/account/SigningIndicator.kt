package social.tbone.core.account

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks in-flight signing requests across backends — port of upstream's
 * SigningIndicator. The local signer is instant and never sets this; NIP-46
 * round-trips do, so the UI can show a deliberate "signing…" state.
 */
class SigningIndicator {
    private val _signingInProgress = MutableStateFlow(false)
    val signingInProgress: StateFlow<Boolean> = _signingInProgress.asStateFlow()
    val isSigning: Boolean get() = _signingInProgress.value

    fun start() { _signingInProgress.value = true }
    fun stop() { _signingInProgress.value = false }
}

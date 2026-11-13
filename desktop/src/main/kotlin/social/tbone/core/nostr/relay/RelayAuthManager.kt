package social.tbone.core.nostr.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import social.tbone.core.Log
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.UnsignedEvent
import social.tbone.core.signer.NostrSigner
import social.tbone.core.signer.NostrSignerType
import social.tbone.core.store.AccountStore

/**
 * NIP-42 relay authentication — port of upstream's RelayAuthManager.
 *
 * Observes [RelayPool.messages] for AUTH challenges and answers with a signed
 * kind-22242 event carrying the relay URL and challenge.
 *
 * Differences from the Android build, both deliberate:
 *  - signing no longer goes through Hilt's NostrSignerFactory; the caller passes
 *    a signer provider, which is the active account's signer in the app;
 *  - Amber accounts cannot be authenticated silently (no intent bridge on
 *    desktop), so they are skipped with a log line rather than blocked on a UI
 *    dialog that cannot exist. That matches upstream's intent of not interrupting
 *    the user for every challenge.
 */
class RelayAuthManager(
    private val pool: RelayPool,
    private val accountStore: AccountStore,
    private val signerProvider: suspend () -> NostrSigner?,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            pool.messages.collect { (relayUrl, message) ->
                if (message is RelayMessage.Auth) {
                    handleAuth(scope, relayUrl, message.challenge)
                }
            }
        }
    }

    private fun handleAuth(scope: CoroutineScope, relayUrl: String, challenge: String) {
        scope.launch {
            val account = accountStore.activeAccount.first()
            if (account == null) {
                Log.w("AUTH from $relayUrl: no active account")
                return@launch
            }
            if (account.signerType == NostrSignerType.AMBER) {
                Log.d("AUTH from $relayUrl: skipping — Amber is not available on desktop")
                return@launch
            }
            val signer = signerProvider() ?: return@launch

            val unsigned = UnsignedEvent(
                pubkey = signer.pubkey,
                kind = EventKind.AUTH,
                content = "",
                tags = listOf(
                    buildJsonArray { add(JsonPrimitive("relay")); add(JsonPrimitive(relayUrl)) },
                    buildJsonArray { add(JsonPrimitive("challenge")); add(JsonPrimitive(challenge)) },
                ),
            )

            signer.signEvent(unsigned)
                .onSuccess { signed ->
                    pool.send(relayUrl, ClientMessage.Auth(signed))
                    Log.d("AUTH sent to $relayUrl")
                }
                .onFailure { e -> Log.w("AUTH signing failed for $relayUrl: ${e.message}") }
        }
    }
}

package social.tbone.core.signer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import social.tbone.core.Log
import social.tbone.core.Tunables
import social.tbone.core.account.NsecBunkerConfig
import social.tbone.core.signer.NostrSignerType
import social.tbone.core.account.SigningIndicator
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.Filter
import social.tbone.core.nostr.UnsignedEvent
import social.tbone.core.nostr.relay.ClientMessage
import social.tbone.core.nostr.relay.RelayMessage
import social.tbone.core.nostr.relay.RelayPool
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val REQUEST_TIMEOUT_MS = 30_000L

/**
 * NIP-46: delegates signing and encryption to a remote nsecBunker over a Nostr
 * relay using encrypted kind-24133 events. Ported from upstream with no wire
 * changes; only the signer plumbing differs (no Hilt, no Android context).
 *
 * The session keypair is ephemeral and stored like any other local key. It is
 * used solely for NIP-44 encryption on the NIP-46 channel and for signing the
 * wrapper events — never to sign user events.
 */
class NsecBunkerSigner(
    override val pubkey: String,
    private val config: NsecBunkerConfig,
    private val pool: RelayPool,
    private val sessionSigner: LocalKeySigner,
    private val scope: CoroutineScope,
    private val signingIndicator: SigningIndicator,
) : NostrSigner {

    override val kind: NostrSignerType = NostrSignerType.NSEC_BUNKER

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Nip46Response>>()
    private var listenerJob: Job? = null

    /**
     * NIP-46 connect handshake. Call once after construction to authenticate
     * with the bunker and retrieve the user's real public key.
     */
    suspend fun handshake(): Result<String> = runCatching {
        val connect = request("connect", listOf(config.sessionPubkey, config.secret)).getOrThrow()
        check(connect == "ack" || connect == "\"ack\"") { "Bunker rejected connect: $connect" }
        request("get_public_key", emptyList()).getOrThrow().trim('"')
    }

    override suspend fun signEvent(event: UnsignedEvent): Result<Event> =
        request("sign_event", listOf(Json.encodeToString(UnsignedEvent.serializer(), event)))
            .mapCatching { Event.fromJson(it) }

    override suspend fun nip44Encrypt(plaintext: String, recipientPubkey: String): Result<String> =
        request("nip44_encrypt", listOf(plaintext, recipientPubkey))

    override suspend fun nip44Decrypt(ciphertext: String, senderPubkey: String): Result<String> =
        request("nip44_decrypt", listOf(ciphertext, senderPubkey))

    fun close() {
        listenerJob?.cancel()
        listenerJob = null
        pending.clear()
    }

    // ── Wire protocol ─────────────────────────────────────────────────────────

    private suspend fun request(method: String, params: List<String>): Result<String> {
        signingIndicator.start()
        return try {
            Result.success(performRequest(method, params))
        } catch (e: Throwable) {
            Log.w("NsecBunker", "$method failed: ${e.message}")
            Result.failure(e)
        } finally {
            signingIndicator.stop()
        }
    }

    private suspend fun performRequest(method: String, params: List<String>): String {
        ensureListening()
        pool.addRelay(config.relayUrl)

        val requestId = UUID.randomUUID().toString().take(8)
        val await = CompletableDeferred<Nip46Response>()
        pending[requestId] = await
        try {
            val requestJson = Json.encodeToString(
                Nip46Request.serializer(),
                Nip46Request(id = requestId, method = method, params = params),
            )
            val encrypted = sessionSigner.nip44Encrypt(requestJson, config.bunkerPubkey).getOrThrow()
            val requestEvent = sessionSigner.signEvent(
                UnsignedEvent(
                    pubkey = config.sessionPubkey,
                    kind = EventKind.NOSTR_CONNECT,
                    tags = listOf(buildJsonArray {
                        add(JsonPrimitive("p"))
                        add(JsonPrimitive(config.bunkerPubkey))
                    }),
                    content = encrypted,
                )
            ).getOrThrow()

            pool.send(config.relayUrl, ClientMessage.Publish(requestEvent))

            val response = try {
                withTimeout(REQUEST_TIMEOUT_MS) { await.await() }
            } catch (_: TimeoutCancellationException) {
                error("nsecBunker did not respond within ${REQUEST_TIMEOUT_MS / 1000}s")
            }
            if (response.error.isNotEmpty()) error("nsecBunker error: ${response.error}")
            return response.result
        } finally {
            pending.remove(requestId)
        }
    }

    /**
     * Subscribes once to bunker responses. Upstream re-subscribed per request and
     * matched on a flow filter; here responses are routed by request id through a
     * pending map, which avoids racing the EOSE window of a subscription that is
     * torn down and re-created for every call.
     */
    private fun ensureListening() {
        if (listenerJob?.isActive == true) return
        synchronized(this) {
            if (listenerJob?.isActive == true) return
            val subId = pool.subscribeTo(
                filters = listOf(
                    Filter(
                        kinds = listOf(EventKind.NOSTR_CONNECT),
                        authors = listOf(config.bunkerPubkey),
                        pTags = listOf(config.sessionPubkey),
                    )
                ),
                label = "nip46",
                relays = setOf(config.relayUrl),
            )
            listenerJob = scope.launch {
                pool.messages.collect { msg ->
                    val event = (msg.message as? RelayMessage.EventMessage)?.event ?: return@collect
                    if (event.kind != EventKind.NOSTR_CONNECT) return@collect
                    val response = parseResponse(event) ?: return@collect
                    pending[response.id]?.complete(response)
                }
            }
            Log.d("NsecBunker", "listening for bunker responses on $subId")
        }
    }

    private fun parseResponse(event: Event): Nip46Response? = runCatching {
        val decrypted = sessionSigner.nip44DecryptSync(event.content, config.bunkerPubkey)
            ?: return null
        Json.decodeFromString(Nip46Response.serializer(), decrypted)
    }.getOrNull()

    @Serializable
    data class Nip46Request(val id: String, val method: String, val params: List<String>)

    @Serializable
    data class Nip46Response(val id: String, val result: String, val error: String = "")

    companion object {
        /**
         * Parses a `bunker://<remote-pubkey>?relay=<url>&secret=<s>` connection
         * string (NIP-46) into a config plus a freshly generated session key.
         */
        fun parseBunkerUrl(
            url: String,
            sessionPubkey: String,
        ): Result<NsecBunkerConfig> = runCatching {
            val trimmed = url.trim()
            require(trimmed.startsWith("bunker://", ignoreCase = true)) {
                "bunker URL must start with bunker://"
            }
            val rest = trimmed.removePrefix("bunker://").removePrefix("BUNKER://")
            val remote = rest.substringBefore('?').trim('/').trim()
            require(REMOTE_HEX.matches(remote)) { "bunker:// must contain a 64-char hex remote pubkey" }

            val query = rest.substringAfter('?', "")
            val params = query.split('&').filter { it.isNotBlank() }.mapNotNull {
                val k = it.substringBefore('=')
                val v = it.substringAfter('=', "")
                if (k.isBlank()) null else k.lowercase() to v
            }.toMap()

            val relay = params["relay"]?.replace("%3A%2F%2F", "://")
                ?: error("bunker URL is missing relay=")
            require(relay.startsWith("wss://") || relay.startsWith("ws://")) {
                "relay must be a ws:// or wss:// URL"
            }
            NsecBunkerConfig(
                bunkerPubkey = remote.lowercase(),
                relayUrl = relay,
                secret = params["secret"].orEmpty(),
                sessionPubkey = sessionPubkey,
            )
        }

        private val REMOTE_HEX = Regex("^[0-9a-fA-F]{64}$")
    }
}

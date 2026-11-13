package social.tbone.core.nostr.relay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import social.tbone.core.Tunables
import social.tbone.core.nostr.Filter
import social.tbone.core.Log
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class RelayStatus { CONNECTING, CONNECTED, DISCONNECTED }

/**
 * Manages a set of [RelayConnection]s and multiplexes their messages into a
 * single [messages] flow.
 *
 * Subscriptions opened via [subscribe] are automatically re-sent to a relay
 * when it reconnects. Call [unsubscribe] or cancel the returned [Job] to stop.
 */
class RelayPool(
    private val scope: CoroutineScope,
    private var connectionFactory: (url: String) -> RelayConnection,
) {
    private val connections = ConcurrentHashMap<String, RelayEntry>()
    private val activeSubscriptions = ConcurrentHashMap<String, Subscription>()

    private val _messages = MutableSharedFlow<PoolMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<PoolMessage> = _messages.asSharedFlow()

    private val _relayStatuses = MutableStateFlow<Map<String, RelayStatus>>(emptyMap())
    val relayStatuses: StateFlow<Map<String, RelayStatus>> = _relayStatuses.asStateFlow()

    /**
     * Per-publication outcome, keyed by event id then relay URL.
     *
     * NIP-01 gives the client no synchronous acknowledgement: `publish()` only hands
     * the frame to each socket, and acceptance arrives later as a relay-to-client
     * `["OK", <id>, <accepted>, <message>]`. Without collecting those, a rejected
     * note (auth-required, blocked, rate-limited, policy) looked exactly like a
     * successful one. This flow makes the difference observable to the UI.
     */
    private val _publishResults = MutableStateFlow<Map<String, Map<String, PublishAck>>>(emptyMap())
    val publishResults: StateFlow<Map<String, Map<String, PublishAck>>> = _publishResults.asStateFlow()

    // ── Diagnostics ─────────────────────────────────────────────────────────────
    // Counts EVENT/EOSE messages multiplexed out of the pool, dumped every 5s with
    // the active-subscription breakdown, so subscription leaks and event floods are
    // visible in logcat without a profiler.
    private val eventCounter = AtomicLong(0)
    private val eoseCounter = AtomicLong(0)

    init {
        scope.launch {
            while (true) {
                delay(STATS_INTERVAL_MS)
                val events = eventCounter.getAndSet(0)
                val eose = eoseCounter.getAndSet(0)
                if (events == 0L && activeSubscriptions.isEmpty()) continue
                val byLabel = activeSubscriptions.values
                    .groupingBy { it.label }
                    .eachCount()
                    .entries
                    .sortedByDescending { it.value }
                    .joinToString(", ") { "${it.key}=${it.value}" }
                Log.d(
                    "PoolStats: ${events / (STATS_INTERVAL_MS / 1000)} evt/s, " +
                        "${eose / (STATS_INTERVAL_MS / 1000)} eose/s, " +
                        "activeSubs=${activeSubscriptions.size} [$byLabel], " +
                        "relays=${connections.size}",
                )
            }
        }
    }

    // ── Relay management ──────────────────────────────────────────────────────

    fun addRelay(url: String) {
        val normalized = normalize(url)
        if (connections.containsKey(normalized)) return
        val connection = connectionFactory(normalized)
        val job = scope.launch { connectWithRetry(normalized, connection) }
        connections[normalized] = RelayEntry(connection, job)
        _relayStatuses.update { it + (normalized to RelayStatus.CONNECTING) }
        Log.d("Added relay: $normalized")
    }

    fun removeRelay(url: String) {
        val normalized = normalize(url)
        connections.remove(normalized)?.job?.cancel()
        _relayStatuses.update { it - normalized }
        Log.d("Removed relay: $normalized")
    }

    private val relayRefCounts = ConcurrentHashMap<String, Int>()

    /**
     * Connect to a relay with reference counting: every [acquireRelay] must be
     * matched by a [releaseRelay], and the connection is only torn down when the
     * last user releases it. Several screens (search, composer @-mentions) may
     * hold the same relay at once without yanking it out from under each other.
     */
    fun acquireRelay(url: String) {
        val normalized = normalize(url)
        synchronized(relayRefCounts) {
            relayRefCounts[normalized] = (relayRefCounts[normalized] ?: 0) + 1
        }
        addRelay(normalized)
    }

    fun releaseRelay(url: String) {
        val normalized = normalize(url)
        val remaining = synchronized(relayRefCounts) {
            val count = (relayRefCounts[normalized] ?: 0) - 1
            if (count <= 0) relayRefCounts.remove(normalized) else relayRefCounts[normalized] = count
            count
        }
        if (remaining <= 0) removeRelay(normalized)
    }

    /**
     * Swap the transport (e.g. plain vs. Tor SOCKS proxy) and reconnect all
     * existing relays so the new client takes effect immediately.
     */
    fun updateTransport(client: okhttp3.OkHttpClient) {
        connectionFactory = { url -> RelayConnection(url, client) }
        val urls = connections.keys.toList()
        urls.forEach { removeRelay(it) }
        urls.forEach { addRelay(it) }
        Log.d("Transport updated, reconnecting ${urls.size} relay(s)")
    }

    fun relayUrls(): Set<String> = connections.keys.toSet()

    companion object {
        /** Strip trailing slashes so wss://foo and wss://foo/ are treated as the same relay. */
        fun normalize(url: String): String = url.trimEnd('/')

        private const val STATS_INTERVAL_MS = 5_000L
    }

    // ── Subscriptions ─────────────────────────────────────────────────────────

    /**
     * Opens a subscription on all current (and future) relays.
     * Returns the subscription ID so callers can filter [messages] by it.
     */
    fun subscribe(filters: List<Filter>, id: String = newSubId(), label: String = "sub"): String =
        subscribeTo(filters, id, label, relays = null)

    /**
     * Opens a subscription scoped to [relays] only — used by location channels
     * so each channel talks only to its own handful of relays instead of every
     * connected relay. Returns the subscription ID.
     */
    fun subscribeTo(
        filters: List<Filter>,
        id: String = newSubId(),
        label: String = "sub",
        relays: Set<String>?,
    ): String {
        val sub = Subscription(id, filters, label, relays?.map { normalize(it) }?.toSet())
        activeSubscriptions[id] = sub
        val req = ClientMessage.Req(id, filters)
        if (sub.relays != null) {
            sub.relays.forEach { url -> connections[url]?.connection?.send(req) }
        } else {
            connections.values.forEach { it.connection.send(req) }
        }
        Log.d("Subscribed: $id [$label] relays=${sub.relays?.size ?: "all"} active=${activeSubscriptions.size}")
        return id
    }

    /** Broadcasts an event to all connected relays. */
    fun publish(event: social.tbone.core.nostr.Event) {
        val msg = ClientMessage.Publish(event)
        connections.values.forEach { it.connection.send(msg) }
        Log.d("Published event ${event.id.take(8)}… to ${connections.size} relay(s)")
    }

    /** Publishes an event to a specific set of relays (location channels). */
    fun publishTo(event: social.tbone.core.nostr.Event, urls: Set<String>) {
        val msg = ClientMessage.Publish(event)
        var sent = 0
        urls.forEach { url ->
            if (connections[normalize(url)]?.connection?.send(msg) == true) sent++
        }
        Log.d("Published event ${event.id.take(8)}… to $sent/${urls.size} relay(s)")
    }

    /** Sends a message to a specific relay by URL. Returns false if not connected. */
    fun send(relayUrl: String, message: ClientMessage): Boolean =
        connections[relayUrl]?.connection?.send(message) ?: false

    /**
     * Sends a pre-serialised NIP-01 message to every connected relay.
     * Used by the desktop relay console (diagnostics) to issue ad-hoc REQ/CLOSE
     * frames without adding a ClientMessage subtype.
     */
    fun sendRaw(raw: String): Boolean {
        var sent = 0
        connections.values.forEach { if (it.connection.sendRaw(raw)) sent++ }
        return sent > 0
    }

    fun unsubscribe(subscriptionId: String) {
        val sub = activeSubscriptions.remove(subscriptionId) ?: return
        val close = ClientMessage.Close(subscriptionId)
        connections.values.forEach { it.connection.send(close) }
        Log.d("Unsubscribed: $subscriptionId [${sub.label}] active=${activeSubscriptions.size}")
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private suspend fun connectWithRetry(url: String, connection: RelayConnection) {
        var delayMs = Tunables.RECONNECT_DELAY_MS
        while (true) {
            Log.d("Connecting: $url")
            try {
                connection.messages.collect { message ->
                    when (message) {
                        is RelayMessage.Connected -> {
                            delayMs = Tunables.RECONNECT_DELAY_MS
                            _relayStatuses.update { it + (url to RelayStatus.CONNECTED) }
                            activeSubscriptions.values.forEach { sub ->
                                // A scoped subscription is only sent to its own relays.
                                if (sub.relays == null || normalize(url) in sub.relays) {
                                    connection.send(ClientMessage.Req(sub.id, sub.filters))
                                }
                            }
                            Log.d("Replayed ${activeSubscriptions.size} subscription(s) to $url")
                        }
                        else -> {
                            when (message) {
                                is RelayMessage.EventMessage -> eventCounter.incrementAndGet()
                                is RelayMessage.EndOfStoredEvents -> eoseCounter.incrementAndGet()
                                is RelayMessage.Ok -> recordAck(url, message)
                                else -> Unit
                            }
                            delayMs = Tunables.RECONNECT_DELAY_MS
                            _messages.emit(PoolMessage(url, message))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(e, "Collection error on $url")
            }

            if (!connections.containsKey(url)) break

            _relayStatuses.update { it + (url to RelayStatus.DISCONNECTED) }
            Log.d("Reconnecting $url in ${delayMs}ms")
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(Tunables.MAX_RECONNECT_DELAY_MS)
        }
    }

    /**
     * Records a relay's acceptance/rejection of a published event.
     *
     * Logged at info level, because a rejected publish is exactly the kind of thing
     * that must be visible in the log the user is asked to attach to a bug report.
     */
    private fun recordAck(relayUrl: String, ok: RelayMessage.Ok) {
        val ack = PublishAck(accepted = ok.accepted, message = ok.message)
        _publishResults.update { results ->
            val perRelay = results[ok.eventId].orEmpty() + (relayUrl to ack)
            results + (ok.eventId to perRelay)
        }
        if (ok.accepted) {
            Log.i("RelayPool", "$relayUrl accepted ${ok.eventId.take(8)}…${if (ok.message.isNotBlank()) " (${ok.message})" else ""}")
        } else {
            Log.w(
                "RelayPool",
                "$relayUrl REJECTED ${ok.eventId.take(8)}…: ${ok.message.ifBlank { "no reason given" }}",
            )
        }
    }

    /** Latest known per-relay outcome for [eventId]; empty until relays answer. */
    fun acksFor(eventId: String): Map<String, PublishAck> = _publishResults.value[eventId].orEmpty()

    private fun newSubId(): String = UUID.randomUUID().toString().take(8)

    // ── Data classes ──────────────────────────────────────────────────────────

    private data class RelayEntry(val connection: RelayConnection, val job: Job)
    private data class Subscription(
        val id: String,
        val filters: List<Filter>,
        val label: String,
        /** When set, this subscription only talks to these relays (null = all). */
        val relays: Set<String>? = null,
    )
}

/**
 * A relay message tagged with the relay URL it arrived from.
 */
data class PoolMessage(
    val relayUrl: String,
    val message: RelayMessage,
)

/** One relay's answer to a published event (NIP-01 `OK`). */
data class PublishAck(
    val accepted: Boolean,
    val message: String,
)

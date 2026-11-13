package social.tbone.desktop.app

import kotlinx.coroutines.launch
import social.tbone.core.Log
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.Filter
import social.tbone.core.nostr.relay.RelayMessage
import social.tbone.core.nostr.replyEventId
import social.tbone.core.nostr.rootEventId
import social.tbone.desktop.ui.ThreadData

/**
 * Thread loading — port of upstream's ThreadViewModel.
 *
 * Walks the NIP-10 chain the same way: root → parent → focus, then direct
 * replies (`#e` on the focus id, kind 1) ordered by time. Verification is
 * applied to every event before it is shown, and anything already in the local
 * event cache is used first so a thread opens instantly instead of waiting on
 * relays.
 */
suspend fun AppCore.loadThread(eventId: String): Result<ThreadData> = runCatching {
    val cached = events.byId(eventId)
    val focus = cached ?: fetchOne(eventId)
        ?: error("That note could not be found on the connected relays.")

    // Ancestors: walk up at most 3 hops using reply/root tags.
    val ancestors = mutableListOf<Event>()
    var current = focus
    repeat(3) {
        val parentId = current.parsedTags.replyEventId ?: current.parsedTags.rootEventId ?: return@repeat
        if (ancestors.any { it.id == parentId }) return@repeat
        val parent = events.byId(parentId) ?: fetchOne(parentId) ?: return@repeat
        ancestors.add(0, parent)
        current = parent
    }

    val replies = fetchReplies(focus.id)
    ThreadData(focus, ancestors, replies)
}.onFailure { Log.w("ThreadService", "thread load failed for $eventId: ${it.message}") }

/**
 * One-shot fetch of a bounded set of events, then unsubscribe.
 *
 * Deliberately *not* a flow-filter race: the listener is installed on the pool
 * for the whole window, results accumulate by id, and the loop ends on EOSE or a
 * timeout. This is the same "collect until settled" shape upstream used for
 * quote/metadata fetches, and it cannot hang when a relay never sends EOSE.
 */
private suspend fun AppCore.fetchEvents(filters: List<Filter>, windowMs: Long = 6_000): List<Event> {
    val subId = pool.subscribe(filters, label = "thread-fetch")
    val collected = LinkedHashMap<String, Event>()
    val listener = scope.launch {
        pool.messages.collect { msg ->
            val m = msg.message as? RelayMessage.EventMessage ?: return@collect
            if (m.subscriptionId == subId && m.event.verify()) {
                synchronized(collected) { collected[m.event.id] = m.event }
            }
        }
    }
    kotlinx.coroutines.delay(windowMs)
    listener.cancel()
    pool.unsubscribe(subId)
    accounts.activeAccount.value?.let { acc ->
        val list = synchronized(collected) { collected.values.toList() }
        if (list.isNotEmpty()) events.saveAll(list, acc.pubkey)
    }
    return synchronized(collected) { collected.values.toList() }
}

private suspend fun AppCore.fetchOne(id: String): Event? =
    fetchEvents(listOf(Filter(ids = listOf(id)))).firstOrNull { it.id == id }

private suspend fun AppCore.fetchReplies(rootId: String): List<Event> =
    fetchEvents(
        listOf(Filter(kinds = listOf(EventKind.TEXT_NOTE), eTags = listOf(rootId), limit = 200)),
    )
        // Only direct children of the focused note, oldest first (chat order).
        .filter { it.parsedTags.replyEventId == rootId || it.parsedTags.rootEventId == rootId }
        .sortedBy { it.createdAt }

/** Cached lookup used to paint the focused note before the network answers. */
fun AppCore.eventsSnapshot(id: String): Event? =
    runCatching { kotlinx.coroutines.runBlocking { events.byId(id) } }.getOrNull()

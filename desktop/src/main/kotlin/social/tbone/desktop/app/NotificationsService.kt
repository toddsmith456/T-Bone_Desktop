package social.tbone.desktop.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import social.tbone.core.Log
import social.tbone.core.store.KindSets
import social.tbone.core.Tunables
import social.tbone.core.account.Account
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.Filter
import social.tbone.core.nostr.relay.RelayMessage
import social.tbone.core.nostr.replyEventId
import social.tbone.core.nostr.rootEventId
import social.tbone.core.nostr.quotedEventId
import social.tbone.core.nostr.pubkeys
import java.util.concurrent.ConcurrentHashMap

/** Notification types, mirroring upstream's NotifFilter names. */
enum class NotifKind(val label: String) {
    REPLY("reply"),
    REACTION("reaction"),
    REPOST("repost"),
    QUOTE("quote"),
    MENTION("mention"),
    VOTE("vote"),
}

data class NotifItem(
    val id: String,
    val kind: NotifKind,
    /** Who caused it (actor) — empty for aggregates over several actors. */
    val actors: List<String>,
    val createdAt: Long,
    /** The note being talked about (yours), when applicable. */
    val targetEventId: String?,
    /** The actor's event (the reply/reaction/repost itself). */
    val event: Event,
    /** Reaction content, e.g. "+", "❤". */
    val reaction: String? = null,
    val read: Boolean = false,
)

/**
 * Notifications — port of upstream's `NotificationsRepository`.
 *
 * Kept from the phone app:
 *  - the same five sources (replies, reactions, reposts, quotes, mentions) plus
 *    poll votes, derived from the same filters (`#p` on your pubkey);
 *  - ownership checks: a reaction/reply only notifies when it points at one of
 *    *your* notes, so third-party chatter can't manufacture notifications;
 *  - grouping of reactions per target note with per-emoji actor lists;
 *  - unread tracking against `last_viewed_notifications_at`.
 *
 * Simplified relative to Android: notifications are held in memory for the
 * session and persisted per event id in the event store; there is no Android
 * notification channel (there is no notification centre on the desktop target),
 * and no push — the bell in the sidebar carries the unread dot exactly as the
 * phone's tab did.
 */
class NotificationsService(private val core: AppCore) {

    private val _items = MutableStateFlow<List<NotifItem>>(emptyList())
    val items: StateFlow<List<NotifItem>> = _items.asStateFlow()

    private val _unread = MutableStateFlow(false)
    val unread: StateFlow<Boolean> = _unread.asStateFlow()

    private val ownEventIds = ConcurrentHashMap.newKeySet<String>()
    private val seen = ConcurrentHashMap<String, Long>()
    private var job: Job? = null
    private var subId: String? = null

    fun start(account: Account?) {
        job?.cancel()
        subId?.let { core.pool.unsubscribe(it) }
        subId = null
        _items.value = emptyList()
        if (account == null) return

        ownEventIds.clear()
        seen.clear()

        subId = core.pool.subscribe(
            listOf(
                Filter(kinds = KindSets.NOTIFICATIONS, pTags = listOf(account.pubkey), limit = 100),
                // Your own notes: needed to prove ownership of the target of each notification.
                Filter(authors = listOf(account.pubkey), kinds = KindSets.FEED, limit = 50),
            ),
            label = "notifications",
        )

        job = core.scope.launch {
            core.pool.messages.collect { msg ->
                when (val m = msg.message) {
                    is RelayMessage.EventMessage -> {
                        if (m.subscriptionId != subId) return@collect
                        if (!m.event.verify()) return@collect
                        val e = m.event
                        if (e.pubkey == account.pubkey) {
                            ownEventIds.add(e.id)
                            core.scope.launch { core.events.save(e, account.pubkey) }
                        } else {
                            ingest(e, account)
                        }
                    }
                    is RelayMessage.EndOfStoredEvents ->
                        if (m.subscriptionId == subId) Log.d("Notifications", "EOSE (${_items.value.size} items)")
                    else -> Unit
                }
            }
        }

        refreshUnread()
    }

    private fun ingest(e: Event, account: Account) {
        // Only your own notes can generate notifications.
        val target = e.parsedTags.pubkeys.firstOrNull()
        val refersToYou = target == account.pubkey
        if (!refersToYou) return

        val item = when (e.kind) {
            EventKind.REACTION -> {
                val targetId = e.parsedTags.firstOrNull { it.name == "e" }?.value() ?: return
                if (!isOwn(targetId)) return
                NotifItem(e.id, NotifKind.REACTION, listOf(e.pubkey), e.createdAt, targetId, e, reaction = e.content.ifBlank { "+" })
            }
            EventKind.REPOST -> {
                val targetId = e.parsedTags.firstOrNull { it.name == "e" }?.value() ?: return
                if (!isOwn(targetId)) return
                NotifItem(e.id, NotifKind.REPOST, listOf(e.pubkey), e.createdAt, targetId, e)
            }
            EventKind.TEXT_NOTE -> {
                val replyTo = e.parsedTags.replyEventId
                val quoteOf = e.parsedTags.quotedEventId
                when {
                    replyTo != null && isOwn(replyTo) ->
                        NotifItem(e.id, NotifKind.REPLY, listOf(e.pubkey), e.createdAt, replyTo, e)
                    quoteOf != null && isOwn(quoteOf) ->
                        NotifItem(e.id, NotifKind.QUOTE, listOf(e.pubkey), e.createdAt, quoteOf, e)
                    // Mention: p-tagged you but is not a reply into your own thread.
                    e.parsedTags.pubkeys.contains(account.pubkey) ->
                        NotifItem(e.id, NotifKind.MENTION, listOf(e.pubkey), e.createdAt, null, e)
                    else -> null
                }
            }
            EventKind.POLL_RESPONSE -> {
                val pollId = e.parsedTags.firstOrNull { it.name == "e" }?.value() ?: return
                if (!isOwn(pollId)) return
                NotifItem(e.id, NotifKind.VOTE, listOf(e.pubkey), e.createdAt, pollId, e)
            }
            else -> null
        } ?: return

        // Respect the per-type filters the user switched off.
        val enabled = core.settings.notificationEnabledTypes.value
        if (enabled.isNotEmpty() && item.kind.label !in enabled) return

        seen[item.id] = item.createdAt
        _items.value = (_items.value + item).sortedByDescending { it.createdAt }
        refreshUnread()
        fetchActorProfile(item.actors)
    }

    /** Metadata lookups for notification actors, released after a short window. */
    private fun fetchActorProfile(actors: List<String>) {
        val unknown = actors.filter { core.profiles.profile(it) == null }
        if (unknown.isEmpty()) return
        val id = core.pool.subscribe(
            listOf(Filter(authors = unknown, kinds = listOf(EventKind.METADATA))),
            label = "notif-profiles",
        )
        core.scope.launch {
            delay(4_000)
            core.pool.unsubscribe(id)
        }
    }

    private fun isOwn(eventId: String): Boolean = ownEventIds.contains(eventId)

    fun markRead() {
        val now = System.currentTimeMillis() / 1000
        core.settings.setLastViewedNotificationsAt(now)
        refreshUnread()
    }

    fun refreshUnread() {
        val lastViewed = core.settings.lastViewedNotificationsAt.value
        _unread.value = _items.value.any { it.createdAt > lastViewed }
    }

    /** Grouped view: one row per (type, target note), actors merged. */
    fun grouped(): List<NotifItem> {
        val byKey = LinkedHashMap<String, NotifItem>()
        _items.value.sortedByDescending { it.createdAt }.forEach { item ->
            val key = "${item.kind}:${item.targetEventId ?: item.id}:${item.reaction ?: ""}"
            val existing = byKey[key]
            if (existing == null) {
                byKey[key] = item
            } else if (existing.actors.size < 8) {
                byKey[key] = existing.copy(actors = (existing.actors + item.actors).distinct())
            }
        }
        return byKey.values.toList()
    }

    fun stop() {
        job?.cancel()
        subId?.let { core.pool.unsubscribe(it) }
        subId = null
    }
}

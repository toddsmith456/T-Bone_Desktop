package social.tbone.desktop.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import social.tbone.core.Log
import social.tbone.core.Tunables
import social.tbone.core.account.Account
import social.tbone.core.account.FollowEntry
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.Filter
import social.tbone.core.nostr.ProfileContent
import social.tbone.core.nostr.relay.RelayMessage
import social.tbone.core.nostr.relay.RelayPool
import social.tbone.core.nostr.pubkeys
import social.tbone.core.nostr.quotedEventId
import social.tbone.core.nostr.rootEventId
import social.tbone.core.nostr.replyEventId
import social.tbone.core.settings.ContentFilter
import social.tbone.core.store.KindSets
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

enum class FeedTab { HOME, GLOBAL }

data class FeedUiState(
    val events: List<Event> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val seenCount: Int = 0,
)

/**
 * Feed loading and timeline state — the desktop equivalent of upstream's
 * `FeedViewModel`.
 *
 * Behaviour carried over deliberately (these are the parts that make the feed
 * feel right, and they were tuned upstream):
 *
 *  - **HOME vs GLOBAL.** HOME is the follow graph; GLOBAL is an unfiltered
 *    kind 1/6/1068 window and is never written to the local cache.
 *  - **Own notes first, then expand.** A narrow subscription on your own pubkey
 *    is shown first; when the kind-3 follow list arrives the subscription is
 *    replaced with the full author set.
 *  - **Atomic settle.** Incoming events buffer until the expanded subscription's
 *    EOSE (or a 15 s safety net), then flush in one go so the feed appears all at
 *    once instead of trickling in.
 *  - **NIP-10 reply suppression.** Replies stay out of the timeline; a note is a
 *    reply when it carries "reply"/"root" markers or more than one e-tag.
 *  - **Outbox model.** kind-10002 for follows drives greedy set-cover relay
 *    selection, capped by MAX_OUTBOX_RELAYS / MAX_TOTAL_RELAYS, always keeping
 *    the user's own relays.
 *  - **Content filtering and verification.** Only signature-verified events
 *    enter the feed, and blocked/NSFW/hidden-word filtering is applied at
 *    publish-to-UI time, exactly as upstream.
 */
class FeedService(private val core: AppCore) {

    private val _ui = MutableStateFlow(FeedUiState())
    val ui: StateFlow<FeedUiState> = _ui.asStateFlow()

    private val _tab = MutableStateFlow(FeedTab.HOME)
    val tab: StateFlow<FeedTab> = _tab.asStateFlow()

    private val _quoted = MutableStateFlow<Map<String, Event>>(emptyMap())
    val quoted: StateFlow<Map<String, Event>> = _quoted.asStateFlow()

    /** event id → set of pubkeys that reacted (NIP-25), as loaded from relays. */
    private val _reactions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    val reactions: StateFlow<Map<String, Set<String>>> = _reactions.asStateFlow()

    /** parent event id → reply count. */
    private val _replyCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val replyCounts: StateFlow<Map<String, Int>> = _replyCounts.asStateFlow()

    /** NIP-65 relay hints harvested from follow relay lists. */
    private val followRelayMap = ConcurrentHashMap<String, MutableSet<String>>()

    private val feedEvents = TreeSet<Event>(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
    private val pendingBuffer = ConcurrentLinkedQueue<Event>()
    private val activeSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val quoteSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val metadataFetchSubIds: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val requestedQuoteIds: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private var collectJob: Job? = null
    private var feedSubId: String? = null
    private var followSubId: String? = null
    private var metadataSubId: String? = null
    private var relayListSubId: String? = null
    private var followsRelayListSubId: String? = null
    private var followsForRelaySelection: List<String> = emptyList()
    private var lastLoadedPubkey: String? = null

    @Volatile private var feedSettled = false
    @Volatile private var followsReceived = false
    @Volatile private var running = false
    private var currentAccount: Account? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        if (running) return
        running = true
        collectJob = core.scope.launch(kotlinx.coroutines.Dispatchers.Default) { collectMessages() }
    }

    fun onAccountChanged(account: Account?) {
        currentAccount = account
        if (account == null) clearFeed() else loadFeed(account)
    }

    fun switchTab(tab: FeedTab) {
        if (_tab.value == tab) return
        _tab.value = tab
        val account = currentAccount ?: return
        if (tab == FeedTab.HOME) loadFeed(account) else loadGlobalFeed(account)
    }

    fun refresh() {
        val account = currentAccount ?: return
        if (_tab.value == FeedTab.HOME) loadFeed(account, clearEvents = false) else loadGlobalFeed(account)
    }

    fun invalidateFollowing() {
        val account = currentAccount ?: return
        loadFeed(account, clearEvents = false)
    }

    // ── Feed loading ──────────────────────────────────────────────────────────

    private fun resetSubscriptions() {
        activeSubIds.clear()
        unsub(feedSubId); feedSubId = null
        unsub(followSubId); followSubId = null
        unsub(metadataSubId); metadataSubId = null
        unsub(relayListSubId); relayListSubId = null
        unsub(followsRelayListSubId); followsRelayListSubId = null
        followsForRelaySelection = emptyList()
        followRelayMap.clear()
        feedSettled = false
        followsReceived = false
        pendingBuffer.clear()
        requestedQuoteIds.clear()
        quoteSubIds.forEach { core.pool.unsubscribe(it) }
        quoteSubIds.clear()
        metadataFetchSubIds.forEach { core.pool.unsubscribe(it) }
        metadataFetchSubIds.clear()
    }

    private fun loadFeed(account: Account, clearEvents: Boolean = true) {
        resetSubscriptions()
        synchronized(feedEvents) { if (clearEvents) feedEvents.clear() }

        _ui.update {
            if (clearEvents) it.copy(events = emptyList(), isLoading = true, error = null)
            else it.copy(isLoading = true, error = null)
        }

        val isSameAccount = lastLoadedPubkey == account.pubkey
        lastLoadedPubkey = account.pubkey

        if (isSameAccount) {
            core.scope.launch {
                val cached = core.events.recent(account.pubkey, KindSets.FEED, Tunables.MAX_FEED_EVENTS)
                if (cached.isNotEmpty()) {
                    val snapshot = synchronized(feedEvents) {
                        feedEvents.addAll(cached)
                        feedEvents.take(Tunables.MAX_FEED_EVENTS)
                    }
                    _ui.update { s -> s.copy(events = applyContentFilter(snapshot), seenCount = snapshot.size) }
                    fetchProfilesForEvents(cached)
                }
            }
        }

        val relays = account.relays.ifEmpty { Tunables.DEFAULT_RELAYS }
        relays.forEach { core.pool.addRelay(it) }

        feedSubId = sub(
            listOf(
                Filter(
                    authors = listOf(account.pubkey),
                    kinds = KindSets.FEED,
                    limit = 50,
                )
            ),
            label = "own",
        )
        followSubId = sub(
            listOf(Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.FOLLOW_LIST), limit = 1)),
            label = "follows",
        )
        metadataSubId = sub(
            listOf(Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.METADATA), limit = 1)),
            label = "self-meta",
        )
        relayListSubId = sub(
            listOf(Filter(authors = listOf(account.pubkey), kinds = listOf(EventKind.RELAY_LIST), limit = 1)),
            label = "self-relays",
        )

        core.scope.launch {
            delay(SETTLE_TIMEOUT_MS)
            if (!feedSettled) {
                feedSettled = true
                flushBuffer()
            }
            _ui.update { if (it.isLoading) it.copy(isLoading = false) else it }
        }
    }

    private fun loadGlobalFeed(account: Account) {
        resetSubscriptions()
        synchronized(feedEvents) { feedEvents.clear() }
        lastLoadedPubkey = null
        _ui.update { it.copy(events = emptyList(), isLoading = true, error = null) }

        val relays = account.relays.ifEmpty { Tunables.DEFAULT_RELAYS }
        relays.forEach { core.pool.addRelay(it) }

        feedSubId = sub(listOf(Filter(kinds = KindSets.FEED, limit = 100)), label = "global")
        followsReceived = true

        core.scope.launch {
            delay(SETTLE_TIMEOUT_MS)
            if (!feedSettled) { feedSettled = true; flushBuffer() }
            _ui.update { if (it.isLoading) it.copy(isLoading = false) else it }
        }
    }

    private fun sub(filters: List<Filter>, label: String): String =
        core.pool.subscribe(filters, label = label).also { activeSubIds.add(it) }

    private fun unsub(id: String?) {
        id ?: return
        activeSubIds.remove(id)
        core.pool.unsubscribe(id)
    }

    private fun clearFeed() {
        resetSubscriptions()
        synchronized(feedEvents) { feedEvents.clear() }
        _ui.value = FeedUiState(isLoading = false)
    }

    // ── Message pump ──────────────────────────────────────────────────────────

    private suspend fun collectMessages() {
        core.pool.messages.collect { poolMessage ->
            when (val msg = poolMessage.message) {
                is RelayMessage.EventMessage -> {
                    when {
                        msg.subscriptionId in activeSubIds -> {
                            if (msg.event.verify()) handleEvent(msg.event)
                        }
                        msg.subscriptionId in quoteSubIds -> {
                            if (msg.event.verify()) handleQuoteEvent(msg.event)
                        }
                    }
                }
                is RelayMessage.EndOfStoredEvents -> {
                    when {
                        msg.subscriptionId in quoteSubIds -> {
                            quoteSubIds.remove(msg.subscriptionId)
                            core.pool.unsubscribe(msg.subscriptionId)
                        }
                        msg.subscriptionId in metadataFetchSubIds -> {
                            metadataFetchSubIds.remove(msg.subscriptionId)
                            unsub(msg.subscriptionId)
                        }
                        msg.subscriptionId !in activeSubIds -> Unit
                        msg.subscriptionId == followsRelayListSubId -> {
                            unsub(followsRelayListSubId); followsRelayListSubId = null
                            if (followsForRelaySelection.isNotEmpty()) {
                                selectAndApplyRelays(followsForRelaySelection)
                            }
                        }
                        msg.subscriptionId == feedSubId && followsReceived && !feedSettled -> {
                            feedSettled = true
                            flushBuffer()
                            _ui.update { it.copy(isLoading = false) }
                        }
                        msg.subscriptionId == followSubId && !followsReceived && !feedSettled -> {
                            feedSettled = true
                            flushBuffer()
                            _ui.update { it.copy(isLoading = false) }
                        }
                        else -> _ui.update { it.copy(isLoading = false) }
                    }
                }
                else -> Unit
            }
        }
    }

    private fun handleEvent(event: Event) {
        when (event.kind) {
            EventKind.FOLLOW_LIST -> expandFeedToFollows(event)
            EventKind.METADATA -> {
                core.scope.launch { core.profiles.put(event.pubkey, event.content) }
                val selfPubkey = currentAccount?.pubkey ?: return
                if (event.pubkey == selfPubkey) {
                    val content = ProfileContent.parse(event.content) ?: return
                    core.scope.launch {
                        core.accounts.updateAccount(selfPubkey) {
                            it.copy(displayName = content.bestName, pictureUrl = content.picture)
                        }
                    }
                }
            }
            EventKind.RELAY_LIST -> {
                val selfPubkey = currentAccount?.pubkey ?: return
                if (event.pubkey == selfPubkey) handleSelfRelayList(event) else handleFollowRelayList(event)
            }
            EventKind.TEXT_NOTE -> {
                val eTags = event.parsedTags.filter { it.name == "e" }
                val isReply = eTags.size > 1 || eTags.any { tag ->
                    val marker = tag.value(3) ?: tag.value(2)
                    marker == "reply" || marker == "root"
                }
                if (isReply) return
                addToFeed(event)
            }
            EventKind.REPOST, EventKind.POLL -> addToFeed(event)
        }
    }

    private fun handleQuoteEvent(event: Event) {
        _quoted.update { it + (event.id to event) }
        core.scope.launch { core.events.save(event, "") }
        fetchMetadataForAuthors(listOf(event.pubkey))
    }

    private fun expandFeedToFollows(event: Event) {
        val followed = event.parsedTags.pubkeys
        if (followed.isEmpty()) return
        unsub(followSubId); followSubId = null

        val selfPubkey = currentAccount?.pubkey ?: return
        val allPubkeys = (followed + selfPubkey).distinct()

        followsReceived = true
        unsub(feedSubId)
        feedSettled = false
        feedSubId = sub(
            listOf(Filter(authors = allPubkeys, kinds = KindSets.FEED, limit = 200)),
            label = "home",
        )

        fetchMetadataForAuthors(allPubkeys)

        val entries = event.parsedTags
            .filter { it.name == "p" }
            .mapNotNull { tag ->
                val pk = tag.value(1) ?: return@mapNotNull null
                FollowEntry(pubkey = pk, relay = tag.value(2) ?: "", petname = tag.value(3) ?: "")
            }
        core.scope.launch {
            core.accounts.updateAccount(selfPubkey) {
                it.copy(follows = followed, followEntries = entries)
            }
        }

        unsub(followsRelayListSubId)
        followRelayMap.clear()
        followsForRelaySelection = followed
        followsRelayListSubId = sub(
            listOf(Filter(authors = followed, kinds = listOf(EventKind.RELAY_LIST))),
            label = "follow-relays",
        )
        core.scope.launch {
            delay(OUTBOX_WINDOW_MS)
            if (followsRelayListSubId != null) {
                unsub(followsRelayListSubId); followsRelayListSubId = null
                selectAndApplyRelays(followed)
            }
        }
    }

    private fun handleSelfRelayList(event: Event) {
        // NIP-65: seed from the network only when the account has no local list —
        // local changes are never clobbered by a stale published snapshot.
        val readRelays = event.parsedTags
            .filter { it.name == "r" }
            .mapNotNull { tag ->
                val url = tag.value() ?: return@mapNotNull null
                val marker = tag.value(2)
                if (marker == null || marker == "read") url else null
            }
        if (readRelays.isEmpty()) return
        unsub(relayListSubId); relayListSubId = null
        val account = currentAccount ?: return
        if (account.relays.isNotEmpty()) return
        val normalized = readRelays.map { RelayPool.normalize(it) }.distinct()
            .take(Tunables.MAX_TOTAL_RELAYS)
        core.scope.launch {
            core.accounts.updateAccount(account.pubkey) { current ->
                if (current.relays.isEmpty()) current.copy(relays = normalized) else current
            }
            normalized.forEach { core.pool.addRelay(it) }
        }
    }

    private fun handleFollowRelayList(event: Event) {
        val writeRelays = event.parsedTags
            .filter { it.name == "r" }
            .mapNotNull { tag ->
                val url = tag.value() ?: return@mapNotNull null
                val marker = tag.value(2)
                if (marker == null || marker == "write") url else null
            }
        writeRelays.forEach { relay ->
            followRelayMap.getOrPut(relay) { ConcurrentHashMap.newKeySet() }.add(event.pubkey)
        }
    }

    /**
     * Greedy set cover over follow→relay hints: pick the relays that reach the
     * most follows first, up to [maxRelays].
     */
    private fun greedyRelaySelection(follows: List<String>, maxRelays: Int = Tunables.MAX_OUTBOX_RELAYS): List<String> {
        if (followRelayMap.isEmpty()) return emptyList()
        val uncovered = follows.toMutableSet()
        val selected = mutableListOf<String>()
        while (uncovered.isNotEmpty() && selected.size < maxRelays) {
            val best = followRelayMap.entries
                .filter { it.key !in selected }
                .maxByOrNull { e -> e.value.count { it in uncovered } }
                ?: break
            if (best.value.none { it in uncovered }) break
            selected.add(best.key)
            uncovered.removeAll(best.value)
        }
        Log.d("FeedService", "relay selection: ${selected.size} relays cover ${follows.size - uncovered.size}/${follows.size} follows")
        return selected
    }

    private fun selectAndApplyRelays(follows: List<String>) {
        val selfPubkey = currentAccount?.pubkey ?: return
        val outbox = greedyRelaySelection(follows)
        core.scope.launch {
            var applied = emptyList<String>()
            core.accounts.updateAccount(selfPubkey) { account ->
                val base = account.relays.ifEmpty { Tunables.DEFAULT_RELAYS }
                val baseNorm = base.map { RelayPool.normalize(it) }.distinct()
                // Keep every user relay; fill the remaining slots with outbox picks.
                val remaining = (Tunables.MAX_TOTAL_RELAYS - baseNorm.size).coerceAtLeast(0)
                val outboxFill = outbox.map { RelayPool.normalize(it) }.distinct()
                    .filterNot { it in baseNorm }.take(remaining)
                applied = baseNorm + outboxFill
                account.copy(relays = applied)
            }
            val current = core.pool.relayUrls()
            applied.forEach { core.pool.addRelay(it) }
            (current - applied.toSet()).forEach { core.pool.removeRelay(it) }
            Log.d("FeedService", "applied relay set (${applied.size}): $applied")
        }
    }

    // ── Buffer / publish to UI ────────────────────────────────────────────────

    private fun addToFeed(event: Event) {
        if (_tab.value == FeedTab.HOME) {
            val accountPubkey = currentAccount?.pubkey
            if (accountPubkey != null) core.scope.launch { core.events.save(event, accountPubkey) }
        }
        if (!feedSettled) {
            pendingBuffer.add(event)
            return
        }
        val snapshot = synchronized(feedEvents) {
            feedEvents.add(event)
            feedEvents.take(Tunables.MAX_FEED_EVENTS)
        }
        _ui.update { it.copy(events = applyContentFilter(snapshot), seenCount = snapshot.size) }
        fetchQuotesForEvents(listOf(event))
        fetchProfilesForEvents(listOf(event))
        subscribeReactions(listOf(event.id), event)
    }

    private fun flushBuffer() {
        val events = pendingBuffer.toList().also { pendingBuffer.clear() }
        if (events.isEmpty()) return
        val snapshot = synchronized(feedEvents) {
            feedEvents.addAll(events)
            feedEvents.take(Tunables.MAX_FEED_EVENTS)
        }
        _ui.update { it.copy(events = applyContentFilter(snapshot), seenCount = snapshot.size) }
        fetchQuotesForEvents(events)
        fetchProfilesForEvents(events)
        subscribeReactions(events.map { it.id }, null)
    }

    /** Applies blocked pubkeys, NSFW hiding and hidden words before display. */
    private fun applyContentFilter(events: List<Event>): List<Event> {
        val blocked = core.settings.blockedPubkeys.value
        val hideNsfw = core.settings.hideNsfw.value
        val hideWords = core.settings.hideWords.value
        if (blocked.isEmpty() && !hideNsfw && hideWords.isEmpty()) return events
        return events.filter { !ContentFilter.shouldHide(it, blocked, hideNsfw, hideWords) }
    }

    // ── Reactions / quote resolution ──────────────────────────────────────────

    private fun subscribeReactions(ids: List<String>, forEvent: Event?) {
        if (ids.isEmpty()) return
        val sub = core.pool.subscribe(
            listOf(
                Filter(kinds = listOf(EventKind.REACTION), eTags = ids.take(150)),
                Filter(kinds = listOf(EventKind.TEXT_NOTE), eTags = ids.take(150)),
            ),
            label = "engagement",
        )
        activeSubIds.add(sub)
        core.scope.launch {
            delay(Tunables.REACTION_EOSE_TIMEOUT_MS)
            unsub(sub)
        }
    }

    fun noteReacted(eventId: String, pubkey: String) {
        _reactions.update { map ->
            val set = map[eventId].orEmpty() + pubkey
            map + (eventId to set)
        }
    }

    fun noteReposted(eventId: String, pubkey: String) = noteReacted(eventId, pubkey)

    fun addOwnEvent(event: Event) {
        val snapshot = synchronized(feedEvents) {
            feedEvents.add(event)
            feedEvents.take(Tunables.MAX_FEED_EVENTS)
        }
        _ui.update { it.copy(events = applyContentFilter(snapshot), seenCount = snapshot.size) }
    }

    fun removeEvent(eventId: String) {
        val snapshot = synchronized(feedEvents) {
            feedEvents.removeAll { it.id == eventId }
            feedEvents.toList()
        }
        _ui.update { it.copy(events = snapshot, seenCount = snapshot.size) }
    }

    private fun fetchQuotesForEvents(events: List<Event>) {
        val toResolve = mutableListOf<String>()
        for (event in events) {
            when (event.kind) {
                EventKind.REPOST -> {
                    val embedded = if (event.content.length > Tunables.MAX_REPOST_CONTENT_BYTES) null
                    else runCatching { Event.fromJson(event.content) }.getOrNull()
                    if (embedded != null && embedded.verify()) {
                        _quoted.update { it + (embedded.id to embedded) }
                        fetchMetadataForAuthors(listOf(embedded.pubkey))
                    } else {
                        val refId = event.parsedTags.firstOrNull { it.name == "e" }?.value()
                        if (refId != null && refId !in requestedQuoteIds) toResolve.add(refId)
                    }
                }
                EventKind.TEXT_NOTE -> {
                    val qId = event.parsedTags.quotedEventId
                        ?: permissiveQuoteId(event)
                    if (qId != null && qId !in requestedQuoteIds) toResolve.add(qId)
                }
            }
        }
        val missing = toResolve.distinct().filter { it !in _quoted.value }
        if (missing.isEmpty()) return
        missing.forEach { requestedQuoteIds.add(it) }

        core.scope.launch {
            val cached = core.events.byIds(missing).associateBy { it.id }
            if (cached.isNotEmpty()) {
                _quoted.update { it + cached }
                fetchMetadataForAuthors(cached.values.map { it.pubkey })
            }
            val stillMissing = missing.filter { it !in cached }
            if (stillMissing.isEmpty()) return@launch
            val subId = core.pool.subscribe(
                listOf(Filter(ids = stillMissing, kinds = listOf(EventKind.TEXT_NOTE))),
                label = "quote",
            )
            quoteSubIds.add(subId)
        }
    }

    /**
     * Upstream also recognised inline `nostr:note1…` / `nostr:nevent1…` quotes in
     * note bodies (extractInlineQuoteId). Same rule here: take the first bech32
     * quote reference that resolves to a hex id.
     */
    private fun permissiveQuoteId(event: Event): String? {
        val regex = Regex("""nostr:(note1|nevent1)[0-9a-z]+""")
        val hit = regex.findAll(event.content).firstOrNull()?.value ?: return null
        return social.tbone.core.nostr.Nip19.nostrUriToEventId(hit)
    }

    private fun fetchMetadataForAuthors(pubkeys: List<String>) {
        val unknown = pubkeys.distinct().filter { core.profiles.profile(it) == null }
        if (unknown.isEmpty()) return
        val id = sub(listOf(Filter(authors = unknown, kinds = listOf(EventKind.METADATA))), label = "metadata")
        metadataFetchSubIds.add(id)
    }

    private fun fetchProfilesForEvents(events: List<Event>) {
        val pubkeys = events.flatMap { event ->
            buildList {
                add(event.pubkey)
                if (event.kind == EventKind.TEXT_NOTE) addAll(event.parsedTags.pubkeys)
            }
        }
        fetchMetadataForAuthors(pubkeys)
    }

    /** Thread helpers used by the thread screen. */
    fun rootIdOf(event: Event): String? = event.parsedTags.rootEventId ?: event.parsedTags.replyEventId

    companion object {
        private const val SETTLE_TIMEOUT_MS = 15_000L
        private const val OUTBOX_WINDOW_MS = 5_000L
    }
}

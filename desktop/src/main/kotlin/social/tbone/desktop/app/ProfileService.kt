package social.tbone.desktop.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import social.tbone.core.Log
import social.tbone.core.store.KindSets
import social.tbone.core.account.FollowEntry
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.Filter
import social.tbone.core.nostr.Nip19
import social.tbone.core.nostr.ProfileContent
import social.tbone.core.nostr.pubkeys
import social.tbone.core.nostr.relay.RelayMessage

/** Everything the profile screen shows, resolved in one pass. */
data class ProfileData(
    val pubkey: String,
    val profile: ProfileContent?,
    val notes: List<Event>,
    val followers: List<String>,
    val following: List<String>,
    val relayHints: List<String>,
    val isFollowing: Boolean,
    val isSelf: Boolean,
    val isBlocked: Boolean,
)

/**
 * Profile loading — port of upstream's ProfileRepository/ProfileViewModel.
 *
 * Same sources: kind-0 metadata, the author's own kind-1s, their kind-3 for the
 * follow graph, and NIP-65 relay hints. Follow/unfollow publishes a replacement
 * kind-3 exactly as the phone app did.
 */
suspend fun AppCore.loadProfile(pubkey: String): Result<ProfileData> = runCatching {
    val active = accounts.activeAccount.value

    // 1. Metadata + notes from the local cache first (instant paint).
    val cachedMeta = events.byAuthor(pubkey, listOf(EventKind.METADATA), 1).firstOrNull()
    var profile = cachedMeta?.let { ProfileContent.parse(it.content) } ?: profiles.profile(pubkey)
    var notes = events.byAuthor(pubkey, KindSets.FEED, 50)

    // 2. Subscribe for anything missing; bounded wait, same pattern as threads.
    val subId = pool.subscribe(
        listOf(
            Filter(authors = listOf(pubkey), kinds = listOf(EventKind.METADATA), limit = 1),
            Filter(authors = listOf(pubkey), kinds = KindSets.FEED, limit = 50),
            Filter(authors = listOf(pubkey), kinds = listOf(EventKind.FOLLOW_LIST), limit = 1),
            Filter(authors = listOf(pubkey), kinds = listOf(EventKind.RELAY_LIST), limit = 1),
        ),
        label = "profile",
    )
    var followList: Event? = null
    var relayList: Event? = null
    val collected = LinkedHashMap<String, Event>()

    val listener = scope.launch {
        pool.messages.collect { msg ->
            val m = msg.message as? RelayMessage.EventMessage ?: return@collect
            if (m.subscriptionId != subId) return@collect
            if (!m.event.verify()) return@collect
            when (m.event.kind) {
                EventKind.METADATA -> profile = ProfileContent.parse(m.event.content) ?: profile
                EventKind.FOLLOW_LIST -> followList = m.event
                EventKind.RELAY_LIST -> relayList = m.event
                else -> synchronized(collected) { collected[m.event.id] = m.event }
            }
        }
    }
    delay(5_000)
    listener.cancel()
    pool.unsubscribe(subId)

    if (profile != null && cachedMeta == null) profile?.bestName?.let { }
    cachedMeta?.let { profiles.put(pubkey, it.content) }
    synchronized(collected) { if (collected.isNotEmpty()) notes = collected.values.sortedByDescending { it.createdAt } }
    active?.let { acc -> if (notes.isNotEmpty()) events.saveAll(notes, acc.pubkey) }

    val following = followList?.parsedTags?.pubkeys ?: emptyList()
    val relayHints = relayList?.parsedTags
        ?.filter { it.name == "r" }
        ?.mapNotNull { it.value() }
        ?: emptyList()

    ProfileData(
            pubkey = pubkey,
            profile = profile,
            notes = notes,
            followers = emptyList(), // relays do not index followers; shown as unknown, not faked
            following = following,
            relayHints = relayHints,
            isFollowing = active?.isFollowing(pubkey) ?: false,
            isSelf = active?.pubkey == pubkey,
        isBlocked = settings.isBlocked(pubkey),
    )
}.onFailure { Log.w("ProfileService", "profile load failed: ${it.message}") }

/** Follow/unfollow, preserving existing relay hints and petnames (upstream behaviour). */
suspend fun AppCore.toggleFollow(pubkey: String, follow: Boolean): Result<Unit> = runCatching {
    val active = accounts.activeAccount.value ?: error("No active account")
    val entries = active.followEntries.filter { it.pubkey != pubkey }.let {
        if (follow) it + FollowEntry(pubkey) else it
    }
    setFollowing(pubkey, follow).getOrThrow()
    accounts.updateAccount(active.pubkey) {
        it.copy(follows = entries.map { e -> e.pubkey }, followEntries = entries)
    }
    feed.invalidateFollowing()
}

/** NIP-19 helpers surfaced to the UI (search accepts npub/nprofile/hex). */
fun normalisePubkeyInput(input: String): String? {
    val trimmed = input.trim().removePrefix("nostr:")
    return when {
        trimmed.startsWith("npub1") -> Nip19.npubToHex(trimmed)
        trimmed.startsWith("nprofile1") -> Nip19.nprofileToHex(trimmed)
        trimmed.length == 64 && trimmed.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' } -> trimmed.lowercase()
        else -> null
    }
}

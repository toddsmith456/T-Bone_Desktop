package social.tbone.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import social.tbone.core.nostr.NostrJson
import social.tbone.core.nostr.ProfileContent
import social.tbone.core.Tunables
import java.util.concurrent.ConcurrentHashMap

/**
 * Profile metadata cache — port of upstream's ProfileRepository (which kept a
 * bounded in-memory LruCache and persisted kind-0 events in Room).
 *
 * Here kind-0 events go to the `events` table via [EventStore] and the parsed
 * `ProfileContent` is memoised in a bounded map, so the feed can render names
 * and avatars without re-parsing JSON on every frame.
 */
class ProfileStore(private val db: DesktopDatabase) {

    private val cache = object : LinkedHashMap<String, ProfileContent>(256, 0.75f, true) {
        override fun removeEldestEntry(m: MutableMap.MutableEntry<String, ProfileContent>?): Boolean =
            size > Tunables.PROFILE_CACHE_SIZE
    }

    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    @Volatile private var loaded = false

    fun profile(pubkey: String): ProfileContent? = synchronized(cache) { cache[pubkey] }

    /**
     * Upserts a parsed kind-0 profile. Callers pass the raw event content so the
     * same text is stored verbatim, as upstream did.
     */
    suspend fun put(pubkey: String, content: String) = withContext(Dispatchers.IO) {
        val parsed = ProfileContent.parse(content) ?: return@withContext
        synchronized(cache) { cache[pubkey] = parsed }
        db.transaction {
            update(
                "INSERT OR REPLACE INTO profiles(pubkey, content, updatedAt) VALUES(?, ?, ?)",
                listOf(pubkey, content, System.currentTimeMillis() / 1000),
            )
        }
        _revision.value = _revision.value + 1
    }

    /** Best display name for a pubkey: profile name, else shortened npub-style hex. */
    fun displayName(pubkey: String): String {
        val p = profile(pubkey)
        return p?.bestName ?: p?.name ?: shortHex(pubkey)
    }

    fun picture(pubkey: String): String? = profile(pubkey)?.picture?.takeIf { it.isNotBlank() }

    suspend fun loadAll() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val now = System.currentTimeMillis()
        db.transaction { update("DELETE FROM profiles", emptyList()) } // rebuild from events below
        db.read {
            query(
                """
                SELECT e.pubkey, e.content FROM events e
                JOIN (SELECT pubkey, MAX(createdAt) AS mx FROM events WHERE kind = 0 GROUP BY pubkey) latest
                  ON e.pubkey = latest.pubkey AND e.createdAt = latest.mx
                WHERE e.kind = 0
                """.trimIndent()
            ) { rs ->
                while (rs.next()) {
                    val pk = rs.getString(1)
                    ProfileContent.parse(rs.getString(2))?.let { synchronized(cache) { cache[pk] = it } }
                }
            }
        }
        loaded = true
        _revision.value = _revision.value + 1
    }

    fun invalidateAll() {
        synchronized(cache) { cache.clear() }
        loaded = false
        _revision.value = _revision.value + 1
    }

    companion object {
        fun shortHex(hex: String): String =
            if (hex.length <= 12) hex else "${hex.take(6)}…${hex.takeLast(4)}"
    }
}

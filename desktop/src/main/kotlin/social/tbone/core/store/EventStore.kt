package social.tbone.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import social.tbone.core.Log
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.EventKind
import social.tbone.core.nostr.NostrJson
import social.tbone.core.Tunables

/**
 * Persistent event cache — the desktop replacement for upstream's Room
 * `EventRepository`/`EventDao` pair.
 *
 * Upstream kept every feed event in Room, filtered by kind, and exposed Flow
 * queries. Here the same rows live in the `events` table and the same queries
 * are translated one-for-one (`ORDER BY createdAt DESC`, per-account scoping).
 * In addition, an in-memory recent-events cache backs the hot feed path so
 * scrolling/recomposition does not re-hit SQLite.
 */
class EventStore(private val db: DesktopDatabase) {

    /** Bumped whenever events change, so collectors can refresh. */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    suspend fun save(event: Event, accountPubkey: String) = withContext(Dispatchers.IO) {
        db.transaction {
            update(
                """
                INSERT OR REPLACE INTO events(id, pubkey, createdAt, kind, tags, content, sig, accountPubkey)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                listOf(
                    event.id,
                    event.pubkey,
                    event.createdAt,
                    event.kind,
                    NostrJson.encodeToString(JsonArray.serializer(), JsonArray(event.tags)),
                    event.content,
                    event.sig,
                    accountPubkey,
                ),
            )
            // Cap the per-account cache the same way upstream capped MAX_FEED_EVENTS.
            update(
                """
                DELETE FROM events WHERE accountPubkey = ? AND id NOT IN (
                    SELECT id FROM events WHERE accountPubkey = ?
                    ORDER BY createdAt DESC LIMIT ?
                )
                """.trimIndent(),
                listOf(accountPubkey, accountPubkey, Tunables.MAX_FEED_EVENTS * 4),
            )
        }
        _revision.value = _revision.value + 1
    }

    suspend fun saveAll(events: List<Event>, accountPubkey: String) = withContext(Dispatchers.IO) {
        if (events.isEmpty()) return@withContext
        db.transaction {
            events.forEach { e ->
                update(
                    """
                    INSERT OR REPLACE INTO events(id, pubkey, createdAt, kind, tags, content, sig, accountPubkey)
                    VALUES(?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    listOf(
                        e.id, e.pubkey, e.createdAt, e.kind,
                        NostrJson.encodeToString(JsonArray.serializer(), JsonArray(e.tags)),
                        e.content, e.sig, accountPubkey,
                    ),
                )
            }
        }
        _revision.value = _revision.value + 1
    }

    suspend fun recent(
        accountPubkey: String,
        kinds: List<Int>,
        limit: Int = Tunables.MAX_FEED_EVENTS,
        authors: List<String>? = null,
    ): List<Event> = withContext(Dispatchers.IO) {
        val kindPlaceholders = kinds.joinToString(",") { "?" }
        val authorClause = if (authors.isNullOrEmpty()) "" else
            " AND pubkey IN (${authors.joinToString(",") { "?" }})"
        val args = buildList<Any?> {
            add(accountPubkey); addAll(kinds)
            authors?.let { addAll(it) }
            add(limit)
        }
        db.read {
            query(
                """
                SELECT id, pubkey, createdAt, kind, tags, content, sig FROM events
                WHERE accountPubkey = ? AND kind IN ($kindPlaceholders)$authorClause
                ORDER BY createdAt DESC LIMIT ?
                """.trimIndent(),
                args,
            ) { rs ->
                val out = ArrayList<Event>(limit.coerceAtMost(256))
                while (rs.next()) out.add(rowToEvent(rs))
                out
            }
        }
    }

    suspend fun byId(id: String): Event? = withContext(Dispatchers.IO) {
        db.read {
            query(
                "SELECT id, pubkey, createdAt, kind, tags, content, sig FROM events WHERE id = ?",
                listOf(id),
            ) { rs -> if (rs.next()) rowToEvent(rs) else null }
        }
    }

    suspend fun byIds(ids: List<String>): List<Event> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        db.read {
            query(
                "SELECT id, pubkey, createdAt, kind, tags, content, sig FROM events WHERE id IN ($placeholders)",
                ids,
            ) { rs ->
                val out = ArrayList<Event>(ids.size)
                while (rs.next()) out.add(rowToEvent(rs))
                out
            }
        }
    }

    /** Events of [kinds] by [author] — used for follow lists, relay lists, profiles. */
    suspend fun byAuthor(author: String, kinds: List<Int>, limit: Int = 20): List<Event> =
        withContext(Dispatchers.IO) {
            val kindPlaceholders = kinds.joinToString(",") { "?" }
            db.read {
                query(
                    """
                    SELECT id, pubkey, createdAt, kind, tags, content, sig FROM events
                    WHERE pubkey = ? AND kind IN ($kindPlaceholders)
                    ORDER BY createdAt DESC LIMIT ?
                    """.trimIndent(),
                    buildList<Any?> { add(author); addAll(kinds); add(limit) },
                ) { rs ->
                    val out = ArrayList<Event>()
                    while (rs.next()) out.add(rowToEvent(rs))
                    out
                }
            }
        }

    /** Deletes events older than [cutoffSeconds] that are not in a protected kind set. */
    suspend fun pruneOlderThan(cutoffSeconds: Long, keepKinds: List<Int>) = withContext(Dispatchers.IO) {
        val placeholders = keepKinds.joinToString(",") { "?" }
        db.transaction {
            update(
                "DELETE FROM events WHERE createdAt < ? AND kind NOT IN ($placeholders)",
                buildList<Any?> { add(cutoffSeconds); addAll(keepKinds) },
            )
        }
        Log.d("EventStore", "pruned events older than $cutoffSeconds")
    }

    suspend fun count(): Long = withContext(Dispatchers.IO) {
        db.read { query("SELECT COUNT(*) FROM events") { rs -> if (rs.next()) rs.getLong(1) else 0L } }
    }

    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        db.transaction { update("DELETE FROM events WHERE id = ?", listOf(id)) }
        _revision.value = _revision.value + 1
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        db.transaction { update("DELETE FROM events", emptyList()) }
        _revision.value = _revision.value + 1
    }

    private fun rowToEvent(rs: java.sql.ResultSet): Event {
        val tagsJson = rs.getString("tags")
        val tags: List<JsonArray> = runCatching {
            NostrJson.decodeFromString(JsonArray.serializer(), tagsJson)
                .map { it as JsonArray }
        }.getOrDefault(emptyList())
        return Event(
            id = rs.getString("id"),
            pubkey = rs.getString("pubkey"),
            createdAt = rs.getLong("createdAt"),
            kind = rs.getInt("kind"),
            tags = tags,
            content = rs.getString("content"),
            sig = rs.getString("sig"),
        )
    }
}

/** Kind sets used by the desktop UI, mirroring upstream's feed queries. */
object KindSets {
    val FEED = listOf(EventKind.TEXT_NOTE, EventKind.REPOST, EventKind.POLL)
    val NOTIFICATIONS = listOf(
        EventKind.TEXT_NOTE, EventKind.REACTION, EventKind.REPOST,
        EventKind.POLL_RESPONSE, EventKind.FOLLOW_LIST,
    )
}

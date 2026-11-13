package social.tbone.core.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import social.tbone.core.Log
import social.tbone.core.nostr.NostrJson
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent preferences.
 *
 * Replaces AndroidX `DataStore<Preferences>`. Upstream read settings through
 * Flow-based DataStore; here every key is a plain typed entry in the `prefs`
 * table, and a single [revision] StateFlow is bumped on any write so UI that
 * wants to recompose can observe it. Keys keep upstream's exact names so the
 * behaviour of each setting is unchanged.
 */
class SettingsStore(private val db: DesktopDatabase) {

    private val cache = ConcurrentHashMap<String, String>()
    private val _revision = MutableStateFlow(0L)

    /** Increments on every write; collectors recompose from their own reads. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    init {
        db.read {
            query("SELECT key, value FROM prefs") { rs ->
                while (rs.next()) cache[rs.getString(1)] = rs.getString(2)
            }
        }
        Log.i("SettingsStore", "loaded ${cache.size} preference(s)")
    }

    fun getString(key: String, default: String? = null): String? = cache[key] ?: default

    fun getStringSet(key: String, default: Set<String> = emptySet()): Set<String> {
        val raw = cache[key] ?: return default
        return runCatching {
            NostrJson.decodeFromString<Set<String>>(raw)
        }.getOrDefault(default)
    }

    fun getBoolean(key: String, default: Boolean): Boolean =
        cache[key]?.toBooleanStrictOrNull() ?: default

    fun getInt(key: String, default: Int): Int = cache[key]?.toIntOrNull() ?: default

    fun getLong(key: String, default: Long): Long = cache[key]?.toLongOrNull() ?: default

    fun putString(key: String, value: String?) = put(key, value)

    fun putStringSet(key: String, value: Set<String>) =
        put(key, NostrJson.encodeToString(value))

    fun putBoolean(key: String, value: Boolean) = put(key, value.toString())

    fun putInt(key: String, value: Int) = put(key, value.toString())

    fun putLong(key: String, value: Long) = put(key, value.toString())

    fun remove(key: String) {
        cache.remove(key)
        db.transaction { update("DELETE FROM prefs WHERE key = ?", listOf(key)) }
        bump()
    }

    /** True if the key has ever been written — upstream's "explicitly set" idiom. */
    fun has(key: String): Boolean = cache.containsKey(key)

    private fun put(key: String, value: String?) {
        if (value == null) { remove(key); return }
        cache[key] = value
        db.transaction {
            update("INSERT OR REPLACE INTO prefs(key, value) VALUES(?, ?)", listOf(key, value))
        }
        bump()
    }

    private fun bump() { _revision.value = _revision.value + 1 }

    /** Wipes every stored preference (used by the app-data wipe path). */
    fun clearAll() {
        cache.clear()
        db.transaction { update("DELETE FROM prefs", emptyList()) }
        bump()
    }

    /** Snapshot of all keys, for diagnostics/export. */
    fun snapshot(): Map<String, String> = cache.toMap()
}

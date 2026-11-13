package social.tbone.core.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import social.tbone.core.Log
import java.util.UUID

enum class NoteKind { NOTE, CHECKLIST }

/** A decrypted note or checklist for the UI (port of upstream NoteUi). */
data class NoteUi(
    val id: String,
    val kind: NoteKind,
    val text: String,
    val title: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean = false,
    val sortOrder: Double = 0.0,
    val folderId: String? = null,
) {
    fun displayTitle(): String = when {
        title.isNotBlank() -> title
        else -> text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80) ?: "(empty)"
    }
}

data class NoteFolderUi(
    val id: String,
    val name: String,
    val pinned: Boolean = false,
    val sortOrder: Double = 0.0,
    val createdAt: Long,
    val updatedAt: Long,
)

data class NotesStats(
    val noteCount: Int = 0,
    val checklistCount: Int = 0,
    val totalCiphertextBytes: Long = 0,
)

/**
 * Encrypted notes + checklists — desktop port of upstream's
 * `NotesRepository`/`NoteDao`/`NotesCrypto` trio.
 *
 * Only AES-256-GCM ciphertext and its IV ever reach the database, for both the
 * body and the title, exactly as upstream: the plaintext lives only in memory
 * while a row is encrypted/decrypted. Upstream used the Android Keystore for the
 * key; here the key is the app's 0600 local key file (see [LocalCrypto]).
 *
 * On open, a full encrypt→decrypt round trip runs ([selfTest]); the notes screen
 * only shows its "encrypted" badge when that check passes, matching upstream's
 * behaviour rather than assuming success.
 */
class NotesStore(private val db: DesktopDatabase, private val crypto: LocalCrypto) {

    private val _notes = MutableStateFlow<List<NoteUi>>(emptyList())
    val notes: StateFlow<List<NoteUi>> = _notes.asStateFlow()

    private val _folders = MutableStateFlow<List<NoteFolderUi>>(emptyList())
    val folders: StateFlow<List<NoteFolderUi>> = _folders.asStateFlow()

    private val _stats = MutableStateFlow(NotesStats())
    val stats: StateFlow<NotesStats> = _stats.asStateFlow()

    /** Result of the startup encrypt→decrypt round trip. */
    @Volatile var encryptionVerified: Boolean = false
        private set

    suspend fun load() = withContext(Dispatchers.IO) {
        encryptionVerified = crypto.selfTest()
        _notes.value = db.read {
            query(
                """
                SELECT id, ciphertext, iv, createdAt, updatedAt, kind, pinned, sortOrder, folderId,
                       titleCiphertext, titleIv
                FROM notes ORDER BY pinned DESC, sortOrder ASC, updatedAt DESC
                """.trimIndent()
            ) { rs ->
                val out = ArrayList<NoteUi>()
                while (rs.next()) {
                    val id = rs.getString("id")
                    val body = decryptOrNull(rs.getBytes("ciphertext"), rs.getBytes("iv"))
                    if (body == null) {
                        // Undecryptable row (different key / corruption): skipped, not shown
                        // as garbage, and the row itself stays on disk untouched.
                        Log.w("NotesStore", "could not decrypt note $id")
                        continue
                    }
                    out.add(
                        NoteUi(
                            id = id,
                            kind = if (rs.getString("kind") == "checklist") NoteKind.CHECKLIST else NoteKind.NOTE,
                            text = body,
                            title = decryptOrNull(rs.getBytes("titleCiphertext"), rs.getBytes("titleIv")).orEmpty(),
                            createdAt = rs.getLong("createdAt"),
                            updatedAt = rs.getLong("updatedAt"),
                            pinned = rs.getInt("pinned") == 1,
                            sortOrder = rs.getDouble("sortOrder"),
                            folderId = rs.getString("folderId"),
                        )
                    )
                }
                out
            }
        }
        _folders.value = db.read {
            query("SELECT * FROM note_folders ORDER BY pinned DESC, sortOrder ASC, name ASC") { rs ->
                val out = ArrayList<NoteFolderUi>()
                while (rs.next()) {
                    out.add(
                        NoteFolderUi(
                            id = rs.getString("id"),
                            name = rs.getString("name"),
                            pinned = rs.getInt("pinned") == 1,
                            sortOrder = rs.getDouble("sortOrder"),
                            createdAt = rs.getLong("createdAt"),
                            updatedAt = rs.getLong("updatedAt"),
                        )
                    )
                }
                out
            }
        }
        refreshStats()
    }

    private fun decryptOrNull(ciphertext: ByteArray?, iv: ByteArray?): String? {
        if (ciphertext == null || iv == null) return ""
        return runCatching { String(crypto.decryptSplit(ciphertext, iv)) }.getOrNull()
    }

    private fun refreshStats() {
        db.read {
            query(
                """
                SELECT COALESCE(SUM(CASE WHEN kind = 'note' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN kind = 'checklist' THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(LENGTH(ciphertext)), 0)
                FROM notes
                """.trimIndent()
            ) { rs ->
                if (rs.next()) {
                    _stats.value = NotesStats(rs.getInt(1), rs.getInt(2), rs.getLong(3))
                }
            }
        }
    }

    suspend fun save(note: NoteUi) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        val (bodyCipher, bodyIv) = crypto.encryptSplit(note.text.toByteArray(Charsets.UTF_8))
        val (titleCipher, titleIv) = crypto.encryptSplit(note.title.toByteArray(Charsets.UTF_8))
        db.transaction {
            update(
                """
                INSERT INTO notes(id, ciphertext, iv, createdAt, updatedAt, kind, pinned, sortOrder,
                                  folderId, titleCiphertext, titleIv)
                VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    ciphertext = excluded.ciphertext, iv = excluded.iv,
                    updatedAt = excluded.updatedAt, kind = excluded.kind,
                    pinned = excluded.pinned, sortOrder = excluded.sortOrder,
                    folderId = excluded.folderId,
                    titleCiphertext = excluded.titleCiphertext, titleIv = excluded.titleIv
                """.trimIndent(),
                listOf(
                    note.id, bodyCipher, bodyIv, note.createdAt, now,
                    if (note.kind == NoteKind.CHECKLIST) "checklist" else "note",
                    note.pinned, note.sortOrder, note.folderId, titleCipher, titleIv,
                ),
            )
        }
        load()
    }

    suspend fun create(kind: NoteKind, title: String = "", text: String = "", folderId: String? = null): NoteUi {
        val now = System.currentTimeMillis() / 1000
        val note = NoteUi(
            id = UUID.randomUUID().toString(),
            kind = kind,
            text = text,
            title = title,
            createdAt = now,
            updatedAt = now,
            sortOrder = (_notes.value.maxOfOrNull { it.sortOrder } ?: 0.0) + 1.0,
            folderId = folderId,
        )
        save(note)
        return note
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        db.transaction { update("DELETE FROM notes WHERE id = :id".replace(":id", "?"), listOf(id)) }
        load()
    }

    suspend fun setPinned(id: String, pinned: Boolean) = withContext(Dispatchers.IO) {
        db.transaction { update("UPDATE notes SET pinned = ? WHERE id = ?", listOf(pinned, id)) }
        load()
    }

    suspend fun setFolder(id: String, folderId: String?) = withContext(Dispatchers.IO) {
        db.transaction { update("UPDATE notes SET folderId = ? WHERE id = ?", listOf(folderId, id)) }
        load()
    }

    /** Swaps sort_order with the previous/next sibling, matching upstream's reorder. */
    suspend fun move(id: String, up: Boolean) = withContext(Dispatchers.IO) {
        val list = _notes.value
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return@withContext
        val otherIdx = if (up) idx - 1 else idx + 1
        if (otherIdx !in list.indices) return@withContext
        val a = list[idx]
        val b = list[otherIdx]
        db.transaction {
            update("UPDATE notes SET sortOrder = ? WHERE id = ?", listOf(b.sortOrder, a.id))
            update("UPDATE notes SET sortOrder = ? WHERE id = ?", listOf(a.sortOrder, b.id))
        }
        load()
    }

    // ── Folders ───────────────────────────────────────────────────────────────

    suspend fun createFolder(name: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis() / 1000
        db.transaction {
            update(
                "INSERT INTO note_folders(id, name, pinned, sortOrder, createdAt, updatedAt) VALUES(?,?,?,?,?,?)",
                listOf(
                    UUID.randomUUID().toString(), name, false,
                    (_folders.value.maxOfOrNull { it.sortOrder } ?: 0.0) + 1.0, now, now,
                ),
            )
        }
        load()
    }

    suspend fun renameFolder(id: String, name: String) = withContext(Dispatchers.IO) {
        db.transaction {
            update(
                "UPDATE note_folders SET name = ?, updatedAt = ? WHERE id = ?",
                listOf(name, System.currentTimeMillis() / 1000, id),
            )
        }
        load()
    }

    suspend fun deleteFolder(id: String) = withContext(Dispatchers.IO) {
        db.transaction {
            update("UPDATE notes SET folderId = NULL WHERE folderId = ?", listOf(id))
            update("DELETE FROM note_folders WHERE id = ?", listOf(id))
        }
        load()
    }
}

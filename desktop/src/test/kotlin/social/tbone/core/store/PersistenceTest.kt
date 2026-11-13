package social.tbone.core.store

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import social.tbone.core.nostr.Event
import social.tbone.core.nostr.UnsignedEvent
import social.tbone.core.nostr.toHex
import social.tbone.core.nostr.hexToBytes
import social.tbone.core.nostr.Crypto
import java.io.File

/**
 * Persistence: the SQLite schema, the encrypted note path, and the claim that
 * matters most on a desktop — that note text is not readable from the database
 * files. This is the automated counterpart of the manual canary check.
 */
class PersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var db: DesktopDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    /**
     * Opens [dir] as the process database.
     *
     * [DesktopDatabase] is a process-wide singleton (the desktop equivalent of the
     * single Room instance), so any instance left over from a previous test must be
     * closed first — otherwise the new directory would resolve to the old handle.
     */
    private fun open(dir: File = tmp.root): DesktopDatabase {
        runCatching { DesktopDatabase.current().close() }
        val opened = DesktopDatabase.open(dir)
        db = opened
        return opened
    }

    private fun crypto(dir: File = tmp.root): LocalCrypto = LocalCrypto.forDatabase(File(dir, "tbone.db"))

    @Test
    fun databaseIsCreatedAndMigrated() {
        val d = open()
        assertTrue(d.file.isFile)
        assertNotNull("schema version must be recorded", d.meta("schema_version"))
    }

    @Test
    fun noteIsEncryptedAtRestAndSurvivesReopen() = runBlocking {
        val dir = tmp.newFolder("notes")
        val store = NotesStore(open(dir), crypto(dir))

        val secret = "PLAINTEXT_CANARY_9f3a this must never appear on disk"
        val created = store.create(NoteKind.NOTE, title = "round trip", text = secret)

        // Visible in memory...
        assertTrue(store.notes.value.any { it.id == created.id })

        // ...and absent from every byte of the database + WAL on disk.
        db?.close()
        db = null
        val bytes = dir.listFiles().orEmpty()
            .filter { it.isFile }
            .joinToString("") { String(it.readBytes(), Charsets.ISO_8859_1) }
        assertFalse(
            "note plaintext must not be readable from the data directory",
            bytes.contains("PLAINTEXT_CANARY_9f3a"),
        )
        assertTrue("ciphertext bytes are recorded", store.stats.value.totalCiphertextBytes > 0)

        // Reopening with the same key restores the plaintext.
        val reopened = NotesStore(open(dir), crypto(dir))
        reopened.load()
        val restored = reopened.notes.value.first { it.id == created.id }
        assertTrue(restored.text.contains("PLAINTEXT_CANARY_9f3a"))
        assertEquals("round trip", restored.title)
    }

    @Test
    fun deletingANoteRemovesIt() = runBlocking {
        val dir = tmp.newFolder("del")
        val store = NotesStore(open(dir), crypto(dir))
        val note = store.create(NoteKind.NOTE, title = "gone", text = "x")

        store.delete(note.id)

        assertNull(store.notes.value.firstOrNull { it.id == note.id })
    }

    @Test
    fun eventsPersistWithTagsAndReload() = runBlocking {
        val dir = tmp.newFolder("events")
        val eventStore = EventStore(open(dir))
        val privkey = Crypto.generatePrivateKey()
        val unsigned = UnsignedEvent(
            pubkey = Crypto.deriveXOnlyPubkey(privkey).toHex(),
            kind = 1,
            content = "persisted event",
        )
        val id = unsigned.computeId()
        val event = Event(
            id = id,
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = 1,
            tags = emptyList(),
            content = "persisted event",
            sig = Crypto.schnorrSign(id.hexToBytes(), privkey).toHex(),
        )

        eventStore.save(event, event.pubkey)
        db?.close()
        db = null

        val reopened = EventStore(open(dir))
        val back = reopened.byId(id)
        assertNotNull("event must survive a restart", back)
        assertEquals("persisted event", back!!.content)
        assertTrue("stored events keep their signature to re-verify", back.sig.length == 128)
    }

    @Test
    fun duplicateEventIdsAreStoredOnce() = runBlocking {
        val dir = tmp.newFolder("dedupe")
        val eventStore = EventStore(open(dir))
        val privkey = Crypto.generatePrivateKey()
        val unsigned = UnsignedEvent(
            pubkey = Crypto.deriveXOnlyPubkey(privkey).toHex(), kind = 1, content = "once",
        )
        val id = unsigned.computeId()
        val event = Event(
            id = id,
            pubkey = unsigned.pubkey,
            createdAt = unsigned.createdAt,
            kind = 1,
            tags = emptyList(),
            content = "once",
            sig = Crypto.schnorrSign(id.hexToBytes(), privkey).toHex(),
        )

        eventStore.save(event, event.pubkey)
        eventStore.save(event, event.pubkey)

        assertEquals(1L, eventStore.count())
    }
}

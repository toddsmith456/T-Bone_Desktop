package social.tbone.core.store

import social.tbone.core.Log
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.withLock

/**
 * Local SQLite database for the desktop port.
 *
 * Upstream used Room; Room is an Android/AndroidX library with no supported
 * desktop artifact, so the port keeps the same tables and semantics on top of
 * plain JDBC + SQLite. Every statement used here is a direct translation of the
 * Room DAO query it replaces, including the ORDER BY clauses, so list ordering
 * behaviour is preserved.
 *
 * Threading: a single connection guarded by a lock. All callers are coroutines
 * on Dispatchers.IO, so this serialises writes the way Room's single writer did
 * without risking SQLITE_BUSY.
 */
class DesktopDatabase private constructor(val file: File) {

    private val lock = ReentrantLock()
    private val conn: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    private val crypto = LocalCrypto.forDatabase(file)

    init {
        conn.autoCommit = true
        conn.createStatement().use { st ->
            st.execute("PRAGMA journal_mode=WAL")
            st.execute("PRAGMA foreign_keys=ON")
        }
        migrate()
    }

    private fun migrate() {
        transaction {
            exec(
                """
                CREATE TABLE IF NOT EXISTS schema_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE IF NOT EXISTS events (
                    id TEXT PRIMARY KEY,
                    pubkey TEXT NOT NULL,
                    createdAt INTEGER NOT NULL,
                    kind INTEGER NOT NULL,
                    tags TEXT NOT NULL,
                    content TEXT NOT NULL,
                    sig TEXT NOT NULL,
                    accountPubkey TEXT NOT NULL
                )
                """.trimIndent()
            )
            exec("CREATE INDEX IF NOT EXISTS idx_events_account_kind ON events(accountPubkey, kind, createdAt DESC)")
            exec("CREATE INDEX IF NOT EXISTS idx_events_pubkey ON events(pubkey)")

            exec(
                """
                CREATE TABLE IF NOT EXISTS profiles (
                    pubkey TEXT PRIMARY KEY,
                    content TEXT NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Encrypted notes: only ciphertext + IV ever hit disk, exactly as upstream.
            exec(
                """
                CREATE TABLE IF NOT EXISTS notes (
                    id TEXT PRIMARY KEY,
                    ciphertext BLOB NOT NULL,
                    iv BLOB NOT NULL,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL,
                    kind TEXT NOT NULL DEFAULT 'note',
                    pinned INTEGER NOT NULL DEFAULT 0,
                    sortOrder REAL NOT NULL DEFAULT 0,
                    folderId TEXT,
                    titleCiphertext BLOB,
                    titleIv BLOB
                )
                """.trimIndent()
            )
            exec(
                """
                CREATE TABLE IF NOT EXISTS note_folders (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    pinned INTEGER NOT NULL DEFAULT 0,
                    sortOrder REAL NOT NULL DEFAULT 0,
                    createdAt INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL
                )
                """.trimIndent()
            )
            // Key/value store replacing AndroidX DataStore<Preferences>.
            exec(
                """
                CREATE TABLE IF NOT EXISTS prefs (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent()
            )
            setMeta("schema_version", "1")
        }
    }

    // ── Low-level helpers ─────────────────────────────────────────────────────

    fun <T> transaction(block: Tx.() -> T): T = lock.withLock {
        val tx = Tx(conn)
        try {
            tx.begin()
            val r = block(tx)
            tx.commit()
            r
        } catch (t: Throwable) {
            tx.rollback()
            throw t
        }
    }

    fun <T> read(block: Tx.() -> T): T = lock.withLock { Tx(conn).block() }

    private fun exec(sql: String) = conn.createStatement().use { it.execute(sql) }

    private fun setMeta(key: String, value: String) = lock.withLock {
        conn.prepareStatement("INSERT OR REPLACE INTO schema_meta(key, value) VALUES(?, ?)").use {
            it.setString(1, key); it.setString(2, value); it.executeUpdate()
        }
    }

    fun meta(key: String): String? = read {
        query("SELECT value FROM schema_meta WHERE key = ?", listOf(key)) { rs ->
            if (rs.next()) rs.getString(1) else null
        }
    }

    /** Encrypt a secret blob with the database's local key. */
    fun encrypt(bytes: ByteArray): ByteArray = crypto.encrypt(bytes)

    /** Decrypt a secret blob produced by [encrypt]. */
    fun decrypt(bytes: ByteArray): ByteArray = crypto.decrypt(bytes)

    /**
     * Closes the connection and releases the process-wide handle, so a later
     * [open] can rebuild it. Leaving a closed instance cached made every query
     * after a close fail with "database connection closed" — which is exactly what
     * the restart path (and the reopen tests) do.
     */
    fun close() = runCatching { conn.close() }.also {
        synchronized(DesktopDatabase::class.java) {
            if (instance === this) instance = null
        }
    }

    companion object {
        @Volatile private var instance: DesktopDatabase? = null

        /**
         * Opens (or returns) the single database instance under [dataDir].
         *
         * One instance per process, keyed to [dataDir]: calling this with a different
         * directory while an instance is live is a programming error, not something
         * to paper over by silently swapping the database underneath the app.
         */
        fun open(dataDir: File): DesktopDatabase = instance ?: synchronized(this) {
            instance ?: run {
                dataDir.mkdirs()
                DesktopDatabase(File(dataDir, "tbone.db")).also { instance = it }
            }
        }

        fun current(): DesktopDatabase = instance
            ?: error("DesktopDatabase.open(dataDir) must be called at startup")
    }
}

/** Thin statement wrapper so DAO code reads like the Room queries it replaces. */
class Tx internal constructor(internal val c: Connection) {

    internal fun begin() = c.createStatement().use { it.execute("BEGIN IMMEDIATE") }
    internal fun commit() = c.createStatement().use { it.execute("COMMIT") }
    internal fun rollback() = runCatching { c.createStatement().use { it.execute("ROLLBACK") } }

    fun exec(sql: String) = c.createStatement().use { it.execute(sql) }

    fun update(sql: String, args: List<Any?>): Int =
        c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }

    fun <T> query(sql: String, args: List<Any?> = emptyList(), mapper: (java.sql.ResultSet) -> T): T =
        c.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs -> mapper(rs) }
        }

    private fun bind(ps: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, v ->
            when (v) {
                null -> ps.setNull(i + 1, java.sql.Types.NULL)
                is String -> ps.setString(i + 1, v)
                is Int -> ps.setInt(i + 1, v)
                is Long -> ps.setLong(i + 1, v)
                is Double -> ps.setDouble(i + 1, v)
                is Boolean -> ps.setInt(i + 1, if (v) 1 else 0)
                is ByteArray -> ps.setBytes(i + 1, v)
                else -> ps.setObject(i + 1, v)
            }
        }
    }
}

/**
 * AES-256-GCM at rest, replacing the Android Keystore.
 *
 * A desktop JVM has no secure-element equivalent, so the honest comparison is:
 * Android protected note/key ciphertext with a key held in the Keystore; the
 * desktop holds the key in a 0600 file in the app's config directory, outside
 * the database. Both encrypt with AES-256-GCM with a random 12-byte IV per
 * record. The limitation (the key is a file, not hardware-backed) is documented
 * in docs/PARITY.md and surfaced in the app's settings screen rather than
 * silently claimed as equivalent.
 */
class LocalCrypto private constructor(private val key: SecretKey) {

    private val random = SecureRandom()

    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > IV_LEN) { "Encrypted blob too short" }
        val iv = blob.copyOfRange(0, IV_LEN)
        val body = blob.copyOfRange(IV_LEN, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(body)
    }

    /** Encrypt to an independent (ciphertext, iv) pair, matching the Room column layout. */
    fun encryptSplit(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(plaintext) to iv
    }

    fun decryptSplit(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Round-trip self test — the desktop equivalent of upstream's NotesCrypto.selfTest(). */
    fun selfTest(): Boolean = runCatching {
        val probe = "t-bone desktop crypto self test".toByteArray()
        decrypt(encrypt(probe)).contentEquals(probe)
    }.getOrDefault(false)

    companion object {
        private const val IV_LEN = 12
        private const val TAG_BITS = 128

        /**
         * Loads the key file next to [dbFile], creating it with 0600 permissions
         * on first run. One key per data directory.
         */
        fun forDatabase(dbFile: File): LocalCrypto {
            val keyFile = File(dbFile.parentFile, "storage.key")
            val raw: ByteArray = if (keyFile.isFile && keyFile.length() == 32L) {
                keyFile.readBytes()
            } else {
                val kg = KeyGenerator.getInstance("AES")
                kg.init(256)
                val generated = kg.generateKey().encoded
                keyFile.parentFile?.mkdirs()
                keyFile.writeBytes(generated)
                restrictPermissions(keyFile)
                Log.i("LocalCrypto", "Generated new at-rest encryption key: ${keyFile.absolutePath}")
                generated
            }
            return LocalCrypto(SecretKeySpec(raw, "AES"))
        }

        private fun restrictPermissions(f: File) {
            runCatching {
                Files.setPosixFilePermissions(f.toPath(), PosixFilePermissions.fromString("rw-------"))
            }.onFailure { Log.w("LocalCrypto", "could not set 0600 on ${f.name}: ${it.message}") }
        }
    }
}

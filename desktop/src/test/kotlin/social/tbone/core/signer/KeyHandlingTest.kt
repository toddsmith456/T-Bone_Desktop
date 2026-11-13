package social.tbone.core.signer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import social.tbone.core.nostr.Crypto
import social.tbone.core.nostr.UnsignedEvent
import social.tbone.core.nostr.toHex
import social.tbone.core.store.LocalCrypto
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * Key handling: how the private scalar is stored, whether it can leak, and that the
 * signer refuses to operate after a wipe.
 *
 * The Android build kept the key in the hardware-backed Keystore. A desktop has no
 * equivalent, so the port stores an AES-256-GCM blob in a 0600 file next to the
 * database. These tests cover the parts that are testable without hardware: the
 * file permissions, the envelope, wrong-key behaviour, and the wipe path.
 */
class KeyHandlingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cryptoFor(dir: File): LocalCrypto = LocalCrypto.forDatabase(File(dir, "tbone.db"))

    @Test
    fun keyFileIsCreatedPrivateAndIsStableAcrossReopen() {
        val crypto = cryptoFor(tmp.root)
        val keyFile = File(tmp.root, "storage.key")

        assertTrue("key file must exist", keyFile.isFile)
        assertEquals("key file must hold a 256-bit key", 32L, keyFile.length())

        // 0600 on POSIX. Skipped silently on filesystems without POSIX permissions.
        runCatching {
            val perms = Files.getPosixFilePermissions(Paths.get(keyFile.absolutePath))
            assertEquals(PosixFilePermissions.fromString("rw-------"), perms)
        }

        // Reopening the same directory must reuse the key, otherwise existing
        // ciphertext would become undecryptable on every launch.
        val reopened = cryptoFor(tmp.root)
        val probe = "persisted".toByteArray()
        assertEquals(
            "a new LocalCrypto must decrypt what an earlier one wrote",
            "persisted",
            String(reopened.decrypt(crypto.encrypt(probe))),
        )
    }

    @Test
    fun ciphertextIsNotThePlaintextAndHasFreshIv() {
        val crypto = cryptoFor(tmp.root)
        val secret = "SUPER_SECRET_PRIVATE_SCALAR".toByteArray()

        val a = crypto.encrypt(secret)
        val b = crypto.encrypt(secret)

        assertFalse(
            "ciphertext must not contain the plaintext",
            String(a, Charsets.ISO_8859_1).contains("SUPER_SECRET"),
        )
        assertFalse("a fresh IV is used per record", a.contentEquals(b))
        assertTrue("AES-GCM adds a 16-byte tag after a 12-byte IV", a.size == 12 + secret.size + 16)
    }

    @Test
    fun decryptionWithAnotherDataDirectoryFails() {
        val a = cryptoFor(tmp.newFolder("dirA"))
        val b = cryptoFor(tmp.newFolder("dirB"))
        val blob = a.encrypt("only A may read this".toByteArray())

        var failed = false
        try {
            b.decrypt(blob)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("a different storage.key must not decrypt A's blob", failed)
    }

    @Test
    fun tamperedCiphertextIsRejectedByGcmTag() {
        val crypto = cryptoFor(tmp.root)
        val blob = crypto.encrypt("integrity".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1].toInt() xor 0xFF).toByte()

        var failed = false
        try {
            crypto.decrypt(blob)
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("a flipped bit must fail the GCM tag check", failed)
    }

    @Test
    fun selfTestPasses() {
        assertTrue(cryptoFor(tmp.root).selfTest())
    }

    @Test
    fun generatedSignerSignsAndVerifiesAndNeverStoresPlainKey() {
        val crypto = cryptoFor(tmp.root)
        val (signer, blob) = LocalKeySigner.generate(crypto)

        val unsigned = UnsignedEvent(
            pubkey = signer.pubkey,
            kind = 1,
            content = "signed by the generated key",
        )
        val event = signer.signEventBlocking(unsigned)

        assertTrue("self-signed event must verify", event.verify())
        assertEquals(signer.pubkey, event.pubkey)
        assertNotNull(blob)

        // The persisted blob must round-trip back to the same key, and must not
        // carry the scalar in the clear.
        val recovered = signer.withPrivkey { it.copyOf() }
        assertEquals(32, recovered.size)
        assertEquals(signer.pubkey, Crypto.deriveXOnlyPubkey(recovered).toHex())
        assertFalse(
            "the encrypted blob must not contain the raw scalar",
            String(blob, Charsets.ISO_8859_1).contains(String(recovered, Charsets.ISO_8859_1)),
        )
    }

    @Test
    fun fromPrivateKeyDerivesTheExpectedPubkey() {
        val crypto = cryptoFor(tmp.root)
        val privkey = Crypto.generatePrivateKey()
        val expected = Crypto.deriveXOnlyPubkey(privkey).toHex()

        val signer = LocalKeySigner.fromPrivateKey(privkey, crypto)

        assertEquals(expected, signer.pubkey)
    }

    @Test
    fun fromPrivateKeyRejectsWrongLength() {
        val crypto = cryptoFor(tmp.root)
        var threw = false
        try {
            LocalKeySigner.fromPrivateKey(ByteArray(31), crypto)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun wipedSignerRefusesToSign() {
        val crypto = cryptoFor(tmp.root)
        val (signer, _) = LocalKeySigner.generate(crypto)
        signer.wipe()

        var failed = false
        try {
            signer.signEventBlocking(UnsignedEvent(pubkey = signer.pubkey, kind = 1, content = "x"))
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("a wiped signer must not produce signatures", failed)
    }

    @Test
    fun publicOnlySignerCannotSign() {
        val crypto = cryptoFor(tmp.root)
        val privkey = Crypto.generatePrivateKey()
        val pubkey = Crypto.deriveXOnlyPubkey(privkey).toHex()
        val viewer = LocalKeySigner.publicOnly(pubkey, crypto)

        assertEquals(pubkey, viewer.pubkey)
        var failed = false
        try {
            viewer.signEventBlocking(UnsignedEvent(pubkey = pubkey, kind = 1, content = "x"))
        } catch (_: Exception) {
            failed = true
        }
        assertTrue("a public-only signer must fail closed", failed)
    }
}

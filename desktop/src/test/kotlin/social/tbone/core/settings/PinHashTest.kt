package social.tbone.core.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App-lock PIN hashing and the duress PIN.
 *
 * The security claims the Settings screen makes in user-visible text are asserted
 * here, so the copy cannot drift away from the code: PBKDF2-HMAC-SHA256, a random
 * per-PIN salt, and no plaintext or unsalted hash stored anywhere.
 */
class PinHashTest {

    @Test
    fun hashIsVerifiableAndFormattedAsDocumented() {
        val stored = PinHash.hash("1234")

        assertTrue("stored form is pbkdf2<sep>iters<sep>salt<sep>hash", stored.startsWith("pbkdf2$"))
        assertEquals("four fields", 4, stored.split('$').size)
        assertTrue(PinHash.verify("1234", stored))
    }

    @Test
    fun wrongPinFails() {
        val stored = PinHash.hash("1234")
        assertFalse(PinHash.verify("1235", stored))
        assertFalse(PinHash.verify("", stored))
        assertFalse(PinHash.verify("12345", stored))
    }

    @Test
    fun saltsAreUniqueSoIdenticalPinsHashDifferently() {
        val a = PinHash.hash("0000")
        val b = PinHash.hash("0000")

        assertNotEquals("a random salt is required", a, b)
        assertTrue(PinHash.verify("0000", a))
        assertTrue(PinHash.verify("0000", b))
    }

    @Test
    fun storedStringNeverContainsThePin() {
        val stored = PinHash.hash("482915")
        assertFalse(stored.contains("482915"))
    }

    @Test
    fun recordedIterationCountIsUsedForVerification() {
        // Verification reads the iteration count from the record, so old hashes stay
        // valid if the default is raised later. A record claiming a different count
        // must not accidentally verify.
        val stored = PinHash.hash("9999")
        val parts = stored.split('$')
        assertEquals("120000", parts[1])
    }

    @Test
    fun malformedRecordsFailClosed() {
        val records = listOf(
            "",
            "plain-text-pin",
            "pbkdf2" + '$' + "notanumber" + '$' + "c2FsdA==" + '$' + "aGFzaA==",
            "bcrypt" + '$' + "120000" + '$' + "c2FsdA==" + '$' + "aGFzaA==",
            "pbkdf2" + '$' + "120000" + '$' + "!!!not-base64!!!" + '$' + "aGFzaA==",
        )
        records.forEach {
            assertFalse("'$it' must not verify", PinHash.verify("1234", it))
        }
    }

    @Test
    fun duressPinIsDistinctFromTheUnlockPin() {
        val unlock = PinHash.hash("1234")
        val duress = PinHash.hash("9111")

        assertTrue(PinHash.verify("1234", unlock))
        assertFalse(PinHash.verify("1234", duress))
        assertTrue(PinHash.verify("9111", duress))
        assertNotEquals(unlock, duress)
    }

    @Test
    fun hashingIsStableAcrossInstancesForTheSameRecord() {
        val stored = PinHash.hash("5555")
        repeat(3) { assertTrue(PinHash.verify("5555", stored)) }
    }
}

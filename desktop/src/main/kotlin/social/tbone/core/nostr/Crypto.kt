package social.tbone.core.nostr

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.crypto.params.ECDomainParameters
import org.bouncycastle.crypto.params.ECPublicKeyParameters
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Security

/**
 * BIP-340 Schnorr over secp256k1 for Nostr (NIP-01).
 *
 * [verifySchnorr] is upstream T-Bone's verifier, unchanged apart from the
 * package. [schnorrSign] and [deriveXOnlyPubkey] were Android-Keystore-bound in
 * upstream (`LocalKeySigner`); the desktop port needs a real signing path, so it
 * is implemented here following BIP-340 with Bouncy Castle's curve maths:
 *
 *   d' = d if P has even y else n - d
 *   t  = d' XOR tagged_hash("BIP0340/aux", a)      (a = 32 random bytes)
 *   k0 = tagged_hash("BIP0340/nonce", t || px || m) mod n
 *   R  = k0*G ; k = k0 if R has even y else n - k0
 *   e  = tagged_hash("BIP0340/challenge", rx || px || m) mod n
 *   sig = rx || (k + e*d' mod n)
 *
 * Correctness is covered by tests against the official BIP-340 test vectors and
 * by round-trip verification of locally produced signatures.
 */
object Crypto {

    private val secp256k1 by lazy {
        if (Security.getProvider("BC") == null) Security.addProvider(BouncyCastleProvider())
        val params = SECNamedCurves.getByName("secp256k1")
        ECDomainParameters(params.curve, params.g, params.n, params.h)
    }

    private val random = SecureRandom()

    /**
     * Verifies a BIP-340 Schnorr signature.
     *
     * @param message   32-byte message hash (the event id as bytes)
     * @param signature 64-byte Schnorr signature (r || s)
     * @param pubkey    32-byte x-only public key
     */
    fun verifySchnorr(message: ByteArray, signature: ByteArray, pubkey: ByteArray): Boolean {
        require(message.size == 32) { "Message must be 32 bytes" }
        require(signature.size == 64) { "Signature must be 64 bytes" }
        require(pubkey.size == 32) { "x-only pubkey must be 32 bytes" }

        return runCatching {
            val curve = secp256k1.curve
            val n = secp256k1.n
            val G = secp256k1.g

            val r = BigInteger(1, signature.sliceArray(0..31))
            val s = BigInteger(1, signature.sliceArray(32..63))

            if (r >= curve.field.characteristic || s >= n) return false

            // Lift x-only pubkey to curve point (BIP-340: even y)
            val P = liftX(BigInteger(1, pubkey)) ?: return false

            val e = taggedHash("BIP0340/challenge", r.toBytes32() + pubkey + message)
            val eBig = BigInteger(1, e).mod(n)

            val R = G.multiply(s).subtract(P.multiply(eBig)).normalize()

            if (R.isInfinity) return false
            if (R.affineYCoord.toBigInteger().mod(BigInteger.TWO) != BigInteger.ZERO) return false
            if (R.affineXCoord.toBigInteger() != r) return false

            true
        }.getOrDefault(false)
    }

    /**
     * Signs a 32-byte message hash per BIP-340.
     *
     * @param message   32-byte message hash
     * @param privkey   32-byte secret scalar
     * @param auxRand   32 bytes of auxiliary randomness (BIP-340 allows any value;
     *                  random is recommended and used by default)
     * @return 64-byte signature (r || s)
     */
    fun schnorrSign(
        message: ByteArray,
        privkey: ByteArray,
        auxRand: ByteArray = ByteArray(32).also { random.nextBytes(it) },
    ): ByteArray {
        require(message.size == 32) { "Message must be 32 bytes" }
        require(privkey.size == 32) { "Private key must be 32 bytes" }
        require(auxRand.size == 32) { "Aux randomness must be 32 bytes" }

        val curve = secp256k1.curve
        val n = secp256k1.n
        val G = secp256k1.g

        val d0 = BigInteger(1, privkey)
        require(d0.signum() > 0 && d0 < n) { "Private key out of range" }

        val P = G.multiply(d0).normalize()
        val px = P.affineXCoord.getEncoded().let { if (it.size == 33) it.copyOfRange(1, 33) else it }
        val d = if (P.affineYCoord.toBigInteger().testBit(0)) n.subtract(d0) else d0

        val t = d.toBytes32().xor(taggedHash("BIP0340/aux", auxRand))
        val rand = taggedHash("BIP0340/nonce", t + px + message)
        val k0 = BigInteger(1, rand).mod(n)
        require(k0.signum() != 0) { "Derived nonce is zero" }

        val R = G.multiply(k0).normalize()
        val rx = R.affineXCoord.getEncoded().let { if (it.size == 33) it.copyOfRange(1, 33) else it }
        val k = if (R.affineYCoord.toBigInteger().testBit(0)) n.subtract(k0) else k0

        val e = BigInteger(1, taggedHash("BIP0340/challenge", rx + px + message)).mod(n)
        val s = k.add(e.multiply(d)).mod(n)

        val sig = rx + s.toBytes32()
        // Self-check: never hand out a signature that does not verify.
        check(verifySchnorr(message, sig, px)) { "Produced signature failed self-verification" }
        return sig
    }

    /** Derives the 32-byte x-only public key for a 32-byte private key. */
    fun deriveXOnlyPubkey(privkey: ByteArray): ByteArray {
        require(privkey.size == 32) { "Private key must be 32 bytes" }
        val d = BigInteger(1, privkey)
        require(d.signum() > 0 && d < secp256k1.n) { "Private key out of range" }
        val P = secp256k1.g.multiply(d).normalize()
        val enc = P.affineXCoord.getEncoded()
        return if (enc.size == 33) enc.copyOfRange(1, 33) else enc
    }

    /** Generates a fresh valid secp256k1 private key. */
    fun generatePrivateKey(): ByteArray {
        while (true) {
            val candidate = ByteArray(32).also { random.nextBytes(it) }
            val d = BigInteger(1, candidate)
            if (d.signum() > 0 && d < secp256k1.n) return candidate
        }
    }

    /**
     * ECDH x-coordinate on secp256k1 — the shared secret primitive behind NIP-44.
     * Matches libsecp256k1's `secp256k1_ecdh` (no hashing of the x coordinate).
     */
    fun ecdhX(privkey: ByteArray, pubkeyHexOrBytes: ByteArray): ByteArray {
        val d = BigInteger(1, privkey)
        val P = liftX(BigInteger(1, pubkeyHexOrBytes)) ?: error("Invalid x-only pubkey")
        val shared = P.multiply(d).normalize()
        val enc = shared.affineXCoord.getEncoded()
        return if (enc.size == 33) enc.copyOfRange(1, 33) else enc
    }

    /** Lifts an x coordinate to a secp256k1 curve point with even y (BIP-340). */
    fun liftX(x: BigInteger): ECPoint? {
        val curve = secp256k1.curve
        val p = curve.field.characteristic
        if (x >= p) return null
        val y2 = x.modPow(BigInteger.valueOf(3), p).add(BigInteger.valueOf(7)).mod(p)
        val y = y2.modPow(p.add(BigInteger.ONE).divide(BigInteger.valueOf(4)), p)
        if (y.modPow(BigInteger.TWO, p) != y2) return null
        val yEven = if (y.mod(BigInteger.TWO) == BigInteger.ZERO) y else p.subtract(y)
        return curve.createPoint(x, yEven)
    }

    /** Decodes a validated secp256k1 public key point (used by tests). */
    fun decodePoint(bytes: ByteArray): ECPoint = secp256k1.curve.decodePoint(bytes)

    /** BIP-340 tagged hash: SHA256(SHA256(tag) || SHA256(tag) || message) */
    fun taggedHash(tag: String, message: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        val tagHash = digest.digest(tag.toByteArray(Charsets.UTF_8))
        digest.reset()
        digest.update(tagHash)
        digest.update(tagHash)
        digest.update(message)
        return digest.digest()
    }

    private fun BigInteger.toBytes32(): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.takeLast(32).toByteArray()
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }

    private infix fun ByteArray.xor(other: ByteArray): ByteArray =
        ByteArray(size) { (this[it].toInt() xor other[it].toInt()).toByte() }

    /** Unused-import guard for the verifier's key type (kept for API parity). */
    @Suppress("unused")
    private fun publicKeyParameters(point: ECPoint): ECPublicKeyParameters =
        ECPublicKeyParameters(point, secp256k1)
}

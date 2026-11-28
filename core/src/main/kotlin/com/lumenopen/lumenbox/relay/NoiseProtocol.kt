package com.lumenopen.lumenbox.relay

import com.lumenopen.lumenbox.crypto.ChaCha20Poly1305
import java.math.BigInteger
import java.net.URI
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class NoiseRelayException(val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

data class X25519Keypair(val privateKey: ByteArray, val publicKey: ByteArray) {
    companion object {
        private val secureRandom = SecureRandom()

        fun fromPrivateSeed(seed: ByteArray): X25519Keypair {
            if (seed.size != 32) {
                throw NoiseRelayException("invalid_key_length", "X25519 private key must be 32 bytes")
            }
            val pub = X25519Curve.scalarMultBase(seed)
            return X25519Keypair(seed.copyOf(), pub)
        }

        fun generate(): X25519Keypair {
            val seed = ByteArray(32)
            secureRandom.nextBytes(seed)
            return fromPrivateSeed(seed)
        }
    }
}

/**
 * Pure RFC 7748 X25519 Montgomery ladder + canonical & low-order point validator (`docs/110 §3.2`).
 * Identical across OpenJDK 17 and all Android API levels without JCA provider differences.
 */
object X25519Curve {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
    private val A24: BigInteger = BigInteger.valueOf(121665)
    private val BASE_POINT_LE: ByteArray = ByteArray(32).apply { this[0] = 9 }

    private val FIELD_PRIME_LE: ByteArray = hexToBytes(
        "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"
    )

    private val LOW_ORDER_POINTS_LE: List<ByteArray> = listOf(
        hexToBytes("0000000000000000000000000000000000000000000000000000000000000000"),
        hexToBytes("0100000000000000000000000000000000000000000000000000000000000000"),
        hexToBytes("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"),
        hexToBytes("5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157"),
        hexToBytes("ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
    )

    fun isCanonicalPublicKey(pub: ByteArray): Boolean {
        if (pub.size != 32) return false
        var borrow = 0
        for (i in 0 until 32) {
            val diff = (pub[i].toInt() and 0xff) - (FIELD_PRIME_LE[i].toInt() and 0xff) - borrow
            borrow = if (diff < 0) 1 else 0
        }
        if (borrow != 1) return false
        for (point in LOW_ORDER_POINTS_LE) {
            var acc = 0
            for (i in 0 until 32) {
                acc = acc or ((pub[i].toInt() xor point[i].toInt()) and 0xff)
            }
            if (acc == 0) return false
        }
        return true
    }

    fun clampScalar(raw: ByteArray): ByteArray {
        val out = raw.copyOf()
        out[0] = (out[0].toInt() and 248).toByte()
        out[31] = ((out[31].toInt() and 127) or 64).toByte()
        return out
    }

    fun scalarMultBase(privateKeySeed: ByteArray): ByteArray =
        rawLadder(clampScalar(privateKeySeed), BASE_POINT_LE)

    fun diffieHellman(privateKeySeed: ByteArray, peerPublicKey: ByteArray): ByteArray {
        if (privateKeySeed.size != 32) {
            throw NoiseRelayException("invalid_key_length", "Private key must be 32 bytes")
        }
        if (!isCanonicalPublicKey(peerPublicKey)) {
            throw NoiseRelayException("invalid_public_key", "Non-canonical or low-order X25519 public key")
        }
        val shared = rawLadder(clampScalar(privateKeySeed), peerPublicKey)
        var nonZero = 0
        for (b in shared) nonZero = nonZero or (b.toInt() and 0xff)
        if (nonZero == 0) {
            throw NoiseRelayException("low_order_shared_secret", "All-zero X25519 shared secret")
        }
        return shared
    }

    private fun decodeLittleEndian(le: ByteArray): BigInteger {
        val be = ByteArray(le.size + 1)
        for (i in le.indices) {
            be[be.size - 1 - i] = le[i]
        }
        return BigInteger(be)
    }

    private fun encodeLittleEndian32(v: BigInteger): ByteArray {
        val mod = v.mod(P)
        val be = mod.toByteArray()
        val out = ByteArray(32)
        var outIdx = 0
        for (i in be.indices.reversed()) {
            if (outIdx >= 32) break
            out[outIdx++] = be[i]
        }
        return out
    }

    private fun rawLadder(clampedScalarLE: ByteArray, uPointLE: ByteArray): ByteArray {
        val uClean = uPointLE.copyOf()
        uClean[31] = (uClean[31].toInt() and 0x7f).toByte()
        val k = decodeLittleEndian(clampedScalarLE)
        val x1 = decodeLittleEndian(uClean).mod(P)

        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)
            val sum = da.add(cb).mod(P)
            val diff = da.subtract(cb).mod(P)
            x3 = sum.multiply(sum).mod(P)
            z3 = x1.multiply(diff.multiply(diff).mod(P)).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P)
        }

        if (swap == 1) {
            x2 = x3
            z2 = z3
        }
        val zInv = z2.modPow(P.subtract(BigInteger.valueOf(2)), P) // BigInteger.TWO is absent below Android API 33
        return encodeLittleEndian32(x2.multiply(zInv).mod(P))
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}

class NoiseCipherState(keyBytes: ByteArray? = null) {
    private val key: SecretKeySpec? = keyBytes?.let {
        require(it.size == 32) { "ChaCha20-Poly1305 key must be 32 bytes" }
        SecretKeySpec(it.copyOf(), ChaCha20Poly1305.KEY_ALGORITHM)
    }
    private var nonceCounter: Long = 0L // unsigned 64-bit counter up to MAX_UNSIGNED - 1

    val hasKey: Boolean get() = key != null

    private fun nextNonceBytes(counter: Long): ByteArray {
        if (java.lang.Long.compareUnsigned(counter, -2L) > 0) {
            throw NoiseRelayException("nonce_exhausted", "Noise cipher nonce exhausted")
        }
        val buf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(0)
        buf.putLong(counter)
        return buf.array()
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val secret = key ?: return plaintext.copyOf()
        val iv = nextNonceBytes(nonceCounter)
        nonceCounter += 1L
        val cipher = ChaCha20Poly1305.newCipher()
        cipher.init(Cipher.ENCRYPT_MODE, secret, IvParameterSpec(iv))
        if (ad.isNotEmpty()) cipher.updateAAD(ad)
        return cipher.doFinal(plaintext)
    }

    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val secret = key ?: return ciphertext.copyOf()
        if (ciphertext.size < 16) {
            throw NoiseRelayException("decryption_failed", "Ciphertext shorter than Poly1305 tag")
        }
        val iv = nextNonceBytes(nonceCounter)
        return try {
            val cipher = ChaCha20Poly1305.newCipher()
            cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
            if (ad.isNotEmpty()) cipher.updateAAD(ad)
            val plain = cipher.doFinal(ciphertext)
            nonceCounter += 1L
            plain
        } catch (e: Exception) {
            throw NoiseRelayException("decryption_failed", "AEAD authentication failed", e)
        }
    }
}

data class NoiseTransportSession(val send: NoiseCipherState, val receive: NoiseCipherState)

internal class NoiseSymmetricState(protocolName: String) {
    private var ck: ByteArray
    private var h: ByteArray
    private var cipher = NoiseCipherState(null)

    init {
        val nameBytes = protocolName.toByteArray(StandardCharsets.UTF_8)
        h = if (nameBytes.size <= 32) {
            ByteArray(32).also { System.arraycopy(nameBytes, 0, it, 0, nameBytes.size) }
        } else {
            sha256(nameBytes)
        }
        ck = h.copyOf()
    }

    fun mixHash(data: ByteArray) {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(h)
        md.update(data)
        h = md.digest()
    }

    private fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val tempKey = hmacSha256(chainingKey, ikm)
        val out1 = hmacSha256(tempKey, byteArrayOf(0x01))
        val out2 = hmacSha256(tempKey, out1 + byteArrayOf(0x02))
        return out1 to out2
    }

    fun mixKey(ikm: ByteArray) {
        val (nextCk, tempK) = hkdf2(ck, ikm)
        ck = nextCk
        cipher = NoiseCipherState(tempK)
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ct = cipher.encryptWithAd(h, plaintext)
        mixHash(ct)
        return ct
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val plain = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return plain
    }

    fun splitInitiator(): NoiseTransportSession {
        val (k1, k2) = hkdf2(ck, ByteArray(0))
        return NoiseTransportSession(NoiseCipherState(k1), NoiseCipherState(k2))
    }
}

object NoiseHandshakes {
    const val NK_PROTOCOL_NAME = "Noise_NK_25519_ChaChaPoly_SHA256"
    const val IK_PROTOCOL_NAME = "Noise_IK_25519_ChaChaPoly_SHA256"
    val DEFAULT_PROLOGUE: ByteArray = "lumenbox-relay/1".toByteArray(StandardCharsets.UTF_8)

    class InitiatorHandshake internal constructor(
        private val symmetric: NoiseSymmetricState,
        private val ephemeral: X25519Keypair,
        private val staticKeypair: X25519Keypair?,
    ) {
        private var completed = false

        fun completeMessage2(message2: ByteArray): Pair<ByteArray, NoiseTransportSession> {
            if (completed) {
                throw NoiseRelayException("handshake_order", "Handshake message 2 already processed")
            }
            if (message2.size < 48) {
                throw NoiseRelayException("malformed_message", "Message 2 shorter than 48 bytes")
            }
            completed = true
            val responderEphPub = message2.copyOfRange(0, 32)
            symmetric.mixHash(responderEphPub)
            symmetric.mixKey(X25519Curve.diffieHellman(ephemeral.privateKey, responderEphPub))
            if (staticKeypair != null) {
                symmetric.mixKey(X25519Curve.diffieHellman(staticKeypair.privateKey, responderEphPub))
            }
            val payload = symmetric.decryptAndHash(message2.copyOfRange(32, message2.size))
            return payload to symmetric.splitInitiator()
        }
    }

    fun startNkInitiator(
        responderStaticPublicKey: ByteArray,
        prologue: ByteArray = DEFAULT_PROLOGUE,
        payload: ByteArray = ByteArray(0),
        ephemeralKeypair: X25519Keypair = X25519Keypair.generate(),
    ): Pair<ByteArray, InitiatorHandshake> {
        if (!X25519Curve.isCanonicalPublicKey(responderStaticPublicKey)) {
            throw NoiseRelayException("invalid_public_key", "Responder public key is non-canonical or low-order")
        }
        val sym = NoiseSymmetricState(NK_PROTOCOL_NAME)
        sym.mixHash(prologue)
        sym.mixHash(responderStaticPublicKey)
        sym.mixHash(ephemeralKeypair.publicKey)
        sym.mixKey(X25519Curve.diffieHellman(ephemeralKeypair.privateKey, responderStaticPublicKey))
        val encPayload = sym.encryptAndHash(payload)
        return (ephemeralKeypair.publicKey + encPayload) to InitiatorHandshake(sym, ephemeralKeypair, null)
    }

    fun startIkInitiator(
        initiatorStaticKeypair: X25519Keypair,
        responderStaticPublicKey: ByteArray,
        prologue: ByteArray = DEFAULT_PROLOGUE,
        payload: ByteArray = ByteArray(0),
        ephemeralKeypair: X25519Keypair = X25519Keypair.generate(),
    ): Pair<ByteArray, InitiatorHandshake> {
        if (!X25519Curve.isCanonicalPublicKey(responderStaticPublicKey)) {
            throw NoiseRelayException("invalid_public_key", "Responder public key is non-canonical or low-order")
        }
        val sym = NoiseSymmetricState(IK_PROTOCOL_NAME)
        sym.mixHash(prologue)
        sym.mixHash(responderStaticPublicKey)
        sym.mixHash(ephemeralKeypair.publicKey)
        sym.mixKey(X25519Curve.diffieHellman(ephemeralKeypair.privateKey, responderStaticPublicKey))
        val encStatic = sym.encryptAndHash(initiatorStaticKeypair.publicKey)
        sym.mixKey(X25519Curve.diffieHellman(initiatorStaticKeypair.privateKey, responderStaticPublicKey))
        val encPayload = sym.encryptAndHash(payload)
        val msg1 = ephemeralKeypair.publicKey + encStatic + encPayload
        return msg1 to InitiatorHandshake(sym, ephemeralKeypair, initiatorStaticKeypair)
    }
}

object RecordFramer {
    const val KIND_TEXT: Int = 0x00
    const val KIND_BINARY: Int = 0x01
    const val KIND_CONTROL: Int = 0x02

    const val FLAG_FIRST: Int = 0x01
    const val FLAG_FINAL: Int = 0x02
    const val CHUNK_HEADER_BYTES: Int = 10
    const val MAX_CHUNK_DATA_BYTES: Int = 65_535 - 16 - CHUNK_HEADER_BYTES // 65,509
    const val DEFAULT_MAX_RECORD_BYTES: Int = 16 * 1024 * 1024

    data class DecodedRecord(val kind: Int, val streamId: Long, val payload: ByteArray)

    fun sealRecord(
        cipher: NoiseCipherState,
        kind: Int,
        streamId: Long,
        payload: ByteArray,
        maxRecordBytes: Int = DEFAULT_MAX_RECORD_BYTES,
    ): ByteArray {
        if (!cipher.hasKey) throw NoiseRelayException("unkeyed_cipher", "Unkeyed cipher state")
        if (kind !in KIND_TEXT..KIND_CONTROL) throw NoiseRelayException("invalid_kind", "Invalid kind: $kind")
        if (streamId < 0L || streamId > 0xffff_ffffL) throw NoiseRelayException("invalid_stream_id", "Invalid streamId")
        val totalLen = payload.size
        if (totalLen > maxRecordBytes) throw NoiseRelayException("record_too_large", "Payload exceeds limit")

        val chunkCount = maxOf(1, (totalLen + MAX_CHUNK_DATA_BYTES - 1) / MAX_CHUNK_DATA_BYTES)
        val parts = ArrayList<ByteArray>(chunkCount * 2)
        var wireBytes = 0

        for (i in 0 until chunkCount) {
            val start = i * MAX_CHUNK_DATA_BYTES
            val end = minOf(start + MAX_CHUNK_DATA_BYTES, totalLen)
            val sliceLen = end - start
            var flags = 0
            if (i == 0) flags = flags or FLAG_FIRST
            if (i == chunkCount - 1) flags = flags or FLAG_FINAL

            val plain = ByteBuffer.allocate(CHUNK_HEADER_BYTES + sliceLen).order(ByteOrder.BIG_ENDIAN)
            plain.put(kind.toByte())
            plain.put(flags.toByte())
            plain.putInt(totalLen)
            plain.putInt(streamId.toInt())
            plain.put(payload, start, sliceLen)

            val ct = cipher.encryptWithAd(ByteArray(0), plain.array())
            val prefix = byteArrayOf(((ct.size ushr 8) and 0xff).toByte(), (ct.size and 0xff).toByte())
            parts.add(prefix)
            parts.add(ct)
            wireBytes += 2 + ct.size
        }

        val out = ByteArray(wireBytes)
        var pos = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, pos, p.size)
            pos += p.size
        }
        return out
    }

    fun openRecord(
        cipher: NoiseCipherState,
        wire: ByteArray,
        maxRecordBytes: Int = DEFAULT_MAX_RECORD_BYTES,
    ): DecodedRecord {
        if (!cipher.hasKey) throw NoiseRelayException("unkeyed_cipher", "Unkeyed cipher state")
        if (wire.isEmpty()) throw NoiseRelayException("truncated_record", "Empty wire record")

        var offset = 0
        var chunkIndex = 0
        var expectedKind = 0
        var expectedStreamId = 0L
        var expectedTotalLen = 0L
        var sawFinal = false
        var out = ByteArray(0)
        var written = 0

        while (offset < wire.size) {
            if (sawFinal || offset + 2 > wire.size) {
                throw NoiseRelayException("truncated_record", "Malformed chunk boundary")
            }
            val ctLen = ((wire[offset].toInt() and 0xff) shl 8) or (wire[offset + 1].toInt() and 0xff)
            offset += 2
            if (ctLen < CHUNK_HEADER_BYTES + 16 || offset + ctLen > wire.size) {
                throw NoiseRelayException("truncated_record", "Invalid chunk ciphertext length")
            }
            val ct = wire.copyOfRange(offset, offset + ctLen)
            offset += ctLen

            val plain = cipher.decryptWithAd(ByteArray(0), ct)
            if (plain.size < CHUNK_HEADER_BYTES) {
                throw NoiseRelayException("truncated_record", "Short chunk header")
            }
            val header = ByteBuffer.wrap(plain, 0, CHUNK_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            val kind = header.get().toInt() and 0xff
            val flags = header.get().toInt() and 0xff
            if (kind !in KIND_TEXT..KIND_CONTROL) throw NoiseRelayException("invalid_kind", "Invalid kind: $kind")
            if ((flags and (FLAG_FIRST or FLAG_FINAL).inv()) != 0) {
                throw NoiseRelayException("invalid_flags", "Unknown chunk flags")
            }
            val totalLen = header.int.toLong() and 0xffff_ffffL
            val streamId = header.int.toLong() and 0xffff_ffffL
            val dataLen = plain.size - CHUNK_HEADER_BYTES

            if (chunkIndex == 0) {
                if ((flags and FLAG_FIRST) == 0) throw NoiseRelayException("invalid_flags", "Missing FIRST flag")
                if (totalLen > maxRecordBytes.toLong()) {
                    throw NoiseRelayException("record_too_large", "Total length exceeds maximum")
                }
                val maxPossible = dataLen.toLong() + (wire.size - offset).toLong()
                if (totalLen > maxPossible) {
                    throw NoiseRelayException("truncated_record", "Wire shorter than declared totalLength")
                }
                expectedKind = kind
                expectedStreamId = streamId
                expectedTotalLen = totalLen
                out = ByteArray(totalLen.toInt())
            } else {
                if ((flags and FLAG_FIRST) != 0 ||
                    kind != expectedKind ||
                    streamId != expectedStreamId ||
                    totalLen != expectedTotalLen
                ) {
                    throw NoiseRelayException("corrupt_record", "Chunk metadata mismatch")
                }
            }

            if (written + dataLen > out.size) {
                throw NoiseRelayException("corrupt_record", "Chunk data exceeds declared totalLength")
            }
            System.arraycopy(plain, CHUNK_HEADER_BYTES, out, written, dataLen)
            written += dataLen
            if ((flags and FLAG_FINAL) != 0) sawFinal = true
            chunkIndex += 1
        }

        if (!sawFinal || written.toLong() != expectedTotalLen) {
            throw NoiseRelayException("truncated_record", "Incomplete multi-chunk record")
        }
        return DecodedRecord(expectedKind, expectedStreamId, out)
    }
}

data class ParsedPairingOffer(
    val hostPublicKey: ByteArray,
    val relayUrl: String,
    val routeId: String,
    val pairSecret: ByteArray,
    val directUrls: List<String>,
    val expiresAtEpochMillis: Long? = null,
) {
    companion object {
        fun parse(uriString: String, nowEpochMillis: Long = System.currentTimeMillis()): ParsedPairingOffer {
            val uri = try {
                URI(uriString.trim())
            } catch (e: Exception) {
                throw NoiseRelayException("invalid_pairing_uri", "Invalid URI", e)
            }
            if (uri.scheme != "lumenbox" || uri.host != "pair") {
                throw NoiseRelayException("invalid_pairing_uri", "Expected lumenbox://pair#...")
            }
            if (!uri.rawQuery.isNullOrEmpty()) {
                throw NoiseRelayException(
                    "invalid_pairing_uri",
                    "Pairing parameters must stay in #fragment, never query string"
                )
            }
            val fragment = uri.rawFragment
            if (fragment.isNullOrEmpty()) {
                throw NoiseRelayException("invalid_pairing_uri", "Missing #fragment")
            }
            val params = mutableMapOf<String, MutableList<String>>()
            for (pair in fragment.split("&")) {
                val idx = pair.indexOf('=')
                if (idx <= 0) continue
                val k = URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8)
                val v = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                params.getOrPut(k) { mutableListOf() }.add(v)
            }
            val hostPubB64 = params["hostPub"]?.firstOrNull()
            val relay = params["relay"]?.firstOrNull()
            val route = params["route"]?.firstOrNull()
            val secretB64 = params["pairSecret"]?.firstOrNull()
            if (hostPubB64.isNullOrEmpty() || relay.isNullOrEmpty() || route.isNullOrEmpty() || secretB64.isNullOrEmpty()) {
                throw NoiseRelayException("invalid_pairing_uri", "Missing required fragment parameters")
            }
            val expRaw = params["exp"]?.firstOrNull() ?: params["expiresAt"]?.firstOrNull()
            val expiresAtMillis = if (!expRaw.isNullOrEmpty()) {
                val parsedMillis = expRaw.toLongOrNull()?.let { numeric ->
                    // Values < 10^11 are Unix seconds; >= 10^11 are Unix milliseconds.
                    if (numeric < 100_000_000_000L) numeric * 1_000L else numeric
                } ?: runCatching { java.time.Instant.parse(expRaw).toEpochMilli() }.getOrElse {
                    throw NoiseRelayException("invalid_pairing_uri", "Invalid expiration timestamp in #fragment")
                }
                if (nowEpochMillis > parsedMillis) {
                    throw NoiseRelayException("expired_pairing_offer", "Pairing QR code has expired")
                }
                parsedMillis
            } else {
                null
            }
            val decoder = try {
                Base64.getUrlDecoder()
            } catch (e: Exception) {
                throw NoiseRelayException("invalid_pairing_uri", "Invalid base64url decoder", e)
            }
            val hostPub = try {
                decoder.decode(hostPubB64)
            } catch (e: IllegalArgumentException) {
                throw NoiseRelayException("invalid_pairing_uri", "Malformed base64url hostPub", e)
            }
            val secret = try {
                decoder.decode(secretB64)
            } catch (e: IllegalArgumentException) {
                throw NoiseRelayException("invalid_pairing_uri", "Malformed base64url pairSecret", e)
            }
            if (secret.size != 32 || !X25519Curve.isCanonicalPublicKey(hostPub)) {
                throw NoiseRelayException("invalid_pairing_uri", "Invalid hostPub or pairSecret")
            }
            return ParsedPairingOffer(
                hostPublicKey = hostPub,
                relayUrl = relay,
                routeId = route,
                pairSecret = secret,
                directUrls = params["direct"] ?: emptyList(),
                expiresAtEpochMillis = expiresAtMillis,
            )
        }
    }
}

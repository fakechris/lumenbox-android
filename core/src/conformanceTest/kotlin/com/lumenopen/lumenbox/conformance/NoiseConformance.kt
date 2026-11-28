package com.lumenopen.lumenbox.conformance

import com.lumenopen.lumenbox.relay.NoiseCipherState
import com.lumenopen.lumenbox.relay.NoiseHandshakes
import com.lumenopen.lumenbox.relay.NoiseRelayException
import com.lumenopen.lumenbox.relay.RecordFramer
import com.lumenopen.lumenbox.relay.X25519Curve
import com.lumenopen.lumenbox.relay.X25519Keypair
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Conformance checks for our X25519 and Noise initiator, framework-free so the same code runs as a
 * JVM unit test (`core/src/test`, OpenJDK) and as an instrumented test on an emulator
 * (`app/src/androidTest`, Android's runtime and providers). Each check throws [AssertionError] on a
 * mismatch and returns how many vectors it compared.
 *
 * Vector sources:
 * - RFC 7748 §5.2 (X25519 scalar multiplication and the iterated vector) and §6.1 (Diffie-Hellman).
 * - cacophony's `vectors/cacophony.txt` (public domain), the entries for the two protocols we speak,
 *   pinned in `noise-cacophony-vectors.json` with the source commit.
 * - The relay server's own vectors (`relay-test-vectors.json`), the interop check between the two sides.
 */
object NoiseConformance {
    const val NK = "Noise_NK_25519_ChaChaPoly_SHA256"
    const val IK = "Noise_IK_25519_ChaChaPoly_SHA256"

    /** RFC 7748 §5.2 and §6.1. [iterations] is 1 and 1000 (1,000,000 is skipped: minutes, no new coverage). */
    fun rfc7748(iterations: List<Int> = listOf(1, 1000)): Int {
        var compared = 0
        // §5.2 scalar multiplication vectors (the second has the high bit of u set, which must be masked).
        val scalarVectors = listOf(
            Triple(
                "a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4",
                "e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c",
                "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            ),
            Triple(
                "4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d",
                "e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493",
                "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            ),
        )
        for ((k, u, out) in scalarVectors) {
            assertHex(out, X25519Curve.scalarMult(hex(k), hex(u)), "RFC 7748 §5.2 X25519($k, $u)")
            compared++
        }

        // §5.2 iterated: k = u = 9; then u <- old k, k <- X25519(k, u).
        val iterated = mapOf(
            1 to "422c8e7a6227d7bca1350b3e2bb7279f7897b87bb6854b783c60e80311ae3079",
            1000 to "684cf59ba83309552800ef566f2f4d3c1c3887c49360e3875f2eb94d99532c51",
        )
        var k = ByteArray(32).also { it[0] = 9 }
        var u = k.copyOf()
        var done = 0
        for (target in iterations.sorted()) {
            while (done < target) {
                val next = X25519Curve.scalarMult(k, u)
                u = k
                k = next
                done++
            }
            assertHex(iterated.getValue(target), k, "RFC 7748 §5.2 iterated x$target")
            compared++
        }

        // §6.1 Diffie-Hellman through the Noise-facing API (keypair derivation + checked DH).
        val alice = X25519Keypair.fromPrivateSeed(hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"))
        val bob = X25519Keypair.fromPrivateSeed(hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"))
        assertHex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a", alice.publicKey, "RFC 7748 §6.1 Alice public")
        assertHex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f", bob.publicKey, "RFC 7748 §6.1 Bob public")
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertHex(shared, X25519Curve.diffieHellman(alice.privateKey, bob.publicKey), "RFC 7748 §6.1 K (Alice)")
        assertHex(shared, X25519Curve.diffieHellman(bob.privateKey, alice.publicKey), "RFC 7748 §6.1 K (Bob)")
        return compared + 4
    }

    /**
     * Every cacophony vector for [NK] and [IK], as the initiator: message 0 is ours byte for byte,
     * message 1 decrypts to its payload, the handshake hash matches, then transport messages
     * alternate (even: we encrypt and compare; odd: we decrypt and compare the payload).
     * Returns vectors compared per protocol.
     */
    fun cacophony(json: String): Map<String, Int> {
        val root = Json.parseToJsonElement(json).jsonObject
        val counts = linkedMapOf(NK to 0, IK to 0)
        for (element in root.getValue("vectors").jsonArray) {
            val v = element.jsonObject
            val name = v.str("protocol_name")
            if (name !in counts) continue
            if ("init_psks" in v || "hybrid" in v || "fail" in v) {
                throw AssertionError("$name: unexpected vector variant, keys ${v.keys}")
            }
            if (v.str("init_prologue") != v.str("resp_prologue")) throw AssertionError("$name: prologues differ")
            val messages = v.getValue("messages").jsonArray.map { it.jsonObject }
            val prologue = hex(v.str("init_prologue"))
            val responderStatic = hex(v.str("init_remote_static"))
            val responderStaticFromPrivate = X25519Keypair.fromPrivateSeed(hex(v.str("resp_static"))).publicKey
            assertHex(v.str("init_remote_static"), responderStaticFromPrivate, "$name: resp_static public key")
            val ephemeral = X25519Keypair.fromPrivateSeed(hex(v.str("init_ephemeral")))
            val m0 = messages[0]
            val (msg1, state) = when (name) {
                NK -> NoiseHandshakes.startNkInitiator(responderStatic, prologue, hex(m0.str("payload")), ephemeral)
                else -> NoiseHandshakes.startIkInitiator(
                    X25519Keypair.fromPrivateSeed(hex(v.str("init_static"))),
                    responderStatic,
                    prologue,
                    hex(m0.str("payload")),
                    ephemeral,
                )
            }
            assertHex(m0.str("ciphertext"), msg1, "$name message 0")
            val (reply, session) = state.completeMessage2(hex(messages[1].str("ciphertext")))
            assertHex(messages[1].str("payload"), reply, "$name message 1 payload")
            assertHex(v.str("handshake_hash"), state.handshakeHash, "$name handshake hash")
            for (i in 2 until messages.size) {
                val m = messages[i]
                if (i % 2 == 0) {
                    val ct = session.send.encryptWithAd(ByteArray(0), hex(m.str("payload")))
                    assertHex(m.str("ciphertext"), ct, "$name message $i (initiator -> responder)")
                } else {
                    val pt = session.receive.decryptWithAd(ByteArray(0), hex(m.str("ciphertext")))
                    assertHex(m.str("payload"), pt, "$name message $i (responder -> initiator)")
                }
            }
            counts[name] = counts.getValue(name) + 1
        }
        for ((name, n) in counts) if (n == 0) throw AssertionError("no cacophony vector for $name")
        return counts
    }

    /**
     * The refusals the relay server's `noise.test.ts` checks, on the initiator side, plus a
     * failed decryption leaving the receive nonce where it was. Uses the server's vectors
     * ([serverJson]) for real handshake messages. Returns the number of refusals checked.
     */
    fun refusals(serverJson: String): Int {
        val v = Json.parseToJsonElement(serverJson).jsonObject
        val prologue = v.str("prologueUtf8").toByteArray(StandardCharsets.UTF_8)
        val hostPub = hex(v.str("hostPubHex"))
        val client = X25519Keypair.fromPrivateSeed(hex(v.str("clientPrivHex")))
        var checked = 0

        // Low-order and non-canonical keys: refused as a peer key, by DH, and as a responder static.
        val refusedKeys = listOf(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "0100000000000000000000000000000000000000000000000000000000000000",
            "e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800",
            "5f9c95bca3508c24b1d0b1559c83ef5b04445cc4581c8e86d8224eddd09f1157",
            "ecffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p - 1
            "edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p
            "eeffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f", // p + 1 (non-canonical 1)
            "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
        )
        for (keyHex in refusedKeys) {
            val key = hex(keyHex)
            if (X25519Curve.isCanonicalPublicKey(key)) throw AssertionError("accepted $keyHex as canonical")
            expectCode("invalid_public_key", "DH with $keyHex") { X25519Curve.diffieHellman(client.privateKey, key) }
            expectCode("invalid_public_key", "NK to $keyHex") { NoiseHandshakes.startNkInitiator(key, prologue) }
            expectCode("invalid_public_key", "IK to $keyHex") { NoiseHandshakes.startIkInitiator(client, key, prologue) }
            checked += 4
        }
        if (!X25519Curve.isCanonicalPublicKey(hostPub)) throw AssertionError("refused a valid public key")
        expectCode("invalid_key_length", "31-byte private key") { X25519Curve.diffieHellman(ByteArray(31), hostPub) }
        checked++

        // A wrong responder static key: the responder's real message 2 fails to authenticate.
        val wrongHost = X25519Keypair.fromPrivateSeed(ByteArray(32) { 0x33 }).publicKey
        val (_, nkWrong) = NoiseHandshakes.startNkInitiator(
            wrongHost, prologue, "pair-hello".toByteArray(StandardCharsets.UTF_8),
            X25519Keypair.fromPrivateSeed(hex(v.str("nkClientEphPrivHex"))),
        )
        expectCode("decryption_failed", "NK message 2 with a wrong responder key") { nkWrong.completeMessage2(hex(v.str("nkMsg2Hex"))) }
        val (_, ikWrong) = NoiseHandshakes.startIkInitiator(
            client, wrongHost, prologue, "hello-host".toByteArray(StandardCharsets.UTF_8),
            X25519Keypair.fromPrivateSeed(hex(v.str("ikClientEphPrivHex"))),
        )
        expectCode("decryption_failed", "IK message 2 with a wrong responder key") { ikWrong.completeMessage2(hex(v.str("ikMsg2Hex"))) }
        checked += 2

        // A tampered message 2 (one bit of the tag), a wrong prologue, a message 2 processed twice,
        // and a responder ephemeral that is a low-order point.
        val tamperedMsg2 = hex(v.str("ikMsg2Hex")).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        expectCode("decryption_failed", "tampered IK message 2") { ikStart(v, client, prologue).completeMessage2(tamperedMsg2) }
        expectCode("decryption_failed", "IK with a different prologue") {
            ikStart(v, client, "lumenbox-relay/2".toByteArray(StandardCharsets.UTF_8)).completeMessage2(hex(v.str("ikMsg2Hex")))
        }
        val once = ikStart(v, client, prologue).also { it.completeMessage2(hex(v.str("ikMsg2Hex"))) }
        expectCode("handshake_order", "message 2 twice") { once.completeMessage2(hex(v.str("ikMsg2Hex"))) }
        val lowOrderEph = hex(v.str("ikMsg2Hex")).also { System.arraycopy(ByteArray(32), 0, it, 0, 32) }
        expectCode("invalid_public_key", "low-order responder ephemeral") { ikStart(v, client, prologue).completeMessage2(lowOrderEph) }
        expectCode("malformed_message", "short message 2") { ikStart(v, client, prologue).completeMessage2(ByteArray(47)) }
        checked += 5

        // Transport records: tampered, replayed and reordered records are refused; a refusal does
        // not advance the receive nonce, so the genuine next record still opens.
        val key = ByteArray(32) { 0x42 }
        val sender = NoiseCipherState(key)
        val receiver = NoiseCipherState(key)
        val first = RecordFramer.sealRecord(sender, RecordFramer.KIND_TEXT, 1L, "A".toByteArray(StandardCharsets.UTF_8))
        val second = RecordFramer.sealRecord(sender, RecordFramer.KIND_TEXT, 1L, "B".toByteArray(StandardCharsets.UTF_8))
        val tampered = first.copyOf().also { it[5] = (it[5].toInt() xor 0x01).toByte() }
        expectCode("decryption_failed", "tampered record") { RecordFramer.openRecord(receiver, tampered) }
        expectCode("decryption_failed", "reordered record") { RecordFramer.openRecord(receiver, second) }
        assertText("A", RecordFramer.openRecord(receiver, first).payload, "first record after refusals")
        expectCode("decryption_failed", "replayed record") { RecordFramer.openRecord(receiver, first) }
        assertText("B", RecordFramer.openRecord(receiver, second).payload, "second record after replay refusal")
        checked += 3

        // Forged chunk headers: the declared length is refused before anything that size is allocated
        // (the size check precedes the allocation in openRecord; a 4 GiB claim would not even fit).
        expectCode("record_too_large", "4 GiB declared length") {
            RecordFramer.openRecord(receiver, forgedChunk(sender, totalLength = -1, flags = RecordFramer.FLAG_FIRST))
        }
        expectCode("truncated_record", "16 MiB declared in a 14-byte chunk") {
            RecordFramer.openRecord(receiver, forgedChunk(sender, totalLength = RecordFramer.DEFAULT_MAX_RECORD_BYTES, flags = RecordFramer.FLAG_FIRST))
        }
        expectCode("invalid_flags", "reserved chunk flag") {
            RecordFramer.openRecord(receiver, forgedChunk(sender, totalLength = 4, flags = RecordFramer.FLAG_FIRST or RecordFramer.FLAG_FINAL or 0x80))
        }
        checked += 3
        return checked
    }

    private fun ikStart(v: JsonObject, client: X25519Keypair, prologue: ByteArray) =
        NoiseHandshakes.startIkInitiator(
            client, hex(v.str("hostPubHex")), prologue, "hello-host".toByteArray(StandardCharsets.UTF_8),
            X25519Keypair.fromPrivateSeed(hex(v.str("ikClientEphPrivHex"))),
        ).second

    /** A 14-byte chunk (10-byte header + "tiny") sealed under [sender]'s key, with a forged header. */
    private fun forgedChunk(sender: NoiseCipherState, totalLength: Int, flags: Int): ByteArray {
        val plain = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN).apply {
            put(RecordFramer.KIND_BINARY.toByte())
            put(flags.toByte())
            putInt(totalLength)
            putInt(9)
            put("tiny".toByteArray(StandardCharsets.UTF_8))
        }.array()
        val ct = sender.encryptWithAd(ByteArray(0), plain)
        return ByteBuffer.allocate(2 + ct.size).order(ByteOrder.BIG_ENDIAN).putShort(ct.size.toShort()).put(ct).array()
    }

    private fun expectCode(code: String, what: String, block: () -> Any?) {
        try {
            block()
        } catch (e: NoiseRelayException) {
            if (e.code != code) throw AssertionError("$what: expected '$code', got '${e.code}' (${e.message})", e)
            return
        }
        throw AssertionError("$what: expected NoiseRelayException '$code', nothing was thrown")
    }

    private fun assertHex(expected: String, actual: ByteArray, what: String) {
        val got = toHex(actual)
        if (got != expected) throw AssertionError("$what:\n expected $expected\n actual   $got")
    }

    private fun assertText(expected: String, actual: ByteArray, what: String) {
        val got = actual.toString(StandardCharsets.UTF_8)
        if (got != expected) throw AssertionError("$what: expected '$expected', got '$got'")
    }

    private fun JsonObject.str(key: String): String =
        (this[key] ?: throw AssertionError("vector without '$key'")).jsonPrimitive.content

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

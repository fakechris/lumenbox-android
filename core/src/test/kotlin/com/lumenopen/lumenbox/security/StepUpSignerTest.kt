package com.lumenopen.lumenbox.security

import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.StepUpProof
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class StepUpSignerTest {
    private fun challenge(
        approvalId: String = "APR-123",
        decision: String = "approve",
        timestampEpochSeconds: Long = 1_742_000_000,
        nonceSeed: Int = 7,
    ): StepUpChallenge {
        val nonce = ByteArray(16) { i -> ((nonceSeed * 31 + i) and 0xff).toByte() }
        return StepUpChallenge(approvalId, decision, timestampEpochSeconds, nonce)
    }

    private fun signerFor(keyId: String = "test-key-1"): Pair<JcaP256StepUpSigner, java.security.PublicKey> =
        JcaP256StepUpSigner.generate(keyId)

    @Test
    fun `legitimate signature verifies against SPKI public key`() {
        val (signer, _) = signerFor()
        val spki = signer.publicKeySpki()
        val verified = StepUpVerifier.publicKeyFromSpki(spki)

        val proof = signer.sign(challenge())
        assertTrue(StepUpVerifier.verify(verified, challenge(), proof))
        assertEquals("test-key-1", proof.keyId)
        assertEquals("APR-123", proof.approvalId)
        assertEquals("approve", proof.decision)
    }

    @Test
    fun `tampered approvalId or decision fails verification`() {
        val (signer, publicKey) = signerFor()
        val proof = signer.sign(challenge())

        assertFalse(
            StepUpVerifier.verify(publicKey, challenge(approvalId = "APR-999"), proof),
            "Swapped approvalId must not verify",
        )
        assertFalse(
            StepUpVerifier.verify(publicKey, challenge(decision = "reject"), proof),
            "Swapped decision must not verify",
        )
        val tamperedProof = proof.copy(signatureBase64Url = flipOneSignatureByte(proof.signatureBase64Url))
        assertFalse(StepUpVerifier.verify(publicKey, challenge(), tamperedProof))
    }

    @Test
    fun `canonical payload is deterministic and nonce-sensitive`() {
        assertEquals(
            challenge(nonceSeed = 7).canonicalPayload().toList(),
            challenge(nonceSeed = 7).canonicalPayload().toList(),
            "Same inputs must produce identical canonical bytes",
        )
        assertNotEquals(
            challenge(nonceSeed = 7).canonicalPayload().toList(),
            challenge(nonceSeed = 8).canonicalPayload().toList(),
        )
        assertNotEquals(
            challenge(timestampEpochSeconds = 1).canonicalPayload().toList(),
            challenge(timestampEpochSeconds = 2).canonicalPayload().toList(),
        )
        // Fixed wire format: prefix line, approvalId, decision, timestamp, unpadded base64url nonce.
        val expected = "lumenbox-stepup/1\nAPR-123\napprove\n1742000000\n" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(challenge().nonce)
        assertEquals(expected, String(challenge().canonicalPayload(), Charsets.UTF_8))
    }

    @Test
    fun `cancelled step-up produces no proof`() {
        val cancelledSigner = object : StepUpSigner {
            override val keyId = "cancelled-key"
            override fun publicKeySpki(): ByteArray = ByteArray(0)
            override fun sign(challenge: StepUpChallenge): StepUpProof =
                throw StepUpCancelledException("Biometric prompt dismissed")
        }
        assertThrows(StepUpCancelledException::class.java) {
            cancelledSigner.sign(challenge())
        }
    }

    @Test
    fun `proof JSON field names match the iOS contract byte-for-byte`() {
        val (signer, _) = signerFor()
        val proof = signer.sign(challenge())
        val json = LumenBoxJson.default.encodeToString(StepUpProof.serializer(), proof)
        // iOS StepUpProof Codable keys: keyId, approvalId, decision, signatureBase64Url.
        assertEquals(
            """{"keyId":"test-key-1","approvalId":"APR-123","decision":"approve",""" +
                """"signatureBase64Url":"${proof.signatureBase64Url}"}""",
            json,
        )
        val decoded = LumenBoxJson.default.decodeFromString(StepUpProof.serializer(), json)
        assertEquals(proof, decoded)
    }

    @Test
    fun `DER ECDSA signature is base64url without padding and decodes to a real signature`() {
        val (signer, publicKey) = signerFor()
        val proof = signer.sign(challenge())
        assertFalse(proof.signatureBase64Url.contains('='))
        assertFalse(proof.signatureBase64Url.contains('+'))
        assertFalse(proof.signatureBase64Url.contains('/'))
        val der = Base64.getUrlDecoder().decode(proof.signatureBase64Url)
        assertTrue(der.size > 8)
        // Independent verification path: manual Signature.verify over the canonical payload.
        val verifier = Signature.getInstance(JcaP256StepUpSigner.SIGNATURE_ALGORITHM)
        verifier.initVerify(publicKey)
        verifier.update(challenge().canonicalPayload())
        assertTrue(verifier.verify(der))
    }

    @Test
    fun `signer rejects weak nonces`() {
        assertThrows(IllegalArgumentException::class.java) {
            StepUpChallenge("APR-1", "approve", 1L, ByteArray(8))
        }
    }

    private fun flipOneSignatureByte(base64Url: String): String {
        val der = Base64.getUrlDecoder().decode(base64Url)
        der[der.size - 1] = (der[der.size - 1].toInt() xor 0x21).toByte()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(der)
    }

    /** Regression guard: raw JDK EC generation still yields secp256r1 keys for the verifier. */
    @Test
    fun `independently generated P-256 keypair verifies signer output`() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val keyPair = generator.generateKeyPair()
        val signer = JcaP256StepUpSigner(keyPair, "independent-key")
        val proof = signer.sign(challenge(decision = "reject"))
        assertTrue(StepUpVerifier.verify(keyPair.public, challenge(decision = "reject"), proof))
    }
}

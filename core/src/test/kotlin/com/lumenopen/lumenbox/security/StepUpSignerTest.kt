package com.lumenopen.lumenbox.security

import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.contract.StepUpAssuranceLevel
import com.lumenopen.lumenbox.contract.StepUpProof
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Base64

class StepUpSignerTest {
    private val fixedNonce = ByteArray(16) { idx -> (idx + 1).toByte() }

    private fun challenge(
        approvalId: String = "apr_prod_deploy",
        decision: String = "allow:once",
        timestamp: Long = 1_700_000_000L,
        nonce: ByteArray = fixedNonce,
    ) = StepUpChallenge(
        approvalId = approvalId,
        decision = decision,
        timestampEpochSeconds = timestamp,
        nonce = nonce,
    )

    @Test
    fun `valid ECDSA P-256 signature verifies against exported SPKI public key`() {
        val (signer, _) = JcaP256StepUpSigner.generate("key-1")
        val spki = signer.publicKeySpki()
        val serverSidePublic = StepUpVerifier.publicKeyFromSpki(spki)

        val ch = challenge()
        val proof = signer.sign(ch)

        assertEquals("key-1", proof.keyId)
        assertEquals("apr_prod_deploy", proof.approvalId)
        assertEquals("allow:once", proof.decision)
        assertTrue(StepUpVerifier.verify(serverSidePublic, ch, proof))
    }

    @Test
    fun `canonical payload layout is deterministic and newline-delimited`() {
        val ch = challenge(nonce = ByteArray(16) { 0 })
        val text = String(ch.canonicalPayload(), StandardCharsets.UTF_8)
        assertEquals(
            "lumenbox-stepup/1\napr_prod_deploy\nallow:once\n1700000000\nAAAAAAAAAAAAAAAAAAAAAA",
            text,
        )
    }

    @Test
    fun `tampering with approvalId, decision, timestamp, nonce, or signature fails verification`() {
        val (signer, pub) = JcaP256StepUpSigner.generate("key-1")
        val original = challenge()
        val proof = signer.sign(original)

        // Replay against another approvalId (cross-approval replay rejected)
        val otherApproval = challenge(approvalId = "apr_rm_rf")
        assertFalse(StepUpVerifier.verify(pub, otherApproval, proof))
        assertFalse(StepUpVerifier.verify(pub, otherApproval, proof.copy(approvalId = "apr_rm_rf")))

        // Decision flipped after signing (`allow:once` -> `allow:session`)
        val otherDecision = challenge(decision = "allow:session")
        assertFalse(StepUpVerifier.verify(pub, otherDecision, proof.copy(decision = "allow:session")))

        // Timestamp or nonce altered
        assertFalse(StepUpVerifier.verify(pub, challenge(timestamp = 1_700_000_001L), proof))
        assertFalse(StepUpVerifier.verify(pub, challenge(nonce = ByteArray(16) { 99 }), proof))

        // Corrupted signature bytes and malformed base64url
        val rawSig = Base64.getUrlDecoder().decode(proof.signatureBase64Url)
        rawSig[rawSig.lastIndex] = (rawSig.last().toInt() xor 0x01).toByte()
        val corrupted = proof.copy(
            signatureBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(rawSig),
        )
        assertFalse(StepUpVerifier.verify(pub, original, corrupted))
        assertFalse(StepUpVerifier.verify(pub, original, proof.copy(signatureBase64Url = "%%%not-base64%%%")))

        // Signature from a different keypair fails against the registered SPKI
        val (rogueSigner, _) = JcaP256StepUpSigner.generate("key-1")
        assertFalse(StepUpVerifier.verify(pub, original, rogueSigner.sign(original)))
    }

    @Test
    fun `user cancellation surfaces StepUpCancelledException without producing a proof`() {
        val cancellingSigner = object : StepUpSigner {
            override val keyId: String = "key-cancel"
            override fun publicKeySpki(): ByteArray = ByteArray(0)
            override fun sign(challenge: StepUpChallenge): StepUpProof {
                throw StepUpCancelledException("User tapped Cancel on BiometricPrompt")
            }
        }
        assertThrows(StepUpCancelledException::class.java) {
            cancellingSigner.sign(challenge())
        }
    }

    @Test
    fun `challenge rejects short nonces and empty identifiers, and StepUpProof round-trips JSON`() {
        assertThrows(IllegalArgumentException::class.java) {
            challenge(nonce = ByteArray(8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            challenge(approvalId = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            challenge(decision = "")
        }

        val (signer, pub) = JcaP256StepUpSigner.generate("key-json")
        val ch = challenge()
        val wire = LumenBoxJson.default.encodeToString(StepUpProof.serializer(), signer.sign(ch))
        val decoded = LumenBoxJson.default.decodeFromString(StepUpProof.serializer(), wire)
        assertTrue(StepUpVerifier.verify(pub, ch, decoded))
    }

    @Test
    fun `three-tier StepUpPolicyCoordinator covers biometric, DEVICE_CREDENTIAL fallback, cancellation non-fallback, and unsecured policy`() = runTest {
        val (signer, pub) = JcaP256StepUpSigner.generate("key-tier")
        val ch = challenge(approvalId = "apr_stepup_2273")

        var bioCalls = 0
        var credCalls = 0
        var explicitCalls = 0
        var capability = DeviceAuthCapability.BIOMETRIC_STRONG
        var bioThrowable: Throwable? = null
        var enteredExplicitCode: String? = null

        val fakeGate = object : StepUpPlatformGate {
            override fun probeCapability(): DeviceAuthCapability = capability

            override suspend fun signWithStrongBiometric(
                title: String,
                subtitle: String,
                challenge: StepUpChallenge,
            ): StepUpProof {
                bioCalls += 1
                bioThrowable?.let { throw it }
                return signer.signWithMetadata(challenge, StepUpAssuranceLevel.HARDWARE_BIOMETRIC)
            }

            override suspend fun signWithDeviceCredential(
                title: String,
                subtitle: String,
                challenge: StepUpChallenge,
            ): StepUpProof {
                credCalls += 1
                return signer.signWithMetadata(challenge, StepUpAssuranceLevel.DEVICE_CREDENTIAL)
            }

            override suspend fun promptAndSignExplicitChallenge(
                title: String,
                subtitle: String,
                expectedCode: String,
                challenge: StepUpChallenge,
            ): StepUpProof {
                explicitCalls += 1
                val code = enteredExplicitCode ?: expectedCode
                return signer.signWithMetadata(
                    challenge = challenge,
                    assuranceLevel = StepUpAssuranceLevel.APP_EXPLICIT_CHALLENGE,
                    confirmationCode = code,
                )
            }
        }

        val coordinator = StepUpPolicyCoordinator(fakeGate)

        // 1. Strong biometric available -> signs with hardware_biometric
        val bioProof = coordinator.authorizeAndSign("Approve", "Deploy", ch)
        assertEquals(1, bioCalls)
        assertEquals(0, credCalls)
        assertEquals(
            OpenEnum.Known(StepUpAssuranceLevel.HARDWARE_BIOMETRIC, "hardware_biometric"),
            bioProof.assuranceLevel,
        )
        assertNull(bioProof.challengeConfirmationCode)
        assertTrue(StepUpVerifier.verify(pub, ch, bioProof))

        // 2. Device without fingerprint hardware / biometric not enrolled, PIN set -> DEVICE_CREDENTIAL
        capability = DeviceAuthCapability.DEVICE_CREDENTIAL_ONLY
        val credProof = coordinator.authorizeAndSign("Approve", "Deploy", ch)
        assertEquals(1, bioCalls)
        assertEquals(1, credCalls)
        assertEquals(
            OpenEnum.Known(StepUpAssuranceLevel.DEVICE_CREDENTIAL, "device_credential"),
            credProof.assuranceLevel,
        )
        assertTrue(StepUpVerifier.verify(pub, ch, credProof))

        // 3. Biometric sensor fails/locks out mid-attempt -> seamlessly falls back to DEVICE_CREDENTIAL
        capability = DeviceAuthCapability.BIOMETRIC_STRONG
        bioThrowable = StepUpAuthenticationException("Too many attempts, sensor locked out")
        val lockoutFallback = coordinator.authorizeAndSign("Approve", "Deploy", ch)
        assertEquals(2, bioCalls)
        assertEquals(2, credCalls)
        assertEquals("device_credential", lockoutFallback.assuranceLevel?.rawValue)
        assertTrue(StepUpVerifier.verify(pub, ch, lockoutFallback))

        // 4. Explicit user cancellation NEVER falls back to DEVICE_CREDENTIAL!
        bioThrowable = StepUpCancelledException("User pressed Cancel")
        var cancelledThrown = false
        try {
            coordinator.authorizeAndSign("Approve", "Deploy", ch)
        } catch (_: StepUpCancelledException) {
            cancelledThrown = true
        }
        assertTrue(cancelledThrown)
        assertEquals(2, credCalls)

        // 5. Unsecured phone (no screen lock): blocked in organisation mode, 2-digit code in personal mode
        capability = DeviceAuthCapability.UNSECURED
        var blockedByOrgPolicy = false
        try {
            coordinator.authorizeAndSign(
                title = "Approve",
                subtitle = "Org",
                challenge = ch,
                policy = StepUpSecurityPolicy.ORGANISATION_DEFAULT,
            )
        } catch (_: StepUpScreenLockRequiredException) {
            blockedByOrgPolicy = true
        }
        assertTrue(blockedByOrgPolicy)
        assertEquals(0, explicitCalls)

        // Wrong 2-digit code in personal mode -> rejected
        enteredExplicitCode = "00"
        var mismatchThrown = false
        try {
            coordinator.authorizeAndSign(
                title = "Approve",
                subtitle = "Personal",
                challenge = ch,
                policy = StepUpSecurityPolicy.PERSONAL_DEFAULT,
            )
        } catch (_: StepUpExplicitChallengeMismatchException) {
            mismatchThrown = true
        }
        assertTrue(mismatchThrown)

        // Correct 2-digit code in personal mode -> app_explicit_challenge proof verifies
        enteredExplicitCode = ch.expectedExplicitConfirmationCode()
        val personalProof = coordinator.authorizeAndSign(
            title = "Approve",
            subtitle = "Personal",
            challenge = ch,
            policy = StepUpSecurityPolicy.PERSONAL_DEFAULT,
        )
        assertEquals("app_explicit_challenge", personalProof.assuranceLevel?.rawValue)
        assertEquals(ch.expectedExplicitConfirmationCode(), personalProof.challengeConfirmationCode)
        assertTrue(StepUpVerifier.verify(pub, ch, personalProof))
        assertFalse(StepUpVerifier.verify(pub, ch, personalProof.copy(challengeConfirmationCode = "00")))
    }
}

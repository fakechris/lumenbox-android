package com.lumenopen.lumenbox.security

import com.lumenopen.lumenbox.contract.StepUpProof
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The user declined, cancelled, or dismissed the biometric step-up challenge. */
class StepUpCancelledException(message: String) : RuntimeException(message)

/** Biometric authentication failed in a non-recoverable way (lockout, no enrolment, hardware). */
class StepUpAuthenticationException(message: String) : RuntimeException(message)

/**
 * One high-risk approval challenge (`docs/106 §4.2`, `docs/110 §4.4`): a `stepUpRequired: true`
 * approval must be signed by a hardware-protected key after a fresh biometric unlock.
 * The signature binds `(approvalId, decision)` plus a timestamp and nonce to defeat replays.
 */
data class StepUpChallenge(
    val approvalId: String,
    val decision: String,
    val timestampEpochSeconds: Long,
    val nonce: ByteArray,
) {
    init {
        require(approvalId.isNotEmpty()) { "approvalId must not be empty" }
        require(decision.isNotEmpty()) { "decision must not be empty" }
        require(nonce.size >= 16) { "nonce must carry at least 128 bits of entropy" }
    }

    /**
     * Canonical signing payload, byte-identical on iOS and Android (mirror
     * `Sources/LumenBoxContract` in fakechris/lumenbox-ios): five lines joined by `\n`,
     * UTF-8, nonce in unpadded base64url. Neither platform may deviate from this wire order.
     */
    fun canonicalPayload(): ByteArray = listOf(
        CANONICAL_PREFIX,
        approvalId,
        decision,
        timestampEpochSeconds.toString(),
        Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
    ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)

    override fun equals(other: Any?): Boolean = other is StepUpChallenge &&
        approvalId == other.approvalId &&
        decision == other.decision &&
        timestampEpochSeconds == other.timestampEpochSeconds &&
        nonce.contentEquals(other.nonce)

    override fun hashCode(): Int = arrayOf(approvalId, decision, timestampEpochSeconds, nonce).contentHashCode()

    companion object {
        const val CANONICAL_PREFIX = "lumenbox-stepup/1"
    }
}

/**
 * Injectable high-risk approval signer. Production Android wires [AndroidKeystoreStepUpSigner]
 * (in `:app`, hardware Keystore + `BiometricPrompt.CryptoObject`); JVM tests inject
 * [JcaP256StepUpSigner] or fakes so signature/verification is fully exercised without a device.
 */
interface StepUpSigner {
    /** Stable identifier of the key entry, echoed in [StepUpProof.keyId]. */
    val keyId: String

    /** X.509 `SubjectPublicKeyInfo` (SPKI) DER encoding for device-credential registration. */
    fun publicKeySpki(): ByteArray

    /**
     * Signs the canonical challenge payload after a fresh user authentication.
     * @throws StepUpCancelledException when the user declines or dismisses the challenge.
     * @throws StepUpAuthenticationException when biometric auth fails unrecoverably.
     */
    fun sign(challenge: StepUpChallenge): StepUpProof
}

/** JVM-standard ECDSA P-256 signer over `SHA256withECDSA` (also the algorithm reference). */
class JcaP256StepUpSigner(
    private val privateKey: PrivateKey,
    private val publicKey: PublicKey,
    override val keyId: String,
) : StepUpSigner {
    constructor(keyPair: KeyPair, keyId: String) : this(keyPair.private, keyPair.public, keyId)

    override fun publicKeySpki(): ByteArray = publicKey.encoded

    override fun sign(challenge: StepUpChallenge): StepUpProof {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(challenge.canonicalPayload())
        return StepUpProof(
            keyId = keyId,
            approvalId = challenge.approvalId,
            decision = challenge.decision,
            signatureBase64Url = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature.sign()),
        )
    }

    companion object {
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        const val EC_CURVE = "secp256r1"

        fun generate(keyId: String): Pair<JcaP256StepUpSigner, PublicKey> {
            val generator = java.security.KeyPairGenerator.getInstance("EC")
            generator.initialize(java.security.spec.ECGenParameterSpec(EC_CURVE))
            val keyPair = generator.generateKeyPair()
            return JcaP256StepUpSigner(keyPair, keyId) to keyPair.public
        }
    }
}

/** Server-side-style verification used by unit tests (and the desktop companion). */
object StepUpVerifier {
    fun publicKeyFromSpki(spkiDer: ByteArray): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spkiDer))

    fun privateKeyFromPkcs8(pkcs8Der: ByteArray): PrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8Der))

    /**
     * True when the proof's fields match the challenge and the base64url DER ECDSA signature
     * verifies against the SPKI public key over the canonical payload.
     */
    fun verify(publicKey: PublicKey, challenge: StepUpChallenge, proof: StepUpProof): Boolean {
        if (proof.keyId.isEmpty() || proof.approvalId != challenge.approvalId ||
            proof.decision != challenge.decision
        ) {
            return false
        }
        val signature = try {
            Signature.getInstance(JcaP256StepUpSigner.SIGNATURE_ALGORITHM)
        } catch (_: java.security.GeneralSecurityException) {
            return false
        }
        return try {
            signature.initVerify(publicKey)
            signature.update(challenge.canonicalPayload())
            signature.verify(Base64.getUrlDecoder().decode(proof.signatureBase64Url))
        } catch (_: Exception) {
            false
        }
    }
}

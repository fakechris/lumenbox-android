package com.lumenopen.lumenbox.security

import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.contract.StepUpAssuranceLevel
import com.lumenopen.lumenbox.contract.StepUpProof
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** The user declined, cancelled, or dismissed the step-up challenge. */
class StepUpCancelledException(message: String) : RuntimeException(message)

/** Step-up authentication failed in a non-recoverable way. */
open class StepUpAuthenticationException(message: String) : RuntimeException(message)

/**
 * Thrown when organisation policy requires at least a system screen lock (`PIN` / `Pattern` /
 * `Password` or enrolled biometric) and the device currently has `KeyguardManager.isDeviceSecure == false`.
 */
class StepUpScreenLockRequiredException(
    val settingsActionHint: String,
    message: String = "当前组织策略要求手机开启系统锁屏（指纹/面容或锁屏密码），请先前往系统设置启用锁屏保护。",
) : StepUpAuthenticationException(message)

/** Thrown when the user enters a mismatched 2-digit explicit confirmation code on an unsecured personal device. */
class StepUpExplicitChallengeMismatchException(
    message: String = "两位确认数字输入不匹配，高危操作未放行。",
) : StepUpAuthenticationException(message)

/**
 * Detected authentication capability of the current Android device (`INV-2273`):
 * - [BIOMETRIC_STRONG]: Strong hardware biometric enrolled and ready.
 * - [DEVICE_CREDENTIAL_ONLY]: No strong biometric available/enrolled, or biometric sensor temporarily
 *   locked out, while a system screen lock (`PIN`, `Pattern`, or `Password`) is configured.
 * - [UNSECURED]: Neither biometrics nor a system screen lock is configured on the device.
 */
enum class DeviceAuthCapability {
    BIOMETRIC_STRONG,
    DEVICE_CREDENTIAL_ONLY,
    UNSECURED,
}

data class StepUpSecurityPolicy(
    val requireSystemScreenLock: Boolean,
) {
    companion object {
        val ORGANISATION_DEFAULT = StepUpSecurityPolicy(requireSystemScreenLock = true)
        val PERSONAL_DEFAULT = StepUpSecurityPolicy(requireSystemScreenLock = false)
    }
}

/**
 * One high-risk approval challenge (`docs/106 §4.2`, `docs/110 §4.4`): a `stepUpRequired: true`
 * approval must be signed by a hardware-protected key after a fresh user unlock.
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
     * `Sources/LumenBoxSecurity` in fakechris/lumenbox-ios): five lines joined by `\n`,
     * UTF-8, nonce in unpadded base64url. Neither platform may deviate from this wire order.
     */
    fun canonicalPayload(): ByteArray = listOf(
        CANONICAL_PREFIX,
        approvalId,
        decision,
        timestampEpochSeconds.toString(),
        Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
    ).joinToString("\n").toByteArray(StandardCharsets.UTF_8)

    /**
     * Deterministic 2-digit (`10`–`99`) anti-mis-tap confirmation code derived from SHA-256 of
     * [canonicalPayload] (identical formula to iOS `StepUpChallenge.expectedExplicitConfirmationCode`).
     */
    fun expectedExplicitConfirmationCode(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(canonicalPayload())
        val hi = digest[0].toInt() and 0xFF
        val lo = digest[1].toInt() and 0xFF
        val code = (((hi shl 8) or lo) % 90) + 10
        return "%02d".format(code)
    }

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
 * Injectable high-risk approval signer. Production Android wires `AndroidKeystoreStepUpSigner`
 * (in `:app`, hardware Keystore + biometric / `DEVICE_CREDENTIAL` unlock); JVM tests inject
 * [JcaP256StepUpSigner] or [StepUpPolicyCoordinator].
 */
interface StepUpSigner {
    /** Stable identifier of the key entry, echoed in [StepUpProof.keyId]. */
    val keyId: String

    /** X.509 `SubjectPublicKeyInfo` (SPKI) DER encoding for device-credential registration. */
    fun publicKeySpki(): ByteArray

    /**
     * Signs the canonical challenge payload after a fresh user authentication.
     * @throws StepUpCancelledException when the user declines or dismisses the challenge.
     * @throws StepUpAuthenticationException when authentication fails unrecoverably.
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

    override fun sign(challenge: StepUpChallenge): StepUpProof =
        signWithMetadata(challenge, assuranceLevel = null, confirmationCode = null)

    fun signWithMetadata(
        challenge: StepUpChallenge,
        assuranceLevel: StepUpAssuranceLevel?,
        confirmationCode: String? = null,
    ): StepUpProof {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(challenge.canonicalPayload())
        return StepUpProof(
            keyId = keyId,
            approvalId = challenge.approvalId,
            decision = challenge.decision,
            signatureBase64Url = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signature.sign()),
            assuranceLevel = assuranceLevel?.let { OpenEnum.Known(it, it.wireName) },
            challengeConfirmationCode = confirmationCode,
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

/**
 * Pluggable platform authentication gate (`INV-2273`) so the three-tier fallback policy is
 * 100% deterministically unit-testable in `:core:test` without requiring an Android emulator.
 */
interface StepUpPlatformGate {
    fun probeCapability(): DeviceAuthCapability

    /** Unlocks and signs using strong biometrics (`BIOMETRIC_STRONG` + `CryptoObject(Signature)`). */
    suspend fun signWithStrongBiometric(
        title: String,
        subtitle: String,
        challenge: StepUpChallenge,
    ): StepUpProof

    /**
     * Unlocks and signs using the Android system screen lock (`PIN`, `Pattern`, or `Password` via
     * `AUTH_DEVICE_CREDENTIAL`) over an `AndroidKeyStore` hardware key.
     */
    suspend fun signWithDeviceCredential(
        title: String,
        subtitle: String,
        challenge: StepUpChallenge,
    ): StepUpProof

    /**
     * Requests a 2-digit anti-mis-tap confirmation code from the user and signs on unsecured
     * personal devices (`KeyguardManager.isDeviceSecure == false`).
     */
    suspend fun promptAndSignExplicitChallenge(
        title: String,
        subtitle: String,
        expectedCode: String,
        challenge: StepUpChallenge,
    ): StepUpProof
}

/**
 * Pure-Kotlin three-tier Step-Up policy coordinator (`INV-2273`):
 * 1. `hardware_biometric`: Attempts `BIOMETRIC_STRONG` first; if biometrics fail due to hardware
 *    error or temporary lockout (NEVER on explicit user cancellation!), seamlessly falls back to
 *    system `DEVICE_CREDENTIAL` (`PIN` / `Pattern` / `Password`).
 * 2. `device_credential`: Directly invokes system `PIN` / `Pattern` / `Password` unlock when no
 *    strong biometric is enrolled on the phone.
 * 3. `app_explicit_challenge`: When the phone has no screen lock at all (`UNSECURED`), blocks with
 *    [StepUpScreenLockRequiredException] under organisation policy (`requireSystemScreenLock = true`),
 *    or verifies the 2-digit explicit confirmation code in personal mode (`requireSystemScreenLock = false`).
 */
class StepUpPolicyCoordinator(
    private val platformGate: StepUpPlatformGate,
    private val settingsActionHint: String = "android.app.action.SET_NEW_PASSWORD",
) {
    suspend fun authorizeAndSign(
        title: String,
        subtitle: String,
        challenge: StepUpChallenge,
        policy: StepUpSecurityPolicy = StepUpSecurityPolicy.ORGANISATION_DEFAULT,
    ): StepUpProof {
        return when (platformGate.probeCapability()) {
            DeviceAuthCapability.BIOMETRIC_STRONG -> {
                try {
                    val proof = platformGate.signWithStrongBiometric(title, subtitle, challenge)
                    proof.copy(
                        assuranceLevel = OpenEnum.Known(
                            StepUpAssuranceLevel.HARDWARE_BIOMETRIC,
                            StepUpAssuranceLevel.HARDWARE_BIOMETRIC.wireName,
                        ),
                        challengeConfirmationCode = null,
                    )
                } catch (cancelled: StepUpCancelledException) {
                    // Explicit user dismissal must NEVER auto-fallback or send network traffic.
                    throw cancelled
                } catch (_: Throwable) {
                    // Sensor unavailable or lockout mid-flight: seamlessly fall back to PIN/Pattern/Password!
                    val fallbackProof = platformGate.signWithDeviceCredential(title, subtitle, challenge)
                    fallbackProof.copy(
                        assuranceLevel = OpenEnum.Known(
                            StepUpAssuranceLevel.DEVICE_CREDENTIAL,
                            StepUpAssuranceLevel.DEVICE_CREDENTIAL.wireName,
                        ),
                        challengeConfirmationCode = null,
                    )
                }
            }

            DeviceAuthCapability.DEVICE_CREDENTIAL_ONLY -> {
                val proof = platformGate.signWithDeviceCredential(title, subtitle, challenge)
                proof.copy(
                    assuranceLevel = OpenEnum.Known(
                        StepUpAssuranceLevel.DEVICE_CREDENTIAL,
                        StepUpAssuranceLevel.DEVICE_CREDENTIAL.wireName,
                    ),
                    challengeConfirmationCode = null,
                )
            }

            DeviceAuthCapability.UNSECURED -> {
                if (policy.requireSystemScreenLock) {
                    throw StepUpScreenLockRequiredException(settingsActionHint = settingsActionHint)
                }
                val expectedCode = challenge.expectedExplicitConfirmationCode()
                val proof = platformGate.promptAndSignExplicitChallenge(
                    title = title,
                    subtitle = subtitle,
                    expectedCode = expectedCode,
                    challenge = challenge,
                )
                if (proof.challengeConfirmationCode?.trim() != expectedCode) {
                    throw StepUpExplicitChallengeMismatchException()
                }
                proof.copy(
                    assuranceLevel = OpenEnum.Known(
                        StepUpAssuranceLevel.APP_EXPLICIT_CHALLENGE,
                        StepUpAssuranceLevel.APP_EXPLICIT_CHALLENGE.wireName,
                    ),
                    challengeConfirmationCode = expectedCode,
                )
            }
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
     * True when the proof's fields match the challenge (including explicit confirmation code when
     * `assuranceLevel == app_explicit_challenge`) and the base64url DER ECDSA signature verifies
     * against the SPKI public key over the canonical payload.
     */
    fun verify(publicKey: PublicKey, challenge: StepUpChallenge, proof: StepUpProof): Boolean {
        if (proof.keyId.isEmpty() || proof.approvalId != challenge.approvalId ||
            proof.decision != challenge.decision
        ) {
            return false
        }
        if (proof.assuranceLevel?.rawValue == StepUpAssuranceLevel.APP_EXPLICIT_CHALLENGE.wireName &&
            proof.challengeConfirmationCode != challenge.expectedExplicitConfirmationCode()
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

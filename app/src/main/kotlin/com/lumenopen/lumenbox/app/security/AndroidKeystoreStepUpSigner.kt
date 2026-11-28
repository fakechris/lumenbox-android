package com.lumenopen.lumenbox.app.security

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.contract.StepUpAssuranceLevel
import com.lumenopen.lumenbox.contract.StepUpProof
import com.lumenopen.lumenbox.security.DeviceAuthCapability
import com.lumenopen.lumenbox.security.JcaP256StepUpSigner
import com.lumenopen.lumenbox.security.StepUpAuthenticationException
import com.lumenopen.lumenbox.security.StepUpCancelledException
import com.lumenopen.lumenbox.security.StepUpChallenge
import com.lumenopen.lumenbox.security.StepUpExplicitChallengeMismatchException
import com.lumenopen.lumenbox.security.StepUpPlatformGate
import com.lumenopen.lumenbox.security.StepUpPolicyCoordinator
import com.lumenopen.lumenbox.security.StepUpSecurityPolicy
import com.lumenopen.lumenbox.security.StepUpSigner
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Probes the Android phone's real biometric & screen-lock posture (`INV-2273`):
 * distinguishes `BIOMETRIC_STRONG`, `DEVICE_CREDENTIAL_ONLY` (PIN / Pattern / Password set, no
 * biometric hardware or no enrolled biometrics), and `UNSECURED` (no screen lock set at all).
 */
object DeviceAuthCapabilityProbe {
    fun probe(context: Context): DeviceAuthCapability {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val isDeviceSecure = keyguard?.isDeviceSecure == true

        val strongBiometricReady = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bm = context.getSystemService(BiometricManager::class.java)
            bm?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bm = context.getSystemService(BiometricManager::class.java)
            @Suppress("DEPRECATION")
            bm?.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS
        } else {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT) && isDeviceSecure
        }

        return when {
            strongBiometricReady -> DeviceAuthCapability.BIOMETRIC_STRONG
            isDeviceSecure -> DeviceAuthCapability.DEVICE_CREDENTIAL_ONLY
            else -> DeviceAuthCapability.UNSECURED
        }
    }

    /** Builds an Intent guiding the user directly to Android screen-lock / biometric enrolment settings. */
    fun buildEnrollScreenLockIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_BIOMETRIC_ENROLL).apply {
                putExtra(
                    Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                )
            }
        } else {
            Intent(DevicePolicyManager.ACTION_SET_NEW_PASSWORD)
        }
}

/**
 * Hardware-backed high-risk approval signer (`docs/106 §4.2`, `docs/110 §4.4`, INV-2253, INV-2273).
 *
 * Supports three tiers so phones without fingerprint/face hardware or unenrolled biometrics work cleanly:
 * 1. Primary (`alias`): ECDSA P-256 key in `AndroidKeyStore` requiring per-operation `BIOMETRIC_STRONG`
 *    (`CryptoObject(Signature)`) with `setInvalidatedByBiometricEnrollment(true)`.
 * 2. Device-credential hardware fallback (`credentialAlias`): ECDSA P-256 key in `AndroidKeyStore`
 *    unlocked via system PIN / Pattern / Password (`AUTH_BIOMETRIC_STRONG or AUTH_DEVICE_CREDENTIAL`
 *    15s window on API 30+, `setUserAuthenticationValidityDurationSeconds(15)` on API 28–29).
 * 3. Unsecured personal-mode key (`unsecuredAlias`): Non-exportable `AndroidKeyStore` ECDSA P-256 key
 *    used ONLY after an explicit interactive 2-digit confirmation challenge succeeds when
 *    `KeyguardManager.isDeviceSecure == false` and `policy.requireSystemScreenLock == false`.
 */
class AndroidKeystoreStepUpSigner(
    override val keyId: String = DEFAULT_KEY_ID,
    private val alias: String = DEFAULT_KEY_ID,
    private val credentialAlias: String = DEFAULT_CREDENTIAL_KEY_ID,
    private val unsecuredAlias: String = DEFAULT_UNSECURED_KEY_ID,
) : StepUpSigner {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Generates the primary biometric-bound key if absent; existing keys are reused. */
    fun ensureKey() {
        if (keyStore.containsAlias(alias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec(JcaP256StepUpSigner.EC_CURVE))
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build(),
        )
        generator.generateKeyPair()
    }

    /** Generates the hardware-backed system screen-lock (`DEVICE_CREDENTIAL`) key if absent. */
    fun ensureDeviceCredentialKey() {
        if (keyStore.containsAlias(credentialAlias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        val specBuilder = KeyGenParameterSpec.Builder(credentialAlias, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setAlgorithmParameterSpec(ECGenParameterSpec(JcaP256StepUpSigner.EC_CURVE))
            .setUserAuthenticationRequired(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            specBuilder.setUserAuthenticationParameters(
                15,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
        } else {
            @Suppress("DEPRECATION")
            specBuilder.setUserAuthenticationValidityDurationSeconds(15)
        }
        generator.initialize(specBuilder.build())
        generator.generateKeyPair()
    }

    /** Generates the AndroidKeyStore non-auth-gated key used strictly behind explicit 2-digit challenge. */
    fun ensureUnsecuredFallbackKey() {
        if (keyStore.containsAlias(unsecuredAlias)) return
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(unsecuredAlias, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAlgorithmParameterSpec(ECGenParameterSpec(JcaP256StepUpSigner.EC_CURVE))
                .setUserAuthenticationRequired(false)
                .build(),
        )
        generator.generateKeyPair()
    }

    override fun publicKeySpki(): ByteArray {
        return runCatching {
            ensureKey()
            keyStore.getCertificate(alias).publicKey.encoded
        }.recoverCatching {
            ensureDeviceCredentialKey()
            keyStore.getCertificate(credentialAlias).publicKey.encoded
        }.getOrElse {
            ensureUnsecuredFallbackKey()
            keyStore.getCertificate(unsecuredAlias).publicKey.encoded
        }
    }

    /**
     * A `Signature` initialized with the strong biometric key, wrapped in `BiometricPrompt.CryptoObject`.
     */
    fun newBiometricSignature(): Signature {
        ensureKey()
        return try {
            Signature.getInstance(JcaP256StepUpSigner.SIGNATURE_ALGORITHM).apply { initSign(loadPrivateKey(alias)) }
        } catch (_: KeyPermanentlyInvalidatedException) {
            keyStore.deleteEntry(alias)
            ensureKey()
            Signature.getInstance(JcaP256StepUpSigner.SIGNATURE_ALGORITHM).apply { initSign(loadPrivateKey(alias)) }
        }
    }

    override fun sign(challenge: StepUpChallenge): StepUpProof =
        throw UnsupportedOperationException(
            "Use StepUpBiometricGateway so the key is unlocked via BiometricPrompt / DEVICE_CREDENTIAL / explicit challenge",
        )

    fun signStepUp(
        challenge: StepUpChallenge,
        authenticatedSignature: Signature,
        assurance: StepUpAssuranceLevel = StepUpAssuranceLevel.HARDWARE_BIOMETRIC,
    ): StepUpProof {
        authenticatedSignature.update(challenge.canonicalPayload())
        val der = authenticatedSignature.sign()
        return StepUpProof(
            keyId = keyId,
            approvalId = challenge.approvalId,
            decision = challenge.decision,
            signatureBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(der),
            assuranceLevel = OpenEnum.Known(assurance, assurance.wireName),
        )
    }

    fun signAfterDeviceCredentialUnlock(challenge: StepUpChallenge): StepUpProof {
        ensureDeviceCredentialKey()
        val sig = try {
            Signature.getInstance(JcaP256StepUpSigner.SIGNATURE_ALGORITHM).apply {
                initSign(loadPrivateKey(credentialAlias))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            keyStore.deleteEntry(credentialAlias)
            ensureDeviceCredentialKey()
            Signature.getInstance(JcaP256StepUpSigner.SIGNATURE_ALGORITHM).apply {
                initSign(loadPrivateKey(credentialAlias))
            }
        }
        sig.update(challenge.canonicalPayload())
        return StepUpProof(
            keyId = credentialAlias,
            approvalId = challenge.approvalId,
            decision = challenge.decision,
            signatureBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign()),
            assuranceLevel = OpenEnum.Known(
                StepUpAssuranceLevel.DEVICE_CREDENTIAL,
                StepUpAssuranceLevel.DEVICE_CREDENTIAL.wireName,
            ),
        )
    }

    fun signExplicitUnsecuredChallenge(challenge: StepUpChallenge, confirmedCode: String): StepUpProof {
        ensureUnsecuredFallbackKey()
        val sig = Signature.getInstance(JcaP256StepUpSigner.SIGNATURE_ALGORITHM).apply {
            initSign(loadPrivateKey(unsecuredAlias))
        }
        sig.update(challenge.canonicalPayload())
        return StepUpProof(
            keyId = unsecuredAlias,
            approvalId = challenge.approvalId,
            decision = challenge.decision,
            signatureBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(sig.sign()),
            assuranceLevel = OpenEnum.Known(
                StepUpAssuranceLevel.APP_EXPLICIT_CHALLENGE,
                StepUpAssuranceLevel.APP_EXPLICIT_CHALLENGE.wireName,
            ),
            challengeConfirmationCode = confirmedCode,
        )
    }

    private fun loadPrivateKey(targetAlias: String): PrivateKey =
        keyStore.getKey(targetAlias, null) as? PrivateKey
            ?: throw StepUpAuthenticationException("Step-up key entry '$targetAlias' missing from AndroidKeyStore")

    companion object {
        const val DEFAULT_KEY_ID = "com.lumenopen.lumenbox.stepup.p256"
        const val DEFAULT_CREDENTIAL_KEY_ID = "com.lumenopen.lumenbox.stepup.cred.p256"
        const val DEFAULT_UNSECURED_KEY_ID = "com.lumenopen.lumenbox.stepup.unsecured.p256"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** UI callback registered by Compose so unsecured personal-mode devices can display the 2-digit dialog. */
fun interface ExplicitChallengeUiDialog {
    suspend fun requestTwoDigitConfirmation(
        title: String,
        subtitle: String,
        expectedCode: String,
    ): String
}

/**
 * Suspend gateway over `android.hardware.biometrics.BiometricPrompt` + `DEVICE_CREDENTIAL` fallback
 * + personal-mode explicit confirmation (`INV-2253` & `INV-2273`).
 */
class StepUpBiometricGateway(private val signer: AndroidKeystoreStepUpSigner) {

    suspend fun signWithStepUp(
        context: Context,
        title: String,
        subtitle: String,
        challenge: StepUpChallenge,
        policy: StepUpSecurityPolicy = StepUpSecurityPolicy.ORGANISATION_DEFAULT,
        explicitDialog: ExplicitChallengeUiDialog? = null,
    ): StepUpProof {
        val gate = object : StepUpPlatformGate {
            override fun probeCapability(): DeviceAuthCapability =
                DeviceAuthCapabilityProbe.probe(context)

            override suspend fun signWithStrongBiometric(
                title: String,
                subtitle: String,
                challenge: StepUpChallenge,
            ): StepUpProof = promptStrongBiometric(context, title, subtitle, challenge)

            override suspend fun signWithDeviceCredential(
                title: String,
                subtitle: String,
                challenge: StepUpChallenge,
            ): StepUpProof = promptDeviceCredential(context, title, subtitle, challenge)

            override suspend fun promptAndSignExplicitChallenge(
                title: String,
                subtitle: String,
                expectedCode: String,
                challenge: StepUpChallenge,
            ): StepUpProof {
                val dialog = explicitDialog
                    ?: throw StepUpAuthenticationException("当前设备未设置系统锁屏，请先设置锁屏密码。")
                val entered = dialog.requestTwoDigitConfirmation(title, subtitle, expectedCode).trim()
                if (entered != expectedCode) {
                    throw StepUpExplicitChallengeMismatchException()
                }
                return signer.signExplicitUnsecuredChallenge(challenge, entered)
            }
        }
        return StepUpPolicyCoordinator(gate).authorizeAndSign(
            title = title,
            subtitle = subtitle,
            challenge = challenge,
            policy = policy,
        )
    }

    /** Backwards-compatible alias delegating to [signWithStepUp]. */
    suspend fun signWithBiometric(
        context: Context,
        title: String,
        subtitle: String,
        challenge: StepUpChallenge,
    ): StepUpProof = signWithStepUp(
        context = context,
        title = title,
        subtitle = subtitle,
        challenge = challenge,
    )

    private suspend fun promptStrongBiometric(
        context: Context,
        title: String,
        subtitle: String,
        challenge: StepUpChallenge,
    ): StepUpProof = suspendCancellableCoroutine { continuation ->
        val signature = signer.newBiometricSignature()
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation { cancellationSignal.cancel() }
        val executor = context.mainExecutor
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButton(context.getString(android.R.string.cancel), executor) { _, _ ->
                if (continuation.context.isActive) {
                    continuation.resumeWith(Result.failure(StepUpCancelledException("User pressed negative button")))
                }
            }
            .build()
        prompt.authenticate(
            BiometricPrompt.CryptoObject(signature),
            cancellationSignal,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        continuation.resumeWith(
                            Result.success(
                                signer.signStepUp(
                                    challenge = challenge,
                                    authenticatedSignature = signature,
                                    assurance = StepUpAssuranceLevel.HARDWARE_BIOMETRIC,
                                ),
                            ),
                        )
                    } catch (t: Throwable) {
                        continuation.resumeWith(Result.failure(t))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.context.isActive) return
                    val failure = if (errorCode in CANCEL_ERROR_CODES) {
                        StepUpCancelledException(errString.toString())
                    } else {
                        StepUpAuthenticationException(errString.toString())
                    }
                    continuation.resumeWith(Result.failure(failure))
                }

                override fun onAuthenticationFailed() = Unit
            },
        )
    }

    /**
     * Prompts the Android system screen lock (`PIN`, `Pattern`, or `Password` via `DEVICE_CREDENTIAL`)
     * without passing a `CryptoObject` (which Android forbids alongside `DEVICE_CREDENTIAL`), then
     * signs immediately inside the 15-second hardware key unlock window!
     */
    private suspend fun promptDeviceCredential(
        context: Context,
        title: String,
        subtitle: String,
        challenge: StepUpChallenge,
    ): StepUpProof = suspendCancellableCoroutine { continuation ->
        signer.ensureDeviceCredentialKey()
        val cancellationSignal = CancellationSignal()
        continuation.invokeOnCancellation { cancellationSignal.cancel() }
        val executor = context.mainExecutor
        val builder = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        } else {
            builder.setNegativeButton(context.getString(android.R.string.cancel), executor) { _, _ ->
                if (continuation.context.isActive) {
                    continuation.resumeWith(Result.failure(StepUpCancelledException("User cancelled")))
                }
            }
        }
        val prompt = builder.build()
        prompt.authenticate(
            cancellationSignal,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    try {
                        continuation.resumeWith(Result.success(signer.signAfterDeviceCredentialUnlock(challenge)))
                    } catch (t: Throwable) {
                        continuation.resumeWith(Result.failure(t))
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!continuation.context.isActive) return
                    val failure = if (errorCode in CANCEL_ERROR_CODES) {
                        StepUpCancelledException(errString.toString())
                    } else {
                        StepUpAuthenticationException(errString.toString())
                    }
                    continuation.resumeWith(Result.failure(failure))
                }

                override fun onAuthenticationFailed() = Unit
            },
        )
    }

    private companion object {
        // BIOMETRIC_ERROR_USER_CANCELED, BIOMETRIC_ERROR_CANCELED, BIOMETRIC_ERROR_NEGATIVE_BUTTON.
        val CANCEL_ERROR_CODES = setOf(5, 10, 13)
    }
}

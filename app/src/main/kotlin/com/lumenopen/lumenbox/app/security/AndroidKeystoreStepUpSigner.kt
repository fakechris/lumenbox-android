package com.lumenopen.lumenbox.app.security

import android.content.Context
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import com.lumenopen.lumenbox.contract.StepUpProof
import com.lumenopen.lumenbox.security.JcaP256StepUpSigner
import com.lumenopen.lumenbox.security.StepUpAuthenticationException
import com.lumenopen.lumenbox.security.StepUpCancelledException
import com.lumenopen.lumenbox.security.StepUpChallenge
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
 * Hardware-backed high-risk approval signer (`docs/106 §4.2`, `docs/110 §4.4`, INV-2253).
 *
 * The ECDSA P-256 key lives in `AndroidKeyStore` with
 * `setUserAuthenticationRequired(true)` and `setInvalidatedByBiometricEnrollment(true)`,
 * so every signature needs a fresh `BiometricPrompt` unlock (`CryptoObject(Signature)`)
 * and the key dies when biometrics are re-enrolled. The private key never leaves the
 * secure hardware; only the SPKI public key is exported for device-credential registration.
 */
class AndroidKeystoreStepUpSigner(
    override val keyId: String = DEFAULT_KEY_ID,
    private val alias: String = DEFAULT_KEY_ID,
) : StepUpSigner {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** Generates the step-up key if absent; existing keys are reused (stable `keyId`). */
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

    override fun publicKeySpki(): ByteArray {
        ensureKey()
        return keyStore.getCertificate(alias).publicKey.encoded
    }

    /**
     * A `Signature` initialized with the hardware key, to be wrapped in
     * `BiometricPrompt.CryptoObject` so the unlock transitively authorizes this exact operation.
     */
    fun newBiometricSignature(): Signature {
        ensureKey()
        return try {
            Signature.getInstance(JcaP256StepUpSigner.SIGNATURE_ALGORITHM).apply { initSign(loadPrivateKey()) }
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Biometrics were re-enrolled: the invalidated key must be replaced before use.
            keyStore.deleteEntry(alias)
            ensureKey()
            Signature.getInstance(JcaP256StepUpSigner.SIGNATURE_ALGORITHM).apply { initSign(loadPrivateKey()) }
        }
    }

    /** Produces the proof with a `Signature` that already passed biometric authentication. */
    override fun sign(challenge: StepUpChallenge): StepUpProof =
        throw UnsupportedOperationException(
            "Use newBiometricSignature() + StepUpBiometricGateway so the key is unlocked via BiometricPrompt",
        )

    fun signStepUp(challenge: StepUpChallenge, authenticatedSignature: Signature): StepUpProof {
        authenticatedSignature.update(challenge.canonicalPayload())
        val der = authenticatedSignature.sign()
        return StepUpProof(
            keyId = keyId,
            approvalId = challenge.approvalId,
            decision = challenge.decision,
            signatureBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(der),
        )
    }

    private fun loadPrivateKey(): PrivateKey =
        keyStore.getKey(alias, null) as? PrivateKey
            ?: throw StepUpAuthenticationException("Step-up key entry missing from AndroidKeyStore")

    companion object {
        const val DEFAULT_KEY_ID = "com.lumenopen.lumenbox.stepup.p256"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/**
 * Suspend gateway over the platform `android.hardware.biometrics.BiometricPrompt` (API 28+,
 * this project's `minSdk`). Authenticates the signer's `CryptoObject(Signature)` and returns
 * the [StepUpProof]; user dismissal maps to [StepUpCancelledException].
 */
class StepUpBiometricGateway(private val signer: AndroidKeystoreStepUpSigner) {

    suspend fun signWithBiometric(
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
            .setNegativeButton(context.getString(android.R.string.cancel), executor) { _, _, _ ->
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
                        continuation.resumeWith(Result.success(signer.signStepUp(challenge, signature)))
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

                override fun onAuthenticationFailed() {
                    // Per-attempt mismatch: BiometricPrompt keeps retrying; nothing to do here.
                }
            },
        )
    }

    private companion object {
        // BIOMETRIC_ERROR_USER_CANCELED, BIOMETRIC_ERROR_CANCELED, BIOMETRIC_ERROR_NEGATIVE_BUTTON.
        val CANCEL_ERROR_CODES = setOf(5, 10, 13)
    }
}

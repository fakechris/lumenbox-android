package com.lumenopen.lumenbox.crypto

import java.security.GeneralSecurityException
import java.security.NoSuchAlgorithmException
import javax.crypto.Cipher
import javax.crypto.NoSuchPaddingException

/**
 * The one place the app obtains its ChaCha20-Poly1305 AEAD (RFC 8439: 32-byte key, 12-byte nonce,
 * AAD, 16-byte tag appended to the ciphertext). Used by the Noise transport and the offline cache.
 *
 * The JCA name differs by platform: OpenJDK registers `ChaCha20-Poly1305`; Android's Conscrypt
 * registers the transformation `ChaCha20/Poly1305/NoPadding` (API 28+) and, on newer releases, the
 * JDK name as well. The first name that resolves is remembered for the process (INV-2290).
 */
object ChaCha20Poly1305 {
    const val KEY_ALGORITHM: String = "ChaCha20"
    const val KEY_BYTES: Int = 32
    const val NONCE_BYTES: Int = 12
    const val TAG_BYTES: Int = 16

    internal val CANDIDATE_NAMES: List<String> = listOf("ChaCha20-Poly1305", "ChaCha20/Poly1305/NoPadding")

    /** The JCA name that resolved on this platform. */
    val transformation: String by lazy { resolve() }

    /** A fresh, uninitialised cipher; callers `init` it with a [KEY_ALGORITHM] key and a nonce. */
    fun newCipher(): Cipher = Cipher.getInstance(transformation)

    private fun resolve(): String {
        val failures = ArrayList<String>()
        for (name in CANDIDATE_NAMES) {
            try {
                Cipher.getInstance(name)
                return name
            } catch (e: NoSuchAlgorithmException) {
                failures += "$name: ${e.message}"
            } catch (e: NoSuchPaddingException) {
                failures += "$name: ${e.message}"
            }
        }
        throw GeneralSecurityException(
            "No ChaCha20-Poly1305 provider on this platform; tried ${failures.joinToString("; ")}",
        )
    }
}

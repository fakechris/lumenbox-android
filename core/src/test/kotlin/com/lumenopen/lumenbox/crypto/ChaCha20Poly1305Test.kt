package com.lumenopen.lumenbox.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 8439 §2.8.2 AEAD test vector through the shared cipher: 12-byte nonce, AAD, and the 16-byte
 * tag appended. The same vector runs on the emulator (`app/src/androidTest`, `AeadOnDeviceTest`).
 */
class ChaCha20Poly1305Test {
    private val key = SecretKeySpec(ByteArray(32) { (0x80 + it).toByte() }, ChaCha20Poly1305.KEY_ALGORITHM)
    private val nonce = hex("070000004041424344454647")
    private val aad = hex("50515253c0c1c2c3c4c5c6c7")
    private val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for " +
        "the future, sunscreen would be it.").toByteArray(StandardCharsets.UTF_8)
    private val expected = hex(
        "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
            "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
            "3ff4def08e4b7a9de576d26586cec64b6116" + "1ae10b594f09e26a7e902ecbd0600691",
    )

    @Test
    fun `RFC 8439 AEAD vector seals and opens through the shared cipher`() {
        assertTrue(ChaCha20Poly1305.transformation in ChaCha20Poly1305.CANDIDATE_NAMES)
        val enc = ChaCha20Poly1305.newCipher()
        enc.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(nonce))
        enc.updateAAD(aad)
        val sealed = enc.doFinal(plaintext)
        assertEquals(plaintext.size + ChaCha20Poly1305.TAG_BYTES, sealed.size)
        assertArrayEquals(expected, sealed)

        val dec = ChaCha20Poly1305.newCipher()
        dec.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(nonce))
        dec.updateAAD(aad)
        assertArrayEquals(plaintext, dec.doFinal(sealed))

        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val bad = ChaCha20Poly1305.newCipher()
        bad.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(nonce))
        bad.updateAAD(aad)
        assertThrows(AEADBadTagException::class.java) { bad.doFinal(tampered) }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

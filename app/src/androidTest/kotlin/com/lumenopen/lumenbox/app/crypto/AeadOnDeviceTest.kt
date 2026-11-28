package com.lumenopen.lumenbox.app.crypto

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumenopen.lumenbox.crypto.ChaCha20Poly1305
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The shared AEAD on Android's provider: RFC 8439 §2.8.2 byte for byte (12-byte nonce, AAD, 16-byte
 * tag appended exactly as on the JDK — `ChaCha20Poly1305Test` runs the same vector on the JVM), a
 * round trip, and a tampered tag refused.
 */
@RunWith(AndroidJUnit4::class)
class AeadOnDeviceTest {
    private val key = SecretKeySpec(ByteArray(32) { (0x80 + it).toByte() }, ChaCha20Poly1305.KEY_ALGORITHM)
    private val nonce = hexToBytes("070000004041424344454647")
    private val aad = hexToBytes("50515253c0c1c2c3c4c5c6c7")
    private val plaintext = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for " +
        "the future, sunscreen would be it.").toByteArray(StandardCharsets.UTF_8)
    private val expectedHex =
        "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
            "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
            "3ff4def08e4b7a9de576d26586cec64b6116" + "1ae10b594f09e26a7e902ecbd0600691"

    @Test
    fun rfc8439VectorRoundTripAndTamperRefusal() {
        val enc = ChaCha20Poly1305.newCipher()
        Log.i("LumenBoxAead", "transformation=${ChaCha20Poly1305.transformation} provider=${enc.provider.name}")
        enc.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(nonce))
        enc.updateAAD(aad)
        val sealed = enc.doFinal(plaintext)
        assertEquals(expectedHex, bytesToHex(sealed))

        val dec = ChaCha20Poly1305.newCipher()
        dec.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(nonce))
        dec.updateAAD(aad)
        assertArrayEquals(plaintext, dec.doFinal(sealed))

        val tampered = sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val bad = ChaCha20Poly1305.newCipher()
        bad.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(nonce))
        bad.updateAAD(aad)
        assertThrows(AEADBadTagException::class.java) { bad.doFinal(tampered) }
    }
}

package com.lumenopen.lumenbox.relay

import com.lumenopen.lumenbox.conformance.NoiseConformance
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * X25519 and the Noise initiator against RFC 7748, the published cacophony vectors (public domain,
 * source commit in `noise-cacophony-vectors.json`) and the refusals the relay server tests. The
 * same checks run on the emulator (`app/src/androidTest`, `NoiseConformanceOnDeviceTest`).
 */
class NoiseConformanceTest {
    private fun resource(name: String) = javaClass.classLoader.getResource(name)!!.readText()

    @Test
    fun `RFC 7748 X25519 vectors, iterated 1 and 1000 times, and the DH example`() {
        assertEquals(8, NoiseConformance.rfc7748())
    }

    @Test
    fun `cacophony NK and IK vectors byte for byte`() {
        assertEquals(
            mapOf(NoiseConformance.NK to 1, NoiseConformance.IK to 1),
            NoiseConformance.cacophony(resource("noise-cacophony-vectors.json")),
        )
    }

    @Test
    fun `refuses bad keys, wrong responder, tampered, replayed, reordered and forged records`() {
        assertEquals(46, NoiseConformance.refusals(resource("relay-test-vectors.json")))
    }

    @Test
    fun `the nonce space ends at 2^64 - 2 and a failed decryption does not advance the nonce`() {
        val key = ByteArray(32) { 7 }
        val send = NoiseCipherState(key)
        val recv = NoiseCipherState(key)
        send.setNonce(-2L) // 2^64 - 2, the last usable nonce (2^64 - 1 is reserved)
        recv.setNonce(-2L)
        val last = send.encryptWithAd(ByteArray(0), byteArrayOf(1, 2, 3))
        val forged = last.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertEquals("decryption_failed", assertThrows(NoiseRelayException::class.java) { recv.decryptWithAd(ByteArray(0), forged) }.code)
        assertArrayEquals(byteArrayOf(1, 2, 3), recv.decryptWithAd(ByteArray(0), last))
        assertEquals("nonce_exhausted", assertThrows(NoiseRelayException::class.java) { send.encryptWithAd(ByteArray(0), byteArrayOf(4)) }.code)
        assertEquals("nonce_exhausted", assertThrows(NoiseRelayException::class.java) { recv.decryptWithAd(ByteArray(0), last) }.code)
    }
}

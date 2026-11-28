package com.lumenopen.lumenbox.app.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lumenopen.lumenbox.relay.NoiseHandshakes
import com.lumenopen.lumenbox.relay.RecordFramer
import com.lumenopen.lumenbox.relay.X25519Keypair
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

/**
 * The Noise NK / IK handshakes and transport records, run through Android's own crypto providers
 * and checked byte for byte against the server's vectors (the same file `LumenBoxCoreTest` uses).
 */
@RunWith(AndroidJUnit4::class)
class NoiseOnDeviceTest {
    @Test
    fun noiseNkAndIkMatchServerVectorsByteForByte() {
        val v = relayVectors()
        val prologue = v.getString("prologueUtf8").toByteArray(StandardCharsets.UTF_8)
        val hostPub = hexToBytes(v.getString("hostPubHex"))
        val clientKp = X25519Keypair.fromPrivateSeed(hexToBytes(v.getString("clientPrivHex")))
        assertEquals(v.getString("clientPubHex"), bytesToHex(clientKp.publicKey))

        val (nkMsg1, nk) = NoiseHandshakes.startNkInitiator(
            responderStaticPublicKey = hostPub,
            prologue = prologue,
            payload = "pair-hello".toByteArray(StandardCharsets.UTF_8),
            ephemeralKeypair = X25519Keypair.fromPrivateSeed(hexToBytes(v.getString("nkClientEphPrivHex"))),
        )
        assertEquals(v.getString("nkMsg1Hex"), bytesToHex(nkMsg1))
        val (nkReply, _) = nk.completeMessage2(hexToBytes(v.getString("nkMsg2Hex")))
        assertEquals("pair-ok", nkReply.toString(StandardCharsets.UTF_8))

        val (ikMsg1, ik) = NoiseHandshakes.startIkInitiator(
            initiatorStaticKeypair = clientKp,
            responderStaticPublicKey = hostPub,
            prologue = prologue,
            payload = "hello-host".toByteArray(StandardCharsets.UTF_8),
            ephemeralKeypair = X25519Keypair.fromPrivateSeed(hexToBytes(v.getString("ikClientEphPrivHex"))),
        )
        assertEquals(v.getString("ikMsg1Hex"), bytesToHex(ikMsg1))
        val (ikReply, session) = ik.completeMessage2(hexToBytes(v.getString("ikMsg2Hex")))
        assertEquals("welcome-phone", ikReply.toString(StandardCharsets.UTF_8))

        val clientWire = RecordFramer.sealRecord(
            session.send,
            RecordFramer.KIND_TEXT,
            7L,
            "ping-over-noise-ik".toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals(v.getString("clientToHostWireRecordHex"), bytesToHex(clientWire))
        val fromHost = RecordFramer.openRecord(session.receive, hexToBytes(v.getString("hostToClientWireRecordHex")))
        assertEquals("pong-from-host", fromHost.payload.toString(StandardCharsets.UTF_8))
    }
}

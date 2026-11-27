package com.lumenopen.lumenbox

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.InboxDecisionItem
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.NotificationSummary
import com.lumenopen.lumenbox.contract.WakePayload
import com.lumenopen.lumenbox.push.DisplayedNotification
import com.lumenopen.lumenbox.push.LumenPushToken
import com.lumenopen.lumenbox.push.WakeNotificationCoordinator
import com.lumenopen.lumenbox.relay.NoiseCipherState
import com.lumenopen.lumenbox.relay.NoiseHandshakes
import com.lumenopen.lumenbox.relay.NoiseRelayException
import com.lumenopen.lumenbox.relay.ParsedPairingOffer
import com.lumenopen.lumenbox.relay.RecordFramer
import com.lumenopen.lumenbox.relay.X25519Curve
import com.lumenopen.lumenbox.relay.X25519Keypair
import com.lumenopen.lumenbox.storage.InMemoryKeyStoreVault
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.storage.PersonalInstallationAlreadyExistsException
import com.lumenopen.lumenbox.transport.DirectHttpsTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

@Serializable
private data class RelayVectors(
    val prologueUtf8: String,
    val hostPrivHex: String,
    val hostPubHex: String,
    val clientPrivHex: String,
    val clientPubHex: String,
    val nkClientEphPrivHex: String,
    val nkMsg1Hex: String,
    val nkMsg2Hex: String,
    val ikClientEphPrivHex: String,
    val ikMsg1Hex: String,
    val ikMsg2Hex: String,
    val clientToHostWireRecordHex: String,
    val hostToClientWireRecordHex: String,
)

private fun hexToBytes(hex: String): ByteArray {
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
    return out
}

private fun bytesToHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

class LumenBoxCoreTest {
    @Test
    fun `Noise_NK, Noise_IK, and RecordFramer match server test vectors byte-for-byte`() {
        val raw = javaClass.classLoader.getResource("relay-test-vectors.json")!!.readText()
        val vectors = LumenBoxJson.default.decodeFromString(RelayVectors.serializer(), raw)

        val hostPub = hexToBytes(vectors.hostPubHex)
        val clientKp = X25519Keypair.fromPrivateSeed(hexToBytes(vectors.clientPrivHex))
        assertEquals(vectors.clientPubHex, bytesToHex(clientKp.publicKey))

        // 1. Noise_NK pairing handshake
        val nkEph = X25519Keypair.fromPrivateSeed(hexToBytes(vectors.nkClientEphPrivHex))
        val (nkMsg1, nkState) = NoiseHandshakes.startNkInitiator(
            responderStaticPublicKey = hostPub,
            prologue = vectors.prologueUtf8.toByteArray(StandardCharsets.UTF_8),
            payload = "pair-hello".toByteArray(StandardCharsets.UTF_8),
            ephemeralKeypair = nkEph,
        )
        assertEquals(vectors.nkMsg1Hex, bytesToHex(nkMsg1))
        val (nkReply, _) = nkState.completeMessage2(hexToBytes(vectors.nkMsg2Hex))
        assertEquals("pair-ok", nkReply.toString(StandardCharsets.UTF_8))

        // 2. Noise_IK reconnect handshake
        val ikEph = X25519Keypair.fromPrivateSeed(hexToBytes(vectors.ikClientEphPrivHex))
        val (ikMsg1, ikState) = NoiseHandshakes.startIkInitiator(
            initiatorStaticKeypair = clientKp,
            responderStaticPublicKey = hostPub,
            prologue = vectors.prologueUtf8.toByteArray(StandardCharsets.UTF_8),
            payload = "hello-host".toByteArray(StandardCharsets.UTF_8),
            ephemeralKeypair = ikEph,
        )
        assertEquals(vectors.ikMsg1Hex, bytesToHex(ikMsg1))
        assertFalse(bytesToHex(ikMsg1).contains(vectors.clientPubHex))

        val (ikReply, session) = ikState.completeMessage2(hexToBytes(vectors.ikMsg2Hex))
        assertEquals("welcome-phone", ikReply.toString(StandardCharsets.UTF_8))

        // 3. Transport record framing in both directions
        val clientWire = RecordFramer.sealRecord(
            cipher = session.send,
            kind = RecordFramer.KIND_TEXT,
            streamId = 7L,
            payload = "ping-over-noise-ik".toByteArray(StandardCharsets.UTF_8),
        )
        assertEquals(vectors.clientToHostWireRecordHex, bytesToHex(clientWire))

        val fromHost = RecordFramer.openRecord(
            cipher = session.receive,
            wire = hexToBytes(vectors.hostToClientWireRecordHex),
        )
        assertEquals(RecordFramer.KIND_TEXT, fromHost.kind)
        assertEquals(7L, fromHost.streamId)
        assertEquals("pong-from-host", fromHost.payload.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `rejects low-order X25519 keys, query-string pairing secrets, and 4 GiB forged chunk headers`() {
        assertFalse(X25519Curve.isCanonicalPublicKey(ByteArray(32)))
        assertFalse(X25519Curve.isCanonicalPublicKey(ByteArray(32) { 0xff.toByte() }))

        assertThrows(NoiseRelayException::class.java) {
            ParsedPairingOffer.parse("lumenbox://pair?pairSecret=abc#hostPub=xyz")
        }

        val key = ByteArray(32) { 0x42 }
        val send = NoiseCipherState(key)
        val recv = NoiseCipherState(key)
        val large = ByteArray(150_000) { (it and 0xff).toByte() }
        val wire = RecordFramer.sealRecord(send, RecordFramer.KIND_BINARY, 99L, large)
        val opened = RecordFramer.openRecord(recv, wire)
        assertArrayEquals(large, opened.payload)

        // Forged 4 GiB totalLength inside 14-byte chunk fails fast before allocating
        val attackSend = NoiseCipherState(key)
        val attackRecv = NoiseCipherState(key)
        val forgedPlain = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN).apply {
            put(RecordFramer.KIND_BINARY.toByte())
            put(RecordFramer.FLAG_FIRST.toByte())
            putInt(-1) // 0xffff_ffff unsigned = 4 GiB
            putInt(1)
            put("tiny".toByteArray(StandardCharsets.UTF_8))
        }.array()
        val ct = attackSend.encryptWithAd(ByteArray(0), forgedPlain)
        val forgedWire = ByteBuffer.allocate(2 + ct.size).order(ByteOrder.BIG_ENDIAN).apply {
            putShort(ct.size.toShort())
            put(ct)
        }.array()
        assertThrows(NoiseRelayException::class.java) {
            RecordFramer.openRecord(attackRecv, forgedWire)
        }
    }

    @Test
    fun `forward-compatible decoders preserve unknown inbox kinds as Other`() {
        val json = """
            [
              {"id":"inb_1","kind":"tool_approval","summary":"Run deploy.sh","toolName":"bash","stepUpRequired":true,"unknownField":99},
              {"id":"inb_2","kind":"quantum_gate_2027","summary":"Approve circuit","nested":{"a":1}}
            ]
        """.trimIndent()
        val items = LumenBoxJson.default.decodeFromString(
            ListSerializer(InboxDecisionItem.serializer()),
            json
        )
        assertEquals(2, items.size)
        assertEquals(
            InboxDecisionItem.ToolApproval("inb_1", "Run deploy.sh", "bash", true),
            items[0]
        )
        assertEquals(
            InboxDecisionItem.Other("inb_2", "quantum_gate_2027", "Approve circuit"),
            items[1]
        )
    }

    @Test
    fun `multi-installation store, two-phase Android wake fallback, and 401 access-removed wipe`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-android-test").toFile()
        try {
            val store = InstallationStore(InMemoryKeyStoreVault(), tmpDir)
            store.addConnection(
                InstallationConnection(
                    id = "mac-home",
                    connId = "c_mac",
                    kind = InstallationConnection.Kind.PERSONAL_MAC,
                    displayName = "Studio Mac",
                    serverOriginOrRelayUrl = "wss://relay.lumenbox.com",
                    needsYouCount = 1,
                )
            )
            assertThrows(PersonalInstallationAlreadyExistsException::class.java) {
                store.addConnection(
                    InstallationConnection(
                        id = "mac-2",
                        connId = "c_mac2",
                        kind = InstallationConnection.Kind.PERSONAL_MAC,
                        displayName = "Second Mac",
                        serverOriginOrRelayUrl = "wss://relay.lumenbox.com",
                    )
                )
            }

            store.addConnection(
                InstallationConnection(
                    id = "org-acme",
                    connId = "c_acme",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Acme Corp",
                    serverOriginOrRelayUrl = "https://lumenbox.acme.example",
                    needsYouCount = 4,
                    redactLockScreenNotifications = false,
                )
            )
            store.saveRefreshToken("org-acme", "rt_acme_secret")
            assertEquals(2, store.topLevelNeedsYouProjection().size)
            assertTrue(LumenPushToken.isValid("LumenPushToken[AbCdEfGhIjKlMnOpQrSt12]"))

            // Two-phase Android wake coordinator posts generic fallback BEFORE network fetch, then updates!
            val posted = mutableListOf<DisplayedNotification>()
            val coordinator = WakeNotificationCoordinator(
                store = store,
                transportForConnection = { conn ->
                    DirectHttpsTransport(
                        baseOrigin = conn.serverOriginOrRelayUrl,
                        connectionId = conn.id,
                        store = store,
                        accessTokenInMemory = "at_123",
                    ) { _, _ ->
                        // Assert generic fallback is ALREADY visible while network call is in flight!
                        assertEquals(1, posted.size)
                        assertTrue(posted.first().isGenericFallback)
                        val body = LumenBoxJson.default.encodeToString(
                            NotificationSummary.serializer(),
                            NotificationSummary(
                                seq = 9L,
                                kind = "inbox",
                                title = "Approval Requested",
                                body = "Deploy v0.5.0 to staging",
                                createdAt = "2026-11-27T12:00:00Z",
                            )
                        ).toByteArray(StandardCharsets.UTF_8)
                        TransportResponse(200, emptyMap(), body)
                    }
                },
                postOrUpdateNotification = { posted.add(it) },
            )

            val finalBanner = coordinator.handleWakeMessage(WakePayload(connId = "c_acme", seq = 9L))
            assertEquals(2, posted.size)
            assertEquals("Approval Requested", finalBanner.title)
            assertEquals("Deploy v0.5.0 to staging", finalBanner.body)
            assertFalse(finalBanner.isGenericFallback)

            // 401 access-removed immediately wipes org-acme while leaving mac-home intact
            val revokedTransport = DirectHttpsTransport(
                baseOrigin = "https://lumenbox.acme.example",
                connectionId = "org-acme",
                store = store,
                accessTokenInMemory = "at_revoked",
            ) { _, _ ->
                TransportResponse(
                    401,
                    emptyMap(),
                    """{"code":"access-removed","error":"Member left directory"}""".toByteArray(StandardCharsets.UTF_8)
                )
            }
            assertThrows(ApiEnvelopeException::class.java) {
                kotlinx.coroutines.runBlocking {
                    revokedTransport.send(TransportRequest("GET", "/api/v1/inbox"))
                }
            }
            assertNull(store.findById("org-acme"))
            assertNull(store.readRefreshToken("org-acme"))
            assertTrue(store.findById("mac-home") != null)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

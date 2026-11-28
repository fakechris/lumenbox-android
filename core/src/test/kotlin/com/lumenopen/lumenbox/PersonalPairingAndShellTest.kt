package com.lumenopen.lumenbox

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.lumenopen.lumenbox.contract.InboxDecisionItem
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.pairing.PairingQrDecoder
import com.lumenopen.lumenbox.pairing.QrScanOutcome
import com.lumenopen.lumenbox.relay.HostMuxHandlers
import com.lumenopen.lumenbox.relay.HostSleepingInfo
import com.lumenopen.lumenbox.relay.NoiseCipherState
import com.lumenopen.lumenbox.relay.NoiseRelayException
import com.lumenopen.lumenbox.relay.NoiseSymmetricState
import com.lumenopen.lumenbox.relay.NoiseTransportSession
import com.lumenopen.lumenbox.relay.RelayMux
import com.lumenopen.lumenbox.relay.RelayWebSocket
import com.lumenopen.lumenbox.relay.RelayWebSocketFactory
import com.lumenopen.lumenbox.relay.TransportPathMode
import com.lumenopen.lumenbox.relay.VirtualHttpRequest
import com.lumenopen.lumenbox.relay.VirtualHttpResponse
import com.lumenopen.lumenbox.relay.WebSocketRelayConnector
import com.lumenopen.lumenbox.relay.X25519Curve
import com.lumenopen.lumenbox.relay.X25519Keypair
import com.lumenopen.lumenbox.session.PersonalMacSessionCoordinator
import com.lumenopen.lumenbox.session.PersonalMacSessionState
import com.lumenopen.lumenbox.storage.InMemoryKeyStoreVault
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationPresence
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.storage.PersonalInstallationAlreadyExistsException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class PersonalPairingAndShellTest {
    private val b64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64Decoder = Base64.getUrlDecoder()

    @Test
    fun `PairingQrDecoder decodes padded CameraX Y-plane QR and rejects query leaks and expired codes`() {
        val hostKp = X25519Keypair.generate()
        val pairSecret = ByteArray(32) { (0x10 + it).toByte() }
        val nowMillis = 1_795_800_000_000L
        val validExpSec = (nowMillis / 1_000L) + 120L

        val validUri = "lumenbox://pair#hostPub=${b64Encoder.encodeToString(hostKp.publicKey)}" +
            "&relay=wss%3A%2F%2Frelay.lumenbox.com" +
            "&route=rt_cam_1" +
            "&pairSecret=${b64Encoder.encodeToString(pairSecret)}" +
            "&exp=$validExpSec"

        // Render synthetic QR into a padded Y-plane (rowStride = width + 24, mimicking CameraX alignment)
        val width = 360
        val height = 360
        val rowStride = width + 24
        val matrix = QRCodeWriter().encode(
            validUri,
            BarcodeFormat.QR_CODE,
            width,
            height,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val paddedY = ByteArray(rowStride * height) { 0xff.toByte() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                paddedY[y * rowStride + x] = if (matrix.get(x, y)) 0x10.toByte() else 0xf0.toByte()
            }
        }

        val decoded = PairingQrDecoder.decodeYPlane(
            yPlane = paddedY,
            width = width,
            height = height,
            rowStride = rowStride,
            nowEpochMillis = nowMillis,
        )
        assertTrue(decoded is QrScanOutcome.ValidOffer)
        val offer = (decoded as QrScanOutcome.ValidOffer).offer
        assertEquals("rt_cam_1", offer.routeId)

        // Same QR scanned after expiration is rejected with expired_pairing_offer
        val expired = PairingQrDecoder.decodeYPlane(
            yPlane = paddedY,
            width = width,
            height = height,
            rowStride = rowStride,
            nowEpochMillis = nowMillis + 180_000L,
        )
        assertTrue(expired is QrScanOutcome.RejectedOffer)
        assertEquals("expired_pairing_offer", (expired as QrScanOutcome.RejectedOffer).code)

        // Query-string leak is strictly rejected
        val queryLeak = PairingQrDecoder.evaluateDecodedText(
            "lumenbox://pair?pairSecret=${b64Encoder.encodeToString(pairSecret)}#hostPub=${b64Encoder.encodeToString(hostKp.publicKey)}"
        )
        assertTrue(queryLeak is QrScanOutcome.RejectedOffer)
        assertEquals("invalid_pairing_uri", (queryLeak as QrScanOutcome.RejectedOffer).code)

        // Unrelated website QR is ignored without surfacing scary errors
        val unrelated = PairingQrDecoder.evaluateDecodedText("https://example.org/welcome")
        assertTrue(unrelated is QrScanOutcome.IgnoredNonLumenBox)

        // Verify 200 consecutive random X25519 keypairs decode cleanly on 280x280 Y-plane across binarizer & pure-grid fallbacks (INV-2300)
        repeat(200) { round ->
            val rndKp = X25519Keypair.generate()
            val rndUri = "lumenbox://pair#hostPub=${b64Encoder.encodeToString(rndKp.publicKey)}" +
                "&relay=wss%3A%2F%2Frelay.lumenbox.com&route=rt_rnd_$round" +
                "&pairSecret=${b64Encoder.encodeToString(pairSecret)}&exp=$validExpSec"
            val smallMatrix = QRCodeWriter().encode(rndUri, BarcodeFormat.QR_CODE, 280, 280, mapOf(EncodeHintType.MARGIN to 2))
            val smallStride = 304
            val smallY = ByteArray(smallStride * 280) { 0xff.toByte() }
            for (y in 0 until 280) {
                for (x in 0 until 280) {
                    smallY[y * smallStride + x] = if (smallMatrix.get(x, y)) 0x10.toByte() else 0xf0.toByte()
                }
            }
            val rndOutcome = PairingQrDecoder.decodeYPlane(smallY, 280, 280, smallStride, nowMillis)
            assertTrue(rndOutcome is QrScanOutcome.ValidOffer, "Round $round failed to decode high-density QR")
        }
    }

    @Test
    fun `PersonalMacSessionCoordinator pairs via Noise_NK, switches to Noise_IK, handles host_sleeping, persists across restart, and guards duplicate Mac`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2254-test").toFile()
        try {
            val sharedVault = InMemoryKeyStoreVault()
            val store = InstallationStore(sharedVault, tmpDir)

            // Add an organisation installation to verify top-level multi-installation switcher isolation
            store.addConnection(
                InstallationConnection(
                    id = "org-alpha",
                    connId = "c_org_alpha",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Alpha Enterprise",
                    serverOriginOrRelayUrl = "https://alpha.lumenbox.example",
                    needsYouCount = 3,
                )
            )

            val hostStatic = X25519Keypair.generate()
            val pairSecret = ByteArray(32) { (0x33 + it).toByte() }
            val pairHandshakesSeen = AtomicInteger(0)
            val registeredDevices = LinkedHashMap<String, ByteArray>()
            var latestHostMux: RelayMux? = null

            val fakeHostSocketFactory = RelayWebSocketFactory { _, _ ->
                InMemoryMacHostWebSocket(
                    hostStatic = hostStatic,
                    expectedPairSecret = pairSecret,
                    pairHandshakesSeen = pairHandshakesSeen,
                    registeredDevices = registeredDevices,
                    onHostMuxReady = { latestHostMux = it },
                )
            }

            val connector = WebSocketRelayConnector(socketFactory = fakeHostSocketFactory)
            var now = Instant.parse("2026-11-27T20:00:00Z")
            val coordinator = PersonalMacSessionCoordinator(
                store = store,
                connector = connector,
                clock = { now },
            )

            val qrUri = "lumenbox://pair#hostPub=${b64Encoder.encodeToString(hostStatic.publicKey)}" +
                "&relay=wss%3A%2F%2Frelay.lumenbox.com" +
                "&route=rt_home_mac" +
                "&pairSecret=${b64Encoder.encodeToString(pairSecret)}"

            // 1. Pair via Noise_NK and automatically establish Noise_IK session
            coordinator.pairAndConnect(
                qrUri = qrUri,
                displayName = "Studio MacBook Pro",
                deviceName = "Pixel 9 Pro",
            )
            assertEquals(1, pairHandshakesSeen.get())
            val onlineState = coordinator.state.value
            assertTrue(onlineState is PersonalMacSessionState.Online)
            assertEquals(TransportPathMode.RELAY, (onlineState as PersonalMacSessionState.Online).mode)
            assertEquals("dev_1", onlineState.deviceId)

            // 2. Cache Needs You decisions on the Personal Mac installation and verify top-level projection isolation
            coordinator.cacheInboxItems(
                listOf(
                    InboxDecisionItem.ToolApproval(
                        id = "inb_mac_1",
                        summary = "Allow git push to origin",
                        toolName = "bash",
                        stepUpRequired = true,
                    ),
                    InboxDecisionItem.Question(
                        id = "inb_mac_2",
                        summary = "Target staging or prod?",
                        options = listOf("staging", "prod"),
                    ),
                )
            )
            val projections = store.topLevelNeedsYouProjection()
            assertEquals(2, projections.size)
            assertEquals("Studio MacBook Pro", projections[0].displayName)
            assertEquals(2, projections[0].needsYouCount)
            assertEquals(InstallationPresence.OnlineRelay, projections[0].presence)
            assertEquals("Alpha Enterprise", projections[1].displayName)
            assertEquals(3, projections[1].needsYouCount)

            // 3. Attempting to pair a second Personal Mac is blocked BEFORE consuming the new QR's pairSecret on the network!
            val secondQr = "lumenbox://pair#hostPub=${b64Encoder.encodeToString(hostStatic.publicKey)}" +
                "&relay=wss%3A%2F%2Frelay.lumenbox.com&route=rt_2&pairSecret=${b64Encoder.encodeToString(pairSecret)}"
            assertThrows(PersonalInstallationAlreadyExistsException::class.java) {
                kotlinx.coroutines.runBlocking {
                    coordinator.pairAndConnect(secondQr, "Second Mac", "Pixel 9 Pro")
                }
            }
            assertEquals(1, pairHandshakesSeen.get())

            // 4. Refresh & revoke another paired device over Noise_IK virtual /api/v1/devices
            val devices = coordinator.refreshPairedDevices()
            assertEquals(2, devices.size)
            assertTrue(devices.any { it.id == "dev_1" && it.isCurrentDevice })
            assertTrue(devices.any { it.id == "dev_ipad_2" && !it.isCurrentDevice })
            assertTrue(coordinator.revokePairedDevice("dev_ipad_2"))

            // 5. Mac lid closes and sends authenticated host_sleeping control frame -> immediate banner state!
            val sleepAt = "2026-11-27T20:15:30Z"
            latestHostMux!!.notifyHostSleeping(reason = "lid-close", at = sleepAt)

            val sleepingState = coordinator.state.value
            assertTrue(sleepingState is PersonalMacSessionState.HostSleeping)
            assertEquals(sleepAt, (sleepingState as PersonalMacSessionState.HostSleeping).lastSeenAt)
            assertEquals("lid-close", sleepingState.reason)
            assertEquals(
                InstallationPresence.HostSleeping(sleepAt, "lid-close"),
                store.topLevelNeedsYouProjection()[0].presence,
            )

            // Local encrypted offline cache remains readable while Mac is asleep!
            val offlineInbox = coordinator.readCachedInboxItems()
            assertEquals(2, offlineInbox.size)
            assertEquals("Allow git push to origin", offlineInbox[0].summary)

            // 6. Cold-restart persistence: re-opening InstallationStore + Coordinator restores connections & HostSleeping banner
            coordinator.closeActiveSession()
            val reloadedStore = InstallationStore(sharedVault, tmpDir)
            val reloadedCoordinator = PersonalMacSessionCoordinator(reloadedStore, connector, clock = { now })
            val restoredState = reloadedCoordinator.state.value
            assertTrue(restoredState is PersonalMacSessionState.HostSleeping)
            assertEquals(sleepAt, (restoredState as PersonalMacSessionState.HostSleeping).lastSeenAt)
            assertEquals(2, reloadedCoordinator.readCachedInboxItems().size)

            // 7. Unpair local Mac wipes only Personal Mac, leaving Alpha Enterprise untouched
            reloadedCoordinator.unpairLocalMac()
            assertEquals(PersonalMacSessionState.Unpaired, reloadedCoordinator.state.value)
            assertFalse(reloadedStore.hasPersonalInstallation())
            assertEquals(1, reloadedStore.topLevelNeedsYouProjection().size)
            assertEquals("Alpha Enterprise", reloadedStore.topLevelNeedsYouProjection()[0].displayName)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    /**
     * In-memory simulated relay + Personal Mac responder implementing the exact `Noise_NK` pairing
     * and `Noise_IK` session protocol (`src/mobile-relay/connectors.ts`).
     */
    private inner class InMemoryMacHostWebSocket(
        private val hostStatic: X25519Keypair,
        private val expectedPairSecret: ByteArray,
        private val pairHandshakesSeen: AtomicInteger,
        private val registeredDevices: MutableMap<String, ByteArray>,
        private val onHostMuxReady: (RelayMux) -> Unit,
    ) : RelayWebSocket {
        private val textListeners = CopyOnWriteArrayList<(String) -> Unit>()
        private val binaryListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
        private val closeListeners = CopyOnWriteArrayList<(Int, String) -> Unit>()
        private var hostMux: RelayMux? = null
        private var closed = false

        override fun sendText(text: String) {
            if (closed) return
            val obj = LumenBoxJson.default.parseToJsonElement(text).jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "client_connect" -> {
                    emitText("""{"type":"client_connected"}""")
                }
                "pair_hello" -> {
                    pairHandshakesSeen.incrementAndGet()
                    val msg1 = b64Decoder.decode(obj["message"]!!.jsonPrimitive.content)
                    val sym = NoiseSymmetricState("Noise_NK_25519_ChaChaPoly_SHA256")
                    sym.mixHash("lumenbox-relay/1".toByteArray(StandardCharsets.UTF_8))
                    sym.mixHash(hostStatic.publicKey)
                    val clientEphPub = msg1.copyOfRange(0, 32)
                    sym.mixHash(clientEphPub)
                    sym.mixKey(X25519Curve.diffieHellman(hostStatic.privateKey, clientEphPub))
                    val reqPlain = sym.decryptAndHash(msg1.copyOfRange(32, msg1.size))
                    val reqObj = LumenBoxJson.default.parseToJsonElement(String(reqPlain, StandardCharsets.UTF_8)).jsonObject
                    val gotSecret = b64Decoder.decode(reqObj["pairSecret"]!!.jsonPrimitive.content)
                    check(gotSecret.contentEquals(expectedPairSecret)) { "Invalid pairSecret" }
                    val deviceStaticPub = b64Decoder.decode(reqObj["deviceStaticPub"]!!.jsonPrimitive.content)
                    val deviceId = "dev_${registeredDevices.size + 1}"
                    registeredDevices[deviceId] = deviceStaticPub

                    val hostEph = X25519Keypair.generate()
                    sym.mixHash(hostEph.publicKey)
                    sym.mixKey(X25519Curve.diffieHellman(hostEph.privateKey, clientEphPub))
                    val replyJson = buildJsonObject {
                        put("deviceId", deviceId)
                        put("connId", "conn_mac_1")
                        put("scopes", JsonArray(listOf(JsonPrimitive("work:read"), JsonPrimitive("work:decide"), JsonPrimitive("devices:manage"))))
                    }.toString().toByteArray(StandardCharsets.UTF_8)
                    val encReply = sym.encryptAndHash(replyJson)
                    val msg2 = hostEph.publicKey + encReply
                    emitText(
                        buildJsonObject {
                            put("type", "pair_ready")
                            put("message", b64Encoder.encodeToString(msg2))
                        }.toString()
                    )
                }
                "ik_hello" -> {
                    val msg1 = b64Decoder.decode(obj["message"]!!.jsonPrimitive.content)
                    val sym = NoiseSymmetricState("Noise_IK_25519_ChaChaPoly_SHA256")
                    sym.mixHash("lumenbox-relay/1".toByteArray(StandardCharsets.UTF_8))
                    sym.mixHash(hostStatic.publicKey)
                    val clientEphPub = msg1.copyOfRange(0, 32)
                    sym.mixHash(clientEphPub)
                    sym.mixKey(X25519Curve.diffieHellman(hostStatic.privateKey, clientEphPub))
                    val clientStaticPub = sym.decryptAndHash(msg1.copyOfRange(32, 80))
                    sym.mixKey(X25519Curve.diffieHellman(hostStatic.privateKey, clientStaticPub))
                    sym.decryptAndHash(msg1.copyOfRange(80, msg1.size))

                    val matchedId = registeredDevices.entries.firstOrNull { it.value.contentEquals(clientStaticPub) }?.key
                        ?: error("Unknown device public key")

                    val hostEph = X25519Keypair.generate()
                    sym.mixHash(hostEph.publicKey)
                    sym.mixKey(X25519Curve.diffieHellman(hostEph.privateKey, clientEphPub))
                    sym.mixKey(X25519Curve.diffieHellman(hostEph.privateKey, clientStaticPub))
                    val welcome = buildJsonObject {
                        put("deviceId", matchedId)
                        put("scopes", JsonArray(listOf(JsonPrimitive("work:read"), JsonPrimitive("devices:manage"))))
                    }.toString().toByteArray(StandardCharsets.UTF_8)
                    val encWelcome = sym.encryptAndHash(welcome)
                    val initiatorSplit = sym.splitInitiator()
                    // Host send/receive are the swapped initiator receive/send!
                    val hostNoiseSession = NoiseTransportSession(
                        send = initiatorSplit.receive,
                        receive = initiatorSplit.send,
                    )
                    val mux = RelayMux(
                        role = RelayMux.Role.HOST,
                        session = hostNoiseSession,
                        sendWire = { wire -> binaryListeners.forEach { it(wire) } },
                        deviceId = matchedId,
                        handlers = object : HostMuxHandlers {
                            override suspend fun onHttpRequest(req: VirtualHttpRequest, deviceId: String?): VirtualHttpResponse {
                                return when {
                                    req.method == "GET" && req.path == "/api/v1/devices" -> {
                                        val body = """
                                            {
                                              "items": [
                                                {"id":"dev_1","name":"Pixel 9 Pro","platform":"android","lastSeenAt":"2026-11-27T20:00:00Z"},
                                                {"id":"dev_ipad_2","name":"iPad mini","platform":"ios","lastSeenAt":"2026-11-27T18:00:00Z"}
                                              ]
                                            }
                                        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                                        VirtualHttpResponse(200, mapOf("content-type" to "application/json"), body)
                                    }
                                    req.method == "DELETE" && req.path.startsWith("/api/v1/devices/") -> {
                                        VirtualHttpResponse(200, mapOf("content-type" to "application/json"), """{"removed":true}""".toByteArray(StandardCharsets.UTF_8))
                                    }
                                    else -> VirtualHttpResponse(404, emptyMap(), ByteArray(0))
                                }
                            }
                        },
                    )
                    hostMux = mux
                    onHostMuxReady(mux)
                    emitText(
                        buildJsonObject {
                            put("type", "ik_ready")
                            put("message", b64Encoder.encodeToString(hostEph.publicKey + encWelcome))
                        }.toString()
                    )
                }
            }
        }

        override fun sendBinary(bytes: ByteArray) {
            if (!closed) {
                hostMux?.receiveWire(bytes)
            }
        }

        override fun onText(listener: (String) -> Unit) {
            textListeners.add(listener)
        }

        override fun onBinary(listener: (ByteArray) -> Unit) {
            binaryListeners.add(listener)
        }

        override fun onClose(listener: (code: Int, reason: String) -> Unit) {
            closeListeners.add(listener)
        }

        override fun close(code: Int, reason: String) {
            if (!closed) {
                closed = true
                closeListeners.forEach { it(code, reason) }
            }
        }

        private fun emitText(text: String) {
            textListeners.forEach { it(text) }
        }
    }
}

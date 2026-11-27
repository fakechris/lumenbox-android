package com.lumenopen.lumenbox

import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.desktop.DesktopBinaryStreamOpener
import com.lumenopen.lumenbox.desktop.DesktopViewerSessionState
import com.lumenopen.lumenbox.desktop.ReadOnlyDesktopSessionCoordinator
import com.lumenopen.lumenbox.desktop.ReadOnlyRfb38Decoder
import com.lumenopen.lumenbox.desktop.RfbFrameSnapshot
import com.lumenopen.lumenbox.desktop.RfbProtocolException
import com.lumenopen.lumenbox.generated.models.GeneratedDesktopTicketRequest
import com.lumenopen.lumenbox.relay.DuplexRelayStream
import com.lumenopen.lumenbox.relay.HostMuxHandlers
import com.lumenopen.lumenbox.relay.NoiseCipherState
import com.lumenopen.lumenbox.relay.NoiseTransportSession
import com.lumenopen.lumenbox.relay.RelayMux
import com.lumenopen.lumenbox.relay.RelayWebSocket
import com.lumenopen.lumenbox.relay.RelayWebSocketFactory
import com.lumenopen.lumenbox.relay.StreamTarget
import com.lumenopen.lumenbox.relay.VirtualHttpRequest
import com.lumenopen.lumenbox.relay.VirtualHttpResponse
import com.lumenopen.lumenbox.relay.VirtualStreamRequest
import com.lumenopen.lumenbox.storage.InMemoryKeyStoreVault
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.EncryptedRelayTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

class ReadOnlyDesktopVncTest {

    @Test
    fun `OpenAPI desktop ticket schema strictly locks Mode to VIEW with no control mode`() {
        val allowedModes = GeneratedDesktopTicketRequest.Mode.entries.map { it.value }
        assertEquals(listOf("view"), allowedModes)
        val encoded = LumenBoxJson.default.encodeToString(
            GeneratedDesktopTicketRequest.serializer(),
            GeneratedDesktopTicketRequest(mode = GeneratedDesktopTicketRequest.Mode.VIEW),
        )
        assertTrue(encoded.contains("\"mode\":\"view\""))
        assertFalse(encoded.contains("control"))
    }

    @Test
    fun `ReadOnlyRfb38Decoder decodes fragmented Raw, CopyRect, Bell, ServerCutText, and DesktopSize updates with zero input packets`() {
        val outboundPackets = mutableListOf<ByteArray>()
        val snapshots = mutableListOf<RfbFrameSnapshot>()

        val decoder = ReadOnlyRfb38Decoder(
            sendToServer = { outboundPackets.add(it.copyOf()) },
            onFrameUpdated = { snapshots.add(it) },
        )

        // 1. Feed fragmented server version banner "RFB 003.008\n"
        decoder.onServerBytes("RFB 00".toByteArray(StandardCharsets.US_ASCII))
        assertTrue(outboundPackets.isEmpty())
        decoder.onServerBytes("3.008\n".toByteArray(StandardCharsets.US_ASCII))
        assertEquals(1, outboundPackets.size)
        assertArrayEquals(ReadOnlyRfb38Decoder.RFB_3_8_VERSION_BANNER, outboundPackets[0])

        // 2. Security types: [count=2, VNCAuth=2, None=1] -> selects None(1)
        decoder.onServerBytes(byteArrayOf(2, 2, 1))
        assertEquals(2, outboundPackets.size)
        assertArrayEquals(byteArrayOf(1), outboundPackets[1])

        // 3. SecurityResult OK (0u32) -> sends ClientInit(shared=1)
        decoder.onServerBytes(byteArrayOf(0, 0, 0, 0))
        assertEquals(3, outboundPackets.size)
        assertArrayEquals(byteArrayOf(1), outboundPackets[2])

        // 4. ServerInit: 4x2 framebuffer, name "Coder Box"
        val serverInit = buildServerInitPacket(width = 4, height = 2, name = "Coder Box")
        // Feed 5 bytes at a time to exercise arbitrary TCP/WS fragmentation
        var offset = 0
        while (offset < serverInit.size) {
            val end = minOf(offset + 5, serverInit.size)
            decoder.onServerBytes(serverInit.copyOfRange(offset, end))
            offset = end
        }

        // Expect SetPixelFormat(type=0), SetEncodings(type=2), initial FramebufferUpdateRequest(type=3, inc=0)
        assertEquals(6, outboundPackets.size)
        assertEquals(0, outboundPackets[3][0].toInt())
        assertEquals(2, outboundPackets[4][0].toInt())
        assertEquals(3, outboundPackets[5][0].toInt())
        assertEquals(0, outboundPackets[5][1].toInt()) // non-incremental initial full frame

        // ServerInit configures dimensions/name; first snapshot emits upon FramebufferUpdate
        val initSnap = requireNotNull(decoder.currentSnapshot())
        assertEquals(4, initSnap.width)
        assertEquals(2, initSnap.height)
        assertEquals("Coder Box", initSnap.desktopName)
        assertTrue(snapshots.isEmpty())

        // 5. FramebufferUpdate with 2x2 Raw rectangle at (0, 0) + Bell(2) + ServerCutText(3)
        // Pixel format we requested: 32bpp little-endian, R shift=16, G shift=8, B shift=0 -> [B, G, R, 0]
        val rawUpdate = ByteArrayOutputStream().apply {
            // FramebufferUpdate header: type=0, pad=0, numRects=1
            write(byteArrayOf(0, 0, 0, 1))
            // Rect header: x=0, y=0, w=2, h=2, encoding=0 (Raw)
            write(rectHeader(x = 0, y = 0, w = 2, h = 2, encoding = ReadOnlyRfb38Decoder.ENCODING_RAW))
            // 4 pixels: Red, Green, Blue, White in little-endian [B, G, R, X]
            write(byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0x00)) // (0,0) = 0xFFFF0000
            write(byteArrayOf(0x00, 0xFF.toByte(), 0x00, 0x00)) // (1,0) = 0xFF00FF00
            write(byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x00)) // (0,1) = 0xFF0000FF
            write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00)) // (1,1) = 0xFFFFFFFF
            // Bell (type=2)
            write(byteArrayOf(2))
            // ServerCutText (type=3, 3 pad bytes, len=5, "hello") — must be ignored cleanly
            write(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 5))
            write("hello".toByteArray(StandardCharsets.ISO_8859_1))
        }.toByteArray()

        // Feed byte-by-byte to prove streaming state machine resilience
        for (b in rawUpdate) {
            decoder.onServerBytes(byteArrayOf(b))
        }

        assertEquals(1, snapshots.size)
        val afterRaw = snapshots.last()
        assertEquals(0xFFFF0000.toInt(), afterRaw.argbPixels[0]) // (0,0)
        assertEquals(0xFF00FF00.toInt(), afterRaw.argbPixels[1]) // (1,0)
        assertEquals(0xFF0000FF.toInt(), afterRaw.argbPixels[4]) // (0,1) in width=4
        assertEquals(0xFFFFFFFF.toInt(), afterRaw.argbPixels[5]) // (1,1) in width=4

        // Incremental FramebufferUpdateRequest(inc=1) was sent automatically
        assertEquals(7, outboundPackets.size)
        assertEquals(3, outboundPackets[6][0].toInt())
        assertEquals(1, outboundPackets[6][1].toInt())

        // 6. CopyRect (encoding=1): copy the 2x2 block from src=(0,0) to dst=(2,0)
        val copyRectUpdate = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1))
            write(rectHeader(x = 2, y = 0, w = 2, h = 2, encoding = ReadOnlyRfb38Decoder.ENCODING_COPY_RECT))
            // srcX=0, srcY=0
            write(byteArrayOf(0, 0, 0, 0))
        }.toByteArray()
        decoder.onServerBytes(copyRectUpdate)

        assertEquals(2, snapshots.size)
        val afterCopy = snapshots.last()
        assertEquals(0xFFFF0000.toInt(), afterCopy.argbPixels[2]) // (2,0) copied from (0,0)
        assertEquals(0xFF00FF00.toInt(), afterCopy.argbPixels[3]) // (3,0) copied from (1,0)
        assertEquals(0xFF0000FF.toInt(), afterCopy.argbPixels[6]) // (2,1) copied from (0,1)
        assertEquals(0xFFFFFFFF.toInt(), afterCopy.argbPixels[7]) // (3,1) copied from (1,1)

        // 7. DesktopSize pseudo-encoding (-223): resize to 6x3
        val resizeUpdate = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1))
            write(rectHeader(x = 0, y = 0, w = 6, h = 3, encoding = ReadOnlyRfb38Decoder.ENCODING_DESKTOP_SIZE))
        }.toByteArray()
        decoder.onServerBytes(resizeUpdate)

        assertEquals(3, snapshots.size)
        assertEquals(6, snapshots.last().width)
        assertEquals(3, snapshots.last().height)
        assertEquals(18, snapshots.last().argbPixels.size)

        // Zero KeyEvent(4), PointerEvent(5), or ClientCutText(6) packets ever emitted!
        assertZeroInputMessages(outboundPackets)
    }

    @Test
    fun `Personal Mac RelayMux VNC_RO session streams read-only frames over Noise_IK and closes cleanly`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2258-mux").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "personal-mac",
                    connId = "c_mac_vnc",
                    kind = InstallationConnection.Kind.PERSONAL_MAC,
                    displayName = "Personal Mac",
                    serverOriginOrRelayUrl = "wss://relay.lumenbox.example/ws",
                )
            )

            val keyClientToHost = ByteArray(32) { (it + 11).toByte() }
            val keyHostToClient = ByteArray(32) { (it + 77).toByte() }
            val phoneSession = NoiseTransportSession(
                send = NoiseCipherState(keyClientToHost),
                receive = NoiseCipherState(keyHostToClient),
            )
            val hostSession = NoiseTransportSession(
                send = NoiseCipherState(keyHostToClient),
                receive = NoiseCipherState(keyClientToHost),
            )

            val capturedTicketBodies = CopyOnWriteArrayList<String>()
            val capturedStreamTargets = CopyOnWriteArrayList<VirtualStreamRequest>()
            val capturedClientRfbBytes = CopyOnWriteArrayList<ByteArray>()
            var serverStreamClosed = false

            lateinit var clientMux: RelayMux
            lateinit var hostMux: RelayMux

            val hostHandlers = object : HostMuxHandlers {
                override suspend fun onHttpRequest(req: VirtualHttpRequest, deviceId: String?): VirtualHttpResponse {
                    return when {
                        req.method == "GET" && req.path == "/api/v1/agents" -> {
                            VirtualHttpResponse(
                                status = 200,
                                headers = mapOf("content-type" to "application/json"),
                                body = """{"items":[{"id":"agent-coder","name":"Coder","role":"engineer","status":"idle","hasDesktop":true}]}"""
                                    .toByteArray(StandardCharsets.UTF_8),
                            )
                        }
                        req.method == "POST" && req.path == "/api/v1/desktops/agent-coder/tickets" -> {
                            capturedTicketBodies.add(String(req.body ?: ByteArray(0), StandardCharsets.UTF_8))
                            VirtualHttpResponse(
                                status = 200,
                                headers = mapOf("content-type" to "application/json"),
                                body = """{"ticket":"tk_once_60s","path":"/vnc-ro/0/websockify?ticket=tk_once_60s","expiresInSeconds":60}"""
                                    .toByteArray(StandardCharsets.UTF_8),
                            )
                        }
                        else -> VirtualHttpResponse(404, emptyMap(), """{"error":"not found","code":"not_found"}""".toByteArray())
                    }
                }

                override suspend fun onOpenStream(
                    req: VirtualStreamRequest,
                    stream: DuplexRelayStream,
                    deviceId: String?,
                ) {
                    capturedStreamTargets.add(req)
                    var step = 0
                    stream.onClose { serverStreamClosed = true }
                    stream.onData { chunk, _ ->
                        capturedClientRfbBytes.add(chunk.copyOf())
                        when (step) {
                            0 -> {
                                // Received client RFB 003.008\n -> send SecurityTypes [1 (None)]
                                step = 1
                                CoroutineScope(Dispatchers.Unconfined).launch {
                                    stream.send(byteArrayOf(1, 1), binary = true)
                                }
                            }
                            1 -> {
                                // Received SecurityType=1 -> send SecurityResult=0
                                step = 2
                                CoroutineScope(Dispatchers.Unconfined).launch {
                                    stream.send(byteArrayOf(0, 0, 0, 0), binary = true)
                                }
                            }
                            2 -> {
                                // Received ClientInit(1) -> send ServerInit(2x1) + 1 Raw frame
                                step = 3
                                val initAndFrame = ByteArrayOutputStream().apply {
                                    write(buildServerInitPacket(width = 2, height = 1, name = "Mac Agent"))
                                    write(byteArrayOf(0, 0, 0, 1))
                                    write(rectHeader(x = 0, y = 0, w = 2, h = 1, encoding = ReadOnlyRfb38Decoder.ENCODING_RAW))
                                    write(byteArrayOf(0x12, 0x34, 0x56, 0x00)) // pixel 0: R=0x56, G=0x34, B=0x12
                                    write(byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x00)) // pixel 1
                                }.toByteArray()
                                CoroutineScope(Dispatchers.Unconfined).launch {
                                    stream.send(initAndFrame, binary = true)
                                }
                            }
                        }
                    }
                    // Kick off RFB 3.8 handshake from server
                    stream.send(ReadOnlyRfb38Decoder.RFB_3_8_VERSION_BANNER, binary = true)
                }
            }

            clientMux = RelayMux(
                role = RelayMux.Role.CLIENT,
                session = phoneSession,
                sendWire = { wire -> hostMux.receiveWire(wire) },
            )
            hostMux = RelayMux(
                role = RelayMux.Role.HOST,
                session = hostSession,
                sendWire = { wire -> clientMux.receiveWire(wire) },
                handlers = hostHandlers,
                deviceId = "dev_vnc_1",
            )

            val coordinator = ReadOnlyDesktopSessionCoordinator(
                connectionId = "personal-mac",
                store = store,
                transportProvider = { EncryptedRelayTransport.overMux(clientMux) },
                streamOpenerProvider = { DesktopBinaryStreamOpener.overRelayMux(muxProvider = { clientMux }, scope = this) },
            )

            val agents = coordinator.refreshAgents()
            assertEquals(1, agents.size)
            assertEquals("agent-coder", agents[0].id)

            coordinator.startWatching("agent-coder")
            // Let async control packet launches settle
            for (i in 0 until 20) {
                val st = coordinator.state.value
                if (st is DesktopViewerSessionState.WatchingReadOnly && st.snapshot.frameNumber >= 1L) break
                delay(15)
            }

            assertEquals(1, capturedTicketBodies.size)
            assertTrue(capturedTicketBodies[0].contains("\"mode\":\"view\""))
            assertEquals(1, capturedStreamTargets.size)
            assertEquals(StreamTarget.VNC_RO, capturedStreamTargets[0].target)
            assertEquals("/vnc-ro/0/websockify?ticket=tk_once_60s", capturedStreamTargets[0].path)

            val watching = coordinator.state.value as DesktopViewerSessionState.WatchingReadOnly
            assertEquals(2, watching.snapshot.width)
            assertEquals(1, watching.snapshot.height)
            assertEquals(0xFF563412.toInt(), watching.snapshot.argbPixels[0])
            assertEquals(0xFFEFCDAB.toInt(), watching.snapshot.argbPixels[1])

            assertZeroInputMessages(capturedClientRfbBytes)

            coordinator.disconnect("Backgrounded")
            delay(20)
            assertTrue(serverStreamClosed)
            assertTrue(coordinator.state.value is DesktopViewerSessionState.Disconnected)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `Organisation WSS session verifies single-use vnc-ro URL, rejects control vnc path, and emits zero input frames`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2258-org").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "org-hq",
                    connId = "c_org_hq",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Org HQ",
                    serverOriginOrRelayUrl = "https://hq.lumenbox.example",
                )
            )

            var nextTicketJson = """{"ticket":"tk_org_view","path":"/vnc-ro/2/websockify","expiresInSeconds":60}"""
            val fakeTransport = ApiTransport { req: TransportRequest ->
                when {
                    req.method == "POST" && req.path == "/api/v1/desktops/agent-ops/tickets" ->
                        TransportResponse(
                            statusCode = 200,
                            headers = mapOf("content-type" to "application/json"),
                            body = nextTicketJson.toByteArray(StandardCharsets.UTF_8),
                        )
                    else -> TransportResponse(404, emptyMap(), ByteArray(0))
                }
            }

            val connectedWsUrls = mutableListOf<String>()
            val wsOutboundBinary = mutableListOf<ByteArray>()
            var wsClosed = false
            var inboundCallback: ((ByteArray) -> Unit)? = null

            val fakeWsFactory = RelayWebSocketFactory { url, _ ->
                connectedWsUrls.add(url)
                object : RelayWebSocket {
                    override fun sendText(text: String) {
                        throw AssertionError("RFB WebSocket must never send text frames")
                    }

                    override fun sendBinary(bytes: ByteArray) {
                        wsOutboundBinary.add(bytes.copyOf())
                    }

                    override fun onText(listener: (String) -> Unit) = Unit
                    override fun onBinary(listener: (ByteArray) -> Unit) {
                        inboundCallback = listener
                    }

                    override fun onClose(listener: (code: Int, reason: String) -> Unit) = Unit
                    override fun close(code: Int, reason: String) {
                        wsClosed = true
                    }
                }
            }

            val coordinator = ReadOnlyDesktopSessionCoordinator(
                connectionId = "org-hq",
                store = store,
                transportProvider = { fakeTransport },
                streamOpenerProvider = {
                    DesktopBinaryStreamOpener.overWebSocket(
                        serverOriginProvider = { "https://hq.lumenbox.example" },
                        wsFactory = fakeWsFactory,
                    )
                },
            )

            coordinator.startWatching("agent-ops")
            assertEquals(listOf("wss://hq.lumenbox.example/vnc-ro/2/websockify?ticket=tk_org_view"), connectedWsUrls)

            val deliver = requireNotNull(inboundCallback)
            deliver(ReadOnlyRfb38Decoder.RFB_3_8_VERSION_BANNER)
            deliver(byteArrayOf(1, 1))
            deliver(byteArrayOf(0, 0, 0, 0))
            deliver(buildServerInitPacket(width = 1, height = 1, name = "Ops Desktop"))
            deliver(
                ByteArrayOutputStream().apply {
                    write(byteArrayOf(0, 0, 0, 1))
                    write(rectHeader(x = 0, y = 0, w = 1, h = 1, encoding = ReadOnlyRfb38Decoder.ENCODING_RAW))
                    write(byteArrayOf(0x20, 0x40, 0x60, 0x00))
                }.toByteArray()
            )

            val state = coordinator.state.value as DesktopViewerSessionState.WatchingReadOnly
            assertEquals("Ops Desktop", state.snapshot.desktopName)
            assertEquals(0xFF604020.toInt(), state.snapshot.argbPixels[0])
            assertZeroInputMessages(wsOutboundBinary)

            coordinator.disconnect()
            assertTrue(wsClosed)

            // Security check: if a buggy server ever returned `/vnc/2/websockify` (control endpoint), client MUST refuse!
            nextTicketJson = """{"ticket":"tk_bad","path":"/vnc/2/websockify","expiresInSeconds":60}"""
            assertThrows(RfbProtocolException::class.java) {
                kotlinx.coroutines.runBlocking {
                    coordinator.issueReadOnlyTicket("agent-ops")
                }
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun assertZeroInputMessages(packets: List<ByteArray>) {
        assertTrue(packets.isNotEmpty(), "Expected handshake/control packets")
        for (pkt in packets) {
            if (pkt.contentEquals(ReadOnlyRfb38Decoder.RFB_3_8_VERSION_BANNER)) continue
            if (pkt.size == 1 && pkt[0].toInt() == 1) continue // SecurityType=1 or ClientInit(shared=1)
            val msgType = pkt[0].toInt() and 0xFF
            assertTrue(
                msgType in setOf(0, 2, 3),
                "Forbidden outbound RFB message type $msgType (KeyEvent=4, PointerEvent=5, ClientCutText=6 are strictly banned)",
            )
        }
    }

    private fun buildServerInitPacket(width: Int, height: Int, name: String): ByteArray {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val buf = ByteBuffer.allocate(24 + nameBytes.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(width.toShort())
        buf.putShort(height.toShort())
        // Server default PixelFormat (16 bytes)
        buf.put(32) // bpp
        buf.put(24) // depth
        buf.put(0) // little-endian
        buf.put(1) // true-colour
        buf.putShort(255)
        buf.putShort(255)
        buf.putShort(255)
        buf.put(16)
        buf.put(8)
        buf.put(0)
        buf.put(byteArrayOf(0, 0, 0))
        buf.putInt(nameBytes.size)
        buf.put(nameBytes)
        return buf.array()
    }

    private fun rectHeader(x: Int, y: Int, w: Int, h: Int, encoding: Int): ByteArray {
        val buf = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(x.toShort())
        buf.putShort(y.toShort())
        buf.putShort(w.toShort())
        buf.putShort(h.toShort())
        buf.putInt(encoding)
        return buf.array()
    }
}

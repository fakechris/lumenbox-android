package com.lumenopen.lumenbox

import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.relay.DiscoveredLanEndpoint
import com.lumenopen.lumenbox.relay.DuplexRelayStream
import com.lumenopen.lumenbox.relay.HostMuxHandlers
import com.lumenopen.lumenbox.relay.HostSleepingInfo
import com.lumenopen.lumenbox.relay.LanDiscoveryProvider
import com.lumenopen.lumenbox.relay.NoiseCipherState
import com.lumenopen.lumenbox.relay.NoiseHandshakes
import com.lumenopen.lumenbox.relay.NoiseRelayException
import com.lumenopen.lumenbox.relay.NoiseSymmetricState
import com.lumenopen.lumenbox.relay.NoiseTransportSession
import com.lumenopen.lumenbox.relay.ParsedPairingOffer
import com.lumenopen.lumenbox.relay.RelayMux
import com.lumenopen.lumenbox.relay.StreamCloseOutcome
import com.lumenopen.lumenbox.relay.StreamTarget
import com.lumenopen.lumenbox.relay.TransportPathMode
import com.lumenopen.lumenbox.relay.VirtualHttpRequest
import com.lumenopen.lumenbox.relay.VirtualHttpResponse
import com.lumenopen.lumenbox.relay.VirtualStreamRequest
import com.lumenopen.lumenbox.relay.WebSocketRelayConnector
import com.lumenopen.lumenbox.relay.X25519Curve
import com.lumenopen.lumenbox.relay.X25519Keypair
import com.lumenopen.lumenbox.transport.EncryptedRelayTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Minimal RFC 6455 loopback WebSocket test server so `:core:test` exercises real `JdkRelayWebSocketFactory`
 * TCP sockets, Noise_NK pairing, Noise_IK reconnect, RelayMux flow-control credits, mDNS candidate fallback,
 * `host_sleeping`, and unauthenticated socket truncation!
 */
private class LoopbackWsServer(
    private val onConnection: (Conn) -> Unit,
) : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val url: String = "ws://127.0.0.1:${serverSocket.localPort}/ws"
    @Volatile
    private var running = true
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()

    init {
        thread(isDaemon = true, name = "loopback-ws-accept") {
            while (running) {
                val sock = try {
                    serverSocket.accept()
                } catch (_: Throwable) {
                    break
                }
                sockets.add(sock)
                thread(isDaemon = true, name = "loopback-ws-conn") {
                    handleSocket(sock)
                }
            }
        }
    }

    class Conn(
        private val socket: Socket,
        private val out: OutputStream,
    ) {
        private val writeLock = Any()
        var onText: ((String) -> Unit)? = null
        var onBinary: ((ByteArray) -> Unit)? = null

        fun sendText(text: String) = sendFrame(0x1, text.toByteArray(StandardCharsets.UTF_8))
        fun sendBinary(bytes: ByteArray) = sendFrame(0x2, bytes)

        /** Abruptly drops TCP without sending a Noise `stream_end` frame (simulates relay truncation). */
        fun dropAbruptly() {
            runCatching { socket.close() }
        }

        private fun sendFrame(opcode: Int, payload: ByteArray) {
            synchronized(writeLock) {
                val header = ByteArrayOutputStream()
                header.write(0x80 or opcode)
                when {
                    payload.size < 126 -> header.write(payload.size)
                    payload.size <= 65_535 -> {
                        header.write(126)
                        header.write((payload.size ushr 8) and 0xff)
                        header.write(payload.size and 0xff)
                    }
                    else -> {
                        header.write(127)
                        for (shift in 56 downTo 0 step 8) {
                            header.write(((payload.size.toLong() ushr shift) and 0xff).toInt())
                        }
                    }
                }
                out.write(header.toByteArray())
                out.write(payload)
                out.flush()
            }
        }
    }

    private fun handleSocket(sock: Socket) {
        try {
            val input = BufferedInputStream(sock.getInputStream())
            val output = sock.getOutputStream()
            val reqHeaders = StringBuilder()
            var prev = 0
            var state = 0
            while (true) {
                val b = input.read()
                if (b < 0) return
                reqHeaders.append(b.toChar())
                state = when {
                    state == 0 && b == '\r'.code -> 1
                    state == 1 && b == '\n'.code -> 2
                    state == 2 && b == '\r'.code -> 3
                    state == 3 && b == '\n'.code -> 4
                    else -> if (b == '\r'.code && prev != '\r'.code) 1 else 0
                }
                prev = b
                if (state == 4) break
            }
            val keyLine = reqHeaders.lineSequence()
                .firstOrNull { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                ?: return
            val clientKey = keyLine.substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((clientKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII))
            )
            output.write(
                (
                    "HTTP/1.1 101 Switching Protocols\r\n" +
                        "Upgrade: websocket\r\n" +
                        "Connection: Upgrade\r\n" +
                        "Sec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(StandardCharsets.US_ASCII)
            )
            output.flush()

            val conn = Conn(sock, output)
            onConnection(conn)

            while (!sock.isClosed) {
                val b0 = input.read()
                if (b0 < 0) break
                val b1 = input.read()
                if (b1 < 0) break
                val opcode = b0 and 0x0f
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7f).toLong()
                if (len == 126L) {
                    len = ((readExact(input, 2).let { ((it[0].toInt() and 0xff) shl 8) or (it[1].toInt() and 0xff) })).toLong()
                } else if (len == 127L) {
                    val lb = readExact(input, 8)
                    len = 0L
                    for (i in 0 until 8) len = (len shl 8) or (lb[i].toLong() and 0xffL)
                }
                val mask = if (masked) readExact(input, 4) else ByteArray(0)
                val payload = readExact(input, len.toInt())
                if (masked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }
                when (opcode) {
                    0x1 -> conn.onText?.invoke(payload.toString(StandardCharsets.UTF_8))
                    0x2 -> conn.onBinary?.invoke(payload)
                    0x8 -> break
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { sock.close() }
        }
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r < 0) throw IllegalStateException("Unexpected EOF")
            off += r
        }
        return buf
    }

    override fun close() {
        running = false
        runCatching { serverSocket.close() }
        sockets.forEach { runCatching { it.close() } }
    }
}

private fun respondNkForTest(
    hostKeypair: X25519Keypair,
    message1: ByteArray,
    replyPayload: (ByteArray) -> ByteArray,
): ByteArray {
    val sym = NoiseSymmetricState(NoiseHandshakes.NK_PROTOCOL_NAME)
    sym.mixHash(NoiseHandshakes.DEFAULT_PROLOGUE)
    sym.mixHash(hostKeypair.publicKey)
    val clientEph = message1.copyOfRange(0, 32)
    sym.mixHash(clientEph)
    sym.mixKey(X25519Curve.diffieHellman(hostKeypair.privateKey, clientEph))
    val clientPlain = sym.decryptAndHash(message1.copyOfRange(32, message1.size))
    val hostEph = X25519Keypair.generate()
    sym.mixHash(hostEph.publicKey)
    sym.mixKey(X25519Curve.diffieHellman(hostEph.privateKey, clientEph))
    val encReply = sym.encryptAndHash(replyPayload(clientPlain))
    return hostEph.publicKey + encReply
}

private fun respondIkForTest(
    hostKeypair: X25519Keypair,
    message1: ByteArray,
    admitClient: (ByteArray) -> ByteArray,
): Pair<ByteArray, NoiseTransportSession> {
    val sym = NoiseSymmetricState(NoiseHandshakes.IK_PROTOCOL_NAME)
    sym.mixHash(NoiseHandshakes.DEFAULT_PROLOGUE)
    sym.mixHash(hostKeypair.publicKey)
    val clientEph = message1.copyOfRange(0, 32)
    sym.mixHash(clientEph)
    sym.mixKey(X25519Curve.diffieHellman(hostKeypair.privateKey, clientEph))
    val clientStaticPub = sym.decryptAndHash(message1.copyOfRange(32, 80))
    sym.mixKey(X25519Curve.diffieHellman(hostKeypair.privateKey, clientStaticPub))
    sym.decryptAndHash(message1.copyOfRange(80, message1.size))
    val replyPlain = admitClient(clientStaticPub)
    val hostEph = X25519Keypair.generate()
    sym.mixHash(hostEph.publicKey)
    sym.mixKey(X25519Curve.diffieHellman(hostEph.privateKey, clientEph))
    sym.mixKey(X25519Curve.diffieHellman(hostEph.privateKey, clientStaticPub))
    val encReply = sym.encryptAndHash(replyPlain)
    // Host split is swapped relative to initiator: initiator send == responder receive!
    val initSplit = sym.splitInitiator()
    val hostSession = NoiseTransportSession(send = initSplit.receive, receive = initSplit.send)
    return (hostEph.publicKey + encReply) to hostSession
}

class RelayMuxE2ETest {
    private val b64Enc = Base64.getUrlEncoder().withoutPadding()
    private val b64Dec = Base64.getUrlDecoder()

    @Test
    fun `real WebSocket Noise_NK pairing, mDNS direct preference & relay fallback, flow-controlled RelayMux without blocking POST stop, host_sleeping, and unauthenticated EOF truncation`() =
        runBlocking {
            val hostKp = X25519Keypair.generate()
            val phoneKp = X25519Keypair.generate()
            val pairSecret = ByteArray(32) { (it + 1).toByte() }
            val admittedDevices = ConcurrentHashMap.newKeySet<String>()
            val latestHostMux = AtomicReference<RelayMux?>(null)

            fun makeHostHandlers(conn: LoopbackWsServer.Conn) = object : HostMuxHandlers {
                override suspend fun onHttpRequest(req: VirtualHttpRequest, deviceId: String?): VirtualHttpResponse {
                    if (req.method == "POST" && req.path == "/api/v1/conversations/c1/stop") {
                        return VirtualHttpResponse(
                            200,
                            mapOf("content-type" to "application/json"),
                            """{"stopped":true}""".toByteArray(StandardCharsets.UTF_8)
                        )
                    }
                    return VirtualHttpResponse(200, emptyMap(), """{"ok":true}""".toByteArray(StandardCharsets.UTF_8))
                }

                override suspend fun onOpenStream(
                    req: VirtualStreamRequest,
                    stream: DuplexRelayStream,
                    deviceId: String?,
                ) {
                    if (req.target == StreamTarget.VNC_RO && req.path == "/vnc-ro/large") {
                        // Send 96 KiB > client's 32 KiB initial window! Will pause until client acknowledges reads!
                        val bigFrame = ByteArray(96 * 1024) { 0x5a }
                        stream.send(bigFrame, binary = true)
                        stream.close()
                    } else if (req.path == "/vnc-ro/truncate") {
                        stream.send("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII), binary = true)
                        // Drop THIS connection's TCP socket WITHOUT sending authenticated stream_end!
                        conn.dropAbruptly()
                    }
                }
            }

            fun attachPeerLogic(conn: LoopbackWsServer.Conn, isRelay: Boolean) {
                val perConnHandlers = makeHostHandlers(conn)
                var mux: RelayMux? = null
                var registeredClient = !isRelay
                conn.onText = { text ->
                    val obj = LumenBoxJson.default.parseToJsonElement(text).jsonObject
                    val type = obj["type"]?.jsonPrimitive?.contentOrNull
                    if (!registeredClient && type == "client_connect") {
                        registeredClient = true
                        conn.sendText("""{"type":"client_connected","sessionId":"s_1"}""")
                    } else if (type == "pair_hello") {
                        val msg1 = b64Dec.decode(obj["message"]!!.jsonPrimitive.content)
                        val msg2 = respondNkForTest(hostKp, msg1) { plain ->
                            val req = LumenBoxJson.default.parseToJsonElement(plain.toString(StandardCharsets.UTF_8)).jsonObject
                            assertEquals(b64Enc.encodeToString(pairSecret), req["pairSecret"]!!.jsonPrimitive.content)
                            admittedDevices.add(req["deviceStaticPub"]!!.jsonPrimitive.content)
                            LumenBoxJson.default.encodeToString(
                                JsonObject.serializer(),
                                buildJsonObject {
                                    put("deviceId", "dev_pixel9")
                                    put("connId", "conn_mac")
                                    put("scopes", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("work:decide")) })
                                }
                            ).toByteArray(StandardCharsets.UTF_8)
                        }
                        conn.sendText("""{"type":"pair_ready","message":"${b64Enc.encodeToString(msg2)}"}""")
                    } else if (type == "ik_hello") {
                        val msg1 = b64Dec.decode(obj["message"]!!.jsonPrimitive.content)
                        val (msg2, hostSession) = respondIkForTest(hostKp, msg1) { clientPub ->
                            check(admittedDevices.contains(b64Enc.encodeToString(clientPub))) { "Device not paired" }
                            """{"deviceId":"dev_pixel9","scopes":["work:decide","chat:write"]}""".toByteArray(StandardCharsets.UTF_8)
                        }
                        val m = RelayMux(
                            role = RelayMux.Role.HOST,
                            session = hostSession,
                            sendWire = { wire -> conn.sendBinary(wire) },
                            handlers = perConnHandlers,
                            deviceId = "dev_pixel9",
                            initialWindowBytes = 32 * 1024,
                        )
                        mux = m
                        latestHostMux.set(m)
                        conn.sendText("""{"type":"ik_ready","message":"${b64Enc.encodeToString(msg2)}"}""")
                    }
                }
                conn.onBinary = { wire -> mux?.receiveWire(wire) }
            }

            LoopbackWsServer { attachPeerLogic(it, isRelay = true) }.use { relayServer ->
                LoopbackWsServer { attachPeerLogic(it, isRelay = false) }.use { lanServer ->
                    // 1. mDNS discovers reachable LAN endpoint -> prefers DIRECT over RELAY!
                    val mDnsConnector = WebSocketRelayConnector(
                        lanDiscovery = LanDiscoveryProvider { routeId, _ ->
                            listOf(DiscoveredLanEndpoint("MacStudio", routeId, lanServer.url))
                        }
                    )
                    val offerUri =
                        "lumenbox://pair#hostPub=${b64Enc.encodeToString(hostKp.publicKey)}" +
                            "&relay=${relayServer.url}&route=rt_home" +
                            "&pairSecret=${b64Enc.encodeToString(pairSecret)}"
                    val paired = try {
                        withTimeout(2_000) {
                            mDnsConnector.pairPhoneWithOffer(
                                offer = ParsedPairingOffer.parse(offerUri),
                                deviceKeypair = phoneKp,
                                deviceName = "Pixel 9 Pro",
                            )
                        }
                    } catch (e: Throwable) {
                        throw AssertionError("Timed out at Step 1 (pairPhoneWithOffer)", e)
                    }
                    assertEquals(TransportPathMode.DIRECT, paired.mode)
                    assertEquals("dev_pixel9", paired.deviceId)

                    // 2. When LAN endpoint is unreachable OR upgrades WS then drops before ik_ready, falls back to RELAY!
                    LoopbackWsServer { staleConn ->
                        staleConn.onText = { staleConn.dropAbruptly() }
                    }.use { staleLanServer ->
                        val sleepNotice = CompletableDeferred<HostSleepingInfo>()
                        val fallbackConnector = WebSocketRelayConnector(
                            lanDiscovery = LanDiscoveryProvider { routeId, _ ->
                                listOf(
                                    DiscoveredLanEndpoint("OfflineMac", routeId, "ws://127.0.0.1:1/unreachable"),
                                    DiscoveredLanEndpoint("StaleWsPort", routeId, staleLanServer.url),
                                )
                            }
                        )
                        val session = try {
                            withTimeout(3_000) {
                                fallbackConnector.connectPhoneIk(
                                    hostPublicKey = hostKp.publicKey,
                                    deviceKeypair = phoneKp,
                                    relayUrl = relayServer.url,
                                    routeId = "rt_home",
                                    initialWindowBytes = 32 * 1024,
                                    autoReplenishStreamWindow = false, // Hold window intentionally to prove flow control!
                                    onHostSleeping = { sleepNotice.complete(it) },
                                )
                            }
                        } catch (e: Throwable) {
                            throw AssertionError("Timed out at Step 2 (connectPhoneIk)", e)
                        }
                        assertEquals(TransportPathMode.RELAY, session.mode)

                        // 3. Flow-controlled stream stalls at 32 KiB window WITHOUT blocking concurrent POST /stop!
                        val vncStream = try {
                            withTimeout(2_000) {
                                session.mux.openStream(
                                    VirtualStreamRequest(StreamTarget.VNC_RO, "/vnc-ro/large")
                                )
                            }
                        } catch (e: Throwable) {
                            throw AssertionError("Timed out at Step 3a (openStream)", e)
                        }
                        val receivedStreamBytes = ByteArrayOutputStream()
                        val streamOutcome = CompletableDeferred<StreamCloseOutcome>()
                        vncStream.onData { chunk, _ -> synchronized(receivedStreamBytes) { receivedStreamBytes.write(chunk) } }
                        vncStream.onClose { streamOutcome.complete(it) }

                        // Wait briefly so the first 32 KiB window fills and pauses stream 1 on host
                        delay(80)
                        assertEquals(32 * 1024, synchronized(receivedStreamBytes) { receivedStreamBytes.size() })

                        // Concurrent POST /stop over ApiTransport.overMux completes immediately despite stalled stream 1!
                        val apiTransport = EncryptedRelayTransport.overMux(session.mux)
                        val stopRes = try {
                            withTimeout(2_000) {
                                val stopJob = async {
                                    apiTransport.send(TransportRequest("POST", "/api/v1/conversations/c1/stop"))
                                }
                                stopJob.await()
                            }
                        } catch (e: Throwable) {
                            throw AssertionError("Timed out at Step 3b (concurrent POST /stop)", e)
                        }
                        assertEquals(200, stopRes.statusCode)
                        assertTrue(stopRes.body.toString(StandardCharsets.UTF_8).contains("stopped"))

                        // Replenish window credit -> remaining 64 KiB arrives and closes with AuthenticatedEnd!
                        vncStream.acknowledgeRead(64 * 1024)
                        val s3Outcome = try {
                            withTimeout(2_000) { streamOutcome.await() }
                        } catch (e: Throwable) {
                            throw AssertionError("Timed out at Step 3c (streamOutcome after acknowledgeRead, got=${synchronized(receivedStreamBytes) { receivedStreamBytes.size() }})", e)
                        }
                        assertEquals(StreamCloseOutcome.AuthenticatedEnd, s3Outcome)
                        assertEquals(96 * 1024, synchronized(receivedStreamBytes) { receivedStreamBytes.size() })
                        assertArrayEquals(ByteArray(96 * 1024) { 0x5a }, receivedStreamBytes.toByteArray())

                        // 4. Host pre-sleep notice arrives immediately over control stream
                        latestHostMux.get()!!.notifyHostSleeping(reason = "clamshell", at = "2026-11-27T13:50:00Z")
                        val sleepInfo = try {
                            withTimeout(2_000) { sleepNotice.await() }
                        } catch (e: Throwable) {
                            throw AssertionError("Timed out at Step 4 (host_sleeping)", e)
                        }
                        assertEquals(HostSleepingInfo("2026-11-27T13:50:00Z", "clamshell"), sleepInfo)
                        session.close()

                        // 5. Unauthenticated socket EOF mid-stream (without stream_end) is flagged as Truncated!
                        val truncSession = try {
                            withTimeout(3_000) {
                                fallbackConnector.connectPhoneIk(
                                    hostPublicKey = hostKp.publicKey,
                                    deviceKeypair = phoneKp,
                                    relayUrl = relayServer.url,
                                    routeId = "rt_home",
                                )
                            }
                        } catch (e: Throwable) {
                            throw AssertionError("Timed out at Step 5a (truncSession connect)", e)
                        }
                        val truncOutcome = CompletableDeferred<StreamCloseOutcome>()
                        val truncStream = try {
                            withTimeout(2_000) {
                                truncSession.mux.openStream(
                                    VirtualStreamRequest(StreamTarget.VNC_RO, "/vnc-ro/truncate")
                                )
                            }
                        } catch (e: Throwable) {
                            throw AssertionError("Timed out at Step 5b (truncStream open)", e)
                        }
                        truncStream.onClose { truncOutcome.complete(it) }
                        val outcome = try {
                            withTimeout(2_000) { truncOutcome.await() }
                        } catch (e: Throwable) {
                            throw AssertionError("Timed out at Step 5c (truncOutcome await)", e)
                        }
                        assertTrue(outcome is StreamCloseOutcome.Truncated, "Expected Truncated on unauthenticated EOF, got $outcome")
                    }
                }
            }
        }
}

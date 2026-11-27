package com.lumenopen.lumenbox.relay

import com.lumenopen.lumenbox.contract.LumenBoxJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

interface RelayWebSocket {
    fun sendText(text: String)
    fun sendBinary(bytes: ByteArray)
    fun onText(listener: (String) -> Unit)
    fun onBinary(listener: (ByteArray) -> Unit)
    fun onClose(listener: (code: Int, reason: String) -> Unit)
    fun close(code: Int = 1000, reason: String = "Normal closure")
}

fun interface RelayWebSocketFactory {
    suspend fun connect(url: String, connectTimeoutMillis: Long): RelayWebSocket
}

/**
 * Pure RFC 6455 WebSocket client over standard `java.net.Socket` / `javax.net.ssl.SSLSocketFactory`.
 *
 * - Compatible with Android API 28–35 and OpenJDK 17 (`String(bytes, StandardCharsets.US_ASCII)`).
 * - Enforces [connectTimeoutMillis] as a total wall-clock deadline across TCP connect, TLS handshake,
 *   and HTTP 101 header reads, closing the in-progress socket immediately on cancellation or expiry.
 * - Caps both individual frames and reassembled fragmented messages at 32 MiB before buffering.
 * - Immediately fires `onClose(1006, ...)` on raw TCP `FIN`/`RST` when no WebSocket Close frame arrived.
 */
class SocketRelayWebSocketFactory(
    private val sslSocketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
) : RelayWebSocketFactory {
    companion object {
        private const val MAX_MESSAGE_BYTES: Long = 32L * 1024L * 1024L
    }

    private val random = SecureRandom()

    private fun remainingMillisOrThrow(deadlineNano: Long): Int {
        val remNs = deadlineNano - System.nanoTime()
        if (remNs <= 0L) {
            throw SocketTimeoutException("WebSocket connect/upgrade exceeded total deadline")
        }
        return TimeUnit.NANOSECONDS.toMillis(remNs).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    override suspend fun connect(url: String, connectTimeoutMillis: Long): RelayWebSocket {
        val callerJob = currentCoroutineContext().job
        val deadlineNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(connectTimeoutMillis.coerceAtLeast(1L))
        val rawSocket = Socket()
        val handedOff = AtomicBoolean(false)
        val cancelHandle = callerJob.invokeOnCompletion { cause ->
            if (cause != null && !handedOff.get()) {
                runCatching { rawSocket.close() }
            }
        }

        return try {
            val ws = withContext(Dispatchers.IO) {
                val uri = URI.create(url)
                val scheme = uri.scheme?.lowercase()
                    ?: throw NoiseRelayException("ws_invalid_url", "Missing scheme in $url")
                val isTls = when (scheme) {
                    "wss" -> true
                    "ws" -> false
                    else -> throw NoiseRelayException("ws_invalid_url", "Unsupported WebSocket scheme: $scheme")
                }
                val host = uri.host ?: throw NoiseRelayException("ws_invalid_url", "Missing host in $url")
                val port = if (uri.port > 0) uri.port else if (isTls) 443 else 80
                val pathAndQuery = buildString {
                    append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
                    if (!uri.rawQuery.isNullOrEmpty()) {
                        append('?')
                        append(uri.rawQuery)
                    }
                }

                rawSocket.tcpNoDelay = true
                rawSocket.connect(InetSocketAddress(host, port), remainingMillisOrThrow(deadlineNano))

                val socket: Socket = if (isTls) {
                    (sslSocketFactory.createSocket(rawSocket, host, port, true) as SSLSocket).apply {
                        sslParameters = sslParameters.also { params: SSLParameters ->
                            params.endpointIdentificationAlgorithm = "HTTPS"
                            runCatching { params.serverNames = listOf(SNIHostName(host)) }
                        }
                        soTimeout = remainingMillisOrThrow(deadlineNano)
                        startHandshake()
                    }
                } else {
                    rawSocket
                }

                try {
                    val keyBytes = ByteArray(16).also { random.nextBytes(it) }
                    val secKey = Base64.getEncoder().encodeToString(keyBytes)
                    val hostHeader = if ((isTls && port == 443) || (!isTls && port == 80)) host else "$host:$port"
                    val out = socket.getOutputStream()
                    val req = (
                        "GET $pathAndQuery HTTP/1.1\r\n" +
                            "Host: $hostHeader\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Key: $secKey\r\n" +
                            "Sec-WebSocket-Version: 13\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    out.write(req)
                    out.flush()

                    val input = BufferedInputStream(socket.getInputStream())
                    val headerText = readHttpHeaderBlock(socket, input, deadlineNano)
                    socket.soTimeout = 0

                    val statusLine = headerText.lineSequence().firstOrNull().orEmpty()
                    if (!statusLine.startsWith("HTTP/1.1 101")) {
                        throw NoiseRelayException("ws_upgrade_failed", "Expected 101 Switching Protocols, got: $statusLine")
                    }
                    val expectedAccept = Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                            .digest((secKey + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(StandardCharsets.US_ASCII))
                    )
                    val actualAccept = headerText.lineSequence()
                        .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
                        ?.substringAfter(':')
                        ?.trim()
                    if (actualAccept != expectedAccept) {
                        throw NoiseRelayException("ws_upgrade_failed", "Invalid Sec-WebSocket-Accept header")
                    }

                    createSocketWebSocket(socket, input, out)
                } catch (inner: Throwable) {
                    runCatching { socket.close() }
                    throw inner
                }
            }

            if (!callerJob.isActive) {
                withContext(NonCancellable) { ws.close(1000, "Cancelled during handoff") }
                callerJob.ensureActive()
            }
            handedOff.set(true)
            ws
        } catch (e: CancellationException) {
            runCatching { rawSocket.close() }
            throw e
        } catch (e: Throwable) {
            runCatching { rawSocket.close() }
            if (e is NoiseRelayException) throw e
            throw NoiseRelayException("ws_connect_failed", "Failed connecting to $url: ${e.message}", e)
        } finally {
            cancelHandle.dispose()
        }
    }

    private fun createSocketWebSocket(
        socket: Socket,
        input: InputStream,
        out: java.io.OutputStream,
    ): RelayWebSocket {
        val textListeners = CopyOnWriteArrayList<(String) -> Unit>()
        val binaryListeners = CopyOnWriteArrayList<(ByteArray) -> Unit>()
        val closeListeners = CopyOnWriteArrayList<(Int, String) -> Unit>()
        val writeLock = Any()
        val closedNotified = AtomicBoolean(false)

        fun notifyCloseOnce(code: Int, reason: String) {
            if (closedNotified.compareAndSet(false, true)) {
                runCatching { socket.close() }
                closeListeners.forEach { runCatching { it(code, reason) } }
            }
        }

        fun writeMaskedFrame(opcode: Int, payload: ByteArray) {
            val mask = ByteArray(4).also { random.nextBytes(it) }
            val header = ByteArrayOutputStream(14)
            header.write(0x80 or (opcode and 0x0f))
            when {
                payload.size < 126 -> header.write(0x80 or payload.size)
                payload.size <= 65_535 -> {
                    header.write(0x80 or 126)
                    header.write((payload.size ushr 8) and 0xff)
                    header.write(payload.size and 0xff)
                }
                else -> {
                    header.write(0x80 or 127)
                    val len = payload.size.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        header.write(((len ushr shift) and 0xffL).toInt())
                    }
                }
            }
            header.write(mask)
            val masked = ByteArray(payload.size)
            for (i in payload.indices) {
                masked[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            }
            synchronized(writeLock) {
                out.write(header.toByteArray())
                if (masked.isNotEmpty()) out.write(masked)
                out.flush()
            }
        }

        thread(isDaemon = true, name = "lumenbox-ws-reader") {
            var fragOpcode = -1
            val fragBuffer = ByteArrayOutputStream()
            try {
                while (!socket.isClosed) {
                    val b0 = input.read()
                    if (b0 < 0) {
                        notifyCloseOnce(1006, "Abnormal EOF without WebSocket Close frame")
                        break
                    }
                    val b1 = input.read()
                    if (b1 < 0) {
                        notifyCloseOnce(1006, "Truncated WebSocket frame header")
                        break
                    }
                    val fin = (b0 and 0x80) != 0
                    val opcode = b0 and 0x0f
                    val isMasked = (b1 and 0x80) != 0
                    var len = (b1 and 0x7f).toLong()
                    if (len == 126L) {
                        val ext = readExactBytes(input, 2)
                        len = (((ext[0].toInt() and 0xff) shl 8) or (ext[1].toInt() and 0xff)).toLong()
                    } else if (len == 127L) {
                        val ext = readExactBytes(input, 8)
                        len = 0L
                        for (i in 0 until 8) len = (len shl 8) or (ext[i].toLong() and 0xffL)
                    }
                    if (len < 0L || len > MAX_MESSAGE_BYTES) {
                        notifyCloseOnce(1009, "Frame exceeds 32 MiB safety cap")
                        break
                    }
                    val mask = if (isMasked) readExactBytes(input, 4) else null
                    val payload = readExactBytes(input, len.toInt())
                    if (mask != null) {
                        for (i in payload.indices) {
                            payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
                        }
                    }

                    when (opcode) {
                        0x0 -> { // Continuation
                            if (fragOpcode < 0) {
                                notifyCloseOnce(1002, "Unexpected continuation frame")
                                break
                            }
                            if (fragBuffer.size().toLong() + payload.size.toLong() > MAX_MESSAGE_BYTES) {
                                notifyCloseOnce(1009, "Fragmented message exceeds 32 MiB safety cap")
                                break
                            }
                            fragBuffer.write(payload)
                            if (fin) {
                                val full = fragBuffer.toByteArray()
                                val completedOp = fragOpcode
                                fragOpcode = -1
                                fragBuffer.reset()
                                if (completedOp == 0x1) {
                                    val txt = String(full, StandardCharsets.UTF_8)
                                    textListeners.forEach { it(txt) }
                                } else if (completedOp == 0x2) {
                                    binaryListeners.forEach { it(full) }
                                }
                            }
                        }
                        0x1, 0x2 -> {
                            if (fin) {
                                if (opcode == 0x1) {
                                    val txt = String(payload, StandardCharsets.UTF_8)
                                    textListeners.forEach { it(txt) }
                                } else {
                                    binaryListeners.forEach { it(payload) }
                                }
                            } else {
                                fragOpcode = opcode
                                fragBuffer.reset()
                                fragBuffer.write(payload)
                            }
                        }
                        0x8 -> { // Close
                            val closeCode = if (payload.size >= 2) {
                                ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff)
                            } else {
                                1000
                            }
                            val closeReason = if (payload.size > 2) {
                                String(payload, 2, payload.size - 2, StandardCharsets.UTF_8)
                            } else {
                                ""
                            }
                            runCatching { writeMaskedFrame(0x8, payload) }
                            notifyCloseOnce(closeCode, closeReason)
                            break
                        }
                        0x9 -> { // Ping -> Pong
                            runCatching { writeMaskedFrame(0xA, payload) }
                        }
                        0xA -> Unit // Pong
                    }
                }
            } catch (err: Throwable) {
                notifyCloseOnce(1006, err.message ?: "Socket read failed")
            }
        }

        return object : RelayWebSocket {
            override fun sendText(text: String) {
                runCatching { writeMaskedFrame(0x1, text.toByteArray(StandardCharsets.UTF_8)) }
                    .onFailure { notifyCloseOnce(1006, it.message ?: "Write failed") }
            }

            override fun sendBinary(bytes: ByteArray) {
                runCatching { writeMaskedFrame(0x2, bytes) }
                    .onFailure { notifyCloseOnce(1006, it.message ?: "Write failed") }
            }

            override fun onText(listener: (String) -> Unit) {
                textListeners.add(listener)
            }

            override fun onBinary(listener: (ByteArray) -> Unit) {
                binaryListeners.add(listener)
            }

            override fun onClose(listener: (code: Int, reason: String) -> Unit) {
                closeListeners.add(listener)
                if (closedNotified.get()) {
                    listener(1006, "Already closed")
                }
            }

            override fun close(code: Int, reason: String) {
                val reasonBytes = reason.toByteArray(StandardCharsets.UTF_8)
                val body = ByteArray(2 + reasonBytes.size)
                body[0] = ((code ushr 8) and 0xff).toByte()
                body[1] = (code and 0xff).toByte()
                System.arraycopy(reasonBytes, 0, body, 2, reasonBytes.size)
                runCatching { writeMaskedFrame(0x8, body) }
                notifyCloseOnce(code, reason)
            }
        }
    }

    private fun readHttpHeaderBlock(socket: Socket, input: InputStream, deadlineNano: Long): String {
        val buf = ByteArrayOutputStream(512)
        var matched = 0
        while (buf.size() < 16_384) {
            socket.soTimeout = remainingMillisOrThrow(deadlineNano)
            val b = input.read()
            if (b < 0) throw EOFException("EOF while reading WebSocket upgrade headers")
            buf.write(b)
            matched = when {
                matched == 0 && b == '\r'.code -> 1
                matched == 1 && b == '\n'.code -> 2
                matched == 2 && b == '\r'.code -> 3
                matched == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            // Use API 19+ String(byte[], Charset) constructor so Android 28–32 never hits API 33 ByteArrayOutputStream.toString(Charset)
            if (matched == 4) return String(buf.toByteArray(), StandardCharsets.US_ASCII)
        }
        throw NoiseRelayException("ws_header_too_large", "WebSocket response headers exceeded 16 KiB")
    }

    private fun readExactBytes(input: InputStream, count: Int): ByteArray {
        val out = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val r = input.read(out, offset, count - offset)
            if (r < 0) throw EOFException("Unexpected EOF inside WebSocket frame")
            offset += r
        }
        return out
    }
}

enum class TransportPathMode { DIRECT, RELAY }

data class PairingResult(
    val deviceId: String,
    val connId: String,
    val scopes: List<String>,
    val mode: TransportPathMode,
    val selectedUrl: String,
)

data class ConnectedPhoneSession(
    val mux: RelayMux,
    val deviceId: String,
    val scopes: List<String>,
    val mode: TransportPathMode,
    val selectedUrl: String,
    private val ws: RelayWebSocket,
) {
    fun close() {
        mux.close(authenticated = true)
        ws.close(1000, "Session closed")
    }
}

/**
 * Real WebSocket client connector for personal-mode `#fragment` QR pairing (`Noise_NK`) and
 * mutual zero-knowledge reconnection (`Noise_IK`) with LAN/tailnet direct-first candidate selection
 * (`docs/110 §3.1–§3.2`, `INV-2251`).
 */
class WebSocketRelayConnector(
    private val socketFactory: RelayWebSocketFactory = SocketRelayWebSocketFactory(),
    private val lanDiscovery: LanDiscoveryProvider = LanDiscoveryProvider.NONE,
    private val directProbeTimeoutMillis: Long = 1_200L,
    private val relayConnectTimeoutMillis: Long = 5_000L,
) {
    private val b64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64Decoder = Base64.getUrlDecoder()

    private suspend fun collectDirectCandidates(explicitDirectUrls: List<String>, routeId: String): List<String> {
        val discovered = runCatching {
            lanDiscovery.discoverDirectEndpoints(routeId, directProbeTimeoutMillis).map { it.wsUrl }
        }.getOrDefault(emptyList())
        return LinkedHashSet<String>().apply {
            addAll(discovered)
            addAll(explicitDirectUrls)
        }.toList()
    }

    private suspend fun openRelaySocket(relayUrl: String, routeId: String): RelayWebSocket {
        val relayWs = socketFactory.connect(relayUrl, relayConnectTimeoutMillis)
        val connected = CompletableDeferred<Unit>()
        relayWs.onText { text ->
            if (connected.isCompleted) return@onText
            val obj = parseJsonObjectOrNull(text) ?: return@onText
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "client_connected" -> connected.complete(Unit)
                "relay_error" -> {
                    val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
                    connected.completeExceptionally(
                        NoiseRelayException("relay_rejected", "Relay rejected client: $reason")
                    )
                }
            }
        }
        relayWs.onClose { code, reason ->
            if (!connected.isCompleted) {
                connected.completeExceptionally(
                    NoiseRelayException("relay_closed", "Relay closed before session ($code: $reason)")
                )
            }
        }
        relayWs.sendText(
            LumenBoxJson.default.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("type", "client_connect")
                    put("routeId", routeId)
                }
            )
        )
        try {
            withTimeout(relayConnectTimeoutMillis) { connected.await() }
        } catch (err: Throwable) {
            relayWs.close(1000, "Connect failed")
            throw err
        }
        return relayWs
    }

    /**
     * Pairs a phone with a single-use `#fragment` QR offer (`Noise_NK`).
     * Only falls back across candidates when connection fails BEFORE `pair_hello` is transmitted,
     * never replaying a potentially consumed one-time `pairSecret` after an ambiguous reply drop.
     */
    suspend fun pairPhoneWithOffer(
        offer: ParsedPairingOffer,
        deviceKeypair: X25519Keypair,
        deviceName: String,
        platform: String = "android",
    ): PairingResult {
        var ws: RelayWebSocket? = null
        var selectedMode = TransportPathMode.RELAY
        var selectedUrl = offer.relayUrl

        for (candidate in collectDirectCandidates(offer.directUrls, offer.routeId)) {
            val directWs = runCatching {
                socketFactory.connect(candidate, directProbeTimeoutMillis)
            }.getOrNull()
            if (directWs != null) {
                ws = directWs
                selectedMode = TransportPathMode.DIRECT
                selectedUrl = candidate
                break
            }
        }
        if (ws == null) {
            ws = openRelaySocket(offer.relayUrl, offer.routeId)
            selectedMode = TransportPathMode.RELAY
            selectedUrl = offer.relayUrl
        }

        val activeWs = ws
        try {
            val reqJson = LumenBoxJson.default.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("pairSecret", b64Encoder.encodeToString(offer.pairSecret))
                    put("deviceStaticPub", b64Encoder.encodeToString(deviceKeypair.publicKey))
                    put("deviceName", deviceName)
                    put("platform", platform)
                }
            ).toByteArray(StandardCharsets.UTF_8)

            val (msg1, state) = NoiseHandshakes.startNkInitiator(
                responderStaticPublicKey = offer.hostPublicKey,
                payload = reqJson,
            )

            val replyDeferred = CompletableDeferred<PairingResult>()
            activeWs.onText { text ->
                if (replyDeferred.isCompleted) return@onText
                val env = parseJsonObjectOrNull(text) ?: return@onText
                if (env["type"]?.jsonPrimitive?.contentOrNull != "pair_ready") return@onText
                val msg2B64 = env["message"]?.jsonPrimitive?.contentOrNull ?: return@onText
                try {
                    val (plain, _) = state.completeMessage2(b64Decoder.decode(msg2B64))
                    val replyObj = parseJsonObjectOrNull(String(plain, StandardCharsets.UTF_8))
                        ?: throw NoiseRelayException("invalid_pair_reply", "Malformed pairing reply JSON")
                    val deviceId = replyObj["deviceId"]?.jsonPrimitive?.contentOrNull
                        ?: throw NoiseRelayException("invalid_pair_reply", "Missing deviceId")
                    val connId = replyObj["connId"]?.jsonPrimitive?.contentOrNull
                        ?: throw NoiseRelayException("invalid_pair_reply", "Missing connId")
                    val scopes = (replyObj["scopes"] as? JsonArray)
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                        ?: emptyList()
                    replyDeferred.complete(PairingResult(deviceId, connId, scopes, selectedMode, selectedUrl))
                } catch (err: Throwable) {
                    replyDeferred.completeExceptionally(err)
                }
            }
            activeWs.onClose { code, reason ->
                if (!replyDeferred.isCompleted) {
                    replyDeferred.completeExceptionally(
                        NoiseRelayException("pair_closed", "Pairing socket closed ($code: $reason)")
                    )
                }
            }

            activeWs.sendText(
                LumenBoxJson.default.encodeToString(
                    JsonObject.serializer(),
                    buildJsonObject {
                        put("type", "pair_hello")
                        put("message", b64Encoder.encodeToString(msg1))
                    }
                )
            )
            return withTimeout(relayConnectTimeoutMillis) { replyDeferred.await() }
        } finally {
            activeWs.close(1000, "Pairing complete")
        }
    }

    private suspend fun handshakeIkOverSocket(
        ws: RelayWebSocket,
        mode: TransportPathMode,
        selectedUrl: String,
        timeoutMillis: Long,
        hostPublicKey: ByteArray,
        deviceKeypair: X25519Keypair,
        initialWindowBytes: Int,
        autoReplenishStreamWindow: Boolean,
        onHostSleeping: ((HostSleepingInfo) -> Unit)?,
    ): ConnectedPhoneSession {
        val (msg1, state) = NoiseHandshakes.startIkInitiator(
            initiatorStaticKeypair = deviceKeypair,
            responderStaticPublicKey = hostPublicKey,
        )

        val sessionDeferred = CompletableDeferred<ConnectedPhoneSession>()
        ws.onText { text ->
            if (sessionDeferred.isCompleted) return@onText
            val env = parseJsonObjectOrNull(text) ?: return@onText
            when (env["type"]?.jsonPrimitive?.contentOrNull) {
                "host_offline" -> {
                    sessionDeferred.completeExceptionally(
                        NoiseRelayException("host_offline", "Host went offline during handshake")
                    )
                }
                "ik_ready" -> {
                    val msg2B64 = env["message"]?.jsonPrimitive?.contentOrNull ?: return@onText
                    try {
                        val (plain, noiseSession) = state.completeMessage2(b64Decoder.decode(msg2B64))
                        val infoObj = parseJsonObjectOrNull(String(plain, StandardCharsets.UTF_8))
                            ?: throw NoiseRelayException("invalid_ik_reply", "Malformed Noise_IK admission JSON")
                        val deviceId = infoObj["deviceId"]?.jsonPrimitive?.contentOrNull
                            ?: throw NoiseRelayException("invalid_ik_reply", "Missing deviceId")
                        val scopes = (infoObj["scopes"] as? JsonArray)
                            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                            ?: emptyList()

                        val mux = RelayMux(
                            role = RelayMux.Role.CLIENT,
                            session = noiseSession,
                            sendWire = { wire -> ws.sendBinary(wire) },
                            initialWindowBytes = initialWindowBytes,
                            autoReplenishStreamWindow = autoReplenishStreamWindow,
                            onHostSleeping = onHostSleeping,
                            onProtocolError = { ws.close(4003, "Encrypted record verification failed") },
                        )
                        ws.onBinary { bytes -> mux.receiveWire(bytes) }
                        ws.onClose { code, reason ->
                            mux.close(
                                reason = NoiseRelayException(
                                    "truncated_stream",
                                    "Transport socket closed ($code: $reason)"
                                ),
                                authenticated = false,
                            )
                        }
                        sessionDeferred.complete(
                            ConnectedPhoneSession(mux, deviceId, scopes, mode, selectedUrl, ws)
                        )
                    } catch (err: Throwable) {
                        ws.close(4003, "Handshake verification failed")
                        sessionDeferred.completeExceptionally(err)
                    }
                }
            }
        }
        ws.onClose { code, reason ->
            if (!sessionDeferred.isCompleted) {
                sessionDeferred.completeExceptionally(
                    NoiseRelayException("ik_rejected", "Noise_IK closed ($code: $reason)")
                )
            }
        }

        ws.sendText(
            LumenBoxJson.default.encodeToString(
                JsonObject.serializer(),
                buildJsonObject {
                    put("type", "ik_hello")
                    put("message", b64Encoder.encodeToString(msg1))
                }
            )
        )

        return try {
            withTimeout(timeoutMillis) { sessionDeferred.await() }
        } catch (err: Throwable) {
            ws.close(1000, "Handshake failed")
            throw err
        }
    }

    /**
     * Reconnects a paired phone via `Noise_IK`: commits to a `DIRECT` candidate only after full
     * `Noise_IK` admission succeeds, falling back to the next candidate or [relayUrl] if an obsolete
     * LAN endpoint upgrades WebSocket and drops before `ik_ready`.
     */
    suspend fun connectPhoneIk(
        hostPublicKey: ByteArray,
        deviceKeypair: X25519Keypair,
        relayUrl: String,
        routeId: String,
        directUrls: List<String> = emptyList(),
        initialWindowBytes: Int = RelayMux.DEFAULT_INITIAL_WINDOW_BYTES,
        autoReplenishStreamWindow: Boolean = true,
        onHostSleeping: ((HostSleepingInfo) -> Unit)? = null,
    ): ConnectedPhoneSession {
        for (candidate in collectDirectCandidates(directUrls, routeId)) {
            val directSession = runCatching {
                val ws = socketFactory.connect(candidate, directProbeTimeoutMillis)
                handshakeIkOverSocket(
                    ws = ws,
                    mode = TransportPathMode.DIRECT,
                    selectedUrl = candidate,
                    timeoutMillis = directProbeTimeoutMillis,
                    hostPublicKey = hostPublicKey,
                    deviceKeypair = deviceKeypair,
                    initialWindowBytes = initialWindowBytes,
                    autoReplenishStreamWindow = autoReplenishStreamWindow,
                    onHostSleeping = onHostSleeping,
                )
            }.getOrNull()
            if (directSession != null) return directSession
        }

        val relayWs = openRelaySocket(relayUrl, routeId)
        return handshakeIkOverSocket(
            ws = relayWs,
            mode = TransportPathMode.RELAY,
            selectedUrl = relayUrl,
            timeoutMillis = relayConnectTimeoutMillis,
            hostPublicKey = hostPublicKey,
            deviceKeypair = deviceKeypair,
            initialWindowBytes = initialWindowBytes,
            autoReplenishStreamWindow = autoReplenishStreamWindow,
            onHostSleeping = onHostSleeping,
        )
    }

    private fun parseJsonObjectOrNull(text: String): JsonObject? =
        runCatching { LumenBoxJson.default.parseToJsonElement(text).jsonObject }.getOrNull()
}

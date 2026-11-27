package com.lumenopen.lumenbox.desktop

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.generated.models.GeneratedAgentPage
import com.lumenopen.lumenbox.generated.models.GeneratedDesktopTicketRequest
import com.lumenopen.lumenbox.generated.models.GeneratedDesktopTicketResponse
import com.lumenopen.lumenbox.relay.DuplexRelayStream
import com.lumenopen.lumenbox.relay.RelayMux
import com.lumenopen.lumenbox.relay.RelayWebSocket
import com.lumenopen.lumenbox.relay.RelayWebSocketFactory
import com.lumenopen.lumenbox.relay.SocketRelayWebSocketFactory
import com.lumenopen.lumenbox.relay.StreamTarget
import com.lumenopen.lumenbox.relay.VirtualStreamRequest
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
data class AgentDesktopSummary(
    val id: String,
    val name: String,
    val role: String? = null,
    val status: String? = null,
    val hasDesktop: Boolean = true,
)

data class IssuedReadOnlyDesktopTicket(
    val agentId: String,
    val ticket: String,
    val streamPathWithTicket: String,
    val wsUrlWithTicket: String?,
    val expiresInSeconds: Int,
)

sealed interface DesktopViewerSessionState {
    data object Idle : DesktopViewerSessionState
    data class RequestingViewTicket(val agentId: String) : DesktopViewerSessionState
    data class ConnectingStream(val agentId: String, val path: String) : DesktopViewerSessionState
    data class WatchingReadOnly(
        val agentId: String,
        val snapshot: RfbFrameSnapshot,
    ) : DesktopViewerSessionState

    data class Disconnected(
        val agentId: String,
        val reason: String,
    ) : DesktopViewerSessionState
}

/**
 * Binary byte-stream handle carrying strictly read-only RFB 3.8 traffic (`docs/110 §3.2, §4.4`).
 * Used identically over Organisation direct WSS (`/vnc-ro/...`) and Personal Mac `Noise_IK`
 * `RelayMux.openStream(StreamTarget.VNC_RO, ...)`.
 */
interface DesktopByteStream : AutoCloseable {
    fun sendClientControlBytes(bytes: ByteArray)
    fun onServerBytes(listener: (ByteArray) -> Unit)
    fun onClosed(listener: (String) -> Unit)
    override fun close()
}

fun interface DesktopBinaryStreamOpener {
    suspend fun openReadOnlyStream(ticket: IssuedReadOnlyDesktopTicket): DesktopByteStream

    companion object {
        /** Opens a multiplexed `StreamTarget.VNC_RO` binary sub-stream inside the `Noise_IK` session. */
        fun overRelayMux(
            muxProvider: () -> RelayMux?,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        ): DesktopBinaryStreamOpener = DesktopBinaryStreamOpener { issued ->
            val mux = muxProvider()
                ?: throw IllegalStateException("个人 Mac 加密多路复用通道未在线，无法打开只读桌面流。")
            val stream: DuplexRelayStream = mux.openStream(
                VirtualStreamRequest(
                    target = StreamTarget.VNC_RO,
                    path = issued.streamPathWithTicket,
                )
            )
            object : DesktopByteStream {
                override fun sendClientControlBytes(bytes: ByteArray) {
                    scope.launch {
                        runCatching { stream.send(bytes, binary = true) }
                    }
                }

                override fun onServerBytes(listener: (ByteArray) -> Unit) {
                    stream.onData { chunk, _ -> listener(chunk) }
                }

                override fun onClosed(listener: (String) -> Unit) {
                    stream.onClose { outcome -> listener(outcome.toString()) }
                }

                override fun close() {
                    runCatching { stream.close() }
                }
            }
        }

        /** Opens a direct WSS binary WebSocket to `/vnc-ro/<index>/websockify?ticket=...` (`docs/106 §5 R6`). */
        fun overWebSocket(
            serverOriginProvider: () -> String,
            wsFactory: RelayWebSocketFactory = SocketRelayWebSocketFactory(),
            connectTimeoutMillis: Long = 8_000L,
        ): DesktopBinaryStreamOpener = DesktopBinaryStreamOpener { issued ->
            val targetUrl = resolveSecureWebSocketUrl(
                serverOrigin = serverOriginProvider(),
                explicitWsUrl = issued.wsUrlWithTicket,
                streamPathWithTicket = issued.streamPathWithTicket,
            )
            val ws: RelayWebSocket = wsFactory.connect(targetUrl, connectTimeoutMillis)
            object : DesktopByteStream {
                override fun sendClientControlBytes(bytes: ByteArray) {
                    ws.sendBinary(bytes)
                }

                override fun onServerBytes(listener: (ByteArray) -> Unit) {
                    ws.onBinary(listener)
                }

                override fun onClosed(listener: (String) -> Unit) {
                    ws.onClose { code, reason -> listener("WS $code $reason".trim()) }
                }

                override fun close() {
                    ws.close(1000, "Desktop viewer closed")
                }
            }
        }

        internal fun resolveSecureWebSocketUrl(
            serverOrigin: String,
            explicitWsUrl: String?,
            streamPathWithTicket: String,
        ): String {
            if (!explicitWsUrl.isNullOrBlank()) {
                val uri = URI.create(explicitWsUrl)
                val path = uri.rawPath.orEmpty()
                require(isReadOnlyVncPath(path)) {
                    "Refusing non-read-only VNC WebSocket path: $path"
                }
                return appendQueryParamIfMissing(explicitWsUrl, "ticket", extractTicketParam(streamPathWithTicket))
            }
            val originUri = URI.create(serverOrigin.trimEnd('/'))
            val wsScheme = when (originUri.scheme?.lowercase()) {
                "https", "wss" -> "wss"
                "http", "ws" -> {
                    val host = originUri.host?.lowercase().orEmpty()
                    if (host == "127.0.0.1" || host == "localhost" || host == "::1") "ws"
                    else throw RfbProtocolException("cleartext_ws_forbidden", "Refusing cleartext ws:// desktop stream off loopback")
                }
                else -> "wss"
            }
            val authority = originUri.rawAuthority
                ?: throw RfbProtocolException("invalid_origin", "Invalid server origin: $serverOrigin")
            return "$wsScheme://$authority$streamPathWithTicket"
        }

        internal fun isReadOnlyVncPath(rawPath: String): Boolean =
            rawPath.startsWith("/vnc-ro/") || rawPath == "/vnc-ro"

        private fun extractTicketParam(pathWithQuery: String): String? {
            val qIdx = pathWithQuery.indexOf('?')
            if (qIdx < 0) return null
            return pathWithQuery.substring(qIdx + 1)
                .split('&')
                .firstOrNull { it.startsWith("ticket=") }
                ?.substringAfter('=')
                ?.takeIf { it.isNotEmpty() }
        }

        private fun appendQueryParamIfMissing(url: String, key: String, value: String?): String {
            if (value.isNullOrEmpty()) return url
            if (url.contains("?$key=") || url.contains("&$key=")) return url
            val sep = if ('?' in url) '&' else '?'
            return "$url$sep$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
    }
}

/**
 * Coordinates read-only Agent desktop watching (`docs/106 §5 R6`, `docs/110 §0.7, §4.4`, INV-2258):
 * - Requests single-use 60-second `view` tickets ONLY (`GeneratedDesktopTicketRequest.Mode.VIEW`).
 * - Verifies that the granted stream path routes to `/vnc-ro/...` (rejecting any `/vnc/` control path!).
 * - Streams RFB 3.8 frames through [ReadOnlyRfb38Decoder] with zero pointer/keyboard transmission.
 */
class ReadOnlyDesktopSessionCoordinator(
    private val connectionId: String,
    private val store: InstallationStore,
    private val transportProvider: () -> ApiTransport?,
    private val streamOpenerProvider: () -> DesktopBinaryStreamOpener?,
) {
    companion object {
        const val EXPECTED_MAX_TICKET_TTL_SECONDS = 60
        private const val AGENTS_CACHE_TABLE = "agentsDesktopSnapshot"
        private const val AGENTS_CACHE_ROW = "items"
    }

    private val sessionLock = Any()
    private var activeStream: DesktopByteStream? = null
    private var activeDecoder: ReadOnlyRfb38Decoder? = null
    private var activeAgentId: String? = null

    private val _state = MutableStateFlow<DesktopViewerSessionState>(DesktopViewerSessionState.Idle)
    val state: StateFlow<DesktopViewerSessionState> = _state.asStateFlow()

    fun readCachedAgents(): List<AgentDesktopSummary> =
        runCatching {
            store.openOfflineStore(connectionId).getJson(
                AGENTS_CACHE_TABLE,
                AGENTS_CACHE_ROW,
                ListSerializer(AgentDesktopSummary.serializer()),
            )
        }.getOrNull().orEmpty()

    suspend fun refreshAgents(): List<AgentDesktopSummary> {
        val transport = transportProvider() ?: return readCachedAgents()
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/agents",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        throwIfError(res.statusCode, res.body)

        val page = LumenBoxJson.default.decodeFromString(
            GeneratedAgentPage.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val mapped = page.items.map { wire ->
            AgentDesktopSummary(
                id = wire.id,
                name = wire.name,
                role = wire.role,
                status = wire.status,
                hasDesktop = wire.hasDesktop ?: true,
            )
        }
        store.openOfflineStore(connectionId).putJson(
            AGENTS_CACHE_TABLE,
            AGENTS_CACHE_ROW,
            ListSerializer(AgentDesktopSummary.serializer()),
            mapped,
        )
        return mapped
    }

    /**
     * Requests a single-use 60-second `mode: "view"` ticket from `POST /api/v1/desktops/{agentId}/tickets`.
     * Refuses any response whose path is not `/vnc-ro/...` or whose TTL exceeds 60 seconds.
     */
    suspend fun issueReadOnlyTicket(agentId: String): IssuedReadOnlyDesktopTicket {
        require(agentId.isNotBlank()) { "agentId must not be blank" }
        val transport = transportProvider()
            ?: throw IllegalStateException("当前处于离线状态，无法申请 60 秒单次桌面观看票据。")

        val reqBody = LumenBoxJson.default.encodeToString(
            GeneratedDesktopTicketRequest.serializer(),
            GeneratedDesktopTicketRequest(mode = GeneratedDesktopTicketRequest.Mode.VIEW),
        ).toByteArray(StandardCharsets.UTF_8)

        val encodedAgent = URLEncoder.encode(agentId, StandardCharsets.UTF_8.name()).replace("+", "%20")
        val res = transport.send(
            TransportRequest(
                method = "POST",
                path = "/api/v1/desktops/$encodedAgent/tickets",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = reqBody,
                idempotencyKey = UUID.randomUUID().toString(),
            )
        )
        throwIfError(res.statusCode, res.body)

        val wire = LumenBoxJson.default.decodeFromString(
            GeneratedDesktopTicketResponse.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        require(wire.ticket.isNotBlank()) { "Server returned blank desktop view ticket" }
        require(wire.expiresInSeconds in 1..EXPECTED_MAX_TICKET_TTL_SECONDS) {
            "Desktop view ticket TTL (${wire.expiresInSeconds}s) violates 60s single-use cap"
        }

        val rawPath = wire.path.trim()
        val pathOnly = rawPath.substringBefore('?')
        if (!DesktopBinaryStreamOpener.isReadOnlyVncPath(pathOnly)) {
            throw RfbProtocolException(
                "non_readonly_path_refused",
                "Security guard refused non-read-only desktop path '$pathOnly' (expected /vnc-ro/...)",
            )
        }

        val fullPathWithTicket = if (rawPath.contains("?ticket=") || rawPath.contains("&ticket=")) {
            rawPath
        } else {
            val sep = if ('?' in rawPath) '&' else '?'
            "$rawPath${sep}ticket=${URLEncoder.encode(wire.ticket, StandardCharsets.UTF_8.name())}"
        }

        return IssuedReadOnlyDesktopTicket(
            agentId = agentId,
            ticket = wire.ticket,
            streamPathWithTicket = fullPathWithTicket,
            wsUrlWithTicket = wire.wsUrl,
            expiresInSeconds = wire.expiresInSeconds,
        )
    }

    /**
     * Mints a fresh 60-second single-use `view` ticket and opens the read-only RFB 3.8 stream.
     * Automatically closes any previously open desktop stream first.
     */
    suspend fun startWatching(agentId: String) {
        disconnect("Switching desktop session")
        _state.value = DesktopViewerSessionState.RequestingViewTicket(agentId)

        val issued = try {
            issueReadOnlyTicket(agentId)
        } catch (err: Throwable) {
            _state.value = DesktopViewerSessionState.Disconnected(
                agentId = agentId,
                reason = err.message ?: "申请只读桌面票据失败",
            )
            throw err
        }

        _state.value = DesktopViewerSessionState.ConnectingStream(agentId, issued.streamPathWithTicket)
        val opener = streamOpenerProvider()
            ?: run {
                val msg = "桌面二进制流通道未就绪"
                _state.value = DesktopViewerSessionState.Disconnected(agentId, msg)
                throw IllegalStateException(msg)
            }

        val byteStream = try {
            opener.openReadOnlyStream(issued)
        } catch (err: Throwable) {
            _state.value = DesktopViewerSessionState.Disconnected(
                agentId = agentId,
                reason = err.message ?: "连接 /vnc-ro/ 桌面流失败",
            )
            throw err
        }

        val decoder = ReadOnlyRfb38Decoder(
            sendToServer = { outbound ->
                // Guardrail: verify every outbound packet starts with an allowed read-only byte!
                assertAllowedReadOnlyClientPacket(outbound)
                byteStream.sendClientControlBytes(outbound)
            },
            onFrameUpdated = { snapshot ->
                _state.value = DesktopViewerSessionState.WatchingReadOnly(
                    agentId = agentId,
                    snapshot = snapshot,
                )
            },
        )

        synchronized(sessionLock) {
            activeAgentId = agentId
            activeStream = byteStream
            activeDecoder = decoder
        }

        byteStream.onServerBytes { chunk ->
            try {
                decoder.onServerBytes(chunk)
            } catch (err: Throwable) {
                disconnect("RFB 协议错误：${err.message}")
            }
        }
        byteStream.onClosed { closeReason ->
            val wasCurrent = synchronized(sessionLock) {
                if (activeStream === byteStream) {
                    activeStream = null
                    activeDecoder = null
                    true
                } else {
                    false
                }
            }
            if (wasCurrent) {
                _state.value = DesktopViewerSessionState.Disconnected(agentId, closeReason)
            }
        }
    }

    fun requestFullRefresh() {
        val dec = synchronized(sessionLock) { activeDecoder }
        dec?.requestFullRefresh()
    }

    /** Immediately closes the live VNC stream (e.g. on user exit or `Lifecycle.Event.ON_STOP`). */
    fun disconnect(reason: String = "已断开只读桌面观看") {
        val (prevStream, prevAgent) = synchronized(sessionLock) {
            val s = activeStream
            val a = activeAgentId
            activeStream = null
            activeDecoder = null
            activeAgentId = null
            s to a
        }
        prevStream?.close()
        if (prevAgent != null) {
            _state.value = DesktopViewerSessionState.Disconnected(prevAgent, reason)
        } else {
            _state.value = DesktopViewerSessionState.Idle
        }
    }

    private fun assertAllowedReadOnlyClientPacket(packet: ByteArray) {
        if (packet.isEmpty()) return
        if (packet.contentEquals(ReadOnlyRfb38Decoder.RFB_3_8_VERSION_BANNER)) return
        if (packet.size == 1 && packet[0].toInt() == 1) return // SecurityType=None(1) or ClientInit(shared=1)
        val msgType = packet[0]
        check(
            msgType == ReadOnlyRfb38Decoder.CLIENT_MSG_SET_PIXEL_FORMAT ||
                msgType == ReadOnlyRfb38Decoder.CLIENT_MSG_SET_ENCODINGS ||
                msgType == ReadOnlyRfb38Decoder.CLIENT_MSG_FRAMEBUFFER_UPDATE_REQUEST
        ) {
            "Security violation: blocked non-read-only RFB client message type ${msgType.toInt() and 0xff}"
        }
    }

    private fun throwIfError(statusCode: Int, body: ByteArray) {
        if (statusCode in 200..299) return
        val env = runCatching {
            LumenBoxJson.default.decodeFromString(
                ApiErrorEnvelope.serializer(),
                String(body, StandardCharsets.UTF_8),
            )
        }.getOrNull()
        if (env != null) throw ApiEnvelopeException(statusCode, env)
        throw IllegalStateException("Desktop API failed with HTTP $statusCode")
    }
}

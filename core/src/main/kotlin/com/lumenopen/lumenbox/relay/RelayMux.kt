package com.lumenopen.lumenbox.relay

import com.lumenopen.lumenbox.contract.LumenBoxJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

enum class StreamTarget(val wireName: String) {
    EVENTS("events"),
    CONVERSATION_STREAM("conversation-stream"),
    VNC_RO("vnc-ro");

    companion object {
        fun fromWire(raw: String): StreamTarget =
            entries.firstOrNull { it.wireName == raw }
                ?: throw NoiseRelayException("invalid_stream_target", "Unknown stream target: $raw")
    }
}

data class VirtualHttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

data class VirtualHttpResponse(
    val status: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

data class VirtualStreamRequest(
    val target: StreamTarget,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
)

sealed interface StreamCloseOutcome {
    /** Stream ended cleanly via an authenticated `stream_end` control frame inside ChaCha20-Poly1305. */
    data object AuthenticatedEnd : StreamCloseOutcome
    /** Stream closed locally by the caller. */
    data object LocalClose : StreamCloseOutcome
    /** Peer reset the stream with an authenticated error reason. */
    data class PeerReset(val error: String) : StreamCloseOutcome
    /** Underlying socket closed without an authenticated `stream_end` frame (truncation detected). */
    data class Truncated(val cause: Throwable) : StreamCloseOutcome
}

interface DuplexRelayStream {
    val streamId: Long
    suspend fun send(chunk: ByteArray, binary: Boolean = true)
    /** Consumes `bytes` and returns receive-window credit (`window_update`) to the peer. */
    fun acknowledgeRead(bytes: Int)
    fun close()
    fun onData(listener: (chunk: ByteArray, binary: Boolean) -> Unit)
    fun onClose(listener: (StreamCloseOutcome) -> Unit)
}

interface HostMuxHandlers {
    suspend fun onHttpRequest(req: VirtualHttpRequest, deviceId: String?): VirtualHttpResponse =
        VirtualHttpResponse(404, mapOf("content-type" to "application/json"), ByteArray(0))

    suspend fun onOpenStream(req: VirtualStreamRequest, stream: DuplexRelayStream, deviceId: String?) {
        throw UnsupportedOperationException("Streams unsupported")
    }
}

@Serializable
data class HostSleepingInfo(val at: String, val reason: String)

/**
 * Coroutine-safe, credit-based stream multiplexer over a `Noise_IK` session (`docs/110 §3.2`, `INV-2251`).
 *
 * Wire-compatible with `fakechris/lumenbox` `src/mobile-relay/mux.ts`:
 * - Control frames (`streamId = 0`, `KIND_CONTROL`): `http_request`, `http_response`, `stream_open`,
 *   `stream_opened`, `stream_end`, `stream_reset`, `window_update`, `host_sleeping`.
 * - Every outbound record encryption + socket write is serialized under [sendLock] so AEAD nonces stay
 *   strictly monotonic on the wire under high concurrency.
 * - Unauthenticated EOF before `stream_end` fails pending HTTP calls and marks open streams [StreamCloseOutcome.Truncated].
 */
class RelayMux(
    val role: Role,
    private val session: NoiseTransportSession,
    private val sendWire: (ByteArray) -> Unit,
    private val handlers: HostMuxHandlers? = null,
    private val deviceId: String? = null,
    val initialWindowBytes: Int = DEFAULT_INITIAL_WINDOW_BYTES,
    private val maxRecordBytes: Int = RecordFramer.DEFAULT_MAX_RECORD_BYTES,
    private val autoReplenishStreamWindow: Boolean = true,
    private val onHostSleeping: ((HostSleepingInfo) -> Unit)? = null,
    private val onProtocolError: ((Throwable) -> Unit)? = null,
) {
    enum class Role { CLIENT, HOST }

    companion object {
        const val DEFAULT_INITIAL_WINDOW_BYTES: Int = 256 * 1024
        private const val CONTROL_STREAM_ID: Long = 0L
        private const val MAX_FLOW_SLICE_BYTES: Int = 32 * 1024
    }

    private class OutboundCredit(var available: Int) {
        val waiters = ArrayDeque<CompletableDeferred<Unit>>()
        var closed: Boolean = false
    }

    private class PendingHttpCall(
        val deferred: CompletableDeferred<VirtualHttpResponse>,
    ) {
        var status: Int? = null
        var headers: Map<String, String>? = null
        val bodyBuffer = ByteArrayOutputStream()
    }

    private class InboundHttpAccumulator(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    ) {
        val bodyBuffer = ByteArrayOutputStream()
    }

    private class ActiveStreamState {
        val opened = CompletableDeferred<Unit>()
        val dataListeners = mutableListOf<(ByteArray, Boolean) -> Unit>()
        val closeListeners = mutableListOf<(StreamCloseOutcome) -> Unit>()
        val earlyChunks = ArrayDeque<Pair<ByteArray, Boolean>>()
        var closed: Boolean = false
        var closeOutcome: StreamCloseOutcome? = null
    }

    private val stateLock = Any()
    private val sendLock = Any()
    private val receiveLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var nextStreamId: Long = if (role == Role.CLIENT) 1L else 2L
    @Volatile
    private var closed: Boolean = false

    private val credits = HashMap<Long, OutboundCredit>()
    private val pendingHttp = HashMap<Long, PendingHttpCall>()
    private val inboundHttp = HashMap<Long, InboundHttpAccumulator>()
    private val streams = HashMap<Long, ActiveStreamState>()

    val isClosed: Boolean get() = closed

    private fun allocateStreamId(): Long = synchronized(stateLock) {
        val id = nextStreamId
        nextStreamId += 2L
        id
    }

    private fun creditForLocked(streamId: Long): OutboundCredit =
        credits.getOrPut(streamId) { OutboundCredit(initialWindowBytes) }

    private fun sendEncryptedRecord(kind: Int, streamId: Long, payload: ByteArray) {
        synchronized(sendLock) {
            if (closed) return
            val wire = RecordFramer.sealRecord(
                cipher = session.send,
                kind = kind,
                streamId = streamId,
                payload = payload,
                maxRecordBytes = maxRecordBytes,
            )
            sendWire(wire)
        }
    }

    private fun sendControlJson(obj: JsonObject) {
        if (closed) return
        val bytes = LumenBoxJson.default.encodeToString(JsonObject.serializer(), obj)
            .toByteArray(StandardCharsets.UTF_8)
        sendEncryptedRecord(RecordFramer.KIND_CONTROL, CONTROL_STREAM_ID, bytes)
    }

    private suspend fun sendFlowControlled(streamId: Long, data: ByteArray, kind: Int) {
        if (data.isEmpty()) return
        var offset = 0
        while (offset < data.size) {
            var waiter: CompletableDeferred<Unit>? = null
            var step = 0
            synchronized(stateLock) {
                val credit = creditForLocked(streamId)
                if (closed || credit.closed) {
                    throw NoiseRelayException("stream_closed", "Stream $streamId closed while sending")
                }
                if (credit.available <= 0) {
                    val w = CompletableDeferred<Unit>()
                    credit.waiters.addLast(w)
                    waiter = w
                } else {
                    step = minOf(credit.available, data.size - offset, MAX_FLOW_SLICE_BYTES)
                    credit.available -= step
                }
            }
            if (waiter != null) {
                waiter!!.await()
                continue
            }
            val slice = data.copyOfRange(offset, offset + step)
            offset += step
            sendEncryptedRecord(kind, streamId, slice)
        }
    }

    fun notifyHostSleeping(reason: String = "suspend", at: String = Instant.now().toString()) {
        sendControlJson(
            buildJsonObject {
                put("type", "host_sleeping")
                put("at", at)
                put("reason", reason)
            }
        )
    }

    suspend fun request(req: VirtualHttpRequest): VirtualHttpResponse {
        if (closed) throw NoiseRelayException("mux_closed", "RelayMux is closed")
        val streamId = allocateStreamId()
        val deferred = CompletableDeferred<VirtualHttpResponse>()
        val bodyBytes = req.body
        val hasBody = bodyBytes != null && bodyBytes.isNotEmpty()

        synchronized(stateLock) {
            if (closed) throw NoiseRelayException("mux_closed", "RelayMux is closed")
            creditForLocked(streamId)
            pendingHttp[streamId] = PendingHttpCall(deferred)
        }

        sendControlJson(
            buildJsonObject {
                put("type", "http_request")
                put("streamId", streamId)
                put("method", req.method)
                put("path", req.path)
                put(
                    "headers",
                    buildJsonObject { req.headers.forEach { (k, v) -> put(k, v) } }
                )
                put("hasBody", hasBody)
            }
        )

        if (bodyBytes != null && bodyBytes.isNotEmpty()) {
            sendFlowControlled(streamId, bodyBytes, RecordFramer.KIND_BINARY)
            sendControlJson(
                buildJsonObject {
                    put("type", "stream_end")
                    put("streamId", streamId)
                }
            )
        }

        return deferred.await()
    }

    suspend fun openStream(req: VirtualStreamRequest): DuplexRelayStream {
        if (closed) throw NoiseRelayException("mux_closed", "RelayMux is closed")
        val streamId = allocateStreamId()
        val state = ActiveStreamState()
        synchronized(stateLock) {
            if (closed) throw NoiseRelayException("mux_closed", "RelayMux is closed")
            creditForLocked(streamId)
            streams[streamId] = state
        }

        sendControlJson(
            buildJsonObject {
                put("type", "stream_open")
                put("streamId", streamId)
                put("target", req.target.wireName)
                put("path", req.path)
                if (req.headers.isNotEmpty()) {
                    put("headers", buildJsonObject { req.headers.forEach { (k, v) -> put(k, v) } })
                }
            }
        )

        state.opened.await()
        return buildStreamHandle(streamId, state)
    }

    private fun buildStreamHandle(streamId: Long, state: ActiveStreamState): DuplexRelayStream =
        object : DuplexRelayStream {
            override val streamId: Long = streamId

            override suspend fun send(chunk: ByteArray, binary: Boolean) {
                sendFlowControlled(
                    streamId,
                    chunk,
                    if (binary) RecordFramer.KIND_BINARY else RecordFramer.KIND_TEXT
                )
            }

            override fun acknowledgeRead(bytes: Int) {
                if (bytes <= 0 || closed) return
                sendControlJson(
                    buildJsonObject {
                        put("type", "window_update")
                        put("streamId", streamId)
                        put("deltaBytes", bytes)
                    }
                )
            }

            override fun close() {
                val listeners: List<(StreamCloseOutcome) -> Unit>
                synchronized(stateLock) {
                    if (state.closed) return
                    state.closed = true
                    state.closeOutcome = StreamCloseOutcome.LocalClose
                    releaseStreamLocked(streamId)
                    listeners = state.closeListeners.toList()
                }
                sendControlJson(
                    buildJsonObject {
                        put("type", "stream_end")
                        put("streamId", streamId)
                    }
                )
                listeners.forEach { it(StreamCloseOutcome.LocalClose) }
            }

            override fun onData(listener: (chunk: ByteArray, binary: Boolean) -> Unit) {
                val buffered = mutableListOf<Pair<ByteArray, Boolean>>()
                var shouldReleaseNow = false
                synchronized(stateLock) {
                    state.dataListeners.add(listener)
                    while (state.earlyChunks.isNotEmpty()) {
                        buffered.add(state.earlyChunks.removeFirst())
                    }
                    if (state.closed && state.earlyChunks.isEmpty()) {
                        shouldReleaseNow = true
                    }
                }
                for ((chunk, binary) in buffered) {
                    listener(chunk, binary)
                }
                if (shouldReleaseNow) {
                    synchronized(stateLock) { releaseStreamLocked(streamId) }
                }
            }

            override fun onClose(listener: (StreamCloseOutcome) -> Unit) {
                val alreadyClosedOutcome: StreamCloseOutcome? = synchronized(stateLock) {
                    if (state.closed) {
                        state.closeOutcome ?: StreamCloseOutcome.AuthenticatedEnd
                    } else {
                        state.closeListeners.add(listener)
                        null
                    }
                }
                if (alreadyClosedOutcome != null) {
                    listener(alreadyClosedOutcome)
                }
            }
        }

    fun receiveWire(wire: ByteArray) {
        if (closed) return
        val record = try {
            synchronized(receiveLock) {
                RecordFramer.openRecord(session.receive, wire, maxRecordBytes)
            }
        } catch (err: Throwable) {
            closeWithTransportError(err)
            onProtocolError?.invoke(err)
            return
        }

        if (record.kind == RecordFramer.KIND_CONTROL) {
            handleControlPayload(record.payload)
            return
        }

        val payloadLen = record.payload.size
        var replenishHttpOrAutoStream = false
        var dataTargets: List<(ByteArray, Boolean) -> Unit> = emptyList()
        val isBinary = record.kind == RecordFramer.KIND_BINARY

        synchronized(stateLock) {
            val inReq = inboundHttp[record.streamId]
            if (inReq != null) {
                inReq.bodyBuffer.write(record.payload)
                replenishHttpOrAutoStream = payloadLen > 0
                return@synchronized
            }
            val pending = pendingHttp[record.streamId]
            if (pending != null) {
                pending.bodyBuffer.write(record.payload)
                replenishHttpOrAutoStream = payloadLen > 0
                return@synchronized
            }
            val stream = streams[record.streamId]
            if (stream != null && !stream.closed) {
                replenishHttpOrAutoStream = payloadLen > 0 && autoReplenishStreamWindow
                if (stream.dataListeners.isEmpty()) {
                    stream.earlyChunks.addLast(record.payload to isBinary)
                } else {
                    dataTargets = stream.dataListeners.toList()
                }
            }
        }

        if (replenishHttpOrAutoStream) {
            sendControlJson(
                buildJsonObject {
                    put("type", "window_update")
                    put("streamId", record.streamId)
                    put("deltaBytes", payloadLen)
                }
            )
        }

        for (fn in dataTargets) {
            fn(record.payload, isBinary)
        }
    }

    private fun handleControlPayload(payload: ByteArray) {
        val obj = try {
            LumenBoxJson.default.parseToJsonElement(payload.toString(StandardCharsets.UTF_8)).jsonObject
        } catch (err: Throwable) {
            closeWithTransportError(err)
            onProtocolError?.invoke(err)
            return
        }

        when (obj["type"]?.jsonPrimitive?.contentOrNull) {
            "window_update" -> {
                val streamId = obj["streamId"]?.jsonPrimitive?.longOrNull ?: return
                val delta = obj["deltaBytes"]?.jsonPrimitive?.intOrNull ?: return
                if (delta <= 0) return
                val toWake = mutableListOf<CompletableDeferred<Unit>>()
                synchronized(stateLock) {
                    val credit = creditForLocked(streamId)
                    credit.available += delta
                    while (credit.waiters.isNotEmpty()) {
                        toWake.add(credit.waiters.removeFirst())
                    }
                }
                toWake.forEach { it.complete(Unit) }
            }

            "host_sleeping" -> {
                val at = obj["at"]?.jsonPrimitive?.contentOrNull ?: ""
                val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: "suspend"
                onHostSleeping?.invoke(HostSleepingInfo(at = at, reason = reason))
            }

            "http_request" -> {
                if (role != Role.HOST) return
                val streamId = obj["streamId"]?.jsonPrimitive?.longOrNull ?: return
                val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return
                val headers = decodeStringMap(obj["headers"] as? JsonObject)
                val hasBody = obj["hasBody"]?.jsonPrimitive?.booleanOrNull ?: false
                synchronized(stateLock) { creditForLocked(streamId) }
                if (!hasBody) {
                    scope.launch {
                        dispatchHostHttpRequest(streamId, VirtualHttpRequest(method, path, headers, null))
                    }
                } else {
                    synchronized(stateLock) {
                        inboundHttp[streamId] = InboundHttpAccumulator(method, path, headers)
                    }
                }
            }

            "http_response" -> {
                val streamId = obj["streamId"]?.jsonPrimitive?.longOrNull ?: return
                val status = obj["status"]?.jsonPrimitive?.intOrNull ?: return
                val headers = decodeStringMap(obj["headers"] as? JsonObject)
                val hasBody = obj["hasBody"]?.jsonPrimitive?.booleanOrNull ?: false
                var completedCall: PendingHttpCall? = null
                synchronized(stateLock) {
                    val pending = pendingHttp[streamId] ?: return
                    pending.status = status
                    pending.headers = headers
                    if (!hasBody) {
                        pendingHttp.remove(streamId)
                        releaseStreamLocked(streamId)
                        completedCall = pending
                    }
                }
                completedCall?.deferred?.complete(VirtualHttpResponse(status, headers, ByteArray(0)))
            }

            "stream_open" -> {
                if (role != Role.HOST) return
                val streamId = obj["streamId"]?.jsonPrimitive?.longOrNull ?: return
                val targetRaw = obj["target"]?.jsonPrimitive?.contentOrNull ?: return
                val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return
                val headers = decodeStringMap(obj["headers"] as? JsonObject)
                val state = ActiveStreamState().apply { opened.complete(Unit) }
                synchronized(stateLock) {
                    creditForLocked(streamId)
                    streams[streamId] = state
                }
                val h = handlers
                if (h == null) {
                    synchronized(stateLock) { releaseStreamLocked(streamId) }
                    sendControlJson(
                        buildJsonObject {
                            put("type", "stream_reset")
                            put("streamId", streamId)
                            put("error", "streams_unsupported")
                        }
                    )
                    return
                }
                val handle = buildStreamHandle(streamId, state)
                sendControlJson(
                    buildJsonObject {
                        put("type", "stream_opened")
                        put("streamId", streamId)
                    }
                )
                scope.launch {
                    try {
                        h.onOpenStream(
                            VirtualStreamRequest(StreamTarget.fromWire(targetRaw), path, headers),
                            handle,
                            deviceId
                        )
                    } catch (err: Throwable) {
                        sendControlJson(
                            buildJsonObject {
                                put("type", "stream_reset")
                                put("streamId", streamId)
                                put("error", err.message ?: "stream_error")
                            }
                        )
                    }
                }
            }

            "stream_opened" -> {
                val streamId = obj["streamId"]?.jsonPrimitive?.longOrNull ?: return
                val state = synchronized(stateLock) { streams[streamId] }
                state?.opened?.complete(Unit)
            }

            "stream_end" -> {
                val streamId = obj["streamId"]?.jsonPrimitive?.longOrNull ?: return
                var hostReqToDispatch: VirtualHttpRequest? = null
                var finishedHttp: Pair<PendingHttpCall, VirtualHttpResponse>? = null
                var streamListeners: List<(StreamCloseOutcome) -> Unit> = emptyList()

                synchronized(stateLock) {
                    val inReq = inboundHttp.remove(streamId)
                    if (inReq != null) {
                        hostReqToDispatch = VirtualHttpRequest(
                            inReq.method,
                            inReq.path,
                            inReq.headers,
                            inReq.bodyBuffer.toByteArray()
                        )
                        return@synchronized
                    }
                    val pending = pendingHttp.remove(streamId)
                    if (pending != null) {
                        releaseStreamLocked(streamId)
                        finishedHttp = pending to VirtualHttpResponse(
                            status = pending.status ?: 200,
                            headers = pending.headers ?: emptyMap(),
                            body = pending.bodyBuffer.toByteArray()
                        )
                        return@synchronized
                    }
                    val stream = streams[streamId]
                    if (stream != null && !stream.closed) {
                        stream.closed = true
                        stream.closeOutcome = StreamCloseOutcome.AuthenticatedEnd
                        if (stream.earlyChunks.isEmpty()) {
                            releaseStreamLocked(streamId)
                        }
                        streamListeners = stream.closeListeners.toList()
                    }
                }

                hostReqToDispatch?.let { req ->
                    scope.launch { dispatchHostHttpRequest(streamId, req) }
                }
                finishedHttp?.let { (call, res) -> call.deferred.complete(res) }
                streamListeners.forEach { it(StreamCloseOutcome.AuthenticatedEnd) }
            }

            "stream_reset" -> {
                val streamId = obj["streamId"]?.jsonPrimitive?.longOrNull ?: return
                val errMsg = obj["error"]?.jsonPrimitive?.contentOrNull ?: "stream_reset"
                val err = NoiseRelayException("stream_reset", errMsg)
                var failedHttp: PendingHttpCall? = null
                var failedStream: ActiveStreamState? = null
                var listeners: List<(StreamCloseOutcome) -> Unit> = emptyList()

                synchronized(stateLock) {
                    failedHttp = pendingHttp.remove(streamId)
                    failedStream = streams[streamId]
                    if (failedStream != null) {
                        failedStream!!.closed = true
                        failedStream!!.closeOutcome = StreamCloseOutcome.PeerReset(errMsg)
                        listeners = failedStream!!.closeListeners.toList()
                    }
                    releaseStreamLocked(streamId)
                }

                failedHttp?.deferred?.completeExceptionally(err)
                failedStream?.opened?.completeExceptionally(err)
                listeners.forEach { it(StreamCloseOutcome.PeerReset(errMsg)) }
            }
        }
    }

    private suspend fun dispatchHostHttpRequest(streamId: Long, req: VirtualHttpRequest) {
        val h = handlers
        if (h == null) {
            sendControlJson(
                buildJsonObject {
                    put("type", "http_response")
                    put("streamId", streamId)
                    put("status", 404)
                    put("headers", buildJsonObject { put("content-type", "application/json") })
                    put("hasBody", false)
                }
            )
            synchronized(stateLock) { releaseStreamLocked(streamId) }
            return
        }
        try {
            val res = h.onHttpRequest(req, deviceId)
            val hasBody = res.body.isNotEmpty()
            sendControlJson(
                buildJsonObject {
                    put("type", "http_response")
                    put("streamId", streamId)
                    put("status", res.status)
                    put("headers", buildJsonObject { res.headers.forEach { (k, v) -> put(k, v) } })
                    put("hasBody", hasBody)
                }
            )
            if (hasBody) {
                sendFlowControlled(streamId, res.body, RecordFramer.KIND_BINARY)
                sendControlJson(
                    buildJsonObject {
                        put("type", "stream_end")
                        put("streamId", streamId)
                    }
                )
            }
            synchronized(stateLock) { releaseStreamLocked(streamId) }
        } catch (err: Throwable) {
            sendControlJson(
                buildJsonObject {
                    put("type", "stream_reset")
                    put("streamId", streamId)
                    put("error", err.message ?: "handler_error")
                }
            )
            synchronized(stateLock) { releaseStreamLocked(streamId) }
        }
    }

    private fun releaseStreamLocked(streamId: Long) {
        val credit = credits.remove(streamId)
        if (credit != null) {
            credit.closed = true
            while (credit.waiters.isNotEmpty()) {
                credit.waiters.removeFirst().complete(Unit)
            }
        }
        streams.remove(streamId)
        inboundHttp.remove(streamId)
    }

    /**
     * Closes the multiplexer. When invoked because the underlying socket closed unexpectedly
     * ([authenticated] = false), any still-open HTTP call or stream that has NOT received an
     * authenticated `stream_end` frame is rejected as truncated (`docs/110 §3.2`).
     */
    fun close(reason: Throwable? = null, authenticated: Boolean = false) {
        val pendingCalls: List<PendingHttpCall>
        val openStreams: List<Pair<ActiveStreamState, List<(StreamCloseOutcome) -> Unit>>>
        val creditWaiters = mutableListOf<CompletableDeferred<Unit>>()

        synchronized(stateLock) {
            if (closed) return
            closed = true
            pendingCalls = pendingHttp.values.toList()
            pendingHttp.clear()

            openStreams = streams.values.filter { !it.closed }.map { st ->
                st.closed = true
                val outcome = if (authenticated) {
                    StreamCloseOutcome.LocalClose
                } else {
                    StreamCloseOutcome.Truncated(
                        reason ?: NoiseRelayException("truncated_stream", "Unauthenticated EOF before stream_end")
                    )
                }
                st.closeOutcome = outcome
                st to st.closeListeners.toList()
            }
            streams.clear()
            inboundHttp.clear()

            for (c in credits.values) {
                c.closed = true
                while (c.waiters.isNotEmpty()) creditWaiters.add(c.waiters.removeFirst())
            }
            credits.clear()
        }

        val err = if (authenticated) {
            reason ?: NoiseRelayException("mux_closed", "RelayMux closed")
        } else {
            NoiseRelayException(
                "truncated_stream",
                reason?.message ?: "Unauthenticated socket EOF before stream_end",
                reason
            )
        }
        pendingCalls.forEach { it.deferred.completeExceptionally(err) }
        for ((st, listeners) in openStreams) {
            st.opened.completeExceptionally(err)
            val outcome = st.closeOutcome!!
            listeners.forEach { it(outcome) }
        }
        creditWaiters.forEach { it.complete(Unit) }
        scope.cancel()
    }

    private fun closeWithTransportError(err: Throwable) {
        close(reason = err, authenticated = false)
    }

    private fun decodeStringMap(obj: JsonObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, String>(obj.size)
        for ((k, v) in obj) {
            (v as? JsonPrimitive)?.contentOrNull?.let { out[k] = it }
        }
        return out
    }
}

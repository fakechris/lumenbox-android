package com.lumenopen.lumenbox.conversation

import com.lumenopen.lumenbox.auth.OrganisationTokenManager
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.relay.RelayMux
import com.lumenopen.lumenbox.relay.StreamTarget
import com.lumenopen.lumenbox.relay.VirtualStreamRequest
import com.lumenopen.lumenbox.storage.InstallationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlin.coroutines.coroutineContext

data class SseFrame(
    val id: String? = null,
    val event: String = "message",
    val data: String,
    val retryMillis: Long? = null,
)

/**
 * Stateful incremental Server-Sent Events (WHATWG SSE) line decoder (`docs/106 §4.2`, `docs/110 §2`).
 *
 * Handles arbitrary UTF-8 chunk splits across `\n`, `\r\n`, or `\r`, multi-line `data:` blocks,
 * `event:`, `id:` (`Last-Event-ID`), and comment keep-alives (`:`).
 */
class SseFrameParser(
    private val onFrame: (SseFrame) -> Unit,
) {
    private val lineBuffer = StringBuilder()
    private var pendingCarriageReturn = false

    private var currentId: String? = null
    private var currentEvent: String? = null
    private var currentRetry: Long? = null
    private val dataLines = mutableListOf<String>()

    fun feed(textChunk: String) {
        for (ch in textChunk) {
            if (pendingCarriageReturn) {
                pendingCarriageReturn = false
                if (ch == '\n') continue
            }
            when (ch) {
                '\r' -> {
                    pendingCarriageReturn = true
                    processLine(lineBuffer.toString())
                    lineBuffer.setLength(0)
                }
                '\n' -> {
                    processLine(lineBuffer.toString())
                    lineBuffer.setLength(0)
                }
                else -> lineBuffer.append(ch)
            }
        }
    }

    fun finish() {
        if (pendingCarriageReturn || lineBuffer.isNotEmpty()) {
            pendingCarriageReturn = false
            processLine(lineBuffer.toString())
            lineBuffer.setLength(0)
        }
        dispatchEventIfBuffered()
    }

    private fun processLine(line: String) {
        if (line.isEmpty()) {
            dispatchEventIfBuffered()
            return
        }
        if (line.startsWith(':')) {
            return // SSE comment / keep-alive
        }
        val colonIdx = line.indexOf(':')
        val field: String
        val rawVal: String
        if (colonIdx < 0) {
            field = line
            rawVal = ""
        } else {
            field = line.substring(0, colonIdx)
            val afterColon = line.substring(colonIdx + 1)
            rawVal = if (afterColon.startsWith(' ')) afterColon.substring(1) else afterColon
        }

        when (field) {
            "event" -> currentEvent = rawVal
            "data" -> dataLines.add(rawVal)
            "id" -> if (!rawVal.contains('\u0000')) {
                currentId = rawVal
            }
            "retry" -> rawVal.toLongOrNull()?.takeIf { it >= 0L }?.let { currentRetry = it }
        }
    }

    private fun dispatchEventIfBuffered() {
        if (dataLines.isEmpty()) {
            currentEvent = null
            currentRetry = null
            return
        }
        val joinedData = dataLines.joinToString("\n")
        val eventName = currentEvent?.takeIf { it.isNotEmpty() } ?: "message"
        val retry = currentRetry
        dataLines.clear()
        currentEvent = null
        currentRetry = null
        onFrame(
            SseFrame(
                id = currentId,
                event = eventName,
                data = joinedData,
                retryMillis = retry,
            )
        )
    }
}

/**
 * Dual-transport SSE stream connector (`docs/106 §4.2`, `docs/110 §2, §3.2`):
 * - Opens `GET /api/v1/events` (`StreamTarget.EVENTS`) or `GET /api/v1/conversations/{id}/stream`
 *   (`StreamTarget.CONVERSATION_STREAM`).
 * - Runs identically over `RelayMux.openStream(...)` in Personal Mac mode and authenticated
 *   `HttpURLConnection` streaming in Organisation HTTPS mode (enforcing `401 access-removed` wipe).
 */
fun interface LiveSseConnector {
    fun openSseStream(
        target: StreamTarget,
        path: String,
        lastEventId: String?,
    ): Flow<SseFrame>

    companion object {
        fun overRelayMux(muxProvider: () -> RelayMux?): LiveSseConnector = LiveSseConnector { target, path, lastEventId ->
            callbackFlow {
                val mux = muxProvider()
                    ?: run {
                        close(IllegalStateException("Personal Mac relay session is not connected"))
                        return@callbackFlow
                    }
                val headers = LinkedHashMap<String, String>().apply {
                    put("Accept", "text/event-stream")
                    if (!lastEventId.isNullOrBlank()) {
                        put("Last-Event-ID", lastEventId)
                    }
                }
                val stream = try {
                    mux.openStream(VirtualStreamRequest(target = target, path = path, headers = headers))
                } catch (err: Throwable) {
                    close(err)
                    return@callbackFlow
                }

                val parser = SseFrameParser { frame ->
                    trySend(frame)
                }
                val lock = Any()
                stream.onData { chunk, _ ->
                    val text = String(chunk, StandardCharsets.UTF_8)
                    synchronized(lock) {
                        parser.feed(text)
                    }
                }
                stream.onClose {
                    synchronized(lock) {
                        parser.finish()
                    }
                    close()
                }
                awaitClose {
                    runCatching { stream.close() }
                }
            }
        }

        fun overOrganisationHttps(
            connectionId: String,
            store: InstallationStore,
            tokenManager: OrganisationTokenManager,
        ): LiveSseConnector = LiveSseConnector { _, path, lastEventId ->
            flow {
                val connection = store.findById(connectionId)
                    ?: throw IllegalStateException("Organisation connection $connectionId missing")
                val baseOrigin = connection.serverOriginOrRelayUrl.trimEnd('/')
                var token = tokenManager.validAccessToken(connectionId, forceRefresh = false)
                var conn = openHttpsStreamConnection(baseOrigin, path, token, lastEventId)
                if (conn.responseCode == 401) {
                    val errBody = conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                    conn.disconnect()
                    checkAccessRemovedOrNull(errBody)?.let { env ->
                        tokenManager.clearMemoryToken(connectionId)
                        store.wipeConnection(connectionId)
                        throw ApiEnvelopeException(401, env)
                    }
                    token = tokenManager.validAccessToken(connectionId, forceRefresh = true)
                    conn = openHttpsStreamConnection(baseOrigin, path, token, lastEventId)
                }
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val errBytes = conn.errorStream?.use { it.readBytes() } ?: ByteArray(0)
                        checkAccessRemovedOrNull(errBytes)?.let { env ->
                            if (code == 401) {
                                tokenManager.clearMemoryToken(connectionId)
                                store.wipeConnection(connectionId)
                            }
                            throw ApiEnvelopeException(code, env)
                        }
                        throw IllegalStateException("SSE stream failed with HTTP $code")
                    }
                    val buffered = ArrayDeque<SseFrame>()
                    val parser = SseFrameParser { frame -> buffered.addLast(frame) }
                    BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                        val charBuf = CharArray(2048)
                        while (coroutineContext.isActive) {
                            val read = reader.read(charBuf)
                            if (read < 0) break
                            parser.feed(String(charBuf, 0, read))
                            while (buffered.isNotEmpty()) {
                                emit(buffered.removeFirst())
                            }
                        }
                        parser.finish()
                        while (buffered.isNotEmpty()) {
                            emit(buffered.removeFirst())
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }.flowOn(Dispatchers.IO)
        }

        private fun openHttpsStreamConnection(
            baseOrigin: String,
            path: String,
            bearerToken: String,
            lastEventId: String?,
        ): HttpURLConnection =
            (URI.create("$baseOrigin$path").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 90_000
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Authorization", "Bearer $bearerToken")
                if (!lastEventId.isNullOrBlank()) {
                    setRequestProperty("Last-Event-ID", lastEventId)
                }
            }

        private fun checkAccessRemovedOrNull(body: ByteArray): ApiErrorEnvelope? {
            if (body.isEmpty()) return null
            return runCatching {
                LumenBoxJson.default.decodeFromString(
                    ApiErrorEnvelope.serializer(),
                    String(body, StandardCharsets.UTF_8),
                )
            }.getOrNull()?.takeIf { it.isAccessRemoved }
        }
    }
}

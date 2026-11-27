package com.lumenopen.lumenbox.transport

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.relay.RelayMux
import com.lumenopen.lumenbox.relay.VirtualHttpRequest
import com.lumenopen.lumenbox.storage.InstallationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.UUID

data class TransportRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val idempotencyKey: String? = if (method.equals("POST", ignoreCase = true)) UUID.randomUUID().toString() else null,
)

data class TransportResponse(
    val statusCode: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
)

/**
 * Abstract `/api/v1` transport (`docs/106 §4.1`, `docs/110 §0.3`):
 * identical generated API calls run over direct HTTPS or multiplexed `Noise_IK` relay streams.
 */
fun interface ApiTransport {
    suspend fun send(request: TransportRequest): TransportResponse
}

object CrossProcessFileLock {
    fun <T> withLock(lockFile: File, block: () -> T): T {
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.lock().use {
                return block()
            }
        }
    }
}

class DirectHttpsTransport(
    private val baseOrigin: String,
    private val connectionId: String,
    private val store: InstallationStore,
    @Volatile private var accessTokenInMemory: String? = null,
    private val roundTrip: suspend (TransportRequest, Map<String, String>) -> TransportResponse = defaultUrlConnectionRoundTrip(baseOrigin),
) : ApiTransport {

    override suspend fun send(request: TransportRequest): TransportResponse {
        val mergedHeaders = LinkedHashMap<String, String>(request.headers)
        request.idempotencyKey?.let { mergedHeaders["Idempotency-Key"] = it }
        accessTokenInMemory?.let { mergedHeaders["Authorization"] = "Bearer $it" }

        val response = roundTrip(request, mergedHeaders)
        if (response.statusCode == 401 && response.body.isNotEmpty()) {
            val parsed = runCatching {
                LumenBoxJson.default.decodeFromString(
                    ApiErrorEnvelope.serializer(),
                    response.body.toString(StandardCharsets.UTF_8)
                )
            }.getOrNull()
            if (parsed != null && parsed.isAccessRemoved) {
                store.wipeConnection(connectionId)
                throw ApiEnvelopeException(401, parsed)
            }
        }
        return response
    }

    companion object {
        /** Uses `HttpURLConnection` so `:core` runs on both Android ART (`android.jar`) and OpenJDK 17. */
        private fun defaultUrlConnectionRoundTrip(baseOrigin: String): suspend (TransportRequest, Map<String, String>) -> TransportResponse {
            val trimmedBase = baseOrigin.trimEnd('/')
            return { req, headers ->
                withContext(Dispatchers.IO) {
                    val conn = (URI.create("$trimmedBase${req.path}").toURL().openConnection() as HttpURLConnection).apply {
                        requestMethod = req.method.uppercase()
                        connectTimeout = 10_000
                        readTimeout = 30_000
                        useCaches = false
                        instanceFollowRedirects = false
                        headers
                            .filterKeys { !it.equals("cookie", ignoreCase = true) }
                            .forEach { (k, v) -> setRequestProperty(k, v) }
                        if (req.body != null) {
                            doOutput = true
                            outputStream.use { it.write(req.body) }
                        }
                    }
                    try {
                        val code = conn.responseCode
                        val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                        val bodyBytes = stream?.use { it.readBytes() } ?: ByteArray(0)
                        val flatHeaders = conn.headerFields
                            .filterKeys { it != null }
                            .mapKeys { it.key!!.lowercase() }
                            .mapValues { it.value.firstOrNull().orEmpty() }
                        TransportResponse(code, flatHeaders, bodyBytes)
                    } finally {
                        conn.disconnect()
                    }
                }
            }
        }
    }
}

class EncryptedRelayTransport(
    val directUrls: List<String>,
    val relayUrl: String,
    private val probeDirect: suspend (String) -> Boolean,
    private val exchangeOverNoiseIk: suspend (SelectedPath, String, TransportRequest) -> TransportResponse,
) : ApiTransport {
    enum class SelectedPath { DIRECT, RELAY }

    suspend fun selectCandidate(): Pair<SelectedPath, String> {
        for (url in directUrls) {
            if (probeDirect(url)) return SelectedPath.DIRECT to url
        }
        return SelectedPath.RELAY to relayUrl
    }

    override suspend fun send(request: TransportRequest): TransportResponse {
        val (path, endpoint) = selectCandidate()
        return exchangeOverNoiseIk(path, endpoint, request)
    }

    companion object {
        /** Adapts an active [RelayMux] into an [ApiTransport] carrying `/api/v1` requests with `Idempotency-Key`. */
        fun overMux(mux: RelayMux): ApiTransport = ApiTransport { request ->
            val merged = LinkedHashMap<String, String>(request.headers)
            request.idempotencyKey?.let { merged["Idempotency-Key"] = it }
            val res = mux.request(
                VirtualHttpRequest(
                    method = request.method,
                    path = request.path,
                    headers = merged,
                    body = request.body,
                )
            )
            TransportResponse(statusCode = res.status, headers = res.headers, body = res.body)
        }
    }
}

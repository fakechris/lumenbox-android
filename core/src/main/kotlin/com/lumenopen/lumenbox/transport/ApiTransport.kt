package com.lumenopen.lumenbox.transport

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.storage.InstallationStore
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
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
    private val roundTrip: suspend (TransportRequest, Map<String, String>) -> TransportResponse = defaultJdkRoundTrip(baseOrigin),
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
        private fun defaultJdkRoundTrip(baseOrigin: String): suspend (TransportRequest, Map<String, String>) -> TransportResponse {
            val client = HttpClient.newBuilder().build()
            val trimmedBase = baseOrigin.trimEnd('/')
            return { req, headers ->
                val builder = HttpRequest.newBuilder(URI.create("$trimmedBase${req.path}"))
                headers.forEach { (k, v) -> builder.header(k, v) }
                val publisher = req.body?.let { HttpRequest.BodyPublishers.ofByteArray(it) }
                    ?: HttpRequest.BodyPublishers.noBody()
                builder.method(req.method.uppercase(), publisher)
                val res = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
                val flatHeaders = res.headers().map().mapValues { it.value.firstOrNull().orEmpty() }
                TransportResponse(res.statusCode(), flatHeaders, res.body())
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
}

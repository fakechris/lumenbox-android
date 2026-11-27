package com.lumenopen.lumenbox.auth

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.storage.KeyStoreVault
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.CrossProcessFileLock
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class RefreshRequestPayload(
    val refreshToken: String,
)

@Serializable
private data class PendingRefreshAttempt(
    val refreshTokenSha256Hex: String,
    val idempotencyKey: String,
)

private data class InMemoryAccessToken(
    val value: String,
    val expiresAtEpochMillis: Long,
    val issuedForRefreshTokenSha256Hex: String,
)

/**
 * Hardware-backed rotating token lifecycle manager (`docs/106 §4.3`, `docs/110 §4.2, §4.5`, INV-2255).
 *
 * Security invariants:
 * 1. **Access tokens live 15 minutes in memory only** — never persisted to disk or vault, and no
 *    `agentbox_who` cookie is ever accepted or sent.
 * 2. **Cross-process & in-process serialization**: concurrent refreshes across coroutines or between the
 *    foreground app and the background Wake-Only notification service serialize through an in-process
 *    [Mutex] plus [CrossProcessFileLock].
 * 3. **Idempotent retry protection**: before calling `POST /api/v1/auth/refresh`, a stable
 *    `Idempotency-Key` bound to the current refresh token's SHA-256 is persisted in [KeyStoreVault];
 *    if a network drop loses the response, the retry sends the exact same `Idempotency-Key` so the
 *    server replays the rotated token pair inside its 30s grace window instead of revoking the family.
 * 4. **Immediate remote wipe**: any `401` with `code == "access-removed"` wipes that organisation's
 *    vault namespace, in-memory token, and encrypted offline cache directory immediately.
 */
class OrganisationTokenManager(
    private val store: InstallationStore,
    private val vault: KeyStoreVault,
    private val lockDirectory: File,
    private val refreshRoundTrip: suspend (baseOrigin: String, request: TransportRequest) -> TransportResponse,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val proactiveRefreshSkewMillis: Long = 30_000L,
) {
    companion object {
        const val DEFAULT_ACCESS_TOKEN_TTL_SECONDS = 900L // 15 minutes
        private const val PENDING_REFRESH_KEY = "pendingRefreshAttempt"
    }

    private val memoryTokens = ConcurrentHashMap<String, InMemoryAccessToken>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    init {
        lockDirectory.mkdirs()
    }

    fun seedAuthenticatedSession(
        connectionId: String,
        accessToken: String,
        expiresInSeconds: Long = DEFAULT_ACCESS_TOKEN_TTL_SECONDS,
        refreshToken: String,
    ) {
        store.saveRefreshToken(connectionId, refreshToken)
        vault.write("conn.$connectionId", PENDING_REFRESH_KEY, ByteArray(0))
        val rtHash = sha256Hex(refreshToken)
        memoryTokens[connectionId] = InMemoryAccessToken(
            value = accessToken,
            expiresAtEpochMillis = clockMillis() + expiresInSeconds.coerceAtLeast(1L) * 1_000L,
            issuedForRefreshTokenSha256Hex = rtHash,
        )
    }

    fun peekAccessTokenInMemory(connectionId: String): String? {
        val entry = memoryTokens[connectionId] ?: return null
        if (clockMillis() + proactiveRefreshSkewMillis >= entry.expiresAtEpochMillis) return null
        return entry.value
    }

    fun clearMemoryToken(connectionId: String) {
        memoryTokens.remove(connectionId)
    }

    /**
     * Returns a valid in-memory access token, rotating the stored refresh token under
     * [CrossProcessFileLock] if expired, missing, or [forceRefresh] is true.
     */
    suspend fun validAccessToken(connectionId: String, forceRefresh: Boolean = false): String {
        if (!forceRefresh) {
            peekAccessTokenInMemory(connectionId)?.let { return it }
        }
        val previousToken = memoryTokens[connectionId]?.value
        val mutex = mutexes.computeIfAbsent(connectionId) { Mutex() }
        return mutex.withLock {
            if (!forceRefresh) {
                peekAccessTokenInMemory(connectionId)?.let { return@withLock it }
            } else {
                // Another coroutine may have already rotated while we waited on the mutex!
                val current = memoryTokens[connectionId]
                if (current != null &&
                    current.value != previousToken &&
                    clockMillis() + proactiveRefreshSkewMillis < current.expiresAtEpochMillis
                ) {
                    return@withLock current.value
                }
            }

            withContext(Dispatchers.IO) {
                val lockFile = File(lockDirectory, "refresh-$connectionId.lock")
                CrossProcessFileLock.withLock(lockFile) {
                    rotateUnderCrossProcessLock(connectionId, previousToken, forceRefresh)
                }
            }
        }
    }

    /**
     * Creates an [ApiTransport] for [connectionId] that automatically attaches the 15-minute
     * in-memory `Bearer` token, refreshes once on an expired token `401`, and wipes immediately
     * on `401 code: "access-removed"`.
     */
    fun createAuthenticatedTransport(
        connectionId: String,
        roundTrip: suspend (baseOrigin: String, request: TransportRequest, headers: Map<String, String>) -> TransportResponse,
    ): ApiTransport = ApiTransport { request ->
        val conn = store.findById(connectionId)
            ?: throw OrganisationAuthException("connection_missing", "组织连接不存在或已被远程擦除")
        val token = validAccessToken(connectionId, forceRefresh = false)
        val firstResponse = executeOnce(conn.serverOriginOrRelayUrl, connectionId, request, token, roundTrip)
        if (firstResponse.statusCode != 401) {
            return@ApiTransport firstResponse
        }
        // Note: if 401 was `access-removed`, `executeOnce` already wiped and threw ApiEnvelopeException!
        val refreshedToken = validAccessToken(connectionId, forceRefresh = true)
        executeOnce(conn.serverOriginOrRelayUrl, connectionId, request, refreshedToken, roundTrip)
    }

    private fun rotateUnderCrossProcessLock(
        connectionId: String,
        previousAccessToken: String?,
        forceRefresh: Boolean,
    ): String {
        val connection = store.findById(connectionId)
            ?: throw OrganisationAuthException("connection_missing", "组织连接不存在或已被远程擦除")
        val storedRefreshToken = store.readRefreshToken(connectionId)
            ?: throw OrganisationAuthException("missing_refresh_token", "缺少设备刷新凭据，请重新登录")
        val storedRtHash = sha256Hex(storedRefreshToken)

        val currentMem = memoryTokens[connectionId]
        if (currentMem != null && clockMillis() + proactiveRefreshSkewMillis < currentMem.expiresAtEpochMillis) {
            if (!forceRefresh || currentMem.value != previousAccessToken || currentMem.issuedForRefreshTokenSha256Hex != storedRtHash) {
                return currentMem.value
            }
        }

        // Reuse the persisted Idempotency-Key if an earlier attempt for this exact refreshToken crashed/dropped!
        val existingAttempt = readPendingAttempt(connectionId)
        val idempotencyKey = if (existingAttempt != null && existingAttempt.refreshTokenSha256Hex == storedRtHash) {
            existingAttempt.idempotencyKey
        } else {
            UUID.randomUUID().toString().also { freshKey ->
                writePendingAttempt(connectionId, PendingRefreshAttempt(storedRtHash, freshKey))
            }
        }

        val reqBody = LumenBoxJson.default.encodeToString(
            RefreshRequestPayload.serializer(),
            RefreshRequestPayload(refreshToken = storedRefreshToken),
        ).toByteArray(StandardCharsets.UTF_8)

        val response = kotlinx.coroutines.runBlocking {
            refreshRoundTrip(
                connection.serverOriginOrRelayUrl,
                TransportRequest(
                    method = "POST",
                    path = "/api/v1/auth/refresh",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                        "Idempotency-Key" to idempotencyKey,
                    ),
                    body = reqBody,
                    idempotencyKey = idempotencyKey,
                ),
            )
        }

        if (response.statusCode == 401) {
            val env = decodeErrorEnvelopeOrNull(response.body)
            if (env != null && env.isAccessRemoved) {
                wipeOnAccessRemoved(connectionId)
                throw ApiEnvelopeException(401, env)
            }
            if (env != null) {
                throw ApiEnvelopeException(401, env)
            }
            throw OrganisationAuthException("refresh_unauthorized", "刷新设备令牌被拒绝（HTTP 401）")
        }
        if (response.statusCode !in 200..299) {
            val env = decodeErrorEnvelopeOrNull(response.body)
            if (env != null) throw ApiEnvelopeException(response.statusCode, env)
            throw OrganisationAuthException("refresh_failed", "刷新设备令牌失败（HTTP ${response.statusCode}）")
        }

        val obj = LumenBoxJson.default.parseToJsonElement(String(response.body, StandardCharsets.UTF_8)).jsonObject
        val nextAccessToken = obj["accessToken"]?.jsonPrimitive?.contentOrNull
            ?: throw OrganisationAuthException("invalid_refresh_response", "刷新响应缺少 accessToken")
        val nextRefreshToken = obj["refreshToken"]?.jsonPrimitive?.contentOrNull
            ?: throw OrganisationAuthException("invalid_refresh_response", "刷新响应缺少 refreshToken")
        val expiresIn = obj["expiresInSeconds"]?.jsonPrimitive?.longOrNull
            ?: obj["expiresIn"]?.jsonPrimitive?.longOrNull
            ?: DEFAULT_ACCESS_TOKEN_TTL_SECONDS

        store.saveRefreshToken(connectionId, nextRefreshToken)
        clearPendingAttempt(connectionId)
        val nextRtHash = sha256Hex(nextRefreshToken)
        memoryTokens[connectionId] = InMemoryAccessToken(
            value = nextAccessToken,
            expiresAtEpochMillis = clockMillis() + expiresIn.coerceAtLeast(1L) * 1_000L,
            issuedForRefreshTokenSha256Hex = nextRtHash,
        )
        return nextAccessToken
    }

    private suspend fun executeOnce(
        baseOrigin: String,
        connectionId: String,
        request: TransportRequest,
        accessToken: String,
        roundTrip: suspend (String, TransportRequest, Map<String, String>) -> TransportResponse,
    ): TransportResponse {
        val mergedHeaders = LinkedHashMap<String, String>()
        request.headers
            .filterKeys { !it.equals("cookie", ignoreCase = true) }
            .forEach { (k, v) -> mergedHeaders[k] = v }
        request.idempotencyKey?.let { mergedHeaders["Idempotency-Key"] = it }
        mergedHeaders["Authorization"] = "Bearer $accessToken"

        val res = roundTrip(baseOrigin, request, mergedHeaders)
        if (res.statusCode == 401 && res.body.isNotEmpty()) {
            val env = decodeErrorEnvelopeOrNull(res.body)
            if (env != null && env.isAccessRemoved) {
                wipeOnAccessRemoved(connectionId)
                throw ApiEnvelopeException(401, env)
            }
        }
        return res
    }

    private fun wipeOnAccessRemoved(connectionId: String) {
        memoryTokens.remove(connectionId)
        store.wipeConnection(connectionId)
        File(lockDirectory, "refresh-$connectionId.lock").delete()
    }

    private fun decodeErrorEnvelopeOrNull(body: ByteArray): ApiErrorEnvelope? =
        runCatching {
            LumenBoxJson.default.decodeFromString(
                ApiErrorEnvelope.serializer(),
                String(body, StandardCharsets.UTF_8),
            )
        }.getOrNull()

    private fun readPendingAttempt(connectionId: String): PendingRefreshAttempt? {
        val raw = vault.read("conn.$connectionId", PENDING_REFRESH_KEY) ?: return null
        if (raw.isEmpty()) return null
        return runCatching {
            LumenBoxJson.default.decodeFromString(
                PendingRefreshAttempt.serializer(),
                String(raw, StandardCharsets.UTF_8),
            )
        }.getOrNull()
    }

    private fun writePendingAttempt(connectionId: String, attempt: PendingRefreshAttempt) {
        val bytes = LumenBoxJson.default.encodeToString(PendingRefreshAttempt.serializer(), attempt)
            .toByteArray(StandardCharsets.UTF_8)
        vault.write("conn.$connectionId", PENDING_REFRESH_KEY, bytes)
    }

    private fun clearPendingAttempt(connectionId: String) {
        vault.write("conn.$connectionId", PENDING_REFRESH_KEY, ByteArray(0))
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

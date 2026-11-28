package com.lumenopen.lumenbox.auth

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.InstallationMode
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.contract.ServerMeta
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class OrganisationAuthException(val code: String, message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

enum class OAuthProviderKind(val wireName: String, val displayLabel: String) {
    DINGTALK("dingtalk", "钉钉"),
    FEISHU("feishu", "飞书");

    companion object {
        fun fromWire(raw: String): OpenEnum<OAuthProviderKind> {
            val match = entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) }
            return if (match != null) OpenEnum.Known(match, raw) else OpenEnum.Other(raw)
        }
    }
}

object OAuthProviderKindOpenSerializer : KSerializer<OpenEnum<OAuthProviderKind>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OpenEnum.OAuthProviderKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OpenEnum<OAuthProviderKind> =
        OAuthProviderKind.fromWire(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: OpenEnum<OAuthProviderKind>) {
        encoder.encodeString(value.rawValue)
    }
}

@Serializable
data class OAuthProviderDescriptor(
    @Serializable(with = OAuthProviderKindOpenSerializer::class)
    val id: OpenEnum<OAuthProviderKind>,
    val displayName: String? = null,
    val enabled: Boolean = true,
) {
    val label: String
        get() = displayName?.takeIf { it.isNotBlank() } ?: when (val k = id) {
            is OpenEnum.Known -> k.value.displayLabel
            is OpenEnum.Other -> k.rawValue
        }
}

@Serializable
private data class OAuthProvidersEnvelope(
    val items: List<OAuthProviderDescriptor> = emptyList(),
)

data class OrganisationServerDiscovery(
    val normalizedOrigin: String,
    val meta: ServerMeta,
    val providers: List<OAuthProviderDescriptor>,
)

@Serializable
data class PendingPkceSession(
    val normalizedOrigin: String,
    val organisationDisplayName: String,
    val redactLockScreenNotifications: Boolean,
    val providerId: String,
    val redirectUri: String,
    val codeVerifier: String,
    val codeChallenge: String,
    val state: String,
    val authorizationUrl: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

@Serializable
private data class OAuthStartRequest(
    val provider: String,
    val redirectUri: String,
    val codeChallenge: String,
    val codeChallengeMethod: String = "S256",
    val state: String,
)

@Serializable
private data class OAuthStartResponse(
    val authorizationUrl: String,
    val state: String? = null,
)

@Serializable
private data class OAuthExchangeRequest(
    val provider: String,
    val code: String,
    val codeVerifier: String,
    val redirectUri: String,
    val deviceName: String,
    val platform: String = "android",
    val stepUpKeyId: String? = null,
    val stepUpPublicKeySpkiBase64Url: String? = null,
)

sealed interface OAuthExchangeOutcome {
    data class Authenticated(
        val connId: String,
        val deviceId: String,
        val accessToken: String,
        val expiresInSeconds: Long,
        val refreshToken: String,
        val scopes: List<String>,
        val principalName: String?,
    ) : OAuthExchangeOutcome

    /** Returned when a directory user signs in before an Admin activates them (`docs/109 §2`, `docs/110 §4.1`). */
    data class AskedToJoin(
        val organisationName: String,
        val message: String,
        val requestId: String? = null,
    ) : OAuthExchangeOutcome
}

data class ParsedOAuthCallback(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
)

/**
 * RFC 7636 OAuth 2.0 Authorization Code + PKCE (`S256`) client for DingTalk and Feishu organisation sign-in
 * (`docs/106 §4.2–4.3`, `docs/110 §4.1`, INV-2255).
 */
object PkceGenerator {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    /** 32 random bytes -> 43-char URL-safe unpadded base64 verifier (RFC 7636 §4.1: 43..128 chars). */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        return encoder.encodeToString(bytes)
    }

    /** `BASE64URL-ENCODE(SHA256(ASCII(code_verifier)))` per RFC 7636 §4.2. */
    fun computeS256Challenge(codeVerifier: String): String {
        require(codeVerifier.length in 43..128) { "PKCE code_verifier must be 43..128 characters" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
        return encoder.encodeToString(digest)
    }

    fun generateStateNonce(): String {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        return encoder.encodeToString(bytes)
    }
}

class PkceOAuthClient(
    private val unauthenticatedRoundTrip: suspend (baseOrigin: String, request: TransportRequest) -> TransportResponse,
    private val allowLoopbackHttpForTests: Boolean = false,
) {
    companion object {
        const val DEFAULT_REDIRECT_URI = "lumenbox://oauth/callback"
        const val PKCE_SESSION_TTL_MILLIS = 10L * 60L * 1_000L
    }

    private val b64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun normalizeOrganisationOrigin(rawUrl: String): String {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) {
            throw OrganisationAuthException("invalid_origin", "请输入组织服务器地址（https://...）")
        }
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (e: Exception) {
            throw OrganisationAuthException("invalid_origin", "服务器地址格式无效：$trimmed", e)
        }
        val scheme = uri.scheme?.lowercase()
            ?: throw OrganisationAuthException("invalid_origin", "缺少协议头 https://")
        val host = uri.host?.lowercase()
            ?: throw OrganisationAuthException("invalid_origin", "缺少有效域名或主机名")
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
        if (scheme != "https" && !(allowLoopbackHttpForTests && scheme == "http" && isLoopback)) {
            throw OrganisationAuthException("https_required", "企业组织服务器必须使用 HTTPS（docs/106 §5 R1）")
        }
        if (!uri.userInfo.isNullOrEmpty() || !uri.rawQuery.isNullOrEmpty() || !uri.rawFragment.isNullOrEmpty()) {
            throw OrganisationAuthException("invalid_origin", "组织服务器地址不得携带凭据、查询参数或 #fragment")
        }
        val portSuffix = if (uri.port > 0 && !((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80))) {
            ":${uri.port}"
        } else {
            ""
        }
        return "$scheme://$host$portSuffix"
    }

    /**
     * Reads `GET /api/v1/meta` and `GET /api/v1/auth/providers` to verify an organisation installation
     * and discover configured DingTalk / Feishu OAuth channels.
     */
    suspend fun discoverServer(rawServerUrl: String): OrganisationServerDiscovery {
        val origin = normalizeOrganisationOrigin(rawServerUrl)
        val metaRes = unauthenticatedRoundTrip(
            origin,
            TransportRequest(
                method = "GET",
                path = "/api/v1/meta",
                headers = mapOf("Accept" to "application/json"),
            ),
        )
        throwIfErrorResponse(metaRes)
        val meta = LumenBoxJson.default.decodeFromString(
            ServerMeta.serializer(),
            String(metaRes.body, StandardCharsets.UTF_8),
        )
        val mode = meta.installation.mode
        if (mode is OpenEnum.Known && mode.value != InstallationMode.ORGANISATION) {
            throw OrganisationAuthException(
                "not_organisation_server",
                "该地址不是企业组织服务器（当前模式：${mode.rawValue}），个人 Mac 请使用扫码配对。",
            )
        }

        val providersRes = unauthenticatedRoundTrip(
            origin,
            TransportRequest(
                method = "GET",
                path = "/api/v1/auth/providers",
                headers = mapOf("Accept" to "application/json"),
            ),
        )
        val providers = if (providersRes.statusCode in 200..299 && providersRes.body.isNotEmpty()) {
            parseProviders(String(providersRes.body, StandardCharsets.UTF_8))
        } else {
            deriveProvidersFromCapabilities(meta.capabilities)
        }
        val enabledProviders = providers.filter { it.enabled }
        if (enabledProviders.isEmpty()) {
            throw OrganisationAuthException(
                "no_oauth_providers",
                "该组织服务器尚未启用钉钉或飞书登录渠道。",
            )
        }
        return OrganisationServerDiscovery(
            normalizedOrigin = origin,
            meta = meta,
            providers = enabledProviders,
        )
    }

    suspend fun startPkceAuthorization(
        discovery: OrganisationServerDiscovery,
        providerId: String,
        redirectUri: String = DEFAULT_REDIRECT_URI,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): PendingPkceSession {
        val verifier = PkceGenerator.generateCodeVerifier()
        val challenge = PkceGenerator.computeS256Challenge(verifier)
        val state = PkceGenerator.generateStateNonce()
        val bodyBytes = LumenBoxJson.default.encodeToString(
            OAuthStartRequest.serializer(),
            OAuthStartRequest(
                provider = providerId,
                redirectUri = redirectUri,
                codeChallenge = challenge,
                codeChallengeMethod = "S256",
                state = state,
            ),
        ).toByteArray(StandardCharsets.UTF_8)

        val res = unauthenticatedRoundTrip(
            discovery.normalizedOrigin,
            TransportRequest(
                method = "POST",
                path = "/api/v1/auth/oauth/start",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                body = bodyBytes,
            ),
        )
        throwIfErrorResponse(res)
        val start = LumenBoxJson.default.decodeFromString(
            OAuthStartResponse.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val authUri = runCatching { URI(start.authorizationUrl) }.getOrNull()
            ?: throw OrganisationAuthException("invalid_auth_url", "服务端返回的授权地址无效")
        val scheme = authUri.scheme?.lowercase()
        if (scheme != "https" && !(allowLoopbackHttpForTests && scheme == "http")) {
            throw OrganisationAuthException("invalid_auth_url", "OAuth 授权地址必须使用 HTTPS")
        }
        val effectiveState = start.state?.takeIf { it.isNotBlank() } ?: state
        val displayName = discovery.meta.installation.name?.takeIf { it.isNotBlank() }
            ?: URI(discovery.normalizedOrigin).host

        return PendingPkceSession(
            normalizedOrigin = discovery.normalizedOrigin,
            organisationDisplayName = displayName,
            redactLockScreenNotifications = discovery.meta.installation.redactLockScreenNotifications ?: false,
            providerId = providerId,
            redirectUri = redirectUri,
            codeVerifier = verifier,
            codeChallenge = challenge,
            state = effectiveState,
            authorizationUrl = start.authorizationUrl,
            createdAtEpochMillis = nowEpochMillis,
            expiresAtEpochMillis = nowEpochMillis + PKCE_SESSION_TTL_MILLIS,
        )
    }

    fun parseCallbackUri(callbackUriString: String): ParsedOAuthCallback {
        val uri = try {
            URI(callbackUriString.trim())
        } catch (e: Exception) {
            throw OrganisationAuthException("invalid_oauth_callback", "无效的 OAuth 回调地址", e)
        }
        if (uri.scheme != "lumenbox" || uri.host != "oauth" || uri.path != "/callback") {
            throw OrganisationAuthException("invalid_oauth_callback", "非预期的 OAuth 回调路径：$callbackUriString")
        }
        val params = LinkedHashMap<String, String>()
        val rawQuery = uri.rawQuery.orEmpty()
        for (part in rawQuery.split("&")) {
            if (part.isEmpty()) continue
            val idx = part.indexOf('=')
            if (idx <= 0) continue
            val k = URLDecoder.decode(part.substring(0, idx), StandardCharsets.UTF_8.name())
            val v = URLDecoder.decode(part.substring(idx + 1), StandardCharsets.UTF_8.name())
            params.putIfAbsent(k, v)
        }
        return ParsedOAuthCallback(
            code = params["code"],
            state = params["state"],
            error = params["error"],
            errorDescription = params["error_description"],
        )
    }

    /**
     * Validates callback `state` + TTL, exchanges the authorization code + `codeVerifier` for a
     * rotating device credential (registering the hardware step-up SPKI public key when available),
     * and distinguishes active members from `asked_to_join` pending activation (`docs/109 §2`).
     */
    suspend fun completePkceCallback(
        pending: PendingPkceSession,
        callbackUriString: String,
        deviceName: String,
        stepUpKeyId: String? = null,
        stepUpPublicKeySpki: ByteArray? = null,
        idempotencyKey: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): OAuthExchangeOutcome {
        if (nowEpochMillis > pending.expiresAtEpochMillis) {
            throw OrganisationAuthException("pkce_expired", "登录会话已超时，请重新发起企业登录。")
        }
        val callback = parseCallbackUri(callbackUriString)
        if (!callback.error.isNullOrBlank()) {
            val detail = callback.errorDescription?.takeIf { it.isNotBlank() } ?: callback.error
            throw OrganisationAuthException("oauth_denied", "企业授权未完成：$detail")
        }
        val gotState = callback.state
        if (gotState.isNullOrBlank() || !constantTimeEquals(pending.state, gotState)) {
            throw OrganisationAuthException("oauth_state_mismatch", "安全校验失败：OAuth state 不匹配，已拒绝登录。")
        }
        val code = callback.code?.takeIf { it.isNotBlank() }
            ?: throw OrganisationAuthException("oauth_missing_code", "OAuth 回调缺少授权码 code")

        val reqPayload = OAuthExchangeRequest(
            provider = pending.providerId,
            code = code,
            codeVerifier = pending.codeVerifier,
            redirectUri = pending.redirectUri,
            deviceName = deviceName,
            platform = "android",
            stepUpKeyId = stepUpKeyId,
            stepUpPublicKeySpkiBase64Url = stepUpPublicKeySpki?.let { b64UrlEncoder.encodeToString(it) },
        )
        val bodyBytes = LumenBoxJson.default.encodeToString(OAuthExchangeRequest.serializer(), reqPayload)
            .toByteArray(StandardCharsets.UTF_8)

        val res = unauthenticatedRoundTrip(
            pending.normalizedOrigin,
            TransportRequest(
                method = "POST",
                path = "/api/v1/auth/oauth/exchange",
                headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
                body = bodyBytes,
                idempotencyKey = idempotencyKey,
            ),
        )
        // Handle 202 / 403 asked_to_join envelopes cleanly as well as 200 status="asked_to_join"
        val rawBody = String(res.body, StandardCharsets.UTF_8)
        if (res.statusCode == 202 || res.statusCode == 403) {
            val obj = runCatching { LumenBoxJson.default.parseToJsonElement(rawBody).jsonObject }.getOrNull()
            val codeField = obj?.get("code")?.jsonPrimitive?.contentOrNull
                ?: obj?.get("status")?.jsonPrimitive?.contentOrNull
            if (obj != null && (codeField == "asked_to_join" || codeField == "asked-to-join")) {
                return OAuthExchangeOutcome.AskedToJoin(
                    organisationName = obj["organisationName"]?.jsonPrimitive?.contentOrNull
                        ?: pending.organisationDisplayName,
                    message = obj["message"]?.jsonPrimitive?.contentOrNull
                        ?: obj["error"]?.jsonPrimitive?.contentOrNull
                        ?: "已向组织管理员提交激活申请，审批通过后即可使用。",
                    requestId = obj["requestId"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
        throwIfErrorResponse(res)

        val obj = LumenBoxJson.default.parseToJsonElement(rawBody).jsonObject
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: "authenticated"
        if (status == "asked_to_join" || status == "asked-to-join") {
            return OAuthExchangeOutcome.AskedToJoin(
                organisationName = obj["organisationName"]?.jsonPrimitive?.contentOrNull
                    ?: pending.organisationDisplayName,
                message = obj["message"]?.jsonPrimitive?.contentOrNull
                    ?: "已向组织管理员提交激活申请，审批通过后即可使用。",
                requestId = obj["requestId"]?.jsonPrimitive?.contentOrNull,
            )
        }

        val accessToken = obj["accessToken"]?.jsonPrimitive?.contentOrNull
            ?: throw OrganisationAuthException("invalid_exchange_response", "缺少 accessToken")
        val refreshToken = obj["refreshToken"]?.jsonPrimitive?.contentOrNull
            ?: throw OrganisationAuthException("invalid_exchange_response", "缺少 refreshToken")
        val deviceId = obj["deviceId"]?.jsonPrimitive?.contentOrNull
            ?: throw OrganisationAuthException("invalid_exchange_response", "缺少 deviceId")
        val connId = obj["connId"]?.jsonPrimitive?.contentOrNull ?: "conn_$deviceId"
        val expiresIn = obj["expiresInSeconds"]?.jsonPrimitive?.longOrNull
            ?: obj["expiresIn"]?.jsonPrimitive?.longOrNull
            ?: 900L
        val scopes = obj["scopes"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?: emptyList()
        val principalName = (obj["principal"] as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull

        return OAuthExchangeOutcome.Authenticated(
            connId = connId,
            deviceId = deviceId,
            accessToken = accessToken,
            expiresInSeconds = expiresIn,
            refreshToken = refreshToken,
            scopes = scopes,
            principalName = principalName,
        )
    }

    private fun parseProviders(rawJson: String): List<OAuthProviderDescriptor> {
        val elem = LumenBoxJson.default.parseToJsonElement(rawJson)
        if (elem is JsonObject && "items" in elem) {
            return LumenBoxJson.default.decodeFromJsonElement(OAuthProvidersEnvelope.serializer(), elem).items
        }
        val arr = runCatching { elem.jsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { item ->
            val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val rawId = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: obj["provider"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            OAuthProviderDescriptor(
                id = OAuthProviderKind.fromWire(rawId),
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull
                    ?: obj["name"]?.jsonPrimitive?.contentOrNull,
                enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }
    }

    private fun deriveProvidersFromCapabilities(capabilities: List<String>): List<OAuthProviderDescriptor> {
        val result = ArrayList<OAuthProviderDescriptor>()
        if (capabilities.any { it == "auth:dingtalk" || it == "oauth:dingtalk" }) {
            result.add(OAuthProviderDescriptor(id = OAuthProviderKind.fromWire("dingtalk")))
        }
        if (capabilities.any { it == "auth:feishu" || it == "oauth:feishu" }) {
            result.add(OAuthProviderDescriptor(id = OAuthProviderKind.fromWire("feishu")))
        }
        return result
    }

    private fun throwIfErrorResponse(res: TransportResponse) {
        if (res.statusCode in 200..299) return
        val bodyStr = String(res.body, StandardCharsets.UTF_8)
        val env = runCatching {
            LumenBoxJson.default.decodeFromString(ApiErrorEnvelope.serializer(), bodyStr)
        }.getOrNull()
        if (env != null) {
            throw ApiEnvelopeException(res.statusCode, env)
        }
        throw OrganisationAuthException("http_${res.statusCode}", "请求失败（HTTP ${res.statusCode}）")
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(
            a.toByteArray(StandardCharsets.UTF_8),
            b.toByteArray(StandardCharsets.UTF_8),
        )
}

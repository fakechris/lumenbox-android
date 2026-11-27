package com.lumenopen.lumenbox.push

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.generated.models.GeneratedActionAck
import com.lumenopen.lumenbox.generated.models.GeneratedDevicePushConfigRequest
import com.lumenopen.lumenbox.generated.models.GeneratedDevicePushPreferences
import com.lumenopen.lumenbox.generated.models.GeneratedQuietHoursWindow
import com.lumenopen.lumenbox.session.OrganisationSessionCoordinator
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Push delivery channel identifier (`docs/110 §5.3–§5.4`, INV-2259):
 * supports Google FCM alongside domestic Android system push channels carrying identical
 * zero-content `{ connId, seq }` wake envelopes.
 */
@Serializable
enum class PushChannelKind(val wireName: String) {
    @SerialName("fcm") FCM("fcm"),
    @SerialName("huawei_hms") HUAWEI_HMS("huawei_hms"),
    @SerialName("xiaomi_mipush") XIAOMI_MIPUSH("xiaomi_mipush"),
    @SerialName("oppo_heytap") OPPO_HEYTAP("oppo_heytap"),
    @SerialName("vivo_push") VIVO_PUSH("vivo_push"),
    @SerialName("honor_push") HONOR_PUSH("honor_push");

    companion object {
        fun fromWire(raw: String): PushChannelKind =
            entries.firstOrNull { it.wireName.equals(raw.trim(), ignoreCase = true) } ?: FCM
    }
}

data class NativePushRegistrationToken(
    val channel: PushChannelKind,
    val nativeToken: String,
)

/**
 * Pluggable provider for FCM or domestic OEM vendor system push SDKs (`docs/110 §5.4`).
 * In GMS environments, FCM provides the token; in non-GMS domestic enterprise deployments,
 * vendor push adapters implement this interface while DingTalk/Feishu `lumenbox://open/...`
 * card deep-links provide dual-insurance reachability.
 */
fun interface NativePushChannelAdapter {
    val channel: PushChannelKind get() = PushChannelKind.FCM
    suspend fun obtainNativeToken(): NativePushRegistrationToken?
}

@Serializable
data class QuietHoursPreference(
    val startLocalTime: String,
    val endLocalTime: String,
    val timezone: String,
)

@Serializable
enum class PushNotificationLevel(val wireName: String) {
    @SerialName("needs-you-only") NEEDS_YOU_ONLY("needs-you-only"),
    @SerialName("needs-you-and-completions") NEEDS_YOU_AND_COMPLETIONS("needs-you-and-completions"),
    @SerialName("off") OFF("off"),
}

@Serializable
data class ConnectionPushPreferences(
    val level: PushNotificationLevel = PushNotificationLevel.NEEDS_YOU_AND_COMPLETIONS,
    val quietHours: QuietHoursPreference? = null,
)

@Serializable
data class PushGatewayRegisterRequest(
    val appId: String,
    val deviceId: String,
    val platform: String = "android",
    val channel: String,
    val nativePushToken: String,
)

@Serializable
data class PushGatewayRegisterResponse(
    val pushToken: String,
    val channel: String? = null,
)

@Serializable
data class PushGatewayUnregisterRequest(
    val appId: String,
    val deviceId: String,
)

/**
 * Stateless Wake-Only push gateway client (`push.lumenbox.com`, `docs/110 §5.3`, INV-2215 / INV-2259).
 *
 * Exchanges `(appId, deviceId, platform="android", channel, nativePushToken)` for an unguessable
 * ~131-bit capability wrapper `LumenPushToken[<22-char-base62>]` and caches it in the hardware-backed
 * vault. Honours `AGENTBOX_PUSH_GATEWAY_URL` for white-label / self-hosted enterprise gateways.
 */
class PushGatewayClient(
    private val store: InstallationStore,
    private val appId: String = DEFAULT_APP_ID,
    private val gatewayOriginOverride: String? = null,
    private val envLookup: (String) -> String? = { System.getenv(it) },
    private val unauthenticatedRoundTrip: suspend (baseOrigin: String, request: TransportRequest) -> TransportResponse =
        { origin, req ->
            val headers = LinkedHashMap(req.headers)
            req.idempotencyKey?.let { headers["Idempotency-Key"] = it }
            OrganisationSessionCoordinator.defaultHttpsRoundTrip(origin, req, headers)
        },
) {
    companion object {
        const val DEFAULT_PUSH_GATEWAY_URL = "https://push.lumenbox.com"
        const val ENV_PUSH_GATEWAY_URL = "AGENTBOX_PUSH_GATEWAY_URL"
        const val DEFAULT_APP_ID = "com.lumenopen.lumenbox"
    }

    fun resolveGatewayOrigin(): String {
        val candidate = gatewayOriginOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: envLookup(ENV_PUSH_GATEWAY_URL)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_PUSH_GATEWAY_URL
        val normalized = candidate.trimEnd('/')
        val lower = normalized.lowercase()
        require(
            lower.startsWith("https://") ||
                lower.startsWith("http://127.0.0.1") ||
                lower.startsWith("http://localhost") ||
                lower.startsWith("http://[::1]")
        ) {
            "Push gateway URL must use HTTPS (or loopback for hermetic tests): $normalized"
        }
        return normalized
    }

    fun readCachedCapabilityToken(): String? =
        store.readCachedPushCapabilityToken()?.takeIf { LumenPushToken.isValid(it) }

    suspend fun registerNativePushToken(registration: NativePushRegistrationToken): String {
        require(registration.nativeToken.isNotBlank()) { "nativePushToken must not be blank" }
        val deviceId = store.getOrCreatePushInstallationDeviceId()
        val reqPayload = PushGatewayRegisterRequest(
            appId = appId,
            deviceId = deviceId,
            platform = "android",
            channel = registration.channel.wireName,
            nativePushToken = registration.nativeToken.trim(),
        )
        val bodyBytes = LumenBoxJson.default.encodeToString(
            PushGatewayRegisterRequest.serializer(),
            reqPayload,
        ).toByteArray(StandardCharsets.UTF_8)

        val res = unauthenticatedRoundTrip(
            resolveGatewayOrigin(),
            TransportRequest(
                method = "POST",
                path = "/v1/devices/register",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = bodyBytes,
                idempotencyKey = UUID.randomUUID().toString(),
            ),
        )
        if (res.statusCode !in 200..299) {
            throw IllegalStateException("Push gateway registration failed with HTTP ${res.statusCode}")
        }
        val parsed = LumenBoxJson.default.decodeFromString(
            PushGatewayRegisterResponse.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        require(LumenPushToken.isValid(parsed.pushToken)) {
            "Gateway returned malformed LumenPushToken capability wrapper"
        }
        store.saveCachedPushCapabilityToken(parsed.pushToken, registration.channel.wireName)
        return parsed.pushToken
    }

    suspend fun unregisterDeviceFromGateway(): Boolean {
        val deviceId = store.getOrCreatePushInstallationDeviceId()
        val bodyBytes = LumenBoxJson.default.encodeToString(
            PushGatewayUnregisterRequest.serializer(),
            PushGatewayUnregisterRequest(appId = appId, deviceId = deviceId),
        ).toByteArray(StandardCharsets.UTF_8)

        val res = unauthenticatedRoundTrip(
            resolveGatewayOrigin(),
            TransportRequest(
                method = "POST",
                path = "/v1/devices/unregister",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = bodyBytes,
                idempotencyKey = UUID.randomUUID().toString(),
            ),
        )
        store.clearCachedPushCapabilityToken()
        return res.statusCode in 200..299
    }
}

/**
 * Synchronizes the device's `LumenPushToken[...]` capability wrapper, random per-installation `connId`,
 * and per-connection notification preferences (`needs-you-only` / `needs-you-and-completions` / `off` +
 * optional `quietHours`) to each personal Mac (`Noise_IK`) or organisation server (`HTTPS`) via
 * `PUT /api/v1/devices/self/push` and `DELETE /api/v1/devices/self/push` (`docs/106 §4.2`, `docs/110 §5.3`).
 */
class DevicePushSyncCoordinator(
    private val store: InstallationStore,
    private val gatewayClient: PushGatewayClient,
    private val channelAdapters: List<NativePushChannelAdapter> = emptyList(),
    private val transportForConnection: (String) -> ApiTransport?,
) {
    companion object {
        private const val PREFS_TABLE = "pushPreferences"
        private const val PREFS_ROW = "self"
    }

    fun readPreferences(connectionId: String): ConnectionPushPreferences =
        runCatching {
            store.openOfflineStore(connectionId).getJson(
                PREFS_TABLE,
                PREFS_ROW,
                ConnectionPushPreferences.serializer(),
            )
        }.getOrNull() ?: ConnectionPushPreferences()

    fun savePreferencesLocal(connectionId: String, preferences: ConnectionPushPreferences) {
        store.openOfflineStore(connectionId).putJson(
            PREFS_TABLE,
            PREFS_ROW,
            ConnectionPushPreferences.serializer(),
            preferences,
        )
    }

    /**
     * Resolves a native token from the first available channel (FCM first, then configured domestic
     * vendor push channels), exchanges it for `LumenPushToken[...]` at the gateway, and returns it.
     */
    suspend fun ensureCapabilityToken(): Pair<String, PushChannelKind>? {
        for (adapter in channelAdapters) {
            val nativeReg = runCatching { adapter.obtainNativeToken() }.getOrNull()
            if (nativeReg != null && nativeReg.nativeToken.isNotBlank()) {
                val capability = gatewayClient.registerNativePushToken(nativeReg)
                return capability to nativeReg.channel
            }
        }
        val cached = gatewayClient.readCachedCapabilityToken() ?: return null
        val ch = store.readCachedPushChannel()?.let { PushChannelKind.fromWire(it) } ?: PushChannelKind.FCM
        return cached to ch
    }

    /**
     * Syncs `PUT /api/v1/devices/self/push` for one installation connection.
     */
    suspend fun syncConnectionPush(
        connectionId: String,
        preferencesOverride: ConnectionPushPreferences? = null,
    ): Boolean {
        val connection = store.findById(connectionId) ?: return false
        val prefs = preferencesOverride?.also { savePreferencesLocal(connectionId, it) }
            ?: readPreferences(connectionId)

        if (prefs.level == PushNotificationLevel.OFF) {
            return disableConnectionPush(connectionId)
        }

        val (capabilityToken, channel) = ensureCapabilityToken() ?: return false
        require(LumenPushToken.isValid(capabilityToken)) { "Invalid LumenPushToken" }

        val transport = transportForConnection(connectionId) ?: return false
        val wireLevel = when (prefs.level) {
            PushNotificationLevel.NEEDS_YOU_ONLY -> GeneratedDevicePushPreferences.Level.NEEDS_MINUS_YOU_MINUS_ONLY
            PushNotificationLevel.NEEDS_YOU_AND_COMPLETIONS -> GeneratedDevicePushPreferences.Level.NEEDS_MINUS_YOU_MINUS_AND_MINUS_COMPLETIONS
            PushNotificationLevel.OFF -> GeneratedDevicePushPreferences.Level.OFF
        }
        val wireQuiet = prefs.quietHours?.let {
            GeneratedQuietHoursWindow(
                startLocalTime = it.startLocalTime,
                endLocalTime = it.endLocalTime,
                timezone = it.timezone,
            )
        }
        val reqBody = LumenBoxJson.default.encodeToString(
            GeneratedDevicePushConfigRequest.serializer(),
            GeneratedDevicePushConfigRequest(
                pushToken = capabilityToken,
                connId = connection.connId,
                channel = channel.wireName,
                preferences = GeneratedDevicePushPreferences(
                    level = wireLevel,
                    quietHours = wireQuiet,
                ),
            ),
        ).toByteArray(StandardCharsets.UTF_8)

        val res = transport.send(
            TransportRequest(
                method = "PUT",
                path = "/api/v1/devices/self/push",
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = reqBody,
                idempotencyKey = UUID.randomUUID().toString(),
            )
        )
        throwIfError(res.statusCode, res.body)
        val ack = LumenBoxJson.default.decodeFromString(
            GeneratedActionAck.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        return ack.ok
    }

    /** Calls `DELETE /api/v1/devices/self/push` on the target installation. */
    suspend fun disableConnectionPush(connectionId: String): Boolean {
        val transport = transportForConnection(connectionId) ?: return false
        val res = transport.send(
            TransportRequest(
                method = "DELETE",
                path = "/api/v1/devices/self/push",
                headers = mapOf("Accept" to "application/json"),
                idempotencyKey = UUID.randomUUID().toString(),
            )
        )
        throwIfError(res.statusCode, res.body)
        return true
    }

    /** Syncs push capability across all configured installations that currently have a live transport. */
    suspend fun syncAllConnections(): Int {
        var synced = 0
        for (conn in store.listConnections()) {
            val ok = try {
                syncConnectionPush(conn.id)
            } catch (_: Throwable) {
                false
            }
            if (ok) synced++
        }
        return synced
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
        throw IllegalStateException("Push self-config API failed with HTTP $statusCode")
    }
}

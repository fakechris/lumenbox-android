package com.lumenopen.lumenbox.session

import com.lumenopen.lumenbox.auth.OAuthExchangeOutcome
import com.lumenopen.lumenbox.auth.OAuthProviderDescriptor
import com.lumenopen.lumenbox.auth.OrganisationAuthException
import com.lumenopen.lumenbox.auth.OrganisationServerDiscovery
import com.lumenopen.lumenbox.auth.OrganisationTokenManager
import com.lumenopen.lumenbox.auth.PendingPkceSession
import com.lumenopen.lumenbox.auth.PkceOAuthClient
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.InboxDecisionItem
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.security.StepUpSigner
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationPresence
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.storage.KeyStoreVault
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

sealed interface OrganisationOnboardingState {
    data object Idle : OrganisationOnboardingState
    data class Discovering(val serverUrl: String) : OrganisationOnboardingState
    data class ProvidersReady(
        val discovery: OrganisationServerDiscovery,
    ) : OrganisationOnboardingState

    data class AwaitingBrowserCallback(
        val pending: PendingPkceSession,
    ) : OrganisationOnboardingState

    data class ExchangingCode(val organisationName: String) : OrganisationOnboardingState

    data class AskedToJoin(
        val organisationName: String,
        val serverOrigin: String,
        val message: String,
        val requestId: String?,
    ) : OrganisationOnboardingState

    data class Failed(val errorMessage: String) : OrganisationOnboardingState
}

/**
 * Coordinates multi-organisation OAuth 2.0 + PKCE (`S256`) sign-in, `asked_to_join` pending activation,
 * rotating token management, device listing/revocation, and `401 access-removed` remote wipe (`INV-2255`).
 */
class OrganisationSessionCoordinator(
    private val store: InstallationStore,
    private val vault: KeyStoreVault,
    private val oauthClient: PkceOAuthClient,
    private val tokenManager: OrganisationTokenManager,
    private val stepUpSigner: StepUpSigner? = null,
    private val authenticatedRoundTrip: suspend (
        baseOrigin: String,
        request: TransportRequest,
        headers: Map<String, String>,
    ) -> TransportResponse = ::defaultHttpsRoundTrip,
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val PENDING_OAUTH_NAMESPACE = "oauth.pkce.pending"
        private const val PENDING_OAUTH_KEY = "sessionJson"
        private const val PENDING_IDEMPOTENCY_KEY = "exchangeIdempotencyKey"
        private const val META_TABLE = "orgMeta"
        private const val DEVICE_ID_ROW = "selfDeviceId"
        private const val INBOX_TABLE = "inboxSnapshot"
        private const val INBOX_ROW = "items"
        private const val DEVICES_TABLE = "orgDevices"
        private const val DEVICES_ROW = "items"

        suspend fun defaultHttpsRoundTrip(
            baseOrigin: String,
            request: TransportRequest,
            headers: Map<String, String> = request.headers,
        ): TransportResponse = withContext(Dispatchers.IO) {
            val trimmed = baseOrigin.trimEnd('/')
            val conn = (URI.create("$trimmed${request.path}").toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = request.method.uppercase()
                connectTimeout = 10_000
                readTimeout = 30_000
                useCaches = false
                instanceFollowRedirects = false
                headers
                    .filterKeys { !it.equals("cookie", ignoreCase = true) }
                    .forEach { (k, v) -> setRequestProperty(k, v) }
                if (request.body != null) {
                    doOutput = true
                    outputStream.use { it.write(request.body) }
                }
            }
            try {
                val code = conn.responseCode
                val stream = if (code in 200..399) conn.inputStream else conn.errorStream
                val body = stream?.use { it.readBytes() } ?: ByteArray(0)
                val responseHeaders = conn.headerFields
                    .filterKeys { it != null }
                    .mapKeys { it.key!!.lowercase() }
                    .mapValues { it.value.firstOrNull().orEmpty() }
                TransportResponse(code, responseHeaders, body)
            } finally {
                conn.disconnect()
            }
        }
    }

    private val _onboardingState = MutableStateFlow<OrganisationOnboardingState>(OrganisationOnboardingState.Idle)
    val onboardingState: StateFlow<OrganisationOnboardingState> = _onboardingState.asStateFlow()

    init {
        loadPendingPkce()?.let { saved ->
            if (clockMillis() <= saved.expiresAtEpochMillis) {
                _onboardingState.value = OrganisationOnboardingState.AwaitingBrowserCallback(saved)
            } else {
                clearPendingPkce()
            }
        }
    }

    fun resetOnboarding() {
        clearPendingPkce()
        _onboardingState.value = OrganisationOnboardingState.Idle
    }

    suspend fun discoverProviders(serverUrl: String): List<OAuthProviderDescriptor> {
        _onboardingState.value = OrganisationOnboardingState.Discovering(serverUrl)
        return try {
            val discovery = oauthClient.discoverServer(serverUrl)
            _onboardingState.value = OrganisationOnboardingState.ProvidersReady(discovery)
            discovery.providers
        } catch (err: ApiEnvelopeException) {
            val msg = "服务器返回错误（${err.envelope.code}）：${err.envelope.error}"
            _onboardingState.value = OrganisationOnboardingState.Failed(msg)
            throw err
        } catch (err: Throwable) {
            val msg = err.message ?: "无法连接到该企业服务器"
            _onboardingState.value = OrganisationOnboardingState.Failed(msg)
            throw err
        }
    }

    /**
     * Starts OAuth 2.0 + PKCE (`S256`) for [providerId], persists the pending PKCE verifier/state
     * across Custom Tabs activity recreation, and returns the authorization URL to launch in Custom Tabs.
     */
    suspend fun beginCustomTabsSignIn(
        discovery: OrganisationServerDiscovery,
        providerId: String,
    ): String {
        return try {
            val pending = oauthClient.startPkceAuthorization(
                discovery = discovery,
                providerId = providerId,
                nowEpochMillis = clockMillis(),
            )
            savePendingPkce(pending)
            _onboardingState.value = OrganisationOnboardingState.AwaitingBrowserCallback(pending)
            pending.authorizationUrl
        } catch (err: Throwable) {
            val msg = err.message ?: "无法发起企业单点登录"
            _onboardingState.value = OrganisationOnboardingState.Failed(msg)
            throw err
        }
    }

    /**
     * Handles `lumenbox://oauth/callback?code=...&state=...` from Custom Tabs:
     * - Exchanges PKCE code + hardware `StepUpProof` SPKI public key;
     * - Transitions to [OrganisationOnboardingState.AskedToJoin] if awaiting admin activation;
     * - Otherwise creates/updates the isolated `ORGANISATION` installation, stores the rotating
     *   refresh token in hardware vault, holds the 15-minute access token in memory, and activates it.
     */
    suspend fun handleOAuthCallback(
        callbackUriString: String,
        deviceName: String = "Android Phone",
    ): OAuthExchangeOutcome {
        val pending = loadPendingPkce()
            ?: throw OrganisationAuthException("no_pending_pkce", "未找到进行中的企业登录请求，请重新发起登录。")
        _onboardingState.value = OrganisationOnboardingState.ExchangingCode(pending.organisationDisplayName)

        val idempotencyKey = vault.read(PENDING_OAUTH_NAMESPACE, PENDING_IDEMPOTENCY_KEY)
            ?.takeIf { it.isNotEmpty() }
            ?.let { String(it, StandardCharsets.UTF_8) }
            ?: UUID.randomUUID().toString().also {
                vault.write(PENDING_OAUTH_NAMESPACE, PENDING_IDEMPOTENCY_KEY, it.toByteArray(StandardCharsets.UTF_8))
            }

        val spki = runCatching { stepUpSigner?.publicKeySpki() }.getOrNull()
        val keyId = if (spki != null) stepUpSigner?.keyId else null

        val outcome = try {
            oauthClient.completePkceCallback(
                pending = pending,
                callbackUriString = callbackUriString,
                deviceName = deviceName,
                stepUpKeyId = keyId,
                stepUpPublicKeySpki = spki,
                idempotencyKey = idempotencyKey,
                nowEpochMillis = clockMillis(),
            )
        } catch (err: Throwable) {
            clearPendingPkce()
            _onboardingState.value = OrganisationOnboardingState.Failed(err.message ?: "企业登录授权码兑换失败")
            throw err
        }

        clearPendingPkce()
        when (outcome) {
            is OAuthExchangeOutcome.AskedToJoin -> {
                _onboardingState.value = OrganisationOnboardingState.AskedToJoin(
                    organisationName = outcome.organisationName,
                    serverOrigin = pending.normalizedOrigin,
                    message = outcome.message,
                    requestId = outcome.requestId,
                )
            }
            is OAuthExchangeOutcome.Authenticated -> {
                val connectionId = deriveOrganisationConnectionId(pending.normalizedOrigin)
                val connection = InstallationConnection(
                    id = connectionId,
                    connId = outcome.connId,
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = pending.organisationDisplayName,
                    serverOriginOrRelayUrl = pending.normalizedOrigin,
                    needsYouCount = store.findById(connectionId)?.needsYouCount ?: 0,
                    redactLockScreenNotifications = pending.redactLockScreenNotifications,
                    presence = InstallationPresence.OnlineDirect,
                )
                store.addConnection(connection)
                tokenManager.seedAuthenticatedSession(
                    connectionId = connectionId,
                    accessToken = outcome.accessToken,
                    expiresInSeconds = outcome.expiresInSeconds,
                    refreshToken = outcome.refreshToken,
                )
                store.openOfflineStore(connectionId).putText(META_TABLE, DEVICE_ID_ROW, outcome.deviceId)
                store.selectActiveInstallation(connectionId)
                _onboardingState.value = OrganisationOnboardingState.Idle
            }
        }
        return outcome
    }

    fun transportForOrganisation(connectionId: String): ApiTransport =
        tokenManager.createAuthenticatedTransport(connectionId, authenticatedRoundTrip)

    fun sseConnectorForOrganisation(connectionId: String): com.lumenopen.lumenbox.conversation.LiveSseConnector =
        com.lumenopen.lumenbox.conversation.LiveSseConnector.overOrganisationHttps(
            connectionId = connectionId,
            store = store,
            tokenManager = tokenManager,
        )

    fun desktopStreamOpenerForOrganisation(connectionId: String): com.lumenopen.lumenbox.desktop.DesktopBinaryStreamOpener =
        com.lumenopen.lumenbox.desktop.DesktopBinaryStreamOpener.overWebSocket(
            serverOriginProvider = {
                store.findById(connectionId)?.serverOriginOrRelayUrl
                    ?: throw IllegalStateException("Organisation connection $connectionId missing")
            },
        )

    fun readCachedInboxItems(connectionId: String): List<InboxDecisionItem> {
        val offline = runCatching { store.openOfflineStore(connectionId) }.getOrNull() ?: return emptyList()
        return offline.getJson(INBOX_TABLE, INBOX_ROW, ListSerializer(InboxDecisionItem.serializer()))
            ?: emptyList()
    }

    suspend fun refreshInbox(connectionId: String): List<InboxDecisionItem> {
        val transport = transportForOrganisation(connectionId)
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/inbox",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        if (res.statusCode !in 200..299) return readCachedInboxItems(connectionId)
        val raw = String(res.body, StandardCharsets.UTF_8)
        val elem = runCatching { LumenBoxJson.default.parseToJsonElement(raw) }.getOrNull()
            ?: return readCachedInboxItems(connectionId)
        val itemsArray = when {
            elem is JsonObject && "items" in elem -> elem["items"]?.jsonArray
            else -> runCatching { elem.jsonArray }.getOrNull()
        } ?: return emptyList()
        val decoded = itemsArray.map {
            LumenBoxJson.default.decodeFromJsonElement(InboxDecisionItem.serializer(), it)
        }
        val offline = store.openOfflineStore(connectionId)
        offline.putJson(INBOX_TABLE, INBOX_ROW, ListSerializer(InboxDecisionItem.serializer()), decoded)
        store.updateNeedsYouCount(connectionId, decoded.size)
        store.updatePresence(connectionId, InstallationPresence.OnlineDirect)
        return decoded
    }

    suspend fun listDevices(connectionId: String): List<PairedDeviceSummary> {
        val offline = runCatching { store.openOfflineStore(connectionId) }.getOrNull() ?: return emptyList()
        val selfDeviceId = offline.getText(META_TABLE, DEVICE_ID_ROW)
        val transport = transportForOrganisation(connectionId)
        val res = runCatching {
            transport.send(
                TransportRequest(
                    method = "GET",
                    path = "/api/v1/devices",
                    headers = mapOf("Accept" to "application/json"),
                )
            )
        }.getOrElse { err ->
            if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) throw err
            null
        }
        if (res != null && res.statusCode in 200..299) {
            val parsed = parseDevicesJson(String(res.body, StandardCharsets.UTF_8), selfDeviceId)
            offline.putJson(DEVICES_TABLE, DEVICES_ROW, ListSerializer(PairedDeviceSummary.serializer()), parsed)
            return parsed
        }
        return offline.getJson(DEVICES_TABLE, DEVICES_ROW, ListSerializer(PairedDeviceSummary.serializer()))
            ?: emptyList()
    }

    suspend fun revokeDevice(connectionId: String, targetDeviceId: String): Boolean {
        val offline = runCatching { store.openOfflineStore(connectionId) }.getOrNull() ?: return false
        val selfDeviceId = offline.getText(META_TABLE, DEVICE_ID_ROW)
        val res = transportForOrganisation(connectionId).send(
            TransportRequest(method = "DELETE", path = "/api/v1/devices/$targetDeviceId")
        )
        if (res.statusCode !in 200..299) return false
        if (targetDeviceId == selfDeviceId) {
            signOutLocally(connectionId)
            return true
        }
        val remaining = (offline.getJson(DEVICES_TABLE, DEVICES_ROW, ListSerializer(PairedDeviceSummary.serializer())) ?: emptyList())
            .filterNot { it.id == targetDeviceId }
        offline.putJson(DEVICES_TABLE, DEVICES_ROW, ListSerializer(PairedDeviceSummary.serializer()), remaining)
        return true
    }

    suspend fun signOut(connectionId: String) {
        if (store.findById(connectionId) != null) {
            runCatching {
                transportForOrganisation(connectionId).send(
                    TransportRequest(method = "POST", path = "/api/v1/auth/logout")
                )
            }
        }
        signOutLocally(connectionId)
    }

    fun signOutLocally(connectionId: String) {
        tokenManager.clearMemoryToken(connectionId)
        store.wipeConnection(connectionId)
    }

    fun deriveOrganisationConnectionId(normalizedOrigin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalizedOrigin.toByteArray(StandardCharsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "org-$digest"
    }

    private fun parseDevicesJson(raw: String, selfDeviceId: String?): List<PairedDeviceSummary> {
        val root = runCatching { LumenBoxJson.default.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return emptyList()
        val items = root["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { item ->
            val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            PairedDeviceSummary(
                id = id,
                name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id,
                platform = obj["platform"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                lastSeenAt = obj["lastSeenAt"]?.jsonPrimitive?.contentOrNull,
                isCurrentDevice = id == selfDeviceId,
            )
        }
    }

    private fun savePendingPkce(pending: PendingPkceSession) {
        val bytes = LumenBoxJson.default.encodeToString(PendingPkceSession.serializer(), pending)
            .toByteArray(StandardCharsets.UTF_8)
        vault.write(PENDING_OAUTH_NAMESPACE, PENDING_OAUTH_KEY, bytes)
        vault.write(PENDING_OAUTH_NAMESPACE, PENDING_IDEMPOTENCY_KEY, ByteArray(0))
    }

    private fun loadPendingPkce(): PendingPkceSession? {
        val raw = vault.read(PENDING_OAUTH_NAMESPACE, PENDING_OAUTH_KEY) ?: return null
        if (raw.isEmpty()) return null
        return runCatching {
            LumenBoxJson.default.decodeFromString(
                PendingPkceSession.serializer(),
                String(raw, StandardCharsets.UTF_8),
            )
        }.getOrNull()
    }

    private fun clearPendingPkce() {
        vault.deleteNamespace(PENDING_OAUTH_NAMESPACE)
    }
}

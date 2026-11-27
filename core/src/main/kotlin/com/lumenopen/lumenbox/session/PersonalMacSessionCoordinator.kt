package com.lumenopen.lumenbox.session

import com.lumenopen.lumenbox.contract.InboxDecisionItem
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.relay.ConnectedPhoneSession
import com.lumenopen.lumenbox.relay.HostSleepingInfo
import com.lumenopen.lumenbox.relay.NoiseRelayException
import com.lumenopen.lumenbox.relay.ParsedPairingOffer
import com.lumenopen.lumenbox.relay.TransportPathMode
import com.lumenopen.lumenbox.relay.WebSocketRelayConnector
import com.lumenopen.lumenbox.relay.X25519Keypair
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationPresence
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.storage.PersonalInstallationAlreadyExistsException
import com.lumenopen.lumenbox.storage.PersonalMacPairingMetadata
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.EncryptedRelayTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

@Serializable
data class PairedDeviceSummary(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeenAt: String? = null,
    val isCurrentDevice: Boolean = false,
)

@Serializable
private data class PersistedHostStatus(
    val sleeping: Boolean,
    val lastSeenAt: String,
    val reason: String = "suspend",
)

sealed interface PersonalMacSessionState {
    data object Unpaired : PersonalMacSessionState
    data class Pairing(val displayName: String) : PersonalMacSessionState
    data class Connecting(
        val installationId: String,
        val displayName: String,
        val lastSeenAt: String?,
    ) : PersonalMacSessionState

    data class Online(
        val installationId: String,
        val displayName: String,
        val deviceId: String,
        val mode: TransportPathMode,
        val selectedUrl: String,
        val scopes: List<String>,
        val connectedAt: String,
    ) : PersonalMacSessionState

    /**
     * Entered immediately upon receiving an authenticated `host_sleeping` control frame (`docs/110 §3.3`),
     * surfacing the "Mac 已休眠（最后在线时间）" banner without waiting on socket read timeouts.
     */
    data class HostSleeping(
        val installationId: String,
        val displayName: String,
        val lastSeenAt: String,
        val reason: String,
    ) : PersonalMacSessionState

    data class Disconnected(
        val installationId: String,
        val displayName: String,
        val errorCode: String?,
        val errorMessage: String?,
        val lastSeenAt: String?,
    ) : PersonalMacSessionState
}

/**
 * Coordinates Personal Mac `#fragment` QR pairing (`Noise_NK`), persistent `Noise_IK` sessions,
 * `host_sleeping` banner transitions, offline cache snapshots, and paired-device management (`INV-2254`).
 */
class PersonalMacSessionCoordinator(
    private val store: InstallationStore,
    private val connector: WebSocketRelayConnector,
    private val clock: () -> Instant = { Instant.now() },
) {
    companion object {
        const val PERSONAL_INSTALLATION_ID = "personal-mac"
        private const val HOST_STATUS_TABLE = "hostStatus"
        private const val HOST_STATUS_ROW = "latest"
        private const val INBOX_CACHE_TABLE = "inboxSnapshot"
        private const val INBOX_CACHE_ROW = "items"
        private const val DEVICES_CACHE_TABLE = "pairedDevices"
        private const val DEVICES_CACHE_ROW = "items"
    }

    private val b64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64Decoder = Base64.getUrlDecoder()
    private val sessionLock = Any()

    @Volatile
    private var activeSession: ConnectedPhoneSession? = null

    private val _state = MutableStateFlow<PersonalMacSessionState>(PersonalMacSessionState.Unpaired)
    val state: StateFlow<PersonalMacSessionState> = _state.asStateFlow()

    init {
        restorePersistedState()
    }

    fun restorePersistedState() {
        val personal = store.findPersonalInstallation()
        if (personal == null) {
            _state.value = PersonalMacSessionState.Unpaired
            return
        }
        val offline = runCatching { store.openOfflineStore(personal.id) }.getOrNull()
        val hostStatus = offline?.getJson(HOST_STATUS_TABLE, HOST_STATUS_ROW, PersistedHostStatus.serializer())
        _state.value = when {
            hostStatus != null && hostStatus.sleeping -> {
                store.updatePresence(personal.id, InstallationPresence.HostSleeping(hostStatus.lastSeenAt, hostStatus.reason))
                PersonalMacSessionState.HostSleeping(
                    installationId = personal.id,
                    displayName = personal.displayName,
                    lastSeenAt = hostStatus.lastSeenAt,
                    reason = hostStatus.reason,
                )
            }
            personal.presence is InstallationPresence.HostSleeping -> {
                PersonalMacSessionState.HostSleeping(
                    installationId = personal.id,
                    displayName = personal.displayName,
                    lastSeenAt = personal.presence.lastSeenAt,
                    reason = personal.presence.reason,
                )
            }
            else -> {
                PersonalMacSessionState.Disconnected(
                    installationId = personal.id,
                    displayName = personal.displayName,
                    errorCode = null,
                    errorMessage = null,
                    lastSeenAt = hostStatus?.lastSeenAt,
                )
            }
        }
    }

    /**
     * Pairs this Android device with a Personal Mac `#fragment` QR URI (`Noise_NK`), registers the
     * isolated connection in [InstallationStore], and immediately connects a persistent `Noise_IK` session.
     */
    suspend fun pairAndConnect(
        qrUri: String,
        displayName: String = "Personal Mac",
        deviceName: String = "Android Phone",
    ): ConnectedPhoneSession {
        if (store.hasPersonalInstallation()) {
            throw PersonalInstallationAlreadyExistsException()
        }
        val nowMillis = clock().toEpochMilli()
        val offer = ParsedPairingOffer.parse(qrUri, nowMillis)

        _state.value = PersonalMacSessionState.Pairing(displayName)
        val deviceKeypair = X25519Keypair.generate()
        val pairResult = try {
            connector.pairPhoneWithOffer(
                offer = offer,
                deviceKeypair = deviceKeypair,
                deviceName = deviceName,
                platform = "android",
            )
        } catch (err: Throwable) {
            _state.value = PersonalMacSessionState.Unpaired
            throw err
        }

        val metadata = PersonalMacPairingMetadata(
            hostPublicKeyBase64Url = b64Encoder.encodeToString(offer.hostPublicKey),
            routeId = offer.routeId,
            directUrls = offer.directUrls,
            deviceId = pairResult.deviceId,
            scopes = pairResult.scopes,
        )
        val connection = InstallationConnection(
            id = PERSONAL_INSTALLATION_ID,
            connId = pairResult.connId,
            kind = InstallationConnection.Kind.PERSONAL_MAC,
            displayName = displayName,
            serverOriginOrRelayUrl = offer.relayUrl,
            needsYouCount = 0,
            personalMetadata = metadata,
            presence = InstallationPresence.Disconnected(),
        )
        store.addConnection(connection)
        store.savePersonalDeviceKeypair(connection.id, deviceKeypair)
        store.selectActiveInstallation(connection.id)

        return reconnect()
    }

    /**
     * Reconnects the paired Personal Mac over `Noise_IK` (preferring `_lumenbox._tcp` / explicit direct
     * endpoints before falling back to the encrypted relay).
     */
    suspend fun reconnect(): ConnectedPhoneSession {
        val personal = store.findPersonalInstallation()
            ?: throw IllegalStateException("No paired personal Mac installation")
        val metadata = personal.personalMetadata
            ?: throw IllegalStateException("Missing personal Mac pairing metadata")
        val deviceKeypair = store.readPersonalDeviceKeypair(personal.id)
            ?: throw IllegalStateException("Missing device static X25519 key in vault")

        val offline = store.openOfflineStore(personal.id)
        val previousLastSeen = offline.getJson(HOST_STATUS_TABLE, HOST_STATUS_ROW, PersistedHostStatus.serializer())?.lastSeenAt
        _state.value = PersonalMacSessionState.Connecting(personal.id, personal.displayName, previousLastSeen)

        closeActiveSession()

        val hostPub = b64Decoder.decode(metadata.hostPublicKeyBase64Url)
        return try {
            val session = connector.connectPhoneIk(
                hostPublicKey = hostPub,
                deviceKeypair = deviceKeypair,
                relayUrl = personal.serverOriginOrRelayUrl,
                routeId = metadata.routeId,
                directUrls = metadata.directUrls,
                onHostSleeping = { info -> onHostSleepingReceived(personal.id, info) },
            )
            synchronized(sessionLock) {
                activeSession = session
            }
            val nowIso = clock().toString()
            offline.putJson(
                HOST_STATUS_TABLE,
                HOST_STATUS_ROW,
                PersistedHostStatus.serializer(),
                PersistedHostStatus(sleeping = false, lastSeenAt = nowIso, reason = "online"),
            )
            val presence = when (session.mode) {
                TransportPathMode.DIRECT -> InstallationPresence.OnlineDirect
                TransportPathMode.RELAY -> InstallationPresence.OnlineRelay
            }
            store.updatePresence(personal.id, presence)
            _state.value = PersonalMacSessionState.Online(
                installationId = personal.id,
                displayName = personal.displayName,
                deviceId = session.deviceId,
                mode = session.mode,
                selectedUrl = session.selectedUrl,
                scopes = session.scopes,
                connectedAt = nowIso,
            )
            session
        } catch (err: Throwable) {
            val code = (err as? NoiseRelayException)?.code ?: "connect_failed"
            // Preserve HostSleeping banner if the Mac was known to have suspended and is still asleep!
            val status = offline.getJson(HOST_STATUS_TABLE, HOST_STATUS_ROW, PersistedHostStatus.serializer())
            if (status != null && status.sleeping && (code == "host_offline" || code == "relay_closed" || code == "ws_connect_failed")) {
                store.updatePresence(personal.id, InstallationPresence.HostSleeping(status.lastSeenAt, status.reason))
                _state.value = PersonalMacSessionState.HostSleeping(
                    installationId = personal.id,
                    displayName = personal.displayName,
                    lastSeenAt = status.lastSeenAt,
                    reason = status.reason,
                )
            } else {
                store.updatePresence(personal.id, InstallationPresence.Disconnected(status?.lastSeenAt))
                _state.value = PersonalMacSessionState.Disconnected(
                    installationId = personal.id,
                    displayName = personal.displayName,
                    errorCode = code,
                    errorMessage = err.message,
                    lastSeenAt = status?.lastSeenAt,
                )
            }
            throw err
        }
    }

    fun activeApiTransport(): ApiTransport? {
        val mux = activeSession?.mux ?: return null
        return EncryptedRelayTransport.overMux(mux)
    }

    /** Exposes the active authenticated [com.lumenopen.lumenbox.relay.RelayMux] for multiplexed streams. */
    fun activeRelayMux(): com.lumenopen.lumenbox.relay.RelayMux? = activeSession?.mux

    /** Saves the latest `Needs you` items into the encrypted offline cache and updates the top-level count. */
    fun cacheInboxItems(items: List<InboxDecisionItem>) {
        val personal = store.findPersonalInstallation() ?: return
        val offline = store.openOfflineStore(personal.id)
        offline.putJson(INBOX_CACHE_TABLE, INBOX_CACHE_ROW, ListSerializer(InboxDecisionItem.serializer()), items)
        store.updateNeedsYouCount(personal.id, items.size)
    }

    /** Reads locally cached `Needs you` items while the Mac is asleep or offline (`docs/110 §3.3`). */
    fun readCachedInboxItems(): List<InboxDecisionItem> {
        val personal = store.findPersonalInstallation() ?: return emptyList()
        val offline = runCatching { store.openOfflineStore(personal.id) }.getOrNull() ?: return emptyList()
        return offline.getJson(INBOX_CACHE_TABLE, INBOX_CACHE_ROW, ListSerializer(InboxDecisionItem.serializer()))
            ?: emptyList()
    }

    fun pendingOfflineActionCount(): Int {
        val personal = store.findPersonalInstallation() ?: return 0
        val offline = runCatching { store.openOfflineStore(personal.id) }.getOrNull() ?: return 0
        return offline.pendingOutboundCount()
    }

    /**
     * Lists paired devices from `GET /api/v1/devices` when online (caching in [ConnectionOfflineStore]),
     * or returns the last cached device list when the Mac is asleep/offline.
     */
    suspend fun refreshPairedDevices(): List<PairedDeviceSummary> {
        val personal = store.findPersonalInstallation() ?: return emptyList()
        val currentDeviceId = personal.personalMetadata?.deviceId
        val offline = store.openOfflineStore(personal.id)
        val transport = activeApiTransport()
        if (transport != null && _state.value is PersonalMacSessionState.Online) {
            val response = runCatching {
                transport.send(TransportRequest(method = "GET", path = "/api/v1/devices"))
            }.getOrNull()
            if (response != null && response.statusCode in 200..299) {
                val parsed = parseDevicesPayload(String(response.body, StandardCharsets.UTF_8), currentDeviceId)
                offline.putJson(DEVICES_CACHE_TABLE, DEVICES_CACHE_ROW, ListSerializer(PairedDeviceSummary.serializer()), parsed)
                return parsed
            }
        }
        val cached = offline.getJson(DEVICES_CACHE_TABLE, DEVICES_CACHE_ROW, ListSerializer(PairedDeviceSummary.serializer()))
        if (cached != null) return cached
        return if (currentDeviceId != null) {
            listOf(
                PairedDeviceSummary(
                    id = currentDeviceId,
                    name = "本机 (Android)",
                    platform = "android",
                    isCurrentDevice = true,
                )
            )
        } else {
            emptyList()
        }
    }

    /** Revokes a paired device on the Mac (`DELETE /api/v1/devices/{deviceId}`). */
    suspend fun revokePairedDevice(targetDeviceId: String): Boolean {
        val personal = store.findPersonalInstallation() ?: return false
        val currentDeviceId = personal.personalMetadata?.deviceId
        val transport = activeApiTransport() ?: return false
        val res = transport.send(TransportRequest(method = "DELETE", path = "/api/v1/devices/$targetDeviceId"))
        if (res.statusCode !in 200..299) return false
        if (targetDeviceId == currentDeviceId) {
            unpairLocalMac()
            return true
        }
        val offline = store.openOfflineStore(personal.id)
        val remaining = (offline.getJson(DEVICES_CACHE_TABLE, DEVICES_CACHE_ROW, ListSerializer(PairedDeviceSummary.serializer())) ?: emptyList())
            .filterNot { it.id == targetDeviceId }
        offline.putJson(DEVICES_CACHE_TABLE, DEVICES_CACHE_ROW, ListSerializer(PairedDeviceSummary.serializer()), remaining)
        return true
    }

    /** Removes the Personal Mac pairing locally, wiping its keystore vault entries and encrypted cache. */
    fun unpairLocalMac() {
        closeActiveSession()
        val personal = store.findPersonalInstallation()
        if (personal != null) {
            store.wipeConnection(personal.id)
        }
        _state.value = PersonalMacSessionState.Unpaired
    }

    fun closeActiveSession() {
        val prev = synchronized(sessionLock) {
            val s = activeSession
            activeSession = null
            s
        }
        prev?.close()
    }

    private fun onHostSleepingReceived(installationId: String, info: HostSleepingInfo) {
        val personal = store.findById(installationId) ?: return
        val lastSeen = info.at.ifBlank { clock().toString() }
        val reason = info.reason.ifBlank { "suspend" }
        runCatching {
            store.openOfflineStore(installationId).putJson(
                HOST_STATUS_TABLE,
                HOST_STATUS_ROW,
                PersistedHostStatus.serializer(),
                PersistedHostStatus(sleeping = true, lastSeenAt = lastSeen, reason = reason),
            )
        }
        store.updatePresence(installationId, InstallationPresence.HostSleeping(lastSeen, reason))
        _state.value = PersonalMacSessionState.HostSleeping(
            installationId = installationId,
            displayName = personal.displayName,
            lastSeenAt = lastSeen,
            reason = reason,
        )
    }

    private fun parseDevicesPayload(rawJson: String, currentDeviceId: String?): List<PairedDeviceSummary> {
        val root = runCatching { LumenBoxJson.default.parseToJsonElement(rawJson).jsonObject }.getOrNull()
            ?: return emptyList()
        val items = root["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { elem ->
            val obj: JsonObject = runCatching { elem.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
            val platform = obj["platform"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val lastSeenAt = obj["lastSeenAt"]?.jsonPrimitive?.contentOrNull
            PairedDeviceSummary(
                id = id,
                name = name,
                platform = platform,
                lastSeenAt = lastSeenAt,
                isCurrentDevice = id == currentDeviceId,
            )
        }
    }
}

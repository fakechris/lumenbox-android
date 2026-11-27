package com.lumenopen.lumenbox.storage

import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.relay.X25519Keypair
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

interface KeyStoreVault {
    fun write(namespace: String, key: String, value: ByteArray)
    fun read(namespace: String, key: String): ByteArray?
    fun deleteNamespace(namespace: String)
}

class InMemoryKeyStoreVault : KeyStoreVault {
    private val map = ConcurrentHashMap<String, ConcurrentHashMap<String, ByteArray>>()

    override fun write(namespace: String, key: String, value: ByteArray) {
        map.computeIfAbsent(namespace) { ConcurrentHashMap() }[key] = value.copyOf()
    }

    override fun read(namespace: String, key: String): ByteArray? =
        map[namespace]?.get(key)?.copyOf()

    override fun deleteNamespace(namespace: String) {
        map.remove(namespace)
    }
}

class PersonalInstallationAlreadyExistsException :
    IllegalStateException("At most one personal Mac installation may be paired")

@Serializable
data class PersonalMacPairingMetadata(
    val hostPublicKeyBase64Url: String,
    val routeId: String,
    val directUrls: List<String> = emptyList(),
    val deviceId: String,
    val scopes: List<String> = emptyList(),
)

/**
 * Cross-boundary connection presence exposed at the top-level switcher (`docs/95 §4`, `docs/110 §3.3`):
 * carries only online/sleeping/offline state and last-seen timestamp, never content.
 */
@Serializable
sealed interface InstallationPresence {
    @Serializable
    data object OnlineDirect : InstallationPresence

    @Serializable
    data object OnlineRelay : InstallationPresence

    @Serializable
    data class HostSleeping(val lastSeenAt: String, val reason: String = "suspend") : InstallationPresence

    @Serializable
    data class Disconnected(val lastSeenAt: String? = null) : InstallationPresence
}

@Serializable
data class InstallationConnection(
    val id: String,
    val connId: String,
    val kind: Kind,
    val displayName: String,
    val serverOriginOrRelayUrl: String,
    val needsYouCount: Int = 0,
    val redactLockScreenNotifications: Boolean = false,
    val personalMetadata: PersonalMacPairingMetadata? = null,
    val presence: InstallationPresence = InstallationPresence.Disconnected(),
) {
    @Serializable
    enum class Kind { PERSONAL_MAC, ORGANISATION }
}

/**
 * Top-level switcher projection (`docs/95 §4`, `docs/110 §0.2`):
 * strictly exposes only `Needs you` count and online/sleeping status across installation boundaries.
 */
data class NeedsYouProjection(
    val installationId: String,
    val displayName: String,
    val kind: InstallationConnection.Kind,
    val needsYouCount: Int,
    val presence: InstallationPresence = InstallationPresence.Disconnected(),
)

class InstallationStore(
    private val vault: KeyStoreVault,
    private val rootDirectory: File,
) {
    companion object {
        private const val REGISTRY_NAMESPACE = "installations.registry"
        private const val REGISTRY_KEY = "connectionsJson"
        private const val ACTIVE_ID_KEY = "activeConnectionId"
        private const val DEVICE_SEED_KEY = "deviceStaticSeed"
    }

    private val random = SecureRandom()
    private val lock = Any()
    private val connections = LinkedHashMap<String, InstallationConnection>()
    private val openOfflineStores = HashMap<String, ConnectionOfflineStore>()
    private val _projections = MutableStateFlow<List<NeedsYouProjection>>(emptyList())
    val projections: StateFlow<List<NeedsYouProjection>> = _projections.asStateFlow()

    private val _activeInstallationId = MutableStateFlow<String?>(null)
    val activeInstallationId: StateFlow<String?> = _activeInstallationId.asStateFlow()

    init {
        rootDirectory.mkdirs()
        synchronized(lock) {
            loadRegistryLocked()
            refreshFlowsLocked()
        }
    }

    fun addConnection(connection: InstallationConnection) {
        synchronized(lock) {
            if (connection.kind == InstallationConnection.Kind.PERSONAL_MAC &&
                connections.values.any { it.kind == InstallationConnection.Kind.PERSONAL_MAC && it.id != connection.id }
            ) {
                throw PersonalInstallationAlreadyExistsException()
            }
            if (vault.read("conn.${connection.id}", "dbKey") == null) {
                val dbKey = ByteArray(32).also { random.nextBytes(it) }
                vault.write("conn.${connection.id}", "dbKey", dbKey)
            }
            File(rootDirectory, connection.id).mkdirs()
            connections[connection.id] = connection
            if (_activeInstallationId.value == null || findByIdLocked(_activeInstallationId.value) == null) {
                setActiveIdLocked(connection.id)
            }
            persistRegistryLocked()
            refreshFlowsLocked()
        }
    }

    fun hasPersonalInstallation(): Boolean = synchronized(lock) {
        connections.values.any { it.kind == InstallationConnection.Kind.PERSONAL_MAC }
    }

    fun findPersonalInstallation(): InstallationConnection? = synchronized(lock) {
        connections.values.firstOrNull { it.kind == InstallationConnection.Kind.PERSONAL_MAC }
    }

    fun findById(id: String): InstallationConnection? = synchronized(lock) { connections[id] }

    fun findByConnId(connId: String): InstallationConnection? =
        synchronized(lock) { connections.values.firstOrNull { it.connId == connId } }

    fun listConnections(): List<InstallationConnection> =
        synchronized(lock) { connections.values.sortedBy { it.id } }

    fun selectActiveInstallation(id: String) {
        synchronized(lock) {
            require(connections.containsKey(id)) { "Unknown installation id: $id" }
            setActiveIdLocked(id)
            refreshFlowsLocked()
        }
    }

    fun updateNeedsYouCount(connectionId: String, needsYouCount: Int) {
        synchronized(lock) {
            val existing = connections[connectionId] ?: return
            connections[connectionId] = existing.copy(needsYouCount = needsYouCount.coerceAtLeast(0))
            persistRegistryLocked()
            refreshFlowsLocked()
        }
    }

    fun updatePresence(connectionId: String, presence: InstallationPresence) {
        synchronized(lock) {
            val existing = connections[connectionId] ?: return
            connections[connectionId] = existing.copy(presence = presence)
            persistRegistryLocked()
            refreshFlowsLocked()
        }
    }

    fun savePersonalDeviceKeypair(connectionId: String, keypair: X25519Keypair) {
        vault.write("conn.$connectionId", DEVICE_SEED_KEY, keypair.privateKey)
    }

    fun readPersonalDeviceKeypair(connectionId: String): X25519Keypair? {
        val seed = vault.read("conn.$connectionId", DEVICE_SEED_KEY) ?: return null
        return X25519Keypair.fromPrivateSeed(seed)
    }

    fun saveRefreshToken(connectionId: String, refreshToken: String) {
        vault.write("conn.$connectionId", "refreshToken", refreshToken.toByteArray(StandardCharsets.UTF_8))
    }

    fun readRefreshToken(connectionId: String): String? =
        vault.read("conn.$connectionId", "refreshToken")?.toString(StandardCharsets.UTF_8)

    fun topLevelNeedsYouProjection(): List<NeedsYouProjection> =
        synchronized(lock) { buildProjectionsLocked() }

    /**
     * Opens (or returns the open) per-connection encrypted offline cache sealed under this
     * connection's own 256-bit `dbKey` from the vault (`docs/110 §2`, INV-2252).
     */
    fun openOfflineStore(connectionId: String): ConnectionOfflineStore {
        val dbKey = vault.read("conn.$connectionId", "dbKey")
            ?: throw IllegalArgumentException("No vault entry for connection $connectionId")
        synchronized(lock) {
            openOfflineStores[connectionId]?.let { return it }
            val store = ConnectionOfflineStore.open(
                connectionId = connectionId,
                directory = File(rootDirectory, connectionId),
                dbKey = dbKey,
            )
            openOfflineStores[connectionId] = store
            return store
        }
    }

    /**
     * Immediate remote wipe on `401 code: "access-removed"` or local unpair (`docs/110 §4.5`):
     * closes and zeroes the offline cache's in-memory key material, destroys the connection's
     * hardware-keystore entries, and deletes its isolated cache directory atomically.
     */
    fun wipeConnection(id: String) {
        val store = synchronized(lock) {
            connections.remove(id)
            val removedStore = openOfflineStores.remove(id)
            if (_activeInstallationId.value == id) {
                setActiveIdLocked(connections.keys.firstOrNull())
            }
            persistRegistryLocked()
            refreshFlowsLocked()
            removedStore
        }
        store?.close()
        vault.deleteNamespace("conn.$id")
        File(rootDirectory, id).deleteRecursively()
    }

    private fun findByIdLocked(id: String?): InstallationConnection? =
        if (id == null) null else connections[id]

    private fun setActiveIdLocked(id: String?) {
        _activeInstallationId.value = id
        if (id == null) {
            vault.deleteNamespace("$REGISTRY_NAMESPACE.active")
        } else {
            vault.write("$REGISTRY_NAMESPACE.active", ACTIVE_ID_KEY, id.toByteArray(StandardCharsets.UTF_8))
        }
    }

    private fun buildProjectionsLocked(): List<NeedsYouProjection> =
        connections.values.sortedWith(
            compareBy<InstallationConnection> { it.kind != InstallationConnection.Kind.PERSONAL_MAC }
                .thenBy { it.id }
        ).map {
            NeedsYouProjection(
                installationId = it.id,
                displayName = it.displayName,
                kind = it.kind,
                needsYouCount = it.needsYouCount,
                presence = it.presence,
            )
        }

    private fun refreshFlowsLocked() {
        _projections.value = buildProjectionsLocked()
    }

    private fun persistRegistryLocked() {
        val payload = LumenBoxJson.default.encodeToString(
            ListSerializer(InstallationConnection.serializer()),
            connections.values.toList(),
        ).toByteArray(StandardCharsets.UTF_8)
        vault.write(REGISTRY_NAMESPACE, REGISTRY_KEY, payload)
    }

    private fun loadRegistryLocked() {
        val raw = vault.read(REGISTRY_NAMESPACE, REGISTRY_KEY) ?: return
        val decoded = runCatching {
            LumenBoxJson.default.decodeFromString(
                ListSerializer(InstallationConnection.serializer()),
                String(raw, StandardCharsets.UTF_8),
            )
        }.getOrDefault(emptyList())
        connections.clear()
        for (conn in decoded) {
            connections[conn.id] = conn
        }
        val savedActive = vault.read("$REGISTRY_NAMESPACE.active", ACTIVE_ID_KEY)
            ?.let { String(it, StandardCharsets.UTF_8) }
        _activeInstallationId.value = when {
            savedActive != null && connections.containsKey(savedActive) -> savedActive
            else -> connections.keys.firstOrNull()
        }
    }
}

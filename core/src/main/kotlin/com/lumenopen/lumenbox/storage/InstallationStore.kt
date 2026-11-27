package com.lumenopen.lumenbox.storage

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

data class InstallationConnection(
    val id: String,
    val connId: String,
    val kind: Kind,
    val displayName: String,
    val serverOriginOrRelayUrl: String,
    val needsYouCount: Int = 0,
    val redactLockScreenNotifications: Boolean = false,
) {
    enum class Kind { PERSONAL_MAC, ORGANISATION }
}

data class NeedsYouProjection(
    val installationId: String,
    val displayName: String,
    val kind: InstallationConnection.Kind,
    val needsYouCount: Int,
)

class InstallationStore(
    private val vault: KeyStoreVault,
    private val rootDirectory: File,
) {
    private val random = SecureRandom()
    private val lock = Any()
    private val connections = LinkedHashMap<String, InstallationConnection>()

    init {
        rootDirectory.mkdirs()
    }

    fun addConnection(connection: InstallationConnection) {
        synchronized(lock) {
            if (connection.kind == InstallationConnection.Kind.PERSONAL_MAC &&
                connections.values.any { it.kind == InstallationConnection.Kind.PERSONAL_MAC && it.id != connection.id }
            ) {
                throw PersonalInstallationAlreadyExistsException()
            }
            val dbKey = ByteArray(32).also { random.nextBytes(it) }
            vault.write("conn.${connection.id}", "dbKey", dbKey)
            File(rootDirectory, connection.id).mkdirs()
            connections[connection.id] = connection
        }
    }

    fun findById(id: String): InstallationConnection? = synchronized(lock) { connections[id] }

    fun findByConnId(connId: String): InstallationConnection? =
        synchronized(lock) { connections.values.firstOrNull { it.connId == connId } }

    fun saveRefreshToken(connectionId: String, refreshToken: String) {
        vault.write("conn.$connectionId", "refreshToken", refreshToken.toByteArray(StandardCharsets.UTF_8))
    }

    fun readRefreshToken(connectionId: String): String? =
        vault.read("conn.$connectionId", "refreshToken")?.toString(StandardCharsets.UTF_8)

    fun topLevelNeedsYouProjection(): List<NeedsYouProjection> =
        synchronized(lock) {
            connections.values.sortedBy { it.id }.map {
                NeedsYouProjection(
                    installationId = it.id,
                    displayName = it.displayName,
                    kind = it.kind,
                    needsYouCount = it.needsYouCount,
                )
            }
        }

    /**
     * Immediate remote wipe on `401 code: "access-removed"` (`docs/110 §4.5`):
     * destroys the connection's hardware-keystore entries and deletes its isolated cache directory.
     */
    fun wipeConnection(id: String) {
        synchronized(lock) {
            connections.remove(id)
        }
        vault.deleteNamespace("conn.$id")
        File(rootDirectory, id).deleteRecursively()
    }
}

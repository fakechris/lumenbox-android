package com.lumenopen.lumenbox.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when a sealed table fails AEAD authentication (tampering or row substitution). */
class OfflineCacheCorruptedException(
    val tableName: String,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

data class PendingOutboundAction(
    val idempotencyKey: String,
    val clientMessageId: String,
    val kind: String,
    val path: String,
    val payloadJson: String,
    val enqueuedAtEpochMillis: Long,
    val sequence: Long,
)

data class OutboundActionDraft(
    val idempotencyKey: String,
    val clientMessageId: String,
    val kind: String,
    val path: String,
    val payloadJson: String,
    val enqueuedAtEpochMillis: Long,
)

@Serializable
private data class OutboundActionRecord(
    val idempotencyKey: String,
    val clientMessageId: String,
    val kind: String,
    val path: String,
    val payloadJson: String,
    val enqueuedAtEpochMillis: Long,
    val sequence: Long,
)

/**
 * Per-connection encrypted offline cache (`docs/110 §2`, `docs/110 §4.5`, INV-2252).
 *
 * Every connection gets an isolated directory and its own 256-bit `dbKey` (managed by
 * [InstallationStore] in the hardware-backed vault). Sensitive payloads are sealed row-by-row
 * with `ChaCha20-Poly1305`; the AEAD additional authenticated data binds
 * `(connectionId, tableName, primaryKey)` so ciphertext cannot be replayed across rows or tables.
 *
 * Table files are rewritten atomically (temp file + fsync + rename) on every mutation, so a
 * crash never leaves a half-written table behind. All state survives process restarts:
 * key-value snapshots, the `durableSeq` watermark, and the idempotent outbound queue.
 */
class ConnectionOfflineStore private constructor(
    val connectionId: String,
    private val directory: File,
    private val dbKey: ByteArray,
) {
    private val lock = Any()
    private val random = SecureRandom()
    private val keySpec = SecretKeySpec(dbKey.copyOf(), "ChaCha20")
    private val json = Json { ignoreUnknownKeys = true }
    /** tableName -> (primaryKey -> value), populated lazily per table on first access. */
    private val tables = HashMap<String, HashMap<String, ByteArray>>()
    private val loadedTables = HashSet<String>()
    private var closed = false

    companion object {
        private val MAGIC: ByteArray = "LBX1".toByteArray(StandardCharsets.US_ASCII)
        private const val NONCE_BYTES = 12
        private const val TAG_BYTES = 16
        private const val META_TABLE = "meta"
        private const val DURABLE_SEQ_ROW = "durableSeq"
        private const val OUTBOUND_SEQ_ROW = "outboundSeq"
        private const val OUTBOUND_TABLE = "outboundQueue"

        fun open(connectionId: String, directory: File, dbKey: ByteArray): ConnectionOfflineStore {
            require(dbKey.size == 32) { "dbKey must be 256-bit" }
            directory.mkdirs()
            return ConnectionOfflineStore(connectionId, directory, dbKey)
        }
    }

    // ---- generic sealed key-value rows ----

    fun put(tableName: String, primaryKey: String, value: ByteArray) = synchronized(lock) {
        ensureLoaded(tableName)
        val rows = tables.getValue(tableName)
        rows[primaryKey] = value.copyOf()
        writeTable(tableName, rows)
    }

    fun putText(tableName: String, primaryKey: String, value: String) =
        put(tableName, primaryKey, value.toByteArray(StandardCharsets.UTF_8))

    fun getText(tableName: String, primaryKey: String): String? =
        get(tableName, primaryKey)?.toString(StandardCharsets.UTF_8)

    fun <T> putJson(tableName: String, primaryKey: String, serializer: KSerializer<T>, value: T) =
        putText(tableName, primaryKey, json.encodeToString(serializer, value))

    fun <T> getJson(tableName: String, primaryKey: String, serializer: KSerializer<T>): T? {
        val text = getText(tableName, primaryKey) ?: return null
        return json.decodeFromString(serializer, text)
    }

    fun get(tableName: String, primaryKey: String): ByteArray? = synchronized(lock) {
        ensureLoaded(tableName)
        tables[tableName]?.get(primaryKey)?.copyOf()
    }

    fun deleteRow(tableName: String, primaryKey: String) {
        synchronized(lock) {
            ensureLoaded(tableName)
            val rows = tables.getValue(tableName)
            if (rows.remove(primaryKey) != null) writeTable(tableName, rows)
        }
    }

    fun rowKeys(tableName: String): List<String> = synchronized(lock) {
        ensureLoaded(tableName)
        return tables[tableName]?.keys?.sorted()?.toList() ?: emptyList()
    }

    // ---- durableSeq watermark (feeds `/api/v1/changes?since=` after wake) ----

    fun durableSeq(): Long? = synchronized(lock) {
        ensureLoaded(META_TABLE)
        val raw = tables[META_TABLE]?.get(DURABLE_SEQ_ROW) ?: return null
        ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).long
    }

    /** Monotonic watermark: only advances, never regresses on stale server responses. */
    fun advanceDurableSeq(observed: Long) {
        synchronized(lock) {
            val current = durableSeq()
            if (current == null || observed > current) {
                val encoded = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(observed).array()
                put(META_TABLE, DURABLE_SEQ_ROW, encoded)
            }
        }
    }

    // ---- idempotent offline outbound queue ----

    fun enqueueOutboundAction(draft: OutboundActionDraft): PendingOutboundAction = synchronized(lock) {
        ensureLoaded(OUTBOUND_TABLE)
        val rows = tables.getValue(OUTBOUND_TABLE)
        val existing = rows.values.map(::decodeOutboundAction)
        require(existing.none { it.clientMessageId == draft.clientMessageId }) {
            "Duplicate clientMessageId: ${draft.clientMessageId}"
        }
        require(existing.none { it.idempotencyKey == draft.idempotencyKey }) {
            "Duplicate idempotencyKey: ${draft.idempotencyKey}"
        }
        val sequence = nextOutboundSequence()
        val action = PendingOutboundAction(
            idempotencyKey = draft.idempotencyKey,
            clientMessageId = draft.clientMessageId,
            kind = draft.kind,
            path = draft.path,
            payloadJson = draft.payloadJson,
            enqueuedAtEpochMillis = draft.enqueuedAtEpochMillis,
            sequence = sequence,
        )
        val record = action.toRecord()
        val next = HashMap(rows)
        next[outboundPrimaryKey(sequence)] = json.encodeToString(OutboundActionRecord.serializer(), record)
            .toByteArray(StandardCharsets.UTF_8)
        writeTable(OUTBOUND_TABLE, next)
        return action
    }

    fun pendingOutboundActions(): List<PendingOutboundAction> = synchronized(lock) {
        ensureLoaded(OUTBOUND_TABLE)
        return tables.getValue(OUTBOUND_TABLE).values
            .map(::decodeOutboundAction)
            .sortedBy { it.sequence }
    }

    /** Removes an action once the server acknowledged its `Idempotency-Key`; true if it was pending. */
    fun acknowledgeOutboundAction(clientMessageId: String): Boolean = synchronized(lock) {
        ensureLoaded(OUTBOUND_TABLE)
        val rows = tables.getValue(OUTBOUND_TABLE)
        val victim = rows.entries.firstOrNull {
            decodeOutboundAction(it.value).clientMessageId == clientMessageId
        } ?: return false
        writeTable(OUTBOUND_TABLE, rows - victim.key)
        return true
    }

    fun pendingOutboundCount(): Int = synchronized(lock) {
        ensureLoaded(OUTBOUND_TABLE)
        return tables.getValue(OUTBOUND_TABLE).size
    }

    // ---- lifecycle ----

    /** Zeroes in-memory key material; storage files remain until [InstallationStore.wipeConnection]. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            dbKey.fill(0)
            try {
                keySpec.destroy()
            } catch (_: javax.security.auth.DestroyFailedException) {
                // DestroyFailedException from SecretKeySpec is harmless; bytes are already copied.
            }
        }
    }

    val isClosed: Boolean get() = synchronized(lock) { closed }

    // ---- sealing internals ----

    private fun aad(tableName: String, primaryKey: String): ByteArray {
        val pkBytes = primaryKey.toByteArray(StandardCharsets.UTF_8)
        val connBytes = connectionId.toByteArray(StandardCharsets.UTF_8)
        val tableBytes = tableName.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream(MAGIC.size + connBytes.size + tableBytes.size + pkBytes.size + 3)
        out.write(MAGIC)
        out.write(0)
        out.write(connBytes)
        out.write(0)
        out.write(tableBytes)
        out.write(0)
        out.write(pkBytes)
        return out.toByteArray()
    }

    private fun seal(tableName: String, primaryKey: String, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(nonce))
        cipher.updateAAD(aad(tableName, primaryKey))
        return nonce + cipher.doFinal(plaintext)
    }

    private fun openSealed(tableName: String, primaryKey: String, sealed: ByteArray): ByteArray {
        if (sealed.size < NONCE_BYTES + TAG_BYTES) {
            throw OfflineCacheCorruptedException(tableName, "Sealed row shorter than nonce + tag")
        }
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(sealed, 0, NONCE_BYTES))
        cipher.updateAAD(aad(tableName, primaryKey))
        return try {
            cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
        } catch (e: Exception) {
            throw OfflineCacheCorruptedException(
                tableName,
                "AEAD authentication failed for row '$primaryKey' (tamper or substitution)",
                e,
            )
        }
    }

    /** Decodes every frame of the table file and verifies each row's AEAD tag (last write wins). */
    private fun ensureLoaded(tableName: String) {
        check(!closed) { "ConnectionOfflineStore for $connectionId is closed" }
        if (tableName in loadedTables) return
        val rows = HashMap<String, ByteArray>()
        val file = tableFile(tableName)
        if (file.exists()) {
            RandomAccessFile(file, "r").use { raf ->
                val magic = ByteArray(MAGIC.size)
                raf.readFully(magic)
                if (!magic.contentEquals(MAGIC)) {
                    throw OfflineCacheCorruptedException(tableName, "Bad table file magic")
                }
                while (raf.filePointer < raf.length()) {
                    if (raf.length() - raf.filePointer < 6) {
                        throw OfflineCacheCorruptedException(tableName, "Truncated frame header")
                    }
                    val frameLen = raf.readInt()
                    val pkLen = raf.readShort().toInt() and 0xffff
                    val sealedLen = frameLen - pkLen
                    if (sealedLen < NONCE_BYTES + TAG_BYTES ||
                        raf.length() - raf.filePointer < frameLen
                    ) {
                        throw OfflineCacheCorruptedException(tableName, "Invalid frame length")
                    }
                    val pkBytes = ByteArray(pkLen).also(raf::readFully)
                    val sealed = ByteArray(sealedLen).also(raf::readFully)
                    val pk = String(pkBytes, StandardCharsets.UTF_8)
                    rows[pk] = openSealed(tableName, pk, sealed)
                }
            }
        }
        tables[tableName] = rows
        loadedTables.add(tableName)
    }

    /** Atomic rewrite: temp file in the same directory, fsync, then rename over the live table. */
    private fun writeTable(tableName: String, rows: Map<String, ByteArray>) {
        val file = tableFile(tableName)
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { raw ->
            val out = java.io.DataOutputStream(raw)
            out.write(MAGIC)
            for ((pk, value) in rows.entries.sortedBy { it.key }) {
                val pkBytes = pk.toByteArray(StandardCharsets.UTF_8)
                if (pkBytes.size > 0xffff) throw IllegalArgumentException("Primary key too long: $pk")
                val sealed = seal(tableName, pk, value)
                out.writeInt(pkBytes.size + sealed.size)
                out.writeShort(pkBytes.size)
                out.write(pkBytes)
                out.write(sealed)
            }
            out.flush()
            raw.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        tables[tableName] = HashMap(rows)
        loadedTables.add(tableName)
    }

    private fun tableFile(tableName: String): File {
        require(tableName.none { it == '/' || it == '\\' || it == '\u0000' }) { "Illegal table name" }
        return File(directory, "$tableName.bin")
    }

    private fun nextOutboundSequence(): Long {
        ensureLoaded(META_TABLE)
        val meta = tables.getValue(META_TABLE)
        val raw = meta[OUTBOUND_SEQ_ROW]
        val seq = if (raw == null) {
            1L
        } else {
            ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN).long + 1L
        }
        meta[OUTBOUND_SEQ_ROW] = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(seq).array()
        writeTable(META_TABLE, meta)
        return seq
    }

    private fun outboundPrimaryKey(sequence: Long): String = sequence.toString(10).padStart(20, '0')

    private fun PendingOutboundAction.toRecord() = OutboundActionRecord(
        idempotencyKey = idempotencyKey,
        clientMessageId = clientMessageId,
        kind = kind,
        path = path,
        payloadJson = payloadJson,
        enqueuedAtEpochMillis = enqueuedAtEpochMillis,
        sequence = sequence,
    )

    private fun decodeOutboundAction(raw: ByteArray): PendingOutboundAction {
        val record = json.decodeFromString(
            OutboundActionRecord.serializer(),
            raw.toString(StandardCharsets.UTF_8),
        )
        return PendingOutboundAction(
            idempotencyKey = record.idempotencyKey,
            clientMessageId = record.clientMessageId,
            kind = record.kind,
            path = record.path,
            payloadJson = record.payloadJson,
            enqueuedAtEpochMillis = record.enqueuedAtEpochMillis,
            sequence = record.sequence,
        )
    }
}

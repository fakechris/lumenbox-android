package com.lumenopen.lumenbox.storage

import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

@Serializable
private data class ConversationSnapshot(val conversationId: String, val title: String, val lastSeq: Long)

class ConnectionOfflineStoreTest {
    @TempDir
    lateinit var tempDir: File

    private fun newVault() = InMemoryKeyStoreVault()

    private fun addConnection(vault: InMemoryKeyStoreVault, id: String): InstallationStore {
        val root = File(tempDir, "installations")
        val store = InstallationStore(vault, root)
        store.addConnection(
            InstallationConnection(
                id = id,
                connId = "conn-$id",
                kind = InstallationConnection.Kind.ORGANISATION,
                displayName = "Installation $id",
                serverOriginOrRelayUrl = "https://org.example.invalid/api/v1",
            )
        )
        return store
    }

    @Test
    fun `restart reload fully decrypts rows, durableSeq watermark and outbound queue`() {
        val vault = newVault()
        val installations = addConnection(vault, "org-a")
        val store = installations.openOfflineStore("org-a")

        store.putText("workspace", "overview", "workspace snapshot text")
        store.putJson("conversations", "conv-1", ConversationSnapshot.serializer(), ConversationSnapshot("conv-1", "Standup", 42))
        store.advanceDurableSeq(41)
        val enqueued = store.enqueueOutboundAction(
            OutboundActionDraft(
                idempotencyKey = "idem-1",
                clientMessageId = "cm-1",
                kind = "inbox.decision",
                path = "/api/v1/inbox/DEC-1/decision",
                payloadJson = """{"decision":"approve"}""",
                enqueuedAtEpochMillis = 1_700_000_000_000,
            )
        )
        assertEquals(1L, enqueued.sequence)
        store.close()

        // Fresh process: same vault, same directory, same 256-bit dbKey.
        val reopenedStore = InstallationStore(vault, File(tempDir, "installations")).openOfflineStore("org-a")

        assertEquals("workspace snapshot text", reopenedStore.getText("workspace", "overview"))
        assertEquals(
            ConversationSnapshot("conv-1", "Standup", 42),
            reopenedStore.getJson("conversations", "conv-1", ConversationSnapshot.serializer()),
        )
        assertEquals(41L, reopenedStore.durableSeq())
        val pending = reopenedStore.pendingOutboundActions()
        assertEquals(1, pending.size)
        assertEquals("idem-1", pending[0].idempotencyKey)
        assertEquals("cm-1", pending[0].clientMessageId)
        assertEquals(1L, pending[0].sequence)
        reopenedStore.close()
    }

    @Test
    fun `tampered ciphertext raises AEAD corruption on reopen`() {
        val vault = newVault()
        val installations = addConnection(vault, "org-tamper")
        val store = installations.openOfflineStore("org-tamper")
        store.putText("workspace", "overview", "do not touch")
        store.close()

        val tableFile = File(File(tempDir, "installations"), "org-tamper/workspace.bin")
        RandomAccessFile(tableFile, "rw").use { raf ->
            raf.seek(raf.length() - 2) // flip a byte inside the Poly1305 tag region
            raf.writeByte(raf.readByte().toInt() xor 0x40)
        }

        val reopened = InstallationStore(vault, File(tempDir, "installations"))
        val store2 = reopened.openOfflineStore("org-tamper")
        val exception = assertThrows(OfflineCacheCorruptedException::class.java) {
            store2.getText("workspace", "overview")
        }
        assertEquals("workspace", exception.tableName)
        store2.close()
    }

    @Test
    fun `swapping row primary keys raises AEAD corruption on reopen`() {
        val vault = newVault()
        val installations = addConnection(vault, "org-swap")
        val store = installations.openOfflineStore("org-swap")
        store.putText("workspace", "alpha", "row a")
        store.putText("workspace", "beta", "row b")
        store.close()

        // Swap the two primary keys in the frame headers; each row's AAD no longer matches its key.
        val tableFile = File(File(tempDir, "installations"), "org-swap/workspace.bin")
        val bytes = tableFile.readBytes()
        // Layout: magic(4) | frameLen(4) pkLen(2) pkBytes("alpha") nonce+ct ... | frameLen(4) pkLen(2) pkBytes("beta") ...
        val firstFrameStart = 4
        val firstPkLen = ((bytes[firstFrameStart + 4].toInt() and 0xff) shl 8) or (bytes[firstFrameStart + 5].toInt() and 0xff)
        val firstPkStart = firstFrameStart + 6
        val firstFrameLen = ((bytes[firstFrameStart].toInt() and 0xff) shl 24) or
            ((bytes[firstFrameStart + 1].toInt() and 0xff) shl 16) or
            ((bytes[firstFrameStart + 2].toInt() and 0xff) shl 8) or
            (bytes[firstFrameStart + 3].toInt() and 0xff)
        val secondFrameStart = firstFrameStart + 6 + firstFrameLen
        val secondPkStart = secondFrameStart + 6
        System.arraycopy(bytes, secondPkStart, bytes, firstPkStart, firstPkLen)
        System.arraycopy(bytes, firstPkStart, bytes, secondPkStart, firstPkLen)
        tableFile.writeBytes(bytes)

        val reopened = InstallationStore(vault, File(tempDir, "installations"))
        val store2 = reopened.openOfflineStore("org-swap")
        assertThrows(OfflineCacheCorruptedException::class.java) {
            store2.getText("workspace", "alpha")
        }
        store2.close()
    }

    @Test
    fun `wipeConnection destroys key and files but leaves sibling connections intact`() {
        val vault = newVault()
        val installations = InstallationStore(vault, File(tempDir, "installations"))
        for (id in listOf("org-a", "org-b")) {
            installations.addConnection(
                InstallationConnection(
                    id = id,
                    connId = "conn-$id",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Installation $id",
                    serverOriginOrRelayUrl = "https://org.example.invalid/api/v1",
                )
            )
        }
        val storeA = installations.openOfflineStore("org-a")
        storeA.putText("workspace", "secret", "wipe me")
        storeA.advanceDurableSeq(7)
        val storeB = installations.openOfflineStore("org-b")
        storeB.putText("workspace", "keep", "still here")

        installations.wipeConnection("org-a")

        assertNull(vault.read("conn.org-a", "dbKey"))
        assertFalse(File(File(tempDir, "installations"), "org-a").exists())
        assertTrue(storeA.isClosed)
        assertEquals("still here", storeB.getText("workspace", "keep"))
        // The wiped connection's vault key is destroyed: it cannot be reopened at all, and the
        // old sealed files are gone, so cached data is unrecoverable.
        assertThrows(IllegalArgumentException::class.java) {
            installations.openOfflineStore("org-a")
        }
    }

    @Test
    fun `outbound queue replays in order, dedupes and acknowledges`() {
        val vault = newVault()
        val installations = addConnection(vault, "org-q")
        val store = installations.openOfflineStore("org-q")

        fun draft(id: String) = OutboundActionDraft(
            idempotencyKey = "idem-$id",
            clientMessageId = "cm-$id",
            kind = "inbox.decision",
            path = "/api/v1/inbox/DEC-$id/decision",
            payloadJson = """{"decision":"approve"}""",
            enqueuedAtEpochMillis = id.toLong(),
        )
        store.enqueueOutboundAction(draft("1"))
        store.enqueueOutboundAction(draft("2"))
        store.enqueueOutboundAction(draft("3"))

        assertThrows(IllegalArgumentException::class.java) { store.enqueueOutboundAction(draft("2")) }

        assertEquals(listOf("1", "2", "3"), store.pendingOutboundActions().map { it.clientMessageId.removePrefix("cm-") })
        assertTrue(store.acknowledgeOutboundAction("cm-2"))
        assertFalse(store.acknowledgeOutboundAction("cm-2"))
        assertEquals(listOf("cm-1", "cm-3"), store.pendingOutboundActions().map { it.clientMessageId })
        assertEquals(2, store.pendingOutboundCount())

        store.close()
        val reopened = InstallationStore(vault, File(tempDir, "installations")).openOfflineStore("org-q")
        // Sequence numbering continues monotonically across restarts.
        assertEquals(4L, reopened.enqueueOutboundAction(draft("4")).sequence)
        assertEquals(listOf("cm-1", "cm-3", "cm-4"), reopened.pendingOutboundActions().map { it.clientMessageId })
        reopened.close()
    }

    @Test
    fun `durableSeq watermark only advances`() {
        val vault = newVault()
        val installations = addConnection(vault, "org-seq")
        val store = installations.openOfflineStore("org-seq")
        assertNull(store.durableSeq())
        store.advanceDurableSeq(10)
        store.advanceDurableSeq(9) // stale server response must not regress the watermark
        assertEquals(10L, store.durableSeq())
        store.advanceDurableSeq(11)
        assertEquals(11L, store.durableSeq())
        store.close()
    }
}

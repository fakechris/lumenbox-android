package com.lumenopen.lumenbox.conversation

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.InboxDecisionItem
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.generated.models.GeneratedBoardColumn
import com.lumenopen.lumenbox.generated.models.GeneratedBoardProjection
import com.lumenopen.lumenbox.generated.models.GeneratedCardActionRequest
import com.lumenopen.lumenbox.generated.models.GeneratedChangesResponse
import com.lumenopen.lumenbox.generated.models.GeneratedConversationMessage
import com.lumenopen.lumenbox.generated.models.GeneratedConversationPage
import com.lumenopen.lumenbox.generated.models.GeneratedConversationSummary
import com.lumenopen.lumenbox.generated.models.GeneratedDeliverableLink
import com.lumenopen.lumenbox.generated.models.GeneratedDeliverableSummary
import com.lumenopen.lumenbox.generated.models.GeneratedMessagePage
import com.lumenopen.lumenbox.generated.models.GeneratedSendMessageAck
import com.lumenopen.lumenbox.generated.models.GeneratedSendMessageRequest
import com.lumenopen.lumenbox.generated.models.GeneratedUploadDescriptor
import com.lumenopen.lumenbox.generated.models.GeneratedWorkPage
import com.lumenopen.lumenbox.generated.models.GeneratedWorkSummary
import com.lumenopen.lumenbox.relay.StreamTarget
import com.lumenopen.lumenbox.storage.ConnectionOfflineStore
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.storage.OutboundActionDraft
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
private data class StagedUploadSpec(
    val localId: String,
    val fileName: String,
    val mimeType: String,
    val kind: String,
    val sizeBytes: Long,
    val uploadIdempotencyKey: String,
    val uploadedAttachmentId: String? = null,
)

@Serializable
private data class StagedPromptBundle(
    val clientMessageId: String,
    val sendIdempotencyKey: String,
    val conversationId: String,
    val text: String,
    val createdAt: String,
    val uploads: List<StagedUploadSpec> = emptyList(),
)

/**
 * Dual-track streaming conversation, `/changes?since=<durableSeq>` durable sync, idempotent
 * multimedia upload, work/board browser, and short-lived deliverable preview coordinator
 * (`docs/106 §4.2`, `docs/110 §2`, INV-2257).
 *
 * Works identically across Personal Mac (`EncryptedRelayTransport.overMux` + `RelayMux.openStream`)
 * and Organisation (`AuthenticatedHttpsTransport` + HTTPS SSE) installations.
 */
class ConversationAndBoardCoordinator(
    private val connectionId: String,
    private val store: InstallationStore,
    private val transportProvider: () -> ApiTransport?,
    private val sseConnectorProvider: () -> LiveSseConnector? = { null },
    private val clock: () -> Instant = { Instant.now() },
    private val linkExpirySafetySkewSeconds: Long = 15L,
) {
    companion object {
        private const val CONVERSATIONS_TABLE = "conversationsSnapshot"
        private const val CONVERSATIONS_ROW = "items"
        private const val MESSAGES_TABLE = "conversationMessages"
        private const val WORK_TABLE = "workSnapshot"
        private const val WORK_ROW = "items"
        private const val BOARD_TABLE = "boardSnapshot"
        private const val BOARD_ROW = "projection"
        private const val DELIVERABLES_TABLE = "deliverablesSnapshot"
        private const val DELIVERABLES_ROW = "items"
        private const val INBOX_TABLE = "inboxSnapshot"
        private const val INBOX_ITEMS_ROW = "items"
        private const val EVENTS_META_TABLE = "eventsCursor"
        private const val LAST_EVENT_ID_ROW = "lastEventId"
        private const val STAGED_BUNDLES_TABLE = "stagedPromptBundles"
        private const val STAGED_BLOBS_TABLE = "stagedUploadBlobs"
        private const val MAX_CHANGES_PAGES_PER_SYNC = 32
    }

    private val flushMutex = Mutex()
    private val previewCache = ConcurrentHashMap<String, DeliverablePreviewTicket>()

    private val _liveTurns = MutableStateFlow<Map<String, LiveTurnDraft>>(emptyMap())
    val liveTurns: StateFlow<Map<String, LiveTurnDraft>> = _liveTurns.asStateFlow()

    // ---- Local Encrypted Cache Readers ----

    fun readCachedConversations(): List<ConversationSummary> =
        openStoreOrNull()?.getJson(
            CONVERSATIONS_TABLE,
            CONVERSATIONS_ROW,
            ListSerializer(ConversationSummary.serializer()),
        ).orEmpty()

    fun readCachedMessages(conversationId: String): List<CommittedMessage> =
        openStoreOrNull()?.getJson(
            MESSAGES_TABLE,
            conversationId,
            ListSerializer(CommittedMessage.serializer()),
        ).orEmpty()

    fun readCachedWorkItems(): List<WorkItemSummary> =
        openStoreOrNull()?.getJson(
            WORK_TABLE,
            WORK_ROW,
            ListSerializer(WorkItemSummary.serializer()),
        ).orEmpty()

    fun readCachedBoard(): BoardSnapshot =
        openStoreOrNull()?.getJson(
            BOARD_TABLE,
            BOARD_ROW,
            BoardSnapshot.serializer(),
        ) ?: BoardSnapshot()

    fun readCachedDeliverables(): List<DeliverableMetadata> =
        openStoreOrNull()?.getJson(
            DELIVERABLES_TABLE,
            DELIVERABLES_ROW,
            ListSerializer(DeliverableMetadata.serializer()),
        ).orEmpty()

    fun currentDurableSeq(): Long =
        openStoreOrNull()?.durableSeq() ?: 0L

    fun pendingPromptCount(): Int =
        openStoreOrNull()?.rowKeys(STAGED_BUNDLES_TABLE)?.size ?: 0

    // ---- 1. Durable Ledger Catch-Up (`GET /api/v1/changes?since=<durableSeq>`) ----

    /**
     * Incrementally catches up the local encrypted store from `GET /api/v1/changes?since=<durableSeq>`
     * on app launch, foreground resume, or wake (`docs/110 §2`). Loops while `hasMore == true`
     * and monotonically advances `durableSeq`.
     */
    suspend fun syncDurableChanges(): Long {
        val transport = transportProvider() ?: return currentDurableSeq()
        val offline = store.openOfflineStore(connectionId)
        var cursor = offline.durableSeq() ?: 0L
        var pages = 0

        while (pages < MAX_CHANGES_PAGES_PER_SYNC) {
            pages += 1
            val res = transport.send(
                TransportRequest(
                    method = "GET",
                    path = "/api/v1/changes?since=$cursor",
                    headers = mapOf("Accept" to "application/json"),
                )
            )
            throwIfError(res.statusCode, res.body)

            val raw = String(res.body, StandardCharsets.UTF_8)
            val parsed = LumenBoxJson.default.decodeFromString(GeneratedChangesResponse.serializer(), raw)
            val rawRoot = runCatching { LumenBoxJson.default.parseToJsonElement(raw).jsonObject }.getOrNull()

            applyChangesDelta(offline, parsed, rawRoot)

            val nextSeq = parsed.durableSeq
            offline.advanceDurableSeq(nextSeq)
            val progressed = nextSeq > cursor
            cursor = offline.durableSeq() ?: nextSeq

            if (parsed.hasMore != true || !progressed) {
                break
            }
        }
        return offline.durableSeq() ?: cursor
    }

    private fun applyChangesDelta(
        offline: ConnectionOfflineStore,
        delta: GeneratedChangesResponse,
        rawRoot: JsonObject?,
    ) {
        val removedIds = delta.removedConversationIds.orEmpty().toSet()
        if (!delta.conversations.isNullOrEmpty() || removedIds.isNotEmpty()) {
            val existing = readCachedConversations().associateByTo(LinkedHashMap()) { it.id }
            removedIds.forEach { id ->
                existing.remove(id)
                offline.deleteRow(MESSAGES_TABLE, id)
            }
            for (wire in delta.conversations.orEmpty()) {
                existing[wire.id] = wire.toDomain()
            }
            val sorted = existing.values.sortedByDescending { it.updatedAt }
            offline.putJson(CONVERSATIONS_TABLE, CONVERSATIONS_ROW, ListSerializer(ConversationSummary.serializer()), sorted)
        }

        if (!delta.messages.isNullOrEmpty()) {
            val byConv = delta.messages.groupBy { it.conversationId }
            for ((convId, incoming) in byConv) {
                if (convId in removedIds) continue
                mergeMessagesIntoCache(offline, convId, incoming.map { it.toDomain() })
            }
        }

        // If `inbox` array is present in `/changes`, update cached inbox & top-level Needs you count
        if (rawRoot != null && "inbox" in rawRoot) {
            val inboxArray = rawRoot["inbox"]?.jsonArray
            if (inboxArray != null) {
                val items = inboxArray.map { elem ->
                    LumenBoxJson.default.decodeFromJsonElement(InboxDecisionItem.serializer(), elem)
                }
                offline.putJson(INBOX_TABLE, INBOX_ITEMS_ROW, ListSerializer(InboxDecisionItem.serializer()), items)
                store.updateNeedsYouCount(connectionId, items.size)
            }
        }

        if (!delta.work.isNullOrEmpty()) {
            val existing = readCachedWorkItems().associateByTo(LinkedHashMap()) { it.id }
            for (wire in delta.work) {
                existing[wire.id] = wire.toDomain()
            }
            val merged = existing.values.sortedByDescending { it.updatedAt }
            offline.putJson(WORK_TABLE, WORK_ROW, ListSerializer(WorkItemSummary.serializer()), merged)
        }

        if (!delta.deliverables.isNullOrEmpty()) {
            val existing = readCachedDeliverables().associateByTo(LinkedHashMap()) { it.id }
            for (wire in delta.deliverables) {
                existing[wire.id] = wire.toDomain()
            }
            val merged = existing.values.sortedByDescending { it.createdAt }
            offline.putJson(DELIVERABLES_TABLE, DELIVERABLES_ROW, ListSerializer(DeliverableMetadata.serializer()), merged)
        }
    }

    // ---- 2. Conversations & Committed Messages ----

    suspend fun refreshConversations(limit: Int = 50, cursor: String? = null): List<ConversationSummary> {
        val transport = transportProvider() ?: return readCachedConversations()
        val query = buildList {
            add("limit=${limit.coerceIn(1, 100)}")
            if (!cursor.isNullOrBlank()) add("cursor=${urlEncode(cursor)}")
        }.joinToString("&")
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/conversations?$query",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        throwIfError(res.statusCode, res.body)

        val page = LumenBoxJson.default.decodeFromString(
            GeneratedConversationPage.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val offline = store.openOfflineStore(connectionId)
        val mergedMap = if (cursor == null) {
            LinkedHashMap()
        } else {
            readCachedConversations().associateByTo(LinkedHashMap()) { it.id }
        }
        for (item in page.items) {
            mergedMap[item.id] = item.toDomain()
        }
        val merged = mergedMap.values.sortedByDescending { it.updatedAt }
        offline.putJson(CONVERSATIONS_TABLE, CONVERSATIONS_ROW, ListSerializer(ConversationSummary.serializer()), merged)
        return merged
    }

    suspend fun refreshConversationMessages(
        conversationId: String,
        limit: Int = 100,
        cursor: String? = null,
    ): List<CommittedMessage> {
        val transport = transportProvider() ?: return readCachedMessages(conversationId)
        val query = buildList {
            add("limit=${limit.coerceIn(1, 100)}")
            if (!cursor.isNullOrBlank()) add("cursor=${urlEncode(cursor)}")
        }.joinToString("&")
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/conversations/${urlEncodePath(conversationId)}/messages?$query",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        throwIfError(res.statusCode, res.body)

        val page = LumenBoxJson.default.decodeFromString(
            GeneratedMessagePage.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val offline = store.openOfflineStore(connectionId)
        return mergeMessagesIntoCache(offline, conversationId, page.items.map { it.toDomain() })
    }

    // ---- 3. Foreground Live Streams (`/events` & `/conversations/{id}/stream`) ----

    /**
     * Collects global foreground durable state transitions from `GET /api/v1/events`
     * with `Last-Event-ID` resume (`docs/110 §2`).
     */
    suspend fun collectGlobalEventsStream(
        onDurableEvent: (SseFrame) -> Unit = {},
    ) {
        val connector = sseConnectorProvider() ?: return
        val offline = store.openOfflineStore(connectionId)
        val lastEventId = offline.getText(EVENTS_META_TABLE, LAST_EVENT_ID_ROW)

        connector.openSseStream(
            target = StreamTarget.EVENTS,
            path = "/api/v1/events",
            lastEventId = lastEventId,
        ).collect { frame ->
            if (!frame.id.isNullOrBlank()) {
                offline.putText(EVENTS_META_TABLE, LAST_EVENT_ID_ROW, frame.id)
            }
            applyGlobalEventFrame(offline, frame)
            onDurableEvent(frame)
        }
    }

    /**
     * Subscribes to transient live deltas (`thinking`, `text`, `tool`, `ShowUI` cards, `message_committed`,
     * `turn_stopped`) while [conversationId] is open on screen (`GET /api/v1/conversations/{id}/stream`).
     */
    suspend fun collectConversationStream(
        conversationId: String,
        onDraftUpdated: (LiveTurnDraft?) -> Unit = {},
    ) {
        val connector = sseConnectorProvider() ?: return
        try {
            connector.openSseStream(
                target = StreamTarget.CONVERSATION_STREAM,
                path = "/api/v1/conversations/${urlEncodePath(conversationId)}/stream",
                lastEventId = null,
            ).collect { frame ->
                applyConversationStreamFrame(conversationId, frame)
                onDraftUpdated(_liveTurns.value[conversationId])
            }
        } finally {
            // Clear transient live draft if the turn had already stopped when the screen closes
            val current = _liveTurns.value[conversationId]
            if (current != null && !current.isRunning && current.responseText.isEmpty() && current.thinkingText.isEmpty()) {
                clearLiveTurn(conversationId)
            }
        }
    }

    /**
     * Processes a single `/conversations/{id}/stream` SSE frame deterministically (exposed for both
     * live stream collection and deterministic unit tests).
     */
    fun applyConversationStreamFrame(conversationId: String, frame: SseFrame) {
        val obj = runCatching { LumenBoxJson.default.parseToJsonElement(frame.data).jsonObject }.getOrNull()
            ?: return
        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: frame.event
        val turnId = obj["turnId"]?.jsonPrimitive?.contentOrNull
            ?: _liveTurns.value[conversationId]?.turnId
            ?: "turn_live"

        when (type) {
            "thinking_delta", "thinking" -> {
                val delta = obj["delta"]?.jsonPrimitive?.contentOrNull
                    ?: obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: return
                updateLiveTurn(conversationId, turnId) { prev ->
                    prev.copy(thinkingText = prev.thinkingText + delta, isRunning = true)
                }
            }

            "text_delta", "delta" -> {
                val delta = obj["delta"]?.jsonPrimitive?.contentOrNull
                    ?: obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: return
                updateLiveTurn(conversationId, turnId) { prev ->
                    prev.copy(responseText = prev.responseText + delta, isRunning = true)
                }
            }

            "tool_progress", "tool_call", "tool" -> {
                val toolId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull
                    ?: obj["id"]?.jsonPrimitive?.contentOrNull
                    ?: return
                val toolName = obj["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
                val statusRaw = obj["status"]?.jsonPrimitive?.contentOrNull ?: "running"
                val summary = obj["summary"]?.jsonPrimitive?.contentOrNull
                val entry = ToolTimelineEntry(
                    id = toolId,
                    name = toolName,
                    status = ToolRunStatus.fromWire(statusRaw),
                    summary = summary,
                )
                updateLiveTurn(conversationId, turnId) { prev ->
                    val updatedList = prev.toolCalls.toMutableList()
                    val idx = updatedList.indexOfFirst { it.id == toolId }
                    if (idx >= 0) updatedList[idx] = entry else updatedList.add(entry)
                    prev.copy(toolCalls = updatedList, isRunning = true)
                }
            }

            "show_ui", "card" -> {
                val cardElem = obj["card"] ?: obj
                val cardId = cardElem.jsonObject["cardId"]?.jsonPrimitive?.contentOrNull ?: return
                val title = cardElem.jsonObject["title"]?.jsonPrimitive?.contentOrNull ?: cardId
                val body = cardElem.jsonObject["body"]?.jsonPrimitive?.contentOrNull
                val actions = cardElem.jsonObject["actions"]?.jsonArray?.mapNotNull { act ->
                    val actObj = runCatching { act.jsonObject }.getOrNull() ?: return@mapNotNull null
                    val actionId = actObj["actionId"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val label = actObj["label"]?.jsonPrimitive?.contentOrNull ?: actionId
                    ShowUiCardAction(actionId = actionId, label = label, style = actObj["style"]?.jsonPrimitive?.contentOrNull)
                }.orEmpty()
                val parsedCard = ShowUiCard(cardId = cardId, title = title, body = body, actions = actions)
                updateLiveTurn(conversationId, turnId) { prev ->
                    val nextCards = prev.cards.filterNot { it.cardId == cardId } + parsedCard
                    prev.copy(cards = nextCards, isRunning = true)
                }
            }

            "message_committed", "turn_completed" -> {
                val msgElem: JsonElement? = obj["message"]
                if (msgElem != null) {
                    val wireMsg = runCatching {
                        LumenBoxJson.default.decodeFromJsonElement(GeneratedConversationMessage.serializer(), msgElem)
                    }.getOrNull()
                    if (wireMsg != null) {
                        val offline = store.openOfflineStore(connectionId)
                        mergeMessagesIntoCache(offline, conversationId, listOf(wireMsg.toDomain()))
                    }
                }
                obj["durableSeq"]?.jsonPrimitive?.longOrNull?.let { seq ->
                    runCatching { store.openOfflineStore(connectionId).advanceDurableSeq(seq) }
                }
                setConversationRunningFlag(conversationId, false)
                clearLiveTurn(conversationId)
            }

            "turn_stopped", "stopped" -> {
                setConversationRunningFlag(conversationId, false)
                val existing = _liveTurns.value[conversationId]
                if (existing != null) {
                    if (existing.responseText.isEmpty() && existing.thinkingText.isEmpty() && existing.toolCalls.isEmpty()) {
                        clearLiveTurn(conversationId)
                    } else {
                        _liveTurns.value = _liveTurns.value + (conversationId to existing.copy(isRunning = false))
                    }
                }
            }
        }
    }

    private fun applyGlobalEventFrame(offline: ConnectionOfflineStore, frame: SseFrame) {
        val obj = runCatching { LumenBoxJson.default.parseToJsonElement(frame.data).jsonObject }.getOrNull()
            ?: return
        obj["durableSeq"]?.jsonPrimitive?.longOrNull?.let { offline.advanceDurableSeq(it) }

        val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: frame.event
        when (type) {
            "message_committed" -> {
                val msgElem = obj["message"] ?: return
                val wire = runCatching {
                    LumenBoxJson.default.decodeFromJsonElement(GeneratedConversationMessage.serializer(), msgElem)
                }.getOrNull() ?: return
                mergeMessagesIntoCache(offline, wire.conversationId, listOf(wire.toDomain()))
            }

            "conversation_updated" -> {
                val convElem = obj["conversation"] ?: return
                val wire = runCatching {
                    LumenBoxJson.default.decodeFromJsonElement(GeneratedConversationSummary.serializer(), convElem)
                }.getOrNull() ?: return
                val map = readCachedConversations().associateByTo(LinkedHashMap()) { it.id }
                map[wire.id] = wire.toDomain()
                offline.putJson(
                    CONVERSATIONS_TABLE,
                    CONVERSATIONS_ROW,
                    ListSerializer(ConversationSummary.serializer()),
                    map.values.sortedByDescending { it.updatedAt },
                )
            }

            "inbox_updated" -> {
                val itemsArr = obj["items"]?.jsonArray ?: return
                val items = itemsArr.map {
                    LumenBoxJson.default.decodeFromJsonElement(InboxDecisionItem.serializer(), it)
                }
                offline.putJson(INBOX_TABLE, INBOX_ITEMS_ROW, ListSerializer(InboxDecisionItem.serializer()), items)
                store.updateNeedsYouCount(connectionId, items.size)
            }

            "work_updated" -> {
                val workElem = obj["work"] ?: return
                val wire = runCatching {
                    LumenBoxJson.default.decodeFromJsonElement(GeneratedWorkSummary.serializer(), workElem)
                }.getOrNull() ?: return
                val map = readCachedWorkItems().associateByTo(LinkedHashMap()) { it.id }
                map[wire.id] = wire.toDomain()
                offline.putJson(
                    WORK_TABLE,
                    WORK_ROW,
                    ListSerializer(WorkItemSummary.serializer()),
                    map.values.sortedByDescending { it.updatedAt },
                )
            }

            "deliverable_created", "deliverable_updated" -> {
                val delivElem = obj["deliverable"] ?: return
                val wire = runCatching {
                    LumenBoxJson.default.decodeFromJsonElement(GeneratedDeliverableSummary.serializer(), delivElem)
                }.getOrNull() ?: return
                val map = readCachedDeliverables().associateByTo(LinkedHashMap()) { it.id }
                map[wire.id] = wire.toDomain()
                offline.putJson(
                    DELIVERABLES_TABLE,
                    DELIVERABLES_ROW,
                    ListSerializer(DeliverableMetadata.serializer()),
                    map.values.sortedByDescending { it.createdAt },
                )
            }
        }
    }

    // ---- 4. Idempotent Multimedia Uploads & Prompt Sending ----

    /**
     * Stages a user message and optional image/file/voice attachments into encrypted local storage
     * with deterministic `clientMessageId` and `Idempotency-Key`s, optimistically inserts the
     * pending message into the conversation timeline, and attempts immediate delivery (`docs/110 §2`).
     *
     * If offline or interrupted mid-upload/mid-POST, [flushPendingConversationsQueue] (or WorkManager)
     * resumes without re-uploading completed attachments or duplicating the conversation turn.
     */
    suspend fun sendMessageWithAttachments(
        conversationId: String,
        text: String,
        attachments: List<LocalUploadAttachment> = emptyList(),
        explicitClientMessageId: String? = null,
    ): CommittedMessage {
        val trimmedText = text.trim()
        require(trimmedText.isNotEmpty() || attachments.isNotEmpty()) {
            "消息正文与附件不能同时为空"
        }

        val offline = store.openOfflineStore(connectionId)
        val clientMessageId = explicitClientMessageId?.takeIf { it.isNotBlank() }
            ?: "cmsg_${UUID.randomUUID()}"
        val sendIdempotencyKey = UUID.randomUUID().toString()
        val nowIso = clock().toString()

        val uploadSpecs = attachments.map { att ->
            val blobKey = "${clientMessageId}_${att.localId}"
            offline.put(STAGED_BLOBS_TABLE, blobKey, att.bytes)
            StagedUploadSpec(
                localId = att.localId,
                fileName = att.fileName,
                mimeType = att.mimeType,
                kind = att.kind.wireName,
                sizeBytes = att.bytes.size.toLong(),
                uploadIdempotencyKey = UUID.randomUUID().toString(),
                uploadedAttachmentId = null,
            )
        }

        val bundle = StagedPromptBundle(
            clientMessageId = clientMessageId,
            sendIdempotencyKey = sendIdempotencyKey,
            conversationId = conversationId,
            text = trimmedText,
            createdAt = nowIso,
            uploads = uploadSpecs,
        )
        offline.putJson(STAGED_BUNDLES_TABLE, clientMessageId, StagedPromptBundle.serializer(), bundle)

        val optimisticAttachments = uploadSpecs.map {
            AttachmentSummary(
                id = "local:${it.localId}",
                name = it.fileName,
                mimeType = it.mimeType,
                kind = it.kind,
                sizeBytes = it.sizeBytes,
            )
        }
        val optimisticMessage = CommittedMessage(
            id = "pending:$clientMessageId",
            conversationId = conversationId,
            clientMessageId = clientMessageId,
            role = OpenEnum.Known(MessageRole.USER, MessageRole.USER.wireName),
            text = trimmedText,
            createdAt = nowIso,
            attachments = optimisticAttachments,
            pendingSend = true,
        )
        mergeMessagesIntoCache(offline, conversationId, listOf(optimisticMessage))

        flushPendingConversationsQueue()
        return readCachedMessages(conversationId).firstOrNull { it.clientMessageId == clientMessageId }
            ?: optimisticMessage
    }

    /**
     * Submits a structured `ShowUI` card action (`POST /api/v1/conversations/{id}/card-actions`)
     * backed by the idempotent outbound queue so transient drops replay safely.
     */
    suspend fun submitShowUiCardAction(
        conversationId: String,
        cardId: String,
        actionId: String,
        payload: Map<String, String> = emptyMap(),
    ) {
        val offline = store.openOfflineStore(connectionId)
        val clientMessageId = "card_${UUID.randomUUID()}"
        val idempotencyKey = UUID.randomUUID().toString()
        val reqModel = GeneratedCardActionRequest(
            clientMessageId = clientMessageId,
            cardId = cardId,
            actionId = actionId,
            payload = payload.takeIf { it.isNotEmpty() },
        )
        val payloadJson = LumenBoxJson.default.encodeToString(GeneratedCardActionRequest.serializer(), reqModel)
        offline.enqueueOutboundAction(
            OutboundActionDraft(
                idempotencyKey = idempotencyKey,
                clientMessageId = clientMessageId,
                kind = "conversation_card_action",
                path = "/api/v1/conversations/${urlEncodePath(conversationId)}/card-actions",
                payloadJson = payloadJson,
                enqueuedAtEpochMillis = clock().toEpochMilli(),
            )
        )
        flushPendingCardActions()
    }

    /**
     * Stops the active agent turn in [conversationId] (`POST /api/v1/conversations/{id}/stop`).
     */
    suspend fun stopConversationTurn(conversationId: String) {
        val transport = transportProvider()
            ?: throw IllegalStateException("当前处于离线状态，无法立即发送停止指令。")
        val res = transport.send(
            TransportRequest(
                method = "POST",
                path = "/api/v1/conversations/${urlEncodePath(conversationId)}/stop",
                headers = mapOf("Accept" to "application/json"),
                idempotencyKey = UUID.randomUUID().toString(),
            )
        )
        throwIfError(res.statusCode, res.body)
        advanceSeqFromBodyIfPresent(res.body)
        setConversationRunningFlag(conversationId, false)
        val currentDraft = _liveTurns.value[conversationId]
        if (currentDraft != null) {
            _liveTurns.value = _liveTurns.value + (conversationId to currentDraft.copy(isRunning = false))
        }
    }

    /**
     * Flushes all staged multimedia uploads + prompts AND queued `ShowUI` card actions in FIFO order.
     * Safe to invoke concurrently from UI coroutines and background `WorkManager` retries.
     */
    suspend fun flushPendingConversationsQueue(): Int = flushMutex.withLock {
        val transport = transportProvider() ?: return@withLock 0
        val offline = store.openOfflineStore(connectionId)
        var deliveredCount = 0

        val bundles = offline.rowKeys(STAGED_BUNDLES_TABLE)
            .mapNotNull { key -> offline.getJson(STAGED_BUNDLES_TABLE, key, StagedPromptBundle.serializer()) }
            .sortedBy { it.createdAt }

        for (initialBundle in bundles) {
            var currentBundle = initialBundle
            var uploadFailed = false

            // Step A: Upload any attachment that does not yet have an uploadedAttachmentId
            val updatedSpecs = currentBundle.uploads.toMutableList()
            for (idx in updatedSpecs.indices) {
                val spec = updatedSpecs[idx]
                if (spec.uploadedAttachmentId != null) continue
                val blobKey = "${currentBundle.clientMessageId}_${spec.localId}"
                val bytes = offline.get(STAGED_BLOBS_TABLE, blobKey)
                    ?: throw IllegalStateException("Staged attachment bytes missing for ${spec.fileName}")

                val uploadRes = try {
                    transport.send(
                        TransportRequest(
                            method = "POST",
                            path = "/api/v1/uploads",
                            headers = mapOf(
                                "Content-Type" to spec.mimeType,
                                "Accept" to "application/json",
                                "X-Upload-Filename" to encodeFilenameHeader(spec.fileName),
                                "X-Upload-Kind" to spec.kind,
                            ),
                            body = bytes,
                            idempotencyKey = spec.uploadIdempotencyKey,
                        )
                    )
                } catch (err: Throwable) {
                    if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) throw err
                    uploadFailed = true
                    break
                }

                if (uploadRes.statusCode == 401) {
                    throwIfError(uploadRes.statusCode, uploadRes.body)
                }
                if (uploadRes.statusCode !in 200..299) {
                    uploadFailed = true
                    break
                }

                val descriptor = LumenBoxJson.default.decodeFromString(
                    GeneratedUploadDescriptor.serializer(),
                    String(uploadRes.body, StandardCharsets.UTF_8),
                )
                descriptor.durableSeq?.let { offline.advanceDurableSeq(it) }
                updatedSpecs[idx] = spec.copy(uploadedAttachmentId = descriptor.attachmentId)
                currentBundle = currentBundle.copy(uploads = updatedSpecs)
                // Persist progress immediately so a drop on attachment #2 never re-uploads attachment #1!
                offline.putJson(
                    STAGED_BUNDLES_TABLE,
                    currentBundle.clientMessageId,
                    StagedPromptBundle.serializer(),
                    currentBundle,
                )
                offline.deleteRow(STAGED_BLOBS_TABLE, blobKey)
            }

            if (uploadFailed) break

            // Step B: Send the prompt referencing the uploaded attachmentIds with stable Idempotency-Key + clientMessageId
            val attachmentIds = currentBundle.uploads.mapNotNull { it.uploadedAttachmentId }
            val sendReq = GeneratedSendMessageRequest(
                clientMessageId = currentBundle.clientMessageId,
                text = currentBundle.text,
                attachmentIds = attachmentIds.takeIf { it.isNotEmpty() },
            )
            val reqBytes = LumenBoxJson.default.encodeToString(
                GeneratedSendMessageRequest.serializer(),
                sendReq,
            ).toByteArray(StandardCharsets.UTF_8)

            val msgRes = try {
                transport.send(
                    TransportRequest(
                        method = "POST",
                        path = "/api/v1/conversations/${urlEncodePath(currentBundle.conversationId)}/messages",
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "Accept" to "application/json",
                        ),
                        body = reqBytes,
                        idempotencyKey = currentBundle.sendIdempotencyKey,
                    )
                )
            } catch (err: Throwable) {
                if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) throw err
                break
            }

            if (msgRes.statusCode == 401) {
                throwIfError(msgRes.statusCode, msgRes.body)
            }
            if (msgRes.statusCode !in 200..299 && msgRes.statusCode != 409) {
                break
            }

            val ack = runCatching {
                LumenBoxJson.default.decodeFromString(
                    GeneratedSendMessageAck.serializer(),
                    String(msgRes.body, StandardCharsets.UTF_8),
                )
            }.getOrNull()
            ack?.durableSeq?.let { offline.advanceDurableSeq(it) }

            val finalizedId = ack?.messageId ?: "msg_${currentBundle.clientMessageId}"
            val finalizedAttachments = currentBundle.uploads.map { spec ->
                AttachmentSummary(
                    id = spec.uploadedAttachmentId ?: spec.localId,
                    name = spec.fileName,
                    mimeType = spec.mimeType,
                    kind = spec.kind,
                    sizeBytes = spec.sizeBytes,
                )
            }
            val committed = CommittedMessage(
                id = finalizedId,
                conversationId = currentBundle.conversationId,
                clientMessageId = currentBundle.clientMessageId,
                role = OpenEnum.Known(MessageRole.USER, MessageRole.USER.wireName),
                text = currentBundle.text,
                createdAt = currentBundle.createdAt,
                attachments = finalizedAttachments,
                pendingSend = false,
            )
            mergeMessagesIntoCache(offline, currentBundle.conversationId, listOf(committed))
            offline.deleteRow(STAGED_BUNDLES_TABLE, currentBundle.clientMessageId)
            currentBundle.uploads.forEach { spec ->
                offline.deleteRow(STAGED_BLOBS_TABLE, "${currentBundle.clientMessageId}_${spec.localId}")
            }
            deliveredCount += 1
        }

        deliveredCount += flushPendingCardActionsInternal(transport, offline)
        deliveredCount
    }

    private suspend fun flushPendingCardActions(): Int = flushMutex.withLock {
        val transport = transportProvider() ?: return@withLock 0
        val offline = store.openOfflineStore(connectionId)
        flushPendingCardActionsInternal(transport, offline)
    }

    private suspend fun flushPendingCardActionsInternal(
        transport: ApiTransport,
        offline: ConnectionOfflineStore,
    ): Int {
        var sent = 0
        val cardActions = offline.pendingOutboundActions()
            .filter { it.kind == "conversation_card_action" }
        for (pending in cardActions) {
            val res = try {
                transport.send(
                    TransportRequest(
                        method = "POST",
                        path = pending.path,
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "Accept" to "application/json",
                        ),
                        body = pending.payloadJson.toByteArray(StandardCharsets.UTF_8),
                        idempotencyKey = pending.idempotencyKey,
                    )
                )
            } catch (err: Throwable) {
                if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) throw err
                break
            }
            if (res.statusCode == 401) {
                throwIfError(res.statusCode, res.body)
            }
            if (res.statusCode in 200..299 || res.statusCode == 409) {
                offline.acknowledgeOutboundAction(pending.clientMessageId)
                advanceSeqFromBodyIfPresent(res.body)
                sent += 1
            } else {
                break
            }
        }
        return sent
    }

    // ---- 5. Work, Board & Short-Lived Deliverable Preview ----

    suspend fun refreshWorkItems(
        stateFilter: String? = null,
        agentId: String? = null,
        waitsOnMe: Boolean? = null,
        limit: Int = 50,
    ): List<WorkItemSummary> {
        val transport = transportProvider() ?: return readCachedWorkItems()
        val query = buildList {
            add("limit=${limit.coerceIn(1, 100)}")
            if (!stateFilter.isNullOrBlank()) add("state=${urlEncode(stateFilter)}")
            if (!agentId.isNullOrBlank()) add("agentId=${urlEncode(agentId)}")
            if (waitsOnMe != null) add("waitsOnMe=$waitsOnMe")
        }.joinToString("&")
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/work?$query",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        throwIfError(res.statusCode, res.body)

        val page = LumenBoxJson.default.decodeFromString(
            GeneratedWorkPage.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val items = page.items.map { it.toDomain() }
        if (stateFilter == null && agentId == null && waitsOnMe == null) {
            store.openOfflineStore(connectionId).putJson(
                WORK_TABLE,
                WORK_ROW,
                ListSerializer(WorkItemSummary.serializer()),
                items,
            )
        }
        return items
    }

    suspend fun refreshBoard(): BoardSnapshot {
        val transport = transportProvider() ?: return readCachedBoard()
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/board",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        throwIfError(res.statusCode, res.body)

        val wire = LumenBoxJson.default.decodeFromString(
            GeneratedBoardProjection.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val snapshot = wire.toDomain()
        val offline = store.openOfflineStore(connectionId)
        offline.putJson(BOARD_TABLE, BOARD_ROW, BoardSnapshot.serializer(), snapshot)
        return snapshot
    }

    /**
     * Resolves a short-lived signed preview/download link from `GET /api/v1/deliverables/{id}`
     * (`docs/106 §4.2`). Reuses an in-memory ticket only while its `expiresAt` remains safely
     * in the future; automatically mints a fresh short-lived URL once expired.
     */
    suspend fun resolveDeliverablePreview(
        deliverableId: String,
        forceRefresh: Boolean = false,
    ): DeliverablePreviewTicket {
        val now = clock()
        if (!forceRefresh) {
            previewCache[deliverableId]?.let { cached ->
                val exp = runCatching { Instant.parse(cached.expiresAt) }.getOrNull()
                if (exp != null && now.plusSeconds(linkExpirySafetySkewSeconds).isBefore(exp)) {
                    return cached
                }
            }
        }
        val transport = transportProvider()
            ?: throw IllegalStateException("当前离线：交付物预览使用短时签名链接，请恢复连接后获取最新链接。")
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/deliverables/${urlEncodePath(deliverableId)}",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        throwIfError(res.statusCode, res.body)

        val wire = LumenBoxJson.default.decodeFromString(
            GeneratedDeliverableLink.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val ticket = DeliverablePreviewTicket(
            id = wire.id,
            title = wire.title,
            mimeType = wire.mimeType,
            sizeBytes = wire.sizeBytes,
            url = wire.url,
            expiresAt = wire.expiresAt,
            inlinePreviewText = wire.inlinePreviewText,
        )
        previewCache[deliverableId] = ticket
        return ticket
    }

    // ---- Internal Helpers & Mappers ----

    private fun mergeMessagesIntoCache(
        offline: ConnectionOfflineStore,
        conversationId: String,
        incoming: List<CommittedMessage>,
    ): List<CommittedMessage> {
        val current = readCachedMessages(conversationId).toMutableList()
        for (msg in incoming) {
            val matchIdx = current.indexOfFirst { existing ->
                existing.id == msg.id ||
                    (!msg.clientMessageId.isNullOrBlank() && existing.clientMessageId == msg.clientMessageId)
            }
            if (matchIdx >= 0) {
                val prev = current[matchIdx]
                // Never let an optimistic pendingSend overwrite an already server-committed message!
                if (!prev.pendingSend && msg.pendingSend) continue
                current[matchIdx] = msg
            } else {
                current.add(msg)
            }
        }
        val ordered = current.sortedWith(
            compareBy<CommittedMessage> { it.createdAt }.thenBy { it.id }
        )
        offline.putJson(
            MESSAGES_TABLE,
            conversationId,
            ListSerializer(CommittedMessage.serializer()),
            ordered,
        )
        return ordered
    }

    private fun updateLiveTurn(
        conversationId: String,
        turnId: String,
        transform: (LiveTurnDraft) -> LiveTurnDraft,
    ) {
        val prev = _liveTurns.value[conversationId]?.takeIf { it.turnId == turnId }
            ?: LiveTurnDraft(conversationId = conversationId, turnId = turnId)
        _liveTurns.value = _liveTurns.value + (conversationId to transform(prev))
    }

    private fun clearLiveTurn(conversationId: String) {
        if (conversationId in _liveTurns.value) {
            _liveTurns.value = _liveTurns.value - conversationId
        }
    }

    private fun setConversationRunningFlag(conversationId: String, running: Boolean) {
        val offline = openStoreOrNull() ?: return
        val existing = readCachedConversations()
        val idx = existing.indexOfFirst { it.id == conversationId }
        if (idx < 0 || existing[idx].running == running) return
        val next = existing.toMutableList()
        next[idx] = next[idx].copy(running = running)
        offline.putJson(CONVERSATIONS_TABLE, CONVERSATIONS_ROW, ListSerializer(ConversationSummary.serializer()), next)
    }

    private fun advanceSeqFromBodyIfPresent(body: ByteArray) {
        if (body.isEmpty()) return
        val seq = runCatching {
            LumenBoxJson.default.parseToJsonElement(String(body, StandardCharsets.UTF_8))
                .jsonObject["durableSeq"]?.jsonPrimitive?.longOrNull
        }.getOrNull() ?: return
        runCatching { store.openOfflineStore(connectionId).advanceDurableSeq(seq) }
    }

    private fun openStoreOrNull(): ConnectionOfflineStore? =
        runCatching { store.openOfflineStore(connectionId) }.getOrNull()

    private fun throwIfError(statusCode: Int, body: ByteArray) {
        if (statusCode in 200..299) return
        val env = runCatching {
            LumenBoxJson.default.decodeFromString(
                ApiErrorEnvelope.serializer(),
                String(body, StandardCharsets.UTF_8),
            )
        }.getOrNull()
        if (env != null) throw ApiEnvelopeException(statusCode, env)
        throw IllegalStateException("API call failed with HTTP $statusCode")
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    private fun urlEncodePath(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun encodeFilenameHeader(fileName: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(fileName.toByteArray(StandardCharsets.UTF_8))

    private fun GeneratedConversationSummary.toDomain(): ConversationSummary = ConversationSummary(
        id = id,
        title = title,
        agentId = agentId,
        agentName = agentName,
        updatedAt = updatedAt,
        running = running ?: false,
        lastMessagePreview = lastMessagePreview,
    )

    private fun GeneratedConversationMessage.toDomain(): CommittedMessage = CommittedMessage(
        id = id,
        conversationId = conversationId,
        clientMessageId = clientMessageId,
        role = MessageRole.fromWire(role),
        text = text,
        thinking = thinking,
        createdAt = createdAt,
        attachments = attachments.orEmpty().map { att ->
            AttachmentSummary(
                id = att.id,
                name = att.name,
                mimeType = att.mimeType,
                kind = att.kind ?: AttachmentMediaKind.infer(null, att.mimeType).wireName,
                sizeBytes = att.sizeBytes,
            )
        },
        toolCalls = toolCalls.orEmpty().map { tc ->
            ToolTimelineEntry(
                id = tc.id,
                name = tc.name,
                status = ToolRunStatus.fromWire(tc.status),
                summary = tc.summary,
            )
        },
        cards = cards.orEmpty().map { card ->
            ShowUiCard(
                cardId = card.cardId,
                title = card.title,
                body = card.body,
                actions = card.actions.orEmpty().map { act ->
                    ShowUiCardAction(actionId = act.actionId, label = act.label, style = act.style)
                },
            )
        },
        deliverableIds = deliverableIds.orEmpty(),
        pendingSend = false,
    )

    private fun GeneratedWorkSummary.toDomain(): WorkItemSummary = WorkItemSummary(
        id = id,
        title = title,
        state = WorkStateKind.fromWire(state),
        agentId = agentId,
        agentName = agentName,
        conversationId = conversationId,
        waitsOnMe = waitsOnMe ?: false,
        summary = summary,
        deliverableIds = deliverableIds.orEmpty(),
        updatedAt = updatedAt,
    )

    private fun GeneratedBoardColumn.toDomain(): BoardColumnSnapshot = BoardColumnSnapshot(
        id = id,
        title = title,
        cards = cards.map { it.toDomain() },
    )

    private fun GeneratedBoardProjection.toDomain(): BoardSnapshot = BoardSnapshot(
        columns = columns.map { it.toDomain() },
        updatedAt = updatedAt,
    )

    private fun GeneratedDeliverableSummary.toDomain(): DeliverableMetadata = DeliverableMetadata(
        id = id,
        title = title,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        workId = workId,
        conversationId = conversationId,
        createdAt = createdAt,
    )
}

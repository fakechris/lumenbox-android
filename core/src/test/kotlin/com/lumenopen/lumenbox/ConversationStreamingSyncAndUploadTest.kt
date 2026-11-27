package com.lumenopen.lumenbox

import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.conversation.AttachmentMediaKind
import com.lumenopen.lumenbox.conversation.ConversationAndBoardCoordinator
import com.lumenopen.lumenbox.conversation.LiveSseConnector
import com.lumenopen.lumenbox.conversation.LocalUploadAttachment
import com.lumenopen.lumenbox.conversation.MarkdownBlock
import com.lumenopen.lumenbox.conversation.MarkdownBlockParser
import com.lumenopen.lumenbox.conversation.ToolRunStatus
import com.lumenopen.lumenbox.conversation.WorkStateKind
import com.lumenopen.lumenbox.generated.models.GeneratedCardActionRequest
import com.lumenopen.lumenbox.generated.models.GeneratedSendMessageRequest
import com.lumenopen.lumenbox.inbox.NeedsYouInboxCoordinator
import com.lumenopen.lumenbox.relay.DuplexRelayStream
import com.lumenopen.lumenbox.relay.HostMuxHandlers
import com.lumenopen.lumenbox.relay.NoiseCipherState
import com.lumenopen.lumenbox.relay.NoiseTransportSession
import com.lumenopen.lumenbox.relay.RelayMux
import com.lumenopen.lumenbox.relay.StreamTarget
import com.lumenopen.lumenbox.relay.VirtualHttpRequest
import com.lumenopen.lumenbox.relay.VirtualHttpResponse
import com.lumenopen.lumenbox.relay.VirtualStreamRequest
import com.lumenopen.lumenbox.storage.InMemoryKeyStoreVault
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.EncryptedRelayTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant

class ConversationStreamingSyncAndUploadTest {

    @Test
    fun `streaming conversation over Noise_IK RelayMux merges thinking, text, tool timeline, ShowUI card, and commits cleanly without duplicate turns`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2257-stream").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "personal-mac",
                    connId = "c_mac_1",
                    kind = InstallationConnection.Kind.PERSONAL_MAC,
                    displayName = "Personal Mac",
                    serverOriginOrRelayUrl = "wss://relay.lumenbox.example/ws",
                )
            )

            val keyClientToHost = ByteArray(32) { (it + 1).toByte() }
            val keyHostToClient = ByteArray(32) { (it + 65).toByte() }
            val phoneSession = NoiseTransportSession(
                send = NoiseCipherState(keyClientToHost),
                receive = NoiseCipherState(keyHostToClient),
            )
            val hostSession = NoiseTransportSession(
                send = NoiseCipherState(keyHostToClient),
                receive = NoiseCipherState(keyClientToHost),
            )

            val stopRequests = mutableListOf<String>()
            val cardActionRequests = mutableListOf<GeneratedCardActionRequest>()

            lateinit var clientMux: RelayMux
            lateinit var hostMux: RelayMux

            val hostHandlers = object : HostMuxHandlers {
                override suspend fun onHttpRequest(req: VirtualHttpRequest, deviceId: String?): VirtualHttpResponse {
                    return when {
                        req.method == "POST" && req.path == "/api/v1/conversations/conv_alpha/stop" -> {
                            stopRequests.add(req.path)
                            VirtualHttpResponse(
                                status = 200,
                                headers = mapOf("content-type" to "application/json"),
                                body = """{"ok":true,"durableSeq":42}""".toByteArray(StandardCharsets.UTF_8),
                            )
                        }
                        req.method == "POST" && req.path == "/api/v1/conversations/conv_alpha/card-actions" -> {
                            val parsed = LumenBoxJson.default.decodeFromString(
                                GeneratedCardActionRequest.serializer(),
                                String(req.body ?: ByteArray(0), StandardCharsets.UTF_8),
                            )
                            cardActionRequests.add(parsed)
                            VirtualHttpResponse(
                                status = 200,
                                headers = mapOf("content-type" to "application/json"),
                                body = """{"ok":true,"durableSeq":43}""".toByteArray(StandardCharsets.UTF_8),
                            )
                        }
                        else -> VirtualHttpResponse(404)
                    }
                }

                override suspend fun onOpenStream(
                    req: VirtualStreamRequest,
                    stream: DuplexRelayStream,
                    deviceId: String?,
                ) {
                    assertEquals(StreamTarget.CONVERSATION_STREAM, req.target)
                    assertEquals("/api/v1/conversations/conv_alpha/stream", req.path)
                    // Intentionally split UTF-8 SSE chunks mid-line to verify incremental SseFrameParser!
                    val rawSse = buildString {
                        append(": keep-alive\n\n")
                        append("event: thinking_delta\ndata: {\"type\":\"thinking_delta\",\"turnId\":\"t_1\",\"delta\":\"Inspecting \"}\n\n")
                        append("event: thinking_delta\ndata: {\"type\":\"thinking_delta\",\"turnId\":\"t_1\",\"delta\":\"logs...\"}\r\n\r\n")
                        append("data: {\"type\":\"tool_progress\",\"turnId\":\"t_1\",\"toolCallId\":\"tc_1\",\"name\":\"rg\",\"status\":\"running\",\"summary\":\"Searching error codes\"}\n\n")
                        append("data: {\"type\":\"tool_progress\",\"turnId\":\"t_1\",\"toolCallId\":\"tc_1\",\"name\":\"rg\",\"status\":\"completed\",\"summary\":\"3 matches\"}\n\n")
                        append("data: {\"type\":\"text_delta\",\"turnId\":\"t_1\",\"delta\":\"## Diagnosis\\n\"}\n\n")
                        append("data: {\"type\":\"text_delta\",\"turnId\":\"t_1\",\"delta\":\"```kotlin\\nval ok = true\\n```\"}\n\n")
                        append("data: {\"type\":\"show_ui\",\"turnId\":\"t_1\",\"card\":{\"cardId\":\"card_deploy\",\"title\":\"Deploy patch?\",\"actions\":[{\"actionId\":\"ship_canary\",\"label\":\"Ship Canary\"}]}}\n\n")
                        append(
                            "data: {\"type\":\"message_committed\",\"turnId\":\"t_1\",\"durableSeq\":41,\"message\":{" +
                                "\"id\":\"msg_assistant_1\",\"conversationId\":\"conv_alpha\",\"role\":\"assistant\"," +
                                "\"text\":\"## Diagnosis\\n```kotlin\\nval ok = true\\n```\",\"thinking\":\"Inspecting logs...\"," +
                                "\"createdAt\":\"2026-11-27T21:00:05Z\"," +
                                "\"toolCalls\":[{\"id\":\"tc_1\",\"name\":\"rg\",\"status\":\"completed\",\"summary\":\"3 matches\"}]," +
                                "\"cards\":[{\"cardId\":\"card_deploy\",\"title\":\"Deploy patch?\",\"actions\":[{\"actionId\":\"ship_canary\",\"label\":\"Ship Canary\"}]}]," +
                                "\"deliverableIds\":[\"deliv_report_1\"]}}\n\n"
                        )
                    }
                    val bytes = rawSse.toByteArray(StandardCharsets.UTF_8)
                    var off = 0
                    while (off < bytes.size) {
                        val end = minOf(off + 37, bytes.size)
                        stream.send(bytes.copyOfRange(off, end), binary = false)
                        off = end
                    }
                    stream.close()
                }
            }

            clientMux = RelayMux(
                role = RelayMux.Role.CLIENT,
                session = phoneSession,
                sendWire = { wire -> hostMux.receiveWire(wire) },
            )
            hostMux = RelayMux(
                role = RelayMux.Role.HOST,
                session = hostSession,
                sendWire = { wire -> clientMux.receiveWire(wire) },
                handlers = hostHandlers,
                deviceId = "dev_1",
            )

            val coordinator = ConversationAndBoardCoordinator(
                connectionId = "personal-mac",
                store = store,
                transportProvider = { EncryptedRelayTransport.overMux(clientMux) },
                sseConnectorProvider = { LiveSseConnector.overRelayMux { clientMux } },
            )

            val observedDraftSnapshots = mutableListOf<String>()
            val job = launch {
                coordinator.collectConversationStream("conv_alpha") { draft ->
                    if (draft != null) {
                        observedDraftSnapshots.add("${draft.thinkingText}|${draft.toolCalls.size}|${draft.responseText}")
                    }
                }
            }
            job.join()

            assertTrue(observedDraftSnapshots.any { it.startsWith("Inspecting logs...|1|## Diagnosis") })
            // Once message_committed arrived, live draft was cleared and committed message stored exactly once:
            assertNull(coordinator.liveTurns.value["conv_alpha"])
            val cachedMsgs = coordinator.readCachedMessages("conv_alpha")
            assertEquals(1, cachedMsgs.size)
            val msg = cachedMsgs.single()
            assertEquals("msg_assistant_1", msg.id)
            assertEquals("Inspecting logs...", msg.thinking)
            assertEquals(1, msg.toolCalls.size)
            assertEquals(OpenEnum.Known(ToolRunStatus.COMPLETED, "completed"), msg.toolCalls.single().status)
            assertEquals(listOf("deliv_report_1"), msg.deliverableIds)
            assertEquals(41L, coordinator.currentDurableSeq())

            // Verify Markdown block splitting on the committed code-fence message
            val mdBlocks = MarkdownBlockParser.parse(msg.text)
            assertEquals(
                listOf(
                    MarkdownBlock.Heading(2, "Diagnosis"),
                    MarkdownBlock.CodeFence("kotlin", "val ok = true"),
                ),
                mdBlocks,
            )

            // Verify ShowUI card action & POST /stop over the same multiplexed transport
            coordinator.submitShowUiCardAction("conv_alpha", "card_deploy", "ship_canary")
            assertEquals(1, cardActionRequests.size)
            assertEquals("card_deploy", cardActionRequests.single().cardId)
            assertEquals("ship_canary", cardActionRequests.single().actionId)

            coordinator.stopConversationTurn("conv_alpha")
            assertEquals(listOf("/api/v1/conversations/conv_alpha/stop"), stopRequests)
            assertEquals(43L, coordinator.currentDurableSeq())

            clientMux.close(authenticated = true)
            hostMux.close(authenticated = true)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `multi-page changes catch-up after wake advances durableSeq monotonically and updates conversations, messages, inbox, work, and deliverables`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2257-changes").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "org-main",
                    connId = "c_org_1",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Acme Engineering",
                    serverOriginOrRelayUrl = "https://acme.lumenbox.example",
                )
            )
            store.openOfflineStore("org-main").advanceDurableSeq(100L)

            val seenPaths = mutableListOf<String>()
            val transport = ApiTransport { req ->
                seenPaths.add("${req.method} ${req.path}")
                when (req.path) {
                    "/api/v1/changes?since=100" -> TransportResponse(
                        statusCode = 200,
                        body = """
                            {
                              "durableSeq": 105,
                              "hasMore": true,
                              "conversations": [
                                {"id":"conv_1","title":"Release 0.5","agentId":"ag_1","agentName":"Builder","updatedAt":"2026-11-27T20:00:00Z","running":true}
                              ],
                              "messages": [
                                {"id":"m_1","conversationId":"conv_1","role":"user","text":"Ship release","createdAt":"2026-11-27T20:00:01Z"}
                              ],
                              "inbox": [
                                {"id":"inb_1","kind":"question","summary":"Target staging first?","options":["Yes","No"]}
                              ]
                            }
                        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                    )
                    "/api/v1/changes?since=105" -> TransportResponse(
                        statusCode = 200,
                        body = """
                            {
                              "durableSeq": 112,
                              "hasMore": false,
                              "conversations": [
                                {"id":"conv_1","title":"Release 0.5","agentId":"ag_1","agentName":"Builder","updatedAt":"2026-11-27T20:02:00Z","running":false,"lastMessagePreview":"Staging green"}
                              ],
                              "messages": [
                                {"id":"m_2","conversationId":"conv_1","role":"assistant","text":"Staging green","createdAt":"2026-11-27T20:02:00Z"}
                              ],
                              "work": [
                                {"id":"wrk_1","title":"Deploy staging","state":"future_custom_stage","waitsOnMe":true,"deliverableIds":["deliv_1"],"updatedAt":"2026-11-27T20:02:00Z"}
                              ],
                              "deliverables": [
                                {"id":"deliv_1","title":"staging-smoke.pdf","mimeType":"application/pdf","sizeBytes":24576,"createdAt":"2026-11-27T20:01:55Z"}
                              ]
                            }
                        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                    )
                    else -> TransportResponse(404)
                }
            }

            val coordinator = ConversationAndBoardCoordinator(
                connectionId = "org-main",
                store = store,
                transportProvider = { transport },
            )
            val inboxCoordinator = NeedsYouInboxCoordinator(
                connectionId = "org-main",
                store = store,
                transportProvider = { transport },
            )

            val finalSeq = coordinator.syncDurableChanges()
            assertEquals(112L, finalSeq)
            assertEquals(
                listOf("GET /api/v1/changes?since=100", "GET /api/v1/changes?since=105"),
                seenPaths,
            )

            val convs = coordinator.readCachedConversations()
            assertEquals(1, convs.size)
            assertFalse(convs.single().running)
            assertEquals("Staging green", convs.single().lastMessagePreview)

            val msgs = coordinator.readCachedMessages("conv_1")
            assertEquals(listOf("m_1", "m_2"), msgs.map { it.id })

            // Top-level Needs you count and encrypted inbox snapshot updated from /changes
            assertEquals(1, store.topLevelNeedsYouProjection().single().needsYouCount)
            assertEquals(1, inboxCoordinator.readCachedItems().size)

            // Forward-compatible unknown work state decoded as OpenEnum.Other without failing
            val work = coordinator.readCachedWorkItems().single()
            assertEquals(OpenEnum.Other("future_custom_stage"), work.state)
            assertTrue(work.waitsOnMe)

            val deliverables = coordinator.readCachedDeliverables()
            assertEquals("staging-smoke.pdf", deliverables.single().title)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `idempotent multimedia uploads and clientMessageId prompt queue survive mid-flight drops without re-uploading completed attachments or duplicating turns`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2257-uploads").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "org-upload",
                    connId = "c_up_1",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Upload Org",
                    serverOriginOrRelayUrl = "https://up.lumenbox.example",
                )
            )

            val uploadCalls = mutableListOf<TransportRequest>()
            val sendMessageCalls = mutableListOf<TransportRequest>()
            var attemptPhase = 1
            // Phase 1: upload #1 (image) succeeds, upload #2 (voice) fails with 503
            // Phase 2: upload #1 is NOT re-sent; upload #2 succeeds, message POST fails with network exception
            // Phase 3: message POST retries with exact same Idempotency-Key + clientMessageId and succeeds!

            val transport = ApiTransport { req ->
                when {
                    req.method == "POST" && req.path == "/api/v1/uploads" -> {
                        uploadCalls.add(req)
                        val kind = req.headers["X-Upload-Kind"]
                        if (kind == "image") {
                            TransportResponse(
                                statusCode = 200,
                                body = """{"attachmentId":"att_srv_img_1","name":"screen.png","mimeType":"image/png","sizeBytes":4,"durableSeq":10}"""
                                    .toByteArray(StandardCharsets.UTF_8),
                            )
                        } else if (attemptPhase == 1) {
                            TransportResponse(statusCode = 503)
                        } else {
                            TransportResponse(
                                statusCode = 200,
                                body = """{"attachmentId":"att_srv_voice_2","name":"note.m4a","mimeType":"audio/mp4","sizeBytes":5,"durableSeq":11}"""
                                    .toByteArray(StandardCharsets.UTF_8),
                            )
                        }
                    }

                    req.method == "POST" && req.path == "/api/v1/conversations/conv_media/messages" -> {
                        sendMessageCalls.add(req)
                        if (attemptPhase == 2) {
                            throw java.io.IOException("Subway tunnel handover dropped response")
                        }
                        val decoded = LumenBoxJson.default.decodeFromString(
                            GeneratedSendMessageRequest.serializer(),
                            String(req.body!!, StandardCharsets.UTF_8),
                        )
                        TransportResponse(
                            statusCode = 200,
                            body = """{"ok":true,"messageId":"srv_msg_99","clientMessageId":"${decoded.clientMessageId}","durableSeq":12}"""
                                .toByteArray(StandardCharsets.UTF_8),
                        )
                    }

                    else -> TransportResponse(404)
                }
            }

            val coordinator = ConversationAndBoardCoordinator(
                connectionId = "org-upload",
                store = store,
                transportProvider = { transport },
                clock = { Instant.parse("2026-11-27T21:10:00Z") },
            )

            val imageAtt = LocalUploadAttachment(
                localId = "loc_img",
                fileName = "screen.png",
                mimeType = "image/png",
                kind = AttachmentMediaKind.IMAGE,
                bytes = byteArrayOf(1, 2, 3, 4),
            )
            val voiceAtt = LocalUploadAttachment(
                localId = "loc_voice",
                fileName = "note.m4a",
                mimeType = "audio/mp4",
                kind = AttachmentMediaKind.VOICE,
                bytes = byteArrayOf(5, 6, 7, 8, 9),
            )

            // Phase 1: initial send fails on second upload; optimistic message is visible with pendingSend=true
            val firstMsg = coordinator.sendMessageWithAttachments(
                conversationId = "conv_media",
                text = "Please inspect screenshot and voice note",
                attachments = listOf(imageAtt, voiceAtt),
                explicitClientMessageId = "cmsg_fixed_1",
            )
            assertTrue(firstMsg.pendingSend)
            assertEquals(1, coordinator.pendingPromptCount())
            assertEquals(2, uploadCalls.size) // image (200) + voice (503)
            assertEquals(0, sendMessageCalls.size)
            val voiceFirstIdemKey = uploadCalls[1].idempotencyKey

            // Phase 2: retry uploads ONLY the remaining voice attachment (with same Idempotency-Key!), then drops on message POST
            attemptPhase = 2
            val deliveredInPhase2 = coordinator.flushPendingConversationsQueue()
            assertEquals(0, deliveredInPhase2)
            assertEquals(3, uploadCalls.size) // only voice retried!
            assertEquals("voice", uploadCalls[2].headers["X-Upload-Kind"])
            assertEquals(voiceFirstIdemKey, uploadCalls[2].idempotencyKey)
            assertEquals(1, sendMessageCalls.size)
            val msgFirstIdemKey = sendMessageCalls[0].idempotencyKey

            // Phase 3: WorkManager / foreground resume flushes again; zero uploads sent, message POST uses exact same Idempotency-Key & clientMessageId!
            attemptPhase = 3
            val deliveredInPhase3 = coordinator.flushPendingConversationsQueue()
            assertEquals(1, deliveredInPhase3)
            assertEquals(0, coordinator.pendingPromptCount())
            assertEquals(3, uploadCalls.size) // still 3!
            assertEquals(2, sendMessageCalls.size)
            assertEquals(msgFirstIdemKey, sendMessageCalls[1].idempotencyKey)

            val sentPayload = LumenBoxJson.default.decodeFromString(
                GeneratedSendMessageRequest.serializer(),
                String(sendMessageCalls[1].body!!, StandardCharsets.UTF_8),
            )
            assertEquals("cmsg_fixed_1", sentPayload.clientMessageId)
            assertEquals(listOf("att_srv_img_1", "att_srv_voice_2"), sentPayload.attachmentIds)

            // Timeline deduplicated optimistic pending message into the committed server message
            val timeline = coordinator.readCachedMessages("conv_media")
            assertEquals(1, timeline.size)
            assertFalse(timeline.single().pendingSend)
            assertEquals("srv_msg_99", timeline.single().id)
            assertEquals(12L, coordinator.currentDurableSeq())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `board projection and short-lived deliverable preview link auto-refresh on expiry`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2257-deliverable").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "org-board",
                    connId = "c_brd_1",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Board Org",
                    serverOriginOrRelayUrl = "https://board.lumenbox.example",
                )
            )

            var now = Instant.parse("2026-11-27T21:00:00Z")
            var ticketCounter = 0

            val transport = ApiTransport { req ->
                when (req.path) {
                    "/api/v1/board" -> TransportResponse(
                        statusCode = 200,
                        body = """
                            {
                              "columns": [
                                {
                                  "id": "col_review",
                                  "title": "In Review",
                                  "cards": [
                                    {"id":"wrk_9","title":"Audit report","state":"in_review","waitsOnMe":true,"deliverableIds":["dlv_42"],"updatedAt":"2026-11-27T20:59:00Z"}
                                  ]
                                }
                              ],
                              "updatedAt": "2026-11-27T21:00:00Z"
                            }
                        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                    )
                    "/api/v1/deliverables/dlv_42" -> {
                        ticketCounter += 1
                        val expiresAt = now.plusSeconds(120).toString()
                        TransportResponse(
                            statusCode = 200,
                            body = """
                                {
                                  "id": "dlv_42",
                                  "title": "audit-summary.md",
                                  "mimeType": "text/markdown",
                                  "sizeBytes": 1024,
                                  "url": "https://board.lumenbox.example/dl/dlv_42?sig=tok_$ticketCounter",
                                  "expiresAt": "$expiresAt",
                                  "inlinePreviewText": "# Summary\n- All checks green"
                                }
                            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                    else -> TransportResponse(404)
                }
            }

            val coordinator = ConversationAndBoardCoordinator(
                connectionId = "org-board",
                store = store,
                transportProvider = { transport },
                clock = { now },
            )

            val board = coordinator.refreshBoard()
            assertEquals(1, board.columns.size)
            assertEquals(
                OpenEnum.Known(WorkStateKind.IN_REVIEW, "in_review"),
                board.columns.single().cards.single().state,
            )

            val firstTicket = coordinator.resolveDeliverablePreview("dlv_42")
            assertEquals("https://board.lumenbox.example/dl/dlv_42?sig=tok_1", firstTicket.url)
            assertEquals(1, ticketCounter)

            // Within validity window (30s later): reuses cached short-lived ticket without network call
            now = now.plusSeconds(30)
            val cachedTicket = coordinator.resolveDeliverablePreview("dlv_42")
            assertEquals(firstTicket.url, cachedTicket.url)
            assertEquals(1, ticketCounter)

            // Past expiry (110s later, within 15s safety skew of 120s TTL): automatically mints fresh link!
            now = now.plusSeconds(80)
            val refreshedTicket = coordinator.resolveDeliverablePreview("dlv_42")
            assertEquals("https://board.lumenbox.example/dl/dlv_42?sig=tok_2", refreshedTicket.url)
            assertEquals(2, ticketCounter)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `global events SSE stream resumes with Last-Event-ID and updates cached inbox, work, and deliverables`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2257-events").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "org-events",
                    connId = "c_ev_1",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Events Org",
                    serverOriginOrRelayUrl = "https://events.lumenbox.example",
                )
            )

            val seenLastEventIds = mutableListOf<String?>()
            var streamRound = 0
            val sseConnector = LiveSseConnector { target, path, lastEventId ->
                assertEquals(StreamTarget.EVENTS, target)
                assertEquals("/api/v1/events", path)
                seenLastEventIds.add(lastEventId)
                streamRound += 1
                kotlinx.coroutines.flow.flow {
                    if (streamRound == 1) {
                        emit(
                            com.lumenopen.lumenbox.conversation.SseFrame(
                                id = "evt_500",
                                event = "inbox_updated",
                                data = """{"type":"inbox_updated","durableSeq":500,"items":[{"id":"inb_live_1","kind":"tool_approval","summary":"Run migration","toolName":"sql","stepUpRequired":true}]}""",
                            )
                        )
                    } else {
                        emit(
                            com.lumenopen.lumenbox.conversation.SseFrame(
                                id = "evt_501",
                                event = "deliverable_created",
                                data = """{"type":"deliverable_created","durableSeq":501,"deliverable":{"id":"deliv_live_9","title":"migration.sql","mimeType":"text/plain","createdAt":"2026-11-27T21:20:00Z"}}""",
                            )
                        )
                    }
                }
            }

            val coordinator = ConversationAndBoardCoordinator(
                connectionId = "org-events",
                store = store,
                transportProvider = { null },
                sseConnectorProvider = { sseConnector },
            )

            coordinator.collectGlobalEventsStream()
            assertEquals(listOf<String?>(null), seenLastEventIds)
            assertEquals(500L, coordinator.currentDurableSeq())
            assertEquals(1, store.topLevelNeedsYouProjection().single().needsYouCount)

            // Reconnects sending Last-Event-ID = evt_500
            coordinator.collectGlobalEventsStream()
            assertEquals(listOf<String?>(null, "evt_500"), seenLastEventIds)
            assertEquals(501L, coordinator.currentDurableSeq())
            assertEquals("migration.sql", coordinator.readCachedDeliverables().single().title)
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

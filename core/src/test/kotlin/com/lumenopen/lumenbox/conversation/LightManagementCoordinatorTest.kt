package com.lumenopen.lumenbox.conversation

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.desktop.AgentDesktopSummary
import com.lumenopen.lumenbox.generated.models.GeneratedCreateConversationRequest
import com.lumenopen.lumenbox.generated.models.GeneratedPatchRoutineRequest
import com.lumenopen.lumenbox.storage.InMemoryKeyStoreVault
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant

class LightManagementCoordinatorTest {

    private fun newTestStore(tmpDir: File): InstallationStore {
        val vault = InMemoryKeyStoreVault()
        val store = InstallationStore(vault, File(tmpDir, "store"))
        store.addConnection(
            InstallationConnection(
                id = "conn_alpha",
                connId = "c_alpha",
                kind = InstallationConnection.Kind.ORGANISATION,
                displayName = "Alpha Org",
                serverOriginOrRelayUrl = "https://alpha.lumenbox.example",
            )
        )
        return store
    }

    @Test
    fun `ComposerLexicalTriggerParser detects leading slash skill and boundary @Agent mentions without false-triggering on URLs or emails`() {
        // 1. Slash command at start of input
        val slashRoot = ComposerLexicalTriggerParser.detect("/")
        assertNotNull(slashRoot)
        assertEquals(ComposerLexicalTriggerKind.SLASH_SKILL, slashRoot!!.kind)
        assertEquals("", slashRoot.query)

        val slashQuery = ComposerLexicalTriggerParser.detect("  /rev")
        assertNotNull(slashQuery)
        assertEquals(ComposerLexicalTriggerKind.SLASH_SKILL, slashQuery!!.kind)
        assertEquals("rev", slashQuery.query)

        // URLs and file paths must NOT trigger slash skill drawer
        assertNull(ComposerLexicalTriggerParser.detect("请看 https://lumenbox.example/api/v1"))
        assertNull(ComposerLexicalTriggerParser.detect("检查 src/host/main.ts"))
        // Once space is typed after /skill, slash trigger closes unless @mention starts
        assertNull(ComposerLexicalTriggerParser.detect("/review 这段代码"))

        // 2. @Agent mention at start, after whitespace, or after Chinese/ASCII punctuation
        val mentionStart = ComposerLexicalTriggerParser.detect("@cod")
        assertNotNull(mentionStart)
        assertEquals(ComposerLexicalTriggerKind.AGENT_MENTION, mentionStart!!.kind)
        assertEquals("cod", mentionStart.query)

        val mentionMid = ComposerLexicalTriggerParser.detect("请先分析日志，@sec", cursorIndex = 11)
        assertNotNull(mentionMid)
        assertEquals(ComposerLexicalTriggerKind.AGENT_MENTION, mentionMid!!.kind)
        assertEquals("sec", mentionMid.query)

        // Email address must NOT trigger @Agent mention
        assertNull(ComposerLexicalTriggerParser.detect("联系 ops@lumenbox.example 处理"))

        // 3. Apply replacements cleanly preserving surrounding text
        val textWithSuffix = "请 @cod 协助审查"
        val midTrigger = ComposerLexicalTriggerParser.detect(textWithSuffix, cursorIndex = 6)!!
        val (updatedMention, mentionCursor) = ComposerLexicalTriggerParser.applyAgentMention(
            originalText = textWithSuffix,
            trigger = midTrigger,
            agentNameOrId = "Code Reviewer",
        )
        assertEquals("请 @Code_Reviewer  协助审查", updatedMention)
        assertEquals("请 @Code_Reviewer ".length, mentionCursor)

        val (updatedSkill, skillCursor) = ComposerLexicalTriggerParser.applySlashSkill(
            originalText = "/rev",
            trigger = ComposerLexicalTriggerParser.detect("/rev")!!,
            skill = SlashSkillSummary(name = "review", summary = "Code review", argumentHint = "<PR>"),
        )
        assertEquals("/review <PR> ", updatedSkill)
        assertEquals("/review <PR> ".length, skillCursor)

        // 4. Filter agents & skills
        val roster = listOf(
            AgentDesktopSummary(id = "coder", name = "工程研发", role = "Full-stack Kotlin/TS", status = "idle", hasDesktop = true),
            AgentDesktopSummary(id = "ops", name = "值班管家", role = "Infra & SRE", status = "busy", hasDesktop = false),
        )
        assertEquals(listOf("ops"), ComposerLexicalTriggerParser.filterAgents(roster, "sre").map { it.id })
        val skills = listOf(
            SlashSkillSummary(name = "review", summary = "代码审查"),
            SlashSkillSummary(name = "deploy-check", summary = "发布前门禁核验"),
        )
        assertEquals(listOf("deploy-check"), ComposerLexicalTriggerParser.filterSkills(skills, "/门禁").map { it.name })
    }

    @Test
    fun `single and multi-agent conversation creation stages optimistic state offline and migrates cleanly with stable Idempotency-Key when online`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2270-conv").toFile()
        try {
            val store = newTestStore(tmpDir)
            var onlineTransport: ApiTransport? = null
            val capturedRequests = mutableListOf<Pair<String?, GeneratedCreateConversationRequest>>()

            val lightCoord = LightManagementCoordinator(
                connectionId = "conn_alpha",
                store = store,
                transportProvider = { onlineTransport },
                clock = { Instant.parse("2026-04-18T10:00:00Z") },
            )
            val convCoord = ConversationAndBoardCoordinator(
                connectionId = "conn_alpha",
                store = store,
                transportProvider = { onlineTransport },
            )

            // 1. Offline multi-@Agent conversation creation stages optimistic placeholder + initial message
            val offlineCreated = lightCoord.createConversation(
                primaryAgentId = "coder",
                primaryAgentName = "工程研发",
                participantAgentIds = listOf("coder", "reviewer", "qa"),
                templateId = "tpl_code_diagnose",
                initialText = "@reviewer @qa 请一起排查构建超时根因",
                explicitClientConversationKey = "cconv_fixed_01",
            )
            assertTrue(offlineCreated.id.startsWith("local_conv_"))
            assertEquals(listOf("coder", "reviewer", "qa"), offlineCreated.participantAgentIds)
            assertEquals(1, lightCoord.pendingCreateConversationCount())

            val cachedConvsOffline = convCoord.readCachedConversations()
            assertEquals(1, cachedConvsOffline.size)
            assertEquals(offlineCreated.id, cachedConvsOffline.single().id)
            val cachedMsgsOffline = convCoord.readCachedMessages(offlineCreated.id)
            assertEquals(1, cachedMsgsOffline.size)
            assertTrue(cachedMsgsOffline.single().pendingSend)

            // 2. Retry while still offline with same explicitClientConversationKey preserves single pending record
            val retryOffline = lightCoord.createConversation(
                primaryAgentId = "coder",
                primaryAgentName = "工程研发",
                participantAgentIds = listOf("coder", "reviewer", "qa"),
                templateId = "tpl_code_diagnose",
                initialText = "@reviewer @qa 请一起排查构建超时根因",
                explicitClientConversationKey = "cconv_fixed_01",
            )
            assertEquals(offlineCreated.id, retryOffline.id)
            assertEquals(1, lightCoord.pendingCreateConversationCount())

            // 3. Bring transport online (first simulate transient network failure to prove Idempotency-Key stays identical)
            var failFirstAttempt = true
            onlineTransport = ApiTransport { req: TransportRequest ->
                val parsed = LumenBoxJson.default.decodeFromString(
                    GeneratedCreateConversationRequest.serializer(),
                    String(req.body ?: ByteArray(0), StandardCharsets.UTF_8),
                )
                capturedRequests.add(req.idempotencyKey to parsed)
                if (failFirstAttempt) {
                    failFirstAttempt = false
                    throw IllegalStateException("Simulated socket reset")
                }
                val respJson = """
                    {
                      "durableSeq": 88,
                      "conversation": {
                        "id": "conv_srv_9001",
                        "title": "排查构建超时根因",
                        "agentId": "coder",
                        "agentName": "工程研发",
                        "participantAgentIds": ["coder", "reviewer", "qa"],
                        "updatedAt": "2026-04-18T10:00:05Z",
                        "running": true,
                        "lastMessagePreview": "@reviewer @qa 请一起排查构建超时根因"
                      },
                      "initialMessage": {
                        "id": "msg_srv_1",
                        "conversationId": "conv_srv_9001",
                        "clientMessageId": "cmsg_init_cconv_fixed_01",
                        "role": "user",
                        "text": "@reviewer @qa 请一起排查构建超时根因",
                        "createdAt": "2026-04-18T10:00:05Z"
                      }
                    }
                """.trimIndent()
                TransportResponse(
                    statusCode = 201,
                    headers = mapOf("content-type" to "application/json"),
                    body = respJson.toByteArray(StandardCharsets.UTF_8),
                )
            }

            // First flush hits transient socket reset -> stays queued
            assertTrue(lightCoord.flushPendingConversationCreations().isEmpty())
            assertEquals(1, lightCoord.pendingCreateConversationCount())

            // Second flush succeeds -> migrates placeholder ID to server ID & clears pending queue
            val flushed = lightCoord.flushPendingConversationCreations()
            assertEquals(1, flushed.size)
            assertEquals("conv_srv_9001", flushed.single().id)
            assertEquals(0, lightCoord.pendingCreateConversationCount())

            assertEquals(2, capturedRequests.size)
            assertNotNull(capturedRequests[0].first)
            assertEquals(capturedRequests[0].first, capturedRequests[1].first, "Idempotency-Key must remain identical across retries")
            assertEquals(listOf("coder", "reviewer", "qa"), capturedRequests[1].second.participantAgentIds)
            assertEquals("tpl_code_diagnose", capturedRequests[1].second.templateId)

            // Placeholder conversation was replaced cleanly by conv_srv_9001
            val cachedAfterFlush = convCoord.readCachedConversations()
            assertEquals(listOf("conv_srv_9001"), cachedAfterFlush.map { it.id })
            assertEquals(listOf("coder", "reviewer", "qa"), cachedAfterFlush.single().participantAgentIds)
            assertTrue(convCoord.readCachedMessages(offlineCreated.id).isEmpty())
            val serverMsgs = convCoord.readCachedMessages("conv_srv_9001")
            assertEquals(1, serverMsgs.size)
            assertFalse(serverMsgs.single().pendingSend)
            assertEquals(88L, convCoord.currentDurableSeq())
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `templates, skills, and agents refresh cleanly and fall back to DefaultStarterTemplates on 404`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2270-tpl").toFile()
        try {
            val store = newTestStore(tmpDir)
            var templatesStatus = 404
            val transport = ApiTransport { req ->
                when (req.path) {
                    "/api/v1/agents" -> TransportResponse(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = """{"items":[{"id":"main","name":"主控管家","role":"Coordinator","status":"idle","hasDesktop":true}]}"""
                            .toByteArray(StandardCharsets.UTF_8),
                    )
                    "/api/v1/templates" -> if (templatesStatus == 404) {
                        TransportResponse(statusCode = 404, headers = emptyMap(), body = ByteArray(0))
                    } else {
                        TransportResponse(
                            statusCode = 200,
                            headers = emptyMap(),
                            body = """{"items":[{"id":"srv_tpl_1","title":"周报汇总","promptTemplate":"请汇总本周合并 PR：","recommendedAgentId":"main","category":"团队"}]}"""
                                .toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                    "/api/v1/skills" -> TransportResponse(
                        statusCode = 200,
                        headers = emptyMap(),
                        body = """{"items":[{"name":"code-review","summary":"审查变更集","argumentHint":"<PR#>"}]}"""
                            .toByteArray(StandardCharsets.UTF_8),
                    )
                    else -> TransportResponse(statusCode = 404, headers = emptyMap(), body = ByteArray(0))
                }
            }

            val coord = LightManagementCoordinator(
                connectionId = "conn_alpha",
                store = store,
                transportProvider = { transport },
            )

            val agents = coord.refreshAgents()
            assertEquals(1, agents.size)
            assertEquals("主控管家", agents.single().name)
            assertTrue(agents.single().hasDesktop)

            // 404 falls back to DefaultStarterTemplates without throwing
            val fallbackTemplates = coord.refreshTemplates()
            assertEquals(DefaultStarterTemplates.items, fallbackTemplates)

            // Server upgrade to 200 replaces with server-supplied templates and persists to encrypted store
            templatesStatus = 200
            val liveTemplates = coord.refreshTemplates()
            assertEquals(listOf("srv_tpl_1"), liveTemplates.map { it.id })
            assertEquals(listOf("srv_tpl_1"), coord.readCachedTemplates().map { it.id })

            val skills = coord.refreshSkills()
            assertEquals(listOf("code-review"), skills.map { it.name })
            assertEquals(listOf("code-review"), coord.readCachedSkills().map { it.name })
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `Routine optimistic pause, resume, and mute commit on 200 and roll back to exact prior snapshot on HTTP 500 or offline`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2270-routine").toFile()
        try {
            val store = newTestStore(tmpDir)
            var patchShouldFail500 = false
            var isOffline = false
            val patchPayloads = mutableListOf<GeneratedPatchRoutineRequest>()

            val transport = ApiTransport { req ->
                when {
                    req.method == "GET" && req.path == "/api/v1/routines" -> {
                        val json = """
                            {
                              "durableSeq": 51,
                              "items": [
                                {
                                  "id": "rt_morning",
                                  "name": "早间巡检日报",
                                  "agentId": "ops",
                                  "agentName": "值班管家",
                                  "schedule": "0 9 * * 1-5",
                                  "enabled": true,
                                  "muted": false,
                                  "nextRunAt": "2026-04-20T01:00:00Z",
                                  "lastRunAt": "2026-04-17T01:00:12Z",
                                  "lastStatus": "completed",
                                  "spendGuardPaused": false
                                },
                                {
                                  "id": "rt_budget_halted",
                                  "name": "高频舆情扫描",
                                  "agentId": "analyst",
                                  "schedule": "*/15 * * * *",
                                  "enabled": false,
                                  "muted": true,
                                  "spendGuardPaused": true,
                                  "spendGuardReason": "超出日限额 $20"
                                }
                              ]
                            }
                        """.trimIndent()
                        TransportResponse(200, emptyMap(), json.toByteArray(StandardCharsets.UTF_8))
                    }

                    req.method == "PATCH" && req.path == "/api/v1/routines/rt_morning" -> {
                        val patch = LumenBoxJson.default.decodeFromString(
                            GeneratedPatchRoutineRequest.serializer(),
                            String(req.body ?: ByteArray(0), StandardCharsets.UTF_8),
                        )
                        patchPayloads.add(patch)
                        if (patchShouldFail500) {
                            val errBody = """{"code":"ROUTINE_LOCKED","error":"Scheduler busy"}"""
                            TransportResponse(500, emptyMap(), errBody.toByteArray(StandardCharsets.UTF_8))
                        } else {
                            val updatedJson = """
                                {
                                  "id": "rt_morning",
                                  "name": "早间巡检日报",
                                  "agentId": "ops",
                                  "agentName": "值班管家",
                                  "schedule": "0 9 * * 1-5",
                                  "enabled": ${patch.enabled ?: true},
                                  "muted": ${patch.muted ?: false},
                                  "nextRunAt": "2026-04-20T01:00:00Z",
                                  "lastStatus": "completed"
                                }
                            """.trimIndent()
                            TransportResponse(200, emptyMap(), updatedJson.toByteArray(StandardCharsets.UTF_8))
                        }
                    }

                    else -> TransportResponse(404, emptyMap(), ByteArray(0))
                }
            }

            val coord = LightManagementCoordinator(
                connectionId = "conn_alpha",
                store = store,
                transportProvider = { if (isOffline) null else transport },
            )

            val initial = coord.refreshRoutines()
            assertEquals(2, initial.size)
            assertTrue(initial[0].enabled)
            assertFalse(initial[0].muted)
            assertTrue(initial[1].spendGuardPaused)

            // 1. Successful optimistic pause (`enabled = false`)
            val optimisticSeenOnSuccess = mutableListOf<Boolean>()
            val afterPause = coord.setRoutinePatchOptimistic(
                routineId = "rt_morning",
                enabled = false,
                onOptimisticApplied = { list -> optimisticSeenOnSuccess.add(list.first().enabled) },
            )
            assertEquals(listOf(false), optimisticSeenOnSuccess)
            assertFalse(afterPause.first().enabled)
            assertFalse(coord.readCachedRoutines().first().enabled)

            // 2. Server HTTP 500 error triggers automatic rollback to exact prior snapshot (`enabled == false`, `muted == false`)
            patchShouldFail500 = true
            val optimisticSeenOnFailure = mutableListOf<Boolean>()
            assertThrows(ApiEnvelopeException::class.java) {
                kotlinx.coroutines.runBlocking {
                    coord.setRoutinePatchOptimistic(
                        routineId = "rt_morning",
                        muted = true,
                        onOptimisticApplied = { list -> optimisticSeenOnFailure.add(list.first().muted) },
                    )
                }
            }
            assertEquals(listOf(true), optimisticSeenOnFailure, "UI sees immediate optimistic flip before server responds")
            val rolledBack = coord.readCachedRoutines().first()
            assertFalse(rolledBack.enabled)
            assertFalse(rolledBack.muted, "Encrypted cache must roll back muted=false after HTTP 500")

            // 3. Offline attempt also rolls back cleanly
            patchShouldFail500 = false
            isOffline = true
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    coord.setRoutinePatchOptimistic(routineId = "rt_morning", enabled = true)
                }
            }
            assertFalse(coord.readCachedRoutines().first().enabled, "Encrypted cache must remain rolled back when offline")
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

package com.lumenopen.lumenbox.conversation

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.desktop.AgentDesktopSummary
import com.lumenopen.lumenbox.generated.models.GeneratedAgentPage
import com.lumenopen.lumenbox.generated.models.GeneratedCreateConversationRequest
import com.lumenopen.lumenbox.generated.models.GeneratedCreateConversationResponse
import com.lumenopen.lumenbox.generated.models.GeneratedPatchRoutineRequest
import com.lumenopen.lumenbox.generated.models.GeneratedRoutinePage
import com.lumenopen.lumenbox.generated.models.GeneratedRoutineSummary
import com.lumenopen.lumenbox.generated.models.GeneratedSkillPage
import com.lumenopen.lumenbox.generated.models.GeneratedSlashSkillSummary
import com.lumenopen.lumenbox.generated.models.GeneratedStarterTemplate
import com.lumenopen.lumenbox.generated.models.GeneratedTemplatePage
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

@Serializable
data class StarterTemplateSummary(
    val id: String,
    val title: String,
    val summary: String? = null,
    val promptTemplate: String,
    val recommendedAgentId: String? = null,
    val category: String? = null,
)

@Serializable
data class SlashSkillSummary(
    val name: String,
    val summary: String? = null,
    val argumentHint: String? = null,
)

@Serializable
data class RoutineItemSummary(
    val id: String,
    val name: String,
    val agentId: String? = null,
    val agentName: String? = null,
    val schedule: String,
    val enabled: Boolean,
    val muted: Boolean = false,
    val nextRunAt: String? = null,
    val lastRunAt: String? = null,
    val lastStatus: String? = null,
    val spendGuardPaused: Boolean = false,
    val spendGuardReason: String? = null,
)

enum class ComposerLexicalTriggerKind {
    AGENT_MENTION,
    SLASH_SKILL,
}

data class ComposerLexicalTrigger(
    val kind: ComposerLexicalTriggerKind,
    val query: String,
    val replaceStartIndex: Int,
    val replaceEndIndex: Int,
)

/**
 * Pure deterministic lexical trigger parser for `@Agent` mentions and `/` skill invocations
 * inside the mobile message composer (`INV-2270`).
 */
object ComposerLexicalTriggerParser {

    /**
     * Inspects [text] at [cursorIndex] (defaults to `text.length`) and returns an active
     * `@mention` or `/skill` trigger token if the cursor is currently positioned inside one.
     *
     * Rules:
     * - `/` skill trigger only activates at the start of the composer (ignoring leading spaces)
     *   so URLs like `https://example.com/foo` or file paths never false-trigger the skill drawer.
     * - `@` mention trigger activates at start-of-input or after whitespace / punctuation,
     *   avoiding email addresses (`alice@example.com` where `@` is preceded by an identifier char).
     */
    fun detect(text: String, cursorIndex: Int = text.length): ComposerLexicalTrigger? {
        if (text.isEmpty()) return null
        val clampedCursor = cursorIndex.coerceIn(0, text.length)
        val prefix = text.substring(0, clampedCursor)

        // 1. Check leading `/` slash command token
        val leadingTrimStart = prefix.indexOfFirst { !it.isWhitespace() }
        if (leadingTrimStart >= 0 && prefix[leadingTrimStart] == '/') {
            val afterSlash = prefix.substring(leadingTrimStart + 1)
            if (afterSlash.none { it.isWhitespace() }) {
                return ComposerLexicalTrigger(
                    kind = ComposerLexicalTriggerKind.SLASH_SKILL,
                    query = afterSlash,
                    replaceStartIndex = leadingTrimStart,
                    replaceEndIndex = clampedCursor,
                )
            }
        }

        // 2. Scan backwards from cursor for `@` mention token
        var scan = clampedCursor - 1
        while (scan >= 0) {
            val ch = prefix[scan]
            if (ch.isWhitespace()) break
            if (ch == '@') {
                val prevChar = if (scan > 0) prefix[scan - 1] else null
                val validBoundary = prevChar == null || prevChar.isWhitespace() || prevChar in listOf('(', '[', '（', '，', ',')
                if (!validBoundary) return null
                val mentionQuery = prefix.substring(scan + 1, clampedCursor)
                return ComposerLexicalTrigger(
                    kind = ComposerLexicalTriggerKind.AGENT_MENTION,
                    query = mentionQuery,
                    replaceStartIndex = scan,
                    replaceEndIndex = clampedCursor,
                )
            }
            scan -= 1
        }
        return null
    }

    /**
     * Replaces [trigger] inside [originalText] with `@<agentHandle> ` and returns `(updatedText, newCursor)`.
     */
    fun applyAgentMention(
        originalText: String,
        trigger: ComposerLexicalTrigger,
        agentNameOrId: String,
    ): Pair<String, Int> {
        val cleanToken = agentNameOrId.trim().replace(' ', '_')
        val insertion = "@$cleanToken "
        val updated = buildString {
            append(originalText.substring(0, trigger.replaceStartIndex))
            append(insertion)
            append(originalText.substring(trigger.replaceEndIndex))
        }
        return updated to (trigger.replaceStartIndex + insertion.length)
    }

    /**
     * Replaces [trigger] inside [originalText] with `/<skillName> ` (plus optional argument placeholder).
     */
    fun applySlashSkill(
        originalText: String,
        trigger: ComposerLexicalTrigger,
        skill: SlashSkillSummary,
    ): Pair<String, Int> {
        val cleanSkill = skill.name.trim().removePrefix("/")
        val hintSuffix = skill.argumentHint?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it " } ?: ""
        val insertion = "/$cleanSkill $hintSuffix"
        val updated = buildString {
            append(originalText.substring(0, trigger.replaceStartIndex))
            append(insertion)
            append(originalText.substring(trigger.replaceEndIndex))
        }
        return updated to (trigger.replaceStartIndex + insertion.length)
    }

    fun filterAgents(agents: List<AgentDesktopSummary>, query: String): List<AgentDesktopSummary> {
        val needle = query.trim()
        if (needle.isEmpty()) return agents
        return agents.filter {
            it.name.contains(needle, ignoreCase = true) ||
                it.id.contains(needle, ignoreCase = true) ||
                (it.role?.contains(needle, ignoreCase = true) == true)
        }
    }

    fun filterSkills(skills: List<SlashSkillSummary>, query: String): List<SlashSkillSummary> {
        val needle = query.trim().removePrefix("/")
        if (needle.isEmpty()) return skills
        return skills.filter {
            it.name.contains(needle, ignoreCase = true) ||
                (it.summary?.contains(needle, ignoreCase = true) == true)
        }
    }
}

/**
 * Built-in fallback starter templates shown on empty/new conversations when the server has not yet
 * exposed custom templates or returned an empty list (`INV-2270`).
 */
object DefaultStarterTemplates {
    val items: List<StarterTemplateSummary> = listOf(
        StarterTemplateSummary(
            id = "tpl_daily_brief",
            title = "今日进展与待办盘点",
            summary = "汇总近期工作状态、阻塞项与下一步优先级",
            promptTemplate = "请帮我盘点当前进行中的任务进展、阻塞风险，并给出今天最优先的 3 件事：",
            category = "效率",
        ),
        StarterTemplateSummary(
            id = "tpl_code_diagnose",
            title = "排查线上或构建异常",
            summary = "定位报错根因并给出最小修复方案与验证命令",
            promptTemplate = "请帮我排查以下异常现象，定位根因并给出修复方案与回归验证步骤：\n\n现象描述：",
            category = "工程",
        ),
        StarterTemplateSummary(
            id = "tpl_doc_digest",
            title = "提炼长文与行动结论",
            summary = "把会议纪要、文档或外部资料浓缩为结构化结论",
            promptTemplate = "请阅读以下材料，提炼核心结论、关键分歧点以及需要我拍板的事项：\n\n",
            category = "总结",
        ),
    )
}

@Serializable
private data class PendingCreateConversationRecord(
    val idempotencyKey: String,
    val clientConversationKey: String,
    val localPlaceholderConversationId: String,
    val title: String?,
    val primaryAgentId: String,
    val primaryAgentName: String?,
    val participantAgentIds: List<String> = emptyList(),
    val templateId: String? = null,
    val initialText: String? = null,
    val createdAt: String,
)

/**
 * Mobile Light-Management Coordinator (`INV-2270`):
 * - `GET /api/v1/agents`: Roster of single/multi-`@Agent` collaborators with busy/idle & desktop badges.
 * - `POST /api/v1/conversations`: Idempotent single/multi-Agent conversation creation with offline queue fallback.
 * - `GET /api/v1/templates` & `GET /api/v1/skills`: Starter templates & searchable `/` skill directory (fail-soft on `404` for older hosts).
 * - `GET /api/v1/routines` & `PATCH /api/v1/routines/{id}`: Routine list + optimistic one-tap pause/resume (`enabled`) and `muted` toggle with automatic rollback on failure.
 */
class LightManagementCoordinator(
    private val connectionId: String,
    private val store: InstallationStore,
    private val transportProvider: () -> ApiTransport?,
    private val clock: () -> Instant = { Instant.now() },
) {
    companion object {
        private const val AGENTS_TABLE = "agentsSnapshot"
        private const val AGENTS_ROW = "items"
        private const val TEMPLATES_TABLE = "starterTemplatesSnapshot"
        private const val TEMPLATES_ROW = "items"
        private const val SKILLS_TABLE = "slashSkillsSnapshot"
        private const val SKILLS_ROW = "items"
        private const val ROUTINES_TABLE = "routinesSnapshot"
        private const val ROUTINES_ROW = "items"
        private const val CONVERSATIONS_TABLE = "conversationsSnapshot"
        private const val CONVERSATIONS_ROW = "items"
        private const val MESSAGES_TABLE = "conversationMessages"
        private const val PENDING_CREATE_CONV_TABLE = "pendingCreateConversations"
    }

    private val mutex = Mutex()

    // ---- Cached Readers ----

    fun readCachedAgents(): List<AgentDesktopSummary> =
        openStoreOrNull()?.getJson(
            AGENTS_TABLE,
            AGENTS_ROW,
            ListSerializer(AgentDesktopSummary.serializer()),
        ).orEmpty()

    fun readCachedTemplates(): List<StarterTemplateSummary> {
        val cached = openStoreOrNull()?.getJson(
            TEMPLATES_TABLE,
            TEMPLATES_ROW,
            ListSerializer(StarterTemplateSummary.serializer()),
        ).orEmpty()
        return cached.ifEmpty { DefaultStarterTemplates.items }
    }

    fun readCachedSkills(): List<SlashSkillSummary> =
        openStoreOrNull()?.getJson(
            SKILLS_TABLE,
            SKILLS_ROW,
            ListSerializer(SlashSkillSummary.serializer()),
        ).orEmpty()

    fun readCachedRoutines(): List<RoutineItemSummary> =
        openStoreOrNull()?.getJson(
            ROUTINES_TABLE,
            ROUTINES_ROW,
            ListSerializer(RoutineItemSummary.serializer()),
        ).orEmpty()

    fun pendingCreateConversationCount(): Int =
        openStoreOrNull()?.rowKeys(PENDING_CREATE_CONV_TABLE)?.size ?: 0

    // ---- 1. Agents Roster (`GET /api/v1/agents`) ----

    suspend fun refreshAgents(): List<AgentDesktopSummary> {
        val transport = transportProvider() ?: return readCachedAgents()
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/agents",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        throwIfError(res.statusCode, res.body)

        val page = LumenBoxJson.default.decodeFromString(
            GeneratedAgentPage.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val agents = page.items.map {
            AgentDesktopSummary(
                id = it.id,
                name = it.name,
                role = it.role,
                status = it.status,
                hasDesktop = it.hasDesktop ?: false,
            )
        }
        store.openOfflineStore(connectionId).putJson(
            AGENTS_TABLE,
            AGENTS_ROW,
            ListSerializer(AgentDesktopSummary.serializer()),
            agents,
        )
        return agents
    }

    // ---- 2. Starter Templates & Slash Skills (`GET /api/v1/templates`, `GET /api/v1/skills`) ----

    suspend fun refreshTemplates(): List<StarterTemplateSummary> {
        val transport = transportProvider() ?: return readCachedTemplates()
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/templates",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        // Older servers before INV-2264 may return 404/501 — fall back cleanly to cached or built-in templates!
        if (res.statusCode == 404 || res.statusCode == 501) {
            return readCachedTemplates()
        }
        throwIfError(res.statusCode, res.body)

        val page = LumenBoxJson.default.decodeFromString(
            GeneratedTemplatePage.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val templates = page.items.map { it.toDomain() }
        if (templates.isNotEmpty()) {
            store.openOfflineStore(connectionId).putJson(
                TEMPLATES_TABLE,
                TEMPLATES_ROW,
                ListSerializer(StarterTemplateSummary.serializer()),
                templates,
            )
        }
        return templates.ifEmpty { DefaultStarterTemplates.items }
    }

    suspend fun refreshSkills(): List<SlashSkillSummary> {
        val transport = transportProvider() ?: return readCachedSkills()
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/skills",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        if (res.statusCode == 404 || res.statusCode == 501) {
            return readCachedSkills()
        }
        throwIfError(res.statusCode, res.body)

        val page = LumenBoxJson.default.decodeFromString(
            GeneratedSkillPage.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val skills = page.items.map { it.toDomain() }
        store.openOfflineStore(connectionId).putJson(
            SKILLS_TABLE,
            SKILLS_ROW,
            ListSerializer(SlashSkillSummary.serializer()),
            skills,
        )
        return skills
    }

    // ---- 3. Idempotent Single/Multi-Agent Conversation Creation (`POST /api/v1/conversations`) ----

    /**
     * Creates a conversation with primary [primaryAgentId] and optional [participantAgentIds]
     * (`@Agent` multi-agent group), [templateId], and [initialText].
     *
     * Uses a stable `Idempotency-Key` + `clientConversationKey` so network retries never create
     * duplicate conversations, and stages an optimistic local conversation when offline.
     */
    suspend fun createConversation(
        primaryAgentId: String,
        primaryAgentName: String? = null,
        participantAgentIds: List<String> = emptyList(),
        title: String? = null,
        templateId: String? = null,
        initialText: String? = null,
        explicitClientConversationKey: String? = null,
    ): ConversationSummary = mutex.withLock {
        require(primaryAgentId.isNotBlank()) { "必须至少选择 1 位主责 Agent" }
        val distinctParticipants = (listOf(primaryAgentId) + participantAgentIds)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        val extraCollaborators = distinctParticipants.filterNot { it == primaryAgentId }

        val clientKey = explicitClientConversationKey?.takeIf { it.isNotBlank() }
            ?: "cconv_${UUID.randomUUID()}"
        val nowIso = clock().toString()
        val offline = store.openOfflineStore(connectionId)

        // Reuse existing queued record if caller retries with the same explicitClientConversationKey
        val existingPending = offline.getJson(
            PENDING_CREATE_CONV_TABLE,
            clientKey,
            PendingCreateConversationRecord.serializer(),
        )
        val idempotencyKey = existingPending?.idempotencyKey ?: UUID.randomUUID().toString()
        val localPlaceholderId = existingPending?.localPlaceholderConversationId ?: "local_conv_$clientKey"
        val resolvedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
            ?: buildDefaultConversationTitle(primaryAgentName ?: primaryAgentId, extraCollaborators.size, initialText)

        val record = PendingCreateConversationRecord(
            idempotencyKey = idempotencyKey,
            clientConversationKey = clientKey,
            localPlaceholderConversationId = localPlaceholderId,
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            primaryAgentId = primaryAgentId,
            primaryAgentName = primaryAgentName,
            participantAgentIds = distinctParticipants,
            templateId = templateId?.takeIf { it.isNotBlank() },
            initialText = initialText?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = existingPending?.createdAt ?: nowIso,
        )
        offline.putJson(PENDING_CREATE_CONV_TABLE, clientKey, PendingCreateConversationRecord.serializer(), record)

        val optimisticSummary = ConversationSummary(
            id = localPlaceholderId,
            title = resolvedTitle,
            agentId = primaryAgentId,
            agentName = primaryAgentName,
            participantAgentIds = distinctParticipants,
            updatedAt = record.createdAt,
            running = !record.initialText.isNullOrBlank(),
            lastMessagePreview = record.initialText,
        )
        upsertConversationInCache(offline, optimisticSummary)
        if (!record.initialText.isNullOrBlank()) {
            val optimisticMsg = CommittedMessage(
                id = "pending_init_$clientKey",
                conversationId = localPlaceholderId,
                clientMessageId = "cmsg_init_$clientKey",
                role = OpenEnum.Known(MessageRole.USER, MessageRole.USER.wireName),
                text = record.initialText,
                createdAt = record.createdAt,
                pendingSend = true,
            )
            offline.putJson(
                MESSAGES_TABLE,
                localPlaceholderId,
                ListSerializer(CommittedMessage.serializer()),
                listOf(optimisticMsg),
            )
        }

        val delivered = deliverPendingConversationRecord(offline, record)
        delivered ?: optimisticSummary
    }

    /**
     * Replays any offline-staged conversation creations with their original `Idempotency-Key`s.
     */
    suspend fun flushPendingConversationCreations(): List<ConversationSummary> = mutex.withLock {
        val offline = store.openOfflineStore(connectionId)
        val keys = offline.rowKeys(PENDING_CREATE_CONV_TABLE)
        val created = mutableListOf<ConversationSummary>()
        for (key in keys) {
            val rec = offline.getJson(PENDING_CREATE_CONV_TABLE, key, PendingCreateConversationRecord.serializer())
                ?: continue
            val res = deliverPendingConversationRecord(offline, rec) ?: break
            created.add(res)
        }
        created
    }

    private suspend fun deliverPendingConversationRecord(
        offline: com.lumenopen.lumenbox.storage.ConnectionOfflineStore,
        record: PendingCreateConversationRecord,
    ): ConversationSummary? {
        val transport = transportProvider() ?: return null
        val reqPayload = GeneratedCreateConversationRequest(
            clientConversationKey = record.clientConversationKey,
            title = record.title,
            agentId = record.primaryAgentId,
            participantAgentIds = record.participantAgentIds.takeIf { it.size > 1 },
            templateId = record.templateId,
            initialText = record.initialText,
        )
        val bodyBytes = LumenBoxJson.default.encodeToString(
            GeneratedCreateConversationRequest.serializer(),
            reqPayload,
        ).toByteArray(StandardCharsets.UTF_8)

        val res = try {
            transport.send(
                TransportRequest(
                    method = "POST",
                    path = "/api/v1/conversations",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                    ),
                    body = bodyBytes,
                    idempotencyKey = record.idempotencyKey,
                )
            )
        } catch (err: Throwable) {
            if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) throw err
            return null
        }

        if (res.statusCode == 401) {
            throwIfError(res.statusCode, res.body)
        }
        if (res.statusCode !in 200..299) {
            throwIfError(res.statusCode, res.body)
        }

        val parsed = LumenBoxJson.default.decodeFromString(
            GeneratedCreateConversationResponse.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        parsed.durableSeq?.let { offline.advanceDurableSeq(it) }

        val serverSummary = ConversationSummary(
            id = parsed.conversation.id,
            title = parsed.conversation.title,
            agentId = parsed.conversation.agentId,
            agentName = parsed.conversation.agentName ?: record.primaryAgentName,
            participantAgentIds = parsed.conversation.participantAgentIds ?: record.participantAgentIds,
            updatedAt = parsed.conversation.updatedAt,
            running = parsed.conversation.running ?: false,
            lastMessagePreview = parsed.conversation.lastMessagePreview ?: record.initialText,
        )

        // Replace local placeholder conversation and migrate initial message cache cleanly
        removeConversationFromCache(offline, record.localPlaceholderConversationId)
        offline.deleteRow(MESSAGES_TABLE, record.localPlaceholderConversationId)
        upsertConversationInCache(offline, serverSummary)

        parsed.initialMessage?.let { wireMsg ->
            val committed = CommittedMessage(
                id = wireMsg.id,
                conversationId = wireMsg.conversationId,
                clientMessageId = wireMsg.clientMessageId,
                role = MessageRole.fromWire(wireMsg.role),
                text = wireMsg.text,
                thinking = wireMsg.thinking,
                createdAt = wireMsg.createdAt,
                pendingSend = false,
            )
            offline.putJson(
                MESSAGES_TABLE,
                serverSummary.id,
                ListSerializer(CommittedMessage.serializer()),
                listOf(committed),
            )
        }

        offline.deleteRow(PENDING_CREATE_CONV_TABLE, record.clientConversationKey)
        return serverSummary
    }

    // ---- 4. Routines Light Management (`GET /api/v1/routines`, `PATCH /api/v1/routines/{id}`) ----

    suspend fun refreshRoutines(): List<RoutineItemSummary> {
        val transport = transportProvider() ?: return readCachedRoutines()
        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/routines",
                headers = mapOf("Accept" to "application/json"),
            )
        )
        if (res.statusCode == 404 || res.statusCode == 501) {
            return readCachedRoutines()
        }
        throwIfError(res.statusCode, res.body)

        val page = LumenBoxJson.default.decodeFromString(
            GeneratedRoutinePage.serializer(),
            String(res.body, StandardCharsets.UTF_8),
        )
        val offline = store.openOfflineStore(connectionId)
        page.durableSeq?.let { offline.advanceDurableSeq(it) }
        val items = page.items.map { it.toDomain() }
        offline.putJson(
            ROUTINES_TABLE,
            ROUTINES_ROW,
            ListSerializer(RoutineItemSummary.serializer()),
            items,
        )
        return items
    }

    /**
     * Optimistically updates `enabled` (pause/resume) and/or `muted` on [routineId] in the local
     * encrypted store, sends `PATCH /api/v1/routines/{id}`, and **automatically rolls back** to
     * the exact pre-mutation snapshot if the server rejects or fails the request (`INV-2270`).
     */
    suspend fun setRoutinePatchOptimistic(
        routineId: String,
        enabled: Boolean? = null,
        muted: Boolean? = null,
        onOptimisticApplied: (List<RoutineItemSummary>) -> Unit = {},
    ): List<RoutineItemSummary> = mutex.withLock {
        val offline = store.openOfflineStore(connectionId)
        val snapshotBefore = readCachedRoutines()
        val idx = snapshotBefore.indexOfFirst { it.id == routineId }
        if (idx < 0) {
            throw IllegalArgumentException("未找到定时任务 $routineId")
        }

        val previousItem = snapshotBefore[idx]
        val optimisticItem = previousItem.copy(
            enabled = enabled ?: previousItem.enabled,
            muted = muted ?: previousItem.muted,
        )
        val optimisticList = snapshotBefore.toMutableList().apply { set(idx, optimisticItem) }
        offline.putJson(
            ROUTINES_TABLE,
            ROUTINES_ROW,
            ListSerializer(RoutineItemSummary.serializer()),
            optimisticList,
        )
        onOptimisticApplied(optimisticList)

        val transport = transportProvider()
        if (transport == null) {
            // Roll back immediately when offline because Routine toggles require live host acknowledgement
            offline.putJson(
                ROUTINES_TABLE,
                ROUTINES_ROW,
                ListSerializer(RoutineItemSummary.serializer()),
                snapshotBefore,
            )
            throw IllegalStateException("当前离线，无法变更远端定时任务状态（已回滚本地开关）。")
        }

        try {
            val req = GeneratedPatchRoutineRequest(
                enabled = enabled,
                muted = muted,
            )
            val bodyBytes = LumenBoxJson.default.encodeToString(
                GeneratedPatchRoutineRequest.serializer(),
                req,
            ).toByteArray(StandardCharsets.UTF_8)

            val res = transport.send(
                TransportRequest(
                    method = "PATCH",
                    path = "/api/v1/routines/${urlEncodePath(routineId)}",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                    ),
                    body = bodyBytes,
                    idempotencyKey = UUID.randomUUID().toString(),
                )
            )
            throwIfError(res.statusCode, res.body)

            val updatedServerItem = LumenBoxJson.default.decodeFromString(
                GeneratedRoutineSummary.serializer(),
                String(res.body, StandardCharsets.UTF_8),
            ).toDomain()

            val committedList = optimisticList.toMutableList().apply { set(idx, updatedServerItem) }
            offline.putJson(
                ROUTINES_TABLE,
                ROUTINES_ROW,
                ListSerializer(RoutineItemSummary.serializer()),
                committedList,
            )
            committedList
        } catch (err: Throwable) {
            // Roll back optimistic mutation on any transport or HTTP error!
            offline.putJson(
                ROUTINES_TABLE,
                ROUTINES_ROW,
                ListSerializer(RoutineItemSummary.serializer()),
                snapshotBefore,
            )
            throw err
        }
    }

    // ---- Internal Helpers ----

    private fun buildDefaultConversationTitle(
        primaryAgentLabel: String,
        extraCount: Int,
        initialText: String?,
    ): String {
        if (!initialText.isNullOrBlank()) {
            val firstLine = initialText.lineSequence().first().trim()
            if (firstLine.isNotEmpty()) {
                return if (firstLine.length <= 28) firstLine else "${firstLine.take(28)}…"
            }
        }
        return if (extraCount > 0) {
            "$primaryAgentLabel 等 ${extraCount + 1} 位 Agent 协作"
        } else {
            "与 $primaryAgentLabel 的新对话"
        }
    }

    private fun upsertConversationInCache(
        offline: com.lumenopen.lumenbox.storage.ConnectionOfflineStore,
        summary: ConversationSummary,
    ) {
        val current = offline.getJson(
            CONVERSATIONS_TABLE,
            CONVERSATIONS_ROW,
            ListSerializer(ConversationSummary.serializer()),
        ).orEmpty().associateByTo(LinkedHashMap()) { it.id }
        current[summary.id] = summary
        val sorted = current.values.sortedByDescending { it.updatedAt }
        offline.putJson(
            CONVERSATIONS_TABLE,
            CONVERSATIONS_ROW,
            ListSerializer(ConversationSummary.serializer()),
            sorted,
        )
    }

    private fun removeConversationFromCache(
        offline: com.lumenopen.lumenbox.storage.ConnectionOfflineStore,
        conversationId: String,
    ) {
        val current = offline.getJson(
            CONVERSATIONS_TABLE,
            CONVERSATIONS_ROW,
            ListSerializer(ConversationSummary.serializer()),
        ).orEmpty().filterNot { it.id == conversationId }
        offline.putJson(
            CONVERSATIONS_TABLE,
            CONVERSATIONS_ROW,
            ListSerializer(ConversationSummary.serializer()),
            current,
        )
    }

    private fun openStoreOrNull() =
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

    private fun urlEncodePath(segment: String): String =
        URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun GeneratedStarterTemplate.toDomain(): StarterTemplateSummary = StarterTemplateSummary(
        id = id,
        title = title,
        summary = summary,
        promptTemplate = promptTemplate,
        recommendedAgentId = recommendedAgentId,
        category = category,
    )

    private fun GeneratedSlashSkillSummary.toDomain(): SlashSkillSummary = SlashSkillSummary(
        name = name,
        summary = summary,
        argumentHint = argumentHint,
    )

    private fun GeneratedRoutineSummary.toDomain(): RoutineItemSummary = RoutineItemSummary(
        id = id,
        name = name,
        agentId = agentId,
        agentName = agentName,
        schedule = schedule,
        enabled = enabled,
        muted = muted ?: false,
        nextRunAt = nextRunAt,
        lastRunAt = lastRunAt,
        lastStatus = lastStatus,
        spendGuardPaused = spendGuardPaused ?: false,
        spendGuardReason = spendGuardReason,
    )
}

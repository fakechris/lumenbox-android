package com.lumenopen.lumenbox.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.conversation.ComposerLexicalTrigger
import com.lumenopen.lumenbox.conversation.ComposerLexicalTriggerKind
import com.lumenopen.lumenbox.conversation.ComposerLexicalTriggerParser
import com.lumenopen.lumenbox.conversation.ConversationSummary
import com.lumenopen.lumenbox.conversation.LightManagementCoordinator
import com.lumenopen.lumenbox.conversation.RoutineItemSummary
import com.lumenopen.lumenbox.conversation.SlashSkillSummary
import com.lumenopen.lumenbox.conversation.StarterTemplateSummary
import com.lumenopen.lumenbox.desktop.AgentDesktopSummary
import kotlinx.coroutines.launch

/**
 * Half-screen `ModalBottomSheet` for creating a single-Agent direct conversation or multi-`@Agent`
 * collaborative room (`INV-2270`), with optional starter template quick-fill.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewConversationRosterSheet(
    agents: List<AgentDesktopSummary>,
    templates: List<StarterTemplateSummary>,
    isHostReachable: Boolean,
    isSubmitting: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreateRequested: (
        primaryAgentId: String,
        primaryAgentName: String?,
        participantAgentIds: List<String>,
        title: String?,
        templateId: String?,
        initialText: String?,
    ) -> Unit,
) {
    var selectedPrimaryId by rememberSaveable(agents.size) {
        mutableStateOf(agents.firstOrNull()?.id.orEmpty())
    }
    var extraParticipantIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var multiAgentMode by rememberSaveable { mutableStateOf(false) }
    var selectedTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var customTitle by rememberSaveable { mutableStateOf("") }
    var initialPrompt by rememberSaveable { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("发起新会话", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (multiAgentMode) "多 Agent 协同模式：先选主责 Agent，再勾选协作成员"
                        else "选择 1 位专职 Agent 直聊，或切换多 Agent 协同",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilterChip(
                    selected = multiAgentMode,
                    onClick = {
                        multiAgentMode = !multiAgentMode
                        if (!multiAgentMode) extraParticipantIds = emptySet()
                    },
                    label = { Text("多 @Agent 协作") },
                )
            }

            errorMessage?.takeIf { it.isNotBlank() }?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (agents.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("暂未同步到 Agent 名册", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = selectedPrimaryId,
                            onValueChange = { selectedPrimaryId = it },
                            singleLine = true,
                            label = { Text("输入目标 Agent ID（如 main / coder）") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = agents, key = { it.id }) { agent ->
                        val isPrimary = agent.id == selectedPrimaryId
                        val isCollaborator = agent.id in extraParticipantIds
                        val selected = isPrimary || (multiAgentMode && isCollaborator)
                        Surface(
                            color = if (selected) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!multiAgentMode) {
                                        selectedPrimaryId = agent.id
                                    } else if (selectedPrimaryId.isBlank()) {
                                        selectedPrimaryId = agent.id
                                    } else if (isPrimary) {
                                        // Keep primary unless another agent is promoted
                                    } else if (isCollaborator) {
                                        extraParticipantIds = extraParticipantIds - agent.id
                                    } else {
                                        extraParticipantIds = extraParticipantIds + agent.id
                                    }
                                },
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            agent.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (agent.hasDesktop) {
                                            Text(
                                                "🖥 桌面",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary,
                                            )
                                        }
                                        agent.status?.takeIf { it.isNotBlank() }?.let { st ->
                                            Text(
                                                "· $st",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                    agent.role?.takeIf { it.isNotBlank() }?.let { role ->
                                        Text(
                                            role,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                if (isPrimary) {
                                    Text(
                                        "主责",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                } else if (multiAgentMode && isCollaborator) {
                                    Text(
                                        "+ 协同",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                } else if (multiAgentMode) {
                                    TextButton(
                                        onClick = {
                                            extraParticipantIds = extraParticipantIds - agent.id
                                            selectedPrimaryId = agent.id
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    ) {
                                        Text("设为主责", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (templates.isNotEmpty()) {
                Text("快速套用启动模板（可选）", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (tpl in templates) {
                        val isChosen = selectedTemplateId == tpl.id
                        FilterChip(
                            selected = isChosen,
                            onClick = {
                                if (isChosen) {
                                    selectedTemplateId = null
                                } else {
                                    selectedTemplateId = tpl.id
                                    initialPrompt = tpl.promptTemplate
                                    if (customTitle.isBlank()) customTitle = tpl.title
                                    tpl.recommendedAgentId?.takeIf { id -> agents.any { it.id == id } }?.let {
                                        selectedPrimaryId = it
                                    }
                                }
                            },
                            label = { Text(tpl.title) },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = customTitle,
                onValueChange = { customTitle = it },
                singleLine = true,
                placeholder = { Text("会话标题（留空将自动按首句或成员生成）") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = initialPrompt,
                onValueChange = { initialPrompt = it },
                maxLines = 4,
                placeholder = { Text("首条任务说明（可留空先进入空会话挑模板）…") },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("取消")
                }
                Button(
                    onClick = {
                        val primaryAgent = agents.firstOrNull { it.id == selectedPrimaryId }
                        val allIds = buildList {
                            add(selectedPrimaryId.trim())
                            if (multiAgentMode) addAll(extraParticipantIds)
                        }.distinct()
                        onCreateRequested(
                            selectedPrimaryId.trim(),
                            primaryAgent?.name,
                            allIds,
                            customTitle.trim().takeIf { it.isNotEmpty() },
                            selectedTemplateId,
                            initialPrompt.trim().takeIf { it.isNotEmpty() },
                        )
                    },
                    enabled = selectedPrimaryId.isNotBlank() && !isSubmitting,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (isHostReachable) "立即创建" else "离线创建并入队")
                }
            }
        }
    }
}

/**
 * Starter template quick-fill cards shown inside an empty conversation (`INV-2270`).
 */
@Composable
fun EmptyConversationStarterTemplatesView(
    templates: List<StarterTemplateSummary>,
    onSelectTemplate: (StarterTemplateSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("从常用任务模板快速开始", style = MaterialTheme.typography.titleSmall)
        Text(
            "点选下方卡片将结构化提示词填入输入框，也可输入 / 唤起技能目录或 @ 提及协同 Agent。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for (tpl in templates) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectTemplate(tpl) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tpl.title, style = MaterialTheme.typography.titleSmall)
                        tpl.category?.takeIf { it.isNotBlank() }?.let { cat ->
                            Text(
                                cat,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    tpl.summary?.takeIf { it.isNotBlank() }?.let { desc ->
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Contextual `@Agent` mention and `/` slash skill suggestion bar docked directly above the
 * message composer whenever [ComposerLexicalTriggerParser.detect] finds an active token (`INV-2270`).
 */
@Composable
fun ComposerAutocompleteSuggestionBar(
    trigger: ComposerLexicalTrigger,
    agents: List<AgentDesktopSummary>,
    skills: List<SlashSkillSummary>,
    onPickAgent: (AgentDesktopSummary) -> Unit,
    onPickSkill: (SlashSkillSummary) -> Unit,
) {
    when (trigger.kind) {
        ComposerLexicalTriggerKind.AGENT_MENTION -> {
            val matches = remember(agents, trigger.query) {
                ComposerLexicalTriggerParser.filterAgents(agents, trigger.query)
            }
            if (matches.isEmpty()) return
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "提及协同 Agent (@${trigger.query})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (agent in matches) {
                            AssistChip(
                                onClick = { onPickAgent(agent) },
                                label = {
                                    val roleTag = agent.role?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                    Text("@${agent.name}$roleTag")
                                },
                            )
                        }
                    }
                }
            }
        }

        ComposerLexicalTriggerKind.SLASH_SKILL -> {
            val matches = remember(skills, trigger.query) {
                ComposerLexicalTriggerParser.filterSkills(skills, trigger.query)
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = if (matches.isEmpty()) {
                            "未找到匹配 /${trigger.query} 的技能（可直接发送斜杠指令）"
                        } else {
                            "可用斜杠技能 (${matches.size})"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (matches.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (skill in matches.take(6)) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onPickSkill(skill) },
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val cleanName = skill.name.removePrefix("/")
                                            val argHint = skill.argumentHint?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
                                            Text(
                                                "/$cleanName$argHint",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontFamily = FontFamily.Monospace,
                                            )
                                            skill.summary?.takeIf { it.isNotBlank() }?.let { desc ->
                                                Text(
                                                    desc,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                )
                                            }
                                        }
                                        Text(
                                            "填入",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Routine light-management panel (`GET /api/v1/routines` + `PATCH /api/v1/routines/{id}`, `INV-2270`):
 * Displays schedule, next run, last execution result, Spend Guard circuit-breaker badges, and
 * one-tap optimistic pause/resume (`enabled`) + notification mute toggles with automatic rollback.
 */
@Composable
fun RoutineLightManagementSection(
    connectionId: String,
    isOnline: Boolean,
    coordinator: LightManagementCoordinator,
    onAccessRemoved: (() -> Unit)? = null,
) {
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    var routines by remember(connectionId) { mutableStateOf(coordinator.readCachedRoutines()) }
    var busyRoutineId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember(connectionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(connectionId, isOnline) {
        routines = coordinator.readCachedRoutines()
        if (isOnline) {
            runCatching {
                routines = coordinator.refreshRoutines()
            }.onFailure { err ->
                if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                    onAccessRemoved?.invoke()
                }
            }
        }
    }

    fun mutateRoutine(routineId: String, enabled: Boolean? = null, muted: Boolean? = null) {
        busyRoutineId = routineId
        errorMessage = null
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        scope.launch {
            try {
                routines = coordinator.setRoutinePatchOptimistic(
                    routineId = routineId,
                    enabled = enabled,
                    muted = muted,
                    onOptimisticApplied = { optimistic -> routines = optimistic },
                )
            } catch (err: ApiEnvelopeException) {
                routines = coordinator.readCachedRoutines()
                if (err.envelope.isAccessRemoved) {
                    onAccessRemoved?.invoke()
                } else {
                    errorMessage = "更新定时任务失败（${err.envelope.code}）：${err.envelope.error}（已回滚开关状态）"
                }
            } catch (err: Throwable) {
                routines = coordinator.readCachedRoutines()
                errorMessage = err.message ?: "更新定时任务失败，已回滚本地状态"
            } finally {
                busyRoutineId = null
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("定时任务 Routine (${routines.size})", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "外出时可随时查看下次触发时间，或一键暂停异常刷屏任务",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isOnline) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                errorMessage = null
                                runCatching {
                                    routines = coordinator.refreshRoutines()
                                }.onFailure { err ->
                                    if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                                        onAccessRemoved?.invoke()
                                    } else {
                                        errorMessage = err.message
                                    }
                                }
                            }
                        }
                    ) {
                        Text("刷新")
                    }
                }
            }

            errorMessage?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (routines.isEmpty()) {
                Text(
                    "当前暂无可见的定时任务（Routine）。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                for (routine in routines) {
                    HorizontalDivider()
                    RoutineItemCardRow(
                        item = routine,
                        isOnline = isOnline,
                        isBusy = busyRoutineId == routine.id,
                        onToggleEnabled = { nextEnabled -> mutateRoutine(routine.id, enabled = nextEnabled) },
                        onToggleMuted = { nextMuted -> mutateRoutine(routine.id, muted = nextMuted) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutineItemCardRow(
    item: RoutineItemSummary,
    isOnline: Boolean,
    isBusy: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleMuted: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall)
                Text(
                    listOfNotNull(
                        item.agentName ?: item.agentId,
                        "计划：${item.schedule}",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = item.enabled,
                enabled = isOnline && !isBusy,
                onCheckedChange = onToggleEnabled,
            )
        }

        if (item.spendGuardPaused) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "⚠ 预算熔断已暂停（Spend Guard）" +
                        (item.spendGuardReason?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                item.nextRunAt?.takeIf { it.isNotBlank() }?.let { next ->
                    Text("下次触发：$next", style = MaterialTheme.typography.labelSmall)
                }
                item.lastStatus?.takeIf { it.isNotBlank() }?.let { status ->
                    val lastTime = item.lastRunAt?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    Text(
                        "上次结果：$status$lastTime",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            FilterChip(
                selected = item.muted,
                enabled = isOnline && !isBusy,
                onClick = { onToggleMuted(!item.muted) },
                label = { Text(if (item.muted) "已静音通知" else "静音通知") },
            )
        }
    }
}

package com.lumenopen.lumenbox.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.lumenopen.lumenbox.app.sync.OutboundSyncWorker
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.conversation.AttachmentMediaKind
import com.lumenopen.lumenbox.conversation.BoardSnapshot
import com.lumenopen.lumenbox.conversation.CommittedMessage
import com.lumenopen.lumenbox.conversation.ConversationAndBoardCoordinator
import com.lumenopen.lumenbox.conversation.ConversationSummary
import com.lumenopen.lumenbox.conversation.DeliverableMetadata
import com.lumenopen.lumenbox.conversation.DeliverablePreviewTicket
import com.lumenopen.lumenbox.conversation.LiveTurnDraft
import com.lumenopen.lumenbox.conversation.LocalUploadAttachment
import com.lumenopen.lumenbox.conversation.MarkdownBlock
import com.lumenopen.lumenbox.conversation.MarkdownBlockParser
import com.lumenopen.lumenbox.conversation.MessageRole
import com.lumenopen.lumenbox.conversation.ShowUiCard
import com.lumenopen.lumenbox.conversation.ToolRunStatus
import com.lumenopen.lumenbox.conversation.ToolTimelineEntry
import com.lumenopen.lumenbox.conversation.WorkItemSummary
import com.lumenopen.lumenbox.conversation.WorkStateKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Streaming conversations list + active conversation detail (`GET /conversations/{id}/stream`),
 * Markdown & code-block renderer, tool timeline, `ShowUI` card actions, `POST /stop`, and
 * idempotent Photo Picker / SAF file / voice upload composer (`docs/106 §4.2`, `docs/110 §2`, INV-2257).
 */
@Composable
fun ConversationsSection(
    connectionId: String,
    isOnline: Boolean,
    coordinator: ConversationAndBoardCoordinator,
    selectedConversationId: String?,
    onSelectConversationId: (String?) -> Unit,
    onOpenDeliverableId: (String) -> Unit,
    onAccessRemoved: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val liveTurnsMap by coordinator.liveTurns.collectAsState()

    var conversations by remember(connectionId) {
        mutableStateOf(coordinator.readCachedConversations())
    }
    var messages by remember(connectionId, selectedConversationId) {
        mutableStateOf(
            if (selectedConversationId != null) coordinator.readCachedMessages(selectedConversationId)
            else emptyList()
        )
    }
    var feedback by remember(connectionId, selectedConversationId) { mutableStateOf<String?>(null) }

    // Initial + online resume catch-up
    LaunchedEffect(connectionId, isOnline) {
        conversations = coordinator.readCachedConversations()
        if (isOnline) {
            runCatching {
                coordinator.flushPendingConversationsQueue()
                coordinator.syncDurableChanges()
                conversations = coordinator.refreshConversations()
            }.onFailure { err ->
                if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                    onAccessRemoved?.invoke()
                }
            }
        }
    }

    // Active conversation messages + transient live stream subscription
    LaunchedEffect(connectionId, selectedConversationId, isOnline) {
        val convId = selectedConversationId ?: return@LaunchedEffect
        messages = coordinator.readCachedMessages(convId)
        if (!isOnline) return@LaunchedEffect

        runCatching {
            messages = coordinator.refreshConversationMessages(convId)
        }.onFailure { err ->
            if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                onAccessRemoved?.invoke()
                return@LaunchedEffect
            }
        }

        // Subscribe to GET /api/v1/conversations/{id}/stream while this conversation stays open
        runCatching {
            coordinator.collectConversationStream(convId) {
                messages = coordinator.readCachedMessages(convId)
                conversations = coordinator.readCachedConversations()
            }
        }.onFailure { err ->
            if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                onAccessRemoved?.invoke()
            }
        }
    }

    if (selectedConversationId == null) {
        ConversationListCard(
            conversations = conversations,
            isOnline = isOnline,
            pendingPromptCount = coordinator.pendingPromptCount(),
            durableSeq = coordinator.currentDurableSeq(),
            feedback = feedback,
            onRefresh = {
                scope.launch {
                    feedback = null
                    runCatching {
                        coordinator.flushPendingConversationsQueue()
                        coordinator.syncDurableChanges()
                        conversations = coordinator.refreshConversations()
                    }.onFailure { err ->
                        if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                            onAccessRemoved?.invoke()
                        } else {
                            feedback = err.message
                        }
                    }
                }
            },
            onOpenConversation = { convId -> onSelectConversationId(convId) },
        )
    } else {
        val summary = conversations.firstOrNull { it.id == selectedConversationId }
        val activeLiveTurn = liveTurnsMap[selectedConversationId]
        StreamingConversationDetailCard(
            conversationId = selectedConversationId,
            summary = summary,
            messages = messages,
            liveTurn = activeLiveTurn,
            isOnline = isOnline,
            feedback = feedback,
            onBackToList = { onSelectConversationId(null) },
            onStopTurn = {
                scope.launch {
                    feedback = null
                    runCatching {
                        coordinator.stopConversationTurn(selectedConversationId)
                        conversations = coordinator.readCachedConversations()
                    }.onFailure { err ->
                        if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                            onAccessRemoved?.invoke()
                        } else {
                            feedback = err.message
                        }
                    }
                }
            },
            onCardAction = { cardId, actionId ->
                scope.launch {
                    feedback = null
                    runCatching {
                        coordinator.submitShowUiCardAction(
                            conversationId = selectedConversationId,
                            cardId = cardId,
                            actionId = actionId,
                        )
                    }.onFailure { err ->
                        if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                            onAccessRemoved?.invoke()
                        } else {
                            OutboundSyncWorker.enqueueForConnection(context, connectionId)
                            feedback = err.message
                        }
                    }
                }
            },
            onSendPrompt = { promptText, stagedFiles ->
                scope.launch {
                    feedback = null
                    try {
                        coordinator.sendMessageWithAttachments(
                            conversationId = selectedConversationId,
                            text = promptText,
                            attachments = stagedFiles,
                        )
                        messages = coordinator.readCachedMessages(selectedConversationId)
                        if (coordinator.pendingPromptCount() > 0) {
                            OutboundSyncWorker.enqueueForConnection(context, connectionId)
                        }
                    } catch (err: ApiEnvelopeException) {
                        if (err.envelope.isAccessRemoved) {
                            onAccessRemoved?.invoke()
                        } else {
                            feedback = "发送失败（${err.envelope.code}）：${err.envelope.error}"
                        }
                    } catch (err: Throwable) {
                        OutboundSyncWorker.enqueueForConnection(context, connectionId)
                        messages = coordinator.readCachedMessages(selectedConversationId)
                        feedback = err.message
                    }
                }
            },
            onOpenDeliverableId = onOpenDeliverableId,
        )
    }
}

@Composable
private fun ConversationListCard(
    conversations: List<ConversationSummary>,
    isOnline: Boolean,
    pendingPromptCount: Int,
    durableSeq: Long,
    feedback: String?,
    onRefresh: () -> Unit,
    onOpenConversation: (String) -> Unit,
) {
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
                    Text("对话会话 (${conversations.size})", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "持久账本水位 durableSeq=$durableSeq" +
                            if (pendingPromptCount > 0) " · 待发送离线队列 $pendingPromptCount 条" else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (isOnline) {
                    OutlinedButton(onClick = onRefresh) {
                        Text("增量同步")
                    }
                }
            }

            feedback?.let { msg ->
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            if (conversations.isEmpty()) {
                Text("暂无会话记录。", style = MaterialTheme.typography.bodyMedium)
            } else {
                for (conv in conversations) {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(conv.title, style = MaterialTheme.typography.titleSmall)
                                if (conv.running) {
                                    AssistChip(
                                        onClick = {},
                                        enabled = false,
                                        label = { Text("执行中") },
                                    )
                                }
                            }
                            Text(
                                listOfNotNull(conv.agentName ?: conv.agentId, conv.updatedAt).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            conv.lastMessagePreview?.takeIf { it.isNotBlank() }?.let { preview ->
                                Text(preview, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                            }
                        }
                        OutlinedButton(onClick = { onOpenConversation(conv.id) }) {
                            Text("打开")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StreamingConversationDetailCard(
    conversationId: String,
    summary: ConversationSummary?,
    messages: List<CommittedMessage>,
    liveTurn: LiveTurnDraft?,
    isOnline: Boolean,
    feedback: String?,
    onBackToList: () -> Unit,
    onStopTurn: () -> Unit,
    onCardAction: (cardId: String, actionId: String) -> Unit,
    onSendPrompt: (text: String, attachments: List<LocalUploadAttachment>) -> Unit,
    onOpenDeliverableId: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var inputDraft by remember(conversationId) { mutableStateOf("") }
    var stagedAttachments by remember(conversationId) { mutableStateOf<List<LocalUploadAttachment>>(emptyList()) }
    var localPickerError by remember(conversationId) { mutableStateOf<String?>(null) }

    // Voice recorder state
    var activeRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var activeRecordingFile by remember { mutableStateOf<File?>(null) }
    val isRecording = activeRecorder != null

    DisposableEffect(conversationId) {
        onDispose {
            runCatching { activeRecorder?.stop() }
            runCatching { activeRecorder?.release() }
            activeRecorder = null
            activeRecordingFile?.delete()
            activeRecordingFile = null
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                readUriIntoAttachment(context, uri, AttachmentMediaKind.IMAGE)
                    .onSuccess { stagedAttachments = stagedAttachments + it }
                    .onFailure { localPickerError = "读取图片失败：${it.message}" }
            }
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            scope.launch {
                readUriIntoAttachment(context, uri, null)
                    .onSuccess { stagedAttachments = stagedAttachments + it }
                    .onFailure { localPickerError = "读取文件失败：${it.message}" }
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            localPickerError = "需要麦克风权限以录制语音消息。"
        }
    }

    val isTurnActive = (liveTurn?.isRunning == true) || (summary?.running == true)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(summary?.title ?: "会话 $conversationId", style = MaterialTheme.typography.titleMedium)
                    summary?.let {
                        Text("Agent：${it.agentName ?: it.agentId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isTurnActive && isOnline) {
                        Button(onClick = onStopTurn) {
                            Text("停止 (Stop)")
                        }
                    }
                    OutlinedButton(onClick = onBackToList) {
                        Text("返回列表")
                    }
                }
            }

            val errText = feedback ?: localPickerError
            if (!errText.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = errText,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (messages.isEmpty() && liveTurn == null) {
                Text("暂无消息，发送文字、图片、文件或语音开始协作。", style = MaterialTheme.typography.bodyMedium)
            } else {
                for (msg in messages) {
                    HorizontalDivider()
                    CommittedMessageBubble(
                        message = msg,
                        onCardAction = onCardAction,
                        onOpenDeliverableId = onOpenDeliverableId,
                    )
                }
            }

            if (liveTurn != null) {
                HorizontalDivider()
                LiveTurnStreamingBubble(
                    draft = liveTurn,
                    onCardAction = onCardAction,
                )
            }

            HorizontalDivider()

            // Staged attachments strip
            if (stagedAttachments.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (att in stagedAttachments) {
                        AssistChip(
                            onClick = {
                                stagedAttachments = stagedAttachments.filterNot { it.localId == att.localId }
                            },
                            label = {
                                Text("附件：${att.fileName} (${formatByteSize(att.bytes.size.toLong())}) ×")
                            },
                        )
                    }
                }
            }

            // Attachment & voice controls
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        localPickerError = null
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                ) {
                    Text("+ 图片")
                }
                OutlinedButton(
                    onClick = {
                        localPickerError = null
                        documentPickerLauncher.launch(arrayOf("*/*"))
                    },
                ) {
                    Text("+ 文件")
                }
                OutlinedButton(
                    onClick = {
                        localPickerError = null
                        if (isRecording) {
                            val rec = activeRecorder
                            val outFile = activeRecordingFile
                            activeRecorder = null
                            activeRecordingFile = null
                            try {
                                rec?.stop()
                                rec?.release()
                                if (outFile != null && outFile.exists() && outFile.length() > 0L) {
                                    val voiceBytes = outFile.readBytes()
                                    stagedAttachments = stagedAttachments + LocalUploadAttachment(
                                        localId = "voice_${UUID.randomUUID()}",
                                        fileName = outFile.name,
                                        mimeType = "audio/mp4",
                                        kind = AttachmentMediaKind.VOICE,
                                        bytes = voiceBytes,
                                    )
                                }
                            } catch (err: Throwable) {
                                localPickerError = "完成录音失败：${err.message}"
                            } finally {
                                outFile?.delete()
                            }
                        } else {
                            val hasPerm = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!hasPerm) {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                startVoiceRecording(context)
                                    .onSuccess { (recorder, file) ->
                                        activeRecorder = recorder
                                        activeRecordingFile = file
                                    }
                                    .onFailure { err ->
                                        localPickerError = "启动录音失败：${err.message}"
                                    }
                            }
                        }
                    },
                ) {
                    Text(if (isRecording) "结束录音并附加" else "+ 语音")
                }
            }

            OutlinedTextField(
                value = inputDraft,
                onValueChange = { inputDraft = it },
                placeholder = { Text("输入指令或补充说明（断网将加密入队并幂等重发）…") },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    val prompt = inputDraft
                    val files = stagedAttachments
                    inputDraft = ""
                    stagedAttachments = emptyList()
                    onSendPrompt(prompt, files)
                },
                enabled = inputDraft.isNotBlank() || stagedAttachments.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isOnline) "发送" else "离线幂等入队（恢复网络自动发送）")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommittedMessageBubble(
    message: CommittedMessage,
    onCardAction: (String, String) -> Unit,
    onOpenDeliverableId: (String) -> Unit,
) {
    val roleLabel = when (val r = message.role) {
        is OpenEnum.Known -> when (r.value) {
            MessageRole.USER -> "我"
            MessageRole.ASSISTANT -> "Agent"
            MessageRole.SYSTEM -> "系统"
        }
        is OpenEnum.Other -> r.rawValue
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(roleLabel, style = MaterialTheme.typography.labelLarge)
            Text(message.createdAt, style = MaterialTheme.typography.labelSmall)
            if (message.pendingSend) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text("待幂等同步") },
                )
            }
        }

        message.thinking?.takeIf { it.isNotBlank() }?.let { thought ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("思考过程", style = MaterialTheme.typography.labelSmall)
                    Text(thought, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (message.toolCalls.isNotEmpty()) {
            ToolProgressTimeline(entries = message.toolCalls)
        }

        if (message.text.isNotBlank()) {
            MarkdownRichContent(markdown = message.text)
        }

        if (message.attachments.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (att in message.attachments) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = {
                            val sizeSuffix = att.sizeBytes?.let { " · ${formatByteSize(it)}" }.orEmpty()
                            Text("${att.name} (${att.kind}$sizeSuffix)")
                        },
                    )
                }
            }
        }

        for (card in message.cards) {
            ShowUiCardView(card = card, onCardAction = onCardAction)
        }

        if (message.deliverableIds.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (delivId in message.deliverableIds) {
                    OutlinedButton(onClick = { onOpenDeliverableId(delivId) }) {
                        Text("预览交付物 $delivId")
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveTurnStreamingBubble(
    draft: LiveTurnDraft,
    onCardAction: (String, String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (draft.isRunning) "Agent 实时流式输出中…" else "本轮已停止",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            if (draft.thinkingText.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("实时思考增量", style = MaterialTheme.typography.labelSmall)
                        Text(draft.thinkingText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (draft.toolCalls.isNotEmpty()) {
                ToolProgressTimeline(entries = draft.toolCalls)
            }

            if (draft.responseText.isNotBlank()) {
                MarkdownRichContent(markdown = draft.responseText)
            }

            for (card in draft.cards) {
                ShowUiCardView(card = card, onCardAction = onCardAction)
            }
        }
    }
}

@Composable
private fun ToolProgressTimeline(entries: List<ToolTimelineEntry>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (tool in entries) {
            val statusBadge = when (val st = tool.status) {
                is OpenEnum.Known -> when (st.value) {
                    ToolRunStatus.RUNNING -> "运行中"
                    ToolRunStatus.COMPLETED -> "已完成"
                    ToolRunStatus.FAILED -> "失败"
                    ToolRunStatus.AWAITING_APPROVAL -> "待审批"
                }
                is OpenEnum.Other -> st.rawValue
            }
            val summarySuffix = tool.summary?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
            Text(
                text = "[$statusBadge] 工具 ${tool.name}$summarySuffix",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShowUiCardView(
    card: ShowUiCard,
    onCardAction: (String, String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(card.title, style = MaterialTheme.typography.titleSmall)
            card.body?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            if (card.actions.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (action in card.actions) {
                        Button(onClick = { onCardAction(card.cardId, action.actionId) }) {
                            Text(action.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MarkdownRichContent(markdown: String) {
    val blocks = remember(markdown) { MarkdownBlockParser.parse(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(block.text, style = style)
                }

                is MarkdownBlock.Paragraph -> {
                    Text(block.text, style = MaterialTheme.typography.bodyMedium)
                }

                is MarkdownBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        for (item in block.items) {
                            Text("• $item", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                is MarkdownBlock.CodeFence -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            block.language?.let { lang ->
                                Text(
                                    lang,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            Text(
                                text = block.code,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Work board (`GET /api/v1/board` & `GET /api/v1/work`) and short-lived expiring link deliverable
 * preview (`GET /api/v1/deliverables/{id}`, `docs/106 §4.2`, INV-2257).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkBoardAndDeliverablesSection(
    connectionId: String,
    isOnline: Boolean,
    coordinator: ConversationAndBoardCoordinator,
    requestedDeliverableId: String?,
    onConsumeRequestedDeliverableId: () -> Unit,
    onJumpToConversation: (String) -> Unit,
    onAccessRemoved: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var board by remember(connectionId) { mutableStateOf(coordinator.readCachedBoard()) }
    var workItems by remember(connectionId) { mutableStateOf(coordinator.readCachedWorkItems()) }
    var deliverables by remember(connectionId) { mutableStateOf(coordinator.readCachedDeliverables()) }
    var waitsOnMeOnly by remember(connectionId) { mutableStateOf(false) }
    var activePreview by remember { mutableStateOf<DeliverablePreviewTicket?>(null) }
    var busyDeliverableId by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember(connectionId) { mutableStateOf<String?>(null) }

    fun openDeliverablePreview(deliverableId: String, forceRefresh: Boolean = false) {
        busyDeliverableId = deliverableId
        errorMessage = null
        scope.launch {
            try {
                activePreview = coordinator.resolveDeliverablePreview(deliverableId, forceRefresh = forceRefresh)
            } catch (err: ApiEnvelopeException) {
                if (err.envelope.isAccessRemoved) {
                    onAccessRemoved?.invoke()
                } else {
                    errorMessage = "打开交付物失败（${err.envelope.code}）：${err.envelope.error}"
                }
            } catch (err: Throwable) {
                errorMessage = err.message ?: "无法获取交付物短时签名链接"
            } finally {
                busyDeliverableId = null
            }
        }
    }

    LaunchedEffect(connectionId, isOnline) {
        if (isOnline) {
            runCatching {
                board = coordinator.refreshBoard()
                workItems = coordinator.refreshWorkItems()
                deliverables = coordinator.readCachedDeliverables()
            }.onFailure { err ->
                if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                    onAccessRemoved?.invoke()
                }
            }
        }
    }

    LaunchedEffect(requestedDeliverableId) {
        val targetId = requestedDeliverableId
        if (!targetId.isNullOrBlank()) {
            onConsumeRequestedDeliverableId()
            openDeliverablePreview(targetId)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                    Text("工作看板 (/api/v1/board)", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = waitsOnMeOnly,
                            onClick = { waitsOnMeOnly = !waitsOnMeOnly },
                            label = { Text("仅看等我处理") },
                        )
                        if (isOnline) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        errorMessage = null
                                        runCatching {
                                            coordinator.syncDurableChanges()
                                            board = coordinator.refreshBoard()
                                            workItems = coordinator.refreshWorkItems(
                                                waitsOnMe = if (waitsOnMeOnly) true else null
                                            )
                                            deliverables = coordinator.readCachedDeliverables()
                                        }.onFailure { err ->
                                            if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                                                onAccessRemoved?.invoke()
                                            } else {
                                                errorMessage = err.message
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text("刷新")
                            }
                        }
                    }
                }

                errorMessage?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (board.columns.isNotEmpty()) {
                    for (col in board.columns) {
                        val visibleCards = if (waitsOnMeOnly) col.cards.filter { it.waitsOnMe } else col.cards
                        HorizontalDivider()
                        Text("${col.title} (${visibleCards.size})", style = MaterialTheme.typography.titleSmall)
                        for (card in visibleCards) {
                            WorkCardRow(
                                item = card,
                                busyDeliverableId = busyDeliverableId,
                                onJumpToConversation = onJumpToConversation,
                                onPreviewDeliverable = { openDeliverablePreview(it) },
                            )
                        }
                    }
                } else {
                    val visibleWork = if (waitsOnMeOnly) workItems.filter { it.waitsOnMe } else workItems
                    if (visibleWork.isEmpty()) {
                        Text("暂无进行中或已完成工作卡片。", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        for (item in visibleWork) {
                            HorizontalDivider()
                            WorkCardRow(
                                item = item,
                                busyDeliverableId = busyDeliverableId,
                                onJumpToConversation = onJumpToConversation,
                                onPreviewDeliverable = { openDeliverablePreview(it) },
                            )
                        }
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("交付物列表 (${deliverables.size})", style = MaterialTheme.typography.titleMedium)
                if (deliverables.isEmpty()) {
                    Text("从对话或看板中点击交付物，或同步增量后在此查看。", style = MaterialTheme.typography.bodySmall)
                } else {
                    for (deliv in deliverables) {
                        HorizontalDivider()
                        DeliverableMetadataRow(
                            item = deliv,
                            busy = busyDeliverableId == deliv.id,
                            isOnline = isOnline,
                            onPreview = { openDeliverablePreview(deliv.id) },
                        )
                    }
                }
            }
        }
    }

    activePreview?.let { ticket ->
        DeliverablePreviewDialog(
            ticket = ticket,
            onRefreshTicket = { openDeliverablePreview(ticket.id, forceRefresh = true) },
            onOpenExternalUrl = { url ->
                runCatching {
                    CustomTabsIntent.Builder()
                        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                        .build()
                        .launchUrl(context, Uri.parse(url))
                }.onFailure { err ->
                    errorMessage = "打开预览链接失败：${err.message}"
                }
            },
            onDismiss = { activePreview = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkCardRow(
    item: WorkItemSummary,
    busyDeliverableId: String?,
    onJumpToConversation: (String) -> Unit,
    onPreviewDeliverable: (String) -> Unit,
) {
    val stateLabel = when (val st = item.state) {
        is OpenEnum.Known -> when (st.value) {
            WorkStateKind.READY -> "待启动"
            WorkStateKind.IN_PROGRESS -> "进行中"
            WorkStateKind.NEEDS_YOU -> "待你确认"
            WorkStateKind.IN_REVIEW -> "待验收"
            WorkStateKind.DONE -> "已完成"
            WorkStateKind.BLOCKED -> "受阻"
        }
        is OpenEnum.Other -> st.rawValue
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("${item.title} · $stateLabel", style = MaterialTheme.typography.bodyMedium)
            item.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item.conversationId?.takeIf { it.isNotBlank() }?.let { convId ->
                    OutlinedButton(onClick = { onJumpToConversation(convId) }) {
                        Text("进入会话")
                    }
                }
                for (delivId in item.deliverableIds) {
                    Button(
                        onClick = { onPreviewDeliverable(delivId) },
                        enabled = busyDeliverableId != delivId,
                    ) {
                        Text("查看交付物")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeliverableMetadataRow(
    item: DeliverableMetadata,
    busy: Boolean,
    isOnline: Boolean,
    onPreview: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium)
            val sizeText = item.sizeBytes?.let { formatByteSize(it) }
            Text(
                listOfNotNull(item.mimeType, sizeText, item.createdAt).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = onPreview, enabled = !busy && isOnline) {
            Text("短链预览")
        }
    }
}

@Composable
private fun DeliverablePreviewDialog(
    ticket: DeliverablePreviewTicket,
    onRefreshTicket: () -> Unit,
    onOpenExternalUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(ticket.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "类型：${ticket.mimeType} · 短时链接有效期至：${ticket.expiresAt}",
                    style = MaterialTheme.typography.bodySmall,
                )
                ticket.inlinePreviewText?.takeIf { it.isNotBlank() }?.let { preview ->
                    HorizontalDivider()
                    MarkdownRichContent(markdown = preview)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onRefreshTicket,
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text("续期链接")
                    }
                    Button(
                        onClick = { onOpenExternalUrl(ticket.url) },
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text("安全打开")
                    }
                    OutlinedButton(onClick = onDismiss) {
                        Text("关闭")
                    }
                }
            }
        }
    }
}

private suspend fun readUriIntoAttachment(
    context: Context,
    uri: Uri,
    explicitKind: AttachmentMediaKind?,
): Result<LocalUploadAttachment> = withContext(Dispatchers.IO) {
    runCatching {
        val resolver = context.contentResolver
        val mimeType = resolver.getType(uri) ?: when (explicitKind) {
            AttachmentMediaKind.IMAGE -> "image/jpeg"
            AttachmentMediaKind.VOICE -> "audio/mp4"
            else -> "application/octet-stream"
        }
        var displayName = "attachment_${System.currentTimeMillis()}"
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
            }
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法打开所选内容流")
        LocalUploadAttachment(
            localId = "att_${UUID.randomUUID()}",
            fileName = displayName,
            mimeType = mimeType,
            kind = explicitKind ?: AttachmentMediaKind.infer(null, mimeType),
            bytes = bytes,
        )
    }
}

private fun startVoiceRecording(context: Context): Result<Pair<MediaRecorder, File>> = runCatching {
    val outFile = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    try {
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(64_000)
        recorder.setAudioSamplingRate(16_000)
        recorder.setOutputFile(outFile.absolutePath)
        recorder.prepare()
        recorder.start()
        recorder to outFile
    } catch (err: Throwable) {
        runCatching { recorder.release() }
        outFile.delete()
        throw err
    }
}

private fun formatByteSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

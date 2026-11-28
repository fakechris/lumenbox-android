package com.lumenopen.lumenbox.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.lumenopen.lumenbox.app.sync.OutboundSyncWorker
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.conversation.AttachmentMediaKind
import com.lumenopen.lumenbox.conversation.BoardSnapshot
import com.lumenopen.lumenbox.conversation.ChatScrollAnchorMachine
import com.lumenopen.lumenbox.conversation.ChatScrollAnchorState
import com.lumenopen.lumenbox.conversation.CodeTokenKind
import com.lumenopen.lumenbox.conversation.CommittedMessage
import com.lumenopen.lumenbox.conversation.ConversationAndBoardCoordinator
import com.lumenopen.lumenbox.conversation.ConversationHapticMoment
import com.lumenopen.lumenbox.conversation.ConversationHapticTriggerDetector
import com.lumenopen.lumenbox.conversation.ConversationKeywordSearch
import com.lumenopen.lumenbox.conversation.ConversationNoiseReducer
import com.lumenopen.lumenbox.conversation.ConversationSummary
import com.lumenopen.lumenbox.conversation.DeliverableMetadata
import com.lumenopen.lumenbox.conversation.DeliverablePreviewTicket
import com.lumenopen.lumenbox.conversation.HapticObservationSnapshot
import com.lumenopen.lumenbox.conversation.HonestWorkingIndicatorState
import com.lumenopen.lumenbox.conversation.HonestWorkingPhase
import com.lumenopen.lumenbox.conversation.HonestWorkingStateMachine
import com.lumenopen.lumenbox.conversation.LiveTurnDraft
import com.lumenopen.lumenbox.conversation.LocalUploadAttachment
import com.lumenopen.lumenbox.conversation.MarkdownBlock
import com.lumenopen.lumenbox.conversation.MarkdownBlockParser
import com.lumenopen.lumenbox.conversation.MessageRole
import com.lumenopen.lumenbox.conversation.ProcessDisclosurePill
import com.lumenopen.lumenbox.conversation.ShowUiCard
import com.lumenopen.lumenbox.conversation.ToolRunStatus
import com.lumenopen.lumenbox.conversation.ToolTimelineEntry
import com.lumenopen.lumenbox.conversation.VirtualizedChatTimelineItem
import com.lumenopen.lumenbox.conversation.WorkItemSummary
import com.lumenopen.lumenbox.conversation.WorkStateKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Virtualized conversation directory (`LazyColumn`) + dedicated full-screen immersive conversation
 * screen (`INV-2257`, `INV-2271`).
 *
 * - Virtualized `LazyColumn` with stable `key` and `contentType`, fixed-aspect-ratio placeholders,
 *   and smart auto-follow that pauses when the user scrolls up to inspect history.
 * - Single-line progressive-disclosure capsule (`ProcessDisclosurePill`) collapsing `thinking`
 *   and consecutive tool runs by default, expanding on tap.
 * - Syntax-highlighted monospace code blocks with horizontal scroll and one-tap copy + haptics.
 * - Honest working indicator gated on `isHostReachable && isTurnRunning` with instant offline
 *   cut-off and `2.5s` completion hold, plus in-conversation keyword search.
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
    modifier: Modifier = Modifier,
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
        ConversationDirectoryScreen(
            conversations = conversations,
            isHostReachable = isOnline,
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
            modifier = modifier,
        )
    } else {
        val summary = conversations.firstOrNull { it.id == selectedConversationId }
        val activeLiveTurn = liveTurnsMap[selectedConversationId]
        ImmersiveStreamingConversationScreen(
            conversationId = selectedConversationId,
            summary = summary,
            messages = messages,
            liveTurn = activeLiveTurn,
            isHostReachable = isOnline,
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
            modifier = modifier,
        )
    }
}

@Composable
private fun ConversationDirectoryScreen(
    conversations: List<ConversationSummary>,
    isHostReachable: Boolean,
    pendingPromptCount: Int,
    durableSeq: Long,
    feedback: String?,
    onRefresh: () -> Unit,
    onOpenConversation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("消息与会话 (${conversations.size})", style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("账本水位 #$durableSeq")
                        if (!isHostReachable) append(" · 离线只读/幂等入队")
                        if (pendingPromptCount > 0) append(" · 待同步 $pendingPromptCount 条")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isHostReachable) {
                OutlinedButton(onClick = onRefresh) {
                    Text("同步")
                }
            }
        }

        feedback?.let { msg ->
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "暂无会话记录。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(
                    items = conversations,
                    key = { it.id },
                    contentType = { "conversation_summary_row" },
                ) { conv ->
                    val honestRunning = isHostReachable && conv.running
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenConversation(conv.id) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    conv.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                if (honestRunning) {
                                    BreathingStatusChip(label = "执行中")
                                } else if (conv.running && !isHostReachable) {
                                    AssistChip(
                                        onClick = {},
                                        enabled = false,
                                        label = { Text("已断连暂停") },
                                    )
                                }
                            }
                            Text(
                                listOfNotNull(conv.agentName ?: conv.agentId, conv.updatedAt).joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            conv.lastMessagePreview?.takeIf { it.isNotBlank() }?.let { preview ->
                                Text(
                                    preview,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 2,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImmersiveStreamingConversationScreen(
    conversationId: String,
    summary: ConversationSummary?,
    messages: List<CommittedMessage>,
    liveTurn: LiveTurnDraft?,
    isHostReachable: Boolean,
    feedback: String?,
    onBackToList: () -> Unit,
    onStopTurn: () -> Unit,
    onCardAction: (cardId: String, actionId: String) -> Unit,
    onSendPrompt: (text: String, attachments: List<LocalUploadAttachment>) -> Unit,
    onOpenDeliverableId: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBackToList)

    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var inputDraft by rememberSaveable(conversationId) { mutableStateOf("") }
    var stagedAttachments by remember(conversationId) { mutableStateOf<List<LocalUploadAttachment>>(emptyList()) }
    var localPickerError by remember(conversationId) { mutableStateOf<String?>(null) }

    // In-conversation keyword search state
    var isSearchOpen by rememberSaveable(conversationId) { mutableStateOf(false) }
    var searchQuery by rememberSaveable(conversationId) { mutableStateOf("") }
    var activeMatchCursor by rememberSaveable(conversationId) { mutableIntStateOf(0) }

    val searchMatches = remember(messages, searchQuery) {
        ConversationKeywordSearch.search(messages, searchQuery)
    }

    // Honest working-state machine (gated on isHostReachable && isTurnRunning + 2.5s completion hold)
    val isTurnRunning = (liveTurn?.isRunning == true) || (summary?.running == true)
    var workingState by remember(conversationId) {
        mutableStateOf(
            HonestWorkingStateMachine.transition(
                previous = HonestWorkingIndicatorState(),
                isHostReachable = isHostReachable,
                isTurnRunning = isTurnRunning,
                nowEpochMillis = System.currentTimeMillis(),
            )
        )
    }

    LaunchedEffect(isHostReachable, isTurnRunning) {
        val now = System.currentTimeMillis()
        val next = HonestWorkingStateMachine.transition(
            previous = workingState,
            isHostReachable = isHostReachable,
            isTurnRunning = isTurnRunning,
            nowEpochMillis = now,
        )
        workingState = next
        val remaining = HonestWorkingStateMachine.remainingHoldMillis(next, now)
        if (remaining > 0L) {
            delay(remaining)
            workingState = HonestWorkingStateMachine.transition(
                previous = workingState,
                isHostReachable = isHostReachable,
                isTurnRunning = isTurnRunning,
                nowEpochMillis = System.currentTimeMillis(),
            )
        }
    }

    // Subtle haptics on first streaming token arrival
    var hapticSnapshot by remember(conversationId) { mutableStateOf(HapticObservationSnapshot()) }
    LaunchedEffect(liveTurn?.turnId, liveTurn?.responseText?.isNotEmpty(), liveTurn?.thinkingText?.isNotEmpty(), liveTurn?.toolCalls?.size, isHostReachable) {
        val (nextSnap, moments) = ConversationHapticTriggerDetector.evaluate(
            previous = hapticSnapshot,
            liveTurn = liveTurn,
            currentInboxCount = hapticSnapshot.inboxCount,
            isHostReachable = isHostReachable,
        )
        hapticSnapshot = nextSnap
        if (ConversationHapticMoment.FIRST_STREAMING_TOKEN in moments) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    // Virtualized timeline projection
    val timelineItems = remember(messages, liveTurn, isHostReachable) {
        ConversationNoiseReducer.projectTimeline(
            messages = messages,
            liveTurn = liveTurn,
            isHostReachable = isHostReachable,
        )
    }

    // Smart auto-follow scroll anchor state machine
    var scrollAnchor by remember(conversationId) {
        mutableStateOf(ChatScrollAnchorState(isPinnedToBottom = true, lastObservedMessageCount = timelineItems.size))
    }

    val isViewportAtBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            val total = layout.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisible = layout.visibleItemsInfo.lastOrNull()
                lastVisible != null && lastVisible.index >= total - 1
            }
        }
    }

    LaunchedEffect(isViewportAtBottom, listState.isScrollInProgress) {
        if (listState.isScrollInProgress || isViewportAtBottom) {
            scrollAnchor = ChatScrollAnchorMachine.onViewportPositionChanged(
                previous = scrollAnchor,
                isAtBottom = isViewportAtBottom,
            )
        }
    }

    LaunchedEffect(timelineItems.size) {
        val (nextAnchor, shouldScroll) = ChatScrollAnchorMachine.onMessageCountUpdated(
            previous = scrollAnchor,
            newMessageCount = timelineItems.size,
        )
        scrollAnchor = nextAnchor
        if (shouldScroll && timelineItems.isNotEmpty() && !isSearchOpen) {
            listState.animateScrollToItem(timelineItems.lastIndex)
        }
    }

    LaunchedEffect(liveTurn?.responseText?.length, liveTurn?.toolCalls?.size) {
        if (liveTurn != null &&
            ChatScrollAnchorMachine.onStreamingDeltaArrived(scrollAnchor) &&
            timelineItems.isNotEmpty() &&
            !isSearchOpen
        ) {
            listState.scrollToItem(timelineItems.lastIndex)
        }
    }

    // Voice recorder lifecycle
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

    Column(modifier = modifier.fillMaxSize()) {
        // 1. Immersive conversation top header + Honest Working Indicator
        Surface(
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onBackToList) {
                            Text("← 返回")
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = summary?.title ?: "会话 $conversationId",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                            )
                            HonestWorkingSubtitleRow(
                                agentLabel = summary?.agentName ?: summary?.agentId ?: "Agent",
                                indicatorState = workingState,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                isSearchOpen = !isSearchOpen
                                if (!isSearchOpen) searchQuery = ""
                            }
                        ) {
                            Text(if (isSearchOpen) "收起搜索" else "搜索")
                        }
                        if (workingState.isBreathingWorking) {
                            OutlinedButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onStopTurn()
                                }
                            ) {
                                Text("停止")
                            }
                        }
                    }
                }

                // Honest offline cut-off banner (replaces fake spinning indicator on disconnect / Mac sleep)
                AnimatedVisibility(visible = workingState.showOfflineBanner) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "连接已断开或宿主机休眠，实时工作态已暂停；当前浏览本地加密历史，你发送的消息将加密入队并在重连后幂等重发。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }

                // In-conversation keyword search bar
                AnimatedVisibility(visible = isSearchOpen) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                activeMatchCursor = 0
                            },
                            singleLine = true,
                            placeholder = { Text("搜索当前会话关键词、工具输出或卡片…") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (searchQuery.isNotBlank()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = if (searchMatches.isEmpty()) {
                                        "未找到匹配项"
                                    } else {
                                        "找到 ${searchMatches.size} 条匹配消息（当前 ${activeMatchCursor + 1}/${searchMatches.size}）"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (searchMatches.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        TextButton(
                                            onClick = {
                                                val prevIdx = if (activeMatchCursor > 0) activeMatchCursor - 1 else searchMatches.lastIndex
                                                activeMatchCursor = prevIdx
                                                scope.launch {
                                                    listState.animateScrollToItem(searchMatches[prevIdx].timelineIndex)
                                                }
                                            }
                                        ) {
                                            Text("上一处")
                                        }
                                        TextButton(
                                            onClick = {
                                                val nextIdx = (activeMatchCursor + 1) % searchMatches.size
                                                activeMatchCursor = nextIdx
                                                scope.launch {
                                                    listState.animateScrollToItem(searchMatches[nextIdx].timelineIndex)
                                                }
                                            }
                                        ) {
                                            Text("下一处")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()

        // Error feedback banner if any
        val errText = feedback ?: localPickerError
        if (!errText.isNullOrBlank()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    text = errText,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }

        // 2. Virtualized LazyColumn Message Stream + Floating "Jump to Bottom" Pill
        val highlightedMessageId = searchMatches.getOrNull(activeMatchCursor)?.messageId
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            if (timelineItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "发送文字、图片、文件或语音开始协作。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        items = timelineItems,
                        key = { _, item -> item.stableKey },
                        contentType = { _, item -> item.contentType },
                    ) { _, item ->
                        when (item) {
                            is VirtualizedChatTimelineItem.CommittedTurnItem -> {
                                CommittedMessageBubble(
                                    item = item,
                                    searchHighlightQuery = searchQuery.trim().takeIf { isSearchOpen && it.isNotEmpty() },
                                    isFocusedSearchMatch = item.message.id == highlightedMessageId,
                                    onCopyCode = { code ->
                                        copyTextToClipboard(context, code)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onCardAction = { cardId, actionId ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onCardAction(cardId, actionId)
                                    },
                                    onOpenDeliverableId = onOpenDeliverableId,
                                )
                            }

                            is VirtualizedChatTimelineItem.LiveStreamingTurnItem -> {
                                LiveTurnStreamingBubble(
                                    item = item,
                                    onCopyCode = { code ->
                                        copyTextToClipboard(context, code)
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onCardAction = { cardId, actionId ->
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        onCardAction(cardId, actionId)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (scrollAnchor.showJumpToBottomPill && timelineItems.isNotEmpty()) {
                Button(
                    onClick = {
                        scrollAnchor = ChatScrollAnchorMachine.onUserJumpToBottom(
                            previous = scrollAnchor,
                            currentMessageCount = timelineItems.size,
                        )
                        scope.launch {
                            listState.animateScrollToItem(timelineItems.lastIndex)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                ) {
                    val unreadSuffix = if (scrollAnchor.unreadCountWhilePaused > 0) {
                        " · ${scrollAnchor.unreadCountWhilePaused} 条新消息"
                    } else {
                        ""
                    }
                    Text("↓ 回到底部$unreadSuffix")
                }
            }
        }

        HorizontalDivider()

        // 3. Docked Bottom Composer Bar
        Surface(
            tonalElevation = 3.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
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
                                    Text("${att.fileName} (${formatByteSize(att.bytes.size.toLong())}) ×")
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AssistChip(
                        onClick = {
                            localPickerError = null
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        label = { Text("+ 图片") },
                    )
                    AssistChip(
                        onClick = {
                            localPickerError = null
                            documentPickerLauncher.launch(arrayOf("*/*"))
                        },
                        label = { Text("+ 文件") },
                    )
                    AssistChip(
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
                        label = { Text(if (isRecording) "● 完成录音" else "+ 语音") },
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = inputDraft,
                        onValueChange = { inputDraft = it },
                        placeholder = {
                            Text(if (isHostReachable) "发消息给 Agent…" else "离线输入（重连自动幂等发送）…")
                        },
                        maxLines = 5,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = {
                            val prompt = inputDraft
                            val files = stagedAttachments
                            inputDraft = ""
                            stagedAttachments = emptyList()
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scrollAnchor = ChatScrollAnchorMachine.onUserJumpToBottom(
                                previous = scrollAnchor,
                                currentMessageCount = timelineItems.size + 1,
                            )
                            onSendPrompt(prompt, files)
                        },
                        enabled = inputDraft.isNotBlank() || stagedAttachments.isNotEmpty(),
                    ) {
                        Text(if (isHostReachable) "发送" else "入队")
                    }
                }
            }
        }
    }
}

@Composable
private fun HonestWorkingSubtitleRow(
    agentLabel: String,
    indicatorState: HonestWorkingIndicatorState,
) {
    when (indicatorState.phase) {
        HonestWorkingPhase.WORKING -> {
            val infinite = rememberInfiniteTransition(label = "working_pulse")
            val pulseAlpha by infinite.animateFloat(
                initialValue = 0.4f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 850),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "pulse_alpha",
            )
            Text(
                text = "● $agentLabel · 正在执行中",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.alpha(pulseAlpha),
            )
        }

        HonestWorkingPhase.RECENTLY_COMPLETED -> {
            Text(
                text = "✓ $agentLabel · 刚刚完成",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }

        HonestWorkingPhase.OFFLINE -> {
            Text(
                text = "○ $agentLabel · 离线缓存模式",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HonestWorkingPhase.IDLE_ONLINE -> {
            Text(
                text = "● $agentLabel · 在线就绪",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BreathingStatusChip(label: String) {
    val infinite = rememberInfiniteTransition(label = "chip_pulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "chip_alpha",
    )
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.alpha(pulseAlpha),
    ) {
        Text(
            text = "● $label",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Single-line low-noise progressive-disclosure capsule (`INV-2271`):
 * Collapses `thinking` reasoning text and consecutive tool calls into a compact pill by default,
 * expanding on tap to reveal the full reasoning and tool timeline.
 */
@Composable
private fun ExpandableProcessDisclosurePillView(
    pill: ProcessDisclosurePill,
) {
    var expanded by rememberSaveable(pill.id) { mutableStateOf(pill.defaultExpanded) }

    val containerColor = when {
        pill.isAwaitingApproval -> MaterialTheme.colorScheme.tertiaryContainer
        pill.hasFailure -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    }
    val contentColor = when {
        pill.isAwaitingApproval -> MaterialTheme.colorScheme.onTertiaryContainer
        pill.hasFailure -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val pulseAlpha = if (pill.isLiveBreathing) {
        val infinite = rememberInfiniteTransition(label = "pill_breathing")
        val anim by infinite.animateFloat(
            initialValue = 0.55f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pill_alpha",
        )
        anim
    } else {
        1.0f
    }

    Surface(
        color = containerColor,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = pill.hasDetails) { expanded = !expanded },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(pulseAlpha),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = pill.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起 ▾" else "详情 ▸",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HorizontalDivider()
                    pill.thinkingText?.takeIf { it.isNotBlank() }?.let { thought ->
                        Text(
                            text = thought,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor,
                        )
                    }
                    if (pill.toolCalls.isNotEmpty()) {
                        ToolProgressTimeline(entries = pill.toolCalls)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommittedMessageBubble(
    item: VirtualizedChatTimelineItem.CommittedTurnItem,
    searchHighlightQuery: String?,
    isFocusedSearchMatch: Boolean,
    onCopyCode: (String) -> Unit,
    onCardAction: (String, String) -> Unit,
    onOpenDeliverableId: (String) -> Unit,
) {
    val message = item.message
    val isUser = (message.role as? OpenEnum.Known)?.value == MessageRole.USER
    val roleLabel = when (val r = message.role) {
        is OpenEnum.Known -> when (r.value) {
            MessageRole.USER -> "我"
            MessageRole.ASSISTANT -> "Agent"
            MessageRole.SYSTEM -> "系统"
        }
        is OpenEnum.Other -> r.rawValue
    }

    val bubbleContainer = when {
        isFocusedSearchMatch -> MaterialTheme.colorScheme.tertiaryContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            color = bubbleContainer,
            shape = RoundedCornerShape(14.dp),
            modifier = if (isUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(roleLabel, style = MaterialTheme.typography.labelLarge)
                    Text(
                        message.createdAt,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (message.pendingSend) {
                        Text(
                            "待同步",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                // Single-line collapsed pill for thinking + tool runs (noise reduction)
                item.processPill?.let { pill ->
                    ExpandableProcessDisclosurePillView(pill = pill)
                }

                if (message.text.isNotBlank()) {
                    MarkdownRichContent(
                        markdown = message.text,
                        highlightQuery = searchHighlightQuery,
                        onCopyCode = onCopyCode,
                    )
                }

                // Fixed-aspect-ratio attachment cards to prevent layout shift
                if (message.attachments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (att in message.attachments) {
                            val kind = AttachmentMediaKind.infer(att.kind, att.mimeType)
                            val ratio = item.fixedAspectSkeletonRatios["att:${att.id}"]
                                ?: ConversationNoiseReducer.defaultAspectRatioForAttachment(kind)
                            FixedAspectAttachmentFrame(
                                name = att.name,
                                kind = kind,
                                sizeBytes = att.sizeBytes,
                                aspectRatio = ratio,
                            )
                        }
                    }
                }

                for (card in message.cards) {
                    ShowUiCardView(card = card, onCardAction = onCardAction)
                }

                // Inline deliverable cards with fixed-height preview frame
                if (message.deliverableIds.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (delivId in message.deliverableIds) {
                            InlineDeliverableCard(
                                deliverableId = delivId,
                                onOpenDeliverableId = onOpenDeliverableId,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FixedAspectAttachmentFrame(
    name: String,
    kind: AttachmentMediaKind,
    sizeBytes: Long?,
    aspectRatio: Float,
) {
    val sizeSuffix = sizeBytes?.let { " · ${formatByteSize(it)}" }.orEmpty()
    if (kind == AttachmentMediaKind.IMAGE) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false),
        ) {
            Box(
                modifier = Modifier.padding(12.dp),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    text = "图片附件 · $name$sizeSuffix",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "${if (kind == AttachmentMediaKind.VOICE) "语音" else "文件"} · $name$sizeSuffix",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun InlineDeliverableCard(
    deliverableId: String,
    onOpenDeliverableId: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDeliverableId(deliverableId) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "交付物产物",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Text(
                    deliverableId,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            OutlinedButton(onClick = { onOpenDeliverableId(deliverableId) }) {
                Text("预览")
            }
        }
    }
}

@Composable
private fun LiveTurnStreamingBubble(
    item: VirtualizedChatTimelineItem.LiveStreamingTurnItem,
    onCopyCode: (String) -> Unit,
    onCardAction: (String, String) -> Unit,
) {
    val draft = item.draft
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Agent", style = MaterialTheme.typography.labelLarge)
                if (item.showBreathingIndicator) {
                    BreathingStatusChip(label = "生成中")
                } else {
                    Text(
                        if (draft.isRunning) "连接中断 · 等待恢复" else "已停止",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item.processPill?.let { pill ->
                ExpandableProcessDisclosurePillView(pill = pill)
            }

            if (draft.responseText.isNotBlank()) {
                MarkdownRichContent(
                    markdown = draft.responseText,
                    onCopyCode = onCopyCode,
                )
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
                    ToolRunStatus.COMPLETED -> "完成"
                    ToolRunStatus.FAILED -> "失败"
                    ToolRunStatus.AWAITING_APPROVAL -> "待审批"
                }
                is OpenEnum.Other -> st.rawValue
            }
            val summarySuffix = tool.summary?.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
            Text(
                text = "[$statusBadge] ${tool.name}$summarySuffix",
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
fun MarkdownRichContent(
    markdown: String,
    highlightQuery: String? = null,
    onCopyCode: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val blocks = remember(markdown) { MarkdownBlockParser.parse(markdown) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Heading -> {
                    val style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    }
                    Text(
                        text = highlightMatchesAnnotated(block.text, highlightQuery),
                        style = style,
                    )
                }

                is MarkdownBlock.Paragraph -> {
                    Text(
                        text = highlightMatchesAnnotated(block.text, highlightQuery),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                is MarkdownBlock.BulletList -> {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        for (bulletItem in block.items) {
                            Text(
                                text = highlightMatchesAnnotated("• $bulletItem", highlightQuery),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                is MarkdownBlock.Table -> {
                    InlineMarkdownTableView(headers = block.headers, rows = block.rows)
                }

                is MarkdownBlock.CodeFence -> {
                    SyntaxHighlightedCodeBlockView(
                        language = block.language,
                        code = block.code,
                        onCopy = {
                            if (onCopyCode != null) {
                                onCopyCode(block.code)
                            } else {
                                copyTextToClipboard(context, block.code)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SyntaxHighlightedCodeBlockView(
    language: String?,
    code: String,
    onCopy: () -> Unit,
) {
    var copiedRecently by remember { mutableStateOf(false) }
    val spans = remember(code, language) { MarkdownBlockParser.tokenizeCode(code, language) }

    LaunchedEffect(copiedRecently) {
        if (copiedRecently) {
            delay(1_800L)
            copiedRecently = false
        }
    }

    val keywordColor = MaterialTheme.colorScheme.primary
    val stringColor = MaterialTheme.colorScheme.tertiary
    val commentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    val numberColor = MaterialTheme.colorScheme.secondary
    val plainColor = MaterialTheme.colorScheme.onSurface

    val highlightedCode = remember(spans, keywordColor, stringColor, commentColor, numberColor, plainColor) {
        buildAnnotatedString {
            for (span in spans) {
                val style = when (span.kind) {
                    CodeTokenKind.KEYWORD -> SpanStyle(color = keywordColor, fontWeight = FontWeight.SemiBold)
                    CodeTokenKind.STRING -> SpanStyle(color = stringColor)
                    CodeTokenKind.COMMENT -> SpanStyle(color = commentColor)
                    CodeTokenKind.NUMBER -> SpanStyle(color = numberColor)
                    CodeTokenKind.PLAIN -> SpanStyle(color = plainColor)
                }
                withStyle(style) {
                    append(span.text)
                }
            }
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = language ?: "code",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        copiedRecently = true
                        onCopy()
                    },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = if (copiedRecently) "✓ 已复制" else "复制代码",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(10.dp),
            ) {
                Text(
                    text = highlightedCode,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun InlineMarkdownTableView(
    headers: List<String>,
    rows: List<List<String>>,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (header in headers) {
                    Text(
                        text = header,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.widthIn(min = 72.dp),
                    )
                }
            }
            HorizontalDivider()
            for (row in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    headers.indices.forEach { colIdx ->
                        Text(
                            text = row.getOrNull(colIdx).orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.widthIn(min = 72.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun highlightMatchesAnnotated(
    text: String,
    query: String?,
): AnnotatedString {
    val needle = query?.trim()?.takeIf { it.isNotEmpty() }
    if (needle == null) return AnnotatedString(text)
    val highlightBg = MaterialTheme.colorScheme.tertiaryContainer
    val highlightFg = MaterialTheme.colorScheme.onTertiaryContainer
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < text.length) {
            val matchIdx = text.indexOf(needle, startIndex = cursor, ignoreCase = true)
            if (matchIdx < 0) {
                append(text.substring(cursor))
                break
            }
            if (matchIdx > cursor) {
                append(text.substring(cursor, matchIdx))
            }
            withStyle(SpanStyle(background = highlightBg, color = highlightFg, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(matchIdx, matchIdx + needle.length))
            }
            cursor = matchIdx + needle.length
        }
    }
}

private fun copyTextToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("code", text))
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

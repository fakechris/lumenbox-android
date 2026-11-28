package com.lumenopen.lumenbox.conversation

import com.lumenopen.lumenbox.contract.OpenEnum

/**
 * Progressive-disclosure summary model that collapses `thinking` reasoning blocks and consecutive
 * tool timeline runs into a single low-noise expandable capsule (`INV-2271`).
 *
 * Expanded detail remains available on tap without flooding the conversation viewport by default.
 */
data class ProcessDisclosurePill(
    val id: String,
    val label: String,
    val isLiveBreathing: Boolean,
    val hasFailure: Boolean,
    val isAwaitingApproval: Boolean,
    val thinkingText: String?,
    val toolCalls: List<ToolTimelineEntry>,
    val defaultExpanded: Boolean = false,
) {
    val hasDetails: Boolean
        get() = !thinkingText.isNullOrBlank() || toolCalls.isNotEmpty()
}

/**
 * Stable virtualized `LazyColumn` item descriptors with deterministic `key` and `contentType`
 * so Compose reuses item compositions without frame drops during live streaming (`INV-2271`).
 */
sealed interface VirtualizedChatTimelineItem {
    val stableKey: String
    val contentType: String

    data class CommittedTurnItem(
        val message: CommittedMessage,
        val processPill: ProcessDisclosurePill?,
        val fixedAspectSkeletonRatios: Map<String, Float>,
    ) : VirtualizedChatTimelineItem {
        override val stableKey: String = "msg:${message.clientMessageId?.takeIf { it.isNotBlank() } ?: message.id}"
        override val contentType: String = when (val r = message.role) {
            is OpenEnum.Known -> "committed_${r.value.wireName}"
            is OpenEnum.Other -> "committed_other"
        }
    }

    data class LiveStreamingTurnItem(
        val draft: LiveTurnDraft,
        val processPill: ProcessDisclosurePill?,
        val showBreathingIndicator: Boolean,
    ) : VirtualizedChatTimelineItem {
        override val stableKey: String = "live:${draft.conversationId}:${draft.turnId}"
        override val contentType: String = "live_streaming_turn"
    }
}

object ConversationNoiseReducer {
    /**
     * Builds a single-line progressive-disclosure capsule for a committed message's `thinking`
     * and `toolCalls` metadata, or `null` if neither is present.
     */
    fun buildCommittedPill(
        message: CommittedMessage,
        estimatedThinkingSeconds: Int? = null,
    ): ProcessDisclosurePill? {
        val cleanThinking = message.thinking?.trim()?.takeIf { it.isNotEmpty() }
        val tools = message.toolCalls
        if (cleanThinking == null && tools.isEmpty()) return null

        val seconds = estimatedThinkingSeconds ?: cleanThinking?.let { estimateSecondsFromThinking(it) }
        return buildPillInternal(
            id = "pill:${message.id}",
            thinkingText = cleanThinking,
            thinkingSeconds = seconds,
            tools = tools,
            isLive = false,
        )
    }

    /**
     * Builds a single-line progressive-disclosure capsule for an active [LiveTurnDraft].
     * While `isHostReachable == false`, `isLiveBreathing` is strictly forced to `false` so
     * disconnected sessions never render a fake spinning state.
     */
    fun buildLivePill(
        draft: LiveTurnDraft,
        isHostReachable: Boolean,
        elapsedThinkingSeconds: Int? = null,
    ): ProcessDisclosurePill? {
        val cleanThinking = draft.thinkingText.trim().takeIf { it.isNotEmpty() }
        val tools = draft.toolCalls
        if (cleanThinking == null && tools.isEmpty()) return null

        val seconds = elapsedThinkingSeconds ?: cleanThinking?.let { estimateSecondsFromThinking(it) }
        return buildPillInternal(
            id = "pill:live:${draft.turnId}",
            thinkingText = cleanThinking,
            thinkingSeconds = seconds,
            tools = tools,
            isLive = draft.isRunning && isHostReachable,
        )
    }

    /**
     * Projects committed messages + optional live draft into virtualized `LazyColumn` items
     * with pre-calculated fixed aspect ratios for image/deliverable placeholders to prevent layout jumps.
     */
    fun projectTimeline(
        messages: List<CommittedMessage>,
        liveTurn: LiveTurnDraft?,
        isHostReachable: Boolean,
    ): List<VirtualizedChatTimelineItem> {
        val items = ArrayList<VirtualizedChatTimelineItem>(messages.size + 1)
        for (msg in messages) {
            val aspectRatios = LinkedHashMap<String, Float>()
            for (att in msg.attachments) {
                val kind = AttachmentMediaKind.infer(att.kind, att.mimeType)
                aspectRatios["att:${att.id}"] = defaultAspectRatioForAttachment(kind)
            }
            for (delivId in msg.deliverableIds) {
                aspectRatios["deliv:$delivId"] = 16f / 9f
            }
            items.add(
                VirtualizedChatTimelineItem.CommittedTurnItem(
                    message = msg,
                    processPill = buildCommittedPill(msg),
                    fixedAspectSkeletonRatios = aspectRatios,
                )
            )
        }
        if (liveTurn != null) {
            items.add(
                VirtualizedChatTimelineItem.LiveStreamingTurnItem(
                    draft = liveTurn,
                    processPill = buildLivePill(liveTurn, isHostReachable = isHostReachable),
                    showBreathingIndicator = isHostReachable && liveTurn.isRunning,
                )
            )
        }
        return items
    }

    fun defaultAspectRatioForAttachment(kind: AttachmentMediaKind): Float = when (kind) {
        AttachmentMediaKind.IMAGE -> 4f / 3f
        AttachmentMediaKind.VOICE -> 4.5f
        AttachmentMediaKind.FILE -> 3.6f
    }

    private fun estimateSecondsFromThinking(thinking: String): Int =
        (thinking.length / 120).coerceIn(1, 60)

    private fun buildPillInternal(
        id: String,
        thinkingText: String?,
        thinkingSeconds: Int?,
        tools: List<ToolTimelineEntry>,
        isLive: Boolean,
    ): ProcessDisclosurePill {
        val hasFailure = tools.any {
            (it.status as? OpenEnum.Known)?.value == ToolRunStatus.FAILED
        }
        val awaitingApproval = tools.any {
            (it.status as? OpenEnum.Known)?.value == ToolRunStatus.AWAITING_APPROVAL
        }
        val runningTool = tools.lastOrNull {
            (it.status as? OpenEnum.Known)?.value == ToolRunStatus.RUNNING
        }

        val segments = mutableListOf<String>()
        if (thinkingText != null) {
            if (isLive && tools.isEmpty()) {
                segments.add("正在思考…")
            } else {
                val secLabel = thinkingSeconds?.let { "${it}s" } ?: ""
                segments.add(if (secLabel.isNotEmpty()) "已思考 $secLabel" else "已完成推理")
            }
        }
        if (tools.isNotEmpty()) {
            when {
                awaitingApproval -> segments.add("等待你审批工具 (${tools.size})")
                isLive && runningTool != null -> segments.add("正在执行 ${runningTool.name} (${tools.size})")
                hasFailure -> {
                    val failedCount = tools.count { (it.status as? OpenEnum.Known)?.value == ToolRunStatus.FAILED }
                    segments.add("调用了 ${tools.size} 个工具（${failedCount} 个失败）")
                }
                else -> segments.add("调用了 ${tools.size} 个工具")
            }
        }

        return ProcessDisclosurePill(
            id = id,
            label = segments.joinToString(" · "),
            isLiveBreathing = isLive,
            hasFailure = hasFailure,
            isAwaitingApproval = awaitingApproval,
            thinkingText = thinkingText,
            toolCalls = tools,
            // Only auto-expand when an action is awaiting approval; otherwise collapsed by default!
            defaultExpanded = awaitingApproval,
        )
    }
}

/**
 * Viewport auto-follow & unread-anchor state machine (`INV-2271`):
 * - Follows streaming deltas and new messages only while pinned to the bottom.
 * - Immediately pauses auto-scroll when the user scrolls up to inspect history so streaming output
 *   never yanks the viewport.
 * - Accumulates `unreadCountWhilePaused` and exposes a floating `"回到底部 · N 条新消息"` indicator.
 */
data class ChatScrollAnchorState(
    val isPinnedToBottom: Boolean = true,
    val unreadCountWhilePaused: Int = 0,
    val lastObservedMessageCount: Int = 0,
) {
    val showJumpToBottomPill: Boolean
        get() = !isPinnedToBottom
}

object ChatScrollAnchorMachine {
    fun onViewportPositionChanged(
        previous: ChatScrollAnchorState,
        isAtBottom: Boolean,
    ): ChatScrollAnchorState {
        return if (isAtBottom) {
            previous.copy(isPinnedToBottom = true, unreadCountWhilePaused = 0)
        } else {
            previous.copy(isPinnedToBottom = false)
        }
    }

    fun onMessageCountUpdated(
        previous: ChatScrollAnchorState,
        newMessageCount: Int,
    ): Pair<ChatScrollAnchorState, Boolean> {
        val added = (newMessageCount - previous.lastObservedMessageCount).coerceAtLeast(0)
        return if (previous.isPinnedToBottom) {
            previous.copy(
                unreadCountWhilePaused = 0,
                lastObservedMessageCount = newMessageCount,
            ) to (added > 0)
        } else {
            previous.copy(
                unreadCountWhilePaused = previous.unreadCountWhilePaused + added,
                lastObservedMessageCount = newMessageCount,
            ) to false
        }
    }

    fun onStreamingDeltaArrived(previous: ChatScrollAnchorState): Boolean =
        previous.isPinnedToBottom

    fun onUserJumpToBottom(
        previous: ChatScrollAnchorState,
        currentMessageCount: Int,
    ): ChatScrollAnchorState = previous.copy(
        isPinnedToBottom = true,
        unreadCountWhilePaused = 0,
        lastObservedMessageCount = currentMessageCount,
    )
}

/**
 * Honest connection & working-state machine (`INV-2271`):
 * - Breathing `WORKING` indicator requires BOTH `isHostReachable == true` AND `isTurnRunning == true`.
 * - Any disconnect / Mac sleep immediately cuts off `WORKING` or `RECENTLY_COMPLETED` to `OFFLINE`
 *   (zero fake spinners).
 * - When an online turn finishes (`WORKING -> !isTurnRunning` while still online), holds
 *   `RECENTLY_COMPLETED` for `completionHoldMillis` (default `2500ms`) to prevent flicker.
 */
enum class HonestWorkingPhase {
    IDLE_ONLINE,
    WORKING,
    RECENTLY_COMPLETED,
    OFFLINE,
}

data class HonestWorkingIndicatorState(
    val phase: HonestWorkingPhase = HonestWorkingPhase.IDLE_ONLINE,
    val holdUntilEpochMillis: Long? = null,
) {
    val isBreathingWorking: Boolean
        get() = phase == HonestWorkingPhase.WORKING

    val showOfflineBanner: Boolean
        get() = phase == HonestWorkingPhase.OFFLINE
}

object HonestWorkingStateMachine {
    const val DEFAULT_COMPLETION_HOLD_MILLIS: Long = 2_500L

    fun transition(
        previous: HonestWorkingIndicatorState,
        isHostReachable: Boolean,
        isTurnRunning: Boolean,
        nowEpochMillis: Long,
        completionHoldMillis: Long = DEFAULT_COMPLETION_HOLD_MILLIS,
    ): HonestWorkingIndicatorState {
        if (!isHostReachable) {
            return HonestWorkingIndicatorState(
                phase = HonestWorkingPhase.OFFLINE,
                holdUntilEpochMillis = null,
            )
        }

        if (isTurnRunning) {
            return HonestWorkingIndicatorState(
                phase = HonestWorkingPhase.WORKING,
                holdUntilEpochMillis = null,
            )
        }

        // Host is reachable and turn is NOT running:
        if (previous.phase == HonestWorkingPhase.WORKING) {
            return HonestWorkingIndicatorState(
                phase = HonestWorkingPhase.RECENTLY_COMPLETED,
                holdUntilEpochMillis = nowEpochMillis + completionHoldMillis,
            )
        }

        if (previous.phase == HonestWorkingPhase.RECENTLY_COMPLETED) {
            val holdUntil = previous.holdUntilEpochMillis ?: 0L
            if (nowEpochMillis < holdUntil) {
                return previous
            }
        }

        return HonestWorkingIndicatorState(
            phase = HonestWorkingPhase.IDLE_ONLINE,
            holdUntilEpochMillis = null,
        )
    }

    fun remainingHoldMillis(state: HonestWorkingIndicatorState, nowEpochMillis: Long): Long {
        if (state.phase != HonestWorkingPhase.RECENTLY_COMPLETED) return 0L
        val until = state.holdUntilEpochMillis ?: return 0L
        return (until - nowEpochMillis).coerceAtLeast(0L)
    }
}

/**
 * Deterministic in-conversation keyword search (`INV-2271`) across message text, tool summaries,
 * and card titles/bodies.
 */
data class ConversationSearchMatch(
    val timelineIndex: Int,
    val messageId: String,
    val snippet: String,
    val occurrenceCount: Int,
)

object ConversationKeywordSearch {
    fun search(
        messages: List<CommittedMessage>,
        query: String,
    ): List<ConversationSearchMatch> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()

        val results = mutableListOf<ConversationSearchMatch>()
        messages.forEachIndexed { index, msg ->
            val searchable = buildString {
                append(msg.text)
                for (tc in msg.toolCalls) {
                    append('\n').append(tc.name)
                    tc.summary?.let { append(' ').append(it) }
                }
                for (card in msg.cards) {
                    append('\n').append(card.title)
                    card.body?.let { append(' ').append(it) }
                }
            }
            val count = countIgnoreCase(searchable, needle)
            if (count > 0) {
                results.add(
                    ConversationSearchMatch(
                        timelineIndex = index,
                        messageId = msg.id,
                        snippet = extractSnippet(searchable, needle),
                        occurrenceCount = count,
                    )
                )
            }
        }
        return results
    }

    private fun countIgnoreCase(haystack: String, needle: String): Int {
        var count = 0
        var start = 0
        while (true) {
            val idx = haystack.indexOf(needle, startIndex = start, ignoreCase = true)
            if (idx < 0) break
            count += 1
            start = idx + needle.length
        }
        return count
    }

    private fun extractSnippet(haystack: String, needle: String): String {
        val idx = haystack.indexOf(needle, ignoreCase = true).coerceAtLeast(0)
        val from = (idx - 24).coerceAtLeast(0)
        val to = (idx + needle.length + 36).coerceAtMost(haystack.length)
        val raw = haystack.substring(from, to).replace('\n', ' ').trim()
        val prefix = if (from > 0) "…" else ""
        val suffix = if (to < haystack.length) "…" else ""
        return "$prefix$raw$suffix"
    }
}

/**
 * Pure tactile feedback trigger detector (`INV-2271`) so UI layers emit subtle haptics at exact
 * transition moments without repeating on every streaming frame.
 */
enum class ConversationHapticMoment {
    FIRST_STREAMING_TOKEN,
    INBOX_DECISION_ARRIVED,
    CODE_COPIED,
    PRIMARY_ACTION_TRIGGERED,
}

data class HapticObservationSnapshot(
    val activeStreamingTurnId: String? = null,
    val hasReceivedFirstStreamingToken: Boolean = false,
    val inboxCount: Int = 0,
)

object ConversationHapticTriggerDetector {
    fun evaluate(
        previous: HapticObservationSnapshot,
        liveTurn: LiveTurnDraft?,
        currentInboxCount: Int,
        isHostReachable: Boolean,
    ): Pair<HapticObservationSnapshot, List<ConversationHapticMoment>> {
        val moments = mutableListOf<ConversationHapticMoment>()

        if (isHostReachable && currentInboxCount > previous.inboxCount) {
            moments.add(ConversationHapticMoment.INBOX_DECISION_ARRIVED)
        }

        val turnId = liveTurn?.turnId
        val hasAnyStreamingPayload = liveTurn != null &&
            (liveTurn.responseText.isNotEmpty() || liveTurn.thinkingText.isNotEmpty() || liveTurn.toolCalls.isNotEmpty())

        val sameTurn = turnId != null && turnId == previous.activeStreamingTurnId
        val alreadyFiredForTurn = sameTurn && previous.hasReceivedFirstStreamingToken

        if (isHostReachable && liveTurn?.isRunning == true && hasAnyStreamingPayload && !alreadyFiredForTurn) {
            moments.add(ConversationHapticMoment.FIRST_STREAMING_TOKEN)
        }

        val nextSnapshot = HapticObservationSnapshot(
            activeStreamingTurnId = turnId,
            hasReceivedFirstStreamingToken = if (turnId == null) false else (alreadyFiredForTurn || hasAnyStreamingPayload),
            inboxCount = currentInboxCount,
        )
        return nextSnapshot to moments
    }
}

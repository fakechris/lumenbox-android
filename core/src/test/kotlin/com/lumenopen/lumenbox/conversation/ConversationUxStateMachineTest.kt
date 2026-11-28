package com.lumenopen.lumenbox.conversation

import com.lumenopen.lumenbox.contract.OpenEnum
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConversationUxStateMachineTest {

    @Test
    fun `progressive disclosure collapses thinking and consecutive tool runs into single-line pill by default`() {
        val plainUserMsg = CommittedMessage(
            id = "m_user_1",
            conversationId = "conv_1",
            role = OpenEnum.Known(MessageRole.USER, "user"),
            text = "帮我检查最近三次部署日志",
            createdAt = "2026-11-28T01:00:00Z",
        )
        assertNull(ConversationNoiseReducer.buildCommittedPill(plainUserMsg))

        val richAssistantMsg = CommittedMessage(
            id = "m_asst_1",
            conversationId = "conv_1",
            role = OpenEnum.Known(MessageRole.ASSISTANT, "assistant"),
            text = "已经核对完毕，最近三次部署均无报错。",
            thinking = "先查看 k8s rollout 历史，再检查 pod 状态与错误计数。".repeat(12),
            createdAt = "2026-11-28T01:00:08Z",
            toolCalls = listOf(
                ToolTimelineEntry("t1", "Read", OpenEnum.Known(ToolRunStatus.COMPLETED, "completed"), "deploy.log"),
                ToolTimelineEntry("t2", "Grep", OpenEnum.Known(ToolRunStatus.COMPLETED, "completed"), "ERROR"),
                ToolTimelineEntry("t3", "Execute", OpenEnum.Known(ToolRunStatus.COMPLETED, "completed"), "kubectl rollout status"),
            ),
        )

        val pill = ConversationNoiseReducer.buildCommittedPill(richAssistantMsg, estimatedThinkingSeconds = 4)
        assertNotNull(pill)
        assertEquals("已思考 4s · 调用了 3 个工具", pill!!.label)
        assertFalse(pill.defaultExpanded, "Committed thinking & tool runs must default to collapsed noise-reduced state")
        assertFalse(pill.isLiveBreathing)
        assertFalse(pill.hasFailure)

        // Awaiting approval automatically expands so the user notices the blocked tool
        val awaitingMsg = richAssistantMsg.copy(
            id = "m_asst_await",
            toolCalls = listOf(
                ToolTimelineEntry("t4", "Execute", OpenEnum.Known(ToolRunStatus.AWAITING_APPROVAL, "awaiting_approval"), "rm -rf staging"),
            ),
        )
        val awaitingPill = ConversationNoiseReducer.buildCommittedPill(awaitingMsg, estimatedThinkingSeconds = 2)
        assertNotNull(awaitingPill)
        assertTrue(awaitingPill!!.isAwaitingApproval)
        assertTrue(awaitingPill.defaultExpanded)
    }

    @Test
    fun `virtualized timeline projection assigns stable keys, contentTypes, and fixed aspect ratios while gating live breathing on host reachability`() {
        val committed = CommittedMessage(
            id = "msg_committed_9",
            conversationId = "conv_2",
            clientMessageId = "cmsg_stable_9",
            role = OpenEnum.Known(MessageRole.ASSISTANT, "assistant"),
            text = "这是生成的架构截图与报告。",
            createdAt = "2026-11-28T01:10:00Z",
            attachments = listOf(
                AttachmentSummary(id = "att_img_1", name = "arch.png", mimeType = "image/png", kind = "image"),
            ),
            deliverableIds = listOf("deliv_report_1"),
        )
        val liveDraft = LiveTurnDraft(
            conversationId = "conv_2",
            turnId = "turn_42",
            thinkingText = "正在汇总指标…",
            responseText = "初步结论：",
            toolCalls = listOf(
                ToolTimelineEntry("tc_live", "FetchMetrics", OpenEnum.Known(ToolRunStatus.RUNNING, "running")),
            ),
            isRunning = true,
        )

        val onlineProjection = ConversationNoiseReducer.projectTimeline(
            messages = listOf(committed),
            liveTurn = liveDraft,
            isHostReachable = true,
        )
        assertEquals(2, onlineProjection.size)
        val first = onlineProjection[0] as VirtualizedChatTimelineItem.CommittedTurnItem
        assertEquals("msg:cmsg_stable_9", first.stableKey)
        assertEquals("committed_assistant", first.contentType)
        assertEquals(4f / 3f, first.fixedAspectSkeletonRatios["att:att_img_1"])
        assertEquals(16f / 9f, first.fixedAspectSkeletonRatios["deliv:deliv_report_1"])

        val onlineLive = onlineProjection[1] as VirtualizedChatTimelineItem.LiveStreamingTurnItem
        assertEquals("live:conv_2:turn_42", onlineLive.stableKey)
        assertTrue(onlineLive.showBreathingIndicator)
        assertTrue(onlineLive.processPill!!.isLiveBreathing)

        // When Mac sleeps / network drops, breathing indicator MUST immediately stop even if draft.isRunning == true
        val offlineProjection = ConversationNoiseReducer.projectTimeline(
            messages = listOf(committed),
            liveTurn = liveDraft,
            isHostReachable = false,
        )
        val offlineLive = offlineProjection[1] as VirtualizedChatTimelineItem.LiveStreamingTurnItem
        assertFalse(offlineLive.showBreathingIndicator)
        assertFalse(offlineLive.processPill!!.isLiveBreathing)
    }

    @Test
    fun `scroll anchor state machine pauses auto-follow when user scrolls up to read history and counts new messages`() {
        var state = ChatScrollAnchorState(isPinnedToBottom = true, lastObservedMessageCount = 5)

        // Pinned at bottom: new message triggers auto-scroll and keeps unread=0
        val (atBottomNext, shouldScrollAtBottom) = ChatScrollAnchorMachine.onMessageCountUpdated(state, 6)
        state = atBottomNext
        assertTrue(shouldScrollAtBottom)
        assertEquals(0, state.unreadCountWhilePaused)
        assertFalse(state.showJumpToBottomPill)
        assertTrue(ChatScrollAnchorMachine.onStreamingDeltaArrived(state))

        // User scrolls up to inspect history -> auto-scroll pauses!
        state = ChatScrollAnchorMachine.onViewportPositionChanged(state, isAtBottom = false)
        assertFalse(state.isPinnedToBottom)
        assertTrue(state.showJumpToBottomPill)
        assertFalse(ChatScrollAnchorMachine.onStreamingDeltaArrived(state), "Streaming tokens must not yank scroll while reading history")

        // Two new messages arrive while reading history -> no auto-scroll, unread increments to 2
        val (pausedNext, shouldScrollWhilePaused) = ChatScrollAnchorMachine.onMessageCountUpdated(state, 8)
        state = pausedNext
        assertFalse(shouldScrollWhilePaused)
        assertEquals(2, state.unreadCountWhilePaused)

        // User taps 'Jump to bottom' pill -> re-pins and clears unread badge
        state = ChatScrollAnchorMachine.onUserJumpToBottom(state, currentMessageCount = 8)
        assertTrue(state.isPinnedToBottom)
        assertEquals(0, state.unreadCountWhilePaused)
        assertFalse(state.showJumpToBottomPill)
    }

    @Test
    fun `honest working state machine enforces 2500ms completion hold and instant disconnect cut-off`() {
        var state = HonestWorkingIndicatorState()

        // 1. Start online idle
        state = HonestWorkingStateMachine.transition(state, isHostReachable = true, isTurnRunning = false, nowEpochMillis = 1_000L)
        assertEquals(HonestWorkingPhase.IDLE_ONLINE, state.phase)
        assertFalse(state.isBreathingWorking)

        // 2. Turn starts while online -> WORKING
        state = HonestWorkingStateMachine.transition(state, isHostReachable = true, isTurnRunning = true, nowEpochMillis = 2_000L)
        assertEquals(HonestWorkingPhase.WORKING, state.phase)
        assertTrue(state.isBreathingWorking)

        // 3. Turn finishes at t=5000ms -> enters RECENTLY_COMPLETED and holds for 2500ms (until 7500ms)
        state = HonestWorkingStateMachine.transition(state, isHostReachable = true, isTurnRunning = false, nowEpochMillis = 5_000L)
        assertEquals(HonestWorkingPhase.RECENTLY_COMPLETED, state.phase)
        assertEquals(7_500L, state.holdUntilEpochMillis)
        assertEquals(1_500L, HonestWorkingStateMachine.remainingHoldMillis(state, nowEpochMillis = 6_000L))

        // At t=7499ms still holding
        state = HonestWorkingStateMachine.transition(state, isHostReachable = true, isTurnRunning = false, nowEpochMillis = 7_499L)
        assertEquals(HonestWorkingPhase.RECENTLY_COMPLETED, state.phase)

        // At t=7500ms smoothly settles back to IDLE_ONLINE
        state = HonestWorkingStateMachine.transition(state, isHostReachable = true, isTurnRunning = false, nowEpochMillis = 7_500L)
        assertEquals(HonestWorkingPhase.IDLE_ONLINE, state.phase)

        // 4. Instant offline cut-off: even if turn was WORKING, disconnect immediately transitions to OFFLINE
        state = HonestWorkingStateMachine.transition(state, isHostReachable = true, isTurnRunning = true, nowEpochMillis = 8_000L)
        assertEquals(HonestWorkingPhase.WORKING, state.phase)
        state = HonestWorkingStateMachine.transition(state, isHostReachable = false, isTurnRunning = true, nowEpochMillis = 8_100L)
        assertEquals(HonestWorkingPhase.OFFLINE, state.phase)
        assertFalse(state.isBreathingWorking)
        assertTrue(state.showOfflineBanner)
    }

    @Test
    fun `code tokenizer, Markdown pipe table parser, keyword search, and haptic trigger detector work deterministically`() {
        val md = """
            # Release Check
            | Module | Status |
            | --- | --- |
            | core | green |
            | app | green |

            ```kotlin
            // Verify signature
            suspend fun verify(code: Int): Boolean {
                val label = "step_up"
                return code == 42
            }
            ```
        """.trimIndent()

        val blocks = MarkdownBlockParser.parse(md)
        assertEquals(3, blocks.size)
        val table = blocks[1] as MarkdownBlock.Table
        assertEquals(listOf("Module", "Status"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("core", "green"), table.rows[0])

        val fence = blocks[2] as MarkdownBlock.CodeFence
        assertEquals("kotlin", fence.language)
        val spans = MarkdownBlockParser.tokenizeCode(fence.code, fence.language)
        assertEquals(fence.code, spans.joinToString("") { it.text })
        assertTrue(spans.any { it.kind == CodeTokenKind.COMMENT && it.text.contains("Verify signature") })
        assertTrue(spans.any { it.kind == CodeTokenKind.KEYWORD && it.text == "suspend" })
        assertTrue(spans.any { it.kind == CodeTokenKind.STRING && it.text == "\"step_up\"" })
        assertTrue(spans.any { it.kind == CodeTokenKind.NUMBER && it.text == "42" })

        // In-conversation keyword search
        val messages = listOf(
            CommittedMessage(
                id = "m1",
                conversationId = "c1",
                role = OpenEnum.Known(MessageRole.USER, "user"),
                text = "请检查 Noise_IK 握手耗时",
                createdAt = "2026-11-28T01:00:00Z",
            ),
            CommittedMessage(
                id = "m2",
                conversationId = "c1",
                role = OpenEnum.Known(MessageRole.ASSISTANT, "assistant"),
                text = "已完成测量，直连耗时 8ms。",
                createdAt = "2026-11-28T01:00:02Z",
                toolCalls = listOf(
                    ToolTimelineEntry("t1", "Benchmark", OpenEnum.Known(ToolRunStatus.COMPLETED, "completed"), "noise_ik latency = 8ms"),
                ),
            ),
        )
        val matches = ConversationKeywordSearch.search(messages, "noise_ik")
        assertEquals(2, matches.size)
        assertEquals("m1", matches[0].messageId)
        assertEquals("m2", matches[1].messageId)

        // Haptic trigger fires only once on first streaming token and once on inbox arrival
        var snap = HapticObservationSnapshot()
        val live1 = LiveTurnDraft(conversationId = "c1", turnId = "t_1", responseText = "你", isRunning = true)
        val (snap1, m1) = ConversationHapticTriggerDetector.evaluate(snap, live1, currentInboxCount = 1, isHostReachable = true)
        snap = snap1
        assertEquals(
            setOf(ConversationHapticMoment.INBOX_DECISION_ARRIVED, ConversationHapticMoment.FIRST_STREAMING_TOKEN),
            m1.toSet(),
        )

        // Subsequent tokens in same turn do NOT re-trigger haptics
        val live2 = live1.copy(responseText = "你好，已完成")
        val (_, m2) = ConversationHapticTriggerDetector.evaluate(snap, live2, currentInboxCount = 1, isHostReachable = true)
        assertTrue(m2.isEmpty())
    }
}

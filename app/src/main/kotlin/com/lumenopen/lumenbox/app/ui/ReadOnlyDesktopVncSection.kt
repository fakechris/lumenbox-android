package com.lumenopen.lumenbox.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.desktop.AgentDesktopSummary
import com.lumenopen.lumenbox.desktop.DesktopViewerSessionState
import com.lumenopen.lumenbox.desktop.ReadOnlyDesktopSessionCoordinator
import com.lumenopen.lumenbox.desktop.RfbFrameSnapshot
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadOnlyDesktopVncSection(
    connectionId: String,
    isOnline: Boolean,
    coordinator: ReadOnlyDesktopSessionCoordinator,
    onAccessRemoved: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val sessionState by coordinator.state.collectAsState()

    var agents by remember(connectionId) { mutableStateOf(coordinator.readCachedAgents()) }
    var selectedAgentId by rememberSaveable(connectionId) {
        mutableStateOf(agents.firstOrNull { it.hasDesktop }?.id ?: agents.firstOrNull()?.id)
    }
    var actionError by remember(connectionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(connectionId, isOnline) {
        if (isOnline) {
            try {
                val refreshed = coordinator.refreshAgents()
                agents = refreshed
                if (selectedAgentId == null || refreshed.none { it.id == selectedAgentId }) {
                    selectedAgentId = refreshed.firstOrNull { it.hasDesktop }?.id ?: refreshed.firstOrNull()?.id
                }
            } catch (err: ApiEnvelopeException) {
                if (err.envelope.isAccessRemoved) {
                    onAccessRemoved()
                } else {
                    actionError = "读取 Agent 列表失败（${err.envelope.code}）：${err.envelope.error}"
                }
            } catch (err: Throwable) {
                actionError = err.message
            }
        }
    }

    // Immediate resource teardown when backgrounded (`ON_STOP`) or navigated away (`onDispose`).
    DisposableEffect(lifecycleOwner, coordinator) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                coordinator.disconnect("应用进入后台，已释放只读桌面流")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            coordinator.disconnect("离开桌面页面，已释放只读桌面流")
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("只读桌面观看 (RFB 3.8)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "60s 单次 view 票据 · /vnc-ro/ 通道 · 零触控与零键盘回传保护",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    AssistChip(
                        onClick = {},
                        label = { Text("仅观看 (View-Only)") },
                    )
                }

                if (agents.isEmpty()) {
                    Text(
                        if (isOnline) "当前安装暂无可用桌面 Agent。" else "当前离线，连接恢复后可选择 Agent 观看实时桌面。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        for (agent in agents) {
                            FilterChip(
                                selected = selectedAgentId == agent.id,
                                onClick = { selectedAgentId = agent.id },
                                enabled = agent.hasDesktop,
                                label = {
                                    val rolePart = agent.role?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                                    Text("${agent.name}$rolePart")
                                },
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val isStreaming = sessionState is DesktopViewerSessionState.WatchingReadOnly ||
                        sessionState is DesktopViewerSessionState.ConnectingStream ||
                        sessionState is DesktopViewerSessionState.RequestingViewTicket

                    Button(
                        enabled = isOnline && !selectedAgentId.isNullOrBlank(),
                        onClick = {
                            val target = selectedAgentId ?: return@Button
                            actionError = null
                            scope.launch {
                                try {
                                    coordinator.startWatching(target)
                                } catch (err: ApiEnvelopeException) {
                                    if (err.envelope.isAccessRemoved) {
                                        onAccessRemoved()
                                    } else {
                                        actionError = "启动桌面观看失败（${err.envelope.code}）：${err.envelope.error}"
                                    }
                                } catch (err: Throwable) {
                                    actionError = err.message ?: "无法建立只读桌面连接"
                                }
                            }
                        },
                    ) {
                        Text(if (isStreaming) "重新申请 60s 票据重连" else "观看实时桌面")
                    }

                    if (isStreaming) {
                        OutlinedButton(
                            onClick = { coordinator.disconnect("已手动停止观看桌面") },
                        ) {
                            Text("断开连接")
                        }
                    }

                    if (sessionState is DesktopViewerSessionState.WatchingReadOnly) {
                        OutlinedButton(
                            onClick = { coordinator.requestFullRefresh() },
                        ) {
                            Text("全帧刷新")
                        }
                    }
                }
            }
        }

        actionError?.let { errText ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = errText,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        when (val st = sessionState) {
            DesktopViewerSessionState.Idle -> Unit
            is DesktopViewerSessionState.RequestingViewTicket -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "正在为 ${st.agentId} 申请 60 秒单次只读票据 (mode=view)…",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            is DesktopViewerSessionState.ConnectingStream -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "正在握手只读 RFB 3.8 二进制流 (${st.path.substringBefore('?')})…",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            is DesktopViewerSessionState.WatchingReadOnly -> {
                ReadOnlyDesktopVncCanvasCard(snapshot = st.snapshot)
            }

            is DesktopViewerSessionState.Disconnected -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "${st.agentId}：${st.reason}",
                        modifier = Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

/**
 * Purely local pinch-to-zoom and pan canvas (`docs/110 §4.4`):
 * - Converts `RfbFrameSnapshot.argbPixels` into an immutable `Bitmap.Config.ARGB_8888`.
 * - Pinch/pan gestures mutate local `scale` / `offsetX` / `offsetY` via `graphicsLayer` ONLY;
 *   zero touch or keyboard events are ever forwarded to [ReadOnlyDesktopSessionCoordinator].
 * - Uses `rememberSaveable` so zoom/pan state survives portrait/landscape rotation smoothly.
 */
@Composable
fun ReadOnlyDesktopVncCanvasCard(
    snapshot: RfbFrameSnapshot,
) {
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable { mutableFloatStateOf(0f) }

    val imageBitmap = remember(snapshot.frameNumber, snapshot.width, snapshot.height) {
        Bitmap.createBitmap(
            snapshot.argbPixels,
            snapshot.width,
            snapshot.height,
            Bitmap.Config.ARGB_8888,
        ).asImageBitmap()
    }
    val safeAspect = (snapshot.width.toFloat() / snapshot.height.coerceAtLeast(1).toFloat())
        .coerceIn(0.5f, 3.0f)

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val title = snapshot.desktopName.ifBlank { "Agent Desktop" }
                    Text(
                        "$title · ${snapshot.width}×${snapshot.height} · 帧 #${snapshot.frameNumber}",
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        "双指缩放 / 单指平移（纯本地视图变换，不向远端发送任何点击或按键）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (scale != 1f || offsetX != 0f || offsetY != 0f) {
                    OutlinedButton(
                        onClick = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                    ) {
                        Text("重置缩放")
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(safeAspect)
                    .heightIn(min = 180.dp)
                    .clipToBounds()
                    .background(Color(0xFF101418))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 5f)
                            scale = nextScale
                            if (nextScale <= 1.001f) {
                                offsetX = 0f
                                offsetY = 0f
                            } else {
                                val maxX = (size.width * (nextScale - 1f)) / 2f
                                val maxY = (size.height * (nextScale - 1f)) / 2f
                                offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                                offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    bitmap = imageBitmap,
                    contentDescription = "只读 Agent 桌面实时画面 (${snapshot.desktopName})",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                )
            }
        }
    }
}

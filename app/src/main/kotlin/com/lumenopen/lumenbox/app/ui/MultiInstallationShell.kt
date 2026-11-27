package com.lumenopen.lumenbox.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenopen.lumenbox.contract.InboxDecisionItem
import com.lumenopen.lumenbox.relay.NoiseRelayException
import com.lumenopen.lumenbox.relay.TransportPathMode
import com.lumenopen.lumenbox.session.PairedDeviceSummary
import com.lumenopen.lumenbox.session.PersonalMacSessionCoordinator
import com.lumenopen.lumenbox.session.PersonalMacSessionState
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationPresence
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.storage.NeedsYouProjection
import com.lumenopen.lumenbox.storage.PersonalInstallationAlreadyExistsException
import kotlinx.coroutines.launch

/**
 * Top-level multi-installation Compose shell (`docs/95 §4`, `docs/110 §0.2, §3`, INV-2254).
 *
 * - Cross-boundary switcher bar exposes ONLY [NeedsYouProjection] (`displayName`, `kind`,
 *   `needsYouCount` badge, and online/sleeping indicator).
 * - Personal Mac view renders the immediate `Mac 已休眠（最后在线时间）` banner upon `host_sleeping`
 *   control frames while keeping locally cached `Needs you` decisions readable offline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiInstallationShell(
    store: InstallationStore,
    personalCoordinator: PersonalMacSessionCoordinator,
    pendingDeepLinkPairUri: String?,
    onConsumeDeepLinkPairUri: () -> Unit,
) {
    val projections by store.projections.collectAsState()
    val activeId by store.activeInstallationId.collectAsState()
    val personalState by personalCoordinator.state.collectAsState()
    val scope = rememberCoroutineScope()

    var showPairScreen by remember { mutableStateOf(false) }
    var pairInitialUri by remember { mutableStateOf<String?>(null) }
    var pairingBusy by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var pairedDevices by remember { mutableStateOf<List<PairedDeviceSummary>>(emptyList()) }

    val hasPersonalMac = projections.any { it.kind == InstallationConnection.Kind.PERSONAL_MAC }

    LaunchedEffect(pendingDeepLinkPairUri) {
        if (!pendingDeepLinkPairUri.isNullOrBlank()) {
            pairInitialUri = pendingDeepLinkPairUri
            pairingError = null
            showPairScreen = true
            onConsumeDeepLinkPairUri()
        }
    }

    LaunchedEffect(personalState) {
        if (personalState !is PersonalMacSessionState.Unpaired) {
            pairedDevices = runCatching { personalCoordinator.refreshPairedDevices() }.getOrDefault(emptyList())
        } else {
            pairedDevices = emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LumenBox") },
                actions = {
                    if (!hasPersonalMac && !showPairScreen) {
                        OutlinedButton(
                            onClick = {
                                pairInitialUri = null
                                pairingError = null
                                showPairScreen = true
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text("配对个人 Mac")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (showPairScreen) {
                CameraQrPairingScreen(
                    initialPairingUri = pairInitialUri,
                    isBusy = pairingBusy,
                    externalError = pairingError,
                    onPairRequested = { qrUri, macName, devName ->
                        pairingBusy = true
                        pairingError = null
                        scope.launch {
                            try {
                                personalCoordinator.pairAndConnect(
                                    qrUri = qrUri,
                                    displayName = macName,
                                    deviceName = devName,
                                )
                                showPairScreen = false
                                pairInitialUri = null
                            } catch (_: PersonalInstallationAlreadyExistsException) {
                                pairingError = "至多只能配对 1 台个人 Mac，请先解除当前个人 Mac 配对。"
                            } catch (err: NoiseRelayException) {
                                pairingError = "配对失败（${err.code}）：${err.message}"
                            } catch (err: Throwable) {
                                pairingError = "配对连接异常：${err.message}"
                            } finally {
                                pairingBusy = false
                            }
                        }
                    },
                    onCancel = {
                        showPairScreen = false
                        pairInitialUri = null
                        pairingError = null
                    },
                )
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    TopLevelInstallationSwitcherBar(
                        projections = projections,
                        activeInstallationId = activeId,
                        onSelectInstallation = { id -> store.selectActiveInstallation(id) },
                    )
                    HorizontalDivider()
                    val selectedProjection = projections.firstOrNull { it.installationId == activeId }
                        ?: projections.firstOrNull()

                    if (selectedProjection == null) {
                        EmptyInstallationsPane(
                            onStartPersonalPairing = {
                                pairInitialUri = null
                                pairingError = null
                                showPairScreen = true
                            },
                        )
                    } else if (selectedProjection.kind == InstallationConnection.Kind.PERSONAL_MAC) {
                        PersonalMacInstallationPane(
                            state = personalState,
                            cachedInbox = remember(personalState, selectedProjection.needsYouCount) {
                                personalCoordinator.readCachedInboxItems()
                            },
                            pendingOfflineActions = remember(personalState) {
                                personalCoordinator.pendingOfflineActionCount()
                            },
                            pairedDevices = pairedDevices,
                            onReconnect = {
                                scope.launch {
                                    runCatching { personalCoordinator.reconnect() }
                                }
                            },
                            onRefreshDevices = {
                                scope.launch {
                                    pairedDevices = runCatching { personalCoordinator.refreshPairedDevices() }
                                        .getOrDefault(pairedDevices)
                                }
                            },
                            onRevokeDevice = { deviceId ->
                                scope.launch {
                                    if (runCatching { personalCoordinator.revokePairedDevice(deviceId) }.getOrDefault(false)) {
                                        pairedDevices = runCatching { personalCoordinator.refreshPairedDevices() }
                                            .getOrDefault(emptyList())
                                    }
                                }
                            },
                            onUnpairMac = {
                                personalCoordinator.unpairLocalMac()
                            },
                        )
                    } else {
                        OrganisationSummaryPlaceholderPane(projection = selectedProjection)
                    }
                }
            }
        }
    }
}

/**
 * Strict cross-boundary switcher bar: takes ONLY [NeedsYouProjection] so no messages, agents,
 * or work items ever cross installation boundaries at the top level (`docs/95 §4`).
 */
@Composable
fun TopLevelInstallationSwitcherBar(
    projections: List<NeedsYouProjection>,
    activeInstallationId: String?,
    onSelectInstallation: (String) -> Unit,
) {
    if (projections.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in projections) {
            val isSelected = item.installationId == activeInstallationId
            val statusDot = when (item.presence) {
                InstallationPresence.OnlineDirect, InstallationPresence.OnlineRelay -> "●"
                is InstallationPresence.HostSleeping -> "◐"
                is InstallationPresence.Disconnected -> "○"
            }
            val kindTag = when (item.kind) {
                InstallationConnection.Kind.PERSONAL_MAC -> "Mac"
                InstallationConnection.Kind.ORGANISATION -> "组织"
            }
            FilterChip(
                selected = isSelected,
                onClick = { onSelectInstallation(item.installationId) },
                label = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("$statusDot ${item.displayName} ($kindTag)")
                        if (item.needsYouCount > 0) {
                            Badge {
                                Text(item.needsYouCount.toString())
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyInstallationsPane(onStartPersonalPairing: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("尚未连接任何 LumenBox 安装", style = MaterialTheme.typography.headlineSmall)
        Text(
            "至多可配对 1 台个人 Mac（#fragment 零知识扫码），并可同时登录任意多个企业组织服务器，本地密钥与离线缓存严格隔离。",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
        )
        Button(onClick = onStartPersonalPairing) {
            Text("扫描个人 Mac 配对二维码")
        }
    }
}

@Composable
private fun PersonalMacInstallationPane(
    state: PersonalMacSessionState,
    cachedInbox: List<InboxDecisionItem>,
    pendingOfflineActions: Int,
    pairedDevices: List<PairedDeviceSummary>,
    onReconnect: () -> Unit,
    onRefreshDevices: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onUnpairMac: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            is PersonalMacSessionState.HostSleeping -> {
                HostSleepingBanner(
                    lastSeenAt = state.lastSeenAt,
                    reason = state.reason,
                    onRetryWakeConnect = onReconnect,
                )
            }
            is PersonalMacSessionState.Online -> {
                val pathLabel = when (state.mode) {
                    TransportPathMode.DIRECT -> "局域网 / Tailnet 直连 (Noise_IK)"
                    TransportPathMode.RELAY -> "端到端加密中继 (Noise_IK)"
                }
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text("${state.displayName} 已在线", style = MaterialTheme.typography.titleMedium)
                        Text("通道：$pathLabel", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            is PersonalMacSessionState.Connecting -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "正在重连 ${state.displayName}（Noise_IK 双向验证）…",
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            is PersonalMacSessionState.Disconnected -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${state.displayName} 当前离线", style = MaterialTheme.typography.titleSmall)
                            state.lastSeenAt?.let {
                                Text("最后在线时间：$it", style = MaterialTheme.typography.bodySmall)
                            }
                            state.errorMessage?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Button(onClick = onReconnect) {
                            Text("重连")
                        }
                    }
                }
            }
            is PersonalMacSessionState.Pairing -> {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("正在配对 ${state.displayName}…", modifier = Modifier.padding(12.dp))
                }
            }
            PersonalMacSessionState.Unpaired -> Unit
        }

        // Offline-readable Needs You snapshot card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Needs you 待决项（本地加密离线缓存：${cachedInbox.size}）",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (pendingOfflineActions > 0) {
                    Text(
                        "待同步幂等操作队列：$pendingOfflineActions 项（恢复在线后自动重放）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (cachedInbox.isEmpty()) {
                    Text("当前没有等待你处理的决策。", style = MaterialTheme.typography.bodyMedium)
                } else {
                    for (item in cachedInbox) {
                        HorizontalDivider()
                        Text(item.summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // Paired devices list & per-device revocation
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("已配对设备", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = onRefreshDevices) {
                        Text("刷新")
                    }
                }
                for (device in pairedDevices) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val suffix = if (device.isCurrentDevice) "（本机）" else ""
                            Text("${device.name}$suffix", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(device.platform, device.lastSeenAt).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!device.isCurrentDevice && state is PersonalMacSessionState.Online) {
                            OutlinedButton(onClick = { onRevokeDevice(device.id) }) {
                                Text("移除")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                OutlinedButton(
                    onClick = onUnpairMac,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("解除本机与该个人 Mac 的配对并擦除本地缓存")
                }
            }
        }
    }
}

@Composable
fun HostSleepingBanner(
    lastSeenAt: String,
    reason: String,
    onRetryWakeConnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Mac 已休眠（最后在线时间：$lastSeenAt）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = "已收到宿主机休眠通知（$reason），当前正在浏览本机 256-bit 加密离线缓存；Mac 唤醒后将通过 Noise_IK 自动恢复同步。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            OutlinedButton(onClick = onRetryWakeConnect) {
                Text("检查是否已唤醒")
            }
        }
    }
}

@Composable
private fun OrganisationSummaryPlaceholderPane(projection: NeedsYouProjection) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(projection.displayName, style = MaterialTheme.typography.headlineSmall)
        Text("待决 Needs you：${projection.needsYouCount} 项", style = MaterialTheme.typography.bodyLarge)
    }
}

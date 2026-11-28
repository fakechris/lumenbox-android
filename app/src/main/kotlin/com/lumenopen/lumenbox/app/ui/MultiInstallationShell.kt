package com.lumenopen.lumenbox.app.ui

import android.os.Build
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import com.lumenopen.lumenbox.app.security.ExplicitChallengeUiDialog
import com.lumenopen.lumenbox.app.security.StepUpBiometricGateway
import com.lumenopen.lumenbox.security.StepUpCancelledException
import com.lumenopen.lumenbox.security.StepUpSecurityPolicy
import kotlinx.coroutines.CompletableDeferred
import com.lumenopen.lumenbox.auth.OAuthExchangeOutcome
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.conversation.ConversationAndBoardCoordinator
import com.lumenopen.lumenbox.conversation.LiveSseConnector
import com.lumenopen.lumenbox.desktop.DesktopBinaryStreamOpener
import com.lumenopen.lumenbox.desktop.ReadOnlyDesktopSessionCoordinator
import com.lumenopen.lumenbox.inbox.NeedsYouInboxCoordinator
import com.lumenopen.lumenbox.push.DevicePushSyncCoordinator
import com.lumenopen.lumenbox.push.LumenBoxDeepLinkRouter
import com.lumenopen.lumenbox.push.OpenDeepLinkTarget
import com.lumenopen.lumenbox.inbox.StepUpChallengeAuthenticator
import com.lumenopen.lumenbox.relay.NoiseRelayException
import com.lumenopen.lumenbox.relay.TransportPathMode
import com.lumenopen.lumenbox.session.OrganisationSessionCoordinator
import com.lumenopen.lumenbox.session.PairedDeviceSummary
import com.lumenopen.lumenbox.session.PersonalMacSessionCoordinator
import com.lumenopen.lumenbox.session.PersonalMacSessionState
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationPresence
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.storage.NeedsYouProjection
import com.lumenopen.lumenbox.storage.PersonalInstallationAlreadyExistsException
import kotlinx.coroutines.launch

private enum class ActiveModal { NONE, PAIR_PERSONAL_MAC, ADD_ORGANISATION }

enum class InstallationTab(val label: String) {
    NEEDS_YOU("待决 Needs you"),
    CONVERSATIONS("流式对话"),
    BOARD_DELIVERABLES("看板与交付物"),
    DESKTOP_VIEWER("桌面观看"),
    DEVICES("设备与连接"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiInstallationShell(
    store: InstallationStore,
    personalCoordinator: PersonalMacSessionCoordinator,
    organisationCoordinator: OrganisationSessionCoordinator,
    pushSyncCoordinator: DevicePushSyncCoordinator? = null,
    stepUpGateway: StepUpBiometricGateway? = null,
    pendingDeepLinkPairUri: String?,
    onConsumeDeepLinkPairUri: () -> Unit,
    pendingOAuthCallbackUri: String?,
    onConsumeOAuthCallbackUri: () -> Unit,
    pendingOpenDeepLinkTarget: OpenDeepLinkTarget? = null,
    onConsumeOpenDeepLinkTarget: () -> Unit = {},
) {
    val context = LocalContext.current
    val projections by store.projections.collectAsState()
    val activeId by store.activeInstallationId.collectAsState()
    val personalState by personalCoordinator.state.collectAsState()
    val orgOnboardingState by organisationCoordinator.onboardingState.collectAsState()
    val scope = rememberCoroutineScope()

    var activeModal by remember { mutableStateOf(ActiveModal.NONE) }
    var pairInitialUri by remember { mutableStateOf<String?>(null) }
    var pairingBusy by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var pairedDevices by remember { mutableStateOf<List<PairedDeviceSummary>>(emptyList()) }
    var globalBannerMessage by remember { mutableStateOf<String?>(null) }
    var routedOpenTarget by remember { mutableStateOf<OpenDeepLinkTarget?>(null) }

    data class PendingExplicitChallenge(
        val title: String,
        val subtitle: String,
        val expectedCode: String,
        val deferred: CompletableDeferred<String>,
    )
    var pendingExplicitChallenge by remember { mutableStateOf<PendingExplicitChallenge?>(null) }

    val explicitChallengeDialog = remember {
        ExplicitChallengeUiDialog { title, subtitle, expectedCode ->
            val deferred = CompletableDeferred<String>()
            pendingExplicitChallenge = PendingExplicitChallenge(title, subtitle, expectedCode, deferred)
            try {
                deferred.await()
            } finally {
                pendingExplicitChallenge = null
            }
        }
    }

    val personalStepUpAuthenticator: StepUpChallengeAuthenticator? = remember(stepUpGateway, context) {
        stepUpGateway?.let { gw ->
            StepUpChallengeAuthenticator { challenge, item ->
                gw.signWithStepUp(
                    context = context,
                    title = "高危工具审批验证",
                    subtitle = "${item.toolName}：${item.summary}",
                    challenge = challenge,
                    policy = StepUpSecurityPolicy.PERSONAL_DEFAULT,
                    explicitDialog = explicitChallengeDialog,
                )
            }
        }
    }

    val organisationStepUpAuthenticator: StepUpChallengeAuthenticator? = remember(stepUpGateway, context) {
        stepUpGateway?.let { gw ->
            StepUpChallengeAuthenticator { challenge, item ->
                gw.signWithStepUp(
                    context = context,
                    title = "高危工具审批验证",
                    subtitle = "${item.toolName}：${item.summary}",
                    challenge = challenge,
                    policy = StepUpSecurityPolicy.ORGANISATION_DEFAULT,
                    explicitDialog = explicitChallengeDialog,
                )
            }
        }
    }

    val hasPersonalMac = projections.any { it.kind == InstallationConnection.Kind.PERSONAL_MAC }
    val defaultDeviceName = remember {
        listOfNotNull(Build.MANUFACTURER?.takeIf { it.isNotBlank() }, Build.MODEL?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .ifBlank { "Android Phone" }
    }

    LaunchedEffect(pendingDeepLinkPairUri) {
        if (!pendingDeepLinkPairUri.isNullOrBlank()) {
            pairInitialUri = pendingDeepLinkPairUri
            pairingError = null
            activeModal = ActiveModal.PAIR_PERSONAL_MAC
            onConsumeDeepLinkPairUri()
        }
    }

    LaunchedEffect(pendingOAuthCallbackUri) {
        val callbackUri = pendingOAuthCallbackUri
        if (!callbackUri.isNullOrBlank()) {
            activeModal = ActiveModal.ADD_ORGANISATION
            onConsumeOAuthCallbackUri()
            runCatching {
                organisationCoordinator.handleOAuthCallback(
                    callbackUriString = callbackUri,
                    deviceName = defaultDeviceName,
                )
            }.onSuccess { outcome ->
                if (outcome is OAuthExchangeOutcome.Authenticated) {
                    activeModal = ActiveModal.NONE
                }
            }
        }
    }

    LaunchedEffect(pendingOpenDeepLinkTarget, projections) {
        val openTarget = pendingOpenDeepLinkTarget ?: return@LaunchedEffect
        onConsumeOpenDeepLinkTarget()
        activeModal = ActiveModal.NONE
        val matched = LumenBoxDeepLinkRouter.resolveTargetConnection(store, openTarget)
        if (matched != null) {
            store.selectActiveInstallation(matched.id)
            routedOpenTarget = openTarget
        } else {
            globalBannerMessage = "收到钉钉/飞书卡片深链，但本机尚未登录对应服务器连接。"
        }
    }

    LaunchedEffect(personalState) {
        pairedDevices = if (personalState !is PersonalMacSessionState.Unpaired) {
            runCatching { personalCoordinator.refreshPairedDevices() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LumenBox") },
                actions = {
                    if (activeModal == ActiveModal.NONE) {
                        if (!hasPersonalMac) {
                            OutlinedButton(
                                onClick = {
                                    pairInitialUri = null
                                    pairingError = null
                                    activeModal = ActiveModal.PAIR_PERSONAL_MAC
                                },
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text("配对 Mac")
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                organisationCoordinator.resetOnboarding()
                                activeModal = ActiveModal.ADD_ORGANISATION
                            },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text("+ 组织")
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
            when (activeModal) {
                ActiveModal.PAIR_PERSONAL_MAC -> {
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
                                    activeModal = ActiveModal.NONE
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
                            activeModal = ActiveModal.NONE
                            pairInitialUri = null
                            pairingError = null
                        },
                    )
                }

                ActiveModal.ADD_ORGANISATION -> {
                    OrganisationSignInScreen(
                        state = orgOnboardingState,
                        coordinator = organisationCoordinator,
                        onClose = { activeModal = ActiveModal.NONE },
                    )
                }

                ActiveModal.NONE -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        globalBannerMessage?.let { notice ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = notice,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    OutlinedButton(onClick = { globalBannerMessage = null }) {
                                        Text("关闭")
                                    }
                                }
                            }
                        }

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
                                    activeModal = ActiveModal.PAIR_PERSONAL_MAC
                                },
                                onStartOrganisationSignIn = {
                                    organisationCoordinator.resetOnboarding()
                                    activeModal = ActiveModal.ADD_ORGANISATION
                                },
                            )
                        } else if (selectedProjection.kind == InstallationConnection.Kind.PERSONAL_MAC) {
                            val inboxCoordinator = remember(selectedProjection.installationId, personalStepUpAuthenticator) {
                                NeedsYouInboxCoordinator(
                                    connectionId = selectedProjection.installationId,
                                    store = store,
                                    transportProvider = { personalCoordinator.activeApiTransport() },
                                    stepUpAuthenticator = personalStepUpAuthenticator,
                                )
                            }
                            val conversationCoordinator = remember(selectedProjection.installationId) {
                                ConversationAndBoardCoordinator(
                                    connectionId = selectedProjection.installationId,
                                    store = store,
                                    transportProvider = { personalCoordinator.activeApiTransport() },
                                    sseConnectorProvider = {
                                        personalCoordinator.activeRelayMux()?.let {
                                            LiveSseConnector.overRelayMux { personalCoordinator.activeRelayMux() }
                                        }
                                    },
                                )
                            }
                            val desktopCoordinator = remember(selectedProjection.installationId) {
                                ReadOnlyDesktopSessionCoordinator(
                                    connectionId = selectedProjection.installationId,
                                    store = store,
                                    transportProvider = { personalCoordinator.activeApiTransport() },
                                    streamOpenerProvider = {
                                        personalCoordinator.activeRelayMux()?.let {
                                            DesktopBinaryStreamOpener.overRelayMux(
                                                muxProvider = { personalCoordinator.activeRelayMux() },
                                            )
                                        }
                                    },
                                )
                            }
                            PersonalMacInstallationPane(
                                installationId = selectedProjection.installationId,
                                store = store,
                                state = personalState,
                                inboxCoordinator = inboxCoordinator,
                                conversationCoordinator = conversationCoordinator,
                                desktopCoordinator = desktopCoordinator,
                                pushSyncCoordinator = pushSyncCoordinator,
                                routedOpenTarget = routedOpenTarget,
                                onConsumeRoutedOpenTarget = { routedOpenTarget = null },
                                pendingOfflineActions = personalCoordinator.pendingOfflineActionCount(),
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
                            val inboxCoordinator = remember(selectedProjection.installationId, organisationStepUpAuthenticator) {
                                NeedsYouInboxCoordinator(
                                    connectionId = selectedProjection.installationId,
                                    store = store,
                                    transportProvider = {
                                        organisationCoordinator.transportForOrganisation(selectedProjection.installationId)
                                    },
                                    stepUpAuthenticator = organisationStepUpAuthenticator,
                                )
                            }
                            val conversationCoordinator = remember(selectedProjection.installationId) {
                                ConversationAndBoardCoordinator(
                                    connectionId = selectedProjection.installationId,
                                    store = store,
                                    transportProvider = {
                                        organisationCoordinator.transportForOrganisation(selectedProjection.installationId)
                                    },
                                    sseConnectorProvider = {
                                        organisationCoordinator.sseConnectorForOrganisation(selectedProjection.installationId)
                                    },
                                )
                            }
                            val desktopCoordinator = remember(selectedProjection.installationId) {
                                ReadOnlyDesktopSessionCoordinator(
                                    connectionId = selectedProjection.installationId,
                                    store = store,
                                    transportProvider = {
                                        organisationCoordinator.transportForOrganisation(selectedProjection.installationId)
                                    },
                                    streamOpenerProvider = {
                                        organisationCoordinator.desktopStreamOpenerForOrganisation(selectedProjection.installationId)
                                    },
                                )
                            }
                            OrganisationInstallationPane(
                                projection = selectedProjection,
                                store = store,
                                coordinator = organisationCoordinator,
                                inboxCoordinator = inboxCoordinator,
                                conversationCoordinator = conversationCoordinator,
                                desktopCoordinator = desktopCoordinator,
                                pushSyncCoordinator = pushSyncCoordinator,
                                routedOpenTarget = routedOpenTarget,
                                onConsumeRoutedOpenTarget = { routedOpenTarget = null },
                                onAccessRemoved = { orgName ->
                                    globalBannerMessage = "你在「$orgName」的访问权限已变更或设备已被移除，该组织的本地凭据与离线缓存已自动安全擦除。"
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingExplicitChallenge?.let { prompt ->
        var typedCode by remember(prompt.expectedCode) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = {
                prompt.deferred.completeExceptionally(StepUpCancelledException("User dismissed explicit challenge"))
            },
            title = { Text(prompt.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(prompt.subtitle, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "当前手机未开启系统锁屏，请输入下方两位防误触确认码（${prompt.expectedCode}）以放行高危操作：",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = typedCode,
                        onValueChange = { typedCode = it.take(2) },
                        singleLine = true,
                        label = { Text("输入 ${prompt.expectedCode}") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { prompt.deferred.complete(typedCode) },
                    enabled = typedCode.length == 2,
                ) {
                    Text("确认放行")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        prompt.deferred.completeExceptionally(StepUpCancelledException("User cancelled explicit challenge"))
                    },
                ) {
                    Text("取消")
                }
            },
        )
    }
}

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
private fun InstallationSubNavigationRow(
    selectedTab: InstallationTab,
    needsYouCount: Int,
    onSelectTab: (InstallationTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (tab in InstallationTab.entries) {
            FilterChip(
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
                label = {
                    if (tab == InstallationTab.NEEDS_YOU && needsYouCount > 0) {
                        Text("${tab.label} ($needsYouCount)")
                    } else {
                        Text(tab.label)
                    }
                },
            )
        }
    }
}

@Composable
private fun EmptyInstallationsPane(
    onStartPersonalPairing: () -> Unit,
    onStartOrganisationSignIn: () -> Unit,
) {
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
        Button(
            onClick = onStartPersonalPairing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("扫描个人 Mac 配对二维码")
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(
            onClick = onStartOrganisationSignIn,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("连接企业组织服务器（钉钉 / 飞书）")
        }
    }
}

@Composable
private fun OrganisationInstallationPane(
    projection: NeedsYouProjection,
    store: InstallationStore,
    coordinator: OrganisationSessionCoordinator,
    inboxCoordinator: NeedsYouInboxCoordinator,
    conversationCoordinator: ConversationAndBoardCoordinator,
    desktopCoordinator: ReadOnlyDesktopSessionCoordinator,
    pushSyncCoordinator: DevicePushSyncCoordinator?,
    routedOpenTarget: OpenDeepLinkTarget?,
    onConsumeRoutedOpenTarget: () -> Unit,
    onAccessRemoved: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val connectionId = projection.installationId
    var activeTab by remember(connectionId) { mutableStateOf(InstallationTab.NEEDS_YOU) }
    var highlightedInboxId by remember(connectionId) { mutableStateOf<String?>(null) }
    var selectedConversationId by remember(connectionId) { mutableStateOf<String?>(null) }
    var requestedDeliverableId by remember(connectionId) { mutableStateOf<String?>(null) }
    var inboxItems by remember(connectionId) {
        mutableStateOf(inboxCoordinator.readCachedItems())
    }
    var devices by remember(connectionId) { mutableStateOf<List<PairedDeviceSummary>>(emptyList()) }
    var syncError by remember(connectionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(routedOpenTarget, connectionId) {
        val target = routedOpenTarget ?: return@LaunchedEffect
        val resolved = LumenBoxDeepLinkRouter.resolveTargetConnection(store, target)
        if (resolved?.id == connectionId) {
            when (target.preferredTab) {
                OpenDeepLinkTarget.FocusTab.NEEDS_YOU -> {
                    activeTab = InstallationTab.NEEDS_YOU
                    highlightedInboxId = target.inboxId
                }
                OpenDeepLinkTarget.FocusTab.CONVERSATIONS -> {
                    activeTab = InstallationTab.CONVERSATIONS
                    selectedConversationId = target.conversationId
                }
                OpenDeepLinkTarget.FocusTab.BOARD_DELIVERABLES -> {
                    activeTab = InstallationTab.BOARD_DELIVERABLES
                }
            }
            onConsumeRoutedOpenTarget()
        }
    }

    suspend fun refreshAll() {
        syncError = null
        try {
            inboxCoordinator.flushPendingDecisions()
            conversationCoordinator.flushPendingConversationsQueue()
            conversationCoordinator.syncDurableChanges()
            inboxItems = inboxCoordinator.refreshInbox()
            devices = coordinator.listDevices(connectionId)
            runCatching { pushSyncCoordinator?.syncConnectionPush(connectionId) }
        } catch (err: ApiEnvelopeException) {
            if (err.envelope.isAccessRemoved) {
                onAccessRemoved(projection.displayName)
                return
            }
            syncError = "同步失败（${err.envelope.code}）：${err.envelope.error}"
        } catch (err: Throwable) {
            syncError = err.message
        }
    }

    LaunchedEffect(connectionId) {
        refreshAll()
    }

    // Foreground global durable events stream (`GET /api/v1/events`)
    LaunchedEffect(connectionId) {
        runCatching {
            conversationCoordinator.collectGlobalEventsStream {
                inboxItems = inboxCoordinator.readCachedItems()
            }
        }.onFailure { err ->
            if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                onAccessRemoved(projection.displayName)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(projection.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "HTTPS + 15m 内存令牌 / /changes 持久增量同步",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(onClick = { scope.launch { refreshAll() } }) {
                    Text("刷新")
                }
            }
        }

        InstallationSubNavigationRow(
            selectedTab = activeTab,
            needsYouCount = inboxItems.size,
            onSelectTab = { activeTab = it },
        )

        syncError?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        when (activeTab) {
            InstallationTab.NEEDS_YOU -> {
                NeedsYouInboxSection(
                    items = inboxItems,
                    isOnline = true,
                    coordinator = inboxCoordinator,
                    highlightedInboxId = highlightedInboxId,
                    onClearHighlightedInboxId = { highlightedInboxId = null },
                    onItemsChanged = { inboxItems = it },
                    onAccessRemoved = { onAccessRemoved(projection.displayName) },
                )
            }

            InstallationTab.CONVERSATIONS -> {
                ConversationsSection(
                    connectionId = connectionId,
                    isOnline = true,
                    coordinator = conversationCoordinator,
                    selectedConversationId = selectedConversationId,
                    onSelectConversationId = { selectedConversationId = it },
                    onOpenDeliverableId = { delivId ->
                        requestedDeliverableId = delivId
                        activeTab = InstallationTab.BOARD_DELIVERABLES
                    },
                    onAccessRemoved = { onAccessRemoved(projection.displayName) },
                )
            }

            InstallationTab.BOARD_DELIVERABLES -> {
                WorkBoardAndDeliverablesSection(
                    connectionId = connectionId,
                    isOnline = true,
                    coordinator = conversationCoordinator,
                    requestedDeliverableId = requestedDeliverableId,
                    onConsumeRequestedDeliverableId = { requestedDeliverableId = null },
                    onJumpToConversation = { convId ->
                        selectedConversationId = convId
                        activeTab = InstallationTab.CONVERSATIONS
                    },
                    onAccessRemoved = { onAccessRemoved(projection.displayName) },
                )
            }

            InstallationTab.DESKTOP_VIEWER -> {
                ReadOnlyDesktopVncSection(
                    connectionId = connectionId,
                    isOnline = true,
                    coordinator = desktopCoordinator,
                    onAccessRemoved = { onAccessRemoved(projection.displayName) },
                )
            }

            InstallationTab.DEVICES -> {
                PushPreferencesSectionCard(
                    connectionId = connectionId,
                    isOnline = true,
                    store = store,
                    pushSyncCoordinator = pushSyncCoordinator,
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("我的登录设备 (/api/v1/devices)", style = MaterialTheme.typography.titleMedium)
                        if (devices.isEmpty()) {
                            Text("暂无已登记设备记录。", style = MaterialTheme.typography.bodySmall)
                        } else {
                            for (dev in devices) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        val selfTag = if (dev.isCurrentDevice) "（本机）" else ""
                                        Text("${dev.name}$selfTag", style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            listOfNotNull(dev.platform, dev.lastSeenAt).joinToString(" · "),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                runCatching {
                                                    if (coordinator.revokeDevice(connectionId, dev.id) && !dev.isCurrentDevice) {
                                                        devices = coordinator.listDevices(connectionId)
                                                    }
                                                }.onFailure { err ->
                                                    if (err is ApiEnvelopeException && err.envelope.isAccessRemoved) {
                                                        onAccessRemoved(projection.displayName)
                                                    }
                                                }
                                            }
                                        },
                                    ) {
                                        Text(if (dev.isCurrentDevice) "退出本机" else "吊销")
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    coordinator.signOut(connectionId)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("退出该组织并擦除本地加密缓存")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonalMacInstallationPane(
    installationId: String,
    store: InstallationStore,
    state: PersonalMacSessionState,
    inboxCoordinator: NeedsYouInboxCoordinator,
    conversationCoordinator: ConversationAndBoardCoordinator,
    desktopCoordinator: ReadOnlyDesktopSessionCoordinator,
    pushSyncCoordinator: DevicePushSyncCoordinator?,
    routedOpenTarget: OpenDeepLinkTarget?,
    onConsumeRoutedOpenTarget: () -> Unit,
    pendingOfflineActions: Int,
    pairedDevices: List<PairedDeviceSummary>,
    onReconnect: () -> Unit,
    onRefreshDevices: () -> Unit,
    onRevokeDevice: (String) -> Unit,
    onUnpairMac: () -> Unit,
) {
    var activeTab by remember(installationId) { mutableStateOf(InstallationTab.NEEDS_YOU) }
    var highlightedInboxId by remember(installationId) { mutableStateOf<String?>(null) }
    var selectedConversationId by remember(installationId) { mutableStateOf<String?>(null) }
    var requestedDeliverableId by remember(installationId) { mutableStateOf<String?>(null) }
    var inboxItems by remember(state) {
        mutableStateOf(inboxCoordinator.readCachedItems())
    }
    val isOnline = state is PersonalMacSessionState.Online

    LaunchedEffect(routedOpenTarget, installationId) {
        val target = routedOpenTarget ?: return@LaunchedEffect
        val resolved = LumenBoxDeepLinkRouter.resolveTargetConnection(store, target)
        if (resolved?.id == installationId) {
            when (target.preferredTab) {
                OpenDeepLinkTarget.FocusTab.NEEDS_YOU -> {
                    activeTab = InstallationTab.NEEDS_YOU
                    highlightedInboxId = target.inboxId
                }
                OpenDeepLinkTarget.FocusTab.CONVERSATIONS -> {
                    activeTab = InstallationTab.CONVERSATIONS
                    selectedConversationId = target.conversationId
                }
                OpenDeepLinkTarget.FocusTab.BOARD_DELIVERABLES -> {
                    activeTab = InstallationTab.BOARD_DELIVERABLES
                }
            }
            onConsumeRoutedOpenTarget()
        }
    }

    LaunchedEffect(isOnline) {
        if (isOnline) {
            runCatching {
                inboxCoordinator.flushPendingDecisions()
                conversationCoordinator.flushPendingConversationsQueue()
                conversationCoordinator.syncDurableChanges()
                inboxItems = inboxCoordinator.refreshInbox()
                pushSyncCoordinator?.syncConnectionPush(installationId)
            }
            runCatching {
                conversationCoordinator.collectGlobalEventsStream {
                    inboxItems = inboxCoordinator.readCachedItems()
                }
            }
        }
    }

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

        InstallationSubNavigationRow(
            selectedTab = activeTab,
            needsYouCount = inboxItems.size,
            onSelectTab = { activeTab = it },
        )

        when (activeTab) {
            InstallationTab.NEEDS_YOU -> {
                NeedsYouInboxSection(
                    items = inboxItems,
                    isOnline = isOnline,
                    pendingOfflineActions = pendingOfflineActions,
                    coordinator = inboxCoordinator,
                    highlightedInboxId = highlightedInboxId,
                    onClearHighlightedInboxId = { highlightedInboxId = null },
                    onItemsChanged = { inboxItems = it },
                )
            }

            InstallationTab.CONVERSATIONS -> {
                ConversationsSection(
                    connectionId = installationId,
                    isOnline = isOnline,
                    coordinator = conversationCoordinator,
                    selectedConversationId = selectedConversationId,
                    onSelectConversationId = { selectedConversationId = it },
                    onOpenDeliverableId = { delivId ->
                        requestedDeliverableId = delivId
                        activeTab = InstallationTab.BOARD_DELIVERABLES
                    },
                )
            }

            InstallationTab.BOARD_DELIVERABLES -> {
                WorkBoardAndDeliverablesSection(
                    connectionId = installationId,
                    isOnline = isOnline,
                    coordinator = conversationCoordinator,
                    requestedDeliverableId = requestedDeliverableId,
                    onConsumeRequestedDeliverableId = { requestedDeliverableId = null },
                    onJumpToConversation = { convId ->
                        selectedConversationId = convId
                        activeTab = InstallationTab.CONVERSATIONS
                    },
                )
            }

            InstallationTab.DESKTOP_VIEWER -> {
                ReadOnlyDesktopVncSection(
                    connectionId = installationId,
                    isOnline = isOnline,
                    coordinator = desktopCoordinator,
                )
            }

            InstallationTab.DEVICES -> {
                PushPreferencesSectionCard(
                    connectionId = installationId,
                    isOnline = isOnline,
                    store = store,
                    pushSyncCoordinator = pushSyncCoordinator,
                )
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
                                if (!device.isCurrentDevice && isOnline) {
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

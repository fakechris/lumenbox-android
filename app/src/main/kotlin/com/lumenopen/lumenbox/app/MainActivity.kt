package com.lumenopen.lumenbox.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lumenopen.lumenbox.app.security.AndroidKeyStoreVault
import com.lumenopen.lumenbox.app.security.AndroidKeystoreStepUpSigner
import com.lumenopen.lumenbox.app.security.StepUpBiometricGateway
import com.lumenopen.lumenbox.app.ui.MultiInstallationShell
import com.lumenopen.lumenbox.auth.OrganisationTokenManager
import com.lumenopen.lumenbox.auth.PkceOAuthClient
import com.lumenopen.lumenbox.push.DevicePushSyncCoordinator
import com.lumenopen.lumenbox.push.LumenBoxDeepLinkRouter
import com.lumenopen.lumenbox.push.OpenDeepLinkTarget
import com.lumenopen.lumenbox.push.PushGatewayClient
import com.lumenopen.lumenbox.relay.WebSocketRelayConnector
import com.lumenopen.lumenbox.session.OrganisationSessionCoordinator
import com.lumenopen.lumenbox.session.PersonalMacSessionCoordinator
import com.lumenopen.lumenbox.storage.InstallationStore
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var installationStore: InstallationStore
    private lateinit var personalCoordinator: PersonalMacSessionCoordinator
    private lateinit var organisationCoordinator: OrganisationSessionCoordinator
    private lateinit var pushSyncCoordinator: DevicePushSyncCoordinator
    private var pendingPairUri by mutableStateOf<String?>(null)
    private var pendingOAuthCallbackUri by mutableStateOf<String?>(null)
    private var pendingOpenTarget by mutableStateOf<OpenDeepLinkTarget?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = AndroidKeyStoreVault(applicationContext)
        val rootDir = File(applicationContext.noBackupFilesDir, "installations")
        val lockDir = File(applicationContext.noBackupFilesDir, "locks")

        installationStore = InstallationStore(
            vault = vault,
            rootDirectory = rootDir,
        )
        val connector = WebSocketRelayConnector(
            lanDiscovery = NsdLanDiscoveryProvider(applicationContext),
        )
        personalCoordinator = PersonalMacSessionCoordinator(
            store = installationStore,
            connector = connector,
        )

        val oauthClient = PkceOAuthClient(
            unauthenticatedRoundTrip = { baseOrigin, req ->
                val headers = LinkedHashMap(req.headers)
                req.idempotencyKey?.let { headers["Idempotency-Key"] = it }
                OrganisationSessionCoordinator.defaultHttpsRoundTrip(baseOrigin, req, headers)
            },
        )
        val tokenManager = OrganisationTokenManager(
            store = installationStore,
            vault = vault,
            lockDirectory = lockDir,
            refreshRoundTrip = { baseOrigin, req ->
                val headers = LinkedHashMap(req.headers)
                req.idempotencyKey?.let { headers["Idempotency-Key"] = it }
                OrganisationSessionCoordinator.defaultHttpsRoundTrip(baseOrigin, req, headers)
            },
        )
        val stepUpSigner = runCatching { AndroidKeystoreStepUpSigner() }.getOrNull()
        val stepUpGateway = stepUpSigner?.let { StepUpBiometricGateway(it) }
        organisationCoordinator = OrganisationSessionCoordinator(
            store = installationStore,
            vault = vault,
            oauthClient = oauthClient,
            tokenManager = tokenManager,
            stepUpSigner = stepUpSigner,
        )
        val pushGatewayClient = PushGatewayClient(store = installationStore)
        pushSyncCoordinator = DevicePushSyncCoordinator(
            store = installationStore,
            gatewayClient = pushGatewayClient,
            transportForConnection = { connId ->
                val conn = installationStore.findById(connId)
                when (conn?.kind) {
                    com.lumenopen.lumenbox.storage.InstallationConnection.Kind.PERSONAL_MAC ->
                        personalCoordinator.activeApiTransport()
                    com.lumenopen.lumenbox.storage.InstallationConnection.Kind.ORGANISATION ->
                        organisationCoordinator.transportForOrganisation(connId)
                    null -> null
                }
            },
        )
        LumenBoxMessagingBridge.ensureNotificationChannel(applicationContext)

        routeIncomingDeepLink(intent)

        setContent {
            MaterialTheme {
                MultiInstallationShell(
                    store = installationStore,
                    personalCoordinator = personalCoordinator,
                    organisationCoordinator = organisationCoordinator,
                    pushSyncCoordinator = pushSyncCoordinator,
                    stepUpGateway = stepUpGateway,
                    pendingDeepLinkPairUri = pendingPairUri,
                    onConsumeDeepLinkPairUri = { pendingPairUri = null },
                    pendingOAuthCallbackUri = pendingOAuthCallbackUri,
                    onConsumeOAuthCallbackUri = { pendingOAuthCallbackUri = null },
                    pendingOpenDeepLinkTarget = pendingOpenTarget,
                    onConsumeOpenDeepLinkTarget = { pendingOpenTarget = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeIncomingDeepLink(intent)
    }

    override fun onDestroy() {
        if (isFinishing && ::personalCoordinator.isInitialized) {
            personalCoordinator.closeActiveSession()
        }
        super.onDestroy()
    }

    private fun routeIncomingDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (!data.scheme.equals("lumenbox", ignoreCase = true)) return
        when (data.host?.lowercase()) {
            "pair" -> pendingPairUri = data.toString()
            "oauth" -> if (data.path == "/callback") {
                pendingOAuthCallbackUri = data.toString()
            }
            "open" -> {
                LumenBoxDeepLinkRouter.parseOpenUri(data.toString())?.let { target ->
                    pendingOpenTarget = target
                }
            }
        }
    }
}

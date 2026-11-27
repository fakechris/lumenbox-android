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
import com.lumenopen.lumenbox.relay.WebSocketRelayConnector
import com.lumenopen.lumenbox.session.OrganisationSessionCoordinator
import com.lumenopen.lumenbox.session.PersonalMacSessionCoordinator
import com.lumenopen.lumenbox.storage.InstallationStore
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var installationStore: InstallationStore
    private lateinit var personalCoordinator: PersonalMacSessionCoordinator
    private lateinit var organisationCoordinator: OrganisationSessionCoordinator
    private var pendingPairUri by mutableStateOf<String?>(null)
    private var pendingOAuthCallbackUri by mutableStateOf<String?>(null)

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

        routeIncomingDeepLink(intent)

        setContent {
            MaterialTheme {
                MultiInstallationShell(
                    store = installationStore,
                    personalCoordinator = personalCoordinator,
                    organisationCoordinator = organisationCoordinator,
                    stepUpGateway = stepUpGateway,
                    pendingDeepLinkPairUri = pendingPairUri,
                    onConsumeDeepLinkPairUri = { pendingPairUri = null },
                    pendingOAuthCallbackUri = pendingOAuthCallbackUri,
                    onConsumeOAuthCallbackUri = { pendingOAuthCallbackUri = null },
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
        if (data.scheme != "lumenbox") return
        when (data.host) {
            "pair" -> pendingPairUri = data.toString()
            "oauth" -> if (data.path == "/callback") {
                pendingOAuthCallbackUri = data.toString()
            }
        }
    }
}

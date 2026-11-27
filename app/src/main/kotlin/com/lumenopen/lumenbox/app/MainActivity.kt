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
import com.lumenopen.lumenbox.app.ui.MultiInstallationShell
import com.lumenopen.lumenbox.relay.WebSocketRelayConnector
import com.lumenopen.lumenbox.session.PersonalMacSessionCoordinator
import com.lumenopen.lumenbox.storage.InstallationStore
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var installationStore: InstallationStore
    private lateinit var personalCoordinator: PersonalMacSessionCoordinator
    private var pendingPairUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val vault = AndroidKeyStoreVault(applicationContext)
        installationStore = InstallationStore(
            vault = vault,
            rootDirectory = File(applicationContext.noBackupFilesDir, "installations"),
        )
        val connector = WebSocketRelayConnector(
            lanDiscovery = NsdLanDiscoveryProvider(applicationContext),
        )
        personalCoordinator = PersonalMacSessionCoordinator(
            store = installationStore,
            connector = connector,
        )
        extractPairDeepLink(intent)?.let { pendingPairUri = it }

        setContent {
            MaterialTheme {
                MultiInstallationShell(
                    store = installationStore,
                    personalCoordinator = personalCoordinator,
                    pendingDeepLinkPairUri = pendingPairUri,
                    onConsumeDeepLinkPairUri = { pendingPairUri = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractPairDeepLink(intent)?.let { pendingPairUri = it }
    }

    override fun onDestroy() {
        if (isFinishing && ::personalCoordinator.isInitialized) {
            personalCoordinator.closeActiveSession()
        }
        super.onDestroy()
    }

    private fun extractPairDeepLink(intent: Intent?): String? {
        val data = intent?.data ?: return null
        if (data.scheme == "lumenbox" && data.host == "pair") {
            return data.toString()
        }
        return null
    }
}

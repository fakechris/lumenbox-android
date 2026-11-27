package com.lumenopen.lumenbox.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import com.lumenopen.lumenbox.app.security.AndroidKeyStoreVault
import com.lumenopen.lumenbox.auth.OrganisationTokenManager
import com.lumenopen.lumenbox.auth.PkceOAuthClient
import com.lumenopen.lumenbox.contract.WakePayload
import com.lumenopen.lumenbox.push.DevicePushSyncCoordinator
import com.lumenopen.lumenbox.push.DisplayedNotification
import com.lumenopen.lumenbox.push.NativePushRegistrationToken
import com.lumenopen.lumenbox.push.PushChannelKind
import com.lumenopen.lumenbox.push.PushGatewayClient
import com.lumenopen.lumenbox.push.WakeNotificationCoordinator
import com.lumenopen.lumenbox.relay.WebSocketRelayConnector
import com.lumenopen.lumenbox.session.OrganisationSessionCoordinator
import com.lumenopen.lumenbox.session.PersonalMacSessionCoordinator
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.EncryptedRelayTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared notification constants & Android notification channel / `Notification.Builder` helper (`docs/110 §5.3`).
 */
object LumenBoxMessagingBridge {
    const val CHANNEL_ID = "lumenbox_needs_you"
    const val CHANNEL_NAME = "LumenBox 待决与任务唤醒"
    const val ACTION_PUSH_WAKE = "com.lumenopen.lumenbox.action.PUSH_WAKE"
    const val ACTION_PUSH_NEW_TOKEN = "com.lumenopen.lumenbox.action.PUSH_NEW_TOKEN"
    const val EXTRA_CONN_ID = "connId"
    const val EXTRA_SEQ = "seq"
    const val EXTRA_NATIVE_TOKEN = "nativePushToken"
    const val EXTRA_PUSH_CHANNEL = "pushChannel"

    fun ensureNotificationChannel(context: Context): NotificationManager {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "零明文唤醒后由本机直连服务器拉取的待决审批与完成提醒"
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        mgr.createNotificationChannel(channel)
        return mgr
    }

    fun postSystemNotification(context: Context, displayed: DisplayedNotification) {
        val mgr = ensureNotificationChannel(context)
        val notificationId = (displayed.notificationKey.hashCode() and 0x7fffffff).coerceAtLeast(1000)

        val contentIntent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            displayed.deepLinkUri?.let { data = Uri.parse(it) }
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            notificationId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val publicVersion = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(displayed.publicLockScreenTitle)
            .setContentText(displayed.publicLockScreenBody)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build()

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(displayed.title)
            .setContentText(displayed.body)
            .setStyle(Notification.BigTextStyle().bigText(displayed.body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!displayed.isGenericFallback)

        if (displayed.redactOnLockScreen) {
            builder.setVisibility(Notification.VISIBILITY_PRIVATE)
            builder.setPublicVersion(publicVersion)
        } else {
            builder.setVisibility(Notification.VISIBILITY_PUBLIC)
        }

        mgr.notify(notificationId, builder.build())
    }
}

/**
 * Real Android two-phase Wake-Only background push service (`docs/110 §5.3–§5.4`, INV-2259).
 *
 * Accepts zero-content `{ connId, seq }` wake intents and native token rotation events from both
 * Google FCM and domestic OEM vendor system push adapters:
 * 1. **Phase 1 (Immediate)**: Synchronously posts a generic fallback notification via
 *    [LumenBoxMessagingBridge.postSystemNotification] before starting any network I/O.
 * 2. **Phase 2 (Authoritative Pull)**: Connects directly to the matching organisation server (HTTPS
 *    with `CrossProcessFileLock` token refresh) or personal Mac (`Noise_IK` session), fetches
 *    `GET /api/v1/notifications/{seq}`, and updates the notification in place while enforcing
 *    lock-screen redaction (`VISIBILITY_PRIVATE` + redacted `publicVersion`).
 */
class LumenBoxMessagingService : Service() {
    companion object {
        private const val BACKGROUND_PULL_TIMEOUT_MS = 9_000L

        fun enqueueWake(context: Context, wake: WakePayload) {
            val intent = Intent(context, LumenBoxMessagingService::class.java).apply {
                action = LumenBoxMessagingBridge.ACTION_PUSH_WAKE
                putExtra(LumenBoxMessagingBridge.EXTRA_CONN_ID, wake.connId)
                putExtra(LumenBoxMessagingBridge.EXTRA_SEQ, wake.seq.toString())
            }
            runCatching { context.startService(intent) }
        }

        fun enqueueNativeTokenRefresh(
            context: Context,
            channel: PushChannelKind,
            nativeToken: String,
        ) {
            val intent = Intent(context, LumenBoxMessagingService::class.java).apply {
                action = LumenBoxMessagingBridge.ACTION_PUSH_NEW_TOKEN
                putExtra(LumenBoxMessagingBridge.EXTRA_PUSH_CHANNEL, channel.wireName)
                putExtra(LumenBoxMessagingBridge.EXTRA_NATIVE_TOKEN, nativeToken)
            }
            runCatching { context.startService(intent) }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfIfIdle(startId)
            return START_NOT_STICKY
        }

        activeJobs.incrementAndGet()
        serviceScope.launch {
            try {
                when (intent.action) {
                    LumenBoxMessagingBridge.ACTION_PUSH_NEW_TOKEN -> handleNewNativeToken(intent)
                    else -> handleWakeIntent(intent)
                }
            } finally {
                if (activeJobs.decrementAndGet() <= 0) {
                    stopSelfResult(startId)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun handleWakeIntent(intent: Intent) {
        val appCtx = applicationContext
        val store = openInstallationStore(appCtx)
        val orgCoordinator = buildOrganisationCoordinator(appCtx, store)
        var transientPersonalCoordinator: PersonalMacSessionCoordinator? = null

        val wakeCoordinator = WakeNotificationCoordinator(
            store = store,
            transportForConnection = { conn ->
                resolveBackgroundTransport(
                    appCtx = appCtx,
                    store = store,
                    orgCoordinator = orgCoordinator,
                    conn = conn,
                    onPersonalCoordinatorCreated = { transientPersonalCoordinator = it },
                )
            },
            postOrUpdateNotification = { displayed ->
                LumenBoxMessagingBridge.postSystemNotification(appCtx, displayed)
            },
        )

        val dataMap = extractWakeDataMap(intent)
        val wake = wakeCoordinator.parseWakeEnvelope(dataMap) ?: return
        try {
            withTimeoutOrNull(BACKGROUND_PULL_TIMEOUT_MS) {
                wakeCoordinator.handleWakeMessage(wake)
            }
        } finally {
            transientPersonalCoordinator?.closeActiveSession()
        }
    }

    private suspend fun handleNewNativeToken(intent: Intent) {
        val nativeToken = intent.getStringExtra(LumenBoxMessagingBridge.EXTRA_NATIVE_TOKEN)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val channelRaw = intent.getStringExtra(LumenBoxMessagingBridge.EXTRA_PUSH_CHANNEL).orEmpty()
        val channel = PushChannelKind.fromWire(channelRaw)

        val appCtx = applicationContext
        val store = openInstallationStore(appCtx)
        val orgCoordinator = buildOrganisationCoordinator(appCtx, store)
        val gatewayClient = PushGatewayClient(store = store)
        val syncCoordinator = DevicePushSyncCoordinator(
            store = store,
            gatewayClient = gatewayClient,
            channelAdapters = listOf(
                com.lumenopen.lumenbox.push.NativePushChannelAdapter {
                    NativePushRegistrationToken(channel = channel, nativeToken = nativeToken)
                }
            ),
            transportForConnection = { connId ->
                val conn = store.findById(connId)
                if (conn?.kind == InstallationConnection.Kind.ORGANISATION) {
                    orgCoordinator.transportForOrganisation(connId)
                } else {
                    null
                }
            },
        )
        runCatching {
            syncCoordinator.ensureCapabilityToken()
            syncCoordinator.syncAllConnections()
        }
    }

    private suspend fun resolveBackgroundTransport(
        appCtx: Context,
        store: InstallationStore,
        orgCoordinator: OrganisationSessionCoordinator,
        conn: InstallationConnection,
        onPersonalCoordinatorCreated: (PersonalMacSessionCoordinator) -> Unit,
    ): ApiTransport? {
        return when (conn.kind) {
            InstallationConnection.Kind.ORGANISATION ->
                orgCoordinator.transportForOrganisation(conn.id)

            InstallationConnection.Kind.PERSONAL_MAC -> {
                val connector = WebSocketRelayConnector(
                    lanDiscovery = NsdLanDiscoveryProvider(appCtx),
                )
                val personal = PersonalMacSessionCoordinator(
                    store = store,
                    connector = connector,
                )
                onPersonalCoordinatorCreated(personal)
                val session = runCatching { personal.reconnect() }.getOrNull() ?: return null
                EncryptedRelayTransport.overMux(session.mux)
            }
        }
    }

    private fun extractWakeDataMap(intent: Intent): Map<String, String> {
        val extras = intent.extras ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (key in extras.keySet()) {
            val strVal = extras.getString(key) ?: when {
                extras.containsKey(key) -> extras.getLong(key, Long.MIN_VALUE)
                    .takeIf { it != Long.MIN_VALUE }
                    ?.toString()
                else -> null
            }
            if (strVal != null) {
                out[key] = strVal
            }
        }
        return out
    }

    private fun openInstallationStore(appCtx: Context): InstallationStore {
        val vault = AndroidKeyStoreVault(appCtx)
        val rootDir = File(appCtx.noBackupFilesDir, "installations")
        return InstallationStore(vault = vault, rootDirectory = rootDir)
    }

    private fun buildOrganisationCoordinator(
        appCtx: Context,
        store: InstallationStore,
    ): OrganisationSessionCoordinator {
        val vault = AndroidKeyStoreVault(appCtx)
        val lockDir = File(appCtx.noBackupFilesDir, "locks")
        val oauthClient = PkceOAuthClient(
            unauthenticatedRoundTrip = { baseOrigin, req ->
                val headers = LinkedHashMap(req.headers)
                req.idempotencyKey?.let { headers["Idempotency-Key"] = it }
                OrganisationSessionCoordinator.defaultHttpsRoundTrip(baseOrigin, req, headers)
            },
        )
        val tokenManager = OrganisationTokenManager(
            store = store,
            vault = vault,
            lockDirectory = lockDir,
            refreshRoundTrip = { baseOrigin, req ->
                val headers = LinkedHashMap(req.headers)
                req.idempotencyKey?.let { headers["Idempotency-Key"] = it }
                OrganisationSessionCoordinator.defaultHttpsRoundTrip(baseOrigin, req, headers)
            },
        )
        return OrganisationSessionCoordinator(
            store = store,
            vault = vault,
            oauthClient = oauthClient,
            tokenManager = tokenManager,
        )
    }

    private fun stopSelfIfIdle(startId: Int) {
        if (activeJobs.get() <= 0) {
            stopSelfResult(startId)
        }
    }
}

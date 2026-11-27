package com.lumenopen.lumenbox.app.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.lumenopen.lumenbox.app.NsdLanDiscoveryProvider
import com.lumenopen.lumenbox.app.security.AndroidKeyStoreVault
import com.lumenopen.lumenbox.auth.OrganisationTokenManager
import com.lumenopen.lumenbox.auth.PkceOAuthClient
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.conversation.ConversationAndBoardCoordinator
import com.lumenopen.lumenbox.inbox.NeedsYouInboxCoordinator
import com.lumenopen.lumenbox.relay.WebSocketRelayConnector
import com.lumenopen.lumenbox.session.OrganisationSessionCoordinator
import com.lumenopen.lumenbox.session.PersonalMacSessionCoordinator
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WorkManager background sync worker (`docs/110 §2`, INV-2257).
 *
 * Replays idempotent multimedia `/uploads` + `/conversations/{id}/messages` bundles, `ShowUI`
 * card actions, and non-secret `Needs you` decisions once network connectivity returns, then
 * advances local state via `GET /api/v1/changes?since=<durableSeq>`.
 */
class OutboundSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val targetConnectionId = inputData.getString(KEY_CONNECTION_ID)
        val vault = AndroidKeyStoreVault(applicationContext)
        val store = InstallationStore(
            vault = vault,
            rootDirectory = File(applicationContext.noBackupFilesDir, "installations"),
        )
        val lockDir = File(applicationContext.noBackupFilesDir, "locks")
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
        val oauthClient = PkceOAuthClient(
            unauthenticatedRoundTrip = { baseOrigin, req ->
                val headers = LinkedHashMap(req.headers)
                req.idempotencyKey?.let { headers["Idempotency-Key"] = it }
                OrganisationSessionCoordinator.defaultHttpsRoundTrip(baseOrigin, req, headers)
            },
        )
        val orgCoordinator = OrganisationSessionCoordinator(
            store = store,
            vault = vault,
            oauthClient = oauthClient,
            tokenManager = tokenManager,
        )

        val candidates = if (!targetConnectionId.isNullOrBlank()) {
            listOfNotNull(store.findById(targetConnectionId))
        } else {
            store.listConnections()
        }

        var shouldRetry = false
        for (conn in candidates) {
            val personalCoordinator = if (conn.kind == InstallationConnection.Kind.PERSONAL_MAC) {
                PersonalMacSessionCoordinator(
                    store = store,
                    connector = WebSocketRelayConnector(
                        lanDiscovery = NsdLanDiscoveryProvider(applicationContext),
                    ),
                )
            } else {
                null
            }
            try {
                val transport = when (conn.kind) {
                    InstallationConnection.Kind.PERSONAL_MAC -> {
                        val macCoord = personalCoordinator!!
                        val session = runCatching { macCoord.reconnect() }.getOrNull()
                        if (session == null) {
                            shouldRetry = true
                            continue
                        }
                        macCoord.activeApiTransport()
                    }
                    InstallationConnection.Kind.ORGANISATION ->
                        orgCoordinator.transportForOrganisation(conn.id)
                }

                val inboxCoordinator = NeedsYouInboxCoordinator(
                    connectionId = conn.id,
                    store = store,
                    transportProvider = { transport },
                )
                val conversationCoordinator = ConversationAndBoardCoordinator(
                    connectionId = conn.id,
                    store = store,
                    transportProvider = { transport },
                )

                inboxCoordinator.flushPendingDecisions()
                conversationCoordinator.flushPendingConversationsQueue()
                conversationCoordinator.syncDurableChanges()

                if (conversationCoordinator.pendingPromptCount() > 0) {
                    shouldRetry = true
                }
            } catch (err: ApiEnvelopeException) {
                if (!err.envelope.isAccessRemoved) {
                    shouldRetry = true
                }
            } catch (_: Throwable) {
                shouldRetry = true
            } finally {
                personalCoordinator?.closeActiveSession()
            }
        }

        return if (shouldRetry) Result.retry() else Result.success()
    }

    companion object {
        private const val KEY_CONNECTION_ID = "connection_id"
        private const val WORK_PREFIX = "lumenbox_outbound_sync"

        fun enqueueForConnection(context: Context, connectionId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<OutboundSyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_CONNECTION_ID to connectionId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                "${WORK_PREFIX}_$connectionId",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                req,
            )
        }
    }
}

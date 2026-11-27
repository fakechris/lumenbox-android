package com.lumenopen.lumenbox.push

import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.NotificationSummary
import com.lumenopen.lumenbox.contract.WakePayload
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import java.nio.charset.StandardCharsets

object LumenPushToken {
    private val REGEX = Regex("^LumenPushToken\\[[0-9A-Za-z]{22}]$")
    fun isValid(token: String): Boolean = REGEX.matches(token)
}

data class DisplayedNotification(
    val notificationKey: String,
    val title: String,
    val body: String,
    val isGenericFallback: Boolean,
    val redactOnLockScreen: Boolean = false,
    val publicLockScreenTitle: String = WakeNotificationCoordinator.FALLBACK_TITLE,
    val publicLockScreenBody: String = WakeNotificationCoordinator.FALLBACK_BODY,
    val deepLinkUri: String? = null,
)

/**
 * Android two-phase Wake-Only coordinator (`docs/110 §5.3`, INV-2259):
 * unlike iOS alert pushes, an Android high-priority FCM / vendor data message displays nothing on its own.
 * [handleWakeMessage] therefore posts a generic fallback notification **immediately** upon wake,
 * then pulls `GET /api/v1/notifications/{seq}` from the authoritative Mac (`Noise_IK`) or organisation
 * server (`HTTPS`) and updates the notification in place (honouring lock-screen redaction when configured,
 * and attaching a `lumenbox://open?...` deep-link intent).
 */
class WakeNotificationCoordinator(
    private val store: InstallationStore,
    private val transportForConnection: suspend (InstallationConnection) -> ApiTransport?,
    private val postOrUpdateNotification: (DisplayedNotification) -> Unit,
) {
    companion object {
        const val FALLBACK_TITLE = "LumenBox"
        const val FALLBACK_BODY = "An item needs your attention"
    }

    suspend fun handleWakeMessage(wake: WakePayload): DisplayedNotification {
        val key = "${wake.connId}:${wake.seq}"
        val connection = store.findByConnId(wake.connId)
        val initialTitle = connection?.displayName ?: FALLBACK_TITLE
        val fallbackDeepLink = LumenBoxDeepLinkRouter.buildNotificationOpenUri(
            connId = wake.connId,
            seq = wake.seq,
        )

        // Phase 1: Immediately post generic fallback before starting any network I/O!
        val fallback = DisplayedNotification(
            notificationKey = key,
            title = initialTitle,
            body = FALLBACK_BODY,
            isGenericFallback = true,
            redactOnLockScreen = connection?.redactLockScreenNotifications ?: true,
            publicLockScreenTitle = initialTitle,
            publicLockScreenBody = FALLBACK_BODY,
            deepLinkUri = fallbackDeepLink,
        )
        postOrUpdateNotification(fallback)

        val transport = connection?.let { runCatching { transportForConnection(it) }.getOrNull() }
            ?: return fallback
        return try {
            // Phase 2: Pull localized notification summary from authoritative server/Mac
            val res = transport.send(
                TransportRequest(method = "GET", path = "/api/v1/notifications/${wake.seq}")
            )
            if (res.statusCode != 200) return fallback
            val summary = LumenBoxJson.default.decodeFromString(
                NotificationSummary.serializer(),
                res.body.toString(StandardCharsets.UTF_8),
            )
            val richDeepLink = LumenBoxDeepLinkRouter.buildNotificationOpenUri(
                connId = wake.connId,
                seq = wake.seq,
                inboxId = summary.inboxId,
                conversationId = summary.conversationId,
                workId = summary.workId,
            )
            val updated = if (connection.redactLockScreenNotifications) {
                DisplayedNotification(
                    notificationKey = key,
                    title = connection.displayName,
                    body = FALLBACK_BODY,
                    isGenericFallback = false,
                    redactOnLockScreen = true,
                    publicLockScreenTitle = connection.displayName,
                    publicLockScreenBody = FALLBACK_BODY,
                    deepLinkUri = richDeepLink,
                )
            } else {
                DisplayedNotification(
                    notificationKey = key,
                    title = summary.title,
                    body = summary.body,
                    isGenericFallback = false,
                    redactOnLockScreen = false,
                    publicLockScreenTitle = connection.displayName,
                    publicLockScreenBody = FALLBACK_BODY,
                    deepLinkUri = richDeepLink,
                )
            }
            postOrUpdateNotification(updated)
            updated
        } catch (_: Exception) {
            fallback
        }
    }

    /** Parses a raw zero-content `{ connId, seq }` map or JSON data message from FCM or domestic vendor push. */
    fun parseWakeEnvelope(dataMap: Map<String, String>): WakePayload? {
        val connId = dataMap["connId"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val seq = dataMap["seq"]?.trim()?.toLongOrNull() ?: return null
        return WakePayload(connId = connId, seq = seq)
    }
}

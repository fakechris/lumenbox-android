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
)

/**
 * Android two-phase Wake-Only coordinator (`docs/110 §5.3`):
 * unlike iOS alert pushes, an Android high-priority FCM data message displays nothing on its own.
 * [handleWakeMessage] therefore posts a generic fallback notification **immediately** upon wake,
 * then pulls `GET /api/v1/notifications/{seq}` from the authoritative Mac or organisation server
 * and updates the notification in place (honouring lock-screen redaction when configured).
 */
class WakeNotificationCoordinator(
    private val store: InstallationStore,
    private val transportForConnection: (InstallationConnection) -> ApiTransport?,
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

        // Phase 1: Immediately post generic fallback before starting any network I/O!
        val fallback = DisplayedNotification(
            notificationKey = key,
            title = initialTitle,
            body = FALLBACK_BODY,
            isGenericFallback = true,
        )
        postOrUpdateNotification(fallback)

        val transport = connection?.let(transportForConnection) ?: return fallback
        return try {
            // Phase 2: Pull localized notification summary from authoritative server/Mac
            val res = transport.send(
                TransportRequest(method = "GET", path = "/api/v1/notifications/${wake.seq}")
            )
            if (res.statusCode != 200) return fallback
            val summary = LumenBoxJson.default.decodeFromString(
                NotificationSummary.serializer(),
                res.body.toString(StandardCharsets.UTF_8)
            )
            val updated = if (connection.redactLockScreenNotifications) {
                DisplayedNotification(
                    notificationKey = key,
                    title = connection.displayName,
                    body = FALLBACK_BODY,
                    isGenericFallback = false,
                )
            } else {
                DisplayedNotification(
                    notificationKey = key,
                    title = summary.title,
                    body = summary.body,
                    isGenericFallback = false,
                )
            }
            postOrUpdateNotification(updated)
            updated
        } catch (_: Exception) {
            fallback
        }
    }
}

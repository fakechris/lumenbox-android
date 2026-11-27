package com.lumenopen.lumenbox.app

/**
 * Entry hook for FCM / vendor push wake messages (`docs/110 §5.3`).
 * Delegates `{ connId, seq }` envelopes to [com.lumenopen.lumenbox.push.WakeNotificationCoordinator]
 * so a generic fallback notification is posted immediately before background pull.
 */
object LumenBoxMessagingBridge {
    const val CHANNEL_ID = "lumenbox_needs_you"
}

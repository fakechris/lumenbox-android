package com.lumenopen.lumenbox

import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.generated.models.GeneratedDevicePushConfigRequest
import com.lumenopen.lumenbox.generated.models.GeneratedDevicePushPreferences
import com.lumenopen.lumenbox.push.ConnectionPushPreferences
import com.lumenopen.lumenbox.push.DevicePushSyncCoordinator
import com.lumenopen.lumenbox.push.DisplayedNotification
import com.lumenopen.lumenbox.push.LumenBoxDeepLinkRouter
import com.lumenopen.lumenbox.push.NativePushChannelAdapter
import com.lumenopen.lumenbox.push.NativePushRegistrationToken
import com.lumenopen.lumenbox.push.OpenDeepLinkTarget
import com.lumenopen.lumenbox.push.PushChannelKind
import com.lumenopen.lumenbox.push.PushGatewayClient
import com.lumenopen.lumenbox.push.PushGatewayRegisterRequest
import com.lumenopen.lumenbox.push.PushNotificationLevel
import com.lumenopen.lumenbox.push.QuietHoursPreference
import com.lumenopen.lumenbox.push.WakeNotificationCoordinator
import com.lumenopen.lumenbox.storage.InMemoryKeyStoreVault
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class PushWakeAndDeepLinkTest {

    @Test
    fun `PushGatewayClient exchanges FCM or domestic vendor token for LumenPushToken capability, honours AGENTBOX_PUSH_GATEWAY_URL, and syncs PUT and DELETE self push`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2259-push-sync").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "org-cn",
                    connId = "c_org_cn_88",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "CN Enterprise",
                    serverOriginOrRelayUrl = "https://cn.lumenbox.example",
                )
            )

            val gatewayRequests = mutableListOf<Pair<String, TransportRequest>>()
            val issuedToken = "LumenPushToken[7kP9mQvR2xW8nL4jF6tB1z]"

            val gatewayClient = PushGatewayClient(
                store = store,
                envLookup = { name ->
                    if (name == PushGatewayClient.ENV_PUSH_GATEWAY_URL) "https://push-whitelabel.lumenbox.example/"
                    else null
                },
                unauthenticatedRoundTrip = { baseOrigin, req ->
                    gatewayRequests.add(baseOrigin to req)
                    when (req.path) {
                        "/v1/devices/register" -> TransportResponse(
                            statusCode = 200,
                            headers = mapOf("content-type" to "application/json"),
                            body = """{"pushToken":"$issuedToken","channel":"huawei_hms"}""".toByteArray(StandardCharsets.UTF_8),
                        )
                        "/v1/devices/unregister" -> TransportResponse(
                            statusCode = 200,
                            headers = mapOf("content-type" to "application/json"),
                            body = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8),
                        )
                        else -> TransportResponse(404, emptyMap(), ByteArray(0))
                    }
                },
            )
            assertEquals("https://push-whitelabel.lumenbox.example", gatewayClient.resolveGatewayOrigin())

            // Simulate non-GMS domestic phone: FCM returns null, Huawei HMS returns vendor token
            val fcmUnavailable = NativePushChannelAdapter { null }
            val huaweiAdapter = NativePushChannelAdapter {
                NativePushRegistrationToken(
                    channel = PushChannelKind.HUAWEI_HMS,
                    nativeToken = "hms_native_token_abc123",
                )
            }

            val hostPutRequests = mutableListOf<GeneratedDevicePushConfigRequest>()
            var hostDeleteCount = 0

            val hostTransport = ApiTransport { req ->
                when {
                    req.method == "PUT" && req.path == "/api/v1/devices/self/push" -> {
                        val parsed = LumenBoxJson.default.decodeFromString(
                            GeneratedDevicePushConfigRequest.serializer(),
                            String(req.body!!, StandardCharsets.UTF_8),
                        )
                        hostPutRequests.add(parsed)
                        TransportResponse(200, emptyMap(), """{"ok":true,"durableSeq":77}""".toByteArray(StandardCharsets.UTF_8))
                    }
                    req.method == "DELETE" && req.path == "/api/v1/devices/self/push" -> {
                        hostDeleteCount++
                        TransportResponse(200, emptyMap(), """{"ok":true,"durableSeq":78}""".toByteArray(StandardCharsets.UTF_8))
                    }
                    else -> TransportResponse(404, emptyMap(), ByteArray(0))
                }
            }

            val syncCoordinator = DevicePushSyncCoordinator(
                store = store,
                gatewayClient = gatewayClient,
                channelAdapters = listOf(fcmUnavailable, huaweiAdapter),
                transportForConnection = { id -> if (id == "org-cn") hostTransport else null },
            )

            val synced = syncCoordinator.syncConnectionPush(
                connectionId = "org-cn",
                preferencesOverride = ConnectionPushPreferences(
                    level = PushNotificationLevel.NEEDS_YOU_ONLY,
                    quietHours = QuietHoursPreference(
                        startLocalTime = "23:00",
                        endLocalTime = "08:00",
                        timezone = "Asia/Shanghai",
                    ),
                ),
            )
            assertTrue(synced)

            // Verify gateway received Huawei HMS registration on overridden AGENTBOX_PUSH_GATEWAY_URL
            assertEquals(1, gatewayRequests.size)
            assertEquals("https://push-whitelabel.lumenbox.example", gatewayRequests[0].first)
            val regWire = LumenBoxJson.default.decodeFromString(
                PushGatewayRegisterRequest.serializer(),
                String(gatewayRequests[0].second.body!!, StandardCharsets.UTF_8),
            )
            assertEquals("android", regWire.platform)
            assertEquals("huawei_hms", regWire.channel)
            assertEquals("hms_native_token_abc123", regWire.nativePushToken)
            assertTrue(regWire.deviceId.startsWith("and_"))

            // Verify host received PUT /api/v1/devices/self/push with LumenPushToken[...] + connId + preferences
            assertEquals(1, hostPutRequests.size)
            val putWire = hostPutRequests[0]
            assertEquals(issuedToken, putWire.pushToken)
            assertEquals("c_org_cn_88", putWire.connId)
            assertEquals("huawei_hms", putWire.channel)
            assertEquals(GeneratedDevicePushPreferences.Level.NEEDS_MINUS_YOU_MINUS_ONLY, putWire.preferences.level)
            assertEquals("Asia/Shanghai", putWire.preferences.quietHours?.timezone)

            // Turning level to OFF calls DELETE /api/v1/devices/self/push
            val disabled = syncCoordinator.syncConnectionPush(
                connectionId = "org-cn",
                preferencesOverride = ConnectionPushPreferences(level = PushNotificationLevel.OFF),
            )
            assertTrue(disabled)
            assertEquals(1, hostDeleteCount)

            // Non-HTTPS non-loopback gateway override must be rejected
            val badGateway = PushGatewayClient(
                store = store,
                gatewayOriginOverride = "http://evil.example.com",
            )
            assertThrows(IllegalArgumentException::class.java) {
                badGateway.resolveGatewayOrigin()
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `two-phase WakeNotificationCoordinator posts immediate generic fallback, updates in place with deep-link, honours lock-screen redaction, and retains fallback when offline`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2259-wake").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "org-open",
                    connId = "c_open_1",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Open Org",
                    serverOriginOrRelayUrl = "https://open.lumenbox.example",
                    redactLockScreenNotifications = false,
                )
            )
            store.addConnection(
                InstallationConnection(
                    id = "org-redacted",
                    connId = "c_redacted_2",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Strict Bank Org",
                    serverOriginOrRelayUrl = "https://bank.lumenbox.example",
                    redactLockScreenNotifications = true,
                )
            )

            val postedTimeline = mutableListOf<DisplayedNotification>()
            var networkReachable = true

            val coordinator = WakeNotificationCoordinator(
                store = store,
                transportForConnection = { conn ->
                    if (!networkReachable) return@WakeNotificationCoordinator null
                    ApiTransport { req ->
                        // Assert Phase 1 fallback was ALREADY posted BEFORE network I/O ran!
                        assertTrue(
                            postedTimeline.any { it.notificationKey.startsWith(conn.connId) && it.isGenericFallback },
                            "Phase 1 generic fallback must be posted before GET /api/v1/notifications/{seq}",
                        )
                        assertEquals("GET", req.method)
                        assertEquals("/api/v1/notifications/901", req.path)
                        TransportResponse(
                            statusCode = 200,
                            headers = mapOf("content-type" to "application/json"),
                            body = """
                                {
                                  "seq": 901,
                                  "kind": "tool_approval",
                                  "title": "Approve deploy to prod",
                                  "body": "Agent coder wants to run kubectl apply",
                                  "createdAt": "2026-11-27T22:40:00Z",
                                  "inboxId": "apr_prod_99"
                                }
                            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                },
                postOrUpdateNotification = { postedTimeline.add(it) },
            )

            // Case 1: Normal two-phase wake (unredacted)
            val wake1 = requireNotNull(coordinator.parseWakeEnvelope(mapOf("connId" to "c_open_1", "seq" to "901")))
            val finalOpen = coordinator.handleWakeMessage(wake1)
            assertEquals(2, postedTimeline.size)
            assertTrue(postedTimeline[0].isGenericFallback)
            assertEquals(WakeNotificationCoordinator.FALLBACK_BODY, postedTimeline[0].body)
            assertFalse(finalOpen.isGenericFallback)
            assertEquals("Approve deploy to prod", finalOpen.title)
            assertEquals("Agent coder wants to run kubectl apply", finalOpen.body)
            assertFalse(finalOpen.redactOnLockScreen)
            assertTrue(finalOpen.deepLinkUri!!.contains("inboxId=apr_prod_99"))

            // Case 2: Lock-screen redaction policy active (`redactLockScreenNotifications = true`)
            postedTimeline.clear()
            val wake2 = requireNotNull(coordinator.parseWakeEnvelope(mapOf("connId" to "c_redacted_2", "seq" to "901")))
            val finalRedacted = coordinator.handleWakeMessage(wake2)
            assertEquals(2, postedTimeline.size)
            assertTrue(finalRedacted.redactOnLockScreen)
            assertEquals("Strict Bank Org", finalRedacted.title)
            assertEquals(WakeNotificationCoordinator.FALLBACK_BODY, finalRedacted.body)
            assertFalse(finalRedacted.body.contains("kubectl"))
            // Deep-link still routes straight to the decision once unlocked!
            assertTrue(finalRedacted.deepLinkUri!!.contains("inboxId=apr_prod_99"))

            // Case 3: Disconnected intranet VPN / offline -> Phase 1 fallback banner stays visible
            postedTimeline.clear()
            networkReachable = false
            val finalOffline = coordinator.handleWakeMessage(wake1)
            assertEquals(1, postedTimeline.size)
            assertTrue(finalOffline.isGenericFallback)
            assertEquals("Open Org", finalOffline.title)
            assertEquals(WakeNotificationCoordinator.FALLBACK_BODY, finalOffline.body)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `LumenBoxDeepLinkRouter parses DingTalk and Feishu lumenbox open deep-links and resolves target installation`() {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2259-deeplink").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "personal-mac",
                    connId = "c_mac_home",
                    kind = InstallationConnection.Kind.PERSONAL_MAC,
                    displayName = "Home Mac",
                    serverOriginOrRelayUrl = "wss://relay.lumenbox.example/ws",
                )
            )
            store.addConnection(
                InstallationConnection(
                    id = "org-dingtalk",
                    connId = "c_org_dt",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "DingTalk HQ",
                    serverOriginOrRelayUrl = "https://dt-hq.lumenbox.example",
                )
            )
            store.addConnection(
                InstallationConnection(
                    id = "org-feishu",
                    connId = "c_org_fs",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Feishu Lab",
                    serverOriginOrRelayUrl = "https://fs-lab.lumenbox.example",
                )
            )

            // 1. Path form with connId + inbox item: lumenbox://open/c_org_dt/inbox/apr_777
            val dtTarget = requireNotNull(
                LumenBoxDeepLinkRouter.parseOpenUri("lumenbox://open/c_org_dt/inbox/apr_777")
            )
            assertEquals("c_org_dt", dtTarget.connId)
            assertEquals("apr_777", dtTarget.inboxId)
            assertEquals(OpenDeepLinkTarget.FocusTab.NEEDS_YOU, dtTarget.preferredTab)
            assertEquals("org-dingtalk", LumenBoxDeepLinkRouter.resolveTargetConnection(store, dtTarget)?.id)

            // 2. Feishu card button with public server origin query param (server doesn't need phone's local connId!)
            val fsTarget = requireNotNull(
                LumenBoxDeepLinkRouter.parseOpenUri(
                    "lumenbox://open/inbox/q_deploy_region?server=https%3A%2F%2Ffs-lab.lumenbox.example%2F"
                )
            )
            assertEquals("q_deploy_region", fsTarget.inboxId)
            assertEquals("https://fs-lab.lumenbox.example", fsTarget.serverOrigin)
            assertEquals("org-feishu", LumenBoxDeepLinkRouter.resolveTargetConnection(store, fsTarget)?.id)

            // 3. Conversation deep-link on Personal Mac
            val convTarget = requireNotNull(
                LumenBoxDeepLinkRouter.parseOpenUri("lumenbox://open/c_mac_home/conversations/conv_99")
            )
            assertEquals("conv_99", convTarget.conversationId)
            assertEquals(OpenDeepLinkTarget.FocusTab.CONVERSATIONS, convTarget.preferredTab)
            assertEquals("personal-mac", LumenBoxDeepLinkRouter.resolveTargetConnection(store, convTarget)?.id)

            // 4. Non-open schemes/hosts return null
            assertNull(LumenBoxDeepLinkRouter.parseOpenUri("lumenbox://pair?offer=abc"))
            assertNull(LumenBoxDeepLinkRouter.parseOpenUri("https://fs-lab.lumenbox.example/inbox"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

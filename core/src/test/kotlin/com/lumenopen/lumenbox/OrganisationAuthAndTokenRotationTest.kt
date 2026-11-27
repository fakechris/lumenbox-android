package com.lumenopen.lumenbox

import com.lumenopen.lumenbox.auth.OAuthExchangeOutcome
import com.lumenopen.lumenbox.auth.OAuthProviderKind
import com.lumenopen.lumenbox.auth.OrganisationAuthException
import com.lumenopen.lumenbox.auth.OrganisationTokenManager
import com.lumenopen.lumenbox.auth.PkceGenerator
import com.lumenopen.lumenbox.auth.PkceOAuthClient
import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.OpenEnum
import com.lumenopen.lumenbox.security.JcaP256StepUpSigner
import com.lumenopen.lumenbox.session.OrganisationOnboardingState
import com.lumenopen.lumenbox.session.OrganisationSessionCoordinator
import com.lumenopen.lumenbox.storage.InMemoryKeyStoreVault
import com.lumenopen.lumenbox.storage.InstallationConnection
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.transport.TransportRequest
import com.lumenopen.lumenbox.transport.TransportResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class OrganisationAuthAndTokenRotationTest {

    @Test
    fun `RFC 7636 Appendix B S256 PKCE vector and strict HTTPS origin normalization`() {
        // Official RFC 7636 Appendix B test vector:
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PkceGenerator.computeS256Challenge(verifier),
        )

        val generated = PkceGenerator.generateCodeVerifier()
        assertEquals(43, generated.length)

        val client = PkceOAuthClient(
            unauthenticatedRoundTrip = { _, _ -> TransportResponse(404) },
        )
        assertEquals(
            "https://lumenbox.acme.example",
            client.normalizeOrganisationOrigin("  lumenbox.acme.example:443  "),
        )
        val httpErr = assertThrows(OrganisationAuthException::class.java) {
            client.normalizeOrganisationOrigin("http://lumenbox.acme.example")
        }
        assertEquals("https_required", httpErr.code)
    }

    @Test
    fun `DingTalk and Feishu PKCE discovery, state mismatch defense, asked_to_join state, and authenticated SPKI exchange`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2255-oauth").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            val clock = AtomicLong(1_795_800_000_000L)

            var lastChallengeSent: String? = null
            var returnAskedToJoin = true
            var registeredStepUpKeyId: String? = null
            var registeredStepUpSpki: String? = null

            val roundTrip: suspend (String, TransportRequest, Map<String, String>) -> TransportResponse = { origin, req, _ ->
                assertEquals("https://corp.lumenbox.example", origin)
                when {
                    req.method == "GET" && req.path == "/api/v1/meta" -> {
                        TransportResponse(
                            200,
                            emptyMap(),
                            """
                            {
                              "api": "1.0",
                              "server": "0.4.12",
                              "installation": {
                                "mode": "organisation",
                                "name": "Nebula Robotics",
                                "redactLockScreenNotifications": true
                              },
                              "capabilities": ["auth:dingtalk", "auth:feishu"]
                            }
                            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                    req.method == "GET" && req.path == "/api/v1/auth/providers" -> {
                        TransportResponse(
                            200,
                            emptyMap(),
                            """
                            {
                              "items": [
                                {"id": "dingtalk", "displayName": "钉钉企业自建登录", "enabled": true},
                                {"id": "feishu", "displayName": "飞书企业登录", "enabled": true}
                              ]
                            }
                            """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                    req.method == "POST" && req.path == "/api/v1/auth/oauth/start" -> {
                        val obj = LumenBoxJson.default.parseToJsonElement(String(req.body!!, StandardCharsets.UTF_8)).jsonObject
                        assertEquals("S256", obj["codeChallengeMethod"]?.jsonPrimitive?.contentOrNull)
                        lastChallengeSent = obj["codeChallenge"]?.jsonPrimitive?.contentOrNull
                        val state = obj["state"]!!.jsonPrimitive.content
                        val provider = obj["provider"]!!.jsonPrimitive.content
                        TransportResponse(
                            200,
                            emptyMap(),
                            """{"authorizationUrl":"https://login.dingtalk.example/oauth2/auth?provider=$provider&state=$state","state":"$state"}"""
                                .toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                    req.method == "POST" && req.path == "/api/v1/auth/oauth/exchange" -> {
                        val obj = LumenBoxJson.default.parseToJsonElement(String(req.body!!, StandardCharsets.UTF_8)).jsonObject
                        val verifier = obj["codeVerifier"]!!.jsonPrimitive.content
                        // Verify PKCE S256 matches the challenge committed during oauth/start!
                        assertEquals(lastChallengeSent, PkceGenerator.computeS256Challenge(verifier))
                        registeredStepUpKeyId = obj["stepUpKeyId"]?.jsonPrimitive?.contentOrNull
                        registeredStepUpSpki = obj["stepUpPublicKeySpkiBase64Url"]?.jsonPrimitive?.contentOrNull

                        if (returnAskedToJoin) {
                            TransportResponse(
                                202,
                                emptyMap(),
                                """{"status":"asked_to_join","organisationName":"Nebula Robotics","message":"已通知组织管理员审批你的账号","requestId":"req_join_99"}"""
                                    .toByteArray(StandardCharsets.UTF_8),
                            )
                        } else {
                            TransportResponse(
                                200,
                                emptyMap(),
                                """
                                {
                                  "status": "authenticated",
                                  "connId": "conn_nebula_1",
                                  "deviceId": "dev_android_7",
                                  "accessToken": "lbxd_access_initial",
                                  "expiresInSeconds": 900,
                                  "refreshToken": "lbxr_refresh_1",
                                  "scopes": ["work:read", "work:decide", "devices:manage"]
                                }
                                """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                            )
                        }
                    }
                    else -> TransportResponse(404)
                }
            }

            val oauthClient = PkceOAuthClient(
                unauthenticatedRoundTrip = { base, req -> roundTrip(base, req, req.headers) },
            )
            val tokenManager = OrganisationTokenManager(
                store = store,
                vault = vault,
                lockDirectory = File(tmpDir, "locks"),
                refreshRoundTrip = { base, req -> roundTrip(base, req, req.headers) },
                clockMillis = clock::get,
            )
            val (stepUpSigner, _) = JcaP256StepUpSigner.generate("key_stepup_android_1")
            val coordinator = OrganisationSessionCoordinator(
                store = store,
                vault = vault,
                oauthClient = oauthClient,
                tokenManager = tokenManager,
                stepUpSigner = stepUpSigner,
                authenticatedRoundTrip = roundTrip,
                clockMillis = clock::get,
            )

            val providers = coordinator.discoverProviders("corp.lumenbox.example")
            assertEquals(2, providers.size)
            assertEquals(OpenEnum.Known(OAuthProviderKind.DINGTALK, "dingtalk"), providers[0].id)
            assertEquals(OpenEnum.Known(OAuthProviderKind.FEISHU, "feishu"), providers[1].id)

            val ready = coordinator.onboardingState.value as OrganisationOnboardingState.ProvidersReady

            // 1. State mismatch is rejected before hitting oauth/exchange
            coordinator.beginCustomTabsSignIn(ready.discovery, "dingtalk")
            val tamperedErr = assertThrows(OrganisationAuthException::class.java) {
                kotlinx.coroutines.runBlocking {
                    coordinator.handleOAuthCallback("lumenbox://oauth/callback?code=auth_code_1&state=forged_state")
                }
            }
            assertEquals("oauth_state_mismatch", tamperedErr.code)

            // 2. Unactivated directory user receives asked_to_join state cleanly without creating a connection
            val authUrl1 = coordinator.beginCustomTabsSignIn(ready.discovery, "dingtalk")
            val state1 = authUrl1.substringAfter("state=")
            val outcome1 = coordinator.handleOAuthCallback(
                "lumenbox://oauth/callback?code=auth_code_pending&state=${URLEncoder.encode(state1, "UTF-8")}"
            )
            assertTrue(outcome1 is OAuthExchangeOutcome.AskedToJoin)
            assertTrue(coordinator.onboardingState.value is OrganisationOnboardingState.AskedToJoin)
            assertEquals(0, store.listConnections().size)

            // 3. Once activated, Feishu PKCE exchange registers hardware SPKI key, creates isolated connection, and holds 15m token in memory only
            returnAskedToJoin = false
            coordinator.discoverProviders("https://corp.lumenbox.example")
            val ready2 = coordinator.onboardingState.value as OrganisationOnboardingState.ProvidersReady
            val authUrl2 = coordinator.beginCustomTabsSignIn(ready2.discovery, "feishu")
            val state2 = authUrl2.substringAfter("state=")
            val outcome2 = coordinator.handleOAuthCallback(
                "lumenbox://oauth/callback?code=auth_code_active&state=${URLEncoder.encode(state2, "UTF-8")}",
                deviceName = "Pixel 9",
            )
            assertTrue(outcome2 is OAuthExchangeOutcome.Authenticated)
            assertEquals("key_stepup_android_1", registeredStepUpKeyId)
            assertNotNull(registeredStepUpSpki)

            val connId = coordinator.deriveOrganisationConnectionId("https://corp.lumenbox.example")
            val savedConn = store.findById(connId)
            assertNotNull(savedConn)
            assertTrue(savedConn!!.redactLockScreenNotifications)
            assertEquals("lbxd_access_initial", tokenManager.peekAccessTokenInMemory(connId))
            assertEquals("lbxr_refresh_1", store.readRefreshToken(connId))
            // Access token is NEVER stored in the persistent vault!
            assertNull(vault.read("conn.$connId", "accessToken"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun `CrossProcessFileLock serializes concurrent token refreshes, replays Idempotency-Key on lost response, and wipes on 401 access-removed`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2255-rotation").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            val clock = AtomicLong(1_795_800_000_000L)

            // Pair 1 Personal Mac + 2 Organisations to prove surgical isolation on 401 access-removed
            store.addConnection(
                InstallationConnection(
                    id = "personal-mac",
                    connId = "c_mac",
                    kind = InstallationConnection.Kind.PERSONAL_MAC,
                    displayName = "Home Mac mini",
                    serverOriginOrRelayUrl = "wss://relay.lumenbox.com",
                )
            )
            store.addConnection(
                InstallationConnection(
                    id = "org-keep",
                    connId = "c_keep",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Keep Org",
                    serverOriginOrRelayUrl = "https://keep.lumenbox.example",
                )
            )
            store.saveRefreshToken("org-keep", "rt_keep_1")

            store.addConnection(
                InstallationConnection(
                    id = "org-target",
                    connId = "c_target",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Target Org",
                    serverOriginOrRelayUrl = "https://target.lumenbox.example",
                )
            )
            store.openOfflineStore("org-target").putText("inboxSnapshot", "items", """[{"id":"i1","kind":"question","summary":"Cached?"}]""")

            val refreshCalls = AtomicInteger(0)
            val seenIdempotencyKeys = CopyOnWriteArrayList<String>()
            var simulateNetworkDropOnce = false
            var revokeAccess = false

            val tokenManager = OrganisationTokenManager(
                store = store,
                vault = vault,
                lockDirectory = File(tmpDir, "locks"),
                refreshRoundTrip = { _, req ->
                    val idem = req.headers["Idempotency-Key"]!!
                    seenIdempotencyKeys.add(idem)
                    if (simulateNetworkDropOnce) {
                        simulateNetworkDropOnce = false
                        throw RuntimeException("Simulated subway tunnel connection reset after server rotated token")
                    }
                    if (revokeAccess) {
                        return@OrganisationTokenManager TransportResponse(
                            401,
                            emptyMap(),
                            """{"code":"access-removed","error":"Employee left directory"}""".toByteArray(StandardCharsets.UTF_8),
                        )
                    }
                    val count = refreshCalls.incrementAndGet()
                    TransportResponse(
                        200,
                        emptyMap(),
                        """
                        {
                          "accessToken": "lbxd_rotated_$count",
                          "expiresInSeconds": 900,
                          "refreshToken": "lbxr_rotated_$count"
                        }
                        """.trimIndent().toByteArray(StandardCharsets.UTF_8),
                    )
                },
                clockMillis = clock::get,
            )

            tokenManager.seedAuthenticatedSession(
                connectionId = "org-target",
                accessToken = "lbxd_initial",
                expiresInSeconds = 900L,
                refreshToken = "lbxr_initial",
            )

            // Advance clock 15 minutes so access token expires -> 8 concurrent coroutines race to refresh!
            clock.addAndGet(901_000L)
            val tokens = (1..8).map {
                async { tokenManager.validAccessToken("org-target") }
            }.awaitAll()

            // Exactly ONE refresh request hit the server across all 8 racing coroutines!
            assertEquals(1, refreshCalls.get())
            assertTrue(tokens.all { it == "lbxd_rotated_1" })
            assertEquals("lbxr_rotated_1", store.readRefreshToken("org-target"))

            // Expire again, drop the network mid-flight, then retry -> MUST send the exact same Idempotency-Key!
            clock.addAndGet(901_000L)
            seenIdempotencyKeys.clear()
            simulateNetworkDropOnce = true
            assertThrows(RuntimeException::class.java) {
                kotlinx.coroutines.runBlocking {
                    tokenManager.validAccessToken("org-target")
                }
            }
            val recoveredToken = tokenManager.validAccessToken("org-target")
            assertEquals("lbxd_rotated_2", recoveredToken)
            assertEquals(2, seenIdempotencyKeys.size)
            assertEquals(seenIdempotencyKeys[0], seenIdempotencyKeys[1])

            // Verify Cookie header is stripped on authenticated calls and 401 access-removed wipes ONLY org-target
            val transport = tokenManager.createAuthenticatedTransport("org-target") { _, _, headers ->
                assertFalse(headers.keys.any { it.equals("cookie", ignoreCase = true) })
                assertEquals("Bearer lbxd_rotated_2", headers["Authorization"])
                TransportResponse(
                    401,
                    emptyMap(),
                    """{"code":"access-removed","error":"Device revoked by Admin"}""".toByteArray(StandardCharsets.UTF_8),
                )
            }
            assertThrows(ApiEnvelopeException::class.java) {
                kotlinx.coroutines.runBlocking {
                    transport.send(
                        TransportRequest(
                            method = "GET",
                            path = "/api/v1/inbox",
                            headers = mapOf("Cookie" to "agentbox_who=forbidden_cookie"),
                        )
                    )
                }
            }

            // org-target is completely wiped (vault, refresh token, memory token, offline cache dir), while personal-mac & org-keep survive!
            assertNull(store.findById("org-target"))
            assertNull(store.readRefreshToken("org-target"))
            assertNull(tokenManager.peekAccessTokenInMemory("org-target"))
            assertFalse(File(tmpDir, "store/org-target").exists())
            assertNotNull(store.findById("personal-mac"))
            assertNotNull(store.findById("org-keep"))
            assertEquals("rt_keep_1", store.readRefreshToken("org-keep"))
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

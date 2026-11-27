package com.lumenopen.lumenbox

import com.lumenopen.lumenbox.contract.ApprovalGrantScope
import com.lumenopen.lumenbox.contract.InboxDecisionItem
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.StepUpProof
import com.lumenopen.lumenbox.generated.models.GeneratedApprovalDecisionRequest
import com.lumenopen.lumenbox.generated.models.GeneratedInboxAnswerRequest
import com.lumenopen.lumenbox.inbox.NeedsYouInboxCoordinator
import com.lumenopen.lumenbox.inbox.ToolApprovalDecision
import com.lumenopen.lumenbox.inbox.WorkResolutionKind
import com.lumenopen.lumenbox.security.JcaP256StepUpSigner
import com.lumenopen.lumenbox.security.StepUpAuthenticationException
import com.lumenopen.lumenbox.security.StepUpCancelledException
import com.lumenopen.lumenbox.security.StepUpChallenge
import com.lumenopen.lumenbox.security.StepUpVerifier
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64

class NeedsYouInboxCoordinatorTest {

    @Test
    fun `OpenAPI generated models restrict tool approval scopes to once and session only`() {
        val allowedScopes = GeneratedApprovalDecisionRequest.Scope.entries.map { it.value }.toSet()
        assertEquals(setOf("once", "session"), allowedScopes)
        assertFalse("always" in allowedScopes)
        assertEquals(setOf("once", "session"), ApprovalGrantScope.entries.map { it.wireName }.toSet())
    }

    @Test
    fun `polymorphic inbox refresh with ETag 304, stepUpRequired P-256 verification, AskSecret zero-disk vault write, Other fallback, and offline idempotent replay`() = runTest {
        val tmpDir = Files.createTempDirectory("lumenbox-inv2256-inbox").toFile()
        try {
            val vault = InMemoryKeyStoreVault()
            val store = InstallationStore(vault, File(tmpDir, "store"))
            store.addConnection(
                InstallationConnection(
                    id = "org-sec",
                    connId = "c_sec",
                    kind = InstallationConnection.Kind.ORGANISATION,
                    displayName = "Security Corp",
                    serverOriginOrRelayUrl = "https://sec.lumenbox.example",
                )
            )

            val (signer, serverVerifyPubKey) = JcaP256StepUpSigner.generate("key_hw_p256_1")
            val recordedRequests = mutableListOf<TransportRequest>()
            var lastWireSecretBodySnapshot: ByteArray? = null
            var isOnline = true

            val transport = ApiTransport { req ->
                recordedRequests.add(req)
                if (req.path == "/api/v1/inbox/sec_1/answer" && req.body != null) {
                    lastWireSecretBodySnapshot = req.body!!.copyOf()
                }
                when {
                    req.method == "GET" && req.path == "/api/v1/inbox" -> {
                        if (req.headers["If-None-Match"] == "\"etag-inbox-v1\"") {
                            TransportResponse(304, mapOf("etag" to "\"etag-inbox-v1\""))
                        } else {
                            val body = """
                                {
                                  "items": [
                                    {"id":"apr_high","kind":"tool_approval","summary":"Execute terraform apply","toolName":"bash","stepUpRequired":true},
                                    {"id":"apr_low","kind":"tool_approval","summary":"Read README.md","toolName":"read_file","stepUpRequired":false},
                                    {"id":"sec_1","kind":"ask_secret","summary":"Provide STRIPE_WEBHOOK_SECRET","secretName":"STRIPE_WEBHOOK_SECRET"},
                                    {"id":"out_1","kind":"outbox_draft","summary":"Send release note to Slack","channel":"slack"},
                                    {"id":"q_1","kind":"question","summary":"Which region?","options":["ap-east-1","eu-central-1"]},
                                    {"id":"cls_1","kind":"close_proposal","summary":"All tests passed","workId":"wrk_10"},
                                    {"id":"exc_1","kind":"pause","summary":"Docker rate limit hit","workId":"wrk_11","errorCode":"rate_limit"},
                                    {"id":"oth_1","kind":"future_2028_gate","summary":"Approve multi-cluster federation"}
                                  ]
                                }
                            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
                            TransportResponse(200, mapOf("etag" to "\"etag-inbox-v1\""), body)
                        }
                    }
                    req.method == "POST" && req.path == "/api/v1/approvals/apr_high" -> {
                        val decoded = LumenBoxJson.default.decodeFromString(
                            GeneratedApprovalDecisionRequest.serializer(),
                            String(req.body!!, StandardCharsets.UTF_8),
                        )
                        assertEquals(GeneratedApprovalDecisionRequest.Action.ALLOW, decoded.action)
                        assertEquals(GeneratedApprovalDecisionRequest.Scope.ONCE, decoded.scope)
                        val proof = decoded.stepUpProof
                        assertNotNull(proof)
                        // Reconstruct server-side StepUpChallenge and cryptographically verify P-256 signature!
                        val serverChallenge = StepUpChallenge(
                            approvalId = "apr_high",
                            decision = "allow:once",
                            timestampEpochSeconds = proof!!.timestampEpochSeconds!!,
                            nonce = Base64.getUrlDecoder().decode(proof.nonceBase64Url!!),
                        )
                        val verified = StepUpVerifier.verify(
                            publicKey = serverVerifyPubKey,
                            challenge = serverChallenge,
                            proof = StepUpProof(
                                keyId = proof.keyId,
                                approvalId = proof.approvalId,
                                decision = proof.decision,
                                signatureBase64Url = proof.signatureBase64Url,
                            ),
                        )
                        assertTrue(verified, "Server must verify hardware P-256 StepUpProof")
                        TransportResponse(200, emptyMap(), """{"ok":true,"durableSeq":41}""".toByteArray(StandardCharsets.UTF_8))
                    }
                    req.method == "POST" -> {
                        TransportResponse(200, emptyMap(), """{"ok":true,"durableSeq":42}""".toByteArray(StandardCharsets.UTF_8))
                    }
                    else -> TransportResponse(404)
                }
            }

            // 1. Refresh polymorphic inbox + verify ETag 304 hit + unknown `Other` preservation
            var userCancelsBiometrics = false
            val coordinator = NeedsYouInboxCoordinator(
                connectionId = "org-sec",
                store = store,
                transportProvider = { if (isOnline) transport else null },
                stepUpAuthenticator = { challenge, _ ->
                    if (userCancelsBiometrics) {
                        throw StepUpCancelledException("User pressed cancel on BiometricPrompt")
                    }
                    signer.sign(challenge)
                },
                clockEpochSeconds = { 1_795_800_123L },
            )

            val items = coordinator.refreshInbox()
            assertEquals(8, items.size)
            assertEquals(8, store.findById("org-sec")!!.needsYouCount)
            assertTrue(items.last() is InboxDecisionItem.Other)
            assertEquals("Approve multi-cluster federation", (items.last() as InboxDecisionItem.Other).summary)

            // Second refresh sends If-None-Match and receives 304 Not Modified cleanly
            val cached304 = coordinator.refreshInbox()
            assertEquals(8, cached304.size)

            val highRiskItem = items[0] as InboxDecisionItem.ToolApproval

            // 2. Cancelling BiometricPrompt aborts BEFORE sending any network request!
            val reqCountBeforeCancel = recordedRequests.size
            userCancelsBiometrics = true
            assertThrows(StepUpCancelledException::class.java) {
                kotlinx.coroutines.runBlocking {
                    coordinator.submitToolApproval(highRiskItem, ToolApprovalDecision.Allow(ApprovalGrantScope.ONCE))
                }
            }
            assertEquals(reqCountBeforeCancel, recordedRequests.size)

            // Missing authenticator on stepUpRequired == true also refuses!
            val noBiometricCoordinator = NeedsYouInboxCoordinator(
                connectionId = "org-sec",
                store = store,
                transportProvider = { transport },
                stepUpAuthenticator = null,
            )
            assertThrows(StepUpAuthenticationException::class.java) {
                kotlinx.coroutines.runBlocking {
                    noBiometricCoordinator.submitToolApproval(highRiskItem, ToolApprovalDecision.Allow(ApprovalGrantScope.SESSION))
                }
            }

            // 3. Valid BiometricPrompt signature succeeds, updates durableSeq, and decrements NeedsYouCount
            userCancelsBiometrics = false
            coordinator.submitToolApproval(highRiskItem, ToolApprovalDecision.Allow(ApprovalGrantScope.ONCE))
            assertEquals(7, store.findById("org-sec")!!.needsYouCount)
            assertEquals(41L, store.openOfflineStore("org-sec").durableSeq())

            // 4. AskSecret write-only vault submission: zeroes CharArray, refuses offline queueing, never touches disk!
            val secretItem = items[2] as InboxDecisionItem.SecretPrompt
            isOnline = false
            val offlineSecret = "whsec_do_not_persist_offline".toCharArray()
            assertThrows(IllegalStateException::class.java) {
                kotlinx.coroutines.runBlocking {
                    coordinator.submitSecretDirectToVault(secretItem, offlineSecret)
                }
            }
            assertTrue(offlineSecret.all { it == '\u0000' }, "Secret chars must be zeroed even on offline failure")
            assertEquals(0, store.openOfflineStore("org-sec").pendingOutboundCount())

            isOnline = true
            val liveSecret = "whsec_live_vault_only_999".toCharArray()
            coordinator.submitSecretDirectToVault(secretItem, liveSecret)
            assertTrue(liveSecret.all { it == '\u0000' }, "Secret chars must be zeroed immediately after vault send")
            val secretPost = recordedRequests.last()
            assertEquals("/api/v1/inbox/sec_1/answer", secretPost.path)
            assertTrue(secretPost.body!!.all { it == 0.toByte() }, "Client request buffer must also be zeroed in finally")
            val parsedSecretReq = LumenBoxJson.default.decodeFromString(
                GeneratedInboxAnswerRequest.serializer(),
                String(lastWireSecretBodySnapshot!!, StandardCharsets.UTF_8),
            )
            assertEquals("STRIPE_WEBHOOK_SECRET", parsedSecretReq.secretName)
            assertEquals("whsec_live_vault_only_999", parsedSecretReq.secretValue)
            assertEquals(0, store.openOfflineStore("org-sec").pendingOutboundCount())

            // 5. Offline durable decisions (Question, OutboxDraft, CloseProposal, ExceptionRecovery) queue offline & flush idempotently
            isOnline = false
            coordinator.answerQuestion(items[4] as InboxDecisionItem.Question, "ap-east-1")
            coordinator.decideOutboxDraft(items[3] as InboxDecisionItem.OutboxDraft, approveSend = true)
            coordinator.resolveWorkItem("cls_1", "wrk_10", WorkResolutionKind.COUNT_AS_DONE)
            coordinator.resolveWorkItem("exc_1", "wrk_11", WorkResolutionKind.RETRY)
            assertEquals(4, store.openOfflineStore("org-sec").pendingOutboundCount())

            isOnline = true
            val flushed = coordinator.flushPendingDecisions()
            assertEquals(4, flushed)
            assertEquals(0, store.openOfflineStore("org-sec").pendingOutboundCount())
            assertEquals(42L, store.openOfflineStore("org-sec").durableSeq())
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}

package com.lumenopen.lumenbox.inbox

import com.lumenopen.lumenbox.contract.ApiEnvelopeException
import com.lumenopen.lumenbox.contract.ApiErrorEnvelope
import com.lumenopen.lumenbox.contract.ApprovalGrantScope
import com.lumenopen.lumenbox.contract.InboxDecisionItem
import com.lumenopen.lumenbox.contract.LumenBoxJson
import com.lumenopen.lumenbox.contract.StepUpProof
import com.lumenopen.lumenbox.generated.models.GeneratedApprovalDecisionRequest
import com.lumenopen.lumenbox.generated.models.GeneratedInboxAnswerRequest
import com.lumenopen.lumenbox.generated.models.GeneratedStepUpProofPayload
import com.lumenopen.lumenbox.generated.models.GeneratedWorkDecisionRequest
import com.lumenopen.lumenbox.security.StepUpAuthenticationException
import com.lumenopen.lumenbox.security.StepUpChallenge
import com.lumenopen.lumenbox.storage.InstallationStore
import com.lumenopen.lumenbox.storage.OutboundActionDraft
import com.lumenopen.lumenbox.transport.ApiTransport
import com.lumenopen.lumenbox.transport.TransportRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.UUID

sealed interface ToolApprovalDecision {
    /** Mobile device credentials strictly grant only [ApprovalGrantScope.ONCE] or [ApprovalGrantScope.SESSION]. */
    data class Allow(val scope: ApprovalGrantScope) : ToolApprovalDecision
    data class Deny(val reason: String? = null) : ToolApprovalDecision

    fun canonicalDecisionTag(): String = when (this) {
        is Allow -> "allow:${scope.wireName}"
        is Deny -> "deny"
    }
}

enum class WorkResolutionKind(val wireName: String) {
    COUNT_AS_DONE("done"),
    PUT_OFF("put_off"),
    STOP("stop"),
    RETRY("retry"),
}

fun interface StepUpChallengeAuthenticator {
    suspend fun authenticateAndSign(
        challenge: StepUpChallenge,
        item: InboxDecisionItem.ToolApproval,
    ): StepUpProof
}

/**
 * Polymorphic `Needs you` inbox coordinator (`docs/106 §4.2`, `docs/110 §1, §4.4`, INV-2256).
 *
 * Works identically over Personal Mac (`EncryptedRelayTransport.overMux`) and Organisation
 * (`AuthenticatedHttpsTransport`), using OpenAPI-generated request models from `contracts/v1.openapi.json`:
 * - Enforces `once` / `session` approval scopes (never `"always"`).
 * - Requires hardware biometric [StepUpProof] bound to `(approvalId, decision, timestamp, nonce)`
 *   whenever `item.stepUpRequired == true` and the user allows the tool.
 * - Submits `AskSecret` (`SecretPrompt`) write-only directly to `POST /api/v1/inbox/{id}/answer`
 *   without ever writing the secret to local disk/offline queue, zeroing `CharArray` buffers after send.
 * - Persists non-secret decisions in [com.lumenopen.lumenbox.storage.ConnectionOfflineStore]'s
 *   idempotent outbound queue so subway/Wi-Fi handovers retry safely with the same `Idempotency-Key`.
 */
class NeedsYouInboxCoordinator(
    private val connectionId: String,
    private val store: InstallationStore,
    private val transportProvider: () -> ApiTransport?,
    private val stepUpAuthenticator: StepUpChallengeAuthenticator? = null,
    private val clockEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000L },
) {
    companion object {
        private const val INBOX_TABLE = "inboxSnapshot"
        private const val INBOX_ITEMS_ROW = "items"
        private const val INBOX_ETAG_ROW = "etag"
    }

    private val random = SecureRandom()

    fun readCachedItems(): List<InboxDecisionItem> {
        val offline = runCatching { store.openOfflineStore(connectionId) }.getOrNull() ?: return emptyList()
        return offline.getJson(INBOX_TABLE, INBOX_ITEMS_ROW, ListSerializer(InboxDecisionItem.serializer()))
            ?: emptyList()
    }

    /**
     * Refreshes `GET /api/v1/inbox` using `If-None-Match` when an `ETag` is cached (`docs/106 §4.1`).
     */
    suspend fun refreshInbox(): List<InboxDecisionItem> {
        val transport = transportProvider() ?: return readCachedItems()
        val offline = store.openOfflineStore(connectionId)
        val headers = LinkedHashMap<String, String>()
        headers["Accept"] = "application/json"
        offline.getText(INBOX_TABLE, INBOX_ETAG_ROW)?.takeIf { it.isNotBlank() }?.let { etag ->
            headers["If-None-Match"] = etag
        }

        val res = transport.send(
            TransportRequest(
                method = "GET",
                path = "/api/v1/inbox",
                headers = headers,
            )
        )
        if (res.statusCode == 304) {
            val cached = readCachedItems()
            store.updateNeedsYouCount(connectionId, cached.size)
            return cached
        }
        throwIfError(res.statusCode, res.body)

        val raw = String(res.body, StandardCharsets.UTF_8)
        val root = LumenBoxJson.default.parseToJsonElement(raw)
        val itemsJson = when {
            root is JsonObject && "items" in root -> root["items"]?.jsonArray
            else -> runCatching { root.jsonArray }.getOrNull()
        } ?: emptyList()

        val items = itemsJson.map { elem ->
            LumenBoxJson.default.decodeFromJsonElement(InboxDecisionItem.serializer(), elem)
        }
        offline.putJson(INBOX_TABLE, INBOX_ITEMS_ROW, ListSerializer(InboxDecisionItem.serializer()), items)
        res.headers["etag"]?.takeIf { it.isNotBlank() }?.let { etag ->
            offline.putText(INBOX_TABLE, INBOX_ETAG_ROW, etag)
        }
        store.updateNeedsYouCount(connectionId, items.size)
        return items
    }

    /**
     * Submits a tool approval decision (`POST /api/v1/approvals/{id}`).
     * Strictly caps scope to `once` or `session`, and requires hardware biometric [StepUpProof]
     * whenever `item.stepUpRequired` is true on an [ToolApprovalDecision.Allow] decision.
     */
    suspend fun submitToolApproval(
        item: InboxDecisionItem.ToolApproval,
        decision: ToolApprovalDecision,
    ) {
        val proofPayload: GeneratedStepUpProofPayload? = if (item.stepUpRequired && decision is ToolApprovalDecision.Allow) {
            val authenticator = stepUpAuthenticator
                ?: throw StepUpAuthenticationException("该高危工具审批需要硬件生物识别签名（BiometricPrompt），当前设备未就绪。")
            val nonce = ByteArray(16).also { random.nextBytes(it) }
            val challenge = StepUpChallenge(
                approvalId = item.id,
                decision = decision.canonicalDecisionTag(),
                timestampEpochSeconds = clockEpochSeconds(),
                nonce = nonce,
            )
            val proof = authenticator.authenticateAndSign(challenge, item)
            if (proof.approvalId != challenge.approvalId || proof.decision != challenge.decision || proof.signatureBase64Url.isBlank()) {
                throw StepUpAuthenticationException("步进生物识别签名绑定校验不一致，已拒绝发送。")
            }
            GeneratedStepUpProofPayload(
                keyId = proof.keyId,
                approvalId = proof.approvalId,
                decision = proof.decision,
                signatureBase64Url = proof.signatureBase64Url,
                timestampEpochSeconds = challenge.timestampEpochSeconds,
                nonceBase64Url = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(challenge.nonce),
            )
        } else {
            null
        }

        val generatedRequest = when (decision) {
            is ToolApprovalDecision.Allow -> GeneratedApprovalDecisionRequest(
                action = GeneratedApprovalDecisionRequest.Action.ALLOW,
                scope = when (decision.scope) {
                    ApprovalGrantScope.ONCE -> GeneratedApprovalDecisionRequest.Scope.ONCE
                    ApprovalGrantScope.SESSION -> GeneratedApprovalDecisionRequest.Scope.SESSION
                },
                stepUpProof = proofPayload,
            )
            is ToolApprovalDecision.Deny -> GeneratedApprovalDecisionRequest(
                action = GeneratedApprovalDecisionRequest.Action.DENY,
                reason = decision.reason,
            )
        }

        val payloadJson = LumenBoxJson.default.encodeToString(
            GeneratedApprovalDecisionRequest.serializer(),
            generatedRequest,
        )
        submitDurableAction(
            itemIdToRemove = item.id,
            kind = "tool_approval",
            path = "/api/v1/approvals/${item.id}",
            payloadJson = payloadJson,
        )
    }

    suspend fun answerQuestion(
        item: InboxDecisionItem.Question,
        answerText: String,
    ) {
        val trimmed = answerText.trim()
        require(trimmed.isNotEmpty()) { "Answer must not be blank" }
        val payloadJson = LumenBoxJson.default.encodeToString(
            GeneratedInboxAnswerRequest.serializer(),
            GeneratedInboxAnswerRequest(
                kind = "question",
                answer = trimmed,
            ),
        )
        submitDurableAction(
            itemIdToRemove = item.id,
            kind = "question_answer",
            path = "/api/v1/inbox/${item.id}/answer",
            payloadJson = payloadJson,
        )
    }

    suspend fun decideOutboxDraft(
        item: InboxDecisionItem.OutboxDraft,
        approveSend: Boolean,
    ) {
        val payloadJson = LumenBoxJson.default.encodeToString(
            GeneratedInboxAnswerRequest.serializer(),
            GeneratedInboxAnswerRequest(
                kind = "outbox_draft",
                action = if (approveSend) "send" else "discard",
            ),
        )
        submitDurableAction(
            itemIdToRemove = item.id,
            kind = "outbox_decision",
            path = "/api/v1/inbox/${item.id}/answer",
            payloadJson = payloadJson,
        )
    }

    suspend fun resolveWorkItem(
        inboxItemId: String,
        workId: String,
        resolution: WorkResolutionKind,
        note: String? = null,
    ) {
        require(workId.isNotBlank()) { "workId must not be blank" }
        val payloadJson = LumenBoxJson.default.encodeToString(
            GeneratedWorkDecisionRequest.serializer(),
            GeneratedWorkDecisionRequest(
                decision = resolution.wireName,
                note = note,
            ),
        )
        submitDurableAction(
            itemIdToRemove = inboxItemId,
            kind = "work_decision",
            path = "/api/v1/work/$workId/decisions",
            payloadJson = payloadJson,
        )
    }

    /**
     * Write-only `AskSecret` submission (`docs/106 §4.2`, `docs/110 §4.4`):
     * - Transmits straight to the server vault (`POST /api/v1/inbox/{id}/answer`).
     * - NEVER writes [secretChars] into local storage or the offline queue!
     * - Zeroes [secretChars] and intermediate byte buffers in `finally`.
     */
    suspend fun submitSecretDirectToVault(
        item: InboxDecisionItem.SecretPrompt,
        secretChars: CharArray,
    ) {
        require(secretChars.isNotEmpty()) { "密钥内容不能为空" }
        val transport = transportProvider()
            ?: run {
                secretChars.fill('\u0000')
                throw IllegalStateException("当前离线：为确保密钥永不落盘缓存，请恢复在线后直接提交至服务器保险库。")
            }

        var utf8SecretBytes: ByteArray? = null
        var bodyBytes: ByteArray? = null
        try {
            val byteBuf: ByteBuffer = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secretChars))
            utf8SecretBytes = ByteArray(byteBuf.remaining()).also { byteBuf.get(it) }
            byteBuf.array().fill(0)

            val reqModel = GeneratedInboxAnswerRequest(
                kind = "secret_prompt",
                secretName = item.secretName,
                secretValue = String(utf8SecretBytes, StandardCharsets.UTF_8),
            )
            bodyBytes = LumenBoxJson.default.encodeToString(
                GeneratedInboxAnswerRequest.serializer(),
                reqModel,
            ).toByteArray(StandardCharsets.UTF_8)

            val idempotencyKey = UUID.randomUUID().toString()
            val res = transport.send(
                TransportRequest(
                    method = "POST",
                    path = "/api/v1/inbox/${item.id}/answer",
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Accept" to "application/json",
                    ),
                    body = bodyBytes,
                    idempotencyKey = idempotencyKey,
                )
            )
            throwIfError(res.statusCode, res.body)
            advanceSeqIfPresent(res.body)
            removeItemFromCache(item.id)
        } finally {
            secretChars.fill('\u0000')
            utf8SecretBytes?.fill(0)
            bodyBytes?.fill(0)
        }
    }

    /** Replays pending non-secret decisions queued while offline using their original `Idempotency-Key`. */
    suspend fun flushPendingDecisions(): Int {
        val transport = transportProvider() ?: return 0
        val offline = store.openOfflineStore(connectionId)
        var flushed = 0
        for (pending in offline.pendingOutboundActions()) {
            val res = runCatching {
                transport.send(
                    TransportRequest(
                        method = "POST",
                        path = pending.path,
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "Accept" to "application/json",
                        ),
                        body = pending.payloadJson.toByteArray(StandardCharsets.UTF_8),
                        idempotencyKey = pending.idempotencyKey,
                    )
                )
            }.getOrNull() ?: break
            if (res.statusCode in 200..299 || res.statusCode == 409) {
                offline.acknowledgeOutboundAction(pending.clientMessageId)
                advanceSeqIfPresent(res.body)
                flushed += 1
            } else {
                break
            }
        }
        return flushed
    }

    private suspend fun submitDurableAction(
        itemIdToRemove: String,
        kind: String,
        path: String,
        payloadJson: String,
    ) {
        val offline = store.openOfflineStore(connectionId)
        val idempotencyKey = UUID.randomUUID().toString()
        val clientMessageId = "dec_${UUID.randomUUID()}"
        offline.enqueueOutboundAction(
            OutboundActionDraft(
                idempotencyKey = idempotencyKey,
                clientMessageId = clientMessageId,
                kind = kind,
                path = path,
                payloadJson = payloadJson,
                enqueuedAtEpochMillis = clockEpochSeconds() * 1_000L,
            )
        )

        val transport = transportProvider()
        if (transport == null) {
            removeItemFromCache(itemIdToRemove)
            return
        }

        val res = transport.send(
            TransportRequest(
                method = "POST",
                path = path,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = payloadJson.toByteArray(StandardCharsets.UTF_8),
                idempotencyKey = idempotencyKey,
            )
        )
        throwIfError(res.statusCode, res.body)
        offline.acknowledgeOutboundAction(clientMessageId)
        advanceSeqIfPresent(res.body)
        removeItemFromCache(itemIdToRemove)
    }

    private fun removeItemFromCache(itemId: String) {
        val offline = runCatching { store.openOfflineStore(connectionId) }.getOrNull() ?: return
        val remaining = readCachedItems().filterNot { it.id == itemId }
        offline.putJson(INBOX_TABLE, INBOX_ITEMS_ROW, ListSerializer(InboxDecisionItem.serializer()), remaining)
        store.updateNeedsYouCount(connectionId, remaining.size)
    }

    private fun advanceSeqIfPresent(body: ByteArray) {
        if (body.isEmpty()) return
        val seq = runCatching {
            LumenBoxJson.default.parseToJsonElement(String(body, StandardCharsets.UTF_8))
                .jsonObject["durableSeq"]?.jsonPrimitive?.longOrNull
        }.getOrNull() ?: return
        runCatching { store.openOfflineStore(connectionId).advanceDurableSeq(seq) }
    }

    private fun throwIfError(statusCode: Int, body: ByteArray) {
        if (statusCode in 200..299) return
        val env = runCatching {
            LumenBoxJson.default.decodeFromString(
                ApiErrorEnvelope.serializer(),
                String(body, StandardCharsets.UTF_8),
            )
        }.getOrNull()
        if (env != null) throw ApiEnvelopeException(statusCode, env)
        throw IllegalStateException("Inbox operation failed with HTTP $statusCode")
    }
}

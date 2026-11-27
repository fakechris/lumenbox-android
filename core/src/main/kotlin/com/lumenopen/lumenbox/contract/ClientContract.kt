package com.lumenopen.lumenbox.contract

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object LumenBoxJson {
    val default: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}

/**
 * Forward-compatible string enum wrapper (`docs/106 §4.1`, `docs/110 §1`):
 * unknown server enum values decode into [Other] instead of failing deserialization.
 */
sealed interface OpenEnum<out T> {
    val rawValue: String

    data class Known<out T>(val value: T, override val rawValue: String) : OpenEnum<T>
    data class Other(override val rawValue: String) : OpenEnum<Nothing>
}

enum class InstallationMode(val wireName: String) {
    SINGLE_MACHINE("single-machine"),
    ORGANISATION("organisation");

    companion object {
        fun fromWire(raw: String): OpenEnum<InstallationMode> {
            val match = entries.firstOrNull { it.wireName == raw }
            return if (match != null) OpenEnum.Known(match, raw) else OpenEnum.Other(raw)
        }
    }
}

object InstallationModeOpenSerializer : KSerializer<OpenEnum<InstallationMode>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OpenEnum.InstallationMode", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OpenEnum<InstallationMode> =
        InstallationMode.fromWire(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: OpenEnum<InstallationMode>) {
        encoder.encodeString(value.rawValue)
    }
}

@Serializable
data class ServerInstallationDescriptor(
    @Serializable(with = InstallationModeOpenSerializer::class)
    val mode: OpenEnum<InstallationMode>,
    val name: String? = null,
    val redactLockScreenNotifications: Boolean? = null,
)

@Serializable
data class ServerMeta(
    val api: String,
    val server: String,
    val installation: ServerInstallationDescriptor,
    val capabilities: List<String> = emptyList(),
    val preview: List<String> = emptyList(),
)

/**
 * Device credentials strictly forbid `"always"` standing approvals (`docs/106 §4.2`, `docs/110 §4.4`).
 * Only [ONCE] (`"once"`) and [SESSION] (`"session"`) exist on mobile clients.
 */
@Serializable
enum class ApprovalGrantScope(val wireName: String) {
    @SerialName("once") ONCE("once"),
    @SerialName("session") SESSION("session"),
}

@Serializable
data class StepUpProof(
    val keyId: String,
    val approvalId: String,
    val decision: String,
    val signatureBase64Url: String,
)

/**
 * Discriminated `/api/v1/inbox` item (`docs/106 §4.2`, `docs/110 §1`, INV-2256):
 * covers tool approvals (`once`/`session` + `stepUpRequired`), outbox drafts, questions,
 * write-only secret prompts (`AskSecret`), close proposals, pauses/exceptions, and forward-compatible [Other].
 */
@Serializable(with = InboxDecisionItemSerializer::class)
sealed interface InboxDecisionItem {
    val id: String
    val summary: String

    data class ToolApproval(
        override val id: String,
        override val summary: String,
        val toolName: String,
        val stepUpRequired: Boolean,
        val detail: String? = null,
    ) : InboxDecisionItem

    data class OutboxDraft(
        override val id: String,
        override val summary: String,
        val channel: String,
        val recipient: String? = null,
        val draftBody: String? = null,
    ) : InboxDecisionItem

    data class Question(
        override val id: String,
        override val summary: String,
        val options: List<String>,
        val allowFreeText: Boolean = true,
    ) : InboxDecisionItem

    data class SecretPrompt(
        override val id: String,
        override val summary: String,
        val secretName: String,
        val hint: String? = null,
    ) : InboxDecisionItem

    data class CloseProposal(
        override val id: String,
        override val summary: String,
        val workId: String,
    ) : InboxDecisionItem

    data class ExceptionRecovery(
        override val id: String,
        override val summary: String,
        val workId: String,
        val errorCode: String? = null,
    ) : InboxDecisionItem

    data class Other(
        override val id: String,
        val rawKind: String,
        override val summary: String,
    ) : InboxDecisionItem
}

object InboxDecisionItemSerializer : KSerializer<InboxDecisionItem> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("InboxDecisionItem")

    override fun deserialize(decoder: Decoder): InboxDecisionItem {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("InboxDecisionItemSerializer requires JsonDecoder")
        val obj: JsonObject = jsonDecoder.decodeJsonElement().jsonObject
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: error("Missing inbox item id")
        val kind = obj["kind"]?.jsonPrimitive?.contentOrNull ?: error("Missing inbox item kind")
        val summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: kind

        return when (kind) {
            "tool_approval" -> InboxDecisionItem.ToolApproval(
                id = id,
                summary = summary,
                toolName = obj["toolName"]?.jsonPrimitive?.contentOrNull ?: "tool",
                stepUpRequired = obj["stepUpRequired"]?.jsonPrimitive?.booleanOrNull ?: false,
                detail = obj["detail"]?.jsonPrimitive?.contentOrNull,
            )
            "outbox_draft" -> InboxDecisionItem.OutboxDraft(
                id = id,
                summary = summary,
                channel = obj["channel"]?.jsonPrimitive?.contentOrNull ?: "external",
                recipient = obj["recipient"]?.jsonPrimitive?.contentOrNull,
                draftBody = obj["draftBody"]?.jsonPrimitive?.contentOrNull,
            )
            "question" -> InboxDecisionItem.Question(
                id = id,
                summary = summary,
                options = (obj["options"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                allowFreeText = obj["allowFreeText"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            "secret_prompt", "ask_secret" -> InboxDecisionItem.SecretPrompt(
                id = id,
                summary = summary,
                secretName = obj["secretName"]?.jsonPrimitive?.contentOrNull ?: "SECRET",
                hint = obj["hint"]?.jsonPrimitive?.contentOrNull,
            )
            "close_proposal" -> InboxDecisionItem.CloseProposal(
                id = id,
                summary = summary,
                workId = obj["workId"]?.jsonPrimitive?.contentOrNull ?: "",
            )
            "exception", "pause" -> InboxDecisionItem.ExceptionRecovery(
                id = id,
                summary = summary,
                workId = obj["workId"]?.jsonPrimitive?.contentOrNull ?: "",
                errorCode = obj["errorCode"]?.jsonPrimitive?.contentOrNull,
            )
            else -> InboxDecisionItem.Other(id = id, rawKind = kind, summary = summary)
        }
    }

    override fun serialize(encoder: Encoder, value: InboxDecisionItem) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("InboxDecisionItemSerializer requires JsonEncoder")
        val obj = buildJsonObject {
            put("id", value.id)
            put("summary", value.summary)
            when (value) {
                is InboxDecisionItem.ToolApproval -> {
                    put("kind", "tool_approval")
                    put("toolName", value.toolName)
                    put("stepUpRequired", value.stepUpRequired)
                    value.detail?.let { put("detail", it) }
                }
                is InboxDecisionItem.OutboxDraft -> {
                    put("kind", "outbox_draft")
                    put("channel", value.channel)
                    value.recipient?.let { put("recipient", it) }
                    value.draftBody?.let { put("draftBody", it) }
                }
                is InboxDecisionItem.Question -> {
                    put("kind", "question")
                    put("options", JsonArray(value.options.map { JsonPrimitive(it) }))
                    put("allowFreeText", value.allowFreeText)
                }
                is InboxDecisionItem.SecretPrompt -> {
                    put("kind", "secret_prompt")
                    put("secretName", value.secretName)
                    value.hint?.let { put("hint", it) }
                }
                is InboxDecisionItem.CloseProposal -> {
                    put("kind", "close_proposal")
                    put("workId", value.workId)
                }
                is InboxDecisionItem.ExceptionRecovery -> {
                    put("kind", "exception")
                    put("workId", value.workId)
                    value.errorCode?.let { put("errorCode", it) }
                }
                is InboxDecisionItem.Other -> put("kind", value.rawKind)
            }
        }
        jsonEncoder.encodeJsonElement(obj)
    }
}

@Serializable
data class NotificationSummary(
    val seq: Long,
    val kind: String,
    val title: String,
    val body: String,
    val createdAt: String,
    val inboxId: String? = null,
    val conversationId: String? = null,
    val workId: String? = null,
)

@Serializable
data class WakePayload(
    val connId: String,
    val seq: Long,
)

@Serializable
data class ApiErrorEnvelope(
    val code: String,
    val error: String,
    val requestId: String? = null,
) {
    val isAccessRemoved: Boolean get() = code == "access-removed"
}

class ApiEnvelopeException(val statusCode: Int, val envelope: ApiErrorEnvelope) :
    RuntimeException("HTTP $statusCode (${envelope.code}): ${envelope.error}")

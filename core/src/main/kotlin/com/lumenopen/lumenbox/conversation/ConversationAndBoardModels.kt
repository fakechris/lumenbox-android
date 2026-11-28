package com.lumenopen.lumenbox.conversation

import com.lumenopen.lumenbox.contract.OpenEnum
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

enum class MessageRole(val wireName: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system");

    companion object {
        fun fromWire(raw: String): OpenEnum<MessageRole> {
            val match = entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) }
            return if (match != null) OpenEnum.Known(match, raw) else OpenEnum.Other(raw)
        }
    }
}

object MessageRoleOpenSerializer : KSerializer<OpenEnum<MessageRole>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OpenEnum.MessageRole", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OpenEnum<MessageRole> =
        MessageRole.fromWire(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: OpenEnum<MessageRole>) {
        encoder.encodeString(value.rawValue)
    }
}

enum class ToolRunStatus(val wireName: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    AWAITING_APPROVAL("awaiting_approval");

    companion object {
        fun fromWire(raw: String): OpenEnum<ToolRunStatus> {
            val match = entries.firstOrNull { it.wireName.equals(raw, ignoreCase = true) }
            return if (match != null) OpenEnum.Known(match, raw) else OpenEnum.Other(raw)
        }
    }
}

object ToolRunStatusOpenSerializer : KSerializer<OpenEnum<ToolRunStatus>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OpenEnum.ToolRunStatus", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OpenEnum<ToolRunStatus> =
        ToolRunStatus.fromWire(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: OpenEnum<ToolRunStatus>) {
        encoder.encodeString(value.rawValue)
    }
}

enum class WorkStateKind(val wireName: String) {
    READY("ready"),
    IN_PROGRESS("in_progress"),
    NEEDS_YOU("needs_you"),
    IN_REVIEW("in_review"),
    DONE("done"),
    BLOCKED("blocked");

    companion object {
        fun fromWire(raw: String): OpenEnum<WorkStateKind> {
            val normalized = raw.trim().lowercase().replace('-', '_').replace(' ', '_')
            val match = entries.firstOrNull { it.wireName == normalized }
            return if (match != null) OpenEnum.Known(match, raw) else OpenEnum.Other(raw)
        }
    }
}

object WorkStateKindOpenSerializer : KSerializer<OpenEnum<WorkStateKind>> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("OpenEnum.WorkStateKind", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OpenEnum<WorkStateKind> =
        WorkStateKind.fromWire(decoder.decodeString())

    override fun serialize(encoder: Encoder, value: OpenEnum<WorkStateKind>) {
        encoder.encodeString(value.rawValue)
    }
}

enum class AttachmentMediaKind(val wireName: String) {
    IMAGE("image"),
    VOICE("voice"),
    FILE("file");

    companion object {
        fun infer(explicitKind: String?, mimeType: String): AttachmentMediaKind {
            when (explicitKind?.lowercase()) {
                "image" -> return IMAGE
                "voice", "audio" -> return VOICE
                "file" -> return FILE
            }
            val lowerMime = mimeType.lowercase()
            return when {
                lowerMime.startsWith("image/") -> IMAGE
                lowerMime.startsWith("audio/") -> VOICE
                else -> FILE
            }
        }
    }
}

@Serializable
data class AttachmentSummary(
    val id: String,
    val name: String,
    val mimeType: String,
    val kind: String = AttachmentMediaKind.infer(null, mimeType).wireName,
    val sizeBytes: Long? = null,
)

@Serializable
data class ToolTimelineEntry(
    val id: String,
    val name: String,
    @Serializable(with = ToolRunStatusOpenSerializer::class)
    val status: OpenEnum<ToolRunStatus>,
    val summary: String? = null,
)

@Serializable
data class ShowUiCardAction(
    val actionId: String,
    val label: String,
    val style: String? = null,
)

@Serializable
data class ShowUiCard(
    val cardId: String,
    val title: String,
    val body: String? = null,
    val actions: List<ShowUiCardAction> = emptyList(),
)

@Serializable
data class ConversationSummary(
    val id: String,
    val title: String,
    val agentId: String,
    val agentName: String? = null,
    val participantAgentIds: List<String> = emptyList(),
    val updatedAt: String,
    val running: Boolean = false,
    val lastMessagePreview: String? = null,
)

@Serializable
data class CommittedMessage(
    val id: String,
    val conversationId: String,
    val clientMessageId: String? = null,
    @Serializable(with = MessageRoleOpenSerializer::class)
    val role: OpenEnum<MessageRole>,
    val text: String,
    val thinking: String? = null,
    val createdAt: String,
    val attachments: List<AttachmentSummary> = emptyList(),
    val toolCalls: List<ToolTimelineEntry> = emptyList(),
    val cards: List<ShowUiCard> = emptyList(),
    val deliverableIds: List<String> = emptyList(),
    val pendingSend: Boolean = false,
)

data class LiveTurnDraft(
    val conversationId: String,
    val turnId: String,
    val thinkingText: String = "",
    val responseText: String = "",
    val toolCalls: List<ToolTimelineEntry> = emptyList(),
    val cards: List<ShowUiCard> = emptyList(),
    val isRunning: Boolean = true,
)

@Serializable
data class WorkItemSummary(
    val id: String,
    val title: String,
    @Serializable(with = WorkStateKindOpenSerializer::class)
    val state: OpenEnum<WorkStateKind>,
    val agentId: String? = null,
    val agentName: String? = null,
    val conversationId: String? = null,
    val waitsOnMe: Boolean = false,
    val summary: String? = null,
    val deliverableIds: List<String> = emptyList(),
    val updatedAt: String,
)

@Serializable
data class BoardColumnSnapshot(
    val id: String,
    val title: String,
    val cards: List<WorkItemSummary> = emptyList(),
)

@Serializable
data class BoardSnapshot(
    val columns: List<BoardColumnSnapshot> = emptyList(),
    val updatedAt: String? = null,
)

@Serializable
data class DeliverableMetadata(
    val id: String,
    val title: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val workId: String? = null,
    val conversationId: String? = null,
    val createdAt: String,
)

@Serializable
data class DeliverablePreviewTicket(
    val id: String,
    val title: String,
    val mimeType: String,
    val sizeBytes: Long? = null,
    val url: String,
    val expiresAt: String,
    val inlinePreviewText: String? = null,
)

/**
 * Staged local attachment selected via Photo Picker, SAF Document Picker, or Voice Recorder
 * before upload to `POST /api/v1/uploads`.
 */
data class LocalUploadAttachment(
    val localId: String,
    val fileName: String,
    val mimeType: String,
    val kind: AttachmentMediaKind = AttachmentMediaKind.infer(null, mimeType),
    val bytes: ByteArray,
)

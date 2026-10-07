package com.prismai.llmhost.ui

import com.prismai.llmhost.AttachmentExtractionStatus
import com.prismai.llmhost.PromptAttachment
import org.json.JSONObject

/**
 * JSON round-trip for [PromptAttachment], so an active draft's attachments can
 * live in a `Bundle` through `rememberSaveable`.
 *
 * `PromptAttachment` is a plain data class and is not `Bundle`-serializable, so
 * we encode every field to a string. [decode] returns null on any parse failure
 * rather than throwing: the encoded value comes back from a restored Bundle,
 * and a corrupt entry must be dropped, never crash composition.
 */
object AttachmentTextCodec {

    fun encode(attachment: PromptAttachment): String = JSONObject().apply {
        put("uriString", attachment.uriString)
        put("name", attachment.name)
        put("mimeType", attachment.mimeType ?: JSONObject.NULL)
        put("sizeBytes", attachment.sizeBytes ?: JSONObject.NULL)
        put("extractionStatus", attachment.extractionStatus.name)
        put("promptText", attachment.promptText)
    }.toString()

    fun decode(encoded: String): PromptAttachment? = runCatching {
        val json = JSONObject(encoded)
        PromptAttachment(
            uriString = json.getString("uriString"),
            name = json.getString("name"),
            mimeType = if (json.isNull("mimeType")) null else json.getString("mimeType"),
            sizeBytes = if (json.isNull("sizeBytes")) null else json.getLong("sizeBytes"),
            extractionStatus = AttachmentExtractionStatus.valueOf(json.getString("extractionStatus")),
            promptText = json.getString("promptText"),
        )
    }.getOrNull()
}

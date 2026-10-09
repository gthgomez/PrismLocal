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
    const val MAX_BUNDLE_TEXT_CHARS = 256

    fun encode(attachment: PromptAttachment): String {
        if (attachment.promptText.isNotEmpty()) {
            DraftPayloadStore.put(attachment.uriString, attachment.promptText)
        }
        val isOffloaded = attachment.promptText.length > MAX_BUNDLE_TEXT_CHARS
        return JSONObject().apply {
            put("uriString", attachment.uriString)
            put("name", attachment.name)
            put("mimeType", attachment.mimeType ?: JSONObject.NULL)
            put("sizeBytes", attachment.sizeBytes ?: JSONObject.NULL)
            put("extractionStatus", attachment.extractionStatus.name)
            put("hasDurablePayload", isOffloaded)
            put("promptText", if (isOffloaded) attachment.promptText.take(MAX_BUNDLE_TEXT_CHARS) else attachment.promptText)
        }.toString()
    }

    fun decode(encoded: String): PromptAttachment? = runCatching {
        val json = JSONObject(encoded)
        val uriString = json.getString("uriString")
        val durableText = if (json.optBoolean("hasDurablePayload", false)) {
            DraftPayloadStore.get(uriString) ?: json.optString("promptText", "")
        } else {
            DraftPayloadStore.get(uriString) ?: json.optString("promptText", "")
        }
        PromptAttachment(
            uriString = uriString,
            name = json.getString("name"),
            mimeType = if (json.isNull("mimeType")) null else json.getString("mimeType"),
            sizeBytes = if (json.isNull("sizeBytes")) null else json.getLong("sizeBytes"),
            extractionStatus = AttachmentExtractionStatus.valueOf(json.getString("extractionStatus")),
            promptText = durableText,
        )
    }.getOrNull()
}

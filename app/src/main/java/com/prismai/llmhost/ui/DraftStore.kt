package com.prismai.llmhost.ui

import androidx.compose.runtime.saveable.Saver
import com.prismai.llmhost.PromptAttachment
import org.json.JSONArray
import org.json.JSONObject

/**
 * Draft text and attachments, owned by the chat they belong to.
 *
 * Previously the draft was unkeyed `remember` state in ChatScreen: rotation
 * destroyed it (the activity declares no `configChanges`, so it is recreated),
 * and switching chats carried the same draft into a different conversation.
 * One `remember`ed draft per screen cannot express "this text belongs to chat A".
 */
class DraftStore(initialChatId: String?) {
    var chatId: String? = initialChatId
        private set

    var text: String = ""
    var attachments: List<PromptAttachment> = emptyList()

    private val drafts = mutableMapOf<String, Draft>()

    private class Draft(val text: String, val attachments: List<PromptAttachment>)

    /** Switch the active chat, saving the outgoing draft and loading the incoming one. */
    fun moveTo(nextChatId: String?) {
        if (nextChatId == chatId) return
        persist()
        chatId = nextChatId
        val restored = drafts[key(nextChatId)]
        text = restored?.text ?: ""
        attachments = restored?.attachments ?: emptyList()
    }

    /**
     * Attribute the live draft to the chat the service now reports.
     *
     * After an activity recreation the service is not bound on the first frame,
     * so the active draft is restored into a store seeded with the *saved* chat
     * id before any switch is observed. When the service then reports that same
     * id this is a no-op and the restored draft survives. When it reports a
     * different id, the live draft is saved under the old id (never under a
     * placeholder) and the incoming chat's draft is loaded.
     *
     * The outgoing draft's id is the store's own [chatId], not [currentChatId],
     * so a chat switch can never attribute a draft to the wrong conversation.
     *
     * @return the draft that is now live when the id changed, or null when it did
     *   not change (the caller keeps its current live draft).
     */
    fun applyLiveDraft(
        currentChatId: String?,
        liveText: String,
        liveAttachments: List<PromptAttachment>,
    ): Pair<String, List<PromptAttachment>>? {
        if (currentChatId == chatId) return null
        text = liveText
        attachments = liveAttachments
        moveTo(currentChatId)
        return text to attachments
    }

    /** Erase the active chat's draft. Other chats are untouched. */
    fun clear() {
        attachments.forEach { DraftPayloadStore.remove(it.uriString) }
        text = ""
        attachments = emptyList()
        val removed = drafts.remove(key(chatId))
        removed?.attachments?.forEach { DraftPayloadStore.remove(it.uriString) }
    }

    /** Add attachments to a specific chat's draft (e.g. from picker completion). */
    fun addAttachments(targetChatId: String?, newAttachments: List<PromptAttachment>) {
        if (targetChatId == chatId) {
            attachments = (attachments + newAttachments)
                .distinctBy { it.uriString }
                .takeLast(com.prismai.llmhost.AttachmentSelection.MAX_PROMPT_ATTACHMENTS)
        } else {
            val stored = drafts[key(targetChatId)]
            val merged = ((stored?.attachments ?: emptyList()) + newAttachments)
                .distinctBy { it.uriString }
                .takeLast(com.prismai.llmhost.AttachmentSelection.MAX_PROMPT_ATTACHMENTS)
            drafts[key(targetChatId)] = Draft(stored?.text ?: "", merged)
        }
    }

    /** Clears draft if the targetChatId and contents match the expected accepted draft. */
    fun clearIfMatches(
        targetChatId: String?,
        expectedPrompt: String,
        expectedAttachments: List<PromptAttachment>,
    ) {
        if (targetChatId == chatId) {
            if (text == expectedPrompt && attachments == expectedAttachments) {
                clear()
            }
        } else {
            val stored = drafts[key(targetChatId)]
            if (stored != null && stored.text == expectedPrompt && stored.attachments == expectedAttachments) {
                stored.attachments.forEach { DraftPayloadStore.remove(it.uriString) }
                drafts.remove(key(targetChatId))
            }
        }
    }

    /** Value copy of a chat's draft, for saveable-state persistence. */
    fun snapshotFor(id: String?): Pair<String, List<PromptAttachment>> {
        if (id == chatId) return text to attachments.toList()
        val stored = drafts[key(id)] ?: return "" to emptyList()
        return stored.text to stored.attachments.toList()
    }

    /** Restore a chat's draft from persisted state, without switching chats. */
    fun restore(id: String?, text: String, attachments: List<PromptAttachment>) {
        drafts[key(id)] = Draft(text, attachments.toList())
        if (id == chatId) {
            this.text = text
            this.attachments = attachments.toList()
        }
    }

    /** Capture current state so it survives a move or process death. */
    fun persist() {
        drafts[key(chatId)] = Draft(text, attachments.toList())
    }

    private fun key(id: String?): String = id ?: NEW_CHAT_KEY

    /**
     * Serialize the whole store — every keyed draft, active and inactive — so a
     * Compose [Saver] can carry it through process death. The active draft is
     * persisted first so no edit made since the last [moveTo] is lost.
     */
    fun encodeState(): String {
        persist()
        val draftsJson = JSONObject()
        drafts.forEach { (key, draft) -> draftsJson.put(key, encodeDraft(draft)) }
        return JSONObject().apply {
            put("chatId", chatId ?: JSONObject.NULL)
            put("drafts", draftsJson)
        }.toString()
    }

    private fun encodeDraft(draft: Draft): JSONObject = JSONObject().apply {
        put("text", draft.text)
        put("attachments", JSONArray(draft.attachments.map(AttachmentTextCodec::encode)))
    }

    companion object {
        private const val NEW_CHAT_KEY = "__no_chat__"

        /**
         * Backs `rememberSaveable(saver = DraftStore.Saver)` in ChatScreen. The
         * shared screen store previously used plain `remember`, so only the
         * active chat's draft survived recreation.
         */
        val Saver: Saver<DraftStore, String> = Saver(
            save = { store -> store.encodeState() },
            restore = { encoded -> decodeState(encoded) },
        )

        /** Inverse of [encodeState]. A corrupt payload yields an empty store. */
        internal fun decodeState(encoded: String): DraftStore {
            val json = runCatching { JSONObject(encoded) }.getOrNull()
                ?: return DraftStore(null)
            val chatId = if (json.isNull("chatId")) null else json.getString("chatId")
            val store = DraftStore(chatId)
            val draftsJson = json.optJSONObject("drafts") ?: return store
            val keys = draftsJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val entry = draftsJson.optJSONObject(key) ?: continue
                val id = if (key == NEW_CHAT_KEY) null else key
                store.restore(id, entry.optString("text"), decodeAttachments(entry.optJSONArray("attachments")))
            }
            return store
        }

        private fun decodeAttachments(array: JSONArray?): List<PromptAttachment> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                runCatching { array.getString(index) }.getOrNull()
                    ?.let(AttachmentTextCodec::decode)
            }
        }
    }
}

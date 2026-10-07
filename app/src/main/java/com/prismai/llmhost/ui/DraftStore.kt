package com.prismai.llmhost.ui

import com.prismai.llmhost.PromptAttachment

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

    /** Erase the active chat's draft. Other chats are untouched. */
    fun clear() {
        text = ""
        attachments = emptyList()
        drafts.remove(key(chatId))
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

    private companion object {
        const val NEW_CHAT_KEY = "__no_chat__"
    }
}

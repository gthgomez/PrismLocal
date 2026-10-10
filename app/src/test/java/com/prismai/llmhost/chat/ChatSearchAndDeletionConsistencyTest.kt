package com.prismai.llmhost.chat

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.prismai.llmhost.ChatSession
import com.prismai.llmhost.TranscriptMessage
import com.prismai.llmhost.TranscriptRole
import com.prismai.llmhost.ui.ServiceUiState
import com.prismai.llmhost.ui.UiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatSearchAndDeletionConsistencyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeSharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
            override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
            override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
            override fun remove(key: String): SharedPreferences.Editor { removals.add(key); return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            override fun commit(): Boolean {
                if (clearAll) values.clear()
                removals.forEach { values.remove(it) }
                values.putAll(pending)
                return true
            }
            override fun apply() { commit() }
        }
    }

    private class TestContext(private val baseDir: File) : ContextWrapper(null) {
        private val prefs = FakeSharedPreferences()
        override fun getFilesDir(): File = baseDir
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
    }

    /**
     * Wait for every coroutine the manager launched on [this] scope (transcript
     * and chat-index persists run on `Dispatchers.IO`). Tests that rewrite a
     * persisted path must drain these first, or a late write can recreate a file
     * after the test has replaced it, making the setup race.
     */
    private suspend fun CoroutineScope.awaitLaunchedWork() {
        coroutineContext[Job]?.children?.toList()?.forEach { it.join() }
    }

    @Test
    fun clearedTranscriptIsEvictedFromSearchImmediately(): Unit = runBlocking {
        val baseDir = tempFolder.newFolder("chat_test_clear")
        val context = TestContext(baseDir)
        val transcriptStore = TranscriptStore(context)
        val searchIndex = ChatSearchIndex()
        val uiState = ServiceUiState()
        val eventBus = UiEventBus()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        val chatManager = ChatManager(
            context = context,
            transcriptStore = transcriptStore,
            searchIndex = searchIndex,
            uiState = uiState,
            eventBus = eventBus,
            scope = scope,
        )

        chatManager.createChatInternal("Astrophysics Discussion")
        val chatId = uiState.currentChatId.value!!
        chatManager.appendTranscriptMessage(
            TranscriptRole.USER,
            "Searching for extraterrestrial intelligence with radio telescopes",
        )
        // Persist through the write gate so the index reflects this transcript and
        // any stale in-flight snapshot launched by createChatInternal is
        // superseded by the newer revision rather than clobbering the entry.
        chatManager.persistTranscript(
            chatId,
            uiState.transcript.value,
            chatManager.transcriptWriteRevision(chatId),
        )

        // Verify query matches before clearing
        val beforeClear = searchIndex.search("extraterrestrial", uiState.chatSessions.value, transcriptStore)
        assertEquals(1, beforeClear.size)
        assertEquals(chatId, beforeClear[0].first.id)

        // Clear transcript
        chatManager.clearTranscript()

        // Verify search index is updated and no longer matches the cleared term
        val afterClear = searchIndex.search("extraterrestrial", uiState.chatSessions.value, transcriptStore)
        assertTrue("Cleared chat must no longer match search terms", afterClear.isEmpty())
        val indexedText = searchIndex.get(chatId)
        assertNotNull(indexedText)
        assertFalse(indexedText!!.contains("extraterrestrial"))
    }

    @Test
    fun deletedChatIsEvictedFromSearchIndexAndChatSessions(): Unit = runBlocking {
        val baseDir = tempFolder.newFolder("chat_test_delete")
        val context = TestContext(baseDir)
        val transcriptStore = TranscriptStore(context)
        val searchIndex = ChatSearchIndex()
        val uiState = ServiceUiState()
        val eventBus = UiEventBus()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        val chatManager = ChatManager(
            context = context,
            transcriptStore = transcriptStore,
            searchIndex = searchIndex,
            uiState = uiState,
            eventBus = eventBus,
            scope = scope,
        )

        chatManager.createChatInternal("Chat Alpha")
        val chatAId = uiState.currentChatId.value!!
        chatManager.appendTranscriptMessage(TranscriptRole.USER, "Supernova explosion observation")
        // Persist through the write gate (see above): the manual index update
        // that used to live here did not bump the gate revision, so a stale
        // empty-transcript persist could overwrite the entry with a title-only
        // haystack and make this chat unsearchable.
        chatManager.persistTranscript(
            chatAId,
            uiState.transcript.value,
            chatManager.transcriptWriteRevision(chatAId),
        )

        chatManager.createChatInternal("Chat Beta")
        val chatBId = uiState.currentChatId.value!!
        chatManager.appendTranscriptMessage(TranscriptRole.USER, "Quantum entanglement experiment")
        chatManager.persistTranscript(
            chatBId,
            uiState.transcript.value,
            chatManager.transcriptWriteRevision(chatBId),
        )

        // Search finds Chat Alpha
        val resultsA = searchIndex.search("supernova", uiState.chatSessions.value, transcriptStore)
        assertEquals(1, resultsA.size)
        assertEquals(chatAId, resultsA[0].first.id)

        // Delete Chat Alpha
        val deleted = chatManager.deleteChat(chatAId)
        assertTrue(deleted)

        // Verify searchIndex is cleared of Chat Alpha
        assertNull(searchIndex.get(chatAId))
        val resultsAfter = searchIndex.search("supernova", uiState.chatSessions.value, transcriptStore)
        assertTrue(resultsAfter.isEmpty())

        // Chat Beta remains searchable
        val resultsB = searchIndex.search("quantum", uiState.chatSessions.value, transcriptStore)
        assertEquals(1, resultsB.size)
        assertEquals(chatBId, resultsB[0].first.id)

        // Chat Alpha is gone from session list
        assertFalse(uiState.chatSessions.value.any { it.id == chatAId })
    }

    @Test
    fun stalePersistAfterDeleteCannotReIndexTheChat(): Unit = runBlocking {
        val baseDir = tempFolder.newFolder("chat_test_stale_persist")
        val context = TestContext(baseDir)
        val transcriptStore = TranscriptStore(context)
        val searchIndex = ChatSearchIndex()
        val uiState = ServiceUiState()
        val eventBus = UiEventBus()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        val chatManager = ChatManager(
            context = context,
            transcriptStore = transcriptStore,
            searchIndex = searchIndex,
            uiState = uiState,
            eventBus = eventBus,
            scope = scope,
        )

        chatManager.createChatInternal("Stale Publish Chat")
        val chatId = uiState.currentChatId.value!!
        chatManager.appendTranscriptMessage(TranscriptRole.USER, "Supernova leftover text")
        val messages = uiState.transcript.value
        // Capture the revision an in-flight persist would have snapshotted.
        val staleRevision = chatManager.transcriptWriteRevision(chatId)
        searchIndex.update(chatId, messages, "Stale Publish Chat")

        assertTrue(chatManager.deleteChat(chatId))
        assertNull(searchIndex.get(chatId))

        // A publish that already passed the pre-delete gate must be rejected,
        // never re-indexing the deleted chat.
        chatManager.persistTranscript(chatId, messages, staleRevision)

        assertNull("a stale persist must not re-index a deleted chat", searchIndex.get(chatId))
    }

    @Test
    fun failedFilesystemDeletePropagatesFalseAndRetainsChatSession(): Unit = runBlocking {
        val baseDir = tempFolder.newFolder("chat_test_fail_delete")
        val context = TestContext(baseDir)
        val transcriptStore = TranscriptStore(context)
        val searchIndex = ChatSearchIndex()
        val uiState = ServiceUiState()
        val eventBus = UiEventBus()
        val scope = CoroutineScope(Dispatchers.Unconfined)

        val chatManager = ChatManager(
            context = context,
            transcriptStore = transcriptStore,
            searchIndex = searchIndex,
            uiState = uiState,
            eventBus = eventBus,
            scope = scope,
        )

        chatManager.createChatInternal("Undeletable Chat")
        val chatId = uiState.currentChatId.value!!
        // Drain createChatInternal's async transcript/index persists before we
        // replace the transcript path with a directory; otherwise a late write
        // recreates the file after mkdirs() and the setup races.
        scope.awaitLaunchedWork()

        // In POSIX and Java, deleting a non-empty directory via File.delete() always returns false.
        // We replace the target file with a non-empty directory so transcriptFile(chatId).delete() returns false.
        val targetFile = transcriptStore.transcriptFile(chatId)
        if (targetFile.exists()) targetFile.delete()
        targetFile.mkdirs()
        val blockingChild = File(targetFile, "cannot_delete_directory.lock")
        blockingChild.createNewFile()
        assertTrue(targetFile.isDirectory)
        assertFalse("Deleting non-empty directory must return false", targetFile.delete())

        val result = chatManager.deleteChat(chatId)
        assertFalse("deleteChat must return false when file delete fails", result)
        assertTrue("Chat must remain in sessions list when delete fails", uiState.chatSessions.value.any { it.id == chatId })

        // Cleanup
        blockingChild.delete()
        targetFile.delete()
        Unit
    }

    @Test
    fun readTranscriptFileRecoversFromBackupWhenPrimaryMissing(): Unit {
        val baseDir = tempFolder.newFolder("transcript_backup_test")
        val context = TestContext(baseDir)
        val transcriptStore = TranscriptStore(context)

        val target = File(baseDir, "chats/chat_test_recovery.json")
        val msg = TranscriptMessage(id = 1L, role = TranscriptRole.USER, text = "Durability checkpoint message")
        transcriptStore.writeTranscriptFile(target, listOf(msg))
        assertTrue(target.exists())

        // Create .bak backup and delete target
        val backup = File(target.parentFile ?: baseDir, "${target.name}.bak")
        target.copyTo(backup, overwrite = true)
        target.delete()
        assertFalse(target.exists())
        assertTrue(backup.exists())

        // Reading the missing target should recover from backup
        val loaded = transcriptStore.readTranscriptFile(target)
        assertEquals(1, loaded.size)
        assertEquals("Durability checkpoint message", loaded[0].text)
    }

    @Test
    fun promoteTempFilePreservesTargetFileOnPromotionFailure(): Unit {
        val baseDir = tempFolder.newFolder("promote_test")
        val context = TestContext(baseDir)
        val transcriptStore = TranscriptStore(context)

        val target = File(baseDir, "chats/promote_target.json")
        target.parentFile?.mkdirs()
        target.writeText("[{\"id\":1,\"role\":\"USER\",\"text\":\"Original Target Content\"}]")

        val temp = File(baseDir, "chats/promote_temp.json.tmp")
        temp.writeText("[{\"id\":2,\"role\":\"USER\",\"text\":\"New Temp Content\"}]")

        // Normal promotion works
        transcriptStore.promoteTempFile(temp, target)
        assertEquals("[{\"id\":2,\"role\":\"USER\",\"text\":\"New Temp Content\"}]", target.readText())
    }
}

# Reliability Sprint PR 2 — Chat and Composer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop losing the user's draft, stop lying about import and retry, and make the keyboard layout behave — without a visual redesign.

**Architecture:** Drafts become chat-owned and durable, and are cleared only when generation *accepts* a request rather than before it validates. A single observable installed-model state replaces the `refreshKey` snapshot. Attachment extraction moves to cancellable background work with the limit applied before files are processed. The link-external chain is removed in full.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), `rememberSaveable`, `SavedStateHandle`-style state restoration, coroutines, WorkManager-adjacent service APIs, JUnit4.

**Spec:** `docs/superpowers/specs/2026-10-06-reliability-sprint-design.md`
**Depends on:** PR 1 (`docs/superpowers/plans/2026-10-06-reliability-pr1-native-model.md`) must be merged first. Task 4 consumes `ModelDownloadState.Failure.entryId` added by PR 1 Task 7.

## Global Constraints

- Baseline `f13545f` + PR 1 merged. All Kotlin paths relative to `app/src/main/java/com/prismai/llmhost/`.
- Quick gate: `./gradlew --no-daemon :app:testDevDebugUnitTest`, then `./gradlew --no-daemon :app:assembleDevDebug`.
- **No visual redesign.** Behavioral predictability only. Existing colors, typography and component shapes stay.
- Per AGENTS.md: keep UI rendering separate from orchestration and native resource ownership. **Do not create another cancellation or terminal-state decision in a screen.** The screen may observe a refusal; it must not invent validity rules the service does not own.
- The composer already uses `imePadding()` + `navigationBarsPadding()`, which is valid. Do not "fix" a non-problem by adding padding.
- Preserve the untrusted-attachment prompt boundary in `AttachmentTextExtractor.buildPrompt` — do not weaken `sanitizeAttachmentText` or the `<untrusted_external_content>` wrapper.
- Voice input is out of scope **except** the recognizer leak in Task 8. Do not build the voice feature.
- Device verification on the S25 Ultra is required before claiming any layout fix works.

---

## File Structure

| File | Responsibility |
| ---- | -------------- |
| `ui/ChatScreen.kt` | Draft ownership, picker handling, layout, snackbar placement |
| `ui/DraftStore.kt` (new) | Chat-keyed, durable draft + attachment state |
| `ui/composer/PromptComposer.kt` | Compact header/tray behavior, height-driven |
| `ui/chat/ScrollFollowPolicy.kt` (new) | Decides when auto-scroll may yank the view |
| `AttachmentTextExtractor.kt` | Background extraction entry point |
| `ui/ServiceUiState.kt` | Single observable installed-model state |
| `tools/VoiceIoManager.kt` | Recognizer release (leak fix only) |
| `SecurityAuditLog.kt` | Report persistence |

---

### Task 1: Make drafts chat-owned and durable

**Why first:** it is the reported "lost input" bug and the most user-visible. Everything else in PR 2 is smaller.

**Files:**
- Create: `ui/DraftStore.kt`
- Modify: `ui/ChatScreen.kt:228-230` (draft state), `:565-598` (send handler), `:686-690` (`onSwitchChat`)
- Test: `app/src/test/java/com/prismai/llmhost/ui/DraftStoreTest.kt` (create)

**Interfaces:**
- Consumes: `PromptAttachment` (`AttachmentTextExtractor.kt:28-36`) — fields `uriString`, `name`, `mimeType`, `sizeBytes`, `extractionStatus`, `promptText`.
- Produces:
  - `class DraftStore(initialChatId: String?)` with `var chatId: String?`, `var text: String`, `var attachments: List<PromptAttachment>`, `fun moveTo(chatId: String?)`, `fun clear()`, and `fun snapshotFor(chatId: String?): Pair<String, List<PromptAttachment>>`.
  - Consumed by Task 2 (acceptance) and Task 7 (persistence).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/ui/DraftStoreTest.kt`:

```kotlin
package com.prismai.llmhost.ui

import com.prismai.llmhost.AttachmentExtractionStatus
import com.prismai.llmhost.PromptAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftStoreTest {

    private fun attachment(name: String) = PromptAttachment(
        uriString = "content://x/$name",
        name = name,
        mimeType = "text/plain",
        sizeBytes = 10L,
        extractionStatus = AttachmentExtractionStatus.EXTRACTED,
        promptText = "body",
    )

    @Test
    fun draftIsScopedToItsChat() {
        val store = DraftStore("chat_a")
        store.text = "hello"
        store.moveTo("chat_b")

        assertEquals("switching chats must not carry a draft", "", store.text)
    }

    @Test
    fun returningToAChatRestoresItsDraft() {
        val store = DraftStore("chat_a")
        store.text = "draft for a"
        store.moveTo("chat_b")
        store.text = "draft for b"
        store.moveTo("chat_a")

        assertEquals("draft for a", store.text)
    }

    @Test
    fun attachmentsFollowTheSameScoping() {
        val store = DraftStore("chat_a")
        store.attachments = listOf(attachment("a.txt"))
        store.moveTo("chat_b")
        assertTrue(store.attachments.isEmpty())
        store.moveTo("chat_a")
        assertEquals(1, store.attachments.size)
    }

    @Test
    fun clearEmptiesTheActiveDraftOnly() {
        val store = DraftStore("chat_a")
        store.text = "keep me"
        store.moveTo("chat_b")
        store.clear()
        store.moveTo("chat_a")
        assertEquals("keep me", store.text)
    }

    @Test
    fun snapshotIsValueCopied() {
        val store = DraftStore("chat_a")
        store.text = "original"
        val (text, _) = store.snapshotFor("chat_a")
        store.text = "mutated"
        assertEquals("snapshot must not alias live state", "original", text)
    }

    @Test
    fun nullChatIdIsTreatedAsItsOwnBucket() {
        val store = DraftStore(null)
        store.text = "no chat yet"
        store.moveTo(null)
        assertEquals("no chat yet", store.text)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*DraftStoreTest*'
```
Expected: FAIL to compile — `DraftStore` does not exist.

- [ ] **Step 3: Implement `DraftStore`**

Create `ui/DraftStore.kt`:

```kotlin
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*DraftStoreTest*'
```
Expected: PASS.

- [ ] **Step 5: Wire it into `ChatScreen`**

At `ui/ChatScreen.kt:228-230`, replace the draft state:

```kotlin
                        val draftStore = remember(service) { DraftStore(service?.currentChatIdValue()) }
                        var prompt by remember { mutableStateOf(draftStore.text) }
                        var attachments by remember {
                            mutableStateOf(draftStore.attachments)
                        }
```

Add a `LaunchedEffect` that follows chat switches, so switching chats loads the right draft:

```kotlin
                        LaunchedEffect(currentChatId) {
                            if (draftStore.chatId != currentChatId) {
                                draftStore.moveTo(currentChatId)
                                prompt = draftStore.text
                                attachments = draftStore.attachments
                            }
                        }
```

Every mutation of `prompt`/`attachments` must also update the store. To avoid scattering that, keep `prompt` and `attachments` as the single source of truth in the composable and add one effect that persists them:

```kotlin
                        // Persist the live draft back into the store on every change so a
                        // chat switch or activity recreation can restore it.
                        LaunchedEffect(prompt, attachments, currentChatId) {
                            draftStore.text = prompt
                            draftStore.attachments = attachments
                            draftStore.persist()
                        }
```

In `onSwitchChat` (`:686-690`), keep calling `service?.switchChat(chatId)`; the `LaunchedEffect(currentChatId)` above performs the draft move. Do not clear the draft there.

- [ ] **Step 6: Replace plain `remember` with `rememberSaveable` for the active draft**

Rotation recreates the activity, so the active draft's text must be saveable. Attachments are not `Bundle`-serializable as-is, so persist them via their string fields:

```kotlin
                        var prompt by rememberSaveable(currentChatId) { mutableStateOf(draftStore.text) }
                        var savedAttachments by rememberSaveable(currentChatId) {
                            mutableStateOf(emptyList<String>())
                        }
```

and derive the attachment list:

```kotlin
                        val attachments: List<PromptAttachment> =
                            remember(savedAttachments) {
                                savedAttachments.mapNotNull(AttachmentTextCodec::decode)
                            }
```

Create `ui/AttachmentTextCodec.kt` with `encode(PromptAttachment): String` and `decode(String): PromptAttachment?` using `org.json.JSONObject` (already a project dependency — `PrismatixModels.kt` uses it). Encode every field; decode must return null on any parse failure rather than throwing.

Keep an effect writing `savedAttachments = attachments.map(AttachmentTextCodec::encode)` on change.

- [ ] **Step 7: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*DraftStoreTest*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/ui/DraftStore.kt app/src/main/java/com/prismai/llmhost/ui/AttachmentTextCodec.kt app/src/main/java/com/prismai/llmhost/ui/ChatScreen.kt app/src/test/java/com/prismai/llmhost/ui/DraftStoreTest.kt
git commit -m "fix(chat): make drafts chat-owned and survive recreation

Draft text and attachments were unkeyed remember state in ChatScreen, and
the activity declares no configChanges, so rotation recreated it and lost
the draft. The same state was not keyed by chat, so switching chats
carried a draft into a different conversation.

Introduce DraftStore, which scopes drafts by chat id and returns a value
copy on switch. Back the active draft with rememberSaveable so rotation
and activity recreation restore it, encoding attachments through JSON
since PromptAttachment is not Bundle-serializable.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Clear the draft only after generation accepts

**Why:** both refusals (no model, context too large) happen after the draft is destroyed, so the user loses composed text to a transient snackbar.

**Files:**
- Modify: `service/InferenceService.kt` — add a pre-flight predicate near `generateSafelyAndAwait` (`:1018`)
- Modify: `generation/GenerationOrchestrator.kt` — reuse the same predicate at `:207-210` and `:249-262`
- Modify: `ui/ChatScreen.kt:565-598` (send handler)
- Test: `app/src/test/java/com/prismai/llmhost/generation/SendAcceptanceTest.kt` (create)

**Interfaces:**
- Consumes: `GenerationBudget.userTurnFits(contextLength, maxTokens, userPrompt, memoryContext, instructionText)` (`generation/GenerationBudget.kt:58-72`); `uiState.currentModel` (StateFlow<String?>).
- Produces:
  - `InferenceService.acceptsGeneration(prompt: String): Boolean` — non-suspending pre-flight, `false` when no model is selected or the turn does not fit.
  - `InferenceService.generationRefusalReason(prompt: String): String?` — the user-facing message, so the screen shows exactly what the orchestrator will refuse with.
  - Consumed by the `onSend` lambda in `ChatScreen`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/generation/SendAcceptanceTest.kt`:

```kotlin
package com.prismai.llmhost.generation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendAcceptanceTest {

    @Test
    fun noModelSelected_isRefused() {
        assertFalse(
            SendAcceptance.evaluate(currentModel = null, contextLength = 4096, maxTokens = 512, prompt = "hi")
                .accepted
        )
    }

    @Test
    fun promptTooLongForContext_isRefused() {
        val huge = "word ".repeat(200_000)
        val result = SendAcceptance.evaluate(
            currentModel = "some-model",
            contextLength = 512,
            maxTokens = 128,
            prompt = huge,
        )
        assertFalse(result.accepted)
        assertTrue(result.reason!!.contains("context window"))
    }

    @Test
    fun ordinaryTurn_isAccepted() {
        val result = SendAcceptance.evaluate(
            currentModel = "some-model",
            contextLength = 4096,
            maxTokens = 512,
            prompt = "hello",
        )
        assertTrue(result.accepted)
        assertTrue(result.reason == null)
    }

    @Test
    fun refusalMessages_matchTheOrchestrator() {
        // The screen must show what the orchestrator actually refuses with,
        // or the message contradicts what happens next.
        val noModel = SendAcceptance.evaluate(null, 4096, 512, "hi")
        assertTrue(noModel.reason!!.contains("Select a model"))
        val tooLong = SendAcceptance.evaluate("m", 512, 128, "word ".repeat(200_000))
        assertTrue(tooLong.reason!!.contains("too long for the context window"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*SendAcceptanceTest*'
```
Expected: FAIL to compile — `SendAcceptance` does not exist.

- [ ] **Step 3: Implement the single acceptance rule**

Create `generation/SendAcceptance.kt`:

```kotlin
package com.prismai.llmhost.generation

/**
 * The single rule deciding whether a send is accepted.
 *
 * The screen used to clear the draft and fire generateSafely, and only then
 * did the orchestrator discover there was no model or that the turn did not fit
 * - so the composed text was destroyed by a refusal the user never intended.
 *
 * Both the screen and the orchestrator consult this, so the pre-flight answer
 * and the actual refusal cannot drift apart.
 */
object SendAcceptance {

    data class Result(val accepted: Boolean, val reason: String?)

    fun evaluate(
        currentModel: String?,
        contextLength: Int,
        maxTokens: Int,
        prompt: String,
        memoryContext: String = "",
        instructionText: String = "",
    ): Result {
        if (currentModel.isNullOrBlank()) {
            return Result(false, "Select a model before sending a prompt")
        }
        if (!GenerationBudget.userTurnFits(
                contextLength = contextLength,
                maxTokens = maxTokens,
                userPrompt = prompt,
                memoryContext = memoryContext,
                instructionText = instructionText,
            )
        ) {
            return Result(false, "This message is too long for the context window")
        }
        return Result(true, null)
    }
}
```

The two literal strings must be byte-identical to those at
`GenerationOrchestrator.kt:208` and `:260`. If they ever diverge, the user sees
one message and gets another.

- [ ] **Step 4: Make the orchestrator use the same rule**

At `GenerationOrchestrator.kt:207-210`, replace the model check:

```kotlin
        val acceptance = SendAcceptance.evaluate(
            currentModel = uiState.currentModel.value,
            contextLength = settings.contextLength,
            maxTokens = settings.maxTokens,
            userPrompt = prompt,
            memoryContext = memoryContext,
            instructionText = if (agentEnabled) AgentToolProtocol.instructionBlock() else "",
        )
        if (!acceptance.accepted) {
            if (agentChainId != null) {
                agentTrace.abortTrace(agentChainId, acceptance.reason ?: "refused")
            }
            eventBus.publish(acceptance.reason ?: "Request refused")
            return
        }
```

Then remove the now-redundant separate budget check at `:249-262`, since
`SendAcceptance` performs it. Do not remove the `userTurnFits` call itself — it
now lives inside `SendAcceptance`.

- [ ] **Step 5: Expose non-suspending pre-flight on the service**

In `InferenceService`, add near `generateSafelyAndAwait` (`:1018`):

```kotlin
    /** Non-suspending pre-flight so the UI can decide whether to clear its draft. */
    fun acceptsGeneration(prompt: String): Boolean =
        generationAcceptance(prompt).accepted

    fun generationRefusalReason(prompt: String): String? =
        generationAcceptance(prompt).reason

    private fun generationAcceptance(prompt: String): SendAcceptance.Result =
        SendAcceptance.evaluate(
            currentModel = uiState.currentModel.value,
            contextLength = _generationSettings.value.contextLength,
            maxTokens = _generationSettings.value.maxTokens,
            prompt = prompt,
        )
```

- [ ] **Step 6: Move draft clearing after acceptance**

At `ui/ChatScreen.kt:588-595`, replace the send handler:

```kotlin
                            onSend = {
                                val text = AttachmentTextExtractor.buildPrompt(prompt.trim(), attachments)
                                if (text.isNotEmpty()) {
                                    // Ask the service before destroying anything. A refusal
                                    // must leave the composed text and attachments intact.
                                    if (service?.acceptsGeneration(text) == true) {
                                        prompt = ""
                                        savedAttachments = emptyList()
                                        draftStore.clear()
                                        service?.generateSafely(text)
                                    } else {
                                        snackbarMessage =
                                            service?.generationRefusalReason(text)
                                                ?: "Message refused; draft kept"
                                    }
                                }
                            },
```

Note the screen now *observes* the refusal reason; it does not re-derive it.

- [ ] **Step 7: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*SendAcceptanceTest*' --tests '*GenerationBudgetTest*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL. `GenerationBudgetTest` must still pass — its contract is unchanged.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/generation/SendAcceptance.kt app/src/main/java/com/prismai/llmhost/generation/GenerationOrchestrator.kt app/src/main/java/com/prismai/llmhost/service/InferenceService.kt app/src/main/java/com/prismai/llmhost/ui/ChatScreen.kt app/src/test/java/com/prismai/llmhost/generation/SendAcceptanceTest.kt
git commit -m "fix(chat): keep the draft when a send is refused

Send cleared the prompt and attachments, then called generateSafely,
which is fire-and-forget. The orchestrator discovered afterwards that no
model was selected or that the turn exceeded the context window, so both
refusals destroyed composed text and attachments and left only a
transient snackbar.

Extract SendAcceptance as the single rule, have the orchestrator use it,
and expose a non-suspending pre-flight on the service. The screen now
asks before clearing, and shows the orchestrator's own refusal reason
rather than re-deriving validity.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Move attachment extraction to background work

**Why:** `AttachmentTextExtractor.fromUri` runs `openInputStream` and `BitmapFactory.decodeStream` on the main thread for up to six files, and the limit is applied *after* processing.

**Files:**
- Modify: `AttachmentTextExtractor.kt` — add a suspend entry point near `fromUri` (`:43-78`)
- Modify: `ui/ChatScreen.kt:282-324` (picker callback)
- Test: `app/src/test/java/com/prismai/llmhost/AttachmentSelectionLimitTest.kt` (create)

**Interfaces:**
- Consumes: `AttachmentTextExtractor.fromUri(context: Context, uri: Uri): PromptAttachment` (`:43`), `displayName(context, uri)`, `MAX_ATTACHMENT_BYTES` (`:20`), `MAX_PROMPT_ATTACHMENTS` (6, currently `ChatScreen.kt:133`).
- Produces: `AttachmentTextExtractor.suspend fun fromUriAsync(context: Context, uri: Uri): PromptAttachment?` — null on cancellation or failure, running on `Dispatchers.IO`.
- Consumed by the picker callback.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/AttachmentSelectionLimitTest.kt`:

```kotlin
package com.prismai.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentSelectionLimitTest {

    /**
     * The picker reported "Importing N" while processing every file and then
     * keeping only the last 6. The limit must decide what gets *read* from the
     * content provider, not just what is retained afterwards.
     */
    @Test
    fun limitIsAppliedBeforeProcessing() {
        val selected = List(10) { "file$it.txt" }
        val accepted = AttachmentSelection.takeUpTo(selected, AttachmentSelection.MAX_PROMPT_ATTACHMENTS)
        assertEquals(AttachmentSelection.MAX_PROMPT_ATTACHMENTS, accepted.size)
        assertEquals("file0.txt", accepted.first())
    }

    @Test
    fun selectionUnderLimitIsUnchanged() {
        val selected = listOf("a.txt", "b.txt")
        assertEquals(selected, AttachmentSelection.takeUpTo(selected, AttachmentSelection.MAX_PROMPT_ATTACHMENTS))
    }

    @Test
    fun selectionIsDeduplicatedPreservingOrder() {
        val selected = listOf("a.txt", "b.txt", "a.txt")
        assertEquals(listOf("a.txt", "b.txt"), AttachmentSelection.takeUpTo(selected, AttachmentSelection.MAX_PROMPT_ATTACHMENTS))
    }

    @Test
    fun emptySelectionYieldsEmpty() {
        assertTrue(AttachmentSelection.takeUpTo(emptyList(), AttachmentSelection.MAX_PROMPT_ATTACHMENTS).isEmpty())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*AttachmentSelectionLimitTest*'
```
Expected: FAIL to compile — `AttachmentSelection` does not exist.

- [ ] **Step 3: Implement the selection helper**

Add to `AttachmentTextExtractor.kt`, in the same file so the limit has one home:

```kotlin
/**
 * Shared attachment-selection limit and ordering.
 *
 * Previously MAX_PROMPT_ATTACHMENTS lived in ChatScreen and was applied after
 * every selected file had already been read from its content provider, so a
 * 20-file selection performed 20 blocking reads and discarded most of them.
 */
object AttachmentSelection {
    const val MAX_PROMPT_ATTACHMENTS = 6

    /** Deduplicate by name, preserving order, then cap at [limit]. */
    fun takeUpTo(selected: List<String>, limit: Int): List<String> =
        selected.distinct().take(if (limit > 0) limit else 0)
}
```

Delete the private `MAX_PROMPT_ATTACHMENTS = 6` at `ChatScreen.kt:133` and use
`AttachmentSelection.MAX_PROMPT_ATTACHMENTS`.

- [ ] **Step 4: Add the background extraction entry point**

In `AttachmentTextExtractor.kt`, next to `fromUri`:

```kotlin
    /**
     * Extract an attachment off the main thread.
     *
     * fromUri performs blocking provider reads (openInputStream,
     * BitmapFactory.decodeStream) of up to 512 KiB per file. Calling it from
     * the picker callback ran that on the UI thread. Returns null if the
     * coroutine is cancelled before extraction completes.
     */
    suspend fun fromUriAsync(context: Context, uri: Uri): PromptAttachment? =
        withContext(Dispatchers.IO) {
            ensureActive()
            runCatching { fromUri(context, uri) }.getOrNull()
        }
```

Add `import kotlinx.coroutines.ensureActive`.

- [ ] **Step 5: Rewrite the picker callback**

Replace `ui/ChatScreen.kt:282-324` with a version that limits first, then
extracts in the background:

```kotlin
                        val attachmentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                            onImportPickerFinished()
                            if (uris.isEmpty()) return@rememberLauncherForActivityResult

                            // Apply the limit before reading anything: the old path read
                            // every provider stream on the UI thread and then discarded
                            // all but the last six.
                            val capped = uris.take(AttachmentSelection.MAX_PROMPT_ATTACHMENTS)
                            val dropped = uris.size - capped.size

                            scope.launch {
                                val attached = mutableListOf<PromptAttachment>()
                                val ggufUris = mutableListOf<Uri>()
                                val importedModels = mutableListOf<String>()

                                for (uri in capped) {
                                    ensureActive()
                                    runCatching {
                                        context.contentResolver.takePersistableUriPermission(
                                            uri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                        )
                                    }
                                    val name = AttachmentTextExtractor.displayName(context, uri)
                                    if (name.endsWith(".gguf", ignoreCase = true)) {
                                        ggufUris += uri
                                        importedModels += name
                                    } else {
                                        AttachmentTextExtractor.fromUriAsync(context, uri)
                                            ?.let { attached += it }
                                    }
                                }

                                if (attached.isNotEmpty()) {
                                    attachments = (attachments + attached)
                                        .distinctBy { it.uriString }
                                        .takeLast(AttachmentSelection.MAX_PROMPT_ATTACHMENTS)
                                }
                                // Task 4 imports every selected GGUF, not just the first.
                                ggufUris.forEach { service?.importModel(it) }
                                if (dropped > 0) {
                                    snackbarMessage = "Added $dropped fewer attachment(s) (limit ${AttachmentSelection.MAX_PROMPT_ATTACHMENTS})"
                                }
                            }
                        }
```

- [ ] **Step 6: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*AttachmentSelectionLimitTest*' --tests '*AttachmentTextExtractorTest*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL. The existing `AttachmentTextExtractorTest` must still pass — `fromUri` is unchanged.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/AttachmentTextExtractor.kt app/src/main/java/com/prismai/llmhost/ui/ChatScreen.kt app/src/test/java/com/prismai/llmhost/AttachmentSelectionLimitTest.kt
git commit -m "perf(chat): extract attachments off the main thread, limit first

AttachmentTextExtractor.fromUri performs blocking provider reads -
openInputStream and BitmapFactory.decodeStream of up to 512 KiB per file
- and the picker callback called it directly on the UI thread for every
selected file. MAX_PROMPT_ATTACHMENTS was applied afterwards, so a
20-file selection performed 20 reads and kept six.

Cap the selection before any provider read and extract via a cancellable
suspend function on Dispatchers.IO. Tell the user when files were dropped
rather than silently discarding them.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Import every selected GGUF, and give retry its target

**Why:** "Importing N" imported only the first, and Retry Download retried the dropdown's selection rather than the model that failed.

**Files:**
- Modify: `service/InferenceService.kt` — add a sequential import queue near `importModel` (`:662-668`)
- Modify: `ui/controlplane/ControlPlaneSheet.kt:649-665` (retry), `:634-636` (selection)
- Modify: `model/ModelImportManager.kt:36-44` — relax single-flight for queued imports
- Test: `app/src/test/java/com/prismai/llmhost/model/SequentialImportQueueTest.kt` (create)

**Interfaces:**
- Consumes: `ModelImportState`, `service.importModel(uri: Uri)`, `ModelDownloadState.Failure.entryId` (PR 1 Task 7).
- Produces: `InferenceService.importModels(uris: List<Uri>)` which imports each in order, one at a time.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/model/SequentialImportQueueTest.kt`:

```kotlin
package com.prismai.llmhost.model

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SequentialImportQueueTest {

    @Test
    fun queueImportsEveryUriInOrder() = runTest {
        val done = mutableListOf<String>()
        val queue = SequentialImportQueue { uri -> done += uri }
        queue.enqueueAll(listOf("a", "b", "c"))
        queue.drain()
        assertEquals(listOf("a", "b", "c"), done)
    }

    @Test
    fun queueContinuesAfterAFailure() = runTest {
        val done = mutableListOf<String>()
        val queue = SequentialImportQueue { uri ->
            if (uri == "b") error("boom")
            done += uri
        }
        queue.enqueueAll(listOf("a", "b", "c"))
        queue.drain()
        assertEquals("a failed import must not abort the rest", listOf("a", "c"), done)
    }

    @Test
    fun queueIsNotConcurrent() = runTest {
        var inFlight = 0
        var maxConcurrent = 0
        val queue = SequentialImportQueue {
            inFlight++
            maxConcurrent = maxOf(maxConcurrent, inFlight)
            inFlight--
        }
        queue.enqueueAll(listOf("a", "b", "c"))
        queue.drain()
        assertEquals("ModelImportManager is single-flight", 1, maxConcurrent)
    }

    @Test
    fun emptyQueueIsANoOp() = runTest {
        val done = mutableListOf<String>()
        val queue = SequentialImportQueue { done += it }
        queue.enqueueAll(emptyList())
        queue.drain()
        assertEquals(emptyList<String>(), done)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*SequentialImportQueueTest*'
```
Expected: FAIL to compile — `SequentialImportQueue` does not exist.

- [ ] **Step 3: Implement the queue**

Create `model/SequentialImportQueue.kt`:

```kotlin
package com.prismai.llmhost.model

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs model imports strictly one at a time.
 *
 * The multi-select picker reported "Importing N" but called importModel only
 * for the first URI; the rest were silently dropped. ModelImportManager is
 * single-flight by design, so a naive forEach would have every call after the
 * first rejected with "A model import is already running".
 */
class SequentialImportQueue(private val importOne: suspend (String) -> Unit) {

    private val pending = mutableListOf<String>()

    @Synchronized
    fun enqueueAll(uris: List<String>) {
        pending += uris
    }

    /** Import everything queued, one at a time, continuing past failures. */
    suspend fun drain() {
        while (true) {
            val next = synchronized(this) { pending.removeFirstOrNull() } ?: return
            runCatching { importOne(next) }
        }
    }
}
```

- [ ] **Step 4: Wire it into the service**

In `InferenceService`, near `importModel` (`:662-668`):

```kotlin
    /** Import several GGUFs in order; one at a time, continuing past failures. */
    fun importModels(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val pending = uris.map { it.toString() }
        serviceScope.launch {
            val queue = SequentialImportQueue { uriString ->
                uris.firstOrNull { it.toString() == uriString }?.let { importModel(it) }
            }
            queue.enqueueAll(pending)
            queue.drain()
        }
    }
```

`importModel` is non-suspending and already returns `Job?`; calling it
sequentially from the drain loop respects the single-flight guard because the
previous job completes before the next begins only if `importModel` blocks. Verify
that; if it returns immediately, await the returned `Job?` before the next item.

- [ ] **Step 5: Point the picker at the queue**

In `ChatScreen.kt` (Task 3 Step 5), replace the per-URI import loop:

```kotlin
                                ggufUris.forEach { service?.importModel(it) }
```

with:

```kotlin
                                if (ggufUris.isNotEmpty()) {
                                    service?.importModels(ggufUris)
                                }
```

and make the status message truthful:

```kotlin
                                if (importedModels.isNotEmpty()) {
                                    snackbarMessage = "Importing ${importedModels.size} model(s)"
                                }
```

- [ ] **Step 6: Fix Retry Download to target the failed model**

At `ui/controlplane/ControlPlaneSheet.kt:649-665`, replace the failure branch's
retry so it uses the failed entry's id from PR 1:

```kotlin
            is ModelDownloadState.Failure -> {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "${state.entryName}: ${state.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = PrismRed,
                    )
                    // Retry the model that failed, not the dropdown selection,
                    // which defaults to the first catalog entry.
                    state.entryId?.let { failedId ->
                        TextButton(
                            contentPadding = PaddingValues(0.dp),
                            onClick = { onDownload(failedId) },
                        ) {
                            Text("Retry Download", color = PrismBlue, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
```

Remove the now-unused `selectedEntry?.let` wrapper at `:656`.

- [ ] **Step 7: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*SequentialImportQueueTest*' --tests '*Import*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/model/SequentialImportQueue.kt app/src/main/java/com/prismai/llmhost/model/ModelImportManager.kt app/src/main/java/com/prismai/llmhost/service/InferenceService.kt app/src/main/java/com/prismai/llmhost/ui/ChatScreen.kt app/src/main/java/com/prismai/llmhost/ui/controlplane/ControlPlaneSheet.kt app/src/test/java/com/prismai/llmhost/model/SequentialImportQueueTest.kt
git commit -m "fix(models): import every selected GGUF and retry the failed one

Multi-select reported \"Importing N\" but called importModel only for the
first URI, silently dropping the rest. ImportModelManager is single-flight,
so importing in parallel would have rejected every call after the first;
add SequentialImportQueue, which drains strictly one at a time and
continues past failures.

Retry Download used the dropdown's selectedEntry, which defaults to the
first catalog entry, so retrying a failure on model B re-downloaded model
A. Use the entryId that PR 1 added to ModelDownloadState.Failure.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: One observable installed-model state

**Why:** the cached list is a `remember` snapshot bumped only on `ImportState.Success`, so deletions and downloads leave stale models in the UI.

**Files:**
- Modify: `ui/ServiceUiState.kt` — add `_installedModels` / `installedModels`
- Modify: `service/InferenceService.kt` — publish on every mutation; move `listModels()` off the main thread
- Modify: `ui/ChatScreen.kt:179-181` (snapshot), `:362-366` (refresh effect), `:384-388`
- Test: `app/src/test/java/com/prismai/llmhost/ui/InstalledModelsStateTest.kt` (create)

**Interfaces:**
- Consumes: `ModelManager.listModels(): List<String>`, `ModelStorageManager` mutation paths.
- Produces: `ServiceUiState.installedModels: StateFlow<List<String>>`, republished after import, delete, download, and link (link is removed in Task 6).

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/ui/InstalledModelsStateTest.kt`:

```kotlin
package com.prismai.llmhost.ui

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class InstalledModelsStateTest {

    /**
     * The cached list was a remember snapshot refreshed only on
     * ImportState.Success, so a deleted model stayed visible until something
     * else forced a reload. One observable state must reflect every mutation.
     */
    @Test
    fun deletionRemovesTheModelFromTheObservableList() = runTest {
        val store = InstalledModelsStore()
        store.set(listOf("a", "b"))
        store.set(listOf("a"))
        assertEquals(listOf("a"), store.value)
    }

    @Test
    fun identicalListDoesNotEmit() = runTest {
        val store = InstalledModelsStore()
        store.set(listOf("a"))
        val before = store.emissionCount
        store.set(listOf("a"))
        assertEquals("a redundant refresh must not re-render the picker", before, store.emissionCount)
    }

    @Test
    fun reorderingDoesEmit() = runTest {
        val store = InstalledModelsStore()
        store.set(listOf("a", "b"))
        val before = store.emissionCount
        store.set(listOf("b", "a"))
        assertEquals(before + 1, store.emissionCount)
    }

    @Test
    fun emptyListIsAValidValue() = runTest {
        val store = InstalledModelsStore()
        store.set(listOf("a"))
        store.set(emptyList())
        assertEquals(emptyList<String>(), store.value)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*InstalledModelsStateTest*'
```
Expected: FAIL to compile — `InstalledModelsStore` does not exist.

- [ ] **Step 3: Implement the store**

Add to `ui/ServiceUiState.kt`:

```kotlin
/**
 * Observable installed-model list.
 *
 * The UI previously held a `remember` snapshot refreshed by a `refreshKey` that
 * only incremented on ImportState.Success. Deleting a model, or completing a
 * download through a path that published no ImportState, left the deleted model
 * visible in the picker. This holds the list and suppresses redundant
 * emissions so an unchanged refresh does not re-render the dropdown.
 */
class InstalledModelsStore {
    private val _value = MutableStateFlow<List<String>>(emptyList())
    val value: StateFlow<List<String>> = _value.asStateFlow()

    /** Exposed for tests: how many times the flow actually emitted. */
    var emissionCount: Int = 0
        private set

    init {
        // Count real emissions from the underlying flow.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            _value.drop(1).collect { emissionCount++ }
        }
    }

    fun set(models: List<String>) {
        if (_value.value == models) return
        _value.value = models
    }
}
```

If the emission-count collector proves awkward to construct in a unit test,
replace it with a plain counter incremented inside `set` guarded by the equality
check — the test only needs to distinguish "value changed" from "value
unchanged". Prefer the simpler version if so.

- [ ] **Step 4: Publish from the service on every mutation**

In `InferenceService`, add a private helper and call it from each path that
changes the installed set:

```kotlin
    private suspend fun refreshInstalledModels() {
        // listModels does synchronous filesystem I/O and a manifest parse per
        // model; it must not run on the main thread.
        val models = withContext(Dispatchers.IO) { modelManager.listModels() }
        uiState._installedModels.value = models
    }
```

Add to `ServiceUiState.kt`:

```kotlin
    internal val _installedModels = MutableStateFlow<List<String>>(emptyList())
    val installedModels: StateFlow<List<String>> = _installedModels.asStateFlow()
```

Call `refreshInstalledModels()` after: a successful import, a model deletion, a
completed download, and `refreshDeviceAndModelReadiness()`. Audit for each place
that mutates `ModelStorageManager` and currently publishes `ImportState.Success`;
those are the call sites.

- [ ] **Step 5: Replace the snapshot in `ChatScreen`**

At `ui/ChatScreen.kt:179-181`, delete `refreshKey` and the `models` state. At
`:362-366`, replace the refresh effect with a direct collection:

```kotlin
                        val models by service?.installedModels
                            ?.collectAsState()
                            ?: remember { mutableStateOf(emptyList()) }
```

Collect once with `collectAsState()` at the top of the composable rather than
inside the effect. Remove the `refreshKey++` at `:385`.

- [ ] **Step 6: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*InstalledModelsStateTest*' --tests '*ModelIdentityDeletionTest*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/ui/ServiceUiState.kt app/src/main/java/com/prismai/llmhost/service/InferenceService.kt app/src/main/java/com/prismai/llmhost/ui/ChatScreen.kt app/src/test/java/com/prismai/llmhost/ui/InstalledModelsStateTest.kt
git commit -m "fix(models): one observable installed-model state

The UI held a remember snapshot refreshed by a refreshKey that only
incremented on ImportState.Success, so a deleted model stayed in the
picker until something else forced a reload. Publish a StateFlow from
every path that mutates the installed set instead.

Also move listModels off the main thread: it does synchronous filesystem
I/O and a manifest parse per model and was called from LaunchedEffect and
a remember initializer.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Remove the link-external chain in full

**Why:** it writes a content URI as the *text content* of `model.gguf` and records a fake `sha256`, so nothing downstream can detect the file is not a GGUF.

**Files:**
- Delete: `storage/ModelStorageManager.linkExternalModelUri` (`:365-403`) and its locked wrapper
- Delete: `service/InferenceService.linkExternalModel` (`:686-694`)
- Delete: `ui/ChatScreen.kt` launcher (`:276-281`) and trigger (`:638-640`)
- Delete: `ui/controlplane/ControlPlaneSheet.kt` `onLinkModel` param (`:100`) and button (`:266-275`)
- Test: `app/src/test/java/com/prismai/llmhost/storage/NoFakeLinkedModelTest.kt` (create)

**Interfaces:**
- Consumes: none. Pure deletion.
- Produces: none.

- [ ] **Step 1: Write the guard test**

Create `app/src/test/java/com/prismai/llmhost/storage/NoFakeLinkedModelTest.kt`:

```kotlin
package com.prismai.llmhost.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The link-external path wrote uri.toString() as the contents of a file named
 * model.gguf and recorded sha256 = "linked_saf_uri" / status = "saf_linked".
 * Nothing downstream could detect the file was not a GGUF, so selecting the
 * model attempted to load 40 bytes of text as a model.
 *
 * This test is a tripwire on the sentinels rather than on behavior, because the
 * honest outcome is simply that the feature no longer exists.
 */
class NoFakeLinkedModelTest {

    @Test
    fun theLinkedSentinelIsGoneFromTheSource() {
        val source = javaClass.getResource("/") // not used; see next test
        assertTrue(true)
    }

    @Test
    fun manifestNeverRecordsAFakeLinkedHash() {
        val text = javaClass.protectionDomain.codeSource.location.readText()
        assertFalse(text.contains("linked_saf_uri"))
    }
}
```

Replace that with a source-level check, which is the honest form for a
deleted feature:

```kotlin
class NoFakeLinkedModelTest {

    @Test
    fun linkExternalIsRemovedFromTheStorageManager() {
        val methods = ModelStorageManager::class.java.declaredMethods.map { it.name }
        assertFalse(
            "linkExternalModelUri must not exist: it produced a fake GGUF",
            methods.any { it.contains("linkExternal", ignoreCase = true) },
        )
    }

    @Test
    fun noSentinelLinkedStatusRemains() {
        val fields = ModelStorageManager::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("LINKED", ignoreCase = true) })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*NoFakeLinkedModelTest*'
```
Expected: FAIL — `linkExternalModelUri` still exists.

- [ ] **Step 3: Delete the storage method**

Remove `linkExternalModelUriLocked` and its public wrapper from
`storage/ModelStorageManager.kt`. Then remove any now-unused private helpers it
was the only caller of (the compiler will not flag them; check
`saf_linked`, `linked_saf_uri`, and any `MODEL_FILE` write helper).

Leave `MODEL_FILE = "model.gguf"` — the real import path uses it.

- [ ] **Step 4: Delete the service method**

Remove `InferenceService.linkExternalModel` (`:686-694`).

- [ ] **Step 5: Delete the UI path**

Remove the `linkLauncher` block at `ui/ChatScreen.kt:276-281` and the
`onLinkModel = { ... }` trigger at `:638-640`.

In `ui/controlplane/ControlPlaneSheet.kt`, remove the `onLinkModel` parameter
declaration at `:100` and the button at `:266-275`, leaving "Import GGUF" as the
only model action in that row.

- [ ] **Step 6: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*NoFakeLinkedModelTest*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL. A leftover reference anywhere will fail
compilation, which is the point.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "fix(models): remove the link-external GGUF path entirely

linkExternalModelUri wrote the content URI as the text contents of a file
named model.gguf and recorded sha256 = \"linked_saf_uri\" with status
\"saf_linked\". Nothing downstream could detect the file was not a GGUF,
so selecting the model reported a successful link and then attempted to
load 40 bytes of URI text as a model.

Remove the storage method, the service entry point, the picker wiring and
the control-plane button together. Copy-import is untouched.

Deleting the code rather than hiding the button also removes the only
producer of the linked-model manifest state.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Keyboard-safe layout, header compaction, dismissible errors

**Why:** the snackbar renders behind the keyboard, the tray is an unbounded `Column`, and `minChatHeight = maxHeight * 0.70f` fights the IME.

**Files:**
- Modify: `ui/ChatScreen.kt:170-177` (`BoxWithConstraints`/`Column`), `:171` (`minChatHeight`), `:427-440` (`ChatTopBar`), `:565-569` (composer), `:752-760` (snackbar)
- Modify: `ui/composer/PromptComposer.kt:147-160` (tray), `:165-227` (`AttachmentTray`)
- Create: `ui/chat/ScrollFollowPolicy.kt`
- Test: `app/src/test/java/com/prismai/llmhost/ui/LayoutPolicyTest.kt` (create)

**Interfaces:**
- Consumes: `BoxWithConstraints` constraints; `WindowInsets.ime` already read at `PromptComposer.kt:90-91`.
- Produces:
  - `object LayoutPolicy { fun isShortHeight(maxHeightDp: Dp): Boolean; fun headerCompact(availableHeightDp: Dp): Boolean; fun trayVisible(attachmentCount: Int, isShortHeight: Boolean): Boolean }`
  - `class ScrollFollowPolicy` with `onUserScrolledAway()`, `onUserScrolledToBottom()`, `shouldAutoScroll(): Boolean`.
  - `SnackbarHostState`-equivalent dismissal wiring via the existing `onClearUiMessage`.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/prismai/llmhost/ui/LayoutPolicyTest.kt`:

```kotlin
package com.prismai.llmhost.ui

import androidx.compose.ui.unit.dp
import com.prismai.llmhost.ui.chat.ScrollFollowPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutPolicyTest {

    @Test
    fun shortHeight_collapsesTheTray() {
        assertFalse(LayoutPolicy.trayVisible(attachmentCount = 4, isShortHeight = true))
    }

    @Test
    fun normalHeight_keepsTheTray() {
        assertTrue(LayoutPolicy.trayVisible(attachmentCount = 4, isShortHeight = false))
    }

    @Test
    fun noAttachments_neverShowsATray() {
        assertFalse(LayoutPolicy.trayVisible(attachmentCount = 0, isShortHeight = false))
    }

    @Test
    fun shortHeight_compactsTheHeader() {
        assertTrue(LayoutPolicy.headerCompact(availableHeightDp = 380.dp))
        assertFalse(LayoutPolicy.headerCompact(availableHeightDp = 800.dp))
    }

    @Test
    fun followPolicy_followsUntilTheUserScrollsAway() {
        val p = ScrollFollowPolicy()
        assertTrue(p.shouldAutoScroll())
        p.onUserScrolledAway()
        assertFalse("must not yank the reader back", p.shouldAutoScroll())
    }

    @Test
    fun followPolicy_resumesWhenTheUserReturnsToTheBottom() {
        val p = ScrollFollowPolicy()
        p.onUserScrolledAway()
        p.onUserScrolledToBottom()
        assertTrue(p.shouldAutoScroll())
    }

    @Test
    fun followPolicy_doesNotLoseFollowOnANewMessage() {
        val p = ScrollFollowPolicy()
        p.onUserScrolledAway()
        // A new message arrives while the reader is reading history.
        assertFalse(p.shouldAutoScroll())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*LayoutPolicyTest*'
```
Expected: FAIL to compile — neither class exists.

- [ ] **Step 3: Implement the policies**

Create `ui/chat/ScrollFollowPolicy.kt`:

```kotlin
package com.prismai.llmhost.ui.chat

/**
 * Whether the transcript may auto-scroll.
 *
 * Requirement: keep the latest message visible when following the
 * conversation, but never pull someone away from older messages they are
 * reading. Auto-scroll resumes only when they return to the bottom.
 */
class ScrollFollowPolicy {
    private var following = true

    fun onUserScrolledAway() {
        following = false
    }

    fun onUserScrolledToBottom() {
        following = true
    }

    fun shouldAutoScroll(): Boolean = following
}
```

Create `ui/LayoutPolicy.kt`:

```kotlin
package com.prismai.llmhost.ui

import androidx.compose.ui.unit.Dp

/**
 * Height-driven layout decisions.
 *
 * The composer already applies imePadding() and navigationBarsPadding(), which
 * is correct. What was missing is behavior when the keyboard shrinks the
 * window: the header did not adapt, and the attachment tray - a plain Column of
 * cards - could consume the space the input needs.
 */
object LayoutPolicy {

    /** Below this, the window is too short to afford the full layout. */
    private val SHORT_HEIGHT = 480.dp

    fun isShortHeight(maxHeight: Dp): Boolean = maxHeight < SHORT_HEIGHT

    fun headerCompact(availableHeight: Dp): Boolean = availableHeight < SHORT_HEIGHT

    /** In short layouts the tray is hidden; the count is shown in the composer instead. */
    fun trayVisible(attachmentCount: Int, isShortHeight: Boolean): Boolean =
        attachmentCount > 0 && !isShortHeight
}
```

- [ ] **Step 4: Apply the layout policies in `ChatScreen`**

At `ui/ChatScreen.kt:171`, replace the fixed-ratio height heuristic:

```kotlin
                        val isShortHeight = LayoutPolicy.isShortHeight(maxHeight)
                        // The old maxHeight * 0.70f floor fought the IME: with the
                        // keyboard open the list could not shrink below 70% of a
                        // window that had already shrunk.
                        val minChatHeight = 0.dp
```

Pass `isShortHeight` into `ChatTopBar` (`:427-440`) so it can compact, and use
`LayoutPolicy.headerCompact(maxHeight)` to reduce the header's vertical content
when true — the existing `Spacer(h22)` at `:442` becomes smaller in short
layouts.

- [ ] **Step 5: Move the snackbar inside the keyboard-safe region**

At `ui/ChatScreen.kt:752-760`, the snackbar is aligned to the outer `Box`,
which is outside the `imePadding()`d composer, so errors rendered behind the
keyboard. Move it inside the padded column, directly above the composer:

```kotlin
                            // Inside the IME-padded region: the old placement aligned to the
                            // outer Box, so refusals were invisible behind the keyboard.
                            (snackbarMessage ?: uiMessage)?.let { message ->
                                Snackbar(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                    containerColor = PrismSlate,
                                    contentColor = PrismOnDark,
                                    action = {
                                        TextButton(onClick = {
                                            if (snackbarMessage != null) {
                                                snackbarMessage = null
                                            }
                                            onClearUiMessage(message)
                                        }) {
                                            Text("Dismiss", color = PrismBlue)
                                        }
                                    },
                                ) {
                                    Text(message)
                                }
                            }
```

Keep the existing 8-second auto-dismiss at `:157-165`; the action is additive,
so the error is now dismissible as well as self-clearing.

- [ ] **Step 6: Make the tray height-aware**

In `ui/composer/PromptComposer.kt:147-150`, gate the tray:

```kotlin
            if (LayoutPolicy.trayVisible(attachments.size, isShortHeight)) {
                AttachmentTray(
                    attachments = attachments,
                    onRemove = onRemoveAttachment,
                )
            } else if (attachments.isNotEmpty()) {
                // Keep removal reachable even when the tray is collapsed.
                Text(
                    text = "${attachments.size} attachment(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = PrismOnDark.copy(alpha = 0.7f),
                )
            }
```

Thread an `isShortHeight: Boolean` parameter into `PromptComposer` from
`ChatScreen`. Inside `AttachmentTray` (`:165-227`), replace the unbounded
`Column` with a `LazyRow` so many rows cannot consume the available height.

- [ ] **Step 7: Wire auto-scroll through the follow policy**

In the `LazyColumn` at `ui/ChatScreen.kt:455-507`, observe user scroll to
decide whether to keep following:

```kotlin
                        val scrollFollowPolicy = remember { ScrollFollowPolicy() }
                        val listState = rememberLazyListState()
```

Detect the user leaving the bottom via `listState.layoutInfo`, and call
`scrollFollowPolicy.onUserScrolledAway()` / `onUserScrolledToBottom()`.
Guard the existing scroll-to-bottom effect with
`if (scrollFollowPolicy.shouldAutoScroll())`. Reuse the existing
`AnimatedVisibility` scroll-to-bottom FAB at `:516-563` so a user who scrolled
away has an obvious way back, which then re-enables following.

- [ ] **Step 8: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*LayoutPolicyTest*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/ui/LayoutPolicy.kt app/src/main/java/com/prismai/llmhost/ui/chat/ScrollFollowPolicy.kt app/src/main/java/com/prismai/llmhost/ui/ChatScreen.kt app/src/main/java/com/prismai/llmhost/ui/composer/PromptComposer.kt app/src/test/java/com/prismai/llmhost/ui/LayoutPolicyTest.kt
git commit -m "fix(chat): make the composer keyboard-safe and height-aware

The snackbar was aligned to the outer Box, outside the imePadding'd
composer, so refusals like \"too long for the context window\" rendered
behind the keyboard. Move it inside the padded region and add a Dismiss
action alongside the existing 8s auto-clear.

minChatHeight was maxHeight * 0.70f, a floor that fought the IME because
the window had already shrunk. Replace it with height-driven decisions:
compact the header and collapse the attachment tray in short layouts.
The tray was a plain Column, so six rows could consume the space the
input needs; make it a LazyRow and keep removal reachable when collapsed.

Add ScrollFollowPolicy so the latest message stays visible while
following, but the view is never pulled away from older messages the user
is reading; the existing FAB is the way back.

No visual redesign: existing colors, typography and shapes are unchanged.
Layout behavior is device-unverified and needs the S25 Ultra gate.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Fix the recognizer leak (not the voice feature)

**Why:** `onResults`/`onError` set `isListening = false` without destroying the recognizer, and `stopListening()` early-returns in that state — so the normal success path leaks, and `shutdown()` cannot clean it up.

**Files:**
- Modify: `tools/VoiceIoManager.kt:105-110` (error path), `:129-135` (results path), `:171-177` (`stopListening`)
- Test: `app/src/test/java/com/prismai/llmhost/tools/VoiceRecognizerLifecycleTest.kt` (create)

**Interfaces:**
- Consumes: `SpeechRecognizer.destroy()`.
- Produces: `internal fun releaseRecognizer()` on `VoiceIoManager`, safe to call repeatedly.

- [ ] **Step 1: Write the failing test**

`VoiceIoManager` requires a `Context` and the Android speech framework, so the
testable unit is the release decision, extracted as pure state. Create
`app/src/test/java/com/prismai/llmhost/tools/VoiceRecognizerLifecycleTest.kt`:

```kotlin
package com.prismai.llmhost.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceRecognizerLifecycleTest {

    /**
     * onResults and onError both cleared isListening without destroying the
     * recognizer, and stopListening() early-returns once isListening is false,
     * so shutdown() could never clean it up. The recognizer leaked on the
     * normal success path.
     */
    @Test
    fun completionReleasesTheRecognizerEvenWhenNotListening() {
        val lifecycle = RecognizerLifecycle()
        lifecycle.onRecognizerCreated()
        assertTrue(lifecycle.isCreated)

        lifecycle.onTerminated()

        assertTrue("terminated session must release the recognizer", lifecycle.releaseRequested)
    }

    @Test
    fun repeatedTerminationReleasesOnlyOnce() {
        val lifecycle = RecognizerLifecycle()
        lifecycle.onRecognizerCreated()
        lifecycle.onTerminated()
        lifecycle.onTerminated()
        assertEquals(1, lifecycle.releaseCount)
    }

    @Test
    fun shutdownReleasesAnAbandonedRecognizer() {
        val lifecycle = RecognizerLifecycle()
        lifecycle.onRecognizerCreated()
        lifecycle.onTerminated()   // clears listening state without destroying
        lifecycle.shutdown()      // previously could not clean up
        assertTrue(lifecycle.releaseRequested)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*VoiceRecognizerLifecycleTest*'
```
Expected: FAIL to compile — `RecognizerLifecycle` does not exist.

- [ ] **Step 3: Implement the lifecycle helper**

Create `tools/RecognizerLifecycle.kt`:

```kotlin
package com.prismai.llmhost.tools

/**
 * Tracks whether a SpeechRecognizer is live and requests release exactly once.
 *
 * VoiceIoManager assigned a recognizer in startListening, then onResults and
 * onError both set isListening = false without destroying it. Because
 * stopListening() returns early when !isListening, neither stopListening() nor
 * shutdown() could release it - the recognizer leaked on the ordinary success
 * path, not only on abandonment.
 */
class RecognizerLifecycle {
    var isCreated: Boolean = false
        private set
    var releaseRequested: Boolean = false
        private set
    var releaseCount: Int = 0
        private set

    fun onRecognizerCreated() {
        isCreated = true
        releaseRequested = false
    }

    /** A session ended (results or error); release the recognizer. */
    fun onTerminated() {
        if (isCreated && !releaseRequested) {
            releaseRequested = true
            releaseCount++
        }
    }

    fun shutdown() {
        onTerminated()
        isCreated = false
    }
}
```

- [ ] **Step 4: Wire it into `VoiceIoManager`**

Add a `private val recognizerLifecycle = RecognizerLifecycle()` field. In
`startListening`, after assigning `speechRecognizer`, call
`recognizerLifecycle.onRecognizerCreated()`.

In the `onError` handler (`:105-110`) and the `onResults` handler (`:129-135`),
where each currently sets `isListening = false`, also call
`recognizerLifecycle.onTerminated()` and then destroy:

```kotlin
    /** Release the recognizer. Safe to call repeatedly and when none is held. */
    fun releaseRecognizer() {
        if (!recognizerLifecycle.isCreated) return
        runCatching { speechRecognizer?.destroy() }
        speechRecognizer = null
        recognizerLifecycle.shutdown()
    }
```

Call `releaseRecognizer()` from `onError`, `onResults`, `stopListening`
(after `stopListening()` on the recognizer), and `shutdown()`.

Keep the recognized-text behavior exactly as it is: `_voiceInputResult` is still
dropped on the floor by the UI. **Building the voice feature is out of scope.**

- [ ] **Step 5: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*VoiceRecognizerLifecycleTest*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/tools/RecognizerLifecycle.kt app/src/main/java/com/prismai/llmhost/tools/VoiceIoManager.kt app/src/test/java/com/prismai/llmhost/tools/VoiceRecognizerLifecycleTest.kt
git commit -m "fix(voice): release the speech recognizer when a session ends

startListening assigned a recognizer, then onResults and onError both set
isListening = false without destroying it. stopListening returns early
when !isListening, so neither it nor shutdown could clean the recognizer
up - it leaked on the ordinary success path, not only on abandonment.

Track the recognizer in RecognizerLifecycle and release exactly once on
termination, on explicit stop, and on shutdown.

This is a resource-leak fix only. Building the voice feature (RECORD_AUDIO
runtime permission, transferring recognized text into an editable draft)
remains out of scope; the recognized text is still discarded by the UI.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 9: Make "Report saved locally" actually save

**Why:** the submit handler is a `Toast` claiming a local save that never happens.

**Files:**
- Modify: `ui/chat/MessageItem.kt:72-81` (handler), `:160-216` (dialog)
- Modify: `SecurityAuditLog.kt` (append entry)
- Test: `app/src/test/java/com/prismai/llmhost/SecurityAuditReportTest.kt` (create)

**Interfaces:**
- Consumes: `SecurityAuditLog`'s existing append-only JSONL format and ~512 KB truncation (`SecurityAuditLog.kt:25`).
- Produces: `SecurityAuditLog.appendReport(messageId: String, reason: String, excerpt: String)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/SecurityAuditReportTest.kt`:

```kotlin
package com.prismai.llmhost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityAuditReportTest {

    @Test
    fun reportEntry_isSerializableAndRoundTrips() {
        val entry = SecurityAuditLog.ReportEntry(
            messageId = "m-1",
            reason = "Dangerous or harmful instructions",
            excerpt = "do the thing",
        )
        val json = entry.toJson()
        assertEquals("m-1", json.getString("messageId"))
        assertTrue(json.toString().contains("report"))
    }

    @Test
    fun reportEntry_keepsTimestamp() {
        val entry = SecurityAuditLog.ReportEntry("m-1", "reason", "excerpt", timestampMs = 12345L)
        assertEquals(12345L, entry.toJson().getLong("timestampMs"))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*SecurityAuditReportTest*'
```
Expected: FAIL to compile — `ReportEntry` does not exist.

- [ ] **Step 3: Add the report entry**

In `SecurityAuditLog.kt`, add alongside the existing entry type:

```kotlin
    /**
     * A user-submitted AI-content report.
     *
     * The report dialog previously showed "Report saved locally" from a Toast
     * while persisting nothing. This is the record it claimed to write.
     */
    data class ReportEntry(
        val messageId: String,
        val reason: String,
        val excerpt: String,
        val timestampMs: Long = System.currentTimeMillis(),
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("type", "ai_content_report")
            put("messageId", messageId)
            put("reason", reason)
            put("excerpt", excerpt.take(500))
            put("timestampMs", timestampMs)
        }
    }
```

Add `fun appendReport(messageId: String, reason: String, excerpt: String)`
that writes through the same append-and-truncate path the class already uses.

- [ ] **Step 4: Replace the Toast**

At `ui/chat/MessageItem.kt:77-79`, replace:

```kotlin
            onSubmitReport = { reason ->
                Toast.makeText(context, "Report saved locally: $reason", Toast.LENGTH_SHORT).show()
            }
```

with:

```kotlin
            onSubmitReport = { reason ->
                // Actually persist it. The previous handler showed a Toast
                // claiming a local save that never happened.
                SecurityAuditLog.appendReport(
                    messageId = message.id,
                    reason = reason,
                    excerpt = message.text.take(500),
                )
            }
```

Adjust the field names to whatever the surrounding message model actually uses.
Confirm the append does not throw on failure — wrap in `runCatching` and surface
nothing rather than crashing the dialog.

- [ ] **Step 5: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*SecurityAuditReportTest*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/SecurityAuditLog.kt app/src/main/java/com/prismai/llmhost/ui/chat/MessageItem.kt app/src/test/java/com/prismai/llmhost/SecurityAuditReportTest.kt
git commit -m "fix(chat): persist AI-content reports instead of claiming to

The report dialog's submit handler showed a Toast reading \"Report saved
locally\" while writing nothing anywhere. The message was factually
wrong and the report was lost.

Append reports to the existing append-only SecurityAuditLog with message
id, reason, excerpt and timestamp, reusing its truncation behavior.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 10: Surface document-ingest failures honestly

**Why:** completes PR 1 §1.1a. An empty embedding currently increments a silent `failedCount`, and `PromptBuilder` swallows it behind `runCatching`.

**Files:**
- Modify: `ui/rag/DocumentBrowser.kt:254-263` (ingest trigger)
- Modify: `service/InferenceService.kt` — `ingestDocument` publishes a reason
- Test: `app/src/test/java/com/prismai/llmhost/ui/DocumentIngestMessagingTest.kt` (create)

**Interfaces:**
- Consumes: `RagManager.ingestDocumentWithResult(...)` returning `IngestResult`; `InferenceService.ingestDocument` (`:1801-1803`).
- Produces: a user-visible reason when ingest yields no usable chunks.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/prismai/llmhost/ui/DocumentIngestMessagingTest.kt`:

```kotlin
package com.prismai.llmhost.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentIngestMessagingTest {

    @Test
    fun allChunksFailed_isExplainedNotSilent() {
        val message = DocumentIngestMessaging.describe(inserted = 0, failed = 12, total = 12)
        assertTrue("a total failure must be explained", message.contains("could not"))
    }

    @Test
    fun partialFailure_isReportedHonestly() {
        val message = DocumentIngestMessaging.describe(inserted = 8, failed = 4, total = 12)
        assertTrue(message.contains("8"))
        assertTrue(message.contains("4"))
    }

    @Test
    fun fullSuccess_isNotOverExplained() {
        val message = DocumentIngestMessaging.describe(inserted = 12, failed = 0, total = 12)
        assertTrue(message.contains("12"))
        assertTrue(!message.contains("could not"))
    }

    @Test
    fun emptyInput_isReportedAsSuch() {
        assertEquals("Nothing to index", DocumentIngestMessaging.describe(0, 0, 0))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*DocumentIngestMessagingTest*'
```
Expected: FAIL to compile — `DocumentIngestMessaging` does not exist.

- [ ] **Step 3: Implement the messaging**

Create `ui/rag/DocumentIngestMessaging.kt`:

```kotlin
package com.prismai.llmhost.ui.rag

/**
 * Explain an ingest result.
 *
 * When Engine::encode returns nothing - for example a model that cannot produce
 * embeddings - RagManager counted the chunk as failed and PromptBuilder swallowed
 * the error behind runCatching, so the user saw a silent no-op. State the counts.
 */
object DocumentIngestMessaging {
    fun describe(inserted: Int, failed: Int, total: Int): String = when {
        total == 0 -> "Nothing to index"
        inserted == 0 -> "Could not index this document: no text could be embedded ($failed of $total chunks failed)"
        failed > 0 -> "Indexed $inserted of $total chunks; $failed could not be embedded"
        else -> "Indexed $inserted chunks"
    }
}
```

- [ ] **Step 4: Wire it into the service and browser**

Have `InferenceService.ingestDocument` publish the counts via `publishUiEvent`,
reusing the existing event path so `ChatScreen`'s snackbar shows it without new
wiring. At `ui/rag/DocumentBrowser.kt:254-263`, surface the same string in the
document browser's status line.

- [ ] **Step 5: Verify**

Run:
```bash
./gradlew --no-daemon :app:testDevDebugUnitTest --tests '*DocumentIngestMessagingTest*'
./gradlew --no-daemon :app:assembleDevDebug
```
Expected: PASS / BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/prismai/llmhost/ui/rag/DocumentIngestMessaging.kt app/src/main/java/com/prismai/llmhost/ui/rag/DocumentBrowser.kt app/src/main/java/com/prismai/llmhost/service/InferenceService.kt app/src/test/java/com/prismai/llmhost/ui/DocumentIngestMessagingTest.kt
git commit -m "fix(rag): explain document ingest failures instead of swallowing them

When Engine::encode returned nothing, RagManager incremented a failedCount
that nothing displayed and PromptBuilder swallowed the error behind
runCatching, so the user saw a silent no-op with no way to tell that
indexing had failed.

Report inserted/failed/total through the existing UI event path and the
document browser status line.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Full verification and device gate

**Why:** layout claims are the ones this PR makes, and none of them are verifiable in a JVM test.

**Files:** none. Verification only.

- [ ] **Step 1: Run the unit suite**

```bash
./gradlew --no-daemon :app:testDevDebugUnitTest
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Assemble both debug flavors**

```bash
./gradlew --no-daemon :app:assembleDevDebug :app:assemblePlayDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Compile instrumentation, and state it is not executed**

```bash
./gradlew --no-daemon :app:assembleDevDebugAndroidTest
```
Expected: BUILD SUCCESSFUL. Record explicitly that instrumentation was **compiled
but not executed** — no emulator or device is available here.

- [ ] **Step 4: Run the device gate and record results**

On the S25 Ultra, with the qualified APK from the release gate:

| Journey | Pass/Fail | Notes |
| ------- | --------- | ----- |
| Open keyboard; input, Send/Stop, attachment removal all reachable | | |
| Short-height layout compacts header, collapses tray | | |
| Follow conversation: latest message stays visible | | |
| Reading older messages: no yank-away | | |
| Back dismisses keyboard naturally | | |
| Open/close sheets: draft preserved | | |
| Error appears above keyboard, dismissible | | |
| Rotate with a draft: draft preserved | | |
| Switch chats: drafts do not cross | | |
| Samsung Keyboard **and** Gboard | | |
| Portrait **and** landscape | | |
| Large font scale | | |
| Both navigation modes | | |

- [ ] **Step 5: Report honestly**

State which commands ran and their results, and state plainly that **the layout
and draft behaviors are device-unverified** until the table above is filled in.
Do not claim the keyboard work is done without it.

- [ ] **Step 6: Do not push without user approval**
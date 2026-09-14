package com.prismai.llmhost.agent.tools

import com.prismai.llmhost.tools.AgentToolCall
import com.prismai.llmhost.tools.AgentToolErrorCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/**
 * D6 boundary tests: the agent workspace tools must be confined to the
 * dedicated `filesDir/agent-workspace` subtree and must not reach chats,
 * agent traces, exports, models, or the security audit log that live directly
 * under `filesDir`.
 */
class WorkspaceToolsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File

    @Before
    fun setup() {
        filesDir = tempFolder.newFolder("files")
    }

    private fun call(name: String, vararg args: Pair<String, Any>): AgentToolCall {
        val json = JSONObject()
        args.forEach { (key, value) -> json.put(key, value) }
        return AgentToolCall(name = name, arguments = json)
    }

    @Test
    fun defaultRootCreatesDedicatedSubtreeWithReadme() {
        val root = WorkspaceTools.defaultRoot(filesDir)

        assertEquals(File(filesDir, WorkspaceTools.WORKSPACE_DIR_NAME).canonicalFile, root.canonicalFile)
        assertTrue("workspace root must exist", root.isDirectory)
        assertTrue(
            "boundary README must be seeded",
            File(root, WorkspaceTools.WORKSPACE_README_NAME).isFile,
        )
    }

    @Test
    fun defaultRootIsIdempotentAndPreservesWorkspaceFiles() {
        val root = WorkspaceTools.defaultRoot(filesDir)
        val userFile = File(root, "keep.txt").apply { writeText("KEEP ME") }

        val again = WorkspaceTools.defaultRoot(filesDir)

        assertEquals(root.canonicalFile, again.canonicalFile)
        assertTrue(userFile.isFile)
        assertEquals("KEEP ME", userFile.readText())
    }

    @Test
    fun defaultRootDoesNotCopyOrMoveLegacyFilesDirContents() {
        val legacyChat = File(filesDir, "chats").apply { mkdirs() }
        val transcript = File(legacyChat, "chat-1.json").apply { writeText("PRIVATE CHAT") }
        val auditLog = File(filesDir, "security_audit.jsonl").apply { writeText("AUDIT ENTRY") }
        val looseFile = File(filesDir, "loose-notes.txt").apply { writeText("TOP LEVEL") }

        val root = WorkspaceTools.defaultRoot(filesDir)

        // Nothing is moved or copied out of filesDir.
        assertTrue("chats must stay in filesDir", transcript.isFile)
        assertTrue("audit log must stay in filesDir", auditLog.isFile)
        assertTrue("loose file must stay in filesDir", looseFile.isFile)
        assertFalse("chats must not be copied into workspace", File(root, "chats").exists())
        assertFalse("audit log must not be copied into workspace", File(root, "security_audit.jsonl").exists())
        assertFalse("loose file must not be copied into workspace", File(root, "loose-notes.txt").exists())
    }

    @Test
    fun parentTraversalIntoSiblingAppDataIsDenied() {
        val chats = File(filesDir, "chats").apply { mkdirs() }
        val secret = File(chats, "secret.json").apply { writeText("PRIVATE CHAT") }
        val tools = WorkspaceTools(WorkspaceTools.defaultRoot(filesDir))

        val read = tools.readWorkspaceFile(call("read_workspace_file", "path" to "../chats/secret.json"))

        assertFalse(read.success)
        assertEquals(AgentToolErrorCode.INVALID_ARGUMENT, read.errorCode)
        assertTrue(secret.isFile)
    }

    @Test
    fun auditLogAndTracesAreDeniedAcrossTraversal() {
        File(filesDir, "security_audit.jsonl").writeText("AUDIT")
        File(filesDir, "agent_traces").apply { mkdirs() }
        File(filesDir, "agent_traces/trace.json").writeText("TRACE")
        val tools = WorkspaceTools(WorkspaceTools.defaultRoot(filesDir))

        assertFalse(tools.readWorkspaceFile(call("read_workspace_file", "path" to "../security_audit.jsonl")).success)
        assertFalse(tools.readWorkspaceFile(call("read_workspace_file", "path" to "../agent_traces/trace.json")).success)
        assertFalse(tools.listWorkspaceFiles(call("list_workspace_files", "path" to "../chats")).success)
        assertFalse(tools.listWorkspaceFiles(call("list_workspace_files", "path" to "..")).success)
    }

    @Test
    fun isPathInsideWorkspaceRejectsSiblingPrefixDirectories() {
        val tools = WorkspaceTools(WorkspaceTools.defaultRoot(filesDir))
        val root = File(filesDir, WorkspaceTools.WORKSPACE_DIR_NAME)

        assertTrue(tools.isPathInsideWorkspace(File(root, "file.txt")))
        assertFalse(tools.isPathInsideWorkspace(File(filesDir, "chats")))
        assertFalse(tools.isPathInsideWorkspace(File(filesDir, "security_audit.jsonl")))
        // Component-wise containment: a sibling with a shared string prefix is outside.
        assertFalse(tools.isPathInsideWorkspace(File(filesDir, "agent-workspace-evil")))
    }

    @Test
    fun symlinkEscapingWorkspaceRootIsDenied() {
        val chats = File(filesDir, "chats").apply { mkdirs() }
        val secret = File(chats, "secret.json").apply { writeText("PRIVATE CHAT") }
        val tools = WorkspaceTools(WorkspaceTools.defaultRoot(filesDir))
        val root = File(filesDir, WorkspaceTools.WORKSPACE_DIR_NAME)
        val link = File(root, "escape.json")
        try {
            Files.createSymbolicLink(link.toPath(), secret.toPath())
        } catch (_: Exception) {
            return // Symlinks unavailable in this environment.
        }

        val read = tools.readWorkspaceFile(call("read_workspace_file", "path" to "escape.json"))

        assertFalse("symlink escape must be denied", read.success)
    }

    @Test
    fun filesInsideWorkspaceRemainReadableAndSearchable() {
        val tools = WorkspaceTools(WorkspaceTools.defaultRoot(filesDir))
        val root = File(filesDir, WorkspaceTools.WORKSPACE_DIR_NAME)
        File(root, "src").mkdirs()
        File(root, "src/Main.kt").writeText("fun main() = println(\"workspace\")")

        val read = tools.readWorkspaceFile(call("read_workspace_file", "path" to "src/Main.kt"))
        assertTrue(read.success)
        assertTrue(read.details.getString("content").contains("workspace"))

        val search = tools.searchWorkspaceFiles(call("search_workspace_files", "query" to "workspace"))
        assertTrue(search.success)
        assertTrue(search.details.getInt("file_count") >= 1)
    }

    @Test
    fun listingRootDoesNotLeakFilesDirSiblings() {
        File(filesDir, "chats").apply { mkdirs() }
        File(filesDir, "security_audit.jsonl").writeText("AUDIT")
        val tools = WorkspaceTools(WorkspaceTools.defaultRoot(filesDir))

        val listing = tools.listWorkspaceFiles(call("list_workspace_files"))

        assertTrue(listing.success)
        val names = listing.details.getJSONArray("items").let { items ->
            (0 until items.length()).map { items.getJSONObject(it).getString("name") }
        }
        assertFalse(names.contains("chats"))
        assertFalse(names.contains("security_audit.jsonl"))
    }
}

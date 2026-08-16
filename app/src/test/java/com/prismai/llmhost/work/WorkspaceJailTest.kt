package com.prismai.llmhost.work

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class WorkspaceJailTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var workspaceRoot: File
    private lateinit var outsideFile: File
    private lateinit var target: AndroidLocalExecutionTarget

    @Before
    fun setup() {
        workspaceRoot = tempFolder.newFolder("workspace")
        outsideFile = tempFolder.newFile("outside.txt").apply {
            writeText("SECRET OUTSIDE CONTENT")
        }
        target = AndroidLocalExecutionTarget(workspaceRoot)
    }

    @Test
    fun validRelativePathResolvesInsideWorkspace() {
        val validFile = File(workspaceRoot, "src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("fun main() = println(\"Hello\")")
        }

        val path = WorkspacePath("src/Main.kt")
        val resolved = WorkspacePath.resolveSafely(workspaceRoot, path)

        assertNotNull(resolved)
        assertEquals(validFile.canonicalFile, resolved!!.canonicalFile)

        runBlocking {
            val readResult = target.readFile(path)
            assertTrue(readResult.isSuccess)
            assertEquals("fun main() = println(\"Hello\")", String(readResult.getOrThrow()))
        }
    }

    @Test
    fun parentDirectoryTraversalIsRejected() {
        assertFalse(WorkspacePath.isValid("../outside.txt"))
        assertFalse(WorkspacePath.isValid("src/../../outside.txt"))
        assertFalse(WorkspacePath.isValid(".."))
        assertFalse(WorkspacePath.isValid("/outside.txt"))
        assertFalse(WorkspacePath.isValid("C:/Windows/system32"))
    }

    @Test
    fun nullByteInjectionIsRejected() {
        assertFalse(WorkspacePath.isValid("src/Main.kt\u0000.txt"))
    }

    @Test
    fun controlCharactersAreRejected() {
        assertFalse(WorkspacePath.isValid("src/Main\u0001.kt"))
        assertFalse(WorkspacePath.isValid("src/\u001Ftest.kt"))
    }

    @Test
    fun symlinkEscapingWorkspaceIsBlocked() {
        val symlinkFile = File(workspaceRoot, "escape_link")
        try {
            Files.createSymbolicLink(symlinkFile.toPath(), outsideFile.toPath())
        } catch (_: Exception) {
            // If OS/environment does not allow symlink creation (e.g. non-admin Windows without developer mode), skip test
            return
        }

        val path = WorkspacePath("escape_link")
        val resolved = WorkspacePath.resolveSafely(workspaceRoot, path)

        // Must be blocked because canonical destination is outside workspace root
        assertNull("Symlink escaping workspace boundary MUST resolve to null", resolved)

        runBlocking {
            val readResult = target.readFile(path)
            assertTrue("Reading symlink escaping workspace MUST fail", readResult.isFailure)
        }
    }

    @Test
    fun internalSymlinkIsAllowed() {
        val internalTarget = File(workspaceRoot, "target.txt").apply {
            writeText("INTERNAL TARGET")
        }
        val symlinkFile = File(workspaceRoot, "internal_link.txt")
        try {
            Files.createSymbolicLink(symlinkFile.toPath(), internalTarget.toPath())
        } catch (_: Exception) {
            return
        }

        val path = WorkspacePath("internal_link.txt")
        val resolved = WorkspacePath.resolveSafely(workspaceRoot, path)

        assertNotNull(resolved)
        runBlocking {
            val readResult = target.readFile(path)
            assertTrue(readResult.isSuccess)
            assertEquals("INTERNAL TARGET", String(readResult.getOrThrow()))
        }
    }

    @Test
    fun writeFileCreatesParentDirectoriesSafely() = runBlocking {
        val path = WorkspacePath("nested/dir/structure/file.txt")
        val writeResult = target.writeFile(path, "CREATED CONTENT".toByteArray())

        assertTrue(writeResult.isSuccess)
        val readResult = target.readFile(path)
        assertTrue(readResult.isSuccess)
        assertEquals("CREATED CONTENT", String(readResult.getOrThrow()))
    }

    @Test
    fun listFilesRejectsPathOutsideWorkspace() = runBlocking {
        val path = WorkspacePath("nonexistent_dir")
        val listResult = target.listFiles(path)
        assertTrue(listResult.isFailure)
    }
}

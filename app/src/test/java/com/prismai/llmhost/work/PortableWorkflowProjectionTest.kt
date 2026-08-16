package com.prismai.llmhost.work

import com.prismai.llmhost.work.portable.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class PortableWorkflowProjectionTest {

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val revision = RevisionRefV1(
        kind = "workspace-revision",
        composite_tree_hash = sha256("revision"),
        source = RevisionSource.GIT,
    )

    private val authority = AuthorityRefV1(
        native_kind = AuthorityKind.TASK_CONTRACT,
        native_id = "tc1:test",
        sha256 = sha256("authority"),
    )

    private val evidence = EvidenceRefV1(
        id = "evidence-1",
        kind = EvidenceKind.VERIFIER_RECEIPT,
        sha256 = sha256("evidence"),
        native_path = "/workspace/build/receipt.json",
    )

    private val receipt = VerifierReceiptV1(
        id = "receipt-1",
        status = VerifierStatus.PASSED,
        verifier = VerifierIdentityV1(
            command = "npm test",
            command_sha256 = sha256("npm test"),
            scope = listOf("portable-workflow"),
            independent = true,
            clean_room = true,
        ),
        bound_revision = revision,
        authority = authority,
        evidence = listOf(evidence),
        exit_code = 0,
        produced_at = "2026-08-08T12:00:00.000Z",
    )

    private fun createValidRun(
        terminal: TerminalOutcomeV1? = TerminalOutcomeV1.CompletedVerified(
            receipts = listOf("receipt-1"),
            revision = revision,
        ),
        runRevision: RevisionRefV1? = revision,
        receipts: List<VerifierReceiptV1> = listOf(receipt),
        stageStatus: StageStatus = StageStatus.PASSED,
    ): WorkflowRunV1 {
        val task = TaskRefV1(
            task_id = "task-1",
            goal = "prove the portable contract",
            acceptance_criteria = listOf("schema passes"),
            mutation_policy = MutationPolicy.READ_ONLY,
            required_verifiers = listOf("npm test"),
        )
        return WorkflowRunV1(
            version = "portable-workflow-v1",
            run_id = "run-1",
            task = task,
            authority = authority,
            stages = listOf(
                StageRecordV1(
                    stage_id = "stage-1",
                    kind = StageKind.INTEGRATE,
                    status = stageStatus,
                    input = StageInputV1.Integrate(task = task, stage_refs = emptyList()),
                    result = StageResultV1.Integrate(
                        verifier_receipts = receipts,
                        changed_refs = emptyList(),
                    ),
                    workers = listOf("worker-1"),
                    evidence = listOf(evidence),
                )
            ),
            workers = listOf(
                WorkerRunV1(
                    worker_id = "worker-1",
                    stage_id = "stage-1",
                    role = WorkerRole.VERIFIER,
                    status = WorkerStatus.PASSED,
                    native_authority = authority,
                    evidence = listOf(evidence),
                )
            ),
            terminal = terminal,
            revision = runRevision,
            evidence = listOf(evidence),
        )
    }

    @Test
    fun roundTripsCompleteWorkflowRunThroughJson() {
        val original = createValidRun()
        val json = PortableWorkflowJson.serializeWorkflowRun(original)
        val parsed = PortableWorkflowJson.parseWorkflowRun(json)

        assertEquals(original.version, parsed.version)
        assertEquals(original.run_id, parsed.run_id)
        assertEquals(original.task.task_id, parsed.task.task_id)
        assertEquals(original.stages.size, parsed.stages.size)
        assertEquals(original.terminal?.kind, parsed.terminal?.kind)

        val validation = PortableWorkflowValidator.validateRun(parsed)
        assertTrue("Valid roundtrip workflow run must pass validation", validation.isOk)
    }

    @Test
    fun rejectsUnknownVersion() {
        val invalidRun = createValidRun().copy(version = "portable-workflow-v2")
        val validation = PortableWorkflowValidator.validateRun(invalidRun)

        assertFalse(validation.isOk)
        val errors = (validation as ValidationResult.Error).errors
        assertTrue(errors.any { it.contains("unsupported version") })
    }

    @Test
    fun rejectsVerifiedCompletionWithoutReceipt() {
        val invalidRun = createValidRun(
            terminal = TerminalOutcomeV1.CompletedVerified(
                receipts = listOf("missing-receipt-id"),
                revision = revision,
            )
        )
        val validation = PortableWorkflowValidator.validateRun(invalidRun)

        assertFalse(validation.isOk)
        val errors = (validation as ValidationResult.Error).errors
        assertTrue(errors.any { it.contains("missing receipt") })
    }

    @Test
    fun rejectsVerifiedCompletionWithMismatchedRevision() {
        val mismatchedReceipt = receipt.copy(
            bound_revision = revision.copy(composite_tree_hash = sha256("different-tree"))
        )
        val invalidRun = createValidRun(
            receipts = listOf(mismatchedReceipt),
        )
        val validation = PortableWorkflowValidator.validateRun(invalidRun)

        assertFalse(validation.isOk)
        val errors = (validation as ValidationResult.Error).errors
        assertTrue(errors.any { it.contains("receipt revision mismatch") })
    }

    @Test
    fun rejectsVerifiedCompletionWithNonPassedReceipt() {
        val failedReceipt = receipt.copy(status = VerifierStatus.FAILED)
        val invalidRun = createValidRun(receipts = listOf(failedReceipt))
        val validation = PortableWorkflowValidator.validateRun(invalidRun)

        assertFalse(validation.isOk)
        val errors = (validation as ValidationResult.Error).errors
        assertTrue(errors.any { it.contains("receipt is not passed") })
    }

    @Test
    fun rejectsTerminalRunWithUnresolvedStages() {
        val invalidRun = createValidRun(stageStatus = StageStatus.RUNNING)
        val validation = PortableWorkflowValidator.validateRun(invalidRun)

        assertFalse(validation.isOk)
        val errors = (validation as ValidationResult.Error).errors
        assertTrue(errors.any { it.contains("terminal run has unresolved stages") })
    }

    @Test
    fun prismWorkSessionPreservesBabelAuthorityAndCannotStrengthenUnverified() {
        val unverifiedRun = createValidRun(
            terminal = TerminalOutcomeV1.CompletedUnverified("Tests not executed"),
        )

        val session = WorkSession(
            sessionId = "session-1",
            objective = "Fix bug in repo",
            targetId = ExecutionTargetId.BABEL_HOST,
            authority = WorkAuthority.BABEL_NATIVE_AUTHORITY,
            workspaceRootPath = "/workspace",
            state = WorkSessionState.COMPLETED_UNVERIFIED,
            currentWorkflowProjection = unverifiedRun,
        )

        assertFalse(
            "Prism must not elevate completed_unverified to verified",
            session.isAuthoritativelyVerified,
        )
    }

    @Test
    fun prismLocalAuthorityCanNeverClaimVerifiedSWECompletion() {
        val session = WorkSession(
            sessionId = "session-2",
            objective = "Edit local file",
            targetId = ExecutionTargetId.ANDROID_LOCAL,
            authority = WorkAuthority.LOCAL_PRISM_AUTHORITY,
            workspaceRootPath = "/workspace",
            state = WorkSessionState.COMPLETED_VERIFIED,
            currentWorkflowProjection = null,
        )

        assertFalse(
            "LOCAL_PRISM_AUTHORITY can never be authoritatively verified for SWE tasks",
            session.isAuthoritativelyVerified,
        )
    }
}

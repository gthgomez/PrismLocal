package com.prismai.llmhost.work.portable

const val PORTABLE_WORKFLOW_VERSION = "portable-workflow-v1"

data class RevisionRefV1(
    val kind: String = "workspace-revision",
    val composite_tree_hash: String,
    val source: String, // "git" | "filesystem" | "native-authority"
)

data class EvidenceRefV1(
    val id: String,
    val kind: String, // "event" | "artifact" | "verifier-receipt" | "checkpoint"
    val sha256: String,
    val native_path: String? = null,
)

data class AuthorityRefV1(
    val native_kind: String, // "task-contract" | "instruction-manifest" | "live-session" | "episode"
    val native_id: String,
    val sha256: String,
)

data class VerifierIdentityV1(
    val command: String,
    val command_sha256: String,
    val scope: List<String>,
    val independent: Boolean,
    val clean_room: Boolean,
)

data class VerifierReceiptV1(
    val id: String,
    val status: String, // "passed" | "failed" | "blocked"
    val verifier: VerifierIdentityV1,
    val bound_revision: RevisionRefV1,
    val authority: AuthorityRefV1,
    val evidence: List<EvidenceRefV1>,
    val exit_code: Int? = null,
    val produced_at: String,
)

data class TaskRefV1(
    val task_id: String,
    val goal: String,
    val acceptance_criteria: List<String>,
    val mutation_policy: String, // "read_only" | "workspace_write" | "governed"
    val required_verifiers: List<String>,
)

sealed class StageInputV1(val kind: String) {
    data class Orient(val task: TaskRefV1) : StageInputV1("orient")
    data class Review(val task: TaskRefV1, val target_refs: List<EvidenceRefV1>) : StageInputV1("review")
    data class Attack(val task: TaskRefV1, val target_refs: List<EvidenceRefV1>) : StageInputV1("attack")
    data class Integrate(val task: TaskRefV1, val stage_refs: List<String>) : StageInputV1("integrate")
}

sealed class StageResultV1(val kind: String) {
    data class Orient(val findings: List<String>) : StageResultV1("orient")
    data class Review(val findings: List<String>, val required_changes: List<String>) : StageResultV1("review")
    data class Attack(val findings: List<String>, val reproductions: List<EvidenceRefV1>) : StageResultV1("attack")
    data class Integrate(
        val verifier_receipts: List<VerifierReceiptV1>,
        val changed_refs: List<EvidenceRefV1>,
    ) : StageResultV1("integrate")
}

data class StageRecordV1(
    val stage_id: String,
    val kind: String, // "orient" | "review" | "attack" | "integrate"
    val status: String, // "pending" | "running" | "passed" | "failed" | "blocked" | "cancelled"
    val input: StageInputV1,
    val result: StageResultV1? = null,
    val workers: List<String>,
    val evidence: List<EvidenceRefV1>,
)

data class WorkerRunV1(
    val worker_id: String,
    val stage_id: String,
    val role: String, // "primary" | "reviewer" | "verifier" | "integrator"
    val status: String, // "pending" | "running" | "passed" | "failed" | "blocked" | "cancelled"
    val provider: String? = null,
    val native_authority: AuthorityRefV1,
    val evidence: List<EvidenceRefV1>,
)

sealed class TerminalOutcomeV1(val kind: String) {
    data class CompletedVerified(
        val receipts: List<String>,
        val revision: RevisionRefV1,
    ) : TerminalOutcomeV1("completed_verified")

    data class CompletedUnverified(val reason: String) : TerminalOutcomeV1("completed_unverified")
    data class BlockedExternal(val reason: String) : TerminalOutcomeV1("blocked_external")
    data class BlockedPolicy(val reason: String) : TerminalOutcomeV1("blocked_policy")
    data class BudgetExhausted(val dimension: String) : TerminalOutcomeV1("budget_exhausted")
    data class Cancelled(val reason: String) : TerminalOutcomeV1("cancelled")
    data class InfraFailure(val reason: String) : TerminalOutcomeV1("infra_failure")
    data class AgentFailure(val reason: String) : TerminalOutcomeV1("agent_failure")
}

data class WorkflowRunV1(
    val version: String = PORTABLE_WORKFLOW_VERSION,
    val run_id: String,
    val task: TaskRefV1,
    val authority: AuthorityRefV1,
    val stages: List<StageRecordV1>,
    val workers: List<WorkerRunV1>,
    val terminal: TerminalOutcomeV1? = null,
    val revision: RevisionRefV1? = null,
    val evidence: List<EvidenceRefV1>,
)

data class PortableExportV1(
    val version: String = PORTABLE_WORKFLOW_VERSION,
    val run: WorkflowRunV1,
    val redaction_profile: String = "public", // "public" | "internal" | "diagnostic"
    val exported_at: String,
)

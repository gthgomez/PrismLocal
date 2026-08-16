package com.prismai.llmhost.work.portable

const val PORTABLE_WORKFLOW_VERSION = "portable-workflow-v1"

enum class RevisionSource(val value: String) {
    GIT("git"),
    FILESYSTEM("filesystem"),
    NATIVE_AUTHORITY("native-authority");

    companion object {
        fun from(value: String): RevisionSource =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid RevisionSource: $value")
    }
}

enum class EvidenceKind(val value: String) {
    EVENT("event"),
    ARTIFACT("artifact"),
    VERIFIER_RECEIPT("verifier-receipt"),
    CHECKPOINT("checkpoint");

    companion object {
        fun from(value: String): EvidenceKind =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid EvidenceKind: $value")
    }
}

enum class AuthorityKind(val value: String) {
    TASK_CONTRACT("task-contract"),
    INSTRUCTION_MANIFEST("instruction-manifest"),
    LIVE_SESSION("live-session"),
    EPISODE("episode");

    companion object {
        fun from(value: String): AuthorityKind =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid AuthorityKind: $value")
    }
}

enum class VerifierStatus(val value: String) {
    PASSED("passed"),
    FAILED("failed"),
    BLOCKED("blocked");

    companion object {
        fun from(value: String): VerifierStatus =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid VerifierStatus: $value")
    }
}

enum class MutationPolicy(val value: String) {
    READ_ONLY("read_only"),
    WORKSPACE_WRITE("workspace_write"),
    GOVERNED("governed");

    companion object {
        fun from(value: String): MutationPolicy =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid MutationPolicy: $value")
    }
}

enum class StageKind(val value: String) {
    ORIENT("orient"),
    REVIEW("review"),
    ATTACK("attack"),
    INTEGRATE("integrate");

    companion object {
        fun from(value: String): StageKind =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid StageKind: $value")
    }
}

enum class StageStatus(val value: String) {
    PENDING("pending"),
    RUNNING("running"),
    PASSED("passed"),
    FAILED("failed"),
    BLOCKED("blocked"),
    CANCELLED("cancelled");

    companion object {
        fun from(value: String): StageStatus =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid StageStatus: $value")
    }
}

enum class WorkerRole(val value: String) {
    PRIMARY("primary"),
    REVIEWER("reviewer"),
    VERIFIER("verifier"),
    INTEGRATOR("integrator");

    companion object {
        fun from(value: String): WorkerRole =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid WorkerRole: $value")
    }
}

enum class WorkerStatus(val value: String) {
    PENDING("pending"),
    RUNNING("running"),
    PASSED("passed"),
    FAILED("failed"),
    BLOCKED("blocked"),
    CANCELLED("cancelled");

    companion object {
        fun from(value: String): WorkerStatus =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid WorkerStatus: $value")
    }
}

enum class BudgetDimension(val value: String) {
    TURNS("turns"),
    TOKENS("tokens"),
    REPAIR("repair"),
    INFRA("infra");

    companion object {
        fun from(value: String): BudgetDimension =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid BudgetDimension: $value")
    }
}

enum class RedactionProfile(val value: String) {
    PUBLIC("public"),
    INTERNAL("internal"),
    DIAGNOSTIC("diagnostic");

    companion object {
        fun from(value: String): RedactionProfile =
            entries.find { it.value == value } ?: throw IllegalArgumentException("Invalid RedactionProfile: $value")
    }
}

data class RevisionRefV1(
    val kind: String = "workspace-revision",
    val composite_tree_hash: String,
    val source: RevisionSource,
)

data class EvidenceRefV1(
    val id: String,
    val kind: EvidenceKind,
    val sha256: String,
    val native_path: String? = null,
)

data class AuthorityRefV1(
    val native_kind: AuthorityKind,
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
    val status: VerifierStatus,
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
    val mutation_policy: MutationPolicy,
    val required_verifiers: List<String>,
)

sealed class StageInputV1(val kind: StageKind) {
    data class Orient(val task: TaskRefV1) : StageInputV1(StageKind.ORIENT)
    data class Review(val task: TaskRefV1, val target_refs: List<EvidenceRefV1>) : StageInputV1(StageKind.REVIEW)
    data class Attack(val task: TaskRefV1, val target_refs: List<EvidenceRefV1>) : StageInputV1(StageKind.ATTACK)
    data class Integrate(val task: TaskRefV1, val stage_refs: List<String>) : StageInputV1(StageKind.INTEGRATE)
}

sealed class StageResultV1(val kind: StageKind) {
    data class Orient(val findings: List<String>) : StageResultV1(StageKind.ORIENT)
    data class Review(val findings: List<String>, val required_changes: List<String>) : StageResultV1(StageKind.REVIEW)
    data class Attack(val findings: List<String>, val reproductions: List<EvidenceRefV1>) : StageResultV1(StageKind.ATTACK)
    data class Integrate(
        val verifier_receipts: List<VerifierReceiptV1>,
        val changed_refs: List<EvidenceRefV1>,
    ) : StageResultV1(StageKind.INTEGRATE)
}

data class StageRecordV1(
    val stage_id: String,
    val kind: StageKind,
    val status: StageStatus,
    val input: StageInputV1,
    val result: StageResultV1? = null,
    val workers: List<String>,
    val evidence: List<EvidenceRefV1>,
)

data class WorkerRunV1(
    val worker_id: String,
    val stage_id: String,
    val role: WorkerRole,
    val status: WorkerStatus,
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
    data class BudgetExhausted(val dimension: BudgetDimension) : TerminalOutcomeV1("budget_exhausted")
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
    val redaction_profile: RedactionProfile = RedactionProfile.PUBLIC,
    val exported_at: String,
)

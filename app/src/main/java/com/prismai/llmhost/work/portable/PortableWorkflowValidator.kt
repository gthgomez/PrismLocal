package com.prismai.llmhost.work.portable

sealed class ValidationResult<out T> {
    data class Ok<T>(val value: T) : ValidationResult<T>()
    data class Error(val errors: List<String>) : ValidationResult<Nothing>()

    val isOk: Boolean get() = this is Ok
    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Error -> throw IllegalArgumentException("Validation failed: ${errors.joinToString("; ")}")
    }
}

object PortableWorkflowValidator {
    private val HASH_PATTERN = Regex("^[0-9a-f]{32,64}$")
    private val ID_PATTERN = Regex("^[^\\u0000-\\u001f]{1,256}$")

    fun isValidHash(hash: String): Boolean = HASH_PATTERN.matches(hash)
    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    fun validateRun(run: WorkflowRunV1): ValidationResult<WorkflowRunV1> {
        val errors = mutableListOf<String>()

        if (run.version != PORTABLE_WORKFLOW_VERSION) {
            errors.add("unsupported version: '${run.version}', expected '$PORTABLE_WORKFLOW_VERSION'")
        }

        if (!isValidId(run.run_id)) errors.add("invalid run_id: '${run.run_id}'")
        validateTaskRef(run.task, "run.task", errors)
        validateAuthorityRef(run.authority, "run.authority", errors)

        if (run.revision != null) {
            validateRevisionRef(run.revision, "run.revision", errors)
        }

        for ((idx, ev) in run.evidence.withIndex()) {
            validateEvidenceRef(ev, "run.evidence[$idx]", errors)
        }

        val stageIds = mutableSetOf<String>()
        val workerIds = mutableSetOf<String>()
        val receiptById = mutableMapOf<String, VerifierReceiptV1>()
        val knownEvidence = collectEvidenceIds(run)

        for (stage in run.stages) {
            if (!isValidId(stage.stage_id)) errors.add("invalid stage_id: '${stage.stage_id}'")
            if (!stageIds.add(stage.stage_id)) {
                errors.add("duplicate stage: ${stage.stage_id}")
            }

            validateStageInput(stage.input, "stage(${stage.stage_id}).input", run.task.task_id, errors)

            if (stage.status == StageStatus.PASSED && (stage.result == null || stage.evidence.isEmpty())) {
                errors.add("passed stage requires result and evidence: ${stage.stage_id}")
            }

            for ((idx, ev) in stage.evidence.withIndex()) {
                validateEvidenceRef(ev, "stage(${stage.stage_id}).evidence[$idx]", errors)
            }

            if (stage.result != null) {
                validateStageResult(stage.result, "stage(${stage.stage_id}).result", receiptById, knownEvidence, errors)
            }

            if (stage.input is StageInputV1.Integrate) {
                for (referencedStage in stage.input.stage_refs) {
                    val target = run.stages.find { it.stage_id == referencedStage }
                    if (target == null) {
                        errors.add("missing integrated stage: $referencedStage")
                    } else if (target.status !in setOf(StageStatus.PASSED, StageStatus.FAILED, StageStatus.BLOCKED, StageStatus.CANCELLED)) {
                        errors.add("integrate stage references non-terminal stage: $referencedStage")
                    }
                }
            }
        }

        for (worker in run.workers) {
            if (!isValidId(worker.worker_id)) errors.add("invalid worker_id: '${worker.worker_id}'")
            if (!isValidId(worker.stage_id)) errors.add("invalid worker stage_id: '${worker.stage_id}'")
            if (!workerIds.add(worker.worker_id)) {
                errors.add("duplicate worker: ${worker.worker_id}")
            }
            val stage = run.stages.find { it.stage_id == worker.stage_id }
            if (stage == null) {
                errors.add("worker references missing stage: ${worker.worker_id}")
            } else if (worker.worker_id !in stage.workers) {
                errors.add("worker parent mismatch: ${worker.worker_id}")
            }
            validateAuthorityRef(worker.native_authority, "worker(${worker.worker_id}).native_authority", errors)
            for ((idx, ev) in worker.evidence.withIndex()) {
                validateEvidenceRef(ev, "worker(${worker.worker_id}).evidence[$idx]", errors)
            }
            if (worker.provider != null && worker.provider.isEmpty()) {
                errors.add("worker(${worker.worker_id}).provider must not be empty if present")
            }
        }

        if (run.terminal != null) {
            validateTerminalOutcome(run.terminal, run, receiptById, knownEvidence, errors)
        }

        if (run.terminal != null && run.stages.any { it.status in setOf(StageStatus.PENDING, StageStatus.RUNNING) }) {
            errors.add("terminal run has unresolved stages")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Ok(run)
        } else {
            ValidationResult.Error(errors)
        }
    }

    private fun validateEvidenceRef(ev: EvidenceRefV1, path: String, errors: MutableList<String>) {
        if (!isValidId(ev.id)) errors.add("$path invalid id: '${ev.id}'")
        if (!isValidHash(ev.sha256)) errors.add("$path invalid hash: '${ev.sha256}'")
        if (ev.native_path != null && ev.native_path.isEmpty()) {
            errors.add("$path native_path must not be empty if present")
        }
    }

    private fun validateAuthorityRef(auth: AuthorityRefV1, path: String, errors: MutableList<String>) {
        if (!isValidId(auth.native_id)) errors.add("$path invalid native_id: '${auth.native_id}'")
        if (!isValidHash(auth.sha256)) errors.add("$path invalid hash: '${auth.sha256}'")
    }

    private fun validateRevisionRef(rev: RevisionRefV1, path: String, errors: MutableList<String>) {
        if (!isValidHash(rev.composite_tree_hash)) errors.add("$path invalid composite_tree_hash: '${rev.composite_tree_hash}'")
    }

    private fun validateTaskRef(task: TaskRefV1, path: String, errors: MutableList<String>) {
        if (!isValidId(task.task_id)) errors.add("$path invalid task_id: '${task.task_id}'")
        if (task.goal.isEmpty()) errors.add("$path goal must not be empty")
        for ((idx, ac) in task.acceptance_criteria.withIndex()) {
            if (ac.isEmpty()) errors.add("$path acceptance_criteria[$idx] must not be empty")
        }
        for ((idx, rv) in task.required_verifiers.withIndex()) {
            if (rv.isEmpty()) errors.add("$path required_verifiers[$idx] must not be empty")
        }
    }

    private fun validateStageInput(
        input: StageInputV1,
        path: String,
        expectedTaskId: String,
        errors: MutableList<String>,
    ) {
        val task = when (input) {
            is StageInputV1.Orient -> input.task
            is StageInputV1.Review -> input.task
            is StageInputV1.Attack -> input.task
            is StageInputV1.Integrate -> input.task
        }
        validateTaskRef(task, "$path.task", errors)
        if (task.task_id != expectedTaskId) {
            errors.add("$path task_id '${task.task_id}' does not match run task_id '$expectedTaskId'")
        }

        when (input) {
            is StageInputV1.Review -> {
                for ((idx, ref) in input.target_refs.withIndex()) {
                    validateEvidenceRef(ref, "$path.target_refs[$idx]", errors)
                }
            }
            is StageInputV1.Attack -> {
                for ((idx, ref) in input.target_refs.withIndex()) {
                    validateEvidenceRef(ref, "$path.target_refs[$idx]", errors)
                }
            }
            is StageInputV1.Integrate -> {
                for ((idx, sRef) in input.stage_refs.withIndex()) {
                    if (!isValidId(sRef)) errors.add("$path.stage_refs[$idx] invalid id: '$sRef'")
                }
            }
            is StageInputV1.Orient -> Unit
        }
    }

    private fun validateStageResult(
        result: StageResultV1,
        path: String,
        receiptById: MutableMap<String, VerifierReceiptV1>,
        knownEvidence: Set<String>,
        errors: MutableList<String>,
    ) {
        when (result) {
            is StageResultV1.Orient -> {
                for ((idx, finding) in result.findings.withIndex()) {
                    if (finding.isEmpty()) errors.add("$path.findings[$idx] must not be empty")
                }
            }
            is StageResultV1.Review -> {
                for ((idx, finding) in result.findings.withIndex()) {
                    if (finding.isEmpty()) errors.add("$path.findings[$idx] must not be empty")
                }
                for ((idx, req) in result.required_changes.withIndex()) {
                    if (req.isEmpty()) errors.add("$path.required_changes[$idx] must not be empty")
                }
            }
            is StageResultV1.Attack -> {
                for ((idx, finding) in result.findings.withIndex()) {
                    if (finding.isEmpty()) errors.add("$path.findings[$idx] must not be empty")
                }
                for ((idx, rep) in result.reproductions.withIndex()) {
                    validateEvidenceRef(rep, "$path.reproductions[$idx]", errors)
                }
            }
            is StageResultV1.Integrate -> {
                for ((idx, cr) in result.changed_refs.withIndex()) {
                    validateEvidenceRef(cr, "$path.changed_refs[$idx]", errors)
                }
                for (receipt in result.verifier_receipts) {
                    validateVerifierReceipt(receipt, "$path.verifier_receipts(${receipt.id})", receiptById, knownEvidence, errors)
                }
            }
        }
    }

    private fun validateVerifierReceipt(
        receipt: VerifierReceiptV1,
        path: String,
        receiptById: MutableMap<String, VerifierReceiptV1>,
        knownEvidence: Set<String>,
        errors: MutableList<String>,
    ) {
        if (!isValidId(receipt.id)) errors.add("$path invalid id: '${receipt.id}'")
        if (receipt.verifier.command.isEmpty()) errors.add("$path verifier command must not be empty")
        if (!isValidHash(receipt.verifier.command_sha256)) errors.add("$path invalid verifier command_sha256: '${receipt.verifier.command_sha256}'")
        for ((idx, sc) in receipt.verifier.scope.withIndex()) {
            if (sc.isEmpty()) errors.add("$path verifier.scope[$idx] must not be empty")
        }
        validateRevisionRef(receipt.bound_revision, "$path.bound_revision", errors)
        validateAuthorityRef(receipt.authority, "$path.authority", errors)

        for ((idx, ev) in receipt.evidence.withIndex()) {
            validateEvidenceRef(ev, "$path.evidence[$idx]", errors)
            if (ev.id !in knownEvidence) {
                errors.add("$path references unknown evidence: '${ev.id}'")
            }
        }

        if (receiptById.containsKey(receipt.id)) {
            errors.add("duplicate receipt: ${receipt.id}")
        } else {
            receiptById[receipt.id] = receipt
        }
    }

    private fun validateTerminalOutcome(
        terminal: TerminalOutcomeV1,
        run: WorkflowRunV1,
        receiptById: Map<String, VerifierReceiptV1>,
        knownEvidence: Set<String>,
        errors: MutableList<String>,
    ) {
        when (terminal) {
            is TerminalOutcomeV1.CompletedVerified -> {
                if (run.revision == null) {
                    errors.add("verified completion requires a run revision")
                }
                validateRevisionRef(terminal.revision, "terminal.revision", errors)
                for (receiptId in terminal.receipts) {
                    if (!isValidId(receiptId)) errors.add("terminal invalid receipt id: '$receiptId'")
                    val receipt = receiptById[receiptId]
                    if (receipt == null) {
                        errors.add("verified completion references missing receipt: $receiptId")
                    } else {
                        if (receipt.status != VerifierStatus.PASSED) {
                            errors.add("receipt is not passed: $receiptId")
                        }
                        if (run.revision != null && receipt.bound_revision.composite_tree_hash != run.revision.composite_tree_hash) {
                            errors.add("receipt revision mismatch: $receiptId")
                        }
                        if (receipt.evidence.any { it.id !in knownEvidence }) {
                            errors.add("receipt evidence is not attached: $receiptId")
                        }
                    }
                }
            }
            is TerminalOutcomeV1.CompletedUnverified -> if (terminal.reason.isEmpty()) errors.add("terminal.reason must not be empty")
            is TerminalOutcomeV1.BlockedExternal -> if (terminal.reason.isEmpty()) errors.add("terminal.reason must not be empty")
            is TerminalOutcomeV1.BlockedPolicy -> if (terminal.reason.isEmpty()) errors.add("terminal.reason must not be empty")
            is TerminalOutcomeV1.Cancelled -> if (terminal.reason.isEmpty()) errors.add("terminal.reason must not be empty")
            is TerminalOutcomeV1.InfraFailure -> if (terminal.reason.isEmpty()) errors.add("terminal.reason must not be empty")
            is TerminalOutcomeV1.AgentFailure -> if (terminal.reason.isEmpty()) errors.add("terminal.reason must not be empty")
            is TerminalOutcomeV1.BudgetExhausted -> Unit
        }
    }

    private fun collectEvidenceIds(run: WorkflowRunV1): Set<String> {
        val ids = mutableSetOf<String>()
        run.evidence.forEach { ids.add(it.id) }
        for (stage in run.stages) {
            stage.evidence.forEach { ids.add(it.id) }
            when (val res = stage.result) {
                is StageResultV1.Attack -> res.reproductions.forEach { ids.add(it.id) }
                is StageResultV1.Integrate -> res.changed_refs.forEach { ids.add(it.id) }
                else -> Unit
            }
        }
        for (worker in run.workers) {
            worker.evidence.forEach { ids.add(it.id) }
        }
        return ids
    }
}

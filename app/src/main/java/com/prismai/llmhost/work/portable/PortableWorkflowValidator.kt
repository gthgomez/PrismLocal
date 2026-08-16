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
        if (!isValidId(run.task.task_id)) errors.add("invalid task_id: '${run.task.task_id}'")
        if (!isValidId(run.authority.native_id)) errors.add("invalid authority native_id: '${run.authority.native_id}'")
        if (!isValidHash(run.authority.sha256)) errors.add("invalid authority hash: '${run.authority.sha256}'")

        if (run.revision != null && !isValidHash(run.revision.composite_tree_hash)) {
            errors.add("invalid revision composite_tree_hash: '${run.revision.composite_tree_hash}'")
        }

        for (ev in run.evidence) {
            if (!isValidId(ev.id)) errors.add("invalid evidence id: '${ev.id}'")
            if (!isValidHash(ev.sha256)) errors.add("invalid evidence hash: '${ev.sha256}'")
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
            val stageTaskId = when (val input = stage.input) {
                is StageInputV1.Orient -> input.task.task_id
                is StageInputV1.Review -> input.task.task_id
                is StageInputV1.Attack -> input.task.task_id
                is StageInputV1.Integrate -> input.task.task_id
            }
            if (stageTaskId != run.task.task_id) {
                errors.add("stage task mismatch: ${stage.stage_id}")
            }
            if (stage.status == StageStatus.PASSED && (stage.result == null || stage.evidence.isEmpty())) {
                errors.add("passed stage requires result and evidence: ${stage.stage_id}")
            }

            for (ev in stage.evidence) {
                if (!isValidId(ev.id)) errors.add("invalid stage evidence id: '${ev.id}'")
                if (!isValidHash(ev.sha256)) errors.add("invalid stage evidence hash: '${ev.sha256}'")
            }

            if (stage.result is StageResultV1.Integrate) {
                for (receipt in stage.result.verifier_receipts) {
                    if (!isValidId(receipt.id)) errors.add("invalid receipt id: '${receipt.id}'")
                    if (!isValidHash(receipt.verifier.command_sha256)) errors.add("invalid receipt verifier command_sha256: '${receipt.verifier.command_sha256}'")
                    if (!isValidHash(receipt.bound_revision.composite_tree_hash)) errors.add("invalid receipt bound_revision hash: '${receipt.bound_revision.composite_tree_hash}'")
                    if (!isValidHash(receipt.authority.sha256)) errors.add("invalid receipt authority hash: '${receipt.authority.sha256}'")

                    if (receiptById.containsKey(receipt.id)) {
                        errors.add("duplicate receipt: ${receipt.id}")
                    }
                    receiptById[receipt.id] = receipt
                    for (evidence in receipt.evidence) {
                        if (evidence.id !in knownEvidence) {
                            errors.add("unknown receipt evidence: ${evidence.id}")
                        }
                    }
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
        }

        for (worker in run.workers) {
            if (!isValidId(worker.worker_id)) errors.add("invalid worker_id: '${worker.worker_id}'")
            if (!workerIds.add(worker.worker_id)) {
                errors.add("duplicate worker: ${worker.worker_id}")
            }
            val stage = run.stages.find { it.stage_id == worker.stage_id }
            if (stage == null) {
                errors.add("worker references missing stage: ${worker.worker_id}")
            } else if (worker.worker_id !in stage.workers) {
                errors.add("worker parent mismatch: ${worker.worker_id}")
            }
        }

        if (run.terminal is TerminalOutcomeV1.CompletedVerified) {
            if (run.revision == null) {
                errors.add("verified completion requires a run revision")
            }
            for (receiptId in run.terminal.receipts) {
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

        if (run.terminal != null && run.stages.any { it.status in setOf(StageStatus.PENDING, StageStatus.RUNNING) }) {
            errors.add("terminal run has unresolved stages")
        }

        return if (errors.isEmpty()) {
            ValidationResult.Ok(run)
        } else {
            ValidationResult.Error(errors)
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

package com.prismai.llmhost.work.portable

import org.json.JSONArray
import org.json.JSONObject
import java.time.format.DateTimeFormatter

object PortableWorkflowJson {

    private fun checkAllowedKeys(json: JSONObject, allowedKeys: Set<String>, context: String) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowedKeys) {
                throw IllegalArgumentException("Unknown property '$key' in $context (strict contract violation)")
            }
        }
    }

    fun parseRevision(json: JSONObject): RevisionRefV1 {
        checkAllowedKeys(json, setOf("kind", "composite_tree_hash", "source"), "RevisionRefV1")
        return RevisionRefV1(
            kind = json.optString("kind", "workspace-revision"),
            composite_tree_hash = json.getString("composite_tree_hash"),
            source = RevisionSource.from(json.getString("source")),
        )
    }

    fun serializeRevision(ref: RevisionRefV1): JSONObject = JSONObject()
        .put("kind", ref.kind)
        .put("composite_tree_hash", ref.composite_tree_hash)
        .put("source", ref.source.value)

    fun parseEvidence(json: JSONObject): EvidenceRefV1 {
        checkAllowedKeys(json, setOf("id", "kind", "sha256", "native_path"), "EvidenceRefV1")
        return EvidenceRefV1(
            id = json.getString("id"),
            kind = EvidenceKind.from(json.getString("kind")),
            sha256 = json.getString("sha256"),
            native_path = if (json.has("native_path") && !json.isNull("native_path")) json.getString("native_path") else null,
        )
    }

    fun serializeEvidence(ref: EvidenceRefV1): JSONObject = JSONObject()
        .put("id", ref.id)
        .put("kind", ref.kind.value)
        .put("sha256", ref.sha256)
        .apply {
            if (ref.native_path != null) put("native_path", ref.native_path)
        }

    fun parseAuthority(json: JSONObject): AuthorityRefV1 {
        checkAllowedKeys(json, setOf("native_kind", "native_id", "sha256"), "AuthorityRefV1")
        return AuthorityRefV1(
            native_kind = AuthorityKind.from(json.getString("native_kind")),
            native_id = json.getString("native_id"),
            sha256 = json.getString("sha256"),
        )
    }

    fun serializeAuthority(auth: AuthorityRefV1): JSONObject = JSONObject()
        .put("native_kind", auth.native_kind.value)
        .put("native_id", auth.native_id)
        .put("sha256", auth.sha256)

    fun parseVerifierIdentity(json: JSONObject): VerifierIdentityV1 {
        checkAllowedKeys(json, setOf("command", "command_sha256", "scope", "independent", "clean_room"), "VerifierIdentityV1")
        return VerifierIdentityV1(
            command = json.getString("command"),
            command_sha256 = json.getString("command_sha256"),
            scope = json.getJSONArray("scope").let { arr -> List(arr.length()) { arr.getString(it) } },
            independent = json.getBoolean("independent"),
            clean_room = json.getBoolean("clean_room"),
        )
    }

    fun serializeVerifierIdentity(identity: VerifierIdentityV1): JSONObject = JSONObject()
        .put("command", identity.command)
        .put("command_sha256", identity.command_sha256)
        .put("scope", JSONArray(identity.scope))
        .put("independent", identity.independent)
        .put("clean_room", identity.clean_room)

    fun parseVerifierReceipt(json: JSONObject): VerifierReceiptV1 {
        checkAllowedKeys(
            json,
            setOf("id", "status", "verifier", "bound_revision", "authority", "evidence", "exit_code", "produced_at"),
            "VerifierReceiptV1",
        )
        val producedAt = json.getString("produced_at")
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(producedAt)
        return VerifierReceiptV1(
            id = json.getString("id"),
            status = VerifierStatus.from(json.getString("status")),
            verifier = parseVerifierIdentity(json.getJSONObject("verifier")),
            bound_revision = parseRevision(json.getJSONObject("bound_revision")),
            authority = parseAuthority(json.getJSONObject("authority")),
            evidence = json.getJSONArray("evidence").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
            exit_code = if (json.has("exit_code") && !json.isNull("exit_code")) json.getInt("exit_code") else null,
            produced_at = producedAt,
        )
    }

    fun serializeVerifierReceipt(receipt: VerifierReceiptV1): JSONObject = JSONObject()
        .put("id", receipt.id)
        .put("status", receipt.status.value)
        .put("verifier", serializeVerifierIdentity(receipt.verifier))
        .put("bound_revision", serializeRevision(receipt.bound_revision))
        .put("authority", serializeAuthority(receipt.authority))
        .put("evidence", JSONArray().apply { receipt.evidence.forEach { put(serializeEvidence(it)) } })
        .apply {
            if (receipt.exit_code != null) put("exit_code", receipt.exit_code)
        }
        .put("produced_at", receipt.produced_at)

    fun parseTask(json: JSONObject): TaskRefV1 {
        checkAllowedKeys(
            json,
            setOf("task_id", "goal", "acceptance_criteria", "mutation_policy", "required_verifiers"),
            "TaskRefV1",
        )
        return TaskRefV1(
            task_id = json.getString("task_id"),
            goal = json.getString("goal"),
            acceptance_criteria = json.getJSONArray("acceptance_criteria").let { arr -> List(arr.length()) { arr.getString(it) } },
            mutation_policy = MutationPolicy.from(json.getString("mutation_policy")),
            required_verifiers = json.getJSONArray("required_verifiers").let { arr -> List(arr.length()) { arr.getString(it) } },
        )
    }

    fun serializeTask(task: TaskRefV1): JSONObject = JSONObject()
        .put("task_id", task.task_id)
        .put("goal", task.goal)
        .put("acceptance_criteria", JSONArray(task.acceptance_criteria))
        .put("mutation_policy", task.mutation_policy.value)
        .put("required_verifiers", JSONArray(task.required_verifiers))

    fun parseStageInput(json: JSONObject): StageInputV1 {
        val kindStr = json.getString("kind")
        val kind = StageKind.from(kindStr)
        return when (kind) {
            StageKind.ORIENT -> {
                checkAllowedKeys(json, setOf("kind", "task"), "StageInputV1.Orient")
                StageInputV1.Orient(parseTask(json.getJSONObject("task")))
            }
            StageKind.REVIEW -> {
                checkAllowedKeys(json, setOf("kind", "task", "target_refs"), "StageInputV1.Review")
                StageInputV1.Review(
                    task = parseTask(json.getJSONObject("task")),
                    target_refs = json.getJSONArray("target_refs").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
                )
            }
            StageKind.ATTACK -> {
                checkAllowedKeys(json, setOf("kind", "task", "target_refs"), "StageInputV1.Attack")
                StageInputV1.Attack(
                    task = parseTask(json.getJSONObject("task")),
                    target_refs = json.getJSONArray("target_refs").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
                )
            }
            StageKind.INTEGRATE -> {
                checkAllowedKeys(json, setOf("kind", "task", "stage_refs"), "StageInputV1.Integrate")
                StageInputV1.Integrate(
                    task = parseTask(json.getJSONObject("task")),
                    stage_refs = json.getJSONArray("stage_refs").let { arr -> List(arr.length()) { arr.getString(it) } },
                )
            }
        }
    }

    fun serializeStageInput(input: StageInputV1): JSONObject = when (input) {
        is StageInputV1.Orient -> JSONObject()
            .put("kind", input.kind.value)
            .put("task", serializeTask(input.task))
        is StageInputV1.Review -> JSONObject()
            .put("kind", input.kind.value)
            .put("task", serializeTask(input.task))
            .put("target_refs", JSONArray().apply { input.target_refs.forEach { put(serializeEvidence(it)) } })
        is StageInputV1.Attack -> JSONObject()
            .put("kind", input.kind.value)
            .put("task", serializeTask(input.task))
            .put("target_refs", JSONArray().apply { input.target_refs.forEach { put(serializeEvidence(it)) } })
        is StageInputV1.Integrate -> JSONObject()
            .put("kind", input.kind.value)
            .put("task", serializeTask(input.task))
            .put("stage_refs", JSONArray(input.stage_refs))
    }

    fun parseStageResult(json: JSONObject): StageResultV1 {
        val kind = StageKind.from(json.getString("kind"))
        return when (kind) {
            StageKind.ORIENT -> {
                checkAllowedKeys(json, setOf("kind", "findings"), "StageResultV1.Orient")
                StageResultV1.Orient(
                    findings = json.getJSONArray("findings").let { arr -> List(arr.length()) { arr.getString(it) } },
                )
            }
            StageKind.REVIEW -> {
                checkAllowedKeys(json, setOf("kind", "findings", "required_changes"), "StageResultV1.Review")
                StageResultV1.Review(
                    findings = json.getJSONArray("findings").let { arr -> List(arr.length()) { arr.getString(it) } },
                    required_changes = json.getJSONArray("required_changes").let { arr -> List(arr.length()) { arr.getString(it) } },
                )
            }
            StageKind.ATTACK -> {
                checkAllowedKeys(json, setOf("kind", "findings", "reproductions"), "StageResultV1.Attack")
                StageResultV1.Attack(
                    findings = json.getJSONArray("findings").let { arr -> List(arr.length()) { arr.getString(it) } },
                    reproductions = json.getJSONArray("reproductions").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
                )
            }
            StageKind.INTEGRATE -> {
                checkAllowedKeys(json, setOf("kind", "verifier_receipts", "changed_refs"), "StageResultV1.Integrate")
                StageResultV1.Integrate(
                    verifier_receipts = json.getJSONArray("verifier_receipts").let { arr -> List(arr.length()) { parseVerifierReceipt(arr.getJSONObject(it)) } },
                    changed_refs = json.getJSONArray("changed_refs").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
                )
            }
        }
    }

    fun serializeStageResult(result: StageResultV1): JSONObject = when (result) {
        is StageResultV1.Orient -> JSONObject()
            .put("kind", result.kind.value)
            .put("findings", JSONArray(result.findings))
        is StageResultV1.Review -> JSONObject()
            .put("kind", result.kind.value)
            .put("findings", JSONArray(result.findings))
            .put("required_changes", JSONArray(result.required_changes))
        is StageResultV1.Attack -> JSONObject()
            .put("kind", result.kind.value)
            .put("findings", JSONArray(result.findings))
            .put("reproductions", JSONArray().apply { result.reproductions.forEach { put(serializeEvidence(it)) } })
        is StageResultV1.Integrate -> JSONObject()
            .put("kind", result.kind.value)
            .put("verifier_receipts", JSONArray().apply { result.verifier_receipts.forEach { put(serializeVerifierReceipt(it)) } })
            .put("changed_refs", JSONArray().apply { result.changed_refs.forEach { put(serializeEvidence(it)) } })
    }

    fun parseStage(json: JSONObject): StageRecordV1 {
        checkAllowedKeys(
            json,
            setOf("stage_id", "kind", "status", "input", "result", "workers", "evidence"),
            "StageRecordV1",
        )
        return StageRecordV1(
            stage_id = json.getString("stage_id"),
            kind = StageKind.from(json.getString("kind")),
            status = StageStatus.from(json.getString("status")),
            input = parseStageInput(json.getJSONObject("input")),
            result = if (json.has("result") && !json.isNull("result")) parseStageResult(json.getJSONObject("result")) else null,
            workers = json.getJSONArray("workers").let { arr -> List(arr.length()) { arr.getString(it) } },
            evidence = json.getJSONArray("evidence").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
        )
    }

    fun serializeStage(stage: StageRecordV1): JSONObject = JSONObject()
        .put("stage_id", stage.stage_id)
        .put("kind", stage.kind.value)
        .put("status", stage.status.value)
        .put("input", serializeStageInput(stage.input))
        .apply {
            if (stage.result != null) put("result", serializeStageResult(stage.result))
        }
        .put("workers", JSONArray(stage.workers))
        .put("evidence", JSONArray().apply { stage.evidence.forEach { put(serializeEvidence(it)) } })

    fun parseWorker(json: JSONObject): WorkerRunV1 {
        checkAllowedKeys(
            json,
            setOf("worker_id", "stage_id", "role", "status", "provider", "native_authority", "evidence"),
            "WorkerRunV1",
        )
        return WorkerRunV1(
            worker_id = json.getString("worker_id"),
            stage_id = json.getString("stage_id"),
            role = WorkerRole.from(json.getString("role")),
            status = WorkerStatus.from(json.getString("status")),
            provider = if (json.has("provider") && !json.isNull("provider")) json.getString("provider") else null,
            native_authority = parseAuthority(json.getJSONObject("native_authority")),
            evidence = json.getJSONArray("evidence").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
        )
    }

    fun serializeWorker(worker: WorkerRunV1): JSONObject = JSONObject()
        .put("worker_id", worker.worker_id)
        .put("stage_id", worker.stage_id)
        .put("role", worker.role.value)
        .put("status", worker.status.value)
        .apply {
            if (worker.provider != null) put("provider", worker.provider)
        }
        .put("native_authority", serializeAuthority(worker.native_authority))
        .put("evidence", JSONArray().apply { worker.evidence.forEach { put(serializeEvidence(it)) } })

    fun parseTerminal(json: JSONObject): TerminalOutcomeV1 {
        return when (val kind = json.getString("kind")) {
            "completed_verified" -> {
                checkAllowedKeys(json, setOf("kind", "receipts", "revision"), "TerminalOutcomeV1.CompletedVerified")
                TerminalOutcomeV1.CompletedVerified(
                    receipts = json.getJSONArray("receipts").let { arr -> List(arr.length()) { arr.getString(it) } },
                    revision = parseRevision(json.getJSONObject("revision")),
                )
            }
            "completed_unverified" -> {
                checkAllowedKeys(json, setOf("kind", "reason"), "TerminalOutcomeV1.CompletedUnverified")
                TerminalOutcomeV1.CompletedUnverified(json.getString("reason"))
            }
            "blocked_external" -> {
                checkAllowedKeys(json, setOf("kind", "reason"), "TerminalOutcomeV1.BlockedExternal")
                TerminalOutcomeV1.BlockedExternal(json.getString("reason"))
            }
            "blocked_policy" -> {
                checkAllowedKeys(json, setOf("kind", "reason"), "TerminalOutcomeV1.BlockedPolicy")
                TerminalOutcomeV1.BlockedPolicy(json.getString("reason"))
            }
            "budget_exhausted" -> {
                checkAllowedKeys(json, setOf("kind", "dimension"), "TerminalOutcomeV1.BudgetExhausted")
                TerminalOutcomeV1.BudgetExhausted(BudgetDimension.from(json.getString("dimension")))
            }
            "cancelled" -> {
                checkAllowedKeys(json, setOf("kind", "reason"), "TerminalOutcomeV1.Cancelled")
                TerminalOutcomeV1.Cancelled(json.getString("reason"))
            }
            "infra_failure" -> {
                checkAllowedKeys(json, setOf("kind", "reason"), "TerminalOutcomeV1.InfraFailure")
                TerminalOutcomeV1.InfraFailure(json.getString("reason"))
            }
            "agent_failure" -> {
                checkAllowedKeys(json, setOf("kind", "reason"), "TerminalOutcomeV1.AgentFailure")
                TerminalOutcomeV1.AgentFailure(json.getString("reason"))
            }
            else -> throw IllegalArgumentException("Unknown terminal outcome kind: $kind")
        }
    }

    fun serializeTerminal(terminal: TerminalOutcomeV1): JSONObject = when (terminal) {
        is TerminalOutcomeV1.CompletedVerified -> JSONObject()
            .put("kind", terminal.kind)
            .put("receipts", JSONArray(terminal.receipts))
            .put("revision", serializeRevision(terminal.revision))
        is TerminalOutcomeV1.CompletedUnverified -> JSONObject().put("kind", terminal.kind).put("reason", terminal.reason)
        is TerminalOutcomeV1.BlockedExternal -> JSONObject().put("kind", terminal.kind).put("reason", terminal.reason)
        is TerminalOutcomeV1.BlockedPolicy -> JSONObject().put("kind", terminal.kind).put("reason", terminal.reason)
        is TerminalOutcomeV1.BudgetExhausted -> JSONObject().put("kind", terminal.kind).put("dimension", terminal.dimension.value)
        is TerminalOutcomeV1.Cancelled -> JSONObject().put("kind", terminal.kind).put("reason", terminal.reason)
        is TerminalOutcomeV1.InfraFailure -> JSONObject().put("kind", terminal.kind).put("reason", terminal.reason)
        is TerminalOutcomeV1.AgentFailure -> JSONObject().put("kind", terminal.kind).put("reason", terminal.reason)
    }

    fun parseWorkflowRun(json: JSONObject): WorkflowRunV1 {
        checkAllowedKeys(
            json,
            setOf("version", "run_id", "task", "authority", "stages", "workers", "terminal", "revision", "evidence"),
            "WorkflowRunV1",
        )
        return WorkflowRunV1(
            version = json.getString("version"),
            run_id = json.getString("run_id"),
            task = parseTask(json.getJSONObject("task")),
            authority = parseAuthority(json.getJSONObject("authority")),
            stages = json.getJSONArray("stages").let { arr -> List(arr.length()) { parseStage(arr.getJSONObject(it)) } },
            workers = json.getJSONArray("workers").let { arr -> List(arr.length()) { parseWorker(arr.getJSONObject(it)) } },
            terminal = if (json.has("terminal") && !json.isNull("terminal")) parseTerminal(json.getJSONObject("terminal")) else null,
            revision = if (json.has("revision") && !json.isNull("revision")) parseRevision(json.getJSONObject("revision")) else null,
            evidence = json.getJSONArray("evidence").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
        )
    }

    fun serializeWorkflowRun(run: WorkflowRunV1): JSONObject = JSONObject()
        .put("version", run.version)
        .put("run_id", run.run_id)
        .put("task", serializeTask(run.task))
        .put("authority", serializeAuthority(run.authority))
        .put("stages", JSONArray().apply { run.stages.forEach { put(serializeStage(it)) } })
        .put("workers", JSONArray().apply { run.workers.forEach { put(serializeWorker(it)) } })
        .apply {
            if (run.terminal != null) put("terminal", serializeTerminal(run.terminal))
            if (run.revision != null) put("revision", serializeRevision(run.revision))
        }
        .put("evidence", JSONArray().apply { run.evidence.forEach { put(serializeEvidence(it)) } })

    fun parseExport(json: JSONObject): PortableExportV1 {
        checkAllowedKeys(json, setOf("version", "run", "redaction_profile", "exported_at"), "PortableExportV1")
        val exportedAt = json.getString("exported_at")
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.parse(exportedAt)
        return PortableExportV1(
            version = json.getString("version"),
            run = parseWorkflowRun(json.getJSONObject("run")),
            redaction_profile = RedactionProfile.from(json.getString("redaction_profile")),
            exported_at = exportedAt,
        )
    }

    fun serializeExport(export: PortableExportV1): JSONObject = JSONObject()
        .put("version", export.version)
        .put("run", serializeWorkflowRun(export.run))
        .put("redaction_profile", export.redaction_profile.value)
        .put("exported_at", export.exported_at)
}

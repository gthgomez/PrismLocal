package com.prismai.llmhost.work.portable

import org.json.JSONArray
import org.json.JSONObject

object PortableWorkflowJson {

    fun parseRevision(json: JSONObject): RevisionRefV1 = RevisionRefV1(
        kind = json.optString("kind", "workspace-revision"),
        composite_tree_hash = json.getString("composite_tree_hash"),
        source = json.getString("source"),
    )

    fun serializeRevision(ref: RevisionRefV1): JSONObject = JSONObject()
        .put("kind", ref.kind)
        .put("composite_tree_hash", ref.composite_tree_hash)
        .put("source", ref.source)

    fun parseEvidence(json: JSONObject): EvidenceRefV1 = EvidenceRefV1(
        id = json.getString("id"),
        kind = json.getString("kind"),
        sha256 = json.getString("sha256"),
        native_path = if (json.has("native_path") && !json.isNull("native_path")) json.getString("native_path") else null,
    )

    fun serializeEvidence(ref: EvidenceRefV1): JSONObject = JSONObject()
        .put("id", ref.id)
        .put("kind", ref.kind)
        .put("sha256", ref.sha256)
        .apply {
            if (ref.native_path != null) put("native_path", ref.native_path)
        }

    fun parseAuthority(json: JSONObject): AuthorityRefV1 = AuthorityRefV1(
        native_kind = json.getString("native_kind"),
        native_id = json.getString("native_id"),
        sha256 = json.getString("sha256"),
    )

    fun serializeAuthority(auth: AuthorityRefV1): JSONObject = JSONObject()
        .put("native_kind", auth.native_kind)
        .put("native_id", auth.native_id)
        .put("sha256", auth.sha256)

    fun parseVerifierIdentity(json: JSONObject): VerifierIdentityV1 = VerifierIdentityV1(
        command = json.getString("command"),
        command_sha256 = json.getString("command_sha256"),
        scope = json.getJSONArray("scope").let { arr -> List(arr.length()) { arr.getString(it) } },
        independent = json.getBoolean("independent"),
        clean_room = json.getBoolean("clean_room"),
    )

    fun serializeVerifierIdentity(identity: VerifierIdentityV1): JSONObject = JSONObject()
        .put("command", identity.command)
        .put("command_sha256", identity.command_sha256)
        .put("scope", JSONArray(identity.scope))
        .put("independent", identity.independent)
        .put("clean_room", identity.clean_room)

    fun parseVerifierReceipt(json: JSONObject): VerifierReceiptV1 = VerifierReceiptV1(
        id = json.getString("id"),
        status = json.getString("status"),
        verifier = parseVerifierIdentity(json.getJSONObject("verifier")),
        bound_revision = parseRevision(json.getJSONObject("bound_revision")),
        authority = parseAuthority(json.getJSONObject("authority")),
        evidence = json.getJSONArray("evidence").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
        exit_code = if (json.has("exit_code") && !json.isNull("exit_code")) json.getInt("exit_code") else null,
        produced_at = json.getString("produced_at"),
    )

    fun serializeVerifierReceipt(receipt: VerifierReceiptV1): JSONObject = JSONObject()
        .put("id", receipt.id)
        .put("status", receipt.status)
        .put("verifier", serializeVerifierIdentity(receipt.verifier))
        .put("bound_revision", serializeRevision(receipt.bound_revision))
        .put("authority", serializeAuthority(receipt.authority))
        .put("evidence", JSONArray().apply { receipt.evidence.forEach { put(serializeEvidence(it)) } })
        .apply {
            if (receipt.exit_code != null) put("exit_code", receipt.exit_code)
        }
        .put("produced_at", receipt.produced_at)

    fun parseTask(json: JSONObject): TaskRefV1 = TaskRefV1(
        task_id = json.getString("task_id"),
        goal = json.getString("goal"),
        acceptance_criteria = json.getJSONArray("acceptance_criteria").let { arr -> List(arr.length()) { arr.getString(it) } },
        mutation_policy = json.getString("mutation_policy"),
        required_verifiers = json.getJSONArray("required_verifiers").let { arr -> List(arr.length()) { arr.getString(it) } },
    )

    fun serializeTask(task: TaskRefV1): JSONObject = JSONObject()
        .put("task_id", task.task_id)
        .put("goal", task.goal)
        .put("acceptance_criteria", JSONArray(task.acceptance_criteria))
        .put("mutation_policy", task.mutation_policy)
        .put("required_verifiers", JSONArray(task.required_verifiers))

    fun parseStageInput(json: JSONObject): StageInputV1 = when (val kind = json.getString("kind")) {
        "orient" -> StageInputV1.Orient(parseTask(json.getJSONObject("task")))
        "review" -> StageInputV1.Review(
            parseTask(json.getJSONObject("task")),
            json.getJSONArray("target_refs").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } }
        )
        "attack" -> StageInputV1.Attack(
            parseTask(json.getJSONObject("task")),
            json.getJSONArray("target_refs").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } }
        )
        "integrate" -> StageInputV1.Integrate(
            parseTask(json.getJSONObject("task")),
            json.getJSONArray("stage_refs").let { arr -> List(arr.length()) { arr.getString(it) } }
        )
        else -> throw IllegalArgumentException("Unknown stage input kind: $kind")
    }

    fun serializeStageInput(input: StageInputV1): JSONObject = when (input) {
        is StageInputV1.Orient -> JSONObject().put("kind", "orient").put("task", serializeTask(input.task))
        is StageInputV1.Review -> JSONObject().put("kind", "review").put("task", serializeTask(input.task))
            .put("target_refs", JSONArray().apply { input.target_refs.forEach { put(serializeEvidence(it)) } })
        is StageInputV1.Attack -> JSONObject().put("kind", "attack").put("task", serializeTask(input.task))
            .put("target_refs", JSONArray().apply { input.target_refs.forEach { put(serializeEvidence(it)) } })
        is StageInputV1.Integrate -> JSONObject().put("kind", "integrate").put("task", serializeTask(input.task))
            .put("stage_refs", JSONArray(input.stage_refs))
    }

    fun parseStageResult(json: JSONObject): StageResultV1 = when (val kind = json.getString("kind")) {
        "orient" -> StageResultV1.Orient(
            json.getJSONArray("findings").let { arr -> List(arr.length()) { arr.getString(it) } }
        )
        "review" -> StageResultV1.Review(
            json.getJSONArray("findings").let { arr -> List(arr.length()) { arr.getString(it) } },
            json.getJSONArray("required_changes").let { arr -> List(arr.length()) { arr.getString(it) } }
        )
        "attack" -> StageResultV1.Attack(
            json.getJSONArray("findings").let { arr -> List(arr.length()) { arr.getString(it) } },
            json.getJSONArray("reproductions").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } }
        )
        "integrate" -> StageResultV1.Integrate(
            json.getJSONArray("verifier_receipts").let { arr -> List(arr.length()) { parseVerifierReceipt(arr.getJSONObject(it)) } },
            json.getJSONArray("changed_refs").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } }
        )
        else -> throw IllegalArgumentException("Unknown stage result kind: $kind")
    }

    fun serializeStageResult(result: StageResultV1): JSONObject = when (result) {
        is StageResultV1.Orient -> JSONObject().put("kind", "orient").put("findings", JSONArray(result.findings))
        is StageResultV1.Review -> JSONObject().put("kind", "review").put("findings", JSONArray(result.findings))
            .put("required_changes", JSONArray(result.required_changes))
        is StageResultV1.Attack -> JSONObject().put("kind", "attack").put("findings", JSONArray(result.findings))
            .put("reproductions", JSONArray().apply { result.reproductions.forEach { put(serializeEvidence(it)) } })
        is StageResultV1.Integrate -> JSONObject().put("kind", "integrate")
            .put("verifier_receipts", JSONArray().apply { result.verifier_receipts.forEach { put(serializeVerifierReceipt(it)) } })
            .put("changed_refs", JSONArray().apply { result.changed_refs.forEach { put(serializeEvidence(it)) } })
    }

    fun parseStageRecord(json: JSONObject): StageRecordV1 = StageRecordV1(
        stage_id = json.getString("stage_id"),
        kind = json.getString("kind"),
        status = json.getString("status"),
        input = parseStageInput(json.getJSONObject("input")),
        result = if (json.has("result") && !json.isNull("result")) parseStageResult(json.getJSONObject("result")) else null,
        workers = json.getJSONArray("workers").let { arr -> List(arr.length()) { arr.getString(it) } },
        evidence = json.getJSONArray("evidence").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
    )

    fun serializeStageRecord(stage: StageRecordV1): JSONObject = JSONObject()
        .put("stage_id", stage.stage_id)
        .put("kind", stage.kind)
        .put("status", stage.status)
        .put("input", serializeStageInput(stage.input))
        .apply {
            if (stage.result != null) put("result", serializeStageResult(stage.result))
        }
        .put("workers", JSONArray(stage.workers))
        .put("evidence", JSONArray().apply { stage.evidence.forEach { put(serializeEvidence(it)) } })

    fun parseWorkerRun(json: JSONObject): WorkerRunV1 = WorkerRunV1(
        worker_id = json.getString("worker_id"),
        stage_id = json.getString("stage_id"),
        role = json.getString("role"),
        status = json.getString("status"),
        provider = if (json.has("provider") && !json.isNull("provider")) json.getString("provider") else null,
        native_authority = parseAuthority(json.getJSONObject("native_authority")),
        evidence = json.getJSONArray("evidence").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
    )

    fun serializeWorkerRun(worker: WorkerRunV1): JSONObject = JSONObject()
        .put("worker_id", worker.worker_id)
        .put("stage_id", worker.stage_id)
        .put("role", worker.role)
        .put("status", worker.status)
        .apply {
            if (worker.provider != null) put("provider", worker.provider)
        }
        .put("native_authority", serializeAuthority(worker.native_authority))
        .put("evidence", JSONArray().apply { worker.evidence.forEach { put(serializeEvidence(it)) } })

    fun parseTerminalOutcome(json: JSONObject): TerminalOutcomeV1 = when (val kind = json.getString("kind")) {
        "completed_verified" -> TerminalOutcomeV1.CompletedVerified(
            receipts = json.getJSONArray("receipts").let { arr -> List(arr.length()) { arr.getString(it) } },
            revision = parseRevision(json.getJSONObject("revision")),
        )
        "completed_unverified" -> TerminalOutcomeV1.CompletedUnverified(json.getString("reason"))
        "blocked_external" -> TerminalOutcomeV1.BlockedExternal(json.getString("reason"))
        "blocked_policy" -> TerminalOutcomeV1.BlockedPolicy(json.getString("reason"))
        "budget_exhausted" -> TerminalOutcomeV1.BudgetExhausted(json.getString("dimension"))
        "cancelled" -> TerminalOutcomeV1.Cancelled(json.getString("reason"))
        "infra_failure" -> TerminalOutcomeV1.InfraFailure(json.getString("reason"))
        "agent_failure" -> TerminalOutcomeV1.AgentFailure(json.getString("reason"))
        else -> throw IllegalArgumentException("Unknown terminal outcome kind: $kind")
    }

    fun serializeTerminalOutcome(terminal: TerminalOutcomeV1): JSONObject = when (terminal) {
        is TerminalOutcomeV1.CompletedVerified -> JSONObject()
            .put("kind", "completed_verified")
            .put("receipts", JSONArray(terminal.receipts))
            .put("revision", serializeRevision(terminal.revision))
        is TerminalOutcomeV1.CompletedUnverified -> JSONObject().put("kind", "completed_unverified").put("reason", terminal.reason)
        is TerminalOutcomeV1.BlockedExternal -> JSONObject().put("kind", "blocked_external").put("reason", terminal.reason)
        is TerminalOutcomeV1.BlockedPolicy -> JSONObject().put("kind", "blocked_policy").put("reason", terminal.reason)
        is TerminalOutcomeV1.BudgetExhausted -> JSONObject().put("kind", "budget_exhausted").put("dimension", terminal.dimension)
        is TerminalOutcomeV1.Cancelled -> JSONObject().put("kind", "cancelled").put("reason", terminal.reason)
        is TerminalOutcomeV1.InfraFailure -> JSONObject().put("kind", "infra_failure").put("reason", terminal.reason)
        is TerminalOutcomeV1.AgentFailure -> JSONObject().put("kind", "agent_failure").put("reason", terminal.reason)
    }

    fun parseWorkflowRun(json: JSONObject): WorkflowRunV1 = WorkflowRunV1(
        version = json.getString("version"),
        run_id = json.getString("run_id"),
        task = parseTask(json.getJSONObject("task")),
        authority = parseAuthority(json.getJSONObject("authority")),
        stages = json.getJSONArray("stages").let { arr -> List(arr.length()) { parseStageRecord(arr.getJSONObject(it)) } },
        workers = json.getJSONArray("workers").let { arr -> List(arr.length()) { parseWorkerRun(arr.getJSONObject(it)) } },
        terminal = if (json.has("terminal") && !json.isNull("terminal")) parseTerminalOutcome(json.getJSONObject("terminal")) else null,
        revision = if (json.has("revision") && !json.isNull("revision")) parseRevision(json.getJSONObject("revision")) else null,
        evidence = json.getJSONArray("evidence").let { arr -> List(arr.length()) { parseEvidence(arr.getJSONObject(it)) } },
    )

    fun serializeWorkflowRun(run: WorkflowRunV1): JSONObject = JSONObject()
        .put("version", run.version)
        .put("run_id", run.run_id)
        .put("task", serializeTask(run.task))
        .put("authority", serializeAuthority(run.authority))
        .put("stages", JSONArray().apply { run.stages.forEach { put(serializeStageRecord(it)) } })
        .put("workers", JSONArray().apply { run.workers.forEach { put(serializeWorkerRun(it)) } })
        .apply {
            if (run.terminal != null) put("terminal", serializeTerminalOutcome(run.terminal))
            if (run.revision != null) put("revision", serializeRevision(run.revision))
        }
        .put("evidence", JSONArray().apply { run.evidence.forEach { put(serializeEvidence(it)) } })

    fun parseExport(json: JSONObject): PortableExportV1 = PortableExportV1(
        version = json.getString("version"),
        run = parseWorkflowRun(json.getJSONObject("run")),
        redaction_profile = json.optString("redaction_profile", "public"),
        exported_at = json.getString("exported_at"),
    )

    fun serializeExport(export: PortableExportV1): JSONObject = JSONObject()
        .put("version", export.version)
        .put("run", serializeWorkflowRun(export.run))
        .put("redaction_profile", export.redaction_profile)
        .put("exported_at", export.exported_at)
}

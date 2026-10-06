# Repository audit and improvement plan prompt

Audit the current PrismLocal checkout and return an evidence-backed, staged
improvement plan. This is an audit and planning task only. Do not implement fixes,
edit documentation, add tests, refactor code, or execute the proposed plan.

## Protect the checkout

- Read applicable `AGENTS.md` and its referenced repository instructions first.
- Preserve all existing staged, unstaged, and untracked work. Do not reset,
  restore, clean, stash, checkout another branch, pull, rebase, update submodules,
  stage, commit, or push. Do not assume local changes are disposable or yours.
- Inspect local diffs as part of the current product state. Distinguish committed
  behavior from local changes and attribute findings accordingly.
- Return the report in your response. Do not create repository report files.
- Run only bounded verification that cannot overwrite existing work. Inspect
  scripts before running them; avoid formatters, snapshot updates, deployment,
  model downloads, paid generation, and actions that mutate user data. If a check
  cannot run safely, record the limitation and propose its exact future command.
- Never expose credentials, signing material, private model data, or user content
  in the report. Summarize sensitive evidence without copying its values.

## 1. Establish what is actually being audited

Record the repository root, branch, full HEAD SHA, worktree status, local refs,
submodule state where applicable, and the date. Start with read-only commands:

```sh
git rev-parse --show-toplevel
git branch --show-current
git rev-parse HEAD
git status --short --branch --untracked-files=all
git log -12 --oneline --decorate
git diff --stat
git diff --cached --stat
git ls-files --others --exclude-standard
```

Read relevant staged and unstaged diffs and inspect relevant untracked source
files without executing them. Do not equate a clean tracked diff with an empty
worktree. Record enough initial state to detect unexpected changes at the end.

Treat the following handoff as claims to verify, not established facts:

- `main` was reportedly fast-forwarded to `ee4c219`, with previous tracked and
  untracked work restored afterward.
- Recent changes reportedly add or harden reference-conditioned asset generation,
  sprite runtime export, path and output checks, and bounded procedural DSL
  rendering.
- Restored local `README.md`, `docs/STATUS.md`, and `docs/ROADMAP.md` reportedly
  describe the pre-pull state and may be stale.

Check whether `ee4c219` exists locally and is an ancestor of HEAD before selecting
a comparison range. Never treat all history preceding it as the recent change.
If the branch, commit, paths, or product capabilities differ from this handoff,
report the mismatch prominently. Do not change branches or fetch to force a match.
Audit the available checkout, marking the requested but unavailable scope as
unverified and stating what checkout or evidence is needed to assess it.

The checkout where this prompt was authored was clean on
`feat/abliterated-model-catalog` at `a90ef54`; `ee4c219` was not available locally.
It contained root-level `STATUS.md` and `ROADMAP.md`. This is historical context,
not a substitute for your own current git inspection.

## 2. Map the architecture and reconcile documentation

Identify the product's actual entry points, user flows, major modules, dependency
boundaries, storage formats, external integrations, build variants, and test/CI
layout. Trace representative flows from user input through validation, execution,
persistence, and exported output. Cite concrete paths and symbols.

For an Android checkout, include the UI/service boundary, agent tool dispatch,
Kotlin/JNI/native inference lifecycle, model storage/downloads, cancellation,
and resource limits where supported by source. Discover the actual architecture;
do not invent asset subsystems merely because the handoff mentions them.

Compare README, status, roadmap, architecture notes, and build instructions with
the current code, configuration, local diffs, and test evidence. Locate root-level
equivalents when the handoff's `docs/` paths are absent. Produce a claim-by-claim
reconciliation table: document/path, claim, source/test evidence, verdict
(supported, stale, contradicted, or unverified), and proposed correction.
Do not update the documents. A roadmap entry or old green build is not proof of
current implementation or current runtime correctness.

## 3. Examine recent asset and sprite work

Locate the relevant commits, source, local changes, and tests before assessing
these areas. For each area, explain implemented behavior, evidence, failure
handling, remaining uncertainty, and user impact. Mark absent areas explicitly.

- **Reference-conditioned generation:** trace reference selection, file decoding,
  validation, preprocessing, provider/model request construction, and output
  handling. Verify the reference reaches the execution path; distinguish actual
  conditioning from metadata-only input. Inspect unsupported inputs, oversized
  images, cancellation, deterministic configuration, and fallback behavior.
- **Sprite runtime export:** trace frame ordering, dimensions, durations, atlas
  coordinates, pivots/origins, transparency, animation metadata, and consumer
  compatibility. Assess malformed or empty frames, naming collisions, repeated
  export, partial failures, and whether tests read the output as a consumer would.
- **Path and output checks:** trace user- and model-controlled names, references,
  temporary files, archives, and destinations. Examine absolute paths, traversal,
  symlinks, canonical containment, extension/content mismatch, overwrite policy,
  atomic publication, cleanup, and output size limits at actual trust boundaries.
- **Bounded procedural DSL rendering:** identify the grammar, parser, allowed
  operations, validation, and renderer. Check that input is data rather than
  arbitrary executable code. Assess limits on input length, nesting, operation
  count, dimensions, allocation, execution time, and total output. Determine
  whether limits are enforced before expensive work and cover the whole request.
  Inspect invalid numbers, overflow, malformed input, cancellation, and errors.

For each trust boundary, identify input origin, validation location, execution
privilege, accessible resources, and failure behavior. Distinguish reachable
defects from hypothetical concerns; give a bounded reproducer or precise code
trace for credible findings without touching real user data.

## 4. Assess quality with proportionate evidence

Review correctness, security, maintainability, performance/resource use, user
experience, observability, and reproducibility across the actual repository.
Prioritize the recent work and its integration points over unrelated refactors.

Map important behaviors to existing tests and identify missing failure cases.
Discover commands from current build files and CI, reconciling conflicting docs.
Run the narrowest safe existing checks that materially support findings. Record
the exact command, environment requirements, exit result, and relevant output.
Separate passed checks, failed checks, inspected-but-not-run tests, and blocked
verification. Do not install dependencies or claim device/provider/runtime
coverage from unit tests alone. Do not write new tests during this audit.

## 5. Rank findings and produce a staged plan

Every finding must include:

- A stable ID, concise title, affected user behavior, and concrete evidence
  (file and line/symbol, relevant diff/commit, or fresh check output).
- Severity, likelihood/reachability, confidence, and whether it is a confirmed
  defect, missing validation, documentation drift, or an improvement opportunity.
- Proposed change, affected modules, dependencies, regression risk, and a
  verifiable acceptance criterion. Keep observed facts separate from inference.
- Impact and effort estimates (small/medium/large), with rationale and uncertainty.

Rank by demonstrated user impact and risk reduction relative to effort. Avoid
generic best-practice lists, speculative rewrites, duplicate findings, arbitrary
finding quotas, and recommendations for safeguards already implemented.

Return these sections:

1. **Audit baseline and limitations:** exact checkout/diff scope, handoff mismatch,
   unavailable evidence, and verification performed.
2. **Architecture map:** concise module/data-flow map with source references.
3. **Documentation reconciliation:** the claim/evidence/verdict/correction table.
4. **Recent capability assessment:** coverage and trust-boundary findings for all
   four requested areas, including explicit unavailable/not-applicable results.
5. **Prioritized findings:** an impact/effort table with IDs and supporting detail.
6. **Staged improvement plan:** immediate containment or correctness work, then
   reliability and coverage, then maintainability or experience improvements.
   Each stage must reference finding IDs, ordered tasks, affected files/modules,
   dependencies, acceptance checks, completion criteria, and rollback approach
   where relevant. Separate quick wins from larger changes; avoid invented dates.
7. **Open questions and deferred work:** only decisions or evidence that could
   materially alter the plan, plus explicit reasons for deferral.

End by comparing worktree status with the initial baseline, reporting any check
artifacts or unexpected changes without deleting them. State that no improvements
were implemented. Stop after delivering the audit and plan; execution requires a
separate user instruction.

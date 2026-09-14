# Start here — PrismLocal lead-agent dispatch

Read `PrismLocal_Inference_Roadmap.md` for the coherent 33-section report, or its HTML rendering. Read `program.json` for machine-readable dependencies and scopes. Individual agent packets are under `missions/`; the root packet is `missions/PIR-INT.md`.

## Evidence and authority

Audited repository: `gthgomez/PrismLocal`, default branch `main`, commit `49ed799a3e633c5192c2c03317d1d50ffdc57c4c`. Actual llama.cpp gitlink: `bbeb89d76c41bc250f16e4a6fefcc9b530d6e3f3`.

This package does not contain an implemented runtime, a compiled APK or physical performance measurements. The helper unit tests validate the package, not Prism. No coding subagents were executed by the research session. Model identifiers/hashes must be resolved from real artifacts before running a benchmark; never invent them.

A separate coding agent may use these packets to implement the program. The root owns all integration and may reject any experiment. Do not automatically change public repository state, publish APKs, spend money, download restricted artifacts, send private prompts or delete models/worktrees.

## Lead-agent instruction

Act as the PrismLocal integration authority. Inspect AGENTS.md and the live default branch. Compare it with the audited commit rather than blindly applying old findings. Preserve a pinned historical worktree and record any intervening diff. Read Sections 1–6, 21–28 and 30–33, then dispatch mission PIR-00. After its accepted checkpoint, PIR-01, PIR-07 and PIR-08 can run as leaf missions while one owner handles the serial core lane PIR-02→03→04→05. Do not let multiple agents edit Engine.cpp or JNI simultaneously. Resolve dependency checkpoints and scopes explicitly in each dispatch. A completed experiment is a reviewed disposition, not an automatic merge. Use the corrected-neutral baseline before ranking optimizations. Optional experiments must not block a safe single-runtime improvement.

Every worker must return code/report, full base/result SHAs, tests and exit codes, benchmark artifacts when relevant, support scope and a RETAIN/MODIFY/REJECT/BLOCKED disposition. A missing toolchain/model/device is BLOCKED, not PASS. Review both raw evidence and scripts; a machine-readable declaration alone is not independent proof.

## Package checks

Python 3.10+; standard library only for the supplied helpers.

```sh
python3 tools/validate_program.py
python3 -m unittest discover -s tools -p 'test_tools.py' -v
python3 tools/probe_source_invariants.py
```

The final command demonstrates simplified counterexamples, **not execution against Prism**. PIR-00 must create the proposed portable native harness; later missions must create and run the actual regression tests.

When integrating this package into the repository, place `program.json`, `missions/`, `schemas/`, `tools/` and the report under `research/inference/` so packet-relative helper commands resolve. Keep raw private benchmark data and model files out of Git.

## Creating an isolated worktree

First create an approved worktree parent directory outside the repository. Substitute a full immutable base SHA; do not use `main` as `--base`. The helper performs a dry run unless `--apply` is supplied.

```sh
python3 tools/create_worktree.py PIR-00 \
  --repo /path/to/PrismLocal \
  --base 49ed799a3e633c5192c2c03317d1d50ffdc57c4c \
  --root /path/to/existing-worktree-parent
```

For later missions supply `--ledger /path/to/completed.json`, modeled on `schemas/completed.example.json`. Dependency implementation commits must be ancestors of the chosen base. Rejected or blocked experiments may complete an information dependency after review; they never become winning runtime settings. PIR-23 additionally requires an alternate bake-off RETAIN with its material-gain gate passed. The helper does not automatically initialize submodules, run builds or dispatch agents.

## Existing repository gates versus new tests

The audited repository's `scripts/verify.ps1` runs flavor-qualified unit tests and release-like builds:

```sh
./gradlew --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
./gradlew --no-daemon :app:assembleDevBenchmark :app:assemblePlayRelease
```

Windows: `.\gradlew.bat` or `pwsh -File scripts/verify.ps1`. The connected task is `:app:connectedDevDebugAndroidTest`. The inspected emulator wrapper is `bash scripts/emulator/run-tests.sh both`. Verify its current prerequisites. X86 emulators do not establish ARM/GPU/NPU speed or thermal behavior. New host C++ targets and named test classes in the packets must be implemented before their command can pass.

## Benchmark data

`schemas/benchmark-run.schema.json` describes the target JSONL contract. `tools/check_run.py` checks the core identity, nullable measurement, plan, token-accounting and eligibility rules; it is not a full statistical analyzer or cryptographic evidence verifier. A JSON Schema implementation may additionally validate all schema fields. It does not prove that declared hashes match real files: the integrator must hash/review the linked artifacts.

```sh
python3 tools/check_run.py path/to/results/*.jsonl
python3 tools/check_run.py --require-eligible path/to/confirmed-results/*.jsonl
```

All measured attempts, including errors/cancellation/fallbacks, should be preserved. Pre-run setup failures with unresolved model/build identities belong in the separate mission/attempt ledger, not fabricated benchmark rows. Unknown counters are null with availability reasons; do not use zero for unavailable GPU memory or energy.

The `fixtures/synthetic-validator-run.jsonl` file is synthetic test data, explicitly ineligible for comparison. It is not an example of measured phone performance.
